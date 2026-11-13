package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Auto-capture countdown bar — the "charging bar" on the side of
 * the scanner. While a document is held steady it fills from the
 * bottom, like a battery charging; when it is full the photo is
 * captured. It is the visible half of the auto-capture hold:
 * the user sees exactly how long to keep holding still.
 *
 * - progress < 0: hidden (no document, or auto capture off).
 * - progress 0..1: shown, filled to that fraction.
 *
 * Safe to call from any thread (analysis runs on a background
 * thread); the update posts to the UI thread itself.
 */
class CountdownBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var progress = -1f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 70
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.FILL
    }

    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

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
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = w / 2f
        // Track.
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, trackPaint)
        // Fill, rising from the bottom like a charge.
        val fillH = h * progress.coerceIn(0f, 1f)
        if (fillH > 0f) {
            canvas.drawRoundRect(0f, h - fillH, w, h, radius, radius, fillPaint)
            // Bright cap line at the fill's top edge.
            val capH = 3f * resources.displayMetrics.density
            canvas.drawRect(0f, h - fillH, w, h - fillH + capH, capPaint)
        }
    }
}
