package com.hakunaa.app.scanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
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
 * - Rotate Left.
 * - The green checkmark saves the page with the chosen filter.
 *
 * The preview is a downsampled copy (≤1200 px), so picking a
 * filter or moving a slider updates at once; the save always
 * uses the full-resolution page. Not here (and not possible
 * offline): Extract Text (OCR) and To Word — those are cloud
 * features in CamScanner. Markup and Sign are later steps.
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
        loadPageBitmap(path)
        setupSwipe()

        findViewById<LinearLayout>(R.id.btnRotateLeft).setOnClickListener { rotateLeft() }
        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { save() }
        // The confirm label says what happens next (v0.1.76):
        // "Continue" from a thumbnail review (back to the
        // camera), "Finish" from Done / Single / Book (on to
        // Preview / Export). It sits INSIDE the boxy button
        // (v0.1.78), not as a label below it.
        findViewById<MaterialButton>(R.id.btnSave).text =
            intent.getStringExtra(EXTRA_CONFIRM_LABEL) ?: "Finish"
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
     *  drag past 100px moves a page. */
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
        // Keep the filter picked for the page being left.
        val current = imagePath
        if (current != null) pageFilters[current] = selectedFilter
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
                    bitmap?.recycle()
                    previewBitmap?.recycle()
                    bitmap = rotated
                    previewBitmap = preview
                    findViewById<ImageView>(R.id.ivPage).setImageBitmap(previewBitmap)
                    applyFilterToPreview(selectedFilter)
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun save() {
        val bmp = bitmap ?: return
        val currentPath = imagePath
        // "Apply to all pages" ticked: the current page's
        // filter goes on every page. Unticked: each page keeps
        // the filter picked for it while swiping (default
        // NO_SHADOW for a page never touched).
        val applyAll = findViewById<CheckBox>(R.id.cbApplyAll).isChecked && allPages.size > 1
        val currentFilter = selectedFilter
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
                        val final = ImageEnhancer.applyAdjustments(
                            filtered, brightness, contrast, sharpness
                        )
                        if (final != filtered) filtered.recycle()
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
    }
}
