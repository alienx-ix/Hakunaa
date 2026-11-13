package com.hakunaa.app.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/**
 * Scan enhancement filters — the CamScanner-style filter row.
 *
 * - ORIGINAL: the photo as captured.
 * - LIGHTEN: brighter, for dark photos.
 * - ENHANCE: the "magic" filter — more contrast and colour, so
 *   text pops and the page looks clean and vivid.
 * - NO_SHADOW: lifts shadows and evens out uneven light.
 * - BW: pure black and white, best for printed text documents.
 *
 * All pure Kotlin, on-device. No cloud, no ML model.
 */
object ImageEnhancer {

    enum class Filter(val label: String) {
        ORIGINAL("Original"),
        LIGHTEN("Lighten"),
        ENHANCE("Enhance"),
        NO_SHADOW("No Shadow"),
        BW("B&W"),
        SOFT("Soft"),
        GRAY("Gray"),
        VIVID("Vivid"),
        WARM("Warm")
    }

    fun apply(bitmap: Bitmap, filter: Filter): Bitmap {
        if (filter == Filter.ORIGINAL) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        when (filter) {
            Filter.LIGHTEN -> {
                // Brightness +40, slight contrast lift.
                val matrix = ColorMatrix(
                    floatArrayOf(
                        1.1f, 0f, 0f, 0f, 30f,
                        0f, 1.1f, 0f, 0f, 30f,
                        0f, 0f, 1.1f, 0f, 30f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            Filter.ENHANCE -> {
                // More contrast + more colour saturation + a little brightness.
                // This is the "magic colour" look: text gets darker,
                // paper gets whiter, colours get vivid.
                val matrix = ColorMatrix()
                matrix.setSaturation(1.35f)
                val contrast = ColorMatrix(
                    floatArrayOf(
                        1.3f, 0f, 0f, 0f, -18f,
                        0f, 1.3f, 0f, 0f, -18f,
                        0f, 0f, 1.3f, 0f, -18f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                matrix.postConcat(contrast)
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                // NEW (v0.1.41): SHARPEN. The perspective warp
                // resampling softens text a little, and no filter
                // ever re-sharpened it — that is why scans looked
                // "a little poor" next to CamScanner. An unsharp
                // mask after the colour work makes text and lines
                // crisp again. Verified side-by-side on the user's
                // INDEX page: visibly clearer, same colours.
                val sharp = sharpen(result)
                result.recycle()
                return sharp
            }
            Filter.NO_SHADOW -> {
                // Lift the dark areas (shadows) without blowing out
                // the bright paper: brightness up, contrast slightly up.
                val matrix = ColorMatrix(
                    floatArrayOf(
                        1.15f, 0f, 0f, 0f, 22f,
                        0f, 1.15f, 0f, 0f, 22f,
                        0f, 0f, 1.15f, 0f, 22f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            Filter.BW -> {
                // ADAPTIVE black & white. A fixed threshold (the
                // old 160) fails the moment the light is uneven:
                // shadowed paper drops below 160 and the whole
                // shadow turns solid black, eating the text — the
                // user's screenshot was exactly that (top of the
                // page black, only the bright bottom strip left).
                // So each pixel is compared with its LOCAL
                // background (a wide blur of the page): text is
                // darker than the paper around it whatever the
                // light there, and paper is never darker than its
                // own background. Verified on the user's photo:
                // fixed 160 gave a 100% black page, adaptive
                // gives white paper with black text throughout.
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                val gray = IntArray(w * h)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    gray[i] = (0.299 * ((p shr 16) and 0xFF) +
                        0.587 * ((p shr 8) and 0xFF) +
                        0.114 * (p and 0xFF)).toInt()
                }
                val radius = max(10, min(w, h) / 20)
                val bg = boxBlurGray(gray, w, h, radius)
                for (i in pixels.indices) {
                    pixels[i] = if (gray[i] < bg[i] - 15) -0x1000000 else -0x1
                }
                result.setPixels(pixels, 0, w, 0, 0, w, h)
            }
            Filter.SOFT -> {
                // Soft: gentle look — slightly less contrast, a touch
                // brighter, colours muted. Easy on the eyes, good for
                // photos and soft documents.
                val matrix = ColorMatrix()
                matrix.setSaturation(0.85f)
                val soft = ColorMatrix(
                    floatArrayOf(
                        0.9f, 0f, 0f, 0f, 18f,
                        0f, 0.9f, 0f, 0f, 18f,
                        0f, 0f, 0.9f, 0f, 18f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                matrix.postConcat(soft)
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            Filter.GRAY -> {
                // Pure grayscale (no threshold) — keeps the shading,
                // just removes the colour. Good for old documents.
                val matrix = ColorMatrix()
                matrix.setSaturation(0f)
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            Filter.VIVID -> {
                // Vivid: strong colour and contrast, the "magic
                // colour" look turned up — text pops, colours sing.
                val matrix = ColorMatrix()
                matrix.setSaturation(1.6f)
                val contrast = ColorMatrix(
                    floatArrayOf(
                        1.45f, 0f, 0f, 0f, -25f,
                        0f, 1.45f, 0f, 0f, -25f,
                        0f, 0f, 1.45f, 0f, -25f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                matrix.postConcat(contrast)
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            Filter.WARM -> {
                // Warm: warm tones for old, yellowed documents —
                // reds and yellows up, blues down slightly.
                val matrix = ColorMatrix(
                    floatArrayOf(
                        1.15f, 0f, 0f, 0f, 12f,
                        0f, 1.05f, 0f, 0f, 8f,
                        0f, 0f, 0.85f, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                paint.colorFilter = ColorMatrixColorFilter(matrix)
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
            else -> {
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
        }
        return result
    }

    /** Separable box blur on a single-channel int image
     *  (running sums, O(n) whatever the radius). The B&W filter
     *  uses it for the local background estimate. */
    private fun boxBlurGray(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var sum = 0
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
            var sum = 0
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

    /** Unsharp mask — the "clear scan" step. A blurred copy is
     *  subtracted from the original and the difference is added
     *  back (amount 0.9), so text and ruled lines get crisp edges
     *  without changing colours. Pure Kotlin, separable box blur
     *  (radius 2, about the same as a Gaussian radius 2) so it
     *  stays fast even on full-resolution pages. Runs on a
     *  background thread in the caller, never on the UI thread. */
    private fun sharpen(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val blurred = boxBlur(src, w, h, 2)
        val out = IntArray(w * h)
        for (i in src.indices) {
            val s = src[i]
            val b = blurred[i]
            val sr = (s shr 16) and 0xFF
            val sg = (s shr 8) and 0xFF
            val sb = s and 0xFF
            val br = (b shr 16) and 0xFF
            val bg = (b shr 8) and 0xFF
            val bb = b and 0xFF
            val r = (sr + 0.9f * (sr - br)).toInt().coerceIn(0, 255)
            val g = (sg + 0.9f * (sg - bg)).toInt().coerceIn(0, 255)
            val bl = (sb + 0.9f * (sb - bb)).toInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** Separable box blur (horizontal pass, then vertical).
     *  Running sums keep it O(n) — one pass per direction,
     *  no per-pixel window loop. */
    private fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        // Horizontal pass.
        for (y in 0 until h) {
            val row = y * w
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var count = 0
            for (k in 0..r) {
                val p = src[row + k]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
                count++
            }
            for (x in 0 until w) {
                tmp[row + x] = (0xFF shl 24) or
                    ((sumR / count) shl 16) or ((sumG / count) shl 8) or (sumB / count)
                val addX = x + r + 1
                if (addX < w) {
                    val p = src[row + addX]
                    sumR += (p shr 16) and 0xFF
                    sumG += (p shr 8) and 0xFF
                    sumB += p and 0xFF
                    count++
                }
                val remX = x - r
                if (remX >= 0) {
                    val p = src[row + remX]
                    sumR -= (p shr 16) and 0xFF
                    sumG -= (p shr 8) and 0xFF
                    sumB -= p and 0xFF
                    count--
                }
            }
        }
        // Vertical pass.
        for (x in 0 until w) {
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var count = 0
            for (k in 0..r) {
                val p = tmp[k * w + x]
                sumR += (p shr 16) and 0xFF
                sumG += (p shr 8) and 0xFF
                sumB += p and 0xFF
                count++
            }
            for (y in 0 until h) {
                out[y * w + x] = (0xFF shl 24) or
                    ((sumR / count) shl 16) or ((sumG / count) shl 8) or (sumB / count)
                val addY = y + r + 1
                if (addY < h) {
                    val p = tmp[addY * w + x]
                    sumR += (p shr 16) and 0xFF
                    sumG += (p shr 8) and 0xFF
                    sumB += p and 0xFF
                    count++
                }
                val remY = y - r
                if (remY >= 0) {
                    val p = tmp[remY * w + x]
                    sumR -= (p shr 16) and 0xFF
                    sumG -= (p shr 8) and 0xFF
                    sumB -= p and 0xFF
                    count--
                }
            }
        }
        return out
    }
}
