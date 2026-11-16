package com.hakunaa.app.image

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import java.io.File
import com.hakunaa.app.core.StatusBar

/**
 * Image tool flow — Puma-style Compress / Resize / Fit screens.
 *
 * Shows the image (name, size, dimensions) at the top, then a
 * radio list of options, then Back / Next at the bottom.
 *
 * Modes:
 * - COMPRESS: quality 95/90/80/70/60/50/Custom 85, then a
 *   resolution step (keep original or pick a size).
 * - RESIZE: resolution presets (Keep, 240x534 … 3072x6828, Custom).
 * - TARGET_SIZE: target file size in KB or MB.
 * - FIT: aspect ratios (Square 1:1, 4:5, 9:16 Story, 16:9, 3:2).
 * - FIT_SOCIAL: Instagram / FB / TikTok presets.
 * - CONVERT: format PNG / JPG / WebP.
 *
 * Next applies the chosen setting and saves to Pictures/Hakunaa.
 */
class ImageToolActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_IMAGE_URI = "image_uri"
        const val MODE_COMPRESS = "compress"
        const val MODE_RESIZE = "resize"
        const val MODE_TARGET_SIZE = "target_size"
        const val MODE_FIT = "fit"
        const val MODE_FIT_SOCIAL = "fit_social"
        const val MODE_CONVERT = "convert"
        const val MODE_SMART = "smart"
        const val MODE_DP = "dp"
        const val MODE_METADATA = "metadata"
    }

    private var mode = MODE_COMPRESS
    private var inputFile: File? = null
    private var inputSize: Long = 0
    private var inputW = 0
    private var inputH = 0
    private var inputUriString: String? = null
    private var inputName: String = ""
    private var step = 0 // Compress has 2 steps: quality, then resolution.
    /** Card-based option selection (Resize / Fit / Convert / DP). */
    private var selectedCardTag: String? = null
    /** WebP rows in Smart results are hidden until "More Compress". */
    private var webpShown = false
    /** Smart results currently shown (JPG rows, plus WebP after More Compress). */
    private var smartDisplayed: MutableList<ImageCompressor.LevelResult> = mutableListOf()
    /** The Smart result currently selected for preview / save. */
    private var selectedSmartResult: ImageCompressor.LevelResult? = null
    /** Lazily computed WebP results for the Quality flow (More Compress). */
    private var webpLazyResults: List<ImageCompressor.LevelResult>? = null
    /** Before/After compare view in the Smart results. */
    private var compareView: CompareView? = null
    /** Preview wrapper + its toggle button in the Smart results.
     *  The CompareView is hidden until the user taps Preview
     *  (or taps a result row) — never shown automatically. */
    private var previewWrap: LinearLayout? = null
    private var previewToggleBtn: com.google.android.material.button.MaterialButton? = null
    /** True once the CompareView has been populated (first Preview tap). */
    private var previewInitialised = false
    private var previewVisible = false
    /** Metadata before removal (for the Before/After view). */
    private var metadataBefore: List<Pair<String, String>> = emptyList()
    private var metadataRemovedList: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_tool)

        // Status bar (v0.1.97): keep it visible, no overlap.
        StatusBar.setup(this, true)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_COMPRESS
        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriString == null) {
            Toast.makeText(this, "No image", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val uri = Uri.parse(uriString)
        inputUriString = uriString
        inputName = FileUtils.fileName(contentResolver, uri)
        Thread {
            try {
                val file = FileUtils.copyUriToCache(this, uri, "input_image")
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, options)
                runOnUiThread {
                    inputFile = file
                    inputSize = file.length()
                    inputW = options.outWidth
                    inputH = options.outHeight
                    findViewById<TextView>(R.id.tvImageName).text = FileUtils.fileName(contentResolver, uri)
                    findViewById<TextView>(R.id.tvImageInfo).text =
                        "${FileUtils.formatSize(inputSize)}\n1x  ${inputW}x${inputH} px"
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    findViewById<ImageView>(R.id.ivThumb).setImageBitmap(bitmap)
                    renderStep()
                    if (mode == MODE_SMART) runSmartCompress()
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()

        findViewById<TextView>(R.id.btnBack).setOnClickListener { backStep() }

        // System Back must step back within the tool (not exit it outright).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { backStep() }
        })

        findViewById<android.widget.EditText>(R.id.etTargetSize).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                findViewById<android.widget.EditText>(R.id.etTargetSize)
                    .setBackgroundResource(R.drawable.bg_search)
            }
        })

        findViewById<TextView>(R.id.tvUnitKb).setOnClickListener {
            targetUnitIsMb = false
            updateUnitToggle()
        }
        findViewById<TextView>(R.id.tvUnitMb).setOnClickListener {
            targetUnitIsMb = true
            updateUnitToggle()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNext)
            .setOnClickListener { next() }
        // Top X: same as Back — step back inside the tool, or ask first.
        findViewById<ImageView>(R.id.btnClose).setOnClickListener { backStep() }
    }

    /** One Back press: step back inside the tool, or ask before leaving.
     *  Quality mode never lands on the resolution step (step 2) — going
     *  back from the results (step 3) returns to the Quality cards (1).
     *  HD Compress never lands on the (removed) size step either —
     *  back from the HD result (step 2) returns to the mode cards (0). */
    private fun backStep() {
        if (mode == MODE_COMPRESS && step > 0) {
            step = if (compressMode == 1 && step == 3) 1
                else if (compressMode == 3) 0
                else step - 1
            renderStep()
        } else {
            attemptLeave()
        }
    }

    private fun titleForMode(): String = when (mode) {
        MODE_RESIZE -> "Resize"
        MODE_TARGET_SIZE -> "Resize to KB, MB"
        MODE_FIT -> "Fit"
        MODE_FIT_SOCIAL -> "Fit to social media"
        MODE_CONVERT -> "Convert"
        MODE_SMART -> "Smart Compress"
        MODE_DP -> "DP Edit"
        MODE_METADATA -> "Remove Metadata"
        else -> "Compress"
    }

    private fun renderStep() {
        findViewById<TextView>(R.id.tvTitle).text = titleForMode()
        val group = findViewById<RadioGroup>(R.id.rgOptions)
        group.removeAllViews()
        group.visibility = View.VISIBLE
        findViewById<LinearLayout>(R.id.resultsContainer).visibility = View.GONE
        findViewById<LinearLayout>(R.id.targetSizeContainer).visibility = View.GONE
        findViewById<LinearLayout>(R.id.modeContainer).visibility = View.GONE
        findViewById<LinearLayout>(R.id.qualityContainer).visibility = View.GONE
        findViewById<LinearLayout>(R.id.cardsContainer).visibility = View.GONE
        findViewById<LinearLayout>(R.id.metadataContainer).visibility = View.GONE
        val nextBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNext)
        // Tapping IS the action (no Next) for: Compress step 0, Quality
        // cards, Fit to social media, DP shapes, Metadata step 0.
        // Smart Compress: step 0 is working (no Next), step 1 shows results (Done).
        nextBtn.visibility = if ((mode == MODE_COMPRESS && (step == 0 || (step == 1 && compressMode == 1))) ||
            (mode == MODE_SMART && step == 0) ||
            mode == MODE_FIT_SOCIAL || mode == MODE_DP ||
            (mode == MODE_METADATA && step == 0)) View.GONE else View.VISIBLE
        nextBtn.text = if ((mode == MODE_COMPRESS && isCompressDoneStep()) ||
            (mode == MODE_SMART && step == 1) ||
            (mode == MODE_METADATA && step == 1)) "Done" else "Next"
        // HD Compress NEVER has a size step: one tap, no options. If
        // anything lands here with compressMode 3 (e.g. a stale step),
        // go back to the mode cards instead of showing size options.
        if (mode == MODE_COMPRESS && compressMode == 3 && step == 1) step = 0
        val subtitle = findViewById<TextView>(R.id.tvSubtitle)
        when (mode) {
            MODE_COMPRESS -> {
                when (step) {
                    0 -> {
                        subtitle.text = "Choose how to compress"
                        group.visibility = View.GONE
                        findViewById<LinearLayout>(R.id.modeContainer).visibility = View.VISIBLE
                        buildCompressModeCards()
                    }
                    1 -> when (compressMode) {
                        1 -> {
                            subtitle.text = "Reduce file size, choose quality"
                            group.visibility = View.GONE
                            findViewById<LinearLayout>(R.id.qualityContainer).visibility = View.VISIBLE
                            buildQualityCards()
                        }
                        2 -> {
                            subtitle.text = "Enter the target size"
                            group.visibility = View.GONE
                            findViewById<LinearLayout>(R.id.targetSizeContainer).visibility = View.VISIBLE
                        }
                        // No branch 3: HD Compress has NO size step
                        // (removed — HD is one tap, JPEG 95, full
                        // resolution). The guard above also forces
                        // step 1 + compressMode 3 back to step 0.
                    }
                    2 -> when (compressMode) {
                        1 -> {
                            // Quality NEVER shows the resolution step (fix 3):
                            // jump straight to the results instead.
                            if (smartResults.isNotEmpty()) {
                                step = 3
                                renderStep()
                                showSmartResults(smartResults)
                            } else {
                                runQualitySmartResults()
                            }
                        }
                        else -> {
                            subtitle.text = "Compressed — see the result below"
                            group.visibility = View.GONE
                        }
                    }
                    else -> {
                        subtitle.text = "Tap a result to preview it, then Save"
                        group.visibility = View.GONE
                        findViewById<LinearLayout>(R.id.resultsContainer).visibility = View.VISIBLE
                    }
                }
            }
            MODE_SMART -> {
                if (step == 0) {
                    subtitle.text = "Trying all levels…"
                    group.visibility = View.GONE
                } else {
                    subtitle.text = "Tap a result to preview it, then Save"
                    group.visibility = View.GONE
                    findViewById<LinearLayout>(R.id.resultsContainer).visibility = View.VISIBLE
                }
            }
            MODE_RESIZE -> {
                subtitle.text = "Change photo resolution"
                group.visibility = View.GONE
                buildResolutionCards()
            }
            MODE_TARGET_SIZE -> {
                subtitle.text = "Resize to target file size"
                addOptions(group, listOf(
                    "50" to "50 KB",
                    "100" to "100 KB",
                    "200" to "200 KB",
                    "500" to "500 KB",
                    "1024" to "1 MB",
                    "2048" to "2 MB",
                    "5120" to "5 MB"
                ), 2)
            }
            MODE_FIT, MODE_FIT_SOCIAL -> {
                subtitle.text = if (mode == MODE_FIT_SOCIAL)
                    "Fit to Instagram, FB, TikTok and more"
                else "Adjust to social media, square, any size"
                group.visibility = View.GONE
                buildAspectCards()
            }
            MODE_CONVERT -> {
                subtitle.text = "Change format to JPG, PNG, WebP, TIFF, BMP, AVIF"
                group.visibility = View.GONE
                buildFormatCards()
            }
            MODE_DP -> {
                subtitle.text = "Choose a DP shape"
                group.visibility = View.GONE
                buildDpCards()
            }
            MODE_METADATA -> {
                if (step == 0) {
                    subtitle.text = "This photo's metadata"
                    group.visibility = View.GONE
                    buildMetadataRows()
                } else {
                    subtitle.text = "Metadata removed"
                    group.visibility = View.GONE
                    buildMetadataAfterRows()
                }
            }
        }
    }

    /** Clean selectable card rows (title + subtitle), selected one
     *  highlighted teal. Used by Resize / Fit / Convert / DP.
     *  When [ratioIndicator] is given (aspect cards), a small
     *  bordered rectangle drawn in the card's proportions sits at
     *  the left of the title. When [badgeProvider] is given (Fit to
     *  social media), a small coloured platform badge (initial or
     *  simple shape, drawn in code) sits next to the ratio
     *  indicator. When [centerText] is true the title/subtitle are
     *  and the title is larger + bold. */
    private fun buildOptionCards(
        options: List<Triple<String, String, String>>,
        selected: String?,
        onSelect: (String) -> Unit,
        ratioIndicator: ((String) -> Pair<Float, Float>?)? = null,
        centerText: Boolean = false,
        badgeProvider: ((String) -> Pair<String, Int>?)? = null
    ) {
        val container = findViewById<LinearLayout>(R.id.cardsContainer)
        container.removeAllViews()
        container.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        val gapPx = (8 * density).toInt()
        for ((index, opt) in options.withIndex()) {
            val (tag, title, subtitle) = opt
            val isSelected = tag == selected
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 12f * density
                cardElevation = 2f * density
                setCardBackgroundColor(
                    if (isSelected) 0xFFE0F2F1.toInt() else getColor(R.color.hakunaa_surface)
                )
                strokeWidth = if (isSelected) (2 * density).toInt() else 0
                strokeColor = getColor(R.color.hakunaa_teal)
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * density).toInt(), (12 * density).toInt(),
                    (16 * density).toInt(), (12 * density).toInt()
                )
            }
            val ratio = ratioIndicator?.invoke(tag)
            if (ratio != null) {
                // 48dp tall, width = 48 x aspect, capped at 96dp.
                val aspect = (ratio.first / ratio.second).coerceIn(0.3f, 2.5f)
                val hPx = (48 * density).toInt()
                val wPx = ((48 * aspect).coerceIn(16f, 96f) * density).toInt()
                val indicator = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(android.graphics.Color.TRANSPARENT)
                        setStroke((2 * density).toInt(), getColor(R.color.hakunaa_teal))
                        cornerRadius = 4f * density
                    }
                    layoutParams = LinearLayout.LayoutParams(wPx, hPx).apply {
                        marginEnd = (12 * density).toInt()
                    }
                }
                inner.addView(indicator)
            }
            val badge = badgeProvider?.invoke(tag)
            if (badge != null) {
                // Platform mark: small coloured circle with the
                // platform initial / simple shape, drawn in code
                // (no trademarked assets).
                val badgeView = TextView(this).apply {
                    text = badge.first
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(getColor(android.R.color.white))
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(badge.second)
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        (40 * density).toInt(), (40 * density).toInt()
                    ).apply {
                        marginEnd = (12 * density).toInt()
                    }
                }
                inner.addView(badgeView)
            }
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val tvTitle = TextView(this).apply {
                text = title
                textSize = if (centerText) 17f else 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
                if (centerText) {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
            }
            textCol.addView(tvTitle)
            if (subtitle.isNotEmpty()) {
                val tvSub = TextView(this).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(getColor(R.color.hakunaa_text_secondary))
                    if (centerText) {
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                }
                textCol.addView(tvSub)
            }
            inner.addView(textCol)
            card.addView(inner)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) params.topMargin = gapPx
            card.layoutParams = params
            card.setOnClickListener { onSelect(tag) }
            container.addView(card)
        }
    }

    /** Resize: Custom FIRST (exam photos need exact sizes), then
     *  the presets. NOTHING is pre-selected — the user picks a
     *  size. ("Keep resolution" was removed: Resize means changing
     *  the size.) Tapping Custom selects it AND opens the
     *  value-entry dialog at once. */
    private fun buildResolutionCards() {
        val options = mutableListOf<Triple<String, String, String>>()
        options.add(
            Triple(
                "Custom: ${inputW}x${inputH}",
                "Custom: ${inputW}x${inputH}",
                "Enter your own size — for exam forms"
            )
        )
        for (p in listOf(
            "240x534", "360x800", "480x1068", "600x1334", "768x1708",
            "1024x2276", "1200x2668", "1280x2844", "1152x2560",
            "1440x3200", "3072x6828"
        )) options.add(Triple(p, p, ""))
        buildOptionCards(options, selectedCardTag, onSelect = { tag ->
            selectedCardTag = tag
            renderStep()
            // Tapping Custom selects it AND opens the WxH dialog.
            if (tag.startsWith("Custom")) showCustomSizeDialog()
        })
    }

    /** Fit / Fit to social media: aspect cards. Tapping one in
     *  FIT_SOCIAL opens the crop preview (adjustable frame); in FIT
     *  it just selects, and Next applies it. FIT_SOCIAL tags are
     *  unique per platform ("1:1:ig_post" vs "1:1:fb_profile") so
     *  only the tapped card highlights, and nothing is pre-selected
     *  there. Each card shows a small ratio-shape indicator plus a
     *  coloured platform badge (drawn in code), with centre-aligned
     *  bold ratio text. */
    private fun buildAspectCards() {
        val options = if (mode == MODE_FIT_SOCIAL) listOf(
            Triple("1:1:ig_post", "1:1", "Instagram post (square)"),
            Triple("4:5:ig_feed", "4:5", "Instagram feed (portrait)"),
            Triple("9:16:story", "9:16", "Instagram / TikTok / WhatsApp story"),
            Triple("16:9:cover", "16:9", "Facebook / YouTube cover"),
            Triple("1:1:fb_profile", "1:1", "Facebook profile")
        ) else listOf(
            Triple("1:1", "1:1", "Square"),
            Triple("4:5", "4:5", "Portrait 4:5"),
            Triple("9:16", "9:16", "Story 9:16"),
            Triple("16:9", "16:9", "Landscape 16:9"),
            Triple("3:2", "3:2", "Photo 3:2")
        )
        // FIT keeps its 1:1 default; FIT_SOCIAL starts with nothing selected.
        if (mode == MODE_FIT && selectedCardTag == null) selectedCardTag = "1:1"
        buildOptionCards(
            options, selectedCardTag,
            onSelect = { tag ->
                selectedCardTag = tag
                if (mode == MODE_FIT_SOCIAL) {
                    // Aspect is the tag prefix ("1:1" of "1:1:ig_post").
                    val parts = tag.split(":")
                    launchCropActivity(
                        parts.getOrNull(0)?.toIntOrNull() ?: 1,
                        parts.getOrNull(1)?.toIntOrNull() ?: 1,
                        circle = false
                    )
                } else {
                    renderStep()
                }
            },
            ratioIndicator = { tag ->
                val parts = tag.split(":")
                val w = parts.getOrNull(0)?.toFloatOrNull()
                val h = parts.getOrNull(1)?.toFloatOrNull()
                if (w != null && h != null && w > 0 && h > 0) w to h else null
            },
            // Fit to social media only: centre-aligned bold ratio
            // text + a platform badge per card (initial / shape,
            // drawn in code — no trademarked assets).
            centerText = mode == MODE_FIT_SOCIAL,
            badgeProvider = if (mode == MODE_FIT_SOCIAL) { tag ->
                when (tag) {
                    "1:1:ig_post", "4:5:ig_feed" -> "Ig" to 0xFFE1306C.toInt()
                    "9:16:story" -> "▶" to 0xFF000000.toInt()
                    "16:9:cover" -> "▶" to 0xFFFF0000.toInt()
                    "1:1:fb_profile" -> "f" to 0xFF1877F2.toInt()
                    else -> null
                }
            } else null
        )
    }

    /** Convert: format cards with compatibility info. */
    private fun buildFormatCards() {
        val options = listOf(
            Triple(
                "jpg", "JPG",
                "Smallest, best for photos. Accepted everywhere, including Govt forms."
            ),
            Triple("png", "PNG", "Lossless, best for graphics."),
            Triple(
                "webp", "WebP",
                "Smaller than JPG. Works on WhatsApp, Instagram, web. NOT accepted in Govt forms/portals."
            ),
            Triple(
                "tiff", "TIFF",
                "Print quality, large files. Best for printing/scanning."
            ),
            Triple(
                "bmp", "BMP",
                "Uncompressed, very large. Works everywhere on Windows."
            ),
            Triple(
                "avif", "AVIF",
                "Smallest of all, modern format. Not accepted in Govt forms/portals yet."
            )
        )
        if (selectedCardTag == null) selectedCardTag = "jpg"
        buildOptionCards(options, selectedCardTag, onSelect = { tag ->
            selectedCardTag = tag
            renderStep()
        })
    }

    /** DP Edit: a LIVE preview row first — the picked photo as a
     *  circle and as a square, side by side — then the Circle /
     *  Square cards (tapping one opens the crop preview). */
    private fun buildDpCards() {
        val options = listOf(
            Triple(
                "circle", "Circle",
                "Round profile photo (PNG, transparent outside)"
            ),
            Triple("square", "Square", "Square profile photo (JPG)")
        )
        buildOptionCards(options, selectedCardTag, onSelect = { tag ->
            selectedCardTag = tag
            launchCropActivity(1, 1, circle = tag == "circle")
        })
        val container = findViewById<LinearLayout>(R.id.cardsContainer)
        val density = resources.displayMetrics.density
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }
        val ivCircle = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (120 * density).toInt(), (120 * density).toInt()
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(getColor(R.color.hakunaa_outline))
        }
        val ivSquare = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (120 * density).toInt(), (120 * density).toInt()
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(getColor(R.color.hakunaa_outline))
        }
        fun previewCell(iv: ImageView, label: String): LinearLayout {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            cell.addView(iv)
            cell.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                gravity = Gravity.CENTER
                setPadding(0, (8 * density).toInt(), 0, 0)
            })
            return cell
        }
        previewRow.addView(previewCell(ivCircle, "Circle"))
        previewRow.addView(previewCell(ivSquare, "Square"))
        container.addView(previewRow, 0)
        // Render both previews on a background thread, then show them.
        val input = inputFile ?: return
        Thread {
            try {
                val ts = System.currentTimeMillis()
                val circleFile = File(cacheDir, "hakunaa_dp_circle_$ts.png")
                val squareFile = File(cacheDir, "hakunaa_dp_square_$ts.jpg")
                val circleOk = ImageCompressor.circleCrop(input, circleFile)
                val squareOk = ImageCompressor.fitToAspect(input, squareFile, 1, 1)
                val circleBmp = if (circleOk) BitmapFactory.decodeFile(circleFile.absolutePath) else null
                val squareBmp = if (squareOk) BitmapFactory.decodeFile(squareFile.absolutePath) else null
                runOnUiThread {
                    circleBmp?.let { ivCircle.setImageBitmap(it) }
                    squareBmp?.let { ivSquare.setImageBitmap(it) }
                }
            } catch (_: Throwable) {
                // Previews are a bonus — the shape cards still work.
            }
        }.start()
    }

    /** Open the crop preview with an aspect ratio locked (and
     *  optionally a circle frame for DP Circle). */
    private fun launchCropActivity(aspectW: Int, aspectH: Int, circle: Boolean) {
        val uri = inputUriString ?: return
        val intent = android.content.Intent(this, ImageCropActivity::class.java)
        intent.putExtra(ImageCropActivity.EXTRA_IMAGE_URI, uri)
        intent.putExtra(ImageCropActivity.EXTRA_ASPECT_W, aspectW)
        intent.putExtra(ImageCropActivity.EXTRA_ASPECT_H, aspectH)
        intent.putExtra(ImageCropActivity.EXTRA_CIRCLE, circle)
        startActivity(intent)
    }

    private fun addOptions(group: RadioGroup, options: List<Pair<String, String>>, checked: Int) {
        for ((i, option) in options.withIndex()) {
            val radio = RadioButton(this)
            radio.id = View.generateViewId()
            radio.tag = option.first
            radio.text = "${option.first}\n${option.second}"
            radio.isChecked = i == checked
            radio.setPadding(16, 24, 16, 24)
            radio.textSize = 16f
            group.addView(radio)
        }
    }

    private fun selectedTag(): String? {
        val group = findViewById<RadioGroup>(R.id.rgOptions)
        val checked = group.findViewById<RadioButton>(group.checkedRadioButtonId) ?: return null
        return checked.tag as? String
    }

    /** Leaving the tool asks first — a polished professional dialog:
     *  rounded card, amber warning icon, clear title + message, a
     *  prominent teal "Leave" action and a subtle "Cancel". */
    private fun attemptLeave() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(true)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
        }
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = 20f * density
            cardElevation = 8f * density
            setCardBackgroundColor(getColor(R.color.hakunaa_surface))
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * density).toInt(), (24 * density).toInt(),
                (24 * density).toInt(), (20 * density).toInt()
            )
        }
        val icon = TextView(this).apply {
            text = "!"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(getColor(android.R.color.white))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF59E0B.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                (48 * density).toInt(), (48 * density).toInt()
            )
        }
        val title = TextView(this).apply {
            text = "Leave this tool?"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        val message = TextView(this).apply {
            text = "Your work will be discarded."
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (20 * density).toInt(), 0, 0)
        }
        val cancelBtn = android.widget.Button(this).apply {
            text = "Cancel"
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            setBackgroundResource(android.R.color.transparent)
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        }
        val leaveBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Leave"
            setTextColor(getColor(android.R.color.white))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            cornerRadius = (12 * density).toInt()
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
        }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        leaveBtn.setOnClickListener { dialog.dismiss(); finish() }
        buttons.addView(cancelBtn)
        buttons.addView(leaveBtn)
        inner.addView(icon)
        inner.addView(title)
        inner.addView(message)
        inner.addView(buttons)
        card.addView(inner)
        outer.addView(card)
        dialog.setContentView(outer)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    /** Smart Compress: try all levels, show every result, user picks one. */
    private fun runSmartCompress() {
        val input = inputFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Working…"
        Thread {
            try {
                val base = File(cacheDir, "hakunaa_smart_${System.currentTimeMillis()}")
                val results = ImageCompressor.compressAllLevels(input, base)
                runOnUiThread {
                    if (results.isEmpty()) {
                        status.text = "Already optimized — even the strongest settings couldn't shrink this image."
                    } else {
                        webpLazyResults = null
                        smartResults = results
                        step = 1
                        renderStep()
                        showSmartResults(results)
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Failed — try another image" }
            }
        }.start()
    }

    private fun next() {
        val input = inputFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        if (mode == MODE_METADATA) {
            if (step == 1) finish()
            return
        }
        if (mode == MODE_SMART) {
            if (step == 1) finish()
            return
        }
        // Compress: 3 buttons — Quality %, Target size (MB/KB),
        // HD Compress (size only, 0% quality loss). Step 0 is the
        // buttons (Next is hidden there), so nothing to do here.
        if (mode == MODE_COMPRESS && step == 0) return
        if (mode == MODE_COMPRESS && step == 1) {
            when (compressMode) {
                1 -> { /* Quality is picked by tapping a card (Next is hidden there). */ }
                2 -> {
                    val targetBytes = targetSizeBytes()
                    val etTarget = findViewById<android.widget.EditText>(R.id.etTargetSize)
                    if (targetBytes == null || targetBytes <= 0) {
                        status.text = "Enter a size first (e.g. 500 KB or 2 MB)"
                        return
                    }
                    if (targetBytes >= inputSize) {
                        etTarget.setBackgroundResource(R.drawable.bg_input_error)
                        status.text = "Target must be smaller than the original (${com.hakunaa.app.core.FileUtils.formatSize(inputSize)})"
                        return
                    }
                    etTarget.setBackgroundResource(R.drawable.bg_search)
                    status.text = "Checking…"
                    val nextBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNext)
                    nextBtn.isEnabled = false
                    Thread {
                        // Dynamic check: how small can this photo actually go?
                        val minBytes = ImageCompressor.minPossibleSize(input)
                        runOnUiThread {
                            if (targetBytes < minBytes) {
                                nextBtn.isEnabled = true
                                showTooLowWarning(targetBytes, minBytes)
                            } else {
                                runTargetCompress(targetBytes)
                            }
                        }
                    }.start()
                }
                3 -> runHdCompress()
            }
            return
        }
        if (mode == MODE_COMPRESS && step == 2) {
            if (compressMode == 1) {
                // Safety net: Quality must never sit on the resolution
                // step — go straight to the Smart results instead.
                runQualitySmartResults()
                return
            }
            finish()
            return
        }
        if (mode == MODE_COMPRESS && step == 3) {
            finish()
            return
        }
        // Resize Custom: ask for the exact size on the UI thread
        // (a dialog must never be shown from a background thread).
        if (mode == MODE_RESIZE && (selectedCardTag ?: selectedTag())?.startsWith("Custom") == true) {
            showCustomSizeDialog()
            return
        }
        // Resize with nothing chosen is an error, never a silent copy.
        if (mode == MODE_RESIZE && (selectedCardTag ?: selectedTag()) == null) {
            status.text = "Choose a size first"
            return
        }
        status.text = "Working…"
        // Set when an AVIF convert fails because this phone has no AVIF encoder.
        var avifUnavailable = false
        Thread {
            try {
                val out = when (mode) {
                    MODE_RESIZE -> {
                        val resolution = selectedCardTag ?: selectedTag()
                        val outFile = File(cacheDir, "hakunaa_resized_${System.currentTimeMillis()}.jpg")
                        val ok = if (resolution == null) {
                            false
                        } else {
                            val dims = resolution.split("x")
                            val w = dims.getOrNull(0)?.toIntOrNull()
                            val h = dims.getOrNull(1)?.toIntOrNull()
                            if (w != null && h != null) ImageCompressor.resizeTo(input, outFile, w, h)
                            else false
                        }
                        if (ok) outFile else null
                    }
                    MODE_TARGET_SIZE -> {
                        val kb = selectedTag()?.toLongOrNull() ?: 200L
                        val outFile = File(cacheDir, "hakunaa_target_${System.currentTimeMillis()}.jpg")
                        ImageCompressor.compressToTargetSize(input, outFile, kb * 1024)
                    }
                    MODE_FIT, MODE_FIT_SOCIAL -> {
                        val aspect = selectedCardTag ?: selectedTag() ?: "1:1"
                        val parts = aspect.split(":")
                        val w = parts.getOrNull(0)?.toIntOrNull() ?: 1
                        val h = parts.getOrNull(1)?.toIntOrNull() ?: 1
                        val outFile = File(cacheDir, "hakunaa_fit_${System.currentTimeMillis()}.jpg")
                        if (ImageCompressor.fitToAspect(input, outFile, w, h)) outFile else null
                    }
                    MODE_CONVERT -> {
                        val format = when (selectedCardTag ?: selectedTag()) {
                            "png" -> ImageCompressor.Format.PNG
                            "webp" -> ImageCompressor.Format.WEBP
                            "tiff" -> ImageCompressor.Format.TIFF
                            "bmp" -> ImageCompressor.Format.BMP
                            "avif" -> ImageCompressor.Format.AVIF
                            else -> ImageCompressor.Format.JPEG
                        }
                        val outFile = File(
                            cacheDir,
                            "hakunaa_converted_${System.currentTimeMillis()}.${format.extension}"
                        )
                        val ok = ImageCompressor.convertFormat(input, outFile, format)
                        if (!ok && format == ImageCompressor.Format.AVIF) avifUnavailable = true
                        if (ok) outFile else null
                    }
                    else -> null
                }
                runOnUiThread {
                    if (out != null && out.exists()) {
                        val after = out.length()
                        val saved = MediaStoreSaver.save(this, out, MediaStoreSaver.Kind.IMAGE, out.name)
                        status.text = if (saved != null) "Done — saved to Pictures/Hakunaa"
                        else "Done, but couldn't save to Pictures — please try again"
                        findViewById<TextView>(R.id.tvResult).text =
                            "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(after)} " +
                                "(${FileUtils.savedPercent(inputSize, after)})"
                    } else {
                        status.text = if (avifUnavailable)
                            "AVIF isn't available on this phone — try another format"
                        else "Failed — try another image"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Failed — try another image" }
            }
        }.start()
    }

    private var smartResults: List<ImageCompressor.LevelResult> = emptyList()
    private var qualityChoice = 85
    /** Compress sub-mode: 0=none, 1=Quality, 2=Target size, 3=HD Compress. */
    private var compressMode = 0

    /** True when the Compress flow is on its final step (Next = Done). */
    private fun isCompressDoneStep(): Boolean = when (compressMode) {
        1 -> step == 3
        2, 3 -> step == 2
        else -> false
    }

    /** The 3 professional Compress buttons: Quality / Target size / HD Compress. */
    private fun buildCompressModeCards() {
        val container = findViewById<LinearLayout>(R.id.modeContainer)
        container.removeAllViews()
        val inflater = android.view.LayoutInflater.from(this)
        val gapPx = (12 * resources.displayMetrics.density).toInt()
        val modes = listOf(
            Triple(R.drawable.ic_tune, "Quality", "Choose a quality %") to 1,
            Triple(R.drawable.ic_text_resize, "Target size", "Enter MB or KB") to 2,
            Triple(R.drawable.ic_bolt, "HD Compress", "Only size reduces, 0% quality loss") to 3
        )
        for ((index, entry) in modes.withIndex()) {
            val (info, mode) = entry
            val (icon, title, subtitle) = info
            val card = inflater.inflate(R.layout.item_image_tool, container, false)
            card.findViewById<android.widget.ImageView>(R.id.ivIcon).setImageResource(icon)
            card.findViewById<TextView>(R.id.tvTitle).text = title
            card.findViewById<TextView>(R.id.tvSubtitle).text = subtitle
            if (index > 0) {
                val params = card.layoutParams as? LinearLayout.LayoutParams
                params?.topMargin = gapPx
                card.layoutParams = params
            }
            card.setOnClickListener {
                compressMode = mode
                if (mode == 3) {
                    // HD: straight to the one-tap compress — no size
                    // step, no options (fix 3).
                    runHdCompress()
                } else {
                    step = 1
                    renderStep()
                }
            }
            container.addView(card)
        }
    }

    /** HD Compress: one tap, no options — SAME photo, SAME size, 100% quality,
     *  just a smaller file. Best of JPEG 95 / WebP 95 at FULL resolution —
     *  no downscale, no blur. After compressing it shows a result
     *  screen (Preview + size info + Save) like the other compress
     *  menus — it NEVER saves silently. */
    private fun runHdCompress() {
        val input = inputFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Working…"
        val nextBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNext)
        nextBtn.isEnabled = false
        Thread {
            try {
                val base = File(cacheDir, "hakunaa_hd_${System.currentTimeMillis()}")
                val results = ImageCompressor.compressAtQuality(input, base, 95, 10000)
                runOnUiThread {
                    nextBtn.isEnabled = true
                    if (results.isNotEmpty()) {
                        webpLazyResults = null
                        smartResults = results
                        step = 2
                        renderStep()
                        status.text = "HD done — preview below, then Save"
                        // Result screen (not a silent save): Preview
                        // button + "Original → New • saved %" header +
                        // Save selected. allowWebp = true because a
                        // WebP input stays WebP at quality 95.
                        showSmartResults(results, allowWebp = true)
                    } else {
                        status.text = "Already optimized — this photo is already small"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    nextBtn.isEnabled = true
                    status.text = "Failed — try another image"
                }
            }
        }.start()
    }

    /** Run the Target-size compress on a background thread. */
    private fun runTargetCompress(targetBytes: Long) {
        val input = inputFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Working…"
        val nextBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNext)
        nextBtn.isEnabled = false
        Thread {
            try {
                val outFile = File(cacheDir, "hakunaa_target_${System.currentTimeMillis()}.jpg")
                val result = ImageCompressor.compressToTargetSize(input, outFile, targetBytes)
                runOnUiThread {
                    nextBtn.isEnabled = true
                    if (result != null && result.exists() && result.length() <= targetBytes) {
                        step = 2
                        renderStep()
                        saveSingleResult(result)
                    } else {
                        try { outFile.delete() } catch (_: Throwable) { }
                        status.text = "Could not reach that size — try a bigger target"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    nextBtn.isEnabled = true
                    status.text = "Failed — try another image"
                }
            }
        }.start()
    }

    /** Target too low: warn FIRST — the photo will look poor. */
    private fun showTooLowWarning(targetBytes: Long, minBytes: Long) {
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "That size is too low — the photo will look poor."
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Very low target?")
            .setMessage("The smallest this photo can be is ${com.hakunaa.app.core.FileUtils.formatSize(minBytes)}. A ${com.hakunaa.app.core.FileUtils.formatSize(targetBytes)} target will look very poor. Continue anyway?")
            .setPositiveButton("Continue anyway") { _, _ -> runTargetCompress(targetBytes) }
            .setNegativeButton("Change target", null)
            .show()
    }

    /** Quality % -> Smart results at FULL resolution (no resolution step). */
    private fun runQualitySmartResults() {
        val input = inputFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Working…"
        val nextBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNext)
        nextBtn.isEnabled = false
        Thread {
            try {
                val base = File(cacheDir, "hakunaa_smart_${System.currentTimeMillis()}")
                val results = ImageCompressor.compressAtQuality(input, base, qualityChoice, 10000)
                runOnUiThread {
                    nextBtn.isEnabled = true
                    if (results.isEmpty()) {
                        status.text = "Already optimized — even the strongest settings couldn't shrink this image."
                    } else {
                        webpLazyResults = null
                        smartResults = results
                        step = 3
                        renderStep()
                        showSmartResults(results)
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    nextBtn.isEnabled = true
                    status.text = "Failed — try another image"
                }
            }
        }.start()
    }

    /** Target size in bytes from the custom input (number + KB/MB toggle). */
    private fun targetSizeBytes(): Long? {
        val text = findViewById<android.widget.EditText>(R.id.etTargetSize).text.toString().trim()
        val value = text.toDoubleOrNull() ?: return null
        return (value * if (targetUnitIsMb) 1024 * 1024 else 1024).toLong()
    }

    /** KB/MB segmented toggle state (KB by default). */
    private var targetUnitIsMb = false

    /** Paint the KB/MB toggle: selected half teal + white, other half grey. */
    private fun updateUnitToggle() {
        val kb = findViewById<TextView>(R.id.tvUnitKb)
        val mb = findViewById<TextView>(R.id.tvUnitMb)
        if (targetUnitIsMb) {
            mb.setBackgroundResource(R.drawable.bg_teal_button)
            mb.setTextColor(getColor(android.R.color.white))
            kb.background = null
            kb.setTextColor(getColor(R.color.hakunaa_text_secondary))
        } else {
            kb.setBackgroundResource(R.drawable.bg_teal_button)
            kb.setTextColor(getColor(android.R.color.white))
            mb.background = null
            mb.setTextColor(getColor(R.color.hakunaa_text_secondary))
        }
    }

    /** Quality % as professional tappable cards (not a radio list). */
    /** Quality % as SMALL compact rows, one line: "95% - Maximum Quality".
     *  Every row is EXACTLY 48dp tall, full-width, same 16dp side
     *  padding, same 8dp gap, text vertically centred — perfect
     *  alignment and spacing. */
    private fun buildQualityCards() {
        val container = findViewById<LinearLayout>(R.id.qualityContainer)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        val gapPx = (8 * density).toInt()
        val rowHeightPx = (48 * density).toInt()
        val qualities = listOf(
            95 to "Maximum quality",
            90 to "High quality",
            80 to "Good quality",
            70 to "Balanced quality",
            60 to "Medium quality",
            50 to "Enough quality",
            85 to "Custom quality: 85"
        )
        for ((index, entry) in qualities.withIndex()) {
            val (quality, label) = entry
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 12f * density
                cardElevation = 2f * density
                setCardBackgroundColor(getColor(R.color.hakunaa_surface))
            }
            val tv = TextView(this).apply {
                text = "$quality% - $label"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            card.addView(tv)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                rowHeightPx
            )
            if (index > 0) params.topMargin = gapPx
            card.layoutParams = params
            card.setOnClickListener {
                qualityChoice = quality
                runQualitySmartResults()
            }
            container.addView(card)
        }
    }

    /** Resize Custom: a polished dialog — Width and Height side by
     *  side with a "×" between them, quick-pick chips for common
     *  exam sizes, and a teal Resize action. */
    private fun showCustomSizeDialog() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(true)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
        }
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = 20f * density
            cardElevation = 8f * density
            setCardBackgroundColor(getColor(R.color.hakunaa_surface))
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * density).toInt(), (24 * density).toInt(),
                (24 * density).toInt(), (20 * density).toInt()
            )
        }
        val title = TextView(this).apply {
            text = "Custom size"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        val hintText = TextView(this).apply {
            text = "Enter the exact size you need — for exam forms."
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            setPadding(0, (8 * density).toInt(), 0, (16 * density).toInt())
        }
        // Width × Height, side by side.
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val etW = android.widget.EditText(this).apply {
            hint = "Width"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(inputW.toString())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_search)
            setPadding(0, (16 * density).toInt(), 0, (16 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val times = TextView(this).apply {
            text = "×"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        }
        val etH = android.widget.EditText(this).apply {
            hint = "Height"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(inputH.toString())
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_search)
            setPadding(0, (16 * density).toInt(), 0, (16 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        sizeRow.addView(etW)
        sizeRow.addView(times)
        sizeRow.addView(etH)
        // Quick-pick chips for common exam sizes.
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        val examSizes = listOf(
            Triple(600, 600, "Passport"),
            Triple(800, 800, "Square"),
            Triple(1080, 1080, "Square"),
            Triple(1080, 1350, "Portrait")
        )
        for ((w, h, label) in examSizes) {
            val chip = TextView(this).apply {
                text = "$w×$h\n$label"
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_teal))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(getColor(R.color.hakunaa_surface))
                    setStroke((1 * density).toInt(), getColor(R.color.hakunaa_teal))
                    cornerRadius = 12f * density
                }
                setPadding(
                    (8 * density).toInt(), (8 * density).toInt(),
                    (8 * density).toInt(), (8 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    val m = (4 * density).toInt()
                    marginStart = m
                    marginEnd = m
                }
            }
            chip.setOnClickListener {
                etW.setText(w.toString())
                etH.setText(h.toString())
            }
            chipsRow.addView(chip)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (20 * density).toInt(), 0, 0)
        }
        val cancelBtn = android.widget.Button(this).apply {
            text = "Cancel"
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            setBackgroundResource(android.R.color.transparent)
            setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
        }
        val resizeBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Resize"
            setTextColor(getColor(android.R.color.white))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            cornerRadius = (12 * density).toInt()
            setPadding((20 * density).toInt(), 0, (20 * density).toInt(), 0)
        }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        resizeBtn.setOnClickListener {
            val w = etW.text.toString().toIntOrNull()
            val h = etH.text.toString().toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) {
                dialog.dismiss()
                resizeToCustom(w, h)
            } else {
                Toast.makeText(
                    this, "Enter a valid width and height",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        buttons.addView(cancelBtn)
        buttons.addView(resizeBtn)
        inner.addView(title)
        inner.addView(hintText)
        inner.addView(sizeRow)
        inner.addView(chipsRow)
        inner.addView(buttons)
        card.addView(inner)
        outer.addView(card)
        dialog.setContentView(outer)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    /** Resize to an exact custom size and save. */
    private fun resizeToCustom(w: Int, h: Int) {
        val input = inputFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Working…"
        Thread {
            try {
                val outFile = File(cacheDir, "hakunaa_resized_${System.currentTimeMillis()}.jpg")
                val ok = ImageCompressor.resizeTo(input, outFile, w, h)
                runOnUiThread {
                    if (ok && outFile.exists()) saveSingleResult(outFile)
                    else status.text = "Failed — try another image"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Failed — try another image" }
            }
        }.start()
    }

    /** Save one finished file to Pictures/Hakunaa and show its sizes. */
    private fun saveSingleResult(outFile: java.io.File) {
        val status = findViewById<TextView>(R.id.tvStatus)
        val after = outFile.length()
        val saved = MediaStoreSaver.save(this, outFile, MediaStoreSaver.Kind.IMAGE, outFile.name)
        status.text = if (saved != null) "Done — saved to Pictures/Hakunaa"
        else "Done, but couldn't save to Pictures — please try again"
        findViewById<TextView>(R.id.tvResult).text =
            "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(after)} " +
                "(${FileUtils.savedPercent(inputSize, after)})"
    }

    /** One Smart result as a professional card: label + Best badge +
     *  "size • saved N%" + a strong teal SAVE button. Tapping the card
     *  SELECTS it (highlight + opens the Before/After preview);
     *  tapping SAVE saves that row straight away. */
    private fun buildSmartResultCard(r: ImageCompressor.LevelResult, showBest: Boolean): View {
        val density = resources.displayMetrics.density
        val isSelected = r == selectedSmartResult
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = 12f * density
            cardElevation = 2f * density
            setCardBackgroundColor(
                if (isSelected) 0xFFE0F2F1.toInt() else getColor(R.color.hakunaa_surface)
            )
            strokeWidth = if (isSelected) (2 * density).toInt() else 0
            strokeColor = getColor(R.color.hakunaa_teal)
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(), (12 * density).toInt(),
                (16 * density).toInt(), (12 * density).toInt()
            )
        }
        val line1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = r.label
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        line1.addView(label)
        if (showBest) {
            val best = TextView(this).apply {
                text = "  Best  "
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(android.R.color.white))
                setBackgroundColor(getColor(R.color.hakunaa_teal))
                setPadding(12, 4, 12, 4)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = (12 * density).toInt()
            line1.addView(best, lp)
        }
        // Strong SAVE button at the right end — a real teal button,
        //  not plain text, so it is clearly tappable.
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        line1.addView(spacer)
        val saveBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "SAVE"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(android.R.color.white))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            cornerRadius = (8 * density).toInt()
            minimumHeight = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt()
            )
        }
        line1.addView(saveBtn)
        val line2 = TextView(this).apply {
            text = "${FileUtils.formatSize(r.size)}  •  saved ${r.savingPercent}%"
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
        }
        inner.addView(line1)
        inner.addView(line2)
        card.addView(inner)
        card.setOnClickListener { selectSmartResult(r) }
        saveBtn.setOnClickListener { saveSmartResult(r) }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = (8 * density).toInt()
        card.layoutParams = params
        return card
    }

    /** Rebuild the result cards from [smartDisplayed] and update the
     *  summary header + CompareView to match the selection. */
    private fun refreshSmartRows() {
        val container = findViewById<LinearLayout>(R.id.resultsContainer)
        val rowsContainer = container.findViewWithTag<LinearLayout>("smart_rows")
            ?: return
        rowsContainer.removeAllViews()
        val best = smartDisplayed.minByOrNull { it.size }
        for (r in smartDisplayed) {
            rowsContainer.addView(buildSmartResultCard(r, r == best))
        }
        // Header follows the true best across all shown rows.
        val header = container.findViewWithTag<TextView>("smart_header")
        if (header != null && best != null) {
            header.text = "Original ${FileUtils.formatSize(inputSize)} → " +
                "Best ${FileUtils.formatSize(best.size)} • saved ${best.savingPercent}%"
        }
    }

    /** Select a Smart result: highlight its card and open the
     *  Before/After preview for it (no save until "Save selected"
     *  or the row's SAVE button). */
    private fun selectSmartResult(r: ImageCompressor.LevelResult) {
        selectedSmartResult = r
        if (previewInitialised) compareView?.setCompressedFile(r.file)
        refreshSmartRows()
        // Tapping ANY result row opens the preview for that result.
        if (previewWrap != null) setPreviewVisible(true)
    }

    /** Show/hide the Before/After preview. The CompareView is only
     *  populated the first time it is shown — never automatically.
     *  If the selection changed while it was hidden, it is synced
     *  to the current selection when shown again. */
    private fun setPreviewVisible(visible: Boolean) {
        val wrap = previewWrap ?: return
        previewVisible = visible
        if (visible) {
            val input = inputFile
            val selected = selectedSmartResult
            if (!previewInitialised && input != null && selected != null) {
                compareView?.setImages(input, selected.file)
                previewInitialised = true
            } else if (previewInitialised && selected != null) {
                compareView?.setCompressedFile(selected.file)
            }
        }
        wrap.visibility = if (visible) View.VISIBLE else View.GONE
        previewToggleBtn?.text = if (visible) "Hide preview" else "Preview — Before / After"
    }

    /** "More Compress": compute the WebP results LAZILY on a
     *  background thread (Quality flow only) and append them below
     *  the JPG rows with the Govt compatibility note. */
    private fun loadMoreCompressWebp() {
        val input = inputFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Checking WebP…"
        Thread {
            try {
                val results = webpLazyResults ?: ImageCompressor.compressAtQualityWebp(
                    input,
                    File(cacheDir, "hakunaa_webp_${System.currentTimeMillis()}"),
                    qualityChoice, 10000
                ).also { webpLazyResults = it }
                runOnUiThread {
                    status.text = ""
                    webpShown = true
                    val container = findViewById<LinearLayout>(R.id.resultsContainer)
                    val moreSection = container.findViewWithTag<LinearLayout>("smart_more")
                    moreSection?.removeAllViews()
                    if (results.isEmpty()) {
                        moreSection?.addView(TextView(this).apply {
                            text = "WebP couldn't shrink this photo further."
                            textSize = 13f
                            setTextColor(getColor(R.color.hakunaa_text_secondary))
                            setPadding(16, 16, 16, 8)
                        })
                    } else {
                        moreSection?.addView(TextView(this).apply {
                            text = "WebP — smaller than JPG"
                            textSize = 14f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(getColor(R.color.hakunaa_text))
                            setPadding(16, 16, 16, 4)
                        })
                        moreSection?.addView(TextView(this).apply {
                            text = "✓ Works: WhatsApp, Instagram, web\n✗ NOT accepted: Govt forms / portals"
                            textSize = 13f
                            setTextColor(getColor(R.color.hakunaa_text_secondary))
                            setPadding(16, 0, 16, 8)
                        })
                        smartDisplayed.addAll(results)
                        refreshSmartRows()
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "WebP check failed — try again" }
            }
        }.start()
    }

    /** Smart results: JPG ONLY by default — WebP is never shown
     *  unless the user asks for it via "More Compress" (Quality
     *  flow) or "More Compress with WebP" (Smart flow). Professional
     *  layout: a summary header, a Preview button (the Before/After
     *  CompareView appears ONLY on tap — never automatically),
     *  selectable result cards, and a full-width teal
     *  "Save selected" button. [allowWebp] is true only for the HD
     *  result screen, whose single result may be WebP (a WebP input
     *  stays WebP at quality 95). */
    private fun showSmartResults(
        results: List<ImageCompressor.LevelResult>,
        allowWebp: Boolean = false
    ) {
        val container = findViewById<LinearLayout>(R.id.resultsContainer)
        container.removeAllViews()
        webpShown = false
        previewWrap = null
        previewToggleBtn = null
        compareView = null
        previewInitialised = false
        previewVisible = false
        val density = resources.displayMetrics.density
        // JPG only — the old `else results` fallback (which showed
        // WebP rows by default) is gone on purpose. (HD passes
        // allowWebp = true for its single result.)
        smartDisplayed = results
            .filter {
                it.format == ImageCompressor.Format.JPEG ||
                    (allowWebp && it.format == ImageCompressor.Format.WEBP)
            }
            .sortedBy { it.size }
            .toMutableList()
        selectedSmartResult = smartDisplayed.minByOrNull { it.size }

        if (smartDisplayed.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No smaller JPG version for this photo — try More Compress for WebP."
                textSize = 14f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                setPadding(16, 16, 16, 8)
            })
        } else {
            // Summary header card.
            val best = smartDisplayed.minByOrNull { it.size }!!
            val headerCard = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 12f * density
                cardElevation = 2f * density
                setCardBackgroundColor(0xFFE0F2F1.toInt())
            }
            val headerText = TextView(this).apply {
                tag = "smart_header"
                text = "Original ${FileUtils.formatSize(inputSize)} → " +
                    "Best ${FileUtils.formatSize(best.size)} • saved ${best.savingPercent}%"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
                setPadding(
                    (16 * density).toInt(), (14 * density).toInt(),
                    (16 * density).toInt(), (14 * density).toInt()
                )
            }
            headerCard.addView(headerText)
            container.addView(headerCard)

            // Before/After preview — NOT shown automatically. A
            // teal outline "Preview" button reveals the CompareView
            // (populated on first tap); tapping again hides it.
            // Tapping a result row opens it too (selectSmartResult).
            val previewBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = "Preview — Before / After"
                setTextColor(getColor(R.color.hakunaa_teal))
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
                strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                strokeWidth = (2 * density).toInt()
                cornerRadius = (12 * density).toInt()
                setIconResource(android.R.drawable.ic_menu_view)
                iconTint = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (12 * density).toInt()
                layoutParams = lp
            }
            container.addView(previewBtn)
            previewToggleBtn = previewBtn
            val previewContainer = LinearLayout(this).apply {
                tag = "smart_preview_wrap"
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = (12 * density).toInt()
                }
            }
            val cv = CompareView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (260 * density).toInt()
                )
            }
            previewContainer.addView(cv)
            container.addView(previewContainer)
            compareView = cv
            previewWrap = previewContainer
            previewBtn.setOnClickListener { setPreviewVisible(!previewVisible) }

            // "Results" section heading, then the selectable cards.
            val rowsLabel = TextView(this).apply {
                text = "Results"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
                setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
            }
            container.addView(rowsLabel)
            // Selectable result cards.
            val rowsContainer = LinearLayout(this).apply {
                tag = "smart_rows"
                orientation = LinearLayout.VERTICAL
            }
            container.addView(rowsContainer)
            refreshSmartRows()
        }

        // "More Compress" — Quality flow ("More Compress") and Smart
        // flow ("More Compress with WebP"). WebP is computed lazily
        // on tap, never up front, via loadMoreCompressWebp().
        val showMore = (mode == MODE_COMPRESS && compressMode == 1) || mode == MODE_SMART
        if (showMore) {
            val moreSection = LinearLayout(this).apply {
                tag = "smart_more"
                orientation = LinearLayout.VERTICAL
            }
            val moreBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = if (mode == MODE_SMART) "More Compress with WebP" else "More Compress"
                setTextColor(getColor(R.color.hakunaa_teal))
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
                strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                strokeWidth = (2 * density).toInt()
                cornerRadius = (12 * density).toInt()
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (12 * density).toInt()
                layoutParams = lp
            }
            moreBtn.setOnClickListener {
                if (webpShown) return@setOnClickListener
                moreSection.removeView(moreBtn)
                loadMoreCompressWebp()
            }
            moreSection.addView(moreBtn)
            container.addView(moreSection)
        }

        // Prominent teal "Save selected" — saves the selected result.
        // Bold 16sp text on the full-width 56dp button so it reads
        // as the screen's primary action.
        if (smartDisplayed.isNotEmpty()) {
            val saveBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = "Save selected"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(android.R.color.white))
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                cornerRadius = (12 * density).toInt()
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (56 * density).toInt()
                )
                lp.topMargin = (16 * density).toInt()
                layoutParams = lp
            }
            saveBtn.setOnClickListener {
                selectedSmartResult?.let { saveSmartResult(it) }
            }
            container.addView(saveBtn)
        }
    }

    /** Read every bit of this image's metadata (EXIF via the
     *  framework android.media.ExifInterface — no new dependency). */
    private fun readMetadata(): List<Pair<String, String>> {
        val file = inputFile ?: return emptyList()
        val rows = mutableListOf<Pair<String, String>>()
        rows.add("File" to inputName.ifEmpty { file.name })
        rows.add("Format" to ImageCompressor.formatOf(file).name)
        rows.add("Size" to FileUtils.formatSize(inputSize))
        rows.add("Dimensions" to "${inputW} x ${inputH} px")
        try {
            val exif = android.media.ExifInterface(file.absolutePath)
            val dt = exif.getAttribute(android.media.ExifInterface.TAG_DATETIME)
            rows.add("Date taken" to (dt ?: "Not present"))
            val make = exif.getAttribute(android.media.ExifInterface.TAG_MAKE)
            val model = exif.getAttribute(android.media.ExifInterface.TAG_MODEL)
            val camera = listOfNotNull(make, model).joinToString(" ").ifEmpty { "Not present" }
            rows.add("Camera" to camera)
            val latLong = FloatArray(2)
            val hasGps = exif.getLatLong(latLong)
            rows.add(
                "Location" to if (hasGps) "${latLong[0]}, ${latLong[1]}" else "Not present"
            )
            val orient = exif.getAttributeInt(
                android.media.ExifInterface.TAG_ORIENTATION, -1
            )
            rows.add("Orientation" to if (orient >= 0) orient.toString() else "Not present")
            val iso = exif.getAttribute(android.media.ExifInterface.TAG_ISO_SPEED_RATINGS)
            rows.add("ISO" to (iso ?: "Not present"))
        } catch (_: Throwable) {
            rows.add("Details" to "Could not read")
        }
        return rows
    }

    private fun addMetadataRow(container: LinearLayout, label: String, value: String) {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }
        val tvLabel = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val tvValue = TextView(this).apply {
            text = value
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        row.addView(tvLabel)
        row.addView(tvValue)
        container.addView(row)
    }

    /** Step 0: show ALL the image's metadata + a Remove button. */
    private fun buildMetadataRows() {
        val container = findViewById<LinearLayout>(R.id.metadataContainer)
        container.removeAllViews()
        container.visibility = View.VISIBLE
        if (metadataBefore.isEmpty()) metadataBefore = readMetadata()
        for ((label, value) in metadataBefore) addMetadataRow(container, label, value)
        val density = resources.displayMetrics.density
        val removeBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Remove metadata"
            setTextColor(getColor(android.R.color.white))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            cornerRadius = (12 * density).toInt()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (16 * density).toInt()
            layoutParams = lp
        }
        removeBtn.setOnClickListener { doRemoveMetadata() }
        container.addView(removeBtn)
    }

    /** Strip the metadata, then show the Before/After view. */
    private fun doRemoveMetadata() {
        val input = inputFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Removing metadata…"
        Thread {
            try {
                val removed = mutableListOf<String>()
                for ((label, value) in metadataBefore) {
                    if (value != "Not present" && label in listOf(
                            "Date taken", "Camera", "Location", "ISO", "Orientation"
                        )
                    ) removed.add(label)
                }
                val ext = if (input.name.lowercase().endsWith(".png")) "png" else "jpg"
                val out = File(cacheDir, "hakunaa_clean_${System.currentTimeMillis()}.$ext")
                val ok = ImageCompressor.stripMetadata(input, out)
                runOnUiThread {
                    if (ok && out.exists()) {
                        metadataRemovedList = removed
                        val saved = MediaStoreSaver.save(
                            this, out, MediaStoreSaver.Kind.IMAGE, out.name
                        )
                        status.text = if (saved != null) "Done — saved to Pictures/Hakunaa"
                        else "Done, but couldn't save to Pictures — please try again"
                        findViewById<TextView>(R.id.tvResult).text =
                            "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(out.length())}"
                        step = 1
                        renderStep()
                    } else {
                        status.text = "Failed — try another image"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Failed — try another image" }
            }
        }.start()
    }

    /** Step 1: Before/After — what the photo had, what was removed. */
    private fun buildMetadataAfterRows() {
        val container = findViewById<LinearLayout>(R.id.metadataContainer)
        container.removeAllViews()
        container.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        val beforeTitle = TextView(this).apply {
            text = "Before"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding(0, 0, 0, (4 * density).toInt())
        }
        container.addView(beforeTitle)
        for ((label, value) in metadataBefore) addMetadataRow(container, label, value)
        val afterTitle = TextView(this).apply {
            text = "After — removed"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding(0, (16 * density).toInt(), 0, (4 * density).toInt())
        }
        container.addView(afterTitle)
        if (metadataRemovedList.isEmpty()) {
            val none = TextView(this).apply {
                text = "Nothing to remove — this photo had no location, camera or date info."
                textSize = 14f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
            }
            container.addView(none)
        } else {
            for (item in metadataRemovedList) {
                val row = TextView(this).apply {
                    text = "✓ $item removed"
                    textSize = 14f
                    setTextColor(getColor(R.color.hakunaa_success))
                    setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                }
                container.addView(row)
            }
            val note = TextView(this).apply {
                text = "The saved copy has no location, camera or date info."
                textSize = 13f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                setPadding(0, (8 * density).toInt(), 0, 0)
            }
            container.addView(note)
        }
    }

    private fun saveSmartResult(r: ImageCompressor.LevelResult) {
        val saved = MediaStoreSaver.save(this, r.file, MediaStoreSaver.Kind.IMAGE, r.file.name)
        findViewById<TextView>(R.id.tvStatus).text =
            if (saved != null) "Done — saved to Pictures/Hakunaa"
            else "Done, but couldn't save to Pictures — please try again"
        findViewById<TextView>(R.id.tvResult).text =
            "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(r.size)} (${r.savingPercent}% smaller)"
    }
}
