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
    private val peach = Color.parseColor("#FBDCD2")
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

    /** Reorder pages, FAITHFUL to iLovePDF (v0.1.157, Fix #49 —
     * the v0.1.156 version had it backwards: coloured blocks
     * with white letters, no centre dot. iLovePDF has a red
     * pill with white chevrons + a white dot, next to LIGHT
     * peach blocks with RED letters. Redrawn from the iLovePDF
     * reference, not copied. */
    private fun Canvas.organize() {
        // Red pill with up/down chevrons + centre dot.
        fill.color = red
        drawRoundRect(RectF(12f, 8f, 32f, 92f), 8f, 8f, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 4.5f
        drawLine(22f, 20f, 16f, 28f, stroke)
        drawLine(22f, 20f, 28f, 28f, stroke)
        drawLine(22f, 80f, 16f, 72f, stroke)
        drawLine(22f, 80f, 28f, 72f, stroke)
        fill.color = Color.WHITE
        drawCircle(22f, 50f, 4f, fill)
        // A block (top) and B block (bottom): LIGHT peach + RED letters.
        rr(40f, 10f, 44f, peach, 8f)
        rr(40f, 54f, 44f, peach, 8f)
        textPaint.color = red
        textPaint.textSize = 24f
        drawText("A", 62f, 38f, textPaint)
        drawText("B", 62f, 82f, textPaint)
        textPaint.color = Color.WHITE
    }

    /** Pen nib on a blue square, FAITHFUL to iLovePDF (v0.1.157,
     * Fix #49 — the v0.1.156 nib was a thin dagger with a big
     * floating barrel. iLovePDF's nib is a compact pentagon
     * with wide shoulders, plus a SMALL barrel piece just above
     * it, tip pointing down-left. Redrawn from the reference. */
    private fun Canvas.sign() {
        rr(14f, 14f, 72f, blue, 10f)
        save()
        rotate(45f, 50f, 50f)
        fill.color = Color.WHITE
        // Small barrel piece above the nib.
        drawRoundRect(RectF(43f, 20f, 57f, 30f), 3f, 3f, fill)
        // Compact nib: flat top, wide shoulders, tip down.
        val nib = android.graphics.Path().apply {
            moveTo(37f, 38f)
            lineTo(63f, 38f)
            quadTo(64f, 54f, 58f, 64f)
            lineTo(50f, 78f)
            lineTo(42f, 64f)
            quadTo(36f, 54f, 37f, 38f)
            close()
        }
        drawPath(nib, fill)
        // Slit + breather hole in blue.
        stroke.color = blue
        stroke.strokeWidth = 3.5f
        drawLine(50f, 76f, 50f, 58f, stroke)
        fill.color = blue
        drawCircle(50f, 50f, 4.5f, fill)
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

    /** OPEN padlock on a blue square, FAITHFUL to iLovePDF
     * Unlock (v0.1.157, Fix #49 — the v0.1.156 closed padlock
     * was not the reference: iLovePDF's Unlock has the shackle
     * OPEN on the right, wider arch, taller shackle. The open
     * padlock reads as lock AND unlock, which fits the combined
     * "Lock / Unlock" tool.) */
    private fun Canvas.lock() {
        rr(14f, 14f, 72f, blue, 10f)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 7.5f
        // Shackle: arch open on the right side.
        drawArc(RectF(35f, 24f, 65f, 54f), 180f, 145f, false, stroke)
        drawLine(62.3f, 30.4f, 62f, 42f, stroke)
        drawLine(35f, 39f, 35f, 52f, stroke)
        // Body + keyhole.
        fill.color = Color.WHITE
        drawRoundRect(RectF(30f, 50f, 70f, 86f), 7f, 7f, fill)
        fill.color = blue
        drawCircle(50f, 63f, 5f, fill)
        drawRoundRect(RectF(47.5f, 65f, 52.5f, 75f), 2.5f, 2.5f, fill)
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

    /** Crossed wrench + screwdriver on a green square, FAITHFUL
     * to iLovePDF (v0.1.157, Fix #49 — the v0.1.156 pair was
     * MIRRORED (wrench jaw top-right, screwdriver handle
     * top-left) and the screwdriver handle was a near-square
     * that read as a diamond. iLovePDF: wrench jaw TOP-LEFT
     * with a C-opening, round holed handle-end BOTTOM-RIGHT;
     * screwdriver LONG narrow handle TOP-RIGHT, flat tip
     * BOTTOM-LEFT. Redrawn from the reference, verified in a
     * PIL preview before building.) */
    private fun Canvas.repair() {
        rr(14f, 14f, 72f, green, 10f)
        // Screwdriver FIRST (under the wrench): rotated +45 deg
        // so its handle lands top-right and its tip bottom-left.
        save()
        rotate(45f, 50f, 50f)
        fill.color = Color.WHITE
        drawRoundRect(RectF(45f, 14f, 55f, 36f), 5f, 5f, fill)
        drawRect(RectF(47f, 36f, 53f, 68f), fill)
        drawRect(RectF(45f, 68f, 55f, 78f), fill)
        restore()
        // Wrench SECOND (on top): rotated -45 deg so its jaw
        // lands top-left and its handle-end bottom-right.
        save()
        rotate(-45f, 50f, 50f)
        fill.color = Color.WHITE
        drawCircle(50f, 26f, 13f, fill)
        // C-opening: 70-degree wedge cut, apex at the jaw centre.
        fill.color = green
        val mouth = android.graphics.Path().apply {
            moveTo(50f, 26f)
            lineTo(40f, 12f)
            lineTo(60f, 12f)
            close()
        }
        drawPath(mouth, fill)
        fill.color = Color.WHITE
        drawRoundRect(RectF(44.5f, 40f, 55.5f, 72f), 6f, 6f, fill)
        drawCircle(50f, 74f, 9.5f, fill)
        fill.color = green
        drawCircle(50f, 74f, 4f, fill)
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

    /** Dashed circular arrow on a purple square, FAITHFUL to
     * iLovePDF Rotate (v0.1.157, Fix #50 — the v0.1.155/156
     * "page + bold arrow" was my invention: iLovePDF has NO
     * page, just a dashed circle with an arrowhead at the top.
     * Solid arc on the left/bottom, three dashes on the right,
     * arrowhead attached to the arc end pointing up-right.) */
    private fun Canvas.rotate() {
        rr(14f, 14f, 72f, purple, 10f)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 6f
        // Solid arc: 60 deg sweeping 240 deg clockwise (ends at 300 deg).
        drawArc(RectF(27f, 29f, 73f, 75f), 60f, 240f, false, stroke)
        // Three dashes on the right side.
        stroke.strokeWidth = 7f
        drawArc(RectF(27f, 29f, 73f, 75f), 292f, 20f, false, stroke)
        drawArc(RectF(27f, 29f, 73f, 75f), 352f, 20f, false, stroke)
        drawArc(RectF(27f, 29f, 73f, 75f), 22f, 20f, false, stroke)
        // Arrowhead attached to the arc end (61.5, 32.1).
        fill.color = Color.WHITE
        val head = android.graphics.Path().apply {
            moveTo(67.5f, 21.7f)
            lineTo(65.6f, 39.1f)
            lineTo(53.4f, 32.1f)
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

    /** An image pulled OUT of a PDF page (v0.1.157, Fix #49 —
     * the v0.1.156 arrowhead was a splayed blob and the text
     * lines inside the page added clutter at small size. Now:
     * clean page outline (no text lines), bigger image card,
     * and a proper filled-triangle arrowhead pointing
     * up-right. The "extract" action reads at a glance and
     * the icon stays clearly different from Image to PDF.) */
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
        // The image sliding out to the right.
        rr(46f, 42f, 46f, green, 8f)
        fill.color = Color.WHITE
        val mountain = android.graphics.Path().apply {
            moveTo(50f, 80f)
            lineTo(62f, 60f)
            lineTo(70f, 70f)
            lineTo(76f, 62f)
            lineTo(88f, 80f)
            close()
        }
        drawPath(mountain, fill)
        drawCircle(59f, 54f, 5f, fill)
        // Clean outward arrow: shaft + filled triangle head.
        stroke.color = green
        stroke.strokeWidth = 7f
        drawLine(74f, 42f, 88f, 28f, stroke)
        fill.color = green
        val head = android.graphics.Path().apply {
            moveTo(96f, 20f)
            lineTo(91.7f, 32.7f)
            lineTo(83.3f, 24.3f)
            close()
        }
        drawPath(head, fill)
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

    /** Rubber stamp on a purple square, FAITHFUL to iLovePDF
     * (v0.1.157, Fix #49 — the v0.1.156 stamp was a separate
     * circle + trapezoid neck, so the head/neck seam showed.
     * iLovePDF's handle is ONE pawn-shaped silhouette: round
     * head, gentle waist, flared foot — then a wide flat base
     * and a thin stamped line under it.) */
    private fun Canvas.watermark() {
        rr(14f, 14f, 72f, purple, 10f)
        fill.color = Color.WHITE
        // Pawn-shaped handle: one continuous path.
        val handle = android.graphics.Path().apply {
            moveTo(50f, 20f)
            quadTo(62f, 20f, 62f, 32f)
            quadTo(62f, 40f, 56f, 46f)
            quadTo(61f, 52f, 63f, 58f)
            lineTo(37f, 58f)
            quadTo(39f, 52f, 44f, 46f)
            quadTo(38f, 40f, 38f, 32f)
            quadTo(38f, 20f, 50f, 20f)
            close()
        }
        drawPath(handle, fill)
        // Flat base + the stamped line under it.
        drawRoundRect(RectF(24f, 62f, 76f, 76f), 5f, 5f, fill)
        drawRoundRect(RectF(30f, 80f, 70f, 85f), 2.5f, 2.5f, fill)
    }

    /** Four bold arrows pointing outwards (resize / expand). */
    private fun Canvas.resize() {
        arrow(38f, 38f, -16f, -16f, tealDk, 7f)
        arrow(62f, 38f, 16f, -16f, tealDk, 7f)
        arrow(38f, 62f, -16f, 16f, tealDk, 7f)
        arrow(62f, 62f, 16f, 16f, tealDk, 7f)
    }
}
