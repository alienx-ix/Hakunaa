package com.hakunaa.app.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Finger signature pad (v0.1.148, PDF-only). Draws dark ink on a
 * white pad on screen; exports a transparent PNG so the signature
 * stamps cleanly onto a PDF page without a white box.
 */
class SignaturePadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#111827")
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var hasInk = false
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE

    /** Pen colour for the drawn signature (v0.1.153, Fix #23). */
    fun setInkColor(color: Int) {
        paint.color = color
        invalidate()
    }

    private fun trackPoint(x: Float, y: Float) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                path.moveTo(event.x, event.y)
                hasInk = true
                trackPoint(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                path.lineTo(event.x, event.y)
                trackPoint(event.x, event.y)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        canvas.drawPath(path, paint)
    }

    fun isEmpty(): Boolean = !hasInk

    fun clear() {
        path.reset()
        hasInk = false
        minX = Float.MAX_VALUE
        minY = Float.MAX_VALUE
        maxX = -Float.MAX_VALUE
        maxY = -Float.MAX_VALUE
        invalidate()
    }

    /** Transparent bitmap of the signature (ink only). Null if the
     * pad has no size yet or no ink was drawn. */
    fun toBitmap(): Bitmap? {
        if (width <= 0 || height <= 0 || !hasInk) return null
        // Crop to the drawn ink (v0.1.153): the placed signature is
        // the signature itself, not a full-pad bitmap with a tiny
        // squiggle in the middle.
        val pad = 12f
        val left = (minX - pad).coerceIn(0f, width.toFloat()).toInt()
        val top = (minY - pad).coerceIn(0f, height.toFloat()).toInt()
        val right = (maxX + pad).coerceIn(0f, width.toFloat()).toInt()
        val bottom = (maxY + pad).coerceIn(0f, height.toFloat()).toInt()
        if (right <= left || bottom <= top) return null
        val full = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        c.drawPath(path, paint)
        return Bitmap.createBitmap(full, left, top, right - left, bottom - top)
    }
}
