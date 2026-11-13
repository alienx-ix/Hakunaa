package com.hakunaa.app.scanner

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
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
import android.view.View
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
    private var docName: String? = null
    private var mode = Mode.SINGLE
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraExecutor: ExecutorService? = null

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
    private var hdOn = true
    private var autoCaptureOn = true
    private var gridOn = false
    private var isCapturing = false
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

    companion object {
        private const val PREFS_NAME = "hakunaa_prefs"
        private const val PREF_AUTO_CAPTURE_DELAY_MS = "auto_capture_delay_ms"
        private const val PREF_EXPOSURE_INDEX = "exposure_index"
        private const val PREF_GRID_ON = "grid_on"
        private const val PREF_AUTO_CAPTURE_ON = "auto_capture_on"
        private const val PREF_SOUND_ON = "sound_on"
        private const val PREF_SPIRIT_LEVEL_ON = "spirit_level_on"
        private const val PREF_ORIENTATION = "orientation"
        private const val ORIENTATION_AUTO = "auto"
        private const val ORIENTATION_LANDSCAPE = "landscape"
        private const val ORIENTATION_PORTRAIT = "portrait"
    }

    // Settings (the CamScanner-style panel, opened from the
    // top-bar menu): the shutter sound, the spirit level, and
    // the screen orientation — all saved, so they stay.
    private var soundOn = true
    private var spiritLevelOn = false
    private var orientation = ORIENTATION_AUTO
    private var sensorManager: SensorManager? = null
    private var levelSensor: Sensor? = null
    private var smoothRoll = 0f
    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val roll = SpiritLevelView.rollFromAccelerometer(x, y, z)
            // Low-pass: the bubble must not jitter with every
            // hand tremor.
            smoothRoll += 0.25f * (roll - smoothRoll)
            findViewById<SpiritLevelView>(R.id.spiritLevel).setRoll(smoothRoll)
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
                        docName = result.data?.getStringExtra("doc_name") ?: docName
                        updatePagesLabel()
                        if (mode == Mode.SINGLE) {
                            saveAsPdf()
                        }
                    }
                }
            }
        }

    private val pagesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // PDF saved from the Pages screen — clear and stay on camera.
                pages.clear()
                docName = null
                updatePagesLabel()
                findViewById<TextView>(R.id.tvStatus).text =
                    "Done — PDF saved. Scan another document when ready"
            }
            // RESULT_ADD_PAGE or back: stay on the camera, pages kept.
        }

    // Export is a separate screen: while it is open this
    // activity stops and the camera is released — no scan runs
    // in the background. On save, clear the pages; on cancel,
    // keep them and leave the Save button visible so the user
    // can open export again.
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
                docName = null
                updatePagesLabel()
            } else {
                status.text = "Save cancelled — ${pages.size} page(s) ready, tap Save as PDF when ready"
                updatePagesLabel()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnCapture).setOnClickListener { capturePhoto() }
        findViewById<ImageButton>(R.id.btnFlash).setOnClickListener { toggleTorch() }
        // HD is on by default (full camera quality) — show it teal.
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnHd).apply {
            setTextColor(getColor(R.color.hakunaa_teal))
            strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAuto)
            .setOnClickListener { toggleAutoCapture() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnHd)
            .setOnClickListener { toggleHd() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            showSettingsDialog()
        }
        // Bottom row, left: the Grid lines for framing.
        findViewById<LinearLayout>(R.id.btnGrid).setOnClickListener { toggleGrid() }
        findViewById<LinearLayout>(R.id.btnImportImages).setOnClickListener {
            pickPhoto.launch("image/*")
        }
        findViewById<TextView>(R.id.tvModeSingle).setOnClickListener { setMode(Mode.SINGLE) }
        findViewById<TextView>(R.id.tvModeBatch).setOnClickListener { setMode(Mode.BATCH) }
        // Book mode is its own landscape screen (two pages at
        // once, A then B) — it opens from this tab.
        findViewById<TextView>(R.id.tvModeBook).setOnClickListener {
            startActivity(Intent(this, BookScannerActivity::class.java))
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSavePdf)
            .setOnClickListener { onSavePdfClicked() }

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
        soundOn = prefs().getBoolean(PREF_SOUND_ON, true)
        spiritLevelOn = prefs().getBoolean(PREF_SPIRIT_LEVEL_ON, false)
        orientation = prefs().getString(PREF_ORIENTATION, ORIENTATION_AUTO) ?: ORIENTATION_AUTO
        applyOrientation()
        updateSpiritLevel()
        // A rotation (or the system killing the activity) must
        // not lose the pages already captured.
        savedInstanceState?.getStringArrayList("page_paths")?.let { paths ->
            pages.clear()
            pages.addAll(paths.map { File(it) }.filter { it.exists() })
        }
        savedInstanceState?.getString("doc_name")?.let { docName = it }
        savedInstanceState?.getString("mode")?.let {
            try { mode = Mode.valueOf(it) } catch (_: Throwable) { }
        }
        if (pages.isNotEmpty()) setMode(mode)
        setupTapToFocus()
        updatePagesLabel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            "page_paths", ArrayList(pages.map { it.absolutePath })
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

    private fun updateSpiritLevel() {
        val view = findViewById<SpiritLevelView>(R.id.spiritLevel)
        view.visibility = if (spiritLevelOn) View.VISIBLE else View.GONE
        if (spiritLevelOn) {
            smoothRoll = 0f
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

    private fun updateGridButton() {
        val overlay = findViewById<GridOverlayView>(R.id.gridOverlay)
        overlay.visibility = if (gridOn) View.VISIBLE else View.GONE
        val color = if (gridOn) getColor(R.color.hakunaa_teal) else getColor(android.R.color.white)
        findViewById<ImageView>(R.id.ivGridIcon).setColorFilter(color)
        findViewById<TextView>(R.id.tvGridLabel).setTextColor(color)
    }

    private fun toggleAutoCapture() {
        setAutoCapture(!autoCaptureOn)
    }

    /** The screen orientation from the Settings panel: Auto
     *  (follow the phone), Landscape, or Portrait. Saved, so
     *  it stays. Changing it recreates this screen — the
     *  pages already captured are restored from the saved
     *  state, so nothing is lost. */
    private fun setOrientation(value: String) {
        orientation = value
        prefs().edit().putString(PREF_ORIENTATION, value).apply()
        applyOrientation()
    }

    private fun applyOrientation() {
        requestedOrientation = when (orientation) {
            ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** The Settings panel — the CamScanner-style menu the
     *  top-bar button opens: Auto Capture, Orientation, Grid,
     *  Sound, Spirit Level, and More Settings. A dark rounded
     *  card over the dimmed preview. Every row is a real
     *  setting that takes effect at once. */
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
        val tvOrientationState = view.findViewById<TextView>(R.id.tvOrientationState)
        val btnAuto = view.findViewById<MaterialButton>(R.id.btnOrientationAuto)
        val btnLandscape = view.findViewById<MaterialButton>(R.id.btnOrientationLandscape)
        val btnPortrait = view.findViewById<MaterialButton>(R.id.btnOrientationPortrait)

        fun refreshStates() {
            swAuto.isChecked = autoCaptureOn
            tvAutoState.text = if (autoCaptureOn) "On" else "Off"
            swGrid.isChecked = gridOn
            tvGridState.text = if (gridOn) "On" else "Off"
            swSound.isChecked = soundOn
            tvSoundState.text = if (soundOn) "On" else "Off"
            swLevel.isChecked = spiritLevelOn
            tvLevelState.text = if (spiritLevelOn) "On" else "Off"
            tvOrientationState.text = when (orientation) {
                ORIENTATION_LANDSCAPE -> "Landscape"
                ORIENTATION_PORTRAIT -> "Portrait"
                else -> "Auto"
            }
            val teal = getColor(R.color.hakunaa_teal)
            val grey = getColor(android.R.color.darker_gray)
            fun styleOrientationButton(btn: MaterialButton, selected: Boolean) {
                btn.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                        if (selected) teal else Color.parseColor("#33FFFFFF")
                    )
                btn.setTextColor(getColor(android.R.color.white))
                btn.strokeColor = android.content.res.ColorStateList.valueOf(
                    if (selected) teal else grey
                )
            }
            styleOrientationButton(btnAuto, orientation == ORIENTATION_AUTO)
            styleOrientationButton(btnLandscape, orientation == ORIENTATION_LANDSCAPE)
            styleOrientationButton(btnPortrait, orientation == ORIENTATION_PORTRAIT)
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
            spiritLevelOn = checked
            prefs().edit().putBoolean(PREF_SPIRIT_LEVEL_ON, checked).apply()
            updateSpiritLevel()
            refreshStates()
        }
        btnAuto.setOnClickListener { setOrientation(ORIENTATION_AUTO); refreshStates() }
        btnLandscape.setOnClickListener { setOrientation(ORIENTATION_LANDSCAPE); refreshStates() }
        btnPortrait.setOnClickListener { setOrientation(ORIENTATION_PORTRAIT); refreshStates() }

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
        val swHd = view.findViewById<SwitchMaterial>(R.id.swHd)
        swHd.isChecked = hdOn
        swHd.setOnCheckedChangeListener { _, checked ->
            if (checked != hdOn) toggleHd()
        }
        view.findViewById<ImageButton>(R.id.btnCloseMoreSettings)
            .setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun toggleHd() {
        hdOn = !hdOn
        // Re-bind the camera with the new capture setting.
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnHd)
        val color = if (hdOn) getColor(R.color.hakunaa_teal) else getColor(android.R.color.white)
        btn.setTextColor(color)
        btn.strokeColor = android.content.res.ColorStateList.valueOf(color)
        findViewById<TextView>(R.id.tvStatus).text =
            if (hdOn) "Full quality — original camera resolution"
            else "HD off — captures at 1920x1080"
        startCamera()
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
                val preview = Preview.Builder()
                    // The sensor's NATIVE 4:3 aspect — the widest
                    // field of view, nothing cropped. A 16:9 target
                    // crops the sensor's frame top and bottom, so
                    // the screen still looked slightly zoomed in.
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build().also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
                }
                // ORIGINAL CAMERA QUALITY: MAXIMIZE_QUALITY captures
                // at the sensor's full resolution with full processing
                // (MINIMIZE_LATENCY used the fast, lower-quality path),
                // JPEG quality 100, and no resolution cap while HD is
                // on. HD off caps at 1920x1080 for smaller files.
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                if (!hdOn) {
                    captureBuilder.setTargetResolution(android.util.Size(1920, 1080))
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
                camera = try {
                    val analysis = buildAnalysis(fixedResolution = true)
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture, analysis
                    )
                } catch (_: Throwable) {
                    try {
                        val analysis = buildAnalysis(fixedResolution = false)
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            this, cameraSelector, preview, imageCapture, analysis
                        )
                    } catch (_: Throwable) {
                        analysisOn = false
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            this, cameraSelector, preview, imageCapture
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
     *  taps inside the actual camera image focus the camera. */
    private fun previewContentRect(): RectF {
        val previewView = findViewById<PreviewView>(R.id.previewView)
        val w = previewView.width.toFloat()
        val h = previewView.height.toFloat()
        // Portrait preview: 3 wide, 4 tall.
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
                // a short grace; only a real loss resets.
                if (now - lastDetectMs < 600) return
                steadyStartMs = 0
                lastCorners = null
                smoothCorners = null
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
            // both use the smoothed corners.
            val prevSmooth = smoothCorners
            val smoothed = if (prevSmooth == null) {
                corners.copyOf()
            } else {
                FloatArray(8) { i -> prevSmooth[i] + 0.35f * (corners[i] - prevSmooth[i]) }
            }
            smoothCorners = smoothed
            // A document is in the frame — draw its real edges.
            edgeOverlay().updateCorners(
                smoothed,
                imageProxy.imageInfo.rotationDegrees,
                imageProxy.width,
                imageProxy.height
            )
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
        // The batch so far (for "Apply to all pages").
        intent.putStringArrayListExtra(
            EnhanceActivity.EXTRA_PAGE_PATHS,
            ArrayList(pages.map { it.absolutePath })
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
        findViewById<TextView>(R.id.tvPages).text =
            if (pages.isEmpty()) ""
            else "${pages.size} page(s) scanned"
        // Visible in BOTH modes once a page exists: Batch opens
        // the Pages screen, Single opens export directly — so
        // after cancelling export the user can open it again.
        findViewById<View>(R.id.btnSavePdf).visibility =
            if (pages.isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** Save button: Batch reviews pages first, Single goes
     *  straight to the export screen. */
    private fun onSavePdfClicked() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Scan at least one page first", Toast.LENGTH_SHORT).show()
            return
        }
        if (mode == Mode.BATCH) openPages() else saveAsPdf()
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
        // is already enhanced at capture, so it does not.
        intent.putExtra(PagesActivity.EXTRA_BATCH_ENHANCE, true)
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
