package com.hakunaa.app.video

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.hakunaa.app.core.Presets
import java.io.File

/**
 * Video Tools engine (v0.1.159) — NEW file. All the FFmpeg work the
 * 10 Video Tools need that VideoCompressor does not already do:
 * Convert to 13 formats, audio extract in 5 formats, cut / split /
 * crop / thumbnail-cover / color grading / Advanced compress
 * (CRF-or-bitrate, two-pass, preset, denoise, sharpen).
 *
 * VideoCompressor.kt is NOT touched (user rule: algorithm
 * untouched). The execution pattern here mirrors it exactly:
 * argv array (no String parsing), FFprobe up front for duration
 * + audio presence, FFmpegSession + statistics callback for
 * progress, real FFmpeg error detail on failure.
 */
object VideoToolsEngine {

    data class ProbeInfo(
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val hasAudio: Boolean
    )

    fun probe(path: String): ProbeInfo? {
        return try {
            val info = FFprobeKit.getMediaInformation(path)
            val mi = info.mediaInformation ?: return null
            val durationMs = mi.duration?.toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
            var width = 0
            var height = 0
            var hasAudio = false
            try {
                for (stream in mi.streams ?: emptyList()) {
                    val type = stream.type ?: ""
                    if (type.equals("video", ignoreCase = true)) {
                        width = try { stream.width.toInt() } catch (_: Throwable) { 0 }
                        height = try { stream.height.toInt() } catch (_: Throwable) { 0 }
                    }
                    if (type.equals("audio", ignoreCase = true)) hasAudio = true
                }
            } catch (_: Throwable) {
            }
            ProbeInfo(durationMs, width, height, hasAudio)
        } catch (_: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Shared execution (mirrors VideoCompressor.runSession).
    // ------------------------------------------------------------------

    var currentSessionId: Long = 0L
        private set

    fun cancel() {
        if (currentSessionId != 0L) {
            try {
                FFmpegKit.cancel(currentSessionId)
            } catch (_: Throwable) {
            }
        }
    }

    private fun stripAudioArgs(argv: Array<String>): Array<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < argv.size) {
            when (argv[i]) {
                "-c:a" -> i += 2
                "-b:a" -> i += 2
                else -> {
                    out += argv[i]
                    i++
                }
            }
        }
        return out.toTypedArray()
    }

    /**
     * Probe the input, then run. Audio args are stripped when the
     * input has no audio stream (forcing -c:a on a silent video
     * makes FFmpeg fail). [audioOnly] fails fast with a clear
     * message when there is no audio track to extract.
     */
    fun runWithProbe(
        argv: Array<String>,
        audioOnly: Boolean,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        val inputPath = argv.getOrNull(argv.indexOf("-i") + 1) ?: ""
        var durationMs = 0L
        var hasAudio = true
        try {
            val p = probe(inputPath)
            if (p != null) {
                durationMs = p.durationMs
                hasAudio = p.hasAudio
            }
        } catch (_: Throwable) {
        }
        if (!hasAudio) {
            if (audioOnly) {
                onDone(false, "This video has no audio track to extract")
                return
            }
            runSession(stripAudioArgs(argv), durationMs, onProgress, onDone)
            return
        }
        runSession(argv, durationMs, onProgress, onDone)
    }

    fun runSession(
        argv: Array<String>,
        durationMs: Long,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        var finished = false
        FFmpegKitConfig.enableStatisticsCallback { statistics: Statistics ->
            if (finished) return@enableStatisticsCallback
            val time = statistics.time
            if (durationMs > 0 && time > 0) {
                val percent = ((time.toDouble() / durationMs.toDouble()) * 100.0)
                    .toInt().coerceIn(0, 100)
                onProgress(percent)
            }
        }
        val outputPath = argv.last()
        val session = FFmpegSession.create(argv) { s ->
            finished = true
            val ok = ReturnCode.isSuccess(s.returnCode)
            val detail = if (ok) outputPath else {
                val out = s.output ?: ""
                val tail = out.trim().lines().filter { it.isNotBlank() }
                    .takeLast(3).joinToString(" | ").take(300)
                val base = s.failStackTrace?.trim()?.take(200)
                when {
                    tail.isNotBlank() -> tail
                    !base.isNullOrBlank() -> base
                    else -> "FFmpeg failed"
                }
            }
            onDone(ok, detail)
        }
        FFmpegKitConfig.asyncFFmpegExecute(session)
        currentSessionId = session.sessionId
    }

    // ------------------------------------------------------------------
    // Convert — 13 formats.
    // ------------------------------------------------------------------

    enum class ConvertFormat(
        val label: String,
        val extension: String,
        val videoCodec: String,
        val audioCodec: String,
        val mime: String,
        val qualityArg: String,
        val isGif: Boolean = false
    ) {
        MP4("MP4", "mp4", "libx264", "aac", "video/mp4", "crf"),
        GP3("3GP", "3gp", "mpeg4", "aac", "video/3gpp", "qv"),
        MOV("MOV", "mov", "libx264", "aac", "video/quicktime", "crf"),
        FLV("FLV", "flv", "libx264", "aac", "video/x-flv", "crf"),
        MKV("MKV", "mkv", "libx264", "aac", "video/x-matroska", "crf"),
        AVI("AVI", "avi", "mpeg4", "libmp3lame", "video/x-msvideo", "qv"),
        MTS("MTS", "mts", "libx264", "aac", "video/mp2t", "crf"),
        TS("TS", "ts", "libx264", "aac", "video/mp2t", "crf"),
        M4V("M4V", "m4v", "libx264", "aac", "video/x-m4v", "crf"),
        WEBM("WebM", "webm", "libvpx-vp9", "libopus", "video/webm", "vp9"),
        GIF("GIF", "gif", "gif", "", "image/gif", "gif", true),
        WMV("WMV", "wmv", "wmv2", "wmav2", "video/x-ms-wmv", "qv"),
        MPG("MPG", "mpg", "mpeg2video", "mp2", "video/mpeg", "qv")
    }

    fun buildConvert13Command(
        inputPath: String,
        outputPath: String,
        format: ConvertFormat,
        crf: Int = 28,
        resolution: VideoCompressor.Resolution = VideoCompressor.Resolution.ORIGINAL,
        hasAudio: Boolean = true
    ): Array<String> {
        val args = mutableListOf<String>()
        args += "-i"
        args += inputPath
        if (format.isGif) {
            // GIF: no audio, 12 fps, 480px wide (keeps the file
            // small; resolution choice does not apply to GIF).
            args += "-vf"
            args += "fps=12,scale=480:-1:flags=lanczos"
            args += "-c:v"
            args += "gif"
            args += "-an"
            args += outputPath
            return args.toTypedArray()
        }
        args += "-c:v"
        args += format.videoCodec
        when (format.qualityArg) {
            "crf" -> {
                args += "-crf"
                args += crf.toString()
                args += "-pix_fmt"
                args += "yuv420p"
            }
            "vp9" -> {
                args += "-crf"
                args += crf.toString()
                args += "-b:v"
                args += "0"
                args += "-pix_fmt"
                args += "yuv420p"
            }
            else -> {
                // mpeg4 / wmv2 / mpeg2video: no CRF — -q:v 3 is
                // good quality for these older codecs.
                args += "-q:v"
                args += "3"
            }
        }
        resolution.scaleFilter?.let { filter ->
            args += "-vf"
            args += filter
        }
        if (hasAudio) {
            args += "-c:a"
            args += format.audioCodec
            args += "-b:a"
            args += Presets.AUDIO_BITRATE
        }
        args += outputPath
        return args.toTypedArray()
    }

    fun convert13(
        inputPath: String,
        outputPath: String,
        format: ConvertFormat,
        resolution: VideoCompressor.Resolution = VideoCompressor.Resolution.ORIGINAL,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            runWithProbe(
                buildConvert13Command(inputPath, outputPath, format, 28, resolution, true),
                false, onProgress, onDone
            )
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    // ------------------------------------------------------------------
    // Video to Audio — 5 formats.
    // ------------------------------------------------------------------

    enum class AudioFormat(
        val label: String,
        val extension: String,
        val codec: String,
        val mime: String,
        val needsBitrate: Boolean
    ) {
        MP3("MP3", "mp3", "libmp3lame", "audio/mpeg", true),
        M4A("M4A", "m4a", "aac", "audio/mp4", true),
        WAV("WAV", "wav", "pcm_s16le", "audio/wav", false),
        OGG("OGG", "ogg", "libopus", "audio/ogg", true),
        FLAC("FLAC", "flac", "flac", "audio/flac", false)
    }

    fun buildAudioCommand(
        inputPath: String,
        outputPath: String,
        format: AudioFormat
    ): Array<String> {
        val args = mutableListOf<String>()
        args += "-i"
        args += inputPath
        args += "-vn"
        args += "-c:a"
        args += format.codec
        if (format.needsBitrate) {
            args += "-b:a"
            args += "192k"
        }
        args += outputPath
        return args.toTypedArray()
    }

    fun extractAudio(
        inputPath: String,
        outputPath: String,
        format: AudioFormat,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            runWithProbe(
                buildAudioCommand(inputPath, outputPath, format),
                true, onProgress, onDone
            )
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    // ------------------------------------------------------------------
    // Cutter (stream copy, no re-encode) + Splitter (segment).
    // ------------------------------------------------------------------

    fun buildCutCommand(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long
    ): Array<String> {
        return arrayOf(
            "-ss", (startMs / 1000.0).toString(),
            "-to", (endMs / 1000.0).toString(),
            "-i", inputPath,
            "-c", "copy",
            outputPath
        )
    }

    fun cut(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long,
        durationMs: Long,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            // Stream copy has no time-based progress (no re-encode),
            // so the session runs without a duration denominator.
            runSession(buildCutCommand(inputPath, outputPath, startMs, endMs), durationMs, onProgress, onDone)
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    fun buildSplitCommand(
        inputPath: String,
        outputPattern: String,
        partSeconds: Int
    ): Array<String> {
        return arrayOf(
            "-i", inputPath,
            "-f", "segment",
            "-segment_time", partSeconds.toString(),
            "-c", "copy",
            "-reset_timestamps", "1",
            outputPattern
        )
    }

    fun split(
        inputPath: String,
        outputPattern: String,
        partSeconds: Int,
        durationMs: Long,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            runSession(buildSplitCommand(inputPath, outputPattern, partSeconds), durationMs, onProgress, onDone)
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    // ------------------------------------------------------------------
    // Crop — centered crop to an aspect, re-encoded at CRF 28.
    // ------------------------------------------------------------------

    fun buildCropCommand(
        inputPath: String,
        outputPath: String,
        cropW: Int,
        cropH: Int,
        x: Int,
        y: Int,
        hasAudio: Boolean = true
    ): Array<String> {
        val args = mutableListOf<String>()
        args += "-i"
        args += inputPath
        args += "-vf"
        args += "crop=$cropW:$cropH:$x:$y"
        args += "-c:v"
        args += "libx264"
        args += "-crf"
        args += "28"
        args += "-pix_fmt"
        args += "yuv420p"
        if (hasAudio) {
            args += "-c:a"
            args += "copy"
        }
        args += outputPath
        return args.toTypedArray()
    }

    fun crop(
        inputPath: String,
        outputPath: String,
        cropW: Int,
        cropH: Int,
        x: Int,
        y: Int,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            runWithProbe(
                buildCropCommand(inputPath, outputPath, cropW, cropH, x, y, true),
                false, onProgress, onDone
            )
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    // ------------------------------------------------------------------
    // Thumbnail — extract one frame, then attach it as the cover.
    // ------------------------------------------------------------------

    fun buildFrameCommand(
        inputPath: String,
        outputJpg: String,
        timeMs: Long
    ): Array<String> {
        return arrayOf(
            "-ss", (timeMs / 1000.0).toString(),
            "-i", inputPath,
            "-frames:v", "1",
            "-q:v", "2",
            outputJpg
        )
    }

    fun extractFrame(
        inputPath: String,
        outputJpg: String,
        timeMs: Long,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            // Single frame: no progress denominator needed.
            runSession(buildFrameCommand(inputPath, outputJpg, timeMs), 0L, {}, onDone)
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    fun buildAttachCoverCommand(
        inputPath: String,
        coverJpg: String,
        outputPath: String
    ): Array<String> {
        return arrayOf(
            "-i", inputPath,
            "-i", coverJpg,
            "-map", "0",
            "-map", "1",
            "-c", "copy",
            "-c:v:1", "png",
            "-disposition:v:1", "attached_pic",
            outputPath
        )
    }

    fun attachCover(
        inputPath: String,
        coverJpg: String,
        outputPath: String,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            runSession(buildAttachCoverCommand(inputPath, coverJpg, outputPath), 0L, {}, onDone)
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    // ------------------------------------------------------------------
    // Color grading — eq + hue + unsharp + colortemperature.
    // Slider values are passed pre-scaled; a filter part is only
    // added when it differs from neutral, so "no change" stays a
    // plain re-encode at CRF 28.
    // ------------------------------------------------------------------

    /**
     * Build the -vf filter string. brightness -1..1, contrast
     * 0..2, saturation 0..3, hueDeg -180..180, sharpness 0..2,
     * temperatureK 2000..10000 (6500 = neutral, no filter).
     * Returns "" when everything is neutral.
     */
    fun colorFilter(
        brightness: Float,
        contrast: Float,
        saturation: Float,
        hueDeg: Int,
        sharpness: Float,
        temperatureK: Int
    ): String {
        val parts = mutableListOf<String>()
        if (brightness != 0f || contrast != 1f || saturation != 1f) {
            parts += "eq=brightness=$brightness:contrast=$contrast:saturation=$saturation"
        }
        if (hueDeg != 0) parts += "hue=h=$hueDeg"
        if (sharpness > 0f) parts += "unsharp=5:5:$sharpness:5:5:0.0"
        if (temperatureK != 6500) parts += "colortemperature=temperature=$temperatureK:mix=1"
        return parts.joinToString(",")
    }

    fun buildColorCommand(
        inputPath: String,
        outputPath: String,
        filter: String,
        hasAudio: Boolean = true
    ): Array<String> {
        val args = mutableListOf<String>()
        args += "-i"
        args += inputPath
        if (filter.isNotEmpty()) {
            args += "-vf"
            args += filter
        }
        args += "-c:v"
        args += "libx264"
        args += "-crf"
        args += "28"
        args += "-pix_fmt"
        args += "yuv420p"
        if (hasAudio) {
            args += "-c:a"
            args += "aac"
            args += "-b:a"
            args += Presets.AUDIO_BITRATE
        }
        args += outputPath
        return args.toTypedArray()
    }

    fun colorGrade(
        inputPath: String,
        outputPath: String,
        filter: String,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            runWithProbe(
                buildColorCommand(inputPath, outputPath, filter, true),
                false, onProgress, onDone
            )
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    fun buildColorFrameCommand(
        inputPath: String,
        outputJpg: String,
        timeMs: Long,
        filter: String
    ): Array<String> {
        val args = mutableListOf<String>()
        args += "-ss"
        args += (timeMs / 1000.0).toString()
        args += "-i"
        args += inputPath
        if (filter.isNotEmpty()) {
            args += "-vf"
            args += filter
        }
        args += "-frames:v"
        args += "1"
        args += "-q:v"
        args += "2"
        args += outputJpg
        return args.toTypedArray()
    }

    fun colorFrame(
        inputPath: String,
        outputJpg: String,
        timeMs: Long,
        filter: String,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            runSession(buildColorFrameCommand(inputPath, outputJpg, timeMs, filter), 0L, {}, onDone)
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    // ------------------------------------------------------------------
    // Advanced compress — CRF mode OR bitrate mode (alternative),
    // preset, resolution, FPS, codec, audio, two-pass (bitrate
    // mode only), denoise + sharpen. All in one FFmpeg command.
    // ------------------------------------------------------------------

    data class AdvancedOptions(
        val crf: Int,
        val useBitrate: Boolean,
        val bitrateMbps: Int,
        val resolution: VideoCompressor.Resolution,
        val fps: Int, // 0 = original
        val codec: VideoCompressor.Codec,
        val audioKeep: Boolean,
        val audioBitrate: String,
        val twoPass: Boolean,
        val format: String, // mp4 / mkv / mov
        val preset: String, // ultrafast / fast / medium / slow
        val denoise: Boolean,
        val sharpen: Boolean
    )

    fun advancedVideoFilter(opts: AdvancedOptions): String {
        val parts = mutableListOf<String>()
        opts.resolution.scaleFilter?.let { parts += it }
        if (opts.denoise) parts += "hqdn3d=4:3:6:4.5"
        if (opts.sharpen) parts += "unsharp=5:5:0.8:5:5:0.0"
        return parts.joinToString(",")
    }

    fun buildAdvancedCommand(
        inputPath: String,
        outputPath: String,
        opts: AdvancedOptions,
        hasAudio: Boolean = true,
        pass: Int = 0, // 0 = single pass, 1/2 = two-pass stage
        passLogFile: String = ""
    ): Array<String> {
        val args = mutableListOf<String>()
        args += "-i"
        args += inputPath
        val filter = advancedVideoFilter(opts)
        if (filter.isNotEmpty()) {
            args += "-vf"
            args += filter
        }
        args += "-c:v"
        args += opts.codec.ffmpegName
        args += "-preset"
        args += opts.preset
        if (opts.useBitrate) {
            args += "-b:v"
            args += "${opts.bitrateMbps}M"
            if (pass > 0) {
                args += "-pass"
                args += pass.toString()
                args += "-passlogfile"
                args += passLogFile
            }
        } else {
            args += "-crf"
            args += opts.crf.toString()
        }
        args += "-pix_fmt"
        args += "yuv420p"
        // hev1 is the Android/MP4 tag for HEVC — only for MP4
        // output. MKV/MOV do not use it.
        if (opts.codec == VideoCompressor.Codec.H265 && opts.format == "mp4") {
            args += "-tag:v"
            args += "hev1"
        }
        if (opts.fps > 0) {
            args += "-r"
            args += opts.fps.toString()
        }
        if (pass == 1) {
            // Pass 1: no audio, output discarded.
            args += "-an"
            args += "-f"
            args += "null"
            args += "/dev/null"
            return args.toTypedArray()
        }
        if (hasAudio && opts.audioKeep) {
            args += "-c:a"
            args += "aac"
            args += "-b:a"
            args += opts.audioBitrate
        }
        args += outputPath
        return args.toTypedArray()
    }

    fun advanced(
        inputPath: String,
        outputPath: String,
        opts: AdvancedOptions,
        cacheDir: File,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            val useTwoPass = opts.useBitrate && opts.twoPass
            if (!useTwoPass) {
                runWithProbe(
                    buildAdvancedCommand(inputPath, outputPath, opts, true),
                    false, onProgress, onDone
                )
                return
            }
            // Two-pass: pass 1 analyzes (output discarded), pass 2
            // encodes with the smart bit distribution.
            val logFile = File(cacheDir, "twopass_${System.currentTimeMillis()}").absolutePath
            val pass1 = buildAdvancedCommand(inputPath, outputPath, opts, true, 1, logFile)
            runWithProbe(pass1, false, onProgress) { ok1, msg1 ->
                if (!ok1) {
                    onDone(false, msg1)
                    return@runWithProbe
                }
                val pass2 = buildAdvancedCommand(inputPath, outputPath, opts, true, 2, logFile)
                runWithProbe(pass2, false, onProgress) { ok2, msg2 ->
                    // Clean the pass log files.
                    try {
                        File("$logFile-0.log").delete()
                        File("$logFile-0.log.mbtree").delete()
                    } catch (_: Throwable) {
                    }
                    onDone(ok2, msg2)
                }
            }
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }
}
