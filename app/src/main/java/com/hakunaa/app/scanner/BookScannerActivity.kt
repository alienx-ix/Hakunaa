package com.hakunaa.app.scanner

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.hakunaa.app.core.StatusBar

/**
 * Book Scan — scan a whole book, two pages at a time.
 *
 * The phone is held in LANDSCAPE so both pages of an open book
 * fit side by side. A dashed spine line splits them into A
 * (left page, 1st) and B (right page, 2nd); the line is MANUAL
 * only (v0.1.71) — the user drags it to the fold, it never
 * moves on its own, and it stays put between spreads.
 *
 * - Double (default): one capture takes BOTH pages at once —
 *   A first, then B — each saved RAW (v0.1.77) and filtered
 *   exactly once in Enhance. Flip the book, capture again,
 *   and the pages keep appending in order.
 * - Single: tap the page to scan (A or B), then capture takes
 *   only that page.
 *
 * Capture is MANUAL (a book's pages change while you flip, so
 * auto-capture would fire at the wrong moment). After every
 * capture a thumbnail of the page flies from the centre to the
 * Pages button (v0.1.73) and the count ticks up when it lands,
 * like a professional scanner. When the user is done — Pages or
 * Save PDF — the book goes through the Filter (Enhance) screen
 * first, like a Batch (v0.1.71): pick a filter per page (or one
 * for all), crop or adjust a page, then the Preview (Pages)
 * screen to reorder, rotate or delete, then Export. Review and
 * reorder on the Pages screen; save the whole book as one PDF.
 *
 * A 2D Spirit Level (v0.1.102) sits right next to the Capture
 * button, exactly like Single/Batch — display-only, always on.
 *
 * The camera permission is asked the same way as the main
 * scanner (it is normally already granted by then).
 *
 * No cloud, no account. Files never leave the phone.
 */
class BookScannerActivity : AppCompatActivity() {

    private val pages = mutableListOf<File>()
    private var leaveDialog: Dialog? = null
    // Raw file for each page (v0.1.77), as in ScannerActivity:
    // the cropped file a page came from, keyed by the page's
    // CURRENT file path. Enhance must filter from the raw
    // file, never from an already-filtered file, or the
    // filter stacks and the image comes out overexposed.
    private val rawPaths = mutableMapOf<String, String>()

    /** The raw (unfiltered) file for a page. */
    private fun rawPathFor(page: File): String =
        rawPaths[page.absolutePath] ?: page.absolutePath

    /** Apply Enhance's result (v0.1.77): for each returned
     *  (source, enhanced) pair, find the page whose raw file
     *  is that source and replace it with the enhanced file,
     *  remembering the raw file for next time. */
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
    // The bitmap shown in the small persistent thumbnail
    // beside the Pages count (v0.1.74) — kept so it can be
    // recycled when a newer capture replaces it.
    private var lastPageThumbBmp: Bitmap? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

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
    private var isCapturing = false
    private var doubleMode = true
    private var selectedSide = SpineLineView.SIDE_LEFT
    private var spineFraction = 0.5f

    // Spirit Level (v0.1.102): the 2D bullseye next to the
    // Capture button, exactly like Single/Batch. Display-only,
    // always on — it shows the phone's tilt live so the user
    // holds the book level. Same sensor pattern as the main
    // scanner, but no Settings toggle here.
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

    private fun spineLine(): SpineLineView =
        findViewById(R.id.spineLine)

    /** Hide the status bar (v0.1.108): Book is a full-screen
     *  camera and the user does not want the clock / battery
     *  strip on it. A swipe from the top reveals it briefly
     *  (transient), then it hides again. Called in onCreate
     *  and onResume so it stays hidden on every return. */
    private fun hideStatusBar() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.statusBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this@BookScannerActivity, "Camera permission is needed to scan. Allow it in Settings.", Toast.LENGTH_LONG).show()
            }
        }

    // Book → Filter (Enhance) first (v0.1.71): the Book flow
    // was capture → Pages/Export with no Filter screen, so a
    // book page could never change its filter or be cropped /
    // adjusted. Now Pages and Save PDF both open Enhance for
    // the first page with "Apply to all pages" already ticked
    // (one filter covers the book; the user can swipe pages
    // and pick a filter per page, or un-tick it). Back from
    // Enhance cancels — the pages stay on the camera. On save,
    // the first page is replaced by the enhanced file and the
    // PREVIEW screen (Pages) opens: reorder (long-press drag),
    // rotate, delete, full-screen preview, then Save → Export.
    private val enhanceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Enhance returns a new file per page, matched
                // to its raw source (v0.1.77) — each page is
                // replaced in its own slot. Fall back to the
                // old single path.
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

    private val pagesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // PDF saved from the Pages screen — clear and stay on camera.
                pages.clear()
                rawPaths.clear()
                OverlayStore.clear()
                docName = null
                updateLabels()
                Toast.makeText(this@BookScannerActivity, "PDF saved", Toast.LENGTH_SHORT).show()
            } else if (result.resultCode == PdfExportActivity.RESULT_DISCARD) {
                // Discard confirmed on the export screen reached
                // via the Preview (v0.1.71): drop every page so
                // the camera comes back fresh.
                pages.clear()
                rawPaths.clear()
                OverlayStore.clear()
                docName = null
                updateLabels()
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
        updateLabels()
    }

    /** "Discard this book?" warning (v0.1.92, same as the
     *  Batch leave warning): with pages scanned, leaving
     *  asks first — the pages are NOT saved as a draft, they
     *  are discarded. Cancel keeps scanning. */
    private fun attemptLeave() {
        if (pages.isNotEmpty()) {
            showLeaveBookDialog()
        } else {
            finish()
        }
    }

    private fun showLeaveBookDialog() {
        if (leaveDialog?.isShowing == true) return
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_discard)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // Width from the SHORTER side (v0.1.104): in landscape
        // widthPixels is the LONG side (2048), so 85% of it
        // made the dialog nearly full-screen. Single/Batch
        // (portrait) use 85% of the short side — the same
        // compact professional dialog. Use the smaller
        // dimension here so Book matches Single exactly.
        val shorter = minOf(
            resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels
        )
        val width = (shorter * 0.85).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        val n = pages.size
        dialog.findViewById<TextView>(R.id.tvDiscardTitle).text = "Discard book?"
        dialog.findViewById<TextView>(R.id.tvDiscardMessage).text =
            if (n == 1) "You have 1 scanned page in this book. Leave now and discard it?"
            else "You have $n scanned pages in this book. Leave now and discard them?"
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

    override fun onBackPressed() {
        attemptLeave()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_scanner)

        // Status bar (v0.1.108): HIDDEN in Book — the user
        // does not want the clock / battery strip on the
        // camera screen. The preview already runs truly
        // edge-to-edge (v0.1.102, no inset padding), so
        // hiding the bar just frees the top strip; the
        // buttons keep their explicit margins in the layout.
        // A swipe from the top reveals it briefly.
        StatusBar.setup(this, false, applyInsets = false)
        hideStatusBar()
        docName = "Hakunaa Book " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.US).format(Date())

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { attemptLeave() }
        findViewById<ImageButton>(R.id.btnTorch).setOnClickListener { toggleTorch() }
        findViewById<ImageButton>(R.id.btnCapture).setOnClickListener { captureBook() }
        // Exit (v0.1.104, Book only) — right by the Capture
        // button, so leaving does not mean hunting for the
        // corner arrow. Same leave warning as Back.
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnExit)
            .setOnClickListener { attemptLeave() }
        findViewById<TextView>(R.id.tvSingle).setOnClickListener { setDoubleMode(false) }
        findViewById<TextView>(R.id.tvDouble).setOnClickListener { setDoubleMode(true) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPages)
            .setOnClickListener { openPages() }
        // Thumbnail (v0.1.104) — shows the LAST capture
        // full-screen, so the user can check the page they
        // just scanned without leaving the camera.
        findViewById<ImageView>(R.id.ivLastPageThumb)
            .setOnClickListener { openLastCapture() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSavePdf)
            .setOnClickListener { saveAsPdf() }

        // Spine line: MANUAL only (v0.1.71) — a drag moves it,
        // and it never moves on its own; there is no automatic
        // spine follow. A tap picks a side in Single mode.
        // Double mode takes both pages, so no selection.
        spineLine().onSpineChanged = { fraction ->
            spineFraction = fraction
        }
        spineLine().onSideTapped = { side ->
            if (!doubleMode) {
                selectedSide = side
                spineLine().selectedSide = side
            }
        }
        // The spine line lives inside the camera image (the
        // fitCenter bars on the sides are not part of the book).
        // Recompute it on EVERY layout change (v0.1.102) — the
        // old one-shot post{} went stale when the preview was
        // re-laid out (camera bind, inset change), so the spine
        // drifted off the real fold and drags/splits landed in
        // the wrong place.
        val previewView = findViewById<PreviewView>(R.id.previewView)
        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            spineLine().contentRect = previewContentRect()
        }
        previewView.post {
            spineLine().contentRect = previewContentRect()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        updateLabels()
    }

    override fun onResume() {
        super.onResume()
        cameraResumed = true
        // Keep the status bar hidden on every return (v0.1.108)
        // — coming back from Enhance / Pages must not bring
        // the clock strip back onto the camera.
        hideStatusBar()
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
        stopSpiritLevel()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
        }
    }

    /** The spirit level only listens while this screen is
     *  visible — a sensor listener left running in the
     *  background would burn battery. */
    private fun startSpiritLevel() {
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

    private fun setDoubleMode(double: Boolean) {
        doubleMode = double
        // (The locals are named *_view: a local called "double"
        // would shadow the parameter and break the if below.)
        val singleView = findViewById<TextView>(R.id.tvSingle)
        val doubleView = findViewById<TextView>(R.id.tvDouble)
        if (double) {
            doubleView.background = getDrawable(R.drawable.mode_single_double_selected)
            doubleView.setTextColor(getColor(android.R.color.white))
            doubleView.setTypeface(null, android.graphics.Typeface.BOLD)
            singleView.background = null
            singleView.setTextColor(getColor(android.R.color.white))
            singleView.setTypeface(null, android.graphics.Typeface.NORMAL)
            spineLine().selectedSide = SpineLineView.SIDE_NONE
        } else {
            singleView.background = getDrawable(R.drawable.mode_single_double_selected)
            singleView.setTextColor(getColor(android.R.color.white))
            singleView.setTypeface(null, android.graphics.Typeface.BOLD)
            doubleView.background = null
            doubleView.setTextColor(getColor(android.R.color.white))
            doubleView.setTypeface(null, android.graphics.Typeface.NORMAL)
            spineLine().selectedSide = selectedSide
        }
    }

    private fun toggleTorch() {
        torchOn = !torchOn
        // A phone without a torch must not crash the scanner.
        try {
            camera?.cameraControl?.enableTorch(torchOn)
        } catch (_: Throwable) {
        }
        findViewById<ImageButton>(R.id.btnTorch).setImageResource(
            if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
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
                    // The sensor's native 4:3 aspect, landscape —
                    // the widest field of view for a spread.
                    .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                    .build().also {
                        it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
                    }
                // ORIGINAL CAMERA QUALITY: MAXIMIZE_QUALITY, JPEG
                // 100, full sensor resolution.
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .build().also {
                        it.flashMode = ImageCapture.FLASH_MODE_AUTO
                    }
                // No live spine analysis (v0.1.71): the spine
                // line is MANUAL only — the user drags it to
                // the fold. No analysis use case is bound, so
                // the camera opens on ANY phone and no CPU is
                // spent tracking the spine.
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                cameraBound = true
                try {
                    if (torchOn) camera?.cameraControl?.enableTorch(true)
                } catch (_: Throwable) {
                }
            } catch (_: Throwable) {
                Toast.makeText(this@BookScannerActivity, "Couldn't start the camera — try again", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** The preview is 4:3 fitCenter inside the full-screen
     *  PreviewView, so the sides are black bars in landscape.
     *  Only the image rect holds the book. */
    private fun previewContentRect(): RectF {
        val previewView = findViewById<PreviewView>(R.id.previewView)
        val w = previewView.width.toFloat()
        val h = previewView.height.toFloat()
        // Landscape preview: 4 wide, 3 tall.
        val contentW = h * 4f / 3f
        return if (contentW <= w) {
            RectF((w - contentW) / 2f, 0f, (w + contentW) / 2f, h)
        } else {
            val contentH = w * 3f / 4f
            RectF(0f, (h - contentH) / 2f, w, (h + contentH) / 2f)
        }
    }

    private fun captureBook() {
        val capture = imageCapture
        if (capture == null || isCapturing) {
            if (capture == null) {
                Toast.makeText(this, "Camera is not ready yet", Toast.LENGTH_SHORT).show()
            }
            return
        }
        isCapturing = true
        val photoFile = File(cacheDir, "book_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        // The shutter click — only when the Sound setting is on.
        ShutterSound.play(this)
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    runOnUiThread { Toast.makeText(this@BookScannerActivity, "Capture failed — try again", Toast.LENGTH_SHORT).show() }
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Thread { processBookPhoto(photoFile) }.start()
                }
            }
        )
    }

    /** Small downsampled bitmap of a page file, for the
     *  fly-to-Pages thumbnail (longest side 256). Null when
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

    /** Capture animation (v0.1.73), like a professional
     *  scanner: a thumbnail of the capture appears at the
     *  centre of the screen and flies to the Pages button,
     *  shrinking as it goes — and the count ticks up the
     *  moment it lands. Runs on the UI thread. isCapturing
     *  is released only when the animation ends, so no second
     *  capture can start mid-flight.
     *
     *  `lastBmp` (v0.1.74) is a small thumbnail of the most
     *  recent page — when the fly lands, it becomes the small
     *  persistent thumbnail beside the Pages count, like
     *  CamScanner (not just the number changing). */
    private fun playCaptureFlyAnimation(thumbBmp: Bitmap?, newCount: Int, lastBmp: Bitmap?) {
        if (thumbBmp == null) {
            isCapturing = false
            updateLabels()
            setLastPageThumb(lastBmp)
            return
        }
        val thumb = findViewById<ImageView>(R.id.ivCaptureThumb)
        val pagesBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPages)
        thumb.setImageBitmap(thumbBmp)
        thumb.alpha = 1f
        thumb.scaleX = 1f
        thumb.scaleY = 1f
        thumb.translationX = 0f
        thumb.translationY = 0f
        thumb.visibility = View.VISIBLE
        // Wait for the thumbnail to be laid out (it was GONE),
        // then fly its centre onto the Pages button's centre.
        thumb.post {
            val thumbLoc = IntArray(2)
            val btnLoc = IntArray(2)
            thumb.getLocationOnScreen(thumbLoc)
            pagesBtn.getLocationOnScreen(btnLoc)
            val dx = (btnLoc[0] + pagesBtn.width / 2f) - (thumbLoc[0] + thumb.width / 2f)
            val dy = (btnLoc[1] + pagesBtn.height / 2f) - (thumbLoc[1] + thumb.height / 2f)
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
                    isCapturing = false
                    updateLabels()
                    setLastPageThumb(lastBmp)
                }
                .start()
        }
    }

    /** Show `bmp` as the small persistent thumbnail beside
     *  the Pages count (v0.1.74). A null bitmap hides it.
     *  The previous thumbnail bitmap is recycled. */
    private fun setLastPageThumb(bmp: Bitmap?) {
        val lastThumb = findViewById<ImageView>(R.id.ivLastPageThumb)
        try {
            lastPageThumbBmp?.recycle()
        } catch (_: Throwable) {
        }
        lastPageThumbBmp = bmp
        if (bmp == null) {
            lastThumb.visibility = View.GONE
            lastThumb.setImageBitmap(null)
        } else {
            lastThumb.setImageBitmap(bmp)
            lastThumb.visibility = View.VISIBLE
        }
    }

    /** Split the spread, enhance each page, and add them in
     *  order (A then B in Double; the tapped side in Single).
     *  Runs off the UI thread — the split + enhance of two
     *  full-resolution pages takes a moment. On success the
     *  pages join the book at once and a thumbnail of the
     *  capture flies to the Pages button (v0.1.73) — the count
     *  ticks up when it lands, like a professional scanner. */
    private fun processBookPhoto(photoFile: File) {
        try {
            // Decode WITH the EXIF rotation (v0.1.103) — the
            // plain decodeFile ignored it, so on this phone the
            // spread came out in the sensor's raw orientation
            // (portrait) instead of the landscape the user held
            // it in. Splitting that portrait bitmap gave wrong
            // halves, and the A/B pages never came out as
            // proper portrait pages — "completely malfunction".
            // The main scanner hit the exact same bug (v0.1.66)
            // and this same helper fixed it there.
            val bitmap = FileUtils.decodeBitmapWithExif(photoFile.absolutePath)
            if (bitmap == null) {
                runOnUiThread {
                    isCapturing = false
                    Toast.makeText(this@BookScannerActivity, "Couldn't read that photo — try again", Toast.LENGTH_SHORT).show()
                }
                return
            }
            val split = DocumentDetector.splitSpread(bitmap, spineFraction)
            bitmap.recycle()
            if (split == null) {
                runOnUiThread {
                    isCapturing = false
                    Toast.makeText(this@BookScannerActivity, "Couldn't split the pages — try again", Toast.LENGTH_SHORT).show()
                }
                return
            }
            val (left, right) = split
            val newPages = mutableListOf<File>()
            // The split pages are saved RAW (v0.1.77) — no
            // filter at capture. Enhance (which the Book flow
            // always goes through) applies NO_SHADOW once.
            // Filtering here AND in Enhance stacked the filter
            // and the pages came out overexposed.
            if (doubleMode || selectedSide == SpineLineView.SIDE_LEFT) {
                val file = File(cacheDir, "book_page_${System.currentTimeMillis()}_a.jpg")
                FileOutputStream(file).use { left.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                newPages.add(file)
            }
            if (doubleMode || selectedSide == SpineLineView.SIDE_RIGHT) {
                val file = File(cacheDir, "book_page_${System.currentTimeMillis()}_b.jpg")
                FileOutputStream(file).use { right.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                newPages.add(file)
            }
            left.recycle()
            right.recycle()
            photoFile.delete()
            // The spine line stays where the user put it
            // (manual only) — it is not reset between spreads.
            // A small thumbnail of the first new page, for the
            // fly-to-Pages animation, and one of the most
            // recent page, for the small persistent thumbnail
            // beside the count (v0.1.74) — both decoded here,
            // off the UI thread, so the animation never
            // hitches.
            val thumbBmp = decodeThumb(newPages[0])
            val lastBmp = decodeThumb(newPages.last())
            runOnUiThread {
                pages.addAll(newPages)
                // Freshly split pages are raw — each is its own
                // raw source (v0.1.77).
                for (file in newPages) rawPaths[file.absolutePath] = file.absolutePath
                // The count ticks up only when the thumbnail
                // lands on the Pages button (v0.1.73) — the
                // fly animation finishes that (or, if the
                // thumbnail could not be made, right away).
                playCaptureFlyAnimation(thumbBmp, newPages.size, lastBmp)
            }
        } catch (_: Throwable) {
            runOnUiThread {
                isCapturing = false
                Toast.makeText(this@BookScannerActivity, "Something went wrong — try again", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateLabels() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPages).text =
            "Pages (${pages.size})"
        findViewById<View>(R.id.btnSavePdf).visibility =
            if (pages.isNotEmpty()) View.VISIBLE else View.GONE
        // No pages left (saved / discarded) — the small
        // thumbnail beside the count goes away too.
        if (pages.isEmpty()) {
            val lastThumb = findViewById<ImageView>(R.id.ivLastPageThumb)
            lastThumb.visibility = View.GONE
            lastThumb.setImageBitmap(null)
            try {
                lastPageThumbBmp?.recycle()
            } catch (_: Throwable) {
            }
            lastPageThumbBmp = null
        }
    }

    /** Book → Filter (Enhance) for the first page, with
     *  "Apply to all pages" already ticked. The Enhance screen
     *  lets the user swipe through every page, pick a filter
     *  per page (or one for all), and re-crop a page. */
    private fun openBookEnhance() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Scan at least one page first", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, EnhanceActivity::class.java)
        // Enhance works from the RAW files (v0.1.77) — a page
        // already enhanced must be filtered from its raw
        // source, never from the filtered file.
        intent.putExtra(EnhanceActivity.EXTRA_IMAGE_PATH, rawPathFor(pages[0]))
        intent.putStringArrayListExtra(
            EnhanceActivity.EXTRA_PAGE_PATHS,
            ArrayList(pages.map { rawPathFor(it) })
        )
        intent.putExtra(EnhanceActivity.EXTRA_APPLY_ALL_CHECKED, true)
        enhanceLauncher.launch(intent)
    }

    /** Thumbnail tap (v0.1.104): show the LAST capture
     *  full-screen, so the user can check the page they just
     *  scanned without leaving the camera. */
    private fun openLastCapture() {
        if (pages.isEmpty()) return
        val last = pages.last()
        val intent = Intent(this, PagePreviewActivity::class.java)
        intent.putExtra(PagePreviewActivity.EXTRA_PAGE_PATH, last.absolutePath)
        intent.putExtra(
            PagePreviewActivity.EXTRA_PAGE_LABEL,
            "Page ${pages.size}"
        )
        startActivity(intent)
    }

    /** Book after Enhance (v0.1.71): the Preview screen.
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

    private fun openPages() {
        // Pages now goes through the Filter (Enhance) screen
        // first (v0.1.71) — a book page gets its filters and
        // menus there, then the Preview opens.
        openBookEnhance()
    }

    private fun saveAsPdf() {
        // Save PDF (v0.1.104) — straight to the Preview,
        // showing the pages from the FIRST one. The Preview's
        // Save goes to Export. Filters are still available
        // through the Pages button, which goes via Enhance.
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
}
