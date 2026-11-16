package com.hakunaa.app.image

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
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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
    }

    private var originalFile: File? = null
    private var compressedFile: File? = null
    private var originalBitmap: Bitmap? = null
    private var compressedBitmap: Bitmap? = null
    private var dividerFraction = 0.5f
    private var viewMode = MODE_SPLIT
    private var loadGeneration = 0

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

    /** Show [original] vs [compressed]. */
    fun setImages(original: File, compressed: File) {
        originalFile = original
        compressedFile = compressed
        viewMode = MODE_SPLIT
        dividerFraction = 0.5f
        loadBitmaps()
    }

    /** Keep the same original, swap in a different result file. */
    fun setCompressedFile(compressed: File) {
        if (compressed == compressedFile) return
        compressedFile = compressed
        loadBitmaps()
    }

    fun setDividerFraction(fraction: Float) {
        dividerFraction = fraction.coerceIn(0.05f, 0.95f)
        invalidate()
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
        when (viewMode) {
            MODE_BEFORE -> {
                orig?.let { canvas.drawBitmap(it, null, dest, null) }
                drawLabel(canvas, "Before — tap to switch", 16f * density, 16f * density)
            }
            MODE_AFTER -> {
                comp?.let { canvas.drawBitmap(it, null, dest, null) }
                drawLabel(canvas, "After — tap to switch", 16f * density, 16f * density)
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
                    drawDivider(canvas, dividerX, dest.top, dest.bottom)
                }
                drawLabel(canvas, "Before", 16f * density, 16f * density)
                val afterText = "After"
                val afterWidth = labelPaint.measureText(afterText)
                drawLabel(
                    canvas, afterText,
                    dest.right - afterWidth - 32f * density, 16f * density
                )
            }
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

    private fun drawDivider(canvas: Canvas, x: Float, top: Float, bottom: Float) {
        val density = resources.displayMetrics.density
        canvas.drawLine(x, top, x, bottom, dividerPaint)
        val cy = (top + bottom) / 2f
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
    private var dragging = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val slop = 12f * density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                // Don't disallow intercept yet: a vertical drag here
                // should still scroll the results list. Only a
                // horizontal drag (below) claims the gesture.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(event.x - downX) > slop) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    viewMode = MODE_SPLIT
                    dividerFraction = (event.x / width).coerceIn(0.05f, 0.95f)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    // A tap cycles: split -> Before -> After -> split.
                    viewMode = when (viewMode) {
                        MODE_SPLIT -> MODE_BEFORE
                        MODE_BEFORE -> MODE_AFTER
                        else -> MODE_SPLIT
                    }
                    invalidate()
                }
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loadGeneration++
        originalBitmap?.recycle()
        compressedBitmap?.recycle()
        originalBitmap = null
        compressedBitmap = null
    }
}
