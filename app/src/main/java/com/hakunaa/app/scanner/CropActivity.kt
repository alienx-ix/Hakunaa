package com.hakunaa.app.scanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import java.io.File

/**
 * Crop screen — the CamScanner-style crop step after capture.
 *
 * - The photo with a teal frame and draggable corner handles.
 * - Auto Detect: finds the document edges and snaps the frame.
 * - Left / Right: rotate the photo 90 degrees.
 * - Next: straightens the page (perspective correction) and
 *   opens the Enhance screen.
 */
class CropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PHOTO_PATH = "photo_path"
        const val EXTRA_CROPPED_PATH = "cropped_path"
    }

    private var bitmap: Bitmap? = null
    private var photoFile: File? = null
    private var pagePaths: List<String> = emptyList()

    private val enhanceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // Enhance finished — pass the result back to the scanner.
                val data = Intent()
                data.putExtra(
                    EXTRA_CROPPED_PATH,
                    result.data?.getStringExtra(EnhanceActivity.EXTRA_ENHANCED_PATH)
                )
                setResult(RESULT_OK, data)
                finish()
            }
            // If the user went back from Enhance, stay on the crop screen.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val path = intent.getStringExtra(EXTRA_PHOTO_PATH)
        if (path == null) {
            Toast.makeText(this, "Photo not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        photoFile = File(path)
        // Pass-through: the batch so far (for "Apply to all
        // pages") — it goes on to Enhance.
        pagePaths = intent.getStringArrayListExtra(EnhanceActivity.EXTRA_PAGE_PATHS)
            ?: emptyList()
        Thread {
            try {
                val bmp = BitmapFactory.decodeFile(path)
                runOnUiThread {
                    if (bmp == null) {
                        Toast.makeText(this, "Couldn't open the photo", Toast.LENGTH_SHORT).show()
                        finish()
                        return@runOnUiThread
                    }
                    bitmap = bmp
                    val cropView = findViewById<CropView>(R.id.cropView)
                    cropView.setBitmap(bmp)
                    // Auto-detect the document edges right away.
                    autoDetect()
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't open the photo", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()

        findViewById<LinearLayout>(R.id.btnRotateLeft).setOnClickListener { rotate(-90f) }
        findViewById<LinearLayout>(R.id.btnRotateRight).setOnClickListener { rotate(90f) }
        findViewById<LinearLayout>(R.id.btnAutoDetect).setOnClickListener { autoDetect() }
        findViewById<TextView>(R.id.btnNext).setOnClickListener { next() }
    }

    private fun autoDetect() {
        val bmp = bitmap ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        Thread {
            try {
                val corners = DocumentDetector.detectCorners(bmp)
                runOnUiThread {
                    if (corners != null) {
                        findViewById<CropView>(R.id.cropView).setCorners(corners)
                        status.text = "Document detected — drag the corners to adjust"
                    } else {
                        status.text = "Couldn't detect the page — drag the corners yourself"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Couldn't detect the page — drag the corners yourself" }
            }
        }.start()
    }

    private fun rotate(degrees: Float) {
        val bmp = bitmap ?: return
        val matrix = Matrix()
        matrix.postRotate(degrees)
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        bitmap = rotated
        findViewById<CropView>(R.id.cropView).setBitmap(rotated)
        autoDetect()
    }

    private fun next() {
        val bmp = bitmap ?: return
        val corners = findViewById<CropView>(R.id.cropView).getCorners()
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Straightening…"
        Thread {
            try {
                val straightened = DocumentDetector.warpToRectangle(bmp, corners) ?: bmp
                val out = File(cacheDir, "scan_cropped_${System.currentTimeMillis()}.jpg")
                out.outputStream().use { stream ->
                    // Near-lossless intermediate (95) — the final PDF
                    // tiers re-encode from this, so it must not be
                    // the quality bottleneck.
                    straightened.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
                if (straightened != bmp) straightened.recycle()
                runOnUiThread {
                    val intent = Intent(this, EnhanceActivity::class.java)
                    intent.putExtra(EnhanceActivity.EXTRA_IMAGE_PATH, out.absolutePath)
                    intent.putStringArrayListExtra(
                        EnhanceActivity.EXTRA_PAGE_PATHS,
                        ArrayList(pagePaths)
                    )
                    enhanceLauncher.launch(intent)
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Couldn't straighten — try again" }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        bitmap?.recycle()
    }
}
