package com.deliverytracker.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var strokeWidthPx = 10f * resources.displayMetrics.density
    private var progressRatio = 0.5f // 0.0 to 1.0
    private var progressColor = 0xFFE23744.toInt()
    private var trackColor = 0xFF27272A.toInt()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val boundsRect = RectF()

    fun setProgress(progress: Float, color: Int) {
        this.progressRatio = progress.coerceIn(0.0f, 1.0f)
        this.progressColor = color
        invalidate()
    }

    fun setStrokeWidthDp(dp: Float) {
        this.strokeWidthPx = dp * resources.displayMetrics.density
        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx
        updateBounds()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateBounds()
    }

    private fun updateBounds() {
        if (width == 0 || height == 0) return  // View not yet laid out; onSizeChanged will call us again
        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx

        // Inset by exactly half the stroke width so the outer edge touches the screen edge (0 to w)
        val halfStroke = strokeWidthPx / 2f
        boundsRect.set(
            halfStroke,
            halfStroke,
            width.toFloat() - halfStroke,
            height.toFloat() - halfStroke
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // 1. Draw 360 degree background track
        trackPaint.color = trackColor
        canvas.drawArc(boundsRect, 0f, 360f, false, trackPaint)

        // 2. Draw active progress arc starting from top (270 degrees) if progress > 0
        if (progressRatio > 0.001f) {
            progressPaint.color = progressColor
            val sweepAngle = 360f * progressRatio
            canvas.drawArc(boundsRect, 270f, sweepAngle, false, progressPaint)
        }
    }
}
