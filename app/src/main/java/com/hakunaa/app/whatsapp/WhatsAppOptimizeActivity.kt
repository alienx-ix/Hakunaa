package com.hakunaa.app.whatsapp

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.image.ImageCompressor
import com.hakunaa.app.pdf.PdfTools
import com.hakunaa.app.video.ExoVideoView
import com.hakunaa.app.video.VideoCompressor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File
import com.hakunaa.app.core.StatusBar

/**
 * WhatsApp Optimize — one tap: pick a video, photo or PDF, see its
 * details + preview, confirm, and get a copy that fits WhatsApp.
 * Targets: video ≤ 16 MB (WhatsApp's media limit — bigger videos
 * get re-compressed by WhatsApp itself), photo ≤ 300 KB at max
 * 1600 px (WhatsApp downscales photos anyway), PDF ≤ 16 MB via
 * Smart Compress. Output is never bigger than the input — if a
 * file is already within limits, it is saved as-is and shared.
 *
 * NO auto-save (v0.1.192): optimizing only shows the result with
 * a preview — the file is saved only when the user taps Save.
 * Share to WhatsApp works without saving (FileProvider).
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

    // Result (not saved yet — Save is the only thing that saves).
    private var resultFile: File? = null
    private var resultKind: Kind? = null
    private var resultBefore: Long = 0
    private var resultExt: String = ""
    private var savedUri: Uri? = null
    private var savedMime: String = ""

    // Full-screen in-app video player.
    private var fullScreenRoot: FrameLayout? = null
    private var fullScreenPlayer: ExoVideoView? = null
    private val playerHandler = Handler(Looper.getMainLooper())

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
        // Status bar (v0.1.97): keep it visible, no overlap.
        StatusBar.setup(this, true)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        findViewById<View>(R.id.rowPickVideo).setOnClickListener { if (!optimizing) pickVideo.launch("video/*") }
        findViewById<View>(R.id.rowPickImage).setOnClickListener { if (!optimizing) pickImage.launch("image/*") }
        findViewById<View>(R.id.rowPickPdf).setOnClickListener { if (!optimizing) pickPdf.launch("application/pdf") }
        findViewById<Button>(R.id.btnOptimize).setOnClickListener { if (!optimizing) startOptimize() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveResult() }
        findViewById<Button>(R.id.btnShareWa).setOnClickListener { shareToWhatsApp() }
        findViewById<Button>(R.id.btnDone).setOnClickListener { finish() }
        findViewById<View>(R.id.ivPlayOverlay).setOnClickListener {
            pickedFile?.let { if (pickedKind == Kind.VIDEO) playVideo(it) }
        }
        findViewById<View>(R.id.ivPreview).setOnClickListener {
            pickedFile?.let { if (pickedKind == Kind.VIDEO) playVideo(it) }
        }
        findViewById<View>(R.id.ivResultPlayOverlay).setOnClickListener {
            resultFile?.let { if (resultKind == Kind.VIDEO) playVideo(it) }
        }
        findViewById<View>(R.id.ivResultPreview).setOnClickListener {
            resultFile?.let { if (resultKind == Kind.VIDEO) playVideo(it) }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullScreenRoot != null) closeFullScreenPlayer() else finish()
            }
        })
    }

    override fun onDestroy() {
        closeFullScreenPlayer()
        super.onDestroy()
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
        resultFile = null
        resultKind = null
        savedUri = null
        savedMime = ""
        findViewById<MaterialCardView>(R.id.cardResult).visibility = View.GONE
        hideProgress()
        showDetails(file, kind, name)
        findViewById<Button>(R.id.btnOptimize).visibility = View.VISIBLE
        setStatus("Ready — tap Optimize for WhatsApp to start.")
    }

    private fun showDetails(file: File, kind: Kind, name: String) {
        val details = StringBuilder()
        details.append(name).append("\n").append(FileUtils.formatSize(file.length()))
        val preview = findViewById<ImageView>(R.id.ivPreview)
        val playOverlay = findViewById<ImageView>(R.id.ivPlayOverlay)
        preview.setImageBitmap(null)
        preview.visibility = View.GONE
        playOverlay.visibility = View.GONE
        when (kind) {
            Kind.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
                    val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
                    if (durMs > 0) details.append(" • ${formatDuration(durMs)}")
                    details.append(" • ${w}x$h")
                    retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                        preview.setImageBitmap(it)
                        preview.visibility = View.VISIBLE
                        playOverlay.visibility = View.VISIBLE
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
        findViewById<View>(R.id.layoutProgress).visibility = View.VISIBLE
        val progress = findViewById<ProgressBar>(R.id.progressBar)
        // Indeterminate until the first real progress value arrives:
        // some videos report no duration, so no progress values come
        // at all — the bar must keep spinning instead of sitting at 0.
        progress.isIndeterminate = true
        progress.progress = 0
        findViewById<TextView>(R.id.tvProgressText).text = "Optimizing…"
        setStatus("Optimizing…")
        when (kind) {
            Kind.VIDEO -> optimizeVideo(file)
            Kind.IMAGE -> optimizeImage(file)
            Kind.PDF -> optimizePdf(file)
        }
    }

    private fun hideProgress() {
        findViewById<View>(R.id.layoutProgress).visibility = View.GONE
    }

    private fun updateProgress(p: Int) {
        val progress = findViewById<ProgressBar>(R.id.progressBar)
        progress.isIndeterminate = false
        progress.progress = p
        findViewById<TextView>(R.id.tvProgressText).text = "Optimizing… $p%"
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
            onProgress = { p -> runOnUiThread { updateProgress(p) } },
            onDone = { ok, note ->
                runOnUiThread {
                    if (!ok) {
                        optimizeFailed("Couldn't optimize: $note")
                    } else {
                        showResult(out, Kind.VIDEO, inputSize, note, "mp4")
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
                runOnUiThread { showResult(bestFile, Kind.IMAGE, inputSize, note, format.extension) }
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
                        showResult(targetResult!!, Kind.IMAGE, inputSize, "", "jpg")
                    }
                    best != null -> {
                        // Smallest possible, still over target — keep it anyway.
                        try { out.delete() } catch (_: Throwable) { }
                        val (bestFile, format, note) = best
                        showResult(bestFile, Kind.IMAGE, inputSize, "$note (still over 200 KB, smallest possible)", format.extension)
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
                    showResult(out, Kind.PDF, inputSize, extra, "pdf")
                } else {
                    // Already the smallest it can be — save the original as-is.
                    try { out.delete() } catch (_: Throwable) { }
                    finishWithOriginal(file, Kind.PDF, "Already as small as it gets — ready as-is.")
                }
            }
        }.start()
    }

    /** The file is already within WhatsApp's limits — show it as-is. */
    private fun finishWithOriginal(file: File, kind: Kind, message: String) {
        val ext = pickedName.substringAfterLast('.', "").ifBlank {
            when (kind) {
                Kind.VIDEO -> "mp4"
                Kind.IMAGE -> "jpg"
                Kind.PDF -> "pdf"
            }
        }
        showResult(file, kind, file.length(), message, ext)
    }

    /**
     * Show the result — preview + sizes — WITHOUT saving. The file
     * is saved only when the user taps Save. The output is never
     * bigger than the input — callers check that before reaching here.
     */
    private fun showResult(out: File, kind: Kind, before: Long, note: String, ext: String) {
        optimizing = false
        hideProgress()
        resultFile = out
        resultKind = kind
        resultBefore = before
        resultExt = ext
        savedUri = null
        savedMime = ""
        val after = out.length()
        val sizes = if (after < before) {
            "${FileUtils.formatSize(before)} → ${FileUtils.formatSize(after)}  •  ${FileUtils.savedPercent(before, after)}"
        } else {
            FileUtils.formatSize(after)
        }
        findViewById<TextView>(R.id.tvResultSizes).text = sizes
        val targetText = when (kind) {
            Kind.VIDEO -> if (after <= VIDEO_TARGET_BYTES) "Under 16 MB — WhatsApp sends it as-is." else "Still over 16 MB — WhatsApp will re-compress it."
            Kind.IMAGE -> if (after <= IMAGE_TARGET_BYTES) "Under 200 KB — ready for WhatsApp." else "Still over 200 KB — smallest possible."
            Kind.PDF -> if (after <= PDF_TARGET_BYTES) "Under 16 MB — sends faster on WhatsApp." else "Still over 16 MB — WhatsApp will send it as a document."
        }
        findViewById<TextView>(R.id.tvResultTarget).text =
            if (note.isNotBlank()) "$targetText $note" else targetText
        findViewById<TextView>(R.id.tvSaved).visibility = View.GONE
        findViewById<Button>(R.id.btnSave).apply {
            text = "Save"
            isEnabled = true
        }
        showResultPreview(out, kind)
        findViewById<MaterialCardView>(R.id.cardResult).visibility = View.VISIBLE
        setStatus("Done — preview it, then Save or Share to WhatsApp.")
    }

    /** Preview of the OPTIMIZED file on the result card. */
    private fun showResultPreview(file: File, kind: Kind) {
        val preview = findViewById<ImageView>(R.id.ivResultPreview)
        val playOverlay = findViewById<ImageView>(R.id.ivResultPlayOverlay)
        preview.setImageBitmap(null)
        preview.visibility = View.GONE
        playOverlay.visibility = View.GONE
        when (kind) {
            Kind.VIDEO -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                        preview.setImageBitmap(it)
                        preview.visibility = View.VISIBLE
                        playOverlay.visibility = View.VISIBLE
                    }
                } catch (_: Throwable) {
                } finally {
                    try { retriever.release() } catch (_: Throwable) { }
                }
            }
            Kind.IMAGE -> {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 800) }
                BitmapFactory.decodeFile(file.absolutePath, opts)?.let {
                    preview.setImageBitmap(it)
                    preview.visibility = View.VISIBLE
                }
            }
            Kind.PDF -> {
                try {
                    PDDocument.load(file).use { doc ->
                        val bmp = PDFRenderer(doc).renderImageWithDPI(0, 72f)
                        preview.setImageBitmap(bmp)
                        preview.visibility = View.VISIBLE
                    }
                } catch (_: Throwable) { }
            }
        }
    }

    /** Save the result to shared storage — the ONLY thing that saves. */
    private fun saveResult() {
        val out = resultFile ?: return
        val kind = resultKind ?: return
        val displayName = displayNameFor(kind, resultExt)
        val folderKind = when (kind) {
            Kind.VIDEO -> MediaStoreSaver.Kind.VIDEO
            Kind.IMAGE -> MediaStoreSaver.Kind.IMAGE
            Kind.PDF -> MediaStoreSaver.Kind.DOCUMENT
        }
        val mime = when (resultExt.lowercase()) {
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
        if (uri == null) {
            toast("Couldn't save the file")
            return
        }
        savedUri = uri
        savedMime = mime
        findViewById<TextView>(R.id.tvSaved).apply {
            text = "Saved to $folder"
            visibility = View.VISIBLE
        }
        findViewById<Button>(R.id.btnSave).apply {
            text = "Saved ✓"
            isEnabled = false
        }
        setStatus("Saved — ready for WhatsApp.")
    }

    /** Share to WhatsApp directly — works WITHOUT saving first. */
    private fun shareToWhatsApp() {
        val out = resultFile ?: return
        val kind = resultKind ?: return
        val uri: Uri
        val mime: String
        if (savedUri != null) {
            uri = savedUri!!
            mime = savedMime
        } else {
            mime = when (resultExt.lowercase()) {
                "mp4" -> "video/mp4"
                "webp" -> "image/webp"
                "png" -> "image/png"
                "pdf" -> "application/pdf"
                else -> "image/jpeg"
            }
            uri = try {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", out)
            } catch (_: Throwable) {
                toast("Couldn't share this file")
                return
            }
        }
        val share = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.whatsapp")
        }
        try {
            startActivity(share)
        } catch (_: Throwable) {
            // WhatsApp not installed — fall back to the generic chooser.
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(fallback, "Share via"))
            } catch (_: Throwable) {
                toast("Couldn't share this file")
            }
        }
    }

    /** In-app full-screen video player (ExoPlayer, same as Video Tools). */
    private fun playVideo(file: File) {
        closeFullScreenPlayer()
        val uri = Uri.fromFile(file)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val player = ExoVideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(player)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(12), dp(8), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        }
        val seek = SeekBar(this).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        val playPause = TextView(this).apply {
            text = "❚❚"
            textSize = 22f
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(4), dp(16), dp(4))
        }
        val time = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bottomRow.addView(playPause)
        bottomRow.addView(time)
        controls.addView(seek)
        controls.addView(bottomRow)
        root.addView(controls)
        val close = TextView(this).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
        }
        root.addView(close)
        var userSeeking = false
        val tick = object : Runnable {
            override fun run() {
                val p = fullScreenPlayer ?: return
                if (p === player && p.duration > 0) {
                    if (!userSeeking) {
                        seek.progress = ((p.currentPosition.toLong() * 1000) / p.duration).toInt()
                    }
                    time.text = "${formatClock(p.currentPosition.toLong())} / ${formatClock(p.duration)}"
                    playPause.text = if (p.isPlaying()) "❚❚" else "▶"
                    playerHandler.postDelayed(this, 500)
                }
            }
        }
        player.onPrepared = {
            player.start()
            playerHandler.post(tick)
        }
        player.onError = {
            toast("Couldn't play this video")
            closeFullScreenPlayer()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && player.duration > 0) {
                    player.seekTo((progress.toLong() * player.duration / 1000).toInt())
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(s: SeekBar?) { userSeeking = false }
        })
        playPause.setOnClickListener {
            if (player.isPlaying()) player.pause() else player.start()
        }
        root.setOnClickListener {
            if (player.isPlaying()) player.pause() else player.start()
        }
        close.setOnClickListener { closeFullScreenPlayer() }
        (findViewById<View>(android.R.id.content) as ViewGroup).addView(root)
        fullScreenRoot = root
        fullScreenPlayer = player
        player.setVideoURI(uri)
    }

    private fun closeFullScreenPlayer() {
        val root = fullScreenRoot ?: return
        playerHandler.removeCallbacksAndMessages(null)
        try {
            fullScreenPlayer?.stopPlayback()
        } catch (_: Throwable) {
        }
        fullScreenPlayer = null
        try {
            (findViewById<View>(android.R.id.content) as ViewGroup).removeView(root)
        } catch (_: Throwable) {
        }
        fullScreenRoot = null
    }

    private fun optimizeFailed(message: String) {
        optimizing = false
        hideProgress()
        findViewById<Button>(R.id.btnOptimize).visibility = View.VISIBLE
        setStatus(message)
    }

    private fun displayNameFor(kind: Kind, ext: String): String {
        val base = pickedName.substringBeforeLast('.', pickedName).ifBlank { "file" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${base}_whatsapp.$ext"
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

    private fun formatClock(ms: Long): String {
        val totalSeconds = (ms / 1000).toInt()
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }

    private fun sampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        val largest = maxOf(width, height)
        while (largest / sample > maxDim) sample *= 2
        return sample
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
