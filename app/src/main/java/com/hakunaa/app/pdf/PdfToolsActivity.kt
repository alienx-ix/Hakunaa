package com.hakunaa.app.pdf

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
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
        findViewById<Button>(R.id.btnRotate).setOnClickListener { rotatePdf() }
        findViewById<Button>(R.id.btnWatermark).setOnClickListener { watermarkPdf() }
        findViewById<Button>(R.id.btnPageNumbers).setOnClickListener { pageNumbersPdf() }
        findViewById<Button>(R.id.btnDeletePages).setOnClickListener { deletePagesPdf() }
        findViewById<Button>(R.id.btnExtractPages).setOnClickListener { extractPagesPdf() }
        findViewById<Button>(R.id.btnExtractImages).setOnClickListener { extractImagesPdf() }
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
                val savedBytes = PdfTools.compressLossless(input, out)
                runOnUiThread {
                    when {
                        savedBytes > 0 && publishPdf(out) -> {
                            val after = out.length()
                            status.text = "Done — ${FileUtils.formatSize(before)} → ${FileUtils.formatSize(after)} " +
                                "(${FileUtils.savedPercent(before, after)}), saved to Documents/Hakunaa/${out.name}"
                        }
                        savedBytes == 0L -> {
                            status.text = "Already optimized — this PDF is as small as it gets " +
                                "without losing quality. Try Smart Compress for a smaller file."
                        }
                        else -> {
                            status.text = "Compress failed — check the file and try again"
                        }
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
        status.text = "Compressing PDF (smart)…"
        Thread {
            try {
                val before = input.length()
                val out = File(hakunaaCacheDir(), "hakunaa_smart_${System.currentTimeMillis()}.pdf")
                // Best Smart Compress: tries 150 DPI / JPEG 82% first,
                // and if the result is not smaller it automatically
                // escalates (100 DPI / 70%, then 72 DPI / 60%) until
                // the output IS smaller. Never hands back a bigger file.
                val (replaced, note) = PdfTools.compressSmartBest(input, out)
                runOnUiThread {
                    when {
                        replaced > 0 && out.exists() && publishPdf(out) -> {
                            val after = out.length()
                            status.text = "Done — ${FileUtils.formatSize(before)} → ${FileUtils.formatSize(after)} " +
                                "(${FileUtils.savedPercent(before, after)}), saved to Documents/Hakunaa/${out.name}. $replaced image(s) compressed.$note"
                        }
                        replaced == 0 && note == "already" -> {
                            status.text = "Already optimized — even the strongest Smart settings couldn't shrink this PDF. Try Lossless Compress, or the images are already as small as they get."
                        }
                        else -> {
                            status.text = "Compress failed — check the file and try again"
                        }
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Compress failed — check the file and try again" }
            }
        }.start()
    }

    private fun selectedRotation(): Int {
        return when (findViewById<RadioGroup>(R.id.rgRotate).checkedRadioButtonId) {
            R.id.rbRotate180 -> 180
            R.id.rbRotate270 -> 270
            else -> 90
        }
    }

    private fun rotatePdf() {
        val input = firstPickedPdf() ?: return
        val degrees = selectedRotation()
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Rotating PDF by $degrees°…"
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_rotated_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.rotate(input, out, degrees) && publishPdf(out)
                runOnUiThread {
                    status.text = if (ok) "Done — rotated $degrees°, saved to Documents/Hakunaa/${out.name}"
                    else "Rotate failed — check the file and try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Rotate failed — check the file and try again" }
            }
        }.start()
    }

    private fun watermarkPdf() {
        val input = firstPickedPdf() ?: return
        val text = findViewById<EditText>(R.id.etWatermark).text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Type a watermark text first", Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Adding watermark…"
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_watermarked_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.watermark(input, out, text) && publishPdf(out)
                runOnUiThread {
                    status.text = if (ok) "Done — watermarked, saved to Documents/Hakunaa/${out.name}"
                    else "Watermark failed — check the file and try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Watermark failed — check the file and try again" }
            }
        }.start()
    }

    private fun pageNumbersPdf() {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Adding page numbers…"
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_numbered_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.pageNumbers(input, out) && publishPdf(out)
                runOnUiThread {
                    status.text = if (ok) "Done — page numbers added, saved to Documents/Hakunaa/${out.name}"
                    else "Page numbers failed — check the file and try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Page numbers failed — check the file and try again" }
            }
        }.start()
    }

    private fun readPageSelection(): Set<Int>? {
        val text = findViewById<EditText>(R.id.etPages).text.toString()
        val pages = PdfTools.parsePageSelection(text)
        if (pages == null) {
            Toast.makeText(
                this, "Type pages like 1,3,5-7", Toast.LENGTH_SHORT
            ).show()
        }
        return pages
    }

    private fun deletePagesPdf() {
        val input = firstPickedPdf() ?: return
        val pages = readPageSelection() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Deleting ${pages.size} page(s)…"
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_deleted_${System.currentTimeMillis()}.pdf")
                val removed = PdfTools.deletePages(input, out, pages)
                val ok = removed >= 0 && publishPdf(out)
                runOnUiThread {
                    status.text = if (ok) "Done — $removed page(s) deleted, saved to Documents/Hakunaa/${out.name}"
                    else "Delete pages failed — check the pages and try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Delete pages failed — check the pages and try again" }
            }
        }.start()
    }

    private fun extractPagesPdf() {
        val input = firstPickedPdf() ?: return
        val pages = readPageSelection() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Extracting ${pages.size} page(s)…"
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_extracted_${System.currentTimeMillis()}.pdf")
                val kept = PdfTools.extractPages(input, out, pages)
                val ok = kept >= 0 && publishPdf(out)
                runOnUiThread {
                    status.text = if (ok) "Done — $kept page(s) extracted, saved to Documents/Hakunaa/${out.name}"
                    else "Extract pages failed — check the pages and try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Extract pages failed — check the pages and try again" }
            }
        }.start()
    }

    private fun extractImagesPdf() {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Extracting images…"
        Thread {
            try {
                val outDir = File(hakunaaCacheDir(), "images_${System.currentTimeMillis()}").apply { mkdirs() }
                val created = PdfTools.extractImages(input, outDir)
                var published = 0
                for (file in created) {
                    if (MediaStoreSaver.save(this, file, MediaStoreSaver.Kind.IMAGE, file.name) != null) {
                        published++
                    }
                }
                runOnUiThread {
                    status.text = if (published > 0) "Done — $published image(s) saved to Pictures/Hakunaa"
                    else if (created.isEmpty()) "No images found in this PDF"
                    else "Extract failed — check the file and try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Extract failed — check the file and try again" }
            }
        }.start()
    }
}
