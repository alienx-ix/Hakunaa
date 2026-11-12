package com.hakunaa.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import java.io.File

/**
 * Edit image — basic photo editing tools.
 * Sliders for Brightness, Contrast and Saturation, plus Rotate.
 * The preview updates live as the sliders move; Save writes the
 * edited image to Pictures/Hakunaa.
 */
class ImageEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
    }

    private var bitmap: Bitmap? = null
    private var inputFile: File? = null
    private var brightness = 0f
    private var contrast = 1f
    private var saturation = 1f
    private var rotation = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_edit)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

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

        setupSlider(R.id.sbBrightness, -100, 100, 0) { value ->
            brightness = value.toFloat()
            updatePreview()
        }
        setupSlider(R.id.sbContrast, 50, 200, 100) { value ->
            contrast = value / 100f
            updatePreview()
        }
        setupSlider(R.id.sbSaturation, 0, 200, 100) { value ->
            saturation = value / 100f
            updatePreview()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnRotate)
            .setOnClickListener {
                rotation = (rotation + 90f) % 360f
                updatePreview()
            }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave)
            .setOnClickListener { save() }
    }

    private fun setupSlider(id: Int, min: Int, max: Int, initial: Int, onChange: (Int) -> Unit) {
        val seek = findViewById<SeekBar>(id)
        seek.min = min
        seek.max = max
        seek.progress = initial
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updatePreview() {
        val bmp = bitmap ?: return
        Thread {
            try {
                // Apply the adjustments to a small preview copy.
                val preview = Bitmap.createScaledBitmap(bmp, 800, (800f * bmp.height / bmp.width).toInt(), true)
                val tmp = File(cacheDir, "edit_preview_${System.currentTimeMillis()}.jpg")
                val tmpInput = File(cacheDir, "edit_preview_in_${System.currentTimeMillis()}.jpg")
                tmpInput.outputStream().use { out ->
                    preview.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                val ok = ImageCompressor.adjust(tmpInput, tmp, brightness, contrast, saturation)
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
        val input = inputFile ?: return
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
                val ok = ImageCompressor.adjust(working, out, brightness, contrast, saturation)
                if (working != input) {
                    try { working.delete() } catch (_: Throwable) { }
                }
                runOnUiThread {
                    if (ok) {
                        val saved = MediaStoreSaver.save(this, out, MediaStoreSaver.Kind.IMAGE, out.name)
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
