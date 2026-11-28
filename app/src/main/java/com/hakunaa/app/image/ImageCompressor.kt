package com.hakunaa.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.hakunaa.app.core.FileUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Image compression — Android Bitmap, on-device.
 * Photos: JPEG quality 85% is visually lossless and much smaller
 * than the camera original. WebP is ~25–35% smaller than JPEG.
 * AVIF is ~50% smaller than JPEG but slow on older phones, so it
 * is an option for later, not the default. AVIF encode goes through
 * FFmpegKit (libaom-av1, verified present in ffmpeg-kit-full-gpl
 * 8.1.9); TIFF/BMP use pure-Kotlin writers, no new library.
 */
object ImageCompressor {
    /** Every format has its real MIME type — MediaStore MUST get
     *  the right one (v0.1.123 fix): TIFF/AVIF files were saved
     *  as "image/jpeg", so Gallery could not open them at all.
     *  GIF is new (FFmpeg). HEIC is INPUT ONLY — this FFmpeg
     *  build has no HEIF muxer, so HEIC output is not offered
     *  (and the user's phone has no HEVC decoder to check it). */
    enum class Format(val extension: String, val mime: String) {
        JPEG("jpg", "image/jpeg"),
        WEBP("webp", "image/webp"),
        PNG("png", "image/png"),
        TIFF("tif", "image/tiff"),
        BMP("bmp", "image/bmp"),
        AVIF("avif", "image/avif"),
        GIF("gif", "image/gif"),
        HEIC("heic", "image/heic")
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
            // TIFF/BMP have no Bitmap.CompressFormat — pure-Kotlin
            // writers below. AVIF goes through FFmpegKit (libaom-av1).
            when (format) {
                Format.TIFF -> {
                    val ok = writeTiff(bitmap, output)
                    bitmap.recycle()
                    return ok
                }
                Format.BMP -> {
                    val ok = writeBmp(bitmap, output)
                    bitmap.recycle()
                    return ok
                }
                Format.AVIF -> {
                    bitmap.recycle()
                    return convertToAvif(input, output, quality)
                }
                else -> { /* fall through to Bitmap.compress below */ }
            }
            // WEBP_LOSSY needs API 30+; WEBP works on all supported APIs here.
            val compressFormat = when (format) {
                Format.JPEG -> Bitmap.CompressFormat.JPEG
                Format.WEBP -> Bitmap.CompressFormat.WEBP
                Format.PNG -> Bitmap.CompressFormat.PNG
                else -> Bitmap.CompressFormat.JPEG
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
     * (JPEG / PNG / WEBP / GIF / TIFF / BMP / AVIF) at [quality].
     * TIFF and BMP use pure-Kotlin writers (no new library);
     * AVIF encodes via FFmpegKit (libaom-av1), GIF via FFmpegKit
     * (palettegen, best 256 colours). A HEIC input (iPhone photo)
     * is decoded to a temp PNG with FFmpeg first — Android's
     * BitmapFactory cannot read HEIC on phones without an HEVC
     * decoder. Returns true on success.
     */
    fun convertFormat(input: File, output: File, format: Format, quality: Int = 90): Boolean {
        // HEIC input: decode to a temp PNG first, convert from there.
        var tempHeic: File? = null
        var src = input
        return try {
            if (formatOf(input) == Format.HEIC) {
                tempHeic = File(input.parent, "hakunaa_heic_${System.currentTimeMillis()}.png")
                if (!decodeHeicToPng(input, tempHeic)) return false
                src = tempHeic
            }
            when (format) {
                Format.TIFF -> {
                    val bitmap = BitmapFactory.decodeFile(src.absolutePath)
                        ?: return false
                    val ok = writeTiff(bitmap, output)
                    bitmap.recycle()
                    ok
                }
                Format.BMP -> {
                    val bitmap = BitmapFactory.decodeFile(src.absolutePath)
                        ?: return false
                    val ok = writeBmp(bitmap, output)
                    bitmap.recycle()
                    ok
                }
                Format.AVIF -> convertToAvif(src, output, quality)
                Format.GIF -> convertToGif(src, output)
                else -> {
                    val bitmap = BitmapFactory.decodeFile(src.absolutePath)
                        ?: return false
                    val compressFormat = when (format) {
                        Format.JPEG -> Bitmap.CompressFormat.JPEG
                        Format.WEBP -> Bitmap.CompressFormat.WEBP
                        Format.PNG -> Bitmap.CompressFormat.PNG
                        else -> Bitmap.CompressFormat.JPEG
                    }
                    FileOutputStream(output).use { out ->
                        bitmap.compress(compressFormat, quality, out)
                    }
                    bitmap.recycle()
                    true
                }
            }
        } catch (_: Throwable) {
            false
        } finally {
            try { tempHeic?.delete() } catch (_: Throwable) { }
        }
    }

    /**
     * GIF encode via FFmpegKit (native gif encoder + palettegen,
     * both in ffmpeg-kit-full-gpl). One still frame, best 256
     * colours. Blocking call.
     */
    private fun convertToGif(input: File, output: File): Boolean {
        return try {
            val argv = arrayOf(
                "-y", "-i", input.absolutePath,
                "-vf", "split[s0][s1];[s0]palettegen=stats_mode=single[p];[s1][p]paletteuse",
                "-frames:v", "1", output.absolutePath
            )
            val session = FFmpegKit.executeWithArguments(argv)
            val ok = ReturnCode.isSuccess(session.returnCode) &&
                output.exists() && output.length() > 0
            if (!ok) {
                try { output.delete() } catch (_: Throwable) { }
            }
            ok
        } catch (_: Throwable) {
            try { output.delete() } catch (_: Throwable) { }
            false
        }
    }

    /**
     * Decode one HEIC/HEIF still image to PNG with FFmpeg (mov
     * demuxer + native HEVC decoder — both in ffmpeg-kit-full-gpl).
     * Blocking call.
     */
    fun decodeHeicToPng(input: File, output: File): Boolean {
        return try {
            val argv = arrayOf(
                "-y", "-i", input.absolutePath,
                "-frames:v", "1", output.absolutePath
            )
            val session = FFmpegKit.executeWithArguments(argv)
            val ok = ReturnCode.isSuccess(session.returnCode) &&
                output.exists() && output.length() > 0
            if (!ok) {
                try { output.delete() } catch (_: Throwable) { }
            }
            ok
        } catch (_: Throwable) {
            try { output.delete() } catch (_: Throwable) { }
            false
        }
    }

    /** Little-endian writers for the BMP/TIFF helpers below. */
    private fun writeShortLE(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun writeIntLE(out: OutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    /**
     * Standard 24-bit uncompressed BMP writer (BITMAPFILEHEADER +
     * BITMAPINFOHEADER, bottom-up BGR rows padded to 4 bytes).
     * Produces a valid .bmp any normal viewer can open.
     */
    private fun writeBmp(bitmap: Bitmap, output: File): Boolean {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val rowSize = ((w * 3 + 3) / 4) * 4
            val imageSize = rowSize * h
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            FileOutputStream(output).use { out ->
                out.write(byteArrayOf(0x42, 0x4D)) // "BM"
                writeIntLE(out, 54 + imageSize)
                writeIntLE(out, 0)
                writeIntLE(out, 54)
                writeIntLE(out, 40) // BITMAPINFOHEADER size
                writeIntLE(out, w)
                writeIntLE(out, h) // positive = bottom-up
                writeShortLE(out, 1) // planes
                writeShortLE(out, 24) // bits per pixel
                writeIntLE(out, 0) // BI_RGB, uncompressed
                writeIntLE(out, imageSize)
                writeIntLE(out, 2835) // ~72 dpi horizontal
                writeIntLE(out, 2835) // ~72 dpi vertical
                writeIntLE(out, 0)
                writeIntLE(out, 0)
                val pad = rowSize - w * 3
                val padBytes = ByteArray(pad)
                val row = ByteArray(w * 3)
                for (y in h - 1 downTo 0) {
                    for (x in 0 until w) {
                        val p = pixels[y * w + x]
                        row[x * 3] = (p and 0xFF).toByte() // B
                        row[x * 3 + 1] = ((p shr 8) and 0xFF).toByte() // G
                        row[x * 3 + 2] = ((p shr 16) and 0xFF).toByte() // R
                    }
                    out.write(row)
                    if (pad > 0) out.write(padBytes)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Minimal uncompressed TIFF writer (little-endian, one strip,
     * 8 bits per sample, RGB). Layout: 8-byte header, IFD at 8
     * (2 + 10 entries x 12 + 4 = 126 bytes, ends at 134),
     * BitsPerSample array (6 bytes) at 134, pixel data at 140.
     * Produces a valid .tif any normal viewer can open.
     */
    private fun writeTiff(bitmap: Bitmap, output: File): Boolean {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixelBytes = w * h * 3
            val bitsOffset = 134
            val dataOffset = 140
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            FileOutputStream(output).use { out ->
                out.write(byteArrayOf(0x49, 0x49)) // "II", little-endian
                writeShortLE(out, 42)
                writeIntLE(out, 8) // first IFD offset
                writeShortLE(out, 10) // entry count
                fun entry(tag: Int, type: Int, count: Int, value: Int) {
                    writeShortLE(out, tag)
                    writeShortLE(out, type)
                    writeIntLE(out, count)
                    writeIntLE(out, value)
                }
                entry(256, 4, 1, w) // ImageWidth, LONG
                entry(257, 4, 1, h) // ImageLength, LONG
                entry(258, 3, 3, bitsOffset) // BitsPerSample, SHORT x3
                entry(259, 3, 1, 1) // Compression = none
                entry(262, 3, 1, 2) // PhotometricInterpretation = RGB
                entry(273, 4, 1, dataOffset) // StripOffsets
                entry(277, 3, 1, 3) // SamplesPerPixel
                entry(278, 4, 1, h) // RowsPerStrip
                entry(279, 4, 1, pixelBytes) // StripByteCounts
                entry(284, 3, 1, 1) // PlanarConfiguration = chunky
                writeIntLE(out, 0) // no next IFD
                writeShortLE(out, 8)
                writeShortLE(out, 8)
                writeShortLE(out, 8)
                val row = ByteArray(w * 3)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val p = pixels[y * w + x]
                        row[x * 3] = ((p shr 16) and 0xFF).toByte() // R
                        row[x * 3 + 1] = ((p shr 8) and 0xFF).toByte() // G
                        row[x * 3 + 2] = (p and 0xFF).toByte() // B
                    }
                    out.write(row)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * AVIF encode via FFmpegKit (libaom-av1 + avif muxer, both
     * verified present in ffmpeg-kit-full-gpl 8.1.9). Blocking
     * call. Quality 0..100 maps to libaom CRF 63..0 (lower CRF =
     * better quality). JPEG/PNG inputs go straight to FFmpeg;
     * anything else is decoded to a temp PNG first (FFmpeg reads
     * the temp, which is deleted afterwards).
     */
    private fun convertToAvif(input: File, output: File, quality: Int): Boolean {
        var temp: File? = null
        return try {
            var srcFile = input
            val srcFormat = formatOf(input)
            if (srcFormat != Format.JPEG && srcFormat != Format.PNG) {
                val bitmap = BitmapFactory.decodeFile(input.absolutePath)
                    ?: return false
                temp = File(
                    input.parent,
                    "hakunaa_avif_${System.currentTimeMillis()}.png"
                )
                FileOutputStream(temp).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                bitmap.recycle()
                srcFile = temp
            }
            // -still-picture 1: single-frame AVIF still image.
            // -cpu-used 6: fast encode, still visually lossless
            // at these CRF values.
            val crf = ((100 - quality.coerceIn(0, 100)) * 63 / 100)
                .coerceIn(0, 63)
            val argv = arrayOf(
                "-y", "-i", srcFile.absolutePath,
                "-c:v", "libaom-av1", "-pix_fmt", "yuv420p",
                "-crf", crf.toString(), "-cpu-used", "6",
                "-still-picture", "1", output.absolutePath
            )
            val session = FFmpegKit.executeWithArguments(argv)
            val ok = ReturnCode.isSuccess(session.returnCode) &&
                output.exists() && output.length() > 0
            if (!ok) {
                try { output.delete() } catch (_: Throwable) { }
            }
            ok
        } catch (_: Throwable) {
            try { output.delete() } catch (_: Throwable) { }
            false
        } finally {
            try { temp?.delete() } catch (_: Throwable) { }
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
            val bytes = ByteArray(16)
            java.io.FileInputStream(file).use { it.read(bytes) }
            when {
                bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> Format.JPEG
                bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> Format.PNG
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                    bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                    bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> Format.WEBP
                bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() -> Format.BMP
                bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x2A.toByte() && bytes[3] == 0x00.toByte() -> Format.TIFF
                bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() &&
                    bytes[2] == 0x00.toByte() && bytes[3] == 0x2A.toByte() -> Format.TIFF
                bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
                    bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
                    bytes[8] == 0x66.toByte() && bytes[9] == 0x74.toByte() &&
                    bytes[10] == 0x79.toByte() && bytes[11] == 0x70.toByte() &&
                    bytes[12] == 0x61.toByte() && bytes[13] == 0x76.toByte() &&
                    bytes[14] == 0x69.toByte() && bytes[15] == 0x66.toByte() -> Format.AVIF
                // HEIC/HEIF: an ftyp box whose brand is heic/heix/
                // hevc/hevx/mif1/msf1. (AVIF is caught above — its
                // brand is "avif", not in this set.)
                bytes[4] == 0x66.toByte() && bytes[5] == 0x74.toByte() &&
                    bytes[6] == 0x79.toByte() && bytes[7] == 0x70.toByte() &&
                    String(bytes, 8, 4, Charsets.US_ASCII) in
                        setOf("heic", "heix", "hevc", "hevx", "mif1", "msf1") -> Format.HEIC
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
            Format.PNG, Format.TIFF, Format.BMP, Format.AVIF,
            Format.GIF, Format.HEIC -> listOf(
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
            // Small-file downscale (v0.1.126): decode at most 2048 px
            // (same as Smart/Quality), so small targets are actually
            // reachable — a full-res 16 MP photo cannot reach 100 KB
            // even at quality 10. WhatsApp Optimize already resizes
            // to 1600 px first, so this is a no-op for it.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.absolutePath, bounds)
            var sample = 1
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            while (largest / sample > 2048) sample *= 2
            val bitmap = BitmapFactory.decodeFile(
                input.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
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
     * v0.1.131 (Batch 15): the COMPANION result for the Target
     * screen — WebP at the SAME target size, so the Target result
     * shows TWO boxes (JPEG + WebP) instead of one giant box.
     * Mirrors compressToTargetSize exactly (same 2048 downscale,
     * same binary search on quality 10..95, same "use the lowest
     * quality tried" fallback), except it encodes WebP
     * (Bitmap.CompressFormat.WEBP — lossy, works on all supported
     * APIs, same as compress()) instead of JPEG. Returns a ready
     * LevelResult (label "WebP Target <size>") with the quality
     * the binary search settled on, or null if WebP could not
     * shrink below the original. Does NOT change
     * compressToTargetSize.
     */
    fun compressWebpToTargetSize(
        input: File,
        output: File,
        targetBytes: Long
    ): LevelResult? {
        return try {
            val inputSize = input.length()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.absolutePath, bounds)
            var sample = 1
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            while (largest / sample > 2048) sample *= 2
            val bitmap = BitmapFactory.decodeFile(
                input.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            var low = 10
            var high = 95
            var bestBytes: ByteArray? = null
            var bestQuality = 10
            // Binary search: fewer tries, same result.
            while (low <= high) {
                val mid = (low + high) / 2
                val bytes = ByteArrayOutputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.WEBP, mid, stream)
                    stream.toByteArray()
                }
                if (bytes.size <= targetBytes) {
                    bestBytes = bytes
                    bestQuality = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            // If nothing fit, use the lowest quality tried.
            val result = bestBytes ?: ByteArrayOutputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, 10, stream)
                stream.toByteArray()
            }
            bitmap.recycle()
            FileOutputStream(output).use { it.write(result) }
            val size = output.length()
            if (size >= inputSize) {
                try { output.delete() } catch (_: Throwable) { }
                return null
            }
            val saving = ((inputSize - size) * 100 / inputSize).toInt()
            LevelResult(
                "WebP Target ${com.hakunaa.app.core.FileUtils.formatSize(targetBytes)}",
                Format.WEBP, bestQuality, output, size, saving
            )
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
    /** In-memory Adjust: the exact same math as [adjustFull], Bitmap
     *  in/out, no file I/O. Used for the live preview
     *  (ImageEditActivity) so a slider move updates instantly instead
     *  of waiting on a JPEG write + file adjust + JPEG decode.
     *  The caller owns both bitmaps — recycle src and the result
     *  when done. (sharpen() recycles its own input internally.) */
    fun adjustBitmap(src: Bitmap, p: AdjustParams): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
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
        return result
    }

    fun adjustFull(input: File, output: File, p: AdjustParams): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return false
            val result = adjustBitmap(bitmap, p)
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
     * Smart results for a CHOSEN quality: try that quality in the
     * ORIGINAL format only — a JPG stays a JPG, a WebP stays a
     * WebP (never auto-converted). PNG/TIFF/BMP/AVIF inputs shrink
     * via JPEG. Returns the result only if it is smaller than the
     * original, smallest first. Nothing bigger than the original
     * is ever returned.
     */
    fun compressAtQuality(
        input: File,
        outputBase: File,
        quality: Int,
        // Small-file downscale by default (v0.1.126 — restores the
        // old build): the photo is decoded at most 2048 px, so a
        // 3456x4608 camera photo compresses to a small file again
        // instead of 1.1 MB at full resolution. HD and WebP keep
        // their own full-res paths (compressHd / compressWebpLevels).
        maxDimension: Int = 2048
    ): List<LevelResult> {
        val inputSize = input.length()
        // FORMAT-AWARE: same rule as compressAllLevels.
        val format = when (formatOf(input)) {
            Format.JPEG -> Format.JPEG
            Format.WEBP -> Format.WEBP
            Format.PNG, Format.TIFF, Format.BMP, Format.AVIF,
            Format.GIF, Format.HEIC -> Format.JPEG
        }
        val label = if (format == Format.WEBP) "WebP $quality%" else "JPEG $quality%"
        val candidate = File(
            outputBase.parent,
            "${outputBase.nameWithoutExtension}_q${quality}_${format.extension}.${format.extension}"
        )
        val ok = compress(input, candidate, quality, format, maxDimension)
        if (!ok || !candidate.exists()) {
            try { candidate.delete() } catch (_: Throwable) { }
            return emptyList()
        }
        val size = candidate.length()
        if (size >= inputSize) {
            try { candidate.delete() } catch (_: Throwable) { }
            return emptyList()
        }
        val saving = ((inputSize - size) * 100 / inputSize).toInt()
        return listOf(LevelResult(label, format, quality, candidate, size, saving))
    }

    /**
     * v0.1.131 (Batch 15): the COMPANION result for the Quality
     * screen — the same chosen quality in an EXPLICIT format
     * (JPEG or WebP), so the Quality result shows TWO boxes
     * (JPEG + WebP) instead of one giant box. Mirrors
     * compressAtQuality exactly, except the format is given by
     * the caller instead of taken from the input. Same 2048
     * downscale, same "smaller than original or nothing" rule.
     * Does NOT change compressAtQuality or any other function.
     */
    fun compressAtQualityFormat(
        input: File,
        outputBase: File,
        quality: Int,
        format: Format,
        maxDimension: Int = 2048
    ): List<LevelResult> {
        val inputSize = input.length()
        val label = if (format == Format.WEBP) "WebP $quality%" else "JPEG $quality%"
        val candidate = File(
            outputBase.parent,
            "${outputBase.nameWithoutExtension}_q${quality}_${format.extension}.${format.extension}"
        )
        val ok = compress(input, candidate, quality, format, maxDimension)
        if (!ok || !candidate.exists()) {
            try { candidate.delete() } catch (_: Throwable) { }
            return emptyList()
        }
        val size = candidate.length()
        if (size >= inputSize) {
            try { candidate.delete() } catch (_: Throwable) { }
            return emptyList()
        }
        val saving = ((inputSize - size) * 100 / inputSize).toInt()
        return listOf(LevelResult(label, format, quality, candidate, size, saving))
    }

    /**
     * "Better Compress with WebP" results: WebP at SEVERAL quality
     * levels (95 / 85 / 75 / 70 / 60), all smaller than the original,
     * smallest first. The WebP window shows all of them so the user
     * picks the quality/size they want — not just one result.
     * Nothing bigger than the original is ever returned.
     */
    fun compressWebpLevels(input: File, outputBase: File): List<LevelResult> {
        val inputSize = input.length()
        val results = mutableListOf<LevelResult>()
        // v0.1.141 (Batch 25): FIVE levels now — WebP 75% added
        // (user-approved mockup: 5 boxes like Smart, WebP 60 /
        // 70 / 75 / 85 / 95). Sizes are computed per image, never
        // hardcoded.
        for (quality in listOf(95, 85, 75, 70, 60)) {
            val candidate = File(
                outputBase.parent,
                "${outputBase.nameWithoutExtension}_webp_q$quality.webp"
            )
            // Downscale like Smart (v0.1.127): the old build's
            // small files. Full-res WebP (v0.1.124-126) gave
            // 664 KB at 60% while Smart JPEG gave 97 KB.
            val ok = compress(input, candidate, quality, Format.WEBP, 2048)
            if (!ok || !candidate.exists()) {
                try { candidate.delete() } catch (_: Throwable) { }
                continue
            }
            val size = candidate.length()
            if (size < inputSize) {
                val saving = ((inputSize - size) * 100 / inputSize).toInt()
                results.add(
                    LevelResult("WebP $quality%", Format.WEBP, quality, candidate, size, saving)
                )
            } else {
                try { candidate.delete() } catch (_: Throwable) { }
            }
        }
        return results.sortedBy { it.size }
    }

    /**
     * HD Compress: full resolution, visually lossless. Tries JPEG 95
     * then JPEG 90 (WebP 95 then 90 for WebP inputs; JPEG for PNG /
     * TIFF / BMP / AVIF inputs) and returns the SMALLEST result that
     * is smaller than the original — one result only.
     *
     * The old HD tried JPEG 95 alone, which almost never shrinks an
     * already-compressed camera photo — so HD always answered
     * "Already optimized" and looked totally broken. The ladder
     * gives it a real chance while staying at full resolution.
     */
    fun compressHd(input: File, outputBase: File): List<LevelResult> {
        val inputSize = input.length()
        val format = when (formatOf(input)) {
            Format.JPEG -> Format.JPEG
            Format.WEBP -> Format.WEBP
            Format.PNG, Format.TIFF, Format.BMP, Format.AVIF,
            Format.GIF, Format.HEIC -> Format.JPEG
        }
        var best: LevelResult? = null
        for (quality in listOf(95, 90)) {
            val candidate = File(
                outputBase.parent,
                "${outputBase.nameWithoutExtension}_hd_q$quality.${format.extension}"
            )
            val ok = compress(input, candidate, quality, format, 10000)
            if (!ok || !candidate.exists()) {
                try { candidate.delete() } catch (_: Throwable) { }
                continue
            }
            val size = candidate.length()
            if (size < inputSize) {
                val saving = ((inputSize - size) * 100 / inputSize).toInt()
                val label = if (format == Format.WEBP) "WebP $quality%" else "JPEG $quality%"
                val r = LevelResult(label, format, quality, candidate, size, saving)
                if (best == null || size < best.size) {
                    best?.file?.let { try { it.delete() } catch (_: Throwable) { } }
                    best = r
                } else {
                    try { candidate.delete() } catch (_: Throwable) { }
                }
            } else {
                try { candidate.delete() } catch (_: Throwable) { }
            }
        }
        return best?.let { listOf(it) } ?: emptyList()
    }

    /**
     * v0.1.131 (Batch 15): the COMPANION result for the HD screen
     * — the HD ladder (qualities 95 then 90, FULL resolution
     * 10000, smallest smaller-than-original) in an EXPLICIT
     * format (JPEG or WebP), so the HD result shows TWO boxes
     * (JPEG + WebP) instead of one giant box. Mirrors compressHd
     * exactly, except the format is given by the caller. The HD
     * WebP is full-res and high quality — HD is NEVER
     * downscaled. Does NOT change compressHd.
     */
    fun compressHdCompanion(
        input: File,
        outputBase: File,
        format: Format
    ): List<LevelResult> {
        val inputSize = input.length()
        var best: LevelResult? = null
        for (quality in listOf(95, 90)) {
            val candidate = File(
                outputBase.parent,
                "${outputBase.nameWithoutExtension}_hd_${format.extension}_q$quality.${format.extension}"
            )
            val ok = compress(input, candidate, quality, format, 10000)
            if (!ok || !candidate.exists()) {
                try { candidate.delete() } catch (_: Throwable) { }
                continue
            }
            val size = candidate.length()
            if (size < inputSize) {
                val saving = ((inputSize - size) * 100 / inputSize).toInt()
                val label = if (format == Format.WEBP) "WebP $quality%" else "JPEG $quality%"
                val r = LevelResult(label, format, quality, candidate, size, saving)
                if (best == null || size < best.size) {
                    best?.file?.let { try { it.delete() } catch (_: Throwable) { } }
                    best = r
                } else {
                    try { candidate.delete() } catch (_: Throwable) { }
                }
            } else {
                try { candidate.delete() } catch (_: Throwable) { }
            }
        }
        return best?.let { listOf(it) } ?: emptyList()
    }

    /** Smallest this photo can be (JPEG quality 10, full resolution). */
    fun minPossibleSize(input: File): Long {
        return try {
            val out = File(input.parent, "hakunaa_min_${System.currentTimeMillis()}.jpg")
            // Same downscale as Target (v0.1.126): the smallest the
            // photo can be is the downscaled JPEG-10 size.
            val ok = compress(input, out, 10, Format.JPEG, 2048)
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
     * FIVE levels (v0.1.123 — was 3): the caller shows them with
     * real sizes and lets the user pick one to save. Nothing bigger
     * than the original is returned.
     */
    fun compressAllLevels(
        input: File,
        outputBase: File,
        // Small-file downscale by default (v0.1.126 — restores the
        // old build): 2048 decodes a 3456x4608 photo at 864x1152,
        // so Smart gives small files again. Pass 10000 for the
        // full-size variation. NOT a fixed size target — the result
        // adapts to each photo.
        maxDimension: Int = 2048
    ): List<LevelResult> {
        val inputSize = input.length()
        val attempts = when (formatOf(input)) {
            Format.JPEG -> listOf(
                Triple(95, Format.JPEG, "JPEG 95%"),
                Triple(85, Format.JPEG, "JPEG 85%"),
                Triple(75, Format.JPEG, "JPEG 75%"),
                Triple(70, Format.JPEG, "JPEG 70%"),
                Triple(60, Format.JPEG, "JPEG 60%")
            )
            Format.WEBP -> listOf(
                Triple(95, Format.WEBP, "WebP 95%"),
                Triple(85, Format.WEBP, "WebP 85%"),
                Triple(75, Format.WEBP, "WebP 75%"),
                Triple(70, Format.WEBP, "WebP 70%"),
                Triple(60, Format.WEBP, "WebP 60%")
            )
            Format.PNG, Format.TIFF, Format.BMP, Format.AVIF -> listOf(
                Triple(100, Format.PNG, "PNG"),
                Triple(95, Format.JPEG, "JPEG 95%"),
                Triple(85, Format.JPEG, "JPEG 85%"),
                Triple(70, Format.JPEG, "JPEG 70%"),
                Triple(60, Format.JPEG, "JPEG 60%")
            )
            Format.GIF, Format.HEIC -> listOf(
                Triple(95, Format.JPEG, "JPEG 95%"),
                Triple(85, Format.JPEG, "JPEG 85%"),
                Triple(75, Format.JPEG, "JPEG 75%"),
                Triple(70, Format.JPEG, "JPEG 70%"),
                Triple(60, Format.JPEG, "JPEG 60%")
            )
        }
        val results = mutableListOf<LevelResult>()
        for ((quality, format, label) in attempts) {
            val candidate = File(
                outputBase.parent,
                "${outputBase.nameWithoutExtension}_${label.replace(" ", "_").replace("%", "")}.${format.extension}"
            )
            // [maxDimension] resolution (v0.1.126): the caller picks
            // the variation — 2048 for the small-file ladder (the
            // old build's super results), 10000 for full size.
            // WebP is separate (its own window, own full-res
            // ladder), so no cross-format comparison happens here.
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

    /**
     * The "Best" result (v0.1.124) is NOT the smallest — the smallest
     * is the LOWEST quality and looks bad. "Best" is the highest-
     * quality result whose file is still close to the smallest
     * (within 2x), i.e. the quality + size BALANCED one. The
     * smallest keeps its own label, "Smaller". Returns null for
     * an empty list.
     */
    fun balancedBest(results: List<LevelResult>): LevelResult? {
        if (results.isEmpty()) return null
        val smallest = results.minByOrNull { it.size } ?: return null
        val limit = smallest.size * 2
        return results.filter { it.size <= limit }.maxByOrNull { it.quality }
            ?: smallest
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

    /** Outer area of a DP Crop: what fills the corners outside
     *  the circle (Circle) or the bands around the fitted photo
     *  (Square). BLUR is the default — the photo's own blurred
     *  colours. TRANSPARENT only applies to Circle (Square is
     *  always a full JPG canvas). */
    enum class DpBackground { BLUR, COLOR, TRANSPARENT }

    /** Cheap, good-looking blur: downscale to ~32px on the short
     *  side, then upscale with bilinear filtering. No RenderScript,
     *  no new library. */
    private fun blurredBackground(bitmap: Bitmap, outW: Int, outH: Int): Bitmap {
        val smallH = (32f * outH / outW).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, 32, smallH, true)
        val blurred = Bitmap.createScaledBitmap(small, outW, outH, true)
        small.recycle()
        return blurred
    }

    /** Composite a square photo into a circle on a square canvas
     *  with the chosen outer area. Returns the composited bitmap
     *  (caller recycles it). */
    private fun renderCircleOnBackground(
        square: Bitmap,
        background: DpBackground,
        color: Int
    ): Bitmap {
        val size = square.width
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        when (background) {
            DpBackground.BLUR -> {
                val bg = blurredBackground(square, size, size)
                canvas.drawBitmap(bg, 0f, 0f, null)
                bg.recycle()
            }
            DpBackground.COLOR -> canvas.drawColor(color)
            DpBackground.TRANSPARENT -> { /* leave transparent */ }
        }
        val path = Path()
        path.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
        canvas.clipPath(path)
        canvas.drawBitmap(square, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))
        return result
    }

    /** Save a composited circle bitmap: PNG when transparent,
     *  JPG 92 otherwise (smaller than PNG). */
    private fun saveCircleBitmap(result: Bitmap, output: File, background: DpBackground): Boolean {
        return try {
            if (background == DpBackground.TRANSPARENT) {
                FileOutputStream(output).use { out ->
                    result.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            } else {
                FileOutputStream(output).use { out ->
                    result.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * DP Crop — Circle: centre-square the photo, mask it to a
     * circle, composite it on a square canvas with the chosen
     * outer area (Blur / Color / Transparent).
     */
    fun dpCircle(input: File, output: File, background: DpBackground, color: Int): Boolean {
        return try {
            val bitmap = FileUtils.decodeBitmapWithExif(input.absolutePath) ?: return false
            val size = minOf(bitmap.width, bitmap.height)
            val x = (bitmap.width - size) / 2
            val y = (bitmap.height - size) / 2
            val square = Bitmap.createBitmap(bitmap, x, y, size, size)
            val result = renderCircleOnBackground(square, background, color)
            val ok = saveCircleBitmap(result, output, background)
            bitmap.recycle()
            square.recycle()
            result.recycle()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /** DP Crop — Circle from a CHOSEN square rect (crop activity):
     *  crop the [left, top, size] square, mask it to a circle,
     *  composite with the chosen outer area. */
    fun dpCircleRect(
        input: File,
        output: File,
        left: Int,
        top: Int,
        size: Int,
        background: DpBackground,
        color: Int
    ): Boolean {
        return try {
            val bitmap = FileUtils.decodeBitmapWithExif(input.absolutePath) ?: return false
            val s = size.coerceIn(1, minOf(bitmap.width, bitmap.height))
            val l = left.coerceIn(0, bitmap.width - s)
            val t = top.coerceIn(0, bitmap.height - s)
            val square = Bitmap.createBitmap(bitmap, l, t, s, s)
            val result = renderCircleOnBackground(square, background, color)
            val ok = saveCircleBitmap(result, output, background)
            bitmap.recycle()
            square.recycle()
            result.recycle()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * DP Crop — Square: the WHOLE photo is fitted (contained,
     *  nothing cropped) inside a square canvas, with the chosen
     *  outer area filling the bands (Blur = the photo's own blurred
     *  colours, Color = a solid colour). Always JPG 92.
     */
    fun dpSquare(input: File, output: File, background: DpBackground, color: Int): Boolean {
        return try {
            val bitmap = FileUtils.decodeBitmapWithExif(input.absolutePath) ?: return false
            val size = maxOf(bitmap.width, bitmap.height)
            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            when (background) {
                DpBackground.BLUR -> {
                    val bg = blurredBackground(bitmap, size, size)
                    canvas.drawBitmap(bg, 0f, 0f, null)
                    bg.recycle()
                }
                DpBackground.COLOR -> canvas.drawColor(color)
                DpBackground.TRANSPARENT -> canvas.drawColor(0xFFFFFFFF.toInt())
            }
            // Fit the whole photo inside, centred.
            val fitScale = minOf(
                size.toFloat() / bitmap.width,
                size.toFloat() / bitmap.height
            )
            val fitW = (bitmap.width * fitScale).toInt().coerceAtLeast(1)
            val fitH = (bitmap.height * fitScale).toInt().coerceAtLeast(1)
            val fitted = Bitmap.createScaledBitmap(bitmap, fitW, fitH, true)
            canvas.drawBitmap(fitted, (size - fitW) / 2f, (size - fitH) / 2f, null)
            FileOutputStream(output).use { out ->
                result.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bitmap.recycle()
            fitted.recycle()
            result.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** DP Crop — Square from a CHOSEN square rect (crop tool,
     *  v0.1.133 Batch 17): crop the [left, top, size] square and
     *  save it as JPG 92. The cropped region IS the square, so
     *  there is no outer area to fill (unlike the old fit flow). */
    fun dpSquareRect(
        input: File,
        output: File,
        left: Int,
        top: Int,
        size: Int
    ): Boolean {
        return try {
            val bitmap = FileUtils.decodeBitmapWithExif(input.absolutePath) ?: return false
            val s = size.coerceIn(1, minOf(bitmap.width, bitmap.height))
            val l = left.coerceIn(0, bitmap.width - s)
            val t = top.coerceIn(0, bitmap.height - s)
            val square = Bitmap.createBitmap(bitmap, l, t, s, s)
            val ok = try {
                FileOutputStream(output).use { out ->
                    square.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                true
            } catch (_: Throwable) {
                false
            }
            bitmap.recycle()
            square.recycle()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * DP Crop — from the ADJUST step (v0.1.124): the photo is placed
     * with the user's adjust transform ([userScale] relative to fit,
     * 1x–4x; [userDx]/[userDy] as fractions of the output square,
     * 0 = centred) on a square canvas with the chosen outer area.
     * Circle masks the photo to a circle (PNG when transparent);
     * Square keeps the whole fitted-adjusted photo. Always full
     * resolution — the adjust view works on a preview, this renders
     * the real file.
     */
    fun dpAdjust(
        input: File,
        output: File,
        circle: Boolean,
        background: DpBackground,
        color: Int,
        userScale: Float,
        userDx: Float,
        userDy: Float
    ): Boolean {
        return try {
            val bitmap = FileUtils.decodeBitmapWithExif(input.absolutePath) ?: return false
            val size = maxOf(bitmap.width, bitmap.height)
            val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            when (background) {
                DpBackground.BLUR -> {
                    val bg = blurredBackground(bitmap, size, size)
                    canvas.drawBitmap(bg, 0f, 0f, null)
                    bg.recycle()
                }
                DpBackground.COLOR -> canvas.drawColor(color)
                DpBackground.TRANSPARENT -> { /* leave transparent */ }
            }
            // Fit scale, then the user's zoom + pan (fractions of the square).
            val fitScale = minOf(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
            val scale = fitScale * userScale.coerceIn(1f, 4f)
            val drawW = bitmap.width * scale
            val drawH = bitmap.height * scale
            val cx = size / 2f + userDx * size
            val cy = size / 2f + userDy * size
            val left = cx - drawW / 2f
            val top = cy - drawH / 2f
            if (circle) {
                val path = Path()
                path.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
                canvas.clipPath(path)
            }
            val matrix = Matrix()
            matrix.setScale(scale, scale)
            matrix.postTranslate(left, top)
            canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG))
            val ok = if (circle && background == DpBackground.TRANSPARENT) {
                FileOutputStream(output).use { out ->
                    result.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                true
            } else {
                FileOutputStream(output).use { out ->
                    result.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                true
            }
            bitmap.recycle()
            result.recycle()
            ok
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Remove Metadata — WITHOUT re-encoding (v0.1.121 fix).
     *
     * The old version decoded the photo and re-encoded it at JPEG
     * 95%, which made most photos BIGGER (a camera JPEG at ~85
     * re-encoded at 95 grows) — the reported bug. This version
     * removes the metadata segments from the file bytes and copies
     * the image data verbatim, so the output is NEVER bigger than
     * the original — only smaller (by the size of the removed
     * metadata), or exactly equal when there is none.
     *
     * JPEG: drops APP1 segments (EXIF + XMP). PNG: drops eXIf /
     * tEXt / iTXt / zTXt chunks. WebP: drops the EXIF chunk from
     * the RIFF container (and clears the VP8X EXIF flag). Anything
     * else falls back to a re-encode that is only kept when it is
     * not bigger than the original.
     */
    fun stripMetadata(input: File, output: File): Boolean {
        return try {
            val bytes = input.readBytes()
            val cleaned: ByteArray? = when {
                isJpegBytes(bytes) -> stripJpegMetadata(bytes)
                isPngBytes(bytes) -> stripPngMetadata(bytes)
                isWebPBytes(bytes) -> stripWebPMetadata(bytes)
                else -> null
            }
            if (cleaned != null) {
                output.writeBytes(cleaned)
                true
            } else {
                stripMetadataReencode(input, output)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /** Re-encode without EXIF (fallback for TIFF/BMP/AVIF) — kept
     *  only when the result is NOT bigger than the original, so
     *  the compression law is never broken. */
    private fun stripMetadataReencode(input: File, output: File): Boolean {
        return try {
            val bitmap = FileUtils.decodeBitmapWithExif(input.absolutePath) ?: return false
            val isPng = formatOf(input) == Format.PNG
            val format = if (isPng) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            for (quality in listOf(90, 75)) {
                val tmp = File(
                    output.parent,
                    "hakunaa_clean_tmp_${System.currentTimeMillis()}.jpg"
                )
                FileOutputStream(tmp).use { out ->
                    bitmap.compress(format, if (isPng) 100 else quality, out)
                }
                if (tmp.exists() && tmp.length() <= input.length()) {
                    tmp.copyTo(output, overwrite = true)
                    try { tmp.delete() } catch (_: Throwable) { }
                    bitmap.recycle()
                    return true
                }
                try { tmp.delete() } catch (_: Throwable) { }
            }
            bitmap.recycle()
            false
        } catch (_: Throwable) {
            false
        }
    }

    private fun isJpegBytes(b: ByteArray) =
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    private fun isPngBytes(b: ByteArray) =
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() &&
            b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()

    private fun isWebPBytes(b: ByteArray) =
        b.size >= 12 && b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() &&
            b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() &&
            b[10] == 'B'.code.toByte() && b[11] == 'P'.code.toByte()

    /** JPEG: copy every segment verbatim except APP1 (EXIF + XMP).
     *  Returns null when the file is malformed (caller falls back).
     *  The scan data after SOS is copied as-is, never parsed. */
    private fun stripJpegMetadata(bytes: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 2) // SOI
        var pos = 2
        while (pos + 1 < bytes.size) {
            if (bytes[pos] != 0xFF.toByte()) return null
            val marker = bytes[pos + 1].toInt() and 0xFF
            if (marker == 0xD9) { // EOI — copy the rest and finish.
                out.write(bytes, pos, bytes.size - pos)
                return out.toByteArray()
            }
            if (marker == 0xDA) { // SOS — scan data follows; copy it all.
                out.write(bytes, pos, bytes.size - pos)
                return out.toByteArray()
            }
            if (marker == 0x01 || marker in 0xD0..0xD7) {
                // Standalone markers have no length field.
                out.write(bytes, pos, 2)
                pos += 2
                continue
            }
            if (pos + 3 >= bytes.size) return null
            val len = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            if (len < 2 || pos + 2 + len > bytes.size) return null
            val isExif = marker == 0xE1 && len >= 8 &&
                bytes[pos + 4] == 'E'.code.toByte() &&
                bytes[pos + 5] == 'x'.code.toByte() &&
                bytes[pos + 6] == 'i'.code.toByte() &&
                bytes[pos + 7] == 'f'.code.toByte()
            val isXmp = marker == 0xE1 && len >= 33 &&
                startsWithAscii(bytes, pos + 4, "http://ns.adobe.com/xap/1.0/")
            if (!isExif && !isXmp) {
                out.write(bytes, pos, 2 + len)
            }
            pos += 2 + len
        }
        return null
    }

    private fun startsWithAscii(bytes: ByteArray, pos: Int, prefix: String): Boolean {
        if (pos + prefix.length > bytes.size) return false
        for (i in prefix.indices) {
            if (bytes[pos + i] != prefix[i].code.toByte()) return false
        }
        return true
    }

    /** PNG: copy every chunk verbatim except the metadata chunks
     *  (eXIf / tEXt / iTXt / zTXt). Returns null when malformed. */
    private fun stripPngMetadata(bytes: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 8) // PNG signature
        var pos = 8
        while (pos + 8 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val total = 8 + len + 4
            if (len < 0 || pos + total > bytes.size) return null
            if (type !in setOf("eXIf", "tEXt", "iTXt", "zTXt")) {
                out.write(bytes, pos, total)
            }
            pos += total
            if (type == "IEND") return out.toByteArray()
        }
        return null
    }

    /** WebP: copy every RIFF chunk verbatim except EXIF, and clear
     *  the VP8X EXIF-present flag so the file stays consistent.
     *  Returns the input unchanged (same size) when no EXIF chunk
     *  exists. Returns null when malformed. */
    private fun stripWebPMetadata(bytes: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 12) // RIFF header
        var pos = 12
        var removedExif = false
        while (pos + 8 <= bytes.size) {
            val type = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = (bytes[pos + 4].toInt() and 0xFF) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            val total = 8 + size + (size % 2)
            if (size < 0 || pos + total > bytes.size) return null
            if (type == "EXIF") {
                removedExif = true
            } else if (type == "VP8X" && size >= 10) {
                out.write(bytes, pos, 8)
                // Clear the EXIF-present flag (bit 3 of byte 0).
                out.write((bytes[pos + 8].toInt() and 0xFF) and 0xF7)
                out.write(bytes, pos + 9, total - 9)
            } else {
                out.write(bytes, pos, total)
            }
            pos += total
        }
        if (!removedExif) return bytes.copyOf()
        val result = out.toByteArray()
        // Fix the RIFF size field (file size - 8, little-endian).
        val riffSize = result.size - 8
        result[4] = (riffSize and 0xFF).toByte()
        result[5] = ((riffSize shr 8) and 0xFF).toByte()
        result[6] = ((riffSize shr 16) and 0xFF).toByte()
        result[7] = ((riffSize shr 24) and 0xFF).toByte()
        return result
    }
}
