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
        P1080("scale=-2:1080", "1080p"),
        P480("scale=-2:480", "480p")
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

    /** Rotate / flip a video. The filter is applied to the video
     *  stream; the audio stream is copied untouched. */
    enum class RotateMode(val filter: String, val label: String) {
        CW90("transpose=1", "90° Clockwise"),
        CCW90("transpose=2", "90° Counter-clockwise"),
        ROT180("transpose=1,transpose=1", "180°"),
        FLIP_H("hflip", "Flip Horizontal"),
        FLIP_V("vflip", "Flip Vertical")
    }

    /**
     * Build the Rotate command: re-encode with libx264 at CRF 28
     * (same quality as Compress), apply the rotate/flip filter,
     * copy the audio stream as-is.
     */
    fun buildRotateCommand(
        inputPath: String,
        outputPath: String,
        mode: RotateMode,
        crf: Int = 28,
        hasAudio: Boolean = true
    ): Array<String> {
        val args = mutableListOf<String>()
        args += "-i"; args += inputPath
        args += "-c:v"; args += "libx264"
        args += "-crf"; args += crf.toString()
        args += "-pix_fmt"; args += "yuv420p"
        args += "-vf"; args += mode.filter
        if (hasAudio) {
            args += "-c:a"; args += "aac"
            args += "-b:a"; args += Presets.AUDIO_BITRATE
        }
        args += outputPath
        return args.toTypedArray()
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
        // No "-preset" option. The reference app uses "-preset fast",
        // but "fast" trades compression efficiency for speed — on an
        // already-compressed video it produced a BIGGER file (11.7 MB
        // → 12.0 MB). libx264's default preset "medium" compresses
        // better (smaller files) at the same CRF, so we leave the
        // preset out and let libx264 use its default. The user asked
        // for the BEST compression, not the fastest — so efficiency
        // wins over speed here.
        args += "-crf"; args += crf.toString()
        args += "-pix_fmt"; args += "yuv420p"
        // Resolution: only scale when the user picked one. ORIGINAL
        // adds no filter at all, so the source resolution is kept.
        resolution.scaleFilter?.let { filter ->
            args += "-vf"; args += filter
        }
        // H.265 needs a tag that plays on Android. "hvc1" is the
        // Apple/QuickTime tag — an H.265 MP4 with hvc1 would NOT play
        // in the user's Android gallery (v0.1.17, 50% smaller file
        // that refused to play). "hev1" is the standard Android/MP4
        // tag for HEVC, so we use that. AV1 uses cpu-used 4 (faster,
        // still good quality).
        when (codec) {
            Codec.H265 -> { args += "-tag:v"; args += "hev1" }
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
            // No "-preset": libx264's default "medium" compresses better
            // than "fast" (see buildCommand — "fast" made an already
            // compressed video bigger). Best compression wins over speed.
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

    /**
     * Can THIS phone actually play back a video in the given codec?
     * Encoding is not the problem — FFmpeg can encode H.265 anywhere.
     * Playback is: if the phone has no HEVC decoder, an H.265 file
     * will not play in the gallery or any player that relies on the
     * system codecs. The user proved this: a fresh v0.1.19 compress
     * (hev1 tag) still would not play on their phone, so the tag was
     * never the real cause — the phone simply has no HEVC decoder.
     * Checked with MediaCodecList (system decoders only, not
     * encoders). H.264 ("video/avc") is present on every Android
     * phone, so it is the safe default. H.265 is "video/hevc",
     * AV1 is "video/av01".
     */
    fun isPlaybackSupported(mimeType: String): Boolean {
        return try {
            val codecList = android.media.MediaCodecList(
                android.media.MediaCodecList.REGULAR_CODECS
            )
            codecList.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.contains(mimeType)
            }
        } catch (_: Throwable) {
            // If the check itself fails, assume H.264 only (the one
            // codec that is guaranteed on every Android phone).
            mimeType == "video/avc"
        }
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

    /**
     * Best compression: try the user's settings first; if the result
     * is NOT smaller than the input, automatically escalate to
     * stronger settings until the output IS smaller. The user asked
     * for the best compression — so the app finds it instead of
     * handing back a bigger file or giving up. Attempts, in order:
     * (user CRF, user resolution) → (CRF 33) → (CRF 38) →
     * (CRF 33, 720p) → (CRF 38, 720p). Stops at the first attempt
     * that is actually smaller. On success, `onDone` receives
     * (true, note) where note is "" if the user's own settings won,
     * or " (used CRF 33, 720p…)" naming the stronger settings that
     * were needed. On failure, (false, reason).
     */
    fun compressBest(
        inputPath: String,
        outputPath: String,
        inputSize: Long,
        crf: Int,
        codec: Codec = Codec.H264,
        resolution: Resolution = Resolution.ORIGINAL,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        // Escalation ladder: each step compresses harder than the last.
        val attempts = mutableListOf<Pair<Int, Resolution>>()
        attempts += crf to resolution
        if (crf < 33) attempts += 33 to resolution
        if (crf < 38) attempts += 38 to resolution
        if (resolution == Resolution.ORIGINAL) {
            attempts += 33 to Resolution.P720
            attempts += 38 to Resolution.P720
        }
        // De-duplicate (e.g. user already picked CRF 33).
        val uniqueAttempts = attempts.distinct()
        runBestAttempt(inputPath, outputPath, inputSize, codec, uniqueAttempts, 0, onProgress, onDone)
    }

    private fun runBestAttempt(
        inputPath: String,
        outputPath: String,
        inputSize: Long,
        codec: Codec,
        attempts: List<Pair<Int, Resolution>>,
        index: Int,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        if (index >= attempts.size) {
            onDone(false, "This video is already as small as it gets — even the strongest settings couldn't shrink it")
            return
        }
        val (crf, resolution) = attempts[index]
        try {
            executeCommand(
                buildCommand(inputPath, outputPath, crf, codec, Presets.VIDEO_PRESET, true, resolution),
                onProgress
            ) { ok, message ->
                if (!ok) {
                    onDone(false, message)
                    return@executeCommand
                }
                // On success, executeCommand passes the output path back.
                val outFile = java.io.File(message)
                val outSize = if (outFile.exists()) outFile.length() else Long.MAX_VALUE
                if (outSize < inputSize) {
                    // This attempt actually made it smaller — keep it.
                    val note = if (index == 0) "" else " (used CRF $crf, ${resolution.label} to get it smaller)"
                    onDone(true, note)
                } else {
                    // Bigger (or equal) — delete it and try harder.
                    try { outFile.delete() } catch (_: Throwable) { }
                    runBestAttempt(inputPath, outputPath, inputSize, codec, attempts, index + 1, onProgress, onDone)
                }
            }
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    /**
     * WhatsApp Optimize: shrink a video until it fits under
     * [targetBytes] (WhatsApp's media limit is 16 MB — anything
     * bigger gets re-compressed by WhatsApp itself, losing quality).
     * Escalation ladder: CRF 28 → 33 → 38 at original resolution,
     * then 720p, then 480p. Stops at the first attempt that fits.
     * If no attempt fits, the SMALLEST attempt is kept anyway
     * (still smaller than the input) with a note that it is still
     * over the limit — send it as a document in WhatsApp instead.
     * Never hands back a file bigger than the input.
     */
    fun compressForWhatsApp(
        inputPath: String,
        outputPath: String,
        inputSize: Long,
        targetBytes: Long,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        val attempts = listOf(
            28 to Resolution.ORIGINAL,
            33 to Resolution.ORIGINAL,
            38 to Resolution.ORIGINAL,
            33 to Resolution.P720,
            38 to Resolution.P720,
            33 to Resolution.P480,
            38 to Resolution.P480
        )
        runWhatsAppAttempt(inputPath, outputPath, inputSize, targetBytes, attempts, 0, null, onProgress, onDone)
    }

    private fun runWhatsAppAttempt(
        inputPath: String,
        outputPath: String,
        inputSize: Long,
        targetBytes: Long,
        attempts: List<Pair<Int, Resolution>>,
        index: Int,
        bestFile: java.io.File?,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        if (index >= attempts.size) {
            val best = bestFile
            if (best != null && best.exists() && best.length() < inputSize) {
                val bestSize = best.length()
                try {
                    best.copyTo(java.io.File(outputPath), overwrite = true)
                    best.delete()
                } catch (_: Throwable) { }
                onDone(true, " (smallest possible is ${com.hakunaa.app.core.FileUtils.formatSize(bestSize)} — still over ${com.hakunaa.app.core.FileUtils.formatSize(targetBytes)}, send it as a document in WhatsApp)")
            } else {
                try { best?.delete() } catch (_: Throwable) { }
                onDone(false, "This video is already as small as it gets — even the strongest settings couldn't shrink it")
            }
            return
        }
        val (crf, resolution) = attempts[index]
        val attemptFile = java.io.File("$outputPath.try$index.mp4")
        try {
            executeCommand(
                buildCommand(inputPath, attemptFile.absolutePath, crf, Codec.H264, Presets.VIDEO_PRESET, true, resolution),
                onProgress
            ) { ok, message ->
                if (!ok) {
                    try { attemptFile.delete() } catch (_: Throwable) { }
                    try { bestFile?.delete() } catch (_: Throwable) { }
                    onDone(false, message)
                    return@executeCommand
                }
                val size = if (attemptFile.exists()) attemptFile.length() else Long.MAX_VALUE
                when {
                    size <= targetBytes && size < inputSize -> {
                        // Fits WhatsApp AND is actually smaller — keep it.
                        // (Fits-but-bigger would break the never-bigger law,
                        // so it falls through to try harder instead.)
                        try {
                            attemptFile.copyTo(java.io.File(outputPath), overwrite = true)
                            attemptFile.delete()
                            bestFile?.delete()
                        } catch (_: Throwable) { }
                        val note = if (index == 0) "" else " (used CRF $crf, ${resolution.label})"
                        onDone(true, note)
                    }
                    size < inputSize -> {
                        // Smaller but still over the limit — remember it, try harder.
                        try { bestFile?.delete() } catch (_: Throwable) { }
                        runWhatsAppAttempt(inputPath, outputPath, inputSize, targetBytes, attempts, index + 1, attemptFile, onProgress, onDone)
                    }
                    else -> {
                        // Not smaller — discard, try harder.
                        try { attemptFile.delete() } catch (_: Throwable) { }
                        runWhatsAppAttempt(inputPath, outputPath, inputSize, targetBytes, attempts, index + 1, bestFile, onProgress, onDone)
                    }
                }
            }
        } catch (t: Throwable) {
            try { attemptFile.delete() } catch (_: Throwable) { }
            try { bestFile?.delete() } catch (_: Throwable) { }
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

    fun rotate(
        inputPath: String,
        outputPath: String,
        mode: RotateMode,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        try {
            executeCommand(
                buildRotateCommand(inputPath, outputPath, mode),
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
