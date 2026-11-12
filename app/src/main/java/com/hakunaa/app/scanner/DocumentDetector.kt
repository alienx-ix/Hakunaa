package com.hakunaa.app.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Document edge detection + perspective correction, pure Kotlin.
 * No OpenCV — that would add a 20 MB native library to the APK,
 * and the user already wants the app smaller, not bigger.
 *
 * How it works:
 * 1. Downscale the photo (max 800 px) for speed.
 * 2. Grayscale + a simple brightness threshold: a document is
 *    bright paper on a darker background, so the bright region
 *    is the page.
 * 3. Find the page edges by scanning rows/columns for the bright
 *    region and fitting a line to each side (least squares).
 * 4. Intersect the four edge lines to get the four corners.
 * 5. Warp the full-resolution photo with Matrix.setPolyToPoly,
 *    which maps the four corners to a flat rectangle — the page
 *    looks straight and flat, like a real scan.
 * 6. If detection fails (corners invalid, page too small, or the
 *    photo has no clear bright page), returns null and the caller
 *    keeps the original photo.
 */
object DocumentDetector {

    private data class Line(val a: Float, val b: Float, val c: Float)

    /** Straighten [input] and write the result to a new file. Returns null on failure. */
    fun straighten(input: File): File? {
        return try {
            val full = BitmapFactory.decodeFile(input.absolutePath) ?: return null
            val corners = detectCorners(full) ?: run {
                full.recycle()
                return null
            }
            val out = warpToRectangle(full, corners)
            full.recycle()
            if (out == null) return null
            val outFile = File(input.parent, "straightened_${System.currentTimeMillis()}.jpg")
            outFile.outputStream().use { stream ->
                out.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            }
            out.recycle()
            outFile
        } catch (_: Throwable) {
            null
        }
    }

    /** Detect the four document corners in [bitmap]. Returns null if not found. */
    private fun detectCorners(bitmap: Bitmap): FloatArray? {
        // Downscale for speed.
        val maxSide = 800
        val scale = min(1f, maxSide.toFloat() / max(bitmap.width, bitmap.height).toFloat())
        val smallW = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val smallH = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, smallW, smallH, true)
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()

        // Grayscale + brightness.
        val gray = FloatArray(w * h)
        var sum = 0f
        for (i in pixels.indices) {
            val p = pixels[i]
            val lum = 0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)
            gray[i] = lum
            sum += lum
        }
        val mean = sum / gray.size
        // A page is bright; use a threshold a bit above the mean.
        val threshold = min(200f, mean + 25f)

        // For each row, find the leftmost and rightmost bright pixel.
        // For each column, find the topmost and bottommost bright pixel.
        // Fit a line to each side and intersect them.
        val leftEdge = mutableListOf<Pair<Float, Float>>()
        val rightEdge = mutableListOf<Pair<Float, Float>>()
        val topEdge = mutableListOf<Pair<Float, Float>>()
        val bottomEdge = mutableListOf<Pair<Float, Float>>()
        for (y in 0 until h) {
            var left = -1
            var right = -1
            for (x in 0 until w) {
                if (gray[y * w + x] > threshold) {
                    if (left < 0) left = x
                    right = x
                }
            }
            if (left >= 0 && right > left + w * 0.3) {
                leftEdge.add(Pair(left.toFloat(), y.toFloat()))
                rightEdge.add(Pair(right.toFloat(), y.toFloat()))
            }
        }
        for (x in 0 until w) {
            var top = -1
            var bottom = -1
            for (y in 0 until h) {
                if (gray[y * w + x] > threshold) {
                    if (top < 0) top = y
                    bottom = y
                }
            }
            if (top >= 0 && bottom > top + h * 0.3) {
                topEdge.add(Pair(x.toFloat(), top.toFloat()))
                bottomEdge.add(Pair(x.toFloat(), bottom.toFloat()))
            }
        }
        if (leftEdge.size < h * 0.3 || rightEdge.size < h * 0.3 ||
            topEdge.size < w * 0.3 || bottomEdge.size < w * 0.3
        ) {
            return null
        }
        // Fit x = a*y + b for left/right, y = a*x + b for top/bottom.
        val leftLine = fitVertical(leftEdge) ?: return null
        val rightLine = fitVertical(rightEdge) ?: return null
        val topLine = fitHorizontal(topEdge) ?: return null
        val bottomLine = fitHorizontal(bottomEdge) ?: return null

        // Intersect: TL = top & left, TR = top & right,
        // BR = bottom & right, BL = bottom & left.
        val tl = intersect(topLine, leftLine) ?: return null
        val tr = intersect(topLine, rightLine) ?: return null
        val br = intersect(bottomLine, rightLine) ?: return null
        val bl = intersect(bottomLine, leftLine) ?: return null

        // Validate: corners must be inside the image and form a
        // reasonable quadrilateral (not too small, not degenerate).
        val pts = floatArrayOf(tl.first, tl.second, tr.first, tr.second, br.first, br.second, bl.first, bl.second)
        val minX = pts.filterIndexed { i, _ -> i % 2 == 0 }.min()
        val maxX = pts.filterIndexed { i, _ -> i % 2 == 0 }.max()
        val minY = pts.filterIndexed { i, _ -> i % 2 == 1 }.min()
        val maxY = pts.filterIndexed { i, _ -> i % 2 == 1 }.max()
        if (minX < -w * 0.05f || maxX > w * 1.05f || minY < -h * 0.05f || maxY > h * 1.05f) return null
        if (maxX - minX < w * 0.25f || maxY - minY < h * 0.25f) return null

        // Scale corners back to full resolution.
        val invScale = 1f / scale
        return floatArrayOf(
            (tl.first * invScale).coerceIn(0f, bitmap.width.toFloat()),
            (tl.second * invScale).coerceIn(0f, bitmap.height.toFloat()),
            (tr.first * invScale).coerceIn(0f, bitmap.width.toFloat()),
            (tr.second * invScale).coerceIn(0f, bitmap.height.toFloat()),
            (br.first * invScale).coerceIn(0f, bitmap.width.toFloat()),
            (br.second * invScale).coerceIn(0f, bitmap.height.toFloat()),
            (bl.first * invScale).coerceIn(0f, bitmap.width.toFloat()),
            (bl.second * invScale).coerceIn(0f, bitmap.height.toFloat())
        )
    }

    /** Fit x = a*y + b (a near-vertical edge). Returns Line(a, b, 0) in ax + by + c = 0 form. */
    private fun fitVertical(points: List<Pair<Float, Float>>): Line? {
        // Least squares for x = a*y + b.
        var n = 0
        var sumY = 0.0
        var sumX = 0.0
        var sumYY = 0.0
        var sumYX = 0.0
        for ((x, y) in points) {
            n++
            sumY += y
            sumX += x
            sumYY += y * y
            sumYX += y * x
        }
        if (n < 3) return null
        val denom = n * sumYY - sumY * sumY
        if (abs(denom) < 1e-6) return null
        val a = ((n * sumYX - sumY * sumX) / denom).toFloat()
        val b = ((sumX - a * sumY) / n).toFloat()
        // x - a*y - b = 0
        return Line(1f, -a, -b)
    }

    /** Fit y = a*x + b (a near-horizontal edge). Returns Line in ax + by + c = 0 form. */
    private fun fitHorizontal(points: List<Pair<Float, Float>>): Line? {
        var n = 0
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for ((x, y) in points) {
            n++
            sumX += x
            sumY += y
            sumXX += x * x
            sumXY += x * y
        }
        if (n < 3) return null
        val denom = n * sumXX - sumX * sumX
        if (abs(denom) < 1e-6) return null
        val a = ((n * sumXY - sumX * sumY) / denom).toFloat()
        val b = ((sumY - a * sumX) / n).toFloat()
        // a*x - y + b = 0
        return Line(a, -1f, b)
    }

    /** Intersect two lines (ax + by + c = 0). Returns (x, y) or null if parallel. */
    private fun intersect(l1: Line, l2: Line): Pair<Float, Float>? {
        val det = l1.a * l2.b - l2.a * l1.b
        if (abs(det) < 1e-6f) return null
        val x = (l1.b * l2.c - l2.b * l1.c) / det
        val y = (l2.a * l1.c - l1.a * l2.c) / det
        return Pair(x, y)
    }

    /** Warp the photo so the four corners become a flat rectangle. */
    private fun warpToRectangle(bitmap: Bitmap, corners: FloatArray): Bitmap? {
        // Destination: a rectangle sized to the average edge lengths.
        val topW = dist(corners[0], corners[1], corners[2], corners[3])
        val bottomW = dist(corners[4], corners[5], corners[6], corners[7])
        val leftH = dist(corners[0], corners[1], corners[6], corners[7])
        val rightH = dist(corners[2], corners[3], corners[4], corners[5])
        val outW = ((topW + bottomW) / 2f).toInt().coerceAtLeast(1)
        val outH = ((leftH + rightH) / 2f).toInt().coerceAtLeast(1)
        val dst = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat()
        )
        val matrix = Matrix()
        if (!matrix.setPolyToPoly(corners, 0, dst, 0, 4)) return null
        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
}
