package com.hakunaa.app.core

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Status bar fix (v0.1.97). On Android 15 the app runs
 *  edge-to-edge, so content goes UNDER the status bar and the
 *  icons can turn invisible (white icons on a light screen)
 *  or overlap the app's own top bar (scanner). This fixes
 *  both, for the whole app:
 *  - The content is padded below the status bar, so nothing
 *    sits under it or overlaps it.
 *  - The icon colour matches the screen: light screens get
 *    DARK icons, dark screens get LIGHT icons.
 *  Call once in each Activity's onCreate, after
 *  setContentView, with true for a light screen and false
 *  for a dark one. */
object StatusBar {

    fun setup(activity: Activity, lightBackground: Boolean) {
        val window = activity.window
        // Opt out of edge-to-edge layout: the decor lays the
        // content out below the system bars instead of under
        // them.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        // Icon colour: dark icons on a light screen, light
        // icons on a dark screen.
        WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = lightBackground
        // Solid status bar colour matching the screen, so the
        // bar never shows the content behind it.
        window.statusBarColor =
            if (lightBackground) Color.parseColor("#F6F7F9") else Color.BLACK
        // Safety net: if edge-to-edge is still enforced (the
        // opt-out above is ignored), pad the content by the
        // status bar height so nothing hides under it. When
        // the opt-out works the inset is 0, so this adds no
        // extra padding.
        val content = activity.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(0, top, 0, 0)
            insets
        }
    }
}
