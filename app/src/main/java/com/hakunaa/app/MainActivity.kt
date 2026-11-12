package com.hakunaa.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.image.ImageCompressActivity
import com.hakunaa.app.pdf.PdfToolsActivity
import com.hakunaa.app.video.VideoCompressActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.cardPdf).setOnClickListener {
            startActivity(Intent(this, PdfToolsActivity::class.java))
        }
        findViewById<View>(R.id.cardImage).setOnClickListener {
            startActivity(Intent(this, ImageCompressActivity::class.java))
        }
        findViewById<View>(R.id.cardVideo).setOnClickListener {
            startActivity(Intent(this, VideoCompressActivity::class.java))
        }
    }
}
