package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * On-screen brightness slider — appears right beside the focus
 * ring when the user taps the preview, the way a real camera
 * app does it. Drag up for brighter, down for darker; tap the
 * slider to jump straight to a level. No separate settings
 * screen — the adjustment lives on the camera screen itself.
 *
 * - value 0 = darkest, 1 = brightest (mapped by the activity
 *   onto the camera's exposure-compensation range).
 * - setValue() only shows a level (used when the slider first
 *   appears); onValueChanged fires only for the user's drags.
 * - A small sun at the top says which way is brighter.
 */
class ExposureSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var value: Float = 0.5f
        private set

    var onValueChanged: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 90
        style = Paint.Style.FILL
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.FILL
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val sunRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }

    fun setValue(v: Float) {
        value = v.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val top = trackTop()
                val bottom = trackBottom()
                val v = 1f - ((event.y - top) / (bottom - top)).coerceIn(0f, 1f)
                if (v != value) {
                    value = v
                    invalidate()
                    onValueChanged?.invoke(value)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // Sun glyph at the top, track below it. The padding at both
    // ends is wider than the thumb so the thumb is never clipped.
    private fun trackTop(): Float = 28f * density + 13f * density

    private fun trackBottom(): Float = height - 13f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f
        val trackW = 6f * density
        val top = trackTop()
        val bottom = trackBottom()
        // Sun at the top: brighter is up.
        val sunR = 6f * density
        val sunCy = 14f * density
        canvas.drawCircle(cx, sunCy, sunR, sunPaint)
        for (i in 0 until 8) {
            val a = (i * PI / 4).toFloat()
            val r1 = sunR + 3f * density
            val r2 = sunR + 7f * density
            canvas.drawLine(
                cx + cos(a) * r1, sunCy + sin(a) * r1,
                cx + cos(a) * r2, sunCy + sin(a) * r2,
                sunRayPaint
            )
        }
        // Track.
        val radius = trackW / 2f
        canvas.drawRoundRect(cx - trackW / 2f, top, cx + trackW / 2f, bottom, radius, radius, trackPaint)
        // Fill from the bottom up to the current level.
        val fillH = (bottom - top) * value.coerceIn(0f, 1f)
        if (fillH > 0f) {
            canvas.drawRoundRect(
                cx - trackW / 2f, bottom - fillH, cx + trackW / 2f, bottom,
                radius, radius, fillPaint
            )
        }
        // Thumb at the current level.
        val thumbY = bottom - fillH
        val thumbR = 11f * density
        canvas.drawCircle(cx, thumbY, thumbR, thumbPaint)
        canvas.drawCircle(cx, thumbY, thumbR, thumbRingPaint)
    }
}
