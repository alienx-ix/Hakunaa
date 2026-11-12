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
        H265("libx265", "h265")
    }

    fun buildCommand(
        inputPath: String,
        outputPath: String,
        crf: Int,
        codec: Codec = Codec.H264,
        preset: String = Presets.VIDEO_PRESET
    ): String {
        val videoCodec = codec.ffmpegName
        // H.265 needs a pixel-format + tag that plays on most phones.
        val extra = if (codec == Codec.H265) " -pix_fmt yuv420p -tag:v hvc1" else " -pix_fmt yuv420p"
        return "-i \"$inputPath\" -c:v $videoCodec -crf $crf -preset $preset$extra " +
            "-c:a aac -b:a ${Presets.AUDIO_BITRATE} \"$outputPath\""
    }

    fun compress(
        inputPath: String,
        outputPath: String,
        crf: Int,
        codec: Codec = Codec.H264,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        // Probe duration first, so the progress bar has a denominator.
        var durationMs: Long = 0
        try {
            val info = com.arthenica.ffmpegkit.FFprobeKit.getMediaInformation(inputPath)
            durationMs = info.mediaInformation?.duration?.toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
        } catch (_: Exception) {
            durationMs = 0
        }
        FFmpegKitConfig.enableStatisticsCallback { statistics: Statistics ->
            val time = statistics.time
            if (durationMs > 0 && time > 0) {
                val percent = ((time.toDouble() / durationMs.toDouble()) * 100.0)
                    .toInt().coerceIn(0, 100)
                onProgress(percent)
            }
        }
        val session = FFmpegKit.executeAsync(buildCommand(inputPath, outputPath, crf, codec)) { s ->
            FFmpegKitConfig.disableStatisticsCallback()
            val ok = ReturnCode.isSuccess(s.returnCode)
            onDone(ok, if (ok) outputPath else (s.failStackTrace ?: "FFmpeg failed"))
        }
        // Keep the session reference alive for cancellation in the Activity.
        currentSessionId = session.sessionId
    }

    var currentSessionId: Long = 0L
        private set

    fun cancel() {
        if (currentSessionId != 0L) FFmpegKit.cancel(currentSessionId)
    }
}
