package com.hakunaa.app.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Code-drawn PDF tool icons (v0.1.148) — exact match for the approved
 * PDF Tools menu mockup. Every icon is drawn in a 100x100 box and
 * scaled to the view size, so it stays crisp at any card size.
 */
class PdfIconView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Kind {
        SMART, LOSSLESS, MERGE, SPLIT, ORGANIZE, SIGN, JPG, LOCK,
        REPAIR, PAGE_NO, ROTATE, IMG_PDF, TEXT, WATERMARK, IMG_EXTRACT, RESIZE
    }

    var kind: Kind = Kind.SMART
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val green = Color.parseColor("#78BE5A")
    private val blue = Color.parseColor("#508CDC")
    private val red = Color.parseColor("#E85A46")
    private val red2 = Color.parseColor("#F4826E")
    private val yellow = Color.parseColor("#FAC83C")
    private val purple = Color.parseColor("#AA5AA0")
    private val tealDk = Color.parseColor("#009682")

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height) / 100f
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - 100f * s) / 2f, (height - 100f * s) / 2f)
        canvas.scale(s, s)
        when (kind) {
            Kind.SMART -> canvas.compress(green)
            Kind.LOSSLESS -> canvas.compress(blue)
            Kind.MERGE -> canvas.mergeSplit(inward = true)
            Kind.SPLIT -> canvas.mergeSplit(inward = false)
            Kind.ORGANIZE -> canvas.organize()
            Kind.SIGN -> canvas.sign()
            Kind.JPG -> canvas.jpg()
            Kind.LOCK -> canvas.lock()
            Kind.REPAIR -> canvas.repair()
            Kind.PAGE_NO -> canvas.pageNo()
            Kind.ROTATE -> canvas.rotate()
            Kind.IMG_PDF -> canvas.image(yellow)
            Kind.TEXT -> canvas.textT()
            Kind.WATERMARK -> canvas.watermark()
            Kind.IMG_EXTRACT -> canvas.image(green)
            Kind.RESIZE -> canvas.resize()
        }
        canvas.restore()
    }

    private fun Canvas.rr(x: Float, y: Float, size: Float, color: Int, r: Float = 8f) {
        fill.color = color
        drawRoundRect(RectF(x, y, x + size, y + size), r, r, fill)
    }

    private fun Canvas.arrow(
        x: Float, y: Float, dx: Float, dy: Float, color: Int, w: Float = 5f
    ) {
        stroke.color = color
        stroke.strokeWidth = w
        drawLine(x, y, x + dx, y + dy, stroke)
        val ang = atan2(dy.toDouble(), dx.toDouble())
        val len = 13f
        for (a in listOf(ang + 2.5, ang - 2.5)) {
            drawLine(
                x + dx, y + dy,
                x + dx + len * cos(a).toFloat(), y + dy + len * sin(a).toFloat(),
                stroke
            )
        }
    }

    /** Four squares, arrows pointing inwards (compress). */
    private fun Canvas.compress(color: Int) {
        rr(10f, 10f, 34f, color)
        rr(56f, 10f, 34f, color)
        rr(10f, 56f, 34f, color)
        rr(56f, 56f, 34f, color)
        arrow(27f, 27f, 12f, 12f, Color.WHITE)
        arrow(73f, 27f, -12f, 12f, Color.WHITE)
        arrow(27f, 73f, 12f, -12f, Color.WHITE)
        arrow(73f, 73f, -12f, -12f, Color.WHITE)
    }

    /** Two overlapping squares; arrows towards each other (merge)
     * or away from each other (split). */
    private fun Canvas.mergeSplit(inward: Boolean) {
        rr(14f, 12f, 42f, red)
        rr(44f, 42f, 42f, red2)
        if (inward) {
            arrow(24f, 22f, 16f, 16f, Color.WHITE)
            arrow(76f, 74f, -16f, -16f, Color.WHITE)
        } else {
            arrow(34f, 32f, -14f, -14f, Color.WHITE)
            arrow(64f, 60f, 14f, 14f, Color.WHITE)
        }
    }

    /** Three horizontal lines (organize / list). */
    private fun Canvas.organize() {
        stroke.color = blue
        stroke.strokeWidth = 7f
        for (y in listOf(28f, 50f, 72f)) {
            drawLine(16f, y, 84f, y, stroke)
        }
    }

    /** Pen + signature curve. */
    private fun Canvas.sign() {
        stroke.color = tealDk
        stroke.strokeWidth = 8f
        drawLine(16f, 60f, 68f, 16f, stroke)
        fill.color = tealDk
        val nib = android.graphics.Path().apply {
            moveTo(68f, 16f)
            lineTo(86f, 10f)
            lineTo(80f, 32f)
            close()
        }
        drawPath(nib, fill)
        stroke.strokeWidth = 7f
        drawArc(RectF(14f, 58f, 86f, 92f), 200f, 140f, false, stroke)
    }

    /** Yellow square with "JPG". */
    private fun Canvas.jpg() {
        rr(14f, 14f, 72f, yellow, 10f)
        textPaint.textSize = 26f
        drawText("JPG", 50f, 59f, textPaint)
    }

    /** Padlock. */
    private fun Canvas.lock() {
        stroke.color = blue
        stroke.strokeWidth = 6f
        drawArc(RectF(32f, 14f, 68f, 50f), 180f, 180f, false, stroke)
        rr(24f, 44f, 52f, blue, 9f)
    }

    /** Wrench: open jaw + handle. */
    private fun Canvas.repair() {
        stroke.color = tealDk
        stroke.strokeWidth = 9f
        drawArc(RectF(46f, 6f, 92f, 52f), 300f, 120f, false, stroke)
        stroke.strokeWidth = 10f
        drawLine(20f, 82f, 48f, 54f, stroke)
    }

    /** Four numbered mini squares (page numbers). */
    private fun Canvas.pageNo() {
        val nums = listOf("1", "2", "3", "4")
        val spots = listOf(18f to 18f, 56f to 18f, 18f to 56f, 56f to 56f)
        textPaint.textSize = 18f
        for (i in nums.indices) {
            val (x, y) = spots[i]
            rr(x, y, 26f, purple, 7f)
            drawText(nums[i], x + 13f, y + 19f, textPaint)
        }
    }

    /** Circular arrow (rotate). */
    private fun Canvas.rotate() {
        stroke.color = purple
        stroke.strokeWidth = 7f
        drawArc(RectF(16f, 16f, 84f, 84f), 40f, 280f, false, stroke)
        fill.color = purple
        val head = android.graphics.Path().apply {
            moveTo(78f, 20f)
            lineTo(94f, 42f)
            lineTo(62f, 38f)
            close()
        }
        drawPath(head, fill)
    }

    /** Rounded square + mountain + sun (image). */
    private fun Canvas.image(color: Int) {
        rr(14f, 14f, 72f, color, 10f)
        fill.color = Color.WHITE
        val mountain = android.graphics.Path().apply {
            moveTo(20f, 68f)
            lineTo(36f, 38f)
            lineTo(48f, 56f)
            lineTo(58f, 44f)
            lineTo(80f, 68f)
            close()
        }
        drawPath(mountain, fill)
        drawCircle(30f, 30f, 6f, fill)
    }

    /** Big teal "T" (extract text). */
    private fun Canvas.textT() {
        textPaint.color = tealDk
        textPaint.textSize = 64f
        drawText("T", 50f, 72f, textPaint)
        textPaint.color = Color.WHITE
    }

    /** Stamp in a purple square (watermark). */
    private fun Canvas.watermark() {
        rr(14f, 14f, 72f, purple, 10f)
        fill.color = Color.WHITE
        drawRoundRect(RectF(28f, 60f, 72f, 76f), 6f, 6f, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 7f
        drawLine(50f, 30f, 50f, 60f, stroke)
        drawCircle(50f, 28f, 9f, fill)
    }

    /** Four arrows pointing outwards (resize / expand). */
    private fun Canvas.resize() {
        arrow(38f, 38f, -14f, -14f, tealDk)
        arrow(62f, 38f, 14f, -14f, tealDk)
        arrow(38f, 62f, -14f, 14f, tealDk)
        arrow(62f, 62f, 14f, 14f, tealDk)
    }
}
