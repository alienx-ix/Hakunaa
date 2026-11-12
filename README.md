# Hakunaa

One Android app — PDF (All PDF Tools) + Image Compression + Video Compression.
100% on-device. Files never leave the phone. No account, no upload, no server.

## What this is

- **App name:** Hakunaa
- **Package:** `com.hakunaa.app` — do not change `applicationId` after a Play Store release.
- **Platform:** Android only, native Kotlin. No web version.
- **Repo:** `alienx-ix/Hakunaa` — public, created 2026-11-12, default branch `main`

## Quality bar

Must be better than every other app, not like them. Professional look —
it must not look AI-built. Clean Material 3, one restrained blue,
white surfaces, proper spacing, real progress and real before/after sizes.

Video rule, from the analysed reference app
(Video Converter, `com.audio.videoformat.formatchanger.videoconverter`):
keep ORIGINAL resolution and framerate, libx264 + CRF + `fast` preset,
AAC 128k. Reference output: 1080x1080, 24fps, H.264 High, ~1.5 Mbps,
60 seconds = ~12 MB. Other apps blur because they drop to 720p / 800 kbps,
ultrafast preset or MediaCodec.

CRF label — corrected: lower CRF = higher quality / bigger file.
Higher CRF = more compression / smaller file.
The reference app's label had this backwards.

Presets: High 23, Balanced 28, Small 33, Smallest 38, User Default 24.
Default: Balanced 28. Codec options: H.264 (default, fast, plays
everywhere), H.265 (about 50% smaller, slower) and AV1 (20–30% smaller
than H.265, very slow).

PDF: 90% of a PDF's size is images. Lossless (metadata / duplicate
removal) is 20–30% smaller at 100% quality. Smart lossy
(JPEG 80–85% + DPI 300 → 150) is 60–70% smaller, eye cannot tell.

Image: JPEG 85% is visually lossless for photos. WebP is
25–35% smaller than JPEG. AVIF is an option for later.

## Starter v0.1.0 — what is in this code

- Home screen with 3 cards: PDF Tools, Image Compress, Video Compress
- `video/` — VideoCompressor (FFmpegKit, H.264/H.265/AV1, CRF, progress,
  cancel) + VideoCompressActivity (pick, preset, codec, progress,
  before/after size, saves to Movies/Hakunaa)
- `pdf/` — PdfTools.merge, split (one file per page), compressLossless
  (metadata strip, 20–30% smaller, quality 100% same), compressSmart
  (images downscaled to 150 DPI, JPEG 82%, 60–70% smaller), imageToPdf
  (PDFBox-Android) + PdfToolsActivity (pick PDFs / image, merge, split,
  compress lossless, compress smart, image to PDF, saves to Documents/Hakunaa)
- `image/` — ImageCompressor (JPEG 85% / WebP, max 2048px) +
  ImageCompressActivity (pick, compress, before/after size,
  saves to Pictures/Hakunaa)
- `core/` — Presets (one source of truth) + FileUtils

## Status — honest

- **Compiled — 2026-11-12.** The full app builds to a signed debug APK
  here in this workspace (manual aapt2 + kotlinc + d8 pipeline, because
  Gradle's daemon is blocked by the runtime's Java TCP restriction).
  APK verified: `com.hakunaa.app` v0.1.0, 34 MB, signed (v2+v3),
  2 DEX files, FFmpeg native libs for arm64-v8a + armeabi-v7a, all
  4 activities present.
- **Android support:** lowest Android 7.0 (API 24 — FFmpegKit needs it),
  targets Android 16 (API 36, the newest stable; Android 17/18 are not
  released yet). AGP 8.6.1, Kotlin 1.9.24.
- **Not yet live-tested on a phone.** No control has been clicked live
  yet. Next step: install the APK on a real phone and live-check every
  control below, then compare a real video output against the reference.
- **Do not call this finished** until the live phone checks pass.

## Control inventory (live check pending)

- Home: PDF card, Image card, Video card — each must open its screen
- Video: Pick a video, preset radio (23 / 28 / 33 / 38 / 24),
  codec radio (H.264 / H.265 / AV1), Compress, progress bar, status,
  before/after result
- PDF: Pick PDFs, Merge PDFs, Split PDF, Compress PDF (lossless),
  Compress PDF (smart), Image to PDF, status, before/after size on Compress
- Image: Pick an image, Compress, status, before/after result

## Not built yet

- Video Convert (MP4/WebM/MOV), Video to MP3
- Play Store listing, ads, analytics — none, by design

## Run it

1. Open this folder in Android Studio (Hedgehog or newer)
2. Let Gradle sync. First sync downloads FFmpegKit (~30–40 MB)
3. Run on a real phone (emulator video encode is very slow)
4. Pick a video → Balanced CRF 28 → Compress → check
   Movies/Hakunaa for the output and compare quality + size
