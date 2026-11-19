package com.hakunaa.app.image

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.core.StatusBar
import com.hakunaa.app.pdf.PdfTools
import java.io.File

/**
 * Image tools hub (v0.1.111) — box-by-box menu, importance order,
 * professional look like the approved mockup.
 *
 * Pick a photo, then tap a tool. Search filters the cards by name.
 *
 * - Compress: reduce file size — pick a resolution, see every
 *   level's real size, tap one to save (Smart results).
 * - Quick squish: one tap, just make the image smaller (best
 *   compression — tries every level, keeps the smallest).
 * - Crop: remove unwanted parts of the photo.
 * - Resize: change photo resolution.
 * - Social Media Crop: 1:1, 9:16, 4:5 (Instagram / TikTok / FB).
 * - Convert: change format to PNG, JPG, WebP.
 * - Edit image: basic photo editing (brightness, contrast,
 *   saturation, rotate).
 * - DP Edit: Circle or Square profile photo.
 * - Image to PDF: one A4 page per photo.
 * - Remove Metadata: strip location / camera / date (EXIF).
 *   (Target Size lives inside Compress — no separate card.)
 *
 * Everything is on-device. A compress output is never bigger than
 * the original.
 */
class ImageCompressActivity : AppCompatActivity() {

    private var inputUri: Uri? = null
    private var inputSize: Long = -1

    /** True while a tool screen is open — the pick is cleared on return. */
    private var launchedTool = false

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            inputUri = uri
            if (uri != null) {
                inputSize = FileUtils.fileSize(contentResolver, uri)
                // The tool that was tapped waits for the pick — run it now.
                val action = pendingAction
                pendingAction = null
                action?.invoke()
            } else {
                pendingAction = null
            }
        }

    /** Forget the picked photo — a NEW photo is picked every time. */
    private fun clearPickedPhoto() {
        inputUri = null
        inputSize = -1
    }

    override fun onResume() {
        super.onResume()
        // Back from a tool: the photo goes away, next tap picks fresh.
        if (launchedTool) {
            launchedTool = false
            clearPickedPhoto()
        }
    }

    /** The tool tapped while no image was picked yet — runs after the pick. */
    private var pendingAction: (() -> Unit)? = null

    /** Run [action] with an image: now if one is picked, else ask first. */
    private fun withImage(action: () -> Unit) {
        if (inputUri != null) {
            action()
        } else {
            pendingAction = action
            pickImage.launch("image/*")
        }
    }

    private data class ToolDef(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val iconRotation: Float = 0f,
        val fullWidth: Boolean = false,
        val action: () -> Unit
    )

    private lateinit var toolDefs: List<ToolDef>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_compress)

        // Status bar (v0.1.97): keep it visible, no overlap.
        StatusBar.setup(this, true)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        toolDefs = listOf(
            ToolDef("Compress", "Reduce file size", R.drawable.ic_arrow_right, 90f) {
                withImage { openTool(ImageToolActivity.MODE_COMPRESS) }
            },
            ToolDef("Smart Compress", "Try all levels, pick the best", R.drawable.ic_bolt) {
                withImage { openTool(ImageToolActivity.MODE_SMART) }
            },
            ToolDef("Crop", "Remove unwanted parts", R.drawable.ic_crop) {
                withImage { openCrop() }
            },
            ToolDef("Resize", "Change photo resolution", R.drawable.ic_text_resize) {
                withImage { openTool(ImageToolActivity.MODE_RESIZE) }
            },
            ToolDef("Social Media Crop", "1:1, 9:16, 4:5", R.drawable.ic_frame) {
                withImage { openTool(ImageToolActivity.MODE_FIT_SOCIAL) }
            },
            ToolDef("Convert", "PNG, JPG, WebP", R.drawable.ic_convert) {
                withImage { openTool(ImageToolActivity.MODE_CONVERT) }
            },
            ToolDef("Edit Image", "Basic photo editing tools", R.drawable.ic_markup) {
                withImage { openEdit() }
            },
            ToolDef("DP Edit", "Circle & Square", R.drawable.ic_profile) {
                withImage { openTool(ImageToolActivity.MODE_DP) }
            },
            ToolDef("Image to PDF", "Photo to PDF", R.drawable.ic_doc) {
                withImage { imageToPdf() }
            },
            ToolDef("Remove Metadata", "Strip location & metadata", R.drawable.ic_shield) {
                withImage { openTool(ImageToolActivity.MODE_METADATA) }
            }
        )

        buildToolGrid("")
        findViewById<EditText>(R.id.etSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                buildToolGrid(s?.toString() ?: "")
            }
        })
    }

    /** Build the box grid (2 per row; a full-width card gets its own row). */
    private fun buildToolGrid(filter: String) {
        val container = findViewById<LinearLayout>(R.id.toolsContainer)
        container.removeAllViews()
        val q = filter.trim().lowercase()
        val tools = toolDefs.filter { q.isEmpty() || it.title.lowercase().contains(q) }
        var i = 0
        while (i < tools.size) {
            val tool = tools[i]
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(6, 6, 6, 6) }
            row.addView(makeToolCard(tool), lp)
            if (!tool.fullWidth && i + 1 < tools.size && !tools[i + 1].fullWidth) {
                row.addView(makeToolCard(tools[i + 1]), lp)
                i += 2
            } else {
                i++
            }
            container.addView(row)
        }
        if (tools.isEmpty()) {
            val hint = TextView(this).apply {
                text = "No tools match \"$filter\""
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                textSize = 14f
                setPadding(8, 16, 8, 16)
            }
            container.addView(hint)
        }
    }

    private fun makeToolCard(tool: ToolDef): View {
        val card = layoutInflater.inflate(R.layout.item_image_tool, null) as MaterialCardView
        val icon = card.findViewById<ImageView>(R.id.ivIcon)
        icon.setImageResource(tool.iconRes)
        icon.rotation = tool.iconRotation
        card.findViewById<TextView>(R.id.tvTitle).text = tool.title
        card.findViewById<TextView>(R.id.tvSubtitle).text = tool.subtitle
        card.setOnClickListener { tool.action() }
        return card
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
        launchedTool = true
        val intent = Intent(this, ImageToolActivity::class.java)
        intent.putExtra(ImageToolActivity.EXTRA_MODE, mode)
        intent.putExtra(ImageToolActivity.EXTRA_IMAGE_URI, inputUri.toString())
        startActivity(intent)
    }

    private fun openEdit() {
        if (!requireImage()) return
        launchedTool = true
        val intent = Intent(this, ImageEditActivity::class.java)
        intent.putExtra(ImageEditActivity.EXTRA_IMAGE_URI, inputUri.toString())
        startActivity(intent)
    }

    private fun openCrop() {
        if (!requireImage()) return
        launchedTool = true
        val intent = Intent(this, ImageCropActivity::class.java)
        intent.putExtra(ImageCropActivity.EXTRA_IMAGE_URI, inputUri.toString())
        startActivity(intent)
    }

    /** Image to PDF: one A4 page per photo, saved to Documents/Hakunaa. */
    private fun imageToPdf() {
        val uri = inputUri ?: run {
            Toast.makeText(this, getString(R.string.pick_first_image), Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        val result = findViewById<TextView>(R.id.tvResult)
        status.text = "Making PDF…"
        Thread {
            try {
                val input = FileUtils.copyUriToCache(this, uri, "input_image")
                val out = File(cacheDir, "hakunaa_image_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.imageToPdf(input, out)
                runOnUiThread {
                    clearPickedPhoto()
                    if (ok && out.exists()) {
                        val saved = MediaStoreSaver.save(
                            this, out, MediaStoreSaver.Kind.DOCUMENT, out.name
                        )
                        status.text = if (saved != null) "Done — saved to Documents/Hakunaa"
                        else "Done, but couldn't save — please try again"
                        result.text = "PDF — ${FileUtils.formatSize(out.length())}"
                    } else {
                        status.text = "Failed — try another image"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Failed — try another image" }
            }
        }.start()
    }
}
