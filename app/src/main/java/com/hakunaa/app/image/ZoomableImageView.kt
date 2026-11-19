package com.hakunaa.app.image

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.abs

/**
 * A single zoomable image (v0.1.121) — used by the full-screen
 * preview dialog (Preview mode and both halves of Compare mode).
 *
 * Pinch to zoom (1x–4x), one-finger drag to pan while zoomed,
 * double-tap to smoothly reset. A small "N.N×" badge appears
 * while zoomed. Bitmaps are decoded downsampled (longest side
 * 1600px) on a background thread, honouring EXIF rotation.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 4f
        private const val MAX_PREVIEW_DIM = 1600
        private const val ZOOM_RESET_DURATION_MS = 250L
    }

    private var bitmap: Bitmap? = null
    private var loadGeneration = 0
    private val drawMatrix = Matrix()
    private var scaleFactor = MIN_ZOOM
    private var zoomAnimator: ValueAnimator? = null

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            zoomAnimator?.cancel()
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prev = scaleFactor
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val factor = scaleFactor / prev
            if (factor != 1f) {
                drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampPan()
                imageMatrix = drawMatrix
                invalidate()
            }
            return true
        }
    }
    private val scaleDetector = ScaleGestureDetector(context, scaleListener)

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetZoom()
            return true
        }
    }
    private val gestureDetector = GestureDetector(context, gestureListener)

    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 13f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    init {
        scaleType = ScaleType.MATRIX
    }

    /** Load [file] downsampled, honouring EXIF rotation. */
    fun setImageFile(file: File) {
        val generation = ++loadGeneration
        Thread {
            val bmp = decodeDownsampled(file)
            post {
                if (generation != loadGeneration) {
                    bmp?.recycle()
                    return@post
                }
                bitmap?.recycle()
                bitmap = bmp
                resetZoomInstant()
                updateBaseMatrix()
                setImageBitmap(bmp)
                invalidate()
            }
        }.start()
    }

    private fun decodeDownsampled(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > MAX_PREVIEW_DIM) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
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
                val m = Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                bmp.recycle()
                bmp = rotated
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** Fit the bitmap inside the view (centred). Called when the
     *  bitmap arrives or the size changes. */
    private fun updateBaseMatrix() {
        val bmp = bitmap ?: return
        if (width <= 0 || height <= 0) return
        val scale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The bitmap may have arrived before layout — fit it now.
        if (bitmap != null && scaleFactor <= MIN_ZOOM + 0.001f) {
            updateBaseMatrix()
        }
    }

    /** Clamp the pan so the zoomed image always covers the view. */
    private fun clampPan() {
        val bmp = bitmap ?: return
        if (scaleFactor <= MIN_ZOOM + 0.001f) {
            updateBaseMatrix()
            return
        }
        val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        drawMatrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        if (rect.width() <= width) {
            dx = (width - rect.width()) / 2f - rect.left
        } else if (rect.left > 0f) {
            dx = -rect.left
        } else if (rect.right < width) {
            dx = width - rect.right
        }
        if (rect.height() <= height) {
            dy = (height - rect.height()) / 2f - rect.top
        } else if (rect.top > 0f) {
            dy = -rect.top
        } else if (rect.bottom < height) {
            dy = height - rect.bottom
        }
        if (dx != 0f || dy != 0f) {
            drawMatrix.postTranslate(dx, dy)
        }
    }

    /** Smoothly animate the zoom back to 1x and re-centre. */
    fun resetZoom() {
        zoomAnimator?.cancel()
        if (scaleFactor <= MIN_ZOOM + 0.001f) {
            updateBaseMatrix()
            return
        }
        val values = FloatArray(9)
        drawMatrix.getValues(values)
        val startScale = scaleFactor
        val startTx = values[Matrix.MTRANS_X]
        val startTy = values[Matrix.MTRANS_Y]
        val bmp = bitmap ?: return
        val fitScale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val fitTx = (width - bmp.width * fitScale) / 2f
        val fitTy = (height - bmp.height * fitScale) / 2f
        val startMatrixScale = values[Matrix.MSCALE_X]
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_RESET_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                scaleFactor = startScale + (MIN_ZOOM - startScale) * t
                val s = startMatrixScale + (fitScale - startMatrixScale) * t
                val tx = startTx + (fitTx - startTx) * t
                val ty = startTy + (fitTy - startTy) * t
                drawMatrix.reset()
                drawMatrix.postScale(s, s)
                drawMatrix.postTranslate(tx, ty)
                imageMatrix = drawMatrix
                invalidate()
            }
            start()
        }
    }

    private fun resetZoomInstant() {
        zoomAnimator?.cancel()
        scaleFactor = MIN_ZOOM
        drawMatrix.reset()
        imageMatrix = drawMatrix
    }

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            dragging = false
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                zoomAnimator?.cancel()
                lastX = event.x
                lastY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleFactor > MIN_ZOOM + 0.001f) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (!dragging && (abs(dx) > 8f || abs(dy) > 8f)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        drawMatrix.postTranslate(dx, dy)
                        clampPan()
                        imageMatrix = drawMatrix
                        invalidate()
                    }
                    lastX = event.x
                    lastY = event.y
                    return true
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (scaleFactor > MIN_ZOOM + 0.001f) {
            val density = resources.displayMetrics.density
            val text = "%.1f×".format(scaleFactor)
            val textWidth = badgePaint.measureText(text)
            val fm = badgePaint.fontMetrics
            val textHeight = fm.bottom - fm.top
            val padH = 10f * density
            val padV = 6f * density
            val x = width - textWidth - 16f * density
            val y = height - 16f * density - textHeight
            canvas.drawRoundRect(
                x - padH, y - padV,
                x + textWidth + padH, y + textHeight + padV,
                8f * density, 8f * density, badgeBgPaint
            )
            canvas.drawText(text, x, y - fm.top, badgePaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        zoomAnimator?.cancel()
        loadGeneration++
        bitmap?.recycle()
        bitmap = null
    }
}
