package com.hakunaa.app.pdf

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import java.io.File

class PdfToolsActivity : AppCompatActivity() {
    private val pickedUris = mutableListOf<Uri>()

    private val pickPdfs =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            pickedUris.clear()
            pickedUris.addAll(uris)
            findViewById<TextView>(R.id.tvFiles).text =
                if (uris.isEmpty()) getString(R.string.ready)
                else "${uris.size} PDFs selected"
        }

    private val pickImageForPdf =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val status = findViewById<TextView>(R.id.tvStatus)
            status.text = "Creating PDF from image…"
            Thread {
                val input = FileUtils.copyUriToCache(this, uri, "image_to_pdf")
                val outDir = hakunaaDocumentsDir()
                val out = File(outDir, "hakunaa_image_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.imageToPdf(input, out)
                runOnUiThread {
                    status.text = if (ok) "Done — saved to Documents/Hakunaa/${out.name}"
                    else "Failed — try another image"
                }
            }.start()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_tools)

        findViewById<Button>(R.id.btnPickPdfs).setOnClickListener { pickPdfs.launch("application/pdf") }
        findViewById<Button>(R.id.btnMerge).setOnClickListener { mergePdfs() }
        findViewById<Button>(R.id.btnSplit).setOnClickListener { splitPdf() }
        findViewById<Button>(R.id.btnCompressPdf).setOnClickListener { compressPdf() }
        findViewById<Button>(R.id.btnCompressSmart).setOnClickListener { compressSmartPdf() }
        findViewById<Button>(R.id.btnImageToPdf).setOnClickListener { pickImageForPdf.launch("image/*") }
    }

    private fun hakunaaDocumentsDir(): File =
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "Hakunaa"
        ).apply { mkdirs() }

    private fun firstPickedPdf(): File? {
        val uri = pickedUris.firstOrNull() ?: run {
            Toast.makeText(this, "Pick a PDF first", Toast.LENGTH_SHORT).show()
            return null
        }
        return FileUtils.copyUriToCache(this, uri, "single.pdf")
    }

    private fun mergePdfs() {
        if (pickedUris.size < 2) {
            Toast.makeText(this, getString(R.string.pick_first_pdfs), Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Merging ${pickedUris.size} PDFs…"
        Thread {
            val inputs = pickedUris.mapIndexed { index, uri ->
                FileUtils.copyUriToCache(this, uri, "merge_$index.pdf")
            }
            val out = File(hakunaaDocumentsDir(), "hakunaa_merged_${System.currentTimeMillis()}.pdf")
            val ok = PdfTools.merge(inputs, out)
            runOnUiThread {
                status.text = if (ok) "Done — saved to Documents/Hakunaa/${out.name}"
                else "Merge failed — check the files and try again"
            }
        }.start()
    }

    private fun splitPdf() {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Splitting PDF…"
        Thread {
            val outDir = File(hakunaaDocumentsDir(), "split_${System.currentTimeMillis()}").apply { mkdirs() }
            val created = PdfTools.split(input, outDir)
            runOnUiThread {
                status.text = if (created.isNotEmpty()) "Done — ${created.size} pages saved to Documents/Hakunaa/${outDir.name}"
                else "Split failed — check the file and try again"
            }
        }.start()
    }

    private fun compressPdf() {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Compressing PDF (lossless)…"
        Thread {
            val before = input.length()
            val out = File(hakunaaDocumentsDir(), "hakunaa_compressed_${System.currentTimeMillis()}.pdf")
            val ok = PdfTools.compressLossless(input, out)
            runOnUiThread {
                if (ok) {
                    val after = out.length()
                    status.text = "Done — ${FileUtils.formatSize(before)} → ${FileUtils.formatSize(after)} " +
                        "(${FileUtils.savedPercent(before, after)}), saved to Documents/Hakunaa/${out.name}"
                } else {
                    status.text = "Compress failed — check the file and try again"
                }
            }
        }.start()
    }

    private fun compressSmartPdf() {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Compressing PDF (smart — 150 DPI, JPEG 82%)…"
        Thread {
            val before = input.length()
            val out = File(hakunaaDocumentsDir(), "hakunaa_smart_${System.currentTimeMillis()}.pdf")
            val ok = PdfTools.compressSmart(input, out)
            runOnUiThread {
                if (ok) {
                    val after = out.length()
                    status.text = "Done — ${FileUtils.formatSize(before)} → ${FileUtils.formatSize(after)} " +
                        "(${FileUtils.savedPercent(before, after)}), saved to Documents/Hakunaa/${out.name}"
                } else {
                    status.text = "Compress failed — check the file and try again"
                }
            }
        }.start()
    }
}
