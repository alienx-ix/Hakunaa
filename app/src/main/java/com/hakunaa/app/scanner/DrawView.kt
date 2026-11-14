package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Freehand drawing overlay — used for Draw (markup on the
 * page) and for the signature pad.
 *
 * Strokes are stored in NORMALIZED coordinates (0..1 relative
 * to the content width / height), so the same strokes map
 * correctly to the on-screen preview and to the full-res
 * bitmap at save time, whatever its size. The stroke width is
 * stored the same way (as a fraction of the content width).
 *
 * The content is shown fitCenter inside this view (the same
 * as the page ImageView), so a touch is mapped through the
 * displayed rect: view → normalized → bitmap pixels.
 */
class DrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** One freehand stroke: normalized points + color + width. */
    data class Stroke(
        val points: MutableList<PointF>,
        val color: Int,
        /** Stroke width as a fraction of the content width. */
        val widthNorm: Float
    )

    private val strokes = mutableListOf<Stroke>()
    private var currentStroke: Stroke? = null

    /** Content (bitmap / pad) size — the coordinate space. */
    private var contentW = 0
    private var contentH = 0

    var drawColor: Int = Color.BLACK
    /** Stroke width in view pixels (set by the caller). */
    var strokeWidthPx: Float = 8f

    /** When false the view only SHOWS the strokes and never
     *  takes a touch — so the drawing stays visible on the
     *  page after Done, while swipes reach the page below.
     *  (v0.1.83 fix: hiding the view on Done made the
     *  drawing vanish from the screen, so the user thought
     *  it was lost.) */
    var drawEnabled: Boolean = false
    /** Erase mode (v0.1.83): a touch deletes the stroke it
     *  lands on, instead of drawing a new one. */
    var eraseMode: Boolean = false
    /** Eraser radius in view pixels. */
    var eraseRadiusPx: Float = 40f

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Tell the view the content size (the page bitmap, or the
     *  pad itself). Until this is set, touches are ignored. */
    fun setContentSize(w: Int, h: Int) {
        contentW = w
        contentH = h
        invalidate()
    }

    /** Zoom state (v0.1.83): when the page is zoomed, the
     *  strokes are drawn at the zoomed position, so they stay
     *  on the page while the user inspects it. Set from the
     *  activity's zoom; 1f = no zoom. */
    var zoomScale: Float = 1f
    var zoomFocusX: Float = 0f
    var zoomFocusY: Float = 0f
    var zoomPanX: Float = 0f
    var zoomPanY: Float = 0f

    /** The content's displayed rect inside this view
     *  (fitCenter, zoom-aware). */
    fun displayedRect(): FloatArray {
        // left, top, width, height (view pixels)
        if (contentW <= 0 || contentH <= 0 || width <= 0 || height <= 0) {
            return floatArrayOf(0f, 0f, width.toFloat(), height.toFloat())
        }
        val scale = min(width.toFloat() / contentW, height.toFloat() / contentH)
        var left = (width - contentW * scale) / 2f
        var top = (height - contentH * scale) / 2f
        var w = contentW * scale
        var h = contentH * scale
        if (zoomScale > 1f) {
            // Zoomed: scale the rect around the focal point,
            // then pan — the same transform the image gets.
            left = zoomFocusX + (left - zoomFocusX) * zoomScale + zoomPanX
            top = zoomFocusY + (top - zoomFocusY) * zoomScale + zoomPanY
            w *= zoomScale
            h *= zoomScale
        }
        return floatArrayOf(left, top, w, h)
    }

    /** View point → normalized content point, or null when the
     *  touch is outside the displayed content. */
    private fun toNormalized(x: Float, y: Float): PointF? {
        val r = displayedRect()
        val nx = (x - r[0]) / r[2]
        val ny = (y - r[1]) / r[3]
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return PointF(nx, ny)
    }

    /** Normalized point → view point. */
    private fun toView(p: PointF): PointF {
        val r = displayedRect()
        return PointF(r[0] + p.x * r[2], r[1] + p.y * r[3])
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Not in a drawing mode — show the strokes only and
        // let the touch reach the page below (swipe / zoom).
        if (!drawEnabled || contentW <= 0 || contentH <= 0) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (eraseMode) {
                    eraseAt(event.x, event.y)
                    return true
                }
                val p = toNormalized(event.x, event.y) ?: return false
                val r = displayedRect()
                val wNorm = strokeWidthPx / r[2]
                currentStroke = Stroke(mutableListOf(p), drawColor, wNorm)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (eraseMode) {
                    eraseAt(event.x, event.y)
                    return true
                }
                val stroke = currentStroke ?: return false
                val p = toNormalized(event.x, event.y) ?: return false
                // Skip points that are too close together — they
                // only add noise to the path.
                val last = stroke.points.last()
                if (kotlin.math.abs(p.x - last.x) < 0.002f &&
                    kotlin.math.abs(p.y - last.y) < 0.002f
                ) return true
                stroke.points.add(p)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val stroke = currentStroke
                if (stroke != null && stroke.points.size >= 2) {
                    strokes.add(stroke)
                }
                currentStroke = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Delete every stroke that passes within the eraser
     *  radius of the touch point. The distance is measured
     *  in view pixels, so the radius feels the same whatever
     *  the page shape is. */
    private fun eraseAt(x: Float, y: Float) {
        val r = displayedRect()
        val before = strokes.size
        strokes.removeAll { stroke ->
            stroke.points.any { pt ->
                val vx = pt.x * r[2] + r[0]
                val vy = pt.y * r[3] + r[1]
                val vdx = vx - x
                val vdy = vy - y
                kotlin.math.sqrt(vdx * vdx + vdy * vdy) <= eraseRadiusPx
            }
        }
        if (strokes.size != before) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = displayedRect()
        for (stroke in strokes) drawStroke(canvas, stroke, r)
        currentStroke?.let { drawStroke(canvas, it, r) }
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke, rect: FloatArray) {
        if (stroke.points.size < 2) return
        val path = Path()
        val first = toView(stroke.points[0])
        path.moveTo(first.x, first.y)
        for (i in 1 until stroke.points.size) {
            val p = toView(stroke.points[i])
            path.lineTo(p.x, p.y)
        }
        paint.color = stroke.color
        paint.strokeWidth = stroke.widthNorm * rect[2]
        canvas.drawPath(path, paint)
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            invalidate()
        }
    }

    fun clearAll() {
        strokes.clear()
        currentStroke = null
        invalidate()
    }

    fun hasStrokes(): Boolean = strokes.isNotEmpty() || currentStroke != null

    fun getStrokes(): List<Stroke> = strokes.toList()

    fun setStrokes(list: List<Stroke>) {
        strokes.clear()
        strokes.addAll(list.map { Stroke(it.points.toMutableList(), it.color, it.widthNorm) })
        currentStroke = null
        invalidate()
    }

    /**
     * Render the strokes onto a fresh bitmap of [outWidth] x
     * [outHeight] (transparent background). Used to composite
     * the drawing onto the saved page, and to capture a
     * signature. Returns null when there is nothing to draw.
     */
    fun renderToBitmap(outWidth: Int, outHeight: Int): Bitmap? =
        renderStrokesToBitmap(strokes, outWidth, outHeight)

    /**
     * Render the strokes cropped tight to their content (plus
     * [padPx] padding), on a transparent background — for a
     * signature. Returns null when there is nothing to draw.
     */
    fun renderCroppedBitmap(padPx: Int = 8): Bitmap? {
        if (strokes.isEmpty()) return null
        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        for (stroke in strokes) {
            for (pt in stroke.points) {
                minX = min(minX, pt.x)
                minY = min(minY, pt.y)
                maxX = max(maxX, pt.x)
                maxY = max(maxY, pt.y)
            }
        }
        if (maxX <= minX || maxY <= minY) return null
        // The crop is in the content's own coordinate space
        // (the pad), so render at the view's pixel size and
        // crop the bitmap.
        val full = renderToBitmap(width, height) ?: return null
        val left = max(0, ((minX * width) - padPx).toInt())
        val top = max(0, ((minY * height) - padPx).toInt())
        val right = min(width, ((maxX * width) + padPx).toInt())
        val bottom = min(height, ((maxY * height) + padPx).toInt())
        if (right <= left || bottom <= top) {
            full.recycle()
            return null
        }
        val cropped = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
        full.recycle()
        return cropped
    }

    /**
     * Rotate every stroke 90 degrees counter-clockwise, to
     * match a page that was rotated the same way. A point
     * (nx, ny) becomes (ny, 1 - nx); the width is re-based
     * from the old width to the new width. [oldW] / [oldH]
     * are the content size BEFORE the rotation.
     */
    fun rotateStrokesCCW(oldW: Int, oldH: Int) {
        if (oldW <= 0 || oldH <= 0) return
        val ratio = oldW.toFloat() / oldH
        val rotated = strokes.map { stroke ->
            val pts = stroke.points.map { PointF(it.y, 1f - it.x) }.toMutableList()
            Stroke(pts, stroke.color, stroke.widthNorm * ratio)
        }
        strokes.clear()
        strokes.addAll(rotated)
        currentStroke = null
        invalidate()
    }

    /** Rotate a normalized rect 90 degrees counter-clockwise. */
    companion object {
        /** [left, top, right, bottom] → rotated rect. */
        fun rotateRectCCW(rect: FloatArray): FloatArray {
            // left, top, right, bottom → top, 1-right, bottom, 1-left
            return floatArrayOf(rect[1], 1f - rect[2], rect[3], 1f - rect[0])
        }

        /** Render [strokes] onto a fresh transparent bitmap of
         *  [outWidth] x [outHeight]. Null when nothing to draw. */
        fun renderStrokesToBitmap(
            strokes: List<Stroke>,
            outWidth: Int,
            outHeight: Int
        ): Bitmap? {
            if (strokes.isEmpty()) return null
            val bmp = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val p = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            for (stroke in strokes) {
                if (stroke.points.size < 2) continue
                val path = Path()
                path.moveTo(stroke.points[0].x * outWidth, stroke.points[0].y * outHeight)
                for (i in 1 until stroke.points.size) {
                    path.lineTo(stroke.points[i].x * outWidth, stroke.points[i].y * outHeight)
                }
                p.color = stroke.color
                p.strokeWidth = stroke.widthNorm * outWidth
                canvas.drawPath(path, p)
            }
            return bmp
        }
    }
}
