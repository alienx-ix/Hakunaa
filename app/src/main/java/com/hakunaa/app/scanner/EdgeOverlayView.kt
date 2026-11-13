package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

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
    private var points: FloatArray? = null

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
            points = if (corners == null || corners.size < 8) {
                null
            } else {
                mapToView(corners, rotationDegrees, imageWidth, imageHeight)
            }
            invalidate()
        }
    }

    /** Hide the frame. */
    fun clear() {
        post {
            points = null
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
        // view, centred. The preview is the same aspect, so the
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
        return out
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pts = points ?: return
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
