package com.hakunaa.app.video

import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.core.Presets
import com.hakunaa.app.core.StatusBar
import java.io.File

/**
 * Video Tools menu (v0.1.159): single screen, NO scroll. 10 tools in
 * 2 cols x 5 rows, ALL the same size, lite pink cards (#FFF5F8) +
 * pink border, code-drawn PDF-theme icons (VideoIconView) + white glyphs.
 * Every tool: intro popup (name + bullets + Pick Video / Back) ->
 * pick video -> full-screen tool screen with ALL options inside.
 * Compress tools share one screen with Quick / Smart / Advanced
 * tabs (same picked video). VideoCompressor's algorithm is
 * untouched — this activity only calls it; new FFmpeg work lives
 * in VideoToolsEngine. PDF / Image / Scanner are untouched.
 */
class VideoToolsActivity : AppCompatActivity() {

    // Lightest pink (user-picked shade A, 2026-11-21 — same intensity
    // as the PDF menu's teal #E0F2F1) + pink border.
    private val pinkCard = 0xFFFFF5F8.toInt()
    private val pinkBorder = 0xFFF06292.toInt()
    private val pinkDark = 0xFFEC407A.toInt()

    private data class Tool(
        val name: String,
        val subtitle: String,
        val iconKind: VideoIconView.Kind,
        val explain: String,
        val action: () -> Unit
    )

    // Single-video picker, shared by every tool (one callback at a
    // time — each tool sets it right before launching).
    private var singleVideoCallback: ((Uri) -> Unit)? = null
    private val pickSingleVideo =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) singleVideoCallback?.invoke(uri)
            singleVideoCallback = null
        }

    // Full-screen tool overlay (one at a time).
    private var toolOverlay: View? = null
    private var overlayBitmaps = mutableListOf<Bitmap>()

    // Current picked video (shared across compress tabs).
    private var inputUri: Uri? = null
    private var inputFile: File? = null
    private var inputSize: Long = -1
    private var inputName: String = ""
    private var probeInfo: VideoToolsEngine.ProbeInfo? = null

    // Compress state (shared across the 3 tabs).
    private var compressTab = 0 // 0 Quick, 1 Smart, 2 Advanced
    private var quickCrf = 28
    private var quickRes = VideoCompressor.Resolution.ORIGINAL
    private var quickCodec = VideoCompressor.Codec.H264
    private var advCrf = 28
    private var advUseBitrate = false
    private var advBitrateMbps = 8
    private var advRes = VideoCompressor.Resolution.ORIGINAL
    private var advFps = 0 // 0 = original
    private var advCodec = VideoCompressor.Codec.H264
    private var advAudioKeep = true
    private var advAudioBitrate = "128k"
    private var advTwoPass = false
    private var advFormat = "mp4"
    private var advPreset = "medium"
    private var advDenoise = false
    private var advSharpen = false
    private var smartResults = mutableListOf<SmartResult>()
    private var smartSelected = -1
    private var smartRunning = false

    private data class SmartResult(
        val label: String,
        val file: File,
        val size: Long,
        val savedPct: Int,
        val isBest: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_tools)

        // Status bar (v0.1.97): keep it visible, no overlap.
        // Must be called AFTER setContentView (v0.1.133).
        StatusBar.setup(this, true)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        buildMenu()

        // System Back inside a video tool asks "Leave?" first
        // (PDF pattern, v0.1.155), instead of dropping straight
        // back to the menu.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullScreenRoot != null) {
                    closeFullScreenPlayer()
                } else if (toolOverlay != null) {
                    attemptLeaveVideo { dismissToolOverlay() }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** Plain TextView pill button (PDF pattern, v0.1.151): a plain
     * TextView + GradientDrawable, never a MaterialButton (whose
     * text clipped to nothing in the v0.1.150 tool popup). */
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
                    setColor(pinkDark)
                } else {
                    setColor(Color.WHITE)
                    setStroke(dp(1), pinkDark)
                }
            }
            setTextColor(if (filled) Color.WHITE else pinkDark)
            setOnClickListener { onClick() }
        }
    }

    /** Stateful chip for exclusive choices (PDF Resize lesson:
     * Material3 radios render ALL selected on the user's phone —
     * use chips). Selected = pink fill + white text; unselected =
     * white fill + grey border + dark text. */
    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        setChipSelected(tv, selected)
        tv.setOnClickListener { onClick() }
        return tv
    }

    private fun setChipSelected(tv: TextView, selected: Boolean) {
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            if (selected) {
                setColor(pinkDark)
            } else {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CFD8DC"))
            }
        }
        tv.setTextColor(if (selected) Color.WHITE else getColor(R.color.hakunaa_text))
    }

    /** A chip row that keeps exactly one chip selected. */
    private fun chipRow(
        options: List<String>,
        selectedIdx: Int,
        onSelect: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val chips = mutableListOf<TextView>()
        for ((i, opt) in options.withIndex()) {
            val c = chip(opt, i == selectedIdx) {
                for ((j, cc) in chips.withIndex()) setChipSelected(cc, j == i)
                onSelect(i)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            c.layoutParams = lp
            chips.add(c)
            row.addView(c)
        }
        return row
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
    }

    /** Full-width pink filled action button (56dp). */
    private fun actionButton(text: String, onClick: () -> Unit): TextView {
        return popupPillButton(text, true, onClick).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(20) }
        }
    }

    /** Selectable option row (name + detail, tap to select).
     * Selected = pink tint + pink border + check mark. */
    private fun optionRow(
        title: String,
        detail: String,
        selected: Boolean,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        setOptionRowSelected(row, selected)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val t = TextView(this).apply {
            text = title
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        val d = TextView(this).apply {
            text = detail
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
        }
        texts.addView(t)
        texts.addView(d)
        val check = TextView(this).apply {
            text = if (selected) "✓" else ""
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(pinkDark)
        }
        row.addView(texts)
        row.addView(check)
        row.setOnClickListener { onClick() }
        row.tag = check
        return row
    }

    private fun setOptionRowSelected(row: LinearLayout, selected: Boolean) {
        row.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            if (selected) {
                setColor(pinkCard)
                setStroke(dp(2), pinkBorder)
            } else {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CFD8DC"))
            }
        }
        (row.tag as? TextView)?.text = if (selected) "✓" else ""
    }

    /** Slider row: label + value + SeekBar. */
    private fun sliderRow(
        label: String,
        max: Int,
        progress: Int,
        formatValue: (Int) -> String,
        onChange: (Int) -> Unit
    ): LinearLayout {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val lab = TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val valView = TextView(this).apply {
            text = formatValue(progress)
            textSize = 14f
            setTextColor(pinkDark)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        top.addView(lab)
        top.addView(valView)
        val seek = SeekBar(this).apply {
            this.max = max
            this.progress = progress
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                valView.text = formatValue(p)
                onChange(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        wrap.addView(top)
        wrap.addView(seek)
        return wrap
    }

    /** Rounded white input box (PDF pattern, v0.1.151). */
    private fun inputBoxBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(1), Color.parseColor("#CFD8DC"))
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return ""
        val s = (ms / 1000).toInt()
        val m = s / 60
        val sec = s % 60
        return "%d:%02d".format(m, sec)
    }

    private fun formatClock(ms: Long): String {
        val s = (ms / 1000).toInt()
        val m = s / 60
        val sec = s % 60
        return "%02d:%02d".format(m, sec)
    }

    // ------------------------------------------------------------------
    // Menu — 10 tools, 2 cols x 5 rows, ALL the same size, single
    // screen NO scroll (user correction, 2026-11-21). Lite pink
    // cards + pink border, premium gradient-square vector icons.
    // ------------------------------------------------------------------

    private fun buildMenu() {
        val area = findViewById<LinearLayout>(R.id.contentArea)
        val tools = listOf(
            Tool("Quick Compress", "Pick quality", VideoIconView.Kind.QUICK,
                "• Pick a video, pick a quality\n• High / Balanced / Low / Lowest\n• Smaller file, same look") {
                pickVideoThen { showCompressScreen(0) }
            },
            Tool("Smart Compress", "Auto best size", VideoIconView.Kind.SMART,
                "• Automatic — best size for your video\n• 3 results: 1080p, 720p, 480p\n• Real sizes, pick the best one") {
                pickVideoThen { showCompressScreen(1) }
            },
            // v0.1.195: Multi Compress (UI only — functions come
            // later, user 2026-11-26). Card shows in the menu at
            // position 3; tapping it says coming soon for now.
            Tool("Multi Compress", "Compress many at once", VideoIconView.Kind.MULTI,
                "• Pick many videos at once\n• One quality for all of them\n• Compress all, save all") {
                toast("Multi Compress coming soon")
            },
            Tool("Advanced Compress", "Full manual control", VideoIconView.Kind.ADVANCED,
                "• CRF slider or exact bitrate\n• Two-pass, preset, denoise, sharpen\n• Every setting in one place") {
                pickVideoThen { showCompressScreen(2) }
            },
            Tool("Convert", "12 formats", VideoIconView.Kind.CONVERT,
                "• MP4, MKV, MOV, WebM, AVI, WMV\n• FLV, MPG, 3GP, M4V, TS, GIF") {
                pickVideoThen { showConvertScreen() }
            },
            Tool("Rotate", "Rotate video", VideoIconView.Kind.ROTATE,
                "• 90° clockwise or counter-clockwise\n• 180°, flip horizontal / vertical\n• Audio stays untouched") {
                pickVideoThen { showRotateScreen() }
            },
            Tool("Video to Audio", "Video to MP3", VideoIconView.Kind.AUDIO,
                "• Pull the audio out of a video\n• MP3, M4A, WAV, OGG, FLAC\n• Saved to Music/Hakunaa") {
                pickVideoThen { showAudioScreen() }
            },
            Tool("Video Cutter", "Cut & Split", VideoIconView.Kind.CUTTER,
                "• Cut a clip out of your video\n• Split a long video into parts\n• Fast — no re-encode") {
                pickVideoThen { showCutterScreen() }
            },
            Tool("Custom Thumbnail", "Set video cover", VideoIconView.Kind.THUMB,
                "• Pick any frame of your video\n• Set it as the video cover\n• Shows in your gallery") {
                pickVideoThen { showThumbnailScreen() }
            },
            Tool("Video Crop", "Crop frame", VideoIconView.Kind.CROP,
                "• Crop the video frame\n• 16:9, 1:1, 9:16, 4:3\n• Centered, keeps the audio") {
                pickVideoThen { showCropScreen() }
            }
        )
        for (r in 0 until 5) {
            val rowTools = tools.subList(r * 2, r * 2 + 2)
            area.addView(buildRow(rowTools))
        }
    }

    private fun buildRow(tools: List<Tool>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        for (tool in tools) row.addView(buildCard(tool))
        return row
    }

    private fun buildCard(tool: Tool): LinearLayout {
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
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(pinkCard)
                setStroke((2 * resources.displayMetrics.density).toInt(), pinkBorder)
            }
            val params = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f
            )
            val m = dp(6)
            params.setMargins(m, m, m, m)
            layoutParams = params
            setPadding(dp(6), dp(6), dp(6), dp(6))
            // Tapping a tool opens its intro popup first
            // (explanation + Pick Video + Back) — never the file
            // picker directly (PDF pattern, v0.1.150).
            setOnClickListener { showToolPopup(tool) }
        }
        val icon = VideoIconView(this).apply {
            kind = tool.iconKind
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        card.addView(icon)
        val name = AppCompatTextView(this).apply {
            text = tool.name
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
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
        return card
    }

    // ------------------------------------------------------------------
    // Tool intro popup (PDF pattern, v0.1.150): tapping a tool opens
    // this popup FIRST — never the file picker directly. Icon in a
    // pink-tinted circle, short bullets, Pick Video / Back pills.
    // ------------------------------------------------------------------

    private fun showToolPopup(tool: Tool) {
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
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val iconWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pinkCard)
            }
            layoutParams = LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val icon = VideoIconView(this).apply {
            kind = tool.iconKind
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
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
        val btnPick = popupPillButton("Pick Video", true) {
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
        // Force the window to full width (PDF Fix #1 root cause:
        // a WRAP_CONTENT dialog measures the card collapsed).
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    // ------------------------------------------------------------------
    // Generic full-screen tool overlay (PDF pattern, v0.1.153).
    // Every tool screen is built on newToolOverlay(): a full-screen
    // root above the menu (status-bar padding, consumes ALL touches
    // so nothing falls through to the menu behind), a header with
    // X + title, and a ScrollView content area.
    // ------------------------------------------------------------------

    private fun newToolOverlay(title: String, topExtraDp: Int = 16): LinearLayout {
        dismissToolOverlay()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.hakunaa_surface))
            setPadding(dp(16), statusBarHeightPx() + dp(topExtraDp), dp(16), dp(16))
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
            setOnClickListener { attemptLeaveVideo { dismissToolOverlay() } }
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
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(content)
        root.addView(scroll)
        findViewById<ViewGroup>(android.R.id.content).addView(root)
        toolOverlay = root
        return content
    }

    private fun dismissToolOverlay() {
        closeFullScreenPlayer()
        val overlay = toolOverlay ?: return
        try {
            findViewById<ViewGroup>(android.R.id.content).removeView(overlay)
        } catch (_: Throwable) {
        }
        toolOverlay = null
        // Stop any playing preview video — a detached ExoVideoView
        // keeps its audio playing otherwise.
        for (vv in activeVideoViews) {
            try {
                vv.stopPlayback()
            } catch (_: Throwable) {
            }
        }
        activeVideoViews = mutableListOf()
        smartPreviewView = null
        for (bmp in overlayBitmaps) {
            try {
                bmp.recycle()
            } catch (_: Throwable) {
            }
        }
        overlayBitmaps = mutableListOf()
    }

    /** Full-screen in-app video player (user 2026-11-24): every
     * Play / preview in Video Tools opens this professional
     * full-screen player — video fills the screen, X closes,
     * bottom bar has Play/Pause + SeekBar + time. Auto-plays on
     * open. Tap the video toggles play / pause. Back closes the
     * player first (no leave dialog). */
    private fun showFullScreenPlayer(uri: Uri) {
        closeFullScreenPlayer()
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
        // Bottom control bar (semi-transparent black).
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
        // Top close button.
        val close = TextView(this).apply {
            text = "✕"
            textSize = 26f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = statusBarHeightPx()
            }
            setOnClickListener { closeFullScreenPlayer() }
        }
        root.addView(close)
        // Play / pause toggle (button + tap on the video).
        val togglePlay: () -> Unit = {
            try {
                if (player.isPlaying()) player.pause() else player.start()
            } catch (_: Throwable) {
            }
        }
        playPause.setOnClickListener { togglePlay() }
        player.setOnClickListener { togglePlay() }
        root.setOnClickListener { togglePlay() }
        // Seek bar: drag to seek; live update while playing.
        var userSeeking = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val d = player.duration
                    if (d > 0) {
                        try {
                            player.seekTo((p * d / 1000).toInt())
                        } catch (_: Throwable) {
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                userSeeking = false
            }
        })
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (fullScreenPlayer !== player) return
                try {
                    val d = player.duration
                    val pos = player.currentPosition.toLong()
                    if (d > 0 && !userSeeking) {
                        seek.progress = ((pos * 1000) / d).toInt().coerceIn(0, 1000)
                    }
                    time.text = "${formatClock(pos)} / ${formatClock(d)}"
                    playPause.text = if (player.isPlaying()) "❚❚" else "▶"
                } catch (_: Throwable) {
                }
                handler.postDelayed(this, 500)
            }
        }
        fullScreenHandler = handler
        fullScreenTick = tick
        fullScreenRoot = root
        fullScreenPlayer = player
        findViewById<ViewGroup>(android.R.id.content).addView(root)
        player.onError = { toast("Couldn't play this video") }
        player.setVideoURI(uri)
        player.start()
        handler.post(tick)
    }

    private fun closeFullScreenPlayer() {
        val root = fullScreenRoot ?: return
        fullScreenTick?.let { fullScreenHandler?.removeCallbacks(it) }
        fullScreenTick = null
        fullScreenHandler = null
        try {
            fullScreenPlayer?.stopPlayback()
        } catch (_: Throwable) {
        }
        fullScreenPlayer = null
        try {
            findViewById<ViewGroup>(android.R.id.content).removeView(root)
        } catch (_: Throwable) {
        }
        fullScreenRoot = null
    }

    /** Leave confirmation (PDF pattern, v0.1.155): "!" in a pink
     * circle, "Your work will be discarded.", Cancel (outline) /
     * Leave (filled). Shown on system Back OR a tool screen's X.
     * Done buttons and save-success paths do NOT ask. */
    private fun attemptLeaveVideo(onLeave: () -> Unit) {
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
            setTextColor(pinkDark)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pinkCard)
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
        card.addView(headerRow)
        val msg = TextView(this).apply {
            text = "Your work will be discarded."
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        card.addView(msg)
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
        card.addView(btnRow)
        outer.addView(card)
        dialog.setContentView(outer)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    // ------------------------------------------------------------------
    // Video pick + probe (shared by every tool).
    // ------------------------------------------------------------------

    private fun pickVideoThen(action: () -> Unit) {
        singleVideoCallback = { uri ->
            inputUri = uri
            inputSize = FileUtils.fileSize(contentResolver, uri)
            inputName = FileUtils.fileName(contentResolver, uri)
            // Convert default (user 2026-11-24): a new video always
            // starts on MP4. (Reset here, not in showConvertScreen,
            // so Back from the result screen keeps the chosen format.)
            convertFormat = VideoToolsEngine.ConvertFormat.MP4
            convertRes = VideoCompressor.Resolution.ORIGINAL
            try {
                inputFile = FileUtils.copyUriToCache(
                    this, uri, "input_${System.currentTimeMillis()}.mp4"
                )
            } catch (t: Throwable) {
                toast("Could not read this video: ${t.message ?: "unknown error"}")
                inputFile = null
            }
            probeInfo = inputFile?.let { VideoToolsEngine.probe(it.absolutePath) }
            if (inputFile != null) {
                action()
            }
        }
        pickSingleVideo.launch("video/*")
    }

    /** File info card: name + size + duration + resolution. */
    private fun fileInfoCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CFD8DC"))
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val name = TextView(this).apply {
            text = inputName
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        val p = probeInfo
        val details = StringBuilder(FileUtils.formatSize(inputSize))
        if (p != null) {
            if (p.durationMs > 0) details.append("  •  ").append(formatDuration(p.durationMs))
            if (p.width > 0 && p.height > 0) details.append("  •  ${p.width}×${p.height}")
            if (!p.hasAudio) details.append("  •  no audio")
        }
        val info = TextView(this).apply {
            text = details.toString()
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
        }
        card.addView(name)
        card.addView(info)
        return card
    }

    /** Quick Compress top (user 2026-11-24): SMALL preview on the
     * LEFT + video info beside it (name, size, duration,
     * resolution). Tap the preview to play / pause it. */
    private fun quickHeaderCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CFD8DC"))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val preview = ExoVideoView(this).apply {
            // Square thumbnail (user 2026-11-24): video center-crops
            // to fill the square, professional — never stretched.
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        inputUri?.let { preview.setVideoURI(it) }
        // In-app playback (user 2026-11-24): tap toggles play /
        // pause inside Hakunaa — no external app, no error dialog.
        preview.onPrepared = {
            try {
                preview.seekTo(1000)
            } catch (_: Throwable) {
            }
        }
        preview.onError = {
            toast("Couldn't play this video")
        }
        preview.setOnClickListener {
            val uri = inputUri ?: return@setOnClickListener
            showFullScreenPlayer(uri)
        }
        activeVideoViews.add(preview)
        card.addView(preview)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(12) }
        }
        val name = TextView(this).apply {
            text = inputName
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val p = probeInfo
        val details = StringBuilder(FileUtils.formatSize(inputSize))
        if (p != null) {
            if (p.durationMs > 0) details.append("  •  ").append(formatDuration(p.durationMs))
            if (p.width > 0 && p.height > 0) details.append("  •  ${p.width}×${p.height}")
            if (!p.hasAudio) details.append("  •  no audio")
        }
        val meta = TextView(this).apply {
            text = details.toString()
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val hint = TextView(this).apply {
            text = "Tap preview to open"
            textSize = 12f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        info.addView(name)
        info.addView(meta)
        info.addView(hint)
        card.addView(info)
        return card
    }

    private data class ProgressViews(
        val progress: LinearProgressIndicator,
        val status: TextView,
        val result: TextView
    )

    private fun progressViews(): ProgressViews {
        val progress = LinearProgressIndicator(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val status = TextView(this).apply {
            text = "Ready"
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val result = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_success))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        return ProgressViews(progress, status, result)
    }

    private fun addProgressViews(content: LinearLayout): ProgressViews {
        val pv = progressViews()
        content.addView(pv.progress)
        content.addView(pv.status)
        content.addView(pv.result)
        return pv
    }

    private fun engineGuard(): Boolean {
        if (!VideoCompressor.isEngineAvailable()) {
            toast("Video engine couldn't start on this phone. Please restart the app and try again.")
            return false
        }
        return true
    }

    // ------------------------------------------------------------------
    // Professional Saved popup for videos (same design as the image
    // SavedDialog: green check, file name, location/size/format,
    // Open / Share / Done). Videos have no thumbnail file — the
    // preview frame is shown when one was captured.
    // ------------------------------------------------------------------

    private fun showSavedVideoDialog(
        outFile: File,
        savedUri: Uri?,
        location: String,
        formatLabel: String,
        mime: String,
        thumbBitmap: Bitmap? = null,
        onDone: (() -> Unit)? = null
    ) {
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
                setColor(getColor(R.color.hakunaa_success))
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
                (160 * density).toInt(), (90 * density).toInt()
            ).apply { topMargin = (16 * density).toInt() }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(getColor(R.color.hakunaa_outline))
                cornerRadius = 12f * density
            }
            clipToOutline = true
            visibility = if (thumbBitmap != null) View.VISIBLE else View.GONE
        }
        if (thumbBitmap != null) thumb.setImageBitmap(thumbBitmap)
        val name = TextView(this).apply {
            text = outFile.name
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, 0)
        }
        val details = TextView(this).apply {
            text = "$location  •  ${FileUtils.formatSize(outFile.length())}  •  $formatLabel"
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
        fun actionBtn(text: String, filled: Boolean): TextView {
            return popupPillButton(text, filled) {}.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(48)
                ).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
                setPadding(dp(20), 0, dp(20), 0)
            }
        }
        val openBtn = actionBtn("Open", false)
        val shareBtn = actionBtn("Share", false)
        val doneBtn = actionBtn("Done", true)
        openBtn.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(savedUri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            } catch (_: Throwable) {
                toast("No app can open this file")
            }
            dialog.dismiss()
        }
        shareBtn.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, savedUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share"))
            } catch (_: Throwable) {
                toast("Couldn't share")
            }
            dialog.dismiss()
        }
        doneBtn.setOnClickListener {
            dialog.dismiss()
            onDone?.invoke()
        }
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
    }

    private fun saveVideoAndShow(
        outFile: File,
        kindLabel: String,
        formatLabel: String,
        mime: String,
        kind: MediaStoreSaver.Kind = MediaStoreSaver.Kind.VIDEO,
        thumbBitmap: Bitmap? = null,
        onDone: (() -> Unit)? = null
    ) {
        val saved = MediaStoreSaver.save(this, outFile, kind, outFile.name, mime)
        val location = when (kind) {
            MediaStoreSaver.Kind.VIDEO -> "Movies/Hakunaa"
            MediaStoreSaver.Kind.AUDIO -> "Music/Hakunaa"
            MediaStoreSaver.Kind.IMAGE -> "Pictures/Hakunaa"
            else -> "Hakunaa"
        }
        if (saved != null) {
            showSavedVideoDialog(outFile, saved, location, formatLabel, mime, thumbBitmap, onDone)
        } else {
            toast("Done, but couldn't save — please try again")
            onDone?.invoke()
        }
    }

    override fun onDestroy() {
        closeFullScreenPlayer()
        VideoCompressor.cancel()
        VideoToolsEngine.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Compress screen — Quick / Smart / Advanced tabs, same picked
    // video. One overlay, tabs rebuild the options area.
    // ------------------------------------------------------------------

    private var activeVideoViews = mutableListOf<ExoVideoView>()
    private var smartPreviewView: ExoVideoView? = null

    // Full-screen player (user 2026-11-24): every Play / preview
    // opens this professional full-screen player.
    private var fullScreenRoot: ViewGroup? = null
    private var fullScreenPlayer: ExoVideoView? = null
    private var fullScreenHandler: Handler? = null
    private var fullScreenTick: Runnable? = null

    private fun showCompressScreen(tab: Int) {
        compressTab = tab
        smartRunning = false
        // Header a little higher (user 2026-11-24): top extra
        // 16dp -> 8dp.
        val content = newToolOverlay("Compress Video", 8)
        // Tabs — Quick has NO tabs (user 2026-11-24: remove the
        // Quick / Smart / Advanced buttons from Quick Compress).
        // Smart / Advanced keep them for now.
        if (compressTab != 0) {
            val tabs = chipRow(listOf("Quick", "Smart", "Advanced"), compressTab) { i ->
                compressTab = i
                showCompressScreen(i)
            }
            content.addView(tabs)
        }
        // Top — Quick gets a small left preview + info beside it;
        // Smart / Advanced keep the full-width info card.
        if (compressTab == 0) {
            content.addView(quickHeaderCard())
        } else {
            content.addView(fileInfoCard())
        }
        val optionsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val actionBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(optionsBox)
        content.addView(actionBox)
        val pv = addProgressViews(content)
        // Quick: no "Ready" status line (user 2026-11-24) — the
        // Compress button is the last thing on the screen.
        if (compressTab == 0) {
            pv.status.visibility = View.GONE
            pv.result.visibility = View.GONE
        }
        val resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        content.addView(resultBox)
        when (compressTab) {
            0 -> buildQuickOptions(optionsBox, actionBox, pv, resultBox)
            1 -> buildSmartOptions(optionsBox, actionBox, pv, resultBox)
            else -> buildAdvancedOptions(optionsBox, actionBox, pv, resultBox)
        }
    }

    private fun codecChips(selected: VideoCompressor.Codec, onSelect: (VideoCompressor.Codec) -> Unit): LinearLayout {
        val h265Ok = VideoCompressor.isPlaybackSupported("video/hevc")
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val h264 = chip("H.264", selected == VideoCompressor.Codec.H264) {}
        val h265 = chip(if (h265Ok) "H.265" else "H.265 (not supported)", selected == VideoCompressor.Codec.H265) {}
        // Listeners set AFTER both chips exist (kotlinc forward
        // references fail — a lambda may not reference a view
        // declared later).
        h264.setOnClickListener {
            onSelect(VideoCompressor.Codec.H264)
            setChipSelected(h264, true)
            setChipSelected(h265, false)
        }
        h265.setOnClickListener {
            if (!h265Ok) return@setOnClickListener
            onSelect(VideoCompressor.Codec.H265)
            setChipSelected(h264, false)
            setChipSelected(h265, true)
        }
        if (!h265Ok) {
            h265.alpha = 0.5f
        }
        for (c in listOf(h264, h265)) {
            c.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            row.addView(c)
        }
        return row
    }

    private fun resolutionChips(selected: VideoCompressor.Resolution, onSelect: (VideoCompressor.Resolution) -> Unit): LinearLayout {
        val opts = listOf(
            "Original" to VideoCompressor.Resolution.ORIGINAL,
            "1080p" to VideoCompressor.Resolution.P1080,
            "720p" to VideoCompressor.Resolution.P720,
            "480p" to VideoCompressor.Resolution.P480
        )
        return chipRow(opts.map { it.first }, opts.indexOfFirst { it.second == selected }) { i ->
            onSelect(opts[i].second)
        }
    }

    // ---- Quick ----

    private fun buildQuickOptions(
        optionsBox: LinearLayout,
        actionBox: LinearLayout,
        pv: ProgressViews,
        resultBox: LinearLayout
    ) {
        optionsBox.addView(sectionLabel("QUALITY"))
        val qualities = listOf(
            Triple("High", "CRF 23  •  20–40% smaller", 23),
            Triple("Balanced", "CRF 28  •  40–60% smaller", 28),
            Triple("Low", "CRF 33  •  60–75% smaller", 33),
            Triple("Lowest", "CRF 38  •  75–90% smaller", 38)
        )
        val rows = mutableListOf<LinearLayout>()
        for ((name, detail, crf) in qualities) {
            val row = optionRow(name, detail, crf == quickCrf) {
                quickCrf = crf
                for ((i, r) in rows.withIndex()) setOptionRowSelected(r, qualities[i].third == quickCrf)
            }
            rows.add(row)
            optionsBox.addView(row)
        }
        optionsBox.addView(sectionLabel("RESOLUTION"))
        optionsBox.addView(resolutionChips(quickRes) { quickRes = it })
        optionsBox.addView(sectionLabel("CODEC"))
        optionsBox.addView(codecChips(quickCodec) { quickCodec = it })
        // Instructions (user 2026-11-24) — SMALL and simple,
        // above the Compress button, where the 4K note was.
        optionsBox.addView(sectionLabel("INSTRUCTIONS"))
        val lines = listOf(
            "Pick a quality, resolution and codec, then press Compress.",
            "4K videos are reduced to 1080p automatically.",
            "Preview the result, then press Save."
        )
        for (line in lines) {
            val tv = TextView(this).apply {
                text = "•  $line"
                textSize = 13f
                setTextColor(getColor(R.color.hakunaa_text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            optionsBox.addView(tv)
        }
        val btn = actionButton("Compress") { runQuick(pv, resultBox) }
        actionBox.addView(btn)
    }

    /** Professional compress loading popup (Quick, user 2026-11-24):
     * title + status + progress bar + percent. Non-cancelable
     * while compressing. */
    private class CompressLoadingDialog(
        val dialog: Dialog,
        val progress: LinearProgressIndicator,
        val percent: TextView
    )

    private fun showCompressLoadingDialog(statusText: String, titleText: String = "Compressing…"): CompressLoadingDialog {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
            }
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val title = TextView(this).apply {
            text = titleText
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
        }
        val status = TextView(this).apply {
            text = statusText
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val progress = LinearProgressIndicator(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        val percent = TextView(this).apply {
            text = "0%"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(pinkDark)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        card.addView(title)
        card.addView(status)
        card.addView(progress)
        card.addView(percent)
        outer.addView(card)
        dialog.setContentView(outer)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
        return CompressLoadingDialog(dialog, progress, percent)
    }

    private fun runQuick(pv: ProgressViews, resultBox: LinearLayout) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        resultBox.removeAllViews()
        val out = File(cacheDir, "hakunaa_quick_${System.currentTimeMillis()}.mp4")
        val loading = showCompressLoadingDialog(
            "CRF $quickCrf  •  ${quickCodec.name}  •  ${quickRes.label}"
        )
        // compressBest: the existing ladder — tries the user's
        // settings first, escalates only if the result is not
        // smaller. Never hands back a bigger file.
        // (Algorithm untouched — only the UI around it changed.)
        VideoCompressor.compressBest(
            input.absolutePath, out.absolutePath, inputSize, quickCrf, quickCodec, quickRes,
            onProgress = { p ->
                runOnUiThread {
                    loading.progress.progress = p
                    loading.percent.text = "$p%"
                }
            },
            onDone = { ok, note ->
                runOnUiThread {
                    try {
                        loading.dialog.dismiss()
                    } catch (_: Throwable) {
                    }
                    if (ok) {
                        // No direct save — result screen first.
                        showQuickResult(out)
                    } else {
                        pv.status.text = "Failed: $note"
                        toast("Compress failed: $note")
                    }
                }
            }
        )
    }

    /** Quick result — NEW screen, NO direct save (user 2026-11-24).
     * Small top preview + saved size + Play preview. Save saves
     * (then the professional Saved popup); Back returns to the
     * Quick options with the same video and settings. */
    private fun showQuickResult(out: File) {
        val after = out.length()
        val content = newToolOverlay("Result")
        val preview = ExoVideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { topMargin = dp(12) }
        }
        preview.setVideoURI(Uri.fromFile(out))
        content.addView(preview)
        activeVideoViews.add(preview)
        val playBtn = popupPillButton("▶  Play preview", false) {
            showFullScreenPlayer(Uri.fromFile(out))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(8) }
        }
        content.addView(playBtn)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(pinkCard)
                setStroke(dp(2), pinkBorder)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val sizes = TextView(this).apply {
            text = "${FileUtils.formatSize(inputSize)}  →  ${FileUtils.formatSize(after)}"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
        }
        val saved = TextView(this).apply {
            text = FileUtils.savedPercent(inputSize, after)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_success))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val detail = TextView(this).apply {
            text = "CRF $quickCrf  •  ${quickCodec.name}  •  ${quickRes.label}"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        card.addView(sizes)
        card.addView(saved)
        card.addView(detail)
        content.addView(card)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        val backBtn = popupPillButton("Back", false) {
            showCompressScreen(0)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
                .apply { marginEnd = dp(6) }
        }
        val saveBtn = popupPillButton("Save", true) {
            saveVideoAndShow(out, "Movies/Hakunaa", "MP4", "video/mp4")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
                .apply { marginStart = dp(6) }
        }
        bottom.addView(backBtn)
        bottom.addView(saveBtn)
        content.addView(bottom)
    }

    // ---- Smart ----

    private fun buildSmartOptions(
        optionsBox: LinearLayout,
        actionBox: LinearLayout,
        pv: ProgressViews,
        resultBox: LinearLayout
    ) {
        val explain = TextView(this).apply {
            text = "Smart makes 3 versions of your video — 1080p, 720p and 480p — " +
                "and shows their real sizes. Pick the best one to save."
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        optionsBox.addView(explain)
        // Top video preview (input).
        val preview = ExoVideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200)
            ).apply { topMargin = dp(12) }
        }
        inputUri?.let { preview.setVideoURI(it) }
        optionsBox.addView(preview)
        smartPreviewView = preview
        activeVideoViews.add(preview)
        val playPreview = popupPillButton("▶  Play preview", false) {
            inputUri?.let { showFullScreenPlayer(it) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(8) }
        }
        optionsBox.addView(playPreview)
        val btn = actionButton("Analyze & Compress") { runSmart(pv, resultBox) }
        actionBox.addView(btn)
        // If results already exist (tab switch), show them again.
        if (smartResults.isNotEmpty()) showSmartResults(pv, resultBox)
    }

    private fun runSmart(pv: ProgressViews, resultBox: LinearLayout) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        if (smartRunning) return
        smartRunning = true
        smartResults.clear()
        smartSelected = -1
        resultBox.removeAllViews()
        pv.progress.visibility = View.VISIBLE
        pv.progress.progress = 0
        pv.result.text = ""
        val durationMs = probeInfo?.durationMs ?: 0L
        val hasAudio = probeInfo?.hasAudio ?: true
        val specs = listOf(
            Triple("1080p", 23, VideoCompressor.Resolution.P1080),
            Triple("720p", 26, VideoCompressor.Resolution.P720),
            Triple("480p", 28, VideoCompressor.Resolution.P480)
        )
        val made = mutableListOf<Triple<String, File, Long>>()
        fun runOne(index: Int) {
            if (index >= specs.size) {
                smartRunning = false
                pv.progress.visibility = View.GONE
                for ((label, file, size) in made) {
                    val savedPct = if (inputSize > 0) ((1.0 - size.toDouble() / inputSize.toDouble()) * 100.0).toInt() else 0
                    smartResults.add(SmartResult(label, file, size, savedPct, false))
                }
                if (smartResults.isEmpty()) {
                    pv.status.text = "Failed — couldn't make any version"
                    return
                }
                // Best = highest resolution that saves at least 40%;
                // otherwise the smallest file.
                var bestIdx = -1
                for (i in smartResults.indices) {
                    if (smartResults[i].savedPct >= 40) {
                        bestIdx = i
                        break
                    }
                }
                if (bestIdx < 0) {
                    var smallest = Long.MAX_VALUE
                    for (i in smartResults.indices) {
                        if (smartResults[i].size < smallest) {
                            smallest = smartResults[i].size
                            bestIdx = i
                        }
                    }
                }
                smartResults = smartResults.mapIndexed { i, r ->
                    r.copy(isBest = i == bestIdx)
                }.toMutableList()
                smartSelected = bestIdx
                pv.status.text = "Done — ${smartResults.size} versions ready"
                showSmartResults(pv, resultBox)
                return
            }
            val spec = specs[index]
            val label = spec.first
            val crf = spec.second
            val res = spec.third
            pv.status.text = "Making $label version… (${index + 1}/3)"
            val out = File(cacheDir, "hakunaa_smart_${label}_${System.currentTimeMillis()}.mp4")
            val argv = VideoCompressor.buildCommand(
                input.absolutePath, out.absolutePath, crf,
                VideoCompressor.Codec.H264, Presets.VIDEO_PRESET, hasAudio, res
            )
            VideoToolsEngine.runSession(
                argv, durationMs,
                { p -> runOnUiThread { pv.progress.progress = p } },
                { ok, _ ->
                    runOnUiThread {
                        if (ok && out.exists() && out.length() > 0) {
                            made.add(Triple(label, out, out.length()))
                        } else {
                            try {
                                out.delete()
                            } catch (_: Throwable) {
                            }
                        }
                        runOne(index + 1)
                    }
                }
            )
        }
        runOne(0)
    }

    private fun showSmartResults(pv: ProgressViews, resultBox: LinearLayout) {
        resultBox.removeAllViews()
        val rows = mutableListOf<LinearLayout>()
        for ((i, r) in smartResults.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            setSmartRowSelected(row, i == smartSelected)
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val label = TextView(this).apply {
                text = "${r.label}  •  ${FileUtils.formatSize(r.size)}"
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(getColor(R.color.hakunaa_text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            top.addView(label)
            if (r.isBest) {
                val best = TextView(this).apply {
                    text = "BEST"
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        setColor(getColor(R.color.hakunaa_success))
                    }
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                }
                top.addView(best)
            }
            val check = TextView(this).apply {
                text = if (i == smartSelected) "✓" else ""
                textSize = 20f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(pinkDark)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            }
            top.addView(check)
            row.addView(top)
            val bottom = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            val saved = TextView(this).apply {
                text = if (r.savedPct >= 0) "${r.savedPct}% smaller" else "${-r.savedPct}% larger"
                textSize = 13f
                setTextColor(
                    if (r.savedPct >= 0) getColor(R.color.hakunaa_success)
                    else getColor(R.color.hakunaa_text_secondary)
                )
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val play = popupPillButton("▶ Play", false) {
                showFullScreenPlayer(Uri.fromFile(r.file))
            }.apply {
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(40))
            }
            bottom.addView(saved)
            bottom.addView(play)
            row.addView(bottom)
            row.setOnClickListener {
                smartSelected = i
                for ((j, rr) in rows.withIndex()) {
                    setSmartRowSelected(rr, j == smartSelected)
                    (rr.tag as? TextView)?.text = if (j == smartSelected) "✓" else ""
                }
            }
            row.tag = check
            rows.add(row)
            resultBox.addView(row)
        }
        val saveBtn = actionButton("Save selected") {
            val sel = smartResults.getOrNull(smartSelected) ?: return@actionButton
            pv.status.text = "Saving ${sel.label} version…"
            saveVideoAndShow(sel.file, "Movies/Hakunaa", "MP4", "video/mp4")
        }
        resultBox.addView(saveBtn)
        val doneBtn = popupPillButton("Done", false) {
            dismissToolOverlay()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(12) }
        }
        resultBox.addView(doneBtn)
    }

    private fun setSmartRowSelected(row: LinearLayout, selected: Boolean) {
        row.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            if (selected) {
                setColor(pinkCard)
                setStroke(dp(2), pinkBorder)
            } else {
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CFD8DC"))
            }
        }
    }

    // ---- Advanced ----

    private fun buildAdvancedOptions(
        optionsBox: LinearLayout,
        actionBox: LinearLayout,
        pv: ProgressViews,
        resultBox: LinearLayout
    ) {
        optionsBox.addView(sectionLabel("QUALITY (CRF)"))
        val crfRow = sliderRow("CRF", 20, advCrf - 18, { p -> "CRF ${p + 18}  •  lower = better quality" }) { p ->
            advCrf = p + 18
        }
        optionsBox.addView(crfRow)
        optionsBox.addView(sectionLabel("MODE"))
        val modeRow = chipRow(listOf("CRF mode", "Bitrate mode"), if (advUseBitrate) 1 else 0) { i ->
            advUseBitrate = i == 1
        }
        optionsBox.addView(modeRow)
        val bitrateBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val bitrateInput = EditText(this).apply {
            setText(advBitrateMbps.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 15f
            setTextColor(getColor(R.color.hakunaa_text))
            background = inputBoxBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val bitrateLabel = TextView(this).apply {
            text = "Mbps  •  two-pass uses bitrate mode"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        bitrateBox.addView(bitrateInput)
        bitrateBox.addView(bitrateLabel)
        optionsBox.addView(bitrateBox)
        // Two-pass toggle chip.
        val twoPassChip = chip("Two-pass encoding", advTwoPass) {}
        twoPassChip.setOnClickListener {
            advTwoPass = !advTwoPass
            setChipSelected(twoPassChip, advTwoPass)
        }
        twoPassChip.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
        optionsBox.addView(twoPassChip)
        val twoPassNote = TextView(this).apply {
            text = "Two-pass: ~5–10% better quality at the same size, but takes about twice as long."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        optionsBox.addView(twoPassNote)
        optionsBox.addView(sectionLabel("RESOLUTION"))
        optionsBox.addView(resolutionChips(advRes) { advRes = it })
        optionsBox.addView(sectionLabel("FRAME RATE"))
        val fpsOpts = listOf("Original" to 0, "60" to 60, "30" to 30, "24" to 24)
        optionsBox.addView(chipRow(fpsOpts.map { it.first }, fpsOpts.indexOfFirst { it.second == advFps }) { i ->
            advFps = fpsOpts[i].second
        })
        optionsBox.addView(sectionLabel("CODEC"))
        optionsBox.addView(codecChips(advCodec) { advCodec = it })
        optionsBox.addView(sectionLabel("AUDIO"))
        val audioOpts = listOf("Keep audio" to true, "Remove audio" to false)
        optionsBox.addView(chipRow(audioOpts.map { it.first }, audioOpts.indexOfFirst { it.second == advAudioKeep }) { i ->
            advAudioKeep = audioOpts[i].second
        })
        val audioBitrateOpts = listOf("96k", "128k", "192k", "256k")
        optionsBox.addView(chipRow(audioBitrateOpts, audioBitrateOpts.indexOf(advAudioBitrate)) { i ->
            advAudioBitrate = audioBitrateOpts[i]
        })
        optionsBox.addView(sectionLabel("OUTPUT FORMAT"))
        val fmtOpts = listOf("MP4", "MKV", "MOV")
        optionsBox.addView(chipRow(fmtOpts, fmtOpts.indexOf(advFormat.uppercase())) { i ->
            advFormat = fmtOpts[i].lowercase()
        })
        optionsBox.addView(sectionLabel("ENCODING PRESET"))
        val presetOpts = listOf("ultrafast", "fast", "medium", "slow")
        optionsBox.addView(chipRow(presetOpts, presetOpts.indexOf(advPreset)) { i ->
            advPreset = presetOpts[i]
        })
        val presetNote = TextView(this).apply {
            text = "Preset: speed vs size. Slow is ~5–10% smaller at the same quality."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        optionsBox.addView(presetNote)
        optionsBox.addView(sectionLabel("ENHANCE"))
        val enhanceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val denoiseChip = chip("Denoise", advDenoise) {}
        denoiseChip.setOnClickListener {
            advDenoise = !advDenoise
            setChipSelected(denoiseChip, advDenoise)
        }
        val sharpenChip = chip("Sharpen", advSharpen) {}
        sharpenChip.setOnClickListener {
            advSharpen = !advSharpen
            setChipSelected(sharpenChip, advSharpen)
        }
        for (c in listOf(denoiseChip, sharpenChip)) {
            c.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            enhanceRow.addView(c)
        }
        optionsBox.addView(enhanceRow)
        val enhanceNote = TextView(this).apply {
            text = "Denoise cleans low-light grain. Sharpen restores crispness after compression."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        optionsBox.addView(enhanceNote)
        val btn = actionButton("Compress") {
            val br = bitrateInput.text.toString().toIntOrNull() ?: 8
            advBitrateMbps = br.coerceIn(1, 50)
            runAdvanced(pv, resultBox)
        }
        actionBox.addView(btn)
    }

    private fun runAdvanced(pv: ProgressViews, resultBox: LinearLayout) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        pv.progress.visibility = View.VISIBLE
        pv.progress.progress = 0
        val modeText = if (advUseBitrate) "${advBitrateMbps} Mbps" else "CRF $advCrf"
        pv.status.text = "Compressing… $modeText, ${advCodec.name}, ${advRes.label}, ${advFormat.uppercase()}"
        pv.result.text = ""
        resultBox.removeAllViews()
        val opts = VideoToolsEngine.AdvancedOptions(
            advCrf, advUseBitrate, advBitrateMbps, advRes, advFps, advCodec,
            advAudioKeep, advAudioBitrate, advTwoPass, advFormat, advPreset,
            advDenoise, advSharpen
        )
        val ext = advFormat.lowercase()
        val out = File(cacheDir, "hakunaa_adv_${System.currentTimeMillis()}.$ext")
        VideoToolsEngine.advanced(
            input.absolutePath, out.absolutePath, opts, cacheDir,
            { p -> runOnUiThread { pv.progress.progress = p } },
            { ok, msg ->
                runOnUiThread {
                    pv.progress.visibility = View.GONE
                    if (ok && out.exists() && out.length() > 0) {
                        val after = out.length()
                        pv.status.text = "Done — saved to Movies/Hakunaa"
                        pv.result.text = "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(after)} " +
                            "(${FileUtils.savedPercent(inputSize, after)})"
                        val mime = when (ext) {
                            "mkv" -> "video/x-matroska"
                            "mov" -> "video/quicktime"
                            else -> "video/mp4"
                        }
                        saveVideoAndShow(out, "Movies/Hakunaa", ext.uppercase(), mime)
                    } else {
                        pv.status.text = "Failed: $msg"
                    }
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // Convert — 12 formats (user 2026-11-24: MTS removed, most-used
    // order, preview + format badge on top, dynamic details box,
    // result screen — no direct save).
    // ------------------------------------------------------------------

    private var convertFormat = VideoToolsEngine.ConvertFormat.MP4
    private var convertRes = VideoCompressor.Resolution.ORIGINAL

    /** Convert top: square preview on the LEFT + video info beside
     * it + a small badge showing the CURRENT (input) format, so it
     * is clear at a glance. Tap the preview for the full-screen
     * player. */
    private fun convertHeaderCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CFD8DC"))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val preview = ExoVideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        }
        inputUri?.let { preview.setVideoURI(it) }
        preview.onPrepared = {
            try {
                preview.seekTo(1000)
            } catch (_: Throwable) {
            }
        }
        preview.onError = {
            toast("Couldn't play this video")
        }
        preview.setOnClickListener {
            val uri = inputUri ?: return@setOnClickListener
            showFullScreenPlayer(uri)
        }
        activeVideoViews.add(preview)
        card.addView(preview)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(12) }
        }
        val name = TextView(this).apply {
            text = inputName
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val p = probeInfo
        val details = StringBuilder(FileUtils.formatSize(inputSize))
        if (p != null) {
            if (p.durationMs > 0) details.append("  •  ").append(formatDuration(p.durationMs))
            if (p.width > 0 && p.height > 0) details.append("  •  ${p.width}×${p.height}")
            if (!p.hasAudio) details.append("  •  no audio")
        }
        val meta = TextView(this).apply {
            text = details.toString()
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        // Current (input) format badge — LIGHT + professional
        // (user 2026-11-24: no solid pink box). White pill with a
        // thin pink outline + pink text.
        val ext = inputName.substringAfterLast('.', "").uppercase().ifEmpty { "VIDEO" }
        val badge = TextView(this).apply {
            text = ext
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(pinkDark)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), pinkBorder)
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        info.addView(name)
        info.addView(meta)
        info.addView(badge)
        card.addView(info)
        return card
    }

    private fun showConvertScreen() {
        val content = newToolOverlay("Convert Video")
        content.addView(convertHeaderCard())
        content.addView(sectionLabel("CONVERT TO"))
        val convertBtn = actionButton("Convert to ${convertFormat.label}") {
            runConvert()
        }
        // Details box — shows the selected format's spec + where it
        // plays. Updates live when a format chip is tapped.
        val detailsTitle = TextView(this).apply {
            text = convertFormat.label
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        val detailsText = TextView(this).apply {
            text = convertFormat.details
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val detailsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(pinkCard)
                setStroke(dp(2), pinkBorder)
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        detailsBox.addView(detailsTitle)
        detailsBox.addView(detailsText)
        val grid = GridLayout(this).apply {
            columnCount = 4
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val fmtChips = mutableListOf<TextView>()
        val formats = VideoToolsEngine.ConvertFormat.values()
        for (fmt in formats) {
            val c = chip(fmt.label, fmt == convertFormat) {
                convertFormat = fmt
                for ((j, cc) in fmtChips.withIndex()) setChipSelected(cc, formats[j] == convertFormat)
                convertBtn.text = "Convert to ${convertFormat.label}"
                detailsTitle.text = fmt.label
                detailsText.text = fmt.details
            }
            val lp = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            c.layoutParams = lp
            fmtChips.add(c)
            grid.addView(c)
        }
        content.addView(grid)
        content.addView(detailsBox)
        content.addView(sectionLabel("RESOLUTION"))
        content.addView(resolutionChips(convertRes) { convertRes = it })
        // Convert button PINNED to the bottom (user 2026-11-25):
        // added to the overlay root, below the ScrollView, NOT to
        // the scroll content. The middle space stays empty for now.
        (toolOverlay as? LinearLayout)?.addView(convertBtn)
    }

    private fun runConvert() {
        val input = inputFile ?: return
        if (!engineGuard()) return
        val fmt = convertFormat
        val out = File(cacheDir, "hakunaa_convert_${System.currentTimeMillis()}.${fmt.extension}")
        val loading = showCompressLoadingDialog(
            "To ${fmt.label}  •  ${convertRes.label}", "Converting…"
        )
        VideoToolsEngine.convert12(
            input.absolutePath, out.absolutePath, fmt, convertRes,
            { p -> runOnUiThread { loading.progress.progress = p; loading.percent.text = "$p%" } },
            { ok, msg ->
                runOnUiThread {
                    try {
                        loading.dialog.dismiss()
                    } catch (_: Throwable) {
                    }
                    if (ok && out.exists() && out.length() > 0) {
                        // No direct save — NEW result screen first
                        // (user 2026-11-25: process + result in a
                        // new screen like before).
                        showConvertResult(out, fmt)
                    } else {
                        toast("Convert failed: $msg")
                    }
                }
            }
        )
    }

    /** Convert result — NEW screen, NO direct save (user 2026-11-24).
     * Small top preview + saved size + Play preview. Save saves
     * (then the professional Saved popup); Back returns to the
     * Convert options with the same video and settings. */
    private fun showConvertResult(out: File, fmt: VideoToolsEngine.ConvertFormat) {
        val after = out.length()
        val content = newToolOverlay("Result")
        val preview = ExoVideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { topMargin = dp(12) }
        }
        preview.setVideoURI(Uri.fromFile(out))
        content.addView(preview)
        activeVideoViews.add(preview)
        val playBtn = popupPillButton("▶  Play preview", false) {
            showFullScreenPlayer(Uri.fromFile(out))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(8) }
        }
        content.addView(playBtn)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(pinkCard)
                setStroke(dp(2), pinkBorder)
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val sizes = TextView(this).apply {
            text = "${FileUtils.formatSize(inputSize)}  →  ${FileUtils.formatSize(after)}"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
        }
        val saved = TextView(this).apply {
            text = FileUtils.savedPercent(inputSize, after)
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_success))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val detail = TextView(this).apply {
            text = "${fmt.label}  •  ${convertRes.label}"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        card.addView(sizes)
        card.addView(saved)
        card.addView(detail)
        content.addView(card)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        val backBtn = popupPillButton("Back", false) {
            showConvertScreen()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
                .apply { marginEnd = dp(6) }
        }
        val saveBtn = popupPillButton("Save", true) {
            if (fmt.isGif) {
                saveVideoAndShow(out, "Pictures/Hakunaa", "GIF", fmt.mime, MediaStoreSaver.Kind.IMAGE)
            } else {
                saveVideoAndShow(out, "Movies/Hakunaa", fmt.label, fmt.mime)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
                .apply { marginStart = dp(6) }
        }
        bottom.addView(backBtn)
        bottom.addView(saveBtn)
        content.addView(bottom)
    }

    // ------------------------------------------------------------------
    // Rotate.
    // ------------------------------------------------------------------

    private var rotateMode = VideoCompressor.RotateMode.CW90

    private fun showRotateScreen() {
        val content = newToolOverlay("Rotate Video")
        content.addView(fileInfoCard())
        content.addView(sectionLabel("ROTATE"))
        val modes = listOf(
            "90° Clockwise" to VideoCompressor.RotateMode.CW90,
            "90° Counter-clockwise" to VideoCompressor.RotateMode.CCW90,
            "180°" to VideoCompressor.RotateMode.ROT180,
            "Flip Horizontal" to VideoCompressor.RotateMode.FLIP_H,
            "Flip Vertical" to VideoCompressor.RotateMode.FLIP_V
        )
        val rows = mutableListOf<LinearLayout>()
        for ((label, mode) in modes) {
            val row = optionRow(label, mode.label, mode == rotateMode) {
                rotateMode = mode
                for ((i, r) in rows.withIndex()) setOptionRowSelected(r, modes[i].second == rotateMode)
            }
            rows.add(row)
            content.addView(row)
        }
        val pv = addProgressViews(content)
        val btn = actionButton("Rotate") { runRotate(pv) }
        content.addView(btn)
    }

    private fun runRotate(pv: ProgressViews) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        pv.progress.visibility = View.VISIBLE
        pv.progress.progress = 0
        pv.status.text = "Rotating video… ${rotateMode.label}"
        pv.result.text = ""
        val out = File(cacheDir, "hakunaa_rotated_${System.currentTimeMillis()}.mp4")
        VideoCompressor.rotate(
            input.absolutePath, out.absolutePath, rotateMode,
            { p -> runOnUiThread { pv.progress.progress = p } },
            { ok, msg ->
                runOnUiThread {
                    pv.progress.visibility = View.GONE
                    if (ok && out.exists() && out.length() > 0) {
                        pv.status.text = "Done — saved to Movies/Hakunaa"
                        pv.result.text = "Rotated ${rotateMode.label}: ${FileUtils.formatSize(out.length())}"
                        saveVideoAndShow(out, "Movies/Hakunaa", "MP4", "video/mp4")
                    } else {
                        pv.status.text = "Failed: $msg"
                    }
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // Video to Audio — 5 formats.
    // ------------------------------------------------------------------

    private var audioFormat = VideoToolsEngine.AudioFormat.MP3

    private fun showAudioScreen() {
        val content = newToolOverlay("Video to Audio")
        content.addView(fileInfoCard())
        content.addView(sectionLabel("AUDIO FORMAT"))
        val fmts = VideoToolsEngine.AudioFormat.values()
        content.addView(chipRow(fmts.map { it.label }, fmts.indexOf(audioFormat)) { i ->
            audioFormat = fmts[i]
        })
        val note = TextView(this).apply {
            text = "MP3 plays everywhere. FLAC and WAV are lossless (bigger files)."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        content.addView(note)
        val pv = addProgressViews(content)
        val btn = actionButton("Extract Audio") { runAudio(pv) }
        content.addView(btn)
    }

    private fun runAudio(pv: ProgressViews) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        pv.progress.visibility = View.VISIBLE
        pv.progress.progress = 0
        pv.status.text = "Extracting ${audioFormat.label} audio…"
        pv.result.text = ""
        val fmt = audioFormat
        val out = File(cacheDir, "hakunaa_audio_${System.currentTimeMillis()}.${fmt.extension}")
        VideoToolsEngine.extractAudio(
            input.absolutePath, out.absolutePath, fmt,
            { p -> runOnUiThread { pv.progress.progress = p } },
            { ok, msg ->
                runOnUiThread {
                    pv.progress.visibility = View.GONE
                    if (ok && out.exists() && out.length() > 0) {
                        pv.status.text = "Done — saved to Music/Hakunaa"
                        pv.result.text = "Audio extracted: ${FileUtils.formatSize(out.length())} ${fmt.label}"
                        saveVideoAndShow(out, "Music/Hakunaa", fmt.label, fmt.mime, MediaStoreSaver.Kind.AUDIO)
                    } else {
                        pv.status.text = "Failed: $msg"
                    }
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // Video Cutter — timeline + handles (SeekBar + Set Start/End),
    // stream copy (no re-encode). Splitter lives inside Cutter.
    // ------------------------------------------------------------------

    private var cutStartMs = 0L
    private var cutEndMs = 0L

    private fun showCutterScreen() {
        val content = newToolOverlay("Video Cutter")
        content.addView(fileInfoCard())
        val durationMs = probeInfo?.durationMs ?: 0L
        cutStartMs = 0L
        cutEndMs = durationMs
        val preview = ExoVideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200)
            ).apply { topMargin = dp(12) }
        }
        inputUri?.let { preview.setVideoURI(it) }
        content.addView(preview)
        activeVideoViews.add(preview)
        val seek = SeekBar(this).apply {
            max = durationMs.toInt().coerceAtLeast(1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        content.addView(seek)
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val tvStart = TextView(this).apply {
            text = "Start  00:00"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvEnd = TextView(this).apply {
            text = "End  ${formatClock(durationMs)}"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        timeRow.addView(tvStart)
        timeRow.addView(tvEnd)
        content.addView(timeRow)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    try {
                        preview.seekTo(p)
                    } catch (_: Throwable) {
                    }
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val playBtn = popupPillButton("▶  Play", false) {
            inputUri?.let { showFullScreenPlayer(it) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(8) }
        }
        content.addView(playBtn)
        val setRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val btnSetStart = popupPillButton("Set Start", false) {
            try {
                cutStartMs = preview.currentPosition.toLong()
                tvStart.text = "Start  ${formatClock(cutStartMs)}"
            } catch (_: Throwable) {
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(8)
            }
        }
        val btnSetEnd = popupPillButton("Set End", false) {
            try {
                cutEndMs = preview.currentPosition.toLong()
                tvEnd.text = "End  ${formatClock(cutEndMs)}"
            } catch (_: Throwable) {
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(8)
            }
        }
        setRow.addView(btnSetStart)
        setRow.addView(btnSetEnd)
        content.addView(setRow)
        val cutNote = TextView(this).apply {
            text = "Move the slider (or play) to a moment, then tap Set Start / Set End. " +
                "The cut is instant — the video is not re-encoded, so cuts land on keyframes."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        content.addView(cutNote)
        val pv = addProgressViews(content)
        val cutBtn = actionButton("Cut Clip") {
            if (cutEndMs <= cutStartMs) {
                toast("End must be after Start")
                return@actionButton
            }
            runCut(pv)
        }
        content.addView(cutBtn)
        // Splitter (inside Cutter).
        content.addView(sectionLabel("SPLIT INTO PARTS"))
        val splitNote = TextView(this).apply {
            text = "Split a long video into equal parts — useful for WhatsApp status and uploads."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        content.addView(splitNote)
        val splitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val splitInput = EditText(this).apply {
            setText("30")
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 15f
            setTextColor(getColor(R.color.hakunaa_text))
            background = inputBoxBackground()
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val splitLabel = TextView(this).apply {
            text = "seconds per part"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        }
        splitRow.addView(splitInput)
        splitRow.addView(splitLabel)
        content.addView(splitRow)
        val splitBtn = popupPillButton("Split Video", true) {
            val secs = splitInput.text.toString().toIntOrNull() ?: 30
            if (secs < 5) {
                toast("Each part must be at least 5 seconds")
                return@popupPillButton
            }
            runSplit(pv, secs)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(12) }
        }
        content.addView(splitBtn)
    }

    private fun runCut(pv: ProgressViews) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        pv.progress.visibility = View.VISIBLE
        pv.status.text = "Cutting clip ${formatClock(cutStartMs)} – ${formatClock(cutEndMs)}…"
        pv.result.text = ""
        val out = File(cacheDir, "hakunaa_cut_${System.currentTimeMillis()}.mp4")
        val durationMs = probeInfo?.durationMs ?: 0L
        VideoToolsEngine.cut(
            input.absolutePath, out.absolutePath, cutStartMs, cutEndMs, durationMs,
            { p -> runOnUiThread { pv.progress.progress = p } },
            { ok, msg ->
                runOnUiThread {
                    pv.progress.visibility = View.GONE
                    if (ok && out.exists() && out.length() > 0) {
                        pv.status.text = "Done — saved to Movies/Hakunaa"
                        pv.result.text = "Clip: ${FileUtils.formatSize(out.length())}"
                        saveVideoAndShow(out, "Movies/Hakunaa", "MP4", "video/mp4")
                    } else {
                        pv.status.text = "Failed: $msg"
                    }
                }
            }
        )
    }

    private fun runSplit(pv: ProgressViews, partSeconds: Int) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        pv.progress.visibility = View.VISIBLE
        pv.status.text = "Splitting into ${partSeconds}s parts…"
        pv.result.text = ""
        val stamp = System.currentTimeMillis()
        val pattern = File(cacheDir, "hakunaa_split_${stamp}_%03d.mp4").absolutePath
        val durationMs = probeInfo?.durationMs ?: 0L
        VideoToolsEngine.split(
            input.absolutePath, pattern, partSeconds, durationMs,
            { p -> runOnUiThread { pv.progress.progress = p } },
            { ok, msg ->
                runOnUiThread {
                    pv.progress.visibility = View.GONE
                    if (!ok) {
                        pv.status.text = "Failed: $msg"
                        return@runOnUiThread
                    }
                    val parts = cacheDir.listFiles { f ->
                        f.name.startsWith("hakunaa_split_${stamp}_") && f.name.endsWith(".mp4")
                    }?.sortedBy { it.name } ?: emptyList()
                    if (parts.isEmpty()) {
                        pv.status.text = "Failed — no parts were made"
                        return@runOnUiThread
                    }
                    var savedCount = 0
                    for (part in parts) {
                        val saved = MediaStoreSaver.save(this, part, MediaStoreSaver.Kind.VIDEO, part.name)
                        if (saved != null) savedCount++
                    }
                    pv.status.text = "Done — split into $savedCount parts, saved to Movies/Hakunaa"
                    pv.result.text = parts.joinToString("\n") { it.name + "  •  " + FileUtils.formatSize(it.length()) }
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // Custom Thumbnail — pick a frame, set it as the video cover.
    // ------------------------------------------------------------------

    private var thumbFrameFile: File? = null
    private var thumbFrameMs = 1000L

    private fun showThumbnailScreen() {
        val content = newToolOverlay("Custom Thumbnail")
        content.addView(fileInfoCard())
        val durationMs = probeInfo?.durationMs ?: 0L
        thumbFrameFile = null
        thumbFrameMs = (durationMs / 4).coerceAtLeast(0L)
        val preview = ExoVideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200)
            ).apply { topMargin = dp(12) }
        }
        inputUri?.let { preview.setVideoURI(it) }
        content.addView(preview)
        activeVideoViews.add(preview)
        val seek = SeekBar(this).apply {
            max = durationMs.toInt().coerceAtLeast(1)
            progress = thumbFrameMs.toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        content.addView(seek)
        val timeLabel = TextView(this).apply {
            text = "Frame at ${formatClock(thumbFrameMs)}"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        content.addView(timeLabel)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                thumbFrameMs = p.toLong()
                timeLabel.text = "Frame at ${formatClock(thumbFrameMs)}"
                if (fromUser) {
                    try {
                        preview.seekTo(p)
                    } catch (_: Throwable) {
                    }
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val playBtn = popupPillButton("▶  Play", false) {
            inputUri?.let { showFullScreenPlayer(it) }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(8) }
        }
        content.addView(playBtn)
        val framePreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { topMargin = dp(12) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(getColor(R.color.hakunaa_outline))
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
            visibility = View.GONE
        }
        content.addView(framePreview)
        val pv = addProgressViews(content)
        val captureBtn = popupPillButton("Capture This Frame", true) {
            runCaptureFrame(pv, framePreview)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(16) }
        }
        content.addView(captureBtn)
        val saveBtn = actionButton("Set as Cover & Save") {
            runAttachCover(pv)
        }
        content.addView(saveBtn)
        val note = TextView(this).apply {
            text = "Move the slider to the frame you want, capture it, then set it as the cover. " +
                "The cover shows in your gallery and video players."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        content.addView(note)
    }

    private fun runCaptureFrame(pv: ProgressViews, framePreview: ImageView) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        pv.status.text = "Capturing frame at ${formatClock(thumbFrameMs)}…"
        val frame = File(cacheDir, "hakunaa_frame_${System.currentTimeMillis()}.jpg")
        VideoToolsEngine.extractFrame(
            input.absolutePath, frame.absolutePath, thumbFrameMs
        ) { ok, msg ->
            runOnUiThread {
                if (ok && frame.exists() && frame.length() > 0) {
                    thumbFrameFile = frame
                    try {
                        val bmp = BitmapFactory.decodeFile(frame.absolutePath)
                        if (bmp != null) {
                            overlayBitmaps.add(bmp)
                            framePreview.setImageBitmap(bmp)
                            framePreview.visibility = View.VISIBLE
                        }
                    } catch (_: Throwable) {
                    }
                    pv.status.text = "Frame captured — tap Set as Cover & Save"
                } else {
                    pv.status.text = "Failed: $msg"
                }
            }
        }
    }

    private fun runAttachCover(pv: ProgressViews) {
        val input = inputFile ?: return
        val cover = thumbFrameFile
        if (cover == null || !cover.exists()) {
            toast("Capture a frame first")
            return
        }
        if (!engineGuard()) return
        pv.status.text = "Setting cover…"
        val out = File(cacheDir, "hakunaa_cover_${System.currentTimeMillis()}.mp4")
        VideoToolsEngine.attachCover(
            input.absolutePath, cover.absolutePath, out.absolutePath
        ) { ok, msg ->
            runOnUiThread {
                if (ok && out.exists() && out.length() > 0) {
                    pv.status.text = "Done — saved to Movies/Hakunaa"
                    pv.result.text = "Cover set: ${FileUtils.formatSize(out.length())}"
                    var thumbBmp: Bitmap? = null
                    try {
                        thumbBmp = BitmapFactory.decodeFile(cover.absolutePath)
                        if (thumbBmp != null) overlayBitmaps.add(thumbBmp)
                    } catch (_: Throwable) {
                    }
                    saveVideoAndShow(out, "Movies/Hakunaa", "MP4", "video/mp4", thumbBitmap = thumbBmp)
                } else {
                    pv.status.text = "Failed: $msg"
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Video Crop — aspect presets, centered crop.
    // ------------------------------------------------------------------

    private var cropAspect = "16:9"

    private fun cropDimsFor(aspect: String, w: Int, h: Int): IntArray? {
        if (w <= 0 || h <= 0) return null
        val parts = aspect.split(":")
        if (parts.size != 2) return null
        val aw = parts[0].toDoubleOrNull() ?: return null
        val ah = parts[1].toDoubleOrNull() ?: return null
        if (aw <= 0 || ah <= 0) return null
        val target = aw / ah
        val src = w.toDouble() / h.toDouble()
        val (cw, ch) = if (src > target) {
            // Too wide — trim the sides.
            val nh = h
            val nw = (h * target).toInt()
            nw to nh
        } else {
            // Too tall — trim top and bottom.
            val nw = w
            val nh = (w / target).toInt()
            nw to nh
        }
        // yuv420p needs even dimensions.
        val ew = (cw / 2) * 2
        val eh = (ch / 2) * 2
        if (ew <= 0 || eh <= 0) return null
        val x = ((w - ew) / 2 / 2) * 2
        val y = ((h - eh) / 2 / 2) * 2
        return intArrayOf(ew, eh, x, y)
    }

    private fun showCropScreen() {
        val content = newToolOverlay("Video Crop")
        content.addView(fileInfoCard())
        val p = probeInfo
        val w = p?.width ?: 0
        val h = p?.height ?: 0
        content.addView(sectionLabel("CROP TO"))
        val aspects = listOf("16:9", "1:1", "9:16", "4:3")
        val resultLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        fun updateResult() {
            if (w <= 0 || h <= 0) {
                resultLabel.text = "Couldn't read this video's size."
                return
            }
            val dims = cropDimsFor(cropAspect, w, h)
            if (dims == null) {
                resultLabel.text = "This video is already $cropAspect."
                return
            }
            resultLabel.text = "Result: ${dims[0]}×${dims[1]}  (from ${w}×${h}, centered)"
        }
        content.addView(chipRow(aspects, aspects.indexOf(cropAspect)) { i ->
            cropAspect = aspects[i]
            updateResult()
        })
        updateResult()
        content.addView(resultLabel)
        val note = TextView(this).apply {
            text = "The crop is centered. Audio is kept as-is. The video is re-encoded at CRF 28."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        content.addView(note)
        val pv = addProgressViews(content)
        val btn = actionButton("Crop & Save") { runCrop(pv) }
        content.addView(btn)
    }

    private fun runCrop(pv: ProgressViews) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        val p = probeInfo
        val w = p?.width ?: 0
        val h = p?.height ?: 0
        val dims = cropDimsFor(cropAspect, w, h)
        if (dims == null) {
            toast("This video is already $cropAspect")
            return
        }
        pv.progress.visibility = View.VISIBLE
        pv.progress.progress = 0
        pv.status.text = "Cropping to $cropAspect (${dims[0]}×${dims[1]})…"
        pv.result.text = ""
        val out = File(cacheDir, "hakunaa_crop_${System.currentTimeMillis()}.mp4")
        VideoToolsEngine.crop(
            input.absolutePath, out.absolutePath, dims[0], dims[1], dims[2], dims[3],
            { pr -> runOnUiThread { pv.progress.progress = pr } },
            { ok, msg ->
                runOnUiThread {
                    pv.progress.visibility = View.GONE
                    if (ok && out.exists() && out.length() > 0) {
                        pv.status.text = "Done — saved to Movies/Hakunaa"
                        pv.result.text = "Cropped: ${FileUtils.formatSize(out.length())}"
                        saveVideoAndShow(out, "Movies/Hakunaa", "MP4", "video/mp4")
                    } else {
                        pv.status.text = "Failed: $msg"
                    }
                }
            }
        )
    }

    // ------------------------------------------------------------------
    // Color Grading — sliders + one-frame preview, FFmpeg eq/hue.
    // ------------------------------------------------------------------

    private var cgBrightness = 0f
    private var cgContrast = 1f
    private var cgSaturation = 1f
    private var cgHue = 0
    private var cgSharpness = 0f
    private var cgTemperature = 6500

    private fun showColorScreen() {
        val content = newToolOverlay("Color Grading")
        content.addView(fileInfoCard())
        content.addView(sliderRow("Brightness", 200, 100, { p -> "${p - 100}" }) { p ->
            cgBrightness = (p - 100) / 100f
        })
        content.addView(sliderRow("Contrast", 200, 100, { p -> "${p / 100f}" }) { p ->
            cgContrast = p / 100f
        })
        content.addView(sliderRow("Saturation", 300, 100, { p -> "${p / 100f}" }) { p ->
            cgSaturation = p / 100f
        })
        content.addView(sliderRow("Hue", 360, 180, { p -> "${p - 180}°" }) { p ->
            cgHue = p - 180
        })
        content.addView(sliderRow("Sharpness", 200, 0, { p -> "${p / 100f}" }) { p ->
            cgSharpness = p / 100f
        })
        content.addView(sliderRow("Temperature", 80, 45, { p -> "${2000 + p * 100}K" }) { p ->
            cgTemperature = 2000 + p * 100
        })
        val framePreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { topMargin = dp(16) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(getColor(R.color.hakunaa_outline))
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
            visibility = View.GONE
        }
        content.addView(framePreview)
        val pv = addProgressViews(content)
        val previewBtn = popupPillButton("Preview One Frame", false) {
            runColorFrame(pv, framePreview)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            ).apply { topMargin = dp(16) }
        }
        content.addView(previewBtn)
        val applyBtn = actionButton("Apply & Save") { runColorGrade(pv) }
        content.addView(applyBtn)
        val note = TextView(this).apply {
            text = "Preview shows one frame (1 second in) with your settings. Apply re-encodes the whole video at CRF 28."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        content.addView(note)
    }

    private fun currentColorFilter(): String {
        return VideoToolsEngine.colorFilter(
            cgBrightness, cgContrast, cgSaturation, cgHue, cgSharpness, cgTemperature
        )
    }

    private fun runColorFrame(pv: ProgressViews, framePreview: ImageView) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        pv.status.text = "Rendering preview frame…"
        val frame = File(cacheDir, "hakunaa_color_${System.currentTimeMillis()}.jpg")
        VideoToolsEngine.colorFrame(
            input.absolutePath, frame.absolutePath, 1000L, currentColorFilter()
        ) { ok, msg ->
            runOnUiThread {
                if (ok && frame.exists() && frame.length() > 0) {
                    try {
                        val bmp = BitmapFactory.decodeFile(frame.absolutePath)
                        if (bmp != null) {
                            overlayBitmaps.add(bmp)
                            framePreview.setImageBitmap(bmp)
                            framePreview.visibility = View.VISIBLE
                        }
                    } catch (_: Throwable) {
                    }
                    pv.status.text = "Preview ready — adjust the sliders and preview again, or Apply & Save"
                } else {
                    pv.status.text = "Failed: $msg"
                }
            }
        }
    }

    private fun runColorGrade(pv: ProgressViews) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        pv.progress.visibility = View.VISIBLE
        pv.progress.progress = 0
        pv.status.text = "Applying color grading…"
        pv.result.text = ""
        val out = File(cacheDir, "hakunaa_color_${System.currentTimeMillis()}.mp4")
        VideoToolsEngine.colorGrade(
            input.absolutePath, out.absolutePath, currentColorFilter(),
            { p -> runOnUiThread { pv.progress.progress = p } },
            { ok, msg ->
                runOnUiThread {
                    pv.progress.visibility = View.GONE
                    if (ok && out.exists() && out.length() > 0) {
                        pv.status.text = "Done — saved to Movies/Hakunaa"
                        pv.result.text = "Graded: ${FileUtils.formatSize(out.length())}"
                        saveVideoAndShow(out, "Movies/Hakunaa", "MP4", "video/mp4")
                    } else {
                        pv.status.text = "Failed: $msg"
                    }
                }
            }
        )
    }
}
