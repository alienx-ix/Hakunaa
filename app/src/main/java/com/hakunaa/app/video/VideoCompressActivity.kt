package com.hakunaa.app.video

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import java.io.File

class VideoCompressActivity : AppCompatActivity() {
    private var inputUri: Uri? = null
    private var inputSize: Long = -1
    private var selectedCrf = 28

    private val pickVideo =
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
        setContentView(R.layout.activity_video_compress)

        findViewById<RadioGroup>(R.id.rgPresets).setOnCheckedChangeListener { _, id ->
            selectedCrf = when (id) {
                R.id.rbHigh -> 23
                R.id.rbSmall -> 33
                R.id.rbSmallest -> 38
                R.id.rbUserDefault -> 24
                else -> 28
            }
        }
        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickVideo.launch("video/*") }
        findViewById<Button>(R.id.btnCompress).setOnClickListener { startCompress() }
    }

    private fun startCompress() {
        val uri = inputUri ?: run {
            Toast.makeText(this, getString(R.string.pick_first_video), Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        val result = findViewById<TextView>(R.id.tvResult)
        val progress = findViewById<LinearProgressIndicator>(R.id.progress)
        val codec = when (findViewById<RadioGroup>(R.id.rgCodec).checkedRadioButtonId) {
            R.id.rbH265 -> VideoCompressor.Codec.H265
            R.id.rbAV1 -> VideoCompressor.Codec.AV1
            else -> VideoCompressor.Codec.H264
        }

        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Compressing… CRF $selectedCrf, ${codec.name}, original resolution kept"
        result.text = ""

        val outDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "Hakunaa"
        ).apply { mkdirs() }
        val out = File(outDir, "hakunaa_crf${selectedCrf}_${System.currentTimeMillis()}.mp4")
        val inputFile = FileUtils.copyUriToCache(this, uri, "input_${System.currentTimeMillis()}.mp4")

        VideoCompressor.compress(
            inputFile.absolutePath, out.absolutePath, selectedCrf, codec,
            onProgress = { percent -> runOnUiThread { progress.progress = percent } },
            onDone = { ok, message ->
                runOnUiThread {
                    progress.visibility = View.GONE
                    if (ok) {
                        val after = out.length()
                        status.text = "Done — saved to Movies/Hakunaa"
                        result.text = "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(after)} " +
                            "(${FileUtils.savedPercent(inputSize, after)})"
                    } else {
                        status.text = "Failed: $message"
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        VideoCompressor.cancel()
        super.onDestroy()
    }
}
