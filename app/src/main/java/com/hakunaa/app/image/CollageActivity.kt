package com.hakunaa.app.image

import android.content.ClipData
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
 * Collage Maker — professional redesign (v0.1.117).
 *
 * Pick photos at the top (long-press a photo to drag and reorder
 * it), a LIVE preview of the collage, style tabs (Minimal /
 * Freestyle / Poster / Stitching), a Borders slider + background
 * colour, ratio, and layout template thumbnails. Fully functional:
 * Save renders the chosen layout / border / colour / ratio and
 * writes it to Pictures/Hakunaa.
 *
 * Layouts are lists of cell rectangles (fractions of the canvas);
 * each photo is centre-cropped to fill its cell. Pure Bitmap
 * work — no library, fully offline.
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
    private var templateIndex = 0
    private var styleIndex = 0
    private var gapDp = 8
    private var bgColor = Color.WHITE
    private var rounded = false
    private var ratioW = 1
    private var ratioH = 1
    private var bgColorIndex = 0
    private var ratioIndex = 0
    /** Renders are async — only the latest generation may paint. */
    private var renderGen = 0

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                pickedUris.addAll(uris.take(6 - pickedUris.size))
                rebuildStrip()
                renderPreview()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collage)

        StatusBar.setup(this, true)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

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
                        renderPreview()
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        findViewById<TextView>(R.id.tvBorderValue).text = "${gapDp}dp"
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnMakeCollage)
            .setOnClickListener { makeCollage() }
        renderPreview()
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

    /** Style presets: Minimal = thin white borders; Freestyle = wide
     *  gaps + rounded corners; Poster = the poster layout; Stitching
     *  = no gaps, photos edge to edge. */
    private fun applyStyle(index: Int) {
        styleIndex = index
        when (index) {
            0 -> { gapDp = 8; bgColor = Color.WHITE; rounded = false }
            1 -> { gapDp = 24; bgColor = Color.WHITE; rounded = true }
            2 -> { gapDp = 12; bgColor = Color.WHITE; rounded = false; templateIndex = 5 }
            3 -> { gapDp = 0; bgColor = Color.WHITE; rounded = false }
        }
        bgColorIndex = bgColors.indexOfFirst { it.first == bgColor }.coerceAtLeast(0)
        findViewById<SeekBar>(R.id.sbBorder).progress = gapDp
        findViewById<TextView>(R.id.tvBorderValue).text = "${gapDp}dp"
        buildStyleTabs()
        buildColorSwatches()
        buildTemplateRow()
        renderPreview()
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
                renderPreview()
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
                renderPreview()
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
                buildTemplateRow()
                renderPreview()
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

    // ---------- photo strip (top): thumbnails + Add tile, drag to reorder ----------
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
                rebuildStrip()
                renderPreview()
            }
            frame.addView(iv)
            frame.addView(remove)
            // Long-press to drag and reorder; drop on another tile.
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
                            rebuildStrip()
                            renderPreview()
                        }
                        true
                    }
                    else -> true
                }
            }
            strip.addView(frame)
            Thread {
                try {
                    val file = FileUtils.copyUriToCache(this, uri, "collage_thumb_$i")
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    runOnUiThread { if (bmp != null) iv.setImageBitmap(bmp) }
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
            else "${pickedUris.size} photo(s) picked — long-press to drag and reorder"
    }

    // ---------- live preview + render engine ----------
    private fun renderPreview() {
        val gen = ++renderGen
        val template = templates[templateIndex]
        if (pickedUris.size < template.cells.size) {
            findViewById<ImageView>(R.id.ivPreview).setImageBitmap(null)
            findViewById<TextView>(R.id.tvStatus).text =
                "Pick ${template.cells.size} photo(s) for the ${template.name} layout"
            return
        }
        findViewById<TextView>(R.id.tvStatus).text = getString(R.string.ready)
        Thread {
            try {
                val bmp = renderCollage(720)
                runOnUiThread {
                    if (gen == renderGen && bmp != null) {
                        findViewById<ImageView>(R.id.ivPreview).setImageBitmap(bmp)
                    }
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    /** Render the collage at [canvasWidth] px wide (aspect = ratio). */
    private fun renderCollage(canvasWidth: Int): Bitmap? {
        val template = templates[templateIndex]
        val cells = template.cells
        if (pickedUris.size < cells.size) return null
        val density = resources.displayMetrics.density
        val gapPx = (gapDp * canvasWidth / 360f * density).toFloat()
        val width = canvasWidth
        val height = (canvasWidth * ratioH / ratioW).coerceAtLeast(1)
        val collage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(collage)
        canvas.drawColor(bgColor)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((index, cell) in cells.withIndex()) {
            val uri = pickedUris[index]
            val file = FileUtils.copyUriToCache(this, uri, "collage_cell_$index")
            val bitmap = decodeForCollage(file, 1200) ?: continue
            val half = gapPx / 2f
            val dst = Rect(
                (cell.left * width + half).toInt(),
                (cell.top * height + half).toInt(),
                (cell.right * width - half).toInt(),
                (cell.bottom * height - half).toInt()
            )
            if (rounded) {
                val radius = 24f * canvasWidth / 720f
                val path = Path()
                path.addRoundRect(
                    RectF(dst), radius, radius, Path.Direction.CW
                )
                canvas.save()
                canvas.clipPath(path)
                drawCell(canvas, bitmap, dst, paint)
                canvas.restore()
            } else {
                drawCell(canvas, bitmap, dst, paint)
            }
            bitmap.recycle()
            try { file.delete() } catch (_: Throwable) { }
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

    /** Draw a bitmap centre-cropped to fill [dst]. */
    private fun drawCell(canvas: Canvas, bitmap: Bitmap, dst: Rect, paint: Paint) {
        val scale = maxOf(dst.width().toFloat() / bitmap.width, dst.height().toFloat() / bitmap.height)
        val scaledW = (bitmap.width * scale).toInt()
        val scaledH = (bitmap.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val srcX = (scaledW - dst.width()) / 2
        val srcY = (scaledH - dst.height()) / 2
        val src = Rect(srcX, srcY, srcX + dst.width(), srcY + dst.height())
        canvas.drawBitmap(scaled, src, dst, paint)
        scaled.recycle()
    }

    private fun makeCollage() {
        val template = templates[templateIndex]
        if (pickedUris.size < template.cells.size) {
            Toast.makeText(
                this,
                "This layout needs ${template.cells.size} photos — pick ${template.cells.size - pickedUris.size} more",
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
}
