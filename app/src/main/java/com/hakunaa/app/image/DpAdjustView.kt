package com.hakunaa.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * DP adjust view (v0.1.124, reworked v0.1.133 Batch 17) —
 * the professional CROP TOOL for DP Crop.
 *
 * v0.1.124 let the user pinch-zoom and drag the PHOTO inside a
 * fixed frame. The user asked for the Crop tool itself instead
 * (2026-11-19): pick/adjust the CIRCLE (and Square) FRAME —
 * drag the frame to move it, drag a corner (Square) or the edge
 * (Circle) to resize it — like a professional crop tool. The
 * photo stays fixed, fitted behind the frame; the frame is stored
 * as fractions of the bitmap (like ProCropView), so it can never
 * leave the photo and survives rotation.
 *
 * The outer area (Blur / Color / Transparent) is drawn live in
 * the frame's corners (Circle mode), exactly as the saved DP will
 * look. The activity reads the frame back in bitmap pixels
 * ([getCropRectPx]) and renders full-res with
 * [ImageCompressor.dpCircleRect] / [ImageCompressor.dpSquareRect].
 * The bitmap is owned by the caller — this view never recycles it.
 *
 * v0.1.134 (Batch 18): bigger, bolder resize handles (8dp
 * dots, 3dp ring, 4dp frame outline) — the v0.1.133 dots were
 * too small and the tool did not look professional. The drag /
 * resize math is unchanged and still clamps the frame inside
 * the photo (move coerceIn, corner scaleFit, circle maxR).
 */
class DpAdjustView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    private var blurredBg: Bitmap? = null
    private var circle = false
    private var background = ImageCompressor.DpBackground.BLUR
    private var bgColor = 0xFFFFFFFF.toInt()

    /** Crop frame as fractions (0..1) of the bitmap. Always a square. */
    private val frameF = RectF(0.04f, 0.04f, 0.96f, 0.96f)

    /** Destination rect of the fitted bitmap on screen, recomputed in onDraw. */
    private val dest = RectF()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF00BFA5.toInt()
        // v0.1.134 (Batch 18): 4dp — a bolder, clearly visible
        // professional frame (was 3dp).
        strokeWidth = 4f * resources.displayMetrics.density
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.SQUARE
    }
    private val handleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.FILL
    }
    private val handleDotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        // v0.1.134 (Batch 18): 3dp ring — the resize dots were
        // tiny (5dp radius, 2dp ring) and hard to see; the user
        // called the tool unprofessional. Bigger, bolder dots
        // (8dp radius, 3dp white ring) read as proper grab
        // handles, ProCropView-bold.
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val colorFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val checkerLight = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val checkerGrey = Paint().apply { color = 0xFFE8EAED.toInt() }

    private val density get() = resources.displayMetrics.density
    private val minFramePx get() = 48f * density
    private val handleTouchPx get() = 36f * density
    private val cornerLenPx get() = 26f * density

    // ---- drag state ----
    private var dragCorner = -1 // 0 TL, 1 TR, 2 BR, 3 BL; -1 = none
    private var dragEdge = false // Circle mode: dragging the circumference
    private var draggingInside = false
    private var lastX = 0f
    private var lastY = 0f
    private var anchorX = 0f // opposite corner (fractions) fixed while resizing
    private var anchorY = 0f

    fun setImage(bmp: Bitmap?, circle: Boolean, background: ImageCompressor.DpBackground, color: Int) {
        bitmap = bmp
        this.circle = circle
        this.background = background
        bgColor = color
        blurredBg = null
        reframeLargest()
        invalidate()
    }

    fun setBackgroundChoice(background: ImageCompressor.DpBackground, color: Int) {
        this.background = background
        bgColor = color
        blurredBg = null
        invalidate()
    }

    /** Crop rect in bitmap pixels, safely clamped inside the bitmap. */
    fun getCropRectPx(): Rect {
        val bmp = bitmap ?: return Rect(0, 0, 0, 0)
        val left = (frameF.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val top = (frameF.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val right = (frameF.right * bmp.width).toInt().coerceIn(left + 1, bmp.width)
        val bottom = (frameF.bottom * bmp.height).toInt().coerceIn(top + 1, bmp.height)
        return Rect(left, top, right, bottom)
    }

    /** Largest centred square of the bitmap, in fractions. */
    private fun reframeLargest() {
        val bmp = bitmap ?: return
        val sidePx = min(bmp.width, bmp.height).toFloat()
        val wF = sidePx / bmp.width
        val hF = sidePx / bmp.height
        frameF.set((1f - wF) / 2f, (1f - hF) / 2f, (1f + wF) / 2f, (1f + hF) / 2f)
    }

    private fun frameOnScreen(out: RectF) {
        out.set(
            dest.left + frameF.left * dest.width(),
            dest.top + frameF.top * dest.height(),
            dest.left + frameF.right * dest.width(),
            dest.top + frameF.bottom * dest.height()
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The frame is stored in bitmap fractions, so it survives
        // size changes (rotation keeps the same crop).
        blurredBg = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Always square: the smaller of the two offered sizes.
        val size = minOf(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
        )
    }

    private fun ensureBlurredBg() {
        val bmp = bitmap ?: return
        if (blurredBg != null || width <= 0 || height <= 0) return
        val smallH = (32f * height / width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, 32, smallH, true)
        blurredBg = Bitmap.createScaledBitmap(small, width, height, true)
        small.recycle()
    }

    private fun drawCheckerboard(canvas: Canvas, bounds: RectF) {
        val tile = (16 * resources.displayMetrics.density).toInt().coerceAtLeast(4)
        var y = bounds.top
        var row = 0
        while (y < bounds.bottom) {
            var x = bounds.left
            var col = row
            while (x < bounds.right) {
                canvas.drawRect(
                    x, y,
                    minOf(x + tile, bounds.right),
                    minOf(y + tile, bounds.bottom),
                    if (col % 2 == 0) checkerLight else checkerGrey
                )
                x += tile
                col++
            }
            y += tile
            row++
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        dest.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
        // The photo, fitted and fixed behind the frame.
        canvas.drawBitmap(bmp, null, dest, bitmapPaint)

        val frame = RectF()
        frameOnScreen(frame)

        if (circle) {
            // Dim everything outside the circle.
            val dimPath = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                addCircle(frame.centerX(), frame.centerY(), frame.width() / 2f, Path.Direction.CW)
            }
            canvas.drawPath(dimPath, dimPaint)
            // Outer area in the frame's corners (outside the circle,
            // inside the square) — exactly as the saved DP looks.
            val cornersPath = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(frame, Path.Direction.CW)
                addCircle(frame.centerX(), frame.centerY(), frame.width() / 2f, Path.Direction.CW)
            }
            when (background) {
                ImageCompressor.DpBackground.BLUR -> {
                    ensureBlurredBg()
                    blurredBg?.let {
                        canvas.save()
                        canvas.clipPath(cornersPath)
                        canvas.drawBitmap(it, 0f, 0f, null)
                        canvas.restore()
                    } ?: canvas.drawPath(cornersPath, checkerGrey)
                }
                ImageCompressor.DpBackground.COLOR -> {
                    colorFillPaint.color = bgColor
                    canvas.drawPath(cornersPath, colorFillPaint)
                }
                ImageCompressor.DpBackground.TRANSPARENT -> {
                    canvas.save()
                    canvas.clipPath(cornersPath)
                    drawCheckerboard(canvas, frame)
                    canvas.restore()
                }
            }
            // Circle outline + resize dots at the 4 cardinal points.
            // v0.1.134 (Batch 18): 8dp dots — clearly visible
            // professional grab handles (were 5dp, too small).
            canvas.drawCircle(frame.centerX(), frame.centerY(), frame.width() / 2f, framePaint)
            canvas.drawCircle(frame.centerX(), frame.centerY(), frame.width() / 2f, outlinePaint)
            val dotR = 8f * density
            val cx = frame.centerX()
            val cy = frame.centerY()
            val r = frame.width() / 2f
            for ((dx, dy) in listOf(0f to -r, r to 0f, 0f to r, -r to 0f)) {
                canvas.drawCircle(cx + dx, cy + dy, dotR, handleDotPaint)
                canvas.drawCircle(cx + dx, cy + dy, dotR, handleDotRingPaint)
            }
        } else {
            // Dim everything outside the frame (4 rects).
            canvas.drawRect(0f, 0f, width.toFloat(), frame.top, dimPaint)
            canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
            canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, dimPaint)
            // Frame outline + L-shaped corner handles.
            canvas.drawRect(frame, framePaint)
            canvas.drawRect(frame, outlinePaint)
            drawCorner(canvas, frame.left, frame.top, 1f, 1f)
            drawCorner(canvas, frame.right, frame.top, -1f, 1f)
            drawCorner(canvas, frame.right, frame.bottom, -1f, -1f)
            drawCorner(canvas, frame.left, frame.bottom, 1f, -1f)
        }
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, sx: Float, sy: Float) {
        val l = cornerLenPx
        canvas.drawLine(x, y, x + sx * l, y, cornerPaint)
        canvas.drawLine(x, y, x, y + sy * l, cornerPaint)
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
                dragCorner = cornerHit(event.x, event.y, frame)
                if (dragCorner >= 0) {
                    anchorX = when (dragCorner) {
                        0, 3 -> frameF.right
                        else -> frameF.left
                    }
                    anchorY = when (dragCorner) {
                        0, 1 -> frameF.bottom
                        else -> frameF.top
                    }
                } else if (circle && circleEdgeHit(event.x, event.y, frame)) {
                    dragEdge = true
                } else {
                    draggingInside = frame.contains(event.x, event.y)
                }
                return dragCorner >= 0 || dragEdge || draggingInside
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragCorner >= 0) {
                    resizeCorner(event.x, event.y, bmp)
                } else if (dragEdge) {
                    resizeCircleEdge(event.x, event.y)
                } else if (draggingInside) {
                    val dx = (event.x - lastX) / dest.width()
                    val dy = (event.y - lastY) / dest.height()
                    val w = frameF.width()
                    val h = frameF.height()
                    frameF.left = (frameF.left + dx).coerceIn(0f, 1f - w)
                    frameF.top = (frameF.top + dy).coerceIn(0f, 1f - h)
                    frameF.right = frameF.left + w
                    frameF.bottom = frameF.top + h
                }
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragCorner = -1
                dragEdge = false
                draggingInside = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cornerHit(x: Float, y: Float, frame: RectF): Int {
        val corners = arrayOf(
            frame.left to frame.top,
            frame.right to frame.top,
            frame.right to frame.bottom,
            frame.left to frame.bottom
        )
        for (i in corners.indices) {
            val (cx, cy) = corners[i]
            if (abs(x - cx) <= handleTouchPx && abs(y - cy) <= handleTouchPx) return i
        }
        return -1
    }

    /** Circle mode: touch within a handle of the circumference. */
    private fun circleEdgeHit(x: Float, y: Float, frame: RectF): Boolean {
        val dist = hypot(x - frame.centerX(), y - frame.centerY())
        return abs(dist - frame.width() / 2f) <= handleTouchPx
    }

    /** Locked 1:1 resize from the dragged corner (opposite fixed). */
    private fun resizeCorner(x: Float, y: Float, bmp: Bitmap) {
        val fx = ((x - dest.left) / dest.width()).coerceIn(0f, 1f)
        val fy = ((y - dest.top) / dest.height()).coerceIn(0f, 1f)
        val sx = when (dragCorner) {
            0, 3 -> -1f
            else -> 1f
        }
        val sy = when (dragCorner) {
            0, 1 -> -1f
            else -> 1f
        }
        // Size from the drag distance, square in bitmap pixels.
        var wPx = abs(fx - anchorX) * bmp.width
        var hPx = abs(fy - anchorY) * bmp.height
        if (wPx > hPx) hPx = wPx else wPx = hPx
        // Enforce the minimum size (in bitmap pixels).
        val minWPx = minFramePx / dest.width() * bmp.width
        val minHPx = minFramePx / dest.height() * bmp.height
        if (wPx < minWPx) { wPx = minWPx; hPx = wPx }
        if (hPx < minHPx) { hPx = minHPx; wPx = hPx }
        // Clamp so the rect still fits inside the image from the anchor.
        val maxWF = if (sx < 0) anchorX * bmp.width else (1f - anchorX) * bmp.width
        val maxHF = if (sy < 0) anchorY * bmp.height else (1f - anchorY) * bmp.height
        val scaleFit = min(1f, min(maxWF / wPx, maxHF / hPx))
        wPx *= scaleFit
        hPx *= scaleFit
        val wF = wPx / bmp.width
        val hF = hPx / bmp.height
        val left = if (sx < 0) anchorX - wF else anchorX
        val top = if (sy < 0) anchorY - hF else anchorY
        frameF.set(left, top, left + wF, top + hF)
    }

    /** Circle mode: drag the circumference — resize around the centre. */
    private fun resizeCircleEdge(x: Float, y: Float) {
        val frame = RectF()
        frameOnScreen(frame)
        val cx = frame.centerX()
        val cy = frame.centerY()
        val wantedR = hypot(x - cx, y - cy)
        val minR = minFramePx / 2f
        val maxR = min(
            min(cx - dest.left, dest.right - cx),
            min(cy - dest.top, dest.bottom - cy)
        )
        val r = wantedR.coerceIn(minR, maxR)
        // Radius in screen px -> square side in bitmap fractions.
        val sideW = 2f * r / dest.width()
        val sideH = 2f * r / dest.height()
        val cfx = (cx - dest.left) / dest.width()
        val cfy = (cy - dest.top) / dest.height()
        frameF.set(cfx - sideW / 2f, cfy - sideH / 2f, cfx + sideW / 2f, cfy + sideH / 2f)
    }
}
