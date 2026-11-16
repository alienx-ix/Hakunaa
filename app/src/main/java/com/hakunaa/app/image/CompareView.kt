package com.hakunaa.app.image

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.abs

/**
 * Before / After compare view for the Smart results screen.
 *
 * Shows the original photo on the left of a draggable vertical
 * divider and the compressed result on the right. Drag the divider
 * to compare; tap to switch between the full Before and full
 * After views (split -> Before -> After -> split).
 *
 * Pinch to zoom (1x-4x) and drag to pan while zoomed. When zoomed
 * in, a one-finger drag pans the image instead of moving the
 * divider, and a double-tap smoothly resets the zoom. Zoom resets
 * automatically whenever new images are set.
 *
 * Bitmaps are decoded downsampled on a background thread so a
 * large photo never blocks the UI or blows up memory.
 */
class CompareView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MODE_SPLIT = 0
        private const val MODE_BEFORE = 1
        private const val MODE_AFTER = 2
        /** Longest side of the decoded preview bitmaps. */
        private const val MAX_PREVIEW_DIM = 1200
        /** Pinch-zoom range. */
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 4f
        /** Max gap between two taps for a double-tap zoom reset. */
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
        /** Duration of the animated zoom reset. */
        private const val ZOOM_RESET_DURATION_MS = 250L
    }

    private var originalFile: File? = null
    private var compressedFile: File? = null
    private var originalBitmap: Bitmap? = null
    private var compressedBitmap: Bitmap? = null
    private var dividerFraction = 0.5f
    private var viewMode = MODE_SPLIT
    private var loadGeneration = 0

    // ---- Zoom / pan state ----
    private var zoomEnabled = true
    private var scaleFactor = MIN_ZOOM
    private var panX = 0f
    private var panY = 0f
    /** Base (unzoomed) destination rect of the bitmap, from the last draw. */
    private val lastDest = RectF()
    private var zoomAnimator: ValueAnimator? = null

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            zoomAnimator?.cancel()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!zoomEnabled) return false
            val prevScale = scaleFactor
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val factor = scaleFactor / prevScale
            if (factor != 1f) {
                // Keep the focal point under the fingers while scaling.
                val cx = width / 2f
                val cy = height / 2f
                panX = detector.focusX - cx - factor * (detector.focusX - cx - panX)
                panY = detector.focusY - cy - factor * (detector.focusY - cy - panY)
                clampPan()
                invalidate()
            }
            return true
        }
    }
    private val scaleDetector = ScaleGestureDetector(context, scaleListener)

    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00BFA5.toInt()
        style = Paint.Style.FILL
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2.5f * resources.displayMetrics.density
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val placeholderPaint = Paint().apply {
        color = 0xFFD9DEE6.toInt()
        style = Paint.Style.FILL
    }
    private val placeholderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5B6472.toInt()
        textSize = 14f * resources.displayMetrics.density
    }
    private val clipPath = Path()
    private val clipRect = RectF()

    /** Show [original] vs [compressed]. Resets any zoom. */
    fun setImages(original: File, compressed: File) {
        originalFile = original
        compressedFile = compressed
        viewMode = MODE_SPLIT
        dividerFraction = 0.5f
        resetZoomInstant()
        loadBitmaps()
    }

    /** Keep the same original, swap in a different result file. Resets any zoom. */
    fun setCompressedFile(compressed: File) {
        if (compressed == compressedFile) return
        compressedFile = compressed
        resetZoomInstant()
        loadBitmaps()
    }

    fun setDividerFraction(fraction: Float) {
        dividerFraction = fraction.coerceIn(0.05f, 0.95f)
        invalidate()
    }

    /**
     * Enable or disable pinch-to-zoom. Disabling resets any zoom
     * in progress. Enabled by default.
     */
    fun setZoomEnabled(enabled: Boolean) {
        zoomEnabled = enabled
        if (!enabled) resetZoomInstant()
    }

    /**
     * Smoothly animate the zoom back to 1x and re-centre the image.
     * A no-op when already at 1x.
     */
    fun resetZoom() {
        zoomAnimator?.cancel()
        if (scaleFactor <= MIN_ZOOM + 0.001f && panX == 0f && panY == 0f) return
        val startScale = scaleFactor
        val startPanX = panX
        val startPanY = panY
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_RESET_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                scaleFactor = startScale + (MIN_ZOOM - startScale) * t
                panX = startPanX * (1f - t)
                panY = startPanY * (1f - t)
                invalidate()
            }
            start()
        }
    }

    /** Instant zoom reset, used when new images are set. */
    private fun resetZoomInstant() {
        zoomAnimator?.cancel()
        scaleFactor = MIN_ZOOM
        panX = 0f
        panY = 0f
        invalidate()
    }

    /**
     * Clamp the pan so the zoomed image always covers the view and
     * can never be dragged off-screen. At 1x the image is centred.
     */
    private fun clampPan() {
        if (scaleFactor <= MIN_ZOOM + 0.001f) {
            panX = 0f
            panY = 0f
            return
        }
        if (lastDest.isEmpty) return
        val cx = width / 2f
        val cy = height / 2f
        val s = scaleFactor
        if (lastDest.width() * s <= width) {
            panX = 0f
        } else {
            val minPanX = width - cx - (lastDest.right - cx) * s
            val maxPanX = -cx - (lastDest.left - cx) * s
            panX = panX.coerceIn(minPanX, maxPanX)
        }
        if (lastDest.height() * s <= height) {
            panY = 0f
        } else {
            val minPanY = height - cy - (lastDest.bottom - cy) * s
            val maxPanY = -cy - (lastDest.top - cy) * s
            panY = panY.coerceIn(minPanY, maxPanY)
        }
    }

    private fun loadBitmaps() {
        val generation = ++loadGeneration
        val orig = originalFile
        val comp = compressedFile
        Thread {
            val origBmp = orig?.let { decodeDownsampled(it) }
            val compBmp = comp?.let { decodeDownsampled(it) }
            post {
                if (generation != loadGeneration) {
                    origBmp?.recycle()
                    compBmp?.recycle()
                    return@post
                }
                originalBitmap?.recycle()
                compressedBitmap?.recycle()
                originalBitmap = origBmp
                compressedBitmap = compBmp
                invalidate()
            }
        }.start()
    }

    /** Decode [file] downsampled so its longest side fits
     *  MAX_PREVIEW_DIM, honouring the EXIF rotation. */
    private fun decodeDownsampled(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > MAX_PREVIEW_DIM) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            val degrees = try {
                when (ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Throwable) {
                0f
            }
            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                )
                bitmap.recycle()
                bitmap = rotated
            }
            bitmap
        } catch (_: Throwable) {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val density = resources.displayMetrics.density
        // Rounded corners (12dp) so the preview sits neatly in its card.
        clipRect.set(0f, 0f, w, h)
        clipPath.reset()
        clipPath.addRoundRect(clipRect, 12f * density, 12f * density, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(0f, 0f, w, h, placeholderPaint)

        val orig = originalBitmap
        val comp = compressedBitmap
        if (orig == null && comp == null) {
            val text = "Loading preview…"
            val tw = placeholderTextPaint.measureText(text)
            canvas.drawText(text, (w - tw) / 2f, h / 2f, placeholderTextPaint)
            canvas.restore()
            return
        }
        // Both bitmaps share the same aspect (compress never resizes),
        // so one destination rect fits both.
        val bitmap = orig ?: comp!!
        val dest = fitRect(bitmap.width, bitmap.height, w, h)
        lastDest.set(dest)

        // Zoom transform (scale about the view centre + pan) applies
        // to the images and the divider line only; pills and the
        // divider handle are drawn in screen space below so they
        // keep a constant size and stay readable while zoomed.
        val cx = w / 2f
        val cy = h / 2f
        canvas.save()
        canvas.translate(cx + panX, cy + panY)
        canvas.scale(scaleFactor, scaleFactor)
        canvas.translate(-cx, -cy)

        var dividerScreenX = -1f
        var dividerScreenY = -1f
        when (viewMode) {
            MODE_BEFORE -> {
                orig?.let { canvas.drawBitmap(it, null, dest, null) }
            }
            MODE_AFTER -> {
                comp?.let { canvas.drawBitmap(it, null, dest, null) }
            }
            else -> {
                orig?.let { canvas.drawBitmap(it, null, dest, null) }
                comp?.let { drawn ->
                    val dividerX = dest.left + dest.width() * dividerFraction
                    canvas.save()
                    // Compressed only on the right of the divider.
                    canvas.clipRect(dividerX, dest.top, dest.right, dest.bottom)
                    canvas.drawBitmap(drawn, null, dest, null)
                    canvas.restore()
                    canvas.drawLine(dividerX, dest.top, dividerX, dest.bottom, dividerPaint)
                    // Handle position in screen space (constant size).
                    dividerScreenX = cx + panX + scaleFactor * (dividerX - cx)
                    dividerScreenY = cy + panY
                }
            }
        }
        canvas.restore()

        // ---- Screen-space overlays (pills, handle, zoom badge) ----
        val zoomed = scaleFactor > MIN_ZOOM + 0.001f
        when (viewMode) {
            MODE_BEFORE -> {
                val hint = if (zoomed) "Before — double-tap to reset zoom"
                    else "Before — tap to switch"
                drawLabel(canvas, hint, 16f * density, 16f * density)
            }
            MODE_AFTER -> {
                val hint = if (zoomed) "After — double-tap to reset zoom"
                    else "After — tap to switch"
                drawLabel(canvas, hint, 16f * density, 16f * density)
            }
            else -> {
                drawLabel(canvas, "Before", 16f * density, 16f * density)
                val afterText = "After"
                val afterWidth = labelPaint.measureText(afterText)
                val afterX = minOf(dest.right, w) - afterWidth - 16f * density
                drawLabel(canvas, afterText, afterX, 16f * density)
                if (dividerScreenX >= 0f) {
                    drawDividerHandle(canvas, dividerScreenX, dividerScreenY)
                }
            }
        }
        if (zoomed) {
            val zoomText = "%.1f×".format(scaleFactor)
            val zoomWidth = labelPaint.measureText(zoomText)
            drawLabel(
                canvas, zoomText,
                w - zoomWidth - 16f * density, h - 16f * density - labelPaint.fontMetrics.run { bottom - top }
            )
        }
        canvas.restore()
    }

    /** Largest rect of the bitmap's aspect that fits inside w x h. */
    private fun fitRect(bw: Int, bh: Int, w: Float, h: Float): RectF {
        val scale = minOf(w / bw, h / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        val density = resources.displayMetrics.density
        val padH = 10f * density
        val padV = 6f * density
        val textWidth = labelPaint.measureText(text)
        val textHeight = labelPaint.fontMetrics.run { bottom - top }
        val bg = RectF(
            x - padH, y - padV,
            x + textWidth + padH, y + textHeight + padV
        )
        canvas.drawRoundRect(bg, 8f * density, 8f * density, labelBgPaint)
        canvas.drawText(text, x, y - labelPaint.fontMetrics.top, labelPaint)
    }

    /** Teal circular handle with ‹ › chevrons, drawn at a constant
     *  screen size so it never balloons while zoomed. Skipped when
     *  the divider is off-screen. */
    private fun drawDividerHandle(canvas: Canvas, x: Float, cy: Float) {
        val density = resources.displayMetrics.density
        if (x < -24f * density || x > width + 24f * density) return
        val radius = 20f * density
        canvas.drawCircle(x, cy, radius, handlePaint)
        canvas.drawCircle(x, cy, radius, handleBorderPaint)
        // Two small chevrons (‹ ›) inside the handle.
        val s = 6f * density
        val gap = 4f * density
        val path = Path()
        // Left chevron.
        path.moveTo(x - gap - s / 2, cy - s)
        path.lineTo(x - gap - s, cy)
        path.lineTo(x - gap - s / 2, cy + s)
        // Right chevron.
        path.moveTo(x + gap + s / 2, cy - s)
        path.lineTo(x + gap + s, cy)
        path.lineTo(x + gap + s / 2, cy + s)
        canvas.drawPath(path, chevronPaint)
    }

    private var downX = 0f
    private var downY = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var dragging = false
    private var panning = false
    private var pinched = false
    private var lastTapUpTime = 0L

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (zoomEnabled) {
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) {
                pinched = true
                dragging = false
                panning = false
                return true
            }
        }
        val density = resources.displayMetrics.density
        val slop = 12f * density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zoomAnimator?.cancel()
                downX = event.x
                downY = event.y
                lastPanX = event.x
                lastPanY = event.y
                dragging = false
                panning = false
                // Don't disallow intercept yet: a vertical drag here
                // should still scroll the results list. Only a
                // horizontal drag (below) claims the gesture.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleFactor > MIN_ZOOM + 0.001f) {
                    // Zoomed: one-finger drag pans the image.
                    if (!panning &&
                        (abs(event.x - downX) > slop || abs(event.y - downY) > slop)
                    ) {
                        panning = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (panning) {
                        panX += event.x - lastPanX
                        panY += event.y - lastPanY
                        clampPan()
                        invalidate()
                    }
                    lastPanX = event.x
                    lastPanY = event.y
                    return true
                }
                if (!dragging && abs(event.x - downX) > slop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    viewMode = MODE_SPLIT
                    dividerFraction = (event.x / width).coerceIn(0.05f, 0.95f)
                    invalidate()
                }
                lastPanX = event.x
                lastPanY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragging && !panning && !pinched &&
                    event.actionMasked == MotionEvent.ACTION_UP
                ) {
                    if (scaleFactor > MIN_ZOOM + 0.001f) {
                        // Zoomed: a tap does nothing; a double-tap
                        // smoothly resets the zoom.
                        val now = SystemClock.uptimeMillis()
                        if (now - lastTapUpTime < DOUBLE_TAP_TIMEOUT_MS) {
                            resetZoom()
                            lastTapUpTime = 0L
                        } else {
                            lastTapUpTime = now
                        }
                    } else {
                        // A tap cycles: split -> Before -> After -> split.
                        viewMode = when (viewMode) {
                            MODE_SPLIT -> MODE_BEFORE
                            MODE_BEFORE -> MODE_AFTER
                            else -> MODE_SPLIT
                        }
                        invalidate()
                    }
                }
                dragging = false
                panning = false
                pinched = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        zoomAnimator?.cancel()
        loadGeneration++
        originalBitmap?.recycle()
        compressedBitmap?.recycle()
        originalBitmap = null
        compressedBitmap = null
    }
}
