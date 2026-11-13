package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Auto-capture ring — the smooth alternate to the old vertical
 * countdown bar (which lagged: the analysis drove it ~6 times
 * a second, so it moved in jerky steps and made the whole
 * scanner feel laggy).
 *
 * The ring wraps the big capture button and fills like a
 * camera timer while the page is held steady; full means
 * capture. It is driven by a ValueAnimator on the UI thread
 * (60 fps, vsync), so it moves smoothly — the analysis only
 * starts and stops it, never drives a frame.
 *
 * Safe to call setProgress from any thread; the update posts
 * to the UI thread itself.
 */
class CaptureRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var progress: Float = -1f

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 70
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }

    /** Progress 0..1 (the ring fills); < 0 hides the ring. */
    fun setProgress(p: Float) {
        post {
            progress = p
            visibility = if (p < 0f) View.GONE else View.VISIBLE
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progress < 0f) return
        val pad = 6f * density
        val rect = RectF(pad, pad, width - pad, height - pad)
        canvas.drawOval(rect, trackPaint)
        val sweep = progress.coerceIn(0f, 1f) * 360f
        if (sweep > 0f) {
            canvas.drawArc(rect, -90f, sweep, false, ringPaint)
        }
    }
}
