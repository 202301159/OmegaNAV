package com.example.smartnavdr

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

interface DeadReckoningListener {
    fun onStateUpdated(posX: Double, posY: Double, velX: Double, velY: Double)
}

/**
 * Dead Reckoning manager that mirrors the logic of the DRModule.jsx:
 *
 *  - Uses 2D linear acceleration (x, y) in device frame.
 *  - Uses only heading (compass yaw) to rotate acceleration into world frame.
 *  - Estimates and applies bias when stationary.
 *  - Applies a deadzone to kill noise.
 *  - Integrates acceleration -> velocity -> position with damping.
 */
class SensorFusionManager(
    context: Context,
    private val listener: DeadReckoningListener
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Sensors
    private val linearAccelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    // Orientation / heading
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private var headingDeg = 0.0
    private var hasHeading = false
    private val headingAlpha = 0.2 // low-pass filter on heading

    // DR state
    private var posX = 0.0    // metres
    private var posY = 0.0
    private var velX = 0.0    // m/s
    private var velY = 0.0

    // Bias for accel in device frame (x, y)
    private var biasAx = 0.0
    private var biasAy = 0.0

    // Stationary detection
    private var stationaryCount = 0
    private val stationaryThreshold = 0.12   // m/s²
    private val stationarySamplesRequired = 6

    // Noise/deadzone threshold
    private val deadzone = 0.05

    // Velocity damping (to prevent drift explosion)
    private val damping = 1.0

    // Time tracking (nanos from sensor event)
    private var lastTimestampNanos: Long? = null

    fun start() {
        rotationVectorSensor?.also {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        linearAccelSensor?.also {
            sensorManager.registerListener(
                this,
                it,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun reset() {
        posX = 0.0
        posY = 0.0
        velX = 0.0
        velY = 0.0
        biasAx = 0.0
        biasAy = 0.0
        stationaryCount = 0
        lastTimestampNanos = null
        headingDeg = 0.0
        hasHeading = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_ACCELEROMETER -> handleLinearAcceleration(event)
        }
    }

    private fun handleRotationVector(event: SensorEvent) {
        // Get orientation (azimuth / yaw)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)

        var azimuthRad = orientation[0].toDouble() // -π..π
        var azimuthDeg = Math.toDegrees(azimuthRad)

        if (azimuthDeg < 0.0) {
            azimuthDeg += 360.0
        }

        headingDeg = if (!hasHeading) {
            azimuthDeg
        } else {
            // Low-pass filter to make heading smoother
            headingAlpha * azimuthDeg + (1.0 - headingAlpha) * headingDeg
        }
        hasHeading = true
    }

    private fun handleLinearAcceleration(event: SensorEvent) {
        val axRaw = event.values[0].toDouble()
        val ayRaw = event.values[1].toDouble()

        val nowNanos = event.timestamp
        val prev = lastTimestampNanos
        lastTimestampNanos = nowNanos

        if (prev == null) {
            // First sample, only initialise timestamp
            return
        }

        val dt = (nowNanos - prev) / 1_000_000_000.0
        if (dt <= 0.0 || dt < 0.001 || dt > 1.0) {
            // Ignore impossible or ridiculous dt
            return
        }

        // --- Stationary detection (2D magnitude) ---
        val accMag2D = hypot(axRaw, ayRaw)
        if (accMag2D < stationaryThreshold) {
            stationaryCount += 1
        } else {
            stationaryCount = 0
        }

        if (stationaryCount >= stationarySamplesRequired) {
            // Update bias when stationary
            biasAx = biasAx * 0.8 + axRaw * 0.2
            biasAy = biasAy * 0.8 + ayRaw * 0.2

            // Hard zero velocity (ZUPT)
            velX = 0.0
            velY = 0.0

            // Output state (position stays same)
            listener.onStateUpdated(posX, posY, velX, velY)
            return
        }

        // --- Bias correction ---
        var ax = axRaw - biasAx
        var ay = ayRaw - biasAy

        // --- Deadzone to kill tiny noise ---
        if (abs(ax) < deadzone) ax = 0.0
        if (abs(ay) < deadzone) ay = 0.0

        // --- Rotate acceleration to world frame using heading ---
        val headingRad = Math.toRadians(headingDeg)
        val cosH = cos(headingRad)
        val sinH = sin(headingRad)

        val worldAx = ax * cosH + ay * sinH
        val worldAy = -ax * sinH + ay * cosH

        // --- Integrate acceleration -> velocity, with damping ---
        val velXNew = (velX + worldAx * dt) * exp(-damping * dt)
        val velYNew = (velY + worldAy * dt) * exp(-damping * dt)

        // --- Integrate velocity -> position ---
        val posXNew = posX + velXNew * dt
        val posYNew = posY + velYNew * dt

        velX = velXNew
        velY = velYNew
        posX = posXNew
        posY = posYNew

        listener.onStateUpdated(posX, posY, velX, velY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op for now
    }
}
