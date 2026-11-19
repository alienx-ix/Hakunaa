package com.hakunaa.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import java.io.File
import com.hakunaa.app.core.StatusBar

/**
 * Color Correction — full professional Adjust panel (v0.1.117).
 *
 * A row of tools (Auto, Exposure, Brightness, Contrast, Highlights,
 * Shade, Saturation, Vibrance, Color temp, Tone, Sharpness, Fading,
 * Vignette, Enhance, Dehaze) and one slider that controls the
 * selected tool. Every slider really changes the image via
 * ImageCompressor.adjustFull; the preview updates live as the
 * slider moves; Save writes the edited image. Rotate is kept.
 */
class ImageEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
    }

    private class ToolState(
        val name: String,
        val min: Int,
        val max: Int,
        val def: Int,
        var value: Int = def
    )

    private val tools = listOf(
        ToolState("Exposure", -100, 100, 0),
        ToolState("Brightness", -100, 100, 0),
        ToolState("Contrast", 50, 200, 100),
        ToolState("Highlights", -100, 100, 0),
        ToolState("Shade", -100, 100, 0),
        ToolState("Saturation", 0, 200, 100),
        ToolState("Vibrance", -100, 100, 0),
        ToolState("Color temp", -100, 100, 0),
        ToolState("Tone", -100, 100, 0),
        ToolState("Sharpness", 0, 100, 0),
        ToolState("Fading", 0, 100, 0),
        ToolState("Vignette", 0, 100, 0),
        ToolState("Enhance", 0, 100, 0),
        ToolState("Dehaze", 0, 100, 0)
    )

    private var bitmap: Bitmap? = null
    private var inputFile: File? = null
    /** The file adjustments start from (the original, or the Auto result). */
    private var baseFile: File? = null
    private var selectedTool = 1 // Brightness by default (index into tools).
    private var rotation = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_edit)

        StatusBar.setup(this, true)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriString == null) {
            Toast.makeText(this, "No image", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Thread {
            try {
                val file = FileUtils.copyUriToCache(this, Uri.parse(uriString), "input_image")
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                runOnUiThread {
                    if (bmp == null) {
                        Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                        finish()
                        return@runOnUiThread
                    }
                    inputFile = file
                    baseFile = file
                    bitmap = bmp
                    findViewById<ImageView>(R.id.ivPreview).setImageBitmap(bmp)
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()

        buildToolRow()
        selectTool(selectedTool)
        findViewById<SeekBar>(R.id.sbValue).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val tool = tools[selectedTool]
                    tool.value = progress + tool.min
                    findViewById<TextView>(R.id.tvToolValue).text = tool.value.toString()
                    updatePreview()
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            }
        )
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRotate)
            .setOnClickListener {
                rotation = (rotation + 90f) % 360f
                updatePreview()
            }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave)
            .setOnClickListener { save() }
    }

    /** The tool chips row — Auto first, then every Adjust tool. */
    private fun buildToolRow() {
        val row = findViewById<LinearLayout>(R.id.toolRow)
        row.removeAllViews()
        val density = resources.displayMetrics.density
        // Auto: one-tap auto enhance.
        val auto = TextView(this).apply {
            text = "Auto"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            setTextColor(getColor(R.color.hakunaa_text))
            setBackgroundResource(R.drawable.bg_search)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * density).toInt()
            layoutParams = lp
        }
        auto.setOnClickListener { applyAuto() }
        row.addView(auto)
        for ((i, tool) in tools.withIndex()) {
            val chip = TextView(this).apply {
                text = tool.name
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                val selected = i == selectedTool
                setTextColor(
                    if (selected) getColor(android.R.color.white)
                    else getColor(R.color.hakunaa_text)
                )
                setBackgroundResource(
                    if (selected) R.drawable.bg_teal_button else R.drawable.bg_search
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (8 * density).toInt()
                layoutParams = lp
            }
            chip.setOnClickListener { selectTool(i) }
            row.addView(chip)
        }
    }

    private fun selectTool(index: Int) {
        selectedTool = index
        buildToolRow()
        val tool = tools[index]
        findViewById<TextView>(R.id.tvToolName).text = tool.name
        findViewById<TextView>(R.id.tvToolValue).text = tool.value.toString()
        // Offset range (0..max-min) so no SeekBar.setMin is needed (API 24 safe).
        val seek = findViewById<SeekBar>(R.id.sbValue)
        seek.max = tool.max - tool.min
        seek.progress = tool.value - tool.min
    }

    /** Current slider values as an AdjustParams. */
    private fun buildParams(): ImageCompressor.AdjustParams {
        val p = ImageCompressor.AdjustParams()
        for (t in tools) {
            when (t.name) {
                "Exposure" -> p.exposure = t.value.toFloat()
                "Brightness" -> p.brightness = t.value.toFloat()
                "Contrast" -> p.contrast = t.value / 100f
                "Highlights" -> p.highlights = t.value.toFloat()
                "Shade" -> p.shade = t.value.toFloat()
                "Saturation" -> p.saturation = t.value / 100f
                "Vibrance" -> p.vibrance = t.value.toFloat()
                "Color temp" -> p.temperature = t.value.toFloat()
                "Tone" -> p.tone = t.value.toFloat()
                "Sharpness" -> p.sharpness = t.value.toFloat()
                "Fading" -> p.fading = t.value.toFloat()
                "Vignette" -> p.vignette = t.value.toFloat()
                "Enhance" -> p.enhance = t.value.toFloat()
                "Dehaze" -> p.dehaze = t.value.toFloat()
            }
        }
        return p
    }

    /** Auto: run the one-tap auto enhance, then reset the sliders. */
    private fun applyAuto() {
        val input = baseFile ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Auto enhancing…"
        Thread {
            try {
                val out = File(cacheDir, "edit_auto_${System.currentTimeMillis()}.jpg")
                val ok = ImageCompressor.autoEnhance(input, out)
                runOnUiThread {
                    if (ok && out.exists()) {
                        val bmp = BitmapFactory.decodeFile(out.absolutePath)
                        if (bmp != null) {
                            baseFile = out
                            bitmap = bmp
                            for (t in tools) t.value = t.def
                            selectTool(selectedTool)
                            findViewById<ImageView>(R.id.ivPreview).setImageBitmap(bmp)
                            status.text = "Auto applied — adjust further if you like"
                        } else {
                            status.text = "Auto failed — try again"
                        }
                    } else {
                        status.text = "Auto failed — try again"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Auto failed — try again" }
            }
        }.start()
    }

    private fun updatePreview() {
        val bmp = bitmap ?: return
        val params = buildParams()
        val rot = rotation
        Thread {
            try {
                var preview = Bitmap.createScaledBitmap(
                    bmp, 800, (800f * bmp.height / bmp.width).toInt(), true
                )
                if (rot != 0f) {
                    val matrix = Matrix()
                    matrix.postRotate(rot)
                    preview = Bitmap.createBitmap(preview, 0, 0, preview.width, preview.height, matrix, true)
                }
                val tmpInput = File(cacheDir, "edit_preview_in_${System.currentTimeMillis()}.jpg")
                tmpInput.outputStream().use { out ->
                    preview.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                val tmp = File(cacheDir, "edit_preview_${System.currentTimeMillis()}.jpg")
                val ok = ImageCompressor.adjustFull(tmpInput, tmp, params)
                var adjusted: Bitmap? = null
                if (ok) adjusted = BitmapFactory.decodeFile(tmp.absolutePath)
                try { tmp.delete() } catch (_: Throwable) { }
                try { tmpInput.delete() } catch (_: Throwable) { }
                preview.recycle()
                runOnUiThread {
                    if (adjusted != null) {
                        findViewById<ImageView>(R.id.ivPreview).setImageBitmap(adjusted)
                    }
                }
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun save() {
        val input = baseFile ?: return
        val params = buildParams()
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Saving…"
        Thread {
            try {
                // Apply rotation first, then the adjustments.
                var working = input
                if (rotation != 0f) {
                    val rotated = File(cacheDir, "edit_rotated_${System.currentTimeMillis()}.jpg")
                    if (ImageCompressor.rotate(input, rotated, rotation)) working = rotated
                }
                val out = File(cacheDir, "hakunaa_edited_${System.currentTimeMillis()}.jpg")
                val ok = ImageCompressor.adjustFull(working, out, params)
                if (working != input) {
                    try { working.delete() } catch (_: Throwable) { }
                }
                runOnUiThread {
                    if (ok) {
                        val saved = MediaStoreSaver.save(
                            this, out, MediaStoreSaver.Kind.IMAGE, out.name,
                            ImageCompressor.formatOf(out).mime
                        )
                        status.text = if (saved != null) "Done — edited image saved to Pictures/Hakunaa"
                        else "Done, but couldn't save to Pictures — please try again"
                    } else {
                        status.text = "Save failed — try again"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Save failed — try again" }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        bitmap?.recycle()
    }
}
