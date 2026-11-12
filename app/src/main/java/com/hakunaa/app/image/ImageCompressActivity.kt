package com.hakunaa.app.image

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import java.io.File

class ImageCompressActivity : AppCompatActivity() {
    private var inputUri: Uri? = null
    private var inputSize: Long = -1

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            inputUri = uri
            if (uri != null) {
                inputSize = FileUtils.fileSize(contentResolver, uri)
                findViewById<TextView>(R.id.tvFile).text =
                    "${FileUtils.fileName(contentResolver, uri)} — ${FileUtils.formatSize(inputSize)}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_compress)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        findViewById<Button>(R.id.btnPickImage).setOnClickListener { pickImage.launch("image/*") }
        findViewById<Button>(R.id.btnCompress).setOnClickListener { compress() }
    }

    private fun compress() {
        val uri = inputUri ?: run {
            Toast.makeText(this, getString(R.string.pick_first_image), Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        val result = findViewById<TextView>(R.id.tvResult)
        status.text = "Compressing…"
        Thread {
            try {
                val input = FileUtils.copyUriToCache(this, uri, "input_image")
                // Best compression: tries JPEG 85% first, and if the
                // result is not smaller it automatically escalates
                // (WebP, lower quality) until the output IS smaller.
                // Never hands back a bigger file.
                val outBase = File(cacheDir, "hakunaa_${System.currentTimeMillis()}")
                val best = ImageCompressor.compressBest(input, outBase)
                runOnUiThread {
                    if (best != null) {
                        val (out, _, note) = best
                        val after = out.length()
                        val saved = MediaStoreSaver.save(
                            this, out, MediaStoreSaver.Kind.IMAGE, out.name
                        )
                        status.text = if (saved != null) "Done — saved to Pictures/Hakunaa$note"
                        else "Done, but couldn't save to Pictures — please try again"
                        result.text = "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(after)} " +
                            "(${FileUtils.savedPercent(inputSize, after)})"
                    } else {
                        status.text = "Already optimized — even the strongest settings couldn't shrink this image."
                        result.text = ""
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Failed — try another image" }
            }
        }.start()
    }
}
