package com.hakunaa.app.video

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import java.io.File

class VideoCompressActivity : AppCompatActivity() {
    private var inputUri: Uri? = null
    private var inputSize: Long = -1
    private var selectedCrf = 28

    private enum class Mode { COMPRESS, CONVERT, MP3 }
    private var mode = Mode.COMPRESS

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
        findViewById<RadioGroup>(R.id.rgMode).setOnCheckedChangeListener { _, id ->
            mode = when (id) {
                R.id.rbModeConvert -> Mode.CONVERT
                R.id.rbModeMp3 -> Mode.MP3
                else -> Mode.COMPRESS
            }
            updateModeUi()
        }
        findViewById<Button>(R.id.btnPickVideo).setOnClickListener { pickVideo.launch("video/*") }
        findViewById<Button>(R.id.btnCompress).setOnClickListener { startAction() }
        updateModeUi()
    }

    /** Show only the option groups that apply to the selected mode. */
    private fun updateModeUi() {
        val compressOptions = findViewById<LinearLayout>(R.id.llCompressOptions)
        val formatOptions = findViewById<LinearLayout>(R.id.llFormatOptions)
        val resolutionOptions = findViewById<LinearLayout>(R.id.llResolutionOptions)
        val actionButton = findViewById<Button>(R.id.btnCompress)
        when (mode) {
            Mode.COMPRESS -> {
                compressOptions.visibility = View.VISIBLE
                formatOptions.visibility = View.GONE
                resolutionOptions.visibility = View.VISIBLE
                actionButton.text = getString(R.string.compress)
            }
            Mode.CONVERT -> {
                compressOptions.visibility = View.GONE
                formatOptions.visibility = View.VISIBLE
                resolutionOptions.visibility = View.VISIBLE
                actionButton.text = "Convert"
            }
            Mode.MP3 -> {
                compressOptions.visibility = View.GONE
                formatOptions.visibility = View.GONE
                resolutionOptions.visibility = View.GONE
                actionButton.text = "Extract MP3"
            }
        }
    }

    private fun selectedResolution(): VideoCompressor.Resolution {
        return when (findViewById<RadioGroup>(R.id.rgResolution).checkedRadioButtonId) {
            R.id.rbRes720 -> VideoCompressor.Resolution.P720
            R.id.rbRes1080 -> VideoCompressor.Resolution.P1080
            else -> VideoCompressor.Resolution.ORIGINAL
        }
    }

    private fun selectedFormat(): VideoCompressor.OutputFormat {
        return when (findViewById<RadioGroup>(R.id.rgFormat).checkedRadioButtonId) {
            R.id.rbFmtWebm -> VideoCompressor.OutputFormat.WEBM
            R.id.rbFmtMov -> VideoCompressor.OutputFormat.MOV
            R.id.rbFmtMkv -> VideoCompressor.OutputFormat.MKV
            else -> VideoCompressor.OutputFormat.MP4
        }
    }

    private fun selectedCodec(): VideoCompressor.Codec {
        return when (findViewById<RadioGroup>(R.id.rgCodec).checkedRadioButtonId) {
            R.id.rbH265 -> VideoCompressor.Codec.H265
            R.id.rbAV1 -> VideoCompressor.Codec.AV1
            else -> VideoCompressor.Codec.H264
        }
    }

    private fun startAction() {
        val uri = inputUri ?: run {
            Toast.makeText(this, getString(R.string.pick_first_video), Toast.LENGTH_SHORT).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        val result = findViewById<TextView>(R.id.tvResult)
        // Never crash on a phone where the native engine cannot load.
        if (!VideoCompressor.isEngineAvailable()) {
            status.text = "Video engine couldn't start on this phone. " +
                "Please restart the app and try again."
            return
        }
        try {
            when (mode) {
                Mode.COMPRESS -> startCompress(uri, status, result)
                Mode.CONVERT -> startConvert(uri, status, result)
                Mode.MP3 -> startMp3(uri, status, result)
            }
        } catch (t: Throwable) {
            findViewById<LinearProgressIndicator>(R.id.progress).visibility = View.GONE
            status.text = "Failed: ${t.message ?: "couldn't start"}"
        }
    }

    private fun startCompress(uri: Uri, status: TextView, result: TextView) {
        val progress = findViewById<LinearProgressIndicator>(R.id.progress)
        val codec = selectedCodec()
        val resolution = selectedResolution()

        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Compressing… CRF $selectedCrf, ${codec.name}, ${resolution.label} resolution"
        result.text = ""

        // FFmpeg writes to a temp file in the app cache; the finished
        // file is then published to Movies/Hakunaa via MediaStore
        // (scoped storage — direct writes to Movies fail on Android 10+).
        val out = File(cacheDir, "hakunaa_crf${selectedCrf}_${System.currentTimeMillis()}.mp4")
        val inputFile = FileUtils.copyUriToCache(this, uri, "input_${System.currentTimeMillis()}.mp4")

        // Best compression: the app tries the user's settings first,
        // and if the result isn't smaller it automatically escalates
        // to stronger settings (higher CRF, then 720p) until the
        // output IS smaller. It never hands back a bigger file.
        VideoCompressor.compressBest(
            inputFile.absolutePath, out.absolutePath, inputSize, selectedCrf, codec, resolution,
            onProgress = { percent -> runOnUiThread { progress.progress = percent } },
            onDone = { ok, note ->
                runOnUiThread {
                    progress.visibility = View.GONE
                    if (ok) {
                        val after = out.length()
                        val saved = MediaStoreSaver.save(
                            this, out, MediaStoreSaver.Kind.VIDEO, out.name
                        )
                        status.text = if (saved != null) "Done — saved to Movies/Hakunaa$note"
                        else "Done, but couldn't save to Movies — please try again"
                        result.text = "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(after)} " +
                            "(${FileUtils.savedPercent(inputSize, after)})"
                    } else {
                        status.text = "Failed: $note"
                    }
                }
            }
        )
    }

    private fun startConvert(uri: Uri, status: TextView, result: TextView) {
        val progress = findViewById<LinearProgressIndicator>(R.id.progress)
        val format = selectedFormat()
        val resolution = selectedResolution()

        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Converting to ${format.label}… ${resolution.label} resolution"
        result.text = ""

        val out = File(cacheDir, "hakunaa_convert_${System.currentTimeMillis()}.${format.extension}")
        val inputFile = FileUtils.copyUriToCache(this, uri, "input_${System.currentTimeMillis()}.mp4")

        VideoCompressor.convert(
            inputFile.absolutePath, out.absolutePath, format, 28, resolution,
            onProgress = { percent -> runOnUiThread { progress.progress = percent } },
            onDone = { ok, message ->
                runOnUiThread {
                    progress.visibility = View.GONE
                    if (ok) {
                        val after = out.length()
                        val saved = MediaStoreSaver.save(
                            this, out, MediaStoreSaver.Kind.VIDEO, out.name, format.mime
                        )
                        status.text = if (saved != null) "Done — saved to Movies/Hakunaa"
                        else "Done, but couldn't save to Movies — please try again"
                        result.text = "${FileUtils.formatSize(inputSize)} → ${FileUtils.formatSize(after)} " +
                            "(${format.label} format)"
                    } else {
                        status.text = "Failed: $message"
                    }
                }
            }
        )
    }

    private fun startMp3(uri: Uri, status: TextView, result: TextView) {
        val progress = findViewById<LinearProgressIndicator>(R.id.progress)

        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Extracting MP3 audio…"
        result.text = ""

        val out = File(cacheDir, "hakunaa_audio_${System.currentTimeMillis()}.mp3")
        val inputFile = FileUtils.copyUriToCache(this, uri, "input_${System.currentTimeMillis()}.mp4")

        VideoCompressor.extractMp3(
            inputFile.absolutePath, out.absolutePath,
            onProgress = { percent -> runOnUiThread { progress.progress = percent } },
            onDone = { ok, message ->
                runOnUiThread {
                    progress.visibility = View.GONE
                    if (ok) {
                        val after = out.length()
                        // MP3 is audio — save it to Music/Hakunaa so it
                        // shows up in music players, not in Movies.
                        val saved = MediaStoreSaver.save(
                            this, out, MediaStoreSaver.Kind.AUDIO, out.name
                        )
                        status.text = if (saved != null) "Done — saved to Music/Hakunaa"
                        else "Done, but couldn't save — please try again"
                        result.text = "Audio extracted: ${FileUtils.formatSize(after)} MP3"
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
