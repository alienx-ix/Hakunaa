package com.hakunaa.app.scanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enhance screen — the CamScanner-style enhance step after crop.
 *
 * - The straightened page, with an editable document name
 *   ("Hakunaa 11-12-2026 19.53" style).
 * - Filter thumbnails: Original, Lighten, Enhance, No Shadow, B&W.
 *   Tap one to preview it on the page.
 * - Rotate Left.
 * - The green checkmark saves the page with the chosen filter.
 *
 * Not here (and not possible offline): Extract Text (OCR) and
 * To Word — those are cloud features in CamScanner. Markup and
 * Sign are later steps.
 */
class EnhanceActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_ENHANCED_PATH = "enhanced_path"
    }

    private var bitmap: Bitmap? = null
    private var selectedFilter = ImageEnhancer.Filter.ENHANCE
    private var currentPreview: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enhance)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Default document name, CamScanner style: "Hakunaa 11-12-2026 19.53".
        val name = "Hakunaa " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())
        findViewById<EditText>(R.id.etDocName).setText(name)

        val path = intent.getStringExtra(EXTRA_IMAGE_PATH)
        if (path == null) {
            Toast.makeText(this, "Image not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Thread {
            try {
                val bmp = BitmapFactory.decodeFile(path)
                runOnUiThread {
                    if (bmp == null) {
                        Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                        finish()
                        return@runOnUiThread
                    }
                    bitmap = bmp
                    // Default filter is Enhance (the one CamScanner selects).
                    applyFilterToPreview(selectedFilter)
                    buildFilterThumbnails()
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()

        findViewById<LinearLayout>(R.id.btnRotateLeft).setOnClickListener { rotateLeft() }
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun applyFilterToPreview(filter: ImageEnhancer.Filter) {
        val bmp = bitmap ?: return
        selectedFilter = filter
        Thread {
            try {
                val filtered = ImageEnhancer.apply(bmp, filter)
                runOnUiThread {
                    currentPreview?.recycle()
                    currentPreview = filtered
                    findViewById<ImageView>(R.id.ivPage).setImageBitmap(filtered)
                    updateFilterSelection()
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun buildFilterThumbnails() {
        val bmp = bitmap ?: return
        val thumb = Bitmap.createScaledBitmap(bmp, 160, (160f * bmp.height / bmp.width).toInt(), true)
        val filters = ImageEnhancer.Filter.values()
        val row = findViewById<LinearLayout>(R.id.filterRow)
        Thread {
            for (filter in filters) {
                try {
                    val filtered = ImageEnhancer.apply(thumb, filter)
                    runOnUiThread {
                        val item = layoutInflater.inflate(R.layout.item_filter, row, false)
                        val image = item.findViewById<ImageView>(R.id.ivFilterThumb)
                        val label = item.findViewById<TextView>(R.id.tvFilterLabel)
                        image.setImageBitmap(filtered)
                        label.text = filter.label
                        item.tag = filter
                        item.setOnClickListener {
                            applyFilterToPreview(filter)
                        }
                        row.addView(item)
                    }
                } catch (_: Throwable) {
                }
            }
            runOnUiThread { updateFilterSelection() }
        }.start()
    }

    private fun updateFilterSelection() {
        val row = findViewById<LinearLayout>(R.id.filterRow)
        for (i in 0 until row.childCount) {
            val item = row.getChildAt(i)
            val filter = item.tag as? ImageEnhancer.Filter ?: continue
            val selected = filter == selectedFilter
            item.findViewById<TextView>(R.id.tvFilterLabel).setTextColor(
                if (selected) getColor(R.color.hakunaa_teal) else getColor(R.color.hakunaa_text_secondary)
            )
            item.findViewById<ImageView>(R.id.ivFilterThumb).alpha = if (selected) 1f else 0.6f
            item.findViewById<View>(R.id.filterBorder).visibility =
                if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun rotateLeft() {
        val bmp = bitmap ?: return
        val matrix = Matrix()
        matrix.postRotate(-90f)
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        bitmap = rotated
        applyFilterToPreview(selectedFilter)
    }

    private fun save() {
        val bmp = bitmap ?: return
        Thread {
            try {
                val filtered = ImageEnhancer.apply(bmp, selectedFilter)
                val docName = findViewById<EditText>(R.id.etDocName).text.toString()
                    .trim().ifEmpty { "Hakunaa Scan" }
                val safeName = docName.replace(Regex("[^A-Za-z0-9 _.-]"), "_")
                val out = File(cacheDir, "scan_enhanced_${System.currentTimeMillis()}.jpg")
                out.outputStream().use { stream ->
                    filtered.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                }
                filtered.recycle()
                runOnUiThread {
                    val data = Intent()
                    data.putExtra(EXTRA_ENHANCED_PATH, out.absolutePath)
                    data.putExtra("doc_name", safeName)
                    setResult(RESULT_OK, data)
                    finish()
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Save failed — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        bitmap?.recycle()
        currentPreview?.recycle()
    }
}
