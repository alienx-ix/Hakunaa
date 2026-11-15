package com.hakunaa.app.image

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
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
    }

    private var mode = MODE_COMPRESS
    private var inputFile: File? = null
    private var inputSize: Long = 0
    private var inputW = 0
    private var inputH = 0
    private var step = 0 // Compress has 2 steps: quality, then resolution.

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

        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            if (mode == MODE_COMPRESS && step > 0) {
                step--
                renderStep()
            } else {
                attemptLeave()
            }
        }

        // System Back must step back within the tool (not exit it outright).
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (mode == MODE_COMPRESS && step > 0) {
                    step--
                    renderStep()
                } else {
                    attemptLeave()
                }
            }
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
        findViewById<ImageView>(R.id.btnClose).setOnClickListener { finish() }
    }

    private fun titleForMode(): String = when (mode) {
        MODE_RESIZE -> "Resize"
        MODE_TARGET_SIZE -> "Resize to KB, MB"
        MODE_FIT -> "Fit"
        MODE_FIT_SOCIAL -> "Fit to social media"
        MODE_CONVERT -> "Convert"
        MODE_SMART -> "Smart Compress"
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
        val nextBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNext)
        // Step 0 (3 buttons) and the Quality cards: tapping IS the action, so no Next.
        // Smart Compress: step 0 is working (no Next), step 1 shows results (Done).
        nextBtn.visibility = if ((mode == MODE_COMPRESS && (step == 0 || (step == 1 && compressMode == 1))) ||
            (mode == MODE_SMART && step == 0)) View.GONE else View.VISIBLE
        nextBtn.text = if ((mode == MODE_COMPRESS && isCompressDoneStep()) ||
            (mode == MODE_SMART && step == 1)) "Done" else "Next"
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
                        3 -> {
                            subtitle.text = "Choose a size — quality stays 100%"
                            addOptions(group, listOf(
                                "50" to "Half size (50%)",
                                "1920" to "HD — 1920px longest side",
                                "25" to "Quarter size (25%)"
                            ), 0)
                        }
                    }
                    2 -> when (compressMode) {
                        1 -> {
                            subtitle.text = "Change photo resolution"
                            addResolutionOptions(group)
                        }
                        else -> {
                            subtitle.text = "Compressed — see the result below"
                            group.visibility = View.GONE
                        }
                    }
                    else -> {
                        subtitle.text = "Tap a result to save it — Best is the smallest"
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
                    subtitle.text = "Tap a result to save it — Best is the smallest"
                    group.visibility = View.GONE
                    findViewById<LinearLayout>(R.id.resultsContainer).visibility = View.VISIBLE
                }
            }
            MODE_RESIZE -> {
                subtitle.text = "Change photo resolution"
                addResolutionOptions(group)
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
                val options = if (mode == MODE_FIT_SOCIAL) listOf(
                    "1:1" to "Instagram post (square)",
                    "4:5" to "Instagram feed (portrait)",
                    "9:16" to "Instagram / TikTok story",
                    "16:9" to "Facebook / YouTube cover",
                    "1:1" to "Facebook profile"
                ) else listOf(
                    "1:1" to "Square",
                    "4:5" to "Portrait 4:5",
                    "9:16" to "Story 9:16",
                    "16:9" to "Landscape 16:9",
                    "3:2" to "Photo 3:2"
                )
                addOptions(group, options, 0)
            }
            MODE_CONVERT -> {
                subtitle.text = "Change format to PNG, JPG, WebP"
                addOptions(group, listOf(
                    "jpg" to "JPG — smallest, best for photos",
                    "png" to "PNG — lossless, best for graphics",
                    "webp" to "WebP — smaller than JPG"
                ), 0)
            }
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

    private fun addResolutionOptions(group: RadioGroup) {
        // Resolutions scaled to the image's aspect ratio.
        val ratio = if (inputH > 0) inputW.toFloat() / inputH else 1f
        val presets = listOf(
            "Keep resolution" to null,
            "240x534" to Pair(240, 534),
            "360x800" to Pair(360, 800),
            "480x1068" to Pair(480, 1068),
            "600x1334" to Pair(600, 1334),
            "768x1708" to Pair(768, 1708),
            "1024x2276" to Pair(1024, 2276),
            "1200x2668" to Pair(1200, 2668),
            "1280x2844" to Pair(1280, 2844),
            "1152x2560" to Pair(1152, 2560),
            "1440x3200" to Pair(1440, 3200),
            "3072x6828" to Pair(3072, 6828)
        )
        for ((i, preset) in presets.withIndex()) {
            val radio = RadioButton(this)
            radio.id = View.generateViewId()
            radio.tag = preset.first
            radio.text = preset.first
            radio.isChecked = i == 0
            radio.setPadding(16, 24, 16, 24)
            radio.textSize = 16f
            group.addView(radio)
        }
        // Custom: the image's own resolution.
        val custom = RadioButton(this)
        custom.id = View.generateViewId()
        custom.tag = "Custom: ${inputW}x${inputH}"
        custom.text = "Custom: ${inputW}x${inputH}"
        custom.setPadding(16, 24, 16, 24)
        custom.textSize = 16f
        group.addView(custom)
    }

    private fun selectedTag(): String? {
        val group = findViewById<RadioGroup>(R.id.rgOptions)
        val checked = group.findViewById<RadioButton>(group.checkedRadioButtonId) ?: return null
        return checked.tag as? String
    }

    /** Leaving the tool asks first — the work is discarded on Leave. */
    private fun attemptLeave() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Leave this tool?")
            .setMessage("Your work will be discarded.")
            .setPositiveButton("Leave") { _, _ -> finish() }
            .setNegativeButton("Cancel", null)
            .show()
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
                3 -> runHdCompress()
            }
            return
        }
        if (mode == MODE_COMPRESS && step == 2) {
            if (compressMode == 1) {
                val maxDim = resolutionMaxDimension()
                val quality = qualityChoice
                status.text = "Working…"
                val nextBtn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnNext)
                nextBtn.isEnabled = false
                Thread {
                    try {
                        val base = File(cacheDir, "hakunaa_smart_${System.currentTimeMillis()}")
                        val results = ImageCompressor.compressAtQuality(input, base, quality, maxDim)
                        runOnUiThread {
                            nextBtn.isEnabled = true
                            if (results.isEmpty()) {
                                status.text = "Already optimized — even the strongest settings couldn't shrink this image."
                            } else {
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
                return
            }
            finish()
            return
        }
        if (mode == MODE_COMPRESS && step == 3) {
            finish()
            return
        }
        status.text = "Working…"
        Thread {
            try {
                val out = when (mode) {
                    MODE_RESIZE -> {
                        val resolution = selectedTag()
                        val outFile = File(cacheDir, "hakunaa_resized_${System.currentTimeMillis()}.jpg")
                        val ok = if (resolution == null || resolution == "Keep resolution" || resolution.startsWith("Custom")) {
                            input.copyTo(outFile, overwrite = true)
                            true
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
                        val aspect = selectedTag() ?: "1:1"
                        val parts = aspect.split(":")
                        val w = parts.getOrNull(0)?.toIntOrNull() ?: 1
                        val h = parts.getOrNull(1)?.toIntOrNull() ?: 1
                        val outFile = File(cacheDir, "hakunaa_fit_${System.currentTimeMillis()}.jpg")
                        if (ImageCompressor.fitToAspect(input, outFile, w, h)) outFile else null
                    }
                    MODE_CONVERT -> {
                        val format = when (selectedTag()) {
                            "png" -> ImageCompressor.Format.PNG
                            "webp" -> ImageCompressor.Format.WEBP
                            else -> ImageCompressor.Format.JPEG
                        }
                        val outFile = File(
                            cacheDir,
                            "hakunaa_converted_${System.currentTimeMillis()}.${format.extension}"
                        )
                        if (ImageCompressor.convertFormat(input, outFile, format)) outFile else null
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
                        status.text = "Failed — try another image"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Failed — try another image" }
            }
        }.start()
    }

    /** Longest side for the chosen resolution (10000 = keep original). */
    private fun resolutionMaxDimension(): Int {
        val tag = selectedTag() ?: return 10000
        if (tag == "Keep resolution" || tag.startsWith("Custom")) return 10000
        val dims = tag.split("x")
        val w = dims.getOrNull(0)?.toIntOrNull() ?: return 10000
        val h = dims.getOrNull(1)?.toIntOrNull() ?: return 10000
        return maxOf(w, h)
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
     *  no downscale, no blur. */
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
                        step = 2
                        renderStep()
                        saveSingleResult(results[0].file)
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
    private fun buildQualityCards() {
        val container = findViewById<LinearLayout>(R.id.qualityContainer)
        container.removeAllViews()
        val inflater = android.view.LayoutInflater.from(this)
        val gapPx = (12 * resources.displayMetrics.density).toInt()
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
            val card = inflater.inflate(R.layout.item_image_tool, container, false)
            card.findViewById<android.widget.ImageView>(R.id.ivIcon).setImageResource(R.drawable.ic_tune)
            card.findViewById<TextView>(R.id.tvTitle).text = "$quality%"
            card.findViewById<TextView>(R.id.tvSubtitle).text = label
            if (index > 0) {
                val params = card.layoutParams as? LinearLayout.LayoutParams
                params?.topMargin = gapPx
                card.layoutParams = params
            }
            card.setOnClickListener {
                qualityChoice = quality
                step = 2
                renderStep()
            }
            container.addView(card)
        }
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

    /** Smart results rows: label + Best badge + real size + saving. Tap = save. */
    private fun showSmartResults(results: List<ImageCompressor.LevelResult>) {
        val container = findViewById<LinearLayout>(R.id.resultsContainer)
        container.removeAllViews()
        for ((index, r) in results.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 20, 16, 20)
                // selectableItemBackground is an ATTRIBUTE, not a drawable —
                // pass it straight to setBackgroundResource and it crashes
                // (Resources$NotFoundException #0x101030e). Resolve it first.
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(
                    android.R.attr.selectableItemBackground, typedValue, true
                )
                setBackgroundResource(typedValue.resourceId)
                isClickable = true
                isFocusable = true
            }
            val line1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val label = TextView(this).apply {
                text = r.label
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
            }
            line1.addView(label)
            if (index == 0) {
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
                lp.marginStart = 12
                line1.addView(best, lp)
            }
            val line2 = TextView(this).apply {
                text = "${FileUtils.formatSize(r.size)}  •  saved ${r.savingPercent}%"
                textSize = 14f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
            }
            row.addView(line1)
            row.addView(line2)
            row.setOnClickListener { saveSmartResult(r) }
            container.addView(row)
            val div = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(getColor(R.color.hakunaa_outline))
            }
            container.addView(div)
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
