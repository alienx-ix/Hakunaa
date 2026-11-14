package com.hakunaa.app.scanner

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Enhance screen — the CamScanner-style enhance step after crop.
 *
 * - The straightened page, with an editable document name
 *   ("Hakunaa 11-12-2026 19.53" style).
 * - Filter thumbnails: Original, Lighten, Enhance, No Shadow, B&W…
 *   Tap one to preview it on the page — and an Adjust panel
 *   opens below the filters (Brightness / Contrast / Sharpness),
 *   the way professional apps do it.
 * - Batch: swipe the page left / right to move between pages
 *   ("2 / 4" in the top bar); each page keeps its own filter.
 *   "Apply to all pages" (ticked by default for a batch) puts
 *   the current page's filter on every page on save; unticked,
 *   each page keeps the filter picked for it.
 * - Crop (bottom bar, next to Adjust): re-crop the current
 *   page from the Filter section.
 * - Rotate (was "Left", renamed v0.1.82).
 * - Draw (v0.1.82): freehand markup on the page — pen
 *   colours + sizes, Undo, Clear. The drawing is saved onto
 *   the page.
 * - Sign (v0.1.82): draw a signature in a pad, then drag it
 *   to where it belongs on the page. It is saved onto the page.
 * - The green checkmark saves the page with the chosen filter.
 *
 * The preview is a downsampled copy (≤1200 px), so picking a
 * filter or moving a slider updates at once; the save always
 * uses the full-resolution page. Not here (and not possible
 * offline): Extract Text (OCR) and To Word — those are cloud
 * features in CamScanner.
 */
class EnhanceActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "image_path"
        const val EXTRA_ENHANCED_PATH = "enhanced_path"
        const val EXTRA_PAGE_PATHS = "page_paths"
        // Start with "Apply to all pages" already ticked — the
        // batch end step, where one filter choice covers the
        // whole batch.
        const val EXTRA_APPLY_ALL_CHECKED = "apply_all_checked"
        // The confirm button's label (v0.1.76): "Continue" when
        // opened from the Batch thumbnail (it returns to the
        // camera to keep capturing), "Finish" everywhere else
        // (Done / Single / Book — it goes on to Preview /
        // Export). Defaults to "Finish" when not given.
        const val EXTRA_CONFIRM_LABEL = "confirm_label"
        // The enhanced file for EVERY page (v0.1.77), in the
        // same order as EXTRA_ENHANCED_SOURCE_PATHS, which
        // holds the source (raw) path each one came from. The
        // caller matches by source path, so a page is never
        // put in the wrong slot.
        const val EXTRA_ENHANCED_PATHS = "enhanced_paths"
        const val EXTRA_ENHANCED_SOURCE_PATHS = "enhanced_source_paths"
    }

    private var bitmap: Bitmap? = null
    private var previewBitmap: Bitmap? = null
    private var imagePath: String? = null
    private var selectedFilter = ImageEnhancer.Filter.NO_SHADOW
    private var currentPreview: Bitmap? = null
    // A tap on a filter starts a background preview pass; a
    // fast second tap must not be overwritten by the first
    // pass finishing late — only the latest pass may show.
    private var previewGeneration = 0
    private var pagePaths: List<String> = emptyList()

    // Batch pages, swipable (v0.1.58, CamScanner-style): every
    // captured page, in order — the current page first, then
    // the rest. A batch shows "2 / 4" in the top bar and the
    // user swipes the page left / right to move between pages
    // and apply a filter to each. A single page has just one
    // entry, so there is nothing to swipe.
    private var allPages: List<String> = emptyList()
    private var currentPageIndex = 0
    // Each page keeps its own filter (default NO_SHADOW), so a
    // filter picked on one page does not change the others.
    // "Apply to all pages", when ticked, overrides this on
    // save and puts the current page's filter on every page.
    private val pageFilters = mutableMapOf<String, ImageEnhancer.Filter>()

    // Draw + Sign + Text (v0.1.82/83) — each page keeps its
    // own drawing and its own placed signature / text, in
    // normalized coordinates (0..1 relative to the page), so
    // they map to any size.
    private val pageDrawings = mutableMapOf<String, MutableList<DrawView.Stroke>>()

    /** A placed overlay (signature or text): its bitmap +
     *  its normalized rect [left, top, right, bottom] on the
     *  page. */
    private data class PlacedOverlay(
        val bitmap: Bitmap,
        var rectNorm: FloatArray
    )

    private val pageSignatures = mutableMapOf<String, PlacedOverlay>()
    private val pageTexts = mutableMapOf<String, PlacedOverlay>()

    // Draw / Erase mode state (v0.1.83).
    private enum class ToolMode { NONE, DRAW, ERASE }

    private var toolMode = ToolMode.NONE
    private val drawColors = listOf(
        Color.BLACK, Color.parseColor("#D32F2F"), Color.parseColor("#1976D2"),
        Color.parseColor("#388E3C"), Color.parseColor("#00BFA5")
    )
    private var drawColorIndex = 0
    // Pen sizes in dp: thin / medium / thick.
    private val drawSizesDp = listOf(5f, 9f, 15f)
    private var drawSizeIndex = 1
    private val drawDotViews = mutableListOf<ImageView>()
    private val drawDotDrawables = mutableListOf<GradientDrawable>()

    // Overlay drag state (view pixels, within pageFrame).
    private var overlayDragOffsetX = 0f
    private var overlayDragOffsetY = 0f
    private var overlayDragging = false

    // Re-crop the current page from the Filter section
    // (v0.1.58): the Crop screen returns the cropped file,
    // which replaces this page here and in the batch list.
    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val croppedPath =
                    result.data?.getStringExtra(CropActivity.EXTRA_CROPPED_PATH)
                if (croppedPath != null) {
                    val file = File(croppedPath)
                    if (file.exists()) {
                        val oldPath = imagePath
                        imagePath = croppedPath
                        if (oldPath != null) {
                            allPages = allPages.map { if (it == oldPath) croppedPath else it }
                            pagePaths = pagePaths.map { if (it == oldPath) croppedPath else it }
                            pageFilters.remove(oldPath)
                        }
                        pageFilters[croppedPath] = selectedFilter
                        loadPageBitmap(croppedPath)
                    }
                }
            }
        }

    // Manual adjustments (the Adjust panel), on top of the filter.
    // Sharpness starts at 40, not 0 (v0.1.58 set 20, raised to
    // 40 in v0.1.59): the user asked for a little more sharpness
    // by default, everywhere, so text is always clearly visible
    // — and 20 was too subtle to see, so it did not look like it
    // worked at all. 40 is the level the user themself set by
    // hand (39) — clearly crisp, without halos. Every filter
    // gets this sharpen boost unless the user moves the slider.
    private var brightness = 0f
    private var contrast = 1f
    private var sharpness = 40f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enhance)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Default document name, CamScanner style: "Hakunaa 11-12-2026 19.53".
        val name = "Hakunaa " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())
        findViewById<EditText>(R.id.etDocName).setText(name)

        pagePaths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS) ?: emptyList()
        imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
        // Every page, in order, without duplicates — the
        // current page first. (pagePaths may or may not
        // already contain it, depending on the caller.)
        val first = imagePath
        allPages = if (first == null) {
            pagePaths
        } else {
            listOf(first) + pagePaths.filter { it != first }
        }
        currentPageIndex = 0
        if (allPages.size > 1) {
            findViewById<View>(R.id.applyAllRow).visibility = View.VISIBLE
            findViewById<TextView>(R.id.tvApplyAllCount).text =
                "(${allPages.size} pages)"
            // The batch end step starts with "Apply to all
            // pages" already ticked — one filter choice covers
            // the whole batch.
            if (intent.getBooleanExtra(EXTRA_APPLY_ALL_CHECKED, false)) {
                findViewById<CheckBox>(R.id.cbApplyAll).isChecked = true
            }
            updatePageIndicator()
        }
        // Ticking "Apply to all pages" applies the current
        // filter to every page right away (v0.1.70), so the
        // preview shows it on every page — swiping to any page
        // shows this filter. Unticking leaves each page's own
        // filter as it was.
        findViewById<CheckBox>(R.id.cbApplyAll).setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                for (path in allPages) pageFilters[path] = selectedFilter
            }
        }

        val path = imagePath
        if (path == null) {
            Toast.makeText(this, "Image not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        // Load the overlays made in an earlier Enhance run
        // (v0.1.84) — the drawing, signature and text from a
        // thumbnail review come back here, so they are still
        // on the page and are not lost when this run saves.
        loadOverlaysFromStore()
        loadPageBitmap(path)
        setupSwipe()

        findViewById<LinearLayout>(R.id.btnRotate).setOnClickListener { rotateLeft() }
        val btnSave = findViewById<ImageButton>(R.id.btnSave)
        btnSave.setOnClickListener { save() }
        // Symbol only, no text (user pick): the icon says what
        // happens next. "Continue" from a thumbnail review
        // (back to the camera) shows a right arrow; "Finish"
        // from Done / Single / Book (on to Preview / Export)
        // shows a tick.
        btnSave.setImageResource(
            if (intent.getStringExtra(EXTRA_CONFIRM_LABEL) == "Continue")
                R.drawable.ic_arrow_right else R.drawable.ic_check
        )
        // Adjust opens ONLY from its menu button — never on its
        // own after a capture. The panel stays hidden until the
        // user asks for it.
        findViewById<LinearLayout>(R.id.btnAdjust).setOnClickListener { toggleAdjustPanel() }
        findViewById<TextView>(R.id.btnAdjustReset).setOnClickListener { resetAdjustments() }
        findViewById<TextView>(R.id.btnAdjustClose).setOnClickListener {
            setAdjustPanelVisible(false)
        }
        // Crop — re-crop the current page from the Filter
        // section, right next to Adjust.
        findViewById<LinearLayout>(R.id.btnCrop).setOnClickListener { openCrop() }
        // Draw — freehand markup (v0.1.82). Erase — rub out
        // strokes (v0.1.83). Text — type text and place it
        // (v0.1.83).
        findViewById<LinearLayout>(R.id.btnDraw).setOnClickListener { setToolMode(ToolMode.DRAW) }
        findViewById<LinearLayout>(R.id.btnErase).setOnClickListener { setToolMode(ToolMode.ERASE) }
        findViewById<LinearLayout>(R.id.btnText).setOnClickListener { openAddTextDialog() }
        setupDrawPanel()
        // Sign — signature pad + placement (v0.1.82).
        findViewById<LinearLayout>(R.id.btnSign).setOnClickListener { openSignaturePad() }
        findViewById<TextView>(R.id.btnSignRemove).setOnClickListener { removeSignature() }
        findViewById<TextView>(R.id.btnSignDone).setOnClickListener {
            findViewById<View>(R.id.signPanel).visibility = View.GONE
        }
        setupSignatureDrag()
        // Text — the placed text's panel (v0.1.83) + the
        // Word-style box (v0.1.85): the X on the box deletes
        // it, the corner handle resizes it.
        findViewById<TextView>(R.id.btnTextRemove).setOnClickListener { removeText() }
        findViewById<TextView>(R.id.btnTextDone).setOnClickListener {
            findViewById<View>(R.id.textPanel).visibility = View.GONE
        }
        findViewById<TextView>(R.id.btnTextDelete).setOnClickListener { removeText() }
        setupTextDrag()
        setupTextResize()
        setupAdjustSliders()
    }

    /** Load one page (by path) into the preview: decode it,
     *  restore that page's own filter, show the raw page at
     *  once, then swap in the filtered version when ready.
     *  Used for the first page, for a swipe to another page,
     *  and after a re-crop. */
    private fun loadPageBitmap(path: String, onReady: (() -> Unit)? = null) {
        Thread {
            try {
                val bmp = BitmapFactory.decodeFile(path)
                // Downsample off the UI thread (v0.1.78):
                // scaling a full-res photo on the UI thread
                // stalls it and the screen lags. The preview
                // and the filter thumbnails' source are both
                // made here, so the UI thread only ever
                // receives ready bitmaps.
                val preview = if (bmp != null) downsample(bmp, 1200) else null
                val thumb = if (bmp != null) {
                    Bitmap.createScaledBitmap(
                        bmp, 160, (160f * bmp.height / bmp.width).toInt(), true
                    )
                } else null
                runOnUiThread {
                    if (bmp == null || preview == null || thumb == null) {
                        Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                        finish()
                        return@runOnUiThread
                    }
                    bitmap?.recycle()
                    previewBitmap?.recycle()
                    currentPreview?.recycle()
                    currentPreview = null
                    bitmap = bmp
                    previewBitmap = preview
                    // This page's own filter (default NO_SHADOW).
                    selectedFilter = pageFilters[path] ?: ImageEnhancer.Filter.NO_SHADOW
                    // Show the downsampled preview at once, then
                    // swap in the filtered version when it is
                    // ready. Never put the full-res bitmap on
                    // the screen (v0.1.77) — uploading a huge
                    // texture stalls the UI thread and the
                    // screen lags; the full-res bitmap is only
                    // needed at save time.
                    findViewById<ImageView>(R.id.ivPage).setImageBitmap(previewBitmap)
                    applyFilterToPreview(selectedFilter)
                    buildFilterThumbnails(thumb)
                    updatePageIndicator()
                    // This page's own drawing + signature.
                    refreshOverlays()
                    onReady?.invoke()
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }

    /** "2 / 4" in the top bar — only for a batch. */
    private fun updatePageIndicator() {
        val indicator = findViewById<TextView>(R.id.tvPageIndicator)
        if (allPages.size > 1) {
            indicator.visibility = View.VISIBLE
            indicator.text = "${currentPageIndex + 1} / ${allPages.size}"
        } else {
            indicator.visibility = View.GONE
        }
    }

    /** Swipe the page left / right to move between batch
     *  pages (CamScanner-style). A swipe stores the current
     *  page's filter first, so each page keeps the filter
     *  picked for it.
     *
     *  This tracks the drag by hand (ACTION_DOWN → ACTION_UP)
     *  instead of a GestureDetector fling: a fling only fires
     *  for a fast swipe, but the user swipes slowly, like
     *  turning pages in a gallery — a slow drag never fired
     *  onFling, so no page ever came. Any mostly-horizontal
     *  drag past 100px moves a page.
     *
     *  (Zoom was removed in v0.1.85 — the user asked for it
     *  to go from this Filter preview: it felt poor here. The
     *  full-screen page preview still zooms.) */
    private fun setupSwipe() {
        val pageView = findViewById<ImageView>(R.id.ivPage)
        var downX = 0f
        var downY = 0f
        pageView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (allPages.size > 1) {
                        val dx = event.x - downX
                        val dy = event.y - downY
                        if (abs(dx) > 100f && abs(dx) > abs(dy) * 1.5f) {
                            if (dx < 0) showPage(currentPageIndex + 1)
                            else showPage(currentPageIndex - 1)
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    /** Show page [index] (clamped). Only the PAGE slides — a
     *  smooth slide animation on ivPage alone, the way a
     *  gallery does it. The filter strip, the top bar and the
     *  bottom bar never move. (The old code swapped the bitmap
     *  instantly and rebuilt the whole filter row on every
     *  swipe, so the entire screen shook.) A swipe during the
     *  slide is ignored, so two slides never overlap. */
    private var pageAnimating = false

    private fun showPage(index: Int) {
        if (allPages.isEmpty() || pageAnimating) return
        val next = index.coerceIn(0, allPages.size - 1)
        if (next == currentPageIndex) return
        val pageView = findViewById<ImageView>(R.id.ivPage)
        val width = pageView.width.toFloat()
        // Keep the filter picked for the page being left, and
        // its drawing + signature.
        val current = imagePath
        if (current != null) {
            pageFilters[current] = selectedFilter
            storeCurrentOverlays()
        }
        val direction = if (next > currentPageIndex) -1f else 1f
        currentPageIndex = next
        imagePath = allPages[next]
        if (width <= 0f) {
            // Not laid out yet — just load, no animation.
            imagePath?.let { loadPageBitmap(it) }
            return
        }
        pageAnimating = true
        // Slide the old page out toward the swipe, then slide
        // the new page in from the other side.
        pageView.animate()
            .translationX(direction * width)
            .setDuration(180)
            .withEndAction {
                imagePath?.let { path ->
                    loadPageBitmap(path) {
                        pageView.translationX = -direction * width
                        pageView.animate()
                            .translationX(0f)
                            .setDuration(250)
                            .withEndAction { pageAnimating = false }
                            .start()
                    }
                } ?: run { pageAnimating = false }
            }
            .start()
    }

    /** Re-crop the current page: the Crop screen opens for
     *  this page and returns the cropped file here. */
    private fun openCrop() {
        val path = imagePath ?: return
        val intent = Intent(this, CropActivity::class.java)
        intent.putExtra(CropActivity.EXTRA_PHOTO_PATH, path)
        intent.putStringArrayListExtra(
            EnhanceActivity.EXTRA_PAGE_PATHS,
            ArrayList(allPages)
        )
        intent.putExtra(CropActivity.EXTRA_SKIP_ENHANCE, true)
        cropLauncher.launch(intent)
    }

    /** A downsampled copy for the live preview — fast enough
     *  that a filter tap or a slider move updates at once. */
    private fun downsample(bitmap: Bitmap, maxSide: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= maxSide) return bitmap
        val scale = maxSide.toFloat() / max
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    /** "Apply to all pages" is ticked (and there is more than one page). */
    private fun isApplyAllChecked(): Boolean =
        allPages.size > 1 &&
            findViewById<CheckBox>(R.id.cbApplyAll)?.isChecked == true

    /** Filter + adjustments, on the preview bitmap. */
    private fun applyFilterToPreview(filter: ImageEnhancer.Filter) {
        val bmp = previewBitmap ?: return
        selectedFilter = filter
        // Remember this filter for the page being shown, so a
        // swipe to another page and back keeps it. When "Apply
        // to all pages" is ticked, remember it for EVERY page
        // (v0.1.70) — otherwise swiping to another page showed
        // that page's old filter and the preview never changed,
        // even though Apply to all was on.
        if (isApplyAllChecked()) {
            for (path in allPages) pageFilters[path] = filter
        } else {
            imagePath?.let { pageFilters[it] = filter }
        }
        // The Adjust panel does NOT open here — a filter tap
        // only changes the filter. The panel opens only from
        // its own menu button.
        val generation = ++previewGeneration
        Thread {
            try {
                val filtered = ImageEnhancer.apply(bmp, filter)
                val adjusted = ImageEnhancer.applyAdjustments(
                    filtered, brightness, contrast, sharpness
                )
                if (adjusted != filtered) filtered.recycle()
                runOnUiThread {
                    // A newer tap already started its own pass —
                    // this stale result must not overwrite it.
                    if (generation != previewGeneration) {
                        adjusted.recycle()
                        return@runOnUiThread
                    }
                    currentPreview?.recycle()
                    currentPreview = adjusted
                    findViewById<ImageView>(R.id.ivPage).setImageBitmap(adjusted)
                    updateFilterSelection()
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    /** Show or hide the Adjust panel, and tint its menu
     *  button teal while it is open. */
    private fun setAdjustPanelVisible(visible: Boolean) {
        findViewById<View>(R.id.adjustPanel).visibility =
            if (visible) View.VISIBLE else View.GONE
        val color = if (visible) getColor(R.color.hakunaa_teal)
            else getColor(android.R.color.white)
        findViewById<ImageView>(R.id.ivAdjustIcon).setColorFilter(color)
        findViewById<TextView>(R.id.tvAdjustLabel).setTextColor(color)
    }

    private fun toggleAdjustPanel() {
        val panel = findViewById<View>(R.id.adjustPanel)
        setAdjustPanelVisible(panel.visibility != View.VISIBLE)
    }

    /** Recompute the preview with the current filter +
     *  adjustments (called when a slider is released). */
    private fun refreshPreview() {
        applyFilterToPreview(selectedFilter)
    }

    private fun setupAdjustSliders() {
        val brightnessSeek = findViewById<SeekBar>(R.id.seekBrightness)
        val contrastSeek = findViewById<SeekBar>(R.id.seekContrast)
        val sharpnessSeek = findViewById<SeekBar>(R.id.seekSharpness)
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                // The value labels update live; the image updates
                // when the finger lifts (a full filter pass on
                // every tick would stutter).
                findViewById<TextView>(R.id.tvBrightnessValue).text =
                    "${brightnessSeek.progress - 100}"
                val contrastValue = 0.5f + contrastSeek.progress / 100f
                findViewById<TextView>(R.id.tvContrastValue).text =
                    String.format(Locale.US, "%.2f", contrastValue)
                findViewById<TextView>(R.id.tvSharpnessValue).text =
                    "${sharpnessSeek.progress}"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                brightness = (brightnessSeek.progress - 100).toFloat()
                contrast = 0.5f + contrastSeek.progress / 100f
                sharpness = sharpnessSeek.progress.toFloat()
                refreshPreview()
            }
        }
        brightnessSeek.setOnSeekBarChangeListener(listener)
        contrastSeek.setOnSeekBarChangeListener(listener)
        sharpnessSeek.setOnSeekBarChangeListener(listener)
    }

    private fun resetAdjustments() {
        brightness = 0f
        contrast = 1f
        sharpness = 40f
        findViewById<SeekBar>(R.id.seekBrightness).progress = 100
        findViewById<SeekBar>(R.id.seekContrast).progress = 50
        findViewById<SeekBar>(R.id.seekSharpness).progress = 40
        findViewById<TextView>(R.id.tvBrightnessValue).text = "0"
        findViewById<TextView>(R.id.tvContrastValue).text = "1.00"
        findViewById<TextView>(R.id.tvSharpnessValue).text = "40"
        refreshPreview()
    }

    /** The filter strip's thumbnails, from a 160px source
     *  made off the UI thread (v0.1.78 — scaling the
     *  full-res page on the UI thread stalled it). The
     *  source thumb is this call's own, so it is recycled
     *  when the strip is done with it. */
    private fun buildFilterThumbnails(thumb: Bitmap) {
        val filters = ImageEnhancer.Filter.values()
        val row = findViewById<LinearLayout>(R.id.filterRow)
        // The thumbnail items are built ONCE. On a page switch
        // the row already has its items — only the thumbnail
        // images change, in place, so the strip never moves or
        // flickers. (The old code removed every view and
        // rebuilt the row on every swipe, which made the whole
        // filter strip shake together with the page.)
        val firstBuild = row.childCount != filters.size
        if (firstBuild) row.removeAllViews()
        Thread {
            for ((index, filter) in filters.withIndex()) {
                try {
                    val filtered = ImageEnhancer.apply(thumb, filter)
                    runOnUiThread {
                        if (firstBuild) {
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
                        } else if (index < row.childCount) {
                            // Update the existing thumbnail in place.
                            row.getChildAt(index)
                                .findViewById<ImageView>(R.id.ivFilterThumb)
                                .setImageBitmap(filtered)
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            thumb.recycle()
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
        val path = imagePath
        // Rotate + downsample off the UI thread (v0.1.78):
        // both touch every pixel of the full-res page and
        // stall the screen when done on the UI thread. The
        // result is applied only if this is still the page
        // being shown (the user may have swiped meanwhile).
        Thread {
            try {
                val matrix = Matrix()
                matrix.postRotate(-90f)
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                val preview = downsample(rotated, 1200)
                runOnUiThread {
                    if (imagePath != path || bitmap !== bmp) {
                        rotated.recycle()
                        preview.recycle()
                        return@runOnUiThread
                    }
                    val oldW = bmp.width
                    val oldH = bmp.height
                    bitmap?.recycle()
                    previewBitmap?.recycle()
                    bitmap = rotated
                    previewBitmap = preview
                    findViewById<ImageView>(R.id.ivPage).setImageBitmap(previewBitmap)
                    // The drawing + signature rotate with the
                    // page (v0.1.82), so they stay where the
                    // user put them.
                    val drawView = findViewById<DrawView>(R.id.drawView)
                    drawView.rotateStrokesCCW(oldW, oldH)
                    drawView.setContentSize(rotated.width, rotated.height)
                    if (path != null) {
                        pageDrawings[path] = drawView.getStrokes().toMutableList()
                        pageSignatures[path]?.let { sig ->
                            sig.rectNorm = DrawView.rotateRectCCW(sig.rectNorm)
                        }
                        pageTexts[path]?.let { txt ->
                            txt.rectNorm = DrawView.rotateRectCCW(txt.rectNorm)
                        }
                    }
                    refreshOverlays()
                    applyFilterToPreview(selectedFilter)
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    // ---------- Draw + Sign (v0.1.82) ----------

    private fun dpToPx(dp: Float): Float =
        dp * resources.displayMetrics.density

    /** The page bitmap's displayed rect inside pageFrame
     *  (fitCenter): [left, top, width, height] in view pixels. */
    private fun displayedRect(): FloatArray {
        val frame = findViewById<View>(R.id.pageFrame)
        val bmp = bitmap
        if (bmp == null || frame.width <= 0 || frame.height <= 0) {
            return floatArrayOf(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
        }
        val scale = minOf(
            frame.width.toFloat() / bmp.width,
            frame.height.toFloat() / bmp.height
        )
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        return floatArrayOf((frame.width - dw) / 2f, (frame.height - dh) / 2f, dw, dh)
    }

    /** Load the current page's drawing + signature + text
     *  into the overlays (called after a page loads, a swipe,
     *  or a rotation). */
    private fun refreshOverlays() {
        val bmp = bitmap ?: return
        val path = imagePath
        val drawView = findViewById<DrawView>(R.id.drawView)
        drawView.setContentSize(bmp.width, bmp.height)
        drawView.setStrokes(if (path == null) emptyList() else pageDrawings[path] ?: emptyList())
        // The drawing stays on screen after Done — show the
        // view when this page has one (v0.1.83 fix).
        updateDrawViewVisibility()
        layoutSignatureOverlay()
        layoutTextOverlay()
    }

    /** Store the on-screen drawing into this page's slot, so
     *  a swipe or a save keeps it. The signature rect is
     *  already kept live while dragging. Also writes the
     *  page's overlays to the shared store (v0.1.84), so a
     *  later Enhance run finds them. */
    private fun storeCurrentOverlays() {
        val path = imagePath ?: return
        val drawView = findViewById<DrawView>(R.id.drawView)
        pageDrawings[path] = drawView.getStrokes().toMutableList()
        writeOverlaysToStore(path)
    }

    /** Enter / leave a Draw / Erase tool. While drawing or
     *  erasing, the DrawView sits over the page and takes the
     *  touches (so a stroke never becomes a page swipe), and
     *  the draw panel shows. When the tool is left, the
     *  drawing STAYS VISIBLE on the page (v0.1.83 fix — the
     *  DrawView used to hide on Done, so the drawing vanished
     *  from the screen and the user thought it was lost); it
     *  only stops taking touches, so swipes and zooms work
     *  again. */
    private fun setToolMode(mode: ToolMode) {
        // Tapping the active tool again leaves it.
        toolMode = if (toolMode == mode) ToolMode.NONE else mode
        val drawView = findViewById<DrawView>(R.id.drawView)
        val panel = findViewById<View>(R.id.drawPanel)
        val colorRow = findViewById<View>(R.id.colorRow)
        if (toolMode == ToolMode.DRAW || toolMode == ToolMode.ERASE) {
            // Close the Adjust panel first — the two panels
            // never show together.
            setAdjustPanelVisible(false)
            val bmp = bitmap
            if (bmp != null) drawView.setContentSize(bmp.width, bmp.height)
            drawView.drawEnabled = true
            drawView.eraseMode = (toolMode == ToolMode.ERASE)
            if (toolMode == ToolMode.DRAW) {
                drawView.drawColor = drawColors[drawColorIndex]
                drawView.strokeWidthPx = dpToPx(drawSizesDp[drawSizeIndex])
                colorRow.visibility = View.VISIBLE
            } else {
                // Eraser: a wide rub, no colour.
                drawView.eraseRadiusPx = dpToPx(20f)
                colorRow.visibility = View.GONE
            }
            drawView.visibility = View.VISIBLE
            panel.visibility = View.VISIBLE
            updateToolButtonTints()
        } else {
            storeCurrentOverlays()
            drawView.drawEnabled = false
            drawView.eraseMode = false
            // Keep the drawing on screen when this page has
            // one; hide the (empty) view only when there is
            // nothing to show.
            updateDrawViewVisibility()
            panel.visibility = View.GONE
            updateToolButtonTints()
        }
    }

    /** Show the DrawView when this page has a drawing (so it
     *  stays visible after Done), hide it when there is
     *  nothing to show. Never called while a tool is active
     *  — then the view is always up. */
    private fun updateDrawViewVisibility() {
        if (toolMode == ToolMode.DRAW || toolMode == ToolMode.ERASE) return
        val drawView = findViewById<DrawView>(R.id.drawView)
        drawView.drawEnabled = false
        drawView.visibility =
            if (drawView.hasStrokes()) View.VISIBLE else View.GONE
    }

    /** Tint the active tool's icon + label teal, the rest
     *  white. */
    private fun updateToolButtonTints() {
        val teal = getColor(R.color.hakunaa_teal)
        val white = getColor(android.R.color.white)
        val drawActive = toolMode == ToolMode.DRAW
        val eraseActive = toolMode == ToolMode.ERASE
        findViewById<ImageView>(R.id.ivDrawIcon)
            .setColorFilter(if (drawActive) teal else white)
        findViewById<TextView>(R.id.tvDrawLabel)
            .setTextColor(if (drawActive) teal else white)
        findViewById<ImageView>(R.id.ivEraseIcon)
            .setColorFilter(if (eraseActive) teal else white)
        findViewById<TextView>(R.id.tvEraseLabel)
            .setTextColor(if (eraseActive) teal else white)
    }

    /** Build the pen colour dots and wire the draw panel. */
    private fun setupDrawPanel() {
        val row = findViewById<LinearLayout>(R.id.colorRow)
        // Small dots (v0.1.85): 24dp + 4dp gap, so the whole
        // panel (dots + Size + Undo + Clear + Done) fits the
        // screen and Clear / Done never hide off the edge.
        val dotSize = dpToPx(24f).toInt()
        val dotMargin = dpToPx(4f).toInt()
        for ((index, color) in drawColors.withIndex()) {
            val dot = ImageView(this)
            val params = LinearLayout.LayoutParams(dotSize, dotSize)
            params.marginEnd = dotMargin
            dot.layoutParams = params
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            dot.background = drawable
            drawDotViews.add(dot)
            drawDotDrawables.add(drawable)
            dot.setOnClickListener { selectDrawColor(index) }
            row.addView(dot)
        }
        selectDrawColor(drawColorIndex)
        updateDrawSizeLabel()
        // The draw panel's Size button also sets the eraser
        // radius in Erase mode (the eraser uses the same
        // size steps: S / M / L).
        findViewById<TextView>(R.id.btnDrawSize).setOnClickListener {
            drawSizeIndex = (drawSizeIndex + 1) % drawSizesDp.size
            updateDrawSizeLabel()
            val dv = findViewById<DrawView>(R.id.drawView)
            dv.strokeWidthPx = dpToPx(drawSizesDp[drawSizeIndex])
            dv.eraseRadiusPx = dpToPx(drawSizesDp[drawSizeIndex] * 2f)
        }
        findViewById<TextView>(R.id.btnDrawUndo).setOnClickListener {
            findViewById<DrawView>(R.id.drawView).undo()
        }
        findViewById<TextView>(R.id.btnDrawClear).setOnClickListener {
            findViewById<DrawView>(R.id.drawView).clearAll()
        }
        findViewById<TextView>(R.id.btnDrawDone).setOnClickListener {
            // Leave whichever tool is active (Draw or Erase);
            // the drawing stays visible on the page.
            if (toolMode != ToolMode.NONE) setToolMode(toolMode)
        }
    }

    private fun selectDrawColor(index: Int) {
        drawColorIndex = index
        findViewById<DrawView>(R.id.drawView).drawColor = drawColors[index]
        for ((i, drawable) in drawDotDrawables.withIndex()) {
            if (i == index) drawable.setStroke(dpToPx(3f).toInt(), Color.WHITE)
            else drawable.setStroke(0, Color.TRANSPARENT)
            drawDotViews[i].background = drawable
        }
    }

    private fun updateDrawSizeLabel() {
        val name = listOf("S", "M", "L")[drawSizeIndex]
        findViewById<TextView>(R.id.btnDrawSize).text = "Size: $name"
    }

    /** Open the signature pad: draw a signature, then place
     *  it on the page. */
    private fun openSignaturePad() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_signature)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val pad = dialog.findViewById<DrawView>(R.id.signPad)
        // The pad's content is the pad itself. It must take
        // touches (v0.1.84 fix — the pad's DrawView has
        // drawEnabled=false by default, so in v0.1.83 the
        // signature could not be drawn at all: the pad opened
        // but no stroke ever appeared).
        pad.drawEnabled = true
        pad.post { pad.setContentSize(pad.width, pad.height) }
        pad.drawColor = Color.BLACK
        pad.strokeWidthPx = dpToPx(6f)
        dialog.findViewById<TextView>(R.id.btnSignPadClear).setOnClickListener {
            pad.clearAll()
        }
        dialog.findViewById<TextView>(R.id.btnSignPadCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<TextView>(R.id.btnSignPadUse).setOnClickListener {
            if (!pad.hasStrokes()) {
                Toast.makeText(this, "Draw your signature first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val sigBmp = pad.renderCroppedBitmap()
            dialog.dismiss()
            if (sigBmp == null) {
                Toast.makeText(this, "Couldn't read the signature", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            placeSignature(sigBmp)
        }
        dialog.show()
    }

    /** Put a signature bitmap on the current page: bottom
     *  right by default (35% of the page width), draggable
     *  after that. */
    private fun placeSignature(sigBmp: Bitmap) {
        val path = imagePath ?: return
        val bmp = bitmap ?: return
        // Drop any old signature bitmap for this page first.
        pageSignatures[path]?.bitmap?.recycle()
        val wFrac = 0.35f
        val hFrac = wFrac * sigBmp.height / sigBmp.width * bmp.width / bmp.height
        val top = (0.88f - hFrac).coerceAtLeast(0.05f)
        val rect = floatArrayOf(0.60f, top, 0.95f, 0.88f)
        pageSignatures[path] = PlacedOverlay(sigBmp, rect)
        layoutSignatureOverlay()
        findViewById<View>(R.id.signPanel).visibility = View.VISIBLE
        // Persist it (v0.1.84) — the next Enhance run loads
        // it back from the store.
        writeOverlaysToStore(path)
    }

    /** Position the signature overlay from its stored
     *  normalized rect (and show / hide it). */
    private fun layoutSignatureOverlay() {
        val path = imagePath
        val sig = if (path == null) null else pageSignatures[path]
        val overlay = findViewById<ImageView>(R.id.ivSignature)
        if (sig == null || bitmap == null) {
            overlay.visibility = View.GONE
            return
        }
        val r = displayedRect()
        val rect = sig.rectNorm
        val w = ((rect[2] - rect[0]) * r[2]).toInt().coerceAtLeast(1)
        val h = ((rect[3] - rect[1]) * r[3]).toInt().coerceAtLeast(1)
        val params = FrameLayout.LayoutParams(w, h)
        params.leftMargin = (r[0] + rect[0] * r[2]).toInt()
        params.topMargin = (r[1] + rect[1] * r[3]).toInt()
        overlay.layoutParams = params
        if (overlay.drawable == null || overlay.getTag(R.id.ivSignature) !== sig.bitmap) {
            overlay.setImageBitmap(sig.bitmap)
            overlay.setTag(R.id.ivSignature, sig.bitmap)
        }
        overlay.visibility = View.VISIBLE
    }

    /** Drag a placed overlay (signature or text) around the
     *  page. The stored normalized rect follows the finger,
     *  clamped inside the page. */
    private fun setupOverlayDrag(
        overlayViewId: Int,
        getPlacement: () -> PlacedOverlay?
    ) {
        val overlay = findViewById<ImageView>(overlayViewId)
        overlay.setOnTouchListener { _, event ->
            val placement = getPlacement() ?: return@setOnTouchListener false
            val frame = findViewById<View>(R.id.pageFrame)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    overlayDragOffsetX = event.x
                    overlayDragOffsetY = event.y
                    overlayDragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!overlayDragging) return@setOnTouchListener false
                    val r = displayedRect()
                    val rect = placement.rectNorm
                    val wFrac = rect[2] - rect[0]
                    val hFrac = rect[3] - rect[1]
                    // The finger's position in the frame, minus
                    // where it grabbed the overlay, is the
                    // overlay's new top-left. Convert it to a
                    // normalized top-left, clamped so the
                    // overlay stays on the page.
                    val loc = IntArray(2)
                    frame.getLocationOnScreen(loc)
                    val fx = event.rawX - loc[0] - overlayDragOffsetX
                    val fy = event.rawY - loc[1] - overlayDragOffsetY
                    rect[0] = ((fx - r[0]) / r[2]).coerceIn(0f, 1f - wFrac)
                    rect[1] = ((fy - r[1]) / r[3]).coerceIn(0f, 1f - hFrac)
                    rect[2] = rect[0] + wFrac
                    rect[3] = rect[1] + hFrac
                    val params = overlay.layoutParams as FrameLayout.LayoutParams
                    params.leftMargin = fx.toInt()
                    params.topMargin = fy.toInt()
                    overlay.layoutParams = params
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    overlayDragging = false
                    // The overlay moved — persist its new spot
                    // (v0.1.84) so a later Enhance run finds it
                    // where the user left it.
                    val p = imagePath
                    if (p != null) writeOverlaysToStore(p)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupSignatureDrag() {
        setupOverlayDrag(R.id.ivSignature) {
            val path = imagePath ?: return@setupOverlayDrag null
            pageSignatures[path]
        }
    }

    private fun setupTextDrag() {
        // Drag the whole text BOX to move the text (the box
        // is the touch target now, not the bare image).
        val box = findViewById<View>(R.id.textBoxContainer)
        box.setOnTouchListener { _, event ->
            val path = imagePath ?: return@setOnTouchListener false
            val txt = pageTexts[path] ?: return@setOnTouchListener false
            val frame = findViewById<View>(R.id.pageFrame)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    overlayDragOffsetX = event.x
                    overlayDragOffsetY = event.y
                    overlayDragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!overlayDragging) return@setOnTouchListener false
                    val r = displayedRect()
                    val rect = txt.rectNorm
                    val wFrac = rect[2] - rect[0]
                    val hFrac = rect[3] - rect[1]
                    val pad = dpToPx(6f)
                    // The box's top-left, minus where the
                    // finger grabbed it, is the box's new spot.
                    // The TEXT sits 6dp inside the box, so add
                    // the padding back to get the text's spot.
                    val loc = IntArray(2)
                    frame.getLocationOnScreen(loc)
                    val fx = event.rawX - loc[0] - overlayDragOffsetX
                    val fy = event.rawY - loc[1] - overlayDragOffsetY
                    rect[0] = ((fx + pad - r[0]) / r[2]).coerceIn(0f, 1f - wFrac)
                    rect[1] = ((fy + pad - r[1]) / r[3]).coerceIn(0f, 1f - hFrac)
                    rect[2] = rect[0] + wFrac
                    rect[3] = rect[1] + hFrac
                    layoutTextOverlay()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    overlayDragging = false
                    val p = imagePath
                    if (p != null) writeOverlaysToStore(p)
                    true
                }
                else -> false
            }
        }
    }

    /** Resize the text box by dragging its bottom-right
     *  handle (Word-style): the box grows / shrinks from its
     *  top-left, and the text scales with it. The width and
     *  height scale together (the text keeps its shape). */
    private fun setupTextResize() {
        val handle = findViewById<TextView>(R.id.btnTextResize)
        var startX = 0f
        var startY = 0f
        var startW = 0f
        var startH = 0f
        var resizing = false
        handle.setOnTouchListener { _, event ->
            val path = imagePath ?: return@setOnTouchListener false
            val txt = pageTexts[path] ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val r = displayedRect()
                    startX = event.rawX
                    startY = event.rawY
                    startW = (txt.rectNorm[2] - txt.rectNorm[0]) * r[2]
                    startH = (txt.rectNorm[3] - txt.rectNorm[1]) * r[3]
                    resizing = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!resizing) return@setOnTouchListener false
                    val r = displayedRect()
                    // Grow by the bigger of the two drags, so
                    // the text scales evenly (no squashing).
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val delta = maxOf(dx, dy)
                    val newW = (startW + delta).coerceIn(40f, r[2])
                    val newH = startH * (newW / startW)
                    val rect = txt.rectNorm
                    rect[2] = (rect[0] + newW / r[2]).coerceAtMost(1f)
                    rect[3] = (rect[1] + newH / r[3]).coerceAtMost(1f)
                    layoutTextOverlay()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    resizing = false
                    val p = imagePath
                    if (p != null) writeOverlaysToStore(p)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeSignature() {
        val path = imagePath ?: return
        pageSignatures[path]?.bitmap?.recycle()
        pageSignatures.remove(path)
        findViewById<ImageView>(R.id.ivSignature).visibility = View.GONE
        findViewById<View>(R.id.signPanel).visibility = View.GONE
        writeOverlaysToStore(path)
    }

    // ---------- Add Text ----------

    // Text sizes (sp) for the Add Text dialog: Small / Medium / Large.
    private val addTextSizesSp = listOf(18f, 28f, 40f)
    private var addTextSizeIndex = 1
    private val addTextColors = listOf(
        Color.BLACK, Color.parseColor("#D32F2F"), Color.parseColor("#1976D2"),
        Color.parseColor("#388E3C"), Color.parseColor("#00BFA5")
    )
    private var addTextColorIndex = 0

    /** Open the Add Text dialog: type the words, pick a size
     *  and a colour, then place the text on the page. */
    private fun openAddTextDialog() {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_add_text)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val etText = dialog.findViewById<EditText>(R.id.etAddText)
        val sizeViews = listOf(
            dialog.findViewById<TextView>(R.id.tvTextSizeS),
            dialog.findViewById<TextView>(R.id.tvTextSizeM),
            dialog.findViewById<TextView>(R.id.tvTextSizeL)
        )
        fun refreshSizeButtons() {
            for ((i, v) in sizeViews.withIndex()) {
                if (i == addTextSizeIndex) {
                    v.setBackgroundColor(getColor(R.color.hakunaa_teal))
                    v.setTextColor(Color.WHITE)
                } else {
                    v.setBackgroundColor(Color.parseColor("#E0E0E0"))
                    v.setTextColor(Color.BLACK)
                }
            }
        }
        for ((i, v) in sizeViews.withIndex()) {
            v.setOnClickListener { addTextSizeIndex = i; refreshSizeButtons() }
        }
        refreshSizeButtons()
        // Colour dots, built in code like the draw panel's.
        val colorRow = dialog.findViewById<LinearLayout>(R.id.textColorRow)
        val dotSize = dpToPx(28f).toInt()
        val dotMargin = dpToPx(6f).toInt()
        val dotViews = mutableListOf<ImageView>()
        val dotDrawables = mutableListOf<GradientDrawable>()
        for ((index, color) in addTextColors.withIndex()) {
            val dot = ImageView(this)
            val params = LinearLayout.LayoutParams(dotSize, dotSize)
            params.marginEnd = dotMargin
            dot.layoutParams = params
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            dot.background = drawable
            dotViews.add(dot)
            dotDrawables.add(drawable)
            dot.setOnClickListener {
                addTextColorIndex = index
                for ((i, d) in dotDrawables.withIndex()) {
                    if (i == index) d.setStroke(dpToPx(3f).toInt(), Color.WHITE)
                    else d.setStroke(0, Color.TRANSPARENT)
                    dotViews[i].background = d
                }
            }
            colorRow.addView(dot)
        }
        for ((i, d) in dotDrawables.withIndex()) {
            if (i == addTextColorIndex) d.setStroke(dpToPx(3f).toInt(), Color.WHITE)
            else d.setStroke(0, Color.TRANSPARENT)
            dotViews[i].background = d
        }
        dialog.findViewById<TextView>(R.id.btnAddTextCancel).setOnClickListener {
            dialog.dismiss()
        }
        dialog.findViewById<TextView>(R.id.btnAddTextAdd).setOnClickListener {
            val text = etText.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Type the text first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val textBmp = renderTextBitmap(
                text, addTextSizesSp[addTextSizeIndex], addTextColors[addTextColorIndex]
            )
            dialog.dismiss()
            if (textBmp == null) {
                Toast.makeText(this, "Couldn't make the text", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            placeText(textBmp)
        }
        dialog.show()
    }

    /** Render typed text onto a transparent bitmap, at a
     *  size that reads well on the page. */
    private fun renderTextBitmap(text: String, sizeSp: Float, color: Int): Bitmap? {
        val density = resources.displayMetrics.density
        val sizePx = sizeSp * density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx
            this.color = color
            typeface = Typeface.DEFAULT_BOLD
        }
        val w = paint.measureText(text).toInt().coerceAtLeast(1)
        val fm = paint.fontMetrics
        val h = (fm.bottom - fm.top).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w + 8, h + 8, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawText(text, 4f, 4f - fm.top, paint)
        return bmp
    }

    /** Put a text bitmap on the current page: centred near
     *  the top by default (30% of the page width), draggable
     *  after that. */
    private fun placeText(textBmp: Bitmap) {
        val path = imagePath ?: return
        val bmp = bitmap ?: return
        // Drop any old text bitmap for this page first.
        pageTexts[path]?.bitmap?.recycle()
        val wFrac = 0.30f
        val hFrac = wFrac * textBmp.height / textBmp.width * bmp.width / bmp.height
        val top = (0.10f).coerceAtLeast(0.02f)
        val rect = floatArrayOf(0.35f, top, 0.35f + wFrac, top + hFrac)
        pageTexts[path] = PlacedOverlay(textBmp, rect)
        layoutTextOverlay()
        findViewById<View>(R.id.textPanel).visibility = View.VISIBLE
        // Persist it (v0.1.84) — the next Enhance run loads
        // it back from the store.
        writeOverlaysToStore(path)
    }

    /** Position the text box (Word-style, v0.1.85) from its
     *  stored normalized rect (and show / hide it). The box
     *  wraps the text with a teal border + 6dp padding, so
     *  the box is a little bigger than the text itself — the
     *  box's top-left sits 6dp up-left of the text's. */
    private fun layoutTextOverlay() {
        val path = imagePath
        val txt = if (path == null) null else pageTexts[path]
        val box = findViewById<View>(R.id.textBoxContainer)
        val overlay = findViewById<ImageView>(R.id.ivTextOverlay)
        if (txt == null || bitmap == null) {
            box.visibility = View.GONE
            return
        }
        val pad = dpToPx(6f).toInt()
        val r = displayedRect()
        val rect = txt.rectNorm
        val tw = ((rect[2] - rect[0]) * r[2]).toInt().coerceAtLeast(1)
        val th = ((rect[3] - rect[1]) * r[3]).toInt().coerceAtLeast(1)
        // The text bitmap fills the box's inside (the box's
        // padding is the 6dp border gap).
        overlay.layoutParams = FrameLayout.LayoutParams(tw, th)
        if (overlay.drawable == null || overlay.getTag(R.id.ivTextOverlay) !== txt.bitmap) {
            overlay.setImageBitmap(txt.bitmap)
            overlay.setTag(R.id.ivTextOverlay, txt.bitmap)
        }
        val params = FrameLayout.LayoutParams(tw + pad * 2, th + pad * 2)
        params.leftMargin = (r[0] + rect[0] * r[2]).toInt() - pad
        params.topMargin = (r[1] + rect[1] * r[3]).toInt() - pad
        box.layoutParams = params
        box.visibility = View.VISIBLE
    }

    private fun removeText() {
        val path = imagePath ?: return
        pageTexts[path]?.bitmap?.recycle()
        pageTexts.remove(path)
        findViewById<View>(R.id.textBoxContainer).visibility = View.GONE
        findViewById<View>(R.id.textPanel).visibility = View.GONE
        writeOverlaysToStore(path)
    }

    // ---------- Overlay persistence (v0.1.84) ----------

    /** Save an overlay bitmap (signature / text) as a PNG in
     *  the cache dir, so it survives across Enhance sessions.
     *  Returns the file path, or null on failure. */
    private fun saveOverlayBitmap(bitmap: Bitmap, prefix: String): String? {
        return try {
            val file = File(
                cacheDir, "overlay_${prefix}_${System.currentTimeMillis()}.png"
            )
            file.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            file.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    /** Load every page's overlays from the shared store
     *  (v0.1.84): the drawing, signature and text made in an
     *  earlier Enhance run (a thumbnail review) come back, so
     *  they are still on the page when this run opens. */
    private fun loadOverlaysFromStore() {
        for (rawPath in allPages) {
            val data = OverlayStore.get(rawPath) ?: continue
            if (data.strokes.isNotEmpty()) {
                pageDrawings[rawPath] = data.strokes.toMutableList()
            }
            if (data.signatureFile != null && data.signatureRect != null) {
                val sigBmp = BitmapFactory.decodeFile(data.signatureFile)
                if (sigBmp != null) {
                    pageSignatures[rawPath] =
                        PlacedOverlay(sigBmp, data.signatureRect.copyOf())
                }
            }
            if (data.textFile != null && data.textRect != null) {
                val txtBmp = BitmapFactory.decodeFile(data.textFile)
                if (txtBmp != null) {
                    pageTexts[rawPath] =
                        PlacedOverlay(txtBmp, data.textRect.copyOf())
                }
            }
        }
    }

    /** Write one page's overlays to the shared store
     *  (v0.1.84), so a later Enhance run finds them. The
     *  signature / text bitmaps are saved as PNG files first
     *  (the file is the source of truth, so the in-memory
     *  bitmap can be recycled freely). */
    private fun writeOverlaysToStore(rawPath: String) {
        val strokes = pageDrawings[rawPath]?.toList() ?: emptyList()
        val sig = pageSignatures[rawPath]
        val txt = pageTexts[rawPath]
        var sigFile: String? = null
        var sigRect: FloatArray? = null
        if (sig != null) {
            sigFile = saveOverlayBitmap(sig.bitmap, "sig")
            sigRect = sig.rectNorm.copyOf()
        }
        var txtFile: String? = null
        var txtRect: FloatArray? = null
        if (txt != null) {
            txtFile = saveOverlayBitmap(txt.bitmap, "text")
            txtRect = txt.rectNorm.copyOf()
        }
        // Keep the store entry only when there is something.
        if (strokes.isEmpty() && sigFile == null && txtFile == null) {
            OverlayStore.remove(rawPath)
        } else {
            OverlayStore.put(
                rawPath,
                OverlayStore.OverlayData(strokes, sigFile, sigRect, txtFile, txtRect)
            )
        }
    }

    /** Write every page's overlays to the shared store. */
    private fun writeAllOverlaysToStore() {
        for (rawPath in allPages) writeOverlaysToStore(rawPath)
    }

    // ---------- end overlay persistence ----------

    // ---------- end Draw + Sign ----------

    private fun save() {
        val bmp = bitmap ?: return
        val currentPath = imagePath
        // Keep the on-screen drawing in this page's slot
        // before the background thread reads it.
        storeCurrentOverlays()
        // Persist every page's overlays (v0.1.84) — the next
        // Enhance run (Done after a review) loads them back,
        // so nothing made here is lost.
        writeAllOverlaysToStore()
        // "Apply to all pages" ticked: the current page's
        // filter goes on every page. Unticked: each page keeps
        // the filter picked for it while swiping (default
        // NO_SHADOW for a page never touched).
        val applyAll = findViewById<CheckBox>(R.id.cbApplyAll).isChecked && allPages.size > 1
        val currentFilter = selectedFilter
        // Snapshot the drawings + signatures + texts now (on
        // the UI thread) — the background thread must not read
        // the live maps while the user could still be drawing.
        val drawingsSnapshot = pageDrawings.mapValues { it.value.toList() }
        val signaturesSnapshot = pageSignatures.toMap()
        val textsSnapshot = pageTexts.toMap()
        Thread {
            try {
                val docName = findViewById<EditText>(R.id.etDocName).text.toString()
                    .trim().ifEmpty { "Hakunaa Scan" }
                val safeName = docName.replace(Regex("[^A-Za-z0-9 _.-]"), "_")
                // Every page is enhanced from its SOURCE file
                // into a NEW file (v0.1.77) — never re-encoded
                // in place. The old code overwrote the other
                // pages' files, so opening Enhance again on an
                // already-filtered page applied the filter a
                // second time and the image came out
                // overexposed; and the review launcher put the
                // enhanced file in the wrong slot. Now each page
                // is filtered exactly once, from its raw file,
                // and the caller matches each new file to its
                // source path. A page that fails is left out —
                // it must never block the save.
                val enhancedPaths = ArrayList<String>()
                val sourcePaths = ArrayList<String>()
                var currentEnhanced: String? = null
                for ((index, path) in allPages.withIndex()) {
                    val pageFilter = if (applyAll) {
                        currentFilter
                    } else {
                        pageFilters[path] ?: ImageEnhancer.Filter.NO_SHADOW
                    }
                    // The current page uses the in-memory
                    // bitmap (it carries any rotation); the
                    // other pages are decoded from their files.
                    val pageBmp = if (path == currentPath) bmp
                    else BitmapFactory.decodeFile(path)
                    if (pageBmp == null) continue
                    try {
                        val filtered = ImageEnhancer.apply(pageBmp, pageFilter)
                        var final = ImageEnhancer.applyAdjustments(
                            filtered, brightness, contrast, sharpness
                        )
                        if (final != filtered) filtered.recycle()
                        // Draw + Sign + Text (v0.1.82/83): the
                        // drawing, the placed signature and
                        // the placed text go ON TOP of the
                        // filtered page, so they stay crisp.
                        // `final` is always a fresh bitmap (never
                        // the input), so copying + recycling it
                        // is safe.
                        val strokes = drawingsSnapshot[path]
                        val sig = signaturesSnapshot[path]
                        val txt = textsSnapshot[path]
                        if (!strokes.isNullOrEmpty() || sig != null || txt != null) {
                            val composited =
                                final.copy(Bitmap.Config.ARGB_8888, true)
                            final.recycle()
                            val canvas = Canvas(composited)
                            if (!strokes.isNullOrEmpty()) {
                                DrawView.renderStrokesToBitmap(
                                    strokes, composited.width, composited.height
                                )?.let { overlay ->
                                    canvas.drawBitmap(overlay, 0f, 0f, null)
                                    overlay.recycle()
                                }
                            }
                            if (sig != null) {
                                val r = sig.rectNorm
                                val dst = RectF(
                                    r[0] * composited.width,
                                    r[1] * composited.height,
                                    r[2] * composited.width,
                                    r[3] * composited.height
                                )
                                canvas.drawBitmap(sig.bitmap, null, dst, null)
                            }
                            if (txt != null) {
                                val r = txt.rectNorm
                                val dst = RectF(
                                    r[0] * composited.width,
                                    r[1] * composited.height,
                                    r[2] * composited.width,
                                    r[3] * composited.height
                                )
                                canvas.drawBitmap(txt.bitmap, null, dst, null)
                            }
                            final = composited
                        }
                        val out = File(
                            cacheDir,
                            "scan_enhanced_${System.currentTimeMillis()}_$index.jpg"
                        )
                        out.outputStream().use { stream ->
                            // Near-lossless (95) — the PDF tiers
                            // re-encode from this file, so it
                            // must keep full quality.
                            final.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                        }
                        final.recycle()
                        if (pageBmp != bmp) pageBmp.recycle()
                        enhancedPaths.add(out.absolutePath)
                        sourcePaths.add(path)
                        if (path == currentPath) currentEnhanced = out.absolutePath
                    } catch (_: Throwable) {
                        if (pageBmp != bmp) pageBmp.recycle()
                    }
                }
                runOnUiThread {
                    val data = Intent()
                    data.putExtra(EXTRA_ENHANCED_PATH, currentEnhanced)
                    data.putStringArrayListExtra(EXTRA_ENHANCED_PATHS, enhancedPaths)
                    data.putStringArrayListExtra(EXTRA_ENHANCED_SOURCE_PATHS, sourcePaths)
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
        previewBitmap?.recycle()
        currentPreview?.recycle()
        for (sig in pageSignatures.values) sig.bitmap.recycle()
        for (txt in pageTexts.values) txt.bitmap.recycle()
    }
}
