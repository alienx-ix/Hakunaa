package com.hakunaa.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.image.ImageCompressActivity
import com.hakunaa.app.pdf.PdfToolsActivity
import com.hakunaa.app.scanner.ScannerActivity
import com.hakunaa.app.video.VideoCompressActivity
import com.hakunaa.app.whatsapp.WhatsAppOptimizeActivity
import com.hakunaa.app.core.StatusBar

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Status bar (v0.1.97): keep it visible, no overlap.
        StatusBar.setup(this, true)
        // Show the installed version on the home screen, so a test
        // result can always be tied to the exact build that ran.
        try {
            val info = packageManager.getPackageInfo(packageName, 0)
            findViewById<TextView>(R.id.tvVersion).text = "Hakunaa v${info.versionName}"
        } catch (_: Throwable) {
        }

        findViewById<View>(R.id.cardPdf).setOnClickListener {
            startActivity(Intent(this, PdfToolsActivity::class.java))
        }
        findViewById<View>(R.id.cardImage).setOnClickListener {
            startActivity(Intent(this, ImageCompressActivity::class.java))
        }
        findViewById<View>(R.id.cardVideo).setOnClickListener {
            startActivity(Intent(this, VideoCompressActivity::class.java))
        }
        findViewById<View>(R.id.cardScanner).setOnClickListener {
            startActivity(Intent(this, ScannerActivity::class.java))
        }
        findViewById<View>(R.id.cardWhatsApp).setOnClickListener {
            startActivity(Intent(this, WhatsAppOptimizeActivity::class.java))
        }
    }
}
