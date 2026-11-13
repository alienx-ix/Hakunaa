package com.hakunaa.app.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
 * auto-enhanced with the ENHANCE filter (the default, with
 * sharpening), so there is no per-page Crop/Enhance screen in
 * the way — the flow stays fast. Review and reorder on the
 * Pages screen; save the whole book as one PDF.
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
    private var isCapturing = false
    private var doubleMode = true
    private var selectedSide = SpineLineView.SIDE_LEFT
    private var spineFraction = 0.5f
    private var smoothSpine: Float? = null
    private var lastDragMs = 0L
    private var lastAnalysisMs = 0L

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

    // Export is a separate screen: while it is open this
    // activity stops and the camera is released — no scan runs
    // in the background. On save, clear the pages; on cancel,
    // keep them and leave the Save button visible.
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val status = findViewById<TextView>(R.id.tvStatus)
            if (result.resultCode == RESULT_OK) {
                val fileName = result.data?.getStringExtra(PdfExportActivity.EXTRA_FILE_NAME)
                    ?: "scan.pdf"
                val sizeBytes = result.data?.getLongExtra(PdfExportActivity.EXTRA_SIZE_BYTES, 0L)
                    ?: 0L
                val size = com.hakunaa.app.core.FileUtils.formatSize(sizeBytes)
                status.text = "Done — book PDF saved to Documents/Hakunaa/$fileName (${pages.size} page(s), $size)"
                Toast.makeText(this, "PDF saved ($size)", Toast.LENGTH_SHORT).show()
                pages.clear()
                docName = null
                updateLabels()
            } else {
                status.text = "Save cancelled — ${pages.size} page(s) ready, tap Save PDF when ready"
                updateLabels()
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
            }
            // RESULT_ADD_PAGE or back: stay on the camera, pages kept.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_scanner)

        cameraExecutor = Executors.newSingleThreadExecutor()
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

        // Spine line: a drag moves it (and pauses the auto
        // follow for 3 seconds); a tap picks a side in Single
        // mode. Double mode takes both pages, so no selection.
        spineLine().onSpineChanged = { fraction ->
            spineFraction = fraction
            lastDragMs = System.currentTimeMillis()
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

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
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
                // AUTO-RESOLVE camera binding — global support.
                // Same ladder as the main scanner: fixed 640x480
                // analysis -> CameraX picks the size -> no
                // analysis at all (the spine line just won't
                // auto-follow; drag it by hand). The camera must
                // open on ANY phone.
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
                try {
                    if (torchOn) camera?.cameraControl?.enableTorch(true)
                } catch (_: Throwable) {
                }
                findViewById<TextView>(R.id.tvStatus).text =
                    if (analysisOn) "Point the camera at an open book — both pages at once"
                    else "Camera ready — drag the spine line by hand (auto-follow isn't supported on this phone)"
            } catch (_: Throwable) {
                findViewById<TextView>(R.id.tvStatus).text =
                    "Couldn't start the camera — try again"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** The live spine-detection stream. A fixed 320x240 is
     *  fastest (small frames, no lag — 4x fewer pixels than
     *  640x480, so the spine scan costs a quarter of the CPU),
     *  but some phones reject that exact combination — then
     *  CameraX picks the size itself (auto resolve), which
     *  works everywhere. */
    private fun buildAnalysis(fixedResolution: Boolean): ImageAnalysis {
        val builder = ImageAnalysis.Builder()
            // 4:3, the same aspect as the preview, so the
            // detected spine maps exactly onto the preview.
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        if (fixedResolution) {
            builder.setTargetResolution(android.util.Size(320, 240))
        }
        return builder.build().also {
            it.setAnalyzer(cameraExecutor!!, ::analyzeFrame)
        }
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

    /** One analysis frame: find the spine valley and move the
     *  line to it (smoothed), unless the user dragged it
     *  recently — a drag wins over the auto follow. */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            if (isCapturing) return
            val now = System.currentTimeMillis()
            if (now - lastAnalysisMs < 250) return
            lastAnalysisMs = now
            val raw = imageProxy.toBitmap()
            // The buffer may be rotated relative to the display;
            // upright it first so the fraction maps onto the
            // preview directly.
            val rotation = imageProxy.imageInfo.rotationDegrees
            val bitmap = if (rotation == 0) {
                raw
            } else {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                    .also { raw.recycle() }
            }
            val frac = DocumentDetector.spineFraction(bitmap)
            bitmap.recycle()
            if (frac == null) return
            if (now - lastDragMs < 3000) return
            val prev = smoothSpine
            val smoothed = if (prev == null) frac else prev + 0.3f * (frac - prev)
            smoothSpine = smoothed
            spineFraction = smoothed
            spineLine().setSpineFraction(smoothed)
        } catch (_: Throwable) {
            // Analysis must never crash the camera.
        } finally {
            imageProxy.close()
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
                val enhanced = ImageEnhancer.apply(left, ImageEnhancer.Filter.ENHANCE)
                val file = File(cacheDir, "book_page_${System.currentTimeMillis()}_a.jpg")
                FileOutputStream(file).use { enhanced.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                enhanced.recycle()
                newPages.add(file)
            }
            if (doubleMode || selectedSide == SpineLineView.SIDE_RIGHT) {
                val enhanced = ImageEnhancer.apply(right, ImageEnhancer.Filter.ENHANCE)
                val file = File(cacheDir, "book_page_${System.currentTimeMillis()}_b.jpg")
                FileOutputStream(file).use { enhanced.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                enhanced.recycle()
                newPages.add(file)
            }
            left.recycle()
            right.recycle()
            photoFile.delete()
            // Next spread: let the spine line re-detect.
            lastDragMs = 0
            smoothSpine = null
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
        val name = docName ?: "Hakunaa Book"
        val intent = Intent(this, PdfExportActivity::class.java)
        intent.putStringArrayListExtra(
            PdfExportActivity.EXTRA_PAGE_PATHS,
            ArrayList(pages.map { it.absolutePath })
        )
        intent.putExtra(PdfExportActivity.EXTRA_DOC_NAME, name)
        exportLauncher.launch(intent)
    }
}
