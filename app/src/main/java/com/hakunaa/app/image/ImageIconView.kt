package com.hakunaa.app.image

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
 * Code-drawn Image Tools icons — v0.1.169.
 * User-approved mockup v40 (2026-11-24): diagonal Canvas LinearGradient
 * rounded squares (light -> dark) + light-tint glyphs, EXACTLY the
 * Video Tools style (VideoIconView.kt). This is NOT VectorDrawable
 * <gradient> (which does not render on the user's phone, proven
 * v0.1.159/160) — LinearGradient is drawn at runtime.
 * Every icon is drawn in a 100x100 box and scaled to the view size.
 * Layout/cards: 2x5, #F5F8FF, blue border #1E88E5, icons 48dp.
 *
 * Geometry (from make_image_tools_mockup40.py):
 * - SMART: pink, 5-point star + 4 OUTWARD corner arrows (tips +-33).
 * - COMPRESS: orange, settings GEAR + 4 outward corner arrows.
 * - CROP: indigo, 4 corner brackets.
 * - RESIZE: light teal, 4 diagonal expand arrows.
 * - SOCIAL: slate, Post style (rounded square + circle + dot).
 * - CONVERT: blue, 2 straight horizontal swap arrows.
 * - COLOR: purple, 3 RGB rings in NEW colours (cyan/yellow/white).
 * - DP: yellow, person silhouette.
 * - PDF: light red, simple photo (frame + sun + mountains).
 * - META: green, eye with slash (metadata hidden).
 */
class ImageIconView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Kind {
        SMART, COMPRESS, CROP, RESIZE, SOCIAL, CONVERT, COLOR, DP, PDF, META
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

    // Gradient pairs (light -> dark, diagonal) per icon, from mockup v40.
    private val smartG = "#EC407A" to "#AD1457"
    private val compressG = "#FFB74D" to "#EF6C00"
    private val cropG = "#5C6BC0" to "#283593"
    private val resizeG = "#4DB6AC" to "#00695C"
    private val socialG = "#607D8B" to "#263238"
    private val convG = "#42A5F5" to "#1565C0"
    private val colorG = "#8E24AA" to "#4A148C"
    private val dpG = "#FFCA28" to "#FF8F00"
    private val pdfG = "#E57373" to "#D32F2F"
    private val metaG = "#66BB6A" to "#2E7D32"

    // Light-tint glyph colours, from mockup v40.
    private val tintSmart = Color.parseColor("#F8BBD0")
    private val tintCompress = Color.parseColor("#FFE082")
    private val tintCrop = Color.parseColor("#C5CAE9")
    private val tintResize = Color.parseColor("#B2DFDB")
    private val tintSocial = Color.parseColor("#CFD8DC")
    private val tintConv = Color.parseColor("#BBDEFB")
    private val tintDp = Color.parseColor("#FFECB3")
    private val tintPdf = Color.WHITE
    private val tintMeta = Color.parseColor("#C8E6C9")

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height) / 100f
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - 100f * s) / 2f, (height - 100f * s) / 2f)
        canvas.scale(s, s)
        when (kind) {
            Kind.SMART -> canvas.smart()
            Kind.COMPRESS -> canvas.compress()
            Kind.CROP -> canvas.crop()
            Kind.RESIZE -> canvas.resize()
            Kind.SOCIAL -> canvas.social()
            Kind.CONVERT -> canvas.convert()
            Kind.COLOR -> canvas.color()
            Kind.DP -> canvas.dp()
            Kind.PDF -> canvas.pdf()
            Kind.META -> canvas.meta()
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

    /** OUTWARD corner arrow (mockup v40): tip at +-33, shaft inward to
     * +-22, barbs 8. Same shape as VideoIconView's old thin inward arrow. */
    private fun Canvas.cornerArrowOut(cx: Float, cy: Float, sx: Float, sy: Float, color: Int) {
        val x0 = cx + sx * 33f
        val y0 = cy + sy * 33f
        stroke.color = color
        stroke.strokeWidth = 4.5f
        drawLine(x0, y0, cx + sx * 22f, cy + sy * 22f, stroke)
        drawLine(x0, y0, x0 - sx * 8f, y0, stroke)
        drawLine(x0, y0, x0, y0 - sy * 8f, stroke)
    }

    private fun Canvas.fourCornerArrowsOut(color: Int) {
        cornerArrowOut(50f, 50f, -1f, -1f, color)
        cornerArrowOut(50f, 50f, 1f, -1f, color)
        cornerArrowOut(50f, 50f, -1f, 1f, color)
        cornerArrowOut(50f, 50f, 1f, 1f, color)
    }

    /** Pink gradient + 5-point star + 4 outward corner arrows. */
    private fun Canvas.smart() {
        gradRect(8.3f, 8.3f, 83.3f, smartG)
        fourCornerArrowsOut(tintSmart)
        fill.color = tintSmart
        val star = Path().apply {
            moveTo(50f, 28f)
            lineTo(55.3f, 42.7f)
            lineTo(70.9f, 43.2f)
            lineTo(58.6f, 52.8f)
            lineTo(62.9f, 67.8f)
            lineTo(50f, 59f)
            lineTo(37.1f, 67.8f)
            lineTo(41.4f, 52.8f)
            lineTo(29.1f, 43.2f)
            lineTo(44.7f, 42.7f)
            close()
        }
        drawPath(star, fill)
    }

    /** Orange gradient + settings GEAR + 4 outward corner arrows. */
    private fun Canvas.compress() {
        gradRect(8.3f, 8.3f, 83.3f, compressG)
        fourCornerArrowsOut(tintCompress)
        // Gear: ring r=13 + 8 teeth (r16 -> r21) + centre dot r=3.
        stroke.color = tintCompress
        stroke.strokeWidth = 5f
        drawCircle(50f, 50f, 13f, stroke)
        val teeth = arrayOf(
            66f to 50f to (71f to 50f),
            61.3f to 61.3f to (64.8f to 64.8f),
            50f to 66f to (50f to 71f),
            38.7f to 61.3f to (35.2f to 64.8f),
            34f to 50f to (29f to 50f),
            38.7f to 38.7f to (35.2f to 35.2f),
            50f to 34f to (50f to 29f),
            61.3f to 38.7f to (64.8f to 35.2f)
        )
        for ((from, to) in teeth) {
            drawLine(from.first, from.second, to.first, to.second, stroke)
        }
        fill.color = tintCompress
        drawCircle(50f, 50f, 3f, fill)
    }

    /** Indigo gradient + 4 corner brackets (arms 18, corners at +-26). */
    private fun Canvas.crop() {
        gradRect(8.3f, 8.3f, 83.3f, cropG)
        stroke.color = tintCrop
        stroke.strokeWidth = 5f
        // TL, TR, BL, BR: corner (ox,oy), arm directions (sx,sy).
        val corners = arrayOf(
            Triple(-1f, -1f, 1f to 1f),
            Triple(1f, -1f, -1f to 1f),
            Triple(-1f, 1f, 1f to -1f),
            Triple(1f, 1f, -1f to -1f)
        )
        for ((ox, oy, dir) in corners) {
            val x0 = 50f + ox * 26f
            val y0 = 50f + oy * 26f
            drawLine(x0, y0, x0 + dir.first * 18f, y0, stroke)
            drawLine(x0, y0, x0, y0 + dir.second * 18f, stroke)
        }
    }

    /** Light teal gradient + 4 diagonal expand arrows (outward). */
    private fun Canvas.resize() {
        gradRect(8.3f, 8.3f, 83.3f, resizeG)
        stroke.color = tintResize
        stroke.strokeWidth = 4.5f
        for ((sx, sy) in arrayOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val x0 = 50f + sx * 14f
            val y0 = 50f + sy * 14f
            val x1 = 50f + sx * 32f
            val y1 = 50f + sy * 32f
            drawLine(x0, y0, x1, y1, stroke)
            drawLine(x1, y1, x1 - sx * 10f, y1, stroke)
            drawLine(x1, y1, x1, y1 - sy * 10f, stroke)
        }
    }

    /** Slate gradient + Post style: rounded square + circle + dot. */
    private fun Canvas.social() {
        gradRect(8.3f, 8.3f, 83.3f, socialG)
        stroke.color = tintSocial
        stroke.strokeWidth = 5f
        drawRoundRect(RectF(26f, 26f, 74f, 74f), 10f, 10f, stroke)
        drawCircle(50f, 50f, 14f, stroke)
        fill.color = tintSocial
        drawCircle(66f, 34f, 4f, fill)
    }

    /** Blue gradient + 2 straight horizontal swap arrows. */
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

    /** Purple gradient + 3 RGB rings in NEW colours (cyan/yellow/white). */
    private fun Canvas.color() {
        gradRect(8.3f, 8.3f, 83.3f, colorG)
        val r = 20f
        stroke.strokeWidth = 5f
        stroke.color = Color.parseColor("#4DD0E1")
        drawCircle(38f, 40f, r, stroke)
        stroke.color = Color.parseColor("#FFD54F")
        drawCircle(62f, 40f, r, stroke)
        stroke.color = Color.WHITE
        drawCircle(50f, 62f, r, stroke)
    }

    /** Yellow gradient + person silhouette (head + shoulders). */
    private fun Canvas.dp() {
        gradRect(8.3f, 8.3f, 83.3f, dpG)
        fill.color = tintDp
        drawOval(RectF(38f, 24f, 62f, 48f), fill)
        stroke.color = tintDp
        stroke.strokeWidth = 6f
        drawArc(RectF(24f, 50f, 76f, 92f), 180f, 180f, false, stroke)
    }

    /** Light red gradient + simple photo (frame + sun + mountains). */
    private fun Canvas.pdf() {
        gradRect(8.3f, 8.3f, 83.3f, pdfG)
        stroke.color = tintPdf
        stroke.strokeWidth = 4.5f
        drawRoundRect(RectF(26f, 26f, 74f, 74f), 5f, 5f, stroke)
        fill.color = tintPdf
        drawOval(RectF(56f, 32f, 64f, 40f), fill)
        val mountains = Path().apply {
            moveTo(30f, 68f)
            lineTo(44f, 48f)
            lineTo(52f, 60f)
            lineTo(58f, 52f)
            lineTo(70f, 68f)
            close()
        }
        drawPath(mountains, fill)
    }

    /** Green gradient + eye with slash (metadata hidden). */
    private fun Canvas.meta() {
        gradRect(8.3f, 8.3f, 83.3f, metaG)
        stroke.color = tintMeta
        stroke.strokeWidth = 5f
        // Eye outline: top arc (20..160) + bottom arc (200..340).
        drawArc(RectF(26f, 34f, 74f, 66f), 20f, 140f, false, stroke)
        drawArc(RectF(26f, 34f, 74f, 66f), 200f, 140f, false, stroke)
        // Iris.
        drawCircle(50f, 50f, 8f, stroke)
        // Slash.
        drawLine(30f, 30f, 70f, 70f, stroke)
    }
}
