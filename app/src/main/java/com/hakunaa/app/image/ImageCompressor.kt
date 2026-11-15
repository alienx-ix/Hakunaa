package com.hakunaa.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.hakunaa.app.core.FileUtils
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
    /** Detect a file's image format from its magic bytes (most reliable). */
    fun formatOf(file: File): Format {
        return try {
            val bytes = ByteArray(12)
            java.io.FileInputStream(file).use { it.read(bytes) }
            when {
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> Format.JPEG
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> Format.PNG
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                    bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                    bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> Format.WEBP
                else -> Format.JPEG
            }
        } catch (_: Throwable) {
            Format.JPEG
        }
    }

    fun compressBest(input: File, outputBase: File): Triple<File, Format, String>? {
        val inputSize = input.length()
        // Keep the ORIGINAL format — a JPG stays a JPG, a WebP stays a
        // WebP (no surprise format change). PNG photos shrink via JPEG.
        val attempts = when (formatOf(input)) {
            Format.JPEG -> listOf(
                Triple(85, Format.JPEG, ""),
                Triple(70, Format.JPEG, " (used JPEG 70%)"),
                Triple(60, Format.JPEG, " (used JPEG 60%)")
            )
            Format.WEBP -> listOf(
                Triple(85, Format.WEBP, ""),
                Triple(70, Format.WEBP, " (used WebP 70%)"),
                Triple(60, Format.WEBP, " (used WebP 60%)")
            )
            Format.PNG -> listOf(
                Triple(100, Format.PNG, ""),
                Triple(85, Format.JPEG, " (used JPEG 85%)"),
                Triple(70, Format.JPEG, " (used JPEG 70%)")
            )
        }
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

    /** Full professional Adjust parameters (ImageEditActivity sliders).
     *  Ranges: exposure/brightness/highlights/shade/vibrance/
     *  temperature/tone -100..100; contrast/saturation 0.5..2.0;
     *  sharpness/fading/vignette/enhance/dehaze 0..100. */
    data class AdjustParams(
        var exposure: Float = 0f,
        var brightness: Float = 0f,
        var contrast: Float = 1f,
        var highlights: Float = 0f,
        var shade: Float = 0f,
        var saturation: Float = 1f,
        var vibrance: Float = 0f,
        var temperature: Float = 0f,
        var tone: Float = 0f,
        var sharpness: Float = 0f,
        var fading: Float = 0f,
        var vignette: Float = 0f,
        var enhance: Float = 0f,
        var dehaze: Float = 0f
    )

    /**
     * Full Adjust: every slider really changes the image.
     * Exposure/temperature scale the channels, brightness/contrast
     * shift around mid-grey, Tone is a gamma curve, Highlights/Shade
     * lift only bright/dark pixels, Fading lifts the blacks,
     * Saturation/Vibrance colour the image, Vignette darkens the
     * edges, Enhance/Dehaze add contrast + saturation pop, and
     * Sharpness is a 3x3 convolution. The linear tone curve is a
     * 256-entry lookup table per channel (fast even at full res);
     * only Sharpness needs a neighbour pass. Output is JPEG 90%.
     */
    fun adjustFull(input: File, output: File, p: AdjustParams): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return false
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val exposureScale = Math.pow(2.0, p.exposure / 100.0 * 1.5).toFloat()
            val effContrast = (p.contrast *
                (1f + p.enhance / 100f * 0.15f + p.dehaze / 100f * 0.25f)).coerceIn(0.3f, 3f)
            val effSat = (p.saturation *
                (1f + p.enhance / 100f * 0.15f + p.dehaze / 100f * 0.2f)).coerceIn(0f, 3f)
            val tempR = 1f + p.temperature / 100f * 0.15f
            val tempB = 1f - p.temperature / 100f * 0.15f
            val gamma = Math.pow(2.0, -p.tone / 100.0 * 0.5).toFloat()
            // Per-channel tone LUT: exposure + temperature + contrast
            // + brightness + gamma, all in one 256-entry table each.
            fun toneLut(scale: Float): IntArray {
                val lut = IntArray(256)
                for (v in 0..255) {
                    var x = v * exposureScale * scale
                    x = (x - 128f) * effContrast + 128f + p.brightness
                    val n = (x / 255f).coerceIn(0f, 1f)
                    lut[v] = (255f * Math.pow(n.toDouble(), gamma.toDouble()).toFloat())
                        .toInt().coerceIn(0, 255)
                }
                return lut
            }
            val lutR = toneLut(tempR)
            val lutG = toneLut(1f)
            val lutB = toneLut(tempB)
            val cx = w / 2f
            val cy = h / 2f
            val maxDist = cx * cx + cy * cy
            val hl = p.highlights / 100f
            val sh = p.shade / 100f
            val fade = p.fading / 100f
            val vib = p.vibrance / 100f
            val vig = p.vignette / 100f
            for (y in 0 until h) {
                val dy = y - cy
                for (x in 0 until w) {
                    val idx = y * w + x
                    var r = lutR[(pixels[idx] shr 16) and 0xFF].toFloat()
                    var g = lutG[(pixels[idx] shr 8) and 0xFF].toFloat()
                    var b = lutB[pixels[idx] and 0xFF].toFloat()
                    val lum = (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
                    if (hl != 0f) {
                        val wgt = (lum - 0.5f).coerceIn(0f, 1f) * 2f
                        val d = hl * wgt * 40f
                        r += d; g += d; b += d
                    }
                    if (sh != 0f) {
                        val wgt = (0.5f - lum).coerceIn(0f, 1f) * 2f
                        val d = sh * wgt * 40f
                        r += d; g += d; b += d
                    }
                    if (fade != 0f) {
                        r = r * (1f - fade * 0.25f) + fade * 32f
                        g = g * (1f - fade * 0.25f) + fade * 32f
                        b = b * (1f - fade * 0.25f) + fade * 32f
                    }
                    val avg = (r + g + b) / 3f
                    val pixelSat = maxOf(r, g, b) - minOf(r, g, b)
                    val s = effSat * (1f + vib * (1f - pixelSat / 255f))
                    r = avg + (r - avg) * s
                    g = avg + (g - avg) * s
                    b = avg + (b - avg) * s
                    if (vig != 0f) {
                        val dx = x - cx
                        val d2 = (dx * dx + dy * dy) / maxDist
                        val fall = 1f - vig * d2 * 1.2f
                        r *= fall; g *= fall; b *= fall
                    }
                    val ri = r.toInt().coerceIn(0, 255)
                    val gi = g.toInt().coerceIn(0, 255)
                    val bi = b.toInt().coerceIn(0, 255)
                    pixels[idx] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
            }
            var result = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            if (p.sharpness != 0f) result = sharpen(result, p.sharpness / 100f)
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

    /** 3x3 sharpen: out = centre + amount * (4*centre - neighbours). */
    private fun sharpen(bitmap: Bitmap, amount: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val dst = src.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val c = src[i]
                val up = src[i - w]
                val down = src[i + w]
                val left = src[i - 1]
                val right = src[i + 1]
                val r = ((c shr 16) and 0xFF) + (amount * (
                    4 * ((c shr 16) and 0xFF) -
                    ((up shr 16) and 0xFF) - ((down shr 16) and 0xFF) -
                    ((left shr 16) and 0xFF) - ((right shr 16) and 0xFF)
                )).toInt()
                val g = ((c shr 8) and 0xFF) + (amount * (
                    4 * ((c shr 8) and 0xFF) -
                    ((up shr 8) and 0xFF) - ((down shr 8) and 0xFF) -
                    ((left shr 8) and 0xFF) - ((right shr 8) and 0xFF)
                )).toInt()
                val b = (c and 0xFF) + (amount * (
                    4 * (c and 0xFF) -
                    (up and 0xFF) - (down and 0xFF) -
                    (left and 0xFF) - (right and 0xFF)
                )).toInt()
                dst[i] = (0xFF shl 24) or
                    (r.coerceIn(0, 255) shl 16) or
                    (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
        }
        val out = Bitmap.createBitmap(dst, w, h, Bitmap.Config.ARGB_8888)
        bitmap.recycle()
        return out
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

    /** One compression level's real result (Smart Compress). */
    data class LevelResult(
        val label: String,
        val format: Format,
        val quality: Int,
        val file: File,
        val size: Long,
        val savingPercent: Int
    )

    /**
     * Smart results for a CHOSEN quality: try that quality in JPEG
     * and WebP and return the ones smaller than the original,
     * smallest first. The caller shows them with real sizes and
     * lets the user pick one to save. Nothing bigger than the
     * original is ever returned.
     */
    fun compressAtQuality(
        input: File,
        outputBase: File,
        quality: Int,
        maxDimension: Int = 2048
    ): List<LevelResult> {
        val inputSize = input.length()
        val results = mutableListOf<LevelResult>()
        for ((format, label) in listOf(
            Format.JPEG to "JPEG $quality%",
            Format.WEBP to "WebP $quality%"
        )) {
            val candidate = File(
                outputBase.parent,
                "${outputBase.nameWithoutExtension}_q${quality}_${format.extension}.${format.extension}"
            )
            val ok = compress(input, candidate, quality, format, maxDimension)
            if (!ok || !candidate.exists()) {
                try { candidate.delete() } catch (_: Throwable) { }
                continue
            }
            val size = candidate.length()
            if (size < inputSize) {
                val saving = ((inputSize - size) * 100 / inputSize).toInt()
                results.add(LevelResult(label, format, quality, candidate, size, saving))
            } else {
                try { candidate.delete() } catch (_: Throwable) { }
            }
        }
        return results.sortedBy { it.size }
    }

    /** Smallest this photo can be (JPEG quality 10, full resolution). */
    fun minPossibleSize(input: File): Long {
        return try {
            val out = File(input.parent, "hakunaa_min_${System.currentTimeMillis()}.jpg")
            val ok = compress(input, out, 10, Format.JPEG, 10000)
            val size = if (ok && out.exists()) out.length() else Long.MAX_VALUE
            try { out.delete() } catch (_: Throwable) { }
            size
        } catch (_: Throwable) {
            Long.MAX_VALUE
        }
    }

    /**
     * Smart Compress: try every level in the ORIGINAL format and
     * return ALL results smaller than the original, smallest first.
     * The caller shows them with real sizes and lets the user pick
     * one to save. Nothing bigger than the original is returned.
     */
    fun compressAllLevels(input: File, outputBase: File): List<LevelResult> {
        val inputSize = input.length()
        val attempts = when (formatOf(input)) {
            Format.JPEG -> listOf(
                Triple(85, Format.JPEG, "JPEG 85%"),
                Triple(70, Format.JPEG, "JPEG 70%"),
                Triple(60, Format.JPEG, "JPEG 60%")
            )
            Format.WEBP -> listOf(
                Triple(85, Format.WEBP, "WebP 85%"),
                Triple(70, Format.WEBP, "WebP 70%"),
                Triple(60, Format.WEBP, "WebP 60%")
            )
            Format.PNG -> listOf(
                Triple(100, Format.PNG, "PNG"),
                Triple(85, Format.JPEG, "JPEG 85%"),
                Triple(70, Format.JPEG, "JPEG 70%")
            )
        }
        val results = mutableListOf<LevelResult>()
        for ((quality, format, label) in attempts) {
            val candidate = File(
                outputBase.parent,
                "${outputBase.nameWithoutExtension}_${label.replace(" ", "_").replace("%", "")}.${format.extension}"
            )
            val ok = compress(input, candidate, quality, format)
            if (!ok || !candidate.exists()) {
                try { candidate.delete() } catch (_: Throwable) { }
                continue
            }
            val size = candidate.length()
            if (size < inputSize) {
                val saving = ((inputSize - size) * 100 / inputSize).toInt()
                results.add(LevelResult(label, format, quality, candidate, size, saving))
            } else {
                try { candidate.delete() } catch (_: Throwable) { }
            }
        }
        return results.sortedBy { it.size }
    }

    /**
     * Auto Enhance (one tap): stretch the photo's own brightness
     * range (1st..99th percentile, outliers ignored) to full black
     * .. white and lift saturation a little. Reuses the same
     * ColorMatrix path as adjust(). No new library, no new file
     * format — output is JPEG 92%.
     */
    fun autoEnhance(input: File, output: File): Boolean {
        return try {
            val bitmap = FileUtils.decodeBitmapWithExif(input.absolutePath) ?: return false
            // Histogram on a small copy (levels don't need full res).
            val sample = maxOf(1, maxOf(bitmap.width, bitmap.height) / 512)
            val small = Bitmap.createScaledBitmap(
                bitmap, (bitmap.width / sample).coerceAtLeast(1),
                (bitmap.height / sample).coerceAtLeast(1), true
            )
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            small.recycle()
            val hist = IntArray(256)
            for (p in pixels) {
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                hist[(r * 30 + g * 59 + b * 11) / 100]++
            }
            val total = pixels.size.toLong()
            val edge = (total / 100).coerceAtLeast(1)
            var lo = 0
            var acc = 0L
            while (lo < 255 && acc < edge) { acc += hist[lo]; lo++ }
            var hi = 255
            acc = 0L
            while (hi > 0 && acc < edge) { acc += hist[hi]; hi-- }
            if (hi - lo < 16) {
                // Already well spread — just a light touch.
                bitmap.recycle()
                return adjust(input, output, 0f, 1f, 1.1f)
            }
            val contrast = (255f / (hi - lo).toFloat()).coerceIn(1f, 2f)
            val brightness = (-lo.toFloat() * contrast).coerceIn(-100f, 100f)
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val paint = Paint()
            val matrix = ColorMatrix()
            matrix.setSaturation(1.1f)
            matrix.postConcat(ColorMatrix(floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast, 0f, brightness,
                0f, 0f, 0f, 1f, 0f
            )))
            paint.colorFilter = ColorMatrixColorFilter(matrix)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            FileOutputStream(output).use { out ->
                result.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bitmap.recycle()
            result.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * DP Edit — Circle: centre-square the photo, mask it to a
     * circle, save as PNG (transparent outside the circle).
     * The square DP is just fitToAspect(1, 1).
     */
    fun circleCrop(input: File, output: File): Boolean {
        return try {
            val bitmap = FileUtils.decodeBitmapWithExif(input.absolutePath) ?: return false
            val size = minOf(bitmap.width, bitmap.height)
            val x = (bitmap.width - size) / 2
            val y = (bitmap.height - size) / 2
            val square = Bitmap.createBitmap(bitmap, x, y, size, size)
            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val path = Path()
            path.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            canvas.clipPath(path)
            canvas.drawBitmap(square, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
            FileOutputStream(output).use { out ->
                result.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            square.recycle()
            result.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** DP Circle with a CHOSEN square: crop the [left, top, size]
     *  square, mask it to a circle, save as PNG (transparent
     *  outside the circle). */
    fun circleCropRect(input: File, output: File, left: Int, top: Int, size: Int): Boolean {
        return try {
            val bitmap = FileUtils.decodeBitmapWithExif(input.absolutePath) ?: return false
            val s = size.coerceIn(1, minOf(bitmap.width, bitmap.height))
            val l = left.coerceIn(0, bitmap.width - s)
            val t = top.coerceIn(0, bitmap.height - s)
            val square = Bitmap.createBitmap(bitmap, l, t, s, s)
            val result = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val path = Path()
            path.addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW)
            canvas.clipPath(path)
            canvas.drawBitmap(square, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
            FileOutputStream(output).use { out ->
                result.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            square.recycle()
            result.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Remove Metadata: decode (which applies EXIF orientation, so
     * the pixels come out the right way up) and re-encode without
     * any EXIF — location, camera and date are gone. JPEG 95%
     * (PNG stays PNG). The re-encode at 95% is visually lossless.
     */
    fun stripMetadata(input: File, output: File): Boolean {
        return try {
            val bitmap = FileUtils.decodeBitmapWithExif(input.absolutePath) ?: return false
            val isPng = input.name.lowercase().endsWith(".png")
            val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val quality = if (isPng) 100 else 95
            FileOutputStream(output).use { out ->
                bitmap.compress(format, quality, out)
            }
            bitmap.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
