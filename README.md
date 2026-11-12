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
  APK verified: `com.hakunaa.app` v0.1.0, signed (v2+v3),
  2 DEX files, all 4 activities present.
- **Crash report 2026-11-12:** the first APK installed but crashed on the
  user's Android 15 phone. Fixes in this build:
  - **16 KB page size:** video engine switched from retired upstream
    FFmpegKit 6.0-2 (4 KB-aligned) to the maintained fork
    `dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9` — verified
    16 KB-aligned (PT_LOAD `0x4000`) for arm64-v8a and x86_64. This is
    the Google Play requirement for apps targeting API 35+ (Nov 2025).
    Same `com.arthenica.ffmpegkit` API, drop-in. Ships 64-bit ABIs only
    (arm64-v8a + x86_64); PDF and Image tools are pure Kotlin/Java and
    still work on every phone.
  - **Packaging:** native `.so` files stored uncompressed (was DEFLATED),
    `extractNativeLibs="false"`, library manifests merged (the
    `androidx.startup` provider was missing before).
  - **Crash-proofing:** video engine load is checked up front and every
    tool shows a message instead of crashing; a global safety net
    reopens the home screen once if anything unexpected ever escapes.
  - **Scoped storage:** all outputs now go through MediaStore
    (Movies/Pictures/Documents/Hakunaa). Direct file writes to public
    folders fail on Android 10+; temp files live in the app cache.
  - **Temp logo:** blue rounded-square "H" adaptive icon (all densities)
    + 512 px Play icon.
- **Android support:** lowest Android 7.0 (API 24), targets Android 16
  (API 36, the newest stable; Android 17/18 are not released yet).
  AGP 8.6.1, Kotlin 1.9.24.
- **Crash log (v0.1.1, 2026-11-12):** the app still crashed on the user's
  phone and the first crash report never appeared. Fixes: the report is
  now plain text (`text/plain` — the earlier build saved a `.txt` file
  as `application/pdf` and MediaStore silently dropped it), it goes to
  Download/Hakunaa (most visible in the Files app) with a Documents
  fallback, and a new `CrashInitProvider` installs the crash handler
  before any other startup component runs. Version bumped to 0.1.1
  (versionCode 2) so the new APK is unmistakable.
- **Freeze (v0.1.2, 2026-11-12):** even a plain Activity with no
  AppCompat/Material and no layout inflation froze on a black screen.
  Proves the freeze is NOT in the UI code — it is the build. The
  unshrunk DEX was ~16 MB (every class from every library), so
  first-launch DEX verification takes far too long and the app sits
  on a black screen. User instructed: use Android Studio. Android
  Studio Panda 4 Patch 1 was installed (~/android-studio) with Gradle
  8.7, but Gradle cannot run on this machine — the runtime blocks
  Java TCP, which Gradle's client-daemon communication needs
  ("Could not receive a message from the daemon"), even with
  --no-daemon. So the best WORKING build is the manual pipeline plus
  R8 shrinking (the same shrinker Android Studio uses). v0.1.3:
  R8 tree-shakes the DEX from 16 MB to ~9 MB (Material views kept —
  R8 first removed MaterialCardView because it is only referenced
  from layout XML, which would have crashed the home screen).
- **v0.1.4 (2026-11-12):** no custom Application, no CrashInitProvider —
  still crashed ("Crash and white screen"). So the crash is NOT in
  the startup code either. Key realisation: the earlier "black-screen
  freeze" was the crash handler restarting the home screen in a loop
  after a startup crash — the real problem all along is a crash at
  launch, hidden by the restart. v0.1.5 shows the exact crash on
  screen instead: the handler opens a plain CrashReportActivity
  (yellow, no Material) with the full error, so a screenshot gives
  the exact exception to fix.
- **v0.1.12 (2026-11-12):** fixes video properly. The "Unrecognized
  option 'preset'" came from FFmpegKit's String-command parser
  mangling the command — and `executeAsync` has no array overload
  (verified with javap). The app now builds the command as an argv
  array and runs it via `FFmpegSession.create(argv, callback)` +
  `asyncFFmpegExecute(session)`, so no string parsing can break the
  options. Same standard command, audio probed and only encoded
  when present.
- **v0.1.13 (2026-11-12):** the user still got the identical
  "Unrecognized option 'preset'" error on v0.1.12. Root cause is the
  `-preset` option itself: FFmpegKit's bundled libx264 build does not
  accept it through this path, in any invocation style (String command,
  `-map`, argv array — all failed on the same option). Fix: `-preset`
  is REMOVED from the command entirely; libx264 falls back to its
  default preset "medium" (slightly slower, same CRF quality). Also
  adds a version label on the home screen ("Hakunaa v0.1.13") so
  future screenshots prove which build is installed — version
  confusion (old build vs new build) is now impossible. Superseded
  before delivery by v0.1.14 below.
- **v0.1.14 (2026-11-12):** PDF Lossless Compress made a file BIGGER
  (user screenshot: 3.7 MB → 3.8 MB, "-3% smaller"). Re-saving an
  already-optimized PDF with PDFBox's default writer adds overhead
  (uncompressed xref), so the output can exceed the input.
  `compressLossless` now returns the bytes saved and, when the
  re-save would be bigger (or equal), it deletes the output and
  reports "Already optimized — this PDF is as small as it gets
  without losing quality. Try Smart Compress for a smaller file."
  A "compress" will never hand the user a larger file again.
  Includes the v0.1.13 video fix and version label.
- **v0.1.15 (2026-11-12):** the REAL video root cause. The user tested
  v0.1.14 and got a new, specific error: "Unknown encoder 'libx264'".
  Inspection of the engine proved the non-GPL `ffmpeg-kit-full`
  package contains NO libx264 and NO libx265 (GPL encoders) — only
  libaom-av1 was present. So H.264/H.265 could never work with it.
  Fix: switched to `ffmpeg-kit-full-gpl:8.1.9` (maintained fork),
  verified to contain libx264, libx265 and libaom-av1, all 16 KB
  aligned, arm64-v8a + x86_64. APK grew 51 MB → 56 MB (more
  encoders). GPL-3.0 applies; the repo is public, so the source
  requirement is already met. Also updated resolve.py + artifacts.txt
  (re-running resolve.py is required after changing the dependency —
  build.py reads artifacts.txt, and a stale one makes the build look
  for the deleted old AAR). The user asked for a full rework with
  full time: video, PDF (v0.1.14 fix) and Image (pure Android Bitmap,
  no FFmpeg) were all reviewed; video was the only broken engine.
- **v0.1.16 (2026-11-12):** `-preset` is BACK. It was removed in
  v0.1.13 because it failed with "Unrecognized option 'preset'" —
  but that was a symptom: the old non-GPL engine had NO libx264, so
  no encoder existed to accept the option. Now the GPL engine
  (v0.1.15) has libx264, so `-preset fast` works — proven by the
  reference app (below), which uses -crf + -preset + libx264 with
  the same GPL FFmpegKit. "fast" matches the reference app's
  Compress mode and is faster than libx264's default "medium" at
  the same CRF quality.
- **Reference app analysis (2026-11-12):** the user asked to reverse
  engineer the old Video Converter app they showed (`com.audio.
  videoformat.formatchanger.videoconverter` v1.2.15, 34.6 MB APK).
  Findings: it also uses FFmpegKit (old upstream 6.0, FFmpeg 6.x,
  libavcodec 60.3.100) with libx264 + libx265 present (GPL build);
  its DEX contains `-crf`, `-preset`, `libx264`, `-b:a` and the
  presets fast/medium/slow/veryfast/veryslow plus H.264 profiles
  baseline/main and an ORIGINAL resolution option. Its code is
  heavily obfuscated (single-letter class/method names), so the
  exact command order was not extracted — but the sample output
  already proved its Compress mode: CRF 24, preset FAST, 1080p,
  ~1.5 Mbps, H.264 High, AAC 128k. Hakunaa now matches that
  engine (GPL, libx264) and those settings (CRF 24 default, FAST
  preset, AAC 128k). Note: this analysis was for learning the
  technique, not copying code.
- **v0.1.17 (2026-11-12):** brings the reference app's missing
  features into Hakunaa (user: "bring the same things into our
  app"). The Video screen now has a mode selector: Compress
  (CRF/codec, as before), Convert (MP4/WebM/MOV/MKV — WebM uses
  VP9 + Opus, the rest H.264 + AAC), and Video to MP3 (libmp3lame
  192k, saved to Music/Hakunaa). Resolution selection added to
  Compress and Convert: Original (no scale filter, best quality),
  1080p, 720p. All encoders verified present in the APK:
  libx264, libx265, libaom-av1, libvpx-vp9, libopus, libmp3lame.
  MediaStoreSaver gained an AUDIO kind (Music/Hakunaa, audio/mpeg)
  and an optional MIME override so WebM/MOV/MKV are saved with
  their real MIME type. REA (morluto/rea) was installed (v6.7.0)
  and jadx/apktool were used for the reference-app analysis; REA's
  binary providers (Ghidra/Hopper/IDA) do not analyse APK archives
  directly, so jadx + apktool + strings + dexdump did the work.
- **v0.1.18 (2026-11-12): Best compression.** The user tested
  v0.1.17 on the same 11.7 MB video and got 12.0 MB — BIGGER.
  Cause: `-preset fast` (restored in v0.1.16 to match the reference
  app) trades compression efficiency for speed, and on an already
  compressed video it made the file bigger. Fix: `-preset` is
  REMOVED from Compress and Convert, so libx264 uses its default
  "medium" preset, which compresses better (smaller files) at the
  same CRF. And Compress now uses `compressBest`: it tries the
  user's settings first, and if the result is NOT smaller than the
  input it automatically escalates — CRF 33, CRF 38, then 720p at
  CRF 33/38 — until the output IS smaller, and reports which
  stronger settings were used. A Compress never hands back a
  bigger file. (The user rejected a "don't save, try something
  else" message — they want the app to find the best result
  itself.)
- **v0.1.19 (2026-11-12): H.265 plays on Android.** The user
  compressed with H.265 + 720p and got a great result (11.7 MB →
  5.9 MB, 50% smaller) — but the video would NOT play. Cause:
  the H.265 command used `-tag:v hvc1`, the Apple/QuickTime tag.
  Android players expect `hev1` for HEVC in MP4. Fix: H.265 now
  uses `-tag:v hev1`, the standard Android tag. (v0.1.18 was built
  and pushed but never delivered as an APK; v0.1.19 includes the
  v0.1.18 best-compression fix plus this playback fix.)
- **v0.1.20 (2026-11-12): only offer codecs the phone can play.**
  The user did a FRESH v0.1.19 compress and the H.265 video
  STILL would not play — so the `hev1` tag was never the real
  cause. The phone simply has no HEVC decoder. Encoding H.265
  is easy; playing it back is the problem. Fix: the app now
  checks the phone's system decoders (MediaCodecList) and does
  NOT offer a codec the phone cannot play back — H.265 (and
  AV1, if unsupported) are disabled with "not supported on this
  phone", and the selection falls back to H.264, which plays on
  every Android phone. A video that plays is worth more than a
  smaller file that never plays. (User: "Android la best a
  Perform pananum" — this is what best on Android means.)
  USER CONFIRMED: Video Compress and Convert work, "idhu video
  ku fixed". Video screen has too many options (confusing) —
  simplify at the END (UI phase).
- **v0.1.21 (2026-11-12): PDF tools from iLovePDF analysis.**
  iLovePDF 4.0.2 (225 MB, com.ilovepdf.www) analysed with
  apktool/jadx/strings + REA/Ghidra: its PDF engine is PSPDFKit
  (commercial, licensed) and its scanner is Genius Scan SDK
  (commercial, licensed); many convert/OCR tools are cloud-based.
  Hakunaa cannot copy those SDKs — it implements the same
  features offline with PDFBox. New PDF tools in this build:
  Rotate (90/180/270), Watermark (diagonal text), Page Numbers
  ("Page X of Y"), Delete Pages, Extract Pages (both take a
  page selection like "1,3,5-7"), and Extract Images (to
  Pictures/Hakunaa). Existing: Merge, Split, Lossless + Smart
  Compress, Image to PDF. File size: user says 56 MB is too
  much — optimize at the END (final version) by switching
  FFmpegKit full-gpl to video-gpl (target ~40–45 MB); for now,
  best functions only.
- **v0.1.22 (2026-11-12): Best PDF Smart Compress.** The user
  tested Smart Compress and got 3.8 MB → 4.2 MB (-10%) even
  though 8 images were "compressed" — the originals were already
  low-quality JPEGs, so re-encoding at JPEG 82% grew them. Fix:
  `compressSmartBest` tries 150 DPI / JPEG 82% first, and if the
  result is not smaller it automatically escalates (100 DPI /
  JPEG 70%, then 72 DPI / JPEG 60%) until the output IS smaller.
  A Smart Compress never hands back a bigger file. (v0.1.22 was
  built and verified but superseded by v0.1.23 before delivery,
  which adds the same guarantee to Image Compress.)
- **v0.1.23 (2026-11-12): Best compression everywhere — never
  bigger, in ANY situation.** The user requires it: no compress
  may ever return a file bigger than the original. Image Compress
  now uses `compressBest`: JPEG 85% first, then WebP 85%
  (25–35% smaller than JPEG), JPEG 70%, WebP 70%, JPEG 60% —
  until the output IS smaller, and reports which stronger
  setting was used. If even the strongest settings cannot shrink
  it, the app says "Already optimized" and saves nothing. Same
  guarantee already in place for Video (`compressBest`, v0.1.18),
  PDF Smart (`compressSmartBest`, v0.1.22) and PDF Lossless
  (size check, v0.1.14).
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
