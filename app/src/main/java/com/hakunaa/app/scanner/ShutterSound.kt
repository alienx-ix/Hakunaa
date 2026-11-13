package com.hakunaa.app.scanner

import android.content.Context
import android.media.MediaActionSound

/**
 * The camera shutter click, shared by the main scanner and
 * Book Scan. It only plays when the user's Sound setting is
 * on (Settings > Sound). A phone without a shutter sound, or
 * a failure to load it, never breaks a capture — the sound
 * is best-effort and fully guarded.
 */
object ShutterSound {

    private const val PREFS_NAME = "hakunaa_prefs"
    private const val PREF_SOUND_ON = "sound_on"

    private var sound: MediaActionSound? = null

    fun isOn(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREF_SOUND_ON, true)
    }

    fun play(context: Context) {
        if (!isOn(context)) return
        try {
            var s = sound
            if (s == null) {
                s = MediaActionSound()
                s.load(MediaActionSound.SHUTTER_CLICK)
                sound = s
            }
            s.play(MediaActionSound.SHUTTER_CLICK)
        } catch (_: Throwable) {
            // No shutter sound on this phone — capture anyway.
        }
    }
}
