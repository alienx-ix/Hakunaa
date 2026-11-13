package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dynamic document-edge overlay — the frame that follows the
 * book, the way professional scanners (CamScanner, Adobe Scan,
 * Microsoft Lens) do it.
 *
 * It is NOT a fixed box: every time the detector finds the
 * document's real corners, this view draws the actual
 * quadrilateral — the four detected edges, with dots on the
 * corners — right where the page is in the preview. Move the
 * phone and the frame moves with the page. No corners found,
 * no frame.
 *
 * The corners arrive in the camera analysis image's buffer
 * coordinates (landscape, unrotated). They are mapped to view
 * coordinates in two steps:
 * 1. Rotate by the analysis image's rotationDegrees, so they
 *    match the portrait preview the user sees.
 * 2. Scale + centre them into the preview's fitCenter rect —
 *    the preview shows the whole frame, so the rect is the
 *    view scaled to the image aspect, centred vertically.
 * Preview and analysis use the same 4:3 target aspect, so the
 * rect matches for both.
 */
class EdgeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // The four corners in view coordinates, TL TR BR BL, or null.
    // NEW (v0.1.53) — the frame GLIDES, it never jumps. The
    // detector hands over a new quad ~4 times a second, and the
    // raw corners jitter a few pixels each time, so drawing them
    // directly made the box shake. Instead the drawn points ease
    // toward the latest target every frame (the same easing the
    // Spirit Level bubble uses), so the frame moves smoothly
    // with the page, with no lag — it settles in ~150 ms.
    private var points: FloatArray? = null
    private var targetPoints: FloatArray? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        alpha = 22
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.FILL
    }

    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    /**
     * Show the detected quadrilateral, or hide it when [corners]
     * is null. Safe to call from any thread (analysis runs on a
     * background thread); the mapping uses this view's measured
     * size on the UI thread.
     *
     * [corners] — TL TR BR BL in the analysis bitmap's coordinates.
     * [rotationDegrees] — the analysis image's rotation (usually
     * 90 for the back camera in portrait). [imageWidth] /
     * [imageHeight] — the analysis bitmap's size.
     */
    fun updateCorners(
        corners: FloatArray?,
        rotationDegrees: Int,
        imageWidth: Int,
        imageHeight: Int
    ) {
        post {
            targetPoints = if (corners == null || corners.size < 8) {
                null
            } else {
                mapToView(corners, rotationDegrees, imageWidth, imageHeight)
            }
            if (targetPoints == null) {
                // No document — hide the frame at once.
                points = null
            } else if (points == null) {
                // First frame: snap straight onto the target, so
                // the box appears at once. After that it eases.
                points = targetPoints?.copyOf()
            }
            invalidate()
        }
    }

    /** Hide the frame. */
    fun clear() {
        post {
            points = null
            targetPoints = null
            invalidate()
        }
    }

    /** Buffer coords -> rotated display coords -> fitCenter view coords. */
    private fun mapToView(
        corners: FloatArray,
        rotationDegrees: Int,
        imageWidth: Int,
        imageHeight: Int
    ): FloatArray {
        // Step 1: rotate the buffer into display orientation.
        // rotationDegrees is the clockwise rotation that turns
        // the buffer into the portrait image the user sees.
        val (dispW, dispH) = if (rotationDegrees == 90 || rotationDegrees == 270) {
            imageHeight.toFloat() to imageWidth.toFloat()
        } else {
            imageWidth.toFloat() to imageHeight.toFloat()
        }
        val rotated = FloatArray(8)
        for (i in 0 until 4) {
            val x = corners[i * 2]
            val y = corners[i * 2 + 1]
            when (rotationDegrees) {
                90 -> {
                    rotated[i * 2] = imageHeight - y
                    rotated[i * 2 + 1] = x
                }
                180 -> {
                    rotated[i * 2] = imageWidth - x
                    rotated[i * 2 + 1] = imageHeight - y
                }
                270 -> {
                    rotated[i * 2] = y
                    rotated[i * 2 + 1] = imageWidth - x
                }
                else -> {
                    rotated[i * 2] = x
                    rotated[i * 2 + 1] = y
                }
            }
        }
        // Step 2: fitCenter — scale the display image into this
        // view, centred. The preview is centred too, so the
        // rect matches the camera preview exactly.
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f || dispW <= 0f || dispH <= 0f) {
            return rotated
        }
        val scale = minOf(viewW / dispW, viewH / dispH)
        val offsetX = (viewW - dispW * scale) / 2f
        val offsetY = (viewH - dispH * scale) / 2f
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = offsetX + rotated[i * 2] * scale
            out[i * 2 + 1] = offsetY + rotated[i * 2 + 1] * scale
        }
        // v0.1.57: pull the frame inside the preview. When the
        // book fills the whole frame the detected corners sit
        // on the view's very edge, so the corner dots are half
        // off-screen and the user cannot see all four edges.
        // Inset each corner toward the quad's centroid so the
        // whole frame (edges + dots) is fully visible. This is
        // display-only — the actual capture still uses the full
        // detected corners (the Crop screen lets the user adjust
        // them), and auto-capture steadiness is unaffected.
        insetTowardCentroid(out, 14f * resources.displayMetrics.density)
        return out
    }

    /** Move each corner of [pts] (TL TR BR BL, view coords)
     *  toward the quad's centroid by [inset] pixels, so the
     *  frame sits fully inside the view. */
    private fun insetTowardCentroid(pts: FloatArray, inset: Float) {
        var cx = 0f
        var cy = 0f
        for (i in 0 until 4) {
            cx += pts[i * 2]
            cy += pts[i * 2 + 1]
        }
        cx /= 4f
        cy /= 4f
        for (i in 0 until 4) {
            val dx = cx - pts[i * 2]
            val dy = cy - pts[i * 2 + 1]
            val len = sqrt(dx * dx + dy * dy)
            if (len > 1e-6f) {
                pts[i * 2] += dx / len * inset
                pts[i * 2 + 1] += dy / len * inset
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Ease the drawn frame toward the latest target, so it
        // glides instead of jumping to each new detection.
        val target = targetPoints
        val current = points
        if (target == null || current == null) return
        var settled = true
        for (i in current.indices) {
            val diff = target[i] - current[i]
            current[i] += diff * 0.35f
            if (abs(diff) > 0.5f) settled = false
        }
        if (!settled) postInvalidateOnAnimation()
        val pts = current
        // Light fill so the detected page area reads clearly.
        val fill = Path().apply {
            moveTo(pts[0], pts[1])
            lineTo(pts[2], pts[3])
            lineTo(pts[4], pts[5])
            lineTo(pts[6], pts[7])
            close()
        }
        canvas.drawPath(fill, fillPaint)
        // The four detected edges — thin light lines.
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            canvas.drawLine(
                pts[i * 2], pts[i * 2 + 1],
                pts[j * 2], pts[j * 2 + 1],
                linePaint
            )
        }
        // Dots on the corners.
        val dotR = 5f * resources.displayMetrics.density
        for (i in 0 until 4) {
            canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], dotR, dotPaint)
            canvas.drawCircle(pts[i * 2], pts[i * 2 + 1], dotR, dotRingPaint)
        }
    }
}
