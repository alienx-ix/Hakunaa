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
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
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
 *    Images / Import Files.
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
    private var cameraExecutor: ExecutorService? = null
    private var flashOn = false
    private var hdOn = false

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
        findViewById<ImageButton>(R.id.btnFlash).setOnClickListener { toggleFlash() }
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

    private fun toggleFlash() {
        flashOn = !flashOn
        imageCapture?.flashMode =
            if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        findViewById<ImageButton>(R.id.btnFlash).setImageResource(
            if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
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
                    it.flashMode =
                        if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
                findViewById<TextView>(R.id.tvStatus).text =
                    "Point the camera at a document and tap Capture"
            } catch (_: Throwable) {
                findViewById<TextView>(R.id.tvStatus).text =
                    "Couldn't start the camera — try picking a photo from the gallery instead"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val capture = imageCapture
        if (capture == null) {
            Toast.makeText(this, "Camera is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Capturing…"
        val photoFile = File(cacheDir, "scan_capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread { status.text = "Capture failed — try again" }
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
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
