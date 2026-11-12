package com.hakunaa.app.core

/**
 * One source of truth for compression presets.
 *
 * Video (from the analysed reference app):
 * 1080x1080, 24fps, H.264 High, ~1.5 Mbps, AAC 128k stereo,
 * 60 seconds = ~12 MB, encoder Lavf60.16.100 (FFmpeg).
 * That app keeps 1080p and 1.5 Mbps; other apps drop to
 * 720p / 800 kbps, ultrafast preset or MediaCodec, so they blur.
 *
 * CRF label — corrected: lower CRF = higher quality / bigger file.
 * Higher CRF = more compression / smaller file.
 * The reference app's label had this backwards.
 */
object Presets {
    data class VideoPreset(val id: String, val name: String, val crf: Int, val saving: String)

    val VIDEO_PRESETS = listOf(
        VideoPreset("high", "High Quality", 23, "20–40% smaller"),
        VideoPreset("balanced", "Balanced", 28, "40–60% smaller"),
        VideoPreset("small", "Small File", 33, "60–75% smaller"),
        VideoPreset("smallest", "Smallest", 38, "75–90% smaller"),
        VideoPreset("user-default", "User Default (CRF 24)", 24, "~40% smaller")
    )

    const val DEFAULT_CRF = 28
    const val VIDEO_PRESET = "fast"
    const val AUDIO_BITRATE = "128k"

    // PDF: 90% of a PDF's size is images.
    // Lossless: remove metadata, duplicate images, unused fonts — 20–30% smaller, quality 100% same.
    // Smart lossy: JPEG 80–85% + DPI 300 -> 150 — 60–70% smaller, eye cannot tell.
    const val PDF_JPEG_QUALITY = 82
    const val PDF_TARGET_DPI = 150

    // Image: JPEG 85% is visually lossless for photos; WebP is ~25–35% smaller than JPEG.
    const val IMAGE_JPEG_QUALITY = 85
}
