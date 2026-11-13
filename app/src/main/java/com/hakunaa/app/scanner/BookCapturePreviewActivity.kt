package com.hakunaa.app.scanner

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.hakunaa.app.R

/**
 * Book capture preview (v0.1.72) — shown after every Book
 * capture, so the user can SEE what was captured. One screen,
 * both pages: A on top, B on the bottom (Double); a Single
 * capture shows just that one page, full height.
 *
 * Keep (or the green check) confirms the pages — the caller
 * adds them to the book. Retake (or Back) discards them — the
 * caller deletes the files and the user captures again.
 * Images are downsampled (max side 2048, like PagePreview) so
 * a full-resolution spread never blows up memory.
 */
class BookCapturePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE_PATHS = "page_paths"
        const val EXTRA_LABELS = "labels"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_capture_preview)

        val paths = intent.getStringArrayListExtra(EXTRA_PAGE_PATHS) ?: emptyList()
        val labels = intent.getStringArrayListExtra(EXTRA_LABELS) ?: emptyList()
        if (paths.isEmpty()) {
            finish()
            return
        }

        val imageA = findViewById<ImageView>(R.id.ivPageA)
        val labelA = findViewById<View>(R.id.tvLabelA)
        val layoutB = findViewById<View>(R.id.layoutPageB)
        val imageB = findViewById<ImageView>(R.id.ivPageB)
        val labelB = findViewById<View>(R.id.tvLabelB)

        // The label shown is the page's real side (A / B).
        (labelA as? android.widget.TextView)?.text = labels.getOrNull(0) ?: "A"
        (labelB as? android.widget.TextView)?.text = labels.getOrNull(1) ?: "B"
        if (paths.size < 2) {
            // Single capture — one page, full height.
            layoutB.visibility = View.GONE
        }
        loadPage(paths[0], imageA)
        if (paths.size >= 2) loadPage(paths[1], imageB)

        findViewById<MaterialButton>(R.id.btnRetake).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
        findViewById<MaterialButton>(R.id.btnKeep).setOnClickListener {
            setResult(RESULT_OK)
            finish()
        }
    }

    /** Downsampled decode on a background thread (same cap as
     *  PagePreview: longest side 2048). A page that cannot be
     *  read ends the preview — the caller keeps its pages. */
    private fun loadPage(path: String, imageView: ImageView) {
        Thread {
            try {
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeFile(path, options)
                var sample = 1
                val maxSide = maxOf(options.outWidth, options.outHeight)
                while (maxSide / sample > 2048) sample *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                val bmp = BitmapFactory.decodeFile(path, options)
                runOnUiThread {
                    if (bmp == null) {
                        Toast.makeText(this, "Could not load page", Toast.LENGTH_SHORT).show()
                        finish()
                        return@runOnUiThread
                    }
                    imageView.setImageBitmap(bmp)
                }
            } catch (_: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Could not load page", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }
}
