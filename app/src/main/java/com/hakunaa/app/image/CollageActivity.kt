package com.hakunaa.app.image

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import java.io.File
import java.io.FileOutputStream
import com.hakunaa.app.core.StatusBar

/**
 * Collage Maker — full redesign (v0.1.118).
 *
 * Pick photos at the top (long-press a photo to drag and reorder
 * it, or drag it onto the preview to put it in a cell), an
 * INTERACTIVE live preview ([CollagePreviewView]: tap a cell to
 * select it, drag inside it to pan the photo, drag a cell edge in
 * Freestyle to resize it), real style engines (Minimal /
 * Freestyle / Poster with caption / Stitching with dashed sewn
 * borders), a Borders slider + background colour, ratio, and
 * layout template thumbnails. Save renders the exact preview
 * model at 1080px and writes it to Pictures/Hakunaa.
 *
 * The preview and Save share one model: [cells] — a list of
 * [CollageCell] (rect fractions + photo index + pan offset) that
 * the activity owns and the preview mutates in place. Save
 * replays that model at full resolution (same gap rule, same
 * poster caption band, same stitch dashes, same pan), so the
 * saved file matches what the preview showed.
 */
class CollageActivity : AppCompatActivity() {

    private data class Template(val name: String, val cells: List<RectF>)

    private val templates = listOf(
        Template(
            "Grid 2x2", listOf(
                RectF(0f, 0f, 0.5f, 0.5f), RectF(0.5f, 0f, 1f, 0.5f),
                RectF(0f, 0.5f, 0.5f, 1f), RectF(0.5f, 0.5f, 1f, 1f)
            )
        ),
        Template(
            "Side by side", listOf(
                RectF(0f, 0f, 0.5f, 1f), RectF(0.5f, 0f, 1f, 1f)
            )
        ),
        Template(
            "Stacked", listOf(
                RectF(0f, 0f, 1f, 0.5f), RectF(0f, 0.5f, 1f, 1f)
            )
        ),
        Template(
            "Row of 3", listOf(
                RectF(0f, 0f, 1f / 3, 1f), RectF(1f / 3, 0f, 2f / 3, 1f),
                RectF(2f / 3, 0f, 1f, 1f)
            )
        ),
        Template(
            "Column of 3", listOf(
                RectF(0f, 0f, 1f, 1f / 3), RectF(0f, 1f / 3, 1f, 2f / 3),
                RectF(0f, 2f / 3, 1f, 1f)
            )
        ),
        Template(
            "Poster", listOf(
                RectF(0f, 0f, 1f, 0.66f),
                RectF(0f, 0.66f, 0.5f, 1f), RectF(0.5f, 0.66f, 1f, 1f)
            )
        )
    )

    private val styles = listOf("Minimal", "Freestyle", "Poster", "Stitching")
    private val ratios = listOf(1 to 1, 4 to 5, 9 to 16, 3 to 4)
    private val bgColors = listOf(
        Color.WHITE to "White",
        Color.BLACK to "Black",
        Color.parseColor("#00BFA5") to "Teal",
        Color.parseColor("#E0E0E0") to "Grey"
    )

    private val pickedUris = mutableListOf<Uri>()
    /** Shared preview/Save model — the preview mutates this in place. */
    private val cells = mutableListOf<CollageCell>()
    /** Preview-size decoded bitmaps, parallel to pickedUris. */
    private var previewBitmaps: List<Bitmap?> = emptyList()
    private var templateIndex = 0
    private var styleIndex = 0
    private var gapDp = 8
    private var bgColor = Color.WHITE
    private var rounded = false
    private var ratioW = 1
    private var ratioH = 1
    private var bgColorIndex = 0
    private var ratioIndex = 0
    private var posterCaption = ""
    /** Preview decodes are async — only the latest generation may paint. */
    private var decodeGen = 0

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                pickedUris.addAll(uris.take(6 - pickedUris.size))
                rebuildCells()
                rebuildStrip()
                loadPreviewBitmaps()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collage)

        StatusBar.setup(this, true)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        rebuildCells()
        val preview = findViewById<CollagePreviewView>(R.id.previewView)
        preview.onPhotoDropped = { fromPhoto, cellIndex ->
            // Assign that photo to the cell; if it already lives in
            // another cell, swap the two cells' photos.
            val other = cells.indexOfFirst { it.photoIndex == fromPhoto }
            if (other != -1 && other != cellIndex) {
                val tmp = cells[cellIndex].photoIndex
                cells[cellIndex].photoIndex = fromPhoto
                cells[other].photoIndex = tmp
            } else {
                cells[cellIndex].photoIndex = fromPhoto
            }
            cells[cellIndex].panX = 0f
            cells[cellIndex].panY = 0f
            updatePreview()
        }
        preview.onModelChanged = {
            // Model already mutated in place; nothing to re-render.
        }
        findViewById<EditText>(R.id.etPosterCaption).addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    posterCaption = s?.toString().orEmpty()
                    updatePreview()
                }
            }
        )

        buildStyleTabs()
        buildRatioChips()
        buildColorSwatches()
        buildTemplateRow()
        rebuildStrip()
        findViewById<SeekBar>(R.id.sbBorder).apply {
            max = 40
            progress = gapDp
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        gapDp = p
                        findViewById<TextView>(R.id.tvBorderValue).text = "${p}dp"
                        updatePreview()
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        findViewById<TextView>(R.id.tvBorderValue).text = "${gapDp}dp"
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMakeCollage)
            .setOnClickListener { makeCollage() }
        updatePreview()
    }

    private val currentStyle: CollageStyle
        get() = CollageStyle.entries[styleIndex]

    /** Rebuild [cells] from the current template, keeping each
     *  cell's photo + pan when the cell count is unchanged. */
    private fun rebuildCells() {
        val rects = templates[templateIndex].cells
        val old = cells.toList()
        cells.clear()
        for ((i, rect) in rects.withIndex()) {
            val r = RectF(rect)
            // Freestyle starts slightly inset so cells read as freeform.
            if (currentStyle == CollageStyle.FREESTYLE) {
                r.inset(0.015f, 0.015f)
            }
            val cell = CollageCell(r, i)
            if (i < old.size) {
                cell.photoIndex = old[i].photoIndex.coerceIn(0, maxOf(0, pickedUris.size - 1))
                cell.panX = old[i].panX
                cell.panY = old[i].panY
            }
            cells.add(cell)
        }
    }

    /** Push the current model into the interactive preview. */
    private fun updatePreview() {
        val preview = findViewById<CollagePreviewView>(R.id.previewView)
        preview.setModel(
            previewBitmaps, cells, currentStyle, gapDp, bgColor,
            rounded, ratioW, ratioH, posterCaption
        )
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = if (pickedUris.size < cells.size) {
            "Pick ${cells.size} photo(s) for the ${templates[templateIndex].name} layout"
        } else {
            getString(R.string.ready)
        }
    }

    /** Decode preview-size bitmaps off the UI thread, then hand them
     *  to the preview (which draws them directly — no render pass). */
    private fun loadPreviewBitmaps() {
        val gen = ++decodeGen
        val uris = pickedUris.toList()
        Thread {
            try {
                val decoded = uris.mapIndexed { index, uri ->
                    try {
                        val file = FileUtils.copyUriToCache(this, uri, "collage_thumb_$index")
                        val bmp = decodeForCollage(file, 720)
                        try { file.delete() } catch (_: Throwable) { }
                        bmp
                    } catch (_: Throwable) {
                        null
                    }
                }
                runOnUiThread {
                    if (gen != decodeGen) {
                        decoded.forEach { it?.recycle() }
                        return@runOnUiThread
                    }
                    previewBitmaps.forEach { it?.recycle() }
                    previewBitmaps = decoded
                    updatePreview()
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    // ---------- style tabs ----------
    private fun buildStyleTabs() {
        val row = findViewById<LinearLayout>(R.id.styleRow)
        row.removeAllViews()
        for ((i, name) in styles.withIndex()) {
            val tv = TextView(this).apply {
                text = name
                textSize = 15f
                setPadding(20, 12, 20, 12)
                setTypeface(null, if (i == styleIndex) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(
                    if (i == styleIndex) getColor(R.color.hakunaa_teal)
                    else getColor(R.color.hakunaa_text_secondary)
                )
            }
            tv.setOnClickListener { applyStyle(i) }
            row.addView(tv)
        }
    }

    /** Real style engines (not presets): Minimal = clean grid, thin
     *  gaps, square corners; Freestyle = freeform draggable /
     *  resizable cells with rounded corners; Poster = poster layout
     *  + caption band; Stitching = dashed sewn borders. */
    private fun applyStyle(index: Int) {
        styleIndex = index
        when (index) {
            0 -> { gapDp = 8; bgColor = Color.WHITE; rounded = false }
            1 -> { gapDp = 16; bgColor = Color.WHITE; rounded = true }
            2 -> { gapDp = 12; bgColor = Color.WHITE; rounded = false; templateIndex = 5 }
            3 -> { gapDp = 12; bgColor = Color.WHITE; rounded = false }
        }
        bgColorIndex = bgColors.indexOfFirst { it.first == bgColor }.coerceAtLeast(0)
        findViewById<SeekBar>(R.id.sbBorder).progress = gapDp
        findViewById<TextView>(R.id.tvBorderValue).text = "${gapDp}dp"
        findViewById<EditText>(R.id.etPosterCaption).visibility =
            if (index == 2) View.VISIBLE else View.GONE
        rebuildCells()
        buildStyleTabs()
        buildColorSwatches()
        buildTemplateRow()
        updatePreview()
    }

    // ---------- ratio chips ----------
    private fun buildRatioChips() {
        val row = findViewById<LinearLayout>(R.id.ratioRow)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        for ((i, r) in ratios.withIndex()) {
            val chip = TextView(this).apply {
                text = "${r.first}:${r.second}"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                setTextColor(
                    if (i == ratioIndex) getColor(android.R.color.white)
                    else getColor(R.color.hakunaa_text)
                )
                background = if (i == ratioIndex) {
                    getDrawable(R.drawable.bg_teal_button)
                } else {
                    getDrawable(R.drawable.bg_search)
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (8 * density).toInt()
                layoutParams = lp
            }
            chip.setOnClickListener {
                ratioIndex = i
                ratioW = r.first
                ratioH = r.second
                buildRatioChips()
                updatePreview()
            }
            row.addView(chip)
        }
    }

    // ---------- background colour swatches ----------
    private fun buildColorSwatches() {
        val row = findViewById<LinearLayout>(R.id.colorRow)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        for ((i, c) in bgColors.withIndex()) {
            val swatch = View(this).apply {
                val size = (36 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (12 * density).toInt()
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(c.first)
                    setStroke(
                        (if (i == bgColorIndex) 3 else 1) * density.toInt(),
                        if (i == bgColorIndex) getColor(R.color.hakunaa_teal)
                        else getColor(R.color.hakunaa_outline)
                    )
                }
            }
            swatch.setOnClickListener {
                bgColorIndex = i
                bgColor = c.first
                buildColorSwatches()
                updatePreview()
            }
            row.addView(swatch)
        }
    }

    // ---------- layout template thumbnails ----------
    private fun buildTemplateRow() {
        val row = findViewById<LinearLayout>(R.id.templateRow)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        for ((i, t) in templates.withIndex()) {
            val holder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (12 * density).toInt()
                layoutParams = lp
            }
            val thumb = ImageView(this).apply {
                val size = (72 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size)
                setImageBitmap(drawTemplateThumb(t))
                setBackgroundResource(
                    if (i == templateIndex) R.drawable.filter_selected_border
                    else R.drawable.bg_search
                )
                setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            }
            val label = TextView(this).apply {
                text = t.name
                textSize = 11f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                gravity = Gravity.CENTER
            }
            thumb.setOnClickListener {
                templateIndex = i
                rebuildCells()
                buildTemplateRow()
                updatePreview()
            }
            holder.addView(thumb)
            holder.addView(label)
            row.addView(holder)
        }
    }

    /** Tiny preview of a template: grey field, white cells. */
    private fun drawTemplateThumb(t: Template): Bitmap {
        val size = 120
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.parseColor("#EDEFF2"))
        val paint = Paint().apply { color = Color.WHITE }
        val gap = 4f
        for (cell in t.cells) {
            canvas.drawRect(
                cell.left * size + gap, cell.top * size + gap,
                cell.right * size - gap, cell.bottom * size - gap, paint
            )
        }
        return bmp
    }

    // ---------- photo strip (top): thumbnails + Add tile, drag to reorder / to preview ----------
    private fun rebuildStrip() {
        val strip = findViewById<LinearLayout>(R.id.photoStrip)
        strip.removeAllViews()
        val density = resources.displayMetrics.density
        val tile = (88 * density).toInt()
        for ((i, uri) in pickedUris.withIndex()) {
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(tile, tile).apply {
                    marginEnd = (8 * density).toInt()
                }
                tag = i
            }
            val iv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            // Reuse the preview bitmap if already decoded.
            previewBitmaps.getOrNull(i)?.let { if (!it.isRecycled) iv.setImageBitmap(it) }
            val remove = TextView(this).apply {
                text = "×"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xAA000000.toInt())
                }
                layoutParams = FrameLayout.LayoutParams(
                    (24 * density).toInt(), (24 * density).toInt()
                ).apply { gravity = Gravity.TOP or Gravity.END }
            }
            remove.setOnClickListener {
                pickedUris.removeAt(i)
                rebuildCells()
                rebuildStrip()
                loadPreviewBitmaps()
            }
            frame.addView(iv)
            frame.addView(remove)
            // Long-press to drag: drop on another tile to reorder,
            // drop on the preview to assign this photo to that cell.
            frame.setOnLongClickListener { v ->
                v.startDragAndDrop(
                    ClipData.newPlainText("collage_index", i.toString()),
                    View.DragShadowBuilder(v), null, 0
                )
                true
            }
            frame.setOnDragListener { v, event ->
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> true
                    DragEvent.ACTION_DROP -> {
                        val from = event.clipData?.getItemAt(0)?.text?.toString()?.toIntOrNull()
                        val to = (v.tag as? Int) ?: pickedUris.size - 1
                        if (from != null && from != to && from in pickedUris.indices) {
                            val uri = pickedUris.removeAt(from)
                            pickedUris.add(to.coerceIn(0, pickedUris.size), uri)
                            rebuildCells()
                            rebuildStrip()
                            loadPreviewBitmaps()
                        }
                        true
                    }
                    else -> true
                }
            }
            strip.addView(frame)
            // Fill the tile from the preview bitmap once decoded.
            Thread {
                try {
                    val bmp = previewBitmaps.getOrNull(i)
                    if (bmp == null || bmp.isRecycled) {
                        val file = FileUtils.copyUriToCache(this, uri, "collage_thumb_$i")
                        val decoded = BitmapFactory.decodeFile(file.absolutePath)
                        runOnUiThread { if (decoded != null) iv.setImageBitmap(decoded) }
                    }
                } catch (_: Throwable) {
                }
            }.start()
        }
        // Add tile.
        val add = TextView(this).apply {
            text = "+ Add"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.hakunaa_teal))
            setBackgroundResource(R.drawable.bg_search)
            layoutParams = LinearLayout.LayoutParams(tile, tile)
        }
        add.setOnClickListener { pickPhotos.launch("image/*") }
        strip.addView(add)
        findViewById<TextView>(R.id.tvPhotos).text =
            if (pickedUris.isEmpty()) "Pick at least 2 photos"
            else "${pickedUris.size} photo(s) picked — long-press to reorder, drag onto the preview to place, tap a photo in the preview to pan it"
    }

    // ---------- render engine (Save path — replays the preview model) ----------
    /** Render the collage at [canvasWidth] px wide (aspect = ratio),
     *  replaying [cells] exactly as the preview shows them. */
    private fun renderCollage(canvasWidth: Int): Bitmap? {
        if (pickedUris.size < cells.size) return null
        val width = canvasWidth
        val height = (canvasWidth * ratioH / ratioW).coerceAtLeast(1)
        val collage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(collage)
        canvas.drawColor(bgColor)
        val gapPx = gapDp * width / 360f
        val scaleY = CollagePreviewView.posterScaleY(currentStyle, posterCaption)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        val density = resources.displayMetrics.density
        val cornerRadius = when {
            currentStyle == CollageStyle.FREESTYLE -> 14f * density * canvasWidth / 720f
            rounded -> 12f * density * canvasWidth / 720f
            else -> 0f
        }
        for (cell in cells) {
            val uri = pickedUris.getOrNull(cell.photoIndex) ?: continue
            val file = FileUtils.copyUriToCache(this, uri, "collage_cell_${cell.photoIndex}")
            val bitmap = decodeForCollage(file, 1200) ?: continue
            val half = gapPx / 2f
            val dst = Rect(
                (cell.rect.left * width + half).toInt(),
                (cell.rect.top * height * scaleY + half).toInt(),
                (cell.rect.right * width - half).toInt(),
                (cell.rect.bottom * height * scaleY - half).toInt()
            )
            if (cornerRadius > 0f) {
                val path = Path()
                path.addRoundRect(RectF(dst), cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(path)
                drawCell(canvas, bitmap, dst, paint, cell.panX, cell.panY)
                canvas.restore()
            } else {
                drawCell(canvas, bitmap, dst, paint, cell.panX, cell.panY)
            }
            if (currentStyle == CollageStyle.FREESTYLE) {
                val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 6f * canvasWidth / 720f
                }
                if (cornerRadius > 0f) {
                    canvas.drawRoundRect(RectF(dst), cornerRadius, cornerRadius, border)
                } else {
                    canvas.drawRect(RectF(dst), border)
                }
            }
            if (currentStyle == CollageStyle.STITCHING) {
                val inset = gapPx / 2f + 5f * density * canvasWidth / 720f
                val stitch = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#5A6472")
                    style = Paint.Style.STROKE
                    strokeWidth = 2.5f * density * canvasWidth / 720f
                    pathEffect = DashPathEffect(
                        floatArrayOf(9f * density * canvasWidth / 720f, 6f * density * canvasWidth / 720f), 0f
                    )
                }
                canvas.drawRect(
                    dst.left + inset, dst.top + inset,
                    dst.right - inset, dst.bottom - inset, stitch
                )
            }
            bitmap.recycle()
            try { file.delete() } catch (_: Throwable) { }
        }
        // Poster caption, centred in the reserved bottom band.
        if (currentStyle == CollageStyle.POSTER && posterCaption.isNotBlank()) {
            val bandTop = height * (1f - CollagePreviewView.POSTER_CAPTION_FRACTION)
            val bandH = height * CollagePreviewView.POSTER_CAPTION_FRACTION
            val darkBg = run {
                val r = Color.red(bgColor) / 255f
                val g = Color.green(bgColor) / 255f
                val b = Color.blue(bgColor) / 255f
                0.2126f * r + 0.7152f * g + 0.0722f * b < 0.5f
            }
            val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (darkBg) Color.WHITE else Color.parseColor("#1A1A1A")
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
            }
            var textSize = bandH * 0.42f
            captionPaint.textSize = textSize
            while (textSize > bandH * 0.18f &&
                captionPaint.measureText(posterCaption) > width * 0.92f
            ) {
                textSize *= 0.9f
                captionPaint.textSize = textSize
            }
            canvas.drawText(
                posterCaption, width / 2f,
                bandTop + bandH / 2f + textSize * 0.35f, captionPaint
            )
        }
        return collage
    }

    /** Decode a photo downsampled so its longest side is at most [maxDim]. */
    private fun decodeForCollage(file: File, maxDim: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            var sample = 1
            val largest = maxOf(options.outWidth, options.outHeight)
            while (largest / sample > maxDim) sample *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (_: Throwable) {
            null
        }
    }

    /** Draw a bitmap centre-cropped to fill [dst], shifted by the
     *  cell's pan offset (fractions of the cell size). */
    private fun drawCell(canvas: Canvas, bitmap: Bitmap, dst: Rect, paint: Paint, panX: Float, panY: Float) {
        val scale = maxOf(dst.width().toFloat() / bitmap.width, dst.height().toFloat() / bitmap.height)
        val srcW = (dst.width() / scale).toInt().coerceAtLeast(1)
        val srcH = (dst.height() / scale).toInt().coerceAtLeast(1)
        val cx = (bitmap.width / 2f + panX * dst.width() / scale).toInt()
        val cy = (bitmap.height / 2f + panY * dst.height() / scale).toInt()
        val src = Rect(
            (cx - srcW / 2).coerceIn(0, bitmap.width - srcW),
            (cy - srcH / 2).coerceIn(0, bitmap.height - srcH),
            (cx + srcW / 2).coerceIn(srcW, bitmap.width),
            (cy + srcH / 2).coerceIn(srcH, bitmap.height)
        )
        canvas.drawBitmap(bitmap, src, dst, paint)
    }

    private fun makeCollage() {
        if (pickedUris.size < cells.size) {
            Toast.makeText(
                this,
                "This layout needs ${cells.size} photos — pick ${cells.size - pickedUris.size} more",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Making collage…"
        Thread {
            try {
                val collage = renderCollage(1080) ?: throw IllegalStateException("render failed")
                val out = File(cacheDir, "hakunaa_collage_${System.currentTimeMillis()}.jpg")
                FileOutputStream(out).use { stream ->
                    collage.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                }
                collage.recycle()
                runOnUiThread {
                    val saved = MediaStoreSaver.save(this, out, MediaStoreSaver.Kind.IMAGE, out.name)
                    status.text = if (saved != null) "Done — collage saved to Pictures/Hakunaa"
                    else "Done, but couldn't save to Pictures — please try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Failed — try other photos" }
            }
        }.start()
    }

    override fun onDestroy() {
        previewBitmaps.forEach { it?.recycle() }
        super.onDestroy()
    }
}
