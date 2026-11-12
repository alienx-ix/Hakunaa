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
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()

        findViewById<TextView>(R.id.btnBack).setOnClickListener {
            if (mode == MODE_COMPRESS && step == 1) {
                step = 0
                renderStep()
            } else {
                finish()
            }
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
        else -> "Compress"
    }

    private fun renderStep() {
        findViewById<TextView>(R.id.tvTitle).text = titleForMode()
        val group = findViewById<RadioGroup>(R.id.rgOptions)
        group.removeAllViews()
        val subtitle = findViewById<TextView>(R.id.tvSubtitle)
        when (mode) {
            MODE_COMPRESS -> {
                if (step == 0) {
                    subtitle.text = "Reduce file size, choose resolution or keep original"
                    addOptions(group, listOf(
                        "95" to "Maximum quality",
                        "90" to "High quality",
                        "80" to "Good quality",
                        "70" to "Balanced quality",
                        "60" to "Medium quality",
                        "50" to "Enough quality",
                        "85" to "Custom quality: 85"
                    ), 0)
                } else {
                    subtitle.text = "Change photo resolution"
                    addResolutionOptions(group)
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

    private fun next() {
        val input = inputFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        // Compress has two steps: quality first, then resolution.
        if (mode == MODE_COMPRESS && step == 0) {
            qualityChoice = selectedTag()?.toIntOrNull() ?: 85
            step = 1
            renderStep()
            return
        }
        status.text = "Working…"
        Thread {
            try {
                val out = when (mode) {
                    MODE_COMPRESS -> {
                        val quality = selectedQuality()
                        val resolution = selectedTag()
                        val outFile = File(cacheDir, "hakunaa_compressed_${System.currentTimeMillis()}.jpg")
                        val dims = resolution?.split("x")
                        val w = dims?.getOrNull(0)?.toIntOrNull()
                        val h = dims?.getOrNull(1)?.toIntOrNull()
                        val ok = if (w != null && h != null) {
                            // Resize to the chosen resolution, then
                            // compress at the chosen quality.
                            val resized = File(cacheDir, "hakunaa_tmp_${System.currentTimeMillis()}.jpg")
                            val resizedOk = ImageCompressor.resizeTo(input, resized, w, h)
                            val compressedOk = if (resizedOk) {
                                ImageCompressor.compress(resized, outFile, quality, ImageCompressor.Format.JPEG, maxDimension = 10000)
                            } else false
                            try { resized.delete() } catch (_: Throwable) { }
                            compressedOk
                        } else {
                            ImageCompressor.compress(input, outFile, quality, ImageCompressor.Format.JPEG)
                        }
                        if (ok) outFile else null
                    }
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

    private fun selectedQuality(): Int {
        // The quality was picked in step 0 and stored before the
        // group was re-rendered with resolutions.
        return qualityChoice
    }

    private var qualityChoice = 85
}
