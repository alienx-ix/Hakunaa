package com.hakunaa.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
        WEBP("webp")
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
     * Best image compression: try JPEG 85% first; if the result is
     * NOT smaller than the input, automatically escalate to stronger
     * settings — WebP 85% (25–35% smaller than JPEG), JPEG 70%,
     * WebP 70%, JPEG 60% — until the output IS smaller. A compress
     * never hands back a bigger file. The user requires it: in ANY
     * situation, the output must never be bigger than the original.
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
            Triple(85, Format.WEBP, " (used WebP to get it smaller)"),
            Triple(70, Format.JPEG, " (used JPEG 70% to get it smaller)"),
            Triple(70, Format.WEBP, " (used WebP 70% to get it smaller)"),
            Triple(60, Format.JPEG, " (used JPEG 60% to get it smaller)")
        )
        for ((quality, format, note) in attempts) {
            val out = File(
                outputBase.parent,
                "${outputBase.nameWithoutExtension}.${format.extension}"
            )
            val ok = compress(input, out, quality, format)
            if (!ok) {
                try { out.delete() } catch (_: Throwable) { }
                continue
            }
            if (!out.exists()) continue
            if (out.length() < inputSize) {
                return Triple(out, format, note)
            }
            // Bigger (or equal) — delete it and try harder.
            try { out.delete() } catch (_: Throwable) { }
        }
        return null
    }
}
