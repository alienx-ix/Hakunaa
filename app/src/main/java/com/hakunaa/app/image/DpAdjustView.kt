package com.hakunaa.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs

/**
 * DP adjust view (v0.1.124) — the professional adjust step for DP
 * Crop. The photo sits inside a square frame (Circle masks it to
 * a circle); pinch to zoom (1x–4x over fit), drag to move, double-
 * tap to reset. The outer area (Blur / Color / Transparent) is
 * drawn live around the photo, exactly as the saved DP will look.
 *
 * The activity reads the transform back as resolution-independent
 * fractions ([userScale] relative to fit, [userDx]/[userDy] as
 * fractions of the frame) and renders full-res with
 * [ImageCompressor.dpAdjust]. The bitmap is owned by the caller —
 * this view never recycles it.
 */
class DpAdjustView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MAX_ZOOM = 4f
    }

    private var bitmap: Bitmap? = null
    private var blurredBg: Bitmap? = null
    private var circle = false
    private var background = ImageCompressor.DpBackground.BLUR
    private var bgColor = 0xFFFFFFFF.toInt()

    private val userMatrix = Matrix()
    private val fitMatrix = Matrix()
    private var fitScale = 1f
    private var scaleFactor = 1f

    private val frameRect = RectF()
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF00BFA5.toInt()
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val checkerLight = Paint().apply { color = 0xFFFFFFFF.toInt() }
    private val checkerGrey = Paint().apply { color = 0xFFE8EAED.toInt() }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(170, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 13f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    fun setImage(bmp: Bitmap?, circle: Boolean, background: ImageCompressor.DpBackground, color: Int) {
        bitmap = bmp
        this.circle = circle
        this.background = background
        bgColor = color
        blurredBg = null
        scaleFactor = 1f
        updateFitMatrix()
        invalidate()
    }

    fun setBackgroundChoice(background: ImageCompressor.DpBackground, color: Int) {
        this.background = background
        bgColor = color
        blurredBg = null
        invalidate()
    }

    /** Zoom over fit (1x = fitted whole photo). */
    val userScale: Float
        get() = scaleFactor

    /** Photo-centre offset from the frame centre, as a fraction of the frame size. */
    val userDx: Float
        get() {
            val bmp = bitmap ?: return 0f
            val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            userMatrix.mapRect(rect)
            return if (frameRect.width() > 0) (rect.centerX() - frameRect.centerX()) / frameRect.width() else 0f
        }

    val userDy: Float
        get() {
            val bmp = bitmap ?: return 0f
            val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
            userMatrix.mapRect(rect)
            return if (frameRect.height() > 0) (rect.centerY() - frameRect.centerY()) / frameRect.height() else 0f
        }

    /** Fit the bitmap inside the square frame, centred. */
    private fun updateFitMatrix() {
        val bmp = bitmap ?: return
        if (width <= 0 || height <= 0) return
        frameRect.set(0f, 0f, width.toFloat(), height.toFloat())
        fitScale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
        val dx = (width - bmp.width * fitScale) / 2f
        val dy = (height - bmp.height * fitScale) / 2f
        fitMatrix.reset()
        fitMatrix.postScale(fitScale, fitScale)
        fitMatrix.postTranslate(dx, dy)
        userMatrix.set(fitMatrix)
        scaleFactor = 1f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        frameRect.set(0f, 0f, w.toFloat(), h.toFloat())
        blurredBg = null
        // Re-fit only when the user has not zoomed (a rotation
        // mid-adjust keeps their transform).
        if (scaleFactor <= 1.001f) updateFitMatrix()
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

    /** Keep the photo centre inside the frame — the photo can never
     *  slide away entirely, and at 1x it stays fitted. */
    private fun clampPan() {
        val bmp = bitmap ?: return
        val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        userMatrix.mapRect(rect)
        var dx = 0f
        var dy = 0f
        val cx = rect.centerX()
        val cy = rect.centerY()
        if (cx < frameRect.left) dx = frameRect.left - cx
        else if (cx > frameRect.right) dx = frameRect.right - cx
        if (cy < frameRect.top) dy = frameRect.top - cy
        else if (cy > frameRect.bottom) dy = frameRect.bottom - cy
        if (dx != 0f || dy != 0f) userMatrix.postTranslate(dx, dy)
    }

    private fun resetToFit() {
        userMatrix.set(fitMatrix)
        scaleFactor = 1f
        invalidate()
    }

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val bmp = bitmap ?: return true
            if (fitScale <= 0f) return true
            val values = FloatArray(9)
            userMatrix.getValues(values)
            val cur = values[Matrix.MSCALE_X]
            val next = (cur * detector.scaleFactor).coerceIn(fitScale, fitScale * MAX_ZOOM)
            val factor = next / cur
            if (factor != 1f) {
                userMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                scaleFactor = next / fitScale
                clampPan()
                invalidate()
            }
            return true
        }
    }
    private val scaleDetector = ScaleGestureDetector(context, scaleListener)

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetToFit()
            return true
        }
    }
    private val gestureDetector = GestureDetector(context, gestureListener)

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
                lastX = event.x
                lastY = event.y
                dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                if (!dragging && (abs(dx) > 8f || abs(dy) > 8f)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    userMatrix.postTranslate(dx, dy)
                    clampPan()
                    invalidate()
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

    private fun ensureBlurredBg() {
        val bmp = bitmap ?: return
        if (blurredBg != null || width <= 0 || height <= 0) return
        val smallH = (32f * height / width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, 32, smallH, true)
        blurredBg = Bitmap.createScaledBitmap(small, width, height, true)
        small.recycle()
    }

    private fun drawCheckerboard(canvas: Canvas) {
        val tile = (16 * resources.displayMetrics.density).toInt().coerceAtLeast(4)
        var y = 0f
        var row = 0
        while (y < frameRect.bottom) {
            var x = 0f
            var col = row
            while (x < frameRect.right) {
                canvas.drawRect(
                    x, y,
                    minOf(x + tile, frameRect.right),
                    minOf(y + tile, frameRect.bottom),
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
        // Outer area — exactly as the saved DP will look.
        when (background) {
            ImageCompressor.DpBackground.BLUR -> {
                ensureBlurredBg()
                blurredBg?.let { canvas.drawBitmap(it, 0f, 0f, null) }
                    ?: canvas.drawColor(0xFFE8EAED.toInt())
            }
            ImageCompressor.DpBackground.COLOR -> canvas.drawColor(bgColor)
            ImageCompressor.DpBackground.TRANSPARENT -> drawCheckerboard(canvas)
        }
        // Photo, clipped to the circle in Circle mode.
        if (circle) {
            val path = Path()
            path.addCircle(
                frameRect.centerX(), frameRect.centerY(),
                frameRect.width() / 2f, Path.Direction.CW
            )
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(bmp, userMatrix, photoPaint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bmp, userMatrix, photoPaint)
        }
        // Frame outline.
        if (circle) {
            canvas.drawCircle(
                frameRect.centerX(), frameRect.centerY(),
                frameRect.width() / 2f - outlinePaint.strokeWidth / 2f,
                outlinePaint
            )
        } else {
            canvas.drawRect(
                frameRect.left + outlinePaint.strokeWidth / 2f,
                frameRect.top + outlinePaint.strokeWidth / 2f,
                frameRect.right - outlinePaint.strokeWidth / 2f,
                frameRect.bottom - outlinePaint.strokeWidth / 2f,
                outlinePaint
            )
        }
        // Zoom badge while zoomed.
        if (scaleFactor > 1.001f) {
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
}
