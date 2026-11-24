package com.hakunaa.app.video

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * In-app video player (ExoPlayer / Media3) — drop-in replacement
 * for VideoView in VideoToolsActivity. SurfaceView + ExoPlayer:
 * hardware-accelerated, ultra smooth, 0 lag, plays far more
 * formats than VideoView (MP4, MKV, WebM, MOV, 3GP...). The video
 * stays inside Hakunaa — no external app, no "Can't play this
 * video" dialog. Same call shape as VideoView (setVideoURI,
 * start, pause, seekTo, stopPlayback) so every play button in
 * Video Tools works unchanged.
 */
class ExoVideoView @JvmOverloads constructor(
    context: Context
) : FrameLayout(context) {

    private val surfaceView = SurfaceView(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private var player: ExoPlayer? = null
    private var prepared = false
    private var pendingSeekMs: Long = -1L

    /** Called once when the player is ready (first frame available). */
    var onPrepared: (() -> Unit)? = null

    /** Called on a playback error (unplayable file). */
    var onError: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        addView(surfaceView)
    }

    /** Current playback position in ms (like VideoView.currentPosition). */
    val currentPosition: Int
        get() = player?.currentPosition?.toInt() ?: 0

    /** Total duration in ms (like VideoView.duration); 0 if unknown. */
    val duration: Long
        get() {
            val d = player?.duration ?: return 0L
            return if (d < 0) 0L else d
        }

    /** Set the video source (like VideoView.setVideoURI). Prepares but does not auto-play. */
    fun setVideoURI(uri: Uri) {
        ensurePlayer()
        prepared = false
        pendingSeekMs = -1L
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
    }

    /** Start / resume playback. */
    fun start() {
        ensurePlayer()
        player?.play()
    }

    /** Pause playback. */
    fun pause() {
        player?.pause()
    }

    /** True while the video is actually playing. */
    fun isPlaying(): Boolean = player?.isPlaying == true

    /** Seek to [ms] milliseconds (like VideoView.seekTo). Safe to call before ready. */
    fun seekTo(ms: Int) {
        val p = player
        if (p == null || !prepared) {
            pendingSeekMs = ms.toLong()
            return
        }
        p.seekTo(ms.toLong())
    }

    /** Stop and release (like VideoView.stopPlayback). */
    fun stopPlayback() {
        releasePlayer()
    }

    /** Release the player. Safe to call more than once. */
    fun release() {
        releasePlayer()
    }

    private fun ensurePlayer() {
        if (player != null) return
        val p = ExoPlayer.Builder(context).build().apply {
            setVideoSurfaceView(surfaceView)
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && !prepared) {
                        prepared = true
                        onPrepared?.invoke()
                        if (pendingSeekMs >= 0) {
                            seekTo(pendingSeekMs.toInt())
                            pendingSeekMs = -1L
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    onError?.invoke()
                }
            })
        }
        player = p
    }

    private fun releasePlayer() {
        try {
            player?.release()
        } catch (_: Throwable) {
        }
        player = null
        prepared = false
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }
}
