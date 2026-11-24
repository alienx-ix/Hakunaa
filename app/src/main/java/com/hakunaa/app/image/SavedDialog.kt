package com.hakunaa.app.image

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import java.io.File

/**
 * The professional save confirmation (v0.1.123 in Convert, v0.1.124
 * in EVERY save place): a clear popup — NOT a small line at the
 * bottom. Shows the saved file (thumbnail + name), where it went,
 * and its size, with Open / Share / Done actions — like
 * professional apps. The thumbnail shows the input photo (the
 * same image) — the saved file itself may not decode in-app
 * (TIFF).
 */
object SavedDialog {

    fun show(
        activity: AppCompatActivity,
        outFile: File,
        savedUri: Uri,
        format: ImageCompressor.Format,
        thumbFile: File? = null,
        onDismiss: (() -> Unit)? = null,
        // v0.1.176: theme accent — teal by default (Image tools);
        // PDF tools pass R.color.hakunaa_blue.
        accentRes: Int = R.color.hakunaa_teal
    ) {
        val density = activity.resources.displayMetrics.density
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(true)
        val outer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
        }
        val card = MaterialCardView(activity).apply {
            radius = 20f * density
            cardElevation = 8f * density
            setCardBackgroundColor(activity.getColor(R.color.hakunaa_surface))
        }
        val inner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (24 * density).toInt(), (24 * density).toInt(),
                (24 * density).toInt(), (20 * density).toInt()
            )
        }
        val icon = TextView(activity).apply {
            text = "✓"
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(activity.getColor(android.R.color.white))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(activity.getColor(accentRes))
            }
            layoutParams = LinearLayout.LayoutParams(
                (48 * density).toInt(), (48 * density).toInt()
            )
        }
        val title = TextView(activity).apply {
            text = "Saved"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(activity.getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        val thumb = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                (120 * density).toInt(), (120 * density).toInt()
            ).apply { topMargin = (16 * density).toInt() }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(activity.getColor(R.color.hakunaa_outline))
                cornerRadius = 12f * density
            }
            clipToOutline = true
        }
        thumbFile?.let {
            BitmapFactory.decodeFile(it.absolutePath)?.let { bmp -> thumb.setImageBitmap(bmp) }
        }
        val name = TextView(activity).apply {
            text = outFile.name
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(activity.getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val details = TextView(activity).apply {
            text = "Pictures/Hakunaa • ${FileUtils.formatSize(outFile.length())} • ${format.name}"
            textSize = 13f
            setTextColor(activity.getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (20 * density).toInt(), 0, 0)
        }
        fun actionButton(text: String, filled: Boolean): MaterialButton {
            return MaterialButton(activity).apply {
                this.text = text
                cornerRadius = (12 * density).toInt()
                setPadding((16 * density).toInt(), 0, (16 * density).toInt(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (4 * density).toInt()
                    marginEnd = (4 * density).toInt()
                }
                if (filled) {
                    setTextColor(activity.getColor(android.R.color.white))
                    backgroundTintList = ColorStateList.valueOf(activity.getColor(accentRes))
                } else {
                    setTextColor(activity.getColor(accentRes))
                    backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.hakunaa_surface))
                    strokeColor = ColorStateList.valueOf(activity.getColor(accentRes))
                    strokeWidth = (1 * density).toInt()
                }
            }
        }
        val openBtn = actionButton("Open", false)
        val shareBtn = actionButton("Share", false)
        val doneBtn = actionButton("Done", true)
        openBtn.setOnClickListener {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(savedUri, format.mime)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(intent)
            } catch (_: Throwable) {
                Toast.makeText(activity, "No app can open this format", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        shareBtn.setOnClickListener {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = format.mime
                    putExtra(android.content.Intent.EXTRA_STREAM, savedUri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(android.content.Intent.createChooser(intent, "Share"))
            } catch (_: Throwable) {
                Toast.makeText(activity, "Couldn't share", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        doneBtn.setOnClickListener { dialog.dismiss() }
        buttons.addView(openBtn)
        buttons.addView(shareBtn)
        buttons.addView(doneBtn)
        inner.addView(icon)
        inner.addView(title)
        inner.addView(thumb)
        inner.addView(name)
        inner.addView(details)
        inner.addView(buttons)
        card.addView(inner)
        outer.addView(card)
        dialog.setContentView(outer)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }
        dialog.show()
    }
}
