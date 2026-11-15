package com.hakunaa.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import com.hakunaa.app.scanner.CropView
import java.io.File
import com.hakunaa.app.core.StatusBar

/**
 * Crop — remove unwanted parts of the photo.
 * Reuses the scanner's CropView: drag the corners to frame the
 * part to keep, then save. Rotate Left / Right if the photo is
 * sideways.
 */
class ImageCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        /** Locked aspect ratio for the frame (0 = free crop). */
        const val EXTRA_ASPECT_W = "aspect_w"
        const val EXTRA_ASPECT_H = "aspect_h"
        /** Circle frame (DP Circle) — the frame is a circle. */
        const val EXTRA_CIRCLE = "circle"
    }

    private var bitmap: Bitmap? = null
    private var inputFile: File? = null
    private var circleMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_crop)

        // Status bar (v0.1.97): keep it visible, no overlap.
        StatusBar.setup(this, false)
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriString == null) {
            Toast.makeText(this, "No image", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Thread {
            try {
                val file = FileUtils.copyUriToCache(this, Uri.parse(uriString), "input_image")
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                runOnUiThread {
                    if (bmp == null) {
                        Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                        finish()
                        return@runOnUiThread
                    }
                    inputFile = file
                    bitmap = bmp
                    val aspectW = intent.getIntExtra(EXTRA_ASPECT_W, 0)
                    val aspectH = intent.getIntExtra(EXTRA_ASPECT_H, 0)
                    circleMode = intent.getBooleanExtra(EXTRA_CIRCLE, false)
                    val cropView = findViewById<CropView>(R.id.cropView)
                    if (circleMode) {
                        cropView.circleMode = true
                        cropView.setAspectRatio(1, 1)
                        findViewById<TextView>(R.id.tvCropTitle).text = "DP Edit — Circle"
                        findViewById<TextView>(R.id.tvStatus).text =
                            "Drag the circle to frame your DP"
                    } else if (aspectW > 0 && aspectH > 0) {
                        cropView.setAspectRatio(aspectW, aspectH)
                        findViewById<TextView>(R.id.tvCropTitle).text = "Fit to social media"
                        findViewById<TextView>(R.id.tvStatus).text =
                            "Drag the frame to choose the portion to keep"
                    }
                    cropView.setBitmap(bmp)
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Couldn't open the image", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()

        findViewById<LinearLayout>(R.id.btnRotateLeft).setOnClickListener { rotate(-90f) }
        findViewById<LinearLayout>(R.id.btnRotateRight).setOnClickListener { rotate(90f) }
        findViewById<TextView>(R.id.btnSave).setOnClickListener { save() }
    }

    private fun rotate(degrees: Float) {
        val bmp = bitmap ?: return
        val matrix = Matrix()
        matrix.postRotate(degrees)
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        bitmap = rotated
        findViewById<CropView>(R.id.cropView).setBitmap(rotated)
    }

    private fun save() {
        val bmp = bitmap ?: return
        val corners = findViewById<CropView>(R.id.cropView).getCorners()
        // The crop is a rectangle: use the bounding box of the
        // four corners (the CropView frame is a quad; for a photo
        // crop the user frames a rectangle).
        val left = minOf(corners[0], corners[6]).toInt()
        val top = minOf(corners[1], corners[3]).toInt()
        val right = maxOf(corners[2], corners[4]).toInt()
        val bottom = maxOf(corners[5], corners[7]).toInt()
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Cropping…"
        Thread {
            try {
                val out = if (circleMode) {
                    File(cacheDir, "hakunaa_dp_${System.currentTimeMillis()}.png")
                } else {
                    File(cacheDir, "hakunaa_cropped_${System.currentTimeMillis()}.jpg")
                }
                val ok = if (circleMode) {
                    val size = minOf(right - left, bottom - top)
                    ImageCompressor.circleCropRect(inputFile!!, out, left, top, size)
                } else {
                    ImageCompressor.crop(inputFile!!, out, left, top, right, bottom)
                }
                runOnUiThread {
                    if (ok) {
                        val saved = MediaStoreSaver.save(this, out, MediaStoreSaver.Kind.IMAGE, out.name)
                        status.text = if (saved != null) "Done — cropped image saved to Pictures/Hakunaa"
                        else "Done, but couldn't save to Pictures — please try again"
                    } else {
                        status.text = "Crop failed — try again"
                    }
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Crop failed — try again" }
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        bitmap?.recycle()
    }
}
