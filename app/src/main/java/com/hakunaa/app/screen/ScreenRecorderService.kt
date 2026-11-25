package com.hakunaa.app.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.hakunaa.app.R
import com.hakunaa.app.core.MediaStoreSaver
import java.io.File
import java.io.FileOutputStream

/**
 * Screen Recorder — foreground service (user 2026-11-25).
 *
 * 0 lag by design: the recording uses the phone's hardware video
 * encoder (MediaCodec via MediaRecorder) and NO compression runs
 * while recording — compression happens only AFTER Stop, on the
 * saved file. The notification carries the timer + Stop/Pause
 * (no floating dot). 30-min auto-stop. Internal audio (Android
 * 10+) is captured with AudioPlaybackCapture + AudioRecord and
 * muxed with FFmpeg after Stop; Mic uses the recorder's MIC
 * source; None records video only.
 */
class ScreenRecorderService : Service() {

    companion object {
        const val ACTION_START = "com.hakunaa.app.screen.START"
        const val ACTION_STOP = "com.hakunaa.app.screen.STOP"
        const val ACTION_PAUSE = "com.hakunaa.app.screen.PAUSE"
        const val ACTION_RESUME = "com.hakunaa.app.screen.RESUME"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        const val EXTRA_AUDIO = "audio"
        const val AUDIO_INTERNAL = "internal"
        const val AUDIO_MIC = "mic"
        const val AUDIO_NONE = "none"
        const val EXTRA_QUALITY = "quality"
        const val QUALITY_1080P = "1080p"
        const val QUALITY_720P = "720p"
        const val QUALITY_480P = "480p"

        // Broadcasts to ScreenRecorderActivity.
        const val BROADCAST_STATE = "com.hakunaa.app.screen.STATE"
        const val EXTRA_STATE = "state" // recording | paused | stopped
        const val EXTRA_ELAPSED = "elapsed" // seconds
        const val EXTRA_RESULT_URI = "result_uri"
        const val EXTRA_RESULT_PATH = "result_path"
        const val EXTRA_RESULT_NAME = "result_name"

        const val CHANNEL_ID = "hakunaa_screen_recorder_v2"
        const val NOTIF_ID = 9001
        const val MAX_SECONDS = 30 * 60 // 30-min auto-stop

        // Shared state — the activity reads this in onResume to show
        // the live recording screen when it returns to the app.
        @Volatile var isRecording = false
        @Volatile var isPaused = false
        @Volatile var startTimeMs = 0L
        @Volatile var pausedTotalMs = 0L
        @Volatile var pauseStartMs = 0L
        @Volatile var outputName: String? = null

        fun elapsedSeconds(): Long {
            if (!isRecording) return 0
            val now = System.currentTimeMillis()
            val paused = pausedTotalMs + if (isPaused) now - pauseStartMs else 0L
            return ((now - startTimeMs - paused) / 1000).coerceAtLeast(0)
        }
    }

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var recorder: MediaRecorder? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var outputFile: File? = null
    private var audioMode = AUDIO_INTERNAL
    private var qualityMode = QUALITY_1080P
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var audioPcmFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val elapsed = elapsedSeconds()
            updateNotification()
            broadcast("recording", elapsed)
            if (elapsed >= MAX_SECONDS) {
                stopRecording()
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                audioMode = intent.getStringExtra(EXTRA_AUDIO) ?: AUDIO_INTERNAL
                qualityMode = intent.getStringExtra(EXTRA_QUALITY) ?: QUALITY_1080P
                if (code != 0 && data != null) startRecording(code, data)
            }
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
        }
        return START_NOT_STICKY
    }

    /** Build + prepare a MediaRecorder for the given video size
     *  and bitrate. Throws if the size is not supported. */
    private fun buildRecorder(width: Int, height: Int, bitrate: Int): MediaRecorder {
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        rec.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (audioMode == AUDIO_MIC) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(bitrate)
            setVideoFrameRate(30)
            setVideoSize(width, height)
            if (audioMode == AUDIO_MIC) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
            }
            setOutputFile(outputFile!!.absolutePath)
            prepare()
        }
        return rec
    }

    private fun startRecording(resultCode: Int, data: Intent) {
        if (isRecording) return
        try {
            // Android 14+: the service MUST already be a foreground
            // service of type mediaProjection BEFORE getMediaProjection()
            // is called, or it throws SecurityException ("Media
            // projections require a foreground service of type ...").
            // So startForeground goes FIRST, then the projection.
            createChannel()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("Screen capture was not granted")
            // Android 14+: a MediaProjection.Callback MUST be
            // registered BEFORE createVirtualDisplay, or it throws
            // "Must register a callback before starting capture".
            // onStop fires if the system/user stops the projection —
            // stop the recording gracefully instead of crashing.
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }
            projection!!.registerCallback(projectionCallback!!, handler)
            val metrics = resources.displayMetrics
            val density = metrics.densityDpi
            val name = "hakunaa_record_${System.currentTimeMillis()}.mp4"
            outputName = name
            outputFile = File(cacheDir, name)
            // Video size + bitrate from the user's quality pick
            // (1080p / 720p / 480p). The target scales the native
            // screen to the chosen short side, never upscaling.
            // Fallback: if the chosen size fails at prepare(), try
            // the next lower quality. Some encoders reject an odd
            // portrait size (e.g. 1080x2400) — the fallback keeps
            // recording working on any phone.
            val nativeW = metrics.widthPixels
            val nativeH = metrics.heightPixels
            fun scaled(shortSide: Int): Pair<Int, Int> {
                val scale = minOf(1.0, shortSide.toDouble() / minOf(nativeW, nativeH))
                val w = maxOf(2, ((nativeW * scale).toInt() and 1.inv()))
                val h = maxOf(2, ((nativeH * scale).toInt() and 1.inv()))
                return w to h
            }
            val bitrateFor = mapOf(
                QUALITY_1080P to 6_000_000,
                QUALITY_720P to 3_000_000,
                QUALITY_480P to 1_500_000
            )
            val shortFor = mapOf(
                QUALITY_1080P to 1080,
                QUALITY_720P to 720,
                QUALITY_480P to 480
            )
            val order = when (qualityMode) {
                QUALITY_720P -> listOf(QUALITY_720P, QUALITY_480P)
                QUALITY_480P -> listOf(QUALITY_480P, QUALITY_720P)
                else -> listOf(QUALITY_1080P, QUALITY_720P, QUALITY_480P)
            }
            val candidates = order.map { q ->
                Triple(scaled(shortFor[q]!!).first, scaled(shortFor[q]!!).second, bitrateFor[q]!!)
            }.distinct()
            var lastError: Throwable? = null
            var chosen: Triple<Int, Int, Int>? = null
            for ((w, h, br) in candidates) {
                try {
                    recorder = buildRecorder(w, h, br)
                    chosen = Triple(w, h, br)
                    break
                } catch (t: Throwable) {
                    lastError = t
                    try { recorder?.release() } catch (_: Throwable) {}
                    recorder = null
                }
            }
            val (width, height) = chosen
                ?.let { it.first to it.second }
                ?: throw (lastError ?: IllegalStateException("Recorder could not start"))
            val surface = recorder!!.surface
            virtualDisplay = projection!!.createVirtualDisplay(
                "HakunaaRecorder", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )
            recorder!!.start()
            isRecording = true
            isPaused = false
            startTimeMs = System.currentTimeMillis()
            pausedTotalMs = 0L
            pauseStartMs = 0L
            if (audioMode == AUDIO_INTERNAL) startInternalAudio()
            handler.post(tick)
            broadcast("recording", 0)
        } catch (t: Throwable) {
            cleanup()
            broadcast("stopped", 0)
            // Never fail silently (user 2026-11-25): if recording
            // cannot start, open the app and tell the user the
            // reason, so the next fix can target it exactly.
            val reason = t.message?.takeIf { it.isNotBlank() }
                ?: t.javaClass.simpleName
            openError("Recording could not start: $reason")
        }
    }

    /** Open the app on the recorder screen with an error message. */
    private fun openError(message: String) {
        val errIntent = Intent(this, ScreenRecorderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ScreenRecorderActivity.EXTRA_ERROR, message)
        }
        try {
            startActivity(errIntent)
        } catch (_: Throwable) {
        }
    }

    private fun pauseRecording() {
        if (!isRecording || isPaused) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) recorder?.pause()
            isPaused = true
            pauseStartMs = System.currentTimeMillis()
            updateNotification()
            broadcast("paused", elapsedSeconds())
        } catch (_: Throwable) {
        }
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) recorder?.resume()
            pausedTotalMs += System.currentTimeMillis() - pauseStartMs
            isPaused = false
            updateNotification()
            broadcast("recording", elapsedSeconds())
        } catch (_: Throwable) {
        }
    }

    private fun stopRecording() {
        if (!isRecording) {
            stopSelf()
            return
        }
        isRecording = false
        isPaused = false
        handler.removeCallbacks(tick)
        stopInternalAudio()
        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }
        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Throwable) {
        }
        recorder = null
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        // Unregister the callback BEFORE stopping the projection,
        // or onStop() would fire and re-enter stopRecording().
        try {
            projectionCallback?.let { projection?.unregisterCallback(it) }
        } catch (_: Throwable) {
        }
        projectionCallback = null
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        // Mux + save + launch run on a background thread — the
        // FFmpeg mux can take a few seconds on a long recording and
        // must never block the main thread (ANR).
        Thread {
            // Internal audio: mux the captured PCM with the video.
            var videoFile = outputFile
            val pcm = audioPcmFile
            if (audioMode == AUDIO_INTERNAL && pcm != null && pcm.exists() && pcm.length() > 0 && videoFile != null) {
                videoFile = muxInternalAudio(videoFile!!, pcm)
            }
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Throwable) {
            }
            // Save to Movies/Hakunaa, then auto-open the app on the
            // result screen (user 2026-11-25).
            val name = outputName ?: "hakunaa_record.mp4"
            var uri: android.net.Uri? = null
            if (videoFile != null && videoFile.exists() && videoFile.length() > 0) {
                uri = MediaStoreSaver.save(this, videoFile, MediaStoreSaver.Kind.VIDEO, name)
            }
            broadcast("stopped", 0)
            val resultIntent = Intent(this, ScreenRecorderActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(ScreenRecorderActivity.EXTRA_SHOW_RESULT, true)
                putExtra(EXTRA_RESULT_URI, uri?.toString())
                putExtra(EXTRA_RESULT_PATH, videoFile?.absolutePath)
                putExtra(EXTRA_RESULT_NAME, name)
            }
            startActivity(resultIntent)
            stopSelf()
        }.start()
    }

    // ---- Internal audio (Android 10+) ----

    private fun startInternalAudio() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val proj = projection ?: return
            val config = AudioPlaybackCaptureConfiguration.Builder(proj)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(44_100)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setAudioPlaybackCaptureConfig(config)
                .setBufferSizeInBytes(AudioRecord.getMinBufferSize(44_100, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 2)
                .build()
            val pcmFile = File(cacheDir, "hakunaa_record_audio_${System.currentTimeMillis()}.pcm")
            audioPcmFile = pcmFile
            audioRecord = record
            record.startRecording()
            audioThread = Thread {
                try {
                    val buf = ByteArray(8192)
                    FileOutputStream(pcmFile).use { out ->
                        while (isRecording && !Thread.currentThread().isInterrupted) {
                            val n = record.read(buf, 0, buf.size)
                            if (n > 0) out.write(buf, 0, n) else if (n < 0) break
                        }
                    }
                } catch (_: Throwable) {
                }
            }.apply { start() }
        } catch (_: Throwable) {
            // Internal audio failed — video still records (no audio).
            audioRecord = null
            audioThread = null
        }
    }

    private fun stopInternalAudio() {
        try {
            audioThread?.interrupt()
            audioThread?.join(500)
        } catch (_: Throwable) {
        }
        audioThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null
    }

    /** Mux the captured PCM (44.1kHz stereo 16-bit) with the video.
     *  Video stream is copied (no re-encode, fast); audio → AAC. */
    private fun muxInternalAudio(video: File, pcm: File): File {
        val out = File(cacheDir, "hakunaa_record_muxed_${System.currentTimeMillis()}.mp4")
        return try {
            val cmd = "ffmpeg -y -i \"${video.absolutePath}\" -f s16le -ar 44100 -ac 2 -i \"${pcm.absolutePath}\" " +
                "-c:v copy -c:a aac -b:a 128k -shortest \"${out.absolutePath}\""
            val session = FFmpegKit.execute(cmd)
            if (ReturnCode.isSuccess(session.returnCode) && out.exists() && out.length() > 0) {
                try { video.delete() } catch (_: Throwable) {}
                out
            } else {
                video
            }
        } catch (_: Throwable) {
            video
        }
    }

    // ---- Notification ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Screen Recorder", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Shows the recording timer and Stop button." }
            manager.createNotificationChannel(channel)
        }
    }

    private fun actionIntent(action: String): PendingIntent {
        val intent = Intent(this, ScreenRecorderService::class.java).apply { this.action = action }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun buildNotification(): Notification {
        val elapsed = elapsedSeconds()
        val mm = elapsed / 60
        val ss = elapsed % 60
        val time = String.format("%02d:%02d", mm, ss)
        val openIntent = Intent(this, ScreenRecorderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_screen_record)
            .setContentTitle(if (isPaused) "Recording paused" else "Recording screen")
            .setContentText("$time  •  Tap to open")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (isPaused) {
            builder.addAction(R.drawable.ic_play, "Resume", actionIntent(ACTION_RESUME))
        } else {
            builder.addAction(R.drawable.ic_pause, "Pause", actionIntent(ACTION_PAUSE))
        }
        builder.addAction(R.drawable.ic_stop, "Stop", actionIntent(ACTION_STOP))
        return builder.build()
    }

    private fun updateNotification() {
        if (!isRecording) return
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIF_ID, buildNotification())
        } catch (_: Throwable) {
        }
    }

    private fun broadcast(state: String, elapsed: Long) {
        sendBroadcast(Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_ELAPSED, elapsed)
        })
    }

    private fun cleanup() {
        isRecording = false
        isPaused = false
        handler.removeCallbacks(tick)
        stopInternalAudio()
        // If startForeground was already called (a start failure
        // after it), remove the notification.
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try {
            projectionCallback?.let { projection?.unregisterCallback(it) }
        } catch (_: Throwable) {
        }
        projectionCallback = null
        try { projection?.stop() } catch (_: Throwable) {}
        projection = null
    }

    override fun onDestroy() {
        val wasRecording = isRecording
        cleanup()
        if (wasRecording) {
            // The service was killed mid-recording (system / crash)
            // — open the app and tell the user instead of vanishing
            // silently (user 2026-11-25).
            openError("Recording stopped unexpectedly. Please try again.")
        }
        super.onDestroy()
    }
}
