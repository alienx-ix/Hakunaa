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
        SMART, LOSSLESS, MERGE, SPLIT, ORGANIZE, SIGN, JPG, LOCK, UNLOCK,
        REPAIR, PAGE_NO, ROTATE, IMG_PDF, DELETE_PAGES, WATERMARK, IMG_EXTRACT, RESIZE
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
            Kind.UNLOCK -> canvas.unlock()
            Kind.REPAIR -> canvas.repair()
            Kind.PAGE_NO -> canvas.pageNo()
            Kind.ROTATE -> canvas.rotate()
            Kind.IMG_PDF -> canvas.image(yellow)
            Kind.DELETE_PAGES -> canvas.deletePages()
            Kind.WATERMARK -> canvas.watermark()
            Kind.IMG_EXTRACT -> canvas.imageExtract()
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

    /** Reorder list: grip dots + three lines. */
    private fun Canvas.organize() {
        fill.color = blue
        for (y in listOf(28f, 50f, 72f)) {
            drawCircle(20f, y, 4f, fill)
            drawCircle(30f, y, 4f, fill)
        }
        stroke.color = blue
        stroke.strokeWidth = 7f
        for (y in listOf(28f, 50f, 72f)) {
            drawLine(42f, y, 84f, y, stroke)
        }
    }

    /** Pen + flowing signature squiggle. */
    /** Fountain pen, drawn bold so it reads as a pen at a
     * glance: thick diagonal barrel, clear nib with a slit, and a
     * flowing signature flourish underneath. (v0.1.153, Fix #19 —
     * the old pen + squiggle did not read as a signature pen.) */
    private fun Canvas.sign() {
        // Signature flourish first (behind the pen).
        stroke.color = tealDk
        stroke.strokeWidth = 6f
        val sig = android.graphics.Path().apply {
            moveTo(12f, 82f)
            cubicTo(22f, 64f, 30f, 92f, 42f, 76f)
            cubicTo(50f, 64f, 56f, 90f, 68f, 74f)
            cubicTo(74f, 66f, 80f, 80f, 88f, 76f)
        }
        drawPath(sig, stroke)
        // Pen barrel: thick diagonal line, nib end at top-right.
        stroke.strokeWidth = 11f
        drawLine(26f, 58f, 58f, 26f, stroke)
        // Nib: filled triangle + slit line.
        fill.color = tealDk
        val nib = android.graphics.Path().apply {
            moveTo(58f, 26f)
            lineTo(88f, 10f)
            lineTo(76f, 42f)
            close()
        }
        drawPath(nib, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 3f
        drawLine(66f, 30f, 78f, 20f, stroke)
    }


    /** Yellow file square with folded corner + "JPG". */
    private fun Canvas.jpg() {
        rr(14f, 14f, 72f, yellow, 10f)
        fill.color = Color.WHITE
        val fold = android.graphics.Path().apply {
            moveTo(64f, 14f)
            lineTo(86f, 36f)
            lineTo(64f, 36f)
            close()
        }
        drawPath(fold, fill)
        textPaint.textSize = 26f
        drawText("JPG", 50f, 62f, textPaint)
    }

    /** Padlock with a white keyhole. */
    private fun Canvas.lock() {
        stroke.color = blue
        stroke.strokeWidth = 7f
        drawArc(RectF(32f, 12f, 68f, 48f), 180f, 180f, false, stroke)
        rr(24f, 44f, 52f, blue, 9f)
        fill.color = Color.WHITE
        drawCircle(50f, 62f, 7f, fill)
        drawRoundRect(RectF(46f, 64f, 54f, 76f), 3f, 3f, fill)
    }

/** Open padlock (unlock): same body as the lock, but the
     * shackle is drawn open on the right side. */
    private fun Canvas.unlock() {
        stroke.color = blue
        stroke.strokeWidth = 7f
        drawArc(RectF(32f, 12f, 68f, 48f), 180f, 130f, false, stroke)
        drawLine(68f, 30f, 68f, 46f, stroke)
        rr(24f, 44f, 52f, blue, 9f)
        fill.color = Color.WHITE
        drawCircle(50f, 62f, 7f, fill)
        drawRoundRect(RectF(46f, 64f, 54f, 76f), 3f, 3f, fill)
    }

    /** Wrench: open jaw crescent + thick handle. */
    /** Real open-jaw wrench (v0.1.153, Fix #19 — the old arc +
     * line did not read as a wrench): a ring with the jaw open at
     * the top-right, two jaw flats, and a thick handle running
     * down to the bottom-left. */
    private fun Canvas.repair() {
        stroke.color = tealDk
        stroke.strokeWidth = 10f
        // Jaw ring: 270° arc, gap at the top-right.
        drawArc(RectF(42f, 6f, 94f, 58f), 20f, 270f, false, stroke)
        // Jaw flats: short lines from the arc ends toward the centre.
        stroke.strokeWidth = 9f
        drawLine(85f, 15f, 74f, 26f, stroke)
        drawLine(90f, 40f, 78f, 37f, stroke)
        // Handle.
        stroke.strokeWidth = 12f
        drawLine(50f, 50f, 16f, 84f, stroke)
    }


    /** Four numbered mini squares (page numbers). */
    private fun Canvas.pageNo() {
        val nums = listOf("1", "2", "3", "4")
        val spots = listOf(18f to 18f, 56f to 18f, 18f to 56f, 56f to 56f)
        textPaint.textSize = 19f
        for (i in nums.indices) {
            val (x, y) = spots[i]
            rr(x, y, 26f, purple, 7f)
            drawText(nums[i], x + 13f, y + 19f, textPaint)
        }
    }

    /** Circular arrow (rotate). */
    private fun Canvas.rotate() {
        stroke.color = purple
        stroke.strokeWidth = 8f
        drawArc(RectF(16f, 16f, 84f, 84f), 40f, 280f, false, stroke)
        fill.color = purple
        val head = android.graphics.Path().apply {
            moveTo(78f, 18f)
            lineTo(96f, 42f)
            lineTo(60f, 38f)
            close()
        }
        drawPath(head, fill)
    }

    /** Rounded square + mountain + sun (image). */
    private fun Canvas.image(color: Int) {
        rr(14f, 14f, 72f, color, 10f)
        fill.color = Color.WHITE
        val mountain = android.graphics.Path().apply {
            moveTo(20f, 70f)
            lineTo(38f, 36f)
            lineTo(50f, 54f)
            lineTo(60f, 42f)
            lineTo(80f, 70f)
            close()
        }
        drawPath(mountain, fill)
        drawCircle(32f, 30f, 6f, fill)
    }

    /** Image with an outward arrow — pulling images OUT of a PDF. */
    private fun Canvas.imageExtract() {
        image(green)
        arrow(60f, 34f, 20f, -20f, Color.WHITE, 6f)
    }

    /** Big teal "T" + text lines (extract text). */

    /** Rubber stamp in a purple square (watermark). */
/** Delete Pages: a page outline with a red X badge at the
     * bottom-right — reads instantly as "remove this page". */
    private fun Canvas.deletePages() {
        stroke.color = tealDk
        stroke.strokeWidth = 6f
        val doc = android.graphics.Path().apply {
            moveTo(26f, 12f)
            lineTo(58f, 12f)
            lineTo(74f, 28f)
            lineTo(74f, 84f)
            lineTo(26f, 84f)
            close()
        }
        drawPath(doc, stroke)
        stroke.strokeWidth = 5f
        drawLine(58f, 12f, 58f, 28f, stroke)
        drawLine(58f, 28f, 74f, 28f, stroke)
        stroke.color = tealDk
        stroke.strokeWidth = 5f
        drawLine(36f, 46f, 64f, 46f, stroke)
        drawLine(36f, 58f, 64f, 58f, stroke)
        drawLine(36f, 70f, 54f, 70f, stroke)
        // Red X badge.
        fill.color = Color.parseColor("#E85A46")
        drawCircle(76f, 76f, 16f, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 6f
        drawLine(69f, 69f, 83f, 83f, stroke)
        drawLine(83f, 69f, 69f, 83f, stroke)
    }

    private fun Canvas.watermark() {
        rr(14f, 14f, 72f, purple, 10f)
        fill.color = Color.WHITE
        drawRoundRect(RectF(26f, 62f, 74f, 78f), 5f, 5f, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 7f
        drawLine(50f, 32f, 50f, 62f, stroke)
        drawCircle(50f, 28f, 10f, fill)
    }

    /** Four bold arrows pointing outwards (resize / expand). */
    private fun Canvas.resize() {
        arrow(38f, 38f, -16f, -16f, tealDk, 7f)
        arrow(62f, 38f, 16f, -16f, tealDk, 7f)
        arrow(38f, 62f, -16f, 16f, tealDk, 7f)
        arrow(62f, 62f, 16f, 16f, tealDk, 7f)
    }
}
