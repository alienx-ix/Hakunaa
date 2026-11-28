package com.hakunaa.app.video

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.hakunaa.app.R

/**
 * In-app video player (ExoPlayer / Media3) — drop-in replacement
 * for VideoView in VideoToolsActivity. PlayerView (no controller)
 * + ExoPlayer: hardware-accelerated, ultra smooth, 0 lag, plays
 * far more formats than VideoView (MP4, MKV, WebM, MOV, 3GP...).
 * The video stays inside Hakunaa — no external app, no "Can't
 * play this video" dialog. Same call shape as VideoView
 * (setVideoURI, start, pause, seekTo, stopPlayback) so every
 * play button in Video Tools works unchanged.
 *
 * Aspect ratio is always correct (user 2026-11-24): PlayerView
 * preserves the video's own aspect — FIT letterboxes / pillars
 * (full-screen player + all inline previews), ZOOM center-crops
 * to fill (the square Quick header preview). No stretching.
 */
class ExoVideoView @JvmOverloads constructor(
    context: Context,
    // Rotate preview (user 2026-11-28): the Rotate tool rotates
    // the preview view (preview.rotation). A SurfaceView does NOT
    // follow View rotation — the video renders unrotated / wrong
    // aspect, so the preview never matches the output. A
    // TextureView IS a normal View, so it rotates with the view
    // and the preview shows exactly what the output will be.
    // Only the Rotate preview uses TextureView; every other
    // preview keeps the default SurfaceView (unchanged).
    useTextureView: Boolean = false
) : FrameLayout(context) {

    private val playerView: PlayerView = if (useTextureView) {
        // Media3 1.4.1 PlayerView has NO public setVideoSurfaceType
        // (and SURFACE_TYPE_TEXTURE_VIEW is private) — the surface
        // type can only be set via the app:surface_type XML
        // attribute, so inflate the TextureView layout. aapt2
        // validates the attribute at link time.
        android.view.LayoutInflater.from(context)
            .inflate(R.layout.exo_video_texture_view, this, false) as PlayerView
    } else {
        PlayerView(context)
    }.apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private var player: ExoPlayer? = null
    private var prepared = false
    private var pendingSeekMs: Long = -1L

    /** Called once when the player is ready (first frame available). */
    var onPrepared: (() -> Unit)? = null

    /** Called on a playback error (unplayable file). */
    var onError: (() -> Unit)? = null

    /** Aspect handling — FIT (default): whole video, correct
     * aspect, black bars where needed. ZOOM: center-crop to fill
     * the view (square thumbnail). */
    var resizeMode: Int
        get() = playerView.resizeMode
        set(value) {
            playerView.resizeMode = value
        }

    init {
        setBackgroundColor(Color.BLACK)
        addView(playerView)
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

    /** Current playback position in ms (like VideoView.currentPosition). */
    val currentPosition: Int
        get() = player?.currentPosition?.toInt() ?: 0

    /** Total duration in ms (like VideoView.duration); 0 if unknown. */
    val duration: Long
        get() {
            val d = player?.duration ?: return 0L
            return if (d < 0) 0L else d
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
        playerView.player = p
    }

    private fun releasePlayer() {
        try {
            playerView.player = null
        } catch (_: Throwable) {
        }
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
