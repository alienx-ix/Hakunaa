package com.hakunaa.app.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Code-drawn Video Tools icons — REFERENCE style (user's image
 * "VIDEO UTILITIES APP ICONS"). Gradient rounded squares + glyphs
 * in LIGHT TINTS of the icon colour, drawn with Canvas
 * LinearGradient. NOTE: this is NOT VectorDrawable <gradient>
 * (which does not render on the user's phone, proven v0.1.159/160)
 * — LinearGradient is drawn at runtime by the graphics pipeline.
 * Every icon is drawn in a 100x100 box and scaled to the view size.
 * Layout/cards unchanged (2x5, #FFF5F8, 48dp).
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

    // Gradient pairs (light -> dark, diagonal) per icon, from the reference.
    private val quickG = "#FFB74D" to "#EF6C00"
    private val smartG = "#4DB6AC" to "#00695C"
    private val advG = "#5C6BC0" to "#283593"
    private val convG = "#42A5F5" to "#1565C0"
    private val rotG = "#4DB6AC" to "#00695C"
    private val colorG = "#3A2A5E" to "#1A0A2E"
    private val audioG = "#7E57C2" to "#4527A0"
    private val cutG = "#EC407A" to "#AD1457"
    private val thumbG = "#FFCA28" to "#FF8F00"
    private val cropG = "#26C6DA" to "#006064"

    // Light-tint glyph colours, from the reference.
    private val tintQuick = Color.parseColor("#FFE082")
    private val tintSmart = Color.parseColor("#B2DFDB")
    private val tintAdv = Color.parseColor("#BBDEFB")
    private val tintConv = Color.parseColor("#BBDEFB")
    private val tintRot = Color.parseColor("#B2DFDB")
    private val tintAudio = Color.parseColor("#D1C4E9")
    private val tintCut = Color.parseColor("#F8BBD0")
    private val tintThumb = Color.parseColor("#FFECB3")
    private val tintCrop = Color.parseColor("#B2EBF2")

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

    /** Rounded square filled with a diagonal LinearGradient. */
    private fun Canvas.gradRect(x: Float, y: Float, size: Float, g: Pair<String, String>, r: Float = 12.5f) {
        fill.shader = LinearGradient(
            x, y, x + size, y + size,
            Color.parseColor(g.first), Color.parseColor(g.second),
            Shader.TileMode.CLAMP
        )
        drawRoundRect(RectF(x, y, x + size, y + size), r, r, fill)
        fill.shader = null
    }

    /** Small arrow pointing inward, at a corner (sx,sy = -1/+1). */
    private fun Canvas.cornerArrow(cx: Float, cy: Float, sx: Float, sy: Float, color: Int) {
        val x0 = cx + sx * 38f
        val y0 = cy + sy * 38f
        val dx = -sx * 10f
        val dy = -sy * 10f
        stroke.color = color
        stroke.strokeWidth = 4.5f
        drawLine(x0, y0, x0 + dx, y0 + dy, stroke)
        drawLine(x0, y0, x0 - sx * 9f, y0, stroke)
        drawLine(x0, y0, x0, y0 - sy * 9f, stroke)
    }

    private fun Canvas.fourCornerArrows(color: Int) {
        cornerArrow(50f, 50f, -1f, -1f, color)
        cornerArrow(50f, 50f, 1f, -1f, color)
        cornerArrow(50f, 50f, -1f, 1f, color)
        cornerArrow(50f, 50f, 1f, 1f, color)
    }

    /** Orange gradient + yellow bolt + 4 yellow inward arrows. */
    private fun Canvas.quick() {
        gradRect(8.3f, 8.3f, 83.3f, quickG)
        fill.color = tintQuick
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
        fourCornerArrows(tintQuick)
    }

    /** Teal gradient + light sparkle + 4 light inward arrows. */
    private fun Canvas.smart() {
        gradRect(8.3f, 8.3f, 83.3f, smartG)
        fill.color = tintSmart
        val star = Path().apply {
            moveTo(50f, 24f)
            lineTo(57f, 43f)
            lineTo(76f, 50f)
            lineTo(57f, 57f)
            lineTo(50f, 76f)
            lineTo(43f, 57f)
            lineTo(24f, 50f)
            lineTo(43f, 43f)
            close()
        }
        drawPath(star, fill)
        fourCornerArrows(tintSmart)
    }

    /** Blue gradient + light-blue gear + 4 light inward arrows. */
    private fun Canvas.advanced() {
        gradRect(8.3f, 8.3f, 83.3f, advG)
        fill.color = tintAdv
        drawCircle(50f, 50f, 20f, fill)
        stroke.color = tintAdv
        stroke.strokeWidth = 7f
        for (a in 0 until 8) {
            val ang = Math.toRadians((a * 45).toDouble())
            val x0 = 50f + 20f * cos(ang).toFloat()
            val y0 = 50f + 20f * sin(ang).toFloat()
            val x1 = 50f + 28f * cos(ang).toFloat()
            val y1 = 50f + 28f * sin(ang).toFloat()
            drawLine(x0, y0, x1, y1, stroke)
        }
        fill.color = Color.parseColor(advG.second)
        drawCircle(50f, 50f, 9f, fill)
        fourCornerArrows(tintAdv)
    }

    /** Blue gradient + two curved circular swap arrows (light blue). */
    private fun Canvas.convert() {
        gradRect(8.3f, 8.3f, 83.3f, convG)
        stroke.color = tintConv
        stroke.strokeWidth = 6f
        drawArc(RectF(20f, 20f, 80f, 80f), 200f, 140f, false, stroke)
        drawArc(RectF(20f, 20f, 80f, 80f), 20f, 140f, false, stroke)
        fill.color = tintConv
        val headT = Path().apply {
            moveTo(80f, 32f)
            lineTo(64f, 28f)
            lineTo(78f, 46f)
            close()
        }
        drawPath(headT, fill)
        val headB = Path().apply {
            moveTo(20f, 68f)
            lineTo(36f, 72f)
            lineTo(22f, 54f)
            close()
        }
        drawPath(headB, fill)
    }

    /** Teal gradient + circular arrow + play triangle centre (light teal). */
    private fun Canvas.rotate() {
        gradRect(8.3f, 8.3f, 83.3f, rotG)
        stroke.color = tintRot
        stroke.strokeWidth = 6f
        drawArc(RectF(20f, 20f, 80f, 80f), 150f, 260f, false, stroke)
        fill.color = tintRot
        val head = Path().apply {
            moveTo(80f, 30f)
            lineTo(64f, 26f)
            lineTo(78f, 44f)
            close()
        }
        drawPath(head, fill)
        // Play triangle in the centre.
        val play = Path().apply {
            moveTo(44f, 40f)
            lineTo(44f, 60f)
            lineTo(62f, 50f)
            close()
        }
        drawPath(play, fill)
    }

    /** Dark square + 3 overlapping RGB OUTLINE rings (neon, not filled). */
    private fun Canvas.color() {
        gradRect(8.3f, 8.3f, 83.3f, colorG)
        val r = 20f
        stroke.strokeWidth = 5f
        stroke.color = Color.parseColor("#FF5252")
        drawCircle(38f, 40f, r, stroke)
        stroke.color = Color.parseColor("#448AFF")
        drawCircle(62f, 40f, r, stroke)
        stroke.color = Color.parseColor("#69F0AE")
        drawCircle(50f, 62f, r, stroke)
    }

    /** Purple gradient + ONE big bold music note (simple style, user-approved). */
    private fun Canvas.audio() {
        gradRect(8.3f, 8.3f, 83.3f, audioG)
        fill.color = tintAudio
        // Note head.
        drawOval(RectF(30f, 62f, 52f, 80f), fill)
        // Stem.
        stroke.color = tintAudio
        stroke.strokeWidth = 6f
        drawLine(50f, 68f, 50f, 18f, stroke)
        // Flag.
        fill.color = tintAudio
        val flag = Path().apply {
            moveTo(50f, 18f)
            lineTo(72f, 30f)
            lineTo(50f, 38f)
            close()
        }
        drawPath(flag, fill)
    }

    /** Red gradient + scissors + dashed line (light pink). */
    private fun Canvas.cutter() {
        gradRect(8.3f, 8.3f, 83.3f, cutG)
        stroke.color = tintCut
        stroke.strokeWidth = 4f
        drawCircle(28f, 64f, 8f, stroke)
        drawCircle(28f, 36f, 8f, stroke)
        stroke.strokeWidth = 5f
        drawLine(34f, 58f, 80f, 28f, stroke)
        drawLine(34f, 42f, 80f, 72f, stroke)
        // Dashed cut line under the scissors.
        stroke.strokeWidth = 4f
        for (i in 0 until 5) {
            val x = 16f + i * 13f
            drawLine(x, 84f, x + 8f, 84f, stroke)
        }
    }

    /** Gold gradient + image frame + mountains + + badge (light gold). */
    private fun Canvas.thumb() {
        gradRect(8.3f, 8.3f, 83.3f, thumbG)
        stroke.color = tintThumb
        stroke.strokeWidth = 4f
        drawRoundRect(RectF(20f, 28f, 72f, 68f), 6f, 6f, stroke)
        fill.color = tintThumb
        drawCircle(31f, 39f, 5f, fill)
        val m1 = Path().apply {
            moveTo(24f, 64f)
            lineTo(40f, 44f)
            lineTo(52f, 64f)
            close()
        }
        drawPath(m1, fill)
        val m2 = Path().apply {
            moveTo(48f, 64f)
            lineTo(60f, 50f)
            lineTo(68f, 64f)
            close()
        }
        drawPath(m2, fill)
        // + badge bottom-right.
        drawCircle(70f, 68f, 10f, fill)
        stroke.color = Color.parseColor(thumbG.second)
        stroke.strokeWidth = 3f
        drawLine(70f, 63f, 70f, 73f, stroke)
        drawLine(65f, 68f, 75f, 68f, stroke)
    }

    /** Teal gradient + film frame + play + crop brackets + sparkle (light cyan). */
    private fun Canvas.crop() {
        gradRect(8.3f, 8.3f, 83.3f, cropG)
        stroke.color = tintCrop
        stroke.strokeWidth = 5f
        drawLine(24f, 24f, 40f, 24f, stroke)
        drawLine(24f, 24f, 24f, 40f, stroke)
        drawLine(76f, 24f, 60f, 24f, stroke)
        drawLine(76f, 24f, 76f, 40f, stroke)
        drawLine(24f, 76f, 40f, 76f, stroke)
        drawLine(24f, 76f, 24f, 60f, stroke)
        drawLine(76f, 76f, 60f, 76f, stroke)
        drawLine(76f, 76f, 76f, 60f, stroke)
        // Film frame centre + play.
        stroke.strokeWidth = 4f
        drawRoundRect(RectF(34f, 38f, 66f, 62f), 4f, 4f, stroke)
        fill.color = tintCrop
        val play = Path().apply {
            moveTo(45f, 44f)
            lineTo(45f, 56f)
            lineTo(57f, 50f)
            close()
        }
        drawPath(play, fill)
        // Small sparkle bottom-right.
        val spark = Path().apply {
            moveTo(72f, 66f)
            lineTo(74.5f, 71.5f)
            lineTo(80f, 74f)
            lineTo(74.5f, 76.5f)
            lineTo(72f, 82f)
            lineTo(69.5f, 76.5f)
            lineTo(64f, 74f)
            lineTo(69.5f, 71.5f)
            close()
        }
        drawPath(spark, fill)
    }
}
