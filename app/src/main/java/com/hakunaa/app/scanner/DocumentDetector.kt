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
import kotlin.math.roundToInt
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
        var gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            gray[i] = 0.299f * ((p shr 16) and 0xFF) +
                0.587f * ((p shr 8) and 0xFF) +
                0.114f * (p and 0xFF)
        }
        // NEW (v0.1.42) — soften sensor noise before
        // thresholding. A 3x3 blur on the small image costs
        // almost nothing and stops single noisy pixels from
        // wobbling the detected edges frame to frame (the
        // shaking box).
        gray = boxBlurGray(gray, w, h, 1)
        // Otsu's threshold: the optimal split between bright paper
        // and darker background, from the actual histogram.
        val threshold = otsuThreshold(gray)

        // For each row, find the leftmost and rightmost bright pixel.
        // For each column, find the topmost and bottommost bright pixel.
        // Fit a line to each side and intersect them.
        //
        // NEW (v0.1.41) — single-page detection in an open book.
        // The spine shadow is NOT dark enough to break the Otsu
        // bright mask, so the bright region spans both pages and
        // the frame used to include the side page. But the spine
        // IS a clear valley in the column-mean brightness profile
        // (the middle rows average out text). findSpineSplit
        // finds that valley; if one side is a small partial page
        // it splits there and keeps only the biggest page. If both
        // sides are comparable (a full two-page spread) it keeps
        // the whole span — that is what the user wants then.
        val split = findSpineSplit(gray, w, h, threshold)
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
            // Split at the spine: the small side is a partial
            // page — the edge of the kept page is the spine.
            if (split != null && left >= 0 && left + 5 < split.x && split.x < right - 5) {
                if (split.splitLeft) left = split.x else right = split.x
            }
            if (left >= 0 && right > left + w * 0.3) {
                leftEdge.add(Pair(left.toFloat(), y.toFloat()))
                rightEdge.add(Pair(right.toFloat(), y.toFloat()))
            }
        }
        for (x in 0 until w) {
            // When a page was split off, its columns must not
            // pull the top/bottom lines off the kept page.
            if (split != null) {
                if (split.splitLeft && x < split.x) continue
                if (!split.splitLeft && x > split.x) continue
            }
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

        // NEW (v0.1.53) — the quad must be a REAL document, not
        // just where a bright mask happens to end. A patterned
        // background (a woven mat, a colourful cloth) is bright
        // in places, so the bright mask finds a big quadrilateral
        // in it and the live frame used to appear over a scene
        // with no document at all. A real document's edges are
        // sharp brightness steps (bright paper against a darker
        // background); a hallucinated mask boundary is not.
        // Verified on the user's own photos: a mat scene scores
        // ~0.2 mean edge strength with 1 strong side (rejected),
        // real pages score 0.56–0.75 with 2–3 strong sides
        // (accepted) — at full size and at the 320x240 live
        // analysis size, with the pre-blur applied.
        if (!hasRealEdges(gray, w, h, pts)) return null

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

    /** A detected quad is a real document only if its edges are
     *  REAL brightness steps. For each of the four sides, sample
     *  points along it and compare the grey just inside the quad
     *  with the grey just outside (4 px either side of the edge,
     *  along the inward normal). A side is "strong" when at least
     *  40% of its samples step by more than 30 grey levels —
     *  bright paper against a darker background. The quad is
     *  accepted when at least two sides are strong and the mean
     *  strength is at least 0.30. One side is allowed to be weak
     *  (a page edge against another page, or against the image
     *  border, has no step there) — but a quad with no real edge
     *  at all is a pattern, not a document, and is rejected.
     *  [pts] is TL TR BR BL in the small-image coordinates. */
    private fun hasRealEdges(gray: FloatArray, w: Int, h: Int, pts: FloatArray): Boolean {
        val corners = arrayOf(
            Pair(pts[0], pts[1]), Pair(pts[2], pts[3]),
            Pair(pts[4], pts[5]), Pair(pts[6], pts[7])
        )
        val cx = (corners[0].first + corners[1].first + corners[2].first + corners[3].first) / 4f
        val cy = (corners[0].second + corners[1].second + corners[2].second + corners[3].second) / 4f
        val sides = arrayOf(
            Pair(corners[0], corners[1]), Pair(corners[1], corners[2]),
            Pair(corners[2], corners[3]), Pair(corners[3], corners[0])
        )
        var strongSides = 0
        var totalFrac = 0f
        for ((a, b) in sides) {
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
            var nx = -dy / len
            var ny = dx / len
            val mx = (a.first + b.first) / 2f
            val my = (a.second + b.second) / 2f
            // The inward normal points toward the quad's centre.
            if ((cx - mx) * nx + (cy - my) * ny < 0) {
                nx = -nx
                ny = -ny
            }
            var strong = 0
            var total = 0
            val samples = 16
            for (k in 0 until samples) {
                val t = (k + 0.5f) / samples
                val x = a.first + dx * t
                val y = a.second + dy * t
                val inside = grayAt(gray, w, h, x + nx * 4f, y + ny * 4f)
                val outside = grayAt(gray, w, h, x - nx * 4f, y - ny * 4f)
                if (inside == null || outside == null) continue
                total++
                if (abs(inside - outside) > 30f) strong++
            }
            val frac = if (total == 0) 0f else strong.toFloat() / total
            totalFrac += frac
            if (frac >= 0.4f) strongSides++
        }
        return totalFrac / 4f >= 0.30f && strongSides >= 2
    }

    /** The grey value at a (possibly fractional) position, or
     *  null when it is outside the image. */
    private fun grayAt(gray: FloatArray, w: Int, h: Int, x: Float, y: Float): Float? {
        val xi = x.roundToInt()
        val yi = y.roundToInt()
        if (xi < 0 || yi < 0 || xi >= w || yi >= h) return null
        return gray[yi * w + xi]
    }

    /** The book's spine, found as a valley in the column-mean
     *  brightness profile. Returns the valley column and which
     *  side is the small partial page — or null when there is no
     *  valley worth splitting on (a single sheet, or a full
     *  two-page spread where both sides are comparable).
     *
     *  Why a valley, not a threshold gap: the spine shadow
     *  (~130 gray) sits ABOVE the Otsu threshold (~92), so the
     *  bright mask never splits there. But averaged over the
     *  middle rows (text averages out) the spine is a clear dip
     *  (~30 levels on a single page, ~60 on a spread) between
     *  two bright plateaus. Verified on the user's own photos:
     *  the INDEX page's valley split the side page off (left
     *  part 71 px vs right 402 px), while the two-page notes
     *  photo kept its spread (255 vs 288). */
    private data class SpineSplit(val x: Int, val splitLeft: Boolean)

    private fun findSpineSplit(gray: FloatArray, w: Int, h: Int, threshold: Float): SpineSplit? {
        // Column means over the middle rows.
        val y0 = h / 4
        val y1 = 3 * h / 4
        val rows = (y1 - y0).coerceAtLeast(1)
        val colMean = FloatArray(w)
        for (x in 0 until w) {
            var sum = 0f
            for (y in y0 until y1) sum += gray[y * w + x]
            colMean[x] = sum / rows
        }
        // Smooth (moving average, window 5).
        val sm = FloatArray(w)
        for (x in 0 until w) {
            var sum = 0f
            var n = 0
            for (k in -2..2) {
                val xx = x + k
                if (xx in 0 until w) {
                    sum += colMean[xx]
                    n++
                }
            }
            sm[x] = sum / n
        }
        // Deepest valley between two bright plateaus.
        var bestX = -1
        var bestDepth = 0f
        val xStart = (w * 0.08f).toInt()
        val xEnd = (w * 0.92f).toInt()
        for (x in xStart..xEnd) {
            var leftPlateau = sm[x]
            for (k in x - 60..x - 8) {
                if (k in 0 until w) leftPlateau = max(leftPlateau, sm[k])
            }
            var rightPlateau = sm[x]
            for (k in x + 8..x + 60) {
                if (k in 0 until w) rightPlateau = max(rightPlateau, sm[k])
            }
            val depth = min(leftPlateau, rightPlateau) - sm[x]
            if (depth < 20f) continue
            if (min(leftPlateau, rightPlateau) < threshold + 20f) continue
            if (depth > bestDepth) {
                bestDepth = depth
                bestX = x
            }
        }
        if (bestX < 0) return null
        // Bright span from the smoothed column means.
        var brightLeft = -1
        var brightRight = -1
        for (x in 0 until w) {
            if (sm[x] > threshold) {
                if (brightLeft < 0) brightLeft = x
                brightRight = x
            }
        }
        if (brightLeft < 0) return null
        val leftPart = bestX - brightLeft
        val rightPart = brightRight - bestX
        // Both sides comparable = a real two-page spread: keep it.
        if (min(leftPart, rightPart) >= 0.5f * max(leftPart, rightPart)) return null
        return SpineSplit(bestX, leftPart < rightPart)
    }

    /** Separable box blur on a single-channel float image
     *  (running sums, O(n) whatever the radius). Used to soften
     *  sensor noise before thresholding. */
    private fun boxBlurGray(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            var count = 0
            for (k in 0..r) {
                sum += src[row + k]
                count++
            }
            for (x in 0 until w) {
                tmp[row + x] = sum / count
                val addX = x + r + 1
                if (addX < w) {
                    sum += src[row + addX]
                    count++
                }
                val remX = x - r
                if (remX >= 0) {
                    sum -= src[row + remX]
                    count--
                }
            }
        }
        for (x in 0 until w) {
            var sum = 0f
            var count = 0
            for (k in 0..r) {
                sum += tmp[k * w + x]
                count++
            }
            for (y in 0 until h) {
                out[y * w + x] = sum / count
                val addY = y + r + 1
                if (addY < h) {
                    sum += tmp[addY * w + x]
                    count++
                }
                val remY = y - r
                if (remY >= 0) {
                    sum -= tmp[remY * w + x]
                    count--
                }
            }
        }
        return out
    }

    /** The book's spine as a column, found as a valley in the
     *  column-mean brightness profile — the same valley
     *  findSpineSplit uses, but WITHOUT the split decision: a
     *  full two-page spread is a valley too, and Book mode wants
     *  its position. Returns the valley column, or null. */
    private fun findSpineValleyX(gray: FloatArray, w: Int, h: Int, threshold: Float): Int? {
        // Column means over the middle rows.
        val y0 = h / 4
        val y1 = 3 * h / 4
        val rows = (y1 - y0).coerceAtLeast(1)
        val colMean = FloatArray(w)
        for (x in 0 until w) {
            var sum = 0f
            for (y in y0 until y1) sum += gray[y * w + x]
            colMean[x] = sum / rows
        }
        // Smooth (moving average, window 5).
        val sm = FloatArray(w)
        for (x in 0 until w) {
            var sum = 0f
            var n = 0
            for (k in -2..2) {
                val xx = x + k
                if (xx in 0 until w) {
                    sum += colMean[xx]
                    n++
                }
            }
            sm[x] = sum / n
        }
        // Deepest valley between two bright plateaus.
        var bestX = -1
        var bestDepth = 0f
        val xStart = (w * 0.08f).toInt()
        val xEnd = (w * 0.92f).toInt()
        for (x in xStart..xEnd) {
            var leftPlateau = sm[x]
            for (k in x - 60..x - 8) {
                if (k in 0 until w) leftPlateau = max(leftPlateau, sm[k])
            }
            var rightPlateau = sm[x]
            for (k in x + 8..x + 60) {
                if (k in 0 until w) rightPlateau = max(rightPlateau, sm[k])
            }
            val depth = min(leftPlateau, rightPlateau) - sm[x]
            if (depth < 20f) continue
            if (min(leftPlateau, rightPlateau) < threshold + 20f) continue
            if (depth > bestDepth) {
                bestDepth = depth
                bestX = x
            }
        }
        return if (bestX < 0) null else bestX
    }

    /** The spine's horizontal position in [bitmap] as a fraction
     *  (0..1) of its width — for the Book mode's spine line.
     *  Null when no spine valley is visible. */
    fun spineFraction(bitmap: Bitmap): Float? {
        return try {
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
            var gray = FloatArray(w * h)
            for (i in pixels.indices) {
                val p = pixels[i]
                gray[i] = 0.299f * ((p shr 16) and 0xFF) +
                    0.587f * ((p shr 8) and 0xFF) +
                    0.114f * (p and 0xFF)
            }
            gray = boxBlurGray(gray, w, h, 1)
            val threshold = otsuThreshold(gray)
            val valleyX = findSpineValleyX(gray, w, h, threshold) ?: return null
            valleyX.toFloat() / w
        } catch (_: Throwable) {
            null
        }
    }

    /** Split an open-book photo into its two pages (A = left,
     *  B = right), each perspective-corrected. [spineFrac] is
     *  the spine's position (0..1 of the width) — the live line
     *  the user dragged, or null to auto-detect it. The spine's
     *  top and bottom points are interpolated along the spread's
     *  top and bottom edges, so a tilted book still splits along
     *  its real spine. Falls back to a plain vertical cut when
     *  the spread's corners can't be found, or when the detector
     *  kept one page only (centre the spread in the frame).
     *  Verified on the user's two-page notes photo: the split
     *  gives two clean, complete, readable pages. */
    fun splitSpread(bitmap: Bitmap, spineFrac: Float?): Pair<Bitmap, Bitmap>? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val frac = spineFrac ?: spineFraction(bitmap) ?: 0.5f
            val spineX = (frac * w).coerceIn(1f, (w - 1).toFloat())
            val corners = detectCorners(bitmap)
            val span = if (corners == null) 0f else {
                val xs = listOf(corners[0], corners[2], corners[4], corners[6])
                xs.max() - xs.min()
            }
            if (corners == null || span < w * 0.7f) {
                val left = Bitmap.createBitmap(bitmap, 0, 0, spineX.toInt(), h)
                val right = Bitmap.createBitmap(bitmap, spineX.toInt(), 0, w - spineX.toInt(), h)
                return Pair(left, right)
            }
            val tl = Pair(corners[0], corners[1])
            val tr = Pair(corners[2], corners[3])
            val br = Pair(corners[4], corners[5])
            val bl = Pair(corners[6], corners[7])
            val spineTop = pointOnEdge(tl, tr, spineX)
            val spineBottom = pointOnEdge(bl, br, spineX)
            val leftCorners = floatArrayOf(
                tl.first, tl.second,
                spineTop.first, spineTop.second,
                spineBottom.first, spineBottom.second,
                bl.first, bl.second
            )
            val rightCorners = floatArrayOf(
                spineTop.first, spineTop.second,
                tr.first, tr.second,
                br.first, br.second,
                spineBottom.first, spineBottom.second
            )
            val left = warpToRectangle(bitmap, leftCorners)
            val right = warpToRectangle(bitmap, rightCorners)
            if (left == null || right == null) {
                val l = Bitmap.createBitmap(bitmap, 0, 0, spineX.toInt(), h)
                val r = Bitmap.createBitmap(bitmap, spineX.toInt(), 0, w - spineX.toInt(), h)
                return Pair(l, r)
            }
            Pair(left, right)
        } catch (_: Throwable) {
            null
        }
    }

    /** The point on the straight edge p1->p2 whose x is [x]. */
    private fun pointOnEdge(p1: Pair<Float, Float>, p2: Pair<Float, Float>, x: Float): Pair<Float, Float> {
        val t = if (p2.first != p1.first) {
            ((x - p1.first) / (p2.first - p1.first)).coerceIn(0f, 1f)
        } else 0f
        return Pair(x, p1.second + t * (p2.second - p1.second))
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
