package com.hakunaa.app.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
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
 *
 * - Live camera preview inside the app (CameraX PreviewView),
 *   not the system camera app.
 * - Asks for the CAMERA permission properly, with the system
 *   dialog, the way every other app does. Without it the camera
 *   cannot open — that was the v0.1.27 bug: the permission was
 *   in the manifest but never requested at runtime.
 * - Capture: takes a photo, auto-detects the document edges
 *   (pure-Kotlin Sobel edge detection, no OpenCV), corrects the
 *   perspective so the page looks flat and straight, then applies
 *   the chosen filter (Color / Grayscale / B&W).
 * - Page preview: every scanned page shows as a thumbnail.
 * - Multi-page: keep scanning, then save all pages as one PDF.
 * - Also accepts photos from the gallery.
 *
 * No cloud, no account. Files never leave the phone.
 */
class ScannerActivity : AppCompatActivity() {

    enum class Filter { COLOR, GRAYSCALE, BW }

    private val pages = mutableListOf<File>()
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService? = null

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
            if (uri != null) addPageFromUri(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<Button>(R.id.btnCapture).setOnClickListener { capturePhoto() }
        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener { pickPhoto.launch("image/*") }
        findViewById<Button>(R.id.btnSavePdf).setOnClickListener { saveAsPdf() }
        findViewById<Button>(R.id.btnClearPages).setOnClickListener { clearPages() }

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

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(findViewById<PreviewView>(R.id.previewView).surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
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
                    processCapturedPhoto(photoFile)
                }
            }
        )
    }

    private fun processCapturedPhoto(photoFile: File) {
        val status = findViewById<TextView>(R.id.tvStatus)
        Thread {
            try {
                // 1. Detect the document corners and straighten it.
                val straightened = DocumentDetector.straighten(photoFile)
                val source = straightened ?: photoFile
                // 2. Apply the chosen filter.
                val filtered = File(cacheDir, "scan_filtered_${System.currentTimeMillis()}.jpg")
                val ok = applyFilter(source, filtered, selectedFilter())
                if (straightened != null && straightened != photoFile) {
                    try { straightened.delete() } catch (_: Throwable) { }
                }
                try { photoFile.delete() } catch (_: Throwable) { }
                runOnUiThread {
                    if (ok) {
                        pages.add(filtered)
                        updatePagesLabel()
                        updatePreview()
                        status.text = "Page ${pages.size} added — scan another, or save as PDF"
                    } else {
                        status.text = "Couldn't process that photo — try again"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Couldn't process that photo — try again" }
            }
        }.start()
    }

    private fun selectedFilter(): Filter {
        return when (findViewById<RadioGroup>(R.id.rgFilter).checkedRadioButtonId) {
            R.id.rbFilterGrayscale -> Filter.GRAYSCALE
            R.id.rbFilterBw -> Filter.BW
            else -> Filter.COLOR
        }
    }

    private fun addPageFromUri(uri: Uri) {
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Adding page…"
        Thread {
            try {
                val input = FileUtils.copyUriToCache(this, uri, "scan_page")
                val straightened = DocumentDetector.straighten(input)
                val source = straightened ?: input
                val filtered = File(cacheDir, "scan_filtered_${System.currentTimeMillis()}.jpg")
                val ok = applyFilter(source, filtered, selectedFilter())
                if (straightened != null && straightened != input) {
                    try { straightened.delete() } catch (_: Throwable) { }
                }
                try { input.delete() } catch (_: Throwable) { }
                runOnUiThread {
                    if (ok) {
                        pages.add(filtered)
                        updatePagesLabel()
                        updatePreview()
                        status.text = "Page ${pages.size} added — scan another, or save as PDF"
                    } else {
                        status.text = "Couldn't add that photo — try again"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Couldn't add that photo — try again" }
            }
        }.start()
    }

    private fun applyFilter(input: File, output: File, filter: Filter): Boolean {
        if (filter == Filter.COLOR) {
            return try {
                input.copyTo(output, overwrite = true)
                true
            } catch (_: Throwable) {
                false
            }
        }
        return try {
            val bitmap = BitmapFactory.decodeFile(input.absolutePath) ?: return false
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val paint = Paint()
            when (filter) {
                Filter.GRAYSCALE -> {
                    val matrix = ColorMatrix()
                    matrix.setSaturation(0f)
                    paint.colorFilter = ColorMatrixColorFilter(matrix)
                    canvas.drawBitmap(bitmap, 0f, 0f, paint)
                }
                Filter.BW -> {
                    val gray = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                    val grayCanvas = Canvas(gray)
                    val grayPaint = Paint()
                    val matrix = ColorMatrix()
                    matrix.setSaturation(0f)
                    grayPaint.colorFilter = ColorMatrixColorFilter(matrix)
                    grayCanvas.drawBitmap(bitmap, 0f, 0f, grayPaint)
                    val pixels = IntArray(gray.width * gray.height)
                    gray.getPixels(pixels, 0, gray.width, 0, 0, gray.width, gray.height)
                    for (i in pixels.indices) {
                        val p = pixels[i]
                        val lum = (0.299 * ((p shr 16) and 0xFF) +
                            0.587 * ((p shr 8) and 0xFF) +
                            0.114 * (p and 0xFF)).toInt()
                        pixels[i] = if (lum < 160) -0x1000000 else -0x1
                    }
                    result.setPixels(pixels, 0, result.width, 0, 0, result.width, result.height)
                    gray.recycle()
                }
                else -> {
                    canvas.drawBitmap(bitmap, 0f, 0f, paint)
                }
            }
            File(output.absolutePath).outputStream().use { out ->
                result.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            bitmap.recycle()
            result.recycle()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun updatePagesLabel() {
        findViewById<TextView>(R.id.tvPages).text =
            if (pages.isEmpty()) "No pages yet — point the camera and tap Capture"
            else "${pages.size} page(s) scanned"
    }

    /** Show the latest scanned page as a thumbnail preview. */
    private fun updatePreview() {
        val last = pages.lastOrNull() ?: return
        try {
            val bitmap = BitmapFactory.decodeFile(last.absolutePath) ?: return
            findViewById<ImageView>(R.id.ivPreview).setImageBitmap(bitmap)
            findViewById<TextView>(R.id.tvPreviewLabel).text = "Latest page — ${pages.size} total"
        } catch (_: Throwable) {
        }
    }

    private fun clearPages() {
        for (page in pages) {
            try { page.delete() } catch (_: Throwable) { }
        }
        pages.clear()
        updatePagesLabel()
        findViewById<ImageView>(R.id.ivPreview).setImageDrawable(null)
        findViewById<TextView>(R.id.tvPreviewLabel).text = ""
        findViewById<TextView>(R.id.tvStatus).text = "Cleared — start a new scan"
    }

    private fun saveAsPdf() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "Scan at least one page first", Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Saving PDF…"
        Thread {
            try {
                val out = File(cacheDir, "hakunaa_scan_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.imageToPdf(pages, out)
                val saved = ok && MediaStoreSaver.save(this, out, MediaStoreSaver.Kind.DOCUMENT, out.name) != null
                runOnUiThread {
                    status.text = if (saved) "Done — scanned PDF saved to Documents/Hakunaa/${out.name} (${pages.size} page(s))"
                    else "Save failed — try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Save failed — try again" }
            }
        }.start()
    }
}
