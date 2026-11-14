package com.hakunaa.app.scanner

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.roundToInt
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * PDF Scanner — professional document scanner, 100% on-device.
 * CamScanner-style flow:
 *
 * 1. Camera (this screen): full-screen live preview, flash + HD
 *    toggles, Single / Batch mode, big capture button, Import
 *    Images / Import Files. AUTO capture: when a document is held
 *    steady in the frame, the app captures it by itself — the way
 *    professional scanners do it (toggle AUTO in the top bar).
 *    While it is held steady a ring around the capture button
 *    fills smoothly; full means capture. The hold time is
 *    adjustable in the menu (2 / 3 / 5 seconds, default 3).
 *    The live edge frame (EdgeOverlayView) draws the document's
 *    real detected corners on the preview, following the page.
 *    Single-page detection: in an open book the detector splits
 *    at the spine and keeps only the biggest page — unless both
 *    pages form a full spread, which it keeps whole.
 * Tap to focus, the way a real camera works: tap anywhere on
 *    the preview and the camera focuses there and meters the
 *    light there — tap a dark corner and it brightens, tap a
 *    bright page and it darkens. A focus ring marks the spot
 *    and a brightness slider appears right beside it (drag up
 *    for brighter, down for darker); no separate settings.
 * 2. Crop (CropActivity): draggable teal corner handles, Auto
 *    Detect, rotate Left / Right, Next.
 * 3. Enhance (EnhanceActivity): filters Original / Lighten /
 *    Enhance / No Shadow / B&W, rotate, document name, checkmark
 *    to save the page.
 * 4. Back here: Single mode asks for the export quality after
 *    one page; Batch mode keeps capturing and asks when saving
 *    all pages as one PDF. The export picker is its OWN SCREEN
 *    (PdfExportActivity) — a dialog would leave the camera
 *    running and auto-capturing behind it. While the export
 *    screen is open this activity stops, CameraX releases the
 *    camera, and no scan runs in the background. The picker
 *    offers Original Quality (full camera resolution),
 *    Compressed (balanced) and Extreme Compress (smallest) —
 *    each with its real size.
 *
 * The edge frame is DYNAMIC: EdgeOverlayView draws the
 * document's real detected corners live on the preview — the
 * frame follows the book and disappears when no document is
 * found. No fixed box.
 *
 * Camera quality: MAXIMIZE_QUALITY capture, JPEG 100, and the
 * sensor's full resolution while HD is on (HD off caps at
 * 1920x1080). Crop / enhance intermediates are saved at JPEG
 * 95 so they never become the quality bottleneck.
 *
 * The camera permission is asked properly with the system dialog
 * when the scanner opens — that was the v0.1.27 bug (permission
 * was in the manifest but never requested at runtime).
 *
 * No cloud, no account. Files never leave the phone.
 */
class ScannerActivity : AppCompatActivity() {

    enum class Mode { SINGLE, BATCH }

    private val pages = mutableListOf<File>()
    // Raw file for each page (v0.1.77): the cropped file a page
    // came from, keyed by the page's CURRENT file path. A page
    // in `pages` may already be an enhanced file (from a
    // thumbnail review); its raw file is what Enhance must
    // filter from — filtering an already-filtered file a
    // second time is what made images come out overexposed.
    // A page with no entry is raw itself.
    private val rawPaths = mutableMapOf<String, String>()

    /** The raw (unfiltered) file for a page — the file Enhance
     *  must filter from. Falls back to the page itself. */
    private fun rawPathFor(page: File): String =
        rawPaths[page.absolutePath] ?: page.absolutePath

    /** Apply Enhance's result (v0.1.77): for each returned
     *  (source, enhanced) pair, find the page whose raw file
     *  is that source and replace it with the enhanced file,
     *  remembering the raw file for next time. Returns true
     *  when the list result was used. */
    private fun applyEnhancedPages(
        enhanced: ArrayList<String>?,
        sources: ArrayList<String>?
    ): Boolean {
        if (enhanced == null || sources == null) return false
        for (i in enhanced.indices) {
            val src = sources.getOrNull(i) ?: continue
            val newFile = File(enhanced[i])
            if (!newFile.exists()) continue
            val idx = pages.indexOfFirst { rawPathFor(it) == src }
            if (idx < 0) continue
            val old = pages[idx]
            rawPaths.remove(old.absolutePath)
            pages[idx] = newFile
            rawPaths[newFile.absolutePath] = src
        }
        return true
    }

    private var docName: String? = null
    // Default mode is BATCH (v0.1.67, user): the scanner opens
    // on Batch — most scans are multi-page, and Single is one
    // tap away. The mode still survives recreation via
    // onSaveInstanceState, and setMode() switches it in-session.
    private var mode = Mode.BATCH
    private var imageCapture: ImageCapture? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService? = null
    // The preview / capture rotation is FIXED to portrait
    // (ROTATION_0, v0.1.59): the Orientation setting (Auto /
    // Landscape / Portrait) is removed — the user asked for it
    // to go, because it caused errors. The activity is locked
    // to portrait (manifest), so the whole screen never
    // rotates and the camera image stays tall. No sensor
    // listener, no rotation switching, nothing to go wrong.

    // Camera lifecycle (the black-screen fix). The camera is
    // bound while this screen is visible and released the
    // moment it leaves; a fresh bind on every resume guarantees
    // the preview comes back after returning from another
    // screen (Book Scan, Pages, Enhance, Export). cameraBound
    // means a bind succeeded; cameraStarting means a bind is
    // in flight (onResume calls startCamera too, so the two
    // must not double-bind).
    private var cameraProvider: androidx.camera.lifecycle.ProcessCameraProvider? = null
    private var cameraResumed = false
    private var cameraBound = false
    private var cameraStarting = false
    private var torchOn = false
    // Full camera quality is the DEFAULT: HD on means the sensor's
    // full resolution, no artificial cap. HD off drops to 1920x1080
    // for users who want smaller files.
    // Capture quality (v0.1.65, HD picker tiers): "max" =
    // Full Sensor = the largest 4:3 size; "veryhigh" /
    // "high" / "medium" / "low" = a 4:3 size from this
    // phone's camera, resolved by qualityTiers() when the
    // camera binds. Only the MP varies — never the aspect.
    private var captureRes = "max"
    private var autoCaptureOn = true
    private var gridOn = false
    private var isCapturing = false
    // The leave-batch confirmation dialog (v0.1.74) — kept
    // so it is never shown twice at once.
    private var leaveDialog: Dialog? = null
    // Auto-capture state: the last detected corners, when the
    // current steady hold started, and when the last capture
    // happened (cooldown so it does not fire twice). The hold
    // time before auto-capture is the user's menu setting
    // (2 / 3 / 5 seconds, default 3) — the countdown bar fills
    // over exactly that time.
    private var lastCorners: FloatArray? = null
    private var steadyStartMs = 0L
    private var lastAnalysisMs = 0L
    private var lastCaptureMs = 0L
    private var autoCaptureDelayMs = 3000L

    // The document must sit still this long before the capture
    // ring even starts — otherwise auto capture fires the
    // moment the page appears, before the user has framed it.
    private val STEADY_HOLD_MS = 1500L
    // Tap-to-focus + brightness: the exposure offset the user
    // set with the on-screen slider (saved, so it stays the
    // next time the scanner opens), and the pending hide
    // timers for the focus ring and the slider.
    private var exposureIndex = 0
    private var focusHideRunnable: Runnable? = null
    private var sliderHideRunnable: Runnable? = null
    // Live edge stability: the raw detected corners jitter a
    // few pixels every frame (sensor noise + hand shake), so the
    // box shakes and the hold never looks steady — the bar kept
    // resetting. The corners are smoothed (exponential moving
    // average) and the overlay + the steadiness check use the
    // smoothed corners only.
    private var smoothCorners: FloatArray? = null
    private var lastDetectMs = 0L
    // The frame only appears after the SAME document has been
    // found on two frames in a row (v0.1.53). A single-frame
    // detection — a hand, a glare, a pattern flicker — must
    // never flash a box. Two steady frames is ~250 ms, which
    // the user never notices, and it kills the flicker.
    private var confirmCount = 0

    companion object {
        private const val PREFS_NAME = "hakunaa_prefs"
        private const val PREF_AUTO_CAPTURE_DELAY_MS = "auto_capture_delay_ms"
        private const val PREF_EXPOSURE_INDEX = "exposure_index"
        private const val PREF_GRID_ON = "grid_on"
        private const val PREF_AUTO_CAPTURE_ON = "auto_capture_on"
        private const val PREF_SOUND_ON = "sound_on"
        private const val PREF_SPIRIT_LEVEL_ON = "spirit_level_on"
        private const val PREF_CAPTURE_RES = "capture_res"
    }

    // Settings (the CamScanner-style panel, opened from the
    // top-bar menu): the shutter sound and the spirit level —
    // all saved, so they stay. (Orientation was here too; it
    // is removed in v0.1.59 — the user asked for it to go,
    // because it caused errors.)
    private var soundOn = false
    // The spirit level is ON BY DEFAULT (v0.1.59): the user
    // asked for it to be on, so the bullseye shows the moment
    // the scanner opens. The user can still turn it off from
    // the bottom-row Level button or the Settings panel.
    private var spiritLevelOn = true
    private var sensorManager: SensorManager? = null
    private var levelSensor: Sensor? = null
    private var smoothRoll = 0f
    private var smoothPitch = 0f
    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val roll = SpiritLevelView.rollFromAccelerometer(x, y, z)
            val pitch = SpiritLevelView.pitchFromAccelerometer(x, y, z)
            // Low-pass: the bubble must not jitter with every
            // hand tremor.
            smoothRoll += 0.25f * (roll - smoothRoll)
            smoothPitch += 0.25f * (pitch - smoothPitch)
            findViewById<SpiritLevelView>(R.id.levelView)
                .setTilt(smoothRoll, smoothPitch)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun prefs(): SharedPreferences =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                findViewById<TextView>(R.id.tvStatus).text =
                    "Camera permission is needed to scan. Allow it in Settings, or pick photos from the gallery instead."
            }
        }

    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) openCropFromUri(uri)
        }

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val path = result.data?.getStringExtra(CropActivity.EXTRA_CROPPED_PATH)
                if (path != null) {
                    val file = File(path)
                    if (file.exists()) {
                        pages.add(file)
                        // A freshly cropped file is raw — it is
                        // its own raw source (v0.1.77).
                        rawPaths[file.absolutePath] = file.absolutePath
                        docName = result.data?.getStringExtra("doc_name") ?: docName
                        // Capture fly animation (v0.1.74), like a
                        // professional scanner: a thumbnail of
                        // the page appears at the centre and
                        // flies to the Done button — the count
                        // ticks up when it lands. Batch: right
                        // after Crop (the page is in). Single:
                        // after Filter (Enhance already ran
                        // inside Crop for Single) — then Export
                        // opens. The thumbnail is decoded off
                        // the UI thread so the animation never
                        // hitches. Single blocks a second
                        // capture while the thumbnail is flying.
                        if (mode == Mode.SINGLE) isCapturing = true
                        Thread {
                            val thumbBmp = decodeThumb(file)
                            runOnUiThread {
                                playCaptureFlyAnimation(thumbBmp) {
                                    if (mode == Mode.SINGLE) {
                                        isCapturing = false
                                        saveAsPdf()
                                    }
                                }
                            }
                        }.start()
                    }
                }
            }
        }

    /** Small downsampled bitmap of a page file, for the
     *  fly-to-Done thumbnail (longest side 256). Null when
     *  the file cannot be read — the caller then skips the
     *  animation and updates the count at once. */
    private fun decodeThumb(file: File): Bitmap? {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)
            var sample = 1
            val maxSide = maxOf(options.outWidth, options.outHeight)
            while (maxSide / sample > 256) sample *= 2
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (_: Throwable) {
            null
        }
    }

    /** Capture animation (v0.1.74), like a professional
     *  scanner: a thumbnail of the capture appears at the
     *  centre of the screen and flies to the pages count,
     *  shrinking as it goes — and the count ticks up the
     *  moment it lands. Runs on the UI thread. Any animation
     *  still in flight is cancelled first, so a fast second
     *  capture never leaves a stuck thumbnail.
     *
     *  The target: Single flies to the Done button, then
     *  Export opens. Batch flies to the right side of the
     *  capture row — the Import button before the first
     *  capture, the small pages thumbnail after it (v0.1.74)
     *  — which is where the thumbnail + count live, like
     *  CamScanner. */
    private fun playCaptureFlyAnimation(thumbBmp: Bitmap?, onLanded: () -> Unit) {
        val status = findViewById<TextView>(R.id.tvStatus)
        // The fly target depends on the mode (see above).
        val targetView: View = if (mode == Mode.BATCH) {
            val thumbContainer = findViewById<View>(R.id.pagesThumbContainer)
            if (thumbContainer.visibility == View.VISIBLE) thumbContainer
            else findViewById(R.id.btnImportImages)
        } else {
            // Single: no Done button any more (v0.1.93) — fly
            // to the capture button, then Export opens.
            findViewById(R.id.btnCapture)
        }
        if (thumbBmp == null) {
            updatePagesLabel()
            if (mode == Mode.BATCH) refreshPagesThumb()
            onLanded()
            return
        }
        val thumb = findViewById<ImageView>(R.id.ivCaptureThumb)
        thumb.animate().cancel()
        thumb.setImageBitmap(thumbBmp)
        thumb.alpha = 1f
        thumb.scaleX = 1f
        thumb.scaleY = 1f
        thumb.translationX = 0f
        thumb.translationY = 0f
        thumb.visibility = View.VISIBLE
        // Wait for the thumbnail to be laid out (it was GONE),
        // then fly its centre onto the target's centre.
        thumb.post {
            val thumbLoc = IntArray(2)
            val targetLoc = IntArray(2)
            thumb.getLocationOnScreen(thumbLoc)
            targetView.getLocationOnScreen(targetLoc)
            val dx = (targetLoc[0] + targetView.width / 2f) - (thumbLoc[0] + thumb.width / 2f)
            val dy = (targetLoc[1] + targetView.height / 2f) - (thumbLoc[1] + thumb.height / 2f)
            thumb.animate()
                .translationXBy(dx)
                .translationYBy(dy)
                .scaleX(0.25f)
                .scaleY(0.25f)
                .alpha(0.5f)
                .setDuration(450)
                .withEndAction {
                    thumb.visibility = View.GONE
                    thumb.setImageBitmap(null)
                    thumb.translationX = 0f
                    thumb.translationY = 0f
                    thumb.scaleX = 1f
                    thumb.scaleY = 1f
                    thumb.alpha = 1f
                    try {
                        thumbBmp.recycle()
                    } catch (_: Throwable) {
                    }
                    updatePagesLabel()
                    if (mode == Mode.BATCH) {
                        refreshPagesThumb()
                        val n = pages.size
                        status.text =
                            if (n == 1) "1 page added — tap Capture for the next page, Done when finished"
                            else "$n pages added — tap Capture for the next page, Done when finished"
                    }
                    onLanded()
                }
                .start()
        }
    }

    private val pagesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // PDF saved from the Pages screen — clear and stay on camera.
                pages.clear()
                rawPaths.clear()
                OverlayStore.clear()
                docName = null
                updatePagesLabel()
                findViewById<TextView>(R.id.tvStatus).text =
                    "Done — PDF saved. Scan another document when ready"
            } else if (result.resultCode == PdfExportActivity.RESULT_DISCARD) {
                // Discard confirmed on the export screen reached
                // via the Preview (v0.1.69): drop every page so
                // the camera comes back fresh.
                pages.clear()
                rawPaths.clear()
                OverlayStore.clear()
                docName = null
                updatePagesLabel()
                findViewById<TextView>(R.id.tvStatus).text =
                    "Discarded — point the camera at a document and tap Capture"
                Toast.makeText(this, "Discarded", Toast.LENGTH_SHORT).show()
            }
            // RESULT_ADD_PAGE or back: stay on the camera, pages kept.
            // But Pages may have deleted or reordered pages —
            // sync to the list it returns (v0.1.90), so a
            // deleted page does not come back the next time
            // the pages are opened.
            val returned = result.data?.getStringArrayListExtra(PagesActivity.EXTRA_PAGE_PATHS)
            if (returned != null) {
                syncPages(returned)
            }
        }

    /** Replace the batch with the page list Pages returns
     *  (after a delete / reorder there). Pages that are gone
     *  drop out of [rawPaths] and the overlay store too, so
     *  nothing deleted can come back. */
    private fun syncPages(returned: ArrayList<String>) {
        val kept = returned.mapNotNull { path ->
            val f = File(path)
            if (f.exists()) f else null
        }
        val keptSet = kept.map { it.absolutePath }.toSet()
        // Drop the raw-path + overlay entries of pages that
        // are no longer in the batch.
        val removed = pages.filter { it.absolutePath !in keptSet }
        for (file in removed) {
            val raw = rawPathFor(file)
            rawPaths.remove(file.absolutePath)
            OverlayStore.remove(raw)
            // Also drop the raw file itself (if it is a
            // different file), so nothing deleted lingers.
            if (raw != file.absolutePath) {
                try { File(raw).delete() } catch (_: Throwable) { }
            }
        }
        rawPaths.keys.retainAll(keptSet)
        pages.clear()
        pages.addAll(kept)
        updatePagesLabel()
    }

    // Batch Done → Filter (Enhance) directly (v0.1.58): the
    // Batch flow was Done → Pages → Save → Enhance → Export,
    // and the user said it is totally wrong — after Done it
    // must go to the Filter section, not straight to a save /
    // preview screen. So Done opens Enhance for the first page
    // with "Apply to all pages" already ticked (one filter
    // covers the batch; the user can swipe pages and pick a
    // filter per page, or un-tick it). Back from Enhance
    // cancels — the pages stay on the camera. On save, the
    // first page is replaced by the enhanced file and the
    // PREVIEW screen (Pages) opens (v0.1.69): the user pointed
    // out that after choosing filters no Preview came and no
    // reorder was possible — it went straight to export. So
    // now Enhance → Preview (thumbnails, drag reorder,
    // rotate, delete, full-screen preview) → Save → Export.
    // Single mode never uses this launcher (its Enhance runs
    // from the Crop screen), so Single is untouched.
    private val enhanceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Enhance returns a new file per page, matched
                // to its raw source (v0.1.77) — each page is
                // replaced in its own slot, never pages[0] by
                // default. Fall back to the old single path.
                val applied = applyEnhancedPages(
                    result.data?.getStringArrayListExtra(EnhanceActivity.EXTRA_ENHANCED_PATHS),
                    result.data?.getStringArrayListExtra(EnhanceActivity.EXTRA_ENHANCED_SOURCE_PATHS)
                )
                if (!applied) {
                    val enhancedPath =
                        result.data?.getStringExtra(EnhanceActivity.EXTRA_ENHANCED_PATH)
                    if (enhancedPath != null && pages.isNotEmpty()) {
                        val file = File(enhancedPath)
                        if (file.exists()) pages[0] = file
                    }
                }
                docName = result.data?.getStringExtra("doc_name") ?: docName
                openPagesAfterEnhance()
            }
        }

    /** Thumbnail → Filter (Enhance) in "review" mode (v0.1.74):
     *  the user checks the filters mid-batch, then comes back
     *  and keeps capturing. Unlike Done → Enhance, the green
     *  check here does NOT go to Preview — it saves the
     *  filters and returns to the camera. Back (CANCELED)
     *  also returns to the camera, pages kept. */
    // Export is a separate screen: while it is open this
    // activity stops and the camera is released — no scan runs
    // in the background. On save, clear the pages; on discard
    // (user confirmed on the export screen, v0.1.67), clear
    // them too so the camera comes back fresh; a plain cancel
    // keeps them and leaves the Save button visible so the
    // user can open export again.
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val status = findViewById<TextView>(R.id.tvStatus)
            if (result.resultCode == RESULT_OK) {
                val fileName = result.data?.getStringExtra(PdfExportActivity.EXTRA_FILE_NAME)
                    ?: "scan.pdf"
                val sizeBytes = result.data?.getLongExtra(PdfExportActivity.EXTRA_SIZE_BYTES, 0L)
                    ?: 0L
                val size = FileUtils.formatSize(sizeBytes)
                status.text = "Done — scanned PDF saved to Documents/Hakunaa/$fileName (${pages.size} page(s), $size)"
                Toast.makeText(this, "PDF saved ($size)", Toast.LENGTH_SHORT).show()
                pages.clear()
                rawPaths.clear()
                OverlayStore.clear()
                docName = null
                updatePagesLabel()
            } else if (result.resultCode == PdfExportActivity.RESULT_DISCARD) {
                // User confirmed discard on the export screen
                // (v0.1.67): drop every page so the camera comes
                // back fresh — no "N page(s) • Done" left at the
                // bottom, in Single or Batch.
                val count = pages.size
                pages.clear()
                rawPaths.clear()
                OverlayStore.clear()
                docName = null
                updatePagesLabel()
                status.text = "Discarded — point the camera at a document and tap Capture"
                Toast.makeText(
                    this,
                    if (count <= 1) "Image discarded" else "$count pages discarded",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                status.text = "Save cancelled — ${pages.size} page(s) ready, tap Save as PDF when ready"
                updatePagesLabel()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        // Back (system or gesture) leaves the scanner — in
        // Batch with pages captured, ask first (v0.1.74),
        // because leaving discards the draft batch.
        onBackPressedDispatcher.addCallback(this) { attemptLeave() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { attemptLeave() }
        findViewById<ImageButton>(R.id.btnCapture).setOnClickListener { capturePhoto() }
        findViewById<ImageButton>(R.id.btnFlash).setOnClickListener { toggleTorch() }
        // HD button (v0.1.62): opens the resolution picker
        // (CamScanner-style) and shows the current resolution.
        captureRes = prefs().getString(PREF_CAPTURE_RES, "max") ?: "max"
        // v0.1.64: the old "std" / "WxH" values no longer
        // exist (quality tiers now) — reset to Full Sensor.
        if (captureRes !in setOf("max", "veryhigh", "high", "medium", "low")) {
            captureRes = "max"
        }
        updateHdButton()
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAuto)
            .setOnClickListener { toggleAutoCapture() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnHd)
            .setOnClickListener { showResolutionDialog() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            showSettingsDialog()
        }
        // Bottom row, left: the Spirit Level (in the Grid
        // button's place — the Grid itself is in the Settings
        // panel now). It is a DISPLAY only (v0.1.63): tapping
        // it does NOT turn it on / off — the user asked for
        // the Level to be controlled ONLY from the Settings
        // panel (Spirit Level switch), so there is no tap
        // listener here at all.
        findViewById<LinearLayout>(R.id.btnImportImages).setOnClickListener {
            pickPhoto.launch("image/*")
        }
        findViewById<LinearLayout>(R.id.pagesThumbContainer).setOnClickListener {
            // The thumbnail IS the way forward (v0.1.93,
            // professional pattern): tap it to open the
            // Filter (Enhance) screen, then Preview, Export.
            onSavePdfClicked()
        }
        findViewById<TextView>(R.id.tvModeSingle).setOnClickListener { setMode(Mode.SINGLE) }
        findViewById<TextView>(R.id.tvModeBatch).setOnClickListener { setMode(Mode.BATCH) }
        // Book mode is its own landscape screen (two pages at
        // once, A then B) — it opens from this tab.
        findViewById<TextView>(R.id.tvModeBook).setOnClickListener {
            startActivity(Intent(this, BookScannerActivity::class.java))
        }
        // (v0.1.93) No Done button any more — the thumbnail
        // is the way forward (see the click above).

        // Ask for the camera permission the way every app does.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        // Auto-capture hold time from the saved menu setting.
        autoCaptureDelayMs = prefs().getLong(PREF_AUTO_CAPTURE_DELAY_MS, 3000L)
        // Saved brightness offset from the on-screen slider.
        exposureIndex = prefs().getInt(PREF_EXPOSURE_INDEX, 0)
        // Saved grid state (the bottom-row setting).
        gridOn = prefs().getBoolean(PREF_GRID_ON, false)
        updateGridButton()
        // Saved Settings-panel choices.
        autoCaptureOn = prefs().getBoolean(PREF_AUTO_CAPTURE_ON, true)
        updateAutoCaptureButton()
        soundOn = prefs().getBoolean(PREF_SOUND_ON, false)
        spiritLevelOn = prefs().getBoolean(PREF_SPIRIT_LEVEL_ON, true)
        updateSpiritLevel()
        // A rotation (or the system killing the activity) must
        // not lose the pages already captured.
        savedInstanceState?.getStringArrayList("page_paths")?.let { paths ->
            pages.clear()
            pages.addAll(paths.map { File(it) }.filter { it.exists() })
            // Restore each page's raw source (v0.1.77), in the
            // same order as the pages.
            savedInstanceState.getStringArrayList("raw_paths")?.let { raws ->
                for (i in pages.indices) {
                    val raw = raws.getOrNull(i) ?: continue
                    rawPaths[pages[i].absolutePath] = raw
                }
            }
        }
        savedInstanceState?.getString("doc_name")?.let { docName = it }
        savedInstanceState?.getString("mode")?.let {
            try { mode = Mode.valueOf(it) } catch (_: Throwable) { }
        }
        // Always sync the mode tabs with the actual mode
        // (v0.1.68): the field defaults to BATCH, but setMode
        // used to run only when pages existed — so on a fresh
        // launch the layout's hardcoded Single highlight stayed
        // and Batch never LOOKED like the default, even though
        // the mode itself was Batch. Now the tabs always match.
        setMode(mode)
        setupTapToFocus()
        updatePagesLabel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            "page_paths", ArrayList(pages.map { it.absolutePath })
        )
        // The raw source for each page (v0.1.77), so a
        // recreation does not lose which file is raw.
        outState.putStringArrayList(
            "raw_paths",
            ArrayList(pages.map { rawPathFor(it) })
        )
        outState.putString("doc_name", docName)
        outState.putString("mode", mode.name)
    }

    override fun onResume() {
        super.onResume()
        cameraResumed = true
        // Returning from another screen left the preview BLACK
        // — the Preview use case never re-attached to the
        // recreated surface. Rebind fresh on every resume so
        // the camera always comes back.
        if (!cameraBound) startCamera()
        startSpiritLevel()
    }

    override fun onPause() {
        super.onPause()
        // Release the camera the moment this screen leaves, so
        // the next screen (or the next resume) starts clean —
        // and so no analysis keeps burning CPU in the
        // background while the user is on another screen.
        cameraResumed = false
        cameraBound = false
        cameraStarting = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
        }
        stopSpiritLevel()
    }

    /** The spirit level only listens while this screen is
     *  visible and the setting is on — a sensor listener left
     *  running in the background would burn battery. */
    private fun startSpiritLevel() {
        if (!spiritLevelOn) return
        if (sensorManager == null) {
            sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
            levelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
        val sensor = levelSensor ?: return
        try {
            sensorManager?.registerListener(
                levelListener, sensor, SensorManager.SENSOR_DELAY_UI
            )
        } catch (_: Throwable) {
        }
    }

    private fun stopSpiritLevel() {
        try {
            sensorManager?.unregisterListener(levelListener)
        } catch (_: Throwable) {
        }
    }

    /** The Spirit Level lives in the bottom row (in the Grid
     *  button's place). It is always there; when it is off it
     *  is dimmed, when it is on it is bright and the bubble
     *  moves. The Settings panel toggles the same state. */
    private fun setSpiritLevel(on: Boolean) {
        spiritLevelOn = on
        prefs().edit().putBoolean(PREF_SPIRIT_LEVEL_ON, on).apply()
        updateSpiritLevel()
    }

    private fun updateSpiritLevel() {
        val widget = findViewById<LinearLayout>(R.id.btnLevel)
        widget.alpha = if (spiritLevelOn) 1f else 0.4f
        val label = findViewById<TextView>(R.id.tvLevelLabel)
        label.setTextColor(
            if (spiritLevelOn) getColor(R.color.hakunaa_teal)
            else getColor(android.R.color.white)
        )
        if (spiritLevelOn) {
            smoothRoll = 0f
            smoothPitch = 0f
            findViewById<SpiritLevelView>(R.id.levelView).setTilt(0f, 0f)
            startSpiritLevel()
        } else {
            stopSpiritLevel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
    }

    private fun setMode(newMode: Mode) {
        mode = newMode
        val single = findViewById<TextView>(R.id.tvModeSingle)
        val batch = findViewById<TextView>(R.id.tvModeBatch)
        // The selected mode is a teal pill with white text; the
        // other is plain white text — a real segmented control,
        // the way Book Scan's Single / Double reads.
        if (newMode == Mode.SINGLE) {
            single.background = getDrawable(R.drawable.mode_tab_selected)
            single.setTextColor(getColor(android.R.color.white))
            single.setTypeface(null, android.graphics.Typeface.BOLD)
            batch.background = null
            batch.setTextColor(getColor(android.R.color.white))
            batch.setTypeface(null, android.graphics.Typeface.NORMAL)
        } else {
            batch.background = getDrawable(R.drawable.mode_tab_selected)
            batch.setTextColor(getColor(android.R.color.white))
            batch.setTypeface(null, android.graphics.Typeface.BOLD)
            single.background = null
            single.setTextColor(getColor(android.R.color.white))
            single.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        findViewById<TextView>(R.id.tvStatus).text =
            if (newMode == Mode.SINGLE) "Single mode — one page, then save"
            else "Batch mode — keep capturing, save when done"
        // The Done button and the right side of the capture row
        // follow the mode (Batch: thumbnail + count after the
        // first capture; Single: Import Images) — refresh both.
        updatePagesLabel()
    }

    /** Torch: the flashlight stays ON while scanning, so the
     *  document is lit in the dark — the way professional scanners
     *  do it. (Different from the capture flash, which only fires
     *  when the photo is taken.) */
    private fun toggleTorch() {
        torchOn = !torchOn
        // A phone without a torch must not crash the scanner.
        try {
            camera?.cameraControl?.enableTorch(torchOn)
        } catch (_: Throwable) {
        }
        findViewById<ImageButton>(R.id.btnFlash).setImageResource(
            if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
        findViewById<TextView>(R.id.tvStatus).text =
            if (torchOn) "Torch on" else "Torch off"
    }

    /** Auto capture on/off. The top-bar AUTO button and the
     *  Settings panel control the same state — it is saved,
     *  so it stays as the user left it. */
    private fun setAutoCapture(on: Boolean) {
        autoCaptureOn = on
        prefs().edit().putBoolean(PREF_AUTO_CAPTURE_ON, on).apply()
        steadyStartMs = 0
        lastCorners = null
        smoothCorners = null
        confirmCount = 0
        cancelCaptureRing()
        updateAutoCaptureButton()
    }

    private fun updateAutoCaptureButton() {
        val btn = findViewById<MaterialButton>(R.id.btnAuto)
        btn.setTextColor(
            if (autoCaptureOn) getColor(R.color.hakunaa_teal)
            else getColor(android.R.color.white)
        )
        btn.strokeColor = android.content.res.ColorStateList.valueOf(
            if (autoCaptureOn) getColor(R.color.hakunaa_teal)
            else getColor(android.R.color.white)
        )
        findViewById<TextView>(R.id.tvStatus).text =
            if (autoCaptureOn) "Auto capture on — hold the page steady"
            else "Auto capture off — tap Capture yourself"
    }

    /** Grid lines on/off — the framing aid. The bottom-row
     *  Grid button and the Settings panel control the same
     *  state — it is saved, so it stays as the user left it. */
    private fun setGrid(on: Boolean) {
        gridOn = on
        prefs().edit().putBoolean(PREF_GRID_ON, on).apply()
        updateGridButton()
    }

    private fun toggleGrid() {
        setGrid(!gridOn)
    }

    /** The grid is toggled from the Settings panel now (the
     *  bottom-row button is the Spirit Level). This only shows
     *  or hides the overlay. */
    private fun updateGridButton() {
        val overlay = findViewById<GridOverlayView>(R.id.gridOverlay)
        overlay.visibility = if (gridOn) View.VISIBLE else View.GONE
    }

    private fun toggleAutoCapture() {
        setAutoCapture(!autoCaptureOn)
    }

    /** The Settings panel — the CamScanner-style menu the
     *  top-bar button opens: Auto Capture, Grid, Sound, Spirit
     *  Level, and More Settings. (Orientation was here too; it
     *  is removed in v0.1.59.) A dark rounded card over the
     *  dimmed preview. Every row is a real setting that takes
     *  effect at once. */
    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_scanner_settings, null)
        val dialog = Dialog(this)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        val swAuto = view.findViewById<SwitchMaterial>(R.id.swAutoCapture)
        val tvAutoState = view.findViewById<TextView>(R.id.tvAutoCaptureState)
        val swGrid = view.findViewById<SwitchMaterial>(R.id.swGrid)
        val tvGridState = view.findViewById<TextView>(R.id.tvGridState)
        val swSound = view.findViewById<SwitchMaterial>(R.id.swSound)
        val tvSoundState = view.findViewById<TextView>(R.id.tvSoundState)
        val swLevel = view.findViewById<SwitchMaterial>(R.id.swSpiritLevel)
        val tvLevelState = view.findViewById<TextView>(R.id.tvSpiritLevelState)

        fun refreshStates() {
            swAuto.isChecked = autoCaptureOn
            tvAutoState.text = if (autoCaptureOn) "On" else "Off"
            swGrid.isChecked = gridOn
            tvGridState.text = if (gridOn) "On" else "Off"
            swSound.isChecked = soundOn
            tvSoundState.text = if (soundOn) "On" else "Off"
            swLevel.isChecked = spiritLevelOn
            tvLevelState.text = if (spiritLevelOn) "On" else "Off"
        }
        refreshStates()

        swAuto.setOnCheckedChangeListener { _, checked ->
            setAutoCapture(checked)
            refreshStates()
        }
        swGrid.setOnCheckedChangeListener { _, checked ->
            setGrid(checked)
            refreshStates()
        }
        swSound.setOnCheckedChangeListener { _, checked ->
            soundOn = checked
            prefs().edit().putBoolean(PREF_SOUND_ON, checked).apply()
            refreshStates()
        }
        swLevel.setOnCheckedChangeListener { _, checked ->
            setSpiritLevel(checked)
            refreshStates()
        }

        view.findViewById<ImageButton>(R.id.btnCloseSettings)
            .setOnClickListener { dialog.dismiss() }
        view.findViewById<TextView>(R.id.tvMoreSettings).setOnClickListener {
            dialog.dismiss()
            showMoreSettingsDialog()
        }
        dialog.show()
    }

    /** More Settings — the second page of the Settings panel:
     *  how long to hold the document steady before
     *  auto-capture fires (2 / 3 / 5 seconds), and HD. */
    private fun showMoreSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_more_settings, null)
        val dialog = Dialog(this)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)

        val rgDelay = view.findViewById<RadioGroup>(R.id.rgDelay)
        val checkedId = when (autoCaptureDelayMs) {
            2000L -> R.id.rbDelay2
            5000L -> R.id.rbDelay5
            else -> R.id.rbDelay3
        }
        rgDelay.check(checkedId)
        rgDelay.setOnCheckedChangeListener { _, id ->
            autoCaptureDelayMs = when (id) {
                R.id.rbDelay2 -> 2000L
                R.id.rbDelay5 -> 5000L
                else -> 3000L
            }
            prefs().edit().putLong(PREF_AUTO_CAPTURE_DELAY_MS, autoCaptureDelayMs).apply()
        }
        view.findViewById<ImageButton>(R.id.btnCloseMoreSettings)
            .setOnClickListener { dialog.dismiss() }
        dialog.show()
        // Wide dialog (v0.1.61): a plain Dialog defaults to a
        // narrow width, which clipped the "More Settings" title
        // and cut off "Full camera resolution" — the user
        // reported both. 90% of the screen width gives every
        // row room to fit on one line.
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    /** The HD resolution picker (v0.1.62, CamScanner-style):
     *  the HD button opens it; it lists what THIS phone's
     *  camera really supports, so every option works — nothing
     *  is premium-locked (CamScanner crowns some sizes). The
     *  choice is saved and the camera re-binds with it. */
    /** The HD resolution picker (v0.1.63, professional
     *  bottom sheet — CamScanner-style): the sheet slides up
     *  from the bottom edge, full width, rounded top corners,
     *  clean rows (bold megapixels + grey dimensions, teal
     *  check on the right, divider under each row) and a
     *  Hide button at the bottom. It lists QUALITY tiers
     *  (v0.1.64 — the user asked to drop the megapixel list):
     *  Full Sensor / Very High / High / Medium / Low, each
     *  with its real resolution in grey. Every tier is a 4:3
     *  size from THIS phone's camera, so every option works
     *  and no photo ever looks "kutty" — nothing is
     *  premium-locked (CamScanner crowns some sizes). The
     *  choice is saved and the camera re-binds with it. */
    private fun showResolutionDialog() {
        val tiers = qualityTiers()
        // Each entry: the quality name, the real resolution
        // in grey (so the user still sees what it means),
        // the saved key, and whether it gets the BEST badge.
        data class ResOption(
            val mp: String,
            val dims: String,
            val value: String,
            val best: Boolean = false
        )
        val options = tiers.map { tier ->
            ResOption(
                tier.name,
                if (tier.size == null) "(max)"
                else "${tier.size.width}x${tier.size.height} \u2022 ${mpLabel(tier.size)}",
                tier.key,
                tier.best
            )
        }
        val checked = options.indexOfFirst { it.value == captureRes }
            .takeIf { it >= 0 } ?: 0
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_resolution)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCancelable(true)
        val rows = dialog.findViewById<LinearLayout>(R.id.resolutionRows)
        for (i in options.indices) {
            val opt = options[i]
            val row = layoutInflater.inflate(R.layout.item_resolution, rows, false)
            row.findViewById<TextView>(R.id.tvMp).text = opt.mp
            row.findViewById<TextView>(R.id.tvDims).text = opt.dims
            if (opt.best) {
                row.findViewById<TextView>(R.id.tvBadge).visibility = View.VISIBLE
            }
            val check = row.findViewById<ImageView>(R.id.ivCheck)
            check.setImageResource(
                if (i == checked) R.drawable.ic_radio_checked
                else R.drawable.ic_radio_unchecked
            )
            row.setOnClickListener {
                captureRes = opt.value
                prefs().edit().putString(PREF_CAPTURE_RES, captureRes).apply()
                updateHdButton()
                findViewById<TextView>(R.id.tvStatus).text =
                    "Quality: ${opt.mp} ${opt.dims}"
                dialog.dismiss()
                startCamera()
            }
            rows.addView(row)
        }
        dialog.findViewById<ImageButton>(R.id.btnCloseResolution)
            .setOnClickListener { dialog.dismiss() }
        dialog.findViewById<TextView>(R.id.tvHideResolution)
            .setOnClickListener { dialog.dismiss() }
        dialog.show()
        // Bottom sheet: full width, anchored to the bottom.
        dialog.window?.apply {
            setGravity(android.view.Gravity.BOTTOM)
            setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    /** "16M" / "12M" / "5M" for a size — the megapixel label
     *  CamScanner shows next to each resolution. */
    private fun mpLabel(size: android.util.Size): String {
        val mp = size.width.toDouble() * size.height.toDouble() / 1_000_000.0
        return if (mp >= 10) "${mp.roundToInt()}M"
        else String.format("%.1fM", mp)
    }

    /** Quality tiers for the HD picker (v0.1.64). The user
     *  asked to drop the long megapixel list and define
     *  QUALITY only: Low / Medium / High / Very High /
     *  Full Sensor. Every tier is a 4:3 size — a 16:9 photo
     *  looks small ("kutty") in the portrait Crop screen,
     *  which is what the user reported after picking a 16:9
     *  size, so 16:9 sizes are never offered here. Full
     *  Sensor = the largest 4:3 size (v0.1.65 — the sensor's
     *  absolute max can be wide). The other tiers
     *  spread across this phone's real 4:3 sizes by rank, so
     *  they are always distinct and sensibly spaced on any
     *  phone (8MP or 108MP). A tier with no distinct size is
     *  skipped, and the names are assigned by count so the
     *  smallest is always Low and the largest Very High.
     *  Camera unreadable = only Full Sensor, never a crash. */
    private data class QualityTier(
        val key: String,
        val name: String,
        val size: android.util.Size?, // null = no cap (camera unreadable fallback)
        val best: Boolean = false
    )

    private fun qualityTiers(): List<QualityTier> {
        val allSizes: List<android.util.Size> = try {
            val manager =
                getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING
                ) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } ?: return listOf(QualityTier("max", "Full Sensor", null, true))
            val chars = manager.getCameraCharacteristics(cameraId)
            val map = chars.get(
                android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
            )
            map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList()
                ?: return listOf(QualityTier("max", "Full Sensor", null, true))
        } catch (e: Exception) {
            return listOf(QualityTier("max", "Full Sensor", null, true))
        }
        // 4:3 only, at least 1280 wide, biggest first.
        val sizes43 = allSizes
            .filter {
                it.width >= 1280 &&
                    kotlin.math.abs(it.width.toDouble() / it.height - 4.0 / 3.0) < 0.03
            }
            .distinct()
            .sortedByDescending { it.width * it.height }
        if (sizes43.isEmpty()) {
            return listOf(QualityTier("max", "Full Sensor", null, true))
        }
        // Full Sensor = the LARGEST 4:3 size (v0.1.65). The
        // sensor's absolute max can be a wide (16:9) size, and
        // a wide photo looks small ("kutty") in the portrait
        // Crop screen — the user measured it even in Full
        // Sensor and said only the QUALITY should vary, never
        // the framing. So every tier is 4:3; only the MP
        // changes. (On most phones the largest 4:3 IS the
        // sensor max, so nothing is lost there.)
        val tiers = mutableListOf(QualityTier("max", "Full Sensor", sizes43[0], true))
        val avail = sizes43.drop(1)
        if (avail.isEmpty()) return tiers
        // Spread the four tiers across the list by rank.
        fun at(frac: Double): android.util.Size {
            val idx = ((avail.size - 1) * frac).roundToInt()
                .coerceIn(0, avail.size - 1)
            return avail[idx]
        }
        val distinct = listOf(at(0.0), at(0.33), at(0.66), at(1.0)).distinct()
        val names = when (distinct.size) {
            4 -> listOf("Very High", "High", "Medium", "Low")
            3 -> listOf("Very High", "High", "Low")
            2 -> listOf("Very High", "Low")
            1 -> listOf("Very High")
            else -> emptyList()
        }
        val keys = when (distinct.size) {
            4 -> listOf("veryhigh", "high", "medium", "low")
            3 -> listOf("veryhigh", "high", "low")
            2 -> listOf("veryhigh", "low")
            1 -> listOf("veryhigh")
            else -> emptyList()
        }
        distinct.forEachIndexed { i, size ->
            tiers.add(QualityTier(keys[i], names[i], size))
        }
        return tiers
    }

    /** The HD button shows the current quality (v0.1.64):
     *  "HD" for Full Sensor, "Very High" / "High" /
     *  "Medium" / "Low" for a picked tier. Always teal — it
     *  is a setting that opens the picker, not a toggle. */
    private fun updateHdButton() {
        val btn =
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnHd)
        val label = when (captureRes) {
            "max" -> "HD"
            "veryhigh" -> "Very High"
            "high" -> "High"
            "medium" -> "Medium"
            "low" -> "Low"
            else -> "HD"
        }
        btn.text = label
        btn.setTextColor(getColor(R.color.hakunaa_teal))
        btn.strokeColor =
            android.content.res.ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
    }

    private fun startCamera() {
        // One bind at a time — onResume calls this too, and the
        // provider future from onCreate may still be pending.
        if (cameraStarting || cameraBound) return
        cameraStarting = true
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraStarting = false
            // Never bind while this screen is not visible — a
            // late provider result after the user left must not
            // grab the camera in the background. onResume will
            // call startCamera again when they come back.
            if (!cameraResumed || isFinishing || isDestroyed) return@addListener
            try {
                val cameraProvider = cameraProviderFuture.get()
                this.cameraProvider = cameraProvider
                val builtPreview = Preview.Builder()
                    // The sensor's NATIVE 4:3 aspect — the widest
                    // field of view, nothing cropped. A 16:9 target
                    // crops the sensor's frame top and bottom, so
                    // the screen still looked slightly zoomed in.
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    // Fixed portrait rotation (v0.1.59) — the
                    // Orientation setting is removed.
                    .setTargetRotation(Surface.ROTATION_0)
                    .build().also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
                }
                preview = builtPreview
                val previewUseCase = builtPreview
                // ORIGINAL CAMERA QUALITY: MAXIMIZE_QUALITY captures
                // at the sensor's full resolution with full processing
                // (MINIMIZE_LATENCY used the fast, lower-quality path),
                // JPEG quality 100. The capture resolution comes
                // from the HD picker (v0.1.65 quality tiers):
                // every tier is a 4:3 size (Full Sensor = the
                // largest 4:3) — applied as the target
                // resolution. Unknown key / unreadable camera
                // = no cap (safe fallback).
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .setTargetRotation(Surface.ROTATION_0)
                // Every tier (incl. Full Sensor) resolves to its
                // 4:3 size; a null size (camera unreadable) or
                // an unknown key means no cap — safe fallback.
                val tierSize = qualityTiers()
                    .firstOrNull { it.key == captureRes }?.size
                if (tierSize != null) {
                    // PORTRAIT target (v0.1.66): this phone is
                    // portrait-locked, so the target resolution
                    // must be portrait (height > width). The
                    // tier sizes come back landscape (WxH) from
                    // Camera2, and a landscape target made
                    // CameraX capture a landscape photo — a
                    // small wide band in the portrait Crop
                    // screen, instead of filling it like the
                    // preview. Swapping W/H here makes every
                    // tier capture portrait, same aspect, only
                    // the MP (MB) differs — as the user asked.
                    captureBuilder.setTargetResolution(
                        android.util.Size(
                            minOf(tierSize.width, tierSize.height),
                            maxOf(tierSize.width, tierSize.height)
                        )
                    )
                }
                imageCapture = captureBuilder.build().also {
                    it.flashMode = ImageCapture.FLASH_MODE_AUTO
                }
                // AUTO-RESOLVE camera binding — global support.
                // Not every phone supports the same stream
                // combination: the fixed 640x480 analysis stream
                // black-screened one phone (the camera never
                // opened at all — bindToLifecycle threw, so
                // nothing was bound). So try the fast setup
                // first, and fall back step by step until the
                // camera opens on ANY phone: fixed analysis size
                // -> CameraX picks the size -> no analysis at
                // all (manual capture still works, auto-capture
                // and the edge overlay just stay off). The
                // preview + capture are never sacrificed.
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                var analysisOn = true
                val captureUseCase = imageCapture ?: return@addListener
                camera = try {
                    val builtAnalysis = buildAnalysis(fixedResolution = true)
                    imageAnalysis = builtAnalysis
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, previewUseCase, captureUseCase, builtAnalysis
                    )
                } catch (_: Throwable) {
                    try {
                        val builtAnalysis = buildAnalysis(fixedResolution = false)
                        imageAnalysis = builtAnalysis
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            this, cameraSelector, previewUseCase, captureUseCase, builtAnalysis
                        )
                    } catch (_: Throwable) {
                        analysisOn = false
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            this, cameraSelector, previewUseCase, captureUseCase
                        )
                    }
                }
                cameraBound = true
                // Torch / brightness must never break the camera:
                // a phone that does not support them just skips
                // them (applyExposure guards itself the same way).
                try {
                    if (torchOn) camera?.cameraControl?.enableTorch(true)
                } catch (_: Throwable) {
                }
                // Restore the user's brightness offset (the
                // on-screen slider), clamped to this camera's range.
                applyExposure(exposureIndex)
                findViewById<TextView>(R.id.tvStatus).text =
                    if (analysisOn) "Point the camera at a document and tap Capture"
                    else "Camera ready — tap Capture yourself (auto-capture isn't supported on this phone)"
            } catch (_: Throwable) {
                findViewById<TextView>(R.id.tvStatus).text =
                    "Couldn't start the camera — try picking a photo from the gallery instead"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** The live-detection stream. A fixed 320x240 is fastest
     *  (small frames, no lag — 4x fewer pixels than 640x480,
     *  so the detector and the bitmap conversion cost a
     *  quarter of the CPU), but some phones reject that exact
     *  combination — then CameraX picks the size itself (auto
     *  resolve), which works everywhere. */
    private fun buildAnalysis(fixedResolution: Boolean): ImageAnalysis {
        val builder = ImageAnalysis.Builder()
            // 4:3, the same aspect as the preview, so the
            // detected corners map exactly onto the preview.
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            // Fixed portrait rotation (v0.1.59) — the analysis
            // matches the preview, so the edge frame stays on
            // the page.
            .setTargetRotation(Surface.ROTATION_0)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        if (fixedResolution) {
            // Small analysis frames (320x240): the detector only
            // needs a small image, and a full-size frame made
            // toBitmap + detection slow enough to lag the preview
            // — the camera dropped frames while the analysis ran.
            // 320x240 is 4:3, so the overlay mapping is unchanged,
            // and the steadiness threshold is halved to match.
            builder.setTargetResolution(android.util.Size(320, 240))
        }
        return builder.build().also {
            it.setAnalyzer(cameraExecutor!!, ::analyzeFrame)
        }
    }

    /** The dynamic edge overlay lives here: the detected page's
     *  real corners, drawn live on the preview. It follows the
     *  book — move the phone and the frame moves with the page —
     *  and disappears the moment no document is found. */
    private fun edgeOverlay(): EdgeOverlayView =
        findViewById(R.id.edgeOverlay)

    /** The countdown bar lives here: it fills like a charge
     *  while the document is held steady, and hides when there
     *  is nothing to count down. */
    private fun captureRing(): CaptureRingView =
        findViewById(R.id.captureRing)

    /** The auto-capture ring, driven by a smooth animator
     *  (not by the analysis): when the hold starts, the ring
     *  fills over the user's delay; full means capture. The
     *  animator runs on the UI thread (60 fps, vsync), so the
     *  ring moves smoothly — the old bar was driven by the
     *  analysis ~6 times a second, which is why it lagged. */
    private var captureAnimator: ValueAnimator? = null
    private var ringCancelled = false

    private fun startCaptureRing() {
        runOnUiThread {
            if (captureAnimator?.isRunning == true) return@runOnUiThread
            val animator = ValueAnimator.ofFloat(0f, 1f)
            animator.duration = autoCaptureDelayMs
            animator.interpolator = LinearInterpolator()
            animator.addUpdateListener {
                captureRing().setProgress(it.animatedValue as Float)
            }
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // A cancelled ring (the hold broke) must not fire.
                    if (!ringCancelled) {
                        findViewById<TextView>(R.id.tvStatus).text = "Document found — capturing…"
                        capturePhoto()
                    }
                }
            })
            ringCancelled = false
            captureAnimator = animator
            animator.start()
        }
    }

    private fun cancelCaptureRing() {
        runOnUiThread {
            ringCancelled = true
            captureAnimator?.cancel()
            captureAnimator = null
            captureRing().setProgress(-1f)
        }
    }


    /** Tap to focus, the way a real camera works: tap anywhere
     *  on the preview and the camera focuses there and meters
     *  the light there (AF + AE at the tapped point) — tap a
     *  dark corner and it brightens, tap a bright page and it
     *  darkens. A focus ring marks the spot and a brightness
     *  slider appears right beside it, so brightness is adjusted
     *  on the screen itself, with no separate settings. */
    private fun setupTapToFocus() {
        val previewView = findViewById<PreviewView>(R.id.previewView)
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val rect = previewContentRect()
                if (rect.contains(event.x, event.y)) {
                    focusAt(event.x, event.y)
                }
            }
            true
        }
        exposureSlider().onValueChanged = { value01 ->
            val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
            if (range != null && range.upper > range.lower) {
                val index = range.lower + (value01 * (range.upper - range.lower)).toInt()
                if (index != exposureIndex) applyExposure(index)
            }
        }
    }

    /** The preview is 4:3 fitCenter inside the full-screen
     *  PreviewView, so the top and bottom are black bars. Only
     *  taps inside the actual camera image focus the camera.
     *  Always portrait now (v0.1.59): the Orientation setting
     *  is removed, so the preview is always tall. */
    private fun previewContentRect(): RectF {
        val previewView = findViewById<PreviewView>(R.id.previewView)
        val w = previewView.width.toFloat()
        val h = previewView.height.toFloat()
        // Centred (fitCenter) inside the preview view, matching
        // the image. Portrait preview: 3 wide, 4 tall.
        val contentH = w * 4f / 3f
        return if (contentH <= h) {
            RectF(0f, (h - contentH) / 2f, w, (h + contentH) / 2f)
        } else {
            val contentW = h * 3f / 4f
            RectF((w - contentW) / 2f, 0f, (w + contentW) / 2f, h)
        }
    }

    private fun focusAt(x: Float, y: Float) {
        // A phone without focus metering must not crash the
        // scanner — a tap then simply does nothing.
        try {
            val cam = camera ?: return
            val previewView = findViewById<PreviewView>(R.id.previewView)
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (_: Throwable) {
            return
        }
        showFocusUi(x, y)
    }

    /** Focus ring at the tap point + brightness slider right
     *  beside it. The ring fades after a moment; the slider
     *  stays a few seconds so it can be dragged, then fades. */
    private fun showFocusUi(x: Float, y: Float) {
        val density = resources.displayMetrics.density
        val ringSize = (80f * density).toInt()
        val ring = findViewById<ImageView>(R.id.focusRing)
        val ringParams = FrameLayout.LayoutParams(ringSize, ringSize)
        ringParams.leftMargin = (x - ringSize / 2f).toInt()
        ringParams.topMargin = (y - ringSize / 2f).toInt()
        ring.layoutParams = ringParams
        ring.animate().cancel()
        ring.alpha = 1f
        ring.visibility = View.VISIBLE
        focusHideRunnable?.let { ring.removeCallbacks(it) }
        focusHideRunnable = Runnable {
            ring.animate().alpha(0f).setDuration(400).withEndAction {
                ring.visibility = View.GONE
            }.start()
        }.also { ring.postDelayed(it, 1200) }

        // Slider beside the ring — right side, unless the tap is
        // near the right edge, then the left side. Kept inside
        // the preview image.
        val slider = exposureSlider()
        val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange
        if (range == null || range.upper <= range.lower) {
            slider.visibility = View.GONE
            return
        }
        val sliderW = (36f * density).toInt()
        val sliderH = (200f * density).toInt()
        val rect = previewContentRect()
        val gap = (12f * density).toInt()
        var sliderX = x + ringSize / 2f + gap
        if (sliderX + sliderW > rect.right - gap) {
            sliderX = x - ringSize / 2f - gap - sliderW
        }
        sliderX = sliderX.coerceIn(rect.left + gap, rect.right - gap - sliderW)
        var sliderY = y - sliderH / 2f
        sliderY = sliderY.coerceIn(rect.top + gap, rect.bottom - gap - sliderH)
        val sliderParams = FrameLayout.LayoutParams(sliderW, sliderH)
        sliderParams.leftMargin = sliderX.toInt()
        sliderParams.topMargin = sliderY.toInt()
        slider.layoutParams = sliderParams
        slider.setValue((exposureIndex - range.lower).toFloat() / (range.upper - range.lower))
        slider.animate().cancel()
        slider.alpha = 1f
        slider.visibility = View.VISIBLE
        resetSliderHideTimer()
    }

    private fun resetSliderHideTimer() {
        val slider = exposureSlider()
        sliderHideRunnable?.let { slider.removeCallbacks(it) }
        sliderHideRunnable = Runnable {
            slider.animate().alpha(0f).setDuration(400).withEndAction {
                slider.visibility = View.GONE
            }.start()
        }.also { slider.postDelayed(it, 4000) }
    }

    private fun hideFocusUi() {
        focusHideRunnable?.let { findViewById<View>(R.id.focusRing).removeCallbacks(it) }
        sliderHideRunnable?.let { exposureSlider().removeCallbacks(it) }
        findViewById<View>(R.id.focusRing).visibility = View.GONE
        exposureSlider().visibility = View.GONE
    }

    /** Brightness offset from the on-screen slider: clamped to
     *  this camera's exposure range, applied at once, saved so
     *  it stays the next time the scanner opens. */
    private fun applyExposure(index: Int) {
        // A phone that does not support exposure compensation
        // must never break the camera — skip it silently.
        try {
            val cam = camera ?: return
            val range = cam.cameraInfo.exposureState.exposureCompensationRange
            val clamped = index.coerceIn(range.lower, range.upper)
            exposureIndex = clamped
            cam.cameraControl.setExposureCompensationIndex(clamped)
            prefs().edit().putInt(PREF_EXPOSURE_INDEX, clamped).apply()
        } catch (_: Throwable) {
        }
    }

    private fun exposureSlider(): ExposureSliderView =
        findViewById(R.id.exposureSlider)

    /** Analyse one camera frame for auto-capture and for the
     *  dynamic edge overlay. Runs on a background thread,
     *  throttled to about 4 frames a second so the frame tracks
     *  the page smoothly without ever lagging the preview. When a document is detected and its
     *  corners stay still, the capture ring around the capture
     *  button fills smoothly over the user's hold time (menu:
     *  2 / 3 / 5 seconds); full means the page is held
     *  correctly — capture it automatically, the way
     *  professional scanners do. The ring is driven by a
     *  ValueAnimator, so the analysis only starts and stops
     *  it — it never drives a frame. */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            if (!autoCaptureOn || isCapturing) {
                smoothCorners = null
                confirmCount = 0
                edgeOverlay().clear()
                cancelCaptureRing()
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastAnalysisMs < 250) return
            if (now - lastCaptureMs < 3000) return
            lastAnalysisMs = now
            val bitmap = imageProxy.toBitmap()
            val corners = DocumentDetector.detectCorners(bitmap)
            bitmap.recycle()
            if (corners == null) {
                // One missed frame must not wipe the box and the
                // bar — detection flickers for a frame now and
                // then (a hand, a glare). Keep the last frame for
                // a longer grace (v0.1.54: 1000 ms, up from 600);
                // only a real loss resets. This is what makes
                // the frame look smooth instead of blinking.
                if (now - lastDetectMs < 1000) return
                steadyStartMs = 0
                lastCorners = null
                smoothCorners = null
                confirmCount = 0
                edgeOverlay().clear()
                cancelCaptureRing()
                return
            }
            lastDetectMs = now
            // Smooth the corners: the raw detection jitters a
            // few pixels every frame, which made the box shake
            // and the hold never look steady (the bar kept
            // resetting). An exponential moving average settles
            // the frame; the overlay and the steadiness check
            // both use the smoothed corners. (v0.1.54: alpha
            // 0.25, down from 0.35 — the frame glides more
            // gently, which is what "smooth" means here.)
            val prevSmooth = smoothCorners
            val smoothed = if (prevSmooth == null) {
                corners.copyOf()
            } else {
                FloatArray(8) { i -> prevSmooth[i] + 0.25f * (corners[i] - prevSmooth[i]) }
            }
            smoothCorners = smoothed
            // Confirm the document: only show the frame once the
            // SAME quad has been found on two frames in a row.
            // A one-frame flicker never draws a box.
            val prevConfirm = lastCorners
            confirmCount = if (prevConfirm == null) {
                1
            } else {
                var maxMove = 0f
                for (i in smoothed.indices) {
                    maxMove = maxOf(maxMove, abs(smoothed[i] - prevConfirm[i]))
                }
                if (maxMove < 20f) confirmCount + 1 else 1
            }
            // A document is in the frame — draw its real edges.
            // (Only once confirmed, so the box never flashes on
            // a single-frame flicker.)
            if (confirmCount >= 2) {
                edgeOverlay().updateCorners(
                    smoothed,
                    imageProxy.imageInfo.rotationDegrees,
                    imageProxy.width,
                    imageProxy.height
                )
            }
            val prev = lastCorners
            val firstSeen = prev == null
            lastCorners = smoothed
            if (firstSeen) {
                runOnUiThread {
                    findViewById<TextView>(R.id.tvStatus).text = "Document found — hold steady…"
                }
            }
            // How far did the (smoothed) corners move since
            // the last check?
            var stable = false
            if (prev != null) {
                var maxMove = 0f
                for (i in smoothed.indices) {
                    maxMove = maxOf(maxMove, abs(smoothed[i] - prev[i]))
                }
                // The analysis frame is now 320x240 (half the old
                // size), so the same real movement is half the
                // pixels — 12 px here is the old 25 px.
                stable = maxMove < 12f
            }
            if (!stable) {
                // Moved — the hold starts over, the ring empties.
                steadyStartMs = 0
                cancelCaptureRing()
                return
            }
            val justStarted = steadyStartMs == 0L
            if (justStarted) {
                steadyStartMs = now
                runOnUiThread {
                    findViewById<TextView>(R.id.tvStatus).text = "Document found — hold steady…"
                }
            }
            // Still too soon: the page has only just stopped
            // moving. Wait for the steady hold to pass before
            // the ring starts, so the capture doesn't fire the
            // instant the document appears. (The ring itself
            // still runs for the chosen delay — 2 / 3 / 5 s —
            // after the hold.)
            if (now - steadyStartMs < STEADY_HOLD_MS) {
                cancelCaptureRing()
                return
            }
            // The ring fills smoothly on its own (a ValueAnimator
            // on the UI thread); full means capture. The analysis
            // only starts it — it never drives a frame, so the
            // old bar's lag is gone.
            startCaptureRing()
        } catch (_: Throwable) {
            // Analysis must never crash the camera.
        } finally {
            imageProxy.close()
        }
    }

    private fun capturePhoto() {
        val capture = imageCapture
        if (capture == null || isCapturing) {
            if (capture == null) {
                Toast.makeText(this, "Camera is not ready yet", Toast.LENGTH_SHORT).show()
            }
            return
        }
        isCapturing = true
        lastCaptureMs = System.currentTimeMillis()
        steadyStartMs = 0
        lastCorners = null
        smoothCorners = null
        confirmCount = 0
        edgeOverlay().clear()
        cancelCaptureRing()
        hideFocusUi()
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Capturing…"
        val photoFile = File(cacheDir, "scan_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        // The shutter click — only when the Sound setting is on.
        ShutterSound.play(this)
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    runOnUiThread { status.text = "Capture failed — try again" }
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    isCapturing = false
                    openCrop(photoFile)
                }
            }
        )
    }

    private fun openCrop(photoFile: File) {
        val intent = Intent(this, CropActivity::class.java)
        intent.putExtra(CropActivity.EXTRA_PHOTO_PATH, photoFile.absolutePath)
        // Batch: after the crop, back to the camera for the
        // next capture — no filter screen per page. The filters
        // are picked at the end, for the whole batch.
        intent.putExtra(CropActivity.EXTRA_SKIP_ENHANCE, mode == Mode.BATCH)
        // The batch so far (for "Apply to all pages") — the
        // RAW files (v0.1.77), so a chained Enhance filters
        // from raw, never from an already-filtered file.
        intent.putStringArrayListExtra(
            EnhanceActivity.EXTRA_PAGE_PATHS,
            ArrayList(pages.map { rawPathFor(it) })
        )
        cropLauncher.launch(intent)
    }

    private fun openCropFromUri(uri: Uri) {
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Adding photo…"
        Thread {
            try {
                val input = FileUtils.copyUriToCache(this, uri, "scan_page")
                runOnUiThread { openCrop(input) }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Couldn't add that photo — try again" }
            }
        }.start()
    }

    private fun updatePagesLabel() {
        // No Done button any more (v0.1.93) — the thumbnail
        // with the page count is the way forward. The right
        // side of the capture row follows the state (Batch:
        // thumbnail + count after the first capture, Import
        // Images before it) — keep it in sync here so no call
        // site can forget it.
        updateRightSide()
    }

    /** Leave the scanner (Back button or the Close X).
     *  In Batch with pages captured, ask first (v0.1.74) —
     *  the batch is a draft, and leaving discards it. With
     *  no pages (or in Single, where a capture goes straight
     *  to Enhance), leave at once. */
    private fun attemptLeave() {
        if (mode == Mode.BATCH && pages.isNotEmpty()) {
            showLeaveBatchDialog()
        } else {
            finish()
        }
    }

    /** "Discard this batch?" warning (v0.1.74, CamScanner-style
     *  centred white card, same as the export discard dialog).
     *  Cancel keeps editing (the dialog just closes); Discard
     *  leaves the scanner and the draft batch is gone. */
    private fun showLeaveBatchDialog() {
        if (leaveDialog?.isShowing == true) return
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_discard)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.85).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        val n = pages.size
        dialog.findViewById<TextView>(R.id.tvDiscardTitle).text = "Discard batch?"
        dialog.findViewById<TextView>(R.id.tvDiscardMessage).text =
            if (n == 1) "You have 1 scanned page in this batch. Leave now and discard it?"
            else "You have $n scanned pages in this batch. Leave now and discard them?"
        dialog.findViewById<View>(R.id.btnDiscardCancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnDiscardConfirm).setOnClickListener {
            dialog.dismiss()
            finish()
        }
        dialog.setCancelable(true)
        dialog.setOnDismissListener { leaveDialog = null }
        leaveDialog = dialog
        dialog.show()
    }

    /** Save button: Batch reviews pages first, Single goes
     *  straight to the export screen. */
    private fun onSavePdfClicked() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Scan at least one page first", Toast.LENGTH_SHORT).show()
            return
        }
        // Batch: Done goes to the Filter (Enhance) section
        // directly — not to the Pages / save screen. Single:
        // straight to export (it already went through Enhance
        // after its one capture).
        if (mode == Mode.BATCH) openBatchEnhance() else saveAsPdf()
    }

    /** Batch Done → Filter (Enhance) for the first page, with
     *  "Apply to all pages" already ticked. The Enhance screen
     *  lets the user swipe through every page, pick a filter
     *  per page (or one for all), and re-crop a page. */
    private fun openBatchEnhance() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Scan at least one page first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, EnhanceActivity::class.java)
        // Enhance works from the RAW files (v0.1.77) — a page
        // already enhanced by a thumbnail review must be
        // filtered from its raw source, never from the
        // filtered file, or the filter stacks and the image
        // comes out overexposed.
        intent.putExtra(EnhanceActivity.EXTRA_IMAGE_PATH, rawPathFor(pages[0]))
        intent.putStringArrayListExtra(
            EnhanceActivity.EXTRA_PAGE_PATHS,
            ArrayList(pages.map { rawPathFor(it) })
        )
        intent.putExtra(EnhanceActivity.EXTRA_APPLY_ALL_CHECKED, true)
        intent.putExtra(EnhanceActivity.EXTRA_CONFIRM_LABEL, "Finish")
        enhanceLauncher.launch(intent)
    }

    /** Right side of the capture row (v0.1.74): in Batch, once
     *  the first page is captured, Import Images goes away and
     *  a small thumbnail with the pages count takes its place
     *  — like CamScanner. Tapping it is the way forward
     *  (v0.1.93): it opens the Filter (Enhance) screen, then
     *  Preview, then Export — there is no Done button any
     *  more. In Single (or with no pages yet), Import Images
     *  stays. Only the visibility and the count text change
     *  here — the thumbnail image itself is set by
     *  refreshPagesThumb(). */
    private fun updateRightSide() {
        val importBtn = findViewById<LinearLayout>(R.id.btnImportImages)
        val thumbContainer = findViewById<LinearLayout>(R.id.pagesThumbContainer)
        if (mode == Mode.BATCH && pages.isNotEmpty()) {
            importBtn.visibility = View.GONE
            thumbContainer.visibility = View.VISIBLE
            val n = pages.size
            findViewById<TextView>(R.id.tvPagesCount).text =
                if (n == 1) "1 page" else "$n pages"
        } else {
            importBtn.visibility = View.VISIBLE
            thumbContainer.visibility = View.GONE
        }
    }

    /** Set the small thumbnail beside the Batch pages count to
     *  the most recent page (v0.1.74). Decoded off the UI
     *  thread so it never hitches. */
    private fun refreshPagesThumb() {
        if (mode != Mode.BATCH || pages.isEmpty()) return
        val lastFile = pages.last()
        Thread {
            val bmp = decodeThumb(lastFile)
            runOnUiThread {
                if (mode == Mode.BATCH && pages.isNotEmpty()) {
                    val iv = findViewById<ImageView>(R.id.ivPagesThumb)
                    if (bmp != null) iv.setImageBitmap(bmp)
                }
            }
        }.start()
    }

    /** Batch mode: review, reorder and save all pages. */
    private fun openPages() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Scan at least one page first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, PagesActivity::class.java)
        intent.putStringArrayListExtra(
            PagesActivity.EXTRA_PAGE_PATHS,
            ArrayList(pages.map { it.absolutePath })
        )
        intent.putExtra(PagesActivity.EXTRA_DOC_NAME, docName)
        // A Batch from this scanner picks its filters at the
        // end (on Save, in the Pages screen); a Book Scan batch
        // picks them earlier, in its own Enhance step before
        // the Preview (v0.1.71).
        intent.putExtra(PagesActivity.EXTRA_BATCH_ENHANCE, true)
        pagesLauncher.launch(intent)
    }

    /** Batch after Enhance (v0.1.69): the Preview screen.
     *  Filters are already picked, so EXTRA_BATCH_ENHANCE is
     *  false — Save in the Preview goes straight to Export
     *  instead of opening Enhance again. The user reorders
     *  (long-press drag), rotates, deletes, or taps a page for
     *  full-screen preview, then Saves. Back keeps the pages
     *  on the camera. */
    private fun openPagesAfterEnhance() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Scan at least one page first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, PagesActivity::class.java)
        intent.putStringArrayListExtra(
            PagesActivity.EXTRA_PAGE_PATHS,
            ArrayList(pages.map { it.absolutePath })
        )
        intent.putExtra(PagesActivity.EXTRA_DOC_NAME, docName)
        intent.putExtra(PagesActivity.EXTRA_BATCH_ENHANCE, false)
        pagesLauncher.launch(intent)
    }

    private fun saveAsPdf() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Scan at least one page first", Toast.LENGTH_SHORT).show()
            return
        }
        // Export opens as its OWN SCREEN (PdfExportActivity).
        // While it is open this activity stops, CameraX releases
        // the camera, and no scan runs in the background.
        val name = docName ?: "Hakunaa Scan"
        val intent = Intent(this, PdfExportActivity::class.java)
        intent.putStringArrayListExtra(
            PdfExportActivity.EXTRA_PAGE_PATHS,
            ArrayList(pages.map { it.absolutePath })
        )
        intent.putExtra(PdfExportActivity.EXTRA_DOC_NAME, name)
        exportLauncher.launch(intent)
    }
}
