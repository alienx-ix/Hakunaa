package com.hakunaa.app.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.hakunaa.app.R
import com.hakunaa.app.core.FileUtils
import com.hakunaa.app.core.MediaStoreSaver
import java.io.File
import java.io.FileOutputStream

/**
 * Collage Maker — combine 2–4 photos into one image.
 *
 * Pick photos, choose a layout, save. Each photo is centre-cropped
 * to fill its cell, so the grid always looks neat. Layouts:
 * - Grid 2x2 (4 photos)
 * - Side by side (2 photos, left + right)
 * - Stacked (2 photos, top + bottom)
 * - Row of 3 (3 photos)
 * - Column of 3 (3 photos)
 *
 * Pure Kotlin Bitmap work — no library, fully offline.
 */
class CollageActivity : AppCompatActivity() {

    private val pickedUris = mutableListOf<Uri>()

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                pickedUris.clear()
                pickedUris.addAll(uris.take(4))
                findViewById<TextView>(R.id.tvPhotos).text =
                    "${pickedUris.size} photo(s) picked — ${pickedUris.joinToString(", ") { FileUtils.fileName(contentResolver, it) }}"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collage)

        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        findViewById<Button>(R.id.btnPickPhotos).setOnClickListener { pickPhotos.launch("image/*") }
        findViewById<Button>(R.id.btnMakeCollage).setOnClickListener { makeCollage() }
    }

    private fun selectedLayout(): Pair<Int, Int> {
        // Returns (columns, rows) for the chosen layout.
        return when (findViewById<RadioGroup>(R.id.rgLayout).checkedRadioButtonId) {
            R.id.rbLayoutSideBySide -> 2 to 1
            R.id.rbLayoutStacked -> 1 to 2
            R.id.rbLayoutRow3 -> 3 to 1
            R.id.rbLayoutCol3 -> 1 to 3
            else -> 2 to 2
        }
    }

    private fun makeCollage() {
        if (pickedUris.size < 2) {
            Toast.makeText(this, "Pick at least 2 photos", Toast.LENGTH_SHORT).show()
            return
        }
        val (cols, rows) = selectedLayout()
        val needed = cols * rows
        if (pickedUris.size < needed) {
            Toast.makeText(
                this,
                "This layout needs $needed photos — pick ${needed - pickedUris.size} more",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val status = findViewById<TextView>(R.id.tvStatus)
        status.text = "Making collage…"
        Thread {
            try {
                val gap = 12
                val cellSize = 1080
                val width = cols * cellSize + (cols + 1) * gap
                val height = rows * cellSize + (rows + 1) * gap
                val collage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(collage)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                var index = 0
                for (row in 0 until rows) {
                    for (col in 0 until cols) {
                        val uri = pickedUris[index++]
                        val file = FileUtils.copyUriToCache(this, uri, "collage_$index")
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            val left = gap + col * (cellSize + gap)
                            val top = gap + row * (cellSize + gap)
                            drawCenterCrop(canvas, bitmap, left, top, cellSize, cellSize, paint)
                            bitmap.recycle()
                        }
                        try { file.delete() } catch (_: Throwable) { }
                    }
                }
                val out = File(cacheDir, "hakunaa_collage_${System.currentTimeMillis()}.jpg")
                FileOutputStream(out).use { stream ->
                    collage.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                }
                collage.recycle()
                runOnUiThread {
                    val saved = MediaStoreSaver.save(this, out, MediaStoreSaver.Kind.IMAGE, out.name)
                    status.text = if (saved != null) "Done — collage saved to Pictures/Hakunaa"
                    else "Done, but couldn't save to Pictures — please try again"
                }
            } catch (_: Throwable) {
                runOnUiThread { status.text = "Failed — try other photos" }
            }
        }.start()
    }

    /** Draw a bitmap centre-cropped to fill a square cell. */
    private fun drawCenterCrop(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        paint: Paint
    ) {
        val scale = maxOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val scaledW = (bitmap.width * scale).toInt()
        val scaledH = (bitmap.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, true)
        val srcX = (scaledW - width) / 2
        val srcY = (scaledH - height) / 2
        val src = Rect(srcX, srcY, srcX + width, srcY + height)
        val dst = Rect(left, top, left + width, top + height)
        canvas.drawBitmap(scaled, src, dst, paint)
        scaled.recycle()
    }
}
