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
 * 2. Grayscale + Otsu's threshold: the optimal split between
 *    bright paper and darker background, computed from the
 *    actual brightness histogram — so it works in dim light,
 *    bright light, and with shadows, not just one fixed level.
 * 3. Find the page edges by scanning rows/columns for the bright
 *    region and fitting a line to each side (least squares, with
 *    outlier rejection so text or shadows near the edge do not
 *    pull the frame off).
 * 4. Intersect the four edge lines to get the four corners.
 * 5. Warp the full-resolution photo with Matrix.setPolyToPoly,
 *    which maps the four corners to a flat rectangle — the page
 *    looks straight and flat, like a real scan.
 * 6. If detection fails (corners invalid, page too small, or the
 *    photo has no clear bright page), returns null and the caller
 *    keeps the original photo. The Crop screen also lets the user
 *    adjust the corners by hand — auto + manual, the way
 *    professional scanners do it.
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

    /** Detect the four document corners in [bitmap]. Returns null if not found.
     *  Corners are in full-resolution bitmap coordinates, in the order
     *  top-left, top-right, bottom-right, bottom-left. */
    fun detectCorners(bitmap: Bitmap): FloatArray? {
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

        // Grayscale.
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = 0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)
        }
        // Otsu's threshold: the optimal split between bright paper
        // and darker background, from the actual histogram.
        val threshold = otsuThreshold(gray)

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

    /** Otsu's method: the threshold that best separates the bright
     *  paper from the darker background, computed from the
     *  brightness histogram. Adapts to dim light, bright light
     *  and shadows — a fixed threshold cannot do that. */
    private fun otsuThreshold(gray: FloatArray): Float {
        val hist = IntArray(256)
        for (v in gray) {
            hist[v.toInt().coerceIn(0, 255)]++
        }
        val total = gray.size
        var sum = 0.0
        for (i in 0..255) sum += i * hist[i]
        var sumB = 0.0
        var wB = 0
        var maxVariance = -1.0
        var best = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = total - wB
            if (wF == 0) break
            sumB += t * hist[t]
            val mB = sumB / wB
            val mF = (sum - sumB) / wF
            val variance = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (variance > maxVariance) {
                maxVariance = variance
                best = t
            }
        }
        // Keep the threshold in a sane range for paper detection.
        return best.toFloat().coerceIn(80f, 220f)
    }

    /** Fit x = a*y + b (a near-vertical edge). Returns Line(a, b, 0) in ax + by + c = 0 form. */
    private fun fitVertical(points: List<Pair<Float, Float>>): Line? {
        return fitVerticalRobust(points)
    }

    /** Two-pass least squares: fit, drop points far from the line
     *  (text or shadows near the edge), then fit again. */
    private fun fitVerticalRobust(points: List<Pair<Float, Float>>): Line? {
        val first = fitVerticalOnce(points) ?: return null
        val residuals = points.map { (x, y) ->
            abs(x - (first.first * y + first.second))
        }
        val median = residuals.sorted()[residuals.size / 2]
        val cutoff = max(4f, median * 3f)
        val inliers = points.filterIndexed { i, _ -> residuals[i] <= cutoff }
        if (inliers.size >= 3) {
            return fitVerticalOnce(inliers)?.let { (a, b) -> Line(1f, -a, -b) }
        }
        return Line(1f, -first.first, -first.second)
    }

    private fun fitVerticalOnce(points: List<Pair<Float, Float>>): Pair<Float, Float>? {
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
        return Pair(a, b)
    }

    /** Fit y = a*x + b (a near-horizontal edge). Returns Line in ax + by + c = 0 form. */
    private fun fitHorizontal(points: List<Pair<Float, Float>>): Line? {
        return fitHorizontalRobust(points)
    }

    private fun fitHorizontalRobust(points: List<Pair<Float, Float>>): Line? {
        val first = fitHorizontalOnce(points) ?: return null
        val residuals = points.map { (x, y) ->
            abs(y - (first.first * x + first.second))
        }
        val median = residuals.sorted()[residuals.size / 2]
        val cutoff = max(4f, median * 3f)
        val inliers = points.filterIndexed { i, _ -> residuals[i] <= cutoff }
        if (inliers.size >= 3) {
            return fitHorizontalOnce(inliers)?.let { (a, b) -> Line(a, -1f, b) }
        }
        return Line(first.first, -1f, first.second)
    }

    private fun fitHorizontalOnce(points: List<Pair<Float, Float>>): Pair<Float, Float>? {
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
        return Pair(a, b)
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
    fun warpToRectangle(bitmap: Bitmap, corners: FloatArray): Bitmap? {
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
