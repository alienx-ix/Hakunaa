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

/**
 * Code-drawn Video Tools icons — REFERENCE gradient style, v0.1.164.
 * User-approved mockup v27 (2026-11-21): diagonal Canvas LinearGradient
 * rounded squares (light -> dark) + light-tint glyphs. This is NOT
 * VectorDrawable <gradient> (which does not render on the user's phone,
 * proven v0.1.159/160) — LinearGradient is drawn at runtime.
 * Every icon is drawn in a 100x100 box and scaled to the view size.
 * Layout/cards unchanged (2x5, #FFF5F8, 48dp).
 *
 * v0.1.164 fixes (user live feedback on v0.1.163):
 * - QUICK/SMART: symbol centred at correct size, corner arrows thicker + clearer.
 * - ADVANCED: HEART in the centre (Material heart, small, perfectly centred)
 *   instead of the gear, + 4 corner arrows.
 * - CONVERT: previous 2 straight-line swap arrows (v0.1.162 style).
 * - ROTATE: the PDF Rotate traced double-arrow (v0.1.158, user-approved).
 * - AUDIO: single big music note, refined proportions.
 * - CUTTER: OPEN scissors (wide V), no bottom dashed line.
 * - THUMB: frame centred at (50,50).
 * - CROP: sparkle at bottom-right removed.
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

    /** Small arrow pointing inward, at a corner (sx,sy = -1/+1). Thicker + clearer. */
    private fun Canvas.cornerArrow(cx: Float, cy: Float, sx: Float, sy: Float, color: Int) {
        val x0 = cx + sx * 40f
        val y0 = cy + sy * 40f
        val dx = -sx * 14f
        val dy = -sy * 14f
        stroke.color = color
        stroke.strokeWidth = 6f
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

    /** Orange gradient + yellow bolt (refined, bolder) + 4 yellow inward arrows. */
    private fun Canvas.quick() {
        gradRect(8.3f, 8.3f, 83.3f, quickG)
        fill.color = tintQuick
        val bolt = Path().apply {
            moveTo(57f, 12f)
            lineTo(33f, 54f)
            lineTo(47f, 54f)
            lineTo(41f, 88f)
            lineTo(67f, 44f)
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

    /** Blue gradient + PERFECT smooth HEART (Material "favorite", small,
     * centred) + 4 light inward arrows. */
    private fun Canvas.advanced() {
        gradRect(8.3f, 8.3f, 83.3f, advG)
        fill.color = tintAdv
        val heart = Path().apply {
            moveTo(50.0f, 75.68f)
            lineTo(45.94f, 71.98f)
            cubicTo(31.52f, 58.91f, 22.0f, 50.28f, 22.0f, 39.7f)
            cubicTo(22.0f, 31.08f, 28.78f, 24.3f, 37.4f, 24.3f)
            cubicTo(42.27f, 24.3f, 46.95f, 26.57f, 50.0f, 30.15f)
            cubicTo(53.05f, 26.57f, 57.73f, 24.3f, 62.6f, 24.3f)
            cubicTo(71.22f, 24.3f, 78.0f, 31.08f, 78.0f, 39.7f)
            cubicTo(78.0f, 50.28f, 68.48f, 58.91f, 54.06f, 71.98f)
            lineTo(50.0f, 75.68f)
            close()
        }
        drawPath(heart, fill)
        fourCornerArrows(tintAdv)
    }

    /** Blue gradient + previous 2 STRAIGHT swap arrows (v0.1.162 style). */
    private fun Canvas.convert() {
        gradRect(8.3f, 8.3f, 83.3f, convG)
        stroke.color = tintConv
        stroke.strokeWidth = 6f
        drawLine(24f, 37f, 74f, 37f, stroke)
        drawLine(76f, 63f, 26f, 63f, stroke)
        fill.color = tintConv
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

    /** Teal gradient + the PDF Rotate traced double-arrow (v0.1.158,
     * user-approved "Super, Nice"), in light teal tint. */
    private fun Canvas.rotate() {
        gradRect(8.3f, 8.3f, 83.3f, rotG)
        fill.color = tintRot
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

    /** Purple gradient + ONE big bold music note (simple style, refined). */
    private fun Canvas.audio() {
        gradRect(8.3f, 8.3f, 83.3f, audioG)
        fill.color = tintAudio
        // Note head.
        drawOval(RectF(28f, 60f, 54f, 80f), fill)
        // Stem.
        stroke.color = tintAudio
        stroke.strokeWidth = 6f
        drawLine(52f, 68f, 52f, 16f, stroke)
        // Flag.
        fill.color = tintAudio
        val flag = Path().apply {
            moveTo(52f, 16f)
            lineTo(74f, 28f)
            lineTo(52f, 38f)
            close()
        }
        drawPath(flag, fill)
    }

    /** Red gradient + OPEN scissors (wide V), centred, no dashed line. */
    private fun Canvas.cutter() {
        gradRect(8.3f, 8.3f, 83.3f, cutG)
        stroke.color = tintCut
        stroke.strokeWidth = 5f
        // Handle rings.
        drawCircle(28f, 66f, 7f, stroke)
        drawCircle(28f, 34f, 7f, stroke)
        // Blades (wide V, crossing at centre).
        stroke.strokeWidth = 5f
        drawLine(34f, 62f, 74f, 30f, stroke)
        drawLine(34f, 38f, 74f, 70f, stroke)
    }

    /** Gold gradient + image frame CENTRED + mountains + + badge. */
    private fun Canvas.thumb() {
        gradRect(8.3f, 8.3f, 83.3f, thumbG)
        stroke.color = tintThumb
        stroke.strokeWidth = 4f
        drawRoundRect(RectF(22f, 30f, 78f, 70f), 6f, 6f, stroke)
        fill.color = tintThumb
        drawCircle(33f, 41f, 5f, fill)
        val m1 = Path().apply {
            moveTo(26f, 66f)
            lineTo(42f, 46f)
            lineTo(54f, 66f)
            close()
        }
        drawPath(m1, fill)
        val m2 = Path().apply {
            moveTo(50f, 66f)
            lineTo(62f, 52f)
            lineTo(70f, 66f)
            close()
        }
        drawPath(m2, fill)
        // + badge bottom-right.
        drawCircle(72f, 70f, 10f, fill)
        stroke.color = Color.parseColor(thumbG.second)
        stroke.strokeWidth = 3f
        drawLine(72f, 65f, 72f, 75f, stroke)
        drawLine(67f, 70f, 77f, 70f, stroke)
    }

    /** Teal gradient + film frame + play + crop brackets (no sparkle). */
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
    }
}
