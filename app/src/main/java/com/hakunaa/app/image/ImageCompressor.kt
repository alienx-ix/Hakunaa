package com.hakunaa.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Image compression — Android Bitmap, on-device.
 * Photos: JPEG quality 85% is visually lossless and much smaller
 * than the camera original. WebP is ~25–35% smaller than JPEG.
 * AVIF is ~50% smaller than JPEG but slow on older phones, so it
 * is an option for later, not the default.
 */
object ImageCompressor {
    enum class Format(val extension: String) {
        JPEG("jpg"),
        WEBP("webp"),
        PNG("png")
    }

    fun compress(
        input: File,
        output: File,
        quality: Int = 85,
        format: Format = Format.JPEG,
        maxDimension: Int = 2048
    ): Boolean {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.absolutePath, options)
            var sample = 1
            val largest = maxOf(options.outWidth, options.outHeight)
            while (largest / sample > maxDimension) sample *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(input.absolutePath, decodeOptions)
                ?: return false
            // WEBP_LOSSY needs API 30+; WEBP works on all supported APIs here.
            val compressFormat = when (format) {
                Format.JPEG -> Bitmap.CompressFormat.JPEG
                Format.WEBP -> Bitmap.CompressFormat.WEBP
                Format.PNG -> Bitmap.CompressFormat.PNG
            }
            FileOutputStream(output).use { out ->
                bitmap.compress(compressFormat, quality, out)
            }
            bitmap.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Resize image: scale the image so its longest side is at most
     * [maxDimension] pixels, keeping the aspect ratio. Returns true
     * on success. The output format is JPEG 90%.
     */
    fun resize(input: File, output: File, maxDimension: Int): Boolean {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.absolutePath, options)
            val largest = maxOf(options.outWidth, options.outHeight)
            if (largest <= maxDimension) {
                // Already small enough — copy it as-is.
                input.copyTo(output, overwrite = true)
                return true
            }
            val scale = maxDimension.toFloat() / largest.toFloat()
            val targetW = (options.outWidth * scale).toInt().coerceAtLeast(1)
            val targetH = (options.outHeight * scale).toInt().coerceAtLeast(1)
            val bitmap = BitmapFactory.decodeFile(input.absolutePath)
                ?: return false
            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            FileOutputStream(output).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            scaled.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Convert image format: re-encode the image as [format]
     * (JPEG / PNG / WEBP) at [quality]. Returns true on success.
     */
    fun convertFormat(input: File, output: File, format: Format, quality: Int = 90): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath)
                ?: return false
            val compressFormat = when (format) {
                Format.JPEG -> Bitmap.CompressFormat.JPEG
                Format.WEBP -> Bitmap.CompressFormat.WEBP
                Format.PNG -> Bitmap.CompressFormat.PNG
            }
            FileOutputStream(output).use { out ->
                bitmap.compress(compressFormat, quality, out)
            }
            bitmap.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Best image compression: try EVERY setting level — JPEG 85%,
     * WebP 85% (25–35% smaller than JPEG), JPEG 70%, WebP 70%,
     * JPEG 60% — and keep the SMALLEST output, not just the first
     * one that is smaller. Best means best: the app tries them all
     * and hands back the smallest file. A compress never hands back
     * a bigger file. The user requires it: in ANY situation, the
     * output must never be bigger than the original.
     * Returns a Triple: (output file, format used, settings note).
     * The note is "" if JPEG 85% won, or names the stronger setting
     * used. Returns null if even the strongest settings could not
     * shrink it (in that case NO output file is left behind).
     * [outputBase] is the file path without extension; the real
     * extension (.jpg/.webp) depends on which format won.
     */
    fun compressBest(input: File, outputBase: File): Triple<File, Format, String>? {
        val inputSize = input.length()
        val attempts = listOf(
            Triple(85, Format.JPEG, ""),
            Triple(85, Format.WEBP, " (used WebP)"),
            Triple(70, Format.JPEG, " (used JPEG 70%)"),
            Triple(70, Format.WEBP, " (used WebP 70%)"),
            Triple(60, Format.JPEG, " (used JPEG 60%)")
        )
        var bestFile: File? = null
        var bestFormat = Format.JPEG
        var bestNote = ""
        var bestSize = Long.MAX_VALUE
        // Try every level and keep the smallest output.
        for ((quality, format, note) in attempts) {
            val candidate = File(
                outputBase.parent,
                "${outputBase.nameWithoutExtension}_try${quality}_${format.extension}.${format.extension}"
            )
            val ok = compress(input, candidate, quality, format)
            if (!ok || !candidate.exists()) {
                try { candidate.delete() } catch (_: Throwable) { }
                continue
            }
            val size = candidate.length()
            if (size < inputSize && size < bestSize) {
                // Smallest so far — keep it, drop the old best.
                try { bestFile?.delete() } catch (_: Throwable) { }
                bestFile = candidate
                bestFormat = format
                bestNote = note
                bestSize = size
            } else {
                try { candidate.delete() } catch (_: Throwable) { }
            }
        }
        return if (bestFile != null) Triple(bestFile, bestFormat, bestNote) else null
    }

    /**
     * Resize to a target file size (Puma's "Resize to KB, MB"):
     * binary-search the JPEG quality until the output is at or
     * under [targetBytes]. If even quality 10 is still too big,
     * the smallest attempt is returned. Returns the output file,
     * or null on failure.
     */
    fun compressToTargetSize(input: File, output: File, targetBytes: Long): File? {
        return try {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return null
            var low = 10
            var high = 95
            var bestBytes: ByteArray? = null
            // Binary search: fewer tries, same result.
            while (low <= high) {
                val mid = (low + high) / 2
                val bytes = ByteArrayOutputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, mid, stream)
                    stream.toByteArray()
                }
                if (bytes.size <= targetBytes) {
                    bestBytes = bytes
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            // If nothing fit, use the lowest quality tried.
            val result = bestBytes ?: ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 10, stream)
                stream.toByteArray()
            }
            bitmap.recycle()
            FileOutputStream(output).use { it.write(result) }
            output
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resize to exact dimensions (width x height), keeping the
     * aspect ratio of the target by scaling to fit. Returns true
     * on success. Output is JPEG 90%.
     */
    fun resizeTo(input: File, output: File, targetW: Int, targetH: Int): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return false
            val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            FileOutputStream(output).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            scaled.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Fit to an aspect ratio (Puma's "Fit" / "Fit to social media"):
     * centre-crops the image to [aspectW]:[aspectH] (e.g. 1:1 for
     * Instagram, 9:16 for a story, 4:5 for a feed post). Returns
     * true on success. Output is JPEG 90%.
     */
    fun fitToAspect(input: File, output: File, aspectW: Int, aspectH: Int): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return false
            val targetRatio = aspectW.toFloat() / aspectH
            val ratio = bitmap.width.toFloat() / bitmap.height
            val (cropW, cropH) = if (ratio > targetRatio) {
                // Too wide — cut the sides.
                val w = (bitmap.height * targetRatio).toInt()
                Pair(w, bitmap.height)
            } else {
                // Too tall — cut top and bottom.
                val h = (bitmap.width / targetRatio).toInt()
                Pair(bitmap.width, h)
            }
            val x = (bitmap.width - cropW) / 2
            val y = (bitmap.height - cropH) / 2
            val cropped = Bitmap.createBitmap(bitmap, x, y, cropW, cropH)
            FileOutputStream(output).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            cropped.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Basic photo editing: brightness (-100..100), contrast
     * (0.5..2.0) and saturation (0..2.0). Returns true on success.
     * Output is JPEG 90%.
     */
    fun adjust(
        input: File,
        output: File,
        brightness: Float,
        contrast: Float,
        saturation: Float
    ): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return false
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val paint = Paint()
            val matrix = ColorMatrix()
            matrix.setSaturation(saturation)
            val adjust = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            matrix.postConcat(adjust)
            paint.colorFilter = ColorMatrixColorFilter(matrix)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            FileOutputStream(output).use { out ->
                result.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            result.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Crop to a rectangle (in pixels, in the original image).
     * Returns true on success. Output is JPEG 92%.
     */
    fun crop(input: File, output: File, left: Int, top: Int, right: Int, bottom: Int): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return false
            val w = (right - left).coerceIn(1, bitmap.width)
            val h = (bottom - top).coerceIn(1, bitmap.height)
            val l = left.coerceIn(0, bitmap.width - 1)
            val t = top.coerceIn(0, bitmap.height - 1)
            val cropped = Bitmap.createBitmap(bitmap, l, t, w, h)
            FileOutputStream(output).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bitmap.recycle()
            cropped.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Rotate an image by [degrees] (90, 180, 270). */
    fun rotate(input: File, output: File, degrees: Float): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return false
            val matrix = Matrix()
            matrix.postRotate(degrees)
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            FileOutputStream(output).use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bitmap.recycle()
            rotated.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
