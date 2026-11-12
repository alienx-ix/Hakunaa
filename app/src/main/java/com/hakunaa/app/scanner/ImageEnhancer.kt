package com.hakunaa.app.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

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
        SOFT("Soft")
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
                // Grayscale, then threshold to pure black / white.
                val gray = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                val grayCanvas = Canvas(gray)
                val grayPaint = Paint()
                val matrix = ColorMatrix()
                matrix.setSaturation(0f)
                grayPaint.colorFilter = ColorMatrixColorFilter(matrix)
                grayCanvas.drawBitmap(bitmap, 0f, 0f, grayPaint)
                val pixels = IntArray(gray.width * gray.height)
                gray.getPixels(pixels, 0, gray.width, 0, 0, gray.width, gray.height)
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val lum = (0.299 * ((p shr 16) and 0xFF) +
                        0.587 * ((p shr 8) and 0xFF) +
                        0.114 * (p and 0xFF)).toInt()
                    pixels[i] = if (lum < 160) -0x1000000 else -0x1
                }
                result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
                gray.recycle()
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
            else -> {
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
            }
        }
        return result
    }
}
