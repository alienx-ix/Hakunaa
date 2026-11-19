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
    /** Smart results currently shown (JPG rows). */
    private var smartDisplayed: MutableList<ImageCompressor.LevelResult> = mutableListOf()
    /** The Smart result currently selected for preview / save. */
    private var selectedSmartResult: ImageCompressor.LevelResult? = null
    /** Lazily computed WebP results for the Better Compress window. */
    private var webpLazyResults: List<ImageCompressor.LevelResult>? = null
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
                        "${FileUtils.formatSize(inputSize)} • ${ImageCompressor.formatOf(file).name}\n${inputW} × ${inputH} px"
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

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBack).setOnClickListener { backStep() }

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
        MODE_DP -> "DP Crop"
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
        findViewById<LinearLayout>(R.id.betterCompressBar).visibility = View.GONE
        findViewById<LinearLayout>(R.id.saveBar).visibility = View.GONE
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
            (mode == MODE_METADATA && step == 1)) "Done"
            else if (mode == MODE_CONVERT) "Convert" else "Next"
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
                            findViewById<LinearLayout>(R.id.resultsContainer).visibility = View.VISIBLE
                        }
                    }
                    else -> {
                        subtitle.text = "Tap View on a result to see it, then Save"
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
                    subtitle.text = "Tap View on a result to see it, then Save"
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
                    "Fit to Instagram, FB, WhatsApp, TikTok and more"
                else "Adjust to social media, square, any size"
                group.visibility = View.GONE
                buildAspectCards()
            }
            MODE_CONVERT -> {
                subtitle.text = "Change format to JPG, PNG, WebP, GIF, TIFF, BMP, AVIF"
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
        updateImageHeader()
    }

    /** Compress step 0 (mode cards) — COMPLETE redesign
     *  (v0.1.125): the photo is the hero and takes ALL spare
     *  height (the header itself is weight 1, the photo inside it
     *  is weight 1 too), shown WHOLE with FIT_CENTER — never
     *  cropped, never cut off (the v0.1.124 240dp CENTER_CROP band
     *  showed only the middle of the photo). The mode rows below
     *  are fixed 88dp each. The content area wraps in this step,
     *  so the photo grows/shrinks with the phone and there is
     *  never a gap before Back. Every other screen keeps the
     *  small thumbnail header — 88dp with rounded corners, a bold
     *  16sp name, and size • format • resolution clearly visible. */
    private fun updateImageHeader() {
        val density = resources.displayMetrics.density
        val thumb = findViewById<ImageView>(R.id.ivThumb)
        val parent = thumb.parent as LinearLayout
        val textCol = parent.getChildAt(1) as LinearLayout
        val name = findViewById<TextView>(R.id.tvImageName)
        val info = findViewById<TextView>(R.id.tvImageInfo)
        val big = mode == MODE_COMPRESS && step == 0
        if (big) {
            // Header takes all spare height; content area wraps.
            parent.orientation = LinearLayout.VERTICAL
            (parent.layoutParams as LinearLayout.LayoutParams).apply {
                height = 0
                weight = 1f
            }
            findViewById<LinearLayout>(R.id.contentArea).apply {
                (layoutParams as LinearLayout.LayoutParams).apply {
                    height = LinearLayout.LayoutParams.WRAP_CONTENT
                    weight = 0f
                }
            }
            // Whole photo, FIT_CENTER — never cropped.
            thumb.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            thumb.scaleType = ImageView.ScaleType.FIT_CENTER
            thumb.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(getColor(R.color.hakunaa_surface))
                cornerRadius = 16f * density
            }
            thumb.clipToOutline = true
            textCol.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (10 * density).toInt() }
            name.textSize = 18f
            name.setTypeface(null, android.graphics.Typeface.BOLD)
            info.textSize = 15f
        } else {
            // Restore: header wraps, content area fills.
            (parent.layoutParams as LinearLayout.LayoutParams).apply {
                height = LinearLayout.LayoutParams.WRAP_CONTENT
                weight = 0f
            }
            findViewById<LinearLayout>(R.id.contentArea).apply {
                (layoutParams as LinearLayout.LayoutParams).apply {
                    height = 0
                    weight = 1f
                }
            }
            parent.orientation = LinearLayout.HORIZONTAL
            thumb.layoutParams = LinearLayout.LayoutParams(
                (88 * density).toInt(), (88 * density).toInt()
            )
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(getColor(R.color.hakunaa_outline))
                cornerRadius = 12f * density
            }
            thumb.clipToOutline = true
            textCol.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = (12 * density).toInt() }
            name.textSize = 16f
            name.setTypeface(null, android.graphics.Typeface.BOLD)
            info.textSize = 14f
        }
    }

    /** Clean selectable card rows (title + subtitle), selected one
     *  highlighted teal. Used by Resize / Fit / Convert / DP.
     *  When [ratioIndicator] is given (aspect cards), a small
     *  bordered rectangle drawn in the card's proportions sits at
     *  the left of the title. When [badgeProvider] is given (Fit to
     *  social media), the exact platform logo (vector drawable,
     *  drawn in code — no downloaded assets) sits on its real
     *  brand background: Instagram's gradient, Facebook's blue
     *  circle, WhatsApp's green circle, YouTube's full red
     *  play-button mark (no circle behind it). When [centerText]
     *  is true the title/subtitle are centre-aligned and the title
     *  is larger + bold. */
    private fun buildOptionCards(
        options: List<Triple<String, String, String>>,
        selected: String?,
        onSelect: (String) -> Unit,
        ratioIndicator: ((String) -> Pair<Float, Float>?)? = null,
        centerText: Boolean = false,
        badgeProvider: ((String) -> Pair<Int, android.graphics.drawable.Drawable>?)? = null,
        // When true, the cards share the screen height (each card
        // weight 1, min 64dp) — used by Fit to social media and
        // Convert, so they fill ONE screen with no scroll and no
        // gap below the last card (v0.1.124).
        weightCards: Boolean = false,
        // When true, tighter vertical padding (Convert's 7 cards).
        compact: Boolean = false,
        // When given, the ratio indicator shows the ACTUAL photo
        // cropped to that aspect (Fit to social media) instead of
        // an empty outline rectangle (v0.1.124).
        previewProvider: ((String) -> android.graphics.Bitmap?)? = null
    ) {
        val container = findViewById<LinearLayout>(R.id.cardsContainer)
        container.removeAllViews()
        container.visibility = View.VISIBLE
        (container.layoutParams as LinearLayout.LayoutParams).apply {
            height = if (weightCards) 0 else LinearLayout.LayoutParams.WRAP_CONTENT
            weight = if (weightCards) 1f else 0f
        }
        val density = resources.displayMetrics.density
        val gapPx = (8 * density).toInt()
        for ((index, opt) in options.withIndex()) {
            val (tag, title, subtitle) = opt
            val isSelected = tag == selected
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 16f * density
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
                val vPad = ((if (compact) 8f else 14f) * density).toInt()
                setPadding(
                    (16 * density).toInt(), vPad,
                    (16 * density).toInt(), vPad
                )
            }
            val ratio = ratioIndicator?.invoke(tag)
            if (ratio != null) {
                // 48dp tall, width = 48 x aspect, capped at 96dp.
                val aspect = (ratio.first / ratio.second).coerceIn(0.3f, 2.5f)
                val hPx = (48 * density).toInt()
                val wPx = ((48 * aspect).coerceIn(16f, 96f) * density).toInt()
                val preview = previewProvider?.invoke(tag)
                if (preview != null) {
                    // The ACTUAL photo, cropped to this aspect —
                    // the frame no longer looks empty.
                    val pv = ImageView(this).apply {
                        setImageBitmap(preview)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            setStroke((2 * density).toInt(), getColor(R.color.hakunaa_teal))
                            cornerRadius = 4f * density
                        }
                        clipToOutline = true
                        layoutParams = LinearLayout.LayoutParams(wPx, hPx).apply {
                            marginEnd = (12 * density).toInt()
                        }
                    }
                    inner.addView(pv)
                } else {
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
            }
            val badge = badgeProvider?.invoke(tag)
            if (badge != null) {
                // Platform logo: the exact mark, drawn in code
                // (vector drawable, no downloaded assets), on its
                // real brand background (supplied by the provider).
                val badgeView = ImageView(this).apply {
                    setImageResource(badge.first)
                    setPadding(
                        (8 * density).toInt(), (8 * density).toInt(),
                        (8 * density).toInt(), (8 * density).toInt()
                    )
                    background = badge.second
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
                textSize = if (centerText) 18f else 16f
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
                    // A second line (after "\n") is the "Works on"
                    // compatibility line — teal, so it reads as
                    // useful info, not grey filler.
                    text = if (subtitle.contains("\n")) {
                        val parts = subtitle.split("\n", limit = 2)
                        android.text.SpannableStringBuilder().apply {
                            append(parts[0])
                            append("\n")
                            val start = length
                            append(parts[1])
                            setSpan(
                                android.text.style.ForegroundColorSpan(
                                    getColor(R.color.hakunaa_teal)
                                ),
                                start, length,
                                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    } else subtitle
                    // Compact cards (Convert/Resize) use 12sp so
                    // the "Works on" line fits on ONE line (v0.1.125).
                    textSize = if (compact) 12f else 13f
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
            // No minimum height (v0.1.125): a 64dp min broke the
            // 12 Resize rows sharing one screen (they need ~44dp).
            val params = if (weightCards) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f
                )
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
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
                // No subtitle (v0.1.125): the 12 rows share one
                // screen by weight (~44dp each) — a second line
                // would clip. The dialog explains Custom.
                ""
            )
        )
        for (p in listOf(
            "240x534", "360x800", "480x1068", "600x1334", "768x1708",
            "1024x2276", "1200x2668", "1280x2844", "1152x2560",
            "1440x3200", "3072x6828"
        )) options.add(Triple(p, p, ""))
        // All 12 rows share ONE screen by weight (v0.1.125):
        // no scroll, no cut-off, per the no-scroll rule.
        buildOptionCards(options, selectedCardTag, onSelect = { tag ->
            selectedCardTag = tag
            renderStep()
            // Tapping Custom selects it AND opens the WxH dialog.
            if (tag.startsWith("Custom")) showCustomSizeDialog()
        }, weightCards = true, compact = true)
    }

    /** Fit / Fit to social media: aspect cards. Tapping one in
     *  FIT_SOCIAL opens the crop preview (adjustable frame); in FIT
     *  it just selects, and Next applies it. FIT_SOCIAL tags are
     *  unique per platform ("1:1:ig_post" vs "1:1:fb_profile") so
     *  only the tapped card highlights, and nothing is pre-selected
     *  there. Each card shows a small ratio-shape indicator plus the
     *  exact platform logo (drawn in code), with centre-aligned
     *  bold ratio text. Includes a WhatsApp DP (profile photo) card. */
    private fun buildAspectCards() {
        val options = if (mode == MODE_FIT_SOCIAL) listOf(
            Triple("1:1:ig_post", "1:1", "Instagram post (square)"),
            Triple("4:5:ig_feed", "4:5", "Instagram feed (portrait)"),
            Triple("9:16:story", "9:16", "Instagram / TikTok / WhatsApp story"),
            Triple("16:9:cover", "16:9", "Facebook / YouTube cover"),
            Triple("1:1:fb_profile", "1:1", "Facebook profile"),
            Triple("1:1:wa_dp", "1:1", "WhatsApp DP (profile photo)")
        ) else listOf(
            Triple("1:1", "1:1", "Square"),
            Triple("4:5", "4:5", "Portrait 4:5"),
            Triple("9:16", "9:16", "Story 9:16"),
            Triple("16:9", "16:9", "Landscape 16:9"),
            Triple("3:2", "3:2", "Photo 3:2")
        )
        // FIT keeps its 1:1 default; FIT_SOCIAL starts with nothing selected.
        if (mode == MODE_FIT && selectedCardTag == null) selectedCardTag = "1:1"
        // Fit to social media: the 6 cards SHARE the screen height
        // (weight) — no empty space, no gap below the last one, no
        // card cut off (v0.1.124; the v0.1.123 fixed-height formula
        // left a big gap above Back on tall phones). Each card's
        // aspect frame shows the ACTUAL photo cropped to that aspect.
        if (mode == MODE_FIT_SOCIAL) buildAspectPreviews(options)
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
            // text + the exact platform logo per card (vector
            // drawable, drawn in code — no downloaded assets), on
            // its real brand background (Instagram gradient,
            // Facebook blue, WhatsApp green, YouTube's red rounded
            // rectangle — all the same 40dp badge size, so no logo
            // looks smaller than the others).
            centerText = mode == MODE_FIT_SOCIAL,
            badgeProvider = if (mode == MODE_FIT_SOCIAL) { tag ->
                when (tag) {
                    "1:1:ig_post", "4:5:ig_feed", "9:16:story" ->
                        R.drawable.ic_logo_instagram to instagramBadgeBackground()
                    "16:9:cover" ->
                        R.drawable.ic_logo_youtube_play to youtubeBadgeBackground()
                    "1:1:fb_profile" ->
                        R.drawable.ic_logo_facebook to brandCircleBackground(0xFF1877F2.toInt())
                    "1:1:wa_dp" ->
                        R.drawable.ic_logo_whatsapp to brandCircleBackground(0xFF25D366.toInt())
                    else -> null
                }
            } else null,
            // Both Fit and Fit-to-social share one screen
            // (v0.1.125) — no scroll, no gap, per the no-scroll rule.
            weightCards = true,
            previewProvider = if (mode == MODE_FIT_SOCIAL) { tag ->
                aspectPreviews[tag]
            } else null
        )
    }

    /** Small preview bitmaps for the Fit-to-social cards: the actual
     *  photo centre-cropped to each aspect, downscaled (~96dp).
     *  Computed synchronously from a small decode — fast. */
    private var aspectPreviews: Map<String, android.graphics.Bitmap> = emptyMap()

    private fun buildAspectPreviews(options: List<Triple<String, String, String>>) {
        val input = inputFile ?: return
        val map = mutableMapOf<String, android.graphics.Bitmap>()
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(input.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > 400) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(input.absolutePath, opts) ?: return
            val density = resources.displayMetrics.density
            for ((tag, _, _) in options) {
                val parts = tag.split(":")
                val w = parts.getOrNull(0)?.toFloatOrNull() ?: continue
                val h = parts.getOrNull(1)?.toFloatOrNull() ?: continue
                if (w <= 0 || h <= 0) continue
                val aspect = w / h
                val bw = bmp.width
                val bh = bmp.height
                val cropW: Int
                val cropH: Int
                if (bw / aspect <= bh) {
                    cropW = bw
                    cropH = (bw / aspect).toInt()
                } else {
                    cropH = bh
                    cropW = (bh * aspect).toInt()
                }
                val x = (bw - cropW) / 2
                val y = (bh - cropH) / 2
                val cropped = android.graphics.Bitmap.createBitmap(
                    bmp, x, y,
                    cropW.coerceAtLeast(1), cropH.coerceAtLeast(1)
                )
                val targetW = (96 * density).toInt()
                val targetH = (targetW / aspect).toInt().coerceAtLeast(1)
                map[tag] = android.graphics.Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
                cropped.recycle()
            }
            bmp.recycle()
        } catch (_: Throwable) {
            // Previews are a bonus — the cards still work with
            // empty outline frames.
        }
        aspectPreviews = map
    }

    /** A solid brand-colour circle, used behind a platform logo. */
    private fun brandCircleBackground(color: Int): android.graphics.drawable.Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** Instagram's REAL badge (v0.1.125): the official 5-stop
     *  gradient — yellow → orange → pink → purple → blue,
     *  bottom-left to top-right, as on the real logo. The old
     *  3-stop purple→pink→orange read as flat pink at 40dp. */
    private fun instagramBadgeBackground(): android.graphics.drawable.Drawable =
        GradientDrawable(
            GradientDrawable.Orientation.BL_TR,
            intArrayOf(
                0xFFFEDA75.toInt(), 0xFFFA7E1E.toInt(), 0xFFD62976.toInt(),
                0xFF962FBF.toInt(), 0xFF4F5BD5.toInt()
            )
        ).apply { shape = GradientDrawable.OVAL }

    /** YouTube's badge: the red rounded rectangle, filled to the
     *  same 40dp footprint as the other (circular) badges, with
     *  the white play triangle on top — so it no longer looks
     *  smaller than the Instagram / Facebook / WhatsApp badges. */
    private fun youtubeBadgeBackground(): android.graphics.drawable.Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFFFF0000.toInt())
            cornerRadius = 8f * resources.displayMetrics.density
        }

    /** Convert: premium format cards. Each card says what the
     *  format IS, then a teal "Works on" line — clearly where it
     *  works/plays, including the honest limits (TIFF does NOT
     *  open in Android Gallery — desktop viewers only; WebP/AVIF
     *  are NOT for Govt forms). Includes GIF (FFmpeg, new in
     *  v0.1.123). HEIC is NOT an output — this FFmpeg build has
     *  no HEIF muxer — but HEIC photos CAN be converted FROM
     *  (decoded with FFmpeg) to any format here. */
    private fun buildFormatCards() {
        // Short subtitles (v0.1.125): every card is title + 2
        // short lines, each ≤ 48 chars, so the "Works on" line
        // never wraps — 7 cards × ~72dp fit one screen by weight.
        // The v0.1.124 long lines wrapped and the 7th card went
        // below the fold.
        val options = listOf(
            Triple(
                "jpg", "JPG",
                "Smallest. Great quality. Govt forms OK.\nWorks on: Android, iPhone, Windows, web."
            ),
            Triple(
                "png", "PNG",
                "Lossless. Best for graphics. Govt forms OK.\nWorks on: Android, iPhone, Windows, web."
            ),
            Triple(
                "webp", "WebP",
                "25–35% smaller than JPG. NOT Govt forms.\nWorks on: WhatsApp, Instagram, Chrome, web."
            ),
            Triple(
                "gif", "GIF",
                "Small, 256 colours. For web & docs.\nWorks on: web, WhatsApp, docs, browsers."
            ),
            Triple(
                "tiff", "TIFF",
                "Print quality. Large files.\nWorks on: desktop only • NOT Android Gallery."
            ),
            Triple(
                "bmp", "BMP",
                "Uncompressed. Very large.\nWorks on: Windows & old apps • NOT for sharing."
            ),
            Triple(
                "avif", "AVIF",
                "Smallest of all. Best per byte.\nWorks on: Android 12+, Chrome • NOT Govt forms."
            )
        )
        if (selectedCardTag == null) selectedCardTag = "jpg"
        // All 7 format cards share ONE screen (v0.1.124): weight
        // cards + compact padding, so nothing scrolls and there is
        // no empty space above Back / Convert.
        buildOptionCards(options, selectedCardTag, onSelect = { tag ->
            selectedCardTag = tag
            renderStep()
        }, weightCards = true, compact = true)
    }

    /** DP Crop state: the outer area of the DP (v0.1.123).
     *  Blur is the main/default — the photo's own blurred colours
     *  fill the corners outside the circle (Circle) or the bands
     *  around the fitted photo (Square). */
    private var dpBackground = ImageCompressor.DpBackground.BLUR
    private var dpBgColor = 0xFFFFFFFF.toInt()
    private var dpCircleIv: ImageView? = null
    private var dpSquareIv: ImageView? = null
    private var dpCircleCaption: TextView? = null
    private var dpSquareCaption: TextView? = null
    private var dpColorRow: LinearLayout? = null
    private val dpBgChips = mutableListOf<TextView>()

    /** DP Crop — the live previews ARE the actions (v0.1.122),
     *  plus the NEW outer-area options (v0.1.123): Blur (default),
     *  Color (pick a colour), Transparent (Circle only). Two LARGE
     *  tappable preview cards side by side — Circle and Square —
     *  each with a bold label and its format underneath. Tapping
     *  either opens the professional adjust step (v0.1.124):
     *  pinch/drag to frame the photo inside the shape, pick the
     *  outer area, then Save. The preview content is the actual
     *  input photo. */
    private fun buildDpCards() {
        // The default is ALWAYS Blur + White (v0.1.124): the screen
        // was landing on Color + Black, and black must never be the
        // default outer area.
        dpBackground = ImageCompressor.DpBackground.BLUR
        dpBgColor = 0xFFFFFFFF.toInt()
        val container = findViewById<LinearLayout>(R.id.cardsContainer)
        container.removeAllViews()
        container.visibility = View.VISIBLE
        val density = resources.displayMetrics.density
        // Previews as big as the screen allows: half the width
        // minus padding and the gap between the two cards.
        val previewSizePx = (resources.displayMetrics.widthPixels / 2 -
            (32 * density).toInt()).coerceAtLeast((120 * density).toInt())
        val ivCircle = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(previewSizePx, previewSizePx)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = checkerboardDrawable()
        }
        val ivSquare = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(previewSizePx, previewSizePx)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(getColor(R.color.hakunaa_outline))
        }
        dpCircleIv = ivCircle
        dpSquareIv = ivSquare
        // One big tappable preview card: the preview itself, a bold
        // label, and the format underneath. Tapping it crops & saves
        // that shape — the preview IS the button.
        fun previewCard(
            iv: ImageView,
            label: String,
            caption: TextView,
            circle: Boolean
        ): View {
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 16f * density
                cardElevation = 2f * density
                strokeWidth = (2 * density).toInt()
                strokeColor = getColor(R.color.hakunaa_teal)
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            inner.addView(iv)
            inner.addView(TextView(this).apply {
                text = label
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
                gravity = Gravity.CENTER
                setPadding(0, (10 * density).toInt(), 0, 0)
            })
            caption.apply {
                textSize = 12f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                gravity = Gravity.CENTER
                setPadding(
                    (8 * density).toInt(), (2 * density).toInt(),
                    (8 * density).toInt(), (12 * density).toInt()
                )
            }
            inner.addView(caption)
            card.addView(inner)
            card.setOnClickListener {
                selectedCardTag = if (circle) "circle" else "square"
                // BOTH shapes open the professional adjust step
                // (v0.1.124): pinch/drag to frame the photo, pick the
                // outer area, then Save. Square used to save
                // immediately — no adjust at all.
                launchDpAdjust(circle)
            }
            return card
        }
        val heading = TextView(this).apply {
            text = "Live preview"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding(0, (4 * density).toInt(), 0, (12 * density).toInt())
        }
        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val circleCell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginEnd = (6 * density).toInt() }
        }
        dpCircleCaption = TextView(this)
        circleCell.addView(
            previewCard(ivCircle, "Circle", dpCircleCaption!!, true)
        )
        val squareCell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = (6 * density).toInt() }
        }
        dpSquareCaption = TextView(this)
        squareCell.addView(
            previewCard(ivSquare, "Square", dpSquareCaption!!, false)
        )
        previewRow.addView(circleCell)
        previewRow.addView(squareCell)
        // --- Outer area (v0.1.123): Blur / Color / Transparent.
        val outerLabel = TextView(this).apply {
            text = "Outer area"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding(0, (20 * density).toInt(), 0, (8 * density).toInt())
        }
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dpBgChips.clear()
        val chipDefs = listOf(
            ImageCompressor.DpBackground.BLUR to "Blur",
            ImageCompressor.DpBackground.COLOR to "Color",
            ImageCompressor.DpBackground.TRANSPARENT to "Transparent (Circle only)"
        )
        for ((bg, label) in chipDefs) {
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(
                    (14 * density).toInt(), (10 * density).toInt(),
                    (14 * density).toInt(), (10 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
            }
            chip.setOnClickListener {
                dpBackground = bg
                updateDpChips()
                renderDpPreviews()
            }
            dpBgChips.add(chip)
            chipsRow.addView(chip)
        }
        // Colour swatches — only shown when Color is picked.
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        dpColorRow = colorRow
        val colors = listOf(
            0xFFFFFFFF.toInt() to "White",
            0xFF111827.toInt() to "Black",
            0xFF00BFA5.toInt() to "Teal",
            0xFFE1306C.toInt() to "Pink",
            0xFF1877F2.toInt() to "Blue",
            0xFFF77737.toInt() to "Orange"
        )
        for ((color, name) in colors) {
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(
                        (2 * density).toInt(),
                        if (color == dpBgColor) getColor(R.color.hakunaa_teal)
                        else 0xFFD0D5DD.toInt()
                    )
                }
                contentDescription = name
                layoutParams = LinearLayout.LayoutParams(
                    (40 * density).toInt(), (40 * density).toInt()
                ).apply {
                    marginStart = (6 * density).toInt()
                    marginEnd = (6 * density).toInt()
                }
                tag = color
            }
            swatch.setOnClickListener {
                dpBgColor = color
                updateDpChips()
                renderDpPreviews()
            }
            colorRow.addView(swatch)
        }
        val hint = TextView(this).apply {
            text = "Tap a preview to adjust & save"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        val tip = TextView(this).apply {
            text = "Tip: Circle works best for WhatsApp & Instagram profile photos.\n" +
                "Blur is the default outer area — your photo's own colours fill the corners."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            setPadding(
                (16 * density).toInt(), (8 * density).toInt(),
                (16 * density).toInt(), 0
            )
        }
        container.addView(heading)
        container.addView(previewRow)
        container.addView(outerLabel)
        container.addView(chipsRow)
        container.addView(colorRow)
        container.addView(hint)
        container.addView(tip)
        updateDpChips()
        renderDpPreviews()
    }

    /** Paint the Outer-area chips + colour swatches for the current
     *  selection. The colour row only shows when Color is picked. */
    private fun updateDpChips() {
        val density = resources.displayMetrics.density
        val defs = listOf(
            ImageCompressor.DpBackground.BLUR,
            ImageCompressor.DpBackground.COLOR,
            ImageCompressor.DpBackground.TRANSPARENT
        )
        for ((i, chip) in dpBgChips.withIndex()) {
            val selected = defs[i] == dpBackground
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
        dpColorRow?.visibility =
            if (dpBackground == ImageCompressor.DpBackground.COLOR) View.VISIBLE else View.GONE
        val colorRow = dpColorRow
        if (colorRow != null) {
            for (i in 0 until colorRow.childCount) {
                val swatch = colorRow.getChildAt(i)
                val color = swatch.tag as? Int ?: continue
                swatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(
                        (2 * density).toInt(),
                        if (color == dpBgColor) getColor(R.color.hakunaa_teal)
                        else 0xFFD0D5DD.toInt()
                    )
                }
            }
        }
    }

    /** Render both DP previews with the CURRENT outer-area choice.
     *  Captions follow the choice too. */
    private fun renderDpPreviews() {
        val input = inputFile ?: return
        val ivCircle = dpCircleIv
        val ivSquare = dpSquareIv
        dpCircleCaption?.text = when (dpBackground) {
            ImageCompressor.DpBackground.BLUR -> "JPG • blurred outside"
            ImageCompressor.DpBackground.COLOR -> "JPG • coloured outside"
            ImageCompressor.DpBackground.TRANSPARENT -> "PNG • transparent outside"
        }
        dpSquareCaption?.text = when (dpBackground) {
            ImageCompressor.DpBackground.BLUR -> "JPG • blurred outside"
            ImageCompressor.DpBackground.COLOR -> "JPG • coloured outside"
            ImageCompressor.DpBackground.TRANSPARENT -> "JPG • blurred outside"
        }
        Thread {
            try {
                val ts = System.currentTimeMillis()
                val circleExt =
                    if (dpBackground == ImageCompressor.DpBackground.TRANSPARENT) "png" else "jpg"
                val circleFile = File(cacheDir, "hakunaa_dp_circle_$ts.$circleExt")
                val squareFile = File(cacheDir, "hakunaa_dp_square_$ts.jpg")
                val circleOk = ImageCompressor.dpCircle(input, circleFile, dpBackground, dpBgColor)
                val squareOk = ImageCompressor.dpSquare(input, squareFile, dpBackground, dpBgColor)
                val circleBmp = if (circleOk) BitmapFactory.decodeFile(circleFile.absolutePath) else null
                val squareBmp = if (squareOk) BitmapFactory.decodeFile(squareFile.absolutePath) else null
                runOnUiThread {
                    circleBmp?.let { ivCircle?.setImageBitmap(it) }
                    squareBmp?.let { ivSquare?.setImageBitmap(it) }
                }
            } catch (_: Throwable) {
                // Previews are a bonus — the preview cards still work.
            }
        }.start()
    }

    /** Open the professional DP adjust step (v0.1.124): pinch/drag
     *  to frame the photo inside the shape, pick the outer area,
     *  then Save. Used for BOTH Circle and Square. */
    private fun launchDpAdjust(circle: Boolean) {
        val uri = inputUriString ?: return
        val intent = android.content.Intent(this, DpAdjustActivity::class.java)
        intent.putExtra(DpAdjustActivity.EXTRA_IMAGE_URI, uri)
        intent.putExtra(DpAdjustActivity.EXTRA_CIRCLE, circle)
        intent.putExtra(DpAdjustActivity.EXTRA_DP_BG, dpBackground.name)
        intent.putExtra(DpAdjustActivity.EXTRA_DP_COLOR, dpBgColor)
        startActivity(intent)
    }

    /** Open the crop preview with an aspect ratio locked (and
     *  optionally a circle frame for DP Circle). DP Crop passes
     *  its outer-area choice too, so the saved DP keeps it. */
    private fun launchCropActivity(
        aspectW: Int,
        aspectH: Int,
        circle: Boolean,
        dpBackground: ImageCompressor.DpBackground? = null,
        dpBgColor: Int = 0xFFFFFFFF.toInt()
    ) {
        val uri = inputUriString ?: return
        val intent = android.content.Intent(this, ImageCropActivity::class.java)
        intent.putExtra(ImageCropActivity.EXTRA_IMAGE_URI, uri)
        intent.putExtra(ImageCropActivity.EXTRA_ASPECT_W, aspectW)
        intent.putExtra(ImageCropActivity.EXTRA_ASPECT_H, aspectH)
        intent.putExtra(ImageCropActivity.EXTRA_CIRCLE, circle)
        if (dpBackground != null) {
            intent.putExtra(ImageCropActivity.EXTRA_DP_BG, dpBackground.name)
            intent.putExtra(ImageCropActivity.EXTRA_DP_COLOR, dpBgColor)
        }
        startActivity(intent)
    }

    /** Small light-grey checkerboard tile, repeated — the
     *  professional way to show transparency behind the Circle
     *  DP preview. */
    private fun checkerboardDrawable(): android.graphics.drawable.BitmapDrawable {
        val tile = (8 * resources.displayMetrics.density).toInt().coerceAtLeast(4)
        val bmp = android.graphics.Bitmap.createBitmap(
            tile * 2, tile * 2, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bmp)
        val light = android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt() }
        val grey = android.graphics.Paint().apply { color = 0xFFE8EAED.toInt() }
        canvas.drawRect(0f, 0f, (tile * 2).toFloat(), (tile * 2).toFloat(), light)
        canvas.drawRect(0f, 0f, tile.toFloat(), tile.toFloat(), grey)
        canvas.drawRect(
            tile.toFloat(), tile.toFloat(),
            (tile * 2).toFloat(), (tile * 2).toFloat(), grey
        )
        return android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
            setTileModeXY(
                android.graphics.Shader.TileMode.REPEAT,
                android.graphics.Shader.TileMode.REPEAT
            )
        }
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
    /** Leave dialog — professional redesign (v0.1.125): the old
     *  one was a big amber "!" with a small text Cancel + teal
     *  Leave right-aligned and lots of empty space. Now: a small
     *  calm teal-tinted "!" next to the title, a short message,
     *  and two EQUAL full-width buttons (Cancel outlined, Leave
     *  filled teal) — compact, balanced, on-brand. Same behaviour:
     *  Cancel stays, Leave discards and exits. */
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
                (20 * density).toInt(), (20 * density).toInt(),
                (20 * density).toInt(), (20 * density).toInt()
            )
        }
        // Icon + title on one row — compact, no big warning circle.
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(this).apply {
            text = "!"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.hakunaa_teal))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE0F2F1.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                (40 * density).toInt(), (40 * density).toInt()
            )
        }
        val title = TextView(this).apply {
            text = "Leave this tool?"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (12 * density).toInt() }
        }
        headerRow.addView(icon)
        headerRow.addView(title)
        val message = TextView(this).apply {
            text = "Your work will be discarded."
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        // Two equal buttons, full width — Cancel outlined, Leave filled.
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (20 * density).toInt(), 0, 0)
        }
        val cancelBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Cancel"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_teal))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
            strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                0, (48 * density).toInt(), 1f
            ).apply { marginEnd = (4 * density).toInt() }
        }
        val leaveBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Leave"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(android.R.color.white))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                0, (48 * density).toInt(), 1f
            ).apply { marginStart = (4 * density).toInt() }
        }
        cancelBtn.setOnClickListener { dialog.dismiss() }
        leaveBtn.setOnClickListener { dialog.dismiss(); finish() }
        buttons.addView(cancelBtn)
        buttons.addView(leaveBtn)
        inner.addView(headerRow)
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

    /** "Compressing…" animation (v0.1.124): a spinning progress
     *  indicator + text in the results area while a compress
     *  computes — the screen must NEVER sit empty (WebP took ~1
     *  minute and the screen stayed blank the whole time). */
    private fun showCompressingProgress(title: String = "Compressing…") {
        val container = findViewById<LinearLayout>(R.id.resultsContainer)
        container.removeAllViews()
        container.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tvStatus).text = ""
        val density = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (48 * density).toInt(), 0, (48 * density).toInt())
        }
        val spinner = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            layoutParams = LinearLayout.LayoutParams(
                (48 * density).toInt(), (48 * density).toInt()
            )
        }
        box.addView(spinner)
        box.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = "This can take a moment for large photos"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, (6 * density).toInt(), 0, 0)
        })
        container.addView(box)
    }

    private fun hideCompressingProgress() {
        findViewById<LinearLayout>(R.id.resultsContainer).visibility = View.GONE
    }

    /** Smart Compress: try all levels, show every result, user picks one. */
    private fun runSmartCompress() {
        val input = inputFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Working…"
        showCompressingProgress()
        Thread {
            try {
                // BOTH resolution variations (v0.1.126): the small-file
                // ladder (downscaled 2048 — the old build's super
                // results, shown by default) and the full-size ladder
                // (10000). Small encodes are fast (1 MP); the full
                // ladder is the same work v0.1.125 already did.
                val smallBase = File(cacheDir, "hakunaa_smart_s_${System.currentTimeMillis()}")
                val fullBase = File(cacheDir, "hakunaa_smart_f_${System.currentTimeMillis()}")
                val smallResults = ImageCompressor.compressAllLevels(input, smallBase, 2048)
                val fullResults = ImageCompressor.compressAllLevels(input, fullBase, 10000)
                // The small ladder is the default view; if it is empty
                // (photo already tiny) fall back to the full ladder.
                val results = if (smallResults.isNotEmpty()) smallResults else fullResults
                runOnUiThread {
                    if (results.isEmpty()) {
                        hideCompressingProgress()
                        status.text = "Already optimized — even the strongest settings couldn't shrink this image."
                    } else {
                        webpLazyResults = null
                        smartSmallResults = smallResults
                        smartFullResults = fullResults
                        smartSizeSmall = smallResults.isNotEmpty()
                        smartResults = results
                        step = 1
                        renderStep()
                        showSmartResults(results)
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    hideCompressingProgress()
                    status.text = "Failed — try another image"
                }
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
                            "gif" -> ImageCompressor.Format.GIF
                            "tiff" -> ImageCompressor.Format.TIFF
                            "bmp" -> ImageCompressor.Format.BMP
                            "avif" -> ImageCompressor.Format.AVIF
                            else -> ImageCompressor.Format.JPEG
                        }
                        convertFormatUsed = format
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
                        // The saved file gets its REAL MIME type
                        // (v0.1.123 fix — TIFF/AVIF were saved as
                        // "image/jpeg" and Gallery could not open
                        // them). Every save shows the professional
                        // save popup (v0.1.124).
                        val fmt = if (mode == MODE_CONVERT) convertFormatUsed
                        else ImageCompressor.formatOf(out)
                        val saved = MediaStoreSaver.save(
                            this, out, MediaStoreSaver.Kind.IMAGE, out.name, fmt.mime
                        )
                        findViewById<TextView>(R.id.tvResult).text =
                            "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(after)} " +
                                "(${FileUtils.savedPercent(inputSize, after)})"
                        status.text = ""
                        if (saved != null) showSavedDialog(out, saved, fmt)
                        else status.text = "Done, but couldn't save to Pictures — please try again"
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
    /** Smart's two resolution variations (v0.1.126): the small-file
     *  ladder (downscaled — the old build's super results) and the
     *  full-size ladder. The switch above the results flips between
     *  them; Small file is the default. */
    private var smartSmallResults: List<ImageCompressor.LevelResult> = emptyList()
    private var smartFullResults: List<ImageCompressor.LevelResult> = emptyList()
    private var smartSizeSmall: Boolean = true
    private var qualityChoice = 85
    /** The format the Convert flow just saved (for the save popup). */
    private var convertFormatUsed: ImageCompressor.Format = ImageCompressor.Format.JPEG
    /** Compress sub-mode: 0=none, 1=Quality, 2=Target size, 3=HD Compress. */
    private var compressMode = 0

    /** True when the Compress flow is on its final step (Next = Done). */
    private fun isCompressDoneStep(): Boolean = when (compressMode) {
        1 -> step == 3
        2, 3 -> step == 2
        else -> false
    }

    /** The 3 Compress modes as rows that FILL the screen (v0.1.124):
     *  the container takes ALL the space below the big photo, and
     *  the 3 rows share it — so there is never an empty gap before
     *  Back, on any phone. (v0.1.123 made them fixed 64dp rows and
     *  left a huge gap; v0.1.122's full-height cards were too big
     *  and empty.) Each row: a 40dp teal-tint icon circle + title +
     *  subtitle, vertically centred, with a chevron. */
    private fun buildCompressModeCards() {
        val container = findViewById<LinearLayout>(R.id.modeContainer)
        container.removeAllViews()
        // Wrap content (v0.1.125): the photo above is the flexible
        // part and takes the spare height — these rows are a fixed
        // comfortable 88dp each, never stretched, never gapped.
        (container.layoutParams as LinearLayout.LayoutParams).apply {
            height = LinearLayout.LayoutParams.WRAP_CONTENT
            weight = 0f
        }
        val density = resources.displayMetrics.density
        val gapPx = (8 * density).toInt()
        val modes = listOf(
            Triple(R.drawable.ic_tune, "Quality", "Choose a quality %") to 1,
            Triple(R.drawable.ic_text_resize, "Target size", "Enter MB or KB") to 2,
            Triple(R.drawable.ic_bolt, "HD Compress", "Only size reduces, 0% quality loss") to 3
        )
        for ((index, entry) in modes.withIndex()) {
            val (info, mode) = entry
            val (icon, title, subtitle) = info
            val card = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 12f * density
                cardElevation = 2f * density
                setCardBackgroundColor(getColor(R.color.hakunaa_surface))
                minimumHeight = (72 * density).toInt()
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * density).toInt(), (12 * density).toInt(),
                    (16 * density).toInt(), (12 * density).toInt()
                )
            }
            val iconView = ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(getColor(R.color.hakunaa_teal))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFE0F2F1.toInt())
                }
                setPadding(
                    (10 * density).toInt(), (10 * density).toInt(),
                    (10 * density).toInt(), (10 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    (40 * density).toInt(), (40 * density).toInt()
                ).apply { marginEnd = (12 * density).toInt() }
            }
            inner.addView(iconView)
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            textCol.addView(TextView(this).apply {
                text = title
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
            })
            textCol.addView(TextView(this).apply {
                text = subtitle
                textSize = 13f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
            })
            inner.addView(textCol)
            inner.addView(TextView(this).apply {
                text = "›"
                textSize = 24f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_teal))
                gravity = Gravity.CENTER_VERTICAL
                setPadding((8 * density).toInt(), 0, 0, 0)
            })
            card.addView(inner)
            // Fixed comfortable height (v0.1.125): the rows do NOT
            // stretch — the photo above takes the spare space.
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (88 * density).toInt()
            )
            if (index > 0) params.topMargin = gapPx
            card.layoutParams = params
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
        showCompressingProgress()
        val nextBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNext)
        nextBtn.isEnabled = false
        Thread {
            try {
                val base = File(cacheDir, "hakunaa_hd_${System.currentTimeMillis()}")
                // HD ladder (v0.1.121 fix): JPEG 95 then 90 at full
                // resolution — 95 alone almost never shrinks a camera
                // photo, which is why HD always said "Already optimized".
                val results = ImageCompressor.compressHd(input, base)
                runOnUiThread {
                    nextBtn.isEnabled = true
                    if (results.isNotEmpty()) {
                        webpLazyResults = null
                        // Better Compress for HD uses quality 95.
                        qualityChoice = 95
                        smartResults = results
                        step = 2
                        renderStep()
                        status.text = "HD done — View, then Save"
                        // Result screen (not a silent save): Preview
                        // button + Save selected. allowWebp = true because a
                        // WebP input stays WebP at quality 95.
                        showSmartResults(results, allowWebp = true)
                    } else {
                        hideCompressingProgress()
                        status.text = "Already optimized — this photo is already small"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    hideCompressingProgress()
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
        showCompressingProgress()
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
                        // Result screen (not a silent save): one clear
                        // card + full-screen Preview + Save.
                        val format = ImageCompressor.formatOf(input)
                        val saving = ((inputSize - result.length()) * 100 / inputSize).toInt()
                        showSmartResults(
                            listOf(
                                ImageCompressor.LevelResult(
                                    "Target ${FileUtils.formatSize(targetBytes)}",
                                    format, 0, result, result.length(), saving
                                )
                            )
                        )
                    } else {
                        hideCompressingProgress()
                        try { outFile.delete() } catch (_: Throwable) { }
                        status.text = "Could not reach that size — try a bigger target"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    hideCompressingProgress()
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

    /** Quality % -> Smart results at the SMALL-file downscale
     *  (v0.1.126 — restores the old build): no resolution step,
     *  the chosen quality compresses the downscaled photo, so the
     *  file is small like the old build. Full resolution is HD's
     *  job, not Quality's. */
    private fun runQualitySmartResults() {
        val input = inputFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Working…"
        showCompressingProgress()
        val nextBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNext)
        nextBtn.isEnabled = false
        Thread {
            try {
                val base = File(cacheDir, "hakunaa_smart_${System.currentTimeMillis()}")
                val results = ImageCompressor.compressAtQuality(input, base, qualityChoice, 2048)
                runOnUiThread {
                    nextBtn.isEnabled = true
                    if (results.isEmpty()) {
                        hideCompressingProgress()
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
                    hideCompressingProgress()
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
        // Rows share the screen height (v0.1.125): 7 rows fill
        // one screen, no gap below the last one, no scroll.
        (container.layoutParams as LinearLayout.LayoutParams).apply {
            height = 0
            weight = 1f
        }
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
            // Share the container height (min 48dp per row).
            card.minimumHeight = rowHeightPx
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
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
        // Quick-pick chips for common exam sizes — 2 per row, so a
        // size like "1080×1080" never wraps mid-number (v0.1.122).
        // Each chip has its size on one line and its label below.
        val chipsGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        chipsGrid.addView(TextView(this).apply {
            text = "Common sizes"
            textSize = 12f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            setPadding(0, 0, 0, (8 * density).toInt())
        })
        val examSizes = listOf(
            Triple(600, 600, "Passport"),
            Triple(800, 800, "Square"),
            Triple(1080, 1080, "Square"),
            Triple(1080, 1350, "Portrait")
        )
        fun makeChip(w: Int, h: Int, label: String): View {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(getColor(R.color.hakunaa_surface))
                    setStroke((1 * density).toInt(), getColor(R.color.hakunaa_teal))
                    cornerRadius = 12f * density
                }
                setPadding(
                    (8 * density).toInt(), (10 * density).toInt(),
                    (8 * density).toInt(), (10 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    val m = (4 * density).toInt()
                    marginStart = m
                    marginEnd = m
                }
            }
            chip.addView(TextView(this).apply {
                text = "$w × $h"
                gravity = Gravity.CENTER
                textSize = 13f
                maxLines = 1
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_teal))
            })
            chip.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 11f
                setTextColor(getColor(R.color.hakunaa_teal))
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
            chip.setOnClickListener {
                etW.setText(w.toString())
                etH.setText(h.toString())
            }
            return chip
        }
        for (row in examSizes.chunked(2)) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for ((w, h, label) in row) rowLayout.addView(makeChip(w, h, label))
            chipsGrid.addView(rowLayout)
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
        inner.addView(chipsGrid)
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
        val saved = MediaStoreSaver.save(
            this, outFile, MediaStoreSaver.Kind.IMAGE, outFile.name,
            ImageCompressor.formatOf(outFile).mime
        )
        if (saved != null) {
            status.text = ""
            SavedDialog.show(this, outFile, saved, ImageCompressor.formatOf(outFile), inputFile)
        } else {
            status.text = "Done, but couldn't save to Pictures — please try again"
        }
        findViewById<TextView>(R.id.tvResult).text =
            "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(after)} " +
                "(${FileUtils.savedPercent(inputSize, after)})"
    }

    /** The professional save confirmation — the shared dialog
     *  (see SavedDialog, used on EVERY save since v0.1.124). */
    private fun showSavedDialog(
        outFile: File,
        savedUri: android.net.Uri,
        format: ImageCompressor.Format
    ) {
        SavedDialog.show(this, outFile, savedUri, format, inputFile)
    }

    /** One result as a simple professional card: label + Best badge +
     *  "size • saved N%" on the left, a small View button on the
     *  RIGHT (v0.1.122 — the separate full-width "Preview — full
     *  screen" button is gone; every result shows itself).
     *  Tapping the card SELECTS it ([onSelect]); tapping View
     *  opens the full-screen View + Compare for THAT result
     *  ([onPreview]). Shared by the main results list and the
     *  WebP window. (Per-row SAVE buttons were removed in v0.1.121 —
     *  they made the screen too complex.) */
    private fun buildResultRow(
        r: ImageCompressor.LevelResult,
        showBest: Boolean,
        isSelected: Boolean,
        onPreview: (ImageCompressor.LevelResult) -> Unit,
        onSelect: (ImageCompressor.LevelResult) -> Unit,
        showSmaller: Boolean = false
    ): View {
        val density = resources.displayMetrics.density
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
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
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
        if (showSmaller) {
            // "Smaller" — the smallest file (lowest quality). It is
            // NOT "Best" anymore (v0.1.124): Best is the quality +
            // size balanced one.
            val smaller = TextView(this).apply {
                text = "  Smaller  "
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(getColor(R.color.hakunaa_surface))
                    setStroke((1 * density).toInt(), getColor(R.color.hakunaa_text_secondary))
                    cornerRadius = 4f * density
                }
                setPadding(12, 4, 12, 4)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = (12 * density).toInt()
            line1.addView(smaller, lp)
        }
        // One clean line, v0.1.123 style (v0.1.125): "size • saved N%"
        // in small secondary text. The v0.1.124 bold-teal saved text
        // wrapped to 2 lines and looked messy — the user prefers
        // this old compact style, so it is back, exactly as it was.
        val line2 = TextView(this).apply {
            text = "${FileUtils.formatSize(r.size)}  •  saved ${r.savingPercent}%"
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
        }
        textCol.addView(line1)
        textCol.addView(line2)
        inner.addView(textCol)
        // Per-result View, right side — opens the full-screen
        // View + Compare for THIS result.
        val previewBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "View"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_teal))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
            strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            strokeWidth = (1 * density).toInt()
            cornerRadius = (10 * density).toInt()
            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (40 * density).toInt()
            ).apply { marginStart = (12 * density).toInt() }
        }
        previewBtn.setOnClickListener { onPreview(r) }
        inner.addView(previewBtn)
        card.addView(inner)
        card.setOnClickListener { onSelect(r) }
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = (8 * density).toInt()
        card.layoutParams = params
        return card
    }

    /** The main results list uses the shared row: tapping the card
     *  selects it, Preview opens the full-screen dialog for that
     *  result (and selects it, so "Save selected" matches). "Best"
     *  is the quality + size BALANCED result, "Smaller" is the
     *  smallest file (v0.1.124). */
    private fun buildSmartResultCard(r: ImageCompressor.LevelResult): View {
        val best = ImageCompressor.balancedBest(smartDisplayed)
        val smallest = smartDisplayed.minByOrNull { it.size }
        return buildResultRow(
            r, r == best, r == selectedSmartResult,
            onPreview = { selectSmartResult(it); openFullPreviewFor(it) },
            onSelect = { selectSmartResult(it) },
            showSmaller = r == smallest && r != best
        )
    }

    /** Rebuild the result cards from [smartDisplayed]. The cards
     *  SHARE the rows height equally (v0.1.125, weight 1 each):
     *  the rows fill the screen, so no card is ever cut off and
     *  there is no empty gap below the last one. */
    private fun refreshSmartRows() {
        val density = resources.displayMetrics.density
        val container = findViewById<LinearLayout>(R.id.resultsContainer)
        val rowsContainer = container.findViewWithTag<LinearLayout>("smart_rows")
            ?: return
        rowsContainer.removeAllViews()
        for (r in smartDisplayed) {
            val card = buildSmartResultCard(r)
            card.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = (8 * density).toInt() }
            rowsContainer.addView(card)
        }
    }

    /** Select a Smart result: highlight its card. Saving happens
     *  only via the "Save selected" button (or Save in Preview). */
    private fun selectSmartResult(r: ImageCompressor.LevelResult) {
        selectedSmartResult = r
        refreshSmartRows()
    }

    /** Open the full-screen Preview + Compare dialog for ONE
     *  result (v0.1.121 redesign, v0.1.122: per-result button). */
    private fun openFullPreviewFor(result: ImageCompressor.LevelResult) {
        val input = inputFile ?: return
        FullPreviewDialog(
            this, input, result, inputSize,
            ImageCompressor.formatOf(input), inputW, inputH
        ) { saveSmartResult(result) }.show()
    }

    /** "Better Compress with WebP" — a full-screen window
     *  (v0.1.122, renamed v0.1.123) that explains WebP and shows
     *  its results there, instead of appending WebP rows inline
     *  on the result screen.
     *
     *  The window (v0.1.124): the picked photo's details, big and
     *  clear at the top (name, size, format, resolution), then
     *  professional WebP details (what WebP is, where it works
     *  and where it does NOT, and the balanced pick once
     *  computed), then MULTIPLE WebP results (95 / 85 / 70 / 60)
     *  as the same cards as the main screen (per-result Preview
     *  on the right), and a Save button pinned at the bottom.
     *  While WebP computes, a "Compressing WebP…" animation
     *  shows — never an empty screen. WebP is computed LAZILY
     *  on a background thread and cached ([webpLazyResults]). */
    private fun showWebpWindow() {
        val input = inputFile ?: return
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.hakunaa_background))
        }
        // Top bar: X + title.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (8 * density).toInt(), (8 * density).toInt(),
                (16 * density).toInt(), (8 * density).toInt()
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
        close.setOnClickListener { dialog.dismiss() }
        val title = TextView(this).apply {
            text = "Better Compress with WebP"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        topBar.addView(close)
        topBar.addView(title)
        root.addView(topBar)
        // Content — NOT a ScrollView (v0.1.125): the window fills
        // one screen; the 4 result cards share the space by weight.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(
                (16 * density).toInt(), (8 * density).toInt(),
                (16 * density).toInt(), (16 * density).toInt()
            )
        }
        // Image details card (v0.1.124): the picked photo's
        // details, big and clear at the top — name, size, format,
        // resolution.
        val imageCard = com.google.android.material.card.MaterialCardView(this).apply {
            radius = 12f * density
            cardElevation = 2f * density
            setCardBackgroundColor(getColor(R.color.hakunaa_surface))
        }
        val imageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (12 * density).toInt(), (12 * density).toInt(),
                (12 * density).toInt(), (12 * density).toInt()
            )
        }
        val webpThumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (64 * density).toInt(), (64 * density).toInt()
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(getColor(R.color.hakunaa_outline))
                cornerRadius = 8f * density
            }
            clipToOutline = true
        }
        inputFile?.let {
            BitmapFactory.decodeFile(it.absolutePath)?.let { bmp -> webpThumb.setImageBitmap(bmp) }
        }
        imageRow.addView(webpThumb)
        val webpImageText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = (12 * density).toInt() }
        }
        webpImageText.addView(TextView(this).apply {
            text = inputName
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        })
        webpImageText.addView(TextView(this).apply {
            text = "${FileUtils.formatSize(inputSize)} • ${ImageCompressor.formatOf(input).name}\n${inputW} × ${inputH} px"
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            setPadding(0, (4 * density).toInt(), 0, 0)
        })
        imageRow.addView(webpImageText)
        imageCard.addView(imageRow)
        content.addView(imageCard)
        // One short line — the whole WebP story (v0.1.125). The
        // v0.1.124 facts card + Works/NOT lines + Best balance
        // line made the page feel complex; the Best badge on the
        // result card already shows the pick. Clean and simple.
        content.addView(TextView(this).apply {
            text = "WebP — 25–35% smaller than JPG, same quality • NOT for Govt forms"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            setPadding(0, (12 * density).toInt(), 0, 0)
        })
        // "Compressing WebP…" animation (v0.1.124) — never an empty
        // screen while WebP computes (it can take ~1 minute on a
        // large photo).
        val progressBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, (32 * density).toInt(), 0, (16 * density).toInt())
        }
        progressBox.addView(android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            layoutParams = LinearLayout.LayoutParams(
                (48 * density).toInt(), (48 * density).toInt()
            )
        })
        progressBox.addView(TextView(this).apply {
            text = "Compressing WebP…"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        })
        progressBox.addView(TextView(this).apply {
            text = "Large photos can take up to a minute"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, (6 * density).toInt(), 0, 0)
        })
        content.addView(progressBox)
        val status = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        content.addView(status)
        // Rows fill the rest of the window (v0.1.125) — the 4
        // cards share the height, no scroll, no gap above Save.
        val rowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        content.addView(rowsContainer)
        root.addView(content)
        // Bottom bar: Save selected (enabled once a result is picked).
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }
        val saveBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "Save selected"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(android.R.color.white))
            backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            cornerRadius = (12 * density).toInt()
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (56 * density).toInt()
            )
        }
        bottomBar.addView(saveBtn)
        root.addView(bottomBar)
        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.show()
        // This window's own selection (independent of the main list).
        var selectedWebp: ImageCompressor.LevelResult? = null
        var webpRows: List<ImageCompressor.LevelResult> = emptyList()
        fun refreshWebpRows() {
            rowsContainer.removeAllViews()
            val best = ImageCompressor.balancedBest(webpRows)
            val smallest = webpRows.minByOrNull { it.size }
            for (r in webpRows) {
                val card = buildResultRow(
                    r, r == best, r == selectedWebp,
                    onPreview = {
                        selectedWebp = it
                        refreshWebpRows()
                        saveBtn.isEnabled = true
                        openFullPreviewFor(it)
                    },
                    onSelect = {
                        selectedWebp = it
                        refreshWebpRows()
                        saveBtn.isEnabled = true
                    },
                    showSmaller = r == smallest && r != best
                )
                // Cards share the rows height equally (v0.1.125).
                card.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ).apply { topMargin = (8 * density).toInt() }
                rowsContainer.addView(card)
            }
        }
        saveBtn.setOnClickListener {
            selectedWebp?.let {
                dialog.dismiss()
                saveSmartResult(it)
            }
        }
        Thread {
            try {
                val results = webpLazyResults ?: ImageCompressor.compressWebpLevels(
                    input,
                    File(cacheDir, "hakunaa_webp_${System.currentTimeMillis()}")
                ).also { webpLazyResults = it }
                runOnUiThread {
                    progressBox.visibility = View.GONE
                    if (results.isEmpty()) {
                        status.text = "WebP couldn't shrink this photo further."
                    } else {
                        status.text = ""
                        webpRows = results.sortedBy { it.size }
                        val best = ImageCompressor.balancedBest(webpRows)
                        selectedWebp = best
                        saveBtn.isEnabled = true
                        refreshWebpRows()
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    progressBox.visibility = View.GONE
                    status.text = "WebP check failed — try again"
                }
            }
        }.start()
    }

    /** Smart results — SIMPLE and professional (v0.1.121 redesign,
     *  v0.1.122: per-result Preview, Better Compress in its own
     *  window; v0.1.123: the button is pinned just above the
     *  bottom Back/Done buttons, NOT inside the scrolling list;
     *  v0.1.124: NO summary header — it pushed Save into scroll —
     *  and Save is pinned in its own bar):
     *  JPG ONLY by default — WebP lives in the "Better Compress
     *  with WebP" window. Layout: simple selectable result cards
     *  each with its own Preview button on the right, and a
     *  full-width teal "Save selected" button pinned above
     *  Back/Done. The HD result screen also carries its own
     *  one-line explanation, with HD Compress — nowhere else.
     *  [allowWebp] is true only for the HD result screen, whose
     *  single result may be WebP (a WebP input stays WebP at
     *  quality 95). */
    private fun showSmartResults(
        results: List<ImageCompressor.LevelResult>,
        allowWebp: Boolean = false
    ) {
        val container = findViewById<LinearLayout>(R.id.resultsContainer)
        container.removeAllViews()
        // Fill the content area (v0.1.125): the results list takes
        // ALL remaining height — the cards share it by weight, so
        // nothing is cut off and there is no empty gap.
        (container.layoutParams as LinearLayout.LayoutParams).apply {
            height = 0
            weight = 1f
        }
        // A stale "Working…" never lingers under the results.
        findViewById<TextView>(R.id.tvStatus).text = ""
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
        // Default selection is the BALANCED Best (v0.1.124) — not
        // the smallest.
        selectedSmartResult = ImageCompressor.balancedBest(smartDisplayed)

        if (smartDisplayed.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No smaller JPG version for this photo — try Better Compress with WebP."
                textSize = 14f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                setPadding(16, 16, 16, 8)
            })
        } else {
            // (v0.1.124) The "Original → Best • saved %" summary
            // header is REMOVED — the top image details already show
            // the original, and each result card shows its own saved
            // size/%. The header only ate space and pushed Save into
            // scroll.
            // HD's own explanation, WITH the HD result — HD
            // guidance lives on the HD screen, nowhere else.
            if (mode == MODE_COMPRESS && compressMode == 3) {
                container.addView(TextView(this).apply {
                    text = "HD — same photo, same size, 100% quality. " +
                        "Only the file got smaller."
                    textSize = 13f
                    setTextColor(getColor(R.color.hakunaa_text_secondary))
                    setPadding(0, (12 * density).toInt(), 0, 0)
                })
            }

            // Resolution switch — Smart ONLY (v0.1.126): "Small file"
            // (downscaled — the old build's super results, default)
            // vs "Full size". One compact 40dp row, so the 5 cards
            // still share the rest of the screen by weight — no
            // scroll. Quality / HD / Target reuse this screen but
            // have no second variation, so they get no switch.
            if (mode == MODE_SMART && smartFullResults.isNotEmpty()) {
                val switchRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (40 * density).toInt()
                    ).apply { topMargin = (8 * density).toInt() }
                }
                val smallChip = com.google.android.material.button.MaterialButton(this).apply {
                    text = "Small file"
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    cornerRadius = (20 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginEnd = (4 * density).toInt()
                    }
                    if (smartSizeSmall) {
                        setTextColor(getColor(android.R.color.white))
                        backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                        strokeWidth = 0
                    } else {
                        setTextColor(getColor(R.color.hakunaa_teal))
                        backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
                        strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                        strokeWidth = (2 * density).toInt()
                    }
                }
                val fullChip = com.google.android.material.button.MaterialButton(this).apply {
                    text = "Full size"
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    cornerRadius = (20 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginStart = (4 * density).toInt()
                    }
                    if (!smartSizeSmall) {
                        setTextColor(getColor(android.R.color.white))
                        backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                        strokeWidth = 0
                    } else {
                        setTextColor(getColor(R.color.hakunaa_teal))
                        backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
                        strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                        strokeWidth = (2 * density).toInt()
                    }
                }
                smallChip.setOnClickListener {
                    if (!smartSizeSmall && smartSmallResults.isNotEmpty()) {
                        smartSizeSmall = true
                        smartResults = smartSmallResults
                        showSmartResults(smartResults)
                    }
                }
                fullChip.setOnClickListener {
                    if (smartSizeSmall && smartFullResults.isNotEmpty()) {
                        smartSizeSmall = false
                        smartResults = smartFullResults
                        showSmartResults(smartResults)
                    }
                }
                switchRow.addView(smallChip)
                switchRow.addView(fullChip)
                container.addView(switchRow)
            }

            // "Results" section heading, then the selectable cards
            // (each with its own Preview button on the right).
            val rowsLabel = TextView(this).apply {
                text = "Results"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
                setPadding(0, (8 * density).toInt(), 0, (4 * density).toInt())
            }
            container.addView(rowsLabel)
            // Selectable result cards — fill the rest of the list
            // height (v0.1.125), shared equally by the cards.
            val rowsContainer = LinearLayout(this).apply {
                tag = "smart_rows"
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            container.addView(rowsContainer)
            refreshSmartRows()
        }

        // Prominent teal "Save selected" — PINNED just above the
        // bottom Back/Done buttons (v0.1.124), in its own bar below
        // the scrolling results, NOT inside the list: it must never
        // go into scroll. Bold 16sp text on the full-width 56dp
        // button so it reads as the screen's primary action.
        val saveBar = findViewById<LinearLayout>(R.id.saveBar)
        saveBar.removeAllViews()
        if (smartDisplayed.isNotEmpty()) {
            val saveBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = "Save selected"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(android.R.color.white))
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                cornerRadius = (12 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (56 * density).toInt()
                )
            }
            saveBtn.setOnClickListener {
                selectedSmartResult?.let { saveSmartResult(it) }
            }
            saveBar.addView(saveBtn)
            saveBar.visibility = View.VISIBLE
        } else {
            saveBar.visibility = View.GONE
        }

        // "Better Compress with WebP" sits just above the bottom
        // Back/Done buttons (v0.1.123) — pinned in its own bar
        // below the scrolling results, NOT inside the list. It is
        // the secondary action; the list stays light. Tapping it
        // opens the WebP window (see showWebpWindow()).
        val showMore = (mode == MODE_COMPRESS && (compressMode == 1 || compressMode == 3)) ||
            mode == MODE_SMART
        val bar = findViewById<LinearLayout>(R.id.betterCompressBar)
        bar.removeAllViews()
        if (showMore) {
            val moreBtn = com.google.android.material.button.MaterialButton(this).apply {
                text = "Better Compress with WebP"
                setTextColor(getColor(R.color.hakunaa_teal))
                backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
                strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                strokeWidth = (2 * density).toInt()
                cornerRadius = (12 * density).toInt()
                // Fixed 56dp like Save selected (v0.1.125): all
                // bottom buttons are the same height, perfectly
                // aligned with the same 8dp gaps.
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (56 * density).toInt()
                )
            }
            moreBtn.setOnClickListener { showWebpWindow() }
            bar.addView(moreBtn)
            bar.visibility = View.VISIBLE
        } else {
            bar.visibility = View.GONE
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
                // Save with the ACTUAL format's extension (a WebP
                // input stays a WebP file, not a misnamed .jpg).
                val ext = ImageCompressor.formatOf(input).extension
                val out = File(cacheDir, "hakunaa_clean_${System.currentTimeMillis()}.$ext")
                val ok = ImageCompressor.stripMetadata(input, out)
                runOnUiThread {
                    if (ok && out.exists()) {
                        metadataRemovedList = removed
                        val saved = MediaStoreSaver.save(
                            this, out, MediaStoreSaver.Kind.IMAGE, out.name,
                            ImageCompressor.formatOf(out).mime
                        )
                        if (saved != null) {
                            status.text = ""
                            SavedDialog.show(this, out, saved, ImageCompressor.formatOf(out), input)
                        } else {
                            status.text = "Done, but couldn't save to Pictures — please try again"
                        }
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
        val saved = MediaStoreSaver.save(
            this, r.file, MediaStoreSaver.Kind.IMAGE, r.file.name,
            ImageCompressor.formatOf(r.file).mime
        )
        if (saved != null) {
            // Professional Saved popup on EVERY save (v0.1.124) —
            // not just Convert.
            findViewById<TextView>(R.id.tvStatus).text = ""
            SavedDialog.show(this, r.file, saved, ImageCompressor.formatOf(r.file), inputFile)
        } else {
            findViewById<TextView>(R.id.tvStatus).text =
                "Done, but couldn't save to Pictures — please try again"
        }
        findViewById<TextView>(R.id.tvResult).text =
            "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(r.size)} (${r.savingPercent}% smaller)"
    }
}
