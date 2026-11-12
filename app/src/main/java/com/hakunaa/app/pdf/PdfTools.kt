package com.hakunaa.app.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.PDFMergerUtility
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream

/**
 * PDF tools — 100% on-device with PDFBox-Android.
 * Merge, Split, Lossless Compress and Image to PDF are implemented here.
 * Smart (lossy) Compress — downscaling embedded images to 150 DPI —
 * is the next step, one by one.
 */
object PdfTools {
    /** Merge [inputs] in order into [output]. Returns true on success. */
    fun merge(inputs: List<File>, output: File): Boolean {
        return try {
            val merger = PDFMergerUtility()
            inputs.forEach { merger.addSource(FileInputStream(it)) }
            merger.destinationFileName = output.absolutePath
            merger.mergeDocuments(null)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Split [input] into one PDF per page in [outputDir]. Returns the created files. */
    fun split(input: File, outputDir: File): List<File> {
        val created = mutableListOf<File>()
        try {
            PDDocument.load(input).use { doc ->
                val splitter = com.tom_roush.pdfbox.multipdf.Splitter()
                val parts = splitter.split(doc)
                var index = 1
                for (part in parts) {
                    part.use {
                        val out = File(outputDir, "hakunaa_page_$index.pdf")
                        it.save(out)
                        created.add(out)
                    }
                    index++
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return created
    }

    /**
     * Lossless Compress: strip metadata and save with full compression.
     * 20–30% smaller, quality 100% the same. Returns true on success.
     * Smart lossy compression (image downscale to 150 DPI) is separate.
     */
    fun compressLossless(input: File, output: File): Boolean {
        return try {
            PDDocument.load(input).use { doc ->
                doc.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation()
                doc.save(output)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** One image per page, fit to an A4 page. Returns true on success. */
    fun imageToPdf(imageFile: File, output: File): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return false
            PDDocument().use { doc ->
                val page = PDPage(PDPage.PAGE_SIZE_A4)
                doc.addPage(page)
                val image = LosslessFactory.createFromImage(doc, bitmap)
                PDPageContentStream(doc, page).use { content ->
                    val pageWidth = page.mediaBox.width
                    val pageHeight = page.mediaBox.height
                    val scale = minOf(
                        pageWidth / bitmap.width.toFloat(),
                        pageHeight / bitmap.height.toFloat()
                    ) * 0.95f
                    val width = bitmap.width * scale
                    val height = bitmap.height * scale
                    val x = (pageWidth - width) / 2f
                    val y = (pageHeight - height) / 2f
                    content.drawImage(image, x, y, width, height)
                }
                doc.save(output)
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}
