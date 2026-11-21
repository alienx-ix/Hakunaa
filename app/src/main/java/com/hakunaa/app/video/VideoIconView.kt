package com.hakunaa.app.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Code-drawn Video Tools icons (v0.1.162) — PDF theme.
 * Same pattern as PdfIconView (v0.1.158, user-approved "Super, Nice"):
 * every icon is drawn in a 100x100 box and scaled to the view size,
 * solid colours only, so it renders perfectly on the user's phone.
 * VectorDrawable <gradient> does NOT render on that phone
 * (proven v0.1.159/160) — no gradients, no shadows, no text.
 * SMART and ROTATE copy the PDF geometries verbatim.
 */
class VideoIconView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Kind {
        QUICK, SMART, ADVANCED, CONVERT, ROTATE, COLOR, AUDIO, CUTTER, THUMB, CROP
    }

    var kind: Kind = Kind.QUICK
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

    private val orange = Color.parseColor("#FF9800")
    private val smartGreen = Color.parseColor("#43A047")
    private val indigo = Color.parseColor("#3949AB")
    private val convBlue = Color.parseColor("#1E88E5")
    private val rotateTeal = Color.parseColor("#00BFA5")
    private val colorPurple = Color.parseColor("#8E24AA")
    private val audioPurple = Color.parseColor("#5E35B1")
    private val cutterPink = Color.parseColor("#D81B60")
    private val thumbOrange = Color.parseColor("#FB8C00")
    private val cropCyan = Color.parseColor("#00ACC1")

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height) / 100f
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - 100f * s) / 2f, (height - 100f * s) / 2f)
        canvas.scale(s, s)
        when (kind) {
            Kind.QUICK -> canvas.quick()
            Kind.SMART -> canvas.smart()
            Kind.ADVANCED -> canvas.advanced()
            Kind.CONVERT -> canvas.convert()
            Kind.ROTATE -> canvas.rotate()
            Kind.COLOR -> canvas.color()
            Kind.AUDIO -> canvas.audio()
            Kind.CUTTER -> canvas.cutter()
            Kind.THUMB -> canvas.thumb()
            Kind.CROP -> canvas.crop()
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

    /** Bold white bolt on an orange square. */
    private fun Canvas.quick() {
        rr(8.3f, 8.3f, 83.3f, orange, 12.5f)
        fill.color = Color.WHITE
        val bolt = Path().apply {
            moveTo(56f, 12f)
            lineTo(34f, 54f)
            lineTo(47f, 54f)
            lineTo(42f, 88f)
            lineTo(66f, 44f)
            lineTo(53f, 44f)
            close()
        }
        drawPath(bolt, fill)
    }

    /** EXACTLY the PDF Smart geometry — 4 green squares + white
     * inward arrows (PdfIconView.compress, colour #43A047). */
    private fun Canvas.smart() {
        rr(10f, 10f, 34f, smartGreen)
        rr(56f, 10f, 34f, smartGreen)
        rr(10f, 56f, 34f, smartGreen)
        rr(56f, 56f, 34f, smartGreen)
        arrow(27f, 27f, 12f, 12f, Color.WHITE)
        arrow(73f, 27f, -12f, 12f, Color.WHITE)
        arrow(27f, 73f, 12f, -12f, Color.WHITE)
        arrow(73f, 73f, -12f, -12f, Color.WHITE)
    }

    /** White gear on an indigo square. */
    private fun Canvas.advanced() {
        rr(8.3f, 8.3f, 83.3f, indigo, 12.5f)
        fill.color = Color.WHITE
        drawCircle(50f, 50f, 20f, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 7f
        for (a in 0 until 8) {
            val ang = Math.toRadians((a * 45).toDouble())
            val x0 = 50f + 20f * cos(ang).toFloat()
            val y0 = 50f + 20f * sin(ang).toFloat()
            val x1 = 50f + 28f * cos(ang).toFloat()
            val y1 = 50f + 28f * sin(ang).toFloat()
            drawLine(x0, y0, x1, y1, stroke)
        }
        fill.color = indigo
        drawCircle(50f, 50f, 9f, fill)
    }

    /** White swap arrows on a blue square. */
    private fun Canvas.convert() {
        rr(8.3f, 8.3f, 83.3f, convBlue, 12.5f)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 6f
        drawLine(24f, 37f, 74f, 37f, stroke)
        drawLine(76f, 63f, 26f, 63f, stroke)
        fill.color = Color.WHITE
        val headR = Path().apply {
            moveTo(78f, 37f)
            lineTo(62f, 23f)
            lineTo(62f, 51f)
            close()
        }
        drawPath(headR, fill)
        val headL = Path().apply {
            moveTo(22f, 63f)
            lineTo(38f, 49f)
            lineTo(38f, 77f)
            close()
        }
        drawPath(headL, fill)
    }

    /** EXACTLY the PDF Rotate geometry — teal square + the two
     * traced white double-arrow paths copied verbatim from
     * PdfIconView.rotate (v0.1.158, Fix #51). */
    private fun Canvas.rotate() {
        rr(8.3f, 8.3f, 83.3f, rotateTeal, 12.5f)
        fill.color = Color.WHITE
        // Arrow 0: left arc + top arrowhead (30 pts, traced).
        val arrow0 = Path().apply {
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
        val arrow1 = Path().apply {
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

    /** White palette on a purple square. */
    private fun Canvas.color() {
        rr(8.3f, 8.3f, 83.3f, colorPurple, 12.5f)
        fill.color = Color.WHITE
        drawCircle(50f, 50f, 24f, fill)
        fill.color = Color.parseColor("#FF5252")
        drawCircle(40f, 40f, 5f, fill)
        fill.color = Color.parseColor("#4285F4")
        drawCircle(60f, 40f, 5f, fill)
        fill.color = Color.parseColor("#00C853")
        drawCircle(50f, 58f, 5f, fill)
        fill.color = colorPurple
        drawCircle(62f, 62f, 5f, fill)
    }

    /** White film strip + music note on a deep purple square. */
    private fun Canvas.audio() {
        rr(8.3f, 8.3f, 83.3f, audioPurple, 12.5f)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 4f
        drawRoundRect(RectF(16f, 30f, 52f, 70f), 6f, 6f, stroke)
        fill.color = Color.WHITE
        for (fy in listOf(38f, 47f, 56f)) {
            drawRoundRect(RectF(16f, fy, 25f, fy + 6f), 2f, 2f, fill)
            drawRoundRect(RectF(43f, fy, 52f, fy + 6f), 2f, 2f, fill)
        }
        drawOval(RectF(55f, 62f, 69f, 74f), fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 4f
        drawLine(67f, 68f, 67f, 32f, stroke)
        fill.color = Color.WHITE
        val flag = Path().apply {
            moveTo(67f, 32f)
            lineTo(81f, 41f)
            lineTo(67f, 45f)
            close()
        }
        drawPath(flag, fill)
    }

    /** White scissors on a pink square. */
    private fun Canvas.cutter() {
        rr(8.3f, 8.3f, 83.3f, cutterPink, 12.5f)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 4f
        drawCircle(28f, 64f, 8f, stroke)
        drawCircle(28f, 36f, 8f, stroke)
        stroke.strokeWidth = 5f
        drawLine(34f, 58f, 80f, 28f, stroke)
        drawLine(34f, 42f, 80f, 72f, stroke)
    }

    /** White image frame on an orange square. */
    private fun Canvas.thumb() {
        rr(8.3f, 8.3f, 83.3f, thumbOrange, 12.5f)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 4f
        drawRoundRect(RectF(22f, 29f, 78f, 71f), 6f, 6f, stroke)
        fill.color = Color.WHITE
        drawCircle(33f, 40f, 5f, fill)
        val m1 = Path().apply {
            moveTo(26f, 67f)
            lineTo(44f, 44f)
            lineTo(58f, 67f)
            close()
        }
        drawPath(m1, fill)
        val m2 = Path().apply {
            moveTo(52f, 67f)
            lineTo(66f, 51f)
            lineTo(74f, 67f)
            close()
        }
        drawPath(m2, fill)
    }

    /** White crop brackets on a cyan square. */
    private fun Canvas.crop() {
        rr(8.3f, 8.3f, 83.3f, cropCyan, 12.5f)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 5f
        drawLine(27f, 27f, 45f, 27f, stroke)
        drawLine(27f, 27f, 27f, 45f, stroke)
        drawLine(73f, 27f, 55f, 27f, stroke)
        drawLine(73f, 27f, 73f, 45f, stroke)
        drawLine(27f, 73f, 45f, 73f, stroke)
        drawLine(27f, 73f, 27f, 55f, stroke)
        drawLine(73f, 73f, 55f, 73f, stroke)
        drawLine(73f, 73f, 73f, 55f, stroke)
    }
}
