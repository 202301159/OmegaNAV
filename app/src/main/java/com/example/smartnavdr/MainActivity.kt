package com.example.smartnavdr

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), DeadReckoningListener {

    private lateinit var pathView: PathView
    private lateinit var textPosition: TextView
    private lateinit var textVelocity: TextView
    private lateinit var buttonStart: Button
    private lateinit var buttonReset: Button
    private lateinit var buttonOpenSlam: Button

    private lateinit var sensorFusionManager: SensorFusionManager
    private var isTracking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pathView = findViewById(R.id.pathView)
        textPosition = findViewById(R.id.textPosition)
        textVelocity = findViewById(R.id.textVelocity)
        buttonStart = findViewById(R.id.buttonStart)
        buttonReset = findViewById(R.id.buttonReset)
        buttonOpenSlam = findViewById(R.id.buttonOpenSlam)

        sensorFusionManager = SensorFusionManager(this, this)

        buttonStart.setOnClickListener {
            isTracking = !isTracking
            if (isTracking) {
                sensorFusionManager.start()
                buttonStart.text = "Stop DR"
            } else {
                sensorFusionManager.stop()
                buttonStart.text = "Start DR"
            }
        }

        buttonReset.setOnClickListener {
            sensorFusionManager.reset()
            pathView.resetPath()
            textPosition.text = "Position: (0.00, 0.00) m"
            textVelocity.text = "Velocity: (0.00, 0.00) m/s"
        }

        buttonOpenSlam.setOnClickListener {
            val intent = Intent(this, SlamActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        if (isTracking) {
            sensorFusionManager.start()
        }
    }

    override fun onPause() {
        super.onPause()
        sensorFusionManager.stop()
    }

    override fun onStateUpdated(posX: Double, posY: Double, velX: Double, velY: Double) {
        runOnUiThread {
            pathView.addPoint(posX, posY)
            textPosition.text = String.format("Position: (%.2f, %.2f) m", posX, posY)
            textVelocity.text = String.format("Velocity: (%.2f, %.2f) m/s", velX, velY)
        }
    }
}
