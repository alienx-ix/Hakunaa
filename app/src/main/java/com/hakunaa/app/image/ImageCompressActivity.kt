package com.hakunaa.app.image

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import java.io.File
import com.hakunaa.app.core.StatusBar

/**
 * Image tools hub — Puma Photo Resize & Compress style.
 *
 * Pick a photo, then choose a tool:
 * - Quick squish: one tap, just make the image smaller (best
 *   compression — tries every level, keeps the smallest).
 * - Resize: change photo resolution.
 * - Compress: reduce file size, choose quality and resolution.
 * - Fit: adjust to social media, square, any size.
 * - Edit image: basic photo editing (brightness, contrast,
 *   saturation, rotate).
 * - Crop: remove unwanted parts of the photo.
 * - Convert: change format to PNG, JPG, WebP.
 * - Fit to social media: Instagram, FB, TikTok and more.
 * - Resize to KB, MB: resize to a target file size.
 *
 * Everything is on-device. The output is never bigger than the
 * original (for compress / quick squish).
 */
class ImageCompressActivity : AppCompatActivity() {
    private var inputUri: Uri? = null
    private var inputSize: Long = -1
    private var inputName: String = ""

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            inputUri = uri
            if (uri != null) {
                inputSize = FileUtils.fileSize(contentResolver, uri)
                inputName = FileUtils.fileName(contentResolver, uri)
                updateFileLabel()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_compress)

        // Status bar (v0.1.97): keep it visible, no overlap.
        StatusBar.setup(this, true)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        findViewById<Button>(R.id.btnPickImage).setOnClickListener { pickImage.launch("image/*") }

        // Quick squish — one tap, just make it smaller.
        // NOTE: toolQuickSquish is a MaterialCardView in the layout,
        // not a LinearLayout — casting it to LinearLayout crashed
        // the whole screen (v0.1.33, ClassCastException).
        findViewById<com.google.android.material.card.MaterialCardView>(R.id.toolQuickSquish)
            .setOnClickListener { quickSquish() }
        findViewById<LinearLayout>(R.id.toolResize).setOnClickListener {
            openTool(ImageToolActivity.MODE_RESIZE)
        }
        findViewById<LinearLayout>(R.id.toolCompress).setOnClickListener {
            openTool(ImageToolActivity.MODE_COMPRESS)
        }
        findViewById<LinearLayout>(R.id.toolFit).setOnClickListener {
            openTool(ImageToolActivity.MODE_FIT)
        }
        findViewById<LinearLayout>(R.id.toolEdit).setOnClickListener { openEdit() }
        findViewById<LinearLayout>(R.id.toolCrop).setOnClickListener { openCrop() }
        findViewById<LinearLayout>(R.id.toolConvert).setOnClickListener {
            openTool(ImageToolActivity.MODE_CONVERT)
        }
        findViewById<LinearLayout>(R.id.toolFitSocial).setOnClickListener {
            openTool(ImageToolActivity.MODE_FIT_SOCIAL)
        }
        findViewById<LinearLayout>(R.id.toolTargetSize).setOnClickListener {
            openTool(ImageToolActivity.MODE_TARGET_SIZE)
        }
        findViewById<LinearLayout>(R.id.toolCollage).setOnClickListener {
            startActivity(Intent(this, CollageActivity::class.java))
        }
    }

    private fun updateFileLabel() {
        findViewById<TextView>(R.id.tvFile).text =
            "$inputName — ${FileUtils.formatSize(inputSize)}"
    }

    private fun requireImage(): Boolean {
        if (inputUri == null) {
            Toast.makeText(this, getString(R.string.pick_first_image), Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun openTool(mode: String) {
        if (!requireImage()) return
        val intent = Intent(this, ImageToolActivity::class.java)
        intent.putExtra(ImageToolActivity.EXTRA_MODE, mode)
        intent.putExtra(ImageToolActivity.EXTRA_IMAGE_URI, inputUri.toString())
        startActivity(intent)
    }

    private fun openEdit() {
        if (!requireImage()) return
        val intent = Intent(this, ImageEditActivity::class.java)
        intent.putExtra(ImageEditActivity.EXTRA_IMAGE_URI, inputUri.toString())
        startActivity(intent)
    }

    private fun openCrop() {
        if (!requireImage()) return
        val intent = Intent(this, ImageCropActivity::class.java)
        intent.putExtra(ImageCropActivity.EXTRA_IMAGE_URI, inputUri.toString())
        startActivity(intent)
    }

    /** Quick squish: one tap, best compression — tries every level
     *  and keeps the smallest file. Never bigger than original. */
    private fun quickSquish() {
        val uri = inputUri ?: run {
            Toast.makeText(this, getString(R.string.pick_first_image), Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        val result = findViewById<TextView>(R.id.tvResult)
        status.text = "Squishing…"
        Thread {
            try {
                val input = FileUtils.copyUriToCache(this, uri, "input_image")
                val outBase = File(cacheDir, "hakunaa_${System.currentTimeMillis()}")
                val best = ImageCompressor.compressBest(input, outBase)
                runOnUiThread {
                    if (best != null) {
                        val (out, _, note) = best
                        val after = out.length()
                        val saved = MediaStoreSaver.save(
                            this, out, MediaStoreSaver.Kind.IMAGE, out.name
                        )
                        status.text = if (saved != null) "Done — saved to Pictures/Hakunaa$note"
                        else "Done, but couldn't save to Pictures — please try again"
                        result.text = "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(after)} " +
                            "(${FileUtils.savedPercent(inputSize, after)})"
                    } else {
                        status.text = "Already optimized — even the strongest settings couldn't shrink this image."
                        result.text = ""
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Failed — try another image" }
            }
        }.start()
    }
}
