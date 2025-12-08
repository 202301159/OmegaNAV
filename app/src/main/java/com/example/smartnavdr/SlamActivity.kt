package com.example.smartnavdr

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Camera
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.math.*

class SlamActivity : AppCompatActivity(), SurfaceHolder.Callback, Camera.PreviewCallback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var slamOverlay: SlamOverlayView
    private lateinit var slamPathView: PathView
    private lateinit var textPose: TextView
    private lateinit var textStats: TextView
    private lateinit var cameraContainer: View
    private lateinit var infoContainer: LinearLayout
    private lateinit var buttonStop: Button

    private var camera: Camera? = null
    private var previewWidth = 0
    private var previewHeight = 0

    private var isRunning = true

    // VO state
    private var lastGray: ByteArray? = null
    private var lastFeatures: List<Feature>? = null

    private var poseX = 0.0
    private var poseY = 0.0

    private var lastFrameTimeMs = 0L
    private var fps = 0
    private var featureCount = 0

    data class Feature(val x: Int, val y: Int, val score: Int)
    data class Match(val prev: Feature, val curr: Feature)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force landscape for SLAM mode
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        setContentView(R.layout.activity_slam)

        cameraContainer = findViewById(R.id.cameraContainer)
        surfaceView = findViewById(R.id.cameraPreview)
        slamOverlay = findViewById(R.id.slamOverlay)
        infoContainer = findViewById(R.id.slamInfoContainer)
        slamPathView = findViewById(R.id.slamPathView)
        textPose = findViewById(R.id.textSlamPose)
        textStats = findViewById(R.id.textSlamStats)
        buttonStop = findViewById(R.id.buttonStopSlam)

        // Initially: camera visible, map/stats hidden
        infoContainer.visibility = View.GONE
        cameraContainer.visibility = View.VISIBLE
        isRunning = true

        surfaceView.holder.addCallback(this)

        buttonStop.setOnClickListener {
            stopSlamAndShowMap()
        }

        // Camera permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    1001
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Camera is opened in surfaceCreated
    }

    override fun onPause() {
        super.onPause()
        releaseCamera()
    }

    // ---------- SurfaceHolder.Callback ----------

    override fun surfaceCreated(holder: SurfaceHolder) {
        openCamera(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        camera?.stopPreview()
        try {
            camera?.setPreviewDisplay(holder)
            camera?.startPreview()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        releaseCamera()
    }

    // ---------- Camera control ----------

    private fun openCamera(holder: SurfaceHolder) {
        try {
            camera = Camera.open()
            camera?.let { cam ->
                val params = cam.parameters
                val size = params.supportedPreviewSizes
                    .minByOrNull { it.width * it.height } ?: params.previewSize
                previewWidth = size.width
                previewHeight = size.height

                params.setPreviewSize(previewWidth, previewHeight)
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                cam.parameters = params

                cam.setPreviewDisplay(holder)
                cam.setPreviewCallback(this)
                cam.startPreview()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseCamera() {
        camera?.setPreviewCallback(null)
        camera?.stopPreview()
        camera?.release()
        camera = null
    }

    private fun stopSlamAndShowMap() {
        if (!isRunning) return
        isRunning = false

        // Stop camera and hide camera view
        releaseCamera()
        cameraContainer.visibility = View.GONE

        // Stop drawing landmarks
        slamOverlay.clearPoints()

        // Show map and stats container
        infoContainer.visibility = View.VISIBLE
    }

    // ---------- Preview frames / VO ----------

    override fun onPreviewFrame(data: ByteArray?, camera: Camera?) {
        if (!isRunning || data == null || camera == null) return

        val now = System.currentTimeMillis()
        val dtMs = if (lastFrameTimeMs == 0L) 0L else now - lastFrameTimeMs
        lastFrameTimeMs = now
        if (dtMs > 0) {
            fps = (1000 / dtMs).toInt().coerceAtMost(60)
        }

        // First width*height bytes are Y (luma) in NV21 format
        val gray = data.copyOf(previewWidth * previewHeight)

        val prevGray = lastGray
        val prevFeatures = lastFeatures

        var dx = 0.0
        var dy = 0.0
        var features: List<Feature> = emptyList()

        if (prevGray != null && prevFeatures != null && prevFeatures.isNotEmpty()) {
            val matches = trackFeatures(prevGray, gray, previewWidth, previewHeight, prevFeatures)
            featureCount = matches.size

            if (matches.isNotEmpty()) {
                val displacements = matches.map { (p, c) ->
                    Pair((c.x - p.x).toDouble(), (c.y - p.y).toDouble())
                }

                val xs = displacements.map { it.first }.sorted()
                val ys = displacements.map { it.second }.sorted()
                dx = xs[xs.size / 2]
                dy = ys[ys.size / 2]

                val pixelToMeter = 0.002
                dx *= -pixelToMeter
                dy *= pixelToMeter
            }
        }

        // Update pose
        poseX += dx
        poseY += dy

        // Detect new features for next frame
        features = detectFeatures(gray, previewWidth, previewHeight)
        lastGray = gray
        lastFeatures = features

        // Update overlay points (green dots)
        val overlayPoints = features.map { it.x to it.y }
        slamOverlay.updatePoints(overlayPoints, previewWidth, previewHeight)

        // Update UI & map path
        runOnUiThread {
            textPose.text = String.format("Pose: (%.2f, %.2f)", poseX, poseY)
            textStats.text = "FPS: $fps | Features: $featureCount"
            slamPathView.addPoint(poseX, poseY)
        }
    }

    // ---------- Feature detection (FAST-ish) ----------

    private fun detectFeatures(gray: ByteArray, width: Int, height: Int): List<Feature> {
        val features = mutableListOf<Feature>()
        val threshold = 20
        val gridSize = 8
        val circle = arrayOf(
            intArrayOf(-3, 0),
            intArrayOf(-3, 1),
            intArrayOf(-2, 2),
            intArrayOf(-1, 3),
            intArrayOf(0, 3),
            intArrayOf(1, 3),
            intArrayOf(2, 2),
            intArrayOf(3, 1),
            intArrayOf(3, 0),
            intArrayOf(3, -1),
            intArrayOf(2, -2),
            intArrayOf(1, -3),
            intArrayOf(0, -3),
            intArrayOf(-1, -3),
            intArrayOf(-2, -2),
            intArrayOf(-3, -1)
        )

        val gridBest = HashMap<Pair<Int, Int>, Feature>()

        var y = 10
        while (y < height - 10) {
            var x = 10
            while (x < width - 10) {
                val centerIdx = y * width + x
                val centerInt = gray[centerIdx].toInt() and 0xFF

                var brighter = 0
                var darker = 0

                for (pt in circle) {
                    val dx = pt[0]
                    val dy = pt[1]
                    val idx = (y + dy) * width + (x + dx)
                    val v = gray[idx].toInt() and 0xFF
                    if (v > centerInt + threshold) brighter++
                    if (v < centerInt - threshold) darker++
                }

                if (brighter >= 12 || darker >= 12) {
                    val score = max(brighter, darker)
                    val gx = x / gridSize
                    val gy = y / gridSize
                    val key = Pair(gx, gy)
                    val existing = gridBest[key]
                    if (existing == null || existing.score < score) {
                        gridBest[key] = Feature(x, y, score)
                    }
                }

                x += 3
            }
            y += 3
        }

        features.addAll(gridBest.values)
        return features.take(1000)
    }

    // ---------- Feature tracking (patch SSD) ----------

    private fun trackFeatures(
        prevGray: ByteArray,
        currGray: ByteArray,
        width: Int,
        height: Int,
        prevFeatures: List<Feature>
    ): List<Match> {
        val matches = mutableListOf<Match>()
        val searchRadius = 12
        val patchRadius = 3
        val maxSSD = 2000.0

        val step = max(1, prevFeatures.size / 300)

        for (i in prevFeatures.indices step step) {
            val f = prevFeatures[i]
            val px = f.x
            val py = f.y

            if (px < patchRadius + 1 || px >= width - patchRadius - 1 ||
                py < patchRadius + 1 || py >= height - patchRadius - 1
            ) continue

            val prevPatch = extractPatch(prevGray, width, px, py, patchRadius)
            var bestSSD = Double.MAX_VALUE
            var bestX = px
            var bestY = py

            var dy = -searchRadius
            while (dy <= searchRadius) {
                var dx = -searchRadius
                while (dx <= searchRadius) {
                    val cx = px + dx
                    val cy = py + dy
                    if (cx < patchRadius + 1 || cx >= width - patchRadius - 1 ||
                        cy < patchRadius + 1 || cy >= height - patchRadius - 1
                    ) {
                        dx += 3
                        continue
                    }

                    val currPatch = extractPatch(currGray, width, cx, cy, patchRadius)
                    var ssd = 0.0
                    for (k in prevPatch.indices) {
                        val diff = (prevPatch[k] - currPatch[k]).toDouble()
                        ssd += diff * diff
                        if (ssd > maxSSD) break
                    }
                    if (ssd < bestSSD) {
                        bestSSD = ssd
                        bestX = cx
                        bestY = cy
                    }

                    dx += 3
                }
                dy += 3
            }

            if (bestSSD < maxSSD) {
                matches.add(Match(f, Feature(bestX, bestY, 0)))
            }
        }

        return matches
    }

    private fun extractPatch(
        img: ByteArray,
        width: Int,
        cx: Int,
        cy: Int,
        r: Int
    ): ByteArray {
        val size = (2 * r + 1)
        val patch = ByteArray(size * size)
        var idx = 0
        var y = cy - r
        while (y <= cy + r) {
            var x = cx - r
            while (x <= cx + r) {
                val i = y * width + x
                patch[idx++] = img[i]
                x++
            }
            y++
        }
        return patch
    }
}
