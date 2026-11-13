package com.hakunaa.app.scanner

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.pdf.PdfTools
import java.io.File

/**
 * Export-quality picker — its OWN SCREEN, not a dialog over the
 * camera. (v0.1.39 showed it as a dialog: the camera kept
 * running and even auto-capturing behind it. Now the scanner
 * activity stops while this screen is open, so no scan runs in
 * the background — the camera is released by the lifecycle.)
 *
 * The user picks one of three tiers, each shown with its REAL
 * file size (the three PDFs are built first, then the options
 * appear with the measured sizes):
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
 * Cancelling (back / Cancel) asks first (v0.1.67, CamScanner-
 * style): "Are you sure to discard this image?" for a single
 * page, "Are you sure to discard all N pages?" for a batch,
 * with Discard / Cancel. Discard deletes the temp PDFs and
 * returns RESULT_DISCARD, so the scanner drops the pages and
 * the camera comes back fresh (no "N page(s) • Done" left at
 * the bottom). Cancel stays on the export screen.
 */
class PdfExportActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE_PATHS = "page_paths"
        const val EXTRA_DOC_NAME = "doc_name"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_SIZE_BYTES = "size_bytes"
        /** Returned when the user confirms discarding the pages on the export screen. */
        const val RESULT_DISCARD = 1001
    }

    enum class Tier(val note: String) {
        ORIGINAL("Full camera quality — largest file"),
        COMPRESSED("Balanced — crisp at print size"),
        EXTREME("Smallest file — for sharing")
    }

    private var built: List<Pair<Tier, File>> = emptyList()
    private var safeName = "Hakunaa Scan"
    private var finished = false
    private var pageCount = 0
    private var discardDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_export)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            askDiscard()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                askDiscard()
            }
        })

        val paths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS) ?: arrayListOf()
        val docName = intent.getStringExtra(EXTRA_DOC_NAME) ?: "Hakunaa Scan"
        safeName = docName.replace(Regex("[^A-Za-z0-9 _.-]"), "_")
        val pages = paths.map { File(it) }.filter { it.exists() }
        if (pages.isEmpty()) {
            Toast.makeText(this, "No pages to export", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        pageCount = pages.size

        findViewById<View>(R.id.btnCancelExport).setOnClickListener { askDiscard() }
        buildVariants(pages)
    }

    private fun buildVariants(pages: List<File>) {
        Thread {
            try {
                val variants = listOf(
                    Tier.ORIGINAL to File(cacheDir, "export_original.pdf"),
                    Tier.COMPRESSED to File(cacheDir, "export_compressed.pdf"),
                    Tier.EXTREME to File(cacheDir, "export_extreme.pdf")
                )
                val done = mutableListOf<Pair<Tier, File>>()
                for ((tier, file) in variants) {
                    val ok = when (tier) {
                        Tier.ORIGINAL -> PdfTools.imageToPdfOriginal(pages, file)
                        Tier.COMPRESSED -> PdfTools.imageToPdfOptimized(pages, file)
                        Tier.EXTREME -> PdfTools.imageToPdfExtreme(pages, file)
                    }
                    if (ok && file.exists()) done.add(tier to file)
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (done.isEmpty()) {
                        Toast.makeText(this, "Couldn't make the PDF — try again", Toast.LENGTH_SHORT).show()
                        finish()
                        return@runOnUiThread
                    }
                    built = done
                    showOptions()
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(this, "Couldn't make the PDF — try again", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }

    private fun showOptions() {
        findViewById<View>(R.id.layoutPreparing).visibility = View.GONE
        findViewById<View>(R.id.layoutOptions).visibility = View.VISIBLE
        val byTier = built.toMap()
        val rows = mapOf(
            Tier.ORIGINAL to Triple(R.id.rowOriginal, R.id.tvOriginalSize, R.id.tvOriginalNote),
            Tier.COMPRESSED to Triple(R.id.rowCompressed, R.id.tvCompressedSize, R.id.tvCompressedNote),
            Tier.EXTREME to Triple(R.id.rowExtreme, R.id.tvExtremeSize, R.id.tvExtremeNote)
        )
        for ((tier, ids) in rows) {
            val row = findViewById<View>(ids.first)
            val file = byTier[tier]
            if (file == null) {
                // That tier failed to build — hide its row.
                row.visibility = View.GONE
                continue
            }
            findViewById<TextView>(ids.second).text = FileUtils.formatSize(file.length())
            findViewById<TextView>(ids.third).text = tier.note
            row.setOnClickListener { saveChosen(file) }
        }
    }

    private fun saveChosen(chosen: File) {
        // Prevent double taps saving twice.
        if (finished) return
        finished = true
        Thread {
            try {
                val outName = "$safeName.pdf"
                val saved = MediaStoreSaver.save(
                    this, chosen, MediaStoreSaver.Kind.DOCUMENT, outName
                ) != null
                val size = chosen.length()
                deleteTemps()
                runOnUiThread {
                    if (saved) {
                        val data = Intent()
                        data.putExtra(EXTRA_FILE_NAME, outName)
                        data.putExtra(EXTRA_SIZE_BYTES, size)
                        setResult(RESULT_OK, data)
                        finish()
                    } else {
                        finished = false
                        Toast.makeText(this, "Save failed — try again", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    finished = false
                    Toast.makeText(this, "Save failed — try again", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Cancel / back (v0.1.67): ask FIRST, CamScanner-style,
     * before anything is discarded. Single page: "Are you sure
     * to discard this image?"; batch: "Are you sure to discard
     * all N pages?". Discard drops the pages (RESULT_DISCARD);
     * Cancel just closes this dialog and stays on export.
     */
    private fun askDiscard() {
        if (discardDialog?.isShowing == true) return
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.dialog_discard)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.85).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.findViewById<TextView>(R.id.tvDiscardMessage).text =
            if (pageCount <= 1) "Are you sure to discard this image?"
            else "Are you sure to discard all $pageCount pages?"
        dialog.findViewById<View>(R.id.btnDiscardCancel).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnDiscardConfirm).setOnClickListener {
            dialog.dismiss()
            discardAndFinish()
        }
        dialog.setCancelable(true)
        discardDialog = dialog
        dialog.show()
    }

    private fun discardAndFinish() {
        if (finished) return
        finished = true
        deleteTemps()
        setResult(RESULT_DISCARD)
        finish()
    }

    private fun deleteTemps() {
        for ((_, file) in built) {
            try { file.delete() } catch (_: Throwable) { }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // If the system killed the screen mid-build, the temps
        // are cache files and the OS clears them anyway.
        if (!finished) deleteTemps()
    }
}
