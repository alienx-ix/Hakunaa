package com.hakunaa.app.whatsapp

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.image.ImageCompressor
import com.hakunaa.app.pdf.PdfTools
import com.hakunaa.app.video.VideoCompressor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File

/**
 * WhatsApp Optimize — one tap: pick a video, photo or PDF, see its
 * details + preview, confirm, and get a copy that fits WhatsApp.
 * Targets: video ≤ 16 MB (WhatsApp's media limit — bigger videos
 * get re-compressed by WhatsApp itself), photo ≤ 300 KB at max
 * 1600 px (WhatsApp downscales photos anyway), PDF ≤ 16 MB via
 * Smart Compress. Output is never bigger than the input — if a
 * file is already within limits, it is saved as-is and shared.
 */
class WhatsAppOptimizeActivity : AppCompatActivity() {

    companion object {
        const val VIDEO_TARGET_BYTES = 16L * 1024 * 1024
        const val IMAGE_TARGET_BYTES = 200L * 1024
        const val IMAGE_MAX_DIMENSION = 1600
        const val PDF_TARGET_BYTES = 16L * 1024 * 1024
    }

    private enum class Kind { VIDEO, IMAGE, PDF }

    private var pickedFile: File? = null
    private var pickedKind: Kind? = null
    private var pickedName: String = "file"
    private var optimizing = false
    private var savedUri: Uri? = null
    private var savedMime: String = ""

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onPicked(it, Kind.VIDEO) }
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onPicked(it, Kind.IMAGE) }
    }
    private val pickPdf = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onPicked(it, Kind.PDF) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whatsapp_optimize)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { if (!optimizing) pickVideo.launch("video/*") }
        findViewById<Button>(R.id.btnPickImage).setOnClickListener { if (!optimizing) pickImage.launch("image/*") }
        findViewById<Button>(R.id.btnPickPdf).setOnClickListener { if (!optimizing) pickPdf.launch("application/pdf") }
        findViewById<Button>(R.id.btnOptimize).setOnClickListener { if (!optimizing) startOptimize() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { shareSaved() }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }
    }

    private fun onPicked(uri: Uri, kind: Kind) {
        val name = FileUtils.fileName(contentResolver, uri)
        val ext = when (kind) {
            Kind.VIDEO -> "mp4"
            Kind.IMAGE -> "jpg"
            Kind.PDF -> "pdf"
        }
        val file = try {
            FileUtils.copyUriToCache(this, uri, "wa_pick_${System.currentTimeMillis()}.$ext")
        } catch (_: Throwable) {
            toast("Couldn't read that file")
            return
        }
        pickedFile = file
        pickedKind = kind
        pickedName = name
        savedUri = null
        findViewById<MaterialCardView>(R.id.cardResult).visibility = View.GONE
        showDetails(file, kind, name)
        findViewById<Button>(R.id.btnOptimize).visibility = View.VISIBLE
        setStatus("Ready — tap Optimize for WhatsApp to start.")
    }

    private fun showDetails(file: File, kind: Kind, name: String) {
        val details = StringBuilder()
        details.append(name).append("\n").append(FileUtils.formatSize(file.length()))
        val preview = findViewById<ImageView>(R.id.ivPreview)
        preview.setImageBitmap(null)
        preview.visibility = View.GONE
        when (kind) {
            Kind.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
                    details.append(" • ${formatDuration(durMs)} • ${w}x$h")
                    retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                        preview.setImageBitmap(it)
                        preview.visibility = View.VISIBLE
                    }
                } catch (_: Throwable) {
                } finally {
                    try { retriever.release() } catch (_: Throwable) { }
                }
                findViewById<TextView>(R.id.tvTarget).text =
                    "Target: under 16 MB, H.264 — so WhatsApp sends it as-is instead of re-compressing it."
            }
            Kind.IMAGE -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                details.append(" • ${bounds.outWidth}x${bounds.outHeight} px")
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 800) }
                BitmapFactory.decodeFile(file.absolutePath, opts)?.let {
                    preview.setImageBitmap(it)
                    preview.visibility = View.VISIBLE
                }
                findViewById<TextView>(R.id.tvTarget).text =
                    "Target: under 200 KB, max 1600 px — WhatsApp downscales photos anyway."
            }
            Kind.PDF -> {
                var pages = -1
                try {
                    PDDocument.load(file).use { doc -> pages = doc.numberOfPages }
                } catch (_: Throwable) { }
                if (pages > 0) details.append(" • $pages pages")
                try {
                    PDDocument.load(file).use { doc ->
                        val bmp = PDFRenderer(doc).renderImageWithDPI(0, 72f)
                        preview.setImageBitmap(bmp)
                        preview.visibility = View.VISIBLE
                    }
                } catch (_: Throwable) { }
                findViewById<TextView>(R.id.tvTarget).text =
                    "Target: under 16 MB via Smart Compress — smaller PDFs send faster."
            }
        }
        findViewById<TextView>(R.id.tvDetails).text = details.toString()
        findViewById<MaterialCardView>(R.id.cardDetails).visibility = View.VISIBLE
    }

    private fun startOptimize() {
        val file = pickedFile ?: return
        val kind = pickedKind ?: return
        optimizing = true
        findViewById<Button>(R.id.btnOptimize).visibility = View.GONE
        findViewById<MaterialCardView>(R.id.cardResult).visibility = View.GONE
        val progress = findViewById<ProgressBar>(R.id.progressBar)
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = (kind != Kind.VIDEO)
        progress.progress = 0
        setStatus("Optimizing…")
        when (kind) {
            Kind.VIDEO -> optimizeVideo(file)
            Kind.IMAGE -> optimizeImage(file)
            Kind.PDF -> optimizePdf(file)
        }
    }

    private fun optimizeVideo(file: File) {
        val inputSize = file.length()
        // No "already under the limit" skip: even a video that already
        // fits gets best-compressed (CRF 28 first, quality kept) if it
        // can be made smaller. Only a video that cannot shrink at all
        // is left as-is.
        val out = File(cacheDir, "wa_video_${System.currentTimeMillis()}.mp4")
        VideoCompressor.compressForWhatsApp(
            file.absolutePath, out.absolutePath, inputSize, VIDEO_TARGET_BYTES,
            onProgress = { p -> runOnUiThread { findViewById<ProgressBar>(R.id.progressBar).progress = p } },
            onDone = { ok, note ->
                runOnUiThread {
                    if (!ok) {
                        optimizeFailed("Couldn't optimize: $note")
                    } else {
                        saveAndShow(out, Kind.VIDEO, inputSize, note, "mp4")
                    }
                }
            }
        )
    }

    private fun optimizeImage(file: File) {
        Thread {
            val inputSize = file.length()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            // No "already under the limit" skip: even a photo that
            // already fits gets best-compressed with quality kept.
            // Step 1: resize to max 1600 px if needed (no visible loss
            // on phone screens).
            var working = file
            val largest = maxOf(bounds.outWidth, bounds.outHeight)
            if (largest > IMAGE_MAX_DIMENSION) {
                val resized = File(cacheDir, "wa_img_resize_${System.currentTimeMillis()}.jpg")
                if (ImageCompressor.resize(file, resized, IMAGE_MAX_DIMENSION)) working = resized
            }
            // Step 2: best quality first — try JPEG/WebP 85/70/60 and
            // keep the smallest. If it fits the target, that is the
            // best result: smallest file at the highest quality.
            val best = ImageCompressor.compressBest(working, File(cacheDir, "wa_img_best_${System.currentTimeMillis()}"))
            if (best != null && best.first.length() <= IMAGE_TARGET_BYTES) {
                val (bestFile, format, note) = best
                runOnUiThread { saveAndShow(bestFile, Kind.IMAGE, inputSize, note, format.extension) }
                return@Thread
            }
            // Step 3: still over target — binary-search the JPEG
            // quality to hit it.
            val out = File(cacheDir, "wa_img_${System.currentTimeMillis()}.jpg")
            val targetResult = ImageCompressor.compressToTargetSize(working, out, IMAGE_TARGET_BYTES)
            runOnUiThread {
                val targetOk = targetResult != null && targetResult.exists() && targetResult.length() < inputSize
                when {
                    targetOk -> {
                        best?.first?.let { try { it.delete() } catch (_: Throwable) { } }
                        saveAndShow(targetResult!!, Kind.IMAGE, inputSize, "", "jpg")
                    }
                    best != null -> {
                        // Smallest possible, still over target — keep it anyway.
                        try { out.delete() } catch (_: Throwable) { }
                        val (bestFile, format, note) = best
                        saveAndShow(bestFile, Kind.IMAGE, inputSize, "$note (still over 200 KB, smallest possible)", format.extension)
                    }
                    else -> {
                        try { out.delete() } catch (_: Throwable) { }
                        finishWithOriginal(file, Kind.IMAGE, "Already as small as it gets — ready as-is.")
                    }
                }
            }
        }.start()
    }

    private fun optimizePdf(file: File) {
        Thread {
            val inputSize = file.length()
            val out = File(cacheDir, "wa_pdf_${System.currentTimeMillis()}.pdf")
            PdfTools.compressSmartBest(file, out)
            runOnUiThread {
                if (out.exists() && out.length() < inputSize) {
                    val extra = if (out.length() > PDF_TARGET_BYTES) " Still over 16 MB — WhatsApp will send it as a document." else ""
                    saveAndShow(out, Kind.PDF, inputSize, extra, "pdf")
                } else {
                    // Already the smallest it can be — save the original as-is.
                    try { out.delete() } catch (_: Throwable) { }
                    finishWithOriginal(file, Kind.PDF, "Already as small as it gets — ready as-is.")
                }
            }
        }.start()
    }

    /** The file is already within WhatsApp's limits — save it as-is. */
    private fun finishWithOriginal(file: File, kind: Kind, message: String) {
        val ext = pickedName.substringAfterLast('.', "").ifBlank {
            when (kind) {
                Kind.VIDEO -> "mp4"
                Kind.IMAGE -> "jpg"
                Kind.PDF -> "pdf"
            }
        }
        saveAndShow(file, kind, file.length(), message, ext)
    }

    /**
     * Save [out] to shared storage and show the result card.
     * When [note] is a plain message (already-ready case) it is
     * shown as-is; otherwise the size change is shown with [note]
     * appended. The output is never bigger than the input — callers
     * check that before reaching here.
     */
    private fun saveAndShow(out: File, kind: Kind, before: Long, note: String, ext: String) {
        val after = out.length()
        val displayName = displayNameFor(kind, ext)
        val folderKind = when (kind) {
            Kind.VIDEO -> MediaStoreSaver.Kind.VIDEO
            Kind.IMAGE -> MediaStoreSaver.Kind.IMAGE
            Kind.PDF -> MediaStoreSaver.Kind.DOCUMENT
        }
        val mime = when (ext.lowercase()) {
            "mp4" -> "video/mp4"
            "webp" -> "image/webp"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            else -> "image/jpeg"
        }
        val folder = when (kind) {
            Kind.VIDEO -> "Movies/Hakunaa"
            Kind.IMAGE -> "Pictures/Hakunaa"
            Kind.PDF -> "Documents/Hakunaa"
        }
        val uri = MediaStoreSaver.save(this, out, folderKind, displayName)
        optimizing = false
        findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE
        if (uri == null) {
            setStatus("Optimized, but couldn't save the file.")
            findViewById<Button>(R.id.btnOptimize).visibility = View.VISIBLE
            return
        }
        savedUri = uri
        savedMime = mime
        val text = if (after < before) {
            "Done! ${FileUtils.formatSize(before)} → ${FileUtils.formatSize(after)} (${FileUtils.savedPercent(before, after)})\nSaved to $folder$note"
        } else {
            "$note\nSaved to $folder"
        }
        findViewById<TextView>(R.id.tvResult).text = text
        findViewById<MaterialCardView>(R.id.cardResult).visibility = View.VISIBLE
        setStatus("Done — ready for WhatsApp.")
    }

    private fun optimizeFailed(message: String) {
        optimizing = false
        findViewById<ProgressBar>(R.id.progressBar).visibility = View.GONE
        findViewById<Button>(R.id.btnOptimize).visibility = View.VISIBLE
        setStatus(message)
    }

    private fun displayNameFor(kind: Kind, ext: String): String {
        val base = pickedName.substringBeforeLast('.', pickedName).ifBlank { "file" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${base}_whatsapp.$ext"
    }

    private fun shareSaved() {
        val uri = savedUri ?: return
        val share = Intent(Intent.ACTION_SEND).apply {
            type = savedMime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Share via"))
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.tvStatus).text = text
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt()
        return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        val largest = maxOf(width, height)
        while (largest / sample > maxDim) sample *= 2
        return sample
    }
}
