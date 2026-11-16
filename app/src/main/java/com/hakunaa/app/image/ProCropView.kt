package com.hakunaa.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ProCropView — professional rectangular crop frame (Google Photos style).
 *
 * Unlike the scanner's CropView (a free-form quadrilateral whose corners
 * can tilt the frame), this view only ever draws an axis-aligned
 * RECTANGLE, so the frame can never go crooked.
 *
 * - Corner handles resize the rectangle (locked aspect kept when set).
 * - Dragging inside the frame pans the whole frame.
 * - Rotation (-45..+45 deg) re-renders the bitmap; the frame is stored
 *   as fractions of the image, so it survives rotation changes.
 * - Circle mode draws a circle inscribed in the square frame (DP Edit).
 */
class ProCropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Called with the crop size in rotated-bitmap pixels whenever the frame changes. */
    var onFrameChanged: ((cropWidthPx: Int, cropHeightPx: Int) -> Unit)? = null

    private var original: Bitmap? = null
    private var rotated: Bitmap? = null
    private var rotationDeg = 0f

    /** Crop frame as fractions (0..1) of the rotated bitmap. Always a rectangle. */
    private val frameF = RectF(0.04f, 0.04f, 0.96f, 0.96f)

    /** null = Freestyle; otherwise width / height of the locked aspect. */
    private var aspect: Float? = null
    private var circleMode = false

    /** Destination rect of the bitmap on screen, recomputed in onDraw. */
    private val dest = RectF()

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val framePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        isAntiAlias = true
    }
    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.STROKE
        strokeWidth = 5f * resources.displayMetrics.density
        strokeCap = Paint.Cap.SQUARE
        isAntiAlias = true
    }
    private val gridPaint = Paint().apply {
        color = Color.argb(110, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
        isAntiAlias = true
    }

    private val density get() = resources.displayMetrics.density
    private val minFramePx get() = 48f * density
    private val handleTouchPx get() = 36f * density
    private val cornerLenPx get() = 26f * density

    // ---- drag state ----
    private var dragCorner = -1 // 0 TL, 1 TR, 2 BR, 3 BL; -1 = none
    private var draggingInside = false
    private var lastX = 0f
    private var lastY = 0f
    private var anchorX = 0f // opposite corner (fractions) fixed while resizing
    private var anchorY = 0f

    // ------------------------------------------------------------------ setup

    fun setBitmap(bmp: Bitmap) {
        releaseBitmaps()
        original = bmp
        rotated = bmp
        rotationDeg = 0f
        reframeLargest()
        notifyChanged()
        invalidate()
    }

    fun setCircleMode(circle: Boolean) {
        circleMode = circle
        if (circle) aspect = 1f
        if (rotated != null) {
            reframeLargest()
            notifyChanged()
            invalidate()
        }
    }

    /** w/h in pixels, or null for Freestyle. "Original" = pass the source w/h. */
    fun setAspectRatio(w: Int, h: Int) {
        aspect = if (w > 0 && h > 0) w.toFloat() / h.toFloat() else null
        if (circleMode) aspect = 1f
        if (rotated != null) {
            reframeLargest()
            notifyChanged()
            invalidate()
        }
    }

    fun setAspectRatio(ratio: Float?) {
        aspect = ratio
        if (circleMode) aspect = 1f
        if (rotated != null) {
            reframeLargest()
            notifyChanged()
            invalidate()
        }
    }

    /** Locks the aspect to the source image's own aspect ratio. */
    fun setAspectOriginal() {
        val bmp = rotated ?: return
        aspect = bmp.width.toFloat() / bmp.height.toFloat()
        reframeLargest()
        notifyChanged()
        invalidate()
    }

    fun getAspectRatio(): Float? = aspect

    fun setCropRotation(degrees: Float) {
        val src = original ?: return
        val deg = degrees.coerceIn(-45f, 45f)
        if (deg == rotationDeg) return
        rotationDeg = deg
        val oldRotated = rotated
        rotated = if (deg == 0f) {
            src
        } else {
            val matrix = Matrix().apply { postRotate(deg) }
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        }
        if (oldRotated != null && oldRotated !== src && oldRotated !== rotated) {
            oldRotated.recycle()
        }
        notifyChanged()
        invalidate()
    }

    fun getCropRotation(): Float = rotationDeg

    fun getRotatedBitmap(): Bitmap? = rotated

    fun getSourceWidth(): Int = rotated?.width ?: 0
    fun getSourceHeight(): Int = rotated?.height ?: 0

    /** Rotation back to 0 and the largest centred frame (keeps the aspect). */
    fun restore() {
        setCropRotation(0f)
        reframeLargest()
        notifyChanged()
        invalidate()
    }

    /** Crop rect in rotated-bitmap pixels, safely clamped inside the bitmap. */
    fun getCropRectPx(): Rect {
        val bmp = rotated ?: return Rect(0, 0, 0, 0)
        val left = (frameF.left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val top = (frameF.top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val right = (frameF.right * bmp.width).toInt().coerceIn(left + 1, bmp.width)
        val bottom = (frameF.bottom * bmp.height).toInt().coerceIn(top + 1, bmp.height)
        return Rect(left, top, right, bottom)
    }

    fun release() {
        releaseBitmaps()
    }

    private fun releaseBitmaps() {
        val oldRotated = rotated
        val oldOriginal = original
        rotated = null
        original = null
        if (oldRotated != null && oldRotated !== oldOriginal) oldRotated.recycle()
        // The original bitmap is owned by the caller (the activity passes it
        // in); do not recycle it here — the activity recycles it on destroy.
    }

    // ------------------------------------------------------------------ frame

    /** Largest centred rect of the current aspect (or a near-full rect when free). */
    private fun reframeLargest() {
        val bmp = rotated ?: return
        val imgAspect = bmp.width.toFloat() / bmp.height.toFloat()
        if (circleMode) {
            val sidePx = min(bmp.width, bmp.height).toFloat()
            val wF = sidePx / bmp.width
            val hF = sidePx / bmp.height
            frameF.set((1f - wF) / 2f, (1f - hF) / 2f, (1f + wF) / 2f, (1f + hF) / 2f)
            return
        }
        val a = aspect
        if (a == null) {
            frameF.set(0.04f, 0.04f, 0.96f, 0.96f)
            return
        }
        // wFrac / hFrac = aspect / imgAspect
        val ratio = a / imgAspect
        if (ratio <= 1f) {
            frameF.set((1f - ratio) / 2f, 0f, (1f + ratio) / 2f, 1f)
        } else {
            val hF = 1f / ratio
            frameF.set(0f, (1f - hF) / 2f, 1f, (1f + hF) / 2f)
        }
    }

    private fun frameOnScreen(out: RectF) {
        out.set(
            dest.left + frameF.left * dest.width(),
            dest.top + frameF.top * dest.height(),
            dest.left + frameF.right * dest.width(),
            dest.top + frameF.bottom * dest.height()
        )
    }

    private fun notifyChanged() {
        val r = getCropRectPx()
        if (r.width() > 0 && r.height() > 0) onFrameChanged?.invoke(r.width(), r.height())
    }

    // ------------------------------------------------------------------ draw

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = rotated ?: return
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        dest.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f)
        canvas.drawBitmap(bmp, null, dest, bitmapPaint)

        val frame = RectF()
        frameOnScreen(frame)

        if (circleMode) {
            // Dim everything outside the circle.
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                addCircle(frame.centerX(), frame.centerY(), frame.width() / 2f, Path.Direction.CW)
            }
            canvas.drawPath(path, dimPaint)
            canvas.drawCircle(frame.centerX(), frame.centerY(), frame.width() / 2f, framePaint)
            canvas.drawCircle(frame.centerX(), frame.centerY(), frame.width() / 2f, cornerPaint)
        } else {
            // Dim everything outside the frame (4 rects).
            canvas.drawRect(0f, 0f, width.toFloat(), frame.top, dimPaint)
            canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
            canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, dimPaint)
            // Rule-of-thirds grid inside the frame.
            val thirdW = frame.width() / 3f
            val thirdH = frame.height() / 3f
            canvas.drawLine(frame.left + thirdW, frame.top, frame.left + thirdW, frame.bottom, gridPaint)
            canvas.drawLine(frame.left + 2 * thirdW, frame.top, frame.left + 2 * thirdW, frame.bottom, gridPaint)
            canvas.drawLine(frame.left, frame.top + thirdH, frame.right, frame.top + thirdH, gridPaint)
            canvas.drawLine(frame.left, frame.top + 2 * thirdH, frame.right, frame.top + 2 * thirdH, gridPaint)
            // Frame outline + L-shaped corner handles.
            canvas.drawRect(frame, framePaint)
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
        val bmp = rotated ?: return false
        if (dest.width() == 0f) return false
        val frame = RectF()
        frameOnScreen(frame)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragCorner = cornerHit(event.x, event.y, frame)
                if (dragCorner >= 0) {
                    // Anchor = opposite corner, fixed while this corner moves.
                    anchorX = when (dragCorner) {
                        0, 3 -> frameF.right
                        else -> frameF.left
                    }
                    anchorY = when (dragCorner) {
                        0, 1 -> frameF.bottom
                        else -> frameF.top
                    }
                } else {
                    draggingInside = frame.contains(event.x, event.y)
                }
                return dragCorner >= 0 || draggingInside
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragCorner >= 0) {
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
                }
                lastX = event.x
                lastY = event.y
                notifyChanged()
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragCorner = -1
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

    private fun resizeCorner(x: Float, y: Float, bmp: Bitmap) {
        // Touch point in image fractions.
        val fx = ((x - dest.left) / dest.width()).coerceIn(0f, 1f)
        val fy = ((y - dest.top) / dest.height()).coerceIn(0f, 1f)
        // Direction this corner moves away from its anchor.
        val sx = when (dragCorner) {
            0, 3 -> -1f
            else -> 1f
        }
        val sy = when (dragCorner) {
            0, 1 -> -1f
            else -> 1f
        }
        val minWF = minFramePx / dest.width()
        val minHF = minFramePx / dest.height()
        val a = aspect
        if (a == null) {
            // Freestyle: move this corner freely, keep the opposite fixed.
            var left = min(frameF.left, anchorX)
            var top = min(frameF.top, anchorY)
            var right = max(frameF.right, anchorX)
            var bottom = max(frameF.bottom, anchorY)
            if (sx < 0) left = min(fx, anchorX - minWF) else right = max(fx, anchorX + minWF)
            if (sy < 0) top = min(fy, anchorY - minHF) else bottom = max(fy, anchorY + minHF)
            frameF.set(
                left.coerceIn(0f, 1f), top.coerceIn(0f, 1f),
                right.coerceIn(0f, 1f), bottom.coerceIn(0f, 1f)
            )
            return
        }
        // Locked aspect: size from the drag distance, aspect enforced in pixels.
        var wPx = abs(fx - anchorX) * bmp.width
        var hPx = abs(fy - anchorY) * bmp.height
        if (wPx / a > hPx) hPx = wPx / a else wPx = hPx * a
        // Enforce the minimum size (in pixels, on the bitmap).
        val minWPx = minFramePx / dest.width() * bmp.width
        val minHPx = minFramePx / dest.height() * bmp.height
        if (wPx < minWPx) { wPx = minWPx; hPx = wPx / a }
        if (hPx < minHPx) { hPx = minHPx; wPx = hPx * a }
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
}
