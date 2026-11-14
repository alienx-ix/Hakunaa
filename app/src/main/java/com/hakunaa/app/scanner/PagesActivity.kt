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
import androidx.activity.result.contract.ActivityResultContracts
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
 * A Batch picks its filters at the end: on Save, the Enhance
 * screen opens for the first page with "Apply to all pages"
 * already ticked, so one filter choice covers the whole
 * batch — the professional batch workflow (CamScanner,
 * Adobe Scan, Microsoft Lens all work this way).
 */
class PagesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE_PATHS = "page_paths"
        const val EXTRA_DOC_NAME = "doc_name"
        // True when this batch comes from the main scanner in
        // Batch mode: its filters are picked at the end, on
        // Save. A Book Scan batch is already enhanced at
        // capture, so it skips this step.
        const val EXTRA_BATCH_ENHANCE = "batch_enhance"
        const val RESULT_ADD_PAGE = 2
    }

    private val pages = mutableListOf<File>()
    private var docName: String? = null
    private lateinit var adapter: PagesAdapter
    private var batchEnhance = false
    private var batchEnhanced = false

    // The filters-at-the-end step: the Enhance screen for the
    // first page, with "Apply to all pages" already ticked, so
    // the chosen filter goes to the whole batch at once.
    // Back from Enhance cancels the save — the pages stay.
    private val enhanceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val enhancedPath =
                    result.data?.getStringExtra(EnhanceActivity.EXTRA_ENHANCED_PATH)
                if (enhancedPath != null && pages.isNotEmpty()) {
                    val file = File(enhancedPath)
                    if (file.exists()) {
                        pages[0] = file
                        adapter.notifyItemChanged(0)
                    }
                }
                docName = result.data?.getStringExtra("doc_name") ?: docName
                updateTitle()
                batchEnhanced = true
                openExport()
            }
        }

    // Export is a separate screen; on save, report back to the
    // camera with the saved file name, then close. On cancel,
    // stay here with the pages kept. On discard (user confirmed
    // on the export screen, v0.1.67), report it up so the
    // camera drops the pages too (v0.1.69, Batch Preview flow).
    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val fileName = result.data?.getStringExtra(PdfExportActivity.EXTRA_FILE_NAME)
                    ?: "scan.pdf"
                val sizeBytes = result.data?.getLongExtra(PdfExportActivity.EXTRA_SIZE_BYTES, 0L)
                    ?: 0L
                val size = FileUtils.formatSize(sizeBytes)
                findViewById<TextView>(R.id.tvStatus).text =
                    "Done — $fileName saved (${pages.size} page(s), $size)"
                Toast.makeText(this, "PDF saved ($size)", Toast.LENGTH_LONG).show()
                val data = Intent()
                data.putExtra("saved_pdf", fileName)
                setResult(RESULT_OK, data)
                finish()
            } else if (result.resultCode == PdfExportActivity.RESULT_DISCARD) {
                setResult(PdfExportActivity.RESULT_DISCARD)
                finish()
            }
        }

    /** The pages as they stand now, for the caller: when
     *  this screen closes (back, Add Page, or the last page
     *  deleted), the caller must get the updated list — a
     *  delete or a reorder made here has to stick, not come
     *  back the next time the pages are opened (v0.1.90). */
    private fun pagesResultData(): Intent {
        val data = Intent()
        data.putStringArrayListExtra(
            EXTRA_PAGE_PATHS,
            ArrayList(pages.map { it.absolutePath })
        )
        return data
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pages)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            setResult(RESULT_CANCELED, pagesResultData())
            finish()
        }

        val paths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS)
        docName = intent.getStringExtra(EXTRA_DOC_NAME)
        batchEnhance = intent.getBooleanExtra(EXTRA_BATCH_ENHANCE, false)
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
            onOpen = { index -> openPreview(index) },
            onRotate = { index -> rotatePage(index) },
            onDelete = { index ->
                try { pages[index].delete() } catch (_: Throwable) { }
                pages.removeAt(index)
                if (pages.isEmpty()) {
                    Toast.makeText(this, "No pages left", Toast.LENGTH_SHORT).show()
                    // No pages left — tell the caller the list
                    // is empty, so it drops the batch too.
                    setResult(RESULT_CANCELED, pagesResultData())
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
                val data = pagesResultData()
                data.putExtra("add_page", true)
                setResult(RESULT_ADD_PAGE, data)
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

    // Tap a page and it opens full-screen (pinch to zoom,
    // double-tap to zoom in/out), so the user can read it and
    // check the scan before saving the PDF.
    private fun openPreview(index: Int) {
        val file = pages[index]
        val intent = Intent(this, PagePreviewActivity::class.java)
        intent.putExtra(PagePreviewActivity.EXTRA_PAGE_PATH, file.absolutePath)
        intent.putExtra(PagePreviewActivity.EXTRA_PAGE_LABEL, "Page ${index + 1}")
        startActivity(intent)
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

    /** Save: a Batch picks its filters at the end — the
     *  Enhance screen for the first page, with "Apply to all
     *  pages" already ticked — and only then the export
     *  screen. A batch that already went through this step
     *  exports straight away. */
    private fun saveAsPdf() {
        if (pages.isEmpty()) {
            Toast.makeText(this, "No pages to save", Toast.LENGTH_SHORT).show()
            return
        }
        if (batchEnhance && !batchEnhanced) {
            val intent = Intent(this, EnhanceActivity::class.java)
            intent.putExtra(EnhanceActivity.EXTRA_IMAGE_PATH, pages[0].absolutePath)
            intent.putStringArrayListExtra(
                EnhanceActivity.EXTRA_PAGE_PATHS,
                ArrayList(pages.map { it.absolutePath })
            )
            intent.putExtra(EnhanceActivity.EXTRA_APPLY_ALL_CHECKED, true)
            enhanceLauncher.launch(intent)
            return
        }
        openExport()
    }

    private fun openExport() {
        // Export opens as its OWN SCREEN (PdfExportActivity).
        val name = docName ?: "Hakunaa Scan"
        val intent = Intent(this, PdfExportActivity::class.java)
        intent.putStringArrayListExtra(
            PdfExportActivity.EXTRA_PAGE_PATHS,
            ArrayList(pages.map { it.absolutePath })
        )
        intent.putExtra(PdfExportActivity.EXTRA_DOC_NAME, name)
        exportLauncher.launch(intent)
    }

    private class PagesAdapter(
        private val pages: List<File>,
        private val onOpen: (Int) -> Unit,
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
            // Tap the card (or its thumbnail) to preview it
            // full-screen; the rotate/delete buttons keep their
            // own actions (they consume the tap first).
            holder.itemView.setOnClickListener { onOpen(holder.adapterPosition) }
            holder.thumb.setOnClickListener { onOpen(holder.adapterPosition) }
            holder.btnRotate.setOnClickListener { onRotate(holder.adapterPosition) }
            holder.btnDelete.setOnClickListener { onDelete(holder.adapterPosition) }
        }

        override fun getItemCount(): Int = pages.size
    }
}
