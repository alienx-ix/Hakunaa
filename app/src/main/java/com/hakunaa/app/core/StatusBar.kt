package com.hakunaa.app.core

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Status bar, the professional way (v0.1.98). Like
 *  CamScanner and other apps, the bar is TRANSPARENT and
 *  handled DYNAMICALLY — never a solid colour band:
 *  - The screen's own background runs seamlessly behind the
 *    bar, so there is NO colour difference or edge at the
 *    top — light screens stay light, dark screens stay dark.
 *  - The icons adapt to the screen: DARK icons on a light
 *    screen, LIGHT icons on a dark screen.
 *  - The activity's root layout takes the status bar inset
 *    as padding, so its CONTENT (buttons, top bar) sits
 *    below the bar while its BACKGROUND extends behind it.
 *  Call once in each Activity's onCreate, after
 *  setContentView, with true for a light screen and false
 *  for a dark one. */
object StatusBar {

    fun setup(activity: Activity, lightBackground: Boolean) {
        val window = activity.window
        // Edge-to-edge: the content runs behind the system
        // bars, and the status bar itself is transparent.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        // Icon colour follows the screen behind the bar.
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = lightBackground
        // The root layout consumes the status bar inset as
        // padding: content below the bar, background behind
        // it — one seamless screen, no band, no overlap.
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0)
        if (root != null) {
            root.fitsSystemWindows = true
            ViewCompat.requestApplyInsets(root)
        }
    }
}
