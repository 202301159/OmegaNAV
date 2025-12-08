package com.example.smartnavdr

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

class PathView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Store world-space positions (in metres)
    private val points = mutableListOf<Pair<Double, Double>>()

    // Path we actually draw (in screen space)
    private val path = Path()

    // Path (trajectory) paint – RED line
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.RED
    }

    // Reference axes paint (light grey)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.LTGRAY
        alpha = 120
    }

    // Current position marker (thicker point)
    private val currentPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }

    // View center in pixels
    private var centerX = 0f
    private var centerY = 0f

    // World-space bounds (min & max in metres)
    private var minWorldX = 0.0
    private var maxWorldX = 0.0
    private var minWorldY = 0.0
    private var maxWorldY = 0.0

    // Auto scale computed from bounds (pixels per metre)
    private var baseScale = 50f

    // User-controlled scale from pinch zoom
    private var userScaleFactor = 1f
    private val minUserScale = 0.5f
    private val maxUserScale = 3f

    // Scale gesture detector for pinch-zoom
    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                userScaleFactor *= detector.scaleFactor
                userScaleFactor = userScaleFactor.coerceIn(minUserScale, maxUserScale)
                rebuildPath()
                invalidate()
                return true
            }
        }
    )

    fun resetPath() {
        points.clear()
        path.reset()
        // Reset scales and bounds
        minWorldX = 0.0
        maxWorldX = 0.0
        minWorldY = 0.0
        maxWorldY = 0.0
        baseScale = 50f
        userScaleFactor = 1f
        invalidate()
    }

    /**
     * Add a new DR point in world coordinates (metres).
     */
    fun addPoint(posX: Double, posY: Double) {
        points.add(posX to posY)

        // Update bounds
        if (points.size == 1) {
            minWorldX = posX
            maxWorldX = posX
            minWorldY = posY
            maxWorldY = posY
        } else {
            minWorldX = min(minWorldX, posX)
            maxWorldX = max(maxWorldX, posX)
            minWorldY = min(minWorldY, posY)
            maxWorldY = max(maxWorldY, posY)
        }

        // Recompute base scale from bounds (auto zoom-out)
        updateBaseScaleFromBounds()

        // Auto zoom-out gently if latest point is off-screen even after base scale + user scale
        if (isLastPointOffScreen()) {
            // Instead of snapping back to 1x, gradually zoom out a bit
            userScaleFactor = (userScaleFactor * 0.8f).coerceAtLeast(1f)
        }

        rebuildPath()
        postInvalidateOnAnimation()
    }

    private fun updateBaseScaleFromBounds() {
        if (width == 0 || height == 0 || points.isEmpty()) {
            return
        }

        val worldWidth = max(1e-3, maxWorldX - minWorldX) // avoid 0
        val worldHeight = max(1e-3, maxWorldY - minWorldY)

        // Use 80% of view size so we have margins
        val usableWidth = width * 0.8f
        val usableHeight = height * 0.8f

        val scaleX = (usableWidth / worldWidth).toFloat()
        val scaleY = (usableHeight / worldHeight).toFloat()

        // Choose smaller scale so both width and height fit
        baseScale = min(scaleX, scaleY)

        // Avoid crazy tiny or huge scales
        baseScale = baseScale.coerceIn(10f, 500f)
    }

    private fun isLastPointOffScreen(): Boolean {
        if (points.isEmpty() || width == 0 || height == 0) return false

        val (lastX, lastY) = points.last()
        val (sx, sy) = worldToScreen(lastX, lastY)

        val padding = 16f // pixels margin
        return sx < padding || sx > width - padding || sy < padding || sy > height - padding
    }

    private fun worldToScreen(wx: Double, wy: Double): Pair<Float, Float> {
        if (width == 0 || height == 0) {
            return 0f to 0f
        }

        // Center of world bounds
        val worldCenterX = (minWorldX + maxWorldX) / 2.0
        val worldCenterY = (minWorldY + maxWorldY) / 2.0

        val finalScale = baseScale * userScaleFactor

        val dx = (wx - worldCenterX) * finalScale
        val dy = (wy - worldCenterY) * finalScale

        val sx = centerX + dx.toFloat()
        val sy = centerY - dy.toFloat()

        return sx to sy
    }

    private fun rebuildPath() {
        path.reset()

        if (points.isEmpty() || width == 0 || height == 0) return

        points.forEachIndexed { index, (wx, wy) ->
            val (sx, sy) = worldToScreen(wx, wy)
            if (index == 0) {
                path.moveTo(sx, sy)
            } else {
                path.lineTo(sx, sy)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        updateBaseScaleFromBounds()
        rebuildPath()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw reference axes
        canvas.drawLine(0f, centerY, width.toFloat(), centerY, axisPaint)
        canvas.drawLine(centerX, 0f, centerX, height.toFloat(), axisPaint)

        // Draw the path
        canvas.drawPath(path, pathPaint)

        // Draw current position marker as a thicker dot
        if (points.isNotEmpty()) {
            val (lastX, lastY) = points.last()
            val (sx, sy) = worldToScreen(lastX, lastY)
            val radius = 12f // marker radius in pixels
            canvas.drawCircle(sx, sy, radius, currentPointPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Let the ScaleGestureDetector inspect all touch events
        scaleGestureDetector.onTouchEvent(event)
        // We consume touch events so pinch zoom works
        return true
    }
}
