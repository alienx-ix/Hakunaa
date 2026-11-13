package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Crop view — the CamScanner-style crop screen.
 *
 * Shows the photo with a teal quadrilateral frame on top. The user
 * drags the four corner handles (white circles with a teal ring)
 * or the edge pill handles to adjust the crop. The dimmed area
 * outside the frame shows what will be cut away.
 *
 * Corners are stored in bitmap coordinates (top-left, top-right,
 * bottom-right, bottom-left) and mapped to the view for drawing
 * and touch handling.
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var bitmap: Bitmap? = null
    /** Corners in bitmap coordinates: TL, TR, BR, BL. */
    private val corners = FloatArray(8)
    private var hasCorners = false

    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#80000000")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val edgeHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    /** Maps bitmap coordinates to view coordinates. */
    private val bitmapToView = Matrix()
    private val viewToBitmap = Matrix()

    private var activeHandle = -1
    private val touchRadius = 90f

    fun setBitmap(bmp: Bitmap) {
        bitmap = bmp
        // Default corners: the full frame, inset slightly.
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        corners[0] = w * 0.05f; corners[1] = h * 0.05f
        corners[2] = w * 0.95f; corners[3] = h * 0.05f
        corners[4] = w * 0.95f; corners[5] = h * 0.95f
        corners[6] = w * 0.05f; corners[7] = h * 0.95f
        hasCorners = true
        updateMatrix()
        invalidate()
    }

    fun setCorners(c: FloatArray) {
        if (c.size != 8) return
        c.copyInto(corners)
        // Order the corners TL / TR / BR / BL (v0.1.65): the
        // detector can hand back a crossed (bowtie) quad on a
        // busy patterned background, and a bowtie frame is
        // undraggable nonsense — reordering the same four
        // points by sum/difference always gives a valid quad.
        orderCorners(corners)
        // Keep every handle on screen (v0.1.55, raised to 4%
        // in v0.1.58): a corner on the very edge of the photo
        // puts its circle half off the screen, where a finger
        // cannot press and drag it (the user hit this on the
        // right side of a book photo, then on BOTH sides — the
        // left and right edges were not visible at all). The
        // 2% inset was not enough: on a 3000px photo it is
        // only ~18px on screen, less than the 26px handle
        // radius, so the circle still hung half off-screen.
        // 4% puts the whole circle inside on every side, so
        // the user can press and crop; they can still drag a
        // corner back out to the edge if they want the full page.
        val bmp = bitmap
        if (bmp != null) {
            val mx = bmp.width * 0.04f
            val my = bmp.height * 0.04f
            for (i in 0 until 4) {
                corners[i * 2] = corners[i * 2].coerceIn(mx, bmp.width - mx)
                corners[i * 2 + 1] = corners[i * 2 + 1].coerceIn(my, bmp.height - my)
            }
        }
        hasCorners = true
        invalidate()
    }

    fun getCorners(): FloatArray = corners.copyOf()

    companion object {
        /** Sort 4 points into TL, TR, BR, BL order (v0.1.65).
         *  TL = smallest x+y, BR = largest x+y, TR = largest
         *  x-y, BL = smallest x-y. Any 4 points reordered this
         *  way form a non-crossing quad. */
        fun orderCorners(pts: FloatArray) {
            if (pts.size != 8) return
            val tl = (0 until 4).minByOrNull { pts[it * 2] + pts[it * 2 + 1] }!!
            val br = (0 until 4).maxByOrNull { pts[it * 2] + pts[it * 2 + 1] }!!
            val tr = (0 until 4).maxByOrNull { pts[it * 2] - pts[it * 2 + 1] }!!
            val bl = (0 until 4).minByOrNull { pts[it * 2] - pts[it * 2 + 1] }!!
            val ordered = floatArrayOf(
                pts[tl * 2], pts[tl * 2 + 1],
                pts[tr * 2], pts[tr * 2 + 1],
                pts[br * 2], pts[br * 2 + 1],
                pts[bl * 2], pts[bl * 2 + 1]
            )
            ordered.copyInto(pts)
        }
    }

    private fun updateMatrix() {
        val bmp = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f
        bitmapToView.reset()
        bitmapToView.postScale(scale, scale)
        bitmapToView.postTranslate(dx, dy)
        bitmapToView.invert(viewToBitmap)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, bitmapToView, null)
        if (!hasCorners) return

        val pts = FloatArray(8)
        bitmapToView.mapPoints(pts, corners)

        val path = Path()
        path.moveTo(pts[0], pts[1])
        path.lineTo(pts[2], pts[3])
        path.lineTo(pts[4], pts[5])
        path.lineTo(pts[6], pts[7])
        path.close()

        // Dim everything outside the quadrilateral.
        val dimPath = Path()
        dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        dimPath.addPath(path)
        dimPath.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(dimPath, dimPaint)

        // Teal frame.
        canvas.drawPath(path, framePaint)

        // Corner handles: white circles with a teal ring.
        for (i in 0 until 4) {
            val x = pts[i * 2]
            val y = pts[i * 2 + 1]
            canvas.drawCircle(x, y, 26f, handlePaint)
            canvas.drawCircle(x, y, 26f, handleRingPaint)
        }
        // Edge pill handles (midpoint of each edge).
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val mx = (pts[i * 2] + pts[j * 2]) / 2f
            val my = (pts[i * 2 + 1] + pts[j * 2 + 1]) / 2f
            canvas.drawRoundRect(mx - 28f, my - 12f, mx + 28f, my + 12f, 12f, 12f, edgeHandlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasCorners) return false
        val pts = FloatArray(8)
        bitmapToView.mapPoints(pts, corners)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = -1
                var best = Float.MAX_VALUE
                for (i in 0 until 4) {
                    val d = hypot(event.x - pts[i * 2], event.y - pts[i * 2 + 1])
                    if (d < touchRadius && d < best) {
                        best = d
                        activeHandle = i
                    }
                }
                // Edge handle: move the whole edge (both corners).
                if (activeHandle == -1) {
                    for (i in 0 until 4) {
                        val j = (i + 1) % 4
                        val mx = (pts[i * 2] + pts[j * 2]) / 2f
                        val my = (pts[i * 2 + 1] + pts[j * 2 + 1]) / 2f
                        val d = hypot(event.x - mx, event.y - my)
                        if (d < touchRadius) {
                            activeHandle = 10 + i
                            break
                        }
                    }
                }
                return activeHandle != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == -1) return false
                val bmp = bitmap ?: return false
                val bpt = floatArrayOf(event.x, event.y)
                viewToBitmap.mapPoints(bpt, bpt)
                val bx = bpt[0].coerceIn(0f, bmp.width.toFloat())
                val by = bpt[1].coerceIn(0f, bmp.height.toFloat())
                if (activeHandle < 4) {
                    corners[activeHandle * 2] = bx
                    corners[activeHandle * 2 + 1] = by
                } else {
                    // Move the whole edge: shift both corners by the delta.
                    val i = activeHandle - 10
                    val j = (i + 1) % 4
                    val oldMx = (corners[i * 2] + corners[j * 2]) / 2f
                    val oldMy = (corners[i * 2 + 1] + corners[j * 2 + 1]) / 2f
                    val dx = bx - oldMx
                    val dy = by - oldMy
                    corners[i * 2] = (corners[i * 2] + dx).coerceIn(0f, bmp.width.toFloat())
                    corners[i * 2 + 1] = (corners[i * 2 + 1] + dy).coerceIn(0f, bmp.height.toFloat())
                    corners[j * 2] = (corners[j * 2] + dx).coerceIn(0f, bmp.width.toFloat())
                    corners[j * 2 + 1] = (corners[j * 2 + 1] + dy).coerceIn(0f, bmp.height.toFloat())
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = -1
                return true
            }
        }
        return false
    }
}
