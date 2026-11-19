package com.hakunaa.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.core.StatusBar
import java.io.File
import java.io.FileOutputStream

/**
 * Crop — professional rectangular crop (Google Photos style).
 *
 * Uses [ProCropView]: a rectangle-only frame (never a tilted quad),
 * corner handles to resize, drag-inside to pan, a rotation slider
 * (-45..+45°) and aspect-ratio chips. Launched 3 ways:
 *  - plain Crop tool: no aspect extras -> Freestyle
 *  - Fit to social media: EXTRA_ASPECT_W/H set -> that aspect, pre-selected
 *  - DP Edit: EXTRA_CIRCLE true -> circle frame, square, aspect row hidden
 *
 * Save: the rotated bitmap is written to a cache file (only when the
 * rotation is non-zero; otherwise the original input file is used),
 * then cropped with ImageCompressor.crop / circleCropRect using the
 * crop rect in rotated-bitmap pixels, and saved via MediaStoreSaver.
 */
class ImageCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        /** Locked aspect ratio for the frame (0 = free crop). */
        const val EXTRA_ASPECT_W = "aspect_w"
        const val EXTRA_ASPECT_H = "aspect_h"
        /** Circle frame (DP Circle) — the frame is a circle. */
        const val EXTRA_CIRCLE = "circle"
    }

    private var bitmap: Bitmap? = null
    private var inputFile: File? = null
    private var circleMode = false
    private var saving = false

    private lateinit var cropView: ProCropView
    private lateinit var tvDimensions: TextView
    private lateinit var tvAngle: TextView
    private lateinit var sbRotation: SeekBar
    private lateinit var tvStatus: TextView

    /** Currently selected aspect chip: null = Freestyle, "original", or "w:h". */
    private var selectedAspect: String? = null
    private val aspectChips = mutableListOf<TextView>()
    private val aspectChipKeys = mutableListOf<String?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_crop)

        // Status bar (v0.1.97): keep it visible, no overlap.
        StatusBar.setup(this, false)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        cropView = findViewById(R.id.cropView)
        tvDimensions = findViewById(R.id.tvDimensions)
        tvAngle = findViewById(R.id.tvAngle)
        sbRotation = findViewById(R.id.sbRotation)
        tvStatus = findViewById(R.id.tvStatus)

        cropView.onFrameChanged = { w, h -> tvDimensions.text = "$w × $h" }

        sbRotation.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val degrees = (progress - 45).toFloat()
                tvAngle.text = "${degrees.toInt()}°"
                // Angle stands out (teal) whenever a rotation is applied.
                tvAngle.setTextColor(
                    if (degrees == 0f) Color.parseColor("#FFFFFF")
                    else Color.parseColor("#00BFA5")
                )
                cropView.setCropRotation(degrees)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        findViewById<TextView>(R.id.btnRotateReset).setOnClickListener { resetRotation() }
        findViewById<TextView>(R.id.btnRestore).setOnClickListener { restore() }
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { save() }

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
                    val aspectW = intent.getIntExtra(EXTRA_ASPECT_W, 0)
                    val aspectH = intent.getIntExtra(EXTRA_ASPECT_H, 0)
                    circleMode = intent.getBooleanExtra(EXTRA_CIRCLE, false)
                    cropView.setBitmap(bmp)
                    if (circleMode) {
                        cropView.setCircleMode(true)
                        findViewById<TextView>(R.id.tvCropTitle).text = "DP Crop — Circle"
                        tvStatus.text = "Drag the circle to frame your DP"
                        findViewById<View>(R.id.aspectScroll).visibility = View.GONE
                    } else if (aspectW > 0 && aspectH > 0) {
                        cropView.setAspectRatio(aspectW, aspectH)
                        findViewById<TextView>(R.id.tvCropTitle).text = "Fit to social media"
                        tvStatus.text = "Drag the frame to choose the portion to keep"
                        selectedAspect = "$aspectW:$aspectH"
                        buildAspectChips(extraKey = selectedAspect)
                    } else {
                        selectedAspect = null
                        buildAspectChips()
                    }
                    updateChipSelection()
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }

    // ------------------------------------------------------------------ aspects

    private fun buildAspectChips(extraKey: String? = null) {
        val row = findViewById<LinearLayout>(R.id.aspectRow)
        row.removeAllViews()
        aspectChips.clear()
        aspectChipKeys.clear()
        // label -> (w, h); null = Freestyle, "original" handled separately.
        // Fit-to-social can pass an aspect outside the standard row
        // (9:16, 4:5, 3:2) — include it as a chip so it can be pre-selected.
        val chips = mutableListOf<Pair<String, String?>>(
            "Freestyle" to null,
            "Original" to "original",
            "1:1" to "1:1",
            "4:3" to "4:3",
            "3:4" to "3:4",
            "16:9" to "16:9"
        )
        if (extraKey != null && chips.none { it.second == extraKey }) {
            chips.add(2, extraKey to extraKey)
        }
        for ((label, key) in chips) {
            val chip = TextView(this).apply {
                text = label
                textSize = 14f
                // Pill padding (dp) + an 8dp gap to the next chip.
                val d = resources.displayMetrics.density
                setPadding((18 * d).toInt(), (9 * d).toInt(), (18 * d).toInt(), (9 * d).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * d).toInt() }
                setOnClickListener { selectAspect(key) }
            }
            styleChip(chip, selected = false)
            row.addView(chip)
            aspectChips.add(chip)
            aspectChipKeys.add(key)
        }
    }

    /**
     * Professional pill button for an aspect chip.
     * Selected: teal fill + white bold text. Unselected: dark surface
     * with a clear grey border + light text. Same size/shape either way,
     * so selecting never shifts the row.
     */
    private fun styleChip(chip: TextView, selected: Boolean) {
        val d = resources.displayMetrics.density
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * d
            if (selected) {
                setColor(Color.parseColor("#00BFA5"))
            } else {
                setColor(Color.parseColor("#1E1E1E"))
                setStroke((1 * d).toInt(), Color.parseColor("#4D4D4D"))
            }
        }
        chip.setTextColor(
            if (selected) Color.parseColor("#FFFFFF")
            else Color.parseColor("#E0E0E0")
        )
        chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun selectAspect(key: String?) {
        selectedAspect = key
        when (key) {
            null -> cropView.setAspectRatio(null as Float?)
            "original" -> cropView.setAspectOriginal()
            else -> {
                val parts = key.split(":")
                cropView.setAspectRatio(parts[0].toInt(), parts[1].toInt())
            }
        }
        updateChipSelection()
    }

    private fun updateChipSelection() {
        for (i in aspectChips.indices) {
            styleChip(aspectChips[i], aspectChipKeys[i] == selectedAspect)
        }
    }

    // ------------------------------------------------------------------ rotation / restore

    private fun resetRotation() {
        sbRotation.progress = 45 // listener applies 0° to the view
    }

    private fun restore() {
        resetRotation()
        cropView.restore()
        tvStatus.text = if (circleMode) "Drag the circle to frame your DP"
        else "Drag the corners to frame the part to keep"
    }

    // ------------------------------------------------------------------ save

    private fun save() {
        if (saving) return
        val rect = cropView.getCropRectPx()
        if (rect.width() <= 0 || rect.height() <= 0 || inputFile == null) return
        saving = true
        tvStatus.text = "Cropping…"
        Thread {
            try {
                // Source for the crop: the rotated bitmap, written to a
                // cache file only when a rotation was applied; otherwise
                // the original input file (no extra JPEG re-encode).
                val rotation = cropView.getCropRotation()
                val sourceFile: File = if (rotation == 0f) {
                    inputFile!!
                } else {
                    val rotatedBmp = cropView.getRotatedBitmap()
                    if (rotatedBmp == null) {
                        runOnUiThread { tvStatus.text = "Crop failed — try again"; saving = false }
                        return@Thread
                    }
                    val tmp = File(cacheDir, "hakunaa_rotated_${System.currentTimeMillis()}.jpg")
                    FileOutputStream(tmp).use { out ->
                        rotatedBmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    tmp
                }
                val out = if (circleMode) {
                    File(cacheDir, "hakunaa_dp_${System.currentTimeMillis()}.png")
                } else {
                    File(cacheDir, "hakunaa_cropped_${System.currentTimeMillis()}.jpg")
                }
                val ok = if (circleMode) {
                    val size = minOf(rect.width(), rect.height())
                    ImageCompressor.circleCropRect(sourceFile, out, rect.left, rect.top, size)
                } else {
                    ImageCompressor.crop(sourceFile, out, rect.left, rect.top, rect.right, rect.bottom)
                }
                runOnUiThread {
                    saving = false
                    if (ok) {
                        val saved = MediaStoreSaver.save(this, out, MediaStoreSaver.Kind.IMAGE, out.name)
                        tvStatus.text = if (saved != null) "Done — cropped image saved to Pictures/Hakunaa"
                        else "Done, but couldn't save to Pictures — please try again"
                    } else {
                        tvStatus.text = "Crop failed — try again"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { saving = false; tvStatus.text = "Crop failed — try again" }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        cropView.release()
        bitmap?.recycle()
        bitmap = null
    }
}
