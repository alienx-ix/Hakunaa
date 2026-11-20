package com.hakunaa.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Finger signature pad (v0.1.148, PDF-only). Draws dark ink on a
 * white pad on screen; exports a transparent PNG so the signature
 * stamps cleanly onto a PDF page without a white box.
 *
 * Editor model (v0.1.156, Fix #48): the pad keeps a list of
 * STROKES (each with its own colour + width), not one path — so
 * Undo can drop the last stroke and the Eraser can wipe part of
 * a drawing (eraser strokes paint with CLEAR). Pen boldness is
 * Thin (4f) / Medium (7f) / Bold (12f); the eraser is a fixed
 * 28f. Strokes are drawn in order, so an eraser stroke only
 * removes ink drawn before it.
 */
class SignaturePadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Stroke(
        val path: Path,
        val color: Int,
        val width: Float,
        val erase: Boolean
    )

    private val strokes = mutableListOf<Stroke>()
    private var current: Stroke? = null
    private var inkColor = Color.parseColor("#111827")
    private var inkWidth = 7f
    private var erasing = false

    /** Fired on the UI thread when a stroke ends (finger lifted)
     * — the activity refreshes the Undo button state from it. */
    var onStrokeEnd: (() -> Unit)? = null

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 28f
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    /** Pen colour for the drawn signature (v0.1.153, Fix #23).
     * Picking a colour always returns to pen mode. */
    fun setInkColor(color: Int) {
        inkColor = color
        erasing = false
        invalidate()
    }

    /** Pen boldness (v0.1.156, Fix #48): Thin 4f / Medium 7f /
     * Bold 12f. Picking a thickness returns to pen mode. */
    fun setStrokeWidth(width: Float) {
        inkWidth = width
        erasing = false
        invalidate()
    }

    /** Current pen thickness (for the activity to highlight the
     * active Thin / Medium / Bold pill). */
    fun currentWidth(): Float = inkWidth

    /** Eraser mode (v0.1.156, Fix #48): when on, strokes wipe
     * ink instead of adding it. */
    fun setEraser(on: Boolean) {
        erasing = on
        invalidate()
    }

    fun isErasing(): Boolean = erasing

    /** Drop the last stroke. Returns false when there is nothing
     * to undo. */
    fun undo(): Boolean {
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.size - 1)
        current = null
        invalidate()
        return true
    }

    fun canUndo(): Boolean = strokes.isNotEmpty()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val s = Stroke(
                    Path().apply { moveTo(event.x, event.y) },
                    inkColor, inkWidth, erasing
                )
                strokes.add(s)
                current = s
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                current?.path?.lineTo(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
                onStrokeEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun drawStrokes(canvas: Canvas) {
        for (s in strokes) {
            if (s.erase) {
                canvas.drawPath(s.path, eraserPaint)
            } else {
                strokePaint.color = s.color
                strokePaint.strokeWidth = s.width
                strokePaint.xfermode = null
                canvas.drawPath(s.path, strokePaint)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        drawStrokes(canvas)
    }

    fun isEmpty(): Boolean = strokes.none { !it.erase }

    fun clear() {
        strokes.clear()
        current = null
        invalidate()
    }

    /** Transparent bitmap of the signature (ink only). Null if the
     * pad has no size yet or no ink was drawn. Crops to the drawn
     * ink (v0.1.153): the placed signature is the signature
     * itself, not a full-pad bitmap with a tiny squiggle in the
     * middle. Eraser strokes do not extend the crop. */
    fun toBitmap(): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val bounds = RectF()
        var hasInk = false
        for (s in strokes) {
            if (s.erase) continue
            val b = RectF()
            s.path.computeBounds(b, true)
            if (b.width() <= 0f && b.height() <= 0f) continue
            if (!hasInk) {
                bounds.set(b)
                hasInk = true
            } else {
                bounds.union(b)
            }
        }
        if (!hasInk) return null
        val pad = 12f
        val left = (bounds.left - pad).coerceIn(0f, width.toFloat()).toInt()
        val top = (bounds.top - pad).coerceIn(0f, height.toFloat()).toInt()
        val right = (bounds.right + pad).coerceIn(0f, width.toFloat()).toInt()
        val bottom = (bounds.bottom + pad).coerceIn(0f, height.toFloat()).toInt()
        if (right <= left || bottom <= top) return null
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        drawStrokes(c)
        return Bitmap.createBitmap(full, left, top, right - left, bottom - top)
    }
}
