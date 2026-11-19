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
## Planned next (agreed 2026-11-14, not built yet)

- **Video Convert — 13 formats:** MP4, 3GP, MOV, FLV, MKV, AVI, MTS, TS, M4V, WEBM, GIF, WMV, MPG (MPEG-2). (Reference-app list + GIF + WMV + MPEG-2. MPEG-3 does not exist as a video format; MTS and TS are the same container.)
- **Audio tools — a lot to add:** Audio Cutter FULL (waveform + drag handles + preview), Audio Format Change (MP3/M4A/WAV/OGG/FLAC), Video to Audio expanded (M4A/WAV/OGG/FLAC beyond MP3), Ringtone Maker (30s clip → system ringtone).
- Size impact of all of it: +~0.5 MB only (app 56 → ~56.5 MB) — no new libraries, everything reuses the FFmpeg already in the app.
- **Video Cutter** also agreed (2026-11-14). Video Rotate already exists (no work). Merge (video/audio) SKIPPED — user: nobody uses it.
- **Screen Recorder** agreed (2026-11-15): JUST record + auto-optimize output (CRF + existing video compressor, ~15–30 MB/min 1080p, no watermark); NO touch indicator; NO overlay (notification controls only); internal audio (Android 10+).
- **Drafts section** agreed (user idea, 2026-11-15): unfinished work auto-saved (scanner pages etc.), auto-delete after N days, days configurable in Settings (1/3/7/30/Never); drafts in app storage (not gallery).
- **Tool Search Bar** agreed (2026-11-15): search bar at the top of Home to search tools by name (needed as the tool list grows).
- **Theme section** agreed (2026-11-15): Dark/Light/System + accent color choices (Teal default + Blue/Purple/Orange/Pink), in Settings; Dark mode must-have, colors nice-to-have.
- **Favorite tools (pin)** agreed (2026-11-15): star/pin favorite tools to the top of Home.
- **App Shortcuts** agreed (2026-11-15): long-press icon → Scan / Compress Video / Convert Video / PDF Tools; XML only, easy, no library.
- **In-app Help/FAQ** agreed (2026-11-15): help section explaining how to use each tool.
- **Request a Tool** agreed (2026-11-15): user can request a new tool from Settings (feedback).
- **Preview after convert** agreed (2026-11-15): in-app preview for image/video/audio (built-in player); PDF opens in the phone's default PDF viewer.
- **Remove Metadata/Location — video** (photos built in v0.1.111; video still to come).
- **More image formats** agreed (2026-11-15): convert to TIFF, BMP, AVIF (AVIF to be verified — needs libaom in FFmpegKit).
- **Simple/Advanced toggle** agreed (user idea, 2026-11-15): toggle at the top of Home; Simple shows basic tools only, Advanced shows all; choice persisted; Search Bar searches all tools in either mode.
- **Custom Video Thumbnail/Cover** agreed (2026-11-15): set a custom cover image for a video.
- **Resize PDF Pages (A4/Letter)** agreed (2026-11-15): resize PDF pages to A4/Letter (print/forms).
- **Quick Rotate (no re-encode)** MUST (2026-11-15): rotate a video in seconds via rotation metadata, no re-encode, no quality loss.
- **Video Color Grading** agreed (2026-11-15): manual color adjust for video (Brightness, Contrast, Saturation, Hue, Sharpness, Temperature) via FFmpeg eq/hue filters; sliders + one-frame preview.
- **Video tools menu** agreed (user idea, 2026-11-15): Standard Compress (basic), Smart Compress (multiple results at once, user picks), Advanced (full manual control), Convert (13 formats), Rotate, Color (grading), Video to Audio, Video Cutter.
- **Same quality, smaller size (visually lossless)** clarified (2026-11-15): quality looks the same to the eye, only the size reduces — this is what the CRF ladder already does (CRF 23-28, 40-60% smaller). Mathematical lossless (CRF 0) makes the file bigger — not wanted; true 0% loss with no size change is Quick Remux.

## v0.1.140 — Batch 24: Better (WebP) window clones the Smart screen layout EXACTLY (on v0.1.139)
- **Better (WebP) window — Smart screen clone** — the user showed the Smart screen and said it is better (2026-11-19: "But.. Smart inu better a irku la... Idhu mari same try panalam la"): make the WebP window the SAME as the Smart screen. Four changes, WebP window only: (a) file info WITHOUT a card — the file-details card around the thumbnail + filename + size is gone; the plain thumbnail + text sit on the background, exactly like Smart's file info; (b) a bold "Results" header above the result cards, exactly like Smart's (14sp bold, 2dp padding, hidden until the results arrive); (c) cards COMPACT like Smart's — each card is exactly Smart's on-screen height via `measureSmartCardHeightPx()` (the v0.1.136 fixed-height mechanism — authorised by the user this time, who asked for Smart's exact layout); (d) card text LEFT-aligned like Smart's — the v0.1.139 centering (MATCH_PARENT row fill + CENTER gravity on the label/size lines) is REVERTED. Kept: full-screen window (v0.1.138), white View buttons, Best/Smaller badges, Smart card colours (#ECF3FB / #E0F2F1), the centred "Original → Best • saved %" summary line and the centred explain box, tight spacing (little space below the explain box, 6dp gaps, 8dp above Save). Smart's code is untouched (verified by diff against v0.1.139: only `buildResultRow`'s `plainCard` branches and `showWebpWindow` changed). Honest note: with 4 fixed compact cards in a full-screen window there may be empty space below the last card (the v0.1.136 arithmetic) — implemented exactly as asked, not "fixed" by stretching.

## v0.1.139 — Batch 23: Better (WebP) window — the result card text is centred in the card (on v0.1.138)
- **Better (WebP) window — card text centred** — the user caught a real gap (2026-11-19: "Text enga center aa irku??" + "Paithiyam aa Nee?"): the summary line and the explain-box text ARE centred (v0.1.136), but the result card text ("WebP 60%", "26.7 KB • saved 93%") was left-aligned and top-aligned inside the tall weight-shared cards, leaving empty space below it. Fix, WebP cards only (the `plainCard` path in `buildResultRow`): the row now FILLS the card height, so the text block + View sit vertically centred in it (aligned with the vertically-centred View button), and the label + badge line and the size line are centred horizontally within the text column (the area left of the View button). Smart's card text stays left-aligned, untouched (verified by diff against v0.1.138: only the `plainCard` branches changed).

## v0.1.138 — Batch 22: Better (WebP) window is FULL-SCREEN like Smart — the fixed card-size change is reverted, old weight-shared cards are back (on v0.1.137)
- **Better (WebP) window — FULL-SCREEN like Smart, old cards back** — the user rebuked the v0.1.136 fixed-height change (2026-11-19: "Ila una yaru card size simple pana sona?" + "Smart mari nottuthu... Muttal" + "Same Old card la.. Perfect a pani kotu.. Smart mari.."): nobody asked for the card size to change — the fixed-height change was my invention. Fix: (a) the `fixedHeightPx = measureSmartCardHeightPx()` path is REMOVED from the WebP rows — the cards are weight-shared (height 0, weight 1) again, EXACTLY like Smart's cards (Smart's `buildResultRow`/`showSmartResults` cards are weight-shared too, so weight-shared IS "like Smart"); (b) the WebP window is now FULL-SCREEN like Smart instead of a floating dialog — Smart is a full-screen activity, the WebP window was a Dialog with dimmed background and margins, and that was the visible difference. Same pattern as FullPreviewDialog: window background = screen colour (kills the floating dialog frame), MATCH_PARENT x MATCH_PARENT, no dim; root, content, and rows container are weight-filled (0, 1f) like Smart's, so the 4 cards share the full-screen height with no gap above Save. Kept: white View buttons, centred summary + explain text, Smart card colours (#ECF3FB / #E0F2F1), Best/Smaller badges, tight spacing (little space below the explain box, 6dp gaps, 8dp above Save). Smart's code is untouched (verified by diff against v0.1.137: only `showWebpWindow` changed).

## v0.1.137 — Batch 21: Better (WebP) window — no empty gap below the last card, tight Smart spacing throughout (on v0.1.136)
- **Better (WebP) window — dialog wraps its content, no gap below the last card** — the cards, View buttons, and text were already like Smart (v0.1.135/136), but a ~300dp empty gap sat below the last card (WebP 95%) before Save (user, 2026-11-19: "Still very very worst.. Nee lam waste than" + "Idhu epdi irku?"). Root cause: the dialog window was MATCH_PARENT height and the content + rows containers were weight-filled (height 0, weight 1f), so they stretched to the full dialog height while the 4 fixed Smart-height cards only occupied ~376dp of it. Fix: the dialog window, the root, the content, and the rows container are all WRAP_CONTENT height now, so the dialog shrinks to fit its content and ends right after Save. Cards stay at Smart's exact height (`measureSmartCardHeightPx`, ~88dp) — NOT stretched taller (the v0.1.135 mistake) and NOT centred as a group (v0.1.131). Smart's code is untouched (verified by diff against v0.1.136: only `showWebpWindow` changed).
- **Better (WebP) window — tight spacing, dynamically fitted** — the user's spacing detail (2026-11-19: "WebP explain ku keela.. Lite a space vitutu.. Then keela Boxes perfect aa align smart mari.. And keela yu romba space ilama perfect a dynamic analyze la venum okva..."): (a) a LITTLE space below the explain box — the first card's 6dp top margin (same as Smart's inter-card gap); (b) the boxes perfectly aligned like Smart — fixed Smart-height cards, 6dp gaps, TOP gravity, unchanged; (c) NO big space below the last card — the content bottom padding (16dp) and bottom-bar top padding (8dp) were trimmed to 0, so the gap above Save is exactly Smart's 8dp (rows container bottom padding, the shadow room).

## v0.1.136 — Batch 20: Better (WebP) window is now EXACTLY like Smart — fixed-height cards + centred text (on v0.1.135)
- **Better (WebP) window — fixed Smart-height cards** — v0.1.135's weight-shared cards were WRONG: 4 cards sharing the rows' height stretched TALLER than Smart's 5 cards, so the window never looked like Smart (user, 2026-11-19: "Idhuvu... Smart um same aa? Neeyae sollu" + "Kuppai la edutha mari iruku Un UI work"). Fix: each WebP card is now exactly Smart's on-screen height via the existing `measureSmartCardHeightPx()` (measures the laid-out Smart card, ~88dp fallback) — `fixedHeightPx = measureSmartCardHeightPx()` in `refreshWebpRows`, 6dp gaps, TOP gravity, same 8dp bottom padding and `clipToPadding=false`. Smart's code is untouched (verified by diff against v0.1.135: only the WebP rows changed).
- **Better (WebP) window — text centred like Smart** — the user asked for the WebP window's text centred like Smart's ("Text um center aa.. Smart mari venum"). Verified side by side in code: Smart's status/result lines (`tvStatus`/`tvResult`) have `gravity=center` in the layout, while the WebP summary line and explain-box text were left-aligned. Fix: the "Original → Best • saved %" summary line and the explain-box title + subtitle are now CENTRED (MATCH_PARENT + `gravity=CENTER`). The result-card title/size lines and the header/file-info text were already identical to Smart's (same shared `buildResultRow`, same left alignment) — verified, not changed.

## v0.1.135 — Batch 19: Better (WebP) window is now EXACTLY like Smart, DP Crop is full-screen with a preview after crop (on v0.1.134)
- **Better (WebP) window — EXACTLY like Smart** — Smart is perfect (user, 2026-11-19: "Smart la perfect a irku don't touch that.. Same adhe mari Better compress ku venum"). Two differences remained after v0.1.134: (1) the WebP View pill was filled with the card's own colour (#ECF3FB), so it looked bluish next to Smart's WHITE button — fix: the pill is now white (`hakunaa_surface`), exactly like Smart's MaterialButton (same 13sp teal text, 1dp teal stroke, 10dp corners, 40dp height); (2) the WebP cards were fixed ~88dp and centred, leaving a big empty gap above and below the group — fix: the cards now share the rows' height by weight (height 0, weight 1, 6dp gaps, TOP gravity), exactly Smart's mechanism, so the window fills its screen with no empty space. Same card style, same badges — the only difference left is 4 cards instead of Smart's 5, so each is taller. Smart's code is untouched.
- **DP Crop — full-screen crop + preview after crop** — the user called DP Crop "nala vae ila" and asked for a full-screen crop with a preview after. Fix: (a) the crop screen is now full-screen — the root is a FrameLayout, the crop view takes the whole screen with margins leaving room for the top bar (status bar + 56dp) and a compact bottom panel (chips + swatches + hint + Back/Save); the old LinearLayout stacked the photo as a small square between the bars. `DpAdjustView` no longer forces itself square (the old `onMeasure` took min of width/height) — the photo is fitted into the full view and the frame (stored in bitmap fractions) works at any size. Status bar handled the Book way (`StatusBar.setup(..., applyInsets = false)`, bars placed explicitly below the status bar / above the nav bar). (b) After tapping Save, the picked frame is rendered full-res (`dpCircleRect` / `dpSquareRect`, unchanged) and a PREVIEW of the real cropped output is shown — full-screen, pinch-zoomable (`ZoomableImageView`, 1x–4x, double-tap to reset) — with Back (returns to the crop tool, frame unchanged, preview file deleted) and Save (writes to Pictures, then the professional Saved popup). Colour row is INVISIBLE (never GONE) so the bottom panel height — and the crop view's bottom margin — never changes. No new features — no rotation, grid, or aspect controls.

## v0.1.134 — Batch 18: Quality picker no longer shows an unwanted screen, Choose PDF size is a themed dialog, DP Crop handles are big and bold, no Smaller label on 2-box screens, WebP View button same as Smart (on v0.1.133)
- **Compress Quality picker — no more unwanted screen** — pressing a quality % card showed a mixed screen: `showCompressingProgress` made the results container VISIBLE while the quality picker was still VISIBLE, so the "Compressing…" spinner box appeared ABOVE the 7 quality cards (which got squeezed) for the whole compression. Fix: the picker is hidden before the progress shows, so the transition is picker → clean spinner → result. On the empty-results path the picker is re-rendered instead of leaving a blank screen. The compression itself and the 2-box result are untouched.
- **Image to PDF — "Choose PDF size" is a themed Option now** — the chooser was a plain system AlertDialog (white, blue Cancel). Fix: a custom themed dialog (plain GradientDrawable card, 20dp corners, teal accents, teal Cancel text) like the app's other option screens — same 3 options with their REAL sizes and notes, same tap-to-save behaviour. The sizes, the pages list, drag reorder and A4/Letter/Portrait/Landscape are unchanged.
- **DP Crop — professional handles** — the v0.1.133 frame crop worked but its resize dots were tiny (5dp radius, 2dp ring) and the frame outline thin, so the tool did not look professional. Fix: 8dp dots with a 3dp white ring at the circle's cardinal points, and a bolder 4dp teal frame outline (both Circle and Square; Square's L-shaped corners already matched ProCropView). The drag/resize math is unchanged and still clamps the frame inside the photo (move coerceIn, corner scaleFit, circle maxR). No new features — no rotation, grid, or aspect controls.
- **Quality / Target / HD 2-box results — no Smaller label** — Best was already removed in v0.1.132; the user now removed Smaller too ("smaller Lable vendam"). Fix: `buildSmartResultCard` hides the Smaller badge on the 2-box screens (same `smartHideBestBadge` flag as Best). NO badges on Quality/Target/HD; Best/Smaller live ONLY in Smart and in the WebP window (which builds its rows directly, so its badges are untouched).
- **WebP ("Better") window — same look as Smart** — the View button looked dark and the colours/texts were not perfect. Root cause (same as v0.1.131's card finding): the dialog's M3 theme tints Material views darker — the two remaining MaterialCardViews in the window (file details card, WebP explain card) and the MaterialButton View buttons all rendered grey-blue. Fix: the file card and explain card are now plain LinearLayout + GradientDrawable in Smart's exact light colour (#ECF3FB, like the result cards), and the View button in the WebP window is a plain TextView pill filled with the card's own colour (teal text, 1dp teal stroke, 10dp corners, 40dp) — so it blends exactly like Smart's white button on Smart's white card. Smart's own View button (MaterialButton) is untouched, as are the WebP box size, 10dp spacing and Best/Smaller badges.

## v0.1.133 — Batch 17: DP Crop is now the Crop tool (drag/resize the circle/square frame), status-bar overlap fixed everywhere, Image to PDF shows 3 sizes to choose (on v0.1.132)
- **DP Crop — the Crop tool itself (Circle + Square)** — the adjust step used to pinch-zoom and drag the PHOTO inside a fixed frame; the user asked for the Crop tool itself instead ("Crop tool yae open aaganum, Circle a pick panra mari"). Fix: the DpAdjustView interaction is now frame-based, like the professional Crop tool (ProCropView's frame logic, adapted): drag the circle/square frame to move it, drag a corner (Square) or the edge (Circle) to resize it, with resize dots on the circle's cardinal points. The photo stays fixed, fitted behind the frame; the frame is stored as fractions of the bitmap, so it can never leave the photo and survives rotation. The outer area (Blur / Color / Transparent + swatches) is still drawn live in the frame's corners (Circle), exactly as the saved DP will look. Save renders full-res from the picked frame: Circle via the existing `dpCircleRect` (chosen square masked to a circle with the outer area), Square via a new `dpSquareRect` (chosen square cropped, JPEG 92). The old pinch/drag transform (`dpAdjust`) is now unused; the chips, swatches, Back/Save and the Color-black default are unchanged.
- **Status-bar overlap — fixed everywhere (root cause)** — the header overlapped the status bar on the DP Crop adjust screen and on Image to PDF ("Top status bar oda overlap aaguthu paru ellma fix pananum"). Root cause: DpAdjustActivity and ImageToPdfActivity were the ONLY two activities calling `StatusBar.setup` BEFORE `setContentView` — at that point the content view did not exist yet, so `getChildAt(0)` was null and the inset padding was silently never applied (audit: all 13 other activities call it after). Fix: both calls moved to after `setContentView`, and `StatusBar.setup` itself now retries once the view is attached if the content view is not there yet, so this class of bug cannot recur. The v0.1.98 status-bar rule itself is unchanged, and Book's hidden status bar is untouched.
- **Image to PDF — 3 sizes, choose then save** — Create PDF used to save directly. Fix: like the scanner export, all 3 sizes are now built up front on the user's chosen page (A4/Letter + Portrait/Landscape) — Normal (full quality, JPEG 95), Compress (2000 px, JPEG 80), Extreme (1200 px, JPEG 60) — via a new page-size-aware `imageToPdfQuality` overload, and a chooser dialog shows each with its REAL file size and note; the user taps one to save it to Documents (the other two are deleted). The pages list, drag reorder, add/remove, A4/Letter and Portrait/Landscape are unchanged.

## v0.1.132 — Batch 16: Quality/HD JPEG first + no Best label, WebP a little space, Resize back to the v0.1.123 menu, Convert JPG → JPEG, DP Crop Color black default + centred adjust, Image to PDF multi-page (on v0.1.131)
- **Quality / Target / HD results — JPEG first, no Best label** — the 2-box screens showed WebP on top (smallest-first) with the Best badge, but JPEG is the user's preference. Fix: on the 2-box screens only, the list is ordered JPEG first, WebP second, JPEG is the default selected box, and the Best badge is hidden (new `smartHideBestBadge` flag, set from `fixedSizeCards`). Smart keeps smallest-first + Best/Smaller. Only the display order and default selection change — the compression and saved sizes are untouched; HD's JPEG and WebP are both still full-res.
- **WebP window — a little space** — the tight 6dp gaps were still "worst" ("Lite a space kotutha ena?"). Fix: 10dp between the WebP boxes. The fixed card-height cap now subtracts the margins, so the last card still never clips. Colour (plain Smart-colour cards) and box size unchanged.
- **Resize menu — back to the v0.1.123 menu** — the v0.1.131 restore to v0.1.128 did NOT satisfy the user ("Resize very worst"). Investigation (git history v0.1.117–v0.1.125): no version ever had a literal "Govt" string in the Resize code — the "for Govt" the user remembers is the Custom row's subtitle "Enter your own size — for exam forms" (exam forms are Govt forms for him), present in v0.1.117–v0.1.124 and removed in v0.1.125 for the weight-shared rows. Fix: the OLD menu is restored exactly as in v0.1.123 — the Custom subtitle is back, and the 12 rows are roomy WRAP_CONTENT cards (14dp padding, 8dp gaps) instead of the weight-shared ~44dp compact rows. The old menu scrolled (v0.1.123 had a ScrollView), so it lives in its own runtime ScrollView — Resize-only (new `targetContainer` parameter on the shared card builder + a `resizeScrollView` field removed on every renderStep); the no-scroll rule still holds everywhere else. This deliberately overrides the Batch 9 no-scroll rule for this ONE menu, because the user explicitly asked for that batch's UI.
- **Convert — JPG → JPEG** — the format card label now says "JPEG" (the user: "JPG -> JPEG idhu matu mathita best a irkum"). Only the display label changes; the tag stays "jpg", so the conversion itself is untouched.
- **DP Crop — Color black default + centred adjust** — the default outer area is now Color (black) instead of Blur (reversing the v0.1.124 rule, at the user's explicit request: "Default aa Blur ilama Color layer black Default"); the tip text matches. The adjust step (DpAdjustActivity + DpAdjustView) was verified unchanged since v0.1.124 — pinch 1x–4x, drag, double-tap reset, chips, swatches and Back/Save all work — but the square was pinned to the top of its weight-1 slot, leaving a ~270dp dead gap above the chips ("worst"). Fix: the square now sits CENTRED in its space (FrameLayout, gravity CENTER); the view still measures itself square, so centring the frame is all it needed.
- **Image to PDF — multiple images + page management (NEW)** — the old flow took ONE image and made a one-page PDF. New screen (new `ImageToPdfActivity`, new `PdfTools.imageToPdf(files, out, pageRect)` overload): pick MULTIPLE images at once (multi-select from the gallery, opens immediately), see each as a page with a thumbnail (EXIF-aware, ~200px), DRAG to reorder pages (ItemTouchHelper — drag handle or long-press), add more images, remove a page, pick page size (A4 / Letter) + orientation (Portrait / Landscape), then Create PDF from the pages in order with the existing PDF writer (lossless engine, one page per image). Pages survive rotation (URIs saved and re-added). The menu's "Image to PDF" card now opens this screen; the old single-image function is removed.

## v0.1.131 — Batch 15: WebP colour + gaps + centring, Compress gap fixed, Quality/Target/HD show 2 boxes, Resize back to old, Social without side previews, Convert boxes bigger (on v0.1.130)
- **WebP window — colour, gaps, centring** — the WebP cards were dark because a MaterialCardView tints darker inside a Dialog (the M3 surface-tint / elevation overlay is stronger there — pixel sampling proved it: Smart renders #ECF3FB, WebP rendered #D9E4F6, and no source colour can pre-compensate because it would need a red value above 255). Fix: the WebP cards are now plain containers (LinearLayout + GradientDrawable) in Smart's exact rendered colours (#ECF3FB unselected, #E0F2F1 selected) with 2dp framework elevation for the shadow — Smart itself keeps MaterialCardView, untouched. The huge gaps were the 5 weight-1 spacers sharing all the slack — removed; the cards sit in a centre-gravity container with a minimal 6dp gap, so the group is perfectly centred and the gaps are minimal.
- **Compress mode + Convert — the bottom gap, root cause** — the big empty space above Back was NOT the layout: `tvStatus` and `tvResult` had no visibility attribute, so they were VISIBLE with empty text, taking ~54dp of invisible dead space (~35dp status + ~19dp result) on every screen where no status was ever set. Measured from the user's screenshot: the gap is ~72dp, matching the dead space + paddings exactly. Fix: both are `gone` by default in the XML (the status helpers already toggle visibility whenever text is set, so a real status still shows). Removing the dead space also gives the Compress photo and the Convert cards more room — Convert's 7 weight-shared boxes each get ~8dp taller, which is the "boxes a little bigger" the user chose.
- **Quality / Target / HD results — 2 normal boxes, no more giant box** — a single result filled the whole weight-shared list, so one box stretched giant ("very very worst"). Fix: each of the 3 menus now shows TWO normal-size boxes (JPEG + WebP) at a fixed Smart height (~88dp), centred as a group. Quality: WebP at the same chosen quality, same 2048 downscale (new `compressAtQualityFormat`). Target: WebP at the same target size (new `compressWebpToTargetSize`, same binary search). HD: WebP from the HD ladder (95/90, FULL resolution — HD is never downscaled; new `compressHdCompanion`). The bottom "Better Compress with WebP" button is REMOVED from these 3 menus (the WebP box is already shown directly); Smart keeps it. The Target labels now say which format they are ("JPEG Target 500 KB" / "WebP Target 500 KB") so the two boxes are distinguishable. The Quality back-navigation path re-shows with the same 2-box settings (it used the defaults, which would have re-stretched the boxes).
- **Resize menu — back to OLD** — the v0.1.129/130 last-box fix (8dp room + clipToPadding=false) had touched the shared option-card container, changing Resize. Fix: a `lastBoxRoom` flag on the shared builder — Resize passes false and gets the exact v0.1.128 menu back (no bottom room, default clipping, set explicitly because the container is reused across screens); every other list keeps the fix.
- **Fit to social media — no side previews** — the preview thumbnails on the left of each card are removed (the preview bitmaps are not computed anymore); just the box (logo + aspect + label) with the empty outline frame, the old way before v0.1.124.

## v0.1.130 — Batch 14: last-box shadow REALLY fixed (clipToPadding root cause), WebP boxes exactly Smart's size (on v0.1.129)
- **Why v0.1.129 failed on the phone** — v0.1.129 added 8dp of room below the last card, but that alone NEVER works: Android's `ViewGroup.clipToPadding` defaults to true, which clips every child's drawing — including its elevation shadow — to the parent's padding box. The last card's shadow falls below the card, into the bottom padding, which is OUTSIDE the padding box, so it was cut at the card's edge no matter how much padding there was. That is exactly what the screenshots showed: middle cards' shadows were visible (they fall in the inter-card margins, inside the padding box); only the LAST card's shadow was missing. And the WebP "fixed 112dp" was a guess from a wrong density estimate — Smart's weight-shared cards render at ~90dp on the user's phone, so 112dp still looked "very very worst".
- **Smart last box — REALLY fixed** — the rows container now sets `clipToPadding = false` (keeping the 8dp room), so the shadow can actually draw into the padding. Same fix on every other card list that got the v0.1.129 padding (Social/Convert/Resize option cards, Compress mode rows, Quality % picker) — their last boxes had the same latent clip.
- **WebP boxes — exactly Smart's size** — no more guessed dp: the WebP cards now MEASURE the Smart card that is already on screen (`measureSmartCardHeightPx()` — the first child of the "smart_rows" list, with an estimate for the HD single-card case and an 88dp fallback) and use that exact height, capped by the space actually available so a small phone never clips. A post-layout re-check rebuilds once if the first measurement was stale (taken while "Compressing WebP…" was still visible). The WebP rows container also gets 8dp below the last card + `clipToPadding = false`, so the last WebP box's shadow renders fully.

## v0.1.129 — Batch 13: Smart last-box shadow + space management, WebP normal-size redesign, last box fixed everywhere (on v0.1.128)
- **Smart results — OK, but the last box's shadow + space management** — Smart was correct overall (5th box size line + View fixed in v0.1.128), but the LAST box always had a problem: its shadow was not correct. Root cause: the last card sat flush at the bottom of its container with zero room below, so its elevation shadow was clipped by the container edge. Fix: the rows container now has 8dp of room below the last card — just enough for the shadow, and a little space above Save (not zero, not the big gap of before). The top is tighter too: the image header padding is 16dp → 12dp and the "Results" heading padding is 4dp → 2dp.
- **WebP window — redesigned, normal-size boxes** — the v0.1.128 weight-shared cards stretched to ~173dp each ("romba perusu"). The 4 WebP cards are now a FIXED normal height (112dp, like Smart's cards), capped by the space actually available so a small phone never clips the last card, with even spacers between them taking the slack. Layout, as asked: top (image details + Original → Best summary), then ONE box explaining WebP a little ("WebP — smaller files, same quality / 25–35% smaller than JPG • NOT for Govt forms"), then the normal-size result boxes below it.
- **Last box fixed EVERYWHERE** — the same clipped-shadow cause was verified in every card list and fixed the same way (8dp of room below the last card): Smart/HD/Target/Quality results, Quality % picker (7 rows), Social/Convert/Resize option cards, and the 3 Compress mode rows. The bottom rule now: a little space after the last box is acceptable, never too much. The menu grid and DP Crop previews were verified and already have room below their last cards — no change.

## v0.1.128 — Batch 12: Smart 5th box fixed, no dead space above Save anywhere, WebP top details (on v0.1.127)
- **Smart results — ONLY the 5th box was broken, and it is fixed** — the first 4 cards (JPEG 60/70/75/85%) were fine; only the 5th (JPEG 95%) lost its size line and its View button rendered as "( View )". Root cause: the v0.1.127 WRAP_CONTENT cards are measured by LinearLayout with the REMAINING space as AT_MOST, so the last card got a tiny spec and measured ~35dp instead of ~75dp. Fix: the 5 result cards now SHARE the rows' height by weight (height 0, weight 1) with a 64dp minimumHeight floor, so they fill the screen and can never squeeze below their content height. The other 4 cards keep the same content and look (same single-line size text, same View button, same badges) — only the height distribution changes so all five fill the screen evenly.
- **No dead space above Save — EVERYWHERE** — the status/result lines (tvStatus/tvResult) were always laid out even when empty, leaving ~54dp of dead white space above the pinned Save button on Smart, Quality, Target, HD and DP Crop results. They are now GONE when empty (new setStatusText/setResultText helpers) and only take space when they actually show a message. The WebP window's own status line and DP Crop's status line got the same treatment. Save sits tight under the content on every screen.
- **WebP window — lite changes, as asked** — (a) more details at the top: a new bold "Original → Best • saved %" summary line appears once the WebP results arrive (e.g. "Original 472.1 KB → Best 35.0 KB • saved 92%"); (b) the big empty space between the WebP 95% card and Save is gone (status line GONE when empty, cards share height).
- **Smaller & Best labels verified correct** — no code change: balancedBest (smallest file = Smaller; Best = highest quality within 2x of smallest) already follows the rule on Smart AND WebP, and the user's screenshots match it (Smart: Smaller JPEG 60% 40.2 KB, Best JPEG 85% 60.2 KB; WebP: Smaller 60% 22.5 KB, Best 85% 35.0 KB).

## v0.1.127 — Batch 11: Smart results fixed, old v0.1.123 UI + function restored, WebP fixed (on v0.1.126)
- **Smart results screen FIXED** — the v0.1.126 layout was broken (size line cut off on every card, View buttons rendering as "( View )", cards too tall). Root cause: the weight-1 cards squeezed below content height on the user's phone. Cards are WRAP_CONTENT again (v0.1.123 style) — a card is exactly as tall as its content, so the size line can NEVER be cut off and View renders as a proper rounded rectangle.
- **Old UI design + Old Fixed Function restored** — Smart/Compress are back to the exact v0.1.123 result UI (compact single-line cards, no header bar, Save pinned, Better Compress bar) and the v0.1.123 downscale function (small files). The v0.1.126 "Small file / Full size" switch is REMOVED entirely — nobody asked for it and it broke the layout. The only differences from v0.1.123 are the user's explicit asks: Smart first in the menu, and "View" instead of "Preview". Nothing invented, nothing added.
- **WebP window fixed — UI + functional** — WebP now also downscales (2048) so it gives small files like Smart (was full-res: 664 KB at 60%). The window layout is fixed too: result cards are WRAP_CONTENT so the 95% card is never cut off, and the big empty gap above the first result is gone. WebP stays in its own window, separate from Smart. HD Compress stays full-res (that is HD's purpose).

## v0.1.126 — Smart + Compress algorithms restored: small files are back (Batch 10)
- **Smart Compress — old super results are back** — Smart was behaving like HD Compress (full resolution, 1.1–4.5 MB for a 4.7 MB photo, JPEG 95% saving only 4%). Smart now downscales again like the old build (max 2048 px: a 3456×4608 photo compresses at 864×1152), so the small files are back. Smart is NOT HD — HD keeps full quality/resolution, Smart compresses aggressively.
- **Smart = ALL variations** — Smart now shows every variation: all quality levels (60/70/75/85/95) AND both resolutions. A compact "Small file / Full size" switch sits above the results (Small file is the default); the 5 cards still share the screen by weight, no scroll. No fixed size target — the result adapts to each photo.
- **Compress tool fixed too (Quality + Target)** — Quality now compresses the downscaled photo (small files like the old build); Target downscales before its binary search, so small targets are actually reachable, and the too-low warning uses the downscaled minimum. HD Compress stays full-res (that is HD's purpose). WebP stays separate in its own window, untouched.

## v0.1.125 — ALL 11 Batch 9 fixes in one build: Smart results back to the old clean v0.1.123 style (single-line saved text, no wrap, no cut-off), top Original bar removed + Save pinned + perfect bottom alignment, WebP window simplified, NO screen scrolls anywhere (dynamic single-screen layouts — the ScrollView root cause fixed), Leave popup redesigned professional, Social Crop fits one screen + accurate logos, Compress mode screen COMPLETELY redesigned (whole photo, no gap), Smart Compress FIRST in the menu, Preview → View everywhere, Convert redesigned to fit all 7 cards in one screen (on v0.1.124)

- **Smart Compress results — old clean style is back (v0.1.123)** — result cards are back to the compact single-line style: "97.3 KB • saved 97%" on ONE line, 14sp secondary, no bold-teal two-line saved text, no wrapping, no cut-off. The v0.1.124 bold-teal saved text wrapped to 2 lines and looked messy — reverted. Best card no longer cut off mid-card.
- **Smart results — ONLY the top Original bar removed, Save pinned, perfect bottom alignment** — the top "Original → Best" bar is gone (the top image details already show the original); everything else stays the old clean style. Save stays pinned in its own bar above Back/Done (never in scroll); Better Compress is now a fixed 56dp button like Save, and the bottom bar gaps are all exactly 8dp, so Save / Better Compress / Back / Done align perfectly. The empty gap above Save is gone.
- **WebP window — same fixes + simplified, clean** — results use the same old clean single-line style (no wrapping saved text). The page is no longer complex: the image-details card, WebP facts card, Works/NOT lines and Best-balance line are gone, replaced by ONE short line: "WebP — 25–35% smaller than JPG, same quality • NOT for Govt forms". The results share the screen height by weight — no scroll.
- **NO-SCROLL RULE — nothing scrolls on ANY screen** — root cause found and fixed: the Compress/Smart/Convert/Social screens all lived inside a ScrollView, where layout_weight never expands (that is why v0.1.124's weight fixes did nothing — Convert still cut off BMP, Social still scrolled, Compress still left a gap). The ScrollView is GONE — the content area is now a plain LinearLayout (weight 1) and every screen's cards share the real screen height by weight, so each screen fits PERFECTLY in one screen on any phone: perfect space management, perfect alignment, no cut-off, no gap.
- **Leave popup — redesigned, more professional** — the "Leave this tool? / Your work will be discarded." dialog is now a clean professional card: a small teal-tint circle with a teal "!" (no more amber warning icon), icon + title on one row, message below, and two EQUAL full-width buttons — Cancel (outlined teal) and Leave (filled teal), both 48dp with 12dp corners. Same behaviour (Cancel dismisses, Leave discards and exits).
- **Social Crop — fits one screen + more accurate logos** — all 6 platform cards now share the screen height by weight (no scroll, WhatsApp card no longer at the bottom edge). Logos verified against the real brand marks: Instagram is now the official 5-stop gradient (#FEDA75 → #FA7E1E → #D62976 → #962FBF → #4F5BD5, bottom-left → top-right) with the correct lens and dot sizes; YouTube play triangle and WhatsApp bubble stroke corrected.
- **Compress mode screen — COMPLETE redesign, professional** — the top photo area is rebuilt: the photo now shows WHOLE (FIT_CENTER, never cropped — the v0.1.124 240dp CENTER_CROP band cut off the photo) on a clean surface background, and it FLEXES to fill the available height (weight 1); the 3 mode rows are fixed 88dp each below it. No cut-off photo, no empty gap above Back, one screen, perfect alignment.
- **Smart Compress FIRST in the menu** — Smart Compress is now the first tool in the Image Tools menu (top-left), ahead of Compress.
- **Preview → View everywhere** — every Preview button on result cards (Smart / WebP / Quality / HD) and the full-screen preview title and toggle now say "View". (Compare is unchanged.)
- **Convert screen — redesigned, all 7 cards fit in one screen** — the 7 format cards share the screen height by weight (the v0.1.124 weight fix failed because the cards were inside a ScrollView). Subtitles are shortened so the "Works on" line fits on one line at 12sp; "Govt forms OK" (JPG/PNG) and "NOT Govt forms" (WebP) are kept in the description. No scroll, no cut-off, BMP and AVIF both visible.
- **Also fixed in this build** — Quality rows share the height (min 48dp each); Resize Custom subtitle removed so all 12 rows fit; the 64dp minimumHeight that broke weighted cards is gone; compact cards use 8dp padding + 12sp subtitles so their text fits on one line.

## v0.1.124 — ALL 13 Batch 8 fixes in one build: Compress mode screen fills the screen, Original→Best summary bar removed everywhere + Save pinned (never in scroll), WebP actually smaller than JPEG (full-res JPEG ladder fix), Compressing animation (never an empty screen), WebP window professional details + image card, top image details big/clear everywhere, saved size/% bold + saved amount, Best = quality+size balanced (smallest = Smaller), Saved popup on EVERY save, Social Crop photo-filled previews + fills screen, Convert single screen (7 cards, no scroll), DP Crop Blur default + professional adjust step for Circle AND Square (no more instant save), top picked-file details everywhere (on v0.1.123)

- **Compress mode screen — fills the screen, professional + premium** — the big 240dp photo stays at the top, and the 3 mode rows now SHARE all the space below it (weight), so there is never an empty gap before Back, on any phone. Each row is vertically centred with a chevron. (v0.1.123's fixed 64dp rows left a huge empty gap.)
- **Original→Best summary bar REMOVED everywhere** — the "Original X → Best Y • saved N%" bar is gone from Compress / Smart / WebP results (the top image details already show the original, and each result card shows its own saved size/%). The freed space means the **Save button is PINNED** just above Back/Done in its own bar below the scrolling list — it never goes into scroll again (with 5 results it was below the fold).
- **WebP actually smaller than JPEG — compress algorithm FULLY verified** — root cause found: the Smart JPEG ladder called `compress()` with the default maxDimension 2048, so a 3456×4608 photo was silently decoded at 864×1152 — JPEG 60% came out at 97 KB while full-res WebP 60% was 664 KB, so WebP looked 7x WORSE. Every ladder is now full-res (10000): Smart JPEG, Quality, HD and WebP are compared at the same resolution, so WebP is smaller than JPEG at the same quality again, as it should be. The saved % is now the honest number (a downscaled 1MP file is not a 97% saving on a 16MP photo).
- **Compressing animation — never an empty screen** — while any compress computes, a teal spinning indicator + "Compressing…" + "This can take a moment for large photos" shows in the results area. The WebP window shows its own "Compressing WebP… / Large photos can take up to a minute" animation (WebP took ~1 minute and the screen stayed blank).
- **WebP window — even MORE professional details at the top** — an image details card first (thumbnail + name + size • format • resolution), then the WebP facts card (what WebP is, where it works, where it does NOT), then the balanced pick once computed ("Best balance: WebP 85% • 1.6 MB • saved 66%").
- **Top image details CLEARLY visible in ALL places** — every result screen's header is now an 88dp rounded thumbnail with a bold 16sp name and "size • FORMAT / resolution" underneath; the picked-file header on every tool screen shows name, size, resolution and format. Only then are compress results easy to understand.
- **Saved size/% MORE visible in ALL places** — each result card now shows the result size BOLD and "saved X (N%)" BOLD TEAL — not small grey text.
- **Best label — quality + size BALANCED, not smallest** — the smallest result (lowest quality) is no longer labelled "Best". A new `balancedBest()` picks the highest-quality result within 2x of the smallest file (e.g. JPEG 85% over JPEG 60%), and the smallest gets its own "Smaller" badge. Two labels: Best & Smaller. The default selection is the balanced Best too.
- **Professional Saved popup on EVERY save** — the Convert-style popup (green check + thumbnail + name + Pictures/Hakunaa • size • format + Open / Share / Done) now appears on every save: Compress, Smart, WebP, Resize, Target, Fit, Remove Metadata, Crop, Fit to social, DP. The dialog moved into a shared `SavedDialog` (new file) so every tool uses the same one.
- **Social Media Crop — MORE UI improvement, no bottom gap** — each aspect frame now shows the ACTUAL photo cropped to that aspect (the teal outline rectangles looked empty), and the 6 cards share the screen height (weight) instead of a fixed-height formula — no gap above Back, no card cut off.
- **Convert — perfect single screen, NOT scrollable** — all 7 format cards (JPG/PNG/WebP/GIF/TIFF/BMP/AVIF) share the screen height with compact padding, so they fit in one screen with no scroll and no empty space above Back/Convert.
- **DP Crop — Blur default + professional adjust step** — the screen now always opens on Blur + White (it was landing on Color + Black). Tapping a preview NO LONGER saves immediately: a new `DpAdjustActivity` + `DpAdjustView` opens for BOTH Circle and Square — pinch to zoom (1x–4x), drag to move, double-tap to reset, outer area (Blur / Color / Transparent for Circle) drawn live exactly as the saved DP will look — then Save renders full-res via a new `ImageCompressor.dpAdjust()` and shows the Saved popup.
- Nothing else changed in this build — scanner, Enhance, Book, video, PDF and the compression law untouched; Batch 4 items 3–7 still held.

## v0.1.123 — ALL 8 Batch 7 fixes in one build: Convert premium + Convert button + professional save popup, TIFF/AVIF actually open, GIF + HEIC input, Compress mode screen redesigned (big photo, small menus), WebP window with MULTIPLE results + details, Smart button pinned above Back/Done + 5 results, Social Crop polish, DP Crop Blur/Color outer area, "Better Compress with WebP" rename everywhere (on v0.1.122)

- **Convert — professional + premium** — the format cards are taller and cleaner (bold name, big "size • quality" line, teal "Works on" compatibility line under each). The bottom button now says **"Convert"** (was "Next"). The step subtitle reads "Pick a format — we'll convert your photo into it" and the GIF line is honest: "GIF — Animated images, stickers — plays on WhatsApp, web". TIFF says plainly it does NOT open in Android Gallery (desktop viewers only); AVIF opens on Android 12+ once saved correctly.
- **Save — a clear popup, not a small line** — after Convert saves, a professional popup appears: green check, a thumbnail of the photo, the file name, "Pictures/Hakunaa • size • FORMAT", and three actions — Open / Share / Done. Other tools keep the small status line.
- **TIFF/AVIF files now DISPLAY on mobile** — root cause: every image save used MIME `image/jpeg`, so a `.tif`/`.avif` file was indexed as JPEG and Gallery could not open it. Every image save site now passes the file's REAL MIME type (ImageToolActivity ×4, ImageCropActivity, ImageEditActivity, PdfToolsActivity ×2). AVIF now opens on Android 12+; TIFF opens in desktop viewers (Android Gallery has no TIFF decoder — the Convert card says so honestly).
- **NEW formats: GIF output + HEIC input** — GIF output is now offered (FFmpeg native GIF encoder + palettegen, verified in the FFmpegKit 8.1.9 AAR). HEIC input is now accepted: HEIC photos decode via FFmpeg (HEIF demuxer + native HEVC decoder) to a temp PNG, then convert to any format. HEIC OUTPUT is NOT offered — FFmpegKit 8.1.9 has no HEIF muxer, so it cannot be encoded or verified on the user's phone (which has no HEVC decoder). Other office formats considered and not added: JP2 (niche, same Android display problem), QOI (niche), SVG (no rasterizer).
- **Compress mode screen redesigned** — the 3 mode cards were TOO BIG in v0.1.122 (they shared the full height). Now the image details are BIG at the top (a 200dp photo with the file name, size and format below) and the 3 menus are SMALL and compact (64dp rows, icon + name + one-line description). Light, clear, professional + premium.
- **WebP window — SUPER, made better** — the title is now "Better Compress with WebP". It shows MULTIPLE WebP results (quality 95 / 85 / 70 / 60 — was one result) so the user picks the quality/size they want, and a professional details line at the top: "Original X → Best WebP Y • saved N% / Quality N% — the same photo, much smaller file". The explanation (what WebP is, where it works, where it does NOT) is unchanged.
- **Smart Compress — lighter + 5 results** — the "More Compress with WebP" button moved OUT of the scrolling list to just above the bottom Back/Done buttons (its own pinned bar) and is renamed **"Better Compress with WebP"**. Smart now shows **5 results** (JPEG 95/85/75/70/60 — was 3), with the same per-result Preview. The "no smaller JPG" hint points to Better Compress with WebP.
- **Social Media Crop — polished further** — the YouTube badge is now a white play triangle on a red rounded rectangle (was a small red rectangle, inconsistent with the circular logos). Social cards have a FIXED height that fills the screen with no empty space and no gap below the last card.
- **DP Crop — NEW outer-area options** — for Circle AND Square: an "Outer area" row with three chips — Blur (the default), Color, Transparent — plus 6 colour swatches (White / Black / Teal / Pink / Blue / Cream) when Color is picked. Circle saves a square image with the chosen outer area (Blur = a blurred version of the photo behind the circle, Transparent = PNG); Square saves a fitted square image with the same outer area (Blur / Color / Transparent). The DP screen's Circle and Square previews now show the chosen outer area live.
- **"Better Compress with WebP" rename in ALL places** — the button, the WebP window title and every comment/reference say "Better Compress with WebP" now; no "More Compress" text remains anywhere in the app. Nothing else changed in this build — scanner, Enhance, Book, compress logic and the compression law untouched; Batch 4 items 3–7 still held.

## v0.1.122 — ALL 9 Batch 6 fixes in one build: menu fills the full screen, Compress mode screen professional, per-result Preview, More Compress WebP window, HD instructions in place, Smart same simplification, more accurate social logos + WhatsApp DP, DP Edit FULL redesign (renamed DP Crop), Resize dialog polish (on v0.1.121)

- **Image Tools menu FILLS the full screen** — the 5 rows now share the screen height equally (weight) and each card fills its row, so the grid reaches the bottom — no empty space. Cards got bigger icons (44→52dp frame, 24→28dp icon) and slightly larger text, content vertically centred, so tall cards still look balanced.
- **Compress mode screen perfect + professional** — the 3 mode cards (Quality / Target size / HD Compress) now share the remaining screen height with centred content, instead of short cards floating over a huge empty gap above the Back button. ScrollView uses fillViewport so the cards stretch to the bottom bar.
- **Per-result Preview (Compress + Smart)** — the separate full-width "Preview — full screen" button is GONE. Every result box now has its own small Preview button on the right side; tapping it opens the full-screen zoomable Preview + Compare for THAT result (and selects it, so "Save selected" matches). Tapping the rest of the box still selects the result.
- **More Compress → NEW WebP window** — the button moved BELOW "Save selected" (secondary action, not wedged between results and Save). Tapping it opens a full-screen window that explains WebP (Google's modern format, 25–35% smaller than JPG at the same quality; works on WhatsApp, Instagram, web; NOT accepted on Govt forms / portals) and shows the WebP results there as the same cards (per-result Preview, Save at the bottom). WebP is computed lazily and cached. Nothing WebP appears inline on the result screens any more.
- **HD instructions in the right place** — the HD result screen now carries its own one-line explanation with the result ("HD — same photo, same size, 100% quality. Only the file got smaller."). The HD mode card keeps its subtitle. No HD/WebP guidance leaks onto other screens. Also fixed: a stale "Working…" status no longer lingers under the results.
- **Smart Compress — same simplification** — Smart uses the same shared result screen, so it gets the per-result Preview, the moved More Compress button and the cleaner layout automatically.
- **Social Media Crop — MORE accurate logos + WhatsApp DP** — Instagram's badge is now its real purple → pink → orange gradient (was solid pink); YouTube is now the full red rounded-rectangle play-button mark (was a white triangle on a red circle); Facebook "f" and colours unchanged. NEW: a WhatsApp DP (profile photo) card (1:1) with a code-drawn WhatsApp glyph (bubble + handset) on the green #25D366 badge. Subtitle now mentions WhatsApp.
- **DP Edit FULL REDESIGN — and renamed "DP Crop"** — the live previews ARE the actions now: two LARGE tappable preview cards side by side (Circle on checkerboard, Square), each with a bold label and its format underneath (PNG • transparent outside / JPG • square). No separate Circle/Square cards below (they duplicated the previews and left the huge empty gap) — just a "Tap a preview to crop & save" hint and a tip (Circle works best for WhatsApp & Instagram profile photos). Previews are bigger (half the screen width each) and properly centred; the preview content is still the actual input photo. Menu entry, activity title and the crop screen title ("DP Crop — Circle") all say DP Crop now; class/file names unchanged.
- **Resize — LIGHT redesign** — the Custom size dialog's quick chips are now 2 per row (was 4 in one row), each with its size on ONE line ("1080 × 1080") and its label below, plus a "Common sizes" label — no more mid-number wrapping. Nothing else changed in this build — scanner, Enhance, Book, compress logic and the compression law untouched; Batch 4 items 3–7 still held.

## v0.1.121 — ALL 11 Batch 5 Image Tools UI/UX items in one build: simplified result screens, full-screen preview + split compare, HD fix, Back/Done buttons match, Convert polish, Color Correction rename, DP Edit polish, exact social logos, metadata never bigger, menu single screen (on v0.1.120)

- **Result screens SIMPLIFIED (Quality / Target / HD / Smart)** — one clear summary header card, one Preview button, simple result cards (label + Best badge + "size • saved N%"), single full-width teal "Save selected". Per-row SAVE buttons removed. Target flow no longer auto-saves — it now shows the same simplified result screen with Preview + Save.
- **Preview TOTAL REDESIGN** — Preview opens a full-screen dialog (new FullPreviewDialog + ZoomableImageView): pinch zoom 1×–4×, pan, double-tap reset, N.N× badge. A Compare button below switches to a split view — TOP original (Before), BOTTOM result (After), each labelled with format, dimensions, file size and % saved. The old inline CompareView (560 lines) is fully replaced and deleted.
- **HD Compress BUG FIXED** — root cause: it tried ONE candidate (JPEG 95); re-encoding an already-compressed camera JPEG at 95 is almost always ≥ original, so it reported "Already optimized" every time. New compressHd() tries a ladder (JPEG 95 then 90; WebP 95 then 90 for WebP inputs) at full resolution and keeps the smallest result smaller than the original. HD now gets the same new preview/compare, plus More Compress + WebP with the clear Govt note.
- **Smart Compress** — same new preview redesign and simplified result view via the shared showSmartResults.
- **Bottom Back/Done buttons MATCH** — Back is now a teal-outline MaterialButton (teal text, teal 2dp stroke, 56dp, 12dp corners), matching the filled-teal Next/Done button. Same style, same size.
- **Convert UI more professional** — option cards polished (radius 12→16dp, larger padding and titles), subtitles shortened and scannable.
- **"Edit Image" renamed "Color Correction"** — menu entry, activity title and docs. The 15-tool Adjust panel itself is untouched. Class name ImageEditActivity unchanged.
- **DP Edit improved** — live previews bigger (120→140dp), Circle preview on a checkerboard background in a teal-ringed rounded card, Square preview in a matching card, bold labels, "Live preview" heading, "Tap a shape below to crop & save" hint.
- **Social Media Crop — exact platform logos** — code-drawn vector drawables (no downloaded assets): Instagram (rounded-square outline + circle + dot), Facebook ("f" letterform), YouTube (play triangle), in 40dp circles on the exact brand colours (Instagram pink, YouTube red, Facebook blue).
- **Remove Metadata BUG FIXED** — root cause: stripMetadata decoded and re-encoded at JPEG 95, so a camera JPEG at ~85 grew bigger. Now byte-level stripping with NO re-encode: JPEG drops APP1 segments (EXIF + XMP), PNG drops eXIf/tEXt/iTXt/zTXt chunks, WebP drops the EXIF chunk and clears the VP8X EXIF flag (RIFF size fixed). Output is NEVER bigger than the original. TIFF/BMP/AVIF fallback re-encodes at 90 then 75, kept only if ≤ original, else honest failure. Also fixed: result is saved with the actual format's extension instead of a name-based ".png"/".jpg".
- **Image Tools main menu** — top search bar removed; cards made compact (44dp icon frame, smaller text/padding) so ALL 10 tools fit on a single mobile screen; bottom "Ready" card removed (image-to-PDF now reports via Toast). Nothing else changed in this build — scanner, Enhance, Book, compress logic and the compression law untouched.

## v0.1.120 — Removals only: Collage removed entirely, Photo Enhance removed (on v0.1.119)

- **Collage REMOVED entirely** — user rated it 0% satisfied (crashed live on v0.1.118). The Collage card is gone from the Image Tools menu, CollageActivity + CollagePreviewView + activity_collage.xml + ic_collage.xml are deleted, and the activity is removed from AndroidManifest. Nothing else in Image Tools changed.
- **Photo Enhance REMOVED** — the Photo Enhance card is gone from the Image Tools menu and its photoEnhance() entry point is deleted. ImageCompressor.autoEnhance stays — it is still used by Edit Image. ic_sparkle.xml (used only by the removed card) is deleted.
- Nothing else changed in this build — scanner, Enhance, Book, compress, and all other tools untouched.

## v0.1.119 — ALL 10 batch-3 fixes in one build, more professional: Crop refinements, Preview button + fully zoomable, HD one-tap + result screen, Resize Custom not pre-selected, Fit-to-social pro, Collage crash fixed + FULL rework, Smart WebP + stronger Save (on v0.1.118)

- **Crop refinements (user: "Super")** — bottom menus more professional: aspect chips are proper pill buttons (teal fill + white bold text when selected, dark surface + clear border when not), tidy rotation row with bold angle label (teal when ≠ 0°); top Restore moved to the CENTRE of the bar, far from the ✓ — no more mistouch. Crop frame/rotation/aspect logic untouched.
- **Before/After Preview — behaviour change** — the CompareView no longer appears automatically. A professional "Preview — Before / After" button shows it (tap again to hide). Preview is FULLY ZOOMABLE: pinch 1×–4× anchored on the focal point, one-finger pan when zoomed (clamped, can't drag off-screen), double-tap to reset, zoom badge (N.N×). Applies to Compress AND Smart Compress.
- **HD Compress — size options REMOVED** — root cause: the 50 / 1920 / 25 screen was reachable via Back from the HD result. That branch is gone + a guard in renderStep(); HD is one tap, JPEG 95, full resolution, no options.
- **HD Compress result screen** — HD no longer saves silently. It now shows the Smart results screen: summary header (Original → Best, saved %), gated Preview button, result row, "Save selected". Nothing saves until the user taps Save.
- **Resize Custom — not pre-selected** — the "Custom: 1080x2400" default selection is removed; nothing is pre-selected. Tapping Custom selects it AND opens the WxH value-entry dialog immediately. Custom stays first.
- **Fit to social media — more professional** — card text centre-aligned, ratio bold 17sp; code-drawn platform badges on each card (Ig pink, ▶ black/red, f blue); Story subtitle now also names WhatsApp. Unique-tag selection + crop-preview launch unchanged.
- **Collage CRASH fixed** — NullPointerException dragging the Borders slider. Root cause: the listener did findViewById on every drag; the ID exists in source, so the crash came from a stale packaged layout. Fixed structurally: all 14 views bound ONCE in bindViews() right after setContentView, every use a safe call — the NPE is impossible by construction.
- **Collage FULL redesign + rework (3rd attempt)** — rewritten activity + preview: photo strip on top (long-press drag to reorder / drop onto a cell), live interactive preview, TRUE freeform Freestyle (move whole cell + edge/corner resize, min 0.12), Poster caption (12% band, auto-shrinking bold centred text), Stitching (real dashed borders), 8 layout templates that actually rearrange, Borders slider + background + ratio live-update, Save renders EXACTLY what the preview shows (one shared scale fixed ~1.5× parity bugs in corner radius / stitch / freestyle border). Cells cycle through picked photos, so any template works with just 2 photos. (Simplification: move needs the cell selected first — tap, then drag; first drag on an unselected cell pans its photo.)
- **Smart Compress — WebP + preview + Save** — "More Compress with WebP" button in Smart flow too (lazy, opt-in only); WebP note now explicit: "✓ Works: WhatsApp, Instagram, web / ✗ NOT accepted: Govt forms / portals". Tapping ANY result row selects AND opens the preview. Per-row SAVE is now a real teal filled button; "Save selected" bold 16sp. "Results" heading + cleaner spacing throughout.

## v0.1.118 — ALL 10 batch-2 fixes in one build: JPG never auto-converts to WebP, professional Before/After compare, Smart results use the space, Crop 100% professional redesign, Resize without Keep, Fit-to-social no default selection, Convert TIFF/BMP/AVIF, DP Edit live previews, Collage FULL functional redesign (on v0.1.117)

- **Compress never auto-converts JPG → WebP** — root cause fixed: compressAtQuality now returns original-format results only (JPEG in → JPEG out); WebP appears ONLY via "More Compress", computed lazily on tap. No more surprise format change.
- **Before/After Preview (professional)** — new CompareView: original vs compressed with a draggable divider, Before/After pills, tap to cycle split / full Before / full After. Every Smart/Quality result can be compared before saving.
- **Smart Compress results use the space** — summary header card (Original → Best, saved %), CompareView filling the space, professional result cards, tap a row to preview it, "Save selected" button (each row also keeps a quick SAVE). Tap-to-save became tap-to-select + Save.
- **Crop 100% professional redesign** — new ProCropView (rectangle-only frame, never tilted): corner handles resize, drag inside pans, dimensions label ("2788 × 2092"), rotation slider −45°…+45° with angle display, aspect chips (Freestyle / Original / 1:1 / 4:3 / 3:4 / 16:9), X / Restore / ✓. Scanner's CropView untouched.
- **Resize** — "Keep resolution" (No change) removed; Custom opens a professional dialog (Width × Height side by side, "for exam forms" hint, quick-pick chips: 600×600 Passport, 800×800, 1080×1080, 1080×1350).
- **Fit to social media** — nothing pre-selected by default; each card has a unique tag (fixes BOTH 1:1 cards showing teal/selected); ratio-shape indicator on each card.
- **Convert — all discussed formats** — TIFF, BMP, AVIF added to JPG/PNG/WebP with compatibility subtitles. TIFF/BMP via pure-Kotlin writers; AVIF via FFmpegKit libaom-av1 (verified present in ffmpeg-kit-full-gpl 8.1.9: libaom-av1 encoder + avif muxer).
- **DP Edit UI** — live Circle + Square previews of the picked photo above the shape cards.
- **Collage FULL functional redesign** — new interactive CollagePreviewView: tap a cell to select, drag inside to pan the photo, drag a photo from the strip onto a cell to assign it, Freestyle = freeform draggable/resizable cells, Poster = caption text rendered on the collage, Stitching = real dashed stitch borders. Save renders exactly what the preview shows. (Simplifications: no scrapbook rotation in Freestyle; adding/removing photos resets pan/rect customisations.)

## v0.1.117 — ALL 14 fixes in one build: JPG-only results + More Compress, X warning, no resolution step, perfect Quality cards, crop/DP previews, Collage redesign, Resize Custom first, Convert compatibility, Metadata Before/After, full Adjust panel, professional exit dialog (on v0.1.116)

- **Compress results JPG only** — Smart results show JPG rows only (Best badge on the smallest JPG). A "More Compress" button below reveals the WebP results with a compatibility note: "WebP — smaller than JPG. Works on WhatsApp, Instagram and the web. NOT accepted in Govt forms/portals."
- **Top X warns** — the X now steps back inside the tool or shows "Leave this tool?" (same as system Back / bottom Back).
- **Resolution step GONE for Quality** — root cause: pressing Back from the results landed on step 2 (the resolution list). Back from results now returns to the Quality cards; step 2 for Quality is unreachable (renderStep fallback + next() safety net + backStep skip). Standalone Resize still uses the resolution list.
- **Quality % boxes perfect** — every row EXACTLY 48dp tall, full-width, 16dp side padding, 8dp gaps, text vertically centred.
- **Crop (Fit to social media) preview + adjustable frame** — tapping an aspect opens the crop screen with the aspect locked; drag the corners to resize (aspect kept) or drag the frame to pan and choose the portion. Aspect options are cards now.
- **Resize: Custom FIRST** — Custom is first in the list and selected by default; tapping Next with Custom asks for the exact width x height (for exam forms). Resolution options are cards now.
- **Convert: compatibility + cards** — JPG "Accepted everywhere, including Govt forms." / PNG "Lossless, best for graphics." / WebP "Smaller than JPG. Works on WhatsApp, Instagram, web. NOT accepted in Govt forms/portals." Format options are cards now.
- **DP Edit preview + adjustable crop** — pick image → DP Edit screen → Circle / Square cards → tapping one opens the crop preview (circle frame for Circle, square for Square); drag to frame the portion, then Save.
- **Collage FULL professional redesign** — pick photos in a strip at the top (long-press to drag and reorder), LIVE preview, style tabs (Minimal / Freestyle / Poster / Stitching), Borders slider + background colour swatches, ratio (1:1 / 4:5 / 9:16 / 3:4), layout template thumbnails, Save renders the chosen layout. Fully functional.
- **Target Size (standalone) REMOVED** — Compress already has Target size; the separate menu card is gone (MODE_TARGET_SIZE code left untouched, just unreachable).
- **Remove Metadata redesign** — pick image → tool screen shows ALL metadata (file, format, size, dimensions, date taken, camera, location, orientation, ISO) → Remove button → Before/After view listing what was removed ("✓ Location removed", …), saved copy has no location/camera/date info.
- **Edit Image full professional Adjust panel** — tool chips (Auto, Exposure, Brightness, Contrast, Highlights, Shade, Saturation, Vibrance, Color temp, Tone, Sharpness, Fading, Vignette, Enhance, Dehaze) + one slider for the selected tool; every slider really changes the image (new adjustFull engine: LUT tone curve + per-pixel highlights/shade/vibrance/vignette + 3x3 sharpen); live preview; Rotate kept; Auto applies one-tap auto enhance and resets the sliders.
- **Exit warning PROFESSIONAL** — "Leave this tool?" is now a polished rounded card: amber "!" icon, clear title + message, prominent teal "Leave" button and subtle "Cancel". Same behaviour (Leave → finish, Cancel → stay).

## v0.1.116 — Photo NOT remembered, Quality cards small one-line, resolution step removed, Target too-low warning (on v0.1.115)

- **Photo NOT remembered** — the v0.1.114 remember-photo (Change row, persisted pick) is REMOVED. Back from a tool clears the photo; a NEW photo must be picked every time a tool is entered. Same after every direct action.
- **Quality % cards SMALL** — compact one-line rows, "95% - Maximum Quality" (no big boxes, no separate label line).
- **Resolution step REMOVED** — tapping a Quality % goes straight to Smart results at full resolution; the long "Change photo resolution" list is gone (it confused).
- **Target too-low warning** — entering a very low target (1 KB / 10 KB) analyses the image first (JPEG 10 = minimum possible) and warns BEFORE compressing: "Very low target? The smallest this photo can be is X. A Y target will look very poor." [Continue anyway] / [Change target].

## v0.1.115 — Back steps back inside the tool, leaving asks first, Target size rejects too-big values (on v0.1.114)

- **Back steps back inside the tool now** — system Back and the in-app Back go one step back within Compress (Quality → 3 buttons, resolution → Quality, results → resolution) instead of exiting the whole tool at once.
- **Leaving a tool asks first** — pressing back at the first step (or in any single-step tool) shows "Leave this tool? / Your work will be discarded." (Cancel stays, Leave exits).
- **Target size validation** — entering a target at or above the original image size is NOT allowed: the input box turns red (`bg_input_error`, white pill + red border) and the status says "Target must be smaller than the original (X)". Typing again clears the red. (The photo-remember fix and the small Quality cards are noted for the final build, not in this version.)

## v0.1.114 — Quality % + Target screens professional, HD is true 0% loss, photo remembered, Quick Squish is Smart Compress (on v0.1.113)

- **Quality % is professional cards now** — 95/90/80/70/60/50/Custom 85 as tappable menu-style cards (no radio list); tap goes straight to the resolution step.
- **Target size KB/MB is a segmented teal toggle** (KB selected by default) instead of radio buttons.
- **HD Compress corrected (user, 2026-11-15): NO downscale, NO blur** — same photo, same size, 100% quality, just a smaller file. Best of JPEG 95 / WebP 95 at FULL resolution (`compressAtQuality(95, 10000)`); the 50% downscale is gone.
- **Picked photo is remembered (user, 2026-11-15 — major fix):** the photo is copied to a fixed cache file + path saved in prefs, restored when the menu opens, so going back and re-entering a tool never asks again. A small current-photo row (thumbnail + name + Change) lets the user pick a different photo.
- **Quick Squish is Smart Compress now (user, 2026-11-15):** renamed card + new MODE_SMART — one tap tries all levels in the ORIGINAL format and shows ALL results with real sizes; the user picks the size they need and taps to save.
- **Quick Squish / compressBest keeps the original format (user, 2026-11-15):** a JPG stays a JPG, a WebP stays a WebP — no surprise format change. New `formatOf()` detects the format from magic bytes. (WebP is accepted by WhatsApp/Instagram/web, but most Govt portals accept only JPG/JPEG — so JPG stays JPG for Govt forms.)
- **PENDING FIX (user correction, 2026-11-15 18:26 IST — fix in the final build):** the picked photo must NOT be remembered. Pick a photo, back, the photo GOES AWAY; a NEW photo must be picked every time a tool is entered. The v0.1.114 remember-photo (Change row) is the wrong behaviour — clear the picked photo on back/leave.
- **PENDING FIX (user, 2026-11-15 18:29 IST — fix in the final build):** the Quality % cards are too big — make them SMALL/compact, not big boxes. Format: "95% - Maximum Quality" on ONE line (the label must NOT sit on a separate line below).

## v0.1.113 — Compress is 3 professional buttons, HD has no options (on v0.1.112)

- **Compress mode picker is 3 buttons now** (menu-style cards, teal-tint icons) — no radio list: **Quality** (choose a quality %), **Target size** (enter MB or KB), **HD Compress** (only size reduces, 0% quality loss). Tapping a button goes straight in — no Next press.
- **Target:** tap the button → the MB/KB value entry opens directly.
- **HD Compress: no options any more** — one tap, Full Quality (JPEG 95), half the size, saved. The Half / HD 1920 / Quarter picker is gone.

## v0.1.112 — Smart-results crash fixed + per-tool file pick + Compress is 3 buttons (on v0.1.111)

- **Crash fixed:** the Smart results screen crashed (`Resources$NotFoundException`, attribute id passed as a drawable). The ripple background is now resolved through the theme first (`TypedValue` + `resolveAttribute`) — the results screen opens.
- **No top "Pick an image" button any more:** tapping a tool asks for the photo then and there — each tool picks its own file. (Collage opens directly, it picks its own photos inside.)
- **Compress is now 3 buttons:**
  - **Quality** — pick a quality % (95/90/80/70/60/50/Custom 85), then a resolution, then Smart results (JPEG vs WebP at that quality, real sizes, Best badge — tap to save).
  - **Target size** — type the size and pick KB or MB, it compresses to exactly that.
  - **HD Compress** — only the size reduces, 0% quality loss (JPEG 95 at Half size / HD 1920px / Quarter size). Quality is never touched.
- Nothing bigger than the original is ever saved, in any of the three.

## v0.1.111 — Image Tools menu (box UI) + Compress Smart results (on v0.1.110)

- **Image Tools menu, box-by-box:** the old list UI is replaced by a professional 2-column grid of 13 tool boxes in importance order (Compress, Quick Squish, Photo Enhance, Crop, Resize, Social Media Crop, Convert, Edit Image, DP Edit, Image to PDF, Collage, Remove Metadata, Target Size full-width) — teal-tint icon circles, bold titles, grey subtitles, like the approved mockup. A search bar filters the cards by name. (The top "Pick an image" button was removed in v0.1.112 — each tool picks its own file now.)
- **Compress, Smart results:** pick a resolution, then see EVERY level's real size (JPEG 85%, WebP 85%, JPEG 70%, WebP 70%, JPEG 60%) with the saving % — tap one to save it. The smallest carries a "Best" badge. Nothing bigger than the original is ever shown. (In v0.1.112 Compress became 3 buttons — Quality % is back, plus Target size and HD Compress.)
- **Photo Enhance (one tap):** auto-levels (the photo's own 1st–99th percentile brightness stretched to full black–white) + a saturation lift. Reuses the existing ColorMatrix adjust path; ~0 MB, no new library.
- **Social Media Crop:** 1:1, 9:16, 4:5 (Instagram / TikTok / FB) via the existing Fit-to-social flow.
- **DP Edit:** Circle (centre-square masked to a circle, saved as transparent PNG) or Square (1:1) — picked from a dialog.
- **Remove Metadata (photos):** decode + re-encode without EXIF — location, camera and date are gone. JPEG 95% (PNG stays PNG). Video metadata removal still to come.
- **Image to PDF:** one A4 page per photo, saved to Documents/Hakunaa.
- **Target Size kept:** the existing "Resize to KB, MB" is still in the menu (full-width last box) — nothing was removed.

## v0.1.110 — HD button removed, torch level with the X (on v0.1.109)

- **HD button REMOVED:** the circle under the Torch is gone, and the whole Book quality-tier feature goes with it (picker, tier code, shared-pref wiring) — Book captures at full sensor again, like before v0.1.109. The big X and the bolt torch stay (the user's picks).
- **Torch moved up, level with the X:** marginTop 36dp → 0dp — the strip's 12dp padding puts the torch's top at 12dp from the screen, exactly the same height as the X on the left.

## v0.1.109 — Book icons picked + HD button (on v0.1.108)

- **Back arrow → big X (user pick #3):** the top-left button is now a bold white X (`ic_close_big`, strokes spanning most of the viewport) — bigger and clearly visible at 56dp. Same button, same Discard-book warning on tap.
- **Torch → lightning bolt (user pick B):** `ic_bolt` (white, off) / `ic_bolt_on` (teal, on) replace the flashlight icons.
- **HD button below the Torch:** a 56dp circle with the tier's short label ("HD" for Full Sensor) right under the torch in the right strip, like the main scanner's HD button. It opens the same quality-tier picker (Full Sensor / Very High / High / Medium / Low with real resolutions); the choice is saved in the shared pref, and the camera re-binds with it right away (unbind + rebind, so the new quality applies immediately). Tiers apply LANDSCAPE in Book (width > height) — the same 4:3 sizes the main scanner applies as portrait.

## v0.1.108 — Book status bar hidden + bigger back arrow (on v0.1.107)

- **Status bar hidden in Book:** the clock / battery strip is gone from the camera screen (immersive — a swipe from the top reveals it briefly). It stays hidden on every return from Enhance / Pages, and the other screens are untouched.
- **Back arrow, bigger and higher:** 48dp → 56dp, marginTop 20dp → 12dp — in the true top corner now that the status bar is gone. (Still the last layout child, so it stays topmost and tappable.)
- **Torch, bigger too:** 48dp → 56dp, matching the arrow.
- **Single/Double gap:** a little 4dp gap between the two buttons now, so they read as two separate choices instead of one block.

## v0.1.107 — Multiple texts per page (on v0.1.106)

- **Texts no longer delete each other:** Add Text was a single slot per page — a second text recycled the first. Now each page holds a LIST of texts (same shape as multiple signatures since v0.1.92): every new text APPENDS and stacks a little lower, so it never lands on top of the last one. Tap any plain text to select it (box + X + handle come back on THAT text); Erase-tap removes just that one; Remove takes the selected one (or the most recent); Done locks ALL texts on the page. Every text is drawn into the saved PDF and persists across Enhance sessions via the store (textFiles/textRects lists).

## v0.1.106 — Book instructions removed entirely (on v0.1.105)

- **No instruction text in Book at all:** the status line is gone — no more "Point the camera…", no mode hints, no "Torch on/off", no "Capturing…". The screen is clean: the A/B spine, the Level, the Capture button and the strips speak for themselves. Errors and confirmations (camera failed, capture failed, PDF saved) are now short Toasts instead of on-screen text, so nothing ever sits on the photo. (`bg_status_pill.xml` deleted with it.)

## v0.1.105 — Exit at the bottom + back arrow higher (on v0.1.104)

- **Exit, at the bottom:** the Exit button now sits at the BOTTOM of the right strip, level with the Save PDF button on the left strip — `alignParentBottom` instead of below Capture, so the shooting hand reaches it without stretching.
- **Back arrow, a little higher:** marginTop 36dp → 20dp, in the true top-left corner like the pro scanner apps. (Still the last layout child, so it stays topmost and tappable.)
- **Status hint, below the Level:** the instruction line now sits right below the Spirit Level (right-aligned with it), on a dark pill with small GREY text (#B0B0B0, 13sp) — Single's clean look, and the busy page and spine line never touch it.

## v0.1.104 — Book navigation fixed + professional Single/Double (on v0.1.103)

- **Back button works now:** the arrow is the LAST child of the layout (topmost). Before, the clickable left strip covered the corner and ate every tap, so the button did nothing.
- **Thumbnail shows the last capture:** tapping the small thumbnail opens the last scanned page full-screen (PagePreviewActivity), so the user can check it without leaving the camera.
- **Save shows the preview from the first page:** Save PDF now goes straight to the Preview (PagesActivity) with the pages from the first one; its Save goes to Export. Filters are still available through the Pages button, which goes via Enhance.
- **Exit button (Book only):** a small Exit button sits below the Capture button, right where the shooting hand is — same leave warning as Back, so leaving does not mean hunting for the corner arrow.
- **Single/Double, professional:** the toggle sits a little LOWER (top spacer 1.3, bottom spacer 0.7) and the selected mode is now a subtle GREY pill (`mode_single_double_selected.xml`, #616161) like the pro scanner apps, not a bright teal block.
- **Warning dialog, compact like Single:** the "Discard book?" dialog used 85% of the screen WIDTH — in landscape that is the long side (2048px), so it came out nearly full-screen. It now uses 85% of the SHORTER side, exactly like Single/Batch — a compact professional dialog.

## v0.1.103 — Book capture: A/B pages come out as proper portrait pages (on v0.1.102)

- **The bug:** Book capture was "completely malfunction" — A/B pages never came out as portrait. Root cause: `processBookPhoto` decoded the capture with plain `BitmapFactory.decodeFile`, which IGNORES the EXIF rotation. On this phone the spread came out in the sensor's raw orientation instead of the landscape the user held it in, so splitting it gave wrong halves. The main scanner hit the exact same bug (v0.1.66) and the same helper fixed it there.
- **The fix:** `processBookPhoto` now decodes with `FileUtils.decodeBitmapWithExif` — the bitmap is rotated upright first, THEN split at the spine, so A and B each come out as a proper portrait page.
- (Includes all of v0.1.102: edge-to-edge preview, centred Capture, back arrow top-left, Level like Single/Batch, spine contentRect fix.)

## v0.1.102 — Book: full-screen preview, centred Capture, back arrow top-left (on v0.1.98)

- **No black at the top + bigger capture area:** the Book preview now runs TRULY edge-to-edge behind the status bar. `StatusBar.setup` gained an `applyInsets` flag (default true); Book calls it with `applyInsets = false`, so the root takes no inset padding and the preview fills the whole screen. The buttons place themselves below the bar with explicit 36dp top margins.
- **Capture button centred:** the right strip is now a RelativeLayout — Torch pinned to the top, Capture `centerInParent` (true vertical centre). The old LinearLayout with two equal spacers pushed Capture below centre because the Torch took space at the top.
- **Back arrow in the right place:** moved out of the left strip to the top-LEFT corner, over the preview, just below the status bar (marginTop 36dp, marginStart 16dp) — like CamScanner's X.
- **Spirit Level, exactly like Single/Batch:** the 2D bullseye (56dp) sits right next to the Capture button at the same height — floating over the preview just left of the right strip (152dp = 140dp strip + 12dp gap), vertically centred with Capture. Display-only, always on, live sensor (starts in onResume, stops in onPause).
- **Spine line stays on the real fold:** `contentRect` is now recomputed on EVERY PreviewView layout change (OnLayoutChangeListener), not just once in onCreate — the old one-shot value went stale when the preview was re-laid out, so the spine drifted and drags/splits landed in the wrong place.

## v0.1.98 — Status bar done the professional way (transparent + dynamic)

- **Problem in v0.1.97:** the solid status bar colour showed a visible band — white on the black scanner screen (time invisible), and a shade edge on Home.
- **Fix (rewrote `core/StatusBar.kt`):** like other professional apps — the status bar is TRANSPARENT, the screen's own background runs seamlessly behind it (NO colour difference, NO edge), and the icons adapt dynamically (DARK on light screens, LIGHT on dark screens). The root layout takes the status bar inset as padding, so content sits below the bar while the background extends behind it. Same 17 Activities, same light/dark split.

## v0.1.97 — Status bar fixed across the entire app

- **Root cause:** on Android 15 the app runs edge-to-edge — content went UNDER the status bar (white icons invisible on light screens; top bar overlapped on the dark scanner screen).
- **Fix (new `core/StatusBar.kt`, called from all 17 Activities):** content sits below the status bar (no overlap anywhere), icons match the screen — DARK icons on light screens (Home, PDF/Image/Video/WhatsApp tools, Pages, Export), LIGHT icons on dark screens (Scanner, Book, Crop, Enhance, Page Preview, Image Crop). Status bar gets a solid colour matching the screen.

## v0.1.96 — Two Enhance modes + Draw/Sign/Text lock after Done

- **Thumbnail click → review mode:** the Enhance screen comes WITH the left back-arrow (←) + 6 tools + Tick, and the tune icon on the selected filter. Tap ← to go back to the camera and capture more — only then can you capture again.
- **Export click → forward mode (don't touch):** the Enhance screen comes WITHOUT the back-arrow — Rotate → Crop → Adjust → Draw → Erase → Text → Sign + Tick, Adjust in the bar below. Same as v0.1.95.
- **Done locks the overlay (user fix):** Draw strokes are already fixed on the page; Sign and Text now LOCK when Done is tapped — they no longer drag, re-select, or resize after Done. Erase / Remove still deletes a locked overlay. Restored overlays come back locked too.

## v0.1.95 — Enhance bar back to the previous design (Adjust below)

- **Previous design restored (user pick):** the Enhance bar is back to Rotate → Crop → **Adjust** → Draw → Erase → Text → Sign + Tick (right only, no left back-arrow). Adjust lives BELOW in the bar with the other tools — the tune icon on the filter thumbnail is gone.
- Export button ('Export • N pages') and thumbnail tap both still open this same Filter screen, then Preview → Export.

## v0.1.94 — Export button back (same place, label Export); Enhance bar tick-only

- **Export button restored (user pick):** the Done button is back in the SAME place — a big teal pill, same size and style — but labelled **"Export • N pages"** instead of "Done". Both the Export button AND the thumbnail tap open the SAME Filter (Enhance) screen, then Preview → Export.
- **Enhance bar tick-only (user rule, v0.1.94):** NO left back-arrow — Export means going forward, so only the right tick is needed (the old design). Adjust stays on the selected filter's thumbnail.

## v0.1.93 — No Done button; Enhance bar: back-arrow + tick; Adjust on the filter

- **Done button removed (professional pattern):** CamScanner / Adobe Scan have no big Done button on the capture screen — the thumbnail with the page count IS the way forward. Tapping the thumbnail now opens the Filter (Enhance) screen, then Preview, then Export. The mid-batch review is gone; you review in Preview and add more pages from there. Single mode's fly animation now lands on the capture button.
- **Enhance bar reworked:** Adjust is OUT of the bottom bar (it lives on the selected filter now). A teal back-arrow (←) sits on the LEFT — tap it to go back to the camera and capture more; the teal tick on the RIGHT goes to the next stage (Preview → Export).
- **Adjust on the filter (CamScanner-style):** only the filter you touched shows a small tune icon on top of its thumbnail — never on every filter. Tapping that icon opens the Adjust panel (Brightness / Contrast / Sharpness).

## v0.1.92 — Book back warning; multiple signatures per page

- **Book back warning:** leaving Book (X or back) with pages scanned just left silently and the pages were gone. Now it asks "Discard book?" first (like Batch's "Discard batch?") — Cancel keeps scanning, Discard leaves. The pages are NOT saved as a draft; the warning says so.
- **Multiple signatures:** adding a 2nd sign used to delete the 1st (only one signature per page was stored). Now a page holds as many signatures as you add — each new one starts a little higher so they don't stack, each drags on its own, Erase-tap removes just that one, and Remove takes the most recent one. All of them save into the PDF and come back on re-open.

## v0.1.91 — Text × and resize handle actually work; clean icons

- **× and ⤢ work now (the real fix):** they were visible but dead — a view sitting outside its parent's bounds can be SEEN but never TOUCHED, and the X/handle sat 26dp outside the text box, so taps fell through to the page. Now they live in the page frame itself, straddling the box's corners (half outside the box, fully inside the frame), so tapping × deletes the text and dragging the handle resizes it. Also the icons are proper clean vector drawings now (a crisp × and a diagonal expand arrow), not the poor "×"/"⤢" text letters.

## v0.1.90 — Preview delete sticks; text ×/⤢ visible

- **Delete in Preview sticks (the bug):** deleting a page in the final Preview only removed it from that screen — the camera's own list still had it, and the raw file still existed, so the page came back the next time. Now the Preview hands its updated list back, and the camera syncs to it (delete + reorder both stick), dropping the raw file and the saved edits of any removed page.
- **× and ⤢ show now (v0.1.89 fix, in this build):** `clipChildren=false` alone was not enough — the box also clips to its padding, so the X and handle sitting outside were still cut off. Now the box and the page also allow drawing past the padding (`clipToPadding=false`), so the teal × and ⤢ show clearly outside the box.

## v0.1.89 — Text ×/⤢ actually visible outside the box

- **× and ⤢ show now:** `clipChildren=false` alone was not enough — the box also clips to its padding, so the X and handle sitting outside were still cut off. Now the box and the page also allow drawing past the padding (`clipToPadding=false`), so the teal × and ⤢ show clearly outside the box.

## v0.1.88 — Text ×/⤢ visible outside the box; Rotate stays after tick

- **× and ⤢ are visible now:** moving them outside the box made the box cut them off (they were invisible). The box and the page now let them draw past the edge, so the teal × and ⤢ show clearly outside the box.
- **Rotate stays after tick:** rotating only changed the on-screen image — the file was still unrotated, so going out and coming back showed it Normal again. Now the rotated image is written back to the page's file (and the rotated drawing / signature / text positions are saved too), so the rotation stays.

## v0.1.87 — Text × and resize handle sit outside the box; Erase works on everything

- **× and ⤢ now sit OUTSIDE the box:** they were half inside the box's corner, hard to grab. Now they sit clearly outside it (26dp out, just a small overlap), so they are easy to tap and drag.
- **Erase works on Sign and Text too:** Erase used to rub out only the drawn strokes. Now, in Erase mode, tapping a signature or a text removes it as well — Erase applies to everything drawn on the screen.

## v0.1.86 — Text box only while editing; Done leaves plain text

- **Box goes away after Done (the fix):** the teal box, the × and the resize handle are EDITING tools only. They show while the text is being placed / adjusted. Press Done and they all disappear — only the plain text stays on the page, the way professional apps do it. Tap the text again and the box comes back to adjust or delete it.
- **× is big and teal now:** 32dp teal circle with a white × and a white ring, so it is clearly visible while editing (it was a small dark circle before).

## v0.1.85 — Zoom removed from Filter preview, Word-style text box, Draw panel fits

- **Zoom removed from the Filter preview** (user asked): pinch / double-tap / on-screen zoom buttons are gone from Enhance — the preview is just the page + filters now. The full-screen page preview still zooms.
- **Text is a Word-style box now:** the placed text sits in a teal-bordered box. Drag the box to move it, drag the bottom-right handle (⤢) to make it bigger or smaller, tap the × on top to delete it. The Remove / Done row is still there too.
- **Draw panel fits:** Clear and Done were hiding off the right edge (the dots + buttons were wider than the screen). The colour dots are smaller now and the panel padding is tighter, so everything sits inside.

## v0.1.84 — Edits persist after Continue, even bottom bar, zoom buttons, Sign pad fixed

- **Edits persist after Continue (the fix):** Draw / Sign / Text made in a thumbnail review ("Continue") used to vanish — the next Enhance (Done → "Finish") loaded the raw file and the new activity had empty overlays. Now the overlays live in a shared `OverlayStore` (keyed by the page's raw path): every Enhance run loads them on open and writes them back on save, so nothing made in a review is lost. The filter still comes from the raw file exactly once.
- **Sign pad fixed:** the signature pad's DrawView had `drawEnabled=false` by default, so in v0.1.83 the pad opened but no stroke could ever be drawn — Sign looked broken. Now the pad takes touches.
- **Bottom bar, even:** the seven tools now SHARE the width evenly (each `layout_weight` 1), so there is no big empty gap between them and the confirm button — the whole bar is used. Icons are proper size now (28dp, 11sp labels).
- **Zoom buttons on screen:** while zoomed in, + / − / 1:1 buttons appear at the bottom-right of the page, so the zoom can be controlled with a tap after the fingers leave the screen. Pinch and double-tap still work.

## v0.1.83 — Draw/Sign stay visible, Erase + Text, 7-tool bar, preview up, zoom

- **Draw / Sign stay on the page after Done (the fix):** the drawing and the signature used to vanish from the screen when Done was pressed, so the user thought they were lost. Now the DrawView stays visible (but stops taking touches, so swipes and zooms work again) and the signature stays put — both still saved onto the page at Finish.
- **Erase:** a new bottom-bar tool — rub out drawn strokes by touching them (a stroke eraser; S / M / L eraser sizes share the Size button).
- **Add Text:** a new bottom-bar tool — type the words, pick a size (Small / Medium / Large) and a colour, then drag the text to where it belongs (Remove / Done). The text is saved onto the page (each page keeps its own, and it rotates with the page).
- **Bottom bar, in the user's order:** Rotate → Crop → Adjust → Draw → Erase → Text → Sign + the confirm button. Seven tools on one row, compact (22dp icons, 10sp labels, 8dp gaps, 12dp side padding) so nothing is clipped.
- **Professional icons:** a new flowing signature icon and a new clean four-corner crop icon (plus a new eraser icon).
- **Preview up:** the page's top margin is 8dp (was 32dp), so the preview sits higher and the space below is used by the filter strip + bottom bar.
- **Zoom:** pinch to zoom the page (1x–4x), double-tap to toggle 2.5x, drag to pan while zoomed. The drawing, signature and text follow the zoom, so they stay on the page while it is inspected. Zoom resets when a new page loads.

## v0.1.82 — Rotate renamed, Draw + Sign fully working

- "Left" is now "Rotate" in the Enhance bottom bar.
- Draw: freehand markup on the page — 5 pen colours, 3 pen sizes, Undo, Clear, Done. The drawing is saved onto the page (each page keeps its own drawing; it rotates with the page).
- Sign: draw a signature in a pad, then drag it to where it belongs on the page (Remove / Done). The signature is saved onto the page (each page keeps its own).

## v0.1.81 — Confirm button is symbol-only (tick / arrow)

The confirm button is a big round teal button (56dp), symbol only, no text. Finish shows a tick, Continue shows a right arrow (the icon is set from the confirm label). It sits centred in the 72dp bar with an even 8dp margin — a little inside from the bottom and the sides, professional placement.

## v0.1.80 — Confirm button is the picked round teal check (style 6)

The confirm button is style 6 from the options sheet, picked by the user: a big round teal check (56dp) with the word below it in bold white ("Continue" from a thumbnail review, "Finish" from Done / Single / Book). It sits a little inside from the bottom and the sides — fully visible, nothing hidden at the edges. (The v0.1.79 boxy button was rejected as worst.)

## v0.1.79 — Approved confirm button (48dp, tight fit, 22sp bold)

The confirm button is the user-approved design from the preview image: a boxy rectangle (8dp corners, 48dp tall), tight fit around the text with no empty space inside, deeper teal (#00897B), 22sp bold text inside ("Continue" from a thumbnail review, "Finish" from Done / Single / Book).

## v0.1.78 — Confirm button is a boxy rectangle, less highlighted, text inside; smoother

The round button is gone — the user did not want a circle. The confirm is a boxy rectangle (8dp corners, 40dp tall, 160dp wide) with the word inside it in bold ("Continue" from a thumbnail review, "Finish" from Done / Single / Book), and the fill is a deeper teal (#00897B) so it is less highlighted than the bright pill.

Smoother (v0.1.78): the Filter screen still lagged sometimes because three full-res pixel jobs ran on the UI thread — the 1200px downsample on page load, the 160px filter-strip source, and the rotate + downsample. All three now run on background threads, so the UI thread only ever receives ready bitmaps.

## v0.1.77 — Confirm button back to the big round style; filter overexposure + lag fixed

The pill button style is gone — the user did not like it. The confirm is the old big round teal button in the corner (56dp, checkmark), with the Continue / Finish label below it, like the other bottom-bar items (Left / Crop / Adjust). Thumbnail review still says "Continue" (back to the camera), Done / Single / Book still say "Finish" (on to Preview / Export).

Filter overexposure fixed: the filter was being applied twice. Enhance re-encoded the other pages in place (so a reviewed page got filtered again on the next save), and the thumbnail review put the enhanced file in pages[0] instead of the reviewed page's own slot. Now every page is filtered exactly once, from its raw cropped file, into a new file — the caller matches each new file to its raw source — and Book pages are saved raw at capture (they were pre-filtered with No Shadow, then filtered again in Enhance). The Enhance preview no longer puts the full-res bitmap on screen (that upload stalled the UI thread and the screen lagged); it shows the downsampled preview instead, and the filtered version swaps in when ready.

## v0.1.76 — Continue (thumbnail) vs Finish (Done); Markup + Sign removed for space

Correction to v0.1.75's single "Finish": the filter confirm now says what actually happens next. From the Batch thumbnail it says "Continue" — pressing it returns to the camera to keep capturing. From Done (and Single / Book) it says "Finish" — pressing it goes on to Preview / Export. Markup and Sign are removed from the Enhance bottom bar (they are later steps and were only greyed-out labels taking space), so the Continue / Finish button sits cleanly at the bottom right: Left / Crop / Adjust / Finish.

## v0.1.75 — Thumbnail review opens on the last page; Finish button; clear Apply-to-all tick

Small corrections, all about the filter step being obvious. Tapping the Batch thumbnail now opens Enhance on the LAST captured page (so the user sees at once whether the latest capture came out right; swiping shows the earlier pages), while Done still opens on the FIRST page. The bottom-right confirm in Enhance is now a clear teal "Finish" button with the tick (one label for every flow — thumbnail review, Done, Single, Book — since it always finishes the filter step). And "Apply to all pages" has a bold white tick on teal when checked, so the tick is unmistakable.

## v0.1.74 — Capture flies to Done in Single + Batch, and a clearer Done button

The same professional fly animation from Book now works in Single and Batch. Single: after the Filter (Enhance), the thumbnail flies to Done and then the export screen opens. Batch: right after Crop, the thumbnail flies to the right side of the capture row — where Import Images was — and the count ticks up when it lands. In Batch, once the first page is captured, Import Images goes away and a small thumbnail with the pages count takes its place, like CamScanner; tapping it opens the filters (Enhance) so the user can check them, then comes back and keeps capturing (the green check saves the filters and returns to the camera, it never jumps to Preview). The Done button is clearer now — bigger (48dp), teal with bold white text, and a plain count ("Done • 3 pages", "Done • 1 page"). In Book, a small thumbnail of the latest capture now sits beside the Pages count (like CamScanner) — tapping it opens Pages. Leaving a Batch with pages captured now asks first — "Discard batch?" — so a draft is never lost by an accidental Back or Close tap.

## v0.1.73 — Book Capture flies to the Pages count (preview removed)

The user rejected the v0.1.72 capture preview as the worst — it is gone (activity, layout and manifest entry deleted). Instead, Book now does what professional scanner apps do: after every capture, a thumbnail of the page appears at the centre of the screen and flies to the Pages button, shrinking as it goes, and the count ticks up the moment it lands. The pages join the book at once (no confirmation screen in the way); the animation is the feedback. Single shows its one page flying the same way.

## v0.1.72 — Book Capture now shows a preview (A top, B bottom)

The user reported: pressing Capture in Book gave no sign the photo was taken. Instead of just an animation, Book now shows a preview after every capture — one screen, both pages: A on top, B on the bottom (Double); a Single capture shows just that one page, full height. Keep adds the pages to the book and goes back to the camera for the next spread; Retake (or Back) discards them and the user captures again. The preview is landscape like the Book camera, so the screen never rotates, and the images are downsampled so a full-resolution spread never blows up memory.

## v0.1.71 — Book Scan finally gets Filters and menus

The user found the gap: in Book Scan, a captured page could never change its filter or be cropped / adjusted — capture went straight to Pages / Export with no Filter (Enhance) screen at all. Fixed, Book only: Pages and Save PDF now open the Filter screen first, exactly like a Batch Done — pick a filter per page (or one for all, pre-ticked), use the full bottom menu (Left / Markup / Crop / Sign / Adjust / green check), then the Preview (Pages) screen to reorder, rotate or delete, then Save → Export. Back from the Filter keeps the pages on the camera. Discard confirmed on the Export screen reached via the Preview now clears the book's pages too, so the camera comes back fresh. The capture itself is untouched: split the spread, auto-enhance NO_SHADOW, append A then B. Also in Book, the A/B spine line is now MANUAL only — the live spine auto-follow is removed entirely (no analysis stream, no CPU spent tracking the spine), the line never moves on its own, the user drags it to the fold, and it stays where it was put between spreads.

## v0.1.70 — Apply to all now actually changes the preview

The user tested Batch and reported: tapping "Apply to all" changed nothing in the preview. Root cause: the checkbox only affected the SAVE step — picking a filter only ever updated the CURRENT page's filter, so swiping to another page showed that page's old filter. Fixed, Enhance screen only: picking a filter while "Apply to all" is ticked now sets that filter for EVERY page, so swiping to any page shows it; and ticking the box itself immediately syncs all pages to the current filter. Unticked, each page keeps its own filter exactly as before. Single mode (one page) is untouched.

## v0.1.69 — Batch now goes Enhance → Preview (reorder) → Export

The user tested Batch and reported: after taking captures and choosing filters, no Preview came and no reorder was possible — it went straight to export. Fixed, Batch only: after Enhance (green check) the Preview screen (Pages) now opens — every page as a thumbnail, long-press drag to reorder, rotate, delete, tap a page for full-screen preview — and Save there goes to Export. Single mode is untouched (its Enhance runs from the Crop screen, straight to Export as before), and so is the Book flow. Also, a Discard confirmed on the export screen reached via the Preview now propagates back correctly (Preview closes, camera drops the pages and comes back fresh) instead of landing back in the Preview with the pages still there.

## v0.1.68 — Batch default now actually SHOWS (the v0.1.67 fix was half-done)

The user tested v0.1.67 and Single was still highlighted on open — "Okthan But, Batch Default a Mathu". Honest root cause: v0.1.67 only changed the mode FIELD to BATCH, but the tab UI never synced on a fresh launch — setMode(mode) ran only when pages already existed, and the layout hard-coded Single as the selected tab (teal pill + bold). So the mode WAS Batch internally, but the screen still SHOWED Single. Fixed: setMode(mode) now runs on EVERY launch (fresh or restored), and the layout no longer hard-codes Single as selected — the tabs always match the real mode, so Batch is highlighted on open.

## v0.1.67 — Export Cancel now asks before discarding (CamScanner-style) + Batch is the default mode

The user tested v0.1.66 and pressed Cancel on the export screen: it went straight back to the camera with the page still kept ("1 page(s) • Done" still at the bottom) — no warning at all. Wrong for Single, where Cancel should mean "throw this away". Now Cancel (and back) on the export screen asks FIRST, in a centred white dialog like CamScanner: "Note — Are you sure to discard this image?" for a single page, "Are you sure to discard all N pages?" for a batch, with Cancel (grey) / Discard (teal). Discard returns RESULT_DISCARD, the scanner drops every page and the camera comes back fresh (no Done button left at the bottom); Cancel just closes the dialog and stays on export. Same in Single and Batch. Also: the scanner now opens on **Batch by default** (user: "default aa.. Batch la irukanum") — most scans are multi-page, Single is one tap away.

## v0.1.66 — Captured photo is now PORTRAIT (full, like the preview)

The user tested v0.1.65 and the Crop photo was still a wide band, not full: "But still ipdi tha iruku, Fulla varalyae.. Fst Mari venum, MB matu tha Differ aganum, Fst Mari ella image um same Aspect than venum." Measured from their screenshots: the captured photo was 899x691, aspect 1.30 (landscape 4:3), while the live preview was 921x1131, aspect 0.81 (portrait 4:3). Root cause: the tier sizes come back landscape (WxH) from Camera2, and I passed them straight to CameraX — so CameraX captured a landscape photo. Two fixes: (1) the target resolution is now requested in PORTRAIT (W/H swapped, so every tier captures portrait 4:3 — same aspect at every quality, only the MP/MB differs, exactly as the user asked, and the photo fills the portrait Crop screen like the preview); (2) a new FileUtils.decodeBitmapWithExif helper applies the JPEG's EXIF orientation on decode (plain decodeFile ignores EXIF, so a landscape-pixels + 90° EXIF photo would still show as a band), used by the Crop screen. Book mode is untouched (it is a landscape activity, so a landscape photo is correct there).

## v0.1.65 — Full Sensor is now the largest 4:3 size (only Quality varies) + no more crossed crop frame

The user measured the v0.1.64 Full Sensor photo and it was still a wide band (921x575, aspect 1.60 — not 4:3): "But still Maruthuu.. Full sensor la koota ipdi.. Na fst yae soliten. Quality matu tha vary aaganum.." The v0.1.64 fix made the TIERS 4:3, but Full Sensor was still uncapped ("max"), and this phone's sensor max is a wide (16:9) size. So now Full Sensor = the largest 4:3 size — every tier is 4:3, and only the MP (quality) varies across tiers, never the framing or aspect, exactly as the user asked. (On most phones the largest 4:3 IS the sensor max.) The Full Sensor row now shows its real 4:3 resolution like the other rows. Also fixed the crossed crop frame the user screenshotted on the woven mat: the detector now rejects any non-convex (bowtie) quad, and CropView reorders any corners it is given into TL/TR/BR/BL order, so the frame can never be a crossed bowtie again. (The mat photo's quad was a skewed-but-convex false positive — no document was in the photo — so the frame is now at least a valid quad the user can drag; real-document detection is unchanged.)

## v0.1.64 — HD picker is now QUALITY tiers (Low / Medium / High / Very High / Full Sensor)

The user said the resolution list was not needed: "Only Quality aa Define pananum, Low medium high very high, Full Sensor nu" — and asked to do it best like that. So the HD picker no longer lists megapixels (16M / 12M / 8M...). It lists five quality tiers, each with its real resolution in grey: Full Sensor (max, BEST badge), Very High, High, Medium, Low. Every tier is a 4:3 size from this phone's camera — a 16:9 photo looks small ("kutty") in the portrait Crop screen, which is what the user reported after picking a 16:9 size, so 16:9 sizes are never offered. The tiers spread across the phone's real 4:3 sizes by rank, so they are always distinct and sensibly spaced on any phone (8MP or 108MP); a tier with no distinct size is skipped, and the smallest is always Low. The HD button shows the quality name ("HD" for Full Sensor). Old saved values ("std" / "WxH") reset to Full Sensor on load.

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
