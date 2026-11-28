package com.hakunaa.app.video

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

    // Gallery image picker for Custom Thumbnail (v0.1.198 Fix 7).
    private var singleImageCallback: ((Uri) -> Unit)? = null
    private val pickSingleImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) singleImageCallback?.invoke(uri)
            singleImageCallback = null
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
    // Smart priority (v0.1.201): what matters most to the user —
    // 0 = Best Quality, 1 = Balanced (default), 2 = Smallest Size.
    // Sets which of the 3 Smart results is selected by default.
    private var smartPriority = 1
    // v0.1.198 Fix 5: the bottom "Save selected" button of the
    // fixed Smart screen (created in showSmartScreen, shown by
    // showSmartResults once results exist).
    private var smartSaveBtn: TextView? = null

    // Video to Audio result preview player (v0.1.199): a plain
    // ExoPlayer (audio only) — released in dismissToolOverlay /
    // onDestroy so it never keeps playing after the screen closes.
    private var audioResultPlayer: ExoPlayer? = null
    private var audioResultHandler: Handler? = null
    private var audioResultTick: Runnable? = null

    // Rotate v2 state (v0.1.199): free angle 0-360, flips, zoom
    // 1-2, pan in preview px. Default angle 90 = old CW90.
    // v0.1.200 rework: base = accumulated 90-degree steps (0-359),
    // fine = -45..45 slider. Total angle = base + fine. Zoom/pan
    // removed (were the confusing part) — rotateFree now runs at
    // default zoom 1.0 with no pan.
    private var rotateBase90 = 0
    private var rotateFine = 0f
    private var rotateFlipH = false
    private var rotateFlipV = false
    private var rotatePreviewView: ExoVideoView? = null

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
                "• Automatic — best size for your video\n• Up to 4 results at one time\n• Real sizes, pick the best one") {
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

    /** Fixed (non-scrolling) tool overlay — v0.1.198 Fix 5
     * (Smart Compress redesign). SAME root + header as
     * newToolOverlay, but the content is NOT inside a ScrollView:
     * the content LinearLayout takes weight 1, so the screen is a
     * fixed layout (header + fixed content) that cannot scroll.
     * Registered the same way (toolOverlay field, added to
     * android.R.id.content), so dismissToolOverlay clears it. */
    private fun newFixedToolOverlay(title: String, topExtraDp: Int = 16): LinearLayout {
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
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(content)
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
        rotatePreviewView = null
        releaseAudioResultPlayer()
        for (bmp in overlayBitmaps) {
            try {
                bmp.recycle()
            } catch (_: Throwable) {
            }
        }
        overlayBitmaps = mutableListOf()
    }

    /** Stop + release the Audio result preview player (v0.1.199).
     * Safe to call more than once; called from
     * dismissToolOverlay (Back / X / new overlay) and onDestroy. */
    private fun releaseAudioResultPlayer() {
        try {
            audioResultTick?.let { audioResultHandler?.removeCallbacks(it) }
        } catch (_: Throwable) {
        }
        audioResultTick = null
        audioResultHandler = null
        try {
            audioResultPlayer?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioResultPlayer?.release()
        } catch (_: Throwable) {
        }
        audioResultPlayer = null
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
            // v0.1.198 Fix 4: show "Loading video…" IMMEDIATELY
            // when the picker returns, then copy + probe on a
            // BACKGROUND thread. (Was all synchronous on the main
            // thread with no feedback — the UI froze until the tool
            // screen finally appeared.)
            val loading = showCompressLoadingDialog("Preparing your video…", "Loading video…")
            Thread {
                try {
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
                        runOnUiThread { toast("Could not read this video: ${t.message ?: "unknown error"}") }
                        inputFile = null
                    }
                    probeInfo = inputFile?.let { VideoToolsEngine.probe(it.absolutePath) }
                    runOnUiThread {
                        try { loading.dialog.dismiss() } catch (_: Throwable) { }
                        if (inputFile != null) {
                            // v0.1.200: a new video invalidates the old
                            // Smart results. Without this, showSmartScreen
                            // re-displays the previous video's results
                            // under the new file card (stale sizes,
                            // stale saved%, 1080p shown for a 480p video).
                            // Tab-switch restore is unaffected — this
                            // only runs on a new pick.
                            smartResults.clear()
                            smartSelected = -1
                            action()
                        }
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        try { loading.dialog.dismiss() } catch (_: Throwable) { }
                        toast("Could not read this video: ${t.message ?: "unknown error"}")
                    }
                }
            }.start()
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
        releaseAudioResultPlayer()
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
        // v0.1.198 Fix 5: Smart is a FIXED screen (no tabs, no
        // scroll) — built separately in showSmartScreen.
        if (compressTab == 1) {
            showSmartScreen()
            return
        }
        // Header a little higher (user 2026-11-24): top extra
        // 16dp -> 8dp.
        val content = newToolOverlay("Compress Video", 8)
        // Tabs — Quick has NO tabs (user 2026-11-24: remove the
        // Quick / Smart / Advanced buttons from Quick Compress).
        // Smart lost them in v0.1.198 Fix 5; Advanced keeps them.
        if (compressTab == 2) {
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
            else -> buildAdvancedOptions(optionsBox, actionBox, pv, resultBox)
        }
    }

    /** Smart Compress — FIXED screen (v0.1.198 Fix 5). No tabs, no
     * scroll: file info + preview + Play preview on top, an EMPTY
     * middle (weight 1) where progress + results appear, and at
     * the bottom "Save selected" (only once results exist) above
     * "Analyze & Compress" (always visible, pinned). No explain
     * text, no "Done" button — the X in the header closes it. */
    private fun showSmartScreen() {
        smartRunning = false
        val content = newFixedToolOverlay("Compress Video", 8)
        // v0.1.199 redesign: header card with a 96dp SQUARE preview
        // on the LEFT + file info beside it (same as Quick
        // Compress) — tap the preview to play full-screen. This
        // replaces the old 200dp preview + "Play preview" pill.
        content.addView(quickHeaderCard())
        // Middle: weight 1 — EMPTY at first; the progress bar +
        // status + result rows appear here.
        val middleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        content.addView(middleBox)
        // Smart instructions card (v0.1.201): fills the empty
        // middle with what Smart does. It STAYS visible while
        // compressing and after results (user 2026-11-28) — the
        // progress bar runs in the space BELOW it, and the
        // results appear there too, on this same screen.
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(pinkCard)
                setStroke(dp(1), pinkBorder)
            }
        }
        infoCard.addView(TextView(this).apply {
            text = "How Smart works"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        })
        val howSteps = listOf(
            "Smart tries different settings on your video",
            "4 best results at one time - pick the best",
            "Takes a little extra time, but worth it"
        )
        for ((i, step) in howSteps.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            row.addView(TextView(this).apply {
                text = "${i + 1}"
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(pinkDark)
                }
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                    marginEnd = dp(10)
                }
            })
            row.addView(TextView(this).apply {
                text = step
                textSize = 14f
                setTextColor(getColor(R.color.hakunaa_text))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            infoCard.addView(row)
        }
        middleBox.addView(infoCard)
        // Priority options (v0.1.201): what matters most — sets
        // which of the 3 Smart results is selected by default.
        // These + the info card are ONE "info section": visible
        // while picking / compressing, HIDDEN when the results
        // come (user 2026-11-28 — results get the whole screen,
        // clear like the 37044.mp4 screenshot).
        val priorityLabel = sectionLabel("What matters most to you?")
        middleBox.addView(priorityLabel)
        val priorityChips = chipRow(
            listOf("Best Quality", "Balanced", "Smallest Size"),
            smartPriority
        ) { idx -> smartPriority = idx }
        middleBox.addView(priorityChips)
        val priorityHint = TextView(this).apply {
            text = "Smart will select the best result for you."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        middleBox.addView(priorityHint)
        // Show/hide the whole info section (instructions card +
        // priority options). Progress time = visible; results
        // time = hidden so the 4 results get the full screen.
        fun setInfoVisible(v: Boolean) {
            val vis = if (v) View.VISIBLE else View.GONE
            infoCard.visibility = vis
            priorityLabel.visibility = vis
            priorityChips.visibility = vis
            priorityHint.visibility = vis
        }
        val pv = addProgressViews(middleBox)
        // Make the progress bar thick and clear (user 2026-11-28:
        // the progress must run CLEARLY in the empty space while
        // Smart compresses). Smart-only — other tools keep the
        // default thin bar.
        pv.progress.trackThickness = dp(10)
        // Empty middle at first — hide the status/result lines
        // until a compress run starts (the Analyze button shows
        // them again before calling runSmart).
        pv.status.visibility = View.GONE
        pv.result.visibility = View.GONE
        val resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // Results scroll if the screen is short (user 2026-11-28:
        // space management must be perfect — the 3 result rows
        // get the remaining space and scroll instead of clipping).
        val resultScroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = false
        }
        resultScroll.addView(resultBox)
        middleBox.addView(resultScroll)
        // Bottom: "Save selected" (hidden until results exist)
        // directly above "Analyze & Compress".
        smartSaveBtn = actionButton("Save selected") {
            val sel = smartResults.getOrNull(smartSelected) ?: return@actionButton
            pv.status.visibility = View.VISIBLE
            pv.status.text = "Saving ${sel.label} version…"
            saveVideoAndShow(sel.file, "Movies/Hakunaa", "MP4", "video/mp4")
        }.apply { visibility = View.GONE }
        content.addView(smartSaveBtn)
        val analyzeBtn = actionButton("Analyze & Compress") {
            // Progress time: the info section (instructions +
            // options) stays visible and the progress bar runs
            // below it. When the results are ready, the info
            // section hides so the results get the full screen.
            setInfoVisible(true)
            pv.status.visibility = View.VISIBLE
            pv.result.visibility = View.VISIBLE
            runSmart(pv, resultBox) { setInfoVisible(false) }
        }
        content.addView(analyzeBtn)
        // If results already exist (re-entering Smart), show them
        // again — info section hidden, results get the full screen.
        if (smartResults.isNotEmpty()) {
            setInfoVisible(false)
            showSmartResults(pv, resultBox)
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

    /** Loading popup used by Quick / Smart / Advanced / Convert /
     * video loading. v0.1.199: an optional Cancel button — passed
     * ONLY by runConvert (user 2026-11-27); the other callers
     * leave it off. The button dismisses this dialog itself, then
     * invokes onCancel (the caller stops the engine). */
    private fun showCompressLoadingDialog(
        statusText: String,
        titleText: String = "Compressing…",
        showCancel: Boolean = false,
        onCancel: (() -> Unit)? = null
    ): CompressLoadingDialog {
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
        if (showCancel) {
            val cancelBtn = popupPillButton("Cancel", false) {
                try {
                    dialog.dismiss()
                } catch (_: Throwable) {
                }
                onCancel?.invoke()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
                ).apply { topMargin = dp(16) }
            }
            card.addView(cancelBtn)
        }
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

    private fun runSmart(pv: ProgressViews, resultBox: LinearLayout, onResults: (() -> Unit)? = null) {
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
        // DYNAMIC Smart (user 2026-11-28): up to 4 results, NO
        // upscale (Fix 9 restored — upscaling is not good), CRF
        // adapts to the input video (bitrate-based). The results
        // are the resolutions at/below the input (1080/720/480 for
        // a 1080p input; 720/480 for 720p; 480 for 480p; plus the
        // original resolution for odd heights like 640p). Each
        // resolution is tried at a standard CRF and a "Max" CRF;
        // up to 4 versions are shown (see finalizeResults).
        val durationSec = (durationMs / 1000.0).coerceAtLeast(1.0)
        val inputBitrateKbps = (inputSize * 8.0 / 1000.0) / durationSec
        val crfShift = if (inputBitrateKbps < 1000) 3 else 0
        val inputH = probeInfo?.height ?: 0
        val specs = mutableListOf<Triple<String, Int, VideoCompressor.Resolution>>()
        fun addRes(label: String, height: Int, baseCrf: Int, res: VideoCompressor.Resolution) {
            if (inputH <= 0 || height <= inputH) {
                specs.add(Triple(label, baseCrf, res))
                specs.add(Triple("$label Max", minOf(baseCrf + 5, 34), res))
            }
        }
        addRes("1080p", 1080, 23 + crfShift, VideoCompressor.Resolution.P1080)
        addRes("720p", 720, 26 + crfShift, VideoCompressor.Resolution.P720)
        addRes("480p", 480, 28 + crfShift, VideoCompressor.Resolution.P480)
        if (inputH > 480 && inputH != 720 && inputH != 1080) {
            // Odd height (e.g. 640p portrait video): also try the
            // ORIGINAL resolution, so Smart has more than one
            // resolution to offer (user 2026-11-28: only 1 result
            // showed for a 360x640 video — Smart must give options).
            specs.add(Triple("${inputH}p", 26 + crfShift, VideoCompressor.Resolution.ORIGINAL))
            specs.add(Triple("${inputH}p Max", minOf(26 + crfShift + 5, 34), VideoCompressor.Resolution.ORIGINAL))
        }
        if (specs.isEmpty()) {
            // Input below 480p: compress at the original resolution.
            specs.add(Triple("${inputH}p", 28 + crfShift, VideoCompressor.Resolution.ORIGINAL))
            specs.add(Triple("${inputH}p Max", minOf(28 + crfShift + 5, 34), VideoCompressor.Resolution.ORIGINAL))
        }
        val made = mutableListOf<Triple<String, File, Long>>()
        // A version that comes out BIGGER than the original is not
        // a compression — it is filtered out, never shown (user
        // 2026-11-28: a "2% larger" Smart result is poor).
        var anyTooBig = false
        fun finalizeResults() {
            smartRunning = false
            pv.progress.visibility = View.GONE
            for ((label, file, size) in made) {
                val savedPct = if (inputSize > 0) ((1.0 - size.toDouble() / inputSize.toDouble()) * 100.0).toInt() else 0
                smartResults.add(SmartResult(label, file, size, savedPct, false))
            }
            // Results: one per resolution first (the best per
            // resolution — std if it saves >= 15%, else smallest),
            // then the Max variants (smallest first) to fill up to
            // 4 total. The user (2026-11-28) saw Smart run several
            // versions but only 1 displayed for a low-res video —
            // Smart must show the options it actually made, not
            // hide them behind a one-per-resolution dedup.
            // Sorted by resolution, highest first.
            val byRes = smartResults.groupBy { it.label.removeSuffix(" Max") }
            val picked = mutableListOf<SmartResult>()
            for ((_, versions) in byRes) {
                val std = versions.firstOrNull { !it.label.endsWith(" Max") }
                val best = if (std != null && std.savedPct >= 15) std
                else versions.minByOrNull { it.size }
                if (best != null) picked.add(best)
            }
            fun resHeight(label: String): Int {
                return label.removeSuffix(" Max").removeSuffix("p").toIntOrNull() ?: 0
            }
            val remaining = smartResults.filter { it !in picked }.sortedBy { it.size }
            val all = (picked + remaining).sortedWith(
                compareByDescending<SmartResult> { resHeight(it.label) }
                    .thenByDescending { it.size }
            )
            smartResults = all.take(4).toMutableList()
            if (smartResults.isEmpty()) {
                pv.status.text = if (anyTooBig) "Already optimized — this video is already as small as it gets"
                else "Failed — couldn't make any version"
                return
            }
            // Default selection follows the user's priority
            // (v0.1.201): Best Quality = highest resolution (the
            // results are sorted by resolution, highest first);
            // Balanced = highest resolution that saves at least
            // 40%, else the smallest file; Smallest Size = the
            // smallest file. The user can still tap any result.
            var bestIdx = -1
            when (smartPriority) {
                0 -> bestIdx = 0
                2 -> {
                    var smallest = Long.MAX_VALUE
                    for (i in smartResults.indices) {
                        if (smartResults[i].size < smallest) {
                            smallest = smartResults[i].size
                            bestIdx = i
                        }
                    }
                }
                else -> {
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
                }
            }
            smartResults = smartResults.mapIndexed { i, r ->
                r.copy(isBest = i == bestIdx)
            }.toMutableList()
            smartSelected = bestIdx
            pv.status.text = "Done — ${smartResults.size} versions ready"
            showSmartResults(pv, resultBox)
            // Results are on screen — hide the info section so
            // the 4 results get the full screen, clear like the
            // 37044.mp4 screenshot (user 2026-11-28).
            onResults?.invoke()
        }
        fun runOne(index: Int) {
            if (index >= specs.size) {
                finalizeResults()
                return
            }
            val spec = specs[index]
            val label = spec.first
            val crf = spec.second
            val res = spec.third
            pv.status.text = "Making $label version… (${index + 1}/${specs.size})"
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
                            if (inputSize <= 0 || out.length() < inputSize) {
                                made.add(Triple(label, out, out.length()))
                            } else {
                                // Bigger than the original — not a
                                // compression, drop it (v0.1.201).
                                anyTooBig = true
                                try {
                                    out.delete()
                                } catch (_: Throwable) {
                                }
                            }
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
                setPadding(dp(14), dp(10), dp(14), dp(10))
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
            // v0.1.199: NO Best/Small tag pills in the video Smart
            // results (user removed them) — only the checkmark
            // selection stays. The Best rule still picks the
            // default selection internally (runSmart), it is just
            // never shown as a tag.
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
                ).apply { topMargin = dp(4) }
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
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(36))
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
        // v0.1.198 Fix 5: "Save selected" is the BOTTOM button
        // (created in showSmartScreen) — show it now that results
        // exist. No "Done" button; the X in the header closes it.
        smartSaveBtn?.visibility = View.VISIBLE
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
        // v0.1.199: Convert ONLY gets a Cancel button (user
        // 2026-11-27). Tapping it stops the FFmpeg session and the
        // dialog; onDone then returns silently (not a failure).
        var cancelled = false
        val loading = showCompressLoadingDialog(
            "To ${fmt.label}  •  ${convertRes.label}", "Converting…",
            showCancel = true,
            onCancel = {
                cancelled = true
                VideoCompressor.cancel()
                VideoToolsEngine.cancel()
            }
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
                    // User-cancelled: back to the Convert screen,
                    // silently — a cancel is not a failure.
                    if (cancelled) return@runOnUiThread
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

    /** Rotate Video v2 (v0.1.199, mockup rotate-redesign-mockup-v2):
     * compact file card → LIVE preview 220dp (video WITH the
     * selected rotation applied live) → FREE rotation slider
     * 0-360° → preset chips (90 / -90 / 180 / Flip H / Flip V /
     * Reset) → Zoom slider 100-200% + pinch-to-zoom and drag-to-
     * move on the preview → "Rotate" pinned bottom. Fixed screen,
     * no scroll. */
    /** Canvas-drawn icon for the Rotate buttons (v0.1.200 rework):
     * Left 90 / Right 90 circular arrows, Flip H / Flip V double
     * arrows. Drawn, not a font glyph, so it renders identically
     * on every phone. kind: 0 = Left 90, 1 = Right 90, 2 = Flip H,
     * 3 = Flip V. */
    private inner class RotateGlyphView(context: Context, val kind: Int) : View(context) {
        private var selected = false

        fun setSelectedState(sel: Boolean) {
            if (sel != selected) {
                selected = sel
                invalidate()
            }
        }

        private fun arrowhead(
            canvas: Canvas, tipX: Float, tipY: Float,
            dirX: Float, dirY: Float, len: Float, halfW: Float, paint: Paint
        ) {
            val baseX = tipX - dirX * len
            val baseY = tipY - dirY * len
            val px = -dirY
            val py = dirX
            val path = Path().apply {
                moveTo(tipX, tipY)
                lineTo(baseX + px * halfW, baseY + py * halfW)
                lineTo(baseX - px * halfW, baseY - py * halfW)
                close()
            }
            canvas.drawPath(path, paint)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val col = if (selected) pinkDark else getColor(R.color.hakunaa_text)
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = col
                style = Paint.Style.STROKE
                strokeWidth = 5f * d
                strokeCap = Paint.Cap.ROUND
            }
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = col
                style = Paint.Style.FILL
            }
            val cx = width / 2f
            val cy = height / 2f
            val r = Math.min(width, height) * 0.32f
            when (kind) {
                0, 1 -> {
                    // Circular arrow, head at the top: Right 90 turns
                    // clockwise (head points right), Left 90 turns
                    // counter-clockwise (head points left).
                    val start = if (kind == 1) 0f else 180f
                    val sweep = if (kind == 1) 270f else -270f
                    val oval = RectF(cx - r, cy - r, cx + r, cy + r)
                    canvas.drawArc(oval, start, sweep, false, stroke)
                    val endDeg = start + sweep // 270 (top) for both
                    val er = Math.toRadians(endDeg.toDouble())
                    val ex = cx + r * Math.cos(er).toFloat()
                    val ey = cy + r * Math.sin(er).toFloat()
                    val tanDeg = if (kind == 1) endDeg + 90f else endDeg - 90f
                    val tr = Math.toRadians(tanDeg.toDouble())
                    val tx = Math.cos(tr).toFloat()
                    val ty = Math.sin(tr).toFloat()
                    val tipL = r * 0.45f
                    val tipX = ex + tx * tipL
                    val tipY = ey + ty * tipL
                    val baseX = ex - tx * tipL * 0.2f
                    val baseY = ey - ty * tipL * 0.2f
                    val hw = r * 0.32f
                    val px = -ty
                    val py = tx
                    val path = Path().apply {
                        moveTo(tipX, tipY)
                        lineTo(baseX + px * hw, baseY + py * hw)
                        lineTo(baseX - px * hw, baseY - py * hw)
                        close()
                    }
                    canvas.drawPath(path, fill)
                }
                2 -> {
                    // Flip H: horizontal double arrow + vertical mirror line.
                    val y = cy
                    val x0 = cx - r
                    val x1 = cx + r
                    canvas.drawLine(x0, y, x1, y, stroke)
                    arrowhead(canvas, x0, y, -1f, 0f, r * 0.45f, r * 0.32f, fill)
                    arrowhead(canvas, x1, y, 1f, 0f, r * 0.45f, r * 0.32f, fill)
                    val div = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFFBDBDBD.toInt()
                        style = Paint.Style.STROKE
                        strokeWidth = 3f * d
                    }
                    canvas.drawLine(cx, cy - r * 0.95f, cx, cy + r * 0.95f, div)
                }
                else -> {
                    // Flip V: vertical double arrow + horizontal mirror line.
                    val x = cx
                    val y0 = cy - r
                    val y1 = cy + r
                    canvas.drawLine(x, y0, x, y1, stroke)
                    arrowhead(canvas, x, y0, 0f, -1f, r * 0.45f, r * 0.32f, fill)
                    arrowhead(canvas, x, y1, 0f, 1f, r * 0.45f, r * 0.32f, fill)
                    val div = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = 0xFFBDBDBD.toInt()
                        style = Paint.Style.STROKE
                        strokeWidth = 3f * d
                    }
                    canvas.drawLine(cx - r * 0.95f, cy, cx + r * 0.95f, cy, div)
                }
            }
        }
    }

    // Professional Play/Pause button for the Video Cutter (user
    // 2026-11-28): a 56dp LITE circle — white fill, thin pink
    // border, pink icon — with a crisp Canvas-drawn glyph: play
    // triangle when paused, two rounded pause bars when playing.
    // No text glyphs, so it renders the same on every phone.
    // (User 2026-11-28 19:03 IST: "Play button professional
    // lite" — the solid pink circle was too heavy.)
    private inner class PlayPauseButton(context: Context) : View(context) {
        var playing = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val r = Math.min(width, height) / 2f
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, r, bg)
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pinkDark
                style = Paint.Style.STROKE
                strokeWidth = 2f * d
            }
            canvas.drawCircle(cx, cy, r - d, border)
            val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pinkDark
                style = Paint.Style.FILL
            }
            if (!playing) {
                // Play triangle, pointing right.
                val s = r * 0.52f
                val path = Path().apply {
                    moveTo(cx - s * 0.55f, cy - s)
                    lineTo(cx - s * 0.55f, cy + s)
                    lineTo(cx + s * 0.85f, cy)
                    close()
                }
                canvas.drawPath(path, icon)
            } else {
                // Pause: two rounded vertical bars.
                val barW = r * 0.28f
                val barH = r * 0.95f
                val gap = r * 0.22f
                val round = barW * 0.5f
                canvas.drawRoundRect(
                    cx - gap - barW, cy - barH / 2f,
                    cx - gap, cy + barH / 2f, round, round, icon
                )
                canvas.drawRoundRect(
                    cx + gap, cy - barH / 2f,
                    cx + gap + barW, cy + barH / 2f, round, round, icon
                )
            }
        }
    }

    private fun showRotateScreen() {
        val content = newFixedToolOverlay("Rotate Video")
        val card = fileInfoCard()
        (card.getChildAt(0) as? TextView)?.let {
            it.maxLines = 1
            it.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        content.addView(card)
        // LIVE preview — LARGE black box. The video view inside is
        // sized to the ROTATED bounding box (see layoutRotatePreview)
        // so the true rotated output shows, never cropped (v0.1.200
        // rework — the v0.1.199 fixed 220dp box cropped 90-degree
        // turns top and bottom).
        val previewBox = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.BLACK)
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(tallPreviewHeightDp())
            ).apply { topMargin = dp(12) }
        }
        val preview = ExoVideoView(this, useTextureView = true).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        inputUri?.let { preview.setVideoURI(it) }
        previewBox.addView(preview)
        rotatePreviewView = preview
        activeVideoViews.add(preview)
        val badge = TextView(this).apply {
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(pinkDark)
            }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(10)
            }
        }
        previewBox.addView(badge)
        content.addView(previewBox)
        // Readout under the preview: "90 deg - Rotated right".
        val readout = TextView(this).apply {
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        content.addView(readout)
        // Total angle, normalised to -180..180 for display/encode.
        fun totalAngle(): Float {
            var a = (rotateBase90 + rotateFine) % 360f
            if (a > 180f) a -= 360f
            if (a < -180f) a += 360f
            return a
        }
        fun readoutText(): String {
            val deg = Math.round(totalAngle())
            val parts = mutableListOf<String>()
            parts.add("$deg deg")
            if (deg > 0) parts.add("Rotated right")
            if (deg < 0) parts.add("Rotated left")
            if (rotateFlipH) parts.add("Flip H")
            if (rotateFlipV) parts.add("Flip V")
            return parts.joinToString(" - ")
        }
        // Size the inner video view to the rotated bounding box and
        // apply the transform (rotation + flips). The preview BOX
        // height ADAPTS to the rotated video's aspect (v0.1.201):
        // a 90-degree turn makes a portrait video landscape, so the
        // box becomes a landscape rectangle and the video fills it
        // edge to edge — no big black bars, the preview looks exactly
        // like the output. Clamped so the box never gets too short
        // or too tall. (User 2026-11-28: "preview it's not good..
        // Need Perfect exact preview.")
        fun layoutRotatePreview() {
            val p = probeInfo ?: return
            val w = p.width.toFloat()
            val h = p.height.toFloat()
            if (w <= 0f || h <= 0f) return
            val ow = previewBox.width.toFloat()
            if (ow <= 0f) return
            val total = rotateBase90 + rotateFine
            val rad = Math.toRadians(total.toDouble())
            val cos = Math.abs(Math.cos(rad))
            val sin = Math.abs(Math.sin(rad))
            val bw = w * cos + h * sin
            val bh = w * sin + h * cos
            val idealBoxH = (ow * bh / bw).toInt()
            val newBoxH = idealBoxH.coerceIn(dp(200), dp(tallPreviewHeightDp()))
            val boxLp = previewBox.layoutParams as LinearLayout.LayoutParams
            if (boxLp.height != newBoxH) {
                boxLp.height = newBoxH
                previewBox.layoutParams = boxLp
            }
            val oh = newBoxH.toFloat()
            val k = minOf(ow / bw, oh / bh)
            val vw = (k * w).toInt().coerceAtLeast(2)
            val vh = (k * h).toInt().coerceAtLeast(2)
            preview.layoutParams = FrameLayout.LayoutParams(vw, vh, Gravity.CENTER)
            preview.rotation = total
            preview.scaleX = if (rotateFlipH) -1f else 1f
            preview.scaleY = if (rotateFlipV) -1f else 1f
            preview.requestLayout()
            // Badge straddles the top edge of the rotated frame.
            val bboxH = (k * bh).toInt()
            val innerTop = ((oh - bboxH) / 2).toInt()
            val badgeLp = badge.layoutParams as FrameLayout.LayoutParams
            badgeLp.topMargin = (innerTop - dp(14)).coerceAtLeast(0)
            badge.requestLayout()
        }
        // The four buttons (declared before refresh so it can paint them).
        val rotateBtns = mutableListOf<Triple<LinearLayout, RotateGlyphView, TextView>>()
        fun paintRotateButtons() {
            for ((btn, glyph, lab) in rotateBtns) {
                val sel = when (glyph.kind) {
                    0 -> rotateBase90 == 270
                    1 -> rotateBase90 == 90
                    2 -> rotateFlipH
                    else -> rotateFlipV
                }
                btn.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(16).toFloat()
                    if (sel) {
                        setColor(pinkCard)
                        setStroke(dp(2), pinkBorder)
                    } else {
                        setColor(Color.WHITE)
                        setStroke(dp(1), Color.parseColor("#CFD8DC"))
                    }
                }
                glyph.setSelectedState(sel)
                lab.setTextColor(if (sel) pinkDark else getColor(R.color.hakunaa_text))
            }
        }
        fun refreshRotateUI() {
            badge.text = "${Math.round(totalAngle())} deg"
            readout.text = readoutText()
            paintRotateButtons()
            layoutRotatePreview()
        }
        fun rotateButton(kind: Int, label: String): LinearLayout {
            val btn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(6), dp(10), dp(6), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (kind < 3) marginEnd = dp(8)
                }
            }
            val glyph = RotateGlyphView(this, kind).apply {
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
            val lab = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            btn.addView(glyph)
            btn.addView(lab)
            rotateBtns.add(Triple(btn, glyph, lab))
            return btn
        }
        val btnsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val btnLeft = rotateButton(0, "Left 90").apply {
            setOnClickListener {
                rotateBase90 = ((rotateBase90 - 90) % 360 + 360) % 360
                refreshRotateUI()
            }
        }
        val btnRight = rotateButton(1, "Right 90").apply {
            setOnClickListener {
                rotateBase90 = (rotateBase90 + 90) % 360
                refreshRotateUI()
            }
        }
        val btnFlipH = rotateButton(2, "Flip H").apply {
            setOnClickListener {
                rotateFlipH = !rotateFlipH
                refreshRotateUI()
            }
        }
        val btnFlipV = rotateButton(3, "Flip V").apply {
            setOnClickListener {
                rotateFlipV = !rotateFlipV
                refreshRotateUI()
            }
        }
        btnsRow.addView(btnLeft)
        btnsRow.addView(btnRight)
        btnsRow.addView(btnFlipH)
        btnsRow.addView(btnFlipV)
        content.addView(btnsRow)
        // Fine-tune slider (-45..+45, default 0) + end labels.
        // Initial position follows the persisted fine-tune value
        // (Back from the result screen keeps the settings).
        val fineRow = sliderRow("FINE TUNE", 90, (rotateFine + 45).toInt(), { p -> "${p - 45}°" }) { p ->
            rotateFine = (p - 45).toFloat()
            refreshRotateUI()
        }
        content.addView(fineRow)
        val fineEnds = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        fineEnds.addView(TextView(this).apply {
            text = "-45 deg"
            textSize = 12f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        fineEnds.addView(TextView(this).apply {
            text = "+45 deg"
            textSize = 12f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
        })
        content.addView(fineEnds)
        val hint = TextView(this).apply {
            text = "Tap a button to rotate  |  Slide to fine-tune"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        content.addView(hint)
        // Tap the preview to play / pause.
        previewBox.setOnClickListener {
            try {
                if (preview.isPlaying()) preview.pause() else preview.start()
            } catch (_: Throwable) {
            }
        }
        refreshRotateUI()
        previewBox.post { layoutRotatePreview() }
        // Spacer pins "Rotate" to the bottom.
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        content.addView(spacer)
        val btn = actionButton("Rotate") { runRotate() }
        content.addView(btn)
        // Fit pass: after layout, give the preview box ALL remaining
        // vertical space so the fixed screen fits exactly on any phone.
        content.post {
            val avail = content.height
            if (avail <= 0) return@post
            var used = 0
            for (i in 0 until content.childCount) {
                val ch = content.getChildAt(i)
                if (ch === previewBox || ch === spacer) continue
                val lp = ch.layoutParams as? LinearLayout.LayoutParams
                used += ch.height + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            }
            val boxLp = previewBox.layoutParams as LinearLayout.LayoutParams
            val targetH = (avail - used - boxLp.topMargin - boxLp.bottomMargin)
                .coerceIn(dp(200), dp(520))
            if (targetH != previewBox.height) {
                previewBox.layoutParams = (previewBox.layoutParams as LinearLayout.LayoutParams).apply {
                    height = targetH
                }
                previewBox.requestLayout()
                previewBox.post { layoutRotatePreview() }
            } else {
                layoutRotatePreview()
            }
        }
    }

    /** Label for the current Rotate settings, e.g. "90° • Flip H"
     * — shown on the result screen and in the loading dialog. */
    private fun rotateTransformLabel(): String {
        val parts = mutableListOf<String>()
        val total = rotateBase90 + rotateFine
        var a = total % 360f
        if (a > 180f) a -= 360f
        if (a < -180f) a += 360f
        val deg = Math.round(a)
        if (deg != 0) parts.add("$deg°")
        if (rotateFlipH) parts.add("Flip H")
        if (rotateFlipV) parts.add("Flip V")
        return if (parts.isEmpty()) "No change" else parts.joinToString(" • ")
    }

    private fun runRotate() {
        val input = inputFile ?: return
        if (!engineGuard()) return
        // Total angle: base 90-steps + fine-tune (-45..45).
        val total = rotateBase90 + rotateFine
        val angle = Math.round(total)
        // Nothing to do?
        if (angle % 360 == 0 && rotateFine == 0f && !rotateFlipH && !rotateFlipV) {
            toast("Choose a rotation first")
            return
        }
        val probe = probeInfo
        if (probe == null || probe.width <= 0 || probe.height <= 0) {
            toast("Could not read this video's size")
            return
        }
        val out = File(cacheDir, "hakunaa_rotate_${System.currentTimeMillis()}.mp4")
        val label = rotateTransformLabel()
        val loading = showCompressLoadingDialog(label, "Rotating…")
        val onDone: (Boolean, String) -> Unit = { ok, msg ->
            runOnUiThread {
                try {
                    loading.dialog.dismiss()
                } catch (_: Throwable) {
                }
                if (ok && out.exists() && out.length() > 0) {
                    // v0.1.199: NO direct save — result screen first.
                    showRotateResult(out)
                } else {
                    toast("Rotate failed: $msg")
                }
            }
        }
        // Exact fixed modes use the PROVEN transpose/flip path;
        // anything else (fine-tune angle, or angle + flip) uses the
        // free-angle engine at default zoom 1.0 with no pan.
        val a360 = ((angle % 360) + 360) % 360
        val fixedMode: VideoCompressor.RotateMode? = when {
            rotateFine != 0f -> null
            a360 == 90 && !rotateFlipH && !rotateFlipV -> VideoCompressor.RotateMode.CW90
            a360 == 270 && !rotateFlipH && !rotateFlipV -> VideoCompressor.RotateMode.CCW90
            a360 == 180 && !rotateFlipH && !rotateFlipV -> VideoCompressor.RotateMode.ROT180
            a360 == 0 && rotateFlipH && !rotateFlipV -> VideoCompressor.RotateMode.FLIP_H
            a360 == 0 && !rotateFlipH && rotateFlipV -> VideoCompressor.RotateMode.FLIP_V
            a360 == 0 && rotateFlipH && rotateFlipV -> VideoCompressor.RotateMode.ROT180
            else -> null
        }
        if (fixedMode != null) {
            VideoCompressor.rotate(
                input.absolutePath, out.absolutePath, fixedMode,
                { p -> runOnUiThread { loading.progress.progress = p; loading.percent.text = "$p%" } },
                onDone
            )
        } else {
            VideoCompressor.rotateFree(
                input.absolutePath, out.absolutePath,
                angleDeg = total,
                flipH = rotateFlipH,
                flipV = rotateFlipV,
                zoom = 1f,
                panXFrac = 0f,
                panYFrac = 0f,
                inputW = probe.width,
                inputH = probe.height,
                onProgress = { p -> runOnUiThread { loading.progress.progress = p; loading.percent.text = "$p%" } },
                onDone = onDone
            )
        }
    }

    /** Rotate result (v0.1.199): rotated video preview + size +
     * Save (ONLY Save publishes) + Back (settings persist — they
     * are fields). Same professional pattern as the Convert /
     * Audio result screens. */
    private fun showRotateResult(out: File) {
        val input = inputFile ?: return
        val content = newFixedToolOverlay("Result")
        val preview = ExoVideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)
            ).apply { topMargin = dp(12) }
        }
        preview.setVideoURI(Uri.fromFile(out))
        content.addView(preview)
        activeVideoViews.add(preview)
        val playPreview = popupPillButton("▶  Play preview", false) {
            showFullScreenPlayer(Uri.fromFile(out))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(8) }
        }
        content.addView(playPreview)
        val after = out.length()
        val inputSize = input.length()
        val sizeCard = LinearLayout(this).apply {
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
        val sizeText = TextView(this).apply {
            text = "${FileUtils.formatSize(inputSize)}  →  ${FileUtils.formatSize(after)}"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        val detail = TextView(this).apply {
            val saved = FileUtils.savedPercent(inputSize, after)
            text = "${rotateTransformLabel()}  •  $saved saved"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_success))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        sizeCard.addView(sizeText)
        sizeCard.addView(detail)
        content.addView(sizeCard)
        // Spacer pins Back + Save to the bottom.
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })
        val backBtn = popupPillButton("Back", false) {
            showRotateScreen()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            )
        }
        content.addView(backBtn)
        val saveBtn = actionButton("Save") {
            saveVideoAndShow(out, "Movies/Hakunaa", "MP4", "video/mp4")
        }
        content.addView(saveBtn)
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
                        // v0.1.199: NO direct save — result screen
                        // first (preview + Save), like Convert.
                        showAudioResult(out, fmt)
                    } else {
                        pv.status.text = "Failed: $msg"
                    }
                }
            }
        )
    }

    /** Video to Audio result (v0.1.199): audio info card + in-app
     * audio preview (Media3 ExoPlayer: play/pause + SeekBar +
     * current/total time) + Save (ONLY Save publishes) + Back
     * (returns to the audio options with the same video/settings).
     * Same professional pattern as the Convert result screen. */
    private fun showAudioResult(out: File, fmt: VideoToolsEngine.AudioFormat) {
        val content = newFixedToolOverlay("Result")
        val durationMs = probeInfo?.durationMs ?: 0L
        // Audio info card: purple music-note icon + name + format • size • duration.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(pinkCard)
                setStroke(dp(2), pinkBorder)
            }
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val icon = TextView(this).apply {
            text = "♪"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#7E57C2"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        }
        card.addView(icon)
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        val name = TextView(this).apply {
            text = out.name
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val meta = TextView(this).apply {
            text = "${fmt.label}  •  ${FileUtils.formatSize(out.length())}  •  ${formatDuration(durationMs)}"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        info.addView(name)
        info.addView(meta)
        card.addView(info)
        content.addView(card)
        // Player card: round play/pause button + SeekBar + times.
        val playerCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#CFD8DC"))
            }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
        val playBtn = TextView(this).apply {
            text = "▶"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pinkDark)
            }
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
        playerCard.addView(playBtn)
        val seekCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(16) }
        }
        val seek = SeekBar(this).apply {
            max = durationMs.toInt().coerceAtLeast(1)
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        val tvCur = TextView(this).apply {
            text = formatClock(0L)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTotal = TextView(this).apply {
            text = formatClock(durationMs)
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
        }
        timeRow.addView(tvCur)
        timeRow.addView(tvTotal)
        seekCol.addView(seek)
        seekCol.addView(timeRow)
        playerCard.addView(seekCol)
        content.addView(playerCard)
        val note = TextView(this).apply {
            text = "Preview the audio, then press Save."
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        content.addView(note)
        // ExoPlayer for the audio preview (Media3 is already a
        // dependency — there is no MediaPlayer anywhere in the app).
        val player = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(out)))
            playWhenReady = false
            prepare()
        }
        audioResultPlayer = player
        var userSeeking = false
        val tickHandler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (audioResultPlayer == null) return
                try {
                    val pos = player.currentPosition
                    if (!userSeeking) seek.progress = pos.toInt().coerceIn(0, seek.max)
                    tvCur.text = formatClock(pos)
                    playBtn.text = if (player.isPlaying) "❚❚" else "▶"
                } catch (_: Throwable) {
                }
                tickHandler.postDelayed(this, 500)
            }
        }
        audioResultTick = tick
        audioResultHandler = tickHandler
        tickHandler.post(tick)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    val d = player.duration
                    if (d > 0) {
                        seek.max = d.toInt()
                        tvTotal.text = formatClock(d)
                    }
                } else if (state == Player.STATE_ENDED) {
                    try {
                        player.seekTo(0)
                        player.pause()
                    } catch (_: Throwable) {
                    }
                    playBtn.text = "▶"
                }
            }
        })
        playBtn.setOnClickListener {
            try {
                if (player.isPlaying) player.pause() else player.play()
                playBtn.text = if (player.isPlaying) "❚❚" else "▶"
            } catch (_: Throwable) {
            }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvCur.text = formatClock(p.toLong())
                    try {
                        player.seekTo(p.toLong())
                    } catch (_: Throwable) {
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
        // Spacer pins Back + Save to the bottom.
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })
        val backBtn = popupPillButton("Back", false) {
            showAudioScreen()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            )
        }
        content.addView(backBtn)
        val saveBtn = actionButton("Save") {
            saveVideoAndShow(out, "Music/Hakunaa", fmt.label, fmt.mime, MediaStoreSaver.Kind.AUDIO)
        }
        content.addView(saveBtn)
    }

    // ------------------------------------------------------------------
    // Video Cutter — timeline + handles (SeekBar + Set Start/End),
    // stream copy (no re-encode). Splitter lives inside Cutter.
    // ------------------------------------------------------------------

    private var cutStartMs = 0L
    private var cutEndMs = 0L

    private fun showCutterScreen() {
        // v0.1.200: FIXED screen (no ScrollView) — everything on
        // ONE screen: compact file card, 300dp preview, 96dp
        // timeline, Start/Clip/End row, Cut Clip pinned to the
        // bottom. The Split row was REMOVED (user 2026-11-28) —
        // its space went to the taller preview (220dp -> 300dp).
        val content = newFixedToolOverlay("Video Cutter")
        val card = fileInfoCard()
        (card.getChildAt(0) as? TextView)?.let {
            it.maxLines = 1
            it.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        content.addView(card)
        val durationMs = probeInfo?.durationMs ?: 0L
        cutStartMs = 0L
        cutEndMs = durationMs
        // Preview 300dp — tap toggles play/pause in place.
        val preview = ExoVideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(300)
            ).apply { topMargin = dp(12) }
        }
        inputUri?.let { preview.setVideoURI(it) }
        content.addView(preview)
        activeVideoViews.add(preview)
        // Professional controls (v0.1.201, user 2026-11-28): a
        // control row between the preview and the timeline — the
        // gap above it is the space the user asked for. Play/Pause
        // + Reset only (user 2026-11-28 19:03 IST: the live time
        // next to Play is removed — the Start/Clip/End row below
        // the timeline already shows the times).
        val playBtn = PlayPauseButton(this).apply {
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        }
        fun refreshPlayState() {
            try {
                playBtn.playing = preview.isPlaying()
            } catch (_: Throwable) {
            }
        }
        fun cutterPill(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.hakunaa_text))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(1), Color.parseColor("#CFD8DC"))
                }
                setPadding(dp(14), dp(8), dp(14), dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
            }
        }
        // Reset gets its real listener below (after trimView and
        // refreshTimes exist); the empty lambda is replaced there.
        val resetBtn = cutterPill("Reset") {}
        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        playBtn.setOnClickListener {
            try {
                if (preview.isPlaying()) preview.pause() else preview.start()
                refreshPlayState()
            } catch (_: Throwable) {
            }
        }
        controlsRow.addView(playBtn)
        controlsRow.addView(resetBtn)
        content.addView(controlsRow)
        preview.setOnClickListener {
            try {
                if (preview.isPlaying()) preview.pause() else preview.start()
                refreshPlayState()
            } catch (_: Throwable) {
            }
        }
        // Timeline with drag handles (v0.1.198 Fix 6).
        val trimView = VideoTrimView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96)
            ).apply { topMargin = dp(8) }
            this.durationMs = durationMs
            startMs = 0L
            endMs = durationMs
        }
        content.addView(trimView)
        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val tvStart = TextView(this).apply {
            text = "Start  00:00"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvClip = TextView(this).apply {
            text = "Clip  ${formatClock(durationMs)}"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(pinkDark)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvEnd = TextView(this).apply {
            text = "End  ${formatClock(durationMs)}"
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        timeRow.addView(tvStart)
        timeRow.addView(tvClip)
        timeRow.addView(tvEnd)
        content.addView(timeRow)
        fun refreshTimes() {
            tvStart.text = "Start  ${formatClock(cutStartMs)}"
            tvClip.text = "Clip  ${formatClock(cutEndMs - cutStartMs)}"
            tvEnd.text = "End  ${formatClock(cutEndMs)}"
        }
        // Reset (control row): full video again — handles back to
        // the ends, preview back to 0, times refreshed.
        resetBtn.setOnClickListener {
            try {
                if (preview.isPlaying()) preview.pause()
                trimView.startMs = 0L
                trimView.endMs = durationMs
                cutStartMs = 0L
                cutEndMs = durationMs
                refreshTimes()
                preview.seekTo(0)
                refreshPlayState()
            } catch (_: Throwable) {
            }
        }
        trimView.onRangeChanged = { s, e ->
            cutStartMs = s
            cutEndMs = e
            refreshTimes()
            try {
                preview.seekTo(s.toInt())
            } catch (_: Throwable) {
            }
        }
        // Timeline thumbnails — extracted in the background; the
        // timeline stays usable (grey strip) until they arrive.
        Thread {
            try {
                val input = inputFile
                if (input != null && durationMs > 0) {
                    val thumbDir = File(cacheDir, "hakunaa_trim_${System.currentTimeMillis()}").apply { mkdirs() }
                    VideoToolsEngine.extractThumbnails(input.absolutePath, thumbDir, 8, durationMs) { files ->
                        runOnUiThread {
                            try {
                                val bmps = files.mapNotNull { f ->
                                    try {
                                        BitmapFactory.decodeFile(f.absolutePath)
                                    } catch (_: Throwable) {
                                        null
                                    }
                                }
                                if (bmps.isNotEmpty()) {
                                    overlayBitmaps.addAll(bmps)
                                    trimView.setThumbnails(bmps)
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }.start()
        val pv = addProgressViews(content)
        // v0.1.200: spacer pins "Cut Clip" to the bottom (the Split
        // row was removed — its space went to the taller preview).
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        content.addView(spacer)
        val cutBtn = actionButton("Cut Clip") {
            if (cutEndMs <= cutStartMs) {
                toast("End must be after Start")
                return@actionButton
            }
            runCut(pv)
        }
        content.addView(cutBtn)
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



    // ------------------------------------------------------------------
    // Custom Thumbnail — pick a frame, set it as the video cover.
    // ------------------------------------------------------------------

    private var thumbFrameFile: File? = null
    private var thumbFrameMs = 1000L

    private fun showThumbnailScreen() {
        // v0.1.199: FIXED screen (no ScrollView) — everything on
        // ONE screen: compact file card, 150dp preview, SeekBar +
        // "Frame at", compact source cards, YOUR THUMBNAIL as a
        // SMALL square beside the Capture pill + "Ready" status,
        // "Set as Cover & Save" pinned bottom.
        val content = newFixedToolOverlay("Custom Thumbnail")
        val card = fileInfoCard()
        (card.getChildAt(0) as? TextView)?.let {
            it.maxLines = 1
            it.ellipsize = android.text.TextUtils.TruncateAt.END
        }
        content.addView(card)
        val durationMs = probeInfo?.durationMs ?: 0L
        thumbFrameFile = null
        thumbFrameMs = (durationMs / 4).coerceAtLeast(0L)
        val preview = ExoVideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150)
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
        // CHOOSE THUMBNAIL — two sources (v0.1.198 Fix 7).
        content.addView(sectionLabel("CHOOSE THUMBNAIL"))
        var thumbSource = 0 // 0 From Video, 1 From Gallery
        val sourceCards = mutableListOf<FrameLayout>()
        fun paintSourceCards() {
            for (i in sourceCards.indices) {
                val sel = i == thumbSource
                sourceCards[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(12).toFloat()
                    if (sel) {
                        setColor(pinkCard)
                        setStroke(dp(2), pinkBorder)
                    } else {
                        setColor(Color.WHITE)
                        setStroke(dp(1), Color.parseColor("#CFD8DC"))
                    }
                }
            }
        }
        fun sourceCard(icon: String, title: String, sub: String, isNew: Boolean): FrameLayout {
            val card = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(104), 1f)
                isClickable = true
                isFocusable = true
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            col.addView(TextView(this).apply {
                text = icon
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(pinkDark)
            })
            col.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.hakunaa_text))
                setPadding(0, dp(4), 0, 0)
            })
            col.addView(TextView(this).apply {
                text = sub
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.hakunaa_text_secondary))
            })
            card.addView(col)
            if (isNew) {
                val badge = TextView(this).apply {
                    text = "NEW"
                    textSize = 10f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(9).toFloat()
                        setColor(getColor(R.color.hakunaa_success))
                    }
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        setMargins(0, dp(6), dp(6), 0)
                    }
                }
                card.addView(badge)
            }
            sourceCards.add(card)
            return card
        }
        val cardsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val cardVideo = sourceCard("▶", "From Video", "Capture a frame", false)
        val cardGallery = sourceCard("🖼", "From Gallery", "Pick any photo", true)
        (cardVideo.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(8)
        (cardGallery.layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        cardsRow.addView(cardVideo)
        cardsRow.addView(cardGallery)
        content.addView(cardsRow)
        // YOUR THUMBNAIL — SMALL square preview (80dp) beside the
        // "Capture This Frame" pill + status (v0.1.199). The old
        // full-width 180dp preview is gone.
        content.addView(sectionLabel("YOUR THUMBNAIL"))
        val thumbRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val framePreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(80), dp(80))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(getColor(R.color.hakunaa_outline))
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
        }
        thumbRow.addView(framePreview)
        // Progress views: the progress bar stays hidden in the
        // content (0 height); the status lives in the right column
        // under the Capture pill ("Ready"); the result line sits
        // below the row, hidden until a cover is set.
        val pv = progressViews()
        content.addView(pv.progress)
        val thumbCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        val captureBtn = popupPillButton("Capture This Frame", false) {
            runCaptureFrame(pv, framePreview)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            )
        }
        thumbCol.addView(captureBtn)
        pv.status.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        thumbCol.addView(pv.status)
        thumbRow.addView(thumbCol)
        content.addView(thumbRow)
        pv.result.visibility = View.GONE
        content.addView(pv.result)
        // Spacer pins "Set as Cover & Save" to the bottom.
        content.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })
        val saveBtn = actionButton("Set as Cover & Save") {
            runAttachCover(pv)
        }
        content.addView(saveBtn)
        // Gallery pick (v0.1.198 Fix 7): copy to cache, SCALE to
        // the video's size (attachCover does no scaling — a raw
        // 12 MP photo would bloat the MP4), then use it as the cover.
        fun pickGalleryImage(preview: ImageView, pv: ProgressViews) {
            singleImageCallback = { uri ->
                Thread {
                    try {
                        val picked = FileUtils.copyUriToCache(
                            this, uri, "hakunaa_cover_pick_${System.currentTimeMillis()}.jpg"
                        )
                        val vw = probeInfo?.width ?: 0
                        val vh = probeInfo?.height ?: 0
                        val maxEdge = if (vw > 0 && vh > 0) Math.max(vw, vh) else 1080
                        var scaled: File? = null
                        try {
                            val bmp = BitmapFactory.decodeFile(picked.absolutePath)
                            if (bmp != null) {
                                val longEdge = Math.max(bmp.width, bmp.height)
                                val out = File(cacheDir, "hakunaa_cover_scaled_${System.currentTimeMillis()}.jpg")
                                if (longEdge <= maxEdge) {
                                    java.io.FileOutputStream(out).use {
                                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                                    }
                                } else {
                                    val scale = maxEdge.toFloat() / longEdge.toFloat()
                                    val nw = (bmp.width * scale).toInt().coerceAtLeast(2)
                                    val nh = (bmp.height * scale).toInt().coerceAtLeast(2)
                                    val small = Bitmap.createScaledBitmap(bmp, nw, nh, true)
                                    java.io.FileOutputStream(out).use {
                                        small.compress(Bitmap.CompressFormat.JPEG, 90, it)
                                    }
                                    small.recycle()
                                }
                                bmp.recycle()
                                scaled = out
                            }
                        } catch (_: Throwable) {
                            scaled = null
                        }
                        val result = scaled
                        runOnUiThread {
                            if (result != null && result.exists()) {
                                thumbFrameFile = result
                                try {
                                    val bmp = BitmapFactory.decodeFile(result.absolutePath)
                                    if (bmp != null) {
                                        overlayBitmaps.add(bmp)
                                        preview.setImageBitmap(bmp)
                                    }
                                } catch (_: Throwable) {
                                }
                                pv.status.text = "Photo picked — tap Set as Cover & Save"
                            } else {
                                toast("Could not read that photo")
                            }
                        }
                    } catch (_: Throwable) {
                        runOnUiThread { toast("Could not read that photo") }
                    }
                }.start()
            }
            pickSingleImage.launch("image/*")
        }
        // Source selection.
        paintSourceCards()
        cardVideo.setOnClickListener {
            thumbSource = 0
            paintSourceCards()
            captureBtn.visibility = View.VISIBLE
        }
        cardGallery.setOnClickListener {
            thumbSource = 1
            paintSourceCards()
            captureBtn.visibility = View.GONE
            pickGalleryImage(framePreview, pv)
        }
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
            // v0.1.198 Fix 7: two sources now (video frame or
            // gallery photo) — the prompt names neither.
            toast("Choose a thumbnail first")
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
                    // v0.1.199: the result line is GONE in the fixed
                    // Thumbnail layout until a cover is set.
                    pv.result.visibility = View.VISIBLE
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
    // Video Crop — aspect presets + draggable frame (v0.1.200 rework:
    // the 12 social presets are gone; 8 aspect ratios in a
    // horizontal chip row, Free = full frame, no aspect lock).
    // ------------------------------------------------------------------

    private data class CropPreset(
        val name: String,
        val ratio: Float // width / height; 0 = Free (no aspect lock)
    )

    private val cropPresets = listOf(
        CropPreset("Free", 0f),
        CropPreset("1:1", 1f),
        CropPreset("16:9", 16f / 9f),
        CropPreset("9:16", 9f / 16f),
        CropPreset("4:5", 4f / 5f),
        CropPreset("4:3", 4f / 3f),
        CropPreset("3:2", 3f / 2f),
        CropPreset("21:9", 21f / 9f)
    )
    private var cropPresetIdx = 0 // default = Free (full frame)
    private var cropRect = intArrayOf(0, 0, 0, 0) // x, y, w, h in video pixels

    /** Tall preview height for the Crop/Rotate rework (v0.1.200):
     * the preview takes the available vertical space (screen height
     * minus the fixed chrome of a tool screen), capped at 520dp.
     * A post-layout fit pass grows/shrinks it to the exact fit. */
    private fun tallPreviewHeightDp(): Int {
        val dm = resources.displayMetrics
        val screenHdp = dm.heightPixels / dm.density
        return (screenHdp - 500f).toInt().coerceIn(280, 520)
    }

    private fun showCropScreen() {
        val content = newFixedToolOverlay("Video Crop")
        content.addView(fileInfoCard())
        // Fresh frame for this video (the rect is rebuilt when the
        // preview frame arrives — a stale rect from another video
        // must never be cropped).
        cropRect = intArrayOf(0, 0, 0, 0)
        // v0.1.200 rework: LARGE preview — the box sizes to the
        // video's aspect (no black bars) and takes the available
        // vertical space (tallPreviewHeightDp, refined by the fit
        // pass below). Falls back to a 320dp box when the size is
        // unknown.
        val vw = probeInfo?.width ?: 0
        val vh = probeInfo?.height ?: 0
        val maxBoxW = resources.displayMetrics.widthPixels - dp(32) // overlay side padding
        val maxBoxH = dp(tallPreviewHeightDp())
        val (boxW, boxH) = if (vw > 0 && vh > 0) {
            val ratio = vw.toFloat() / vh.toFloat()
            if (ratio >= maxBoxW.toFloat() / maxBoxH.toFloat()) {
                maxBoxW to (maxBoxW / ratio).toInt()
            } else {
                (maxBoxH * ratio).toInt() to maxBoxH
            }
        } else {
            maxBoxW to dp(320)
        }
        val previewBox = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(getColor(R.color.hakunaa_outline))
                cornerRadius = dp(12).toFloat()
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(boxW, boxH).apply {
                topMargin = dp(12)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val cropView = VideoCropView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        previewBox.addView(cropView)
        content.addView(previewBox)
        // Output row — live crop size + the selected aspect chip.
        val outputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val outputLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        outputLeft.addView(TextView(this).apply {
            text = "Output"
            textSize = 12f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
        })
        val outputValue = TextView(this).apply {
            text = if (vw > 0 && vh > 0) "$vw × $vh" else "—"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
        }
        outputLeft.addView(outputValue)
        outputRow.addView(outputLeft)
        val aspectChip = TextView(this).apply {
            text = cropPresets[cropPresetIdx].name
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(pinkDark)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(2), pinkBorder)
            }
        }
        outputRow.addView(aspectChip)
        content.addView(outputRow)
        fun updateResult() {
            val preset = cropPresets[cropPresetIdx]
            val r = cropRect
            outputValue.text = if (r[2] > 0 && r[3] > 0) {
                "${r[2]} × ${r[3]}"
            } else if (vw > 0 && vh > 0) {
                "$vw × $vh"
            } else {
                "—"
            }
            aspectChip.text = preset.name
        }
        cropView.onFrameChanged = { x, y, cw, ch ->
            cropRect = intArrayOf(x, y, cw, ch)
            updateResult()
        }
        // Aspect presets — horizontal scrollable chip row.
        content.addView(sectionLabel("ASPECT RATIO"))
        val chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        chipsScroll.addView(chipsRow)
        content.addView(chipsScroll)
        val presetChips = mutableListOf<TextView>()
        fun paintPresetChips() {
            for ((i, c) in presetChips.withIndex()) setChipSelected(c, i == cropPresetIdx)
        }
        fun applyCropPreset() {
            val preset = cropPresets[cropPresetIdx]
            if (preset.ratio <= 0f) cropView.setFree() else cropView.setAspect(preset.ratio)
            updateResult()
        }
        for ((i, preset) in cropPresets.withIndex()) {
            val c = chip(preset.name, i == cropPresetIdx) {
                cropPresetIdx = i
                paintPresetChips()
                applyCropPreset()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            presetChips.add(c)
            chipsRow.addView(c)
        }
        val hint = TextView(this).apply {
            text = "Drag the frame to move  |  Drag a corner to resize"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        content.addView(hint)
        // Default preset = Free (full frame).
        applyCropPreset()
        // Extract one frame for the preview (background thread;
        // grey placeholder until it arrives).
        Thread {
            try {
                val input = inputFile
                val duration = probeInfo?.durationMs ?: 0L
                if (input != null && duration > 0) {
                    val frameFile = File(
                        cacheDir, "hakunaa_crop_frame_${System.currentTimeMillis()}.jpg"
                    )
                    VideoToolsEngine.extractFrame(
                        input.absolutePath, frameFile.absolutePath, duration / 4
                    ) { ok, _ ->
                        runOnUiThread {
                            if (ok && frameFile.exists()) {
                                try {
                                    val bmp = BitmapFactory.decodeFile(frameFile.absolutePath)
                                    if (bmp != null) {
                                        overlayBitmaps.add(bmp)
                                        cropView.setImage(bmp)
                                    }
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }.start()
        val pv = addProgressViews(content)
        // Spacer pins "Crop & Save" to the bottom.
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        content.addView(spacer)
        val btn = actionButton("Crop & Save") { runCrop(pv) }
        content.addView(btn)
        // Fit pass: after layout, give the preview box ALL remaining
        // vertical space so the fixed screen fits exactly on any
        // phone (the initial tallPreviewHeightDp is only an estimate).
        content.post {
            val avail = content.height
            if (avail <= 0) return@post
            var used = 0
            for (i in 0 until content.childCount) {
                val ch = content.getChildAt(i)
                if (ch === previewBox || ch === spacer) continue
                val lp = ch.layoutParams as? LinearLayout.LayoutParams
                used += ch.height + (lp?.topMargin ?: 0) + (lp?.bottomMargin ?: 0)
            }
            val boxLp = previewBox.layoutParams as LinearLayout.LayoutParams
            var targetH = avail - used - boxLp.topMargin - boxLp.bottomMargin
            targetH = targetH.coerceIn(dp(200), dp(520))
            // Keep the video's aspect (no black bars); cap the width.
            val ratio = if (vw > 0 && vh > 0) vw.toFloat() / vh.toFloat() else 16f / 9f
            var newBoxW = (targetH * ratio).toInt()
            var newBoxH = targetH
            if (newBoxW > maxBoxW) {
                newBoxW = maxBoxW
                newBoxH = (newBoxW / ratio).toInt()
            }
            if (newBoxW != previewBox.width || newBoxH != previewBox.height) {
                previewBox.layoutParams = LinearLayout.LayoutParams(newBoxW, newBoxH).apply {
                    topMargin = dp(12)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                previewBox.requestLayout()
            }
        }
    }

    private fun runCrop(pv: ProgressViews) {
        val input = inputFile ?: return
        if (!engineGuard()) return
        val preset = cropPresets[cropPresetIdx]
        val p = probeInfo
        val w = p?.width ?: 0
        val h = p?.height ?: 0
        val r = cropRect
        if (r[2] <= 0 || r[3] <= 0) {
            // The preview frame has not arrived yet.
            toast("Loading the video frame — please wait a moment")
            return
        }
        if (w <= 0 || h <= 0 || r[2] > w || r[3] > h ||
            r[0] + r[2] > w || r[1] + r[3] > h
        ) {
            toast("Could not read this video's size")
            return
        }
        if (r[2] == w && r[3] == h && r[0] == 0 && r[1] == 0) {
            toast("The frame already covers the whole video")
            return
        }
        pv.progress.visibility = View.VISIBLE
        pv.progress.progress = 0
        pv.status.text = if (preset.ratio <= 0f) {
            "Cropping ${r[2]}×${r[3]}…"
        } else {
            "Cropping to ${preset.name} (${r[2]}×${r[3]})…"
        }
        pv.result.text = ""
        val out = File(cacheDir, "hakunaa_crop_${System.currentTimeMillis()}.mp4")
        VideoToolsEngine.crop(
            input.absolutePath, out.absolutePath, r[2], r[3], r[0], r[1],
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
