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
 * PDF Tools menu (v0.1.149): single screen, NO scroll.
 * Top 4 featured cards in 2x2 (Smart / Lossless Compress, Merge /
 * Split), then 12 tools in a 3-per-row grid in the user's order.
 * All 16 cards share the real screen height by weight.
 *
 * File pick (v0.1.149, Image Tools pattern): NO file-pick control
 * at the top of the menu. Each tool opens its own file picker
 * when tapped. Sign PDF opens as a full-screen overlay inside
 * this activity (no new Activity, no Manifest change). Smart
 * Compress shows 5 levels + a Custom size section in an overlay.
 * Image Tools and Scanner are untouched.
 */
class PdfToolsActivity : AppCompatActivity() {

    // Single-PDF picker, shared by every tool (one callback at a
    // time — each tool sets it right before launching).
    private var singlePdfCallback: ((Uri) -> Unit)? = null
    private val pickSinglePdf =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) singlePdfCallback?.invoke(uri)
            singlePdfCallback = null
        }

    // Multi-PDF picker, for Merge only.
    private var multiPdfCallback: ((List<Uri>) -> Unit)? = null
    private val pickPdfs =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            multiPdfCallback?.invoke(uris)
            multiPdfCallback = null
        }

    private val pickImageForPdf =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            toast("Creating PDF from image…")
            Thread {
                try {
                    val input = FileUtils.copyUriToCache(this, uri, "image_to_pdf")
                    val outDir = hakunaaCacheDir()
                    val out = File(outDir, "hakunaa_image_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.imageToPdf(input, out) && publishPdf(out)
                    runOnUiThread {
                        if (ok) showSavedPdfDialog(out, publishPdfUri(out))
                        else toast("Failed — try another image")
                    }
                } catch (_: Throwable) {
                    runOnUiThread { toast("Failed — try another image") }
                }
            }.start()
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

    // Smart Compress overlay state (v0.1.149).
    private var smartOverlay: View? = null
    private var smartInput: File? = null
    private var smartOriginalSize = 0L
    private var smartLevelFiles = mutableListOf<File>()
    private var smartRows = mutableListOf<LinearLayout>()
    private var smartRowFiles = mutableListOf<File?>()
    private var smartSelected = -1
    private var smartCustomFile: File? = null
    private var smartCustomRow: LinearLayout? = null
    private var tvSmartComputing: TextView? = null
    private var smartResultsBox: LinearLayout? = null
    private var tvSmartCustomResult: TextView? = null
    private var etSmartCustomKb: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_tools)

        // Status bar (v0.1.97): keep it visible, no overlap.
        // Must be called AFTER setContentView (v0.1.133).
        StatusBar.setup(this, true)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        buildMenu()

        // System Back closes the Sign / Smart overlay first (same
        // pattern as the WebP overlay in ImageToolActivity).
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    signOverlay != null -> dismissSignOverlay()
                    smartOverlay != null -> dismissSmartOverlay()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------------
    // Menu (v0.1.149): top 4 featured 2x2, then 12 tools 3-per-row in
    // the user's exact order. Weight-shared rows = single screen,
    // no ScrollView, fits any phone. v0.1.149 fixes: featured rows
    // taller (weight 1.25) so subtitles are never clipped, grid
    // cards stronger contrast (#E9ECEF + border) with 14sp bold
    // labels so every box is CLEAR.
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
        for (row in featured) area.addView(buildRow(row, 1.25f))
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
                    // v0.1.149: stronger contrast + border so the
                    // grid boxes are CLEAR, not washed out.
                    setColor(Color.parseColor("#E9ECEF"))
                    setStroke((1 * density).toInt(), Color.parseColor("#CFD8DC"))
                }
            }
            val params = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            val m = dp(6)
            params.setMargins(m, m, m, m)
            layoutParams = params
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { tool.action() }
        }
        val iconSize = if (tool.featured) dp(52) else dp(40)
        val icon = PdfIconView(this).apply {
            kind = tool.kind
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        card.addView(icon)
        val name = TextView(this).apply {
            text = tool.name
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            maxLines = 2
            includeFontPadding = false
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
                includeFontPadding = false
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
                        toast("No PDF viewer found")
                    }
                }
            }
            .setNegativeButton("Done", null)
            .show()
    }

    // ------------------------------------------------------------------
    // Shared helpers.
    // ------------------------------------------------------------------

    // PDF work writes to a temp folder in the app cache; finished files
    // are published to Documents/Hakunaa via MediaStore (scoped storage).
    private fun hakunaaCacheDir(): File =
        File(cacheDir, "pdf").apply { mkdirs() }

    private fun publishPdf(file: File): Boolean =
        MediaStoreSaver.save(this, file, MediaStoreSaver.Kind.DOCUMENT, file.name) != null

    private fun publishPdfUri(file: File): Uri? =
        MediaStoreSaver.save(this, file, MediaStoreSaver.Kind.DOCUMENT, file.name)

    /**
     * Per-tool file pick (v0.1.149, Image Tools pattern): tap a tool
     * and the picker opens for THAT tool. The picked PDF is copied
     * to the cache and handed to [action] on the UI thread.
     */
    private fun pickPdfThen(action: (File) -> Unit) {
        singlePdfCallback = { uri ->
            Thread {
                try {
                    val file = FileUtils.copyUriToCache(this, uri, "single.pdf")
                    runOnUiThread { action(file) }
                } catch (_: Throwable) {
                    runOnUiThread { toast("Could not open this PDF") }
                }
            }.start()
        }
        pickSinglePdf.launch("application/pdf")
    }

    /** Multi-PDF pick for Merge (v0.1.149). */
    private fun pickPdfsThen(action: (List<File>) -> Unit) {
        multiPdfCallback = { uris ->
            if (uris.isEmpty()) {
                toast("Pick at least 2 PDFs")
            } else {
                Thread {
                    try {
                        val files = uris.mapIndexed { index, uri ->
                            FileUtils.copyUriToCache(this, uri, "merge_$index.pdf")
                        }
                        runOnUiThread { action(files) }
                    } catch (_: Throwable) {
                        runOnUiThread { toast("Could not open these PDFs") }
                    }
                }.start()
            }
        }
        pickPdfs.launch("application/pdf")
    }

    // ------------------------------------------------------------------
    // Tools — each entry opens its own file picker (v0.1.149).
    // ------------------------------------------------------------------

    private fun mergePdfs() {
        pickPdfsThen { inputs ->
            if (inputs.size < 2) {
                toast("Pick at least 2 PDFs")
                return@pickPdfsThen
            }
            toast("Merging ${inputs.size} PDFs…")
            Thread {
                try {
                    val out = File(hakunaaCacheDir(), "hakunaa_merged_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.merge(inputs, out)
                    val savedUri = if (ok) publishPdfUri(out) else null
                    runOnUiThread {
                        if (ok && savedUri != null) showSavedPdfDialog(out, savedUri)
                        else toast("Merge failed — check the files and try again")
                    }
                } catch (_: Throwable) {
                    runOnUiThread { toast("Merge failed — check the files and try again") }
                }
            }.start()
        }
    }

    private fun splitPdf() {
        pickPdfThen { input ->
            toast("Splitting PDF…")
            Thread {
                try {
                    val outDir = File(hakunaaCacheDir(), "split_${System.currentTimeMillis()}").apply { mkdirs() }
                    val created = PdfTools.split(input, outDir)
                    val published = created.count { publishPdf(it) }
                    runOnUiThread {
                        if (published > 0) toast("Done — $published page(s) saved to Documents/Hakunaa")
                        else toast("Split failed — check the file and try again")
                    }
                } catch (_: Throwable) {
                    runOnUiThread { toast("Split failed — check the file and try again") }
                }
            }.start()
        }
    }

    private fun compressPdf() {
        pickPdfThen { input ->
            toast("Compressing PDF (lossless)…")
            Thread {
                try {
                    val before = input.length()
                    val out = File(hakunaaCacheDir(), "hakunaa_compressed_${System.currentTimeMillis()}.pdf")
                    val savedBytes = PdfTools.compressLossless(input, out)
                    val savedUri = if (savedBytes > 0) publishPdfUri(out) else null
                    runOnUiThread {
                        when {
                            savedBytes > 0 && savedUri != null -> {
                                val after = out.length()
                                toast("Done — ${FileUtils.formatSize(before)} → ${FileUtils.formatSize(after)} (${FileUtils.savedPercent(before, after)})")
                                showSavedPdfDialog(out, savedUri)
                            }
                            savedBytes == 0L -> toast("Already optimized — this PDF is as small as it gets without losing quality. Try Smart Compress for a smaller file.")
                            else -> toast("Compress failed — check the file and try again")
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { toast("Compress failed — check the file and try again") }
                }
            }.start()
        }
    }

    private fun compressSmartPdf() {
        pickPdfThen { input ->
            showSmartResults(input)
        }
    }

    private fun rotatePdf() {
        pickPdfThen { input ->
            showSingleChoiceDialog("Rotate by", arrayOf("90°", "180°", "270°")) { which ->
                val degrees = listOf(90, 180, 270)[which]
                toast("Rotating PDF by $degrees°…")
                Thread {
                    try {
                        val out = File(hakunaaCacheDir(), "hakunaa_rotated_${System.currentTimeMillis()}.pdf")
                        val ok = PdfTools.rotate(input, out, degrees)
                        val savedUri = if (ok) publishPdfUri(out) else null
                        runOnUiThread {
                            if (ok && savedUri != null) showSavedPdfDialog(out, savedUri)
                            else toast("Rotate failed — check the file and try again")
                        }
                    } catch (_: Throwable) {
                        runOnUiThread { toast("Rotate failed — check the file and try again") }
                    }
                }.start()
            }
        }
    }

    private fun watermarkPdf() {
        pickPdfThen { input ->
            showTextInputDialog(
                "Watermark text", "e.g. CONFIDENTIAL", InputType.TYPE_CLASS_TEXT
            ) { text ->
                if (text.isBlank()) {
                    toast("Type a watermark text first")
                    return@showTextInputDialog
                }
                val clean = text.trim()
                toast("Adding watermark…")
                Thread {
                    try {
                        val out = File(hakunaaCacheDir(), "hakunaa_watermarked_${System.currentTimeMillis()}.pdf")
                        val ok = PdfTools.watermark(input, out, clean)
                        val savedUri = if (ok) publishPdfUri(out) else null
                        runOnUiThread {
                            if (ok && savedUri != null) showSavedPdfDialog(out, savedUri)
                            else toast("Watermark failed — check the file and try again")
                        }
                    } catch (_: Throwable) {
                        runOnUiThread { toast("Watermark failed — check the file and try again") }
                    }
                }.start()
            }
        }
    }

    private fun pageNumbersPdf() {
        pickPdfThen { input ->
            toast("Adding page numbers…")
            Thread {
                try {
                    val out = File(hakunaaCacheDir(), "hakunaa_numbered_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.pageNumbers(input, out)
                    val savedUri = if (ok) publishPdfUri(out) else null
                    runOnUiThread {
                        if (ok && savedUri != null) showSavedPdfDialog(out, savedUri)
                        else toast("Page numbers failed — check the file and try again")
                    }
                } catch (_: Throwable) {
                    runOnUiThread { toast("Page numbers failed — check the file and try again") }
                }
            }.start()
        }
    }

    private fun extractImagesPdf() {
        pickPdfThen { input ->
            toast("Extracting images…")
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
                        when {
                            published > 0 -> toast("Done — $published image(s) saved to Pictures/Hakunaa")
                            created.isEmpty() -> toast("No images found in this PDF")
                            else -> toast("Extract failed — check the file and try again")
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { toast("Extract failed — check the file and try again") }
                }
            }.start()
        }
    }

    private fun pdfToJpg() {
        pickPdfThen { input ->
            toast("Converting PDF to JPG…")
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
                        if (published > 0) toast("Done — $published page(s) saved to Pictures/Hakunaa")
                        else toast("PDF to JPG failed — check the file and try again")
                    }
                } catch (_: Throwable) {
                    runOnUiThread { toast("PDF to JPG failed — check the file and try again") }
                }
            }.start()
        }
    }

    private fun extractTextPdf() {
        pickPdfThen { input ->
            toast("Extracting text…")
            Thread {
                try {
                    val text = PdfTools.extractText(input)
                    if (text == null) {
                        runOnUiThread { toast("Extract failed — check the file and try again") }
                        return@Thread
                    }
                    val out = File(hakunaaCacheDir(), "${input.nameWithoutExtension}.txt")
                    out.writeText(text)
                    val saved = MediaStoreSaver.save(this, out, MediaStoreSaver.Kind.DOCUMENT, out.name, "text/plain")
                    runOnUiThread {
                        if (saved != null) toast("Done — text saved to Documents/Hakunaa/${out.name} (${text.length} characters)")
                        else toast("Extract failed — couldn't save the text file")
                    }
                } catch (_: Throwable) {
                    runOnUiThread { toast("Extract failed — check the file and try again") }
                }
            }.start()
        }
    }

    private fun protectUnlockPdf() {
        pickPdfThen { input ->
            showSingleChoiceDialog("Protect or Unlock?", arrayOf("Protect PDF", "Unlock PDF")) { which ->
                val isProtect = which == 0
                showTextInputDialog(
                    if (isProtect) "Protect PDF" else "Unlock PDF",
                    "Type a password",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                ) { password ->
                    if (password.isEmpty()) {
                        toast("Type a password first")
                        return@showTextInputDialog
                    }
                    if (isProtect) runProtect(input, password) else runUnlock(input, password)
                }
            }
        }
    }

    private fun runProtect(input: File, password: String) {
        toast("Protecting PDF…")
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_protected_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.protect(input, out, password)
                val savedUri = if (ok) publishPdfUri(out) else null
                runOnUiThread {
                    if (ok && savedUri != null) showSavedPdfDialog(out, savedUri)
                    else toast("Protect failed — check the file and try again")
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Protect failed — check the file and try again") }
            }
        }.start()
    }

    private fun runUnlock(input: File, password: String) {
        toast("Unlocking PDF…")
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_unlocked_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.unlock(input, out, password)
                val savedUri = if (ok) publishPdfUri(out) else null
                runOnUiThread {
                    if (ok && savedUri != null) showSavedPdfDialog(out, savedUri)
                    else toast("Unlock failed — wrong password or file not locked")
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Unlock failed — wrong password or file not locked") }
            }
        }.start()
    }

    private fun organizePdf() {
        pickPdfThen { input ->
            showTextInputDialog(
                "New page order", "e.g. 3,1,2", InputType.TYPE_CLASS_TEXT
            ) { orderText ->
                val order = orderText.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (order.isEmpty()) {
                    toast("Type the new page order, e.g. 3,1,2")
                    return@showTextInputDialog
                }
                toast("Organizing PDF…")
                Thread {
                    try {
                        val out = File(hakunaaCacheDir(), "hakunaa_organized_${System.currentTimeMillis()}.pdf")
                        val ok = PdfTools.organize(input, out, order)
                        val savedUri = if (ok) publishPdfUri(out) else null
                        runOnUiThread {
                            if (ok && savedUri != null) showSavedPdfDialog(out, savedUri)
                            else toast("Organize failed — list every page exactly once, e.g. 3,1,2")
                        }
                    } catch (_: Throwable) {
                        runOnUiThread { toast("Organize failed — list every page exactly once, e.g. 3,1,2") }
                    }
                }.start()
            }
        }
    }

    // ------------------------------------------------------------------
    // Repair PDF (v0.1.148, new). Already per-tool pick.
    // ------------------------------------------------------------------

    private fun repairPdf() {
        singlePdfCallback = { uri ->
            toast("Repairing PDF…")
            Thread {
                try {
                    val input = FileUtils.copyUriToCache(this, uri, "repair.pdf")
                    val out = File(hakunaaCacheDir(), "hakunaa_repaired_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.repair(input, out)
                    val savedUri = if (ok) publishPdfUri(out) else null
                    runOnUiThread {
                        if (ok && savedUri != null) showSavedPdfDialog(out, savedUri)
                        else if (ok) toast("Done — repaired, but couldn't save it. Try again.")
                        else toast("Could not repair — this file is too damaged")
                    }
                } catch (_: Throwable) {
                    runOnUiThread { toast("Could not repair — this file is too damaged") }
                }
            }.start()
        }
        pickSinglePdf.launch("application/pdf")
    }

    // ------------------------------------------------------------------
    // Resize Pages (v0.1.148, new).
    // ------------------------------------------------------------------

    private fun resizePdf() {
        pickPdfThen { input ->
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
    }

    private fun runResize(input: File, target: PDRectangle, label: String) {
        toast("Resizing pages to $label…")
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_resized_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.resizePages(input, out, target)
                val savedUri = if (ok) publishPdfUri(out) else null
                runOnUiThread {
                    if (ok && savedUri != null) showSavedPdfDialog(out, savedUri)
                    else toast("Resize failed — check the file and try again")
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Resize failed — check the file and try again") }
            }
        }.start()
    }

    // ------------------------------------------------------------------
    // Smart Compress results overlay (v0.1.149, like Image Smart).
    // 5 levels with REAL sizes, user picks one to save, plus a
    // Custom size section below (type a target size, e.g. 100 KB).
    // Full-screen overlay inside this activity — no new Activity.
    // ------------------------------------------------------------------

    private fun showSmartResults(input: File) {
        if (smartOverlay != null) return
        smartInput = input
        smartOriginalSize = input.length()
        smartLevelFiles = mutableListOf()
        smartRows = mutableListOf()
        smartRowFiles = mutableListOf()
        smartSelected = -1
        smartCustomFile = null
        smartCustomRow = null

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
            setOnClickListener { dismissSmartOverlay() }
        }
        val title = TextView(this).apply {
            text = "Smart Compress"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(close)
        header.addView(title)
        root.addView(header)

        val fileLine = TextView(this).apply {
            text = "Original: ${input.name} • ${FileUtils.formatSize(smartOriginalSize)}"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        root.addView(fileLine)

        tvSmartComputing = TextView(this).apply {
            text = "Computing 5 levels…"
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        }
        root.addView(tvSmartComputing)

        smartResultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        root.addView(smartResultsBox)

        val customLabel = TextView(this).apply {
            text = "Custom size"
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
        root.addView(customLabel)
        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        etSmartCustomKb = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "e.g. 100"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val kbLabel = TextView(this).apply {
            text = "KB"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8); marginEnd = dp(12) }
        }
        val btnCustom = MaterialButton(this).apply {
            text = "Compress"
            setOnClickListener { runSmartCustomSize() }
        }
        customRow.addView(etSmartCustomKb)
        customRow.addView(kbLabel)
        customRow.addView(btnCustom)
        root.addView(customRow)
        tvSmartCustomResult = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        root.addView(tvSmartCustomResult)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(spacer)

        val btnSave = MaterialButton(this).apply {
            text = "Save selected"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(16) }
            setOnClickListener { saveSmartSelected() }
        }
        root.addView(btnSave)

        findViewById<ViewGroup>(android.R.id.content).addView(root)
        smartOverlay = root

        Thread {
            try {
                val levels = PdfTools.compressSmartLevels(input, hakunaaCacheDir())
                runOnUiThread {
                    if (levels.isEmpty()) {
                        tvSmartComputing?.text = "Already optimized — even the strongest Smart settings couldn't shrink this PDF."
                        return@runOnUiThread
                    }
                    tvSmartComputing?.visibility = View.GONE
                    smartLevelFiles = levels.map { it.file }.toMutableList()
                    for (level in levels) {
                        val sizeText = "${FileUtils.formatSize(level.size)} • saved ${FileUtils.savedPercent(smartOriginalSize, level.size)}"
                        val row = buildSmartResultRow(level.label, sizeText)
                        smartResultsBox?.addView(row)
                        smartRows.add(row)
                        smartRowFiles.add(level.file)
                    }
                    // Default: smallest (levels are best quality
                    // first, so the smallest is the last row).
                    selectSmartRow(smartRows.size - 1)
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    tvSmartComputing?.text = "Compress failed — check the file and try again"
                }
            }
        }.start()
    }

    private fun buildSmartResultRow(label: String, sizeText: String): LinearLayout {
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(Color.WHITE)
                setStroke((1 * density).toInt(), Color.parseColor("#CFD8DC"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = getDrawable(outValue.resourceId)
        }
        row.setOnClickListener {
            val idx = smartRows.indexOf(row)
            if (idx >= 0) selectSmartRow(idx)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvLabel = TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        val tvSize = TextView(this).apply {
            text = sizeText
            textSize = 12f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
        }
        left.addView(tvLabel)
        left.addView(tvSize)
        val check = TextView(this).apply {
            text = "✓"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_teal))
            visibility = View.INVISIBLE
        }
        row.addView(left)
        row.addView(check)
        return row
    }

    private fun selectSmartRow(index: Int) {
        smartSelected = index
        val density = resources.displayMetrics.density
        for (i in smartRows.indices) {
            val row = smartRows[i]
            val selected = i == index
            row.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                if (selected) {
                    setColor(0xFFE0F2F1.toInt())
                    setStroke((2 * density).toInt(), getColor(R.color.hakunaa_teal))
                } else {
                    setColor(Color.WHITE)
                    setStroke((1 * density).toInt(), Color.parseColor("#CFD8DC"))
                }
            }
            val check = row.getChildAt(1) as TextView
            check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun saveSmartSelected() {
        if (smartSelected < 0 || smartSelected >= smartRowFiles.size) {
            toast("Pick a result first")
            return
        }
        val file = smartRowFiles[smartSelected] ?: run {
            toast("Pick a result first")
            return
        }
        if (!file.exists()) {
            toast("That result is gone — compute again")
            return
        }
        Thread {
            try {
                val savedUri = publishPdfUri(file)
                runOnUiThread {
                    if (savedUri != null) {
                        dismissSmartOverlay()
                        showSavedPdfDialog(file, savedUri)
                    } else {
                        toast("Save failed — try again")
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Save failed — try again") }
            }
        }.start()
    }

    private fun runSmartCustomSize() {
        val input = smartInput ?: return
        val kb = etSmartCustomKb?.text?.toString()?.trim()?.toIntOrNull()
        if (kb == null || kb <= 0) {
            toast("Type a size in KB, e.g. 100")
            return
        }
        val targetBytes = kb.toLong() * 1024L
        if (targetBytes >= smartOriginalSize) {
            tvSmartCustomResult?.text = "Already smaller than $kb KB — no need to compress"
            return
        }
        tvSmartCustomResult?.text = "Computing…"
        Thread {
            try {
                val result = PdfTools.compressSmartToTarget(input, hakunaaCacheDir(), targetBytes)
                runOnUiThread {
                    val f = result.file
                    if (f == null) {
                        tvSmartCustomResult?.text = "Could not shrink this PDF at all"
                        return@runOnUiThread
                    }
                    smartCustomFile?.let { old ->
                        if (old != f) try { old.delete() } catch (_: Throwable) { }
                    }
                    smartCustomFile = f
                    val sizeText = "${FileUtils.formatSize(result.size)} • saved ${FileUtils.savedPercent(smartOriginalSize, result.size)}"
                    val label = "Custom • $kb KB"
                    if (result.reached) {
                        tvSmartCustomResult?.text = "Done — $sizeText"
                    } else {
                        tvSmartCustomResult?.text = "Could not reach $kb KB — smallest is ${FileUtils.formatSize(result.smallestSize)}"
                    }
                    updateSmartCustomRow(label, sizeText, f)
                }
            } catch (_: Throwable) {
                runOnUiThread { tvSmartCustomResult?.text = "Custom size failed — try again" }
            }
        }.start()
    }

    private fun updateSmartCustomRow(label: String, sizeText: String, file: File) {
        val box = smartResultsBox ?: return
        val row = smartCustomRow
        if (row == null) {
            val newRow = buildSmartResultRow(label, sizeText)
            smartCustomRow = newRow
            box.addView(newRow)
            smartRows.add(newRow)
            smartRowFiles.add(file)
            selectSmartRow(smartRows.size - 1)
        } else {
            val left = row.getChildAt(0) as LinearLayout
            (left.getChildAt(0) as TextView).text = label
            (left.getChildAt(1) as TextView).text = sizeText
            val idx = smartRows.indexOf(row)
            if (idx >= 0) {
                smartRowFiles[idx] = file
                selectSmartRow(idx)
            }
        }
    }

    private fun dismissSmartOverlay() {
        val overlay = smartOverlay ?: return
        findViewById<ViewGroup>(android.R.id.content).removeView(overlay)
        smartOverlay = null
        for (f in smartLevelFiles) try { f.delete() } catch (_: Throwable) { }
        smartCustomFile?.let { try { it.delete() } catch (_: Throwable) { } }
        smartLevelFiles = mutableListOf()
        smartRows = mutableListOf()
        smartRowFiles = mutableListOf()
        smartSelected = -1
        smartCustomFile = null
        smartCustomRow = null
        tvSmartComputing = null
        smartResultsBox = null
        tvSmartCustomResult = null
        etSmartCustomKb = null
        smartInput = null
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
