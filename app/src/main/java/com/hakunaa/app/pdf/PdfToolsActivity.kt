package com.hakunaa.app.pdf

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
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
                try {
                    val input = FileUtils.copyUriToCache(this, uri, "image_to_pdf")
                    val outDir = hakunaaCacheDir()
                    val out = File(outDir, "hakunaa_image_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.imageToPdf(input, out) && publishPdf(out)
                    runOnUiThread {
                        status.text = if (ok) "Done — saved to Documents/Hakunaa/${out.name}"
                        else "Failed — try another image"
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "Failed — try another image" }
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

    // PDF work writes to a temp folder in the app cache; finished files
    // are published to Documents/Hakunaa via MediaStore (scoped storage).
    private fun hakunaaCacheDir(): File =
        File(cacheDir, "pdf").apply { mkdirs() }

    private fun publishPdf(file: File): Boolean =
        MediaStoreSaver.save(this, file, MediaStoreSaver.Kind.DOCUMENT, file.name) != null

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
            try {
                val inputs = pickedUris.mapIndexed { index, uri ->
                    FileUtils.copyUriToCache(this, uri, "merge_$index.pdf")
                }
                val out = File(hakunaaCacheDir(), "hakunaa_merged_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.merge(inputs, out) && publishPdf(out)
                runOnUiThread {
                    status.text = if (ok) "Done — saved to Documents/Hakunaa/${out.name}"
                    else "Merge failed — check the files and try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Merge failed — check the files and try again" }
            }
        }.start()
    }

    private fun splitPdf() {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Splitting PDF…"
        Thread {
            try {
                val outDir = File(hakunaaCacheDir(), "split_${System.currentTimeMillis()}").apply { mkdirs() }
                val created = PdfTools.split(input, outDir)
                val published = created.count { publishPdf(it) }
                runOnUiThread {
                    status.text = if (published > 0) "Done — $published pages saved to Documents/Hakunaa"
                    else "Split failed — check the file and try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Split failed — check the file and try again" }
            }
        }.start()
    }

    private fun compressPdf() {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Compressing PDF (lossless)…"
        Thread {
            try {
                val before = input.length()
                val out = File(hakunaaCacheDir(), "hakunaa_compressed_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.compressLossless(input, out) && publishPdf(out)
                runOnUiThread {
                    if (ok) {
                        val after = out.length()
                        status.text = "Done — ${FileUtils.formatSize(before)} → ${FileUtils.formatSize(after)} " +
                            "(${FileUtils.savedPercent(before, after)}), saved to Documents/Hakunaa/${out.name}"
                    } else {
                        status.text = "Compress failed — check the file and try again"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Compress failed — check the file and try again" }
            }
        }.start()
    }

    private fun compressSmartPdf() {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Compressing PDF (smart — 150 DPI, JPEG 82%)…"
        Thread {
            try {
                val before = input.length()
                val out = File(hakunaaCacheDir(), "hakunaa_smart_${System.currentTimeMillis()}.pdf")
                val replaced = PdfTools.compressSmart(input, out)
                val ok = replaced >= 0 && publishPdf(out)
                runOnUiThread {
                    if (ok) {
                        val after = out.length()
                        val note = if (replaced == 0)
                            " No images to compress — this PDF is text-only, so Smart can't shrink it."
                        else " $replaced image(s) compressed."
                        status.text = "Done — ${FileUtils.formatSize(before)} → ${FileUtils.formatSize(after)} " +
                            "(${FileUtils.savedPercent(before, after)}), saved to Documents/Hakunaa/${out.name}.$note"
                    } else {
                        status.text = "Compress failed — check the file and try again"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Compress failed — check the file and try again" }
            }
        }.start()
    }
}
