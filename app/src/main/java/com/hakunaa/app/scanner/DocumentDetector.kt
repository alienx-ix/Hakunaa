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
 * How it works (v0.1.54 — two-stage, best-in-class):
 * 1. Downscale the photo (max 800 px) for speed.
 * 2. Grayscale + a light blur + Otsu's threshold: the optimal
 *    split between bright paper and darker background, computed
 *    from the actual brightness histogram — so it works in dim
 *    light, bright light, and with shadows, not just one fixed
 *    level.
 * 3. TEXTURE mask: paper is bright AND smooth at the pattern
 *    scale (low local standard deviation over a window about
 *    1/16 of the image), while a patterned background (a woven
 *    mat, a colourful cloth) is bright in places but textured
 *    everywhere. The mask isolates the paper on ANY background.
 * 4. SNAP outward: the texture mask is inset (it only covers
 *    the smooth interior), so each side is snapped outward to
 *    the strongest brightness drop nearby — the true page edge
 *    — and the four snapped lines are intersected for precise
 *    corners. A side at the image border keeps its rough line.
 * 5. VALIDATE twice: the interior must be mostly paper (paper
 *    fraction >= 0.45) AND the edges must be real brightness
 *    steps (hasRealEdges). A patterned background can fake
 *    strong edges, but its interior is not paper; a bright
 *    mask can fake a quad, but its edges are not real steps.
 *    Both checks together reject it.
 * 6. Falls back to the plain bright-mask detector when the
 *    texture path finds nothing.
 * 7. (v0.1.56) Falls back further to a HOUGH path for a
 *    COLOURED object (a notebook cover, an ID card): Canny
 *    edges + a Hough transform find the object's boundary
 *    lines by their edges — which do not depend on colour or
 *    brightness — and the OUTERMOST strong horizontal and
 *    vertical lines are the object's border (a printed design
 *    inside the object has stronger internal lines, so the
 *    strongest lines are NOT the border). Validated by size,
 *    aspect ratio and real edges — NOT by paper fraction, since
 *    a printed cover's interior is textured, not paper.
 * 8. Warp the full-resolution photo with Matrix.setPolyToPoly,
 *    which maps the four corners to a flat rectangle — the page
 *    looks straight and flat, like a real scan.
 * 9. If detection fails (corners invalid, page too small, or
 *    the photo has no clear bright page), returns null and the
 *    caller keeps the original photo. The Crop screen also lets
 *    the user adjust the corners by hand — auto + manual, the
 *    way professional scanners do it.
 *
 * Verified on the user's own photos before porting: the texture
 * mask + snap finds the book on the woven mat (edge strength
 * 0.89, paper fraction 0.67) where the bright mask found only
 * the whole image; it finds the real pages on clean backgrounds
 * too (edge 0.73–0.77, paper 0.52–0.63); and a mat-only scene
 * is rejected (paper fraction 0.36) even though its pattern
 * edges are strong. The Hough path was verified on the user's
 * Camlin HOLLAND cover photo on the same mat: it finds the
 * cover (top y=15, bottom y=215, left x=51, right x=269 in
 * 320x240 coords) where the texture mask covers only 4.7%
 * and the bright mask returns a wrong quad. */
object DocumentDetector {

    private data class Line(val a: Float, val b: Float, val c: Float)

    /** A pixel is "paper-like" (smooth) when its local standard
     *  deviation is below this (grey levels). Paper is smooth;
     *  a woven mat or patterned cloth is textured. */
    private const val TEXTURE_STD_MAX = 20f

    /** A quad's interior must be at least this fraction paper
     *  for it to count as a document (0.52-0.67 on real pages,
     *  0.36 on a mat-only scene). */
    private const val PAPER_FRACTION_MIN = 0.45f

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
     *  top-left, top-right, bottom-right, bottom-left.
     *
     *  Two-stage detection (v0.1.54):
     *  1. TEXTURE mask (bright AND smooth at the pattern scale) finds
     *     the paper on ANY background, including a busy woven mat.
     *  2. SNAP each side outward to the strongest brightness drop —
     *     the true page edge — and intersect the four snapped lines.
     *  3. VALIDATE twice (interior paper fraction AND real edges).
     *  Falls back to the plain bright-mask detector when the
     *  texture path finds nothing, and then (v0.1.56) to the
     *  HOUGH path, which finds a COLOURED object (a notebook
     *  cover) by its boundary edges — no paper needed. */
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
        // Soften sensor noise before thresholding (v0.1.42).
        gray = boxBlurGray(gray, w, h, 1)
        // Otsu's threshold: the optimal split between bright paper
        // and darker background, from the actual histogram.
        val threshold = otsuThreshold(gray)

        // Local texture (standard deviation over a window about
        // 1/16 of the image) — paper is smooth, a pattern is not.
        val stdRadius = max(8, min(w, h) / 16)
        val stdMap = localStdMap(gray, w, h, stdRadius)

        // Stage 1+2: texture mask -> rough quad -> snap outward.
        val textureMask = BooleanArray(w * h) { i ->
            gray[i] > threshold && stdMap[i] < TEXTURE_STD_MAX
        }
        val rough = quadFromMask(textureMask, gray, w, h, threshold)
        if (rough != null) {
            val refined = snapQuadOutward(gray, w, h, rough)
            if (refined != null && isRealDocument(gray, stdMap, w, h, refined, threshold)) {
                return scaleQuad(refined, scale, bitmap.width, bitmap.height)
            }
        }
        // Fallback: the plain bright-mask detector (v0.1.41-53).
        val brightMask = BooleanArray(w * h) { i -> gray[i] > threshold }
        val quad = quadFromMask(brightMask, gray, w, h, threshold)
        if (quad != null && isRealDocument(gray, stdMap, w, h, quad, threshold)) {
            return scaleQuad(quad, scale, bitmap.width, bitmap.height)
        }
        // Last fallback (v0.1.56): the HOUGH path for a coloured
        // object — a notebook cover, an ID card — whose interior
        // is printed/textured, not paper. It finds the object's
        // boundary by its edges (colour-independent) and takes
        // the OUTERMOST strong lines as the border.
        val houghQuad = houghQuad(gray, w, h)
        if (houghQuad != null) {
            return scaleQuad(houghQuad, scale, bitmap.width, bitmap.height)
        }
        return null
    }

    /** A quad is a real document only when BOTH hold: its interior
     *  is mostly paper (paper fraction >= 0.45) AND its edges are
     *  real brightness steps (hasRealEdges). A patterned background
     *  can fake strong edges, but its interior is not paper; a
     *  bright mask can fake a quad, but its edges are not real
     *  steps. [pts] is TL TR BR BL in the small-image coords. */
    private fun isRealDocument(
        gray: FloatArray, stdMap: FloatArray, w: Int, h: Int,
        pts: FloatArray, threshold: Float
    ): Boolean {
        if (!hasRealEdges(gray, w, h, pts)) return false
        return interiorPaperFraction(gray, stdMap, w, h, pts, threshold) >= PAPER_FRACTION_MIN
    }

    /** The fraction of the quad's interior that is paper (bright
     *  AND smooth). Sampled on a 4 px grid to keep it fast.
     *  A real document is mostly paper (0.52-0.67 on the user's
     *  photos); a patterned background is not (0.36 on the mat). */
    private fun interiorPaperFraction(
        gray: FloatArray, stdMap: FloatArray, w: Int, h: Int,
        pts: FloatArray, threshold: Float
    ): Float {
        val quad = arrayOf(
            Pair(pts[0], pts[1]), Pair(pts[2], pts[3]),
            Pair(pts[4], pts[5]), Pair(pts[6], pts[7])
        )
        var paper = 0
        var total = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (pointInQuad(x.toFloat(), y.toFloat(), quad)) {
                    total++
                    val i = y * w + x
                    if (gray[i] > threshold && stdMap[i] < TEXTURE_STD_MAX) paper++
                }
                x += 4
            }
            y += 4
        }
        return if (total == 0) 0f else paper.toFloat() / total
    }

    /** True when (x, y) lies inside the convex quad. */
    private fun pointInQuad(x: Float, y: Float, quad: Array<Pair<Float, Float>>): Boolean {
        var pos = false
        var neg = false
        for (i in 0 until 4) {
            val (x1, y1) = quad[i]
            val (x2, y2) = quad[(i + 1) % 4]
            val cross = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1)
            if (cross > 0f) pos = true
            if (cross < 0f) neg = true
            if (pos && neg) return false
        }
        return true
    }

    /** Find the page's four corners from a binary mask: scan rows
     *  and columns for the mask region (with the spine split for
     *  an open book), fit a line to each side, intersect them.
     *  Returns the quad in small-image coords (TL TR BR BL), or
     *  null when the mask has no clear quadrilateral. This is the
     *  v0.1.41-53 scan/fit logic, extracted so both the texture
     *  mask and the bright mask can use it. */
    private fun quadFromMask(
        mask: BooleanArray, gray: FloatArray, w: Int, h: Int, threshold: Float
    ): FloatArray? {
        // Single-page detection in an open book (v0.1.41): the
        // spine is a valley in the column-mean brightness profile.
        // If one side is a small partial page it splits there and
        // keeps only the biggest page; a full two-page spread is
        // kept whole.
        val split = findSpineSplit(gray, w, h, threshold)
        val leftEdge = mutableListOf<Pair<Float, Float>>()
        val rightEdge = mutableListOf<Pair<Float, Float>>()
        val topEdge = mutableListOf<Pair<Float, Float>>()
        val bottomEdge = mutableListOf<Pair<Float, Float>>()
        for (y in 0 until h) {
            var left = -1
            var right = -1
            for (x in 0 until w) {
                if (mask[y * w + x]) {
                    if (left < 0) left = x
                    right = x
                }
            }
            if (split != null && left >= 0 && left + 5 < split.x && split.x < right - 5) {
                if (split.splitLeft) left = split.x else right = split.x
            }
            if (left >= 0 && right > left + w * 0.3) {
                leftEdge.add(Pair(left.toFloat(), y.toFloat()))
                rightEdge.add(Pair(right.toFloat(), y.toFloat()))
            }
        }
        for (x in 0 until w) {
            if (split != null) {
                if (split.splitLeft && x < split.x) continue
                if (!split.splitLeft && x > split.x) continue
            }
            var top = -1
            var bottom = -1
            for (y in 0 until h) {
                if (mask[y * w + x]) {
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
        val leftLine = fitVertical(leftEdge) ?: return null
        val rightLine = fitVertical(rightEdge) ?: return null
        val topLine = fitHorizontal(topEdge) ?: return null
        val bottomLine = fitHorizontal(bottomEdge) ?: return null
        val tl = intersect(topLine, leftLine) ?: return null
        val tr = intersect(topLine, rightLine) ?: return null
        val br = intersect(bottomLine, rightLine) ?: return null
        val bl = intersect(bottomLine, leftLine) ?: return null
        val pts = floatArrayOf(
            tl.first, tl.second, tr.first, tr.second,
            br.first, br.second, bl.first, bl.second
        )
        val minX = pts.filterIndexed { i, _ -> i % 2 == 0 }.min()
        val maxX = pts.filterIndexed { i, _ -> i % 2 == 0 }.max()
        val minY = pts.filterIndexed { i, _ -> i % 2 == 1 }.min()
        val maxY = pts.filterIndexed { i, _ -> i % 2 == 1 }.max()
        if (minX < -w * 0.05f || maxX > w * 1.05f || minY < -h * 0.05f || maxY > h * 1.05f) return null
        if (maxX - minX < w * 0.25f || maxY - minY < h * 0.25f) return null
        return pts
    }

    /** Snap each side of a rough quad outward to the strongest
     *  brightness drop nearby — the true page edge. The texture
     *  mask is inset (it only covers the smooth interior), so
     *  without this the frame would sit inside the page. For
     *  each side, 24 points are sampled along it; at each, the
     *  brightness profile outward (up to maxDist) is scanned for
     *  the biggest drop (paper -> darker background, step > 20).
     *  A line is fitted to the snapped points (PCA, so tilted
     *  sides work); a side that cannot be snapped (e.g. it sits
     *  on the image border, with nothing outside to search)
     *  keeps its rough line. Returns the refined quad (TL TR BR
     *  BL) or null when the four lines cannot be intersected. */
    private fun snapQuadOutward(gray: FloatArray, w: Int, h: Int, quad: FloatArray): FloatArray? {
        val corners = arrayOf(
            Pair(quad[0], quad[1]), Pair(quad[2], quad[3]),
            Pair(quad[4], quad[5]), Pair(quad[6], quad[7])
        )
        val cx = (corners[0].first + corners[1].first + corners[2].first + corners[3].first) / 4f
        val cy = (corners[0].second + corners[1].second + corners[2].second + corners[3].second) / 4f
        val maxDist = max(30, min(w, h) / 4)
        val lines = mutableListOf<Pair<Pair<Float, Float>, Pair<Float, Float>>>()
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val dx = b.first - a.first
            val dy = b.second - a.second
            val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
            var nx = -dy / len
            var ny = dx / len
            val mx = (a.first + b.first) / 2f
            val my = (a.second + b.second) / 2f
            // The outward normal points AWAY from the quad centre.
            if ((cx - mx) * nx + (cy - my) * ny > 0) {
                nx = -nx
                ny = -ny
            }
            val snapped = mutableListOf<Pair<Float, Float>>()
            val samples = 24
            for (k in 0 until samples) {
                val t = (k + 0.5f) / samples
                val px = a.first + dx * t
                val py = a.second + dy * t
                var bestD = 0
                var bestStep = 0f
                var prev: Float? = null
                var d = -5
                while (d <= maxDist) {
                    val xi = (px + nx * d).roundToInt()
                    val yi = (py + ny * d).roundToInt()
                    val v = if (xi in 0 until w && yi in 0 until h) gray[yi * w + xi] else null
                    if (v == null) {
                        prev = null
                    } else {
                        if (prev != null) {
                            val step = prev - v
                            if (step > bestStep) {
                                bestStep = step
                                bestD = d - 1
                            }
                        }
                        prev = v
                    }
                    d++
                }
                if (bestStep > 20f) {
                    snapped.add(Pair(px + nx * bestD, py + ny * bestD))
                }
            }
            if (snapped.size >= 8) {
                lines.add(pcaLine(snapped) ?: pcaLine(listOf(a, b))!!)
            } else {
                lines.add(pcaLine(listOf(a, b))!!)
            }
        }
        val tl = intersectPca(lines[0], lines[3]) ?: return null
        val tr = intersectPca(lines[0], lines[1]) ?: return null
        val br = intersectPca(lines[2], lines[1]) ?: return null
        val bl = intersectPca(lines[2], lines[3]) ?: return null
        return floatArrayOf(
            tl.first, tl.second, tr.first, tr.second,
            br.first, br.second, bl.first, bl.second
        )
    }

    /** Fit a line to points by PCA (total least squares): the
     *  principal axis of the point cloud. Returns (centre,
     *  direction). Works for a line at any angle, unlike the
     *  axis-aligned fits. */
    private fun pcaLine(points: List<Pair<Float, Float>>): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
        if (points.size < 2) return null
        var cx = 0f
        var cy = 0f
        for ((x, y) in points) {
            cx += x
            cy += y
        }
        cx /= points.size
        cy /= points.size
        var sxx = 0f
        var syy = 0f
        var sxy = 0f
        for ((x, y) in points) {
            val dx = x - cx
            val dy = y - cy
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        val n = points.size.toFloat()
        sxx /= n
        syy /= n
        sxy /= n
        // Principal axis angle: 0.5 * atan2(2*sxy, sxx - syy).
        val angle = 0.5f * kotlin.math.atan2(2f * sxy, sxx - syy)
        val dir = Pair(kotlin.math.cos(angle), kotlin.math.sin(angle))
        return Pair(Pair(cx, cy), dir)
    }

    /** Intersect two PCA lines (centre + direction). */
    private fun intersectPca(
        l1: Pair<Pair<Float, Float>, Pair<Float, Float>>,
        l2: Pair<Pair<Float, Float>, Pair<Float, Float>>
    ): Pair<Float, Float>? {
        val (c1, d1) = l1
        val (c2, d2) = l2
        val det = d1.first * d2.second - d2.first * d1.second
        if (abs(det) < 1e-6f) return null
        val rhsX = c2.first - c1.first
        val rhsY = c2.second - c1.second
        val t = (rhsX * d2.second - rhsY * d2.first) / det
        return Pair(c1.first + t * d1.first, c1.second + t * d1.second)
    }

    /** The local standard deviation of the grey image over a
     *  window of radius [r] (separable box blurs of the image
     *  and of its square: std = sqrt(E[x^2] - E[x]^2)). Paper
     *  is smooth (low std); a patterned background is textured
     *  (high std) even where it is bright. */
    private fun localStdMap(gray: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val mean = boxBlurGray(gray, w, h, r)
        val sq = FloatArray(w * h) { i -> gray[i] * gray[i] }
        val meanSq = boxBlurGray(sq, w, h, r)
        val out = FloatArray(w * h)
        for (i in out.indices) {
            val v = meanSq[i] - mean[i] * mean[i]
            out[i] = sqrt(max(0f, v))
        }
        return out
    }

    /** A Hough line: (rho, thetaDeg). rho = x*cos(theta) +
     *  y*sin(theta) is the signed distance from the origin
     *  along the line's normal; theta is the normal's angle
     *  in degrees (0..179). */
    private data class HoughLine(val rho: Float, val theta: Int, val votes: Int)

    /** Find a coloured object's quad (a notebook cover, an ID
     *  card) by its boundary edges — the third fallback in
     *  detectCorners (v0.1.56). Canny edges + a Hough transform
     *  find the boundary lines, which do not depend on colour
     *  or brightness; the OUTERMOST strong horizontal and
     *  vertical lines are the object's border. A printed design
     *  inside the object has stronger internal lines, so the
     *  strongest lines are NOT the border — that is why this
     *  takes the outermost, not the strongest.
     *  Returns the quad in small-image coords (TL TR BR BL),
     *  or null. Validated by size, aspect ratio and real
     *  edges — NOT by paper fraction, since a printed cover's
     *  interior is textured, not paper. */
    private fun houghQuad(gray: FloatArray, w: Int, h: Int): FloatArray? {
        val edges = cannyEdges(gray, w, h)
        val maxRho = sqrt((w * w + h * h).toFloat()).toInt() + 1
        val rhoCount = 2 * maxRho + 1
        val acc = IntArray(rhoCount * 180)
        val cosT = FloatArray(180)
        val sinT = FloatArray(180)
        for (t in 0 until 180) {
            val rad = Math.toRadians(t.toDouble())
            cosT[t] = kotlin.math.cos(rad).toFloat()
            sinT[t] = kotlin.math.sin(rad).toFloat()
        }
        var edgeCount = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!edges[y * w + x]) continue
                edgeCount++
                for (t in 0 until 180) {
                    val rho = (x * cosT[t] + y * sinT[t]).roundToInt() + maxRho
                    acc[rho * 180 + t]++
                }
            }
        }
        if (edgeCount < 200) return null
        var maxVotes = 0
        for (v in acc) if (v > maxVotes) maxVotes = v
        val voteThreshold = max(35, (maxVotes * 0.25f).toInt())
        val candidates = mutableListOf<HoughLine>()
        for (rho in 0 until rhoCount) {
            for (t in 0 until 180) {
                val v = acc[rho * 180 + t]
                if (v >= voteThreshold) {
                    candidates.add(HoughLine((rho - maxRho).toFloat(), t, v))
                }
            }
        }
        candidates.sortByDescending { it.votes }
        val peaks = mutableListOf<HoughLine>()
        for (c in candidates) {
            var suppressed = false
            for (p in peaks) {
                val dTheta = min(abs(c.theta - p.theta), 180 - abs(c.theta - p.theta))
                if (abs(c.rho - p.rho) < 12f && dTheta < 8) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) peaks.add(c)
            if (peaks.size >= 40) break
        }
        if (peaks.size < 4) return null
        // Split into horizontal-ish (normal near vertical) and
        // vertical-ish (normal near horizontal) lines.
        val horizontal = mutableListOf<HoughLine>()
        val vertical = mutableListOf<HoughLine>()
        for (p in peaks) {
            if (p.theta in 45..135) {
                horizontal.add(p)
            } else {
                // Canonicalise a near-vertical line so its rho
                // is ~x: (rho, theta>90) == (-rho, theta-180).
                if (p.theta > 90) {
                    vertical.add(HoughLine(-p.rho, p.theta - 180, p.votes))
                } else {
                    vertical.add(p)
                }
            }
        }
        if (horizontal.size < 2 || vertical.size < 2) return null
        // OUTERMOST pair in each direction = the object's border.
        val top = horizontal.minByOrNull { it.rho }!!
        val bottom = horizontal.maxByOrNull { it.rho }!!
        val left = vertical.minByOrNull { it.rho }!!
        val right = vertical.maxByOrNull { it.rho }!!
        if (top.rho >= bottom.rho - 10f || left.rho >= right.rho - 10f) return null
        val tl = intersectHough(top, left) ?: return null
        val tr = intersectHough(top, right) ?: return null
        val br = intersectHough(bottom, right) ?: return null
        val bl = intersectHough(bottom, left) ?: return null
        val pts = floatArrayOf(
            tl.first, tl.second, tr.first, tr.second,
            br.first, br.second, bl.first, bl.second
        )
        val minX = pts.filterIndexed { i, _ -> i % 2 == 0 }.min()
        val maxX = pts.filterIndexed { i, _ -> i % 2 == 0 }.max()
        val minY = pts.filterIndexed { i, _ -> i % 2 == 1 }.min()
        val maxY = pts.filterIndexed { i, _ -> i % 2 == 1 }.max()
        if (minX < -w * 0.05f || maxX > w * 1.05f || minY < -h * 0.05f || maxY > h * 1.05f) return null
        if (maxX - minX < w * 0.25f || maxY - minY < h * 0.25f) return null
        // Not the whole frame (a mat-only scene's lines span
        // the image; a real object leaves a margin).
        if (maxX - minX > w * 0.95f || maxY - minY > h * 0.95f) return null
        // Aspect ratio: the longer side at most twice the shorter.
        val topW = dist(pts[0], pts[1], pts[2], pts[3])
        val bottomW = dist(pts[4], pts[5], pts[6], pts[7])
        val leftH = dist(pts[0], pts[1], pts[6], pts[7])
        val rightH = dist(pts[2], pts[3], pts[4], pts[5])
        val avgW = (topW + bottomW) / 2f
        val avgH = (leftH + rightH) / 2f
        if (avgW < 1f || avgH < 1f) return null
        val ratio = max(avgW, avgH) / min(avgW, avgH)
        if (ratio > 2.0f) return null
        // Real edges: the border must be a real brightness step.
        if (!hasRealEdges(gray, w, h, pts)) return null
        return pts
    }

    /** Intersect two Hough lines (rho = x*cos(theta) +
     *  y*sin(theta)). Returns (x, y) or null if parallel. */
    private fun intersectHough(l1: HoughLine, l2: HoughLine): Pair<Float, Float>? {
        val t1 = Math.toRadians(l1.theta.toDouble())
        val t2 = Math.toRadians(l2.theta.toDouble())
        val c1 = kotlin.math.cos(t1).toFloat()
        val s1 = kotlin.math.sin(t1).toFloat()
        val c2 = kotlin.math.cos(t2).toFloat()
        val s2 = kotlin.math.sin(t2).toFloat()
        val det = c1 * s2 - s1 * c2
        if (abs(det) < 1e-6f) return null
        val x = (l1.rho * s2 - l2.rho * s1) / det
        val y = (c1 * l2.rho - c2 * l1.rho) / det
        return Pair(x, y)
    }

    /** Canny edge map (Sobel gradients + non-maximum
     *  suppression + hysteresis). Returns true for edge
     *  pixels. Thresholds low=50, high=150 (on the Sobel
     *  magnitude, which is ~4x the grey step). */
    private fun cannyEdges(gray: FloatArray, w: Int, h: Int): BooleanArray {
        val g = boxBlurGray(gray, w, h, 2)
        val mag = FloatArray(w * h)
        val angle = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val sx = -g[i - w - 1] + g[i - w + 1] - 2f * g[i - 1] + 2f * g[i + 1] - g[i + w - 1] + g[i + w + 1]
                val sy = -g[i - w - 1] - 2f * g[i - w] - g[i - w + 1] + g[i + w - 1] + 2f * g[i + w] + g[i + w + 1]
                mag[i] = sqrt(sx * sx + sy * sy)
                var a = Math.toDegrees(kotlin.math.atan2(sy.toDouble(), sx.toDouble())).toFloat() % 180f
                if (a < 0f) a += 180f
                angle[i] = a
            }
        }
        // Non-maximum suppression (4 directions, along the gradient).
        val nms = BooleanArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val m = mag[i]
                if (m < 50f) continue
                val a = angle[i]
                val dx1: Int
                val dy1: Int
                val dx2: Int
                val dy2: Int
                when {
                    a < 22.5f || a >= 157.5f -> {
                        dx1 = 1; dy1 = 0; dx2 = -1; dy2 = 0
                    }
                    a < 67.5f -> {
                        dx1 = 1; dy1 = -1; dx2 = -1; dy2 = 1
                    }
                    a < 112.5f -> {
                        dx1 = 0; dy1 = -1; dx2 = 0; dy2 = 1
                    }
                    else -> {
                        dx1 = -1; dy1 = -1; dx2 = 1; dy2 = 1
                    }
                }
                val m1 = mag[(y + dy1) * w + (x + dx1)]
                val m2 = mag[(y + dy2) * w + (x + dx2)]
                if (m >= m1 && m >= m2) nms[i] = true
            }
        }
        // Hysteresis: strong edges (>=150) grow into weak (>=50).
        val edge = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        for (i in nms.indices) {
            if (nms[i] && mag[i] >= 150f) {
                edge[i] = true
                stack.addLast(i)
            }
        }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val y = i / w
            val x = i % w
            for (dy in -1..1) {
                for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val j = ny * w + nx
                    if (nms[j] && mag[j] >= 50f && !edge[j]) {
                        edge[j] = true
                        stack.addLast(j)
                    }
                }
            }
        }
        return edge
    }

    /** Scale a small-image quad back to full resolution. */
    private fun scaleQuad(quad: FloatArray, scale: Float, fullW: Int, fullH: Int): FloatArray {
        val invScale = 1f / scale
        return floatArrayOf(
            (quad[0] * invScale).coerceIn(0f, fullW.toFloat()),
            (quad[1] * invScale).coerceIn(0f, fullH.toFloat()),
            (quad[2] * invScale).coerceIn(0f, fullW.toFloat()),
            (quad[3] * invScale).coerceIn(0f, fullH.toFloat()),
            (quad[4] * invScale).coerceIn(0f, fullW.toFloat()),
            (quad[5] * invScale).coerceIn(0f, fullH.toFloat()),
            (quad[6] * invScale).coerceIn(0f, fullW.toFloat()),
            (quad[7] * invScale).coerceIn(0f, fullH.toFloat())
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
