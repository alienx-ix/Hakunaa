package com.hakunaa.app.scanner

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.pdf.PdfTools
import java.io.File

/**
 * PDF Scanner — scan documents with the camera (or pick photos)
 * and save them as one PDF. No cloud, no account, 100% on-device.
 *
 * How it works: each captured photo becomes one scan page. The
 * user picks a filter — Color, Grayscale or B&W (black & white,
 * best for documents) — and the app applies it to every page,
 * then combines all pages into a single PDF (A4, one photo per
 * page) via the same imageToPdf used by PDF Tools.
 *
 * This is a camera + filter + PDF scanner. It does NOT do
 * automatic edge detection or perspective correction yet — that
 * needs OpenCV, which is a later step. The user crops/frames the
 * document in the camera itself.
 */
class ScannerActivity : AppCompatActivity() {

    enum class Filter { COLOR, GRAYSCALE, BW }

    private val pages = mutableListOf<File>()
    private var cameraUri: Uri? = null

    private val takePhoto =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            if (ok) {
                val uri = cameraUri ?: return@registerForActivityResult
                addPageFromUri(uri)
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

        findViewById<Button>(R.id.btnTakePhoto).setOnClickListener { takePhoto() }
        findViewById<Button>(R.id.btnPickPhoto).setOnClickListener { pickPhoto.launch("image/*") }
        findViewById<Button>(R.id.btnSavePdf).setOnClickListener { saveAsPdf() }
        findViewById<Button>(R.id.btnClearPages).setOnClickListener { clearPages() }
        updatePagesLabel()
    }

    private fun takePhoto() {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "hakunaa_scan_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Toast.makeText(this, "Couldn't open the camera", Toast.LENGTH_SHORT).show()
                return
            }
            cameraUri = uri
            takePhoto.launch(uri)
        } catch (_: Throwable) {
            Toast.makeText(this, "Couldn't open the camera", Toast.LENGTH_SHORT).show()
        }
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
                val filtered = File(cacheDir, "scan_filtered_${System.currentTimeMillis()}.jpg")
                val ok = applyFilter(input, filtered, selectedFilter())
                runOnUiThread {
                    if (ok) {
                        pages.add(filtered)
                        updatePagesLabel()
                        status.text = "Page ${pages.size} added — take another, or save as PDF"
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
                    // Grayscale, then a hard threshold: dark pixels
                    // become black, light pixels become white. This
                    // is what makes a scanned document crisp.
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
            if (pages.isEmpty()) "No pages yet — take a photo or pick one"
            else "${pages.size} page(s) scanned"
    }

    private fun clearPages() {
        for (page in pages) {
            try { page.delete() } catch (_: Throwable) { }
        }
        pages.clear()
        updatePagesLabel()
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
