package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Book mode's spine line — CamScanner-style (v0.1.100). The
 * screen stays in PORTRAIT and the camera preview is a tall
 * 3:4 portrait band, so the landscape book appears ROTATED:
 * the spine is a HORIZONTAL dashed line across the middle,
 * page A on TOP and page B at the BOTTOM (exactly like the
 * CamScanner screenshot).
 *
 * - Drag the handle (or the line itself) to move the spine
 *   to the book's real fold — the spine moves UP / DOWN.
 *   A tap elsewhere picks a side — in Single mode that is
 *   the page the next capture takes (top = A, bottom = B).
 * - The line is clamped to the camera image (fitCenter bars
 *   excluded): set [contentRect] once the preview is laid out.
 * - [spineFraction] is a fraction of the content rect's
 *   HEIGHT (0 = top, 1 = bottom). It maps straight onto the
 *   landscape sensor image's WIDTH, so the split in
 *   DocumentDetector.splitSpread needs no conversion.
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

    /** Spine position as a fraction of the content rect's height. */
    var spineFraction: Float = 0.5f
        private set

    /** The side the user tapped in Single mode (none in Double).
     *  SIDE_LEFT = top page (A), SIDE_RIGHT = bottom page (B). */
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
        alpha = 60
        textSize = 72f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val labelSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        alpha = 140
        textSize = 72f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val sideFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        alpha = 22
        style = Paint.Style.FILL
    }

    /** Corner brackets — the framing corners professional
     *  scanners draw around the area the book should fill
     *  (CamScanner's Book mode). Four white L-shapes at the
     *  corners of the camera image, drawn on top. */
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.SQUARE
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

    private fun spineY(): Float {
        val r = rect()
        return r.top + spineFraction * r.height()
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
                canDrag = abs(event.y - spineY()) < 48f * density
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > 12f * density || abs(event.y - downY) > 12f * density) {
                    dragging = true
                }
                if (dragging && canDrag) {
                    spineFraction = ((event.y - r.top) / r.height()).coerceIn(0.05f, 0.95f)
                    invalidate()
                    onSpineChanged?.invoke(spineFraction)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging && event.action == MotionEvent.ACTION_UP) {
                    val side = if (event.y < spineY()) SIDE_LEFT else SIDE_RIGHT
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
        val sy = spineY()
        // Selected side (Single mode): a light teal wash.
        // SIDE_LEFT = top page (A), SIDE_RIGHT = bottom page (B).
        if (selectedSide == SIDE_LEFT) {
            canvas.drawRect(r.left, r.top, r.right, sy, sideFillPaint)
        } else if (selectedSide == SIDE_RIGHT) {
            canvas.drawRect(r.left, sy, r.right, r.bottom, sideFillPaint)
        }
        // Big A / B letters at the centre of each half —
        // A on TOP, B at the BOTTOM (the rotated book).
        val cx = r.centerX()
        val topCy = (r.top + sy) / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        val bottomCy = (sy + r.bottom) / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText("A", cx, topCy, if (selectedSide == SIDE_LEFT) labelSelectedPaint else labelPaint)
        canvas.drawText("B", cx, bottomCy, if (selectedSide == SIDE_RIGHT) labelSelectedPaint else labelPaint)
        // Dashed spine line, left to right across the image.
        canvas.drawLine(r.left, sy, r.right, sy, linePaint)
        // Teal handle at the centre, with a grip (two bars).
        val handleR = 22f * density
        val hx = r.centerX()
        canvas.drawCircle(hx, sy, handleR, handlePaint)
        canvas.drawCircle(hx, sy, handleR, handleRingPaint)
        // Grip: two small triangles pointing up / down — the
        // handle drags the split vertically.
        val tri = 6f * density
        val gap = 4f * density
        val topTri = Path().apply {
            moveTo(hx, sy - gap - tri)
            lineTo(hx - tri, sy - gap)
            lineTo(hx + tri, sy - gap)
            close()
        }
        val bottomTri = Path().apply {
            moveTo(hx, sy + gap + tri)
            lineTo(hx - tri, sy + gap)
            lineTo(hx + tri, sy + gap)
            close()
        }
        canvas.drawPath(topTri, gripPaint)
        canvas.drawPath(bottomTri, gripPaint)
        // Corner brackets at the four corners of the camera
        // image — the framing corners.
        val arm = 36f * density
        val inset = 10f * density
        fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
            val path = Path().apply {
                moveTo(cx + dx * arm, cy)
                lineTo(cx, cy)
                lineTo(cx, cy + dy * arm)
            }
            canvas.drawPath(path, cornerPaint)
        }
        corner(r.left + inset, r.top + inset, 1f, 1f)
        corner(r.right - inset, r.top + inset, -1f, 1f)
        corner(r.left + inset, r.bottom - inset, 1f, -1f)
        corner(r.right - inset, r.bottom - inset, -1f, -1f)
    }
}
