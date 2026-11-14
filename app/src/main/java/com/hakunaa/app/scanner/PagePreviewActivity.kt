package com.hakunaa.app.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import com.hakunaa.app.core.StatusBar

/**
 * Full-screen page preview — tap a page in the Pages screen
 * and it opens here, so the user can read it and check the
 * scan before saving the PDF.
 *
 * Pinch to zoom (up to 4x), drag to pan when zoomed,
 * double-tap to toggle between fit and 2x. The bitmap is
 * downsampled to ~2048 px on the long side, so a full-res
 * phone photo never blows up memory — and 2048 px still
 * stays sharp at 2x on a phone screen.
 */
class PagePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE_PATH = "page_path"
        const val EXTRA_PAGE_LABEL = "page_label"
        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }

    private lateinit var imageView: ImageView
    private val matrix = Matrix()
    private val savedMatrix = Matrix()
    private var mode = NONE
    private val start = PointF()
    private val mid = PointF()
    private var oldDist = 1f
    private var minScale = 1f
    private var bitmap: Bitmap? = null

    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_page_preview)

        // Status bar (v0.1.97): keep it visible, no overlap.
        StatusBar.setup(this, false)
        val path = intent.getStringExtra(EXTRA_PAGE_PATH)
        val label = intent.getStringExtra(EXTRA_PAGE_LABEL) ?: "Page"
        if (path.isNullOrEmpty()) {
            Toast.makeText(this, "Page not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.tvTitle).text = label
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        imageView = findViewById(R.id.ivPage)
        imageView.scaleType = ImageView.ScaleType.MATRIX

        scaleDetector = ScaleGestureDetector(this, ScaleListener())
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom(e.x, e.y)
                return true
            }
        })

        imageView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(matrix)
                    start.set(event.x, event.y)
                    mode = DRAG
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    oldDist = spacing(event)
                    if (oldDist > 10f) {
                        savedMatrix.set(matrix)
                        midPoint(mid, event)
                        mode = ZOOM
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    mode = NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == DRAG) {
                        matrix.set(savedMatrix)
                        matrix.postTranslate(event.x - start.x, event.y - start.y)
                        clampAndApply()
                    }
                }
            }
            true
        }

        Thread {
            try {
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(path, options)
                var sample = 1
                val maxSide = max(options.outWidth, options.outHeight)
                while (maxSide / sample > 2048) sample *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                val bmp = BitmapFactory.decodeFile(path, options)
                runOnUiThread {
                    if (bmp == null) {
                        Toast.makeText(this, "Could not load page", Toast.LENGTH_SHORT).show()
                        finish()
                        return@runOnUiThread
                    }
                    bitmap = bmp
                    imageView.setImageBitmap(bmp)
                    imageView.post { fitToScreen() }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Could not load page", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        bitmap?.recycle()
        super.onDestroy()
    }

    /** Fit the whole page into the screen (the initial view). */
    private fun fitToScreen() {
        val bmp = bitmap ?: return
        val vw = imageView.width.toFloat()
        val vh = imageView.height.toFloat()
        if (vw == 0f || vh == 0f) return
        val scale = min(vw / bmp.width, vh / bmp.height)
        minScale = scale
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate((vw - bmp.width * scale) / 2f, (vh - bmp.height * scale) / 2f)
        imageView.imageMatrix = matrix
    }

    private fun currentScale(): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun toggleZoom(x: Float, y: Float) {
        val target = if (currentScale() < minScale * 1.5f) minScale * 2f else minScale
        val factor = target / currentScale()
        matrix.postScale(factor, factor, x, y)
        clampAndApply()
    }

    /** Keep the page inside the view (and the zoom in range),
     *  then apply the matrix. */
    private fun clampAndApply() {
        val bmp = bitmap ?: return
        val values = FloatArray(9)
        matrix.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        // Clamp the zoom: never smaller than fit, never past 4x.
        if (scale < minScale) {
            val f = minScale / scale
            matrix.postScale(f, f, imageView.width / 2f, imageView.height / 2f)
        } else if (scale > minScale * 4f) {
            val f = (minScale * 4f) / scale
            matrix.postScale(f, f, imageView.width / 2f, imageView.height / 2f)
        }
        // Clamp the pan: the page must always cover the screen
        // (or sit centred when it is smaller than the screen).
        val rect = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        matrix.mapRect(rect)
        val vw = imageView.width.toFloat()
        val vh = imageView.height.toFloat()
        var dx = 0f
        var dy = 0f
        if (rect.width() <= vw) {
            dx = (vw - rect.width()) / 2f - rect.left
        } else {
            if (rect.left > 0f) dx = -rect.left
            else if (rect.right < vw) dx = vw - rect.right
        }
        if (rect.height() <= vh) {
            dy = (vh - rect.height()) / 2f - rect.top
        } else {
            if (rect.top > 0f) dy = -rect.top
            else if (rect.bottom < vh) dy = vh - rect.bottom
        }
        matrix.postTranslate(dx, dy)
        imageView.imageMatrix = matrix
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            matrix.postScale(
                detector.scaleFactor, detector.scaleFactor,
                detector.focusX, detector.focusY
            )
            clampAndApply()
            return true
        }
    }

    private fun spacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return sqrt(x * x + y * y)
    }

    private fun midPoint(point: PointF, event: MotionEvent) {
        point.set((event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
    }
}
