package com.hakunaa.app.scanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import java.io.File
import java.util.Collections

/**
 * Pages screen — the CamScanner-style multi-page review.
 *
 * After capturing several pages (Batch mode), the user sees every
 * page as a thumbnail and can:
 * - Drag a page to reorder it (long-press the card, then drag —
 *   no arrows, the way professional scanners do it)
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
    private lateinit var adapter: PagesAdapter

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

        adapter = PagesAdapter(
            pages,
            onRotate = { index -> rotatePage(index) },
            onDelete = { index ->
                try { pages[index].delete() } catch (_: Throwable) { }
                pages.removeAt(index)
                if (pages.isEmpty()) {
                    Toast.makeText(this, "No pages left", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    adapter.notifyItemRemoved(index)
                    updateTitle()
                }
            }
        )
        val recycler = findViewById<RecyclerView>(R.id.pagesRecycler)
        recycler.layoutManager = GridLayoutManager(this, 2)
        recycler.adapter = adapter

        // Drag to reorder: long-press a card, drag it to its new spot.
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                Collections.swap(pages, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // No swipe action.
            }

            override fun isLongPressDragEnabled(): Boolean = true
        })
        touchHelper.attachToRecyclerView(recycler)

        updateTitle()

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnAddPage)
            .setOnClickListener {
                setResult(RESULT_ADD_PAGE)
                finish()
            }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSavePdf)
            .setOnClickListener { saveAsPdf() }
    }

    private fun updateTitle() {
        val totalBytes = pages.sumOf { it.length() }
        findViewById<TextView>(R.id.tvTitle).text =
            (docName ?: "Scanned Pages") + " (${pages.size}) • ${FileUtils.formatSize(totalBytes)}"
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
                    rotated.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
                bmp.recycle()
                rotated.recycle()
                runOnUiThread { adapter.notifyItemChanged(index) }
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
        status.text = "Preparing export options…"
        val name = docName ?: "Hakunaa Scan"
        // The export dialog builds all three quality tiers, shows
        // each with its real size, and saves the one the user picks.
        PdfExportDialog.show(this, pages.toList(), name) { fileName, sizeBytes ->
            val size = FileUtils.formatSize(sizeBytes)
            status.text = "Done — $fileName saved (${pages.size} page(s), $size)"
            Toast.makeText(this, "PDF saved ($size)", Toast.LENGTH_LONG).show()
            val data = Intent()
            data.putExtra("saved_pdf", fileName)
            setResult(RESULT_OK, data)
            finish()
        }
    }

    private class PagesAdapter(
        private val pages: List<File>,
        private val onRotate: (Int) -> Unit,
        private val onDelete: (Int) -> Unit
    ) : RecyclerView.Adapter<PagesAdapter.PageViewHolder>() {

        class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(R.id.ivPageThumb)
            val label: TextView = view.findViewById(R.id.tvPageLabel)
            val btnRotate: ImageButton = view.findViewById(R.id.btnRotate)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_page, parent, false)
            return PageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val file = pages[position]
            holder.label.text = "Page ${position + 1}"
            holder.thumb.setImageDrawable(null)
            Thread {
                try {
                    val options = BitmapFactory.Options()
                    options.inSampleSize = 4
                    val bmp = BitmapFactory.decodeFile(file.absolutePath, options)
                    holder.thumb.post { holder.thumb.setImageBitmap(bmp) }
                } catch (_: Throwable) {
                }
            }.start()
            holder.btnRotate.setOnClickListener { onRotate(holder.adapterPosition) }
            holder.btnDelete.setOnClickListener { onDelete(holder.adapterPosition) }
        }

        override fun getItemCount(): Int = pages.size
    }
}
