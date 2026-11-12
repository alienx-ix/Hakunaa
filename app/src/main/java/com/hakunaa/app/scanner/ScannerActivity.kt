package com.hakunaa.app.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import kotlin.math.abs
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.pdf.PdfTools
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * PDF Scanner — professional document scanner, 100% on-device.
 * CamScanner-style flow:
 *
 * 1. Camera (this screen): full-screen live preview, flash + HD
 *    toggles, Single / Batch mode, big capture button, Import
 *    Images / Import Files. AUTO capture: when a document is held
 *    steady in the frame, the app captures it by itself — the way
 *    professional scanners do it (toggle AUTO in the top bar).
 * 2. Crop (CropActivity): draggable teal corner handles, Auto
 *    Detect, rotate Left / Right, Next.
 * 3. Enhance (EnhanceActivity): filters Original / Lighten /
 *    Enhance / No Shadow / B&W, rotate, document name, checkmark
 *    to save the page.
 * 4. Back here: Single mode saves the PDF after one page; Batch
 *    mode keeps capturing and saves all pages as one PDF.
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
    private var torchOn = false
    private var hdOn = false
    private var autoCaptureOn = true
    private var isCapturing = false
    // Auto-capture state: the last detected corners, how many
    // consecutive frames they stayed still, and when the last
    // capture happened (cooldown so it does not fire twice).
    private var lastCorners: FloatArray? = null
    private var stableFrames = 0
    private var lastAnalysisMs = 0L
    private var lastCaptureMs = 0L

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnCapture).setOnClickListener { capturePhoto() }
        findViewById<ImageButton>(R.id.btnFlash).setOnClickListener { toggleTorch() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAuto)
            .setOnClickListener { toggleAutoCapture() }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnHd)
            .setOnClickListener { toggleHd() }
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            Toast.makeText(this, "More options coming soon", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.btnAllFeatures).setOnClickListener {
            Toast.makeText(this, "All Features coming soon", Toast.LENGTH_SHORT).show()
        }
        findViewById<LinearLayout>(R.id.btnImportImages).setOnClickListener {
            pickPhoto.launch("image/*")
        }
        findViewById<LinearLayout>(R.id.btnImportFiles).setOnClickListener {
            pickPhoto.launch("image/*")
        }
        findViewById<TextView>(R.id.tvModeSingle).setOnClickListener { setMode(Mode.SINGLE) }
        findViewById<TextView>(R.id.tvModeBatch).setOnClickListener { setMode(Mode.BATCH) }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSavePdf)
            .setOnClickListener { openPages() }

        // Ask for the camera permission the way every app does.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
        updatePagesLabel()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor?.shutdown()
    }

    private fun setMode(newMode: Mode) {
        mode = newMode
        val single = findViewById<TextView>(R.id.tvModeSingle)
        val batch = findViewById<TextView>(R.id.tvModeBatch)
        if (newMode == Mode.SINGLE) {
            single.setTextColor(getColor(R.color.hakunaa_teal))
            single.setTypeface(null, android.graphics.Typeface.BOLD)
            batch.setTextColor(getColor(android.R.color.white))
            batch.setTypeface(null, android.graphics.Typeface.NORMAL)
        } else {
            batch.setTextColor(getColor(R.color.hakunaa_teal))
            batch.setTypeface(null, android.graphics.Typeface.BOLD)
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
        camera?.cameraControl?.enableTorch(torchOn)
        findViewById<ImageButton>(R.id.btnFlash).setImageResource(
            if (torchOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
        findViewById<TextView>(R.id.tvStatus).text =
            if (torchOn) "Torch on" else "Torch off"
    }

    private fun toggleAutoCapture() {
        autoCaptureOn = !autoCaptureOn
        stableFrames = 0
        lastCorners = null
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAuto)
        btn.setTextColor(
            if (autoCaptureOn) getColor(R.color.hakunaa_teal)
            else getColor(android.R.color.white)
        )
        findViewById<TextView>(R.id.tvStatus).text =
            if (autoCaptureOn) "Auto capture on — hold the page steady"
            else "Auto capture off — tap Capture yourself"
    }

    private fun toggleHd() {
        hdOn = !hdOn
        // Re-bind the camera with the new capture setting.
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnHd)
        btn.setTextColor(if (hdOn) getColor(R.color.hakunaa_teal) else getColor(android.R.color.white))
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
                }
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                if (hdOn) {
                    captureBuilder.setTargetResolution(android.util.Size(1920, 1080))
                }
                imageCapture = captureBuilder.build().also {
                    it.flashMode = ImageCapture.FLASH_MODE_AUTO
                }
                // Real-time document detection for auto-capture:
                // analyse a small frame a few times a second.
                val analysis = ImageAnalysis.Builder()
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor!!, ::analyzeFrame)
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, analysis
                )
                if (torchOn) camera?.cameraControl?.enableTorch(true)
                findViewById<TextView>(R.id.tvStatus).text =
                    "Point the camera at a document and tap Capture"
            } catch (_: Throwable) {
                findViewById<TextView>(R.id.tvStatus).text =
                    "Couldn't start the camera — try picking a photo from the gallery instead"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Analyse one camera frame for auto-capture. Runs on a
     *  background thread, throttled to about 3 frames a second.
     *  When a document is detected and its corners stay still for
     *  3 checks in a row, the page is held correctly — capture it
     *  automatically, the way professional scanners do. */
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            if (!autoCaptureOn || isCapturing) return
            val now = System.currentTimeMillis()
            if (now - lastAnalysisMs < 350) return
            if (now - lastCaptureMs < 3000) return
            lastAnalysisMs = now
            val bitmap = imageProxy.toBitmap()
            val corners = DocumentDetector.detectCorners(bitmap)
            bitmap.recycle()
            if (corners == null) {
                stableFrames = 0
                lastCorners = null
                return
            }
            val prev = lastCorners
            if (prev != null) {
                // How far did the corners move since the last check?
                var maxMove = 0f
                for (i in corners.indices) {
                    maxMove = maxOf(maxMove, abs(corners[i] - prev[i]))
                }
                // The analysis frame is small; 25 px of movement
                // means the phone or the page is still moving.
                if (maxMove < 25f) {
                    stableFrames++
                } else {
                    stableFrames = 0
                }
            } else {
                stableFrames = 0
            }
            lastCorners = corners
            if (stableFrames >= 3) {
                stableFrames = 0
                lastCorners = null
                runOnUiThread {
                    findViewById<TextView>(R.id.tvStatus).text = "Document found — capturing…"
                    capturePhoto()
                }
            } else if (stableFrames == 1) {
                runOnUiThread {
                    findViewById<TextView>(R.id.tvStatus).text = "Document found — hold steady…"
                }
            }
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
        stableFrames = 0
        lastCorners = null
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Capturing…"
        val photoFile = File(cacheDir, "scan_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
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
        findViewById<View>(R.id.btnSavePdf).visibility =
            if (pages.isNotEmpty() && mode == Mode.BATCH) View.VISIBLE else View.GONE
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
        pagesLauncher.launch(intent)
    }

    private fun saveAsPdf() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Scan at least one page first", Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Saving PDF…"
        val name = (docName ?: "Hakunaa Scan").replace(Regex("[^A-Za-z0-9 _.-]"), "_")
        Thread {
            try {
                val out = File(cacheDir, "$name.pdf")
                // Optimized: downscaled + JPEG, about 1 MB for 2 pages
                // instead of 22 MB (lossless PNG was the problem).
                val ok = PdfTools.imageToPdfOptimized(pages, out)
                val saved = ok && MediaStoreSaver.save(this, out, MediaStoreSaver.Kind.DOCUMENT, out.name) != null
                runOnUiThread {
                    if (saved) {
                        val sizeKb = out.length() / 1024
                        status.text = "Done — scanned PDF saved to Documents/Hakunaa/${out.name} (${pages.size} page(s), ${sizeKb} KB)"
                        Toast.makeText(this, "PDF saved (${sizeKb} KB)", Toast.LENGTH_SHORT).show()
                        pages.clear()
                        docName = null
                        updatePagesLabel()
                    } else {
                        status.text = "Save failed — try again"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Save failed — try again" }
            }
        }.start()
    }
}
