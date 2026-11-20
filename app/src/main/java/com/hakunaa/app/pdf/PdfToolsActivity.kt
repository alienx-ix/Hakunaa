package com.hakunaa.app.pdf

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
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
    private var smartCustomUnitMb = false
    private var smartCustomDialog: Dialog? = null
    private var customResultBox: LinearLayout? = null
    private var tvCustomResultSize: TextView? = null
    private var tvSmartComputing: TextView? = null
    private var smartResultsBox: LinearLayout? = null
    private var tvSmartCustomResult: TextView? = null
    private var etSmartCustomKb: EditText? = null
    private var ivSmartPreview: ImageView? = null
    private var smartPreviewBitmap: Bitmap? = null

    // View (open PDF in the default viewer) publishes the cache
    // file to Documents/Hakunaa once via MediaStore and reuses
    // that URI — so View + Save never create duplicate copies.
    private val publishedUris = mutableMapOf<File, Uri>()

    // Lossless Compress center result card state (v0.1.150).
    private var losslessOverlay: View? = null
    private var losslessResultFile: File? = null
    private var losslessPreviewBitmap: Bitmap? = null

    // Merge PDF overlay state (v0.1.150): drag-reorder list,
    // then a 3-size result page with rename.
    private var mergeOverlay: View? = null
    private var mergeFiles = mutableListOf<File>()
    private var mergeThumbCache = mutableMapOf<File, Bitmap>()
    private var mergePreviewBitmap: Bitmap? = null
    private var mergeAdapter: MergeAdapter? = null
    private var mergeTouchHelper: ItemTouchHelper? = null
    private var mergeResultFiles = mutableListOf<File>()
    private var mergeResultRows = mutableListOf<LinearLayout>()
    private var mergeResultSelected = -1
    private var mergeOriginalSize = 0L
    private var etMergeName: EditText? = null

    // Split PDF overlay state (v0.1.150).
    private var splitOverlay: View? = null
    private var splitInput: File? = null
    private var splitPageCount = 0
    private var splitOption = 0 // 0 range, 1 every N, 2 single pages
    private var etSplitRange: EditText? = null
    private var etSplitEvery: EditText? = null
    private var etSplitSingle: EditText? = null
    private var splitResultFiles = mutableListOf<File>()

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
                    losslessOverlay != null -> dismissLosslessOverlay()
                    mergeOverlay != null -> dismissMergeOverlay()
                    splitOverlay != null -> dismissSplitOverlay()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Status-bar height in px (v0.1.151, Fix #7): the tool overlays
     * are added to android.R.id.content, ABOVE the activity's
     * fitsSystemWindows insets, so each overlay root must add this
     * top padding itself — otherwise the header overlaps the bar. */
    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    /** Plain TextView pill button for Dialogs (v0.1.151, Fix #1):
     * the proven pattern from the WebP View button — a plain
     * TextView + GradientDrawable, never a MaterialButton (whose
     * text clipped to nothing in the v0.1.150 tool popup). The
     * caller sets the LayoutParams (row pill or full width). */
    private fun popupPillButton(text: String, filled: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                if (filled) {
                    setColor(getColor(R.color.hakunaa_teal))
                } else {
                    setColor(Color.WHITE)
                    setStroke(dp(1), getColor(R.color.hakunaa_teal))
                }
            }
            setTextColor(if (filled) Color.WHITE else getColor(R.color.hakunaa_teal))
            setOnClickListener { onClick() }
        }
    }

    /** Rounded white input box (v0.1.151): EditTexts with a bordered
     * box look professional; the bare underline looks unfinished. */
    private fun inputBoxBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(1), Color.parseColor("#CFD8DC"))
        }
    }

    /** Copy a picked file to the cache KEEPING its original file
     * name (v0.1.151, Fix #7): the old "single.pdf" / "merge_N.pdf"
     * names hid the real file name from every screen. Names are
     * made unique when two picked files share one. */
    private fun copyUriToCacheNamed(uri: Uri): File {
        var name = FileUtils.fileName(contentResolver, uri)
            .replace("/", "_").replace("\\", "_")
        if (name.isBlank()) name = "file.pdf"
        val base = name.substringBeforeLast(".", name)
        val ext = name.substringAfterLast(".", "")
        var candidate = name
        var n = 2
        while (File(cacheDir, candidate).exists()) {
            candidate = if (ext.isEmpty() || ext == name) "${base}_$n" else "${base}_$n.$ext"
            n++
        }
        return FileUtils.copyUriToCache(this, uri, candidate)
    }


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
        val explain: String,
        val pickLabel: String = "Pick PDF",
        val action: () -> Unit
    )

    private fun buildMenu() {
        val area = findViewById<LinearLayout>(R.id.contentArea)
        val featured = listOf(
            listOf(
                Tool("Smart Compress", "Best size", PdfIconView.Kind.SMART, true,
                    "Shrink your PDF the smart way — 5 quality levels to pick from, plus a custom target size. Smaller file, same look. Pick the level that fits your need.") { compressSmartPdf() },
                Tool("Lossless Compress", "No quality loss", PdfIconView.Kind.LOSSLESS, true,
                    "Squeeze out wasted space without touching quality — no image is re-compressed, every pixel stays exactly the same. Best when you want a smaller file with zero quality loss.") { compressPdf() }
            ),
            listOf(
                Tool("Merge PDF", "PDF a serkardhu", PdfIconView.Kind.MERGE, true,
                    "Combine many PDFs into one — pick files, drag them into any order, then merge. The top file comes first in the merged PDF.") { mergePdfs() },
                Tool("Split PDF", "Pages pirikardhu", PdfIconView.Kind.SPLIT, true,
                    "Cut one PDF into smaller PDFs — by page ranges, every N pages, or single pages. Each part is saved as its own PDF.") { splitPdf() }
            )
        )
        val grid = listOf(
            listOf(
                Tool("Organize PDF", null, PdfIconView.Kind.ORGANIZE, false,
                    "Reorder the pages of your PDF — put them in any order you like. Type the new order, e.g. 3,1,2.") { organizePdf() },
                Tool("Sign PDF", null, PdfIconView.Kind.SIGN, false,
                    "Draw your signature and stamp it on any page of your PDF. Choose the page, position and size.") { showSignOverlay() },
                Tool("PDF to JPG", null, PdfIconView.Kind.JPG, false,
                    "Turn every page of your PDF into a JPG image. Each page is saved as its own photo.") { pdfToJpg() }
            ),
            listOf(
                Tool("Protect / Unlock", null, PdfIconView.Kind.LOCK, false,
                    "Lock your PDF with a password — or unlock one you already know the password for. Without the password a locked PDF cannot be opened.") { protectUnlockPdf() },
                Tool("Repair PDF", null, PdfIconView.Kind.REPAIR, false,
                    "Fix a PDF that won't open — rebuilds the broken file structure and saves a clean copy. Works on most damaged files.") { repairPdf() },
                Tool("Page Numbers", null, PdfIconView.Kind.PAGE_NO, false,
                    "Add page numbers to every page of your PDF. The numbers appear at the bottom of each page.") { pageNumbersPdf() }
            ),
            listOf(
                Tool("Rotate PDF", null, PdfIconView.Kind.ROTATE, false,
                    "Rotate all pages of your PDF by 90°, 180° or 270°. The content stays exactly the same.") { rotatePdf() },
                Tool("Image to PDF", null, PdfIconView.Kind.IMG_PDF, false,
                    "Turn your photos into a PDF — pick images, reorder them, choose the page size. A4 or Letter, portrait or landscape.", "Pick Image") { pickImageForPdf.launch("image/*") },
                Tool("Extract Text", null, PdfIconView.Kind.TEXT, false,
                    "Pull all the text out of your PDF into a plain text file. Saved to Documents/Hakunaa.") { extractTextPdf() }
            ),
            listOf(
                Tool("Watermark", null, PdfIconView.Kind.WATERMARK, false,
                    "Stamp your own text across every page of your PDF. Type any text, e.g. CONFIDENTIAL.") { watermarkPdf() },
                Tool("Extract Images", null, PdfIconView.Kind.IMG_EXTRACT, false,
                    "Save every photo inside your PDF as its own image file. The images are saved to Pictures/Hakunaa.") { extractImagesPdf() },
                Tool("Resize Pages", null, PdfIconView.Kind.RESIZE, false,
                    "Change every page to A4 or Letter size, portrait or landscape. Text stays sharp — it is not re-printed as an image.") { resizePdf() }
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
            // v0.1.150: tapping a tool opens its intro popup first
            // (explanation + Pick PDF + Back) — never the file
            // picker directly.
            setOnClickListener { showToolPopup(tool) }
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
    // Tool intro popup (v0.1.150, ALL tools): tapping a tool opens
    // this popup FIRST — never the file picker directly. It explains
    // what the tool does, with a Pick button (opens the file
    // picker for that tool) and a Back button (back to the menu).
    // ------------------------------------------------------------------

    private fun showToolPopup(tool: Tool) {
        val dialog = Dialog(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * resources.displayMetrics.density
                setColor(Color.WHITE)
            }
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val icon = PdfIconView(this).apply {
            kind = tool.kind
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        card.addView(icon)
        val title = TextView(this).apply {
            text = tool.name
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        card.addView(title)
        val explain = TextView(this).apply {
            text = tool.explain
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        card.addView(explain)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        val btnBack = popupPillButton("Back", false) { dialog.dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnPick = popupPillButton(tool.pickLabel, true) {
            dialog.dismiss()
            tool.action()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(8)
            }
        }
        btnRow.addView(btnBack)
        btnRow.addView(btnPick)
        card.addView(btnRow)
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // v0.1.151, Fix #1 ROOT CAUSE: a Dialog left at its default
        // WRAP_CONTENT window size measured this card at a collapsed
        // width (icon + padding only), so the explanation wrapped
        // into a sliver and the weight-0 buttons got no width — their
        // labels clipped to nothing. Force the window to the full
        // width (the same pattern SavedDialog already uses) so the
        // text wraps at the real width and the pills get their share.
        // The buttons are plain TextView pills now, not
        // MaterialButtons, so their labels cannot be clipped by
        // Material theming either.
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    /** Teal filled button (white text) — the app's primary action. */
    private fun styleFilledButton(btn: MaterialButton) {
        btn.backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
        btn.setTextColor(Color.WHITE)
        btn.strokeWidth = 0
        btn.cornerRadius = dp(12)
    }

    /** Teal outline button (white fill, teal text) — secondary action. */
    private fun styleOutlineButton(btn: MaterialButton) {
        btn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        btn.setTextColor(getColor(R.color.hakunaa_teal))
        btn.strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
        btn.strokeWidth = dp(1)
        btn.cornerRadius = dp(12)
    }

    /**
     * View a PDF in the phone's default PDF viewer (v0.1.150).
     * The file is published to Documents/Hakunaa once via
     * MediaStore and the URI is reused — View + Save never
     * create duplicate copies. (The app has no FileProvider and
     * the Manifest is out of scope, so MediaStore is the way
     * PDFs open, same as the Saved dialog's Open button.)
     */
    private fun viewPdf(file: File) {
        Thread {
            try {
                var uri = publishedUris[file]
                if (uri == null) {
                    uri = publishPdfUri(file)
                    if (uri != null) publishedUris[file] = uri
                }
                val openUri = uri
                runOnUiThread {
                    if (openUri == null) {
                        toast("Could not open this PDF")
                        return@runOnUiThread
                    }
                    try {
                        val view = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(openUri, "application/pdf")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(view)
                    } catch (_: Throwable) {
                        toast("No PDF viewer found")
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Could not open this PDF") }
            }
        }.start()
    }

    /** Small white pill button (teal text, teal stroke) — the
     * proven-white View button pattern from the WebP window:
     * a plain TextView + GradientDrawable, never a MaterialButton
     * (MaterialButton white fill is unreliable, v0.1.144–147). */
    private fun viewPillButton(onClick: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = "View"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_teal))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(getColor(R.color.hakunaa_surface))
                setStroke((1 * density).toInt(), getColor(R.color.hakunaa_teal))
            }
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(40))
            setOnClickListener { onClick() }
        }
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

    /** Professional Saved popup for PDFs (v0.1.151, Fix #5): the
     * SAME design as Image tools' SavedDialog — green check,
     * first-page thumbnail, file name, location/size/format, and
     * Open / Share / Done. The old plain AlertDialog ("Saved to
     * Documents/Hakunaa/… / DONE OPEN") is gone from every PDF save. */
    private fun showSavedPdfDialog(outFile: File, savedUri: Uri?) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(true)
        val density = resources.displayMetrics.density
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
        }
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = 20f * density
            cardElevation = 8f * density
            setCardBackgroundColor(getColor(R.color.hakunaa_surface))
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(
                (24 * density).toInt(), (24 * density).toInt(),
                (24 * density).toInt(), (20 * density).toInt()
            )
        }
        val icon = TextView(this).apply {
            text = "✓"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.hakunaa_teal))
            }
            layoutParams = LinearLayout.LayoutParams(
                (48 * density).toInt(), (48 * density).toInt()
            )
        }
        val title = TextView(this).apply {
            text = "Saved"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (120 * density).toInt(), (120 * density).toInt()
            ).apply { topMargin = (16 * density).toInt() }
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#F1F3F4"))
                cornerRadius = 12f * density
            }
        }
        val name = TextView(this).apply {
            text = outFile.name
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val details = TextView(this).apply {
            text = "Documents/Hakunaa • ${FileUtils.formatSize(outFile.length())} • PDF"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            setPadding(0, (4 * density).toInt(), 0, 0)
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (20 * density).toInt(), 0, 0)
        }
        fun actionButton(text: String, filled: Boolean): MaterialButton {
            return MaterialButton(this).apply {
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
                    setTextColor(Color.WHITE)
                    backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                } else {
                    setTextColor(getColor(R.color.hakunaa_teal))
                    backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
                    strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_teal))
                    strokeWidth = (1 * density).toInt()
                }
            }
        }
        val openBtn = actionButton("Open", false)
        val shareBtn = actionButton("Share", false)
        val doneBtn = actionButton("Done", true)
        openBtn.setOnClickListener {
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
            dialog.dismiss()
        }
        shareBtn.setOnClickListener {
            if (savedUri != null) {
                try {
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, savedUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(share, "Share"))
                } catch (_: Throwable) {
                    toast("Couldn't share")
                }
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
        dialog.show()
        // First-page thumbnail, rendered off the UI thread.
        Thread {
            try {
                val bmp = PdfTools.renderFirstPageThumb(outFile, 240)
                runOnUiThread { if (bmp != null) thumb.setImageBitmap(bmp) }
            } catch (_: Throwable) {
                // No thumbnail — the dialog still works without it.
            }
        }.start()
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

    private fun publishPdfUri(file: File): Uri? {
        // v0.1.150: reuse a published URI — View + Save of the
        // same file must never create duplicate copies.
        publishedUris[file]?.let { return it }
        val uri = MediaStoreSaver.save(this, file, MediaStoreSaver.Kind.DOCUMENT, file.name)
        if (uri != null) publishedUris[file] = uri
        return uri
    }

    /**
     * Per-tool file pick (v0.1.149, Image Tools pattern): tap a tool
     * and the picker opens for THAT tool. The picked PDF is copied
     * to the cache and handed to [action] on the UI thread.
     */
    private fun pickPdfThen(action: (File) -> Unit) {
        singlePdfCallback = { uri ->
            Thread {
                try {
                    val file = copyUriToCacheNamed(uri)
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
                        val files = uris.map { uri -> copyUriToCacheNamed(uri) }
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
            showMergeList(inputs)
        }
    }

    /**
     * Merge PDF — file list with FULL drag-and-drop reorder
     * (v0.1.150): every picked PDF shows a clear order number
     * (1, 2, 3…), drag the ☰ handle to move any file anywhere.
     * Merge + Back at the bottom.
     */
    private fun showMergeList(files: List<File>) {
        mergeFiles = files.toMutableList()
        mergeResultFiles = mutableListOf()
        mergeResultRows = mutableListOf()
        mergeResultSelected = -1
        mergeOriginalSize = files.sumOf { it.length() }
        mergeAdapter = null
        mergeTouchHelper = null

        val root = if (mergeOverlay == null) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(R.color.hakunaa_surface))
                // v0.1.151, Fix #7: the overlay sits above the activity's
                // insets, so it adds the status-bar height itself —
                // otherwise the header overlaps the status bar.
                // Fix #9: consume ALL touches — without this, taps on
                // blank space fall through to the menu cards underneath
                // and open other tools.
                setPadding(dp(16), statusBarHeightPx() + dp(16), dp(16), dp(16))
                isClickable = true
                isFocusable = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }.also {
                findViewById<ViewGroup>(android.R.id.content).addView(it)
                mergeOverlay = it
            }
        } else {
            (mergeOverlay as LinearLayout).apply { removeAllViews() }
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
            setOnClickListener { dismissMergeOverlay() }
        }
        val title = TextView(this).apply {
            text = "Merge PDF"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(close)
        header.addView(title)
        root.addView(header)

        val hint = TextView(this).apply {
            text = "Drag ☰ to reorder — the top file comes first"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        root.addView(hint)

        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@PdfToolsActivity)
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
        }
        val adapter = MergeAdapter()
        mergeAdapter = adapter
        list.adapter = adapter
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                Collections.swap(mergeFiles, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // Renumber every row after a drag (the order badge
                // is position-based, like Image to PDF's Page N).
                adapter.notifyDataSetChanged()
            }
        })
        mergeTouchHelper = touchHelper
        touchHelper.attachToRecyclerView(list)
        root.addView(list)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val btnBack = MaterialButton(this).apply {
            text = "Back"
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { dismissMergeOverlay() }
        }
        styleOutlineButton(btnBack)
        val btnMerge = MaterialButton(this).apply {
            text = "Merge"
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener { runMerge() }
        }
        styleFilledButton(btnMerge)
        bottomRow.addView(btnBack)
        bottomRow.addView(btnMerge)
        root.addView(bottomRow)
    }

    /** One Merge list row: drag handle + order badge + name + size. */
    private inner class MergeAdapter : RecyclerView.Adapter<MergeAdapter.VH>() {
        inner class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val density = resources.displayMetrics.density
            val row = LinearLayout(this@PdfToolsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    setColor(Color.WHITE)
                    setStroke((1 * density).toInt(), Color.parseColor("#CFD8DC"))
                }
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
                setPadding(dp(8), dp(10), dp(12), dp(10))
            }
            val handle = TextView(this@PdfToolsActivity).apply {
                text = "☰"
                textSize = 20f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                setPadding(dp(4), dp(4), dp(8), dp(4))
            }
            // v0.1.151, Fix #7: a small first-page preview in the row.
            val thumb = ImageView(this@PdfToolsActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * density
                    setColor(Color.parseColor("#F1F3F4"))
                }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(60)).apply {
                    marginEnd = dp(10)
                }
            }
            val badge = TextView(this@PdfToolsActivity).apply {
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(R.color.hakunaa_teal))
                }
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(12)
                }
            }
            val name = TextView(this@PdfToolsActivity).apply {
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val size = TextView(this@PdfToolsActivity).apply {
                textSize = 12f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            }
            row.addView(handle)
            row.addView(thumb)
            row.addView(badge)
            row.addView(name)
            row.addView(size)
            val holder = VH(row)
            handle.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    mergeTouchHelper?.startDrag(holder)
                }
                false
            }
            return holder
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val file = mergeFiles[position]
            val row = holder.row
            (row.getChildAt(2) as TextView).text = "${position + 1}"
            (row.getChildAt(3) as TextView).text = file.name
            (row.getChildAt(4) as TextView).text = FileUtils.formatSize(file.length())
            // v0.1.151, Fix #7: first-page thumbnail, rendered off
            // the UI thread and cached per file.
            val thumb = row.getChildAt(1) as ImageView
            thumb.tag = file
            val cached = mergeThumbCache[file]
            if (cached != null) {
                thumb.setImageBitmap(cached)
            } else {
                thumb.setImageDrawable(null)
                Thread {
                    try {
                        val bmp = PdfTools.renderFirstPageThumb(file, 240)
                        runOnUiThread {
                            if (bmp != null && thumb.tag == file) {
                                mergeThumbCache[file] = bmp
                                thumb.setImageBitmap(bmp)
                            }
                        }
                    } catch (_: Throwable) {
                        // No preview — the row still works without it.
                    }
                }.start()
            }
        }

        override fun getItemCount(): Int = mergeFiles.size
    }

    private fun runMerge() {
        if (mergeFiles.size < 2) {
            toast("Pick at least 2 PDFs")
            return
        }
        toast("Merging ${mergeFiles.size} PDFs…")
        Thread {
            try {
                val merged = File(hakunaaCacheDir(), "hakunaa_merged_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.merge(mergeFiles, merged)
                if (!ok || !merged.exists()) {
                    runOnUiThread { toast("Merge failed — check the files and try again") }
                    return@Thread
                }
                // Compressed versions of the merged file (dropped
                // honestly if any is bigger than the original).
                val variants = mutableListOf<Triple<String, File, Long>>()
                val comp = File(hakunaaCacheDir(), "hakunaa_merged_comp_${System.currentTimeMillis()}.pdf")
                if (PdfTools.compressSmart(merged, comp, 150, 0.82f) >= 0 && comp.exists() && comp.length() < merged.length()) {
                    variants.add(Triple("Compressed • 150 DPI, JPEG 82%", comp, comp.length()))
                } else {
                    try { comp.delete() } catch (_: Throwable) { }
                }
                val extreme = File(hakunaaCacheDir(), "hakunaa_merged_extreme_${System.currentTimeMillis()}.pdf")
                if (PdfTools.compressSmart(merged, extreme, 72, 0.50f) >= 0 && extreme.exists() && extreme.length() < merged.length()) {
                    variants.add(Triple("Extreme • 72 DPI, JPEG 50%", extreme, extreme.length()))
                } else {
                    try { extreme.delete() } catch (_: Throwable) { }
                }
                runOnUiThread { showMergeResults(merged, variants) }
            } catch (_: Throwable) {
                runOnUiThread { toast("Merge failed — check the files and try again") }
            }
        }.start()
    }

    /**
     * Merge RESULT page (v0.1.150): THREE size levels with REAL
     * sizes (Original + compressed versions), a FILE RENAME field,
     * Save + Back. After save, the professional Saved dialog.
     */
    private fun showMergeResults(merged: File, variants: List<Triple<String, File, Long>>) {
        val root = mergeOverlay as? LinearLayout ?: return
        root.removeAllViews()
        mergeResultFiles = mutableListOf()
        mergeResultRows = mutableListOf()
        mergeResultSelected = -1

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
            setOnClickListener { dismissMergeOverlay() }
        }
        val title = TextView(this).apply {
            text = "Merge result"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(close)
        header.addView(title)
        root.addView(header)

        // v0.1.151, Fix #8: a preview of the merged PDF on top,
        // plus a short info line — the old screen was a bare
        // filename field over a big empty gap.
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.parseColor("#F1F3F4"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(170)
            ).apply { topMargin = dp(12) }
        }
        root.addView(preview)
        val infoLine = TextView(this).apply {
            text = "Merged from ${mergeFiles.size} files"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        root.addView(infoLine)
        Thread {
            try {
                val bmp = PdfTools.renderFirstPageThumb(merged, 480)
                val pages = PdfTools.pageInfo(merged, 0)?.first
                runOnUiThread {
                    if (bmp != null) {
                        mergePreviewBitmap = bmp
                        preview.setImageBitmap(bmp)
                    }
                    if (pages != null) {
                        infoLine.text = "$pages pages • merged from ${mergeFiles.size} files"
                    }
                }
            } catch (_: Throwable) {
                // No preview — the result screen still works.
            }
        }.start()

        val nameLabel = TextView(this).apply {
            text = "File name"
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
        root.addView(nameLabel)
        val defaultName = mergeFiles.firstOrNull()?.nameWithoutExtension?.let { "${it}_merged.pdf" }
            ?: "hakunaa_merged.pdf"
        // v0.1.151, Fix #8: the filename sits in a proper rounded
        // box, not a bare underline.
        val nameBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = inputBoxBackground()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        etMergeName = EditText(this).apply {
            setText(defaultName)
            textSize = 14f
            background = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        nameBox.addView(etMergeName)
        root.addView(nameBox)

        val sizesLabel = TextView(this).apply {
            text = "Choose a size"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(4)
            }
        }
        root.addView(sizesLabel)

        val total = mergeOriginalSize
        val originalRow = buildMergeResultRow(
            "Original", "${FileUtils.formatSize(merged.length())} • no recompression", merged
        )
        root.addView(originalRow)
        mergeResultRows.add(originalRow)
        mergeResultFiles.add(merged)
        for ((label, file, size) in variants) {
            val sizeText = "${FileUtils.formatSize(size)} • saved ${FileUtils.savedPercent(total, size)}"
            val row = buildMergeResultRow(label, sizeText, file)
            root.addView(row)
            mergeResultRows.add(row)
            mergeResultFiles.add(file)
        }
        selectMergeResult(0)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(spacer)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val btnBack = MaterialButton(this).apply {
            text = "Back"
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { showMergeList(mergeFiles) }
        }
        styleOutlineButton(btnBack)
        val btnSave = MaterialButton(this).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener { saveMergeResult() }
        }
        styleFilledButton(btnSave)
        bottomRow.addView(btnBack)
        bottomRow.addView(btnSave)
        root.addView(bottomRow)
    }

    private fun buildMergeResultRow(label: String, sizeText: String, file: File): LinearLayout {
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
            val idx = mergeResultRows.indexOf(row)
            if (idx >= 0) selectMergeResult(idx)
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
        val viewPill = viewPillButton { viewPdf(file) }
        val check = TextView(this).apply {
            text = "✓"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_teal))
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        row.addView(left)
        row.addView(viewPill)
        row.addView(check)
        return row
    }

    private fun selectMergeResult(index: Int) {
        mergeResultSelected = index
        val density = resources.displayMetrics.density
        for (i in mergeResultRows.indices) {
            val row = mergeResultRows[i]
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
            val check = row.getChildAt(2) as TextView
            check.visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun saveMergeResult() {
        if (mergeResultSelected < 0 || mergeResultSelected >= mergeResultFiles.size) {
            toast("Pick a size first")
            return
        }
        val file = mergeResultFiles[mergeResultSelected]
        if (!file.exists()) {
            toast("That result is gone — merge again")
            return
        }
        var name = etMergeName?.text?.toString()?.trim() ?: ""
        if (name.isBlank()) name = "hakunaa_merged.pdf"
        name = name.replace("/", "_").replace("\\", "_")
        if (!name.endsWith(".pdf", ignoreCase = true)) name += ".pdf"
        val renamed = File(hakunaaCacheDir(), name)
        Thread {
            try {
                if (renamed != file) file.copyTo(renamed, overwrite = true)
                val savedUri = publishPdfUri(renamed)
                runOnUiThread {
                    if (savedUri != null) {
                        dismissMergeOverlay()
                        showSavedPdfDialog(renamed, savedUri)
                    } else {
                        toast("Save failed — try again")
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Save failed — try again") }
            }
        }.start()
    }

    private fun dismissMergeOverlay() {
        val overlay = mergeOverlay ?: return
        findViewById<ViewGroup>(android.R.id.content).removeView(overlay)
        mergeOverlay = null
        mergeAdapter = null
        mergeTouchHelper = null
        for (f in mergeResultFiles) {
            if (!publishedUris.containsKey(f)) {
                try { f.delete() } catch (_: Throwable) { }
            }
        }
        mergeResultFiles = mutableListOf()
        mergeResultRows = mutableListOf()
        mergeResultSelected = -1
        etMergeName = null
        for ((_, bmp) in mergeThumbCache) try { bmp.recycle() } catch (_: Throwable) { }
        mergeThumbCache = mutableMapOf()
        mergePreviewBitmap?.recycle()
        mergePreviewBitmap = null
        // The picked input files are cache copies — safe to drop.
        for (f in mergeFiles) try { f.delete() } catch (_: Throwable) { }
        mergeFiles = mutableListOf()
    }

    private fun splitPdf() {
        pickPdfThen { input ->
            Thread {
                try {
                    val info = PdfTools.pageInfo(input, 0)
                    runOnUiThread {
                        if (info == null) {
                            toast("Could not open this PDF")
                        } else {
                            showSplitOptions(input, info.first)
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { toast("Could not open this PDF") }
                }
            }.start()
        }
    }

    /**
     * Split PDF — professional options (v0.1.150): the PDF's page
     * count is shown clearly, then 3 ways to split — by page
     * range, every N pages, or single pages. Best judgment layout,
     * matching the app's professional style.
     */
    private fun showSplitOptions(input: File, total: Int) {
        // Going back from results: drop the old result files.
        for (f in splitResultFiles) {
            if (!publishedUris.containsKey(f)) {
                try { f.delete() } catch (_: Throwable) { }
            }
        }
        splitResultFiles = mutableListOf()
        splitInput = input
        splitPageCount = total
        splitOption = 0

        val root = if (splitOverlay == null) {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(getColor(R.color.hakunaa_surface))
                // v0.1.151, Fix #7: the overlay sits above the activity's
                // insets, so it adds the status-bar height itself —
                // otherwise the header overlaps the status bar.
                // Fix #9: consume ALL touches — without this, taps on
                // blank space fall through to the menu cards underneath
                // and open other tools.
                setPadding(dp(16), statusBarHeightPx() + dp(16), dp(16), dp(16))
                isClickable = true
                isFocusable = true
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            }.also {
                findViewById<ViewGroup>(android.R.id.content).addView(it)
                splitOverlay = it
            }
        } else {
            (splitOverlay as LinearLayout).apply { removeAllViews() }
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
            setOnClickListener { dismissSplitOverlay() }
        }
        val title = TextView(this).apply {
            text = "Split PDF"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(close)
        header.addView(title)
        root.addView(header)

        val fileLine = TextView(this).apply {
            text = "${input.name} • $total page(s)"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        root.addView(fileLine)

        val optionsLabel = TextView(this).apply {
            text = "How do you want to split it?"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        root.addView(optionsLabel)

        val optionRows = mutableListOf<LinearLayout>()
        val optionInputs = mutableListOf<View>()
        val optionDefs = listOf(
            Triple("By page range", "Each range becomes one PDF", "e.g. 1-3, 4-6"),
            Triple("Every N pages", "Split into equal chunks of N pages", "e.g. 5"),
            Triple("Single pages", "One PDF per page", "e.g. 1,3,5 — empty = all pages")
        )
        for (i in optionDefs.indices) {
            val (optTitle, optSub, optHint) = optionDefs[i]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * resources.displayMetrics.density
                    setColor(Color.WHITE)
                    setStroke((1 * resources.displayMetrics.density).toInt(), Color.parseColor("#CFD8DC"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                setPadding(dp(14), dp(12), dp(14), dp(12))
                isClickable = true
                isFocusable = true
            }
            // v0.1.151, Fix #9: title + subtitle on the left, a
            // check on the selected option on the right.
            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvOptTitle = TextView(this).apply {
                text = optTitle
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
            }
            val tvOptSub = TextView(this).apply {
                text = optSub
                textSize = 12f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
            }
            textCol.addView(tvOptTitle)
            textCol.addView(tvOptSub)
            val check = TextView(this).apply {
                text = "✓"
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_teal))
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            }
            val headRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            headRow.addView(textCol)
            headRow.addView(check)
            row.addView(headRow)
            var inputView: View? = null
            if (optHint != null) {
                val et = EditText(this).apply {
                    hint = optHint
                    textSize = 14f
                    inputType = if (i == 1) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
                    background = inputBoxBackground()
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                    visibility = View.GONE
                }
                inputView = et
                row.addView(et)
            }
            if (i == 0) etSplitRange = inputView as? EditText
            if (i == 1) etSplitEvery = inputView as? EditText
            if (i == 2) etSplitSingle = inputView as? EditText
            optionInputs.add(inputView ?: View(this).apply { visibility = View.GONE })
            row.setOnClickListener { selectSplitOption(optionRows, optionInputs, i) }
            optionRows.add(row)
            root.addView(row)
        }
        selectSplitOption(optionRows, optionInputs, 0)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(spacer)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val btnBack = MaterialButton(this).apply {
            text = "Back"
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { dismissSplitOverlay() }
        }
        styleOutlineButton(btnBack)
        val btnSplit = MaterialButton(this).apply {
            text = "Split"
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener { runSplit() }
        }
        styleFilledButton(btnSplit)
        bottomRow.addView(btnBack)
        bottomRow.addView(btnSplit)
        root.addView(bottomRow)
    }

    private fun selectSplitOption(
        rows: List<LinearLayout>, inputs: List<View>, selected: Int
    ) {
        splitOption = selected
        val density = resources.displayMetrics.density
        for (i in rows.indices) {
            val row = rows[i]
            val isSel = i == selected
            row.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                if (isSel) {
                    setColor(0xFFE0F2F1.toInt())
                    setStroke((2 * density).toInt(), getColor(R.color.hakunaa_teal))
                } else {
                    setColor(Color.WHITE)
                    setStroke((1 * density).toInt(), Color.parseColor("#CFD8DC"))
                }
            }
            inputs[i].visibility = if (isSel) View.VISIBLE else View.GONE
            // v0.1.151: a check on the selected option.
            val headRow = row.getChildAt(0) as LinearLayout
            (headRow.getChildAt(1) as TextView).visibility =
                if (isSel) View.VISIBLE else View.GONE
        }
    }

    /** Parse "1-3, 4-6" into a list of page sets (one per range). Null if invalid. */
    private fun parseSplitRanges(text: String, total: Int): List<Set<Int>>? {
        val out = mutableListOf<Set<Int>>()
        for (part in text.split(",")) {
            val t = part.trim()
            if (t.isEmpty()) return null
            if (t.contains("-")) {
                val bits = t.split("-")
                if (bits.size != 2) return null
                val a = bits[0].trim().toIntOrNull() ?: return null
                val b = bits[1].trim().toIntOrNull() ?: return null
                if (a < 1 || b > total || a > b) return null
                out.add((a..b).toSet())
            } else {
                val n = t.toIntOrNull() ?: return null
                if (n < 1 || n > total) return null
                out.add(setOf(n))
            }
        }
        return if (out.isEmpty()) null else out
    }

    private fun runSplit() {
        val input = splitInput ?: return
        val total = splitPageCount
        if (total < 1) {
            toast("Could not read this PDF")
            return
        }
        toast("Splitting PDF…")
        Thread {
            try {
                val outDir = File(hakunaaCacheDir(), "split_${System.currentTimeMillis()}").apply { mkdirs() }
                val created = mutableListOf<File>()
                when (splitOption) {
                    0 -> {
                        val text = runOnUiThreadBlocking { etSplitRange?.text?.toString()?.trim() ?: "" }
                        val ranges = parseSplitRanges(text, total)
                        if (ranges == null) {
                            runOnUiThread { toast("Type valid ranges, e.g. 1-3, 4-6") }
                            return@Thread
                        }
                        for (range in ranges) {
                            val first = range.minOrNull() ?: continue
                            val last = range.maxOrNull() ?: continue
                            val out = File(outDir, "hakunaa_split_$first-$last.pdf")
                            if (PdfTools.extractPages(input, out, range) > 0) created.add(out)
                        }
                    }
                    1 -> {
                        val nText = runOnUiThreadBlocking { etSplitEvery?.text?.toString()?.trim() ?: "" }
                        val n = nText.toIntOrNull()
                        if (n == null || n < 1) {
                            runOnUiThread { toast("Type how many pages per file, e.g. 5") }
                            return@Thread
                        }
                        var start = 1
                        while (start <= total) {
                            val end = minOf(start + n - 1, total)
                            val out = File(outDir, "hakunaa_split_$start-$end.pdf")
                            if (PdfTools.extractPages(input, out, (start..end).toSet()) > 0) created.add(out)
                            start = end + 1
                        }
                    }
                    else -> {
                        // v0.1.151: an optional page list (e.g. 1,3,5)
                        // splits only those pages; empty = all pages.
                        val selText = runOnUiThreadBlocking { etSplitSingle?.text?.toString()?.trim() ?: "" }
                        if (selText.isEmpty()) {
                            created.addAll(PdfTools.split(input, outDir))
                        } else {
                            val pages = PdfTools.parsePageSelection(selText)
                            if (pages == null || pages.any { it < 1 || it > total }) {
                                runOnUiThread { toast("Type valid pages, e.g. 1,3,5") }
                                return@Thread
                            }
                            for (page in pages.sorted()) {
                                val out = File(outDir, "hakunaa_page_$page.pdf")
                                if (PdfTools.extractPages(input, out, setOf(page)) > 0) created.add(out)
                            }
                        }
                    }
                }
                runOnUiThread {
                    if (created.isEmpty()) {
                        toast("Split failed — check the file and try again")
                    } else {
                        showSplitResults(created)
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Split failed — check the file and try again") }
            }
        }.start()
    }

    /** Reads a value on the UI thread from a background thread. */
    private fun runOnUiThreadBlocking(block: () -> String): String {
        var result = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            result = block()
            latch.countDown()
        }
        latch.await()
        return result
    }

    /** Split RESULTS (v0.1.150): every created file with its size
     * and a View button, plus Save all + Back + Done. */
    private fun showSplitResults(files: List<File>) {
        val root = splitOverlay as? LinearLayout ?: return
        root.removeAllViews()
        splitResultFiles = files.toMutableList()

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
            setOnClickListener { dismissSplitOverlay() }
        }
        val title = TextView(this).apply {
            text = "Split result"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(close)
        header.addView(title)
        root.addView(header)

        val countLine = TextView(this).apply {
            text = "${files.size} file(s) created"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        root.addView(countLine)

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
        }
        val listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for (file in files) {
            listBox.addView(buildSplitResultRow(file))
        }
        scroll.addView(listBox)
        root.addView(scroll)

        val btnSaveAll = MaterialButton(this).apply {
            text = "Save all"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(12) }
            setOnClickListener { saveSplitAll() }
        }
        styleFilledButton(btnSaveAll)
        root.addView(btnSaveAll)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val btnBack = MaterialButton(this).apply {
            text = "Back"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener {
                val input = splitInput
                if (input != null) showSplitOptions(input, splitPageCount)
                else dismissSplitOverlay()
            }
        }
        styleOutlineButton(btnBack)
        val btnDone = MaterialButton(this).apply {
            text = "Done"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener { dismissSplitOverlay() }
        }
        styleFilledButton(btnDone)
        bottomRow.addView(btnBack)
        bottomRow.addView(btnDone)
        root.addView(bottomRow)
    }

    private fun buildSplitResultRow(file: File): LinearLayout {
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
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvName = TextView(this).apply {
            text = file.name
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val tvSize = TextView(this).apply {
            text = FileUtils.formatSize(file.length())
            textSize = 12f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
        }
        left.addView(tvName)
        left.addView(tvSize)
        row.addView(left)
        row.addView(viewPillButton { viewPdf(file) })
        return row
    }

    private fun saveSplitAll() {
        if (splitResultFiles.isEmpty()) {
            toast("Nothing to save")
            return
        }
        Thread {
            try {
                var saved = 0
                for (file in splitResultFiles) {
                    if (publishPdfUri(file) != null) saved++
                }
                runOnUiThread {
                    if (saved > 0) {
                        dismissSplitOverlay()
                        toast("Done — $saved file(s) saved to Documents/Hakunaa")
                    } else {
                        toast("Save failed — try again")
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Save failed — try again") }
            }
        }.start()
    }

    private fun dismissSplitOverlay() {
        val overlay = splitOverlay ?: return
        findViewById<ViewGroup>(android.R.id.content).removeView(overlay)
        splitOverlay = null
        for (f in splitResultFiles) {
            if (!publishedUris.containsKey(f)) {
                try { f.delete() } catch (_: Throwable) { }
            }
        }
        splitResultFiles = mutableListOf()
        etSplitRange = null
        etSplitEvery = null
        etSplitSingle = null
        splitInput = null
        splitPageCount = 0
    }

    private fun compressPdf() {
        pickPdfThen { input ->
            showLosslessScreen(input)
        }
    }

    /**
     * Lossless Compress — FULL tool screen (v0.1.151, Fix #6):
     * the old version showed the result in a popup over the menu
     * and stopped there ("menu ulayae pogala"). Now the flow goes
     * INSIDE the tool screen: preview + full explanation + file
     * info, then compress, then the CENTER result card in the same
     * screen. The compress itself is now REAL (v0.1.151, Fix #4).
     */
    private fun showLosslessScreen(input: File) {
        if (losslessOverlay != null) return
        losslessResultFile = null
        losslessPreviewBitmap = null
        val before = input.length()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.hakunaa_surface))
            setPadding(dp(16), statusBarHeightPx() + dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
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
            setOnClickListener { dismissLosslessOverlay() }
        }
        val title = TextView(this).apply {
            text = "Lossless Compress"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(close)
        header.addView(title)
        root.addView(header)

        val fileLine = TextView(this).apply {
            text = "${input.name} • ${FileUtils.formatSize(before)}"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        root.addView(fileLine)

        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.parseColor("#F1F3F4"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(170)
            ).apply { topMargin = dp(12) }
        }
        root.addView(preview)

        val explain = TextView(this).apply {
            text = "Lossless Compress squeezes out wasted space without touching quality — no image is re-compressed, every pixel stays exactly the same."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        root.addView(explain)

        // The CENTER result card lives here (filled in once the
        // compress finishes) — preview + explanation first, then
        // compress, exactly as the user asked.
        val resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(12) }
        }
        val tvStatus = TextView(this).apply {
            text = "Compressing…"
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        resultBox.addView(tvStatus)
        root.addView(resultBox)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val btnBack = MaterialButton(this).apply {
            text = "Back"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { dismissLosslessOverlay() }
        }
        styleOutlineButton(btnBack)
        val btnDone = MaterialButton(this).apply {
            text = "Done"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener { dismissLosslessOverlay() }
        }
        styleOutlineButton(btnDone)
        bottomRow.addView(btnBack)
        bottomRow.addView(btnDone)
        root.addView(bottomRow)

        findViewById<ViewGroup>(android.R.id.content).addView(root)
        losslessOverlay = root

        Thread {
            try {
                val thumb = PdfTools.renderFirstPageThumb(input, 480)
                val pages = PdfTools.pageInfo(input, 0)?.first
                val out = File(hakunaaCacheDir(), "hakunaa_compressed_${System.currentTimeMillis()}.pdf")
                val savedBytes = PdfTools.compressLossless(input, out)
                runOnUiThread {
                    if (losslessOverlay == null) return@runOnUiThread
                    if (thumb != null) {
                        losslessPreviewBitmap = thumb
                        preview.setImageBitmap(thumb)
                    }
                    if (pages != null) {
                        fileLine.text = "${input.name} • ${FileUtils.formatSize(before)} • $pages page(s)"
                    }
                    resultBox.removeAllViews()
                    when {
                        savedBytes > 0 -> {
                            losslessResultFile = out
                            resultBox.addView(buildLosslessResultCard(out, before))
                        }
                        savedBytes == 0L -> {
                            val tvAlready = TextView(this).apply {
                                text = "Already optimized — this PDF is as small as it gets without losing quality. Try Smart Compress for a smaller file."
                                textSize = 14f
                                setTextColor(getColor(R.color.hakunaa_text))
                                gravity = Gravity.CENTER
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                            }
                            resultBox.addView(tvAlready)
                        }
                        else -> {
                            tvStatus.text = "Compress failed — check the file and try again"
                            resultBox.addView(tvStatus)
                        }
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    if (losslessOverlay != null) {
                        resultBox.removeAllViews()
                        tvStatus.text = "Compress failed — check the file and try again"
                        resultBox.addView(tvStatus)
                    }
                }
            }
        }.start()
    }

    /** The CENTER result card for the Lossless screen: result with
     * a View button, a Save button below it. */
    private fun buildLosslessResultCard(result: File, before: Long): LinearLayout {
        val after = result.length()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val resultRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(0xFFE0F2F1.toInt())
                setStroke(dp(1), getColor(R.color.hakunaa_teal))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val resultText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvResult = TextView(this).apply {
            text = "${FileUtils.formatSize(before)} → ${FileUtils.formatSize(after)}"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        val tvSaved = TextView(this).apply {
            text = "saved ${FileUtils.savedPercent(before, after)} • quality 100% the same"
            textSize = 12f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
        }
        resultText.addView(tvResult)
        resultText.addView(tvSaved)
        resultRow.addView(resultText)
        resultRow.addView(viewPillButton { viewPdf(result) })
        card.addView(resultRow)
        val btnSave = MaterialButton(this).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(16) }
            setOnClickListener {
                Thread {
                    try {
                        val savedUri = publishPdfUri(result)
                        runOnUiThread {
                            if (savedUri != null) {
                                dismissLosslessOverlay()
                                showSavedPdfDialog(result, savedUri)
                            } else {
                                toast("Save failed — try again")
                            }
                        }
                    } catch (_: Throwable) {
                        runOnUiThread { toast("Save failed — try again") }
                    }
                }.start()
            }
        }
        styleFilledButton(btnSave)
        card.addView(btnSave)
        return card
    }

    private fun dismissLosslessOverlay() {
        val overlay = losslessOverlay ?: return
        findViewById<ViewGroup>(android.R.id.content).removeView(overlay)
        losslessOverlay = null
        losslessPreviewBitmap?.recycle()
        losslessPreviewBitmap = null
        losslessResultFile?.let { f ->
            if (!publishedUris.containsKey(f)) {
                try { f.delete() } catch (_: Throwable) { }
            }
        }
        losslessResultFile = null
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
        smartPreviewBitmap = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.hakunaa_surface))
            // v0.1.151, Fix #7: the overlay sits above the activity's
            // insets, so it adds the status-bar height itself —
            // otherwise the header overlaps the status bar.
            // Fix #9: consume ALL touches — without this, taps on
            // blank space fall through to the menu cards underneath
            // and open other tools.
            setPadding(dp(16), statusBarHeightPx() + dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
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

        // v0.1.150: TOP PREVIEW (first page) like Image Smart.
        ivSmartPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.parseColor("#F1F3F4"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(170)
            ).apply { topMargin = dp(12) }
        }
        root.addView(ivSmartPreview)

        val fileLine = TextView(this).apply {
            text = "Original: ${input.name} • ${FileUtils.formatSize(smartOriginalSize)}"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        root.addView(fileLine)

        val resultsLabel = TextView(this).apply {
            text = "Results"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        root.addView(resultsLabel)

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

        // Results scroll (weight 1) — the bottom buttons stay
        // pinned below, like Image Smart.
        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
        }
        smartResultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(smartResultsBox)
        root.addView(scroll)

        // Bottom buttons, top to bottom (v0.1.150, like Image
        // Smart): Save selected → Custom size → Close + Done.
        val btnSave = MaterialButton(this).apply {
            text = "Save selected"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(12) }
            setOnClickListener { saveSmartSelected() }
        }
        styleFilledButton(btnSave)
        root.addView(btnSave)

        val btnCustom = MaterialButton(this).apply {
            text = "Custom size"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(8) }
            setOnClickListener { showSmartCustomSizePopup() }
        }
        styleOutlineButton(btnCustom)
        root.addView(btnCustom)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val btnClose = MaterialButton(this).apply {
            text = "Close"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { dismissSmartOverlay() }
        }
        styleOutlineButton(btnClose)
        val btnDone = MaterialButton(this).apply {
            text = "Done"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                if (smartSelected >= 0) saveSmartSelected() else dismissSmartOverlay()
            }
        }
        styleFilledButton(btnDone)
        bottomRow.addView(btnClose)
        bottomRow.addView(btnDone)
        root.addView(bottomRow)

        findViewById<ViewGroup>(android.R.id.content).addView(root)
        smartOverlay = root

        Thread {
            try {
                val thumb = PdfTools.renderFirstPageThumb(input, 480)
                val levels = PdfTools.compressSmartLevels(input, hakunaaCacheDir())
                runOnUiThread {
                    if (thumb != null && smartOverlay != null) {
                        smartPreviewBitmap = thumb
                        ivSmartPreview?.setImageBitmap(thumb)
                    }
                    if (levels.isEmpty()) {
                        tvSmartComputing?.text = "Already optimized — even the strongest Smart settings couldn't shrink this PDF."
                        return@runOnUiThread
                    }
                    tvSmartComputing?.visibility = View.GONE
                    smartLevelFiles = levels.map { it.file }.toMutableList()
                    for (level in levels) {
                        val sizeText = "${FileUtils.formatSize(level.size)} • saved ${FileUtils.savedPercent(smartOriginalSize, level.size)}"
                        val row = buildSmartResultRow(level.label, sizeText, level.file)
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

    private fun buildSmartResultRow(label: String, sizeText: String, file: File?): LinearLayout {
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
        // v0.1.150: VIEW pill on every level — opens that result
        // PDF in the default viewer (Image Smart style).
        val viewPill = viewPillButton { if (file != null) viewPdf(file) }
        val check = TextView(this).apply {
            text = "✓"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_teal))
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        row.addView(left)
        row.addView(viewPill)
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
            // v0.1.150: check is child 2 now (left=0, View pill=1).
            val check = row.getChildAt(2) as TextView
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

    /**
     * Custom size POPUP (v0.1.151): the "Custom size" button opens
     * this popup — type a target size and Smart finds the best
     * quality that fits. KB or MB (Fix #2). The result is shown
     * IN THIS POPUP with View + Save (Fix #3) — it is NOT added
     * to the 5 levels list below.
     */
    private fun showSmartCustomSizePopup() {
        smartCustomDialog?.dismiss()
        smartCustomDialog = null
        val dialog = Dialog(this)
        smartCustomDialog = dialog
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * resources.displayMetrics.density
                setColor(Color.WHITE)
            }
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val title = TextView(this).apply {
            text = "Custom size"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
        }
        card.addView(title)
        val hintText = TextView(this).apply {
            text = "Type a target size — Smart finds the best quality that fits in it."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        card.addView(hintText)
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        etSmartCustomKb = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "e.g. 100"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        inputRow.addView(etSmartCustomKb)
        // v0.1.151, Fix #2: KB / MB unit toggle.
        val unitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        val unitPills = mutableListOf<TextView>()
        for (label in listOf("KB", "MB")) {
            val pill = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(32)
                ).apply { marginEnd = dp(4) }
                setOnClickListener {
                    smartCustomUnitMb = (label == "MB")
                    refreshUnitPills(unitPills)
                }
            }
            unitPills.add(pill)
            unitRow.addView(pill)
        }
        refreshUnitPills(unitPills)
        inputRow.addView(unitRow)
        card.addView(inputRow)
        tvSmartCustomResult = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        card.addView(tvSmartCustomResult)
        // v0.1.151, Fix #3: the result lives IN THIS POPUP.
        customResultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        tvCustomResultSize = TextView(this).apply {
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        customResultBox?.addView(tvCustomResultSize)
        val customBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val btnView = popupPillButton("View", false) {
            smartCustomFile?.let { viewPdf(it) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnSaveCustom = popupPillButton("Save", true) {
            saveSmartCustomFile()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(8)
            }
        }
        customBtnRow.addView(btnView)
        customBtnRow.addView(btnSaveCustom)
        customResultBox?.addView(customBtnRow)
        card.addView(customResultBox)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val btnClose = popupPillButton("Close", false) {
            dialog.dismiss()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnCompute = popupPillButton("Compress", true) {
            runSmartCustomSize()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(8)
            }
        }
        btnRow.addView(btnClose)
        btnRow.addView(btnCompute)
        card.addView(btnRow)
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setOnDismissListener {
            smartCustomDialog = null
            customResultBox = null
            tvCustomResultSize = null
        }
        dialog.show()
    }

    private fun refreshUnitPills(pills: List<TextView>) {
        for ((i, pill) in pills.withIndex()) {
            val selected = (i == 1) == smartCustomUnitMb
            pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                if (selected) {
                    setColor(getColor(R.color.hakunaa_teal))
                } else {
                    setColor(Color.WHITE)
                    setStroke(dp(1), getColor(R.color.hakunaa_teal))
                }
            }
            pill.setTextColor(if (selected) Color.WHITE else getColor(R.color.hakunaa_teal))
        }
    }

    private fun saveSmartCustomFile() {
        val file = smartCustomFile ?: return
        if (!file.exists()) {
            toast("That result is gone — compute again")
            return
        }
        smartCustomDialog?.dismiss()
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
        val value = etSmartCustomKb?.text?.toString()?.trim()?.toIntOrNull()
        if (value == null || value <= 0) {
            toast("Type a size, e.g. 100")
            return
        }
        // v0.1.151, Fix #2: KB or MB (1 MB = 1024 KB).
        val unitLabel = if (smartCustomUnitMb) "MB" else "KB"
        val mult = if (smartCustomUnitMb) 1024L * 1024L else 1024L
        val targetBytes = value.toLong() * mult
        if (targetBytes >= smartOriginalSize) {
            tvSmartCustomResult?.text = "Already smaller than $value $unitLabel — no need to compress"
            return
        }
        tvSmartCustomResult?.text = "Computing…"
        customResultBox?.visibility = View.GONE
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
                    if (result.reached) {
                        tvSmartCustomResult?.text = "Done — fits in $value $unitLabel"
                    } else {
                        tvSmartCustomResult?.text = "Could not reach $value $unitLabel — smallest is ${FileUtils.formatSize(result.smallestSize)}"
                    }
                    // v0.1.151, Fix #3: the result is shown IN THIS
                    // POPUP (View + Save here). It is NOT added to
                    // the 5 levels list below.
                    tvCustomResultSize?.text = sizeText
                    customResultBox?.visibility = View.VISIBLE
                }
            } catch (_: Throwable) {
                runOnUiThread { tvSmartCustomResult?.text = "Custom size failed — try again" }
            }
        }.start()
    }

    private fun dismissSmartOverlay() {
        val overlay = smartOverlay ?: return
        findViewById<ViewGroup>(android.R.id.content).removeView(overlay)
        smartOverlay = null
        smartPreviewBitmap?.recycle()
        smartPreviewBitmap = null
        ivSmartPreview = null
        for (f in smartLevelFiles) try { f.delete() } catch (_: Throwable) { }
        smartCustomFile?.let { try { it.delete() } catch (_: Throwable) { } }
        smartLevelFiles = mutableListOf()
        smartRows = mutableListOf()
        smartRowFiles = mutableListOf()
        smartSelected = -1
        smartCustomFile = null
        smartCustomDialog = null
        customResultBox = null
        tvCustomResultSize = null
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
            // v0.1.151, Fix #7: the overlay sits above the activity's
            // insets, so it adds the status-bar height itself —
            // otherwise the header overlaps the status bar.
            // Fix #9: consume ALL touches — without this, taps on
            // blank space fall through to the menu cards underneath
            // and open other tools.
            setPadding(dp(16), statusBarHeightPx() + dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
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
