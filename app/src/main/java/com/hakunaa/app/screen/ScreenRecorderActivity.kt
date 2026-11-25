package com.hakunaa.app.screen

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.StatusBar
import com.hakunaa.app.video.ExoVideoView
import com.hakunaa.app.video.VideoToolsActivity
import java.io.File

/**
 * Screen Recorder UI (user 2026-11-25).
 *
 * Flow: Start → (mic/internal audio needs RECORD_AUDIO) →
 * system screen-capture permission → 3-2-1 countdown in the app
 * → recording starts (service + notification). While recording
 * the app shows a live timer + Pause + Stop, professional.
 * Stop (notification or in-app) → the app auto-opens on the
 * result screen: preview + View / Share / Compress / Done.
 */
class ScreenRecorderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SHOW_RESULT = "show_result"
        const val EXTRA_ERROR = "error"
    }

    private var audioMode = ScreenRecorderService.AUDIO_INTERNAL
    private var qualityMode = ScreenRecorderService.QUALITY_1080P
    private var mode = "idle" // idle | countdown | recording | result
    private var resultUri: String? = null
    private var resultPath: String? = null
    private var resultName: String? = null
    private var errorMessage: String? = null

    private lateinit var content: LinearLayout
    private var timerView: TextView? = null
    private var pauseBtn: TextView? = null
    private var countdownView: TextView? = null
    private var resultPreview: ExoVideoView? = null
    private var fullScreenRoot: FrameLayout? = null

    private val handler = Handler(Looper.getMainLooper())
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ScreenRecorderService.BROADCAST_STATE) return
            val state = intent.getStringExtra(ScreenRecorderService.EXTRA_STATE) ?: return
            val elapsed = intent.getLongExtra(ScreenRecorderService.EXTRA_ELAPSED, 0)
            when (state) {
                "recording", "paused" -> {
                    if (mode != "recording") showRecordingScreen()
                    updateTimer(elapsed)
                    updatePauseBtn(state == "paused")
                }
                "stopped" -> {
                    if (mode == "recording") showIdleScreen()
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) requestCapture() else toast("Microphone permission is needed for audio")
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            runCountdown(result.resultCode, result.data!!)
        } else {
            toast("Screen capture permission is needed to record")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StatusBar.setup(this, true)
        buildUi()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, IntentFilter(ScreenRecorderService.BROADCAST_STATE), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, IntentFilter(ScreenRecorderService.BROADCAST_STATE))
        }
        if (intent?.getBooleanExtra(EXTRA_SHOW_RESULT, false) == true) {
            resultUri = intent.getStringExtra(ScreenRecorderService.EXTRA_RESULT_URI)
            resultPath = intent.getStringExtra(ScreenRecorderService.EXTRA_RESULT_PATH)
            resultName = intent.getStringExtra(ScreenRecorderService.EXTRA_RESULT_NAME)
            showResultScreen()
        } else if (ScreenRecorderService.isRecording) {
            showRecordingScreen()
            updateTimer(ScreenRecorderService.elapsedSeconds())
            updatePauseBtn(ScreenRecorderService.isPaused)
        } else {
            errorMessage = intent?.getStringExtra(EXTRA_ERROR)
            showIdleScreen()
            errorMessage?.let { toast(it) }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra(EXTRA_SHOW_RESULT, false) == true) {
            resultUri = intent.getStringExtra(ScreenRecorderService.EXTRA_RESULT_URI)
            resultPath = intent.getStringExtra(ScreenRecorderService.EXTRA_RESULT_PATH)
            resultName = intent.getStringExtra(ScreenRecorderService.EXTRA_RESULT_NAME)
            closeFullScreen()
            showResultScreen()
        } else if (intent?.hasExtra(EXTRA_ERROR) == true) {
            errorMessage = intent.getStringExtra(EXTRA_ERROR)
            closeFullScreen()
            showIdleScreen()
            errorMessage?.let { toast(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (mode == "recording" || ScreenRecorderService.isRecording) {
            if (mode != "recording") showRecordingScreen()
            updateTimer(ScreenRecorderService.elapsedSeconds())
            updatePauseBtn(ScreenRecorderService.isPaused)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(stateReceiver) } catch (_: Throwable) {}
        handler.removeCallbacksAndMessages(null)
        resultPreview?.stopPlayback()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (fullScreenRoot != null) {
            closeFullScreen()
            return
        }
        // While recording, Back minimises instead of leaving — the
        // recording keeps running in the service.
        if (mode == "recording") {
            moveTaskToBack(true)
            return
        }
        super.onBackPressed()
    }

    // ---- UI ----

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.hakunaa_surface))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            setPadding(dp(4), dp(4), dp(16), dp(4))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "Screen Recorder"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(close)
        header.addView(title)
        root.addView(header)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scroll.addView(content)
        root.addView(scroll)
        setContentView(root)
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) }
        }
    }

    private fun pillButton(text: String, filled: Boolean, onClick: () -> Unit): TextView {
        val pink = Color.parseColor("#EC407A")
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            if (filled) {
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(28).toFloat()
                    setColor(pink)
                }
            } else {
                setTextColor(pink)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(28).toFloat()
                    setColor(Color.WHITE)
                    setStroke(dp(2), pink)
                }
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { topMargin = dp(12) }
        }
    }

    private fun infoText(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
    }

    // ---- Idle (start) screen ----

    private fun showIdleScreen() {
        mode = "idle"
        content.removeAllViews()
        timerView = null
        pauseBtn = null
        countdownView = null
        resultPreview = null
        errorMessage?.let { msg ->
            val err = TextView(this).apply {
                text = msg
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#E53935"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            content.addView(err)
        }
        content.addView(infoText("Record your screen — apps, games, video calls. No watermark, no lag. The video saves to Movies/Hakunaa."))
        content.addView(sectionLabel("AUDIO"))
        val audioRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val modes = listOf(
            ScreenRecorderService.AUDIO_INTERNAL to "Internal",
            ScreenRecorderService.AUDIO_MIC to "Microphone",
            ScreenRecorderService.AUDIO_NONE to "No audio"
        )
        val chips = mutableListOf<TextView>()
        for ((value, label) in modes) {
            val chip = TextView(this).apply {
                text = label
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
                setOnClickListener {
                    audioMode = value
                    for ((i, c) in chips.withIndex()) setAudioChip(c, modes[i].first == audioMode)
                }
            }
            chips.add(chip)
            audioRow.addView(chip)
        }
        for ((i, c) in chips.withIndex()) setAudioChip(c, modes[i].first == audioMode)
        content.addView(audioRow)
        content.addView(infoText("Internal audio records the sound from apps and games (Android 10+). Microphone records your voice."))
        content.addView(sectionLabel("QUALITY"))
        val qualityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val qualities = listOf(
            ScreenRecorderService.QUALITY_1080P to "1080p",
            ScreenRecorderService.QUALITY_720P to "720p",
            ScreenRecorderService.QUALITY_480P to "480p"
        )
        val qChips = mutableListOf<TextView>()
        for ((value, label) in qualities) {
            val chip = TextView(this).apply {
                text = label
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginEnd = dp(6) }
                setOnClickListener {
                    qualityMode = value
                    for ((i, c) in qChips.withIndex()) setAudioChip(c, qualities[i].first == qualityMode)
                }
            }
            qChips.add(chip)
            qualityRow.addView(chip)
        }
        for ((i, c) in qChips.withIndex()) setAudioChip(c, qualities[i].first == qualityMode)
        content.addView(qualityRow)
        content.addView(infoText("1080p is the sharpest (~10 MB/min). 720p is smaller (~5 MB/min). 480p is the smallest (~2 MB/min)."))
        content.addView(sectionLabel("TIPS"))
        content.addView(infoText("• Recording stops automatically after 30 minutes.\n• Pull down the notification shade anytime to Pause or Stop.\n• The video is compressed only after you stop — so recording stays smooth."))
        val startBtn = pillButton("●  Start Recording", true) { startFlow() }
        startBtn.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
        ).apply { topMargin = dp(24) }
        content.addView(startBtn)
    }

    private fun setAudioChip(chip: TextView, selected: Boolean) {
        val pink = Color.parseColor("#EC407A")
        if (selected) {
            chip.setTextColor(Color.WHITE)
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(pink)
            }
        } else {
            chip.setTextColor(getColor(R.color.hakunaa_text))
            chip.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(2), Color.parseColor("#CFD8DC"))
            }
        }
    }

    // ---- Start flow: permission → countdown → service ----

    private fun startFlow() {
        if (audioMode != ScreenRecorderService.AUDIO_NONE &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        requestCapture()
    }

    private fun requestCapture() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun runCountdown(resultCode: Int, data: Intent) {
        mode = "countdown"
        content.removeAllViews()
        val cd = TextView(this).apply {
            text = "3"
            textSize = 96f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#EC407A"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(120) }
        }
        countdownView = cd
        content.addView(cd)
        val note = infoText("Get ready — recording starts after the countdown.")
        note.gravity = Gravity.CENTER
        content.addView(note)
        var n = 3
        val step = object : Runnable {
            override fun run() {
                if (mode != "countdown") return
                if (n <= 0) {
                    startService(resultCode, data)
                    return
                }
                cd.text = "$n"
                n--
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(step)
    }

    private fun startService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenRecorderService::class.java).apply {
            action = ScreenRecorderService.ACTION_START
            putExtra(ScreenRecorderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecorderService.EXTRA_DATA, data)
            putExtra(ScreenRecorderService.EXTRA_AUDIO, audioMode)
            putExtra(ScreenRecorderService.EXTRA_QUALITY, qualityMode)
        }
        ContextCompat.startForegroundService(this, intent)
        showRecordingScreen()
        // Minimise so the user can record other apps. The
        // notification keeps the timer + Stop; returning to the app
        // shows this recording screen again.
        handler.postDelayed({ moveTaskToBack(true) }, 400)
    }

    // ---- Recording screen (live timer + Pause + Stop) ----

    private fun showRecordingScreen() {
        mode = "recording"
        content.removeAllViews()
        countdownView = null
        resultPreview = null
        val status = TextView(this).apply {
            text = "●  Recording"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#E53935"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(32) }
        }
        content.addView(status)
        val timer = TextView(this).apply {
            text = "00:00"
            textSize = 56f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        timerView = timer
        content.addView(timer)
        content.addView(infoText("Recording is running. The notification also shows the timer — pull it down to Pause or Stop.").apply {
            gravity = Gravity.CENTER
        })
        val pause = pillButton("❚❚  Pause", false) {
            sendServiceAction(
                if (ScreenRecorderService.isPaused) ScreenRecorderService.ACTION_RESUME
                else ScreenRecorderService.ACTION_PAUSE
            )
        }
        pauseBtn = pause
        content.addView(pause)
        val stop = pillButton("■  Stop", true) {
            sendServiceAction(ScreenRecorderService.ACTION_STOP)
        }
        content.addView(stop)
    }

    private fun updateTimer(elapsed: Long) {
        timerView?.text = String.format("%02d:%02d", elapsed / 60, elapsed % 60)
    }

    private fun updatePauseBtn(paused: Boolean) {
        pauseBtn?.text = if (paused) "▶  Resume" else "❚❚  Pause"
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, ScreenRecorderService::class.java).apply { this.action = action }
        ContextCompat.startForegroundService(this, intent)
    }

    // ---- Result screen ----

    private fun showResultScreen() {
        mode = "result"
        content.removeAllViews()
        timerView = null
        pauseBtn = null
        countdownView = null
        resultPreview = null
        val path = resultPath
        val file = if (path != null) File(path) else null
        if (file == null || !file.exists()) {
            content.addView(infoText("The recording was saved to Movies/Hakunaa."))
            val done = pillButton("Done", true) { finish() }
            content.addView(done)
            return
        }
        val preview = ExoVideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200)
            ).apply { topMargin = dp(8) }
        }
        preview.setVideoURI(Uri.fromFile(file))
        preview.setOnClickListener { showFullScreen(Uri.fromFile(file)) }
        content.addView(preview)
        resultPreview = preview
        val name = TextView(this).apply {
            text = resultName ?: file.name
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.hakunaa_text))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        content.addView(name)
        val size = TextView(this).apply {
            text = FileUtils.formatSize(file.length()) + "  •  Movies/Hakunaa"
            textSize = 13f
            setTextColor(getColor(R.color.hakunaa_text_secondary))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
        }
        content.addView(size)
        val viewBtn = pillButton("▶  View", false) {
            showFullScreen(Uri.fromFile(file))
        }
        content.addView(viewBtn)
        val shareBtn = pillButton("Share", false) {
            val uri = resultUri?.let { Uri.parse(it) }
            if (uri != null) {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Share recording"))
            } else {
                toast("Share is not available for this file")
            }
        }
        content.addView(shareBtn)
        val compressBtn = pillButton("Compress", false) {
            startActivity(Intent(this, VideoToolsActivity::class.java))
            toast("Pick the recording from Movies/Hakunaa to compress it")
        }
        content.addView(compressBtn)
        val doneBtn = pillButton("Done", true) { finish() }
        content.addView(doneBtn)
    }

    // ---- Simple full-screen player ----

    private fun showFullScreen(uri: Uri) {
        closeFullScreen()
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
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
        player.setVideoURI(uri)
        player.setOnClickListener {
            if (player.isPlaying()) player.pause() else player.start()
        }
        root.addView(player)
        val close = TextView(this).apply {
            text = "✕"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener { closeFullScreen() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = dp(24) }
        }
        root.addView(close)
        findViewById<ViewGroup>(android.R.id.content).addView(root)
        fullScreenRoot = root
        player.start()
    }

    private fun closeFullScreen() {
        val root = fullScreenRoot ?: return
        try {
            findViewById<ViewGroup>(android.R.id.content).removeView(root)
        } catch (_: Throwable) {
        }
        fullScreenRoot = null
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
