package com.example.omeganav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

data class Pose(
    val timestampNanos: Long,
    val posX: Double,
    val posY: Double,
    val posZ: Double,
    val velX: Double,
    val velY: Double,
    val velZ: Double,
    val rollDeg: Double,
    val pitchDeg: Double,
    val yawDeg: Double,
    val stepCount: Int
)

class DeadReckoner(
    context: Context,
    private val listener: (Pose) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    // Orientation sensors
    private val rotationVector =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val accelRaw =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnet =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    // Linear acceleration (no gravity)
    private val linearAccel =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    // Gyroscope – used for still/motion detection
    private val gyro =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    // Step detector
    private val stepDetector =
        sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    // Orientation state
    private val rotationMatrix = FloatArray(9) { if (it % 4 == 0) 1f else 0f } // identity
    private val orientation = FloatArray(3) // [azimuth (yaw), pitch, roll] in radians

    private val accelVals = FloatArray(3)
    private val magnetVals = FloatArray(3)
    private var hasAccel = false
    private var hasMagnet = false

    private val gyroVals = FloatArray(3)
    private var hasGyro = false

    private val useRotationVector: Boolean = rotationVector != null

    // Integration state
    private var lastLinearTs: Long = 0L
    private var posX = 0.0
    private var posY = 0.0
    private var posZ = 0.0
    private var velX = 0.0
    private var velY = 0.0
    private var velZ = 0.0

    // Step-based DR
    private var stepCount = 0
    private val stepLengthMeters = 0.75   // tune if you want

    // dt clamp
    private val maxDtSeconds = 0.1

    // Stationary detection
    private var stillTime = 0.0
    private val accelStillThreshold = 0.12   // m/s^2 (linear)
    private val gyroStillThreshold = 0.05    // rad/s
    private val stillTimeRequired = 0.5      // seconds

    fun start() {
        reset()

        // Orientation: prefer rotation vector
        if (rotationVector != null) {
            sensorManager.registerListener(
                this,
                rotationVector,
                SensorManager.SENSOR_DELAY_GAME
            )
        } else {
            if (accelRaw != null) {
                sensorManager.registerListener(
                    this,
                    accelRaw,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
            if (magnet != null) {
                sensorManager.registerListener(
                    this,
                    magnet,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
        }

        // Linear acceleration
        if (linearAccel != null) {
            sensorManager.registerListener(
                this,
                linearAccel,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        // Gyroscope
        if (gyro != null) {
            sensorManager.registerListener(
                this,
                gyro,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

        // Step detector
        if (stepDetector != null) {
            sensorManager.registerListener(
                this,
                stepDetector,
                SensorManager.SENSOR_DELAY_GAME
            )
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    fun reset() {
        lastLinearTs = 0L
        posX = 0.0; posY = 0.0; posZ = 0.0
        velX = 0.0; velY = 0.0; velZ = 0.0
        stepCount = 0
        stillTime = 0.0
        hasAccel = false
        hasMagnet = false
        hasGyro = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> handleRotationVector(event)
            Sensor.TYPE_ACCELEROMETER   -> handleAccelRaw(event)
            Sensor.TYPE_MAGNETIC_FIELD  -> handleMagnet(event)
            Sensor.TYPE_LINEAR_ACCELERATION -> handleLinearAccel(event)
            Sensor.TYPE_GYROSCOPE       -> handleGyro(event)
            Sensor.TYPE_STEP_DETECTOR   -> handleStepDetector(event)
        }
    }

    private fun handleRotationVector(ev: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, ev.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        emitPose(ev.timestamp)
    }

    private fun handleAccelRaw(ev: SensorEvent) {
        accelVals[0] = ev.values[0]
        accelVals[1] = ev.values[1]
        accelVals[2] = ev.values[2]
        hasAccel = true
        updateOrientationFromAccelMag()
        emitPose(ev.timestamp)
    }

    private fun handleMagnet(ev: SensorEvent) {
        magnetVals[0] = ev.values[0]
        magnetVals[1] = ev.values[1]
        magnetVals[2] = ev.values[2]
        hasMagnet = true
        updateOrientationFromAccelMag()
        emitPose(ev.timestamp)
    }

    private fun handleGyro(ev: SensorEvent) {
        gyroVals[0] = ev.values[0]
        gyroVals[1] = ev.values[1]
        gyroVals[2] = ev.values[2]
        hasGyro = true
    }

    private fun updateOrientationFromAccelMag() {
        if (useRotationVector) return
        if (!hasAccel || !hasMagnet) return

        val R = FloatArray(9)
        val I = FloatArray(9)
        val ok = SensorManager.getRotationMatrix(R, I, accelVals, magnetVals)
        if (!ok) return

        System.arraycopy(R, 0, rotationMatrix, 0, 9)
        SensorManager.getOrientation(rotationMatrix, orientation)
    }

    private fun handleLinearAccel(ev: SensorEvent) {
        val ts = ev.timestamp

        if (lastLinearTs == 0L) {
            lastLinearTs = ts
            return
        }

        var dt = (ts - lastLinearTs) * 1e-9
        lastLinearTs = ts

        if (dt <= 0.0) return
        if (dt > maxDtSeconds) dt = maxDtSeconds

        // Body-frame linear accel
        val axB = ev.values[0].toDouble()
        val ayB = ev.values[1].toDouble()
        val azB = ev.values[2].toDouble()

        //  Stationary detection 
        val accelNorm = sqrt(axB * axB + ayB * ayB + azB * azB)
        val gyroNorm =
            if (hasGyro) {
                val gx = gyroVals[0].toDouble()
                val gy = gyroVals[1].toDouble()
                val gz = gyroVals[2].toDouble()
                sqrt(gx * gx + gy * gy + gz * gz)
            } else {
                0.0
            }

        if (accelNorm < accelStillThreshold && gyroNorm < gyroStillThreshold) {
            stillTime += dt
        } else {
            stillTime = 0.0
        }

        val isStationary = stillTime > stillTimeRequired

        if (isStationary) {
            // Phone is considered still → stop velocity & freeze position
            velX = 0.0
            velY = 0.0
            velZ = 0.0
            emitPose(ts)
            return
        }

        //  Not stationary → integrate motion 

        // Rotate body accel to world frame: a_world = R * a_body
        val r = rotationMatrix
        val axW = (r[0] * axB + r[1] * ayB + r[2] * azB)
        val ayW = (r[3] * axB + r[4] * ayB + r[5] * azB)
        val azW = (r[6] * axB + r[7] * ayB + r[8] * azB)

        // Integrate velocity
        velX += axW * dt
        velY += ayW * dt
        velZ += azW * dt

        // Slight damping to limit drift
        val damping = 0.98
        velX *= damping
        velY *= damping
        velZ *= damping

        // Integrate position
        posX += velX * dt
        posY += velY * dt
        posZ += velZ * dt

        emitPose(ts)
    }

    private fun handleStepDetector(ev: SensorEvent) {
        val steps = ev.values[0].toInt().coerceAtLeast(1)
        val yaw = orientation[0].toDouble() // radians

        repeat(steps) {
            stepCount++
            val dx = stepLengthMeters * kotlin.math.sin(yaw)
            val dy = stepLengthMeters * kotlin.math.cos(yaw)
            posX += dx
            posY += dy
        }

        emitPose(ev.timestamp)
    }

    private fun emitPose(tsNanos: Long) {
        val azimuth = orientation[0].toDouble() // yaw
        val pitch = orientation[1].toDouble()
        val roll = orientation[2].toDouble()

        val yawDeg = Math.toDegrees(azimuth)
        val pitchDeg = Math.toDegrees(pitch)
        val rollDeg = Math.toDegrees(roll)

        listener(
            Pose(
                timestampNanos = tsNanos,
                posX = posX,
                posY = posY,
                posZ = posZ,
                velX = velX,
                velY = velY,
                velZ = velZ,
                rollDeg = rollDeg,
                pitchDeg = pitchDeg,
                yawDeg = yawDeg,
                stepCount = stepCount
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }
}
