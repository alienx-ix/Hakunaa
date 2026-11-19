package com.hakunaa.app.image

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.core.StatusBar
import com.hakunaa.app.pdf.PdfTools
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.util.Collections

/**
 * Image to PDF — multiple images (v0.1.132, Batch 16).
 *
 * Professional multi-page flow (iLovePDF / CamScanner style):
 * pick MULTIPLE images at once, see each as a page with a
 * thumbnail, DRAG to reorder pages, add more images, remove a
 * page, pick page size (A4 / Letter) + orientation (Portrait /
 * Landscape), then Create PDF. The PDF itself is built by the
 * existing PdfTools.imageToPdf (one page per image, in order) —
 * no new PDF engine. Pages survive rotation (URIs are saved
 * and re-added on restore).
 */
class ImageToPdfActivity : AppCompatActivity() {

    private data class PageItem(
        val uri: Uri,
        val file: File,
        val name: String,
        val size: Long,
        var thumb: Bitmap? = null
    )

    private val pages = mutableListOf<PageItem>()
    private lateinit var adapter: PagesAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnCreate: MaterialButton
    private var creating = false
    private var saving = false
    /** The 3 built sizes (Normal / Compress / Extreme), for cleanup after save. */
    private var builtVariants = listOf<File>()

    /** Page size: "A4" or "Letter". Orientation: "Portrait" or "Landscape". */
    private var pageSize = "A4"
    private var orientation = "Portrait"

    private val sizeChips = mutableListOf<TextView>()
    private val orientationChips = mutableListOf<TextView>()

    private val pickMultiple =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) addUris(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.hakunaa_background))
        }
        // Top bar: X + title.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (8 * density).toInt(), 0,
                (16 * density).toInt(), 0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            )
        }
        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(getColor(R.color.hakunaa_text))
            setPadding(
                (8 * density).toInt(), (8 * density).toInt(),
                (8 * density).toInt(), (8 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                (40 * density).toInt(), (40 * density).toInt()
            )
        }
        close.setOnClickListener { finish() }
        val title = TextView(this).apply {
            text = "Image to PDF"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        topBar.addView(close)
        topBar.addView(title)
        root.addView(topBar)

        // Options: page size + orientation.
        val optionsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (4 * density).toInt(),
                (16 * density).toInt(), 0
            )
        }
        optionsBox.addView(optionRow("Page size", listOf("A4", "Letter"), sizeChips) {
            pageSize = it
            paintChips()
        })
        optionsBox.addView(optionRow("Orientation", listOf("Portrait", "Landscape"), orientationChips) {
            orientation = it
            paintChips()
        })
        root.addView(optionsBox)

        // Page count + drag hint.
        tvCount = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            setPadding(
                (16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (4 * density).toInt()
            )
        }
        root.addView(tvCount)

        // Pages list — drag to reorder.
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ImageToPdfActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(
                (16 * density).toInt(), 0,
                (16 * density).toInt(), (8 * density).toInt()
            )
            clipToPadding = false
        }
        adapter = PagesAdapter()
        list.adapter = adapter
        itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                Collections.swap(pages, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Renumber every row after a drag (Page N is
                // position-based, so the displaced rows need a
                // fresh bind too).
                adapter.notifyDataSetChanged()
                refreshState()
            }
        })
        itemTouchHelper.attachToRecyclerView(list)
        root.addView(list)

        // Empty state.
        tvEmpty = TextView(this).apply {
            text = "No pages yet — tap Add images to pick photos"
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            setPadding(
                (16 * density).toInt(), (32 * density).toInt(),
                (16 * density).toInt(), 0
            )
            visibility = View.GONE
        }
        root.addView(tvEmpty)

        // Bottom bar: Add images / Create PDF.
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                (16 * density).toInt(), (8 * density).toInt(),
                (16 * density).toInt(), (16 * density).toInt()
            )
        }
        val addBtn = MaterialButton(this).apply {
            text = "Add images"
            setTextColor(getColor(R.color.hakunaa_teal))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
            strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            strokeWidth = (2 * density).toInt()
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                0, (56 * density).toInt(), 1f
            ).apply { marginEnd = (6 * density).toInt() }
        }
        addBtn.setOnClickListener { pickMultiple.launch("image/*") }
        btnCreate = MaterialButton(this).apply {
            text = "Create PDF"
            setTextColor(getColor(android.R.color.white))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                0, (56 * density).toInt(), 1f
            ).apply { marginStart = (6 * density).toInt() }
        }
        btnCreate.setOnClickListener { createPdf() }
        bottomBar.addView(addBtn)
        bottomBar.addView(btnCreate)
        root.addView(bottomBar)

        setContentView(root)
        // Status bar (v0.1.133, Batch 17): setup AFTER
        // setContentView — calling it before meant the content
        // view did not exist yet, so the inset padding was never
        // applied and the header overlapped the status bar.
        StatusBar.setup(this, true)
        paintChips()

        // Restore pages after rotation, else open the picker
        // straight away (the old flow picked immediately too).
        val restored = savedInstanceState?.getStringArrayList("page_uris")
        if (!restored.isNullOrEmpty()) {
            addUris(restored.map { Uri.parse(it) })
        } else if (savedInstanceState == null) {
            root.post { pickMultiple.launch("image/*") }
        }
        refreshState()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("page_uris", ArrayList(pages.map { it.uri.toString() }))
    }

    /** One options row: a small label + selectable pills. */
    private fun optionRow(
        label: String,
        options: List<String>,
        chips: MutableList<TextView>,
        onPick: (String) -> Unit
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                (104 * density).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        chips.clear()
        for (option in options) {
            val chip = TextView(this).apply {
                text = option
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(
                    (16 * density).toInt(), (9 * density).toInt(),
                    (16 * density).toInt(), (9 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * density).toInt() }
            }
            chip.setOnClickListener { onPick(option) }
            chips.add(chip)
            row.addView(chip)
        }
        return row
    }

    /** Paint both chip rows for the current page size + orientation. */
    private fun paintChips() {
        val density = resources.displayMetrics.density
        val defs = listOf(
            sizeChips to listOf("A4", "Letter") to pageSize,
            orientationChips to listOf("Portrait", "Landscape") to orientation
        )
        for ((pair, current) in defs) {
            val (chips, options) = pair
            for ((i, chip) in chips.withIndex()) {
                val selected = options[i] == current
                chip.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 20f * density
                    if (selected) setColor(getColor(R.color.hakunaa_teal))
                    else {
                        setColor(getColor(R.color.hakunaa_surface))
                        setStroke((1 * density).toInt(), getColor(R.color.hakunaa_teal))
                    }
                }
                chip.setTextColor(
                    if (selected) getColor(android.R.color.white)
                    else getColor(R.color.hakunaa_teal)
                )
            }
        }
    }

    /** Add picked URIs as pages (skips ones already listed). */
    private fun addUris(uris: List<Uri>) {
        val fresh = uris.filter { uri -> pages.none { it.uri == uri } }
        if (fresh.isEmpty()) return
        Toast.makeText(this, "Adding ${fresh.size} image(s)…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val added = mutableListOf<PageItem>()
                for ((i, uri) in fresh.withIndex()) {
                    val file = FileUtils.copyUriToCache(
                        this, uri, "pdf_page_${System.currentTimeMillis()}_$i"
                    )
                    val name = FileUtils.fileName(contentResolver, uri)
                    val size = FileUtils.fileSize(contentResolver, uri)
                    val thumb = decodeThumb(file)
                    added.add(PageItem(uri, file, name, size, thumb))
                }
                runOnUiThread {
                    val start = pages.size
                    pages.addAll(added)
                    adapter.notifyItemRangeInserted(start, added.size)
                    refreshState()
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't add some images", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Small EXIF-aware thumbnail (~200px) for a page row. */
    private fun decodeThumb(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > 200) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            val degrees = try {
                when (ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Throwable) {
                0f
            }
            if (degrees != 0f) {
                val m = android.graphics.Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                bmp.recycle()
                bmp = rotated
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** Page count, empty state, Create button. */
    private fun refreshState() {
        tvCount.text = if (pages.isEmpty()) "" else
            "${pages.size} page${if (pages.size == 1) "" else "s"}  •  Long-press a page to drag & reorder"
        tvEmpty.visibility = if (pages.isEmpty()) View.VISIBLE else View.GONE
        btnCreate.isEnabled = pages.isNotEmpty() && !creating
    }

    /** Create PDF — but do NOT save directly (v0.1.133, Batch
     *  17). Like the scanner export, build all 3 sizes up front
     *  (Normal / Compress / Extreme) on the chosen page, show
     *  each with its real file size, and let the user choose one
     *  to save. */
    private fun createPdf() {
        if (creating || saving || pages.isEmpty()) return
        creating = true
        refreshState()
        Toast.makeText(this, "Preparing sizes…", Toast.LENGTH_SHORT).show()
        val files = pages.map { it.file }
        val base = if (pageSize == "Letter") PDRectangle.LETTER else PDRectangle.A4
        val rect = if (orientation == "Landscape") {
            PDRectangle(base.height, base.width)
        } else {
            base
        }
        Thread {
            try {
                val stamp = System.currentTimeMillis()
                val variants = listOf(
                    "Normal" to File(cacheDir, "hakunaa_pdf_normal_$stamp.pdf"),
                    "Compress" to File(cacheDir, "hakunaa_pdf_compress_$stamp.pdf"),
                    "Extreme" to File(cacheDir, "hakunaa_pdf_extreme_$stamp.pdf")
                )
                val done = mutableListOf<Pair<String, File>>()
                for ((name, file) in variants) {
                    val ok = when (name) {
                        "Normal" -> PdfTools.imageToPdfQuality(files, file, Int.MAX_VALUE, 0.95f, rect)
                        "Compress" -> PdfTools.imageToPdfQuality(files, file, 2000, 0.8f, rect)
                        else -> PdfTools.imageToPdfQuality(files, file, 1200, 0.6f, rect)
                    }
                    if (ok && file.exists()) done.add(name to file)
                }
                runOnUiThread {
                    creating = false
                    refreshState()
                    if (done.isEmpty()) {
                        Toast.makeText(this, "Failed — try again", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    builtVariants = done.map { it.second }
                    showSizeChooser(done)
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    creating = false
                    refreshState()
                    Toast.makeText(this, "Failed — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** Size chooser: one row per built size, with its real file
     *  size — tap a row to save that PDF (scanner export style). */
    private fun showSizeChooser(built: List<Pair<String, File>>) {
        val density = resources.displayMetrics.density
        val notes = mapOf(
            "Normal" to "Full quality — largest file",
            "Compress" to "Balanced — crisp at print size",
            "Extreme" to "Smallest file — for sharing"
        )
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (20 * density).toInt(), (8 * density).toInt(),
                (20 * density).toInt(), (8 * density).toInt()
            )
        }
        var dialog: AlertDialog? = null
        for ((name, file) in built) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, (12 * density).toInt(), 0, (12 * density).toInt())
            }
            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            topRow.addView(TextView(this).apply {
                text = name
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            topRow.addView(TextView(this).apply {
                text = FileUtils.formatSize(file.length())
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_teal))
            })
            row.addView(topRow)
            row.addView(TextView(this).apply {
                text = notes[name] ?: ""
                textSize = 13f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
            row.setOnClickListener {
                dialog?.dismiss()
                saveChosen(file)
            }
            list.addView(row)
            // Divider between rows.
            if (name != built.last().first) {
                list.addView(View(this).apply {
                    setBackgroundColor(getColor(R.color.hakunaa_outline))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt()
                    )
                })
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Choose PDF size")
            .setView(list)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    /** Save the chosen size to Documents (prevents double taps). */
    private fun saveChosen(chosen: File) {
        if (saving) return
        saving = true
        Toast.makeText(this, "Saving…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val saved = MediaStoreSaver.save(
                    this, chosen, MediaStoreSaver.Kind.DOCUMENT, chosen.name
                ) != null
                // Drop the other built sizes (best effort).
                for (file in builtVariants) {
                    if (file != chosen) {
                        try { file.delete() } catch (_: Throwable) { }
                    }
                }
                runOnUiThread {
                    if (saved) {
                        Toast.makeText(
                            this, "PDF saved to Documents/Hakunaa",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    } else {
                        saving = false
                        Toast.makeText(
                            this, "Done, but couldn't save — please try again",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    saving = false
                    Toast.makeText(this, "Failed — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /** One page row: drag handle + thumbnail + Page N / name • size + remove. */
    private inner class PagesAdapter : RecyclerView.Adapter<PagesAdapter.PageHolder>() {

        inner class PageHolder(val row: View) : RecyclerView.ViewHolder(row) {
            val dragHandle: TextView = row.findViewWithTag("drag")!!
            val thumb: ImageView = row.findViewWithTag("thumb")!!
            val title: TextView = row.findViewWithTag("title")!!
            val sub: TextView = row.findViewWithTag("sub")!!
            val remove: ImageView = row.findViewWithTag("remove")!!
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val density = parent.context.resources.displayMetrics.density
            val card = MaterialCardView(parent.context).apply {
                radius = 12f * density
                cardElevation = 2f * density
                setCardBackgroundColor(getColor(R.color.hakunaa_surface))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            val inner = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (8 * density).toInt(), (10 * density).toInt(),
                    (8 * density).toInt(), (10 * density).toInt()
                )
            }
            val drag = TextView(parent.context).apply {
                text = "⋮⋮"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                gravity = Gravity.CENTER
                tag = "drag"
                setPadding(
                    (8 * density).toInt(), (8 * density).toInt(),
                    (4 * density).toInt(), (8 * density).toInt()
                )
            }
            inner.addView(drag)
            val thumbIv = ImageView(parent.context).apply {
                tag = "thumb"
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(getColor(R.color.hakunaa_outline))
                    cornerRadius = 8f * density
                }
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(
                    (64 * density).toInt(), (64 * density).toInt()
                ).apply { marginEnd = (12 * density).toInt() }
            }
            inner.addView(thumbIv)
            val textCol = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            textCol.addView(TextView(parent.context).apply {
                tag = "title"
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
            })
            textCol.addView(TextView(parent.context).apply {
                tag = "sub"
                textSize = 13f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
            inner.addView(textCol)
            val removeIv = ImageView(parent.context).apply {
                tag = "remove"
                setImageResource(R.drawable.ic_close)
                setColorFilter(getColor(R.color.hakunaa_text_secondary))
                setPadding(
                    (8 * density).toInt(), (8 * density).toInt(),
                    (8 * density).toInt(), (8 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    (40 * density).toInt(), (40 * density).toInt()
                )
            }
            inner.addView(removeIv)
            card.addView(inner)
            return PageHolder(card)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val page = pages[position]
            // First row has no top margin — the rest get 8dp.
            val density = holder.row.resources.displayMetrics.density
            (holder.row.layoutParams as RecyclerView.LayoutParams).apply {
                topMargin = if (position == 0) 0 else (8 * density).toInt()
            }
            holder.title.text = "Page ${position + 1}"
            holder.sub.text = "${page.name} • ${FileUtils.formatSize(page.size)}"
            if (page.thumb != null) holder.thumb.setImageBitmap(page.thumb)
            holder.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(holder)
                }
                false
            }
            holder.remove.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                pages.removeAt(pos).thumb?.recycle()
                notifyItemRemoved(pos)
                notifyDataSetChanged()
                refreshState()
            }
        }

        override fun getItemCount(): Int = pages.size
    }
}
