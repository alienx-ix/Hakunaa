package com.hakunaa.app.image

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import java.io.File

/**
 * Full-screen preview (v0.1.121) — Preview + Compare in one dialog.
 *
 * Preview mode: the compressed result, full screen, pinch-zoom
 * (1x–4x), pan, double-tap reset. A Compare button sits below
 * the image. Compare mode: a SPLIT — the original (Before) on
 * TOP and the compressed result (After) BELOW, each labelled
 * with format, dimensions, file size and % saved. A Preview
 * button switches back. Save saves the result and closes.
 */
class FullPreviewDialog(
    context: Context,
    private val original: File,
    private val result: ImageCompressor.LevelResult,
    private val originalSize: Long,
    private val originalFormat: ImageCompressor.Format,
    private val originalW: Int,
    private val originalH: Int,
    private val onSave: () -> Unit
) : Dialog(context) {

    private var compareMode = false
    private lateinit var contentHolder: LinearLayout
    private lateinit var toggleBtn: MaterialButton
    private lateinit var titleView: TextView
    private lateinit var infoLine: TextView
    private val density = context.resources.displayMetrics.density

    private val beforeInfo: String by lazy {
        "Before • ${originalFormat.name} • ${originalW} × ${originalH} • " +
            FileUtils.formatSize(originalSize)
    }
    private val afterInfo: String by lazy {
        val dims = imageDims(result.file)
        "After • ${result.format.name} • ${dims.first} × ${dims.second} • " +
            "${FileUtils.formatSize(result.size)} • saved ${result.savingPercent}%"
    }

    private fun imageDims(file: File): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            options.outWidth to options.outHeight
        } catch (_: Throwable) {
            0 to 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawableResource(android.R.color.black)
        setCancelable(true)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Top bar: X + title.
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (8 * density).toInt(), (8 * density).toInt(),
                (16 * density).toInt(), (8 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val close = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                (40 * density).toInt(), (40 * density).toInt()
            )
        }
        close.setOnClickListener { dismiss() }
        titleView = TextView(context).apply {
            text = "Preview"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding((12 * density).toInt(), 0, 0, 0)
        }
        topBar.addView(close)
        topBar.addView(titleView)
        root.addView(topBar)

        // Content area — swaps between Preview and Compare.
        contentHolder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(contentHolder)

        // Info line (Preview mode only — Compare shows its own labels).
        infoLine = TextView(context).apply {
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(
                (16 * density).toInt(), (8 * density).toInt(),
                (16 * density).toInt(), (4 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(infoLine)

        // Bottom bar: Compare/Preview toggle + Save.
        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(
                (16 * density).toInt(), (8 * density).toInt(),
                (16 * density).toInt(), (16 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        toggleBtn = MaterialButton(context).apply {
            text = "Compare"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(context.getColor(R.color.hakunaa_teal))
            backgroundTintList = ColorStateList.valueOf(Color.BLACK)
            strokeColor = ColorStateList.valueOf(context.getColor(R.color.hakunaa_teal))
            strokeWidth = (2 * density).toInt()
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                0, (56 * density).toInt(), 1f
            ).apply { marginEnd = (6 * density).toInt() }
        }
        toggleBtn.setOnClickListener {
            if (compareMode) showPreviewMode() else showCompareMode()
        }
        val saveBtn = MaterialButton(context).apply {
            text = "Save"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            backgroundTintList =
                ColorStateList.valueOf(context.getColor(R.color.hakunaa_teal))
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                0, (56 * density).toInt(), 1f
            ).apply { marginStart = (6 * density).toInt() }
        }
        saveBtn.setOnClickListener {
            dismiss()
            onSave()
        }
        bottomBar.addView(toggleBtn)
        bottomBar.addView(saveBtn)
        root.addView(bottomBar)

        setContentView(root)
        window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        showPreviewMode()
    }

    /** Preview mode: the result, full screen and zoomable. */
    private fun showPreviewMode() {
        compareMode = false
        contentHolder.removeAllViews()
        val zv = ZoomableImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }
        zv.setImageFile(result.file)
        contentHolder.addView(zv)
        toggleBtn.text = "Compare"
        titleView.text = "Preview"
        infoLine.text = afterInfo
        infoLine.visibility = View.VISIBLE
    }

    /** Compare mode: Before on TOP, After BELOW, each labelled. */
    private fun showCompareMode() {
        compareMode = true
        contentHolder.removeAllViews()
        contentHolder.addView(compareHalf(original, beforeInfo))
        contentHolder.addView(View(context).apply {
            setBackgroundColor(context.getColor(R.color.hakunaa_teal))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (2 * density).toInt()
            )
        })
        contentHolder.addView(compareHalf(result.file, afterInfo))
        toggleBtn.text = "Preview"
        titleView.text = "Compare"
        infoLine.visibility = View.GONE
    }

    /** One half of the Compare split: an info label + a zoomable image. */
    private fun compareHalf(file: File, info: String): LinearLayout {
        val half = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val label = TextView(context).apply {
            text = info
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFF222222.toInt())
            setPadding(
                (12 * density).toInt(), (8 * density).toInt(),
                (12 * density).toInt(), (8 * density).toInt()
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val zv = ZoomableImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.BLACK)
        }
        zv.setImageFile(file)
        half.addView(label)
        half.addView(zv)
        return half
    }
}
