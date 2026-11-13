package com.hakunaa.app.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
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
import com.hakunaa.app.R
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Book Scan — scan a whole book, two pages at a time.
 *
 * The phone is held in LANDSCAPE so both pages of an open book
 * fit side by side. A dashed spine line splits them into A
 * (left page, 1st) and B (right page, 2nd); the line follows
 * the book's real fold automatically (the spine valley from
 * DocumentDetector) and can be dragged to the fold by hand.
 *
 * - Double (default): one capture takes BOTH pages at once —
 *   A first, then B — each perspective-corrected and enhanced
 *   on its own. Flip the book, capture again, and the pages
 *   keep appending in order: next, next, a whole book.
 * - Single: tap the page to scan (A or B), then capture takes
 *   only that page.
 *
 * Capture is MANUAL (a book's pages change while you flip, so
 * auto-capture would fire at the wrong moment). Each page is
 * auto-enhanced with the NO_SHADOW filter at capture (the
 * default, with sharpening). When the user is done — Pages or
 * Save PDF — the book goes through the Filter (Enhance) screen
 * first, like a Batch (v0.1.71): pick a filter per page (or one
 * for all), crop or adjust a page, then the Preview (Pages)
 * screen to reorder, rotate or delete, then Export. Review and
 * reorder on the Pages screen; save the whole book as one PDF.
 *
 * The camera permission is asked the same way as the main
 * scanner (it is normally already granted by then).
 *
 * No cloud, no account. Files never leave the phone.
 */
class BookScannerActivity : AppCompatActivity() {

    private val pages = mutableListOf<File>()
    private var docName: String? = null
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

    private fun spineLine(): SpineLineView =
        findViewById(R.id.spineLine)

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                findViewById<TextView>(R.id.tvStatus).text =
                    "Camera permission is needed to scan. Allow it in Settings, or pick photos from the gallery instead."
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
                val enhancedPath =
                    result.data?.getStringExtra(EnhanceActivity.EXTRA_ENHANCED_PATH)
                if (enhancedPath != null && pages.isNotEmpty()) {
                    val file = File(enhancedPath)
                    if (file.exists()) pages[0] = file
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
                docName = null
                updateLabels()
                findViewById<TextView>(R.id.tvStatus).text =
                    "Done — PDF saved. Scan another book when ready"
            } else if (result.resultCode == PdfExportActivity.RESULT_DISCARD) {
                // Discard confirmed on the export screen reached
                // via the Preview (v0.1.71): drop every page so
                // the camera comes back fresh.
                pages.clear()
                docName = null
                updateLabels()
                findViewById<TextView>(R.id.tvStatus).text =
                    "Discarded — point the camera at an open book and tap Capture"
                Toast.makeText(this, "Discarded", Toast.LENGTH_SHORT).show()
            }
            // RESULT_ADD_PAGE or back: stay on the camera, pages kept.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_scanner)

        docName = "Hakunaa Book " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.US).format(Date())

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnTorch).setOnClickListener { toggleTorch() }
        findViewById<ImageButton>(R.id.btnCapture).setOnClickListener { captureBook() }
        findViewById<TextView>(R.id.tvSingle).setOnClickListener { setDoubleMode(false) }
        findViewById<TextView>(R.id.tvDouble).setOnClickListener { setDoubleMode(true) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPages)
            .setOnClickListener { openPages() }
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
                findViewById<TextView>(R.id.tvStatus).text =
                    if (side == SpineLineView.SIDE_LEFT) "Single — left page (A) selected, tap Capture"
                    else "Single — right page (B) selected, tap Capture"
            }
        }
        // The spine line lives inside the camera image (the
        // fitCenter bars on the sides are not part of the book).
        findViewById<PreviewView>(R.id.previewView).post {
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
        // Returning from another screen left the preview BLACK
        // — the Preview use case never re-attached to the
        // recreated surface. Rebind fresh on every resume so
        // the camera always comes back.
        if (!cameraBound) startCamera()
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
    }

    private fun setDoubleMode(double: Boolean) {
        doubleMode = double
        // (The locals are named *_view: a local called "double"
        // would shadow the parameter and break the if below.)
        val singleView = findViewById<TextView>(R.id.tvSingle)
        val doubleView = findViewById<TextView>(R.id.tvDouble)
        if (double) {
            doubleView.background = getDrawable(R.drawable.mode_tab_selected)
            doubleView.setTextColor(getColor(android.R.color.white))
            doubleView.setTypeface(null, android.graphics.Typeface.BOLD)
            singleView.background = null
            singleView.setTextColor(getColor(android.R.color.white))
            singleView.setTypeface(null, android.graphics.Typeface.NORMAL)
            spineLine().selectedSide = SpineLineView.SIDE_NONE
            findViewById<TextView>(R.id.tvStatus).text =
                "Double — both pages at once (A then B)"
        } else {
            singleView.background = getDrawable(R.drawable.mode_tab_selected)
            singleView.setTextColor(getColor(android.R.color.white))
            singleView.setTypeface(null, android.graphics.Typeface.BOLD)
            doubleView.background = null
            doubleView.setTextColor(getColor(android.R.color.white))
            doubleView.setTypeface(null, android.graphics.Typeface.NORMAL)
            spineLine().selectedSide = selectedSide
            findViewById<TextView>(R.id.tvStatus).text =
                "Single — tap the page to scan (A or B), then Capture"
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
        findViewById<TextView>(R.id.tvStatus).text =
            if (torchOn) "Torch on" else "Torch off"
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
                findViewById<TextView>(R.id.tvStatus).text =
                    "Point the camera at an open book — drag the spine line to the fold, then Capture"
            } catch (_: Throwable) {
                findViewById<TextView>(R.id.tvStatus).text =
                    "Couldn't start the camera — try again"
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
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Capturing…"
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
                    runOnUiThread { status.text = "Capture failed — try again" }
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Thread { processBookPhoto(photoFile) }.start()
                }
            }
        )
    }

    /** Split the spread, enhance each page, and add them in
     *  order (A then B in Double; the tapped side in Single).
     *  Runs off the UI thread — the split + enhance of two
     *  full-resolution pages takes a moment. */
    private fun processBookPhoto(photoFile: File) {
        try {
            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
            if (bitmap == null) {
                runOnUiThread {
                    isCapturing = false
                    findViewById<TextView>(R.id.tvStatus).text = "Couldn't read that photo — try again"
                }
                return
            }
            val split = DocumentDetector.splitSpread(bitmap, spineFraction)
            bitmap.recycle()
            if (split == null) {
                runOnUiThread {
                    isCapturing = false
                    findViewById<TextView>(R.id.tvStatus).text = "Couldn't split the pages — try again"
                }
                return
            }
            val (left, right) = split
            val newPages = mutableListOf<File>()
            if (doubleMode || selectedSide == SpineLineView.SIDE_LEFT) {
                val enhanced = ImageEnhancer.apply(left, ImageEnhancer.Filter.NO_SHADOW)
                val file = File(cacheDir, "book_page_${System.currentTimeMillis()}_a.jpg")
                FileOutputStream(file).use { enhanced.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                enhanced.recycle()
                newPages.add(file)
            }
            if (doubleMode || selectedSide == SpineLineView.SIDE_RIGHT) {
                val enhanced = ImageEnhancer.apply(right, ImageEnhancer.Filter.NO_SHADOW)
                val file = File(cacheDir, "book_page_${System.currentTimeMillis()}_b.jpg")
                FileOutputStream(file).use { enhanced.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                enhanced.recycle()
                newPages.add(file)
            }
            left.recycle()
            right.recycle()
            photoFile.delete()
            // The spine line stays where the user put it
            // (manual only) — it is not reset between spreads.
            runOnUiThread {
                isCapturing = false
                pages.addAll(newPages)
                updateLabels()
                findViewById<TextView>(R.id.tvStatus).text =
                    if (newPages.size == 2) "2 pages added — flip the book and capture the next spread"
                    else "1 page added — flip the book and capture the next spread"
            }
        } catch (_: Throwable) {
            runOnUiThread {
                isCapturing = false
                findViewById<TextView>(R.id.tvStatus).text = "Something went wrong — try again"
            }
        }
    }

    private fun updateLabels() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnPages).text =
            "Pages (${pages.size})"
        findViewById<View>(R.id.btnSavePdf).visibility =
            if (pages.isNotEmpty()) View.VISIBLE else View.GONE
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
        intent.putExtra(EnhanceActivity.EXTRA_IMAGE_PATH, pages[0].absolutePath)
        intent.putStringArrayListExtra(
            EnhanceActivity.EXTRA_PAGE_PATHS,
            ArrayList(pages.map { it.absolutePath })
        )
        intent.putExtra(EnhanceActivity.EXTRA_APPLY_ALL_CHECKED, true)
        enhanceLauncher.launch(intent)
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
        // Save PDF now goes through the Filter (Enhance) screen
        // first (v0.1.71), then the Preview — the user picks
        // filters, reorders, and Saves there, which opens
        // Export. Export is a separate screen: while it is open
        // this activity stops, CameraX releases the camera, and
        // no scan runs in the background.
        openBookEnhance()
    }
}
