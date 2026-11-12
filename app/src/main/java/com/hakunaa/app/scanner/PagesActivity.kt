package com.hakunaa.app.scanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.pdf.PdfTools
import java.io.File

/**
 * Pages screen — the CamScanner-style multi-page review.
 *
 * After capturing several pages (Batch mode), the user sees every
 * page as a thumbnail and can:
 * - Reorder pages (left / right arrows on each card)
 * - Rotate a page 90 degrees
 * - Delete a page
 * - Add more pages (back to the camera)
 * - Save all pages as one optimized PDF (downscaled + JPEG,
 *   about 1 MB for 2 pages instead of 22 MB)
 */
class PagesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE_PATHS = "page_paths"
        const val EXTRA_DOC_NAME = "doc_name"
        const val RESULT_ADD_PAGE = 2
    }

    private val pages = mutableListOf<File>()
    private var docName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pages)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val paths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS)
        docName = intent.getStringExtra(EXTRA_DOC_NAME)
        if (paths.isNullOrEmpty()) {
            Toast.makeText(this, "No pages", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        for (path in paths) {
            val file = File(path)
            if (file.exists()) pages.add(file)
        }

        findViewById<TextView>(R.id.tvTitle).text =
            (docName ?: "Scanned Pages") + " (${pages.size})"

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddPage)
            .setOnClickListener {
                setResult(RESULT_ADD_PAGE)
                finish()
            }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSavePdf)
            .setOnClickListener { saveAsPdf() }

        renderGrid()
    }

    private fun renderGrid() {
        val grid = findViewById<GridLayout>(R.id.pagesGrid)
        grid.removeAllViews()
        findViewById<TextView>(R.id.tvTitle).text =
            (docName ?: "Scanned Pages") + " (${pages.size})"
        for ((index, file) in pages.withIndex()) {
            val card = layoutInflater.inflate(R.layout.item_page, grid, false)
            val thumb = card.findViewById<ImageView>(R.id.ivPageThumb)
            val label = card.findViewById<TextView>(R.id.tvPageLabel)
            label.text = "Page ${index + 1}"
            Thread {
                try {
                    val options = BitmapFactory.Options()
                    options.inSampleSize = 4
                    val bmp = BitmapFactory.decodeFile(file.absolutePath, options)
                    runOnUiThread { thumb.setImageBitmap(bmp) }
                } catch (_: Throwable) {
                }
            }.start()
            card.findViewById<ImageButton>(R.id.btnMoveLeft).setOnClickListener {
                if (index > 0) {
                    pages.add(index - 1, pages.removeAt(index))
                    renderGrid()
                }
            }
            card.findViewById<ImageButton>(R.id.btnMoveRight).setOnClickListener {
                if (index < pages.size - 1) {
                    pages.add(index + 1, pages.removeAt(index))
                    renderGrid()
                }
            }
            card.findViewById<ImageButton>(R.id.btnRotate).setOnClickListener {
                rotatePage(index)
            }
            card.findViewById<ImageButton>(R.id.btnDelete).setOnClickListener {
                try { file.delete() } catch (_: Throwable) { }
                pages.removeAt(index)
                if (pages.isEmpty()) {
                    Toast.makeText(this, "No pages left", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    renderGrid()
                }
            }
            // Two equal columns in the grid.
            val params = GridLayout.LayoutParams()
            params.width = 0
            params.height = GridLayout.LayoutParams.WRAP_CONTENT
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            card.layoutParams = params
            grid.addView(card)
        }
    }

    private fun rotatePage(index: Int) {
        val file = pages[index]
        Thread {
            try {
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return@Thread
                val matrix = Matrix()
                matrix.postRotate(90f)
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                file.outputStream().use { stream ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                }
                bmp.recycle()
                rotated.recycle()
                runOnUiThread { renderGrid() }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Rotate failed", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun saveAsPdf() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "No pages to save", Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Saving optimized PDF…"
        val name = (docName ?: "Hakunaa Scan").replace(Regex("[^A-Za-z0-9 _.-]"), "_")
        Thread {
            try {
                val out = File(cacheDir, "$name.pdf")
                val ok = PdfTools.imageToPdfOptimized(pages, out)
                val saved = ok && MediaStoreSaver.save(this, out, MediaStoreSaver.Kind.DOCUMENT, out.name) != null
                runOnUiThread {
                    if (saved) {
                        val sizeKb = out.length() / 1024
                        status.text = "Done — ${out.name} saved (${pages.size} page(s), ${sizeKb} KB)"
                        Toast.makeText(this, "PDF saved (${sizeKb} KB)", Toast.LENGTH_LONG).show()
                        val data = Intent()
                        data.putExtra("saved_pdf", out.name)
                        setResult(RESULT_OK, data)
                        finish()
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
