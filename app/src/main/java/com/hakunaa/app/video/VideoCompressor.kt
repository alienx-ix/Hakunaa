package com.hakunaa.app.video

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.hakunaa.app.core.Presets

/**
 * Video tools — FFmpegKit (native FFmpeg), same engine as the
 * analysed reference app (Video Converter v1.2.15, FFmpegKit GPL
 * with libx264/libx265). Three operations:
 * - Compress: re-encode at a chosen CRF/codec/resolution (MP4 out).
 * - Convert: re-encode into another container (MP4/WebM/MOV/MKV).
 * - MP3: extract the audio track as an MP3 file.
 *
 * Compress keeps ORIGINAL resolution by default — no -vf scale
 * filter is added unless the user picks 720p/1080p. That is the
 * whole quality rule: other apps blur because they drop to 720p /
 * 800 kbps. Resolution is the user's choice, not a hidden default.
 *
 * Default compress: libx264, CRF 28, preset fast, AAC 128k.
 * H.265 (libx265) is offered as an option: ~50% smaller than H.264
 * at the same quality, but slower and older phones may not play it.
 */
object VideoCompressor {
    enum class Codec(val ffmpegName: String, val fileSuffix: String) {
        H264("libx264", "h264"),
        H265("libx265", "h265"),
        // AV1 (libaom-av1): 20–30% smaller than H.265 at the same quality,
        // but very slow on phones. Offered as an option, never the default.
        // AV1 CRF runs 0–63; the same preset CRF values are reused here.
        AV1("libaom-av1", "av1")
    }

    /** Output resolution. ORIGINAL keeps the source size (no scale filter). */
    enum class Resolution(val scaleFilter: String?, val label: String) {
        ORIGINAL(null, "Original"),
        P720("scale=-2:720", "720p"),
        P1080("scale=-2:1080", "1080p")
    }

    /** Output container for Convert. WebM uses VP9 + Opus (best for web). */
    enum class OutputFormat(
        val extension: String,
        val videoCodec: String,
        val audioCodec: String,
        val mime: String,
        val label: String
    ) {
        MP4("mp4", "libx264", "aac", "video/mp4", "MP4"),
        WEBM("webm", "libvpx-vp9", "libopus", "video/webm", "WebM"),
        MOV("mov", "libx264", "aac", "video/quicktime", "MOV"),
        MKV("mkv", "libx264", "aac", "video/x-matroska", "MKV")
    }

    /**
     * Build the Compress command as an argument ARRAY (argv), not a
     * single String. FFmpegKit's String-command parser splits on
     * spaces and mishandles quoting — that is what produced
     * "Unrecognized option 'preset'" even though the command text
     * was correct. An argv array passes each argument exactly, so
     * no parsing can shift or merge options. This is the robust,
     * correct way to invoke FFmpegKit.
     */
    fun buildCommand(
        inputPath: String,
        outputPath: String,
        crf: Int,
        codec: Codec = Codec.H264,
        preset: String = Presets.VIDEO_PRESET,
        hasAudio: Boolean = true,
        resolution: Resolution = Resolution.ORIGINAL
    ): Array<String> {
        val args = mutableListOf<String>()
        args += "-i"; args += inputPath
        args += "-c:v"; args += codec.ffmpegName
        // "-preset" is BACK (v0.1.16). It was removed in v0.1.13 because it
        // failed with "Unrecognized option 'preset'" — but that failure was
        // a symptom, not the cause: the old non-GPL engine had NO libx264,
        // so there was no encoder to accept the option. Now that the GPL
        // engine (v0.1.15) has libx264, "-preset fast" works — proven by
        // the reference Video Converter app, which uses -crf + -preset +
        // libx264 with the same GPL FFmpegKit. "fast" matches the
        // reference app's Compress mode and is faster than libx264's
        // default "medium" at the same CRF quality.
        args += "-preset"; args += preset
        args += "-crf"; args += crf.toString()
        args += "-pix_fmt"; args += "yuv420p"
        // Resolution: only scale when the user picked one. ORIGINAL
        // adds no filter at all, so the source resolution is kept.
        resolution.scaleFilter?.let { filter ->
            args += "-vf"; args += filter
        }
        // H.265 needs a tag that plays on most phones; AV1 uses
        // cpu-used 4 (faster, still good quality).
        when (codec) {
            Codec.H265 -> { args += "-tag:v"; args += "hvc1" }
            Codec.AV1 -> { args += "-cpu-used"; args += "4" }
            else -> {}
        }
        // Audio ONLY if the input actually has an audio stream.
        // Forcing "-c:a aac" on a video with no audio makes FFmpeg
        // fail. Audio presence is probed up front with FFprobe.
        if (hasAudio) {
            args += "-c:a"; args += "aac"
            args += "-b:a"; args += Presets.AUDIO_BITRATE
        }
        args += outputPath
        return args.toTypedArray()
    }

    /**
     * Build the Convert command: re-encode into another container.
     * Same CRF quality as Compress (CRF 28 default), same preset.
     * WebM uses VP9 + Opus; MP4/MOV/MKV use H.264 + AAC.
     * Resolution is honoured the same way as Compress.
     */
    fun buildConvertCommand(
        inputPath: String,
        outputPath: String,
        format: OutputFormat,
        crf: Int = 28,
        preset: String = Presets.VIDEO_PRESET,
        hasAudio: Boolean = true,
        resolution: Resolution = Resolution.ORIGINAL
    ): Array<String> {
        val args = mutableListOf<String>()
        args += "-i"; args += inputPath
        args += "-c:v"; args += format.videoCodec
        if (format.videoCodec == "libx264") {
            args += "-preset"; args += preset
            args += "-crf"; args += crf.toString()
            args += "-pix_fmt"; args += "yuv420p"
        } else {
            // VP9: CRF works the same way (0–63); keep the same value.
            args += "-crf"; args += crf.toString()
            args += "-b:v"; args += "0"
            args += "-pix_fmt"; args += "yuv420p"
        }
        resolution.scaleFilter?.let { filter ->
            args += "-vf"; args += filter
        }
        if (hasAudio) {
            args += "-c:a"; args += format.audioCodec
            args += "-b:a"; args += Presets.AUDIO_BITRATE
        }
        args += outputPath
        return args.toTypedArray()
    }

    /**
     * Build the MP3 command: drop the video, keep the audio.
     * libmp3lame at 192 kbps — good quality, plays everywhere.
     */
    fun buildMp3Command(inputPath: String, outputPath: String): Array<String> {
        return arrayOf(
            "-i", inputPath,
            "-vn",
            "-c:a", "libmp3lame",
            "-b:a", "192k",
            outputPath
        )
    }

    // Is the native video engine loadable on this phone?
    // On some Android 15 devices the FFmpeg native libs cannot load
    // (e.g. 16 KB page size). Check once, up front, so a failed load
    // becomes a message instead of a crash.
    private var engineChecked = false
    private var engineOk = false

    fun isEngineAvailable(): Boolean {
        if (engineChecked) return engineOk
        engineChecked = true
        engineOk = try {
            System.loadLibrary("ffmpegkit")
            true
        } catch (_: Throwable) {
            false
        }
        return engineOk
    }

    fun compress(
        inputPath: String,
        outputPath: String,
        crf: Int,
        codec: Codec = Codec.H264,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            executeCommand(
                buildCommand(inputPath, outputPath, crf, codec),
                onProgress, onDone
            )
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    fun compressWithResolution(
        inputPath: String,
        outputPath: String,
        crf: Int,
        codec: Codec = Codec.H264,
        resolution: Resolution = Resolution.ORIGINAL,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            executeCommand(
                buildCommand(inputPath, outputPath, crf, codec, Presets.VIDEO_PRESET, true, resolution),
                onProgress, onDone
            )
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    fun convert(
        inputPath: String,
        outputPath: String,
        format: OutputFormat,
        crf: Int = 28,
        resolution: Resolution = Resolution.ORIGINAL,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            executeCommand(
                buildConvertCommand(inputPath, outputPath, format, crf, Presets.VIDEO_PRESET, true, resolution),
                onProgress, onDone
            )
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    fun extractMp3(
        inputPath: String,
        outputPath: String,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            executeCommand(
                buildMp3Command(inputPath, outputPath),
                onProgress, onDone
            )
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    /**
     * Shared execution for Compress / Convert / MP3: probe the input
     * with FFprobe (duration for the progress bar, audio presence),
     * then run the exact argv via FFmpegSession (no String parsing).
     * Audio arguments are stripped when the input has no audio
     * stream — forcing "-c:a aac" on a silent video makes FFmpeg
     * fail. For MP3 extraction, a video with no audio fails fast
     * with a clear message instead of an FFmpeg error.
     */
    private fun executeCommand(
        argv: Array<String>,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        // Probe duration first, so the progress bar has a denominator,
        // and check whether the input has an audio stream.
        val inputPath = argv.getOrNull(argv.indexOf("-i") + 1) ?: ""
        var durationMs: Long = 0
        var hasAudio = true
        try {
            val info = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(inputPath)
            durationMs = info.mediaInformation?.duration?.toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
            hasAudio = info.mediaInformation?.streams?.any { stream ->
                stream.type?.equals("audio", ignoreCase = true) == true
            } ?: true
        } catch (_: Exception) {
            durationMs = 0
            hasAudio = true
        }
        val isMp3 = argv.contains("-vn")
        if (!hasAudio) {
            if (isMp3) {
                onDone(false, "This video has no audio track to extract")
                return
            }
            // Strip the audio arguments (-c:a <codec> -b:a <bitrate>)
            // so a silent video still compresses/converts fine.
            val stripped = mutableListOf<String>()
            var i = 0
            while (i < argv.size) {
                when (argv[i]) {
                    "-c:a" -> i += 2
                    "-b:a" -> i += 2
                    else -> { stripped += argv[i]; i++ }
                }
            }
            runSession(stripped.toTypedArray(), durationMs, onProgress, onDone)
            return
        }
        runSession(argv, durationMs, onProgress, onDone)
    }

    /** Run one argv via FFmpegSession with progress + real error detail. */
    private fun runSession(
        argv: Array<String>,
        durationMs: Long,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        // FFmpegKit 6.0 has no disableStatisticsCallback(); stop progress
        // with a flag instead — the callback returns early once finished.
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
        // Execute via an FFmpegSession built from an argv array (see
        // buildCommand). FFmpegKit.executeAsync(String) only takes a
        // single String and re-parses it, which is what broke the
        // command. FFmpegSession.create(String[], callback) +
        // asyncFFmpegExecute(session) runs the exact argv, no parsing.
        val outputPath = argv.last()
        val session = com.arthenica.ffmpegkit.FFmpegSession.create(argv) { s ->
            finished = true
            val ok = ReturnCode.isSuccess(s.returnCode)
            // On failure, show the real FFmpeg error (last lines of the
            // session output) instead of the generic "FFmpeg failed" —
            // otherwise a failure can never be diagnosed from the phone.
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
        com.arthenica.ffmpegkit.FFmpegKitConfig.asyncFFmpegExecute(session)
        // Keep the session reference alive for cancellation in the Activity.
        currentSessionId = session.sessionId
    }

    var currentSessionId: Long = 0L
        private set

    fun cancel() {
        if (currentSessionId != 0L) {
            try {
                FFmpegKit.cancel(currentSessionId)
            } catch (_: Throwable) {
                // Engine already gone — nothing to cancel.
            }
        }
    }
}
