package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Book mode's spine line — the dashed line down the middle of
 * the open book, with a teal handle to drag it and big A / B
 * letters for the two pages (A = left page, B = right page).
 *
 * - Drag the handle (or the line itself) to move the spine to
 *   the book's real fold. A tap elsewhere picks a side — in
 *   Single mode that is the page the next capture takes.
 * - The line is clamped to the camera image (fitCenter bars
 *   excluded): set [contentRect] once the preview is laid out.
 * - The auto spine detection moves the line (via
 *   [setSpineFraction]) until the user drags it; the activity
 *   decides when to stop following.
 *
 * Safe to call setSpineFraction from any thread (the analysis
 * runs on a background thread); the update posts to the UI
 * thread itself.
 */
class SpineLineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        const val SIDE_NONE = 0
        const val SIDE_LEFT = 1
        const val SIDE_RIGHT = 2
    }

    /** Spine position as a fraction of the content rect's width. */
    var spineFraction: Float = 0.5f
        private set

    /** The side the user tapped in Single mode (none in Double). */
    var selectedSide: Int = SIDE_NONE
        set(value) {
            field = value
            invalidate()
        }

    /** Fires on a drag (live) with the new fraction. */
    var onSpineChanged: ((Float) -> Unit)? = null

    /** Fires on a tap (not a drag) with the tapped side. */
    var onSideTapped: ((Int) -> Unit)? = null

    /** The camera image's rect inside this view (fitCenter bars excluded). */
    var contentRect: RectF = RectF()
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        pathEffect = DashPathEffect(floatArrayOf(24f * density, 16f * density), 0f)
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.FILL
    }

    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 80
        textSize = 110f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val labelSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        alpha = 170
        textSize = 110f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val sideFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        alpha = 22
        style = Paint.Style.FILL
    }

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var canDrag = false

    fun setSpineFraction(f: Float) {
        post {
            spineFraction = f.coerceIn(0.05f, 0.95f)
            invalidate()
        }
    }

    private fun rect(): RectF {
        return if (contentRect.isEmpty) RectF(0f, 0f, width.toFloat(), height.toFloat()) else contentRect
    }

    private fun spineX(): Float {
        val r = rect()
        return r.left + spineFraction * r.width()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = rect()
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                // Only a grab near the line starts a drag — a
                // swipe anywhere else must not move the spine.
                canDrag = abs(event.x - spineX()) < 48f * density
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > 12f * density || abs(event.y - downY) > 12f * density) {
                    dragging = true
                }
                if (dragging && canDrag) {
                    spineFraction = ((event.x - r.left) / r.width()).coerceIn(0.05f, 0.95f)
                    invalidate()
                    onSpineChanged?.invoke(spineFraction)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging && event.action == MotionEvent.ACTION_UP) {
                    val side = if (event.x < spineX()) SIDE_LEFT else SIDE_RIGHT
                    onSideTapped?.invoke(side)
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = rect()
        if (r.width() <= 0f || r.height() <= 0f) return
        val sx = spineX()
        // Selected side (Single mode): a light teal wash.
        if (selectedSide == SIDE_LEFT) {
            canvas.drawRect(r.left, r.top, sx, r.bottom, sideFillPaint)
        } else if (selectedSide == SIDE_RIGHT) {
            canvas.drawRect(sx, r.top, r.right, r.bottom, sideFillPaint)
        }
        // Big A / B letters at the centre of each half.
        val cy = r.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        val leftCx = (r.left + sx) / 2f
        val rightCx = (sx + r.right) / 2f
        canvas.drawText("A", leftCx, cy, if (selectedSide == SIDE_LEFT) labelSelectedPaint else labelPaint)
        canvas.drawText("B", rightCx, cy, if (selectedSide == SIDE_RIGHT) labelSelectedPaint else labelPaint)
        // Dashed spine line, top to bottom of the image.
        canvas.drawLine(sx, r.top, sx, r.bottom, linePaint)
        // Teal handle at the centre, with a grip (two bars).
        val handleR = 22f * density
        val hy = r.centerY()
        canvas.drawCircle(sx, hy, handleR, handlePaint)
        canvas.drawCircle(sx, hy, handleR, handleRingPaint)
        val barW = 3f * density
        val barH = 18f * density
        val gap = 5f * density
        canvas.drawRect(sx - gap - barW, hy - barH / 2f, sx - gap, hy + barH / 2f, gripPaint)
        canvas.drawRect(sx + gap, hy - barH / 2f, sx + gap + barW, hy + barH / 2f, gripPaint)
    }
}
