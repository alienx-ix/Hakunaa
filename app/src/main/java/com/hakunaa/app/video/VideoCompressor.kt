package com.hakunaa.app.video

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.hakunaa.app.core.Presets

/**
 * Video compression — FFmpegKit (native FFmpeg), same engine as the
 * analysed reference app. Keeps ORIGINAL resolution and framerate:
 * no -vf scale filter is ever added. That is the whole quality rule —
 * other apps blur because they drop to 720p / 800 kbps.
 *
 * Default: libx264, CRF 28, preset fast, AAC 128k.
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

    /**
     * Build the FFmpeg command as an argument ARRAY (argv), not a
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
        hasAudio: Boolean = true
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
            compressUnsafe(inputPath, outputPath, crf, codec, onProgress, onDone)
        } catch (t: Throwable) {
            onDone(false, t.message ?: "Video engine failed to start")
        }
    }

    private fun compressUnsafe(
        inputPath: String,
        outputPath: String,
        crf: Int,
        codec: Codec = Codec.H264,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        // Probe duration first, so the progress bar has a denominator,
        // and check whether the input has an audio stream (audio is
        // only encoded when present — forcing AAC on a silent video
        // makes FFmpeg fail).
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
        val session = com.arthenica.ffmpegkit.FFmpegSession.create(
            buildCommand(inputPath, outputPath, crf, codec, Presets.VIDEO_PRESET, hasAudio)
        ) { s ->
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
