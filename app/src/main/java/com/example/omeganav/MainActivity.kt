package com.example.omeganav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.omeganav.ui.theme.OmegaNavTheme
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private lateinit var sensors: SensorsManager
    private lateinit var deadReckoner: DeadReckoner

    private var accel by mutableStateOf(TripleSample(0f, 0f, 0f))
    private var magnet by mutableStateOf(TripleSample(0f, 0f, 0f))
    private var gyro by mutableStateOf(TripleSample(0f, 0f, 0f))
    private var linear by mutableStateOf(TripleSample(0f, 0f, 0f))
    private var orient by mutableStateOf(OrientationSample(0f, 0f, 0f))
    private var stepRaw by mutableStateOf(0)

    private var pose by mutableStateOf<Pose?>(null)

    private val pathPoints = mutableStateListOf<Pair<Double, Double>>()
    private val maxPathPoints = 800
    private val minStepDistanceForPath = 0.03

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensors = SensorsManager(
            context = this,
            onAccel = { accel = it },
            onGyro = { gyro = it },
            onLinearAccel = { linear = it },
            onOrient = { orient = it },
            onSteps = { stepRaw = it },
            onMagnet = { magnet = it }
        )

        deadReckoner = DeadReckoner(
            context = this,
            listener = { newPose ->
                pose = newPose

                val last = pathPoints.lastOrNull()
                val px = newPose.posX
                val py = newPose.posY

                if (last == null) {
                    pathPoints.add(px to py)
                } else {
                    val dx = px - last.first
                    val dy = py - last.second
                    val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (dist > minStepDistanceForPath) {
                        pathPoints.add(px to py)
                        if (pathPoints.size > maxPathPoints) pathPoints.removeAt(0)
                    }
                }
            }
        )

        setContent {
            OmegaNavTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    FullSensorScreen(
                        accel = accel,
                        gyro = gyro,
                        linear = linear,
                        stepsRaw = stepRaw,
                        pose = pose,
                        pathPoints = pathPoints,
                        onResetPath = { pathPoints.clear() },
                        modifier = Modifier
                            .padding(padding)
                            .padding(16.dp)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensors.start()
        deadReckoner.start()
    }

    override fun onPause() {
        super.onPause()
        sensors.stop()
        deadReckoner.stop()
    }
}

@Composable
fun FullSensorScreen(
    accel: TripleSample,
    gyro: TripleSample,
    linear: TripleSample,
    stepsRaw: Int,
    pose: Pose?,
    pathPoints: List<Pair<Double, Double>>,
    onResetPath: () -> Unit,
    modifier: Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
    ) {

        Text("📱 ALL SENSOR READINGS", style = MaterialTheme.typography.titleLarge)
        Text("")

        Text("Dead-Reckoning Path:")
        DRPathView(
            points = pathPoints,
            modifier = Modifier
                .fillMaxSize()
                .height(260.dp)
                .padding(vertical = 8.dp)
        )

        Button(onClick = onResetPath) { Text("Reset Path") }
        Text("")

        //  ACCEL 
        Text("ACCELEROMETER (m/s²):")
        Text("X = %.2f".format(accel.x))
        Text("Y = %.2f".format(accel.y))
        Text("Z = %.2f".format(accel.z))
        Text("")

        //  GYRO 
        Text("GYROSCOPE (rad/s):")
        Text("X = %.2f".format(gyro.x))
        Text("Y = %.2f".format(gyro.y))
        Text("Z = %.2f".format(gyro.z))
        Text("")

        //  LINEAR ACCEL 
        Text("LINEAR ACCEL (no gravity):")
        Text("X = %.2f".format(linear.x))
        Text("Y = %.2f".format(linear.y))
        Text("Z = %.2f".format(linear.z))
        Text("")

        //  DR OUTPUT
        Text("DEAD RECKONING:", style = MaterialTheme.typography.titleMedium)

        if (pose == null) {
            Text("Waiting for DR data…")
            return
        }

        Text("")
        Text("Position (meters):")
        Text("X = %.2f".format(pose.posX))
        Text("Y = %.2f".format(pose.posY))
        Text("Z = %.2f".format(pose.posZ))

        Text("")
        Text("Velocity (m/s):")
        Text("Vx = %.2f".format(pose.velX))
        Text("Vy = %.2f".format(pose.velY))
        Text("Vz = %.2f".format(pose.velZ))

    }
}

@Composable
fun DRPathView(
    points: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier
) {
    if (points.size < 2) {
        Text("Move to see path…")
        return
    }

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val side = minOf(canvasWidth, canvasHeight) * 0.9f

        val offsetX = (canvasWidth - side) / 2f
        val offsetY = (canvasHeight - side) / 2f

        // Compute world center
        val xs = points.map { it.first }
        val ys = points.map { it.second }

        val minX = xs.minOrNull() ?: 0.0
        val maxX = xs.maxOrNull() ?: 0.0
        val minY = ys.minOrNull() ?: 0.0
        val maxY = ys.maxOrNull() ?: 0.0

        val cx = (minX + maxX) / 2.0
        val cy = (minY + maxY) / 2.0

        val rangeX = max(abs(maxX - cx), abs(minX - cx))
        val rangeY = max(abs(maxY - cy), abs(minY - cy))
        var halfRange = max(rangeX, rangeY)
        if (halfRange < 0.5) halfRange = 0.5

        fun worldToScreen(x: Double, y: Double): Offset {
            val nx = ((x - cx) / halfRange).coerceIn(-1.5, 1.5)
            val ny = ((y - cy) / halfRange).coerceIn(-1.5, 1.5)

            val sx = offsetX + (side / 2f) * (nx.toFloat() + 1f)
            val sy = offsetY + (side / 2f) * (1f - ny.toFloat())

            return Offset(sx, sy)
        }
 
        val center = worldToScreen(cx, cy)

        // Horizontal axis
        drawLine(
            color = Color.LightGray,
            start = Offset(offsetX, center.y),
            end = Offset(offsetX + side, center.y),
            strokeWidth = 2f
        )

        // Vertical axis
        drawLine(
            color = Color.LightGray,
            start = Offset(center.x, offsetY),
            end = Offset(center.x, offsetY + side),
            strokeWidth = 2f
        )

        //  Build and draw path 
        val path = Path()
        points.forEachIndexed { index, p ->
            val pt = worldToScreen(p.first, p.second)
            if (index == 0) path.moveTo(pt.x, pt.y)
            else path.lineTo(pt.x, pt.y)
        }

        drawPath(path, Color.Red, style = Stroke(width = 4f))

        //  Draw current position as dot 
        val last = points.last()
        val lastPt = worldToScreen(last.first, last.second)
        drawCircle(Color.Red, 10f, lastPt)
    }
}
