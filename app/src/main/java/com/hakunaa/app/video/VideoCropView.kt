package com.hakunaa.app.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Video Crop — draggable crop frame over one video frame.
 * v0.1.198 Fix 8. Same interaction as image/DpAdjustView
 * (frame stored as fractions of the bitmap, FIT draw, drag
 * inside to move, drag a corner to resize) but the frame keeps
 * a SELECTED aspect ratio instead of a square, and the callback
 * reports the frame in VIDEO PIXEL coordinates (even-aligned
 * for yuv420p) so the activity can crop with it directly.
 */
class VideoCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null

    /** Crop frame as fractions (0..1) of the bitmap. */
    private val frameF = RectF(0f, 0f, 0f, 0f)

    /** Destination rect of the fitted bitmap on screen, recomputed in onDraw. */
    private val dest = RectF()

    /** Width / height of the crop frame. Set by the activity when a preset is picked. */
    private var aspectRatio = 16f / 9f

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#EC407A")
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EC407A")
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    /** Rule-of-thirds grid lines inside the frame (v0.1.199). */
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }

    private val density get() = resources.displayMetrics.density
    /** Touch target: ±24dp = 48dp total (v0.1.200 rework). */
    private val handleTouchPx get() = 24f * density
    /** Visual handle: 28dp white square, pink stroke (v0.1.200). */
    private val handleSizePx get() = 28f * density

    /** Free-form mode (the "Free" preset): no aspect lock — the
     * frame resizes freely in both directions (v0.1.200). */
    private var freeForm = false

    // ---- drag state ----
    // 0 TL, 1 TR, 2 BR, 3 BL (corners only, v0.1.200). -1 = none.
    private var dragHandle = -1
    private var draggingInside = false
    private var lastX = 0f
    private var lastY = 0f
    private var anchorX = 0f // opposite corner / edge (fractions) fixed while resizing
    private var anchorY = 0f

    /** (x, y, w, h) in video pixel coordinates, even-aligned. Fires on every move/resize. */
    var onFrameChanged: ((Int, Int, Int, Int) -> Unit)? = null

    fun setImage(bmp: Bitmap?) {
        bitmap = bmp
        // Black letterbox bars (like the mockup) once the frame
        // arrives; before that the grey box behind shows.
        if (bmp != null) setBackgroundColor(Color.BLACK)
        reframeForAspect()
        invalidate()
        notifyFrameChanged()
    }

    /** Set the crop aspect (width/height) and reframe to the
     * largest rect of that ratio, keeping the current centre. */
    fun setAspect(ratio: Float) {
        freeForm = false
        aspectRatio = ratio.coerceAtLeast(0.1f)
        reframeForAspect()
        invalidate()
        notifyFrameChanged()
    }

    /** Free-form crop (the "Free" preset, v0.1.200): no aspect
     * lock — the frame covers the whole video and resizes freely. */
    fun setFree() {
        freeForm = true
        frameF.set(0f, 0f, 1f, 1f)
        invalidate()
        notifyFrameChanged()
    }

    /** Crop rect in bitmap pixels, safely clamped inside the bitmap, even-aligned. */
    fun getCropRectPx(): Rect {
        val bmp = bitmap ?: return Rect(0, 0, 0, 0)
        var left = (frameF.left * bmp.width).toInt().coerceIn(0, bmp.width - 2)
        var top = (frameF.top * bmp.height).toInt().coerceIn(0, bmp.height - 2)
        var right = (frameF.right * bmp.width).toInt().coerceIn(left + 2, bmp.width)
        var bottom = (frameF.bottom * bmp.height).toInt().coerceIn(top + 2, bmp.height)
        // yuv420p needs even dimensions + even offsets.
        left = (left / 2) * 2
        top = (top / 2) * 2
        right = (right / 2) * 2
        bottom = (bottom / 2) * 2
        if (right - left < 2) right = left + 2
        if (bottom - top < 2) bottom = top + 2
        return Rect(left, top, right, bottom)
    }

    private fun notifyFrameChanged() {
        val r = getCropRectPx()
        if (r.width() > 0 && r.height() > 0) {
            onFrameChanged?.invoke(r.left, r.top, r.width(), r.height())
        }
    }

    /** Largest centred rect of the current aspect inside the bitmap. */
    private fun reframeForAspect() {
        val bmp = bitmap ?: return
        if (freeForm) {
            frameF.set(0f, 0f, 1f, 1f)
            return
        }
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val wF: Float
        val hF: Float
        if (bw / bh > aspectRatio) {
            hF = 1f
            wF = aspectRatio * bh / bw
        } else {
            wF = 1f
            hF = bw / aspectRatio / bh
        }
        // Keep the current centre if the frame exists, else centre.
        val cx = frameF.centerX().coerceIn(wF / 2f, 1f - wF / 2f)
        val cy = frameF.centerY().coerceIn(hF / 2f, 1f - hF / 2f)
        frameF.set(cx - wF / 2f, cy - hF / 2f, cx + wF / 2f, cy + hF / 2f)
    }

    private fun frameOnScreen(out: RectF) {
        out.set(
            dest.left + frameF.left * dest.width(),
            dest.top + frameF.top * dest.height(),
            dest.left + frameF.right * dest.width(),
            dest.top + frameF.bottom * dest.height()
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = Math.min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        dest.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
        // The frame, fitted and fixed behind the crop rect.
        canvas.drawBitmap(bmp, null, dest, bitmapPaint)

        val frame = RectF()
        frameOnScreen(frame)
        // Dim everything outside the frame (4 rects).
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, dimPaint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, dimPaint)
        // Pink frame outline + rule-of-thirds grid + 4 large
        // corner handles (v0.1.200 rework).
        canvas.drawRect(frame, outlinePaint)
        val thirdW = frame.width() / 3f
        val thirdH = frame.height() / 3f
        canvas.drawLine(frame.left + thirdW, frame.top, frame.left + thirdW, frame.bottom, gridPaint)
        canvas.drawLine(frame.left + 2f * thirdW, frame.top, frame.left + 2f * thirdW, frame.bottom, gridPaint)
        canvas.drawLine(frame.left, frame.top + thirdH, frame.right, frame.top + thirdH, gridPaint)
        canvas.drawLine(frame.left, frame.top + 2f * thirdH, frame.right, frame.top + 2f * thirdH, gridPaint)
        drawHandle(canvas, frame.left, frame.top)
        drawHandle(canvas, frame.right, frame.top)
        drawHandle(canvas, frame.right, frame.bottom)
        drawHandle(canvas, frame.left, frame.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        val s = handleSizePx / 2f
        canvas.drawRect(x - s, y - s, x + s, y + s, handleFillPaint)
        canvas.drawRect(x - s, y - s, x + s, y + s, handleStrokePaint)
    }

    // ------------------------------------------------------------------ touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = bitmap ?: return false
        if (dest.width() == 0f) return false
        val frame = RectF()
        frameOnScreen(frame)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragHandle = handleHit(event.x, event.y, frame)
                if (dragHandle >= 0) {
                    // Anchor = the OPPOSITE corner (corners only).
                    anchorX = when (dragHandle) {
                        0, 3 -> frameF.right
                        else -> frameF.left // 1, 2
                    }
                    anchorY = when (dragHandle) {
                        0, 1 -> frameF.bottom
                        else -> frameF.top // 2, 3
                    }
                } else {
                    draggingInside = frame.contains(event.x, event.y)
                }
                return dragHandle >= 0 || draggingInside
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragHandle in 0..3) {
                    resizeCorner(event.x, event.y, bmp)
                } else if (draggingInside) {
                    val dx = (event.x - lastX) / dest.width()
                    val dy = (event.y - lastY) / dest.height()
                    val w = frameF.width()
                    val h = frameF.height()
                    frameF.left = (frameF.left + dx).coerceIn(0f, 1f - w)
                    frameF.top = (frameF.top + dy).coerceIn(0f, 1f - h)
                    frameF.right = frameF.left + w
                    frameF.bottom = frameF.top + h
                    notifyFrameChanged()
                }
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragHandle = -1
                draggingInside = false
                notifyFrameChanged()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Hit-test the 4 corner handles: 0 TL, 1 TR, 2 BR, 3 BL. */
    private fun handleHit(x: Float, y: Float, frame: RectF): Int {
        val points = arrayOf(
            frame.left to frame.top,     // 0 TL
            frame.right to frame.top,    // 1 TR
            frame.right to frame.bottom, // 2 BR
            frame.left to frame.bottom   // 3 BL
        )
        for (i in points.indices) {
            val (cx, cy) = points[i]
            if (Math.abs(x - cx) <= handleTouchPx && Math.abs(y - cy) <= handleTouchPx) return i
        }
        return -1
    }

    /** Aspect-locked resize from the dragged corner (opposite fixed). */
    private fun resizeCorner(x: Float, y: Float, bmp: Bitmap) {
        val fx = ((x - dest.left) / dest.width()).coerceIn(0f, 1f)
        val fy = ((y - dest.top) / dest.height()).coerceIn(0f, 1f)
        val sx = when (dragHandle) {
            0, 3 -> -1f
            else -> 1f
        }
        val sy = when (dragHandle) {
            0, 1 -> -1f
            else -> 1f
        }
        if (freeForm) {
            // Free-form: width and height follow the drag
            // independently (no aspect lock). Minimum 64px a side.
            val minPx = 64f
            var wPx = (Math.abs(fx - anchorX) * bmp.width).coerceAtLeast(minPx)
            var hPx = (Math.abs(fy - anchorY) * bmp.height).coerceAtLeast(minPx)
            val maxWF = if (sx < 0) anchorX * bmp.width else (1f - anchorX) * bmp.width
            val maxHF = if (sy < 0) anchorY * bmp.height else (1f - anchorY) * bmp.height
            wPx = wPx.coerceAtMost(maxWF)
            hPx = hPx.coerceAtMost(maxHF)
            val wF = wPx / bmp.width
            val hF = hPx / bmp.height
            val left = if (sx < 0) anchorX - wF else anchorX
            val top = if (sy < 0) anchorY - hF else anchorY
            frameF.set(left, top, left + wF, top + hF)
            notifyFrameChanged()
            return
        }
        // Size from the drag distance, in bitmap pixels: the frame
        // must reach the pointer in both directions, keeping the ratio.
        var wPx = Math.max(
            Math.abs(fx - anchorX) * bmp.width,
            Math.abs(fy - anchorY) * bmp.height * aspectRatio
        )
        var hPx = wPx / aspectRatio
        // Enforce the minimum size (64px on the short side).
        val minPx = 64f
        if (wPx < minPx && aspectRatio >= 1f) { wPx = minPx; hPx = wPx / aspectRatio }
        if (hPx < minPx && aspectRatio < 1f) { hPx = minPx; wPx = hPx * aspectRatio }
        // Clamp so the rect still fits inside the image from the anchor.
        val maxWF = if (sx < 0) anchorX * bmp.width else (1f - anchorX) * bmp.width
        val maxHF = if (sy < 0) anchorY * bmp.height else (1f - anchorY) * bmp.height
        val scaleFit = Math.min(1f, Math.min(maxWF / wPx, maxHF / hPx))
        wPx *= scaleFit
        hPx *= scaleFit
        val wF = wPx / bmp.width
        val hF = hPx / bmp.height
        val left = if (sx < 0) anchorX - wF else anchorX
        val top = if (sy < 0) anchorY - hF else anchorY
        frameF.set(left, top, left + wF, top + hF)
        notifyFrameChanged()
    }

}
