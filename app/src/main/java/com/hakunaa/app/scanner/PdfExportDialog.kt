package com.hakunaa.app.scanner

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.pdf.PdfTools
import java.io.File

/**
 * Export-quality picker — shown after a scan is made into a PDF,
 * right when the user taps Save.
 *
 * The user picks one of three tiers, each shown with its REAL
 * file size (the three PDFs are built first, then the dialog
 * opens with the measured sizes):
 *
 * - Original Quality: full camera resolution, JPEG 95 — the
 *   biggest file, the best quality.
 * - Compressed: 2000 px / JPEG 80 — balanced, crisp at print
 *   size (the previous default).
 * - Extreme Compress: 1200 px / JPEG 60 — smallest file, for
 *   sharing over WhatsApp or slow connections.
 *
 * Only the chosen file is saved (Documents/Hakunaa, with the
 * document name); the other two temp files are deleted.
 * Cancelling deletes all three and saves nothing.
 */
object PdfExportDialog {

    enum class Tier(val note: String) {
        ORIGINAL("Full camera quality — largest file"),
        COMPRESSED("Balanced — crisp at print size"),
        EXTREME("Smallest file — for sharing")
    }

    fun show(
        activity: Activity,
        pages: List<File>,
        docName: String,
        onSaved: (fileName: String, sizeBytes: Long) -> Unit
    ) {
        val preparing = MaterialAlertDialogBuilder(activity)
            .setTitle("Preparing export options…")
            .setMessage("Building Original, Compressed and Extreme versions…")
            .setCancelable(false)
            .create()
        preparing.show()
        Thread {
            try {
                val safeName = docName.replace(Regex("[^A-Za-z0-9 _.-]"), "_")
                val variants = listOf(
                    Tier.ORIGINAL to File(activity.cacheDir, "export_original.pdf"),
                    Tier.COMPRESSED to File(activity.cacheDir, "export_compressed.pdf"),
                    Tier.EXTREME to File(activity.cacheDir, "export_extreme.pdf")
                )
                val built = mutableListOf<Pair<Tier, File>>()
                for ((tier, file) in variants) {
                    val ok = when (tier) {
                        Tier.ORIGINAL -> PdfTools.imageToPdfOriginal(pages, file)
                        Tier.COMPRESSED -> PdfTools.imageToPdfOptimized(pages, file)
                        Tier.EXTREME -> PdfTools.imageToPdfExtreme(pages, file)
                    }
                    if (ok && file.exists()) built.add(tier to file)
                }
                activity.runOnUiThread {
                    try { preparing.dismiss() } catch (_: Throwable) { }
                    if (built.isEmpty()) {
                        Toast.makeText(activity, "Couldn't make the PDF — try again", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    showOptions(activity, built, safeName, onSaved)
                }
            } catch (_: Throwable) {
                activity.runOnUiThread {
                    try { preparing.dismiss() } catch (_: Throwable) { }
                    Toast.makeText(activity, "Couldn't make the PDF — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showOptions(
        activity: Activity,
        built: List<Pair<Tier, File>>,
        safeName: String,
        onSaved: (String, Long) -> Unit
    ) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_pdf_export, null)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(true)
            .create()
        val byTier = built.toMap()
        val rows = mapOf(
            Tier.ORIGINAL to Triple(R.id.rowOriginal, R.id.tvOriginalSize, R.id.tvOriginalNote),
            Tier.COMPRESSED to Triple(R.id.rowCompressed, R.id.tvCompressedSize, R.id.tvCompressedNote),
            Tier.EXTREME to Triple(R.id.rowExtreme, R.id.tvExtremeSize, R.id.tvExtremeNote)
        )
        for ((tier, ids) in rows) {
            val row = view.findViewById<View>(ids.first)
            val file = byTier[tier]
            if (file == null) {
                // That tier failed to build — hide its row.
                row.visibility = View.GONE
                continue
            }
            view.findViewById<TextView>(ids.second).text = FileUtils.formatSize(file.length())
            view.findViewById<TextView>(ids.third).text = tier.note
            row.setOnClickListener {
                dialog.dismiss()
                saveChosen(activity, file, built, safeName, onSaved)
            }
        }
        view.findViewById<View>(R.id.btnCancelExport).setOnClickListener {
            dialog.dismiss()
            for ((_, file) in built) {
                try { file.delete() } catch (_: Throwable) { }
            }
        }
        dialog.show()
    }

    private fun saveChosen(
        activity: Activity,
        chosen: File,
        built: List<Pair<Tier, File>>,
        safeName: String,
        onSaved: (String, Long) -> Unit
    ) {
        Thread {
            try {
                val outName = "$safeName.pdf"
                val saved = MediaStoreSaver.save(
                    activity, chosen, MediaStoreSaver.Kind.DOCUMENT, outName
                ) != null
                val size = chosen.length()
                for ((_, file) in built) {
                    try { file.delete() } catch (_: Throwable) { }
                }
                activity.runOnUiThread {
                    if (saved) onSaved(outName, size)
                    else Toast.makeText(activity, "Save failed — try again", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Throwable) {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Save failed — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
