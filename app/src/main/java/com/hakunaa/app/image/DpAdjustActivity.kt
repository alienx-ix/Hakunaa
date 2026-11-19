package com.hakunaa.app.image

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.button.MaterialButton
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.core.StatusBar
import java.io.File

/**
 * DP Crop — professional adjust step (v0.1.124, crop tool
 * v0.1.133 Batch 17). Tapping a DP preview opens this screen:
 * the CROP TOOL itself — drag the circle/square frame to move
 * it, drag a corner (Square) or the edge (Circle) to resize it
 * — and pick the outer area (Blur / Color / Transparent for
 * Circle), drawn live exactly as the saved DP will look. Save
 * renders full-res via [ImageCompressor.dpCircleRect] /
 * [ImageCompressor.dpSquareRect] and shows the professional
 * Saved popup.
 */
class DpAdjustActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_CIRCLE = "circle"
        const val EXTRA_DP_BG = "dp_bg"
        const val EXTRA_DP_COLOR = "dp_color"
    }

    private var inputFile: File? = null
    private var bitmap: Bitmap? = null
    private var circle = false
    private var dpBackground = ImageCompressor.DpBackground.BLUR
    private var dpBgColor = 0xFFFFFFFF.toInt()
    private var saving = false

    private lateinit var adjustView: DpAdjustView
    private lateinit var tvStatus: TextView
    private lateinit var colorRow: LinearLayout
    private val dpBgChips = mutableListOf<TextView>()

    /** The rendered preview file (v0.1.135, Batch 19): the real
     *  cropped output, shown in the preview dialog after the user
     *  taps Save — saved to Pictures from the preview, deleted
     *  if the user goes back to the crop tool. */
    private var previewFile: File? = null
    private var previewDialog: Dialog? = null

    /** Height of a system bar (status / navigation) in px, 0 if
     *  the device has none (gesture nav). Falls back to
     *  [fallbackDp]. */
    private fun systemBarHeight(name: String, fallbackDp: Int): Int {
        val density = resources.displayMetrics.density
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
        else (fallbackDp * density).toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        circle = intent.getBooleanExtra(EXTRA_CIRCLE, false)
        dpBackground = try {
            ImageCompressor.DpBackground.valueOf(
                intent.getStringExtra(EXTRA_DP_BG) ?: "BLUR"
            )
        } catch (_: Throwable) {
            ImageCompressor.DpBackground.BLUR
        }
        dpBgColor = intent.getIntExtra(EXTRA_DP_COLOR, 0xFFFFFFFF.toInt())

        // System bar heights (explicit — this screen is
        // full-screen like Book, so StatusBar.setup runs with
        // applyInsets = false and the bars place themselves below
        // the status bar / above the nav bar).
        val statusBarH = systemBarHeight("status_bar_height", 24)
        val navBarH = systemBarHeight("navigation_bar_height", 0)

        // Full-screen crop (v0.1.135, Batch 19): the photo fills
        // the screen. Root is a FrameLayout — the crop view takes
        // the whole screen, with margins leaving room for the top
        // bar and the bottom panel. The old LinearLayout stacked
        // the photo as a small square between the bars; the user
        // called DP Crop "nala vae ila" and asked for a full-screen
        // crop, so the photo now fills all the space the bars
        // leave. The bottom panel is a compact overlay that does
        // not shrink the photo beyond its own height.
        val root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.hakunaa_background))
        }
        // The crop view: full screen, margins for the bars.
        adjustView = DpAdjustView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = statusBarH + (56 * density).toInt()
                // Bottom margin: estimated first (chips 52 +
                // colour 52 + hint 28 + buttons 80 + panel
                // padding 16), refined once the panel is measured
                // below.
                bottomMargin = (228 * density).toInt() + navBarH
            }
        }
        root.addView(adjustView)
        // Top bar overlay: X + title, below the status bar.
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.hakunaa_background))
            setPadding(
                (8 * density).toInt(), 0,
                (16 * density).toInt(), 0
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (56 * density).toInt(),
                Gravity.TOP
            ).apply { topMargin = statusBarH }
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
        close.setOnClickListener { finish() }
        val title = TextView(this).apply {
            text = if (circle) "DP Crop — Circle" else "DP Crop — Square"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        topBar.addView(close)
        topBar.addView(title)
        root.addView(topBar)

        // Bottom panel overlay: chips + swatches + hint + status
        // + Back/Save, pinned to the bottom over the photo.
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.hakunaa_background))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            setPadding(0, 0, 0, (16 * density).toInt() + navBarH)
        }
        // Outer-area chips: Blur / Color (+ Transparent for Circle).
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        dpBgChips.clear()
        val chipDefs = mutableListOf(
            ImageCompressor.DpBackground.BLUR to "Blur",
            ImageCompressor.DpBackground.COLOR to "Color"
        )
        if (circle) chipDefs.add(
            ImageCompressor.DpBackground.TRANSPARENT to "Transparent"
        )
        for ((bg, label) in chipDefs) {
            val chip = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(
                    (14 * density).toInt(), (10 * density).toInt(),
                    (14 * density).toInt(), (10 * density).toInt()
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * density).toInt() }
            }
            chip.setOnClickListener {
                dpBackground = bg
                updateDpChips()
                adjustView.setBackgroundChoice(dpBackground, dpBgColor)
            }
            dpBgChips.add(chip)
            chipsRow.addView(chip)
        }
        bottomPanel.addView(chipsRow)

        // Colour swatches — only when Color is picked.
        colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
            visibility = View.GONE
        }
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
                adjustView.setBackgroundChoice(dpBackground, dpBgColor)
            }
            colorRow.addView(swatch)
        }
        bottomPanel.addView(colorRow)

        val hint = TextView(this).apply {
            text = if (circle) "Drag the circle to move • Drag the edge to resize"
            else "Drag the frame to move • Drag a corner to resize"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        bottomPanel.addView(hint)

        tvStatus = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            setPadding(0, (8 * density).toInt(), 0, 0)
            visibility = View.GONE
        }
        bottomPanel.addView(tvStatus)

        // Bottom bar: Back / Save.
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                (16 * density).toInt(), (8 * density).toInt(),
                (16 * density).toInt(), (16 * density).toInt()
            )
        }
        val backBtn = MaterialButton(this).apply {
            text = "Back"
            setTextColor(getColor(R.color.hakunaa_teal))
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
            strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            strokeWidth = (2 * density).toInt()
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                0, (56 * density).toInt(), 1f
            ).apply { marginEnd = (6 * density).toInt() }
        }
        backBtn.setOnClickListener { finish() }
        val saveBtn = MaterialButton(this).apply {
            text = "Save"
            setTextColor(getColor(android.R.color.white))
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                0, (56 * density).toInt(), 1f
            ).apply { marginStart = (6 * density).toInt() }
        }
        saveBtn.setOnClickListener { save() }
        bottomBar.addView(backBtn)
        bottomBar.addView(saveBtn)
        bottomPanel.addView(bottomBar)
        root.addView(bottomPanel)

        // Refine the crop view's bottom margin once the panel is
        // measured (its height is constant — the colour row is
        // INVISIBLE, never GONE — so this is a safety net).
        bottomPanel.post {
            val lp = adjustView.layoutParams as FrameLayout.LayoutParams
            if (lp.bottomMargin != bottomPanel.height) {
                lp.bottomMargin = bottomPanel.height
                adjustView.layoutParams = lp
            }
        }

        setContentView(root)
        // Full-screen (Book pattern): no inset padding on the root
        // — the top bar and bottom panel place themselves
        // explicitly below the status bar / above the nav bar.
        // (v0.1.133: setup must run AFTER setContentView.)
        StatusBar.setup(this, true, applyInsets = false)
        updateDpChips()

        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriString == null) {
            Toast.makeText(this, "No image", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Thread {
            try {
                val file = FileUtils.copyUriToCache(this, Uri.parse(uriString), "input_image")
                val bmp = decodeDownsampled(file)
                runOnUiThread {
                    if (bmp == null) {
                        Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                        finish()
                        return@runOnUiThread
                    }
                    inputFile = file
                    bitmap = bmp
                    adjustView.setImage(bmp, circle, dpBackground, dpBgColor)
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }

    /** Paint the Outer-area chips + colour swatches for the current
     *  selection. The colour row only shows when Color is picked. */
    private fun updateDpChips() {
        val density = resources.displayMetrics.density
        val defs = mutableListOf(
            ImageCompressor.DpBackground.BLUR,
            ImageCompressor.DpBackground.COLOR
        )
        if (circle) defs.add(ImageCompressor.DpBackground.TRANSPARENT)
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
        // v0.1.135 (Batch 19): INVISIBLE, never GONE — the
        // colour row reserves its space even when Color is not
        // picked, so the bottom panel's height (and the crop
        // view's bottom margin) never changes when the row
        // appears or disappears.
        colorRow.visibility =
            if (dpBackground == ImageCompressor.DpBackground.COLOR) View.VISIBLE else View.INVISIBLE
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

    private fun decodeDownsampled(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > 1600) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            val degrees = try {
                when (ExifInterface(file.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (_: Throwable) {
                0f
            }
            if (degrees != 0f) {
                val m = android.graphics.Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                bmp.recycle()
                bmp = rotated
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }

    /** Save tap (v0.1.135, Batch 19): render the picked frame
     *  full-res, then show a PREVIEW of the cropped result — the
     *  user sees the actual DP before it is saved. Saving happens
     *  from the preview ([saveRendered]). */
    private fun save() {
        if (saving) return
        val input = inputFile ?: return
        saving = true
        tvStatus.text = "Rendering preview…"
        tvStatus.visibility = View.VISIBLE
        Thread {
            try {
                val ext =
                    if (circle && dpBackground == ImageCompressor.DpBackground.TRANSPARENT) "png" else "jpg"
                val outFile = File(cacheDir, "hakunaa_dp_${System.currentTimeMillis()}.$ext")
                // Crop tool (v0.1.133, Batch 17): the frame the
                // user picked, in bitmap pixels — Circle masks it
                // with the outer area, Square saves the crop.
                val rect = adjustView.getCropRectPx()
                val ok = if (circle) {
                    ImageCompressor.dpCircleRect(
                        input, outFile, rect.left, rect.top, rect.width(),
                        dpBackground, dpBgColor
                    )
                } else {
                    ImageCompressor.dpSquareRect(
                        input, outFile, rect.left, rect.top, rect.width()
                    )
                }
                runOnUiThread {
                    saving = false
                    tvStatus.text = ""
                    tvStatus.visibility = View.GONE
                    if (ok && outFile.exists()) {
                        previewFile = outFile
                        showCropPreview(outFile)
                    } else {
                        tvStatus.text = "Failed — try another image"
                        tvStatus.visibility = View.VISIBLE
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    saving = false
                    tvStatus.text = "Failed — try another image"
                    tvStatus.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    /** The after-crop preview (v0.1.135, Batch 19): the REAL
     *  rendered output, full screen and pinch-zoomable
     *  ([ZoomableImageView], 1x–4x, double-tap to reset) — Back
     *  returns to the crop tool (frame unchanged, preview file
     *  deleted), Save writes it to Pictures and shows the
     *  professional Saved popup. */
    private fun showCropPreview(outFile: File) {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCancelable(true)
        dialog.setOnDismissListener {
            previewDialog = null
            // The rendered preview was not saved — drop it; a
            // fresh one is rendered if the user taps Save again.
            previewFile?.let { f ->
                if (f == outFile) {
                    try { f.delete() } catch (_: Throwable) {}
                    previewFile = null
                }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.hakunaa_background))
        }
        // Top bar: X + "Preview".
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
        close.setOnClickListener { if (!saving) dialog.dismiss() }
        val title = TextView(this).apply {
            text = "Preview"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        topBar.addView(close)
        topBar.addView(title)
        root.addView(topBar)
        // The rendered DP, pinch-zoomable.
        val previewView = ZoomableImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
            setBackgroundColor(getColor(R.color.hakunaa_background))
        }
        previewView.setImageFile(outFile)
        root.addView(previewView)
        // Bottom bar: Back / Save.
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                (16 * density).toInt(), (8 * density).toInt(),
                (16 * density).toInt(), (16 * density).toInt()
            )
        }
        val backBtn = MaterialButton(this).apply {
            text = "Back"
            setTextColor(getColor(R.color.hakunaa_teal))
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
            strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            strokeWidth = (2 * density).toInt()
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                0, (56 * density).toInt(), 1f
            ).apply { marginEnd = (6 * density).toInt() }
        }
        backBtn.setOnClickListener { if (!saving) dialog.dismiss() }
        val saveBtn = MaterialButton(this).apply {
            text = "Save"
            setTextColor(getColor(android.R.color.white))
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                0, (56 * density).toInt(), 1f
            ).apply { marginStart = (6 * density).toInt() }
        }
        saveBtn.setOnClickListener { saveRendered(outFile, dialog) }
        bottomBar.addView(backBtn)
        bottomBar.addView(saveBtn)
        root.addView(bottomBar)
        dialog.setContentView(root)
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.show()
        previewDialog = dialog
    }

    /** Save the previewed output to Pictures (from the preview
     *  dialog) and show the professional Saved popup. */
    private fun saveRendered(outFile: File, dialog: Dialog) {
        if (saving) return
        val input = inputFile ?: return
        saving = true
        Thread {
            try {
                val fmt = ImageCompressor.formatOf(outFile)
                val saved = MediaStoreSaver.save(
                    this, outFile, MediaStoreSaver.Kind.IMAGE, outFile.name, fmt.mime
                )
                runOnUiThread {
                    saving = false
                    if (saved != null) {
                        // The preview did its job — close it first
                        // (previewFile = null so the dismiss
                        // listener does not delete the saved file),
                        // then the professional Saved popup.
                        previewFile = null
                        dialog.dismiss()
                        SavedDialog.show(
                            this, outFile, saved, fmt, input,
                            onDismiss = { finish() }
                        )
                    } else {
                        Toast.makeText(
                            this,
                            "Couldn't save to Pictures — please try again",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    saving = false
                    Toast.makeText(
                        this,
                        "Couldn't save — please try again",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewDialog?.dismiss()
        previewDialog = null
        previewFile?.let { f ->
            try { f.delete() } catch (_: Throwable) {}
            previewFile = null
        }
        bitmap?.recycle()
        bitmap = null
    }
}
