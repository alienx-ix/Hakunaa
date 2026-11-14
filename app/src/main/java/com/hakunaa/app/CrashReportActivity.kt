package com.hakunaa.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import com.hakunaa.app.core.StatusBar

/**
 * Shows the exact crash error on screen (plain Activity, no Material,
 * no layout file) so the user can screenshot it and tell us what
 * broke. Opened by [com.hakunaa.app.core.HakunaaApp]'s crash handler
 * instead of restarting the home screen.
 */
class CrashReportActivity : Activity() {
    companion object {
        const val EXTRA_DETAIL = "crash_detail"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "No details captured."
        val text = TextView(this).apply {
            text = "Hakunaa crashed. Please screenshot this screen and send it.\n\n$detail"
            textSize = 14f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.YELLOW)
            setPadding(32, 32, 32, 32)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        setContentView(scroll)
        // Status bar (v0.1.97): keep it visible, no overlap.
        StatusBar.setup(this, true)
    }
}
