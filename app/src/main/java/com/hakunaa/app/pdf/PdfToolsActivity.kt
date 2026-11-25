package com.hakunaa.app.pdf

import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Matrix
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
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.FileProvider
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.GridLayoutManager
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
import com.hakunaa.app.image.ImageToPdfActivity
import com.hakunaa.app.image.SavedDialog
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


    // Generic full-screen tool overlay (v0.1.153, priority #1):
    // EVERY tool screen is built on newToolOverlay() — one overlay
    // at a time, dismissed by Back or its own X button. Bitmaps
    // created for the screen are tracked here and recycled on
    // dismiss so big PDFs never leak memory.
    private var genericOverlay: View? = null
    private var genericOverlayBitmaps = mutableListOf<Bitmap>()

    // Sign screen state (v0.1.153, Fix #23).
    private var signInput: File? = null
    private var signPageIndex = 0
    private var signPageTotal = 0
    private var signPageW = 0f
    private var signPageH = 0f
    private var signPageBitmap: Bitmap? = null
    private var signSigColor = 0xFF111827.toInt()
    private var signArchive = false

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
    // v0.1.152, Fix #13: first-page preview at the top of Split.
    private var ivSplitPreview: ImageView? = null
    private var splitPreviewBitmap: Bitmap? = null
    private var etSplitRange: EditText? = null
    private var etSplitEvery: EditText? = null
    private var etSplitSingle: EditText? = null
    private var splitResultFiles = mutableListOf<File>()
    // v0.1.193: tap-to-select on the Split results (indices into
    // splitResultFiles) — "Save Selected" saves only these.
    private var splitResultSelected = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_tools)

        // Status bar (v0.1.97): keep it visible, no overlap.
        // Must be called AFTER setContentView (v0.1.133).
        StatusBar.setup(this, true)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        buildMenu()

        // System Back inside a PDF tool asks "Leave?" first
        // (v0.1.155, Fix #41 — Image Tools style), instead of
        // dropping straight back to the menu.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (genericOverlay != null || smartOverlay != null ||
                    losslessOverlay != null || mergeOverlay != null ||
                    splitOverlay != null
                ) {
                    attemptLeavePdf { dismissTopPdfOverlay() }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
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
                    setColor(getColor(R.color.hakunaa_blue))
                } else {
                    setColor(Color.WHITE)
                    setStroke(dp(1), getColor(R.color.hakunaa_blue))
                }
            }
            setTextColor(if (filled) Color.WHITE else getColor(R.color.hakunaa_blue))
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
    // ------------------------------------------------------------------
    // Generic full-screen tool overlay (v0.1.153, priority #1).
    // Every tool screen is built on newToolOverlay(): a full-screen
    // root above the menu (status-bar padding, consumes ALL touches
    // so nothing falls through to the menu behind), a header with
    // X + title, and a dismiss that recycles the screen's bitmaps.
    // ------------------------------------------------------------------

    private fun newToolOverlay(title: String): LinearLayout {
        dismissGenericOverlay()
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
            setOnClickListener { attemptLeavePdf { dismissGenericOverlay() } }
        }
        val titleView = TextView(this).apply {
            text = title
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(close)
        header.addView(titleView)
        root.addView(header)
        findViewById<ViewGroup>(android.R.id.content).addView(root)
        genericOverlay = root
        return root
    }

    private fun dismissGenericOverlay() {
        val overlay = genericOverlay ?: return
        try {
            findViewById<ViewGroup>(android.R.id.content).removeView(overlay)
        } catch (_: Throwable) { }
        genericOverlay = null
        for (bmp in genericOverlayBitmaps) {
            try { bmp.recycle() } catch (_: Throwable) { }
        }
        genericOverlayBitmaps = mutableListOf()
        signInput = null
        signPageBitmap = null
    }

    /** Dismiss whichever PDF tool overlay is open (same priority
     * as the old Back handler). Used by the leave dialog. */
    private fun dismissTopPdfOverlay() {
        when {
            genericOverlay != null -> dismissGenericOverlay()
            smartOverlay != null -> dismissSmartOverlay()
            losslessOverlay != null -> dismissLosslessOverlay()
            mergeOverlay != null -> dismissMergeOverlay()
            splitOverlay != null -> dismissSplitOverlay()
        }
    }

    /** Leave confirmation (v0.1.155, Fix #41): the SAME
     * "Leave this tool?" dialog Image Tools shows (attemptLeave)
     * — "!" in a teal circle, "Your work will be discarded.",
     * Cancel (outline) / Leave (filled teal). Shown when system
     * Back OR a tool screen's X / Back / Close is pressed inside
     * ANY PDF tool screen. Cancel stays in the tool; Leave runs
     * [onLeave] (dismisses the tool back to the menu). Done
     * buttons and save-success paths do NOT ask — that work is
     * finished. Buttons are the PDF-proven plain TextView pills
     * (v0.1.151), never MaterialButtons. */
    private fun attemptLeavePdf(onLeave: () -> Unit) {
        val dialog = Dialog(this)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
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
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(this).apply {
            text = "!"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.hakunaa_blue))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF5F8FF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val title = TextView(this).apply {
            text = "Leave this tool?"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(12) }
        }
        headerRow.addView(icon)
        headerRow.addView(title)
        val message = TextView(this).apply {
            text = "Your work will be discarded."
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            setPadding(0, dp(12), 0, 0)
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        val btnCancel = popupPillButton("Cancel", false) { dialog.dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnLeave = popupPillButton("Leave", true) {
            dialog.dismiss()
            onLeave()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(8)
            }
        }
        btnRow.addView(btnCancel)
        btnRow.addView(btnLeave)
        card.addView(headerRow)
        card.addView(message)
        card.addView(btnRow)
        outer.addView(card)
        dialog.setContentView(outer)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.setCancelable(true)
        dialog.show()
    }

    /** File info line under the header ("name • 12 pages • 2.3 MB"). */
    private fun toolFileLine(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
    }

    /** Standard 170dp first-page preview box. */
    private fun toolPreviewBox(): ImageView {
        return ImageView(this).apply {
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
    }

    /** Render the first page off the UI thread into [iv]. */
    private fun loadToolPreview(iv: ImageView, file: File, width: Int = 480) {
        Thread {
            try {
                val thumb = PdfTools.renderFirstPageThumb(file, width)
                runOnUiThread {
                    if (thumb != null && genericOverlay != null) {
                        genericOverlayBitmaps.add(thumb)
                        iv.setImageBitmap(thumb)
                    }
                }
            } catch (_: Throwable) { }
        }.start()
    }

    /** Bold section label ("Position", "Format", ...). */
    private fun toolSectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(16)
                bottomMargin = dp(8)
            }
        }
    }

    /** One row of option chips (equal width, 8dp gaps). */
    private fun toolChipRow(chips: List<TextView>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            for ((i, chip) in chips.withIndex()) {
                chip.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                }
                addView(chip)
            }
        }
    }

    /** Back (outline) + primary action (teal filled) bottom row. */
    private fun toolBottomRow(actionLabel: String, onAction: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val btnBack = popupPillButton("Back", false) { attemptLeavePdf { dismissGenericOverlay() } }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnAction = popupPillButton(actionLabel, true) { onAction() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(8)
            }
        }
        row.addView(btnBack)
        row.addView(btnAction)
        return row
    }

    /** Short grey explanation line under the preview. */
    private fun toolExplain(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
    }

    /** Result/status line inside a tool screen (grey, centred). */
    private fun toolStatusLine(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
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
        // v0.1.155, Fix #40: a shorter label for the grid card only
        // (the popup title and screen title keep [name]). It must
        // sit BEFORE [action]: the trailing lambda binds to the
        // last parameter, so a field after [action] would swallow it.
        val gridName: String? = null,
        val action: () -> Unit
    )

    private fun buildMenu() {
        val area = findViewById<LinearLayout>(R.id.contentArea)
        val featured = listOf(
            listOf(
                Tool("Smart Compress", "Best size", PdfIconView.Kind.SMART, true,
                    "• 5 quality levels — pick the best size\n• Custom target size (e.g. 100 KB)\n• Smaller file, same look") { compressSmartPdf() },
                Tool("Lossless Compress", "No quality loss", PdfIconView.Kind.LOSSLESS, true,
                    "• Smaller file, zero quality loss\n• No image is re-compressed\n• Every pixel stays exactly the same") { compressPdf() }
            ),
            listOf(
                Tool("Merge PDF", "Combine into one", PdfIconView.Kind.MERGE, true,
                    "• Combine many PDFs into one\n• Drag files into any order\n• Top file comes first") { mergePdfs() },
                Tool("Split PDF", "Split into pages", PdfIconView.Kind.SPLIT, true,
                    "• Cut one PDF into smaller PDFs\n• By page range, every N pages, or single pages") { splitPdf() }
            )
        )
        // v0.1.153: grid order — Fix #27 swaps PDF to JPG and Page
        // Numbers; Fix #26 puts Delete Pages in the old Extract
        // Text slot (row 3, third card). Extract Text is REMOVED
        // (Fix #25) — no replacement was invented for it.
        val grid = listOf(
            listOf(
                Tool("Organize PDF", null, PdfIconView.Kind.ORGANIZE, false,
                    "• Reorder pages with real previews\n• Drag, delete, duplicate, rotate") { organizePdf() },
                Tool("Sign PDF", null, PdfIconView.Kind.SIGN, false,
                    "• See your PDF page first\n• Draw your signature, place it anywhere") { signPdf() },
                Tool("Page Numbers", null, PdfIconView.Kind.PAGE_NO, false,
                    "• Add page numbers to every page\n• Position, format, size and colour") { pageNumbersPdf() }
            ),
            listOf(
                Tool("Protect / Unlock", null, PdfIconView.Kind.LOCK, false,
                    "• Lock your PDF with a password\n• Unlock one when you know the password",
                    gridName = "Lock / Unlock") { protectUnlockPdf() },
                Tool("Repair PDF", null, PdfIconView.Kind.REPAIR, false,
                    "• Fix a PDF that won't open\n• Rebuilds the broken file structure") { repairPdf() },
                Tool("PDF to JPG", null, PdfIconView.Kind.JPG, false,
                    "• Turn every page into a JPG image\n• Each page saved as its own photo") { pdfToJpg() }
            ),
            listOf(
                Tool("Rotate PDF", null, PdfIconView.Kind.ROTATE, false,
                    "• Rotate all pages 90°, 180° or 270°\n• Content stays exactly the same") { rotatePdf() },
                Tool("Image to PDF", null, PdfIconView.Kind.IMG_PDF, false,
                    "• Turn your photos into a PDF\n• Pick the ones you want, reorder them", "Pick Images") {
                    // v0.1.176: blue theme — Image to PDF follows the PDF
                    // menu theme when opened from here.
                    startActivity(Intent(this, ImageToPdfActivity::class.java).apply {
                        putExtra("blueTheme", true)
                    })
                },
                Tool("Delete Pages", null, PdfIconView.Kind.DELETE_PAGES, false,
                    "• Delete pages you don't need\n• Tap pages to select, then delete") { deletePagesPdf() }
            ),
            listOf(
                Tool("Watermark", null, PdfIconView.Kind.WATERMARK, false,
                    "• Stamp your own text on every page\n• E.g. CONFIDENTIAL") { watermarkPdf() },
                Tool("Extract Images", null, PdfIconView.Kind.IMG_EXTRACT, false,
                    "• Save every photo inside your PDF\n• Each image saved on its own") { extractImagesPdf() },
                Tool("Resize Pages", null, PdfIconView.Kind.RESIZE, false,
                    "• Change every page to a new size\n• A4, Letter, A3, A5, Legal and more") { resizePdf() }
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
                    // v0.1.174: PDF <-> Image UI colours interchanged
                    // (user 2026-11-24) — PDF takes Image's blue.
                    // Icons untouched.
                    setColor(0xFFF5F8FF.toInt())
                    setStroke((2 * density).toInt(), Color.parseColor("#1E88E5"))
                } else {
                    // v0.1.152, Fix #11: grid cards get the same
                    // excellent outline as the featured cards —
                    // clean white + 2dp blue border, professional.
                    setColor(Color.WHITE)
                    setStroke((2 * density).toInt(), Color.parseColor("#1E88E5"))
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
        val name = AppCompatTextView(this).apply {
            text = tool.gridName ?: tool.name
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            // v0.1.155, Fix #40: the label must fit on ONE single
            // line, never clipped — auto-size shrinks it (14sp down
            // to 11sp) only when the card is too narrow, so it
            // stays 14sp on normal phones and never wraps or cuts.
            maxLines = 1
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 11, 14, 1, TypedValue.COMPLEX_UNIT_SP
            )
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
        // v0.1.153, Fix #21: the popup card used to stretch to the
        // screen edges (window MATCH_PARENT + card MATCH_PARENT),
        // which made the content look cramped and the alignment
        // feel off. The card now sits in an outer wrapper with
        // 20dp side margins, like a real dialog.
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * resources.displayMetrics.density
                setColor(Color.WHITE)
            }
            // v0.1.152, Fix #12: roomier padding for a premium feel.
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // v0.1.152, Fix #12: icon sits in a tinted circle — the
        // plain icon on white looked bare; the circle makes the
        // popup feel finished and professional.
        // v0.1.155, Fix #34: the v0.1.154 gold circle + "★ PREMIUM"
        // badge are REVERTED — the user never asked for them
        // ("Ipdi kekala, previous yae revert panitu"). Every tool
        // keeps the plain teal-tinted circle, exactly as v0.1.153.
        // Do NOT re-add any premium/gold element here.
        val iconWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFF5F8FF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val icon = PdfIconView(this).apply {
            kind = tool.kind
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
        iconWrap.addView(icon)
        card.addView(iconWrap)
        val title = TextView(this).apply {
            text = tool.name
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        card.addView(title)
        val explain = TextView(this).apply {
            text = tool.explain
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            // v0.1.153, Fix #21: the bullet block is WRAP_CONTENT
            // and centred as a unit under the centred title, with
            // its lines left-aligned to each other — the old
            // full-width left-aligned block looked lopsided next
            // to the centred icon + title. Bullets stay short
            // (v0.1.152, Fix #14).
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(12)
            }
        }
        card.addView(explain)
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
        }
        val btnBack = popupPillButton("Back", false) { dialog.dismiss() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnPick = popupPillButton(tool.pickLabel, true) {
            dialog.dismiss()
            tool.action()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(8)
            }
        }
        btnRow.addView(btnBack)
        btnRow.addView(btnPick)
        card.addView(btnRow)
        outer.addView(card)
        dialog.setContentView(outer)
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
        btn.backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_blue))
        btn.setTextColor(Color.WHITE)
        btn.strokeWidth = 0
        btn.cornerRadius = dp(12)
    }

    /** Teal outline button (white fill, teal text) — secondary action. */
    private fun styleOutlineButton(btn: MaterialButton) {
        btn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        btn.setTextColor(getColor(R.color.hakunaa_blue))
        btn.strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_blue))
        btn.strokeWidth = dp(1)
        btn.cornerRadius = dp(12)
    }

    /**
     * View a PDF in the phone's default PDF viewer (v0.1.193).
     * The cached file is opened through FileProvider — it is
     * NEVER published to MediaStore just to preview it (the old
     * viewPdf saved the file to Documents/Hakunaa on every View
     * tap). Only the Save button publishes. If the cache file is
     * gone but a published URI exists for it, that is reused.
     */
    private fun viewPdf(file: File) {
        try {
            val uri = if (file.exists()) {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            } else {
                publishedUris[file]
            }
            if (uri == null) {
                toast("Could not open this PDF")
                return
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(view)
        } catch (_: Throwable) {
            toast("No PDF viewer found")
        }
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
            setTextColor(getColor(R.color.hakunaa_blue))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * density
                setColor(getColor(R.color.hakunaa_surface))
                setStroke((1 * density).toInt(), getColor(R.color.hakunaa_blue))
            }
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(40))
            setOnClickListener { onClick() }
        }
    }

    // ------------------------------------------------------------------
    // Small dialogs for the inputs the old form used to hold.
    // ------------------------------------------------------------------




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
                setColor(getColor(R.color.hakunaa_blue))
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
                    backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_blue))
                } else {
                    setTextColor(getColor(R.color.hakunaa_blue))
                    backgroundTintList = ColorStateList.valueOf(getColor(R.color.hakunaa_surface))
                    strokeColor = ColorStateList.valueOf(getColor(R.color.hakunaa_blue))
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

    private fun publishPdfUri(file: File): Uri? {
        // v0.1.150: reuse a published URI — View + Save of the
        // same file must never create duplicate copies.
        publishedUris[file]?.let { return it }
        val uri = MediaStoreSaver.save(this, file, MediaStoreSaver.Kind.DOCUMENT, file.name)
        if (uri != null) publishedUris[file] = uri
        return uri
    }

    /**
     * v0.1.193: shared PREVIEW/result screen — NO direct save.
     * Every tool action builds its result to a cache file, then
     * shows this screen (first-page preview + pages + size +
     * detail line) and ONLY the Save button publishes to
     * MediaStore. Back just closes the screen (the cached result
     * stays in the cache, it is never saved).
     */
    private fun showPdfResultScreen(title: String, outFile: File, pageCount: Int, detailLine: String = "") {
        val root = newToolOverlay(title)
        root.addView(toolFileLine("${outFile.name} • ${FileUtils.formatSize(outFile.length())}"))
        val preview = toolPreviewBox()
        root.addView(preview)
        loadToolPreview(preview, outFile)
        val infoLine = TextView(this).apply {
            text = if (detailLine.isBlank()) {
                "$pageCount page(s) • ${FileUtils.formatSize(outFile.length())}"
            } else {
                "$pageCount page(s) • ${FileUtils.formatSize(outFile.length())}\n$detailLine"
            }
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        root.addView(infoLine)
        root.addView(toolExplain("Preview looks right? Save it to Documents/Hakunaa."))
        val status = toolStatusLine("")
        root.addView(status)
        var saving = false
        root.addView(toolBottomRow("Save") {
            if (saving) return@toolBottomRow
            saving = true
            status.text = "Saving…"
            Thread {
                try {
                    val savedUri = publishPdfUri(outFile)
                    runOnUiThread {
                        if (savedUri != null) {
                            dismissGenericOverlay()
                            showSavedPdfDialog(outFile, savedUri)
                        } else {
                            saving = false
                            status.text = "Save failed — try again"
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread {
                        saving = false
                        status.text = "Save failed — try again"
                    }
                }
            }.start()
        })
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
            setOnClickListener { attemptLeavePdf { dismissMergeOverlay() } }
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
            setOnClickListener { attemptLeavePdf { dismissMergeOverlay() } }
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
                    setColor(getColor(R.color.hakunaa_blue))
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


    // ------------------------------------------------------------------
    // Shared page-thumbnail grid (v0.1.153, for Organize + Delete
    // Pages): 3 columns of REAL rendered page previews, tap to
    // select (teal border + checkmark). Organize also gets drag
    // reorder (ItemTouchHelper, attached by the screen) and a "+"
    // add-pages card at the end. Thumbnails render lazily, off the
    // UI thread, and are recycled with the overlay.
    // ------------------------------------------------------------------

    private inner class PageGridAdapter(
        private val items: MutableList<PdfTools.PageRef>,
        private val showAdd: Boolean,
        private val onTap: (Int) -> Unit,
        private val onAdd: (() -> Unit)?
    ) : RecyclerView.Adapter<PageGridAdapter.PageHolder>() {

        val selected = mutableSetOf<Int>()
        private val thumbs = mutableMapOf<PdfTools.PageRef, Bitmap>()
        private val loading = mutableSetOf<PdfTools.PageRef>()

        inner class PageHolder(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
            val thumbWrap: FrameLayout = root.findViewWithTag("wrap")!!
            val thumb: ImageView = root.findViewWithTag("thumb")!!
            val check: TextView = root.findViewWithTag("check")!!
            val label: TextView = root.findViewWithTag("label")!!
        }

        override fun getItemViewType(position: Int): Int {
            return if (showAdd && position == items.size) 1 else 0
        }

        override fun getItemCount(): Int = items.size + if (showAdd) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val density = parent.context.resources.displayMetrics.density
            val cell = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(
                        (4 * density).toInt(), (4 * density).toInt(),
                        (4 * density).toInt(), (4 * density).toInt()
                    )
                }
            }
            if (viewType == 1) {
                // "+" add-pages card.
                val add = TextView(parent.context).apply {
                    text = "+"
                    textSize = 36f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.hakunaa_blue))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 12f * density
                        setColor(Color.parseColor("#F5F8FF"))
                        setStroke((1 * density).toInt(), getColor(R.color.hakunaa_blue))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (118 * density).toInt()
                    )
                }
                add.setOnClickListener { onAdd?.invoke() }
                cell.addView(add)
                val label = TextView(parent.context).apply {
                    text = "Add pages"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(getColor(R.color.hakunaa_text_secondary))
                    tag = "label"
                    setPadding(0, (4 * density).toInt(), 0, 0)
                }
                cell.addView(label)
                // Unused views for the holder — hidden.
                val wrap = FrameLayout(parent.context).apply {
                    tag = "wrap"
                    visibility = View.GONE
                }
                val thumb = ImageView(parent.context).apply { tag = "thumb" }
                val check = TextView(parent.context).apply { tag = "check" }
                wrap.addView(thumb)
                wrap.addView(check)
                cell.addView(wrap)
                return PageHolder(cell)
            }
            val wrap = FrameLayout(parent.context).apply {
                tag = "wrap"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (118 * density).toInt()
                )
            }
            val thumb = ImageView(parent.context).apply {
                tag = "thumb"
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    setColor(Color.parseColor("#F1F3F4"))
                }
                clipToOutline = true
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            wrap.addView(thumb)
            val check = TextView(parent.context).apply {
                tag = "check"
                text = "✓"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(R.color.hakunaa_blue))
                }
                layoutParams = FrameLayout.LayoutParams(
                    (26 * density).toInt(), (26 * density).toInt()
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    setMargins(0, (6 * density).toInt(), (6 * density).toInt(), 0)
                }
                visibility = View.GONE
            }
            wrap.addView(check)
            cell.addView(wrap)
            val label = TextView(parent.context).apply {
                tag = "label"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                setPadding(0, (4 * density).toInt(), 0, 0)
            }
            cell.addView(label)
            return PageHolder(cell)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            if (getItemViewType(position) == 1) return
            val item = items[position]
            holder.label.text = "Page ${item.page + 1}"
            val isSel = selected.contains(position)
            holder.check.visibility = if (isSel) View.VISIBLE else View.GONE
            holder.thumbWrap.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * holder.root.resources.displayMetrics.density
                setColor(Color.TRANSPARENT)
                if (isSel) {
                    setStroke(
                        (2 * holder.root.resources.displayMetrics.density).toInt(),
                        getColor(R.color.hakunaa_blue)
                    )
                }
            }
            holder.thumbWrap.clipToOutline = true
            val bmp = thumbs[item]
            if (bmp != null) {
                holder.thumb.setImageBitmap(bmp)
            } else {
                holder.thumb.setImageBitmap(null)
                loadThumb(item)
            }
            holder.root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION || pos >= items.size) return@setOnClickListener
                onTap(pos)
            }
        }

        private fun loadThumb(item: PdfTools.PageRef) {
            if (thumbs.containsKey(item) || loading.contains(item)) return
            loading.add(item)
            Thread {
                try {
                    var bmp = PdfTools.renderPageThumb(item.file, item.page, 240)
                    if (bmp != null && item.rotation != 0) {
                        val m = Matrix()
                        m.postRotate(item.rotation.toFloat())
                        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                        bmp.recycle()
                        bmp = rotated
                    }
                    val finalBmp = bmp
                    runOnUiThread {
                        loading.remove(item)
                        if (finalBmp != null) {
                            thumbs[item] = finalBmp
                            genericOverlayBitmaps.add(finalBmp)
                            notifyDataSetChanged()
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { loading.remove(item) }
                }
            }.start()
        }
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
            setOnClickListener { attemptLeavePdf { dismissMergeOverlay() } }
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
            setTextColor(getColor(R.color.hakunaa_blue))
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
                    setColor(0xFFF5F8FF.toInt())
                    setStroke((2 * density).toInt(), getColor(R.color.hakunaa_blue))
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
            setOnClickListener { attemptLeavePdf { dismissSplitOverlay() } }
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

        // v0.1.152, Fix #13: TOP PREVIEW (first page) like Smart —
        // the user asked for a preview at the top of Split too.
        ivSplitPreview = ImageView(this).apply {
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
        root.addView(ivSplitPreview)
        splitPreviewBitmap?.recycle()
        splitPreviewBitmap = null
        Thread {
            try {
                val thumb = PdfTools.renderFirstPageThumb(input, 480)
                runOnUiThread {
                    if (thumb != null && splitOverlay != null) {
                        splitPreviewBitmap = thumb
                        ivSplitPreview?.setImageBitmap(thumb)
                    }
                }
            } catch (_: Throwable) { }
        }.start()

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
                ).apply { topMargin = dp(10) }
                // v0.1.152, Fix #13: roomier cards — the old
                // 12dp padding felt cramped next to the preview.
                setPadding(dp(16), dp(14), dp(16), dp(14))
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
                setTextColor(getColor(R.color.hakunaa_blue))
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
            setOnClickListener { attemptLeavePdf { dismissSplitOverlay() } }
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
                    setColor(0xFFF5F8FF.toInt())
                    setStroke((2 * density).toInt(), getColor(R.color.hakunaa_blue))
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
     * and a View button, plus Save all + Back + Done.
     * v0.1.193: tap a row to select it (checkmark + blue border,
     * multiple select) — "Save Selected" saves only the ticked
     * files, "Save all" saves every file. View opens through
     * FileProvider and never saves. */
    private fun showSplitResults(files: List<File>) {
        val root = splitOverlay as? LinearLayout ?: return
        root.removeAllViews()
        splitResultFiles = files.toMutableList()
        splitResultSelected = mutableSetOf()

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
            setOnClickListener { attemptLeavePdf { dismissSplitOverlay() } }
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
        for ((i, file) in files.withIndex()) {
            listBox.addView(buildSplitResultRow(file, i))
        }
        scroll.addView(listBox)
        root.addView(scroll)

        val btnSaveSelected = MaterialButton(this).apply {
            text = "Save Selected"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(12) }
            setOnClickListener { saveSplitSelected() }
        }
        styleFilledButton(btnSaveSelected)
        root.addView(btnSaveSelected)

        val btnSaveAll = MaterialButton(this).apply {
            text = "Save all"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(8) }
            setOnClickListener { saveSplitAll() }
        }
        styleOutlineButton(btnSaveAll)
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
                else attemptLeavePdf { dismissSplitOverlay() }
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

    /** v0.1.193: paint a Split result row for its selection state
     * (blue border + check badge when selected). The check badge
     * is child 2 of the row (left=0, View pill=1). */
    private fun paintSplitResultRow(row: LinearLayout, selected: Boolean) {
        val density = resources.displayMetrics.density
        row.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            if (selected) {
                setColor(0xFFF5F8FF.toInt())
                setStroke((2 * density).toInt(), getColor(R.color.hakunaa_blue))
            } else {
                setColor(Color.WHITE)
                setStroke((1 * density).toInt(), Color.parseColor("#CFD8DC"))
            }
        }
        (row.getChildAt(2) as? TextView)?.visibility =
            if (selected) View.VISIBLE else View.GONE
    }

    private fun buildSplitResultRow(file: File, index: Int): LinearLayout {
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
        }
        row.setOnClickListener {
            if (splitResultSelected.contains(index)) splitResultSelected.remove(index)
            else splitResultSelected.add(index)
            paintSplitResultRow(row, splitResultSelected.contains(index))
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
        val check = TextView(this).apply {
            text = "✓"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(getColor(R.color.hakunaa_blue))
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(8) }
        }
        row.addView(check)
        return row
    }

    /** v0.1.193: save only the ticked Split results. */
    private fun saveSplitSelected() {
        if (splitResultSelected.isEmpty()) {
            toast("Tap a file to select it first")
            return
        }
        val chosen = splitResultSelected.sorted().mapNotNull { splitResultFiles.getOrNull(it) }
        if (chosen.isEmpty()) {
            toast("Tap a file to select it first")
            return
        }
        Thread {
            try {
                var saved = 0
                for (file in chosen) {
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
        ivSplitPreview = null
        splitPreviewBitmap?.recycle()
        splitPreviewBitmap = null
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
            setOnClickListener { attemptLeavePdf { dismissLosslessOverlay() } }
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
            setOnClickListener { attemptLeavePdf { dismissLosslessOverlay() } }
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
                            // v0.1.154, Fix #30: explain WHY nothing
                            // could be saved. compressLossless found
                            // no unfiltered streams to pack and the
                            // metadata strip saved nothing — this PDF
                            // really is already fully compressed, so
                            // the result is honest, not a shrug.
                            // Fix #31: one tap takes the same file
                            // straight to Smart Compress.
                            val alreadyBox = LinearLayout(this).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                            }
                            val tvTitle = TextView(this).apply {
                                text = "Already optimized"
                                textSize = 17f
                                setTypeface(null, android.graphics.Typeface.BOLD)
                                setTextColor(getColor(R.color.hakunaa_text))
                                gravity = Gravity.CENTER
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                            }
                            alreadyBox.addView(tvTitle)
                            val tvWhy = TextView(this).apply {
                                text = "Lossless checked this PDF and found nothing left to squeeze:"
                                textSize = 13f
                                setTextColor(getColor(R.color.hakunaa_text_secondary))
                                gravity = Gravity.CENTER
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { topMargin = dp(10) }
                            }
                            alreadyBox.addView(tvWhy)
                            val tvBullets = TextView(this).apply {
                                text = "• Images already compressed\n• Content streams already packed\n• Metadata already minimal"
                                textSize = 13f
                                setTextColor(getColor(R.color.hakunaa_text))
                                gravity = Gravity.START
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply {
                                    gravity = Gravity.CENTER_HORIZONTAL
                                    topMargin = dp(8)
                                }
                            }
                            alreadyBox.addView(tvBullets)
                            val btnTrySmart = MaterialButton(this).apply {
                                text = "Try with Smart Compress"
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
                                ).apply { topMargin = dp(20) }
                                setOnClickListener {
                                    dismissLosslessOverlay()
                                    showSmartResults(input)
                                }
                            }
                            styleFilledButton(btnTrySmart)
                            alreadyBox.addView(btnTrySmart)
                            resultBox.addView(alreadyBox)
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
                setColor(0xFFF5F8FF.toInt())
                setStroke(dp(1), getColor(R.color.hakunaa_blue))
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
            Thread {
                val info = PdfTools.pageInfo(input, 0)
                runOnUiThread {
                    if (info == null) toast("Could not open this PDF")
                    else showRotateScreen(input, info.first)
                }
            }.start()
        }
    }

    // Rotate PDF — in-screen (v0.1.153, priority #1): the old flow
    // was a plain "Rotate by" choice dialog over the menu.
    private fun showRotateScreen(input: File, total: Int) {
        var degrees = 90
        var scope = 0 // 0 all pages, 1 single page
        var pageBitmap: Bitmap? = null
        var rotatedBitmap: Bitmap? = null
        var renderedPage = -1
        val root = newToolOverlay("Rotate PDF")
        root.addView(toolFileLine("${input.name} • $total page(s)"))
        val preview = toolPreviewBox()
        root.addView(preview)
        // v0.1.193: LIVE preview — the page bitmap rotated by the
        // chosen degrees, so the user sees exactly what the result
        // will look like (the old preview never rotated).
        fun refreshRotatePreview() {
            val src = pageBitmap ?: return
            rotatedBitmap?.let {
                genericOverlayBitmaps.remove(it)
                try { it.recycle() } catch (_: Throwable) { }
            }
            rotatedBitmap = null
            val m = Matrix()
            m.postRotate(degrees.toFloat())
            val rb = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            rotatedBitmap = rb
            genericOverlayBitmaps.add(rb)
            preview.setImageBitmap(rb)
        }
        fun renderRotatePage(index: Int) {
            if (index == renderedPage) return
            renderedPage = index
            Thread {
                try {
                    val bmp = PdfTools.renderPageThumb(input, index, 720)
                    runOnUiThread {
                        if (bmp != null && genericOverlay != null) {
                            pageBitmap?.let {
                                genericOverlayBitmaps.remove(it)
                                try { it.recycle() } catch (_: Throwable) { }
                            }
                            pageBitmap = bmp
                            genericOverlayBitmaps.add(bmp)
                            refreshRotatePreview()
                        }
                    }
                } catch (_: Throwable) { }
            }.start()
        }
        renderRotatePage(0)
        root.addView(toolExplain("Rotate all pages, or just one page. The content stays exactly the same — only the rotation changes."))
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll)
        // Page picker views are declared up front so the scope
        // chips below can show/hide them.
        var pageBox: LinearLayout? = null
        var etPage: EditText? = null
        body.addView(toolSectionLabel("Pages"))
        body.addView(optionChipRow(listOf("All pages", "Single page"), 0) {
            scope = it
            pageBox?.visibility = if (scope == 1) View.VISIBLE else View.GONE
            if (scope == 1) {
                val p = etPage?.text?.toString()?.trim()?.toIntOrNull() ?: 1
                renderRotatePage((p - 1).coerceIn(0, total - 1))
            } else {
                renderRotatePage(0)
            }
        })
        pageBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        etPage = EditText(this).apply {
            hint = "Page number (1–$total)"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            background = inputBoxBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        etPage?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (scope != 1) return
                val p = s.toString().trim().toIntOrNull() ?: return
                if (p in 1..total) renderRotatePage(p - 1)
            }
        })
        pageBox?.addView(etPage)
        body.addView(pageBox)
        body.addView(toolSectionLabel("Rotate by"))
        body.addView(optionChipRow(listOf("90°", "180°", "270°"), 0) {
            degrees = listOf(90, 180, 270)[it]
            refreshRotatePreview()
        })
        val status = toolStatusLine("")
        body.addView(status)
        root.addView(toolBottomRow("Rotate") {
            val pageOneBased: Int? = if (scope == 0) null else {
                val p = etPage?.text?.toString()?.trim()?.toIntOrNull()
                if (p == null || p < 1 || p > total) {
                    status.text = "Type a page number from 1 to $total"
                    return@toolBottomRow
                }
                p
            }
            status.text = "Rotating PDF by $degrees°…"
            Thread {
                try {
                    val out = File(hakunaaCacheDir(), "hakunaa_rotated_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.rotate(input, out, degrees, pageOneBased)
                    runOnUiThread {
                        if (ok) {
                            // v0.1.193: NO direct save — preview /
                            // result screen first; only its Save
                            // button publishes.
                            val detail = if (pageOneBased == null) "Rotated $degrees° • All pages"
                                else "Rotated $degrees° • Page $pageOneBased"
                            showPdfResultScreen("Rotate PDF", out, total, detail)
                        } else {
                            status.text = "Rotate failed — check the file and try again"
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "Rotate failed — check the file and try again" }
                }
            }.start()
        })
    }


    private fun watermarkPdf() {
        pickPdfThen { input ->
            Thread {
                val info = PdfTools.pageInfo(input, 0)
                runOnUiThread {
                    if (info == null) toast("Could not open this PDF")
                    else showWatermarkScreen(input, info.first)
                }
            }.start()
        }
    }

    // Watermark — in-screen with professional options (v0.1.153,
    // Fix #28). The old flow was a plain "Watermark text" dialog
    // over the menu, stamping diagonally at a fixed size/colour.
    // Now: preview with a LIVE watermark overlay, text input,
    // position, opacity slider, size, colour, rotation and pages.
    private fun showWatermarkScreen(input: File, total: Int) {
        var position = 0 // 0 diagonal centre, 1 centre, 2 top, 3 bottom
        var opacity = 0.35f
        var fontSize = 48f
        var colorRgb = 0xFFB4B4B4.toInt()
        var rotation = 45f
        val root = newToolOverlay("Watermark")
        root.addView(toolFileLine("${input.name} • $total page(s)"))
        // Live preview: the page thumbnail with the watermark text
        // drawn over it, exactly as the options say.
        val stage = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(170)
            ).apply { topMargin = dp(12) }
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.parseColor("#F1F3F4"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        stage.addView(preview)
        val wmOverlay = TextView(this).apply {
            text = "CONFIDENTIAL"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(colorRgb)
            // v0.1.193: the PREVIEW overlay is always clearly
            // visible (0.85) — the actual stamp uses the user's
            // chosen opacity (default 35%).
            alpha = 0.85f
            rotation = rotation
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        stage.addView(wmOverlay)
        root.addView(stage)
        loadToolPreview(preview, input)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll)
        body.addView(toolSectionLabel("Watermark text"))
        val etWmText = EditText(this).apply {
            hint = "e.g. CONFIDENTIAL"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            background = inputBoxBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // Live-preview refresh (declared before the watcher that
        // calls it; etWmText is already declared above).
        fun refreshOverlay() {
            wmOverlay.text = if (etWmText.text.toString().isBlank()) "CONFIDENTIAL" else etWmText.text.toString()
            wmOverlay.textSize = (fontSize * 0.42f).coerceIn(10f, 30f)
            wmOverlay.setTextColor(colorRgb)
            // v0.1.193: preview stays clearly visible (0.85) —
            // the stamp itself uses the chosen opacity.
            wmOverlay.alpha = 0.85f
            wmOverlay.rotation = rotation
            val params = wmOverlay.layoutParams as FrameLayout.LayoutParams
            params.gravity = when (position) {
                0, 1 -> Gravity.CENTER
                2 -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                else -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            params.setMargins(dp(8), dp(8), dp(8), dp(8))
            wmOverlay.layoutParams = params
        }
        etWmText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { refreshOverlay() }
        })
        body.addView(etWmText)
        body.addView(toolSectionLabel("Position"))
        body.addView(optionChipRow(listOf("Diagonal", "Centre", "Top", "Bottom"), 0) {
            position = it
            rotation = if (it == 0) 45f else 0f
            refreshOverlay()
        })
        body.addView(toolSectionLabel("Opacity"))
        val tvOpacity = TextView(this).apply {
            text = "35%"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        body.addView(tvOpacity)
        val seekOpacity = SeekBar(this).apply {
            max = 100
            progress = 35
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                opacity = (progress.coerceIn(5, 100)) / 100f
                tvOpacity.text = "${progress.coerceIn(5, 100)}%"
                refreshOverlay()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        body.addView(seekOpacity)
        body.addView(toolSectionLabel("Font size"))
        val wmSizes = listOf(24f, 36f, 48f, 72f)
        body.addView(optionChipRow(listOf("24", "36", "48", "72"), 2) {
            fontSize = wmSizes[it]
            refreshOverlay()
        })
        body.addView(toolSectionLabel("Colour"))
        // v0.1.193: colours are ARGB — the old bare RGB values
        // had alpha 0x00, so the preview overlay text was fully
        // transparent (invisible). The stamp extracts r/g/b, so
        // the actual watermark colour is unchanged.
        val wmColors = listOf(
            0xFFB4B4B4.toInt(), 0xFF111827.toInt(), 0xFF00BFA5.toInt(),
            0xFFE85A46.toInt(), 0xFF508CDC.toInt()
        )
        body.addView(optionChipRow(listOf("Grey", "Black", "Teal", "Red", "Blue"), 0) {
            colorRgb = wmColors[it]
            refreshOverlay()
        })
        body.addView(toolSectionLabel("Rotation"))
        val wmRots = listOf(0f, 45f, -45f, 90f)
        body.addView(optionChipRow(listOf("0°", "45°", "-45°", "90°"), 1) {
            rotation = wmRots[it]
            refreshOverlay()
        })
        body.addView(toolSectionLabel("Pages"))
        val wmRangeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etWmFrom = EditText(this).apply {
            hint = "From (1)"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            background = inputBoxBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val etWmTo = EditText(this).apply {
            hint = "To ($total)"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            background = inputBoxBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        wmRangeRow.addView(etWmFrom)
        wmRangeRow.addView(etWmTo)
        body.addView(wmRangeRow)
        val status = toolStatusLine("")
        body.addView(status)
        root.addView(toolBottomRow("Apply") {
            val text = etWmText.text.toString().trim()
            if (text.isBlank()) {
                status.text = "Type a watermark text first"
                return@toolBottomRow
            }
            val from = etWmFrom.text.toString().trim().toIntOrNull() ?: 1
            val to = etWmTo.text.toString().trim().toIntOrNull() ?: total
            if (from < 1 || to > total || from > to) {
                status.text = "Check the page range"
                return@toolBottomRow
            }
            val opts = PdfTools.WatermarkOptions(
                position = position,
                opacity = opacity,
                fontSizePt = fontSize,
                colorRgb = colorRgb,
                rotationDeg = rotation,
                fromPage = from,
                toPage = to
            )
            status.text = "Adding watermark…"
            Thread {
                try {
                    val out = File(hakunaaCacheDir(), "hakunaa_watermarked_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.watermark(input, out, text, opts)
                    runOnUiThread {
                        if (ok) {
                            // v0.1.193: NO direct save — preview /
                            // result screen first; only its Save
                            // button publishes.
                            val stamped = to - from + 1
                            showPdfResultScreen(
                                "Watermark", out, total,
                                "Watermark on $stamped page(s)"
                            )
                        } else {
                            status.text = "Watermark failed — check the file and try again"
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "Watermark failed — check the file and try again" }
                }
            }.start()
        })
        refreshOverlay()
    }


    private fun pageNumbersPdf() {
        pickPdfThen { input ->
            Thread {
                val info = PdfTools.pageInfo(input, 0)
                runOnUiThread {
                    if (info == null) toast("Could not open this PDF")
                    else showPageNumbersScreen(input, info.first)
                }
            }.start()
        }
    }

    // Page Numbers — in-screen with professional options (v0.1.153,
    // Fix #24). The old tool had NO screen at all (pick -> toast ->
    // save) and stamped from the mediaBox ignoring cropBox/rotation,
    // so on the user's PDFs the numbers landed outside the visible
    // area ("suthama work aagala"). PdfTools.pageNumbers is rewritten
    // to place from the cropBox, rotation-aware, with output
    // verification; this screen adds position, format, size, colour,
    // cover skip, start number and page range, plus a live preview
    // of how the number will look.
    private fun showPageNumbersScreen(input: File, total: Int) {
        var format = 2 // 0 "1", 1 "Page 1", 2 "Page 1 of 10"
        var vPos = 1 // 0 top, 1 bottom
        var hPos = 1 // 0 left, 1 centre, 2 right
        var fontSize = 12f
        // v0.1.193: 0xFF alpha — the old 0x111827 made the
        // mini-preview sample transparent (see Colour list below).
        var colorRgb = 0xFF111827.toInt()
        var skipCover = false
        val root = newToolOverlay("Page Numbers")
        root.addView(toolFileLine("${input.name} • $total page(s)"))
        val preview = toolPreviewBox()
        root.addView(preview)
        loadToolPreview(preview, input)
        root.addView(toolExplain("Add page numbers to every page. See below how the number will look, then apply."))
        // Live mini-preview: a small page-shaped box with the sample
        // number placed exactly where the option says.
        val miniPreview = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.parseColor("#F1F3F4"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64)
            ).apply { topMargin = dp(12) }
        }
        val sample = TextView(this).apply {
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        miniPreview.addView(sample)
        root.addView(miniPreview)
        fun refreshSample() {
            sample.text = when (format) {
                0 -> "1"
                1 -> "Page 1"
                else -> "Page 1 of $total"
            }
            sample.textSize = fontSize
            sample.setTextColor(colorRgb)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = when (vPos * 3 + hPos) {
                    0 -> Gravity.TOP or Gravity.START
                    1 -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    2 -> Gravity.TOP or Gravity.END
                    3 -> Gravity.BOTTOM or Gravity.START
                    4 -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    else -> Gravity.BOTTOM or Gravity.END
                }
                setMargins(dp(14), dp(10), dp(14), dp(10))
            }
            sample.layoutParams = params
        }
        refreshSample()
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll)
        body.addView(toolSectionLabel("Format"))
        body.addView(optionChipRow(listOf("1", "Page 1", "Page 1 of 10"), 2) {
            format = it
            refreshSample()
        })
        body.addView(toolSectionLabel("Position — top / bottom"))
        body.addView(optionChipRow(listOf("Top", "Bottom"), 1) {
            vPos = it
            refreshSample()
        })
        body.addView(toolSectionLabel("Position — left / centre / right"))
        body.addView(optionChipRow(listOf("Left", "Centre", "Right"), 1) {
            hPos = it
            refreshSample()
        })
        body.addView(toolSectionLabel("Font size"))
        val sizes = listOf(10f, 12f, 14f, 18f)
        body.addView(optionChipRow(listOf("10", "12", "14", "18"), 1) {
            fontSize = sizes[it]
            refreshSample()
        })
        body.addView(toolSectionLabel("Colour"))
        // v0.1.193: colours MUST carry the 0xFF alpha byte — the
        // old bare RGB values (0x111827, ...) are alpha 0x00, so
        // setTextColor made the live mini-preview sample fully
        // transparent (invisible). The stamp extracts r/g/b, so
        // the alpha byte does not change the stamped colour.
        val colors = listOf(0xFF111827.toInt(), 0xFF5B6472.toInt(), 0xFF00BFA5.toInt(), 0xFFE85A46.toInt(), 0xFF508CDC.toInt())
        body.addView(optionChipRow(listOf("Black", "Grey", "Teal", "Red", "Blue"), 0) {
            colorRgb = colors[it]
            refreshSample()
        })
        body.addView(toolSectionLabel("Cover page"))
        val skipChip = resizeChip("Skip first page (cover)", false) { }
        skipChip.setOnClickListener {
            skipCover = !skipCover
            paintChip(skipChip, skipCover)
        }
        body.addView(toolChipRow(listOf(skipChip)))
        body.addView(toolSectionLabel("Start number"))
        val etStart = EditText(this).apply {
            hint = "1"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            background = inputBoxBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        body.addView(etStart)
        body.addView(toolSectionLabel("Page range"))
        val rangeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etFrom = EditText(this).apply {
            hint = "From (1)"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            background = inputBoxBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val etTo = EditText(this).apply {
            hint = "To ($total)"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_NUMBER
            background = inputBoxBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        rangeRow.addView(etFrom)
        rangeRow.addView(etTo)
        body.addView(rangeRow)
        val status = toolStatusLine("")
        body.addView(status)
        root.addView(toolBottomRow("Apply") {
            val start = etStart.text.toString().trim().toIntOrNull() ?: 1
            val from = etFrom.text.toString().trim().toIntOrNull() ?: 1
            val to = etTo.text.toString().trim().toIntOrNull() ?: total
            if (start < 0 || from < 1 || to > total || from > to) {
                status.text = "Check the start number and page range"
                return@toolBottomRow
            }
            val opts = PdfTools.PageNumberOptions(
                format = format,
                position = vPos * 3 + hPos,
                fontSizePt = fontSize,
                colorRgb = colorRgb,
                skipCover = skipCover,
                startNumber = start,
                fromPage = from,
                toPage = to
            )
            status.text = "Adding page numbers…"
            Thread {
                try {
                    val out = File(hakunaaCacheDir(), "hakunaa_numbered_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.pageNumbers(input, out, opts)
                    val numbered = (from..to).count { !(skipCover && it == 1) }
                    runOnUiThread {
                        if (ok) {
                            // v0.1.193: NO direct save — preview /
                            // result screen first; only its Save
                            // button publishes.
                            showPdfResultScreen(
                                "Page Numbers", out, total,
                                "Numbers added on $numbered page(s)"
                            )
                        } else {
                            status.text = "Page numbers failed — check the file and try again"
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "Page numbers failed — check the file and try again" }
                }
            }.start()
        })
    }


    private fun extractImagesPdf() {
        pickPdfThen { input ->
            Thread {
                val info = PdfTools.pageInfo(input, 0)
                runOnUiThread {
                    if (info == null) toast("Could not open this PDF")
                    else showExtractImagesScreen(input, info.first)
                }
            }.start()
        }
    }

    // Extract Images — in-screen (v0.1.153, priority #1): the old
    // flow did everything with just a toast, no screen at all.
    // Extract Images — REWORKED (v0.1.156, Fix #45): the v0.1.153
    // flow saved ALL images straight to Pictures on tap ("Done —
    // 13 image(s) saved"). Now the screen extracts on open, shows
    // every image as a thumbnail in a grid, the user ticks the
    // ones they want, and only the selected ones are saved —
    // same selection pattern as Image to PDF (Fix #20). After
    // save, the professional Saved dialog (not a toast).
    private fun showExtractImagesScreen(input: File, total: Int) {
        val root = newToolOverlay("Extract Images")
        root.addView(toolFileLine("${input.name} • $total page(s)"))
        val preview = toolPreviewBox()
        root.addView(preview)
        loadToolPreview(preview, input)
        root.addView(toolExplain("Tick the images you want, then save only those to Pictures/Hakunaa."))
        val status = toolStatusLine("Extracting images…")
        root.addView(status)
        data class ExtractedImage(val file: File, val bitmap: Bitmap, var selected: Boolean = true)
        val extracted = mutableListOf<ExtractedImage>()
        // Save button, declared up front so its label can follow
        // the selection count ("Save 13 images" → "Save 5 images").
        var btnSave: TextView? = null
        fun updateSaveButton() {
            val n = extracted.count { it.selected }
            btnSave?.text = if (extracted.isEmpty()) "Save images" else "Save $n image${if (n == 1) "" else "s"}"
        }
        // Grid adapter (same tap-to-toggle + check-badge pattern as
        // the Organize / Delete Pages thumbnail grids).
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = extracted.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val cell = FrameLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, dp(120)
                    ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                }
                val iv = ImageView(parent.context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                val badge = TextView(parent.context).apply {
                    text = "✓"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(parent.context.getColor(R.color.hakunaa_blue))
                    }
                    layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                        gravity = Gravity.END or Gravity.TOP
                        setMargins(0, dp(6), dp(6), 0)
                    }
                }
                cell.addView(iv)
                cell.addView(badge)
                return object : RecyclerView.ViewHolder(cell) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = extracted[position]
                val cell = holder.itemView as FrameLayout
                val iv = cell.getChildAt(0) as ImageView
                val badge = cell.getChildAt(1) as TextView
                iv.setImageBitmap(item.bitmap)
                iv.alpha = if (item.selected) 1f else 0.45f
                badge.visibility = if (item.selected) View.VISIBLE else View.GONE
                cell.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    if (item.selected) setStroke(dp(2), cell.context.getColor(R.color.hakunaa_blue))
                    else setStroke(dp(1), Color.parseColor("#CFD8DC"))
                }
                cell.setOnClickListener {
                    item.selected = !item.selected
                    notifyItemChanged(position)
                    updateSaveButton()
                }
            }
        }
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@PdfToolsActivity, 3)
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
        }
        grid.adapter = adapter
        root.addView(grid)
        // Bottom row: Back (with the leave dialog) + Save selected.
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val btnBack = popupPillButton("Back", false) { attemptLeavePdf { dismissGenericOverlay() } }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginEnd = dp(8) }
        }
        btnSave = popupPillButton("Save images", true) {
            val chosen = extracted.filter { it.selected }
            if (chosen.isEmpty()) {
                toast("Tick at least one image to save")
                return@popupPillButton
            }
            status.text = "Saving ${chosen.size} image(s)…"
            Thread {
                try {
                    var firstFile: File? = null
                    var firstUri: Uri? = null
                    var published = 0
                    for (item in chosen) {
                        val uri = MediaStoreSaver.save(this, item.file, MediaStoreSaver.Kind.IMAGE, item.file.name, ImageCompressor.formatOf(item.file).mime)
                        if (uri != null) {
                            if (firstUri == null) {
                                firstUri = uri
                                firstFile = item.file
                            }
                            published++
                        }
                    }
                    runOnUiThread {
                        if (published > 0 && firstUri != null && firstFile != null) {
                            dismissGenericOverlay()
                            SavedDialog.show(this@PdfToolsActivity, firstFile, firstUri, ImageCompressor.formatOf(firstFile), accentRes = R.color.hakunaa_blue)
                        } else {
                            status.text = "Save failed — check the file and try again"
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "Save failed — check the file and try again" }
                }
            }.start()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) }
        }
        bottomRow.addView(btnBack)
        bottomRow.addView(btnSave)
        root.addView(bottomRow)
        // Extract on open, then fill the grid (all selected).
        Thread {
            try {
                val outDir = File(hakunaaCacheDir(), "images_${System.currentTimeMillis()}").apply { mkdirs() }
                val created = PdfTools.extractImages(input, outDir)
                val items = mutableListOf<ExtractedImage>()
                for (file in created) {
                    try {
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                        val smaller = if (bounds.outWidth < bounds.outHeight) bounds.outWidth else bounds.outHeight
                        val sample = (smaller / 240).coerceAtLeast(1)
                        val bmpOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, bmpOpts)
                        if (bmp != null) {
                            genericOverlayBitmaps.add(bmp)
                            items.add(ExtractedImage(file, bmp))
                        }
                    } catch (_: Throwable) { }
                }
                runOnUiThread {
                    extracted.addAll(items)
                    adapter.notifyDataSetChanged()
                    updateSaveButton()
                    status.text = when {
                        extracted.isEmpty() -> "No images found in this PDF"
                        else -> "${extracted.size} image(s) found — tick the ones to save"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Extract failed — check the file and try again" }
            }
        }.start()
    }


    private fun pdfToJpg() {
        pickPdfThen { input ->
            Thread {
                val info = PdfTools.pageInfo(input, 0)
                runOnUiThread {
                    if (info == null) toast("Could not open this PDF")
                    else showPdfToJpgScreen(input, info.first)
                }
            }.start()
        }
    }

    // PDF to JPG — in-screen (v0.1.153, priority #1): the old flow
    // did everything with just a toast, no screen at all.
    private fun showPdfToJpgScreen(input: File, total: Int) {
        var dpi = 150f
        val root = newToolOverlay("PDF to JPG")
        root.addView(toolFileLine("${input.name} • $total page(s)"))
        val preview = toolPreviewBox()
        root.addView(preview)
        loadToolPreview(preview, input)
        root.addView(toolExplain("Turn every page into a JPG image. Each page is saved as its own photo in Pictures/Hakunaa."))
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll)
        body.addView(toolSectionLabel("Image quality"))
        body.addView(optionChipRow(listOf("150 DPI", "300 DPI"), 0) {
            dpi = if (it == 0) 150f else 300f
        })
        val status = toolStatusLine("")
        body.addView(status)
        root.addView(toolBottomRow("Convert") {
            status.text = "Converting $total page(s) to JPG…"
            Thread {
                try {
                    val outDir = File(hakunaaCacheDir(), "jpg_${System.currentTimeMillis()}").apply { mkdirs() }
                    val created = PdfTools.pdfToJpg(input, outDir, dpi)
                    runOnUiThread {
                        if (created.isEmpty()) {
                            status.text = "PDF to JPG failed — check the file and try again"
                        } else {
                            // v0.1.193: NO direct save — show every
                            // converted page in a grid; the user
                            // ticks the ones to save.
                            showPdfToJpgResults(input, total, created)
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "PDF to JPG failed — check the file and try again" }
                }
            }.start()
        })
    }

    // PDF to JPG results (v0.1.193): every converted page as a
    // preview box in a grid (Extract Images pattern) — tap to
    // select, multiple select, "Save Selected" saves only the
    // ticked ones, "Save all" saves every page. Nothing is saved
    // until a Save button is tapped.
    private fun showPdfToJpgResults(input: File, total: Int, files: List<File>) {
        val root = newToolOverlay("PDF to JPG")
        root.addView(toolFileLine("${input.name} • $total page(s)"))
        root.addView(toolExplain("Tick the images you want, then save only those to Pictures/Hakunaa."))
        val status = toolStatusLine("${files.size} image(s) — all selected")
        root.addView(status)
        data class JpgImage(val file: File, val bitmap: Bitmap, var selected: Boolean = true)
        val images = mutableListOf<JpgImage>()
        var btnSaveSelected: TextView? = null
        fun updateSaveButton() {
            val n = images.count { it.selected }
            btnSaveSelected?.text = if (images.isEmpty()) "Save Selected" else "Save $n image${if (n == 1) "" else "s"}"
        }
        fun saveJpgFiles(chosen: List<JpgImage>) {
            if (chosen.isEmpty()) {
                toast("Tick at least one image to save")
                return
            }
            status.text = "Saving ${chosen.size} image(s)…"
            Thread {
                try {
                    var firstFile: File? = null
                    var firstUri: Uri? = null
                    var published = 0
                    for (item in chosen) {
                        val uri = MediaStoreSaver.save(this, item.file, MediaStoreSaver.Kind.IMAGE, item.file.name, ImageCompressor.formatOf(item.file).mime)
                        if (uri != null) {
                            if (firstUri == null) {
                                firstUri = uri
                                firstFile = item.file
                            }
                            published++
                        }
                    }
                    runOnUiThread {
                        if (published > 0 && firstUri != null && firstFile != null) {
                            dismissGenericOverlay()
                            SavedDialog.show(this@PdfToolsActivity, firstFile, firstUri, ImageCompressor.formatOf(firstFile), accentRes = R.color.hakunaa_blue)
                        } else {
                            status.text = "Save failed — check the file and try again"
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "Save failed — check the file and try again" }
                }
            }.start()
        }
        // Grid (same tap-to-toggle + check-badge pattern as the
        // Extract Images grid).
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = images.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val cell = FrameLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT, dp(120)
                    ).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                }
                val iv = ImageView(parent.context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                val badge = TextView(parent.context).apply {
                    text = "✓"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(parent.context.getColor(R.color.hakunaa_blue))
                    }
                    layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                        gravity = Gravity.END or Gravity.TOP
                        setMargins(0, dp(6), dp(6), 0)
                    }
                }
                cell.addView(iv)
                cell.addView(badge)
                return object : RecyclerView.ViewHolder(cell) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val item = images[position]
                val cell = holder.itemView as FrameLayout
                val iv = cell.getChildAt(0) as ImageView
                val badge = cell.getChildAt(1) as TextView
                iv.setImageBitmap(item.bitmap)
                iv.alpha = if (item.selected) 1f else 0.45f
                badge.visibility = if (item.selected) View.VISIBLE else View.GONE
                cell.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    if (item.selected) setStroke(dp(2), cell.context.getColor(R.color.hakunaa_blue))
                    else setStroke(dp(1), Color.parseColor("#CFD8DC"))
                }
                cell.setOnClickListener {
                    item.selected = !item.selected
                    notifyItemChanged(position)
                    updateSaveButton()
                }
            }
        }
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@PdfToolsActivity, 3)
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
        }
        grid.adapter = adapter
        root.addView(grid)
        btnSaveSelected = popupPillButton("Save Selected", true) {
            saveJpgFiles(images.filter { it.selected })
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(12) }
        }
        root.addView(btnSaveSelected)
        val btnSaveAll = popupPillButton("Save all", false) {
            saveJpgFiles(images.toList())
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(8) }
        }
        root.addView(btnSaveAll)
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val btnBack = popupPillButton("Back", false) {
            showPdfToJpgScreen(input, total)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) }
        }
        val btnDone = popupPillButton("Done", true) { dismissGenericOverlay() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) }
        }
        bottomRow.addView(btnBack)
        bottomRow.addView(btnDone)
        root.addView(bottomRow)
        // Decode the converted pages into thumbnails (sampled).
        Thread {
            try {
                val items = mutableListOf<JpgImage>()
                for (file in files) {
                    try {
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
                        val smaller = if (bounds.outWidth < bounds.outHeight) bounds.outWidth else bounds.outHeight
                        val sample = (smaller / 240).coerceAtLeast(1)
                        val bmpOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, bmpOpts)
                        if (bmp != null) {
                            genericOverlayBitmaps.add(bmp)
                            items.add(JpgImage(file, bmp))
                        }
                    } catch (_: Throwable) { }
                }
                runOnUiThread {
                    images.addAll(items)
                    adapter.notifyDataSetChanged()
                    updateSaveButton()
                    status.text = if (images.isEmpty()) {
                        "No images could be read — try again"
                    } else {
                        "${images.size} image(s) — tick the ones to save"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "PDF to JPG failed — check the file and try again" }
            }
        }.start()
    }



    private fun protectUnlockPdf() {
        pickPdfThen { input ->
            Thread {
                val info = PdfTools.pageInfo(input, 0)
                runOnUiThread {
                    if (info == null) toast("Could not open this PDF")
                    else showProtectUnlockScreen(input, info.first)
                }
            }.start()
        }
    }

    // Protect / Unlock — in-screen (v0.1.153, Fixes #17 + #18).
    // The old flow was TWO plain dialogs over the menu (a "Protect
    // or Unlock?" choice list, then a bare password field). Now the
    // tool screen shows the preview, two professional option cards
    // (Protect / Unlock, each with its icon + what it does), and
    // the password field lives inside the screen.
    private fun showProtectUnlockScreen(input: File, total: Int) {
        var mode = 0 // 0 protect, 1 unlock
        // btnAction is the bottom Save button, created after the
        // option cards — declare it up front.
        var btnAction: TextView? = null
        val root = newToolOverlay("Protect / Unlock")
        root.addView(toolFileLine("${input.name} • $total page(s)"))
        val preview = toolPreviewBox()
        root.addView(preview)
        loadToolPreview(preview, input)
        root.addView(toolExplain("Lock your PDF with a password, or remove the password from one you already locked."))
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll)

        // v0.1.193: Instructions + specifications box, BELOW the
        // Lock/Unlock cards — it updates with the selected mode.
        // Created up front (before optionCard) so the card click
        // listener can refresh it.
        val infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0xFFF5F8FF.toInt())
                setStroke(dp(1), getColor(R.color.hakunaa_blue))
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val infoTitle = TextView(this).apply {
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        val infoLines = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            setPadding(0, dp(4), 0, 0)
        }
        infoBox.addView(infoTitle)
        infoBox.addView(infoLines)
        fun refreshInfoBox() {
            if (mode == 0) {
                infoTitle.text = "Lock — Instructions"
                infoLines.text = "• Sets a password on this PDF.\n" +
                    "• Anyone opening it must type the password.\n" +
                    "• Use at least 4 characters — letters and numbers work best.\n" +
                    "• Keep the password safe — it cannot be recovered."
            } else {
                infoTitle.text = "Unlock — Instructions"
                infoLines.text = "• Removes the password from this PDF.\n" +
                    "• You must know the current password.\n" +
                    "• The unlocked file opens freely, without a password.\n" +
                    "• If the password is wrong, nothing is changed."
            }
        }

        val cards = mutableListOf<LinearLayout>()
        fun paintCards() {
            for (i in cards.indices) {
                val sel = i == mode
                cards[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    if (sel) {
                        setColor(0xFFF5F8FF.toInt())
                        setStroke(dp(2), getColor(R.color.hakunaa_blue))
                    } else {
                        setColor(Color.WHITE)
                        setStroke(dp(1), Color.parseColor("#CFD8DC"))
                    }
                }
            }
        }
        fun optionCard(
            title: String, desc: String, kind: PdfIconView.Kind, index: Int
        ): LinearLayout {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                isClickable = true
                isFocusable = true
            }
            val icon = PdfIconView(this).apply {
                this.kind = kind
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            }
            card.addView(icon)
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = dp(12) }
            }
            col.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
            })
            col.addView(TextView(this).apply {
                text = desc
                textSize = 13f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
            card.addView(col)
            card.setOnClickListener {
                mode = index
                paintCards()
                refreshInfoBox()
            }
            cards.add(card)
            return card
        }
        body.addView(optionCard(
            "Protect PDF",
            "Lock this PDF with a password. Anyone opening it must type the password.",
            PdfIconView.Kind.LOCK, 0
        ))
        body.addView(optionCard(
            "Unlock PDF",
            "Remove the password from a locked PDF. You must know the current password.",
            PdfIconView.Kind.UNLOCK, 1
        ))
        paintCards()
        refreshInfoBox()
        body.addView(infoBox)

        // v0.1.193: professional password field with a Show /
        // Hide toggle (the password is never shown in a popup).
        var pwVisible = false
        val etPassword = EditText(this).apply {
            hint = "Type a password"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = inputBoxBackground()
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }
        val tvShowPw = TextView(this).apply {
            text = "Show"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_blue))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(4), dp(12))
            isClickable = true
            isFocusable = true
        }
        tvShowPw.setOnClickListener {
            pwVisible = !pwVisible
            val selStart = etPassword.selectionStart
            val selEnd = etPassword.selectionEnd
            etPassword.inputType = if (pwVisible) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            tvShowPw.text = if (pwVisible) "Hide" else "Show"
            try {
                etPassword.setSelection(selStart.coerceAtLeast(0), selEnd.coerceAtLeast(0))
            } catch (_: Throwable) { }
        }
        val pwRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        pwRow.addView(etPassword)
        pwRow.addView(tvShowPw)
        body.addView(pwRow)
        val status = toolStatusLine("")
        body.addView(status)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val btnBack = popupPillButton("Back", false) { attemptLeavePdf { dismissGenericOverlay() } }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(8)
            }
        }
        // v0.1.193: bottom row is Back + Save. Save runs the
        // protect/unlock and shows the preview/result screen —
        // it never saves directly.
        btnAction = popupPillButton("Save", true) {
            val password = etPassword.text.toString()
            if (password.isEmpty()) {
                status.text = "Type a password first"
                return@popupPillButton
            }
            if (mode == 0 && password.length < 4) {
                status.text = "Use at least 4 characters for the password"
                return@popupPillButton
            }
            status.text = if (mode == 0) "Protecting PDF…" else "Unlocking PDF…"
            if (mode == 0) runProtect(input, password, total) else runUnlock(input, password, total)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(8)
            }
        }
        bottomRow.addView(btnBack)
        bottomRow.addView(btnAction)
        root.addView(bottomRow)
    }


    private fun runProtect(input: File, password: String, total: Int) {
        toast("Protecting PDF…")
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_protected_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.protect(input, out, password)
                runOnUiThread {
                    // v0.1.193: NO direct save — preview/result
                    // screen first; only its Save button publishes.
                    if (ok) showPdfResultScreen("Protect / Unlock", out, total, "Protected — a password is set")
                    else toast("Protect failed — check the file and try again")
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Protect failed — check the file and try again") }
            }
        }.start()
    }

    private fun runUnlock(input: File, password: String, total: Int) {
        toast("Unlocking PDF…")
        Thread {
            try {
                val out = File(hakunaaCacheDir(), "hakunaa_unlocked_${System.currentTimeMillis()}.pdf")
                val ok = PdfTools.unlock(input, out, password)
                runOnUiThread {
                    // v0.1.193: NO direct save — preview/result
                    // screen first; only its Save button publishes.
                    if (ok) showPdfResultScreen("Protect / Unlock", out, total, "Unlocked — password removed")
                    else toast("Unlock failed — wrong password or file not locked")
                }
            } catch (_: Throwable) {
                runOnUiThread { toast("Unlock failed — wrong password or file not locked") }
            }
        }.start()
    }

    private fun organizePdf() {
        pickPdfThen { input ->
            Thread {
                val info = PdfTools.pageInfo(input, 0)
                runOnUiThread {
                    if (info == null) toast("Could not open this PDF")
                    else showOrganizeScreen(input, info.first)
                }
            }.start()
        }
    }

    // Organize PDF — full rebuild (v0.1.153, Fix #22). The old flow
    // was a plain type-the-order dialog asking the user to TYPE
    // numbers (e.g. 3,1,2) — that dialog is GONE. Like the
    // professional organizer in the user's reference screenshot:
    // an in-screen grid of REAL page thumbnails, tap to select,
    // drag to reorder, delete / duplicate / rotate in the top bar,
    // Done tick to save, + add-pages card.
    private fun showOrganizeScreen(input: File, total: Int) {
        val items = (0 until total).map { PdfTools.PageRef(input, it, 0) }.toMutableList()
        val root = newToolOverlay("Organize PDF")
        val fileLine = toolFileLine("${input.name} • $total page(s)")
        root.addView(fileLine)
        fun refreshFileLine() {
            fileLine.text = "${input.name} • ${items.size} page(s)"
        }
        val status = toolStatusLine("Drag pages to reorder • Tap a page to select it")
        // Action row: Delete / Duplicate / Rotate 90°.
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        lateinit var adapter: PageGridAdapter
        fun refreshActions() {
            // Actions only make sense with a selection — dim them
            // (by lowering alpha) when nothing is selected.
            val hasSel = adapter.selected.isNotEmpty()
            for (i in 0 until actionRow.childCount) {
                actionRow.getChildAt(i).alpha = if (hasSel) 1f else 0.4f
            }
        }
        fun actionPill(text: String, onClick: () -> Unit): TextView {
            return popupPillButton(text, false) {
                if (adapter.selected.isEmpty()) {
                    status.text = "Tap pages to select them first"
                    return@popupPillButton
                }
                onClick()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (actionRow.childCount > 0) marginStart = dp(8)
                }
            }
        }
        actionRow.addView(actionPill("Delete") {
            val sel = adapter.selected.sortedDescending()
            for (pos in sel) items.removeAt(pos)
            adapter.selected.clear()
            adapter.notifyDataSetChanged()
            refreshFileLine()
            refreshActions()
            status.text = "Deleted — ${items.size} page(s) left"
        })
        actionRow.addView(actionPill("Duplicate") {
            val sel = adapter.selected.sorted()
            var offset = 0
            for (pos in sel) {
                val at = pos + 1 + offset
                items.add(at, items[pos + offset])
                offset++
            }
            adapter.selected.clear()
            adapter.notifyDataSetChanged()
            refreshFileLine()
            refreshActions()
            status.text = "Duplicated — ${items.size} page(s)"
        })
        actionRow.addView(actionPill("Rotate 90°") {
            val sel = adapter.selected.sorted()
            for (pos in sel) {
                val old = items[pos]
                items[pos] = old.copy(rotation = (old.rotation + 90) % 360)
            }
            adapter.selected.clear()
            adapter.notifyDataSetChanged()
            refreshActions()
            status.text = "Rotated 90°"
        })
        root.addView(actionRow)

        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@PdfToolsActivity, 3)
            clipToPadding = false
            setPadding(dp(4), dp(8), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        adapter = PageGridAdapter(items, true,
            onTap = { pos ->
                if (adapter.selected.contains(pos)) adapter.selected.remove(pos)
                else adapter.selected.add(pos)
                adapter.notifyItemChanged(pos)
                refreshActions()
            },
            onAdd = {
                pickPdfThen { file ->
                    Thread {
                        val info = PdfTools.pageInfo(file, 0)
                        runOnUiThread {
                            if (info == null) {
                                toast("Could not open that PDF")
                            } else {
                                val start = items.size
                                for (i in 0 until info.first) {
                                    items.add(PdfTools.PageRef(file, i, 0))
                                }
                                adapter.notifyItemRangeInserted(start, info.first)
                                refreshFileLine()
                                status.text = "Added ${info.first} page(s) — ${items.size} total"
                            }
                        }
                    }.start()
                }
            }
        )
        grid.adapter = adapter
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (from >= items.size || to >= items.size) return false
                Collections.swap(items, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.selected.clear()
                adapter.notifyDataSetChanged()
                refreshActions()
            }
        })
        touchHelper.attachToRecyclerView(grid)
        root.addView(grid)
        root.addView(status)
        refreshActions()
        root.addView(toolBottomRow("Done") {
            if (items.isEmpty()) {
                status.text = "Add at least 1 page"
                return@toolBottomRow
            }
            status.text = "Building organized PDF…"
            Thread {
                try {
                    val out = File(hakunaaCacheDir(), "hakunaa_organized_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.rebuildPages(items, out)
                    val pageCount = items.size
                    runOnUiThread {
                        if (ok) {
                            // v0.1.193: NO direct save — show the
                            // preview/result screen first; only its
                            // Save button publishes.
                            showPdfResultScreen("Organize PDF", out, pageCount, "Reordered • $pageCount page(s)")
                        } else {
                            status.text = "Organize failed — check the file and try again"
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "Organize failed — check the file and try again" }
                }
            }.start()
        })
    }


private fun deletePagesPdf() {
        pickPdfThen { input ->
            Thread {
                val info = PdfTools.pageInfo(input, 0)
                runOnUiThread {
                    if (info == null) toast("Could not open this PDF")
                    else showDeletePagesScreen(input, info.first)
                }
            }.start()
        }
    }

    // Delete Pages — NEW in v0.1.153 (Fix #26, replaces Extract
    // Text in the grid): pick a PDF, see every page as a real
    // thumbnail, tap the pages to delete, Delete N pages, save.
    private fun showDeletePagesScreen(input: File, total: Int) {
        val items = (0 until total).map { PdfTools.PageRef(input, it, 0) }.toMutableList()
        val root = newToolOverlay("Delete Pages")
        root.addView(toolFileLine("${input.name} • $total page(s)"))
        root.addView(toolExplain("Tap the pages you want to delete. The rest stay exactly as they are."))
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@PdfToolsActivity, 3)
            clipToPadding = false
            setPadding(dp(4), dp(8), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        lateinit var adapter: PageGridAdapter
        lateinit var btnAction: TextView
        val status = toolStatusLine("No pages selected")
        adapter = PageGridAdapter(items, false,
            onTap = { pos ->
                if (adapter.selected.contains(pos)) adapter.selected.remove(pos)
                else adapter.selected.add(pos)
                adapter.notifyItemChanged(pos)
                val n = adapter.selected.size
                btnAction.text = if (n == 0) "Delete pages" else "Delete $n page${if (n == 1) "" else "s"}"
                status.text = if (n == 0) "No pages selected" else "$n page(s) selected to delete"
            },
            onAdd = null
        )
        grid.adapter = adapter
        root.addView(grid)
        root.addView(status)
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val btnBack = popupPillButton("Back", false) { attemptLeavePdf { dismissGenericOverlay() } }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(8)
            }
        }
        btnAction = popupPillButton("Delete pages", true) {
            val sel = adapter.selected
            if (sel.isEmpty()) {
                status.text = "Tap pages to select them first"
                return@popupPillButton
            }
            if (sel.size >= items.size) {
                status.text = "Keep at least 1 page"
                return@popupPillButton
            }
            status.text = "Deleting ${sel.size} page(s)…"
            Thread {
                try {
                    val out = File(hakunaaCacheDir(), "hakunaa_deleted_${System.currentTimeMillis()}.pdf")
                    val removed = PdfTools.deletePages(input, out, sel.map { it + 1 }.toSet())
                    runOnUiThread {
                        if (removed > 0) {
                            // v0.1.193: NO direct save — preview /
                            // result screen first (preview of the
                            // remaining pages + count + size);
                            // only its Save button publishes.
                            val remaining = total - removed
                            showPdfResultScreen(
                                "Delete Pages", out, remaining,
                                "$removed page(s) deleted • $remaining page(s) left"
                            )
                        } else {
                            status.text = "Delete failed — check the file and try again"
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "Delete failed — check the file and try again" }
                }
            }.start()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(8)
            }
        }
        bottomRow.addView(btnBack)
        bottomRow.addView(btnAction)
        root.addView(bottomRow)
    }

    // ------------------------------------------------------------------
    // Repair PDF (v0.1.148, new). Already per-tool pick.
    // ------------------------------------------------------------------

    private fun repairPdf() {
        pickPdfThen { input ->
            Thread {
                val info = PdfTools.pageInfo(input, 0)
                // A damaged PDF may not open at all — the repair
                // screen still opens so the user can try anyway.
                runOnUiThread { showRepairScreen(input, info?.first ?: 0) }
            }.start()
        }
    }

    // Repair PDF — in-screen (v0.1.153, priority #1): the old flow
    // did everything with just a toast, no screen at all.
    private fun showRepairScreen(input: File, total: Int) {
        val root = newToolOverlay("Repair PDF")
        root.addView(toolFileLine(
            if (total > 0) "${input.name} • $total page(s)" else input.name
        ))
        val preview = toolPreviewBox()
        root.addView(preview)
        loadToolPreview(preview, input)
        root.addView(toolExplain("Fix a PDF that won't open. The file structure is rebuilt and saved as a new copy — your original is never changed."))
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        root.addView(scroll)
        val status = toolStatusLine("")
        body.addView(status)
        root.addView(toolBottomRow("Repair") {
            status.text = "Repairing PDF…"
            Thread {
                try {
                    val out = File(hakunaaCacheDir(), "hakunaa_repaired_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.repair(input, out)
                    val repairedTotal = PdfTools.pageInfo(out, 0)?.first ?: total
                    runOnUiThread {
                        if (ok) {
                            // v0.1.193: NO direct save — preview /
                            // result screen first; only its Save
                            // button publishes.
                            showPdfResultScreen("Repair PDF", out, repairedTotal, "Repaired")
                        } else {
                            status.text = "Could not repair — this file is too damaged"
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "Could not repair — this file is too damaged" }
                }
            }.start()
        })
    }


    // ------------------------------------------------------------------
    // Resize Pages (v0.1.148, new).
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Resize Pages (v0.1.152, Fix #15 + #16): the old version was a
    // plain AlertDialog whose RadioButtons all rendered selected
    // (Material3 radio drawables in this theme show the checked dot
    // on every button) and offered only A4/Letter. Rebuilt as a
    // professional teal dialog like the tool popups, with stateful
    // chips (exclusive by construction — no RadioButtons at all),
    // 9 page sizes (A4, Letter, A3, A5, Legal, Tabloid, Executive,
    // B5, Custom WxH in mm), orientation, and a fit choice.
    // Sizes are the real standard dimensions in PDF points.
    // ------------------------------------------------------------------

    private data class PageSize(val name: String, val wPt: Float, val hPt: Float)

    private val resizeSizes = listOf(
        PageSize("A4", 595f, 842f),
        PageSize("Letter", 612f, 792f),
        PageSize("A3", 842f, 1191f),
        PageSize("A5", 420f, 595f),
        PageSize("Legal", 612f, 1008f),
        PageSize("Tabloid", 792f, 1224f),
        PageSize("Executive", 522f, 756f),
        PageSize("B5", 499f, 709f),
        PageSize("Custom", 0f, 0f)
    )

    /** Selectable chip — teal filled when selected, white with a
     * grey border when not. Single-select by construction. */
    private fun resizeChip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(10), dp(4), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                if (selected) {
                    setColor(getColor(R.color.hakunaa_blue))
                } else {
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.parseColor("#CFD8DC"))
                }
            }
            setTextColor(if (selected) Color.WHITE else getColor(R.color.hakunaa_text))
            setOnClickListener { onClick() }
        }
    }
    /** Restyle an option chip for its selected state (same look as
     * resizeChip at creation — teal fill when selected). */
    private fun paintChip(chip: TextView, selected: Boolean) {
        chip.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            if (selected) {
                setColor(getColor(R.color.hakunaa_blue))
            } else {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CFD8DC"))
            }
        }
        chip.setTextColor(if (selected) Color.WHITE else getColor(R.color.hakunaa_text))
    }

    /** A self-managing single-select chip row. [onPick] fires with
     * the new index; the row restyles itself. */
    private fun optionChipRow(
        labels: List<String>, initial: Int, onPick: (Int) -> Unit
    ): LinearLayout {
        var selected = initial
        val chips = mutableListOf<TextView>()
        for ((i, label) in labels.withIndex()) {
            val chip = resizeChip(label, i == initial) {
                selected = i
                for (j in chips.indices) paintChip(chips[j], j == selected)
                onPick(i)
            }
            chips.add(chip)
        }
        return toolChipRow(chips)
    }


    // Resize Pages — in-screen (v0.1.153, priority #1): the
    // v0.1.152 teal dialog becomes a full-screen tool screen with
    // the same 9 sizes, orientation and fit options, plus a preview
    // at the top. Nothing else about the options changes.
    private fun resizePdf() {
        pickPdfThen { input ->
            Thread {
                val info = PdfTools.pageInfo(input, 0)
                runOnUiThread {
                    if (info == null) toast("Could not open this PDF")
                    else showResizeScreen(input, info.first)
                }
            }.start()
        }
    }

    // Resize Pages — REWORKED (v0.1.156, Fix #44): the v0.1.153
    // in-screen flow kept the OLD static preview (the original
    // page) and a "Resize" button that closed the screen before
    // saving. Now the preview is LIVE: the page is shown at the
    // NEW size — its frame aspect follows the picked size and
    // orientation, with the new dimensions labelled — and the
    // bottom button is a clear "Resize" that resizes, then shows
    // the preview/result screen (only its Save button publishes).
    private fun showResizeScreen(input: File, total: Int) {
        var selectedSize = 0 // A4
        var landscape = false
        var fitContent = true
        val root = newToolOverlay("Resize Pages")
        root.addView(toolFileLine("${input.name} • $total page(s)"))
        // Live preview: the first page rendered once, shown inside
        // a frame whose aspect matches the TARGET size. Changing
        // size / orientation / custom dimensions re-frames the same
        // page bitmap — what the resized page will look like
        // (content fitted, vector stays vector).
        val previewWrap = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.parseColor("#F1F3F4"))
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200)
            ).apply { topMargin = dp(12) }
        }
        val previewImg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        }
        previewWrap.addView(previewImg)
        root.addView(previewWrap)
        val previewLabel = TextView(this).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        root.addView(previewLabel)
        root.addView(toolExplain("Change every page to a new size. Vector and text content stays vector — nothing is rasterised."))
        // Original page size (for the before/after label).
        var origW = 0f
        var origH = 0f
        // Refreshes the live preview when any option changes.
        // Assigned after the option views exist (it reads the
        // custom size fields); the chip handlers below call it
        // through this variable.
        var refreshResizePreview: () -> Unit = {}

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isFillViewport = true
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(card)
        root.addView(scroll)

        fun sectionLabel(text: String): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#78909C"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(12)
                    bottomMargin = dp(4)
                }
            }
        }
        fun chipRow(chips: List<TextView>): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2) }
            }
            for ((i, chip) in chips.withIndex()) {
                chip.layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply {
                    if (i > 0) marginStart = dp(6)
                }
                row.addView(chip)
            }
            return row
        }

        card.addView(sectionLabel("Page size"))
        val sizeChips = mutableListOf<TextView>()
        val customBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(2)
            }
        }
        fun customEdit(hint: String): EditText {
            return EditText(this).apply {
                this.hint = hint
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 14f
                setTextColor(getColor(R.color.hakunaa_text))
                setHintTextColor(Color.parseColor("#90A4AE"))
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.parseColor("#CFD8DC"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, dp(44), 1f
                ).apply {
                    if (hint == "Height") marginStart = dp(8)
                }
            }
        }
        val etCustomW = customEdit("Width")
        val etCustomH = customEdit("Height")
        customBox.addView(etCustomW)
        customBox.addView(etCustomH)
        fun refreshSizeChips() {
            for (i in sizeChips.indices) {
                val chip = sizeChips[i]
                val selected = i == selectedSize
                chip.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    if (selected) {
                        setColor(getColor(R.color.hakunaa_blue))
                    } else {
                        setColor(Color.WHITE)
                        setStroke(dp(1), Color.parseColor("#CFD8DC"))
                    }
                }
                chip.setTextColor(if (selected) Color.WHITE else getColor(R.color.hakunaa_text))
            }
            customBox.visibility = if (resizeSizes[selectedSize].name == "Custom") View.VISIBLE else View.GONE
        }
        for (i in resizeSizes.indices) {
            sizeChips.add(resizeChip(resizeSizes[i].name, i == selectedSize) {
                selectedSize = i
                refreshSizeChips()
                refreshResizePreview()
            })
        }
        for (rowStart in 0 until sizeChips.size step 3) {
            card.addView(chipRow(sizeChips.subList(rowStart, minOf(rowStart + 3, sizeChips.size))))
        }
        card.addView(customBox)

        card.addView(sectionLabel("Orientation"))
        val orientChips = mutableListOf<TextView>()
        fun refreshOrientChips() {
            val chips = listOf("Portrait" to !landscape, "Landscape" to landscape)
            for (i in chips.indices) {
                val chip = orientChips[i]
                val selected = chips[i].second
                chip.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    if (selected) {
                        setColor(getColor(R.color.hakunaa_blue))
                    } else {
                        setColor(Color.WHITE)
                        setStroke(dp(1), Color.parseColor("#CFD8DC"))
                    }
                }
                chip.setTextColor(if (selected) Color.WHITE else getColor(R.color.hakunaa_text))
            }
        }
        orientChips.add(resizeChip("Portrait", true) {
            landscape = false
            refreshOrientChips()
            refreshResizePreview()
        })
        orientChips.add(resizeChip("Landscape", false) {
            landscape = true
            refreshOrientChips()
            refreshResizePreview()
        })
        card.addView(chipRow(orientChips))

        card.addView(sectionLabel("Content"))
        val fitChips = mutableListOf<TextView>()
        fun refreshFitChips() {
            val chips = listOf("Fit to page" to fitContent, "Keep size" to !fitContent)
            for (i in chips.indices) {
                val chip = fitChips[i]
                val selected = chips[i].second
                chip.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    if (selected) {
                        setColor(getColor(R.color.hakunaa_blue))
                    } else {
                        setColor(Color.WHITE)
                        setStroke(dp(1), Color.parseColor("#CFD8DC"))
                    }
                }
                chip.setTextColor(if (selected) Color.WHITE else getColor(R.color.hakunaa_text))
            }
        }
        fitChips.add(resizeChip("Fit to page", true) {
            fitContent = true
            refreshFitChips()
            refreshResizePreview()
        })
        fitChips.add(resizeChip("Keep size", false) {
            fitContent = false
            refreshFitChips()
            refreshResizePreview()
        })
        card.addView(chipRow(fitChips))
        val status = toolStatusLine("")
        card.addView(status)

        /** Target page rectangle from the current options, or null
         * when Custom has an invalid size (10–1000 mm each side). */
        fun currentTarget(): PDRectangle? {
            val size = resizeSizes[selectedSize]
            if (size.name == "Custom") {
                val wMm = etCustomW.text.toString().trim().toFloatOrNull()
                val hMm = etCustomH.text.toString().trim().toFloatOrNull()
                if (wMm == null || hMm == null || wMm < 10f || hMm < 10f || wMm > 1000f || hMm > 1000f) return null
                val wPt = wMm * 72f / 25.4f
                val hPt = hMm * 72f / 25.4f
                return if (landscape) PDRectangle(hPt, wPt) else PDRectangle(wPt, hPt)
            }
            return if (landscape) PDRectangle(size.hPt, size.wPt) else PDRectangle(size.wPt, size.hPt)
        }
        refreshResizePreview = {
            val target = currentTarget()
            if (target == null) {
                previewLabel.text = "Enter a valid width and height (10–1000 mm)"
            } else {
                val aspect = target.width / target.height
                val wrapW = resources.displayMetrics.widthPixels - dp(32)
                val maxH = dp(200)
                var pw = wrapW
                var ph = (pw / aspect).toInt()
                if (ph > maxH) {
                    ph = maxH
                    pw = (ph * aspect).toInt()
                }
                previewImg.layoutParams = FrameLayout.LayoutParams(pw, ph).apply {
                    gravity = Gravity.CENTER
                }
                previewImg.requestLayout()
                val wMm = (target.width * 25.4f / 72f).toInt()
                val hMm = (target.height * 25.4f / 72f).toInt()
                val name = resizeSizes[selectedSize].name
                val orient = if (landscape) "Landscape" else "Portrait"
                val fit = if (fitContent) "Fit to page" else "Keep size"
                val orig = if (origW > 0f && origH > 0f) {
                    "\nOriginal: ${(origW * 25.4f / 72f).toInt()} × ${(origH * 25.4f / 72f).toInt()} mm"
                } else ""
                previewLabel.text = "New size: $name • $wMm × $hMm mm • $orient • $fit$orig"
            }
        }
        val sizeWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { refreshResizePreview() }
        }
        etCustomW.addTextChangedListener(sizeWatcher)
        etCustomH.addTextChangedListener(sizeWatcher)
        refreshResizePreview()
        // Render the first page once; the live preview re-frames
        // the same bitmap for every option change.
        Thread {
            try {
                val info = PdfTools.pageInfo(input, 0)
                val thumb = PdfTools.renderFirstPageThumb(input, 720)
                runOnUiThread {
                    if (info != null) {
                        origW = info.second
                        origH = info.third
                    }
                    if (thumb != null && genericOverlay != null) {
                        genericOverlayBitmaps.add(thumb)
                        previewImg.setImageBitmap(thumb)
                    }
                    refreshResizePreview()
                }
            } catch (_: Throwable) { }
        }.start()

        // v0.1.193: "Resize" (no longer "Resize & Save") —
        // resize in a worker thread, then the preview/result
        // screen; only its Save button publishes.
        root.addView(toolBottomRow("Resize") {
            val target = currentTarget()
            if (target == null) {
                toast("Enter a valid width and height (10–1000 mm)")
                return@toolBottomRow
            }
            val size = resizeSizes[selectedSize]
            val label = if (size.name == "Custom") {
                val wMm = etCustomW.text.toString().trim().toFloatOrNull() ?: 0f
                val hMm = etCustomH.text.toString().trim().toFloatOrNull() ?: 0f
                "Custom ${wMm.toInt()}×${hMm.toInt()}mm"
            } else size.name
            status.text = "Resizing to $label…"
            Thread {
                try {
                    val out = File(hakunaaCacheDir(), "hakunaa_resized_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.resizePages(input, out, target, fitContent)
                    runOnUiThread {
                        if (ok) {
                            showPdfResultScreen(
                                "Resize Pages", out, total,
                                "New size: $label"
                            )
                        } else {
                            status.text = "Resize failed — check the file and try again"
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { status.text = "Resize failed — check the file and try again" }
                }
            }.start()
        })
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
            setOnClickListener { attemptLeavePdf { dismissSmartOverlay() } }
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
        // v0.1.154, Fix #29: 170dp was far too tall — a portrait
        // page thumb is narrow, so most of the area was empty grey
        // and the 5 result rows were pushed into a scroll. 100dp
        // keeps the preview recognisable and reclaims the space.
        ivSmartPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.parseColor("#F1F3F4"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(100)
            ).apply { topMargin = dp(10) }
        }
        root.addView(ivSmartPreview)

        val fileLine = TextView(this).apply {
            text = "Original: ${input.name} • ${FileUtils.formatSize(smartOriginalSize)}"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        root.addView(fileLine)

        val resultsLabel = TextView(this).apply {
            text = "Results"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
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

        // v0.1.154, Fix #29: NO SCROLL — the old ScrollView let
        // the 5 result rows hide below the fold. The rows now
        // share this weight-1 box by weight (height 0, weight 1
        // in buildSmartResultRow), so all 5 always fit on one
        // screen on any phone. The bottom buttons stay pinned.
        smartResultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(smartResultsBox)

        // Bottom buttons, top to bottom (v0.1.150, like Image
        // Smart): Save selected → Custom size → Close + Done.
        val btnSave = MaterialButton(this).apply {
            text = "Save selected"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(10) }
            setOnClickListener { saveSmartSelected() }
        }
        styleFilledButton(btnSave)
        root.addView(btnSave)

        val btnCustom = MaterialButton(this).apply {
            text = "Custom size"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(6) }
            setOnClickListener { showSmartCustomSizePopup() }
        }
        styleOutlineButton(btnCustom)
        root.addView(btnCustom)

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val btnClose = MaterialButton(this).apply {
            text = "Close"
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { attemptLeavePdf { dismissSmartOverlay() } }
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
                    // v0.1.193: "Small" + "Best" tags (Image Smart
                    // pattern). Smallest = the smallest file; Best
                    // = the highest-quality level within 2x of the
                    // smallest (levels are best quality first, so
                    // the first level within 2x wins).
                    var smallestIdx = 0
                    for (i in levels.indices) {
                        if (levels[i].size < levels[smallestIdx].size) smallestIdx = i
                    }
                    val smallestSize = levels[smallestIdx].size
                    var bestIdx = smallestIdx
                    for (i in levels.indices) {
                        if (levels[i].size <= smallestSize * 2) { bestIdx = i; break }
                    }
                    addSmartTag(smartRows[bestIdx], "Best", getColor(R.color.hakunaa_blue))
                    addSmartTag(smartRows[smallestIdx], "Small", 0xFF43A047.toInt())
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
            // v0.1.154, Fix #29: height 0 + weight 1 — the 5 rows
            // share the results box height exactly, so nothing
            // scrolls and nothing hides below the fold.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(4); bottomMargin = dp(4) }
            setPadding(dp(12), dp(8), dp(12), dp(8))
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
        // v0.1.193: "Small" / "Best" tag pills (filled by
        // showSmartResults once the levels are computed — the
        // tag row is child 2 of the left column).
        val tagRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            visibility = View.GONE
        }
        left.addView(tagRow)
        // v0.1.150: VIEW pill on every level — opens that result
        // PDF in the default viewer (Image Smart style).
        val viewPill = viewPillButton { if (file != null) viewPdf(file) }
        val check = TextView(this).apply {
            text = "✓"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_blue))
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

    /** v0.1.193: add a "Small" / "Best" tag pill to a Smart
     * result row (the tag row is child 2 of the row's left
     * column). */
    private fun addSmartTag(row: LinearLayout, text: String, bgColor: Int) {
        val left = row.getChildAt(0) as? LinearLayout ?: return
        val tagRow = left.getChildAt(2) as? LinearLayout ?: return
        val pill = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(bgColor)
            }
            setPadding(dp(8), dp(2), dp(8), dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }
        tagRow.addView(pill)
        tagRow.visibility = View.VISIBLE
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
                    setColor(0xFFF5F8FF.toInt())
                    setStroke((2 * density).toInt(), getColor(R.color.hakunaa_blue))
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
                    setColor(getColor(R.color.hakunaa_blue))
                } else {
                    setColor(Color.WHITE)
                    setStroke(dp(1), getColor(R.color.hakunaa_blue))
                }
            }
            pill.setTextColor(if (selected) Color.WHITE else getColor(R.color.hakunaa_blue))
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

    private fun signPdf() {
        pickPdfThen { input ->
            Thread {
                val info = PdfTools.pageInfo(input, 0)
                runOnUiThread {
                    if (info == null) toast("Could not open this PDF")
                    else showSignScreen(input, info.first)
                }
            }.start()
        }
    }

    // Sign PDF — iLovePDF-style rebuild (v0.1.153, Fix #23). The old
    // flow stamped the signature at a fixed bottom position without
    // ever showing the page (the user rightly asked: how can you
    // sign a PDF you can't open?). Now: (1) the PDF page is shown
    // accurately, with a thumbnail strip for multi-page PDFs;
    // (2) the Sign PDF button opens a signature pad (draw, pen
    // colour, archive, delete, done); (3) the drawn signature is
    // placed ON the page preview — drag it anywhere, resize with
    // the slider — and Save stamps it at exactly that spot.
    private fun showSignScreen(input: File, total: Int) {
        signInput = input
        signPageIndex = 0
        signPageTotal = total
        signArchive = false
        val root = newToolOverlay("Sign PDF")
        root.addView(toolFileLine("${input.name} • $total page(s)"))
        // btnSign is used by the pad Done handler, created before
        // the bottom row — declare it up front (nullable local).
        var btnSign: TextView? = null
        // v0.1.193: Done button (confirms the placed signatures)
        // — same up-front pattern as btnSign.
        var btnDone: TextView? = null
        // v0.1.193: bottom row (Back + Save) — declared up front
        // so the pad Done handler can show it again after drawing.
        var signBottomRow: LinearLayout? = null
        // seekSize is created with the slider, after the stage —
        // the signature-overlay helpers above it need the reference
        // up front (nullable local, same pattern as btnSign).
        var seekSize: SeekBar? = null

        // Page stage: the page preview (exact aspect) with the
        // signature overlaid on top of it, draggable.
        val stageOuter = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(12) }
        }
        val pageFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.parseColor("#F1F3F4"))
            }
        }
        stageOuter.addView(pageFrame)
        root.addView(stageOuter)
        val pageIv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        pageFrame.addView(pageIv)
        // Placed signatures (v0.1.156, Fix #46 + #47): MULTIPLE
        // signatures, each with geometry in PDF points on its own
        // page. Points survive page switches and give the slider
        // and the stamp one true size. The old single-signature
        // frame-pixel state (sigX/sigY/sigW/sigH + one sigIv) is
        // gone: it kept the slider and the stamp disagreeing —
        // the v0.1.155 signature came out huge and the slider
        // could not shrink it (Fix #46).
        data class PlacedSig(
            val bitmap: Bitmap,
            val page: Int,
            val pageW: Float,
            val pageH: Float,
            var xPt: Float, // from left, PDF points
            var yPt: Float, // from bottom, PDF points
            var wPt: Float,
            var hPt: Float,
            var view: ImageView? = null,
            var handle: View? = null
        )
        val placedSigs = mutableListOf<PlacedSig>()
        var selectedSig: PlacedSig? = null
        var syncingSlider = false
        // Signature geometry, in pageFrame pixels.
        var frameW = 0
        var frameH = 0

        /** Points -> frame pixels for a signature on the current page. */
        fun sigFrameRect(sig: PlacedSig): android.graphics.RectF {
            val sx = frameW / sig.pageW
            val sy = frameH / sig.pageH
            val x = sig.xPt * sx
            val w = sig.wPt * sx
            val h = sig.hPt * sy
            val y = frameH - (sig.yPt + sig.hPt) * sy
            return android.graphics.RectF(x, y, x + w, y + h)
        }

        /** Teal outline marking the selected signature. */
        fun selectedSigBorder(): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setStroke(dp(2), getColor(R.color.hakunaa_blue))
            }
        }

        /** Move the size slider to match the selected signature
         * (programmatic change — guarded so it does not resize). */
        fun syncSizeSlider() {
            val sb = seekSize ?: return
            val sig = selectedSig ?: return
            syncingSlider = true
            sb.progress = (((sig.wPt / sig.pageW) - 0.10f) / 0.80f * 150f).toInt().coerceIn(0, 150)
            syncingSlider = false
        }

        /** Drag the resize handle (bottom-right corner of the
         * selected signature): grows/shrinks it, keeping its
         * aspect, inside the page. */
        fun resizeSig(sig: PlacedSig, handle: View, event: MotionEvent): Boolean {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> true
                MotionEvent.ACTION_MOVE -> {
                    val iv = sig.view ?: return true
                    val params = iv.layoutParams as FrameLayout.LayoutParams
                    val hp = handle.layoutParams as FrameLayout.LayoutParams
                    val aspect = sig.bitmap.height.toFloat() / sig.bitmap.width.toFloat()
                    val frameX = hp.leftMargin + dp(14) + event.x
                    var newW = (frameX - params.leftMargin).coerceIn(dp(40).toFloat(), frameW.toFloat())
                    var newH = newW * aspect
                    if (params.topMargin + newH > frameH) {
                        newH = (frameH - params.topMargin).toFloat().coerceAtLeast(1f)
                        newW = newH / aspect
                    }
                    params.width = newW.toInt()
                    params.height = newH.toInt()
                    iv.layoutParams = params
                    val sx = frameW / sig.pageW
                    val sy = frameH / sig.pageH
                    sig.wPt = newW / sx
                    sig.hPt = newH / sy
                    hp.leftMargin = (params.leftMargin + newW - dp(14)).toInt()
                    hp.topMargin = (params.topMargin + newH - dp(14)).toInt()
                    handle.layoutParams = hp
                    syncSizeSlider()
                    true
                }
                else -> true
            }
        }

        /** Little corner handle on the selected signature. */
        fun addResizeHandle(sig: PlacedSig) {
            val iv = sig.view ?: return
            val params = iv.layoutParams as FrameLayout.LayoutParams
            val handle = TextView(this).apply {
                text = "↘"
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(getColor(R.color.hakunaa_blue))
                }
            }
            handle.layoutParams = FrameLayout.LayoutParams(dp(28), dp(28)).apply {
                leftMargin = (params.leftMargin + params.width - dp(14)).toInt()
                topMargin = (params.topMargin + params.height - dp(14)).toInt()
            }
            handle.setOnTouchListener { _, event -> resizeSig(sig, handle, event) }
            pageFrame.addView(handle)
            sig.handle = handle
        }

        /** Select a signature (light update: border + handle only —
         * never rebuilds the views mid-drag). */
        fun selectSig(sig: PlacedSig?) {
            selectedSig = sig
            for (s in placedSigs) {
                if (s.page != signPageIndex) continue
                val iv = s.view ?: continue
                iv.background = if (s == sig) selectedSigBorder() else null
                if (s == sig) {
                    if (s.handle == null) addResizeHandle(s)
                } else {
                    s.handle?.let { pageFrame.removeView(it) }
                    s.handle = null
                }
            }
            syncSizeSlider()
        }

        /** Drag a placed signature anywhere on the page. Tapping
         * one also selects it (so the slider resizes that one). */
        fun dragSig(sig: PlacedSig, iv: ImageView, event: MotionEvent): Boolean {
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selectSig(sig)
                    iv.tag = Pair(event.x, event.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val off = iv.tag as? Pair<Float, Float> ?: return true
                    val params = iv.layoutParams as FrameLayout.LayoutParams
                    val newX = (params.leftMargin + event.x - off.first).coerceIn(0f, (frameW - params.width).toFloat().coerceAtLeast(0f))
                    val newY = (params.topMargin + event.y - off.second).coerceIn(0f, (frameH - params.height).toFloat().coerceAtLeast(0f))
                    params.leftMargin = newX.toInt()
                    params.topMargin = newY.toInt()
                    iv.layoutParams = params
                    val sx = frameW / sig.pageW
                    val sy = frameH / sig.pageH
                    sig.xPt = newX / sx
                    sig.yPt = (frameH - (newY + params.height)) / sy
                    sig.handle?.let { h ->
                        val hp = h.layoutParams as FrameLayout.LayoutParams
                        hp.leftMargin = (newX + params.width - dp(14)).toInt()
                        hp.topMargin = (newY + params.height - dp(14)).toInt()
                        h.layoutParams = hp
                    }
                    true
                }
                else -> true
            }
        }

        /** Rebuild the signature overlays on the current page:
         * one draggable ImageView per placed signature on this
         * page, teal border + resize handle on the selected one. */
        fun refreshSignOverlays() {
            for (sig in placedSigs) {
                sig.view?.let { pageFrame.removeView(it) }
                sig.handle?.let { pageFrame.removeView(it) }
                sig.view = null
                sig.handle = null
            }
            if (frameW <= 0) return
            for (sig in placedSigs) {
                if (sig.page != signPageIndex) continue
                val r = sigFrameRect(sig)
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_XY
                    setImageBitmap(sig.bitmap)
                }
                iv.layoutParams = FrameLayout.LayoutParams(
                    r.width().toInt().coerceAtLeast(1),
                    r.height().toInt().coerceAtLeast(1)
                ).apply {
                    leftMargin = r.left.toInt()
                    topMargin = r.top.toInt()
                }
                iv.background = if (sig == selectedSig) selectedSigBorder() else null
                iv.setOnTouchListener { _, event -> dragSig(sig, iv, event) }
                pageFrame.addView(iv)
                sig.view = iv
                if (sig == selectedSig) addResizeHandle(sig)
            }
            syncSizeSlider()
        }

        /** "Sign PDF" before the first signature, "Add signature"
         * after — so adding another one is obvious (Fix #47).
         * v0.1.193: the Done button only shows once a signature
         * is placed (it confirms the signing). */
        fun updateSignButton() {
            btnSign?.text = if (placedSigs.isEmpty()) "Sign PDF" else "Add signature"
            btnDone?.visibility = if (placedSigs.isEmpty()) View.GONE else View.VISIBLE
        }

        /** Place a freshly drawn (or saved) signature on the
         * current page. Default size is 30% of the page width
         * (the old 45% came out huge, Fix #46); each extra
         * signature is offset so they never stack exactly. */
        fun addSignature(bmp: Bitmap?) {
            if (bmp == null) return
            val aspect = bmp.height.toFloat() / bmp.width.toFloat()
            val wPt = signPageW * 0.30f
            val hPt = wPt * aspect
            val n = placedSigs.count { it.page == signPageIndex }
            val offPt = (n * 24f).coerceAtMost(signPageW * 0.3f)
            val sig = PlacedSig(
                bitmap = bmp, page = signPageIndex,
                pageW = signPageW, pageH = signPageH,
                xPt = signPageW - wPt - 12f - offPt,
                yPt = 12f + offPt,
                wPt = wPt, hPt = hPt
            )
            placedSigs.add(sig)
            selectedSig = sig
            refreshSignOverlays()
            updateSignButton()
        }

        /** v0.1.193: confirm the placed signature(s) — stamp them
         * all to a temp PDF in the cache, then show the PREVIEW /
         * result screen. Only that screen's Save button publishes
         * (no direct save). Used by both Done and the bottom Save. */
        fun finishSigning() {
            if (placedSigs.isEmpty()) {
                toast("Tap Sign PDF and draw your signature first")
                return
            }
            if (frameW <= 0) {
                toast("Still loading the page — try again in a second")
                return
            }
            // Stamp EVERY placed signature in one pass (Fix #47).
            val placements = placedSigs.map { sig ->
                PdfTools.SignPlacement(sig.bitmap, sig.page, sig.xPt, sig.yPt, sig.wPt, sig.hPt)
            }
            val count = placedSigs.size
            Thread {
                try {
                    val out = File(hakunaaCacheDir(), "hakunaa_signed_${System.currentTimeMillis()}.pdf")
                    val ok = PdfTools.signMultiple(input, out, placements)
                    runOnUiThread {
                        if (ok) {
                            showPdfResultScreen(
                                "Sign PDF", out, total,
                                "Signed • $count signature${if (count == 1) "" else "s"}"
                            )
                        } else {
                            toast("Sign failed — check the file and try again")
                        }
                    }
                } catch (_: Throwable) {
                    runOnUiThread { toast("Sign failed — check the file and try again") }
                }
            }.start()
        }
        fun loadPage(index: Int) {
            signPageIndex = index
            // Switching pages selects the first signature on that
            // page (or none) — signatures on other pages stay put.
            selectedSig = placedSigs.firstOrNull { it.page == index }
            val info = PdfTools.pageInfo(input, index) ?: return
            signPageW = info.second
            signPageH = info.third
            val screenW = resources.displayMetrics.widthPixels - dp(32)
            var w = screenW
            var h = (w * signPageH / signPageW).toInt()
            val maxH = dp(460)
            if (h > maxH) {
                h = maxH
                w = (h * signPageW / signPageH).toInt()
            }
            frameW = w
            frameH = h
            pageFrame.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                gravity = Gravity.CENTER
            }
            pageFrame.requestLayout()
            refreshSignOverlays()
            Thread {
                try {
                    val bmp = PdfTools.renderPageThumb(input, index, 720)
                    runOnUiThread {
                        if (bmp != null && genericOverlay != null) {
                            signPageBitmap?.recycle()
                            signPageBitmap = bmp
                            genericOverlayBitmaps.add(bmp)
                            pageIv.setImageBitmap(bmp)
                            refreshSignOverlays()
                        }
                    }
                } catch (_: Throwable) { }
            }.start()
        }

        // Signature size slider.
        val sizeLabel = TextView(this).apply {
            text = "Signature size"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        root.addView(sizeLabel)
        // Signature size slider (v0.1.156, Fix #46): maps 0..150 to
        // 10%..90% of the page width, so the signature can actually
        // be made small (the old 22%..90% range bottomed out too
        // big). Resizes the SELECTED signature live, centred, in
        // PDF points — the stamp uses exactly that size.
        val sbSize = SeekBar(this).apply {
            max = 150
            progress = 40
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        seekSize = sbSize
        sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (syncingSlider || !fromUser) return
                val sig = selectedSig ?: return
                if (frameW <= 0) return
                val aspect = sig.bitmap.height.toFloat() / sig.bitmap.width.toFloat()
                val centreX = sig.xPt + sig.wPt / 2f
                val centreY = sig.yPt + sig.hPt / 2f
                sig.wPt = (sig.pageW * (0.10f + progress / 150f * 0.80f)).coerceIn(12f, sig.pageW)
                sig.hPt = sig.wPt * aspect
                if (sig.hPt > sig.pageH) {
                    sig.hPt = sig.pageH
                    sig.wPt = sig.hPt / aspect
                }
                sig.xPt = (centreX - sig.wPt / 2f).coerceIn(0f, (sig.pageW - sig.wPt).coerceAtLeast(0f))
                sig.yPt = (centreY - sig.hPt / 2f).coerceIn(0f, (sig.pageH - sig.hPt).coerceAtLeast(0f))
                refreshSignOverlays()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        root.addView(sbSize)

        // Page thumbnail strip (multi-page PDFs).
        val strip = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(76)
            ).apply { topMargin = dp(8) }
            isHorizontalScrollBarEnabled = false
        }
        val stripRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        strip.addView(stripRow)
        root.addView(strip)
        val stripViews = mutableListOf<ImageView>()
        fun paintStrip() {
            for (i in stripViews.indices) {
                stripViews[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 8f * resources.displayMetrics.density
                    setColor(Color.parseColor("#F1F3F4"))
                    if (i == signPageIndex) {
                        setStroke(dp(2), getColor(R.color.hakunaa_blue))
                    }
                }
            }
        }
        Thread {
            try {
                val count = minOf(total, 50)
                for (i in 0 until count) {
                    val bmp = PdfTools.renderPageThumb(input, i, 160)
                    runOnUiThread {
                        if (genericOverlay == null) return@runOnUiThread
                        val iv = ImageView(this@PdfToolsActivity).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            layoutParams = LinearLayout.LayoutParams(dp(56), dp(72)).apply {
                                marginEnd = dp(8)
                            }
                            setPadding(dp(2), dp(2), dp(2), dp(2))
                        }
                        if (bmp != null) {
                            genericOverlayBitmaps.add(bmp)
                            iv.setImageBitmap(bmp)
                        }
                        val index = stripViews.size
                        iv.setOnClickListener {
                            loadPage(index)
                            paintStrip()
                        }
                        stripViews.add(iv)
                        stripRow.addView(iv)
                        paintStrip()
                    }
                }
            } catch (_: Throwable) { }
        }.start()

        // v0.1.193: Done button — confirms the placed signature(s)
        // and opens the preview/result screen. Hidden until a
        // signature is placed (updateSignButton toggles it), and
        // hidden while the pad is open.
        btnDone = popupPillButton("Done", true) {
            finishSigning()
        }.apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(12) }
        }
        root.addView(btnDone)

        // Signature pad (iLovePDF style): replaces the stage while
        // drawing. Draw with finger, pick a pen colour, archive the
        // signature for next time, Delete to clear, Done to place.
        val padBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(12) }
        }
        val padLabel = TextView(this).apply {
            text = "Draw your signature"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        padBox.addView(padLabel)
        val pad = SignaturePadView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * resources.displayMetrics.density
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CBD5E1"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { topMargin = dp(8) }
        }
        padBox.addView(pad)
        // Editor tools for the pad (v0.1.156, Fix #48): pen
        // boldness (Thin / Medium / Bold), Eraser, Undo — small
        // pills in one slim row, so the pad stays clean.
        val padTools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        fun toolPill(text: String): TextView {
            return TextView(this).apply {
                this.text = text
                textSize = 12f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(7), dp(12), dp(7))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
        }
        fun paintToolPill(pill: TextView, active: Boolean, enabled: Boolean = true) {
            pill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                if (active) setColor(getColor(R.color.hakunaa_blue))
                else {
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.parseColor("#CFD8DC"))
                }
            }
            pill.setTextColor(if (active) Color.WHITE else getColor(R.color.hakunaa_text))
            pill.alpha = if (enabled) 1f else 0.4f
        }
        lateinit var widthPills: List<TextView>
        lateinit var eraserPill: TextView
        lateinit var undoPill: TextView
        fun paintPadTools() {
            val widths = listOf(4f, 7f, 12f)
            for (i in widthPills.indices) {
                paintToolPill(widthPills[i], !pad.isErasing() && pad.currentWidth() == widths[i])
            }
            paintToolPill(eraserPill, pad.isErasing())
            paintToolPill(undoPill, false, pad.canUndo())
        }
        widthPills = listOf("Thin" to 4f, "Medium" to 7f, "Bold" to 12f).map { (label, w) ->
            toolPill(label).apply {
                setOnClickListener {
                    pad.setStrokeWidth(w)
                    paintPadTools()
                }
                padTools.addView(this)
            }
        }
        eraserPill = toolPill("Eraser").apply {
            setOnClickListener {
                pad.setEraser(!pad.isErasing())
                paintPadTools()
            }
            padTools.addView(this)
        }
        undoPill = toolPill("Undo").apply {
            setOnClickListener {
                if (pad.undo()) paintPadTools()
            }
            padTools.addView(this)
        }
        pad.onStrokeEnd = { paintPadTools() }
        paintPadTools()
        padBox.addView(padTools)
        val padRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val colorDots = mutableListOf<TextView>()
        val penColors = listOf(0xFF111827.toInt(), 0xFF1E88E5.toInt(), 0xFF9AA0A6.toInt())
        fun paintDots() {
            for (i in colorDots.indices) {
                colorDots[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(penColors[i])
                    if (penColors[i] == signSigColor) {
                        setStroke(dp(2), getColor(R.color.hakunaa_blue))
                    }
                }
            }
        }
        for (color in penColors) {
            val dot = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                    marginEnd = dp(12)
                }
                isClickable = true
                isFocusable = true
            }
            dot.setOnClickListener {
                signSigColor = color
                pad.setInkColor(color)
                paintDots()
                paintPadTools()
            }
            colorDots.add(dot)
            padRow.addView(dot)
        }
        paintDots()
        val tvArchive = TextView(this).apply {
            text = "☐  Archive your signature"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(8) }
            isClickable = true
            isFocusable = true
        }
        tvArchive.setOnClickListener {
            signArchive = !signArchive
            tvArchive.text = if (signArchive) "☑  Archive your signature" else "☐  Archive your signature"
        }
        padRow.addView(tvArchive)
        padBox.addView(padRow)
        val padBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val tvPadDelete = TextView(this).apply {
            text = "Delete"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#E85A46"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
        }
        tvPadDelete.setOnClickListener { pad.clear(); paintPadTools() }
        padBottom.addView(tvPadDelete)
        val btnPadDone = popupPillButton("Done", true) {
            if (pad.isEmpty()) {
                toast("Draw your signature first")
                return@popupPillButton
            }
            val bmp = pad.toBitmap()
            if (bmp == null) {
                toast("Draw your signature first")
                return@popupPillButton
            }
            if (signArchive) {
                Thread {
                    try {
                        val archive = File(filesDir, "archived_signature.png")
                        FileOutputStream(archive).use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    } catch (_: Throwable) { }
                }.start()
            }
            padBox.visibility = View.GONE
            stageOuter.visibility = View.VISIBLE
            sizeLabel.visibility = View.VISIBLE
            seekSize?.visibility = View.VISIBLE
            strip.visibility = View.VISIBLE
            signBottomRow?.visibility = View.VISIBLE
            btnSign?.visibility = View.VISIBLE
            addSignature(bmp)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(dp(110), dp(48)).apply {
                marginStart = dp(8)
            }
        }
        padBottom.addView(btnPadDone)
        padBox.addView(padBottom)
        root.addView(padBox)

        // Bottom row (v0.1.193): Back (left) + Save (right).
        // "Sign PDF" now sits right below the preview page.
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        signBottomRow = bottomRow
        val btnBack = popupPillButton("Back", false) { attemptLeavePdf { dismissGenericOverlay() } }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnSave = popupPillButton("Save", true) {
            finishSigning()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(8)
            }
        }
        bottomRow.addView(btnBack)
        bottomRow.addView(btnSave)
        root.addView(bottomRow)
        // "Sign PDF" button — right BELOW the preview page
        // (root child 2: file line=0, stage=1). Opens the pad.
        btnSign = popupPillButton("Sign PDF", false) {
            stageOuter.visibility = View.GONE
            sizeLabel.visibility = View.GONE
            seekSize?.visibility = View.GONE
            strip.visibility = View.GONE
            btnDone?.visibility = View.GONE
            bottomRow.visibility = View.GONE
            padBox.visibility = View.VISIBLE
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(12) }
        }
        root.addView(btnSign, 2)
        updateSignButton()

        // Saved signature from an earlier session (archive).
        val archived = File(filesDir, "archived_signature.png")
        if (archived.exists()) {
            val tvSaved = TextView(this).apply {
                text = "Use saved signature"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_blue))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            tvSaved.setOnClickListener {
                Thread {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeFile(archived.absolutePath)
                        runOnUiThread {
                            if (bmp != null) addSignature(bmp)
                            else toast("Saved signature could not be read")
                        }
                    } catch (_: Throwable) {
                        runOnUiThread { toast("Saved signature could not be read") }
                    }
                }.start()
            }
            root.addView(tvSaved)
        }

        loadPage(0)
    }







}
