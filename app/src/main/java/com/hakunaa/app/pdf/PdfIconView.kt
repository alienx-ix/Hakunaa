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

    // Mockup palette (v0.1.158, Fix #51) — colours measured from the
    // user-approved mockup 8 PIL renders. Dedicated vals: the shared
    // colours above feed other icons and must NOT change.
    private val orgRed = Color.parseColor("#E85D3F")
    private val orgPeach = Color.parseColor("#F5CDBD")
    private val orgLetterRed = Color.parseColor("#B03A2E")
    private val mockBlue = Color.parseColor("#4170A0")
    private val extractGreen = Color.parseColor("#43A047")
    private val extractGreenDark = Color.parseColor("#2E7D32")
    private val rotateTeal = Color.parseColor("#00BFA5")

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

    /** Reorder pages — EXACTLY as the user-approved mockup 8
     * (v0.1.158, Fix #51). PIL geometry from
     * make_icons_v0158_mockup8.py, scaled 480 -> 100 (÷4.8):
     * red pill with white chevrons + centre dot, next to LIGHT
     * peach blocks with RED letters. No square background. */
    private fun Canvas.organize() {
        // Red pill.
        fill.color = orgRed
        drawRoundRect(RectF(12.5f, 12.5f, 43.8f, 87.5f), 6.2f, 6.2f, fill)
        // White up chevron (top), dot (centre), down chevron (bottom).
        stroke.color = Color.WHITE
        stroke.strokeWidth = 3.8f
        drawLine(21.9f, 43.8f, 28.1f, 36.5f, stroke)
        drawLine(28.1f, 36.5f, 34.4f, 43.8f, stroke)
        drawLine(21.9f, 60.4f, 28.1f, 67.7f, stroke)
        drawLine(28.1f, 67.7f, 34.4f, 60.4f, stroke)
        fill.color = Color.WHITE
        drawCircle(28.1f, 52.1f, 2.9f, fill)
        // A block (top) and B block (bottom): LIGHT peach + RED letters.
        fill.color = orgPeach
        drawRoundRect(RectF(49f, 12.5f, 87.5f, 46.9f), 5f, 5f, fill)
        drawRoundRect(RectF(49f, 53.1f, 87.5f, 87.5f), 5f, 5f, fill)
        textPaint.color = orgLetterRed
        textPaint.textSize = 22f
        drawText("A", 68.1f, 37.6f, textPaint)
        drawText("B", 68.1f, 78.2f, textPaint)
        textPaint.color = Color.WHITE
    }

    /** Pen nib on a blue square — EXACTLY as the user-approved
     * mockup 8 (v0.1.158, Fix #51; pen CENTRED, mockup 4).
     * PIL geometry from make_icons_v0158_mockup8.py, scaled
     * 480 -> 100 (÷4.8): chunky nib pentagon with wide
     * shoulders, SMALL chunky barrel just above it, tip
     * pointing down-left. The pen content is centred in the
     * square before the 45° rotation. */
    private fun Canvas.sign() {
        rr(8.3f, 8.3f, 83.3f, mockBlue, 12.5f)
        save()
        rotate(45f, 50f, 50f)
        fill.color = Color.WHITE
        // Chunky barrel piece above the nib (same width as nib top).
        drawRoundRect(RectF(40f, 20.8f, 60f, 31.7f), 1.7f, 1.7f, fill)
        // Chunky nib pentagon: flat wide top, shoulders, sharp tip.
        val nib = android.graphics.Path().apply {
            moveTo(40f, 37.7f)
            lineTo(60f, 37.7f)
            lineTo(62.1f, 48.1f)
            lineTo(50f, 78.3f)
            lineTo(37.9f, 48.1f)
            close()
        }
        drawPath(nib, fill)
        // Slit + breather hole in blue.
        stroke.color = mockBlue
        stroke.strokeWidth = 1.7f
        drawLine(50f, 75.8f, 50f, 55.2f, stroke)
        fill.color = mockBlue
        drawCircle(50f, 51.3f, 3.5f, fill)
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

    /** Protect SHIELD on a blue square — EXACTLY as the
     * user-approved mockup 8 (v0.1.158, Fix #51). The user
     * asked for the Protect symbol instead of a lock for the
     * combined "Lock / Unlock" tool ("Lock symbol ilama..
     * Protect symbol"). PIL geometry from
     * make_icons_v0158_mockup8.py, scaled 480 -> 100 (÷4.8):
     * white shield outline (peak top-centre, sides curving in
     * to a point at bottom-centre) with a white keyhole
     * centred inside. NO padlock. (Kind.UNLOCK keeps the open
     * padlock — it is the Unlock option card inside the
     * Protect/Unlock chooser, where it must read as Unlock.) */
    private fun Canvas.lock() {
        rr(8.3f, 8.3f, 83.3f, mockBlue, 12.5f)
        // Outer shield (white).
        fill.color = Color.WHITE
        val outer = android.graphics.Path().apply {
            moveTo(31.3f, 29.2f)
            lineTo(50f, 21.3f)
            lineTo(68.8f, 29.2f)
            lineTo(71.7f, 40.6f)
            lineTo(70.4f, 54.6f)
            lineTo(62.9f, 68.8f)
            lineTo(50f, 78.8f)
            lineTo(37.1f, 68.8f)
            lineTo(29.6f, 54.6f)
            lineTo(28.3f, 40.6f)
            close()
        }
        drawPath(outer, fill)
        // Inner shield (blue) — leaves a white border.
        fill.color = mockBlue
        val inner = android.graphics.Path().apply {
            moveTo(34f, 32.5f)
            lineTo(50f, 25.8f)
            lineTo(66f, 32.5f)
            lineTo(68.1f, 41.3f)
            lineTo(67.1f, 53.3f)
            lineTo(60.6f, 65.6f)
            lineTo(50f, 73.8f)
            lineTo(39.4f, 65.6f)
            lineTo(32.9f, 53.3f)
            lineTo(31.9f, 41.3f)
            close()
        }
        drawPath(inner, fill)
        // White keyhole: circle + tapered stem, centred.
        fill.color = Color.WHITE
        drawCircle(50f, 45.6f, 4.4f, fill)
        val stem = android.graphics.Path().apply {
            moveTo(47.7f, 48.8f)
            lineTo(52.3f, 48.8f)
            lineTo(54.4f, 60.8f)
            lineTo(45.6f, 60.8f)
            close()
        }
        drawPath(stem, fill)
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

    /** Double-arrow circle on a purple square — the USER'S OWN
     * reference shape (v0.1.158, Fix #51; user delegated Rotate
     * to best judgment: "Rotate ku edha best a panu"). The
     * shape is TRACED from the reference image the user sent
     * (~/workspace/user/files/35933_35_mlle.jpg — black
     * double-arrow on white; user: "Idhu mari panu"), via
     * marching squares + Douglas-Peucker
     * (~/workspace/trace_rotate.py). Two separate arrows with
     * VISIBLE gaps between them (top arrowhead pointing
     * up-right, bottom arrowhead pointing down-left), white
     * on a TEAL rounded square (user, 20:57 IST: "Also rotate
     * vera color la panu okva" — Rotate in a different colour,
     * not purple; teal is the app's accent and distinct from
     * the other icons). NOT hand-drawn, NOT the mockup 8
     * solid-line arrow (user called it "kuppa"), NOT a dashed
     * arrow. */
    private fun Canvas.rotate() {
        rr(8.3f, 8.3f, 83.3f, rotateTeal, 12.5f)
        fill.color = Color.WHITE
        // Arrow 0: left arc + top arrowhead (30 pts, traced).
        val arrow0 = android.graphics.Path().apply {
            moveTo(48.0f, 12.5f)
            lineTo(47.3f, 13.6f)
            lineTo(47.3f, 22.6f)
            lineTo(42.9f, 23.5f)
            lineTo(38.8f, 25.1f)
            lineTo(32.5f, 29.2f)
            lineTo(27.4f, 35.1f)
            lineTo(24.2f, 42.2f)
            lineTo(23.1f, 49.4f)
            lineTo(23.9f, 56.8f)
            lineTo(27.2f, 64.4f)
            lineTo(30.9f, 68.8f)
            lineTo(32.6f, 68.5f)
            lineTo(37.0f, 63.9f)
            lineTo(36.7f, 62.6f)
            lineTo(34.9f, 60.3f)
            lineTo(33.2f, 57.0f)
            lineTo(31.8f, 50.6f)
            lineTo(32.6f, 44.3f)
            lineTo(34.2f, 40.7f)
            lineTo(36.2f, 37.8f)
            lineTo(41.3f, 33.5f)
            lineTo(46.9f, 31.5f)
            lineTo(47.2f, 41.7f)
            lineTo(47.7f, 42.5f)
            lineTo(48.5f, 42.6f)
            lineTo(65.4f, 28.2f)
            lineTo(65.1f, 26.6f)
            lineTo(49.2f, 12.8f)
            lineTo(48.0f, 12.5f)
            close()
        }
        drawPath(arrow0, fill)
        // Arrow 1: right arc + bottom arrowhead (33 pts, traced).
        val arrow1 = android.graphics.Path().apply {
            moveTo(67.9f, 31.2f)
            lineTo(63.5f, 35.1f)
            lineTo(63.0f, 36.1f)
            lineTo(63.2f, 37.1f)
            lineTo(66.6f, 42.5f)
            lineTo(68.2f, 48.3f)
            lineTo(68.1f, 52.2f)
            lineTo(67.4f, 55.5f)
            lineTo(66.4f, 58.3f)
            lineTo(64.0f, 61.9f)
            lineTo(59.4f, 66.0f)
            lineTo(56.4f, 67.5f)
            lineTo(53.1f, 68.5f)
            lineTo(52.8f, 58.3f)
            lineTo(52.3f, 57.5f)
            lineTo(51.5f, 57.4f)
            lineTo(34.5f, 72.1f)
            lineTo(35.2f, 73.6f)
            lineTo(51.3f, 87.5f)
            lineTo(52.7f, 86.9f)
            lineTo(52.8f, 77.4f)
            lineTo(60.5f, 75.2f)
            lineTo(64.3f, 73.2f)
            lineTo(67.6f, 70.7f)
            lineTo(72.6f, 64.9f)
            lineTo(75.8f, 57.8f)
            lineTo(76.6f, 54.2f)
            lineTo(76.9f, 49.6f)
            lineTo(75.6f, 41.7f)
            lineTo(74.0f, 37.6f)
            lineTo(71.8f, 34.0f)
            lineTo(69.7f, 31.4f)
            lineTo(67.9f, 31.2f)
            close()
        }
        drawPath(arrow1, fill)
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

    /** An image pulled OUT of a PDF page — EXACTLY as the
     * user-approved mockup 8 (v0.1.158, Fix #51). PIL geometry
     * from make_icons_v0158_mockup8.py, scaled 480 -> 100
     * (÷4.8): GREEN rounded square, white page with folded
     * corner + text lines, white image card (mountain + sun)
     * sliding out bottom-right, bold white arrow pointing
     * up-right. Clearly different from Image to PDF. */
    private fun Canvas.imageExtract() {
        rr(8.3f, 8.3f, 83.3f, extractGreen, 12.5f)
        // White page (back-left) with folded top-right corner.
        fill.color = Color.WHITE
        drawRoundRect(RectF(19.8f, 19.8f, 55.2f, 80.2f), 3.3f, 3.3f, fill)
        fill.color = extractGreen
        val fold = android.graphics.Path().apply {
            moveTo(55.2f, 19.8f)
            lineTo(55.2f, 29.2f)
            lineTo(45.8f, 19.8f)
            close()
        }
        drawPath(fold, fill)
        // Green text lines inside the page.
        stroke.color = extractGreen
        stroke.strokeWidth = 1.5f
        drawLine(25f, 35.4f, 50f, 35.4f, stroke)
        drawLine(25f, 41.7f, 50f, 41.7f, stroke)
        drawLine(25f, 47.9f, 50f, 47.9f, stroke)
        // White image card sliding out bottom-right (dark-green border).
        fill.color = extractGreenDark
        drawRoundRect(RectF(43.8f, 46.9f, 77.1f, 75f), 2.5f, 2.5f, fill)
        fill.color = Color.WHITE
        drawRoundRect(RectF(45f, 48.1f, 75.8f, 73.8f), 2.1f, 2.1f, fill)
        // Mountain + sun inside the image card.
        fill.color = extractGreen
        val mountain = android.graphics.Path().apply {
            moveTo(49f, 70.8f)
            lineTo(57.3f, 59.4f)
            lineTo(65.6f, 70.8f)
            close()
        }
        drawPath(mountain, fill)
        drawCircle(67.7f, 54.6f, 2.3f, fill)
        // Bold white arrow pointing up-right out of the page.
        stroke.color = Color.WHITE
        stroke.strokeWidth = 3.3f
        drawLine(60.8f, 37.9f, 73.3f, 25.4f, stroke)
        fill.color = Color.WHITE
        val head = android.graphics.Path().apply {
            moveTo(75.4f, 23.3f)
            lineTo(67.1f, 22.5f)
            lineTo(74.2f, 31.7f)
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
