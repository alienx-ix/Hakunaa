package com.hakunaa.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Professional trim timeline (v0.1.198 Fix 6): a horizontal strip
 * of video-frame thumbnails with a draggable LEFT handle (start)
 * and RIGHT handle (end). The selected range between the handles
 * stays bright; the trimmed-off ends are dimmed. Dragging a handle
 * moves the cut point and reports it via [onRangeChanged].
 *
 * The activity supplies thumbnails ([setThumbnails]) and reads the
 * range back through [startMs]/[endMs]. Until thumbnails arrive
 * the strip is plain grey — the timeline stays draggable.
 */
class VideoTrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Minimum clip length (ms) — the handles can never cross it. */
        const val MIN_GAP_MS = 1000L
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    var durationMs: Long = 0L
        set(value) {
            field = value.coerceAtLeast(0L)
            invalidate()
        }

    /** Cut start (ms). Clamped to 0..(endMs - MIN_GAP_MS). */
    var startMs: Long = 0L
        set(value) {
            field = value.coerceIn(0L, (endMs - MIN_GAP_MS).coerceAtLeast(0L))
            invalidate()
        }

    /** Cut end (ms). Clamped to (startMs + MIN_GAP_MS)..durationMs. */
    var endMs: Long = 0L
        set(value) {
            val lo = (startMs + MIN_GAP_MS).coerceAtMost(durationMs)
            field = value.coerceIn(lo, durationMs.coerceAtLeast(0L))
            invalidate()
        }

    /** Called while a handle is dragged (and on release): (startMs, endMs). */
    var onRangeChanged: ((Long, Long) -> Unit)? = null

    private var thumbs: List<Bitmap> = emptyList()

    fun setThumbnails(bitmaps: List<Bitmap>) {
        thumbs = bitmaps
        invalidate()
    }

    private val stripPaint = Paint().apply { color = Color.parseColor("#CFD8DC") }
    private val dimPaint = Paint().apply { color = Color.argb(170, 255, 255, 255) }
    private val handlePaint = Paint().apply { color = 0xFFEC407A.toInt() }
    private val gripPaint = Paint().apply { color = Color.WHITE }
    private val borderPaint = Paint().apply {
        color = 0xFFEC407A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val handleWidthPx: Int get() = dp(28)
    private val touchWidthPx: Int get() = dp(48)

    private var dragMode = 0 // 0 none, 1 left handle, 2 right handle

    private fun msToX(ms: Long): Float {
        if (durationMs <= 0) return 0f
        return (ms.toFloat() / durationMs.toFloat()) * width.toFloat()
    }

    private fun xToMs(x: Float): Long {
        if (width <= 0 || durationMs <= 0) return 0L
        return ((x / width.toFloat()) * durationMs.toFloat()).toLong()
            .coerceIn(0L, durationMs)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // Thumbnail strip (grey placeholder until thumbs arrive).
        if (thumbs.isEmpty()) {
            canvas.drawRect(0f, 0f, w, h, stripPaint)
        } else {
            val n = thumbs.size
            val tw = w / n
            for (i in thumbs.indices) {
                val dst = RectF(i * tw, 0f, (i + 1) * tw, h)
                canvas.drawBitmap(thumbs[i], null, dst, null)
            }
        }
        val sx = msToX(startMs)
        val ex = msToX(endMs)
        // Dim the trimmed-off ends.
        if (sx > 0f) canvas.drawRect(0f, 0f, sx, h, dimPaint)
        if (ex < w) canvas.drawRect(ex, 0f, w, h, dimPaint)
        // Selected-range border.
        canvas.drawRect(sx, 0f, ex, h, borderPaint)
        // Handles: pink bars with white grip dots.
        drawHandle(canvas, sx, h)
        drawHandle(canvas, ex, h)
    }

    private fun drawHandle(canvas: Canvas, cx: Float, h: Float) {
        val hw = handleWidthPx.toFloat()
        val left = (cx - hw / 2f).coerceIn(0f, (width.toFloat() - hw).coerceAtLeast(0f))
        canvas.drawRoundRect(
            RectF(left, 0f, left + hw, h),
            dp(8).toFloat(), dp(8).toFloat(), handlePaint
        )
        val cy = h / 2f
        val gap = dp(18).toFloat()
        for (dy in listOf(-gap, 0f, gap)) {
            canvas.drawCircle(left + hw / 2f, cy + dy, dp(3).toFloat(), gripPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = event.x
                val sx = msToX(startMs)
                val ex = msToX(endMs)
                val halfTouch = touchWidthPx / 2f
                dragMode = when {
                    Math.abs(x - sx) <= halfTouch -> 1
                    Math.abs(x - ex) <= halfTouch -> 2
                    else -> 0
                }
                if (dragMode != 0) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == 0) return false
                val ms = xToMs(event.x)
                if (dragMode == 1) {
                    startMs = ms.coerceAtMost(endMs - MIN_GAP_MS)
                } else {
                    endMs = ms.coerceAtLeast(startMs + MIN_GAP_MS)
                }
                onRangeChanged?.invoke(startMs, endMs)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != 0) {
                    onRangeChanged?.invoke(startMs, endMs)
                    dragMode = 0
                    parent.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
