package com.example.omeganav

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class PathView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val path = Path()
    private val paint = Paint().apply {
        color = Color.RED
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private var lastX = 0f
    private var lastY = 0f
    private var initialized = false

    fun addDisplacement(dx: Float, dy: Float) {
        if (!initialized) {
            lastX = width / 2f
            lastY = height / 2f
            path.moveTo(lastX, lastY)
            initialized = true
        }
        lastX += dx
        lastY += dy
        path.lineTo(lastX, lastY)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(path, paint)
    }
}
