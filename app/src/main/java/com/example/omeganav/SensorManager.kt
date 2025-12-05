package com.example.omeganav

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

// Generic 3-axis sample (for accel / gyro / linear / magnet)
data class TripleSample(
    val x: Float,
    val y: Float,
    val z: Float
)

// Orientation sample (already in degrees for easier reading)
data class OrientationSample(
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float
)

class SensorsManager(
    context: Context,
    private val onAccel: (TripleSample) -> Unit,
    private val onGyro: (TripleSample) -> Unit,
    private val onLinearAccel: (TripleSample) -> Unit,
    private val onOrient: (OrientationSample) -> Unit,
    private val onSteps: (Int) -> Unit,
    private val onMagnet: (TripleSample) -> Unit
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val rotationVector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val stepDetector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private var steps = 0

    // For orientation from rotation vector
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3) // [azimuth (yaw), pitch, roll] in radians

    fun start() {
        val rate = SensorManager.SENSOR_DELAY_GAME

        accel?.let { sm.registerListener(this, it, rate) }
        gyro?.let { sm.registerListener(this, it, rate) }
        linear?.let { sm.registerListener(this, it, rate) }
        rotationVector?.let { sm.registerListener(this, it, rate) }
        magnet?.let { sm.registerListener(this, it, rate) }
        stepDetector?.let { sm.registerListener(this, it, rate) }

        steps = 0
        onSteps(steps)
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                onAccel(
                    TripleSample(
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2]
                    )
                )
            }

            Sensor.TYPE_GYROSCOPE -> {
                onGyro(
                    TripleSample(
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2]
                    )
                )
            }

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                onLinearAccel(
                    TripleSample(
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2]
                    )
                )
            }

            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)

                val yawDeg =
                    Math.toDegrees(orientation[0].toDouble()).toFloat()  // azimuth
                val pitchDeg =
                    Math.toDegrees(orientation[1].toDouble()).toFloat()
                val rollDeg =
                    Math.toDegrees(orientation[2].toDouble()).toFloat()

                onOrient(
                    OrientationSample(
                        yawDeg = yawDeg,
                        pitchDeg = pitchDeg,
                        rollDeg = rollDeg
                    )
                )
            }

            Sensor.TYPE_MAGNETIC_FIELD -> {
                onMagnet(
                    TripleSample(
                        x = event.values[0],
                        y = event.values[1],
                        z = event.values[2]
                    )
                )
            }

            Sensor.TYPE_STEP_DETECTOR -> {
                val delta = event.values[0].toInt().coerceAtLeast(1)
                steps += delta
                onSteps(steps)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }
}
