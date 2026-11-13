package com.hakunaa.app.scanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.view.GestureDetector
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
    }

    private var bitmap: Bitmap? = null
    private var previewBitmap: Bitmap? = null
    private var imagePath: String? = null
    private var selectedFilter = ImageEnhancer.Filter.ENHANCE
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
    // Each page keeps its own filter (default ENHANCE), so a
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
    // Sharpness starts at 20, not 0 (v0.1.58): the user asked
    // for a little more sharpness by default, everywhere, so
    // text is always clearly visible — every filter gets this
    // small sharpen boost unless the user moves the slider.
    private var brightness = 0f
    private var contrast = 1f
    private var sharpness = 20f

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

        val path = imagePath
        if (path == null) {
            Toast.makeText(this, "Image not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        loadPageBitmap(path)
        setupSwipe()

        findViewById<LinearLayout>(R.id.btnRotateLeft).setOnClickListener { rotateLeft() }
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { save() }
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
    private fun loadPageBitmap(path: String) {
        Thread {
            try {
                val bmp = BitmapFactory.decodeFile(path)
                runOnUiThread {
                    if (bmp == null) {
                        Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                        finish()
                        return@runOnUiThread
                    }
                    bitmap?.recycle()
                    previewBitmap?.recycle()
                    currentPreview?.recycle()
                    currentPreview = null
                    bitmap = bmp
                    previewBitmap = downsample(bmp, 1200)
                    // This page's own filter (default ENHANCE).
                    selectedFilter = pageFilters[path] ?: ImageEnhancer.Filter.ENHANCE
                    // Show the page at once, then swap in the
                    // filtered version when it is ready.
                    findViewById<ImageView>(R.id.ivPage).setImageBitmap(bmp)
                    applyFilterToPreview(selectedFilter)
                    buildFilterThumbnails()
                    updatePageIndicator()
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
     *  picked for it. */
    private fun setupSwipe() {
        val pageView = findViewById<ImageView>(R.id.ivPage)
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (allPages.size <= 1 || e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                // A mostly-horizontal fling, fast enough to be
                // a swipe and not a stray drag.
                if (abs(dx) < 120f || abs(dx) < abs(dy) * 1.5f) return false
                if (dx < 0) showPage(currentPageIndex + 1) else showPage(currentPageIndex - 1)
                return true
            }
        })
        pageView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
        }
    }

    /** Show page [index] (clamped, wraps at the ends). */
    private fun showPage(index: Int) {
        if (allPages.isEmpty()) return
        val next = index.coerceIn(0, allPages.size - 1)
        if (next == currentPageIndex) return
        // Keep the filter picked for the page being left.
        val current = imagePath
        if (current != null) pageFilters[current] = selectedFilter
        currentPageIndex = next
        imagePath = allPages[next]
        imagePath?.let { loadPageBitmap(it) }
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

    /** Filter + adjustments, on the preview bitmap. */
    private fun applyFilterToPreview(filter: ImageEnhancer.Filter) {
        val bmp = previewBitmap ?: return
        selectedFilter = filter
        // Remember this filter for the page being shown, so a
        // swipe to another page and back keeps it.
        imagePath?.let { pageFilters[it] = filter }
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
        sharpness = 20f
        findViewById<SeekBar>(R.id.seekBrightness).progress = 100
        findViewById<SeekBar>(R.id.seekContrast).progress = 50
        findViewById<SeekBar>(R.id.seekSharpness).progress = 20
        findViewById<TextView>(R.id.tvBrightnessValue).text = "0"
        findViewById<TextView>(R.id.tvContrastValue).text = "1.00"
        findViewById<TextView>(R.id.tvSharpnessValue).text = "20"
        refreshPreview()
    }

    private fun buildFilterThumbnails() {
        val bmp = bitmap ?: return
        val thumb = Bitmap.createScaledBitmap(bmp, 160, (160f * bmp.height / bmp.width).toInt(), true)
        val filters = ImageEnhancer.Filter.values()
        val row = findViewById<LinearLayout>(R.id.filterRow)
        // Rebuilt on every page switch, so the thumbnails show
        // the page being viewed — clear the old ones first.
        row.removeAllViews()
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
        previewBitmap = downsample(rotated, 1200)
        applyFilterToPreview(selectedFilter)
    }

    private fun save() {
        val bmp = bitmap ?: return
        val currentPath = imagePath
        // "Apply to all pages" ticked: the current page's
        // filter goes on every page. Unticked: each page keeps
        // the filter picked for it while swiping (default
        // ENHANCE for a page never touched).
        val applyAll = findViewById<CheckBox>(R.id.cbApplyAll).isChecked && allPages.size > 1
        val currentFilter = selectedFilter
        Thread {
            try {
                val filtered = ImageEnhancer.apply(bmp, currentFilter)
                val final = ImageEnhancer.applyAdjustments(
                    filtered, brightness, contrast, sharpness
                )
                if (final != filtered) filtered.recycle()
                val docName = findViewById<EditText>(R.id.etDocName).text.toString()
                    .trim().ifEmpty { "Hakunaa Scan" }
                val safeName = docName.replace(Regex("[^A-Za-z0-9 _.-]"), "_")
                val out = File(cacheDir, "scan_enhanced_${System.currentTimeMillis()}.jpg")
                out.outputStream().use { stream ->
                    // Near-lossless (95) — the PDF tiers re-encode
                    // from this file, so it must keep full quality.
                    final.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
                final.recycle()
                // Every other page, re-encoded in place with its
                // own filter (or the current page's filter when
                // "Apply to all pages" is ticked), so the whole
                // document looks the way the user set it.
                // A page that fails is left as it was — it must
                // never block the save.
                for (path in allPages) {
                    // The current page is saved separately
                    // above — encoding it here too would
                    // filter it twice.
                    if (path == currentPath) continue
                    val pageFilter = if (applyAll) {
                        currentFilter
                    } else {
                        pageFilters[path] ?: ImageEnhancer.Filter.ENHANCE
                    }
                    try {
                        val page = BitmapFactory.decodeFile(path) ?: continue
                        val pageFiltered = ImageEnhancer.apply(page, pageFilter)
                        val pageFinal = ImageEnhancer.applyAdjustments(
                            pageFiltered, brightness, contrast, sharpness
                        )
                        if (pageFinal != pageFiltered) pageFiltered.recycle()
                        File(path).outputStream().use { stream ->
                            pageFinal.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                        }
                        page.recycle()
                        pageFinal.recycle()
                    } catch (_: Throwable) {
                    }
                }
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
        previewBitmap?.recycle()
        currentPreview?.recycle()
    }
}
