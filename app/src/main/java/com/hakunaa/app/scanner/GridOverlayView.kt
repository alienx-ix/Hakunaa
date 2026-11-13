package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Rule-of-thirds grid over the camera preview — the framing
 * aid professional camera apps have. Two thin vertical and
 * two thin horizontal lines, drawn inside the preview's 4:3
 * content rect (the fitCenter image area, not the black bars
 * around it), so the user can line the page up straight.
 * Toggled from the Grid button; off by default.
 *
 * The lines must be THIN and subtle — a framing aid, not a
 * table. The earlier version drew each line as a thick dark
 * bar (3.5dp black) with a white stripe, plus a border
 * around the whole area; on a white page that read as a
 * heavy black grid, which the user called the worst. Now
 * each line is a hairline: a faint dark under-stroke (just
 * enough to define it on white paper) with a thin white
 * line on top, and no border at all — the way CamScanner's
 * grid reads.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 80
        strokeWidth = 1.5f * density
    }

    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 200
        strokeWidth = 1f * density
    }

    private fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        canvas.drawLine(x1, y1, x2, y2, darkPaint)
        canvas.drawLine(x1, y1, x2, y2, lightPaint)
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
        // Thirds only — no border. A border around the framing
        // area made the grid read as a table over the page.
        val x1 = left + contentW / 3f
        val x2 = left + contentW * 2f / 3f
        val y1 = top + contentH / 3f
        val y2 = top + contentH * 2f / 3f
        line(canvas, x1, top, x1, bottom)
        line(canvas, x2, top, x2, bottom)
        line(canvas, left, y1, right, y1)
        line(canvas, left, y2, right, y2)
    }
}
