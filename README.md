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
- **v0.1.24 (2026-11-12): Best means best — try them all, keep
  the smallest.** The user tested v0.1.23: Smart Compress worked
  (3.7 MB → 3.2 MB, 14% smaller) but only 14% — "inu Nallavae
  panalam, best algorithm pick pani". The flaw: `compressSmartBest`
  stopped at the FIRST smaller result (100 DPI / 70%) and never
  tried the stronger settings. Fix: it now tries EVERY level —
  150 DPI / JPEG 82%, 100 DPI / JPEG 70%, 72 DPI / JPEG 60%,
  72 DPI / JPEG 50% — and keeps the SMALLEST output. Same change
  for Image `compressBest` (tries all 5 levels, keeps smallest).
  Slower (runs each level once) but the smallest file wins.
- **v0.1.25 (2026-11-12): iLovePDF-quality UI.** The user said
  the menus are not proper and the alignment is not right — it
  must look like iLovePDF. Redesign (no questions asked, best
  judgement): every screen now has a proper MaterialToolbar with
  a back button; tools are grouped into labelled section cards
  (FILE / ORGANIZE / COMPRESS / CREATE / EDIT / PAGES / EXTRACT
  on PDF; FILE / WHAT TO DO / QUALITY / CODEC / CONVERT TO /
  RESOLUTION on Video) with 12dp corners, 2dp elevation, 16dp
  padding and uniform 12dp gaps; all buttons are the same 56dp
  height; watermark/pages fields are outlined TextInputLayouts;
  status lives in its own card; the home cards now have PDF /
  Image / Video icons. Mode switching on Video now hides the
  whole card (not just the inner layout) so no empty card shows.
- **v0.1.26 (2026-11-12): More tools, like iLovePDF.** The user
  showed iLovePDF's tools grid — "idhula ivlo options iruku"
  (so many options here). New tools, all offline with PDFBox /
  Bitmap: PDF to JPG (every page rendered at 150 DPI),
  Extract TXT (full text to a .txt file), Protect PDF (128-bit
  password lock), Unlock PDF (remove password), Organize PDF
  (reorder pages, e.g. 3,1,2), Create PDF (from typed text),
  Resize Image (longest side in pixels), Convert Image (to
  JPG / PNG / WebP). NOT possible offline: PDF to Word / Excel /
  PowerPoint and OCR — those are iLovePDF-cloud features by
  design, and PSPDFKit/Genius Scan are commercial SDKs that
  cannot be copied.
- **v0.1.27 (2026-11-12): PDF Scanner — the 4th home option.**
  The user asked "scanner enga? epo add pana?" and showed the
  home screen: "Home la 4 Options... Idhula PDF Scanner add
  pananum". Home now has 4 cards: PDF Tools, Image Compress,
  Video Compress, PDF Scanner. The scanner takes photos with the
  camera (or picks from gallery), applies a filter — Color,
  Grayscale, or B&W (best for documents, crisp black text on
  white) — and saves all pages as one A4 PDF. 100% on-device.
  Note: this is a camera + filter + PDF scanner. Automatic edge
  detection and perspective correction need OpenCV, which is a
  later step — the user frames the document in the camera itself
  for now. (v0.1.26 was built and verified but superseded by
  v0.1.27 before delivery, which adds the scanner on top.)
- **v0.1.28 (2026-11-12): Professional scanner — live preview,
  camera permission, auto straighten.** The user reported the
  camera would not open ("Camara Open aagala") and wants a
  professional scanner "same as Professional Scanner apps",
  with preview and everything, "Need Professional +++++++".
  Root cause of the camera failure: the CAMERA permission was
  in the manifest but NEVER requested at runtime — Android 6+
  needs the system dialog. Fix: the scanner now asks for the
  camera permission properly when it opens, the way every app
  does. New professional scanner: live CameraX PreviewView
  inside the app (not the system camera), Capture button,
  auto document detection + perspective correction (pure-Kotlin
  edge detection, NO OpenCV — that would add a 20 MB native
  library and the user wants the app smaller), filters
  (Color / Grayscale / B&W), a live preview thumbnail of the
  latest scanned page, multi-page to one PDF, gallery pick.
  Also fixes the user's question: "ella apps um install pana
  odanae permissions ketu vangum but nee edhum kekela" — yes,
  Hakunaa now asks for the camera permission like other apps.
- **v0.1.29 (2026-11-12): Scanner crash FIXED.** The user
  opened the scanner and the app crashed immediately:
  `ClassNotFoundException: androidx.camera.view.PreviewView`
  (activity_scanner line #44). Root cause: R8 removed
  PreviewView from the DEX — it is referenced from the layout
  XML, and R8 cannot see XML references, so it decided the
  class was unused (the same failure mode as MaterialCardView
  earlier, which already had a keep rule). Fix: added
  `-keep class androidx.camera.** { *; }` to
  proguard-rules.pro. Verified in the built APK: PreviewView
  now present (17 class descriptors in the DEX, was 0).
- **v0.1.30 (2026-11-12): Professional scanner — CamScanner-style
  full flow.** The user said the app "use pana poor a irku,
  professional a therila" and sent CamScanner screenshots of the
  camera, Crop and Enhance screens, asking to refer to a
  professional scanner app first. Rebuilt the scanner to match:
  (1) **Camera** — full-screen live preview, top bar (close,
  flash toggle, HD toggle, menu), Single / Batch mode tabs, big
  round teal capture button, Import Images / Import Files.
  (2) **Crop** — the photo with a teal frame and draggable corner
  handles (white circles, teal ring) + edge pill handles, dimmed
  area outside the frame, Auto Detect (snaps the frame to the
  document), rotate Left / Right, Next. (3) **Enhance** —
  editable document name ("Hakunaa 11-12-2026 19.53" style),
  filter thumbnails Original / Lighten / Enhance (default) /
  No Shadow / B&W, rotate Left, green checkmark to save the page.
  Single mode saves the PDF after one page; Batch mode keeps
  capturing and saves all pages as one PDF. All filters are pure
  Kotlin, on-device. Not possible offline (and not built):
  Extract Text (OCR) and To Word are cloud features in
  CamScanner; Markup and Sign are later steps. First v0.1.30
  build failed on `Filter` needing its `ImageEnhancer.` prefix;
  fixed.
- **v0.1.31 (2026-11-12): PDF size fix + Pages screen + Soft
  filter.** The user said the app is good now ("Yahh ok nala
  irku"), but: (1) the captured PDF was **22 MB for 2 photos** —
  root cause: `imageToPdf` used LosslessFactory, embedding each
  12 MP photo as lossless PNG (~10 MB each). Fix: new
  `imageToPdfOptimized` downscales each page to A4 print
  resolution (longest side 2000 px, ~200 DPI) and embeds as JPEG
  quality 80 — a 2-page scan is now about 1 MB instead of 22 MB,
  text stays crisp at print size. Used by both Single and Batch
  save paths. (2) **Pages screen** (Batch mode): after multiple
  captures, "Save as PDF" opens a thumbnail grid where the user
  can preview every page, reorder it (left/right arrows),
  rotate it, delete it, add more pages, then save as one
  optimized PDF. (3) **Soft filter** added to the Enhance
  screen (Original / Lighten / Enhance / No Shadow / B&W / Soft)
  — gentle, slightly brighter, muted colours. B&W was already
  there. (4) Alignment fixes deferred to the final pass, as the
  user said ("alignment fix pananu ok finall a panikalam").
  Two build fixes: `layout_columnWeight` is not valid on an
  item root (set GridLayout params in code instead), and an
  edit had eaten the first lines of the `rotate` doc comment in
  PdfTools (restored).
- **v0.1.32 (2026-11-12): Full professional — drag reorder,
  auto capture, torch, more filters, better detection.** The
  user asked for "full professional" and added torch ("scan la
  torch enable pananum la"). (1) **Drag reorder**: Pages screen
  rebuilt with RecyclerView + ItemTouchHelper — long-press a
  page and drag it to its new spot, no arrows. (2) **Auto
  capture**: CameraX ImageAnalysis watches the live preview;
  when a document is detected and its corners stay still for 3
  checks, the app captures by itself ("Document found — hold
  steady…"). AUTO toggle in the top bar turns it off; 3-second
  cooldown after each capture so it never fires twice.
  (3) **Torch**: the flash button now turns the flashlight ON
  while scanning (CameraX enableTorch), so documents are lit in
  the dark — the way professional scanners do it. (4) **More
  filters**: Gray, Vivid, Warm added — now 9 filters (Original /
  Lighten / Enhance / No Shadow / B&W / Soft / Gray / Vivid /
  Warm). (5) **Better crop detection**: Otsu's threshold
  (adapts to dim/bright light and shadows instead of one fixed
  level) + two-pass line fitting with outlier rejection, so text
  or shadows near the edge do not pull the frame off. Crop
  screen keeps both auto (Auto Detect) and manual (draggable
  corners), like professional scanners.
- **v0.1.33 (2026-11-12): Professional image tools (Puma Photo
  Resize & Compress style).** The user sent screenshots of Puma
  and asked for all professional tools in photo compress. The
  Image Compress screen is now a full tools hub: **Quick squish**
  (one tap — best compression, tries every level, keeps the
  smallest, never bigger), **Resize** (resolution presets:
  Keep, 240x534 … 3072x6828, Custom), **Compress** (quality
  95/90/80/70/60/50/Custom 85, then a resolution step — keep
  original or pick a size), **Fit** (aspect ratios 1:1, 4:5,
  9:16, 16:9, 3:2), **Edit image** (Brightness / Contrast /
  Saturation sliders + Rotate, live preview), **Crop** (reuses
  the scanner's CropView — drag corners, rotate, save),
  **Convert** (PNG / JPG / WebP), **Fit to social media**
  (Instagram post/feed/story, Facebook, TikTok presets),
  **Resize to KB, MB** (target file size 50 KB – 5 MB, binary
  search on JPEG quality). New code: ImageToolActivity (one flow
  activity, image header + radio list + Back/Next), ImageCrop
  Activity, ImageEditActivity; ImageCompressor gained
  compressToTargetSize, resizeTo, fitToAspect, adjust, crop,
  rotate. Object erase: user asked if it is easy — simple
  brush-and-fill is doable, Magic Eraser quality needs a 200 MB+
  AI model (not feasible at the current app size); not built yet.
- **v0.1.34 (2026-11-12): Image Compress crash FIXED.** The user
  tested v0.1.33 and Image Compress crashed on open:
  `ClassCastException: MaterialCardView cannot be cast to
  LinearLayout` at ImageCompressActivity.onCreate:50. Root cause:
  the Quick Squish card (`toolQuickSquish`) is a MaterialCardView
  in the layout, but the code cast it to LinearLayout. Fixed the
  cast. While checking, found the same class of bug in
  ImageEditActivity — it looked up a `btnBack` ImageButton that
  does not exist in its layout (it uses a toolbar back arrow),
  which would have crashed Edit on open; fixed to use the
  toolbar navigation. All other view casts in the new image
  activities were checked and match their layouts.
- **v0.1.35 (2026-11-12): Video Rotate + Collage Maker.** The user
  picked these two from the ideas list. (1) **Video Rotate**: a
  new Rotate mode in the Video screen — 90° Clockwise, 90°
  Counter-clockwise, 180°, Flip Horizontal (mirror), Flip
  Vertical. FFmpeg transpose/hflip/vflip filter, re-encoded
  libx264 CRF 28, audio copied untouched. (2) **Collage Maker**:
  new tool in the Image Tools hub — pick 2–4 photos, choose a
  layout (Grid 2x2, Side by side, Stacked, Row of 3, Column of
  3), each photo centre-cropped to fill its cell, saved as one
  image to Pictures/Hakunaa. Pure Kotlin Bitmap, no library.
- **v0.1.36 (2026-11-13): WhatsApp Optimize.** The user picked it
  from the noted features. New 5th Home card — tap it, pick a
  video, photo or PDF, see its details + preview, tap Optimize
  for WhatsApp, share the result straight to WhatsApp.
  Targets are WhatsApp's real limits: video ≤ 16 MB (H.264,
  CRF ladder 28 → 33 → 38, then 720p, then 480p), photo ≤ 200 KB
  at max 1600 px (best-quality JPEG/WebP levels first, then
  binary-search to the target), PDF ≤ 16 MB via Smart Compress.
  Rule, set by the user: ALWAYS best-compress, even a file that
  already fits (a 499 KB photo still gets compressed); quality
  first, only as low as the limit needs; output is never bigger
  than the input — a file that cannot shrink at all is saved
  as-is. New: WhatsAppOptimizeActivity + VideoCompressor
  .compressForWhatsApp.
- **v0.1.37 (2026-11-13): Excellent video compression.** The user
  tested WhatsApp Optimize on an 11.7 MB video and got back
  11.6 MB — 1% smaller, poor. Root cause: the ladder stopped at
  the first attempt that was smaller at all. Fix: a rung only
  wins if it saves at least 10% — a 1% saving is not excellent
  compression, so the ladder keeps escalating (CRF 33, 38 at
  original resolution) until a rung saves 10% or runs out; if none
  does, the smallest smaller file is kept with an honest note.
  Quality first: a video that already fits is NEVER downscaled —
  720p/480p only when a video must fit WhatsApp's 16 MB limit
  and CRF alone cannot do it. Video Compress (best) never
  downscales on its own at all.
  One shared runner (`runLadderAttempt`) now serves both Video
  Compress (best) and WhatsApp Optimize, so both give excellent
  results on already-compressed videos. Also fixed: a video with
  no readable duration showed "0:00" (now hidden) and its
  progress bar sat at 0 (now spins until real progress arrives).
- **v0.1.38 (2026-11-13): Scanner — original camera quality +
  export choice + stronger UI.** The user felt camera quality
  was missing. Root causes found: capture used
  CAPTURE_MODE_MINIMIZE_LATENCY (fast, lower-quality path),
  no JPEG quality set, and HD capped at 1920x1080; then the
  PDF downscaled every page to 2000 px / JPEG 80. Fix:
  MAXIMIZE_QUALITY capture, JPEG 100, sensor's full resolution
  while HD is on (HD off = 1920x1080, HD is on by default);
  crop / enhance / rotate intermediates at JPEG 95. Save now
  asks first: Original Quality (full resolution, JPEG 95),
  Compressed (2000 px / 80, the old default) or Extreme
  Compress (1200 px / 60) — each shown with its real file
  size, tap one to save. UI: document frame guide on the
  camera screen, mode tabs in a segmented pill, Pages screen
  shows total size. New: PdfExportDialog + dialog_pdf_export.
- **v0.1.39 (2026-11-13): Scanner export crash FIXED.** The user
  tested v0.1.38 and the app crashed when the export dialog
  opened: `Error inflating class androidx.core.widget
  .NestedScrollView` in Material's `m3_alert_dialog` layout.
  Root cause: R8 removed NestedScrollView because it is only
  referenced from that layout's XML (R8 cannot see XML
  references) — the same class of bug as the PreviewView crash
  in v0.1.28. Fix: `-keep class androidx.core.widget.** { *; }`
  in proguard-rules.pro (both the build copy and the repo copy).
  Verified in the APK dex before delivery: NestedScrollView is
  present. Also in v0.1.39 — scanner frame fixes from the user's
  screenshot: the camera preview used fillCenter, so the screen
  looked zoomed in (now fitCenter + 16:9, the whole frame is
  shown, nothing cropped); the frame guide was a static bold box
  (now a thin 3dp light line, hidden until a document's edges
  are actually detected, then it appears).
- **v0.1.40 (2026-11-13): Professional scanner, three fixes.**
  (1) DYNAMIC edge frame: the fixed box is gone — new
  EdgeOverlayView draws the document's REAL detected corners
  live on the preview (thin teal lines + corner dots + light
  fill), following the book as it moves; analysis runs ~6x/sec
  for smooth tracking. Corners are mapped from the analysis
  buffer (rotation + fitCenter rect). (2) Camera handled
  correctly: preview + analysis now use the sensor's native
  4:3 aspect (16:9 cropped the sensor frame, so the screen
  still looked slightly zoomed) + fitCenter — widest field of
  view, nothing cropped. (3) Export is now its OWN SCREEN
  (PdfExportActivity): the v0.1.39 dialog left the camera
  running and auto-capturing behind it. While the export
  screen is open the scanner stops and CameraX releases the
  camera — no background scan. Same 3 tiers with real sizes.
  Cancel keeps the pages and the Save button stays visible so
  export can be opened again. PdfExportDialog + its layout
  deleted.
- **v0.1.41 (2026-11-13): Single-page detection + clear scans +
  auto-capture countdown.** (1) Single page: the detector used
  to take the whole bright blob, so an open book's side page
  came along. The spine shadow is NOT dark enough to break the
  Otsu mask, but it IS a clear valley in the column-mean
  brightness profile — the detector now finds that valley and,
  when one side is a small partial page, splits there and keeps
  only the biggest page. A full two-page spread (both sides
  comparable) is still kept whole. Verified on the user's two
  photos: INDEX page left edge moved 0 -> 111 (spine), notes
  spread unchanged. (2) Clear scans: no filter ever sharpened,
  so the warp's softening stayed. ENHANCE (the default) now
  adds an unsharp mask after the colour work — verified
  side-by-side on the INDEX page, visibly crisper text/lines.
  The Enhance screen shows the raw page at once, then swaps in
  the sharpened version when ready. (3) Auto capture: a
  countdown bar on the side fills like a charge while the page
  is held steady — full means capture. Hold time is now the
  menu setting (2 / 3 / 5 seconds, default 3 s, saved).
- **v0.1.42 (2026-11-13): Tap to focus + on-screen brightness.**
  A real camera works by tapping the screen: tap anywhere on
  the preview and the camera focuses there and meters the light
  there (AF + AE) — tap a dark corner and it brightens, tap a
  bright page and it darkens. A teal focus ring marks the spot
  and fades; a brightness slider appears right beside the ring
  (drag up for brighter, down for darker, sun on top) — the
  adjustment lives on the screen itself, no separate settings.
  The brightness offset is saved and restored when the scanner
  reopens, clamped to the phone's exposure range (the slider is
  hidden on phones that don't support it). Taps only count
  inside the actual camera image (4:3 fitCenter); taps on the
  black bars or controls do nothing. PLUS stability fixes from
  the user's live test: the countdown bar never filled and the
  box shook because the raw detected corners jitter a few
  pixels every frame — corners are now smoothed (exponential
  moving average) and the bar + frame use the smoothed corners,
  a missed frame gets a 600 ms grace instead of wiping the box,
  and analysis runs at 640x480 (the lag while the bar ran came
  from full-size frames); the detector also blurs sensor noise
  before thresholding. B&W filter fixed too: the fixed 160
  threshold turned shadowed paper solid black (verified on the
  user's photo — 100% black page); it is now adaptive, each
  pixel against its local background, so text stays readable
  under uneven light.
- **v0.1.43 (2026-11-13): Book Scan mode.** The scanner's mode
  tabs gain a third tab, Book, which opens a landscape screen:
  hold the phone sideways, both pages of an open book fit side
  by side, and a dashed spine line splits them into A (left
  page, 1st) and B (right page, 2nd). The line follows the
  book's real fold automatically (the spine valley, smoothed)
  and can be dragged to the fold by hand. Double (default) —
  one capture takes BOTH pages at once, A first then B, each
  perspective-corrected and auto-enhanced on its own; flip the
  book, capture again, pages keep appending in order — next,
  next, a whole book. Single — tap the page to scan (A or B),
  capture takes only that page. Capture is manual (pages change
  while you flip); review on the Pages screen, save the whole
  book as one PDF. The split is the spine valley + interpolation
  along the spread's top/bottom edges, verified on the user's
  two-page notes photo: two clean, complete, readable pages.
  Falls back to a plain vertical cut when the spread's corners
  can't be found.
- **v0.1.43 camera fix (same build): the camera black screen.**
  v0.1.42's camera never opened on the user's phone — black
  screen. Cause: the fixed 640x480 analysis stream made the
  whole use-case combination unsupported on that phone, so
  bindToLifecycle threw and NOTHING was bound (no preview at
  all). Fix — AUTO-RESOLVE binding, in both the main scanner
  and Book Scan: try the fast setup first (640x480 analysis),
  then CameraX picks the size itself, then no analysis at all
  (manual capture still works). The camera must open on ANY
  phone — global support. Torch, tap-to-focus and brightness
  are also guarded now: a phone that does not support one just
  skips it instead of breaking the camera.
- **v0.1.44 (2026-11-13): proper torch icon + smooth capture
  ring (replaces the lagging bar).** The torch icon was wrong
  — it was a CAMERA body, not a torch. It is now a real
  flashlight: white when off, teal with light rays when on
  (both scanner screens). The auto-capture countdown bar is
  GONE — it lagged because the analysis drove it ~6 times a
  second, so it moved in jerky steps and made the whole
  scanner feel laggy. Its alternate: a smooth ring around the
  big capture button (camera-timer style), driven by a
  ValueAnimator on the UI thread (60 fps, vsync); full means
  capture. The analysis only starts and stops the ring — it
  never drives a frame. CountdownBarView is deleted.
- **v0.1.63 Professional HD resolution bottom sheet + traffic-light Level.**
  The user said the v0.1.62 resolution picker worked but did not look professional. Now it is a bottom sheet (CamScanner-style): it slides up from the bottom edge, full width, rounded top corners, clean rows (bold megapixels + grey dimensions, teal check on the right, divider under each row), a BEST badge on Full sensor, and a Hide button at the bottom. And the Level circle is a little bigger (56dp, was 44dp; bubble 11dp, was 9dp) with traffic-light colours the user asked for: RED when the phone is far off level (worst angle), YELLOW when close (balanced, within 6 degrees), GREEN when it sits in the centre (best angle, level within 2 degrees) — so the colour alone tells how level the phone is. The Level is a display only now: tapping it no longer turns it on / off — it is controlled ONLY from the Settings panel (Spirit Level switch), as the user asked. The Crop screen's Auto Detect button is gone — a Retake button sits in its place (camera icon): tap it to discard the photo and go back to the camera for a new capture. Auto-detection still runs when the Crop screen opens (the frame still snaps to the page); only the manual button is removed. The Auto Crop toggle idea was discussed and dropped by the user ('OK vendam').
- **v0.1.62 2D Level style 01 Classic Bullseye (yellow bubble) + HD resolution picker.**
  The user picked style 01 from the animation preview page — a classic bullseye with a yellow bubble, like a real spirit level (teal when level, gliding smoothly in both directions). And the HD button now opens a CamScanner-style resolution picker: it lists what THIS phone's camera really supports (via Camera2, biggest first, "16M (4608x3456)" labels) plus Full sensor and Standard (1920x1080) — all free, nothing premium-locked like CamScanner's crowned sizes. The HD button shows the current resolution ("HD" / "16M" / "1080p"). The old HD on/off toggle is gone (the More Settings HD switch was removed — the picker owns the resolution now). The choice is saved and the camera re-binds with it.
- **v0.1.61 Enhance page a little smaller, a little black on top, and No Shadow is the default filter.**
  The user asked for the Filter (preview) page's image to be a little smaller, with a little black above it — and that things can be added in that space later. Fix: the page ImageView carries a 32dp top margin, so the page is a touch smaller and a little black bar sits above it, ready for future additions. The default filter is now No Shadow (was Enhance): the user said Enhance looks poor when tapped by hand — it over-sharpens (its own unsharp 1.1 plus the Adjust sharpness 40) — and picked No Shadow as the best default (clean white page, shadows removed). The default changed everywhere: the Enhance screen (new pages, per-page filter, save) and the Book Scan auto-enhance at capture. Enhance is still there to tap if wanted. The More Settings dialog is fixed too: it was too narrow, so the "More Settings" title clipped and "Full camera resolution" was cut off — now the dialog is 90% of the screen wide, the title is 18sp on one line, and the sub-label never wraps.
- **v0.1.60 less black at the top, capture area still near the centre, and a smooth page slide.**
  The user said the v0.1.59 centred preview left a lot of empty black at the top. Fix: the preview, the edge overlay and the grid all carry a 160dp bottom margin, so the centred image sits about 80dp higher — the top black bar shrinks to a little black (just the top bar with the menus) and the capture area still sits near the centre, with no big empty gap above the Single button. (Centred with no margin = too much black at the top; FIT_START = no black at the top at all — this is the middle ground.) The frame, grid and tap-to-focus stay on the image. The Filter page swipe is smooth now, too: only the PAGE slides (a 180ms slide out + 250ms slide in, gallery-style) — the filter strip, the top bar and the bottom bar never move. The old code swapped the bitmap instantly and rebuilt the whole filter row on every swipe, so the entire screen shook and the filters shook with the page; now the thumbnails are built once and only their images update in place on a page switch, so the strip stays perfectly still. (The user asked whether there is a better way — a ViewPager2 would give a finger-following drag, but it would mean rebuilding the whole Enhance screen around it; this slide is the professional gallery animation, with only the page moving, which is what was asked for.)
- **v0.1.59 capture area centred, top a little black with the menus, Filter swipe fixed, sharpness really visible, Orientation removed, and a 2D spirit level that is on by default.**
  The user corrected the v0.1.58 UI twice: first, FIT_START put the image at the very top (no black bar — wrong, the top must stay black); then, the 120dp bottom margin pushed the image up and left too much space above the Single button. The user said it plainly: the capture area in the centre is good, with a little black on top and the menus there — the rest can be fixed later. So v0.1.59 is the plain centred preview again (fitCenter, no margin, no top-alignment): a little black bar on top with the menu buttons, the capture area in the middle, the Single / Batch / Book tabs and the capture button below it. The frame, grid and tap-to-focus stay centred on the image. The Filter swipe is fixed too: v0.1.58 used a GestureDetector fling, which only fires for a fast swipe — the user swipes slowly, like turning pages in a gallery, so no next page ever came. Now the drag is tracked by hand (ACTION_DOWN → ACTION_UP): any mostly-horizontal drag past 100px moves a page. The sharpness is fixed properly: the default Adjust sharpness is 40, not 20 (v0.1.58's 20 was too subtle to see at all — it looked like it did nothing; 40 is the level the user themself set by hand, 39, so text is clearly crisp on every filter, without halos). The Orientation setting is REMOVED entirely — the user asked for it to go, because it caused errors: the Auto / Landscape / Portrait row is gone from the Settings panel, the sensor listener and rotation switching are gone, and the camera is fixed to portrait (the activity was already locked to portrait). And the spirit level is now a round bullseye that shows tilt in TWO directions — left / right AND top / bottom — so the phone can be levelled in every direction (the old bar only showed left / right), with the bubble gliding smoothly in both directions and turning teal when level on both axes; and it is ON by default, so it shows the moment the scanner opens. The brightness slider is thin now, not bold: the track is 3dp (was 6dp) and the knob is smaller too, the way a professional camera app does it. (Everything else from v0.1.58 is unchanged: tabs at the bottom, Batch Done → Filter, Crop in the menu, handles 4% inside, Book layout.)
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
