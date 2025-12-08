package com.example.smartnavdr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class SlamOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.GREEN
    }

    private var sourceWidth = 1
    private var sourceHeight = 1

    private val points = mutableListOf<Pair<Int, Int>>()

    /**
     * Update landmark points in source (camera frame) coordinates.
     */
    fun updatePoints(newPoints: List<Pair<Int, Int>>, srcWidth: Int, srcHeight: Int) {
        points.clear()
        points.addAll(newPoints)
        sourceWidth = if (srcWidth <= 0) 1 else srcWidth
        sourceHeight = if (srcHeight <= 0) 1 else srcHeight
        invalidate()
    }

    fun clearPoints() {
        points.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        val sx = width.toFloat() / sourceWidth.toFloat()
        val sy = height.toFloat() / sourceHeight.toFloat()

        val radius = 5f

        for ((x, y) in points) {
            val vx = x * sx
            val vy = y * sy
            canvas.drawCircle(vx, vy, radius, paint)
        }
    }
}
