package com.example.omeganav

import android.hardware.*
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var pathView: PathView

    private var stepLength = 0.5f // meters per step (rough)
    private var azimuth = 0f
    private var lastAccel = FloatArray(3)
    private var lastMag = FloatArray(3)
    private var stepCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        pathView = findViewById(R.id.pathView)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager

        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> lastAccel = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> lastMag = event.values.clone()
        }

        val rotationMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        if (SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMag)) {
            SensorManager.getOrientation(rotationMatrix, orientation)
            azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        }

        // Fake step detection (for demo purposes)
        if (++stepCount % 20 == 0) {
            val dx = (stepLength * sin(Math.toRadians(azimuth.toDouble()))).toFloat() * 50
            val dy = (-stepLength * cos(Math.toRadians(azimuth.toDouble()))).toFloat() * 50
            pathView.addDisplacement(dx, dy)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
    }
}
