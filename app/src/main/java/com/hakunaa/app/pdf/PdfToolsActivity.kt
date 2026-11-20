package com.hakunaa.app.pdf

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.core.StatusBar
import com.hakunaa.app.image.ImageCompressor
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.io.FileOutputStream

/**
 * PDF Tools menu (v0.1.148, full redesign): single screen, NO scroll.
 * Top 4 featured cards in 2x2 (Smart / Lossless Compress, Merge /
 * Split), then 12 tools in a 3-per-row grid in the user's order.
 * All 16 cards share the real screen height by weight. Tool inputs
 * that used to live in the old form (rotate angle, watermark text,
 * password, page order) now come from small dialogs. Sign PDF opens
 * as a full-screen overlay inside this activity (no new Activity,
 * no Manifest change). Image Tools and Scanner are untouched.
 */
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

    // Single-PDF picker, shared by Repair and the Sign overlay.
    private var singlePdfCallback: ((Uri) -> Unit)? = null
    private val pickSinglePdf =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) singlePdfCallback?.invoke(uri)
            singlePdfCallback = null
        }

    // Sign overlay state.
    private var signOverlay: View? = null
    private var signPad: SignaturePadView? = null
    private var etSignPage: EditText? = null
    private var tvSignFile: TextView? = null
    private var tvSignPages: TextView? = null
    private var signPdfFile: File? = null
    private var signPageCount = 0
    private var signPosition = 1 // 0 left, 1 right, 2 center
    private var signSize = 1 // 0 small, 1 medium, 2 large
    private val positionChips = mutableListOf<MaterialButton>()
    private val sizeChips = mutableListOf<MaterialButton>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_tools)

        // Status bar (v0.1.97): keep it visible, no overlap.
        // Must be called AFTER setContentView (v0.1.133).
        StatusBar.setup(this, true)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btnPickPdfs)
            .setOnClickListener { pickPdfs.launch("application/pdf") }

        buildMenu()

        // System Back closes the Sign overlay first (same pattern as
        // the WebP overlay in ImageToolActivity).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (signOverlay != null) {
                    dismissSignOverlay()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // Menu (v0.1.148): top 4 featured 2x2, then 12 tools 3-per-row in
    // the user's exact order. Weight-shared rows = single screen,
    // no ScrollView, fits any phone.
    // ------------------------------------------------------------------

    private data class Tool(
        val name: String,
        val subtitle: String?,
        val kind: PdfIconView.Kind,
        val featured: Boolean,
        val action: () -> Unit
    )

    private fun buildMenu() {
        val area = findViewById<LinearLayout>(R.id.contentArea)
        val featured = listOf(
            listOf(
                Tool("Smart Compress", "Best size", PdfIconView.Kind.SMART, true) { compressSmartPdf() },
                Tool("Lossless Compress", "No quality loss", PdfIconView.Kind.LOSSLESS, true) { compressPdf() }
            ),
            listOf(
                Tool("Merge PDF", "PDF a serkardhu", PdfIconView.Kind.MERGE, true) { mergePdfs() },
                Tool("Split PDF", "Pages pirikardhu", PdfIconView.Kind.SPLIT, true) { splitPdf() }
            )
        )
        val grid = listOf(
            listOf(
                Tool("Organize PDF", null, PdfIconView.Kind.ORGANIZE, false) { organizePdf() },
                Tool("Sign PDF", null, PdfIconView.Kind.SIGN, false) { showSignOverlay() },
                Tool("PDF to JPG", null, PdfIconView.Kind.JPG, false) { pdfToJpg() }
            ),
            listOf(
                Tool("Protect / Unlock", null, PdfIconView.Kind.LOCK, false) { protectUnlockPdf() },
                Tool("Repair PDF", null, PdfIconView.Kind.REPAIR, false) { repairPdf() },
                Tool("Page Numbers", null, PdfIconView.Kind.PAGE_NO, false) { pageNumbersPdf() }
            ),
            listOf(
                Tool("Rotate PDF", null, PdfIconView.Kind.ROTATE, false) { rotatePdf() },
                Tool("Image to PDF", null, PdfIconView.Kind.IMG_PDF, false) { pickImageForPdf.launch("image/*") },
                Tool("Extract Text", null, PdfIconView.Kind.TEXT, false) { extractTextPdf() }
            ),
            listOf(
                Tool("Watermark", null, PdfIconView.Kind.WATERMARK, false) { watermarkPdf() },
                Tool("Extract Images", null, PdfIconView.Kind.IMG_EXTRACT, false) { extractImagesPdf() },
                Tool("Resize Pages", null, PdfIconView.Kind.RESIZE, false) { resizePdf() }
            )
        )
        for (row in featured) area.addView(buildRow(row, 1.15f))
        for (row in grid) area.addView(buildRow(row, 1f))
    }

    private fun buildRow(tools: List<Tool>, weight: Float): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, weight
            )
        }
        for (tool in tools) row.addView(buildCard(tool))
        return row
    }

    private fun buildCard(tool: Tool): LinearLayout {
        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            // Ripple: resolve the attribute id via the theme
            // (android.R.attr.* are attribute ids, never drawables).
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = getDrawable(outValue.resourceId)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                if (tool.featured) {
                    setColor(0xFFE0F2F1.toInt())
                    setStroke((2 * density).toInt(), getColor(R.color.hakunaa_teal))
                } else {
                    setColor(Color.parseColor("#F5F5F7"))
                }
            }
            val params = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            val m = dp(6)
            params.setMargins(m, m, m, m)
            layoutParams = params
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { tool.action() }
        }
        val iconSize = if (tool.featured) dp(56) else dp(48)
        val icon = PdfIconView(this).apply {
            kind = tool.kind
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        card.addView(icon)
        val name = TextView(this).apply {
            text = tool.name
            textSize = if (tool.featured) 15f else 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        card.addView(name)
        if (tool.subtitle != null) {
            val sub = TextView(this).apply {
                text = tool.subtitle
                textSize = 12f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }
            card.addView(sub)
        }
        return card
    }

    // ------------------------------------------------------------------
    // Small dialogs for the inputs the old form used to hold.
    // ------------------------------------------------------------------

    private fun showTextInputDialog(
        title: String, hint: String, inputType: Int, onText: (String) -> Unit
    ) {
        val input = EditText(this).apply {
            this.inputType = inputType
            this.hint = hint
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("OK") { _, _ -> onText(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSingleChoiceDialog(
        title: String, items: Array<String>, onIndex: (Int) -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which -> onIndex(which) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Professional Saved popup (PDF-only; SavedDialog in image/
     * needs an image Format, so PDF saves use this one). */
    private fun showSavedPdfDialog(outFile: File, savedUri: Uri?) {
        AlertDialog.Builder(this)
            .setTitle("Saved")
            .setMessage("Saved to Documents/Hakunaa/${outFile.name}")
            .setPositiveButton("Open") { _, _ ->
                if (savedUri != null) {
                    try {
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(savedUri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(view)
                    } catch (_: Throwable) {
                        Toast.makeText(this, "No PDF viewer found", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Shared helpers (unchanged from the old form UI).
    // ------------------------------------------------------------------

    // PDF work writes to a temp folder in the app cache; finished files
    // are published to Documents/Hakunaa via MediaStore (scoped storage).
    private fun hakunaaCacheDir(): File =
        File(cacheDir, "pdf").apply { mkdirs() }

    private fun publishPdf(file: File): Boolean =
        MediaStoreSaver.save(this, file, MediaStoreSaver.Kind.DOCUMENT, file.name) != null

    private fun publishPdfUri(file: File): Uri? =
        MediaStoreSaver.save(this, file, MediaStoreSaver.Kind.DOCUMENT, file.name)

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

    private fun rotatePdf() {
        showSingleChoiceDialog("Rotate by", arrayOf("90°", "180°", "270°")) { which ->
            val degrees = listOf(90, 180, 270)[which]
            runRotate(degrees)
        }
    }

    private fun runRotate(degrees: Int) {
        val input = firstPickedPdf() ?: return
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
        showTextInputDialog(
            "Watermark text", "e.g. CONFIDENTIAL", InputType.TYPE_CLASS_TEXT
        ) { text ->
            if (text.isBlank()) {
                Toast.makeText(this, "Type a watermark text first", Toast.LENGTH_SHORT).show()
                return@showTextInputDialog
            }
            runWatermark(text.trim())
        }
    }

    private fun runWatermark(text: String) {
        val input = firstPickedPdf() ?: return
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
                    if (MediaStoreSaver.save(this, file, MediaStoreSaver.Kind.IMAGE, file.name, ImageCompressor.formatOf(file).mime) != null) {
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

    private fun pdfToJpg() {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Converting PDF to JPG…"
        Thread {
            try {
                val outDir = File(hakunaaCacheDir(), "jpg_${System.currentTimeMillis()}").apply { mkdirs() }
                val created = PdfTools.pdfToJpg(input, outDir)
                var published = 0
                for (file in created) {
                    if (MediaStoreSaver.save(this, file, MediaStoreSaver.Kind.IMAGE, file.name, ImageCompressor.formatOf(file).mime) != null) {
                        published++
                    }
                }
                runOnUiThread {
                    status.text = if (published > 0) "Done — $published page(s) saved to Pictures/Hakunaa"
                    else "PDF to JPG failed — check the file and try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "PDF to JPG failed — check the file and try again" }
            }
        }.start()
    }

    private fun extractTextPdf() {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Extracting text…"
        Thread {
            try {
                val text = PdfTools.extractText(input)
                if (text == null) {
                    runOnUiThread { status.text = "Extract failed — check the file and try again" }
                    return@Thread
                }
                val out = File(hakunaaCacheDir(), "${input.nameWithoutExtension}.txt")
                out.writeText(text)
                val saved = MediaStoreSaver.save(this, out, MediaStoreSaver.Kind.DOCUMENT, out.name, "text/plain")
                runOnUiThread {
                    status.text = if (saved != null) "Done — text saved to Documents/Hakunaa/${out.name} (${text.length} characters)"
                    else "Extract failed — couldn't save the text file"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Extract failed — check the file and try again" }
            }
        }.start()
    }

    private fun protectUnlockPdf() {
        showSingleChoiceDialog("Protect or Unlock?", arrayOf("Protect PDF", "Unlock PDF")) { which ->
            val isProtect = which == 0
            showTextInputDialog(
                if (isProtect) "Protect PDF" else "Unlock PDF",
                "Type a password",
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            ) { password ->
                if (password.isEmpty()) {
                    Toast.makeText(this, "Type a password first", Toast.LENGTH_SHORT).show()
                    return@showTextInputDialog
                }
                if (isProtect) runProtect(password) else runUnlock(password)
            }
        }
    }

    private fun runProtect(password: String) {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Protecting PDF…"
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_protected_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.protect(input, out, password) && publishPdf(out)
                runOnUiThread {
                    status.text = if (ok) "Done — protected PDF saved to Documents/Hakunaa/${out.name}"
                    else "Protect failed — check the file and try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Protect failed — check the file and try again" }
            }
        }.start()
    }

    private fun runUnlock(password: String) {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Unlocking PDF…"
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_unlocked_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.unlock(input, out, password) && publishPdf(out)
                runOnUiThread {
                    status.text = if (ok) "Done — unlocked PDF saved to Documents/Hakunaa/${out.name}"
                    else "Unlock failed — wrong password or file not locked"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Unlock failed — wrong password or file not locked" }
            }
        }.start()
    }

    private fun organizePdf() {
        showTextInputDialog(
            "New page order", "e.g. 3,1,2", InputType.TYPE_CLASS_TEXT
        ) { orderText ->
            val order = orderText.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (order.isEmpty()) {
                Toast.makeText(this, "Type the new page order, e.g. 3,1,2", Toast.LENGTH_SHORT).show()
                return@showTextInputDialog
            }
            runOrganize(order)
        }
    }

    private fun runOrganize(order: List<Int>) {
        val input = firstPickedPdf() ?: return
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Organizing PDF…"
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_organized_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.organize(input, out, order) && publishPdf(out)
                runOnUiThread {
                    status.text = if (ok) "Done — reorganized PDF saved to Documents/Hakunaa/${out.name}"
                    else "Organize failed — list every page exactly once, e.g. 3,1,2"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Organize failed — list every page exactly once, e.g. 3,1,2" }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Repair PDF (v0.1.148, new).
    // ------------------------------------------------------------------

    private fun repairPdf() {
        singlePdfCallback = { uri ->
            val status = findViewById<TextView>(R.id.tvStatus)
            status.text = "Repairing PDF…"
            Thread {
                try {
                    val input = FileUtils.copyUriToCache(this, uri, "repair.pdf")
                    val out = File(hakunaaCacheDir(), "hakunaa_repaired_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.repair(input, out)
                    val savedUri = if (ok) publishPdfUri(out) else null
                    runOnUiThread {
                        if (ok && savedUri != null) {
                            status.text = "Done — repaired PDF saved to Documents/Hakunaa/${out.name}"
                            showSavedPdfDialog(out, savedUri)
                        } else if (ok) {
                            status.text = "Done — repaired, but couldn't save it. Try again."
                        } else {
                            status.text = "Could not repair — this file is too damaged"
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "Could not repair — this file is too damaged" }
                }
            }.start()
        }
        pickSinglePdf.launch("application/pdf")
    }

    // ------------------------------------------------------------------
    // Resize Pages (v0.1.148, new).
    // ------------------------------------------------------------------

    private fun resizePdf() {
        val input = firstPickedPdf() ?: return
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val sizeLabel = TextView(this).apply {
            text = "Page size"
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 14f
        }
        val rgSize = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbA4 = RadioButton(this).apply { text = "A4"; isChecked = true }
        val rbLetter = RadioButton(this).apply { text = "Letter"; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) } }
        rgSize.addView(rbA4)
        rgSize.addView(rbLetter)
        val orientLabel = TextView(this).apply {
            text = "Orientation"
            setTypeface(null, android.graphics.Typeface.BOLD)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
        }
        val rgOrient = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbPortrait = RadioButton(this).apply { text = "Portrait"; isChecked = true }
        val rbLandscape = RadioButton(this).apply { text = "Landscape"; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) } }
        rgOrient.addView(rbPortrait)
        rgOrient.addView(rbLandscape)
        wrap.addView(sizeLabel)
        wrap.addView(rgSize)
        wrap.addView(orientLabel)
        wrap.addView(rgOrient)
        AlertDialog.Builder(this)
            .setTitle("Resize Pages")
            .setView(wrap)
            .setPositiveButton("Resize") { _, _ ->
                val isA4 = rbA4.isChecked
                val landscape = rbLandscape.isChecked
                val w = if (isA4) 595f else 612f
                val h = if (isA4) 842f else 792f
                val target = if (landscape) PDRectangle(h, w) else PDRectangle(w, h)
                runResize(input, target, if (isA4) "A4" else "Letter")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runResize(input: File, target: PDRectangle, label: String) {
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Resizing pages to $label…"
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_resized_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.resizePages(input, out, target) && publishPdf(out)
                runOnUiThread {
                    status.text = if (ok) "Done — pages resized to $label, saved to Documents/Hakunaa/${out.name}"
                    else "Resize failed — check the file and try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Resize failed — check the file and try again" }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Sign PDF overlay (v0.1.148, new). Full-screen overlay inside
    // this activity — no new Activity, no Manifest change.
    // ------------------------------------------------------------------

    private fun showSignOverlay() {
        if (signOverlay != null) return
        signPosition = 1
        signSize = 1
        signPdfFile = null
        signPageCount = 0
        positionChips.clear()
        sizeChips.clear()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.hakunaa_surface))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding(dp(4), dp(4), dp(16), dp(4))
            setOnClickListener { dismissSignOverlay() }
        }
        val title = TextView(this).apply {
            text = "Sign PDF"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(close)
        header.addView(title)
        root.addView(header)

        val fileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val btnPick = MaterialButton(this).apply {
            text = "Pick PDF"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)
            )
            setOnClickListener {
                singlePdfCallback = { uri ->
                    Thread {
                        try {
                            val file = FileUtils.copyUriToCache(this@PdfToolsActivity, uri, "sign.pdf")
                            val info = PdfTools.pageInfo(file, 0)
                            runOnUiThread {
                                if (info == null) {
                                    Toast.makeText(this@PdfToolsActivity, "Could not open this PDF", Toast.LENGTH_SHORT).show()
                                } else {
                                    signPdfFile = file
                                    signPageCount = info.first
                                    tvSignFile?.text = "PDF picked • ${info.first} page(s)"
                                    tvSignPages?.text = "of ${info.first}"
                                    etSignPage?.hint = info.first.toString()
                                }
                            }
                        } catch (_: Throwable) {
                            runOnUiThread {
                                Toast.makeText(this@PdfToolsActivity, "Could not open this PDF", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
                pickSinglePdf.launch("application/pdf")
            }
        }
        tvSignFile = TextView(this).apply {
            text = "No PDF picked"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(12) }
        }
        fileRow.addView(btnPick)
        fileRow.addView(tvSignFile)
        root.addView(fileRow)

        val pageRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val pageLabel = TextView(this).apply {
            text = "Page"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        etSignPage = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(12)
                marginEnd = dp(8)
            }
        }
        tvSignPages = TextView(this).apply {
            text = "of —"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
        }
        pageRow.addView(pageLabel)
        pageRow.addView(etSignPage)
        pageRow.addView(tvSignPages)
        root.addView(pageRow)

        val padLabel = TextView(this).apply {
            text = "Draw your signature"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(8)
            }
        }
        root.addView(padLabel)
        val pad = SignaturePadView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        signPad = pad
        root.addView(pad)
        val clear = TextView(this).apply {
            text = "Clear"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_teal))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { pad.clear() }
        }
        root.addView(clear)

        val posLabel = TextView(this).apply {
            text = "Position"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            }
        }
        root.addView(posLabel)
        val posRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val posNames = listOf("Bottom Left", "Bottom Right", "Bottom Center")
        for (i in posNames.indices) {
            val chip = makeChip(posNames[i])
            chip.setOnClickListener {
                signPosition = i
                refreshChips(positionChips, signPosition)
            }
            positionChips.add(chip)
            posRow.addView(chip)
        }
        root.addView(posRow)
        refreshChips(positionChips, signPosition)

        val sizeLabel = TextView(this).apply {
            text = "Size"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(8)
            }
        }
        root.addView(sizeLabel)
        val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sizeNames = listOf("Small", "Medium", "Large")
        for (i in sizeNames.indices) {
            val chip = makeChip(sizeNames[i])
            chip.setOnClickListener {
                signSize = i
                refreshChips(sizeChips, signSize)
            }
            sizeChips.add(chip)
            sizeRow.addView(chip)
        }
        root.addView(sizeRow)
        refreshChips(sizeChips, signSize)

        val btnSave = MaterialButton(this).apply {
            text = "Save signed PDF"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(16) }
            setOnClickListener { saveSignedPdf() }
        }
        root.addView(btnSave)

        findViewById<ViewGroup>(android.R.id.content).addView(root)
        signOverlay = root
    }

    private fun makeChip(text: String): MaterialButton {
        return MaterialButton(this).apply {
            this.text = text
            textSize = 13f
            minHeight = 0
            minimumHeight = 0
            strokeWidth = dp(1)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val params = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
            params.marginEnd = dp(8)
            layoutParams = params
        }
    }

    private fun refreshChips(chips: List<MaterialButton>, selected: Int) {
        val teal = getColor(R.color.hakunaa_teal)
        val dark = getColor(R.color.hakunaa_text)
        for (i in chips.indices) {
            val chip = chips[i]
            if (i == selected) {
                chip.backgroundTintList = ColorStateList.valueOf(teal)
                chip.setTextColor(Color.WHITE)
                chip.strokeColor = ColorStateList.valueOf(teal)
            } else {
                chip.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                chip.setTextColor(dark)
                chip.strokeColor = ColorStateList.valueOf(Color.parseColor("#CBD5E1"))
            }
        }
    }

    private fun dismissSignOverlay() {
        val overlay = signOverlay ?: return
        findViewById<ViewGroup>(android.R.id.content).removeView(overlay)
        signOverlay = null
        signPad = null
        etSignPage = null
        tvSignFile = null
        tvSignPages = null
        signPdfFile = null
    }

    private fun saveSignedPdf() {
        val pdf = signPdfFile
        if (pdf == null) {
            Toast.makeText(this, "Pick a PDF first", Toast.LENGTH_SHORT).show()
            return
        }
        val pad = signPad ?: return
        if (pad.isEmpty()) {
            Toast.makeText(this, "Draw your signature first", Toast.LENGTH_SHORT).show()
            return
        }
        val pageNum = etSignPage?.text?.toString()?.trim()?.toIntOrNull() ?: signPageCount
        if (pageNum < 1 || pageNum > signPageCount) {
            Toast.makeText(this, "Page must be 1..$signPageCount", Toast.LENGTH_SHORT).show()
            return
        }
        val info = PdfTools.pageInfo(pdf, pageNum - 1)
        if (info == null) {
            Toast.makeText(this, "Could not read this PDF", Toast.LENGTH_SHORT).show()
            return
        }
        val pageW = info.second
        val (w, h) = when (signSize) {
            0 -> 120f to 42f
            2 -> 240f to 84f
            else -> 180f to 63f
        }
        val x = when (signPosition) {
            0 -> 36f
            2 -> (pageW - w) / 2f
            else -> pageW - 36f - w
        }
        val y = 36f
        Thread {
            try {
                val bmp = pad.toBitmap() ?: return@Thread
                val sigFile = File(hakunaaCacheDir(), "signature_${System.currentTimeMillis()}.png")
                FileOutputStream(sigFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val out = File(hakunaaCacheDir(), "hakunaa_signed_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.signPdf(pdf, out, sigFile, pageNum - 1, x, y, w, h)
                val savedUri = if (ok) publishPdfUri(out) else null
                runOnUiThread {
                    if (ok && savedUri != null) {
                        dismissSignOverlay()
                        findViewById<TextView>(R.id.tvStatus).text =
                            "Done — signed PDF saved to Documents/Hakunaa/${out.name}"
                        showSavedPdfDialog(out, savedUri)
                    } else {
                        Toast.makeText(this, "Sign failed — check the file and try again", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Sign failed — check the file and try again", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
}
