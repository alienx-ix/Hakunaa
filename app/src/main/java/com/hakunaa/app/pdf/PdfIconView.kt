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

    /** Reorder pages, iLovePDF style (v0.1.156, Fix #43 — the
     * grip-dots + lines did not read as "reorder"): a red
     * vertical bar with white up/down chevrons, next to two
     * blocks marked A (top) and B (bottom) — the pages being
     * moved. Redrawn from the iLovePDF reference, not copied. */
    private fun Canvas.organize() {
        // Red bar with up/down chevrons.
        fill.color = red
        drawRoundRect(RectF(10f, 8f, 30f, 92f), 8f, 8f, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 5f
        drawLine(20f, 22f, 15f, 29f, stroke)
        drawLine(20f, 22f, 25f, 29f, stroke)
        drawLine(20f, 78f, 15f, 71f, stroke)
        drawLine(20f, 78f, 25f, 71f, stroke)
        // A block (top) and B block (bottom).
        rr(38f, 8f, 44f, red2, 8f)
        rr(38f, 56f, 44f, red, 8f)
        textPaint.textSize = 22f
        drawText("A", 60f, 38f, textPaint)
        drawText("B", 60f, 86f, textPaint)
    }

    /** Pen nib on a blue square, iLovePDF style (v0.1.156,
     * Fix #43 — the teal nib + flourish was redrawn twice and
     * still did not read as "sign" at a glance. iLovePDF's blue
     * square with a white nib is unmistakable.) */
    private fun Canvas.sign() {
        rr(14f, 14f, 72f, blue, 10f)
        // White nib, tilted 45 degrees.
        save()
        rotate(45f, 50f, 50f)
        fill.color = Color.WHITE
        val nib = android.graphics.Path().apply {
            moveTo(50f, 20f)
            quadTo(66f, 28f, 66f, 48f)
            lineTo(50f, 80f)
            lineTo(34f, 48f)
            quadTo(34f, 28f, 50f, 20f)
            close()
        }
        drawPath(nib, fill)
        // Slit + breather hole in blue.
        stroke.color = blue
        stroke.strokeWidth = 3.5f
        drawLine(50f, 78f, 50f, 52f, stroke)
        fill.color = blue
        drawCircle(50f, 42f, 4.5f, fill)
        restore()
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

    /** Padlock on a blue square, iLovePDF style (v0.1.156,
     * Fix #43 — the bare padlock did not read as "lock/unlock"
     * next to iLovePDF's blue Unlock/Protect icons. Closed
     * shackle = locked; the "Lock / Unlock" tool name covers
     * the unlock half.) */
    private fun Canvas.lock() {
        rr(14f, 14f, 72f, blue, 10f)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 7f
        drawArc(RectF(35f, 26f, 65f, 56f), 180f, 180f, false, stroke)
        rr(29f, 48f, 42f, Color.WHITE, 8f)
        fill.color = blue
        drawCircle(50f, 63f, 6f, fill)
        drawRoundRect(RectF(47f, 65f, 53f, 76f), 3f, 3f, fill)
    }

/** Open padlock on a blue square, iLovePDF Unlock style
     * (v0.1.156, Fix #43): same body as the lock, but the
     * shackle is drawn open on the right side. */
    private fun Canvas.unlock() {
        rr(14f, 14f, 72f, blue, 10f)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 7f
        drawArc(RectF(35f, 26f, 65f, 56f), 180f, 130f, false, stroke)
        drawLine(60f, 30f, 60f, 42f, stroke)
        rr(29f, 48f, 42f, Color.WHITE, 8f)
        fill.color = blue
        drawCircle(50f, 63f, 6f, fill)
        drawRoundRect(RectF(47f, 65f, 53f, 76f), 3f, 3f, fill)
    }

    /** Crossed wrench + screwdriver on a green square, iLovePDF
     * style (v0.1.156, Fix #43 — the v0.1.155 claw hammer is
     * GONE: the user pointed at iLovePDF's green crossed
     * wrench + screwdriver instead. Both tools are white on
     * green, crossed like an X — unmistakably "repair".) */
    private fun Canvas.repair() {
        rr(14f, 14f, 72f, green, 10f)
        // Wrench, tilted +45 degrees: handle + open jaw.
        save()
        rotate(45f, 50f, 50f)
        fill.color = Color.WHITE
        drawRoundRect(RectF(44f, 42f, 56f, 84f), 6f, 6f, fill)
        drawCircle(50f, 30f, 12f, fill)
        // Open mouth: cut a wedge out of the jaw with green.
        fill.color = green
        val mouth = android.graphics.Path().apply {
            moveTo(50f, 12f)
            lineTo(62f, 32f)
            lineTo(38f, 32f)
            close()
        }
        drawPath(mouth, fill)
        restore()
        // Screwdriver, tilted -45 degrees: handle + shaft + tip.
        save()
        rotate(-45f, 50f, 50f)
        fill.color = Color.WHITE
        drawRoundRect(RectF(42f, 14f, 58f, 40f), 7f, 7f, fill)
        drawRect(RectF(46f, 40f, 54f, 74f), fill)
        val tip = android.graphics.Path().apply {
            moveTo(46f, 74f)
            lineTo(54f, 74f)
            lineTo(50f, 84f)
            close()
        }
        drawPath(tip, fill)
        restore()
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

    /** Rotate a page (v0.1.155, Fix #38 — the bare circular arrow
     * read as "refresh", not "rotate a PDF"): a document page in
     * the centre with a BOLD circular arrow sweeping around it,
     * so the action is unmistakably "rotate this page". */
    private fun Canvas.rotate() {
        // Page in the middle.
        stroke.color = purple
        stroke.strokeWidth = 5f
        val doc = android.graphics.Path().apply {
            moveTo(34f, 30f)
            lineTo(58f, 30f)
            lineTo(66f, 38f)
            lineTo(66f, 70f)
            lineTo(34f, 70f)
            close()
        }
        drawPath(doc, stroke)
        drawLine(58f, 30f, 58f, 38f, stroke)
        drawLine(58f, 38f, 66f, 38f, stroke)
        stroke.strokeWidth = 4f
        drawLine(40f, 48f, 60f, 48f, stroke)
        drawLine(40f, 57f, 60f, 57f, stroke)
        // Bold circular arrow around the page.
        stroke.color = purple
        stroke.strokeWidth = 9f
        drawArc(RectF(14f, 14f, 86f, 86f), 40f, 280f, false, stroke)
        fill.color = purple
        val head = android.graphics.Path().apply {
            moveTo(78f, 18f)
            lineTo(96f, 42f)
            lineTo(60f, 38f)
            close()
        }
        drawPath(head, fill)
    }

    /** Rounded square + mountain + sun (image) with a small white
     * "PDF" page badge at the bottom-right (v0.1.155, Fix #37 —
     * the plain image read as just a photo, the same as Extract
     * Images. The PDF badge makes it read as "image → PDF"). */
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
        // PDF page badge, bottom-right.
        fill.color = Color.WHITE
        drawRoundRect(RectF(48f, 58f, 90f, 90f), 8f, 8f, fill)
        stroke.color = tealDk
        stroke.strokeWidth = 3f
        drawRoundRect(RectF(48f, 58f, 90f, 90f), 8f, 8f, stroke)
        textPaint.color = tealDk
        textPaint.textSize = 15f
        drawText("PDF", 69f, 80f, textPaint)
        textPaint.color = Color.WHITE
    }

    /** An image pulled OUT of a PDF page (v0.1.156, Fix #43 —
     * refined from the v0.1.155 version against professional
     * extract icons: the page now has a folded corner like a
     * real PDF page, the image card is bigger and slides out
     * diagonally, and the outward arrow is longer and bolder —
     * the "extract" action reads at a glance and the icon
     * stays clearly different from Image to PDF.) */
    private fun Canvas.imageExtract() {
        // The PDF page behind, left side, with a folded corner.
        stroke.color = tealDk
        stroke.strokeWidth = 6f
        val doc = android.graphics.Path().apply {
            moveTo(12f, 10f)
            lineTo(42f, 10f)
            lineTo(56f, 24f)
            lineTo(56f, 88f)
            lineTo(12f, 88f)
            close()
        }
        drawPath(doc, stroke)
        stroke.strokeWidth = 5f
        drawLine(42f, 10f, 42f, 24f, stroke)
        drawLine(42f, 24f, 56f, 24f, stroke)
        stroke.color = tealDk
        stroke.strokeWidth = 4f
        drawLine(20f, 44f, 48f, 44f, stroke)
        drawLine(20f, 56f, 48f, 56f, stroke)
        // The image sliding out to the right, bigger than before.
        rr(48f, 42f, 44f, green, 8f)
        fill.color = Color.WHITE
        val mountain = android.graphics.Path().apply {
            moveTo(52f, 80f)
            lineTo(64f, 60f)
            lineTo(72f, 70f)
            lineTo(78f, 62f)
            lineTo(88f, 80f)
            close()
        }
        drawPath(mountain, fill)
        drawCircle(61f, 54f, 5f, fill)
        // Long bold outward arrow: the image is coming OUT.
        arrow(74f, 40f, 20f, -20f, green, 8f)
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

    /** Rubber stamp on a purple square, iLovePDF style (v0.1.156,
     * Fix #43 — the diagonal "W" band did not read as a stamp.
     * iLovePDF's purple square with a white stamp is the
     * professional reference: knob handle, neck, flat base.) */
    private fun Canvas.watermark() {
        rr(14f, 14f, 72f, purple, 10f)
        fill.color = Color.WHITE
        // Handle knob + neck.
        drawCircle(50f, 30f, 9f, fill)
        val neck = android.graphics.Path().apply {
            moveTo(44f, 36f)
            lineTo(56f, 36f)
            lineTo(60f, 52f)
            lineTo(40f, 52f)
            close()
        }
        drawPath(neck, fill)
        // Flat base + the stamped line under it.
        drawRoundRect(RectF(24f, 52f, 76f, 68f), 6f, 6f, fill)
        drawRoundRect(RectF(28f, 72f, 72f, 78f), 3f, 3f, fill)
    }

    /** Four bold arrows pointing outwards (resize / expand). */
    private fun Canvas.resize() {
        arrow(38f, 38f, -16f, -16f, tealDk, 7f)
        arrow(62f, 38f, 16f, -16f, tealDk, 7f)
        arrow(38f, 62f, -16f, 16f, tealDk, 7f)
        arrow(62f, 62f, 16f, 16f, tealDk, 7f)
    }
}
