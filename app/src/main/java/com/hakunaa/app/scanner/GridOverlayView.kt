package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Rule-of-thirds grid over the camera preview — the framing
 * aid professional camera apps have. Two vertical and two
 * horizontal lines, drawn inside the preview's 4:3 content
 * rect (the fitCenter image area, not the black bars around
 * it), so the user can line the page up straight. Toggled
 * from the Grid button; off by default.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 140
        strokeWidth = 1.5f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        // The 4:3 preview, fitCenter inside this view.
        val contentW = minOf(w, h * 4f / 3f)
        val contentH = contentW * 3f / 4f
        val left = (w - contentW) / 2f
        val top = (h - contentH) / 2f
        val right = left + contentW
        val bottom = top + contentH
        // Thirds.
        val x1 = left + contentW / 3f
        val x2 = left + contentW * 2f / 3f
        val y1 = top + contentH / 3f
        val y2 = top + contentH * 2f / 3f
        canvas.drawLine(x1, top, x1, bottom, paint)
        canvas.drawLine(x2, top, x2, bottom, paint)
        canvas.drawLine(left, y1, right, y1, paint)
        canvas.drawLine(left, y2, right, y2, paint)
    }
}
