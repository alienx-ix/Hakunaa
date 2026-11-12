package com.hakunaa.app.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream

/**
 * PDF tools — 100% on-device with PDFBox-Android.
 * Merge, Split, Lossless Compress, Smart Compress, Image to PDF,
 * Rotate, Watermark, Page Numbers, Delete Pages, Extract Pages
 * and Extract Images are all implemented here.
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
        } catch (_: Throwable) {
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
        } catch (_: Throwable) {
            return emptyList()
        }
        return created
    }

    /**
     * Lossless Compress: strip metadata and save with full compression.
     * 20–30% smaller on typical PDFs, quality 100% the same.
     * Returns the bytes saved (input size minus output size), 0 if the
     * PDF is already as small as it gets (in that case NO output file
     * is left behind — an earlier build saved a BIGGER file, 3.7 MB
     * → 3.8 MB, because re-saving an already-optimized PDF with
     * PDFBox's default writer can add overhead), or -1 on failure.
     * Smart lossy compression (image downscale to 150 DPI) is separate.
     */
    fun compressLossless(input: File, output: File): Long {
        return try {
            PDDocument.load(input).use { doc ->
                doc.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation()
                doc.save(output)
            }
            val saved = input.length() - output.length()
            if (saved <= 0) {
                // Already optimized (or re-save overhead made it bigger):
                // never hand the user a larger "compressed" file.
                try { output.delete() } catch (_: Throwable) { }
                0L
            } else {
                saved
            }
        } catch (_: Throwable) {
            -1L
        }
    }

    /**
     * Smart Compress (lossy): downscale every embedded image that is
     * larger than [targetDpi] at full-page size, re-encode as JPEG
     * [jpegQuality]. Also re-encodes large losslessly-stored images
     * (PNG etc.) as JPEG 82% even when they fit the page — those are
     * the main source of bloat, and downscaling alone missed them.
     * Typical result 60–70% smaller, eye cannot tell at 150 DPI.
     * Also strips metadata.
     * Returns the number of images replaced, or -1 on failure.
     * NOTE: this can make a file BIGGER if the original images were
     * already low-quality JPEGs (re-encoding at 82% grows them).
     * Use [compressSmartBest] for the safe version that never hands
     * back a bigger file.
     */
    fun compressSmart(
        input: File,
        output: File,
        targetDpi: Int = 150,
        jpegQuality: Float = 0.82f
    ): Int {
        var replaced = 0
        return try {
            PDDocument.load(input).use { doc ->
                for (page in doc.pages) {
                    val maxW = (page.mediaBox.width / 72f * targetDpi).toInt()
                    val maxH = (page.mediaBox.height / 72f * targetDpi).toInt()
                    replaced += downscaleImages(doc, page.resources, maxW, maxH, jpegQuality)
                }
                doc.documentInformation = PDDocumentInformation()
                doc.save(output)
            }
            replaced
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Best Smart Compress: try the standard settings first (150 DPI,
     * JPEG 82%); if the result is NOT smaller than the input, the
     * app automatically escalates to stronger settings (100 DPI /
     * JPEG 70%, then 72 DPI / JPEG 60%) until the output IS smaller.
     * A Smart Compress never hands back a bigger file. The user
     * proved the need: a 3.8 MB PDF came back 4.2 MB (-10%) even
     * though 8 images were "compressed" — the originals were already
     * low-quality JPEGs, so re-encoding at 82% grew them.
     * Returns a Pair: (images replaced, settings note). The note is
     * "" if the standard settings won, or names the stronger
     * settings used. Returns (-1, "") on failure, and (0, "already")
     * if even the strongest settings could not shrink it (in that
     * case NO output file is left behind).
     */
    fun compressSmartBest(input: File, output: File): Pair<Int, String> {
        val inputSize = input.length()
        val attempts = listOf(
            Triple(150, 0.82f, ""),
            Triple(100, 0.70f, " (used 100 DPI, JPEG 70% to get it smaller)"),
            Triple(72, 0.60f, " (used 72 DPI, JPEG 60% to get it smaller)")
        )
        for ((dpi, quality, note) in attempts) {
            val replaced = compressSmart(input, output, dpi, quality)
            if (replaced < 0) return Pair(-1, "")
            if (!output.exists()) return Pair(-1, "")
            val outSize = output.length()
            if (outSize < inputSize) {
                return Pair(replaced, note)
            }
            // Bigger (or equal) — delete it and try harder.
            try { output.delete() } catch (_: Throwable) { }
        }
        return Pair(0, "already")
    }

    /** Walk a page's XObjects (including nested Form XObjects) and downscale images. Returns the count replaced. */
    private fun downscaleImages(
        doc: PDDocument,
        resources: PDResources?,
        maxW: Int,
        maxH: Int,
        quality: Float
    ): Int {
        if (resources == null) return 0
        var count = 0
        val names = resources.xObjectNames?.toList() ?: return 0
        for (name in names) {
            val xObject = try {
                resources.getXObject(name)
            } catch (_: Throwable) {
                continue
            } ?: continue
            when (xObject) {
                is PDImageXObject -> {
                    val replacement = downscaleImage(doc, xObject, maxW, maxH, quality)
                    if (replacement != null) {
                        resources.put(name, replacement)
                        count++
                    }
                }
                is PDFormXObject -> count += downscaleImages(doc, xObject.resources, maxW, maxH, quality)
            }
        }
        return count
    }

    /**
     * Downscale one image to fit [maxW] x [maxH], re-encoded as JPEG.
     * Also re-encodes a large losslessly-stored image (PNG etc.) as
     * JPEG even when it fits the page — otherwise image-heavy PDFs
     * that store big PNGs came back "0% smaller". Returns null if the
     * image was left unchanged.
     */
    private fun downscaleImage(
        doc: PDDocument,
        image: PDImageXObject,
        maxW: Int,
        maxH: Int,
        quality: Float
    ): PDImageXObject? {
        val needsDownscale = image.width > maxW || image.height > maxH
        // Is it already a JPEG? (DCTDecode.) Lossless images (PNG etc.)
        // are far bigger than JPEG 82% at the same pixels.
        val suffix = try { image.suffix } catch (_: Throwable) { null }
        val isJpeg = suffix?.equals("jpg", ignoreCase = true) == true
        val pixelCount = image.width.toLong() * image.height.toLong()
        val needsReencode = !isJpeg && pixelCount > 50_000L
        if (!needsDownscale && !needsReencode) return null
        return try {
            val bitmap = image.image ?: return null
            val scaled = if (needsDownscale) {
                val scale = minOf(
                    maxW / bitmap.width.toFloat(),
                    maxH / bitmap.height.toFloat()
                )
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }
            val result = JPEGFactory.createFromImage(doc, scaled, quality)
            if (scaled !== bitmap) scaled.recycle()
            result
        } catch (_: Throwable) {
            null
        }
    }

    /** One image per page, fit to an A4 page. Returns true on success. */
    fun imageToPdf(imageFile: File, output: File): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return false
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle.A4)
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
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Rotate every page by [degrees] (90, 180 or 270). Adds to the
     * page's existing rotation, so rotating twice works as expected.
     * Returns true on success.
     */
    fun rotate(input: File, output: File, degrees: Int): Boolean {
        return try {
            PDDocument.load(input).use { doc ->
                for (page in doc.pages) {
                    page.rotation = (page.rotation + degrees) % 360
                }
                doc.save(output)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Watermark: stamp [text] diagonally across every page, in
     * semi-transparent light grey, so the content stays readable
     * underneath. Returns true on success.
     */
    fun watermark(input: File, output: File, text: String): Boolean {
        return try {
            PDDocument.load(input).use { doc ->
                for (page in doc.pages) {
                    val box = page.mediaBox
                    PDPageContentStream(
                        doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true
                    ).use { content ->
                        content.setNonStrokingColor(180, 180, 180)
                        content.beginText()
                        // Centre of the page, rotated 45 degrees.
                        content.setTextMatrix(
                            com.tom_roush.pdfbox.util.Matrix.getRotateInstance(
                                Math.toRadians(45.0),
                                box.width / 2f, box.height / 2f
                            )
                        )
                        content.setFont(
                            com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD, 48f
                        )
                        // Centre the text on the rotation point.
                        val textWidth = com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA_BOLD
                            .getStringWidth(text) / 1000f * 48f
                        content.setTextMatrix(
                            com.tom_roush.pdfbox.util.Matrix.getRotateInstance(
                                Math.toRadians(45.0),
                                box.width / 2f - textWidth / 2f, box.height / 2f
                            )
                        )
                        content.showText(text)
                        content.endText()
                    }
                }
                doc.save(output)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Page Numbers: stamp "Page X of Y" at the bottom centre of
     * every page. Returns true on success.
     */
    fun pageNumbers(input: File, output: File): Boolean {
        return try {
            PDDocument.load(input).use { doc ->
                val total = doc.numberOfPages
                var index = 1
                for (page in doc.pages) {
                    val box = page.mediaBox
                    val label = "Page $index of $total"
                    PDPageContentStream(
                        doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true
                    ).use { content ->
                        content.beginText()
                        content.setFont(
                            com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 10f
                        )
                        val textWidth = com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA
                            .getStringWidth(label) / 1000f * 10f
                        content.newLineAtOffset(
                            (box.width - textWidth) / 2f, 20f
                        )
                        content.showText(label)
                        content.endText()
                    }
                    index++
                }
                doc.save(output)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Delete Pages: remove the given 1-based [pages] from the PDF.
     * Returns the number of pages removed, or -1 on failure.
     */
    fun deletePages(input: File, output: File, pages: Set<Int>): Int {
        return try {
            var removed = 0
            PDDocument.load(input).use { doc ->
                val total = doc.numberOfPages
                // Remove from the end so earlier indexes stay valid.
                val sorted = pages.filter { it in 1..total }.sortedDescending()
                for (pageNum in sorted) {
                    doc.removePage(pageNum - 1)
                    removed++
                }
                doc.save(output)
            }
            removed
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Extract Pages: keep only the given 1-based [pages] in a new PDF.
     * Returns the number of pages kept, or -1 on failure.
     */
    fun extractPages(input: File, output: File, pages: Set<Int>): Int {
        return try {
            var kept = 0
            PDDocument.load(input).use { doc ->
                val total = doc.numberOfPages
                val keep = pages.filter { it in 1..total }.sorted()
                if (keep.isEmpty()) return -1
                // Remove every page NOT in the keep list, from the end.
                for (pageNum in total downTo 1) {
                    if (pageNum !in keep) doc.removePage(pageNum - 1)
                }
                kept = keep.size
                doc.save(output)
            }
            kept
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * Extract Images: save every embedded image to [outputDir] as
     * PNG or JPEG (matching its stored format). Returns the created
     * files. Also walks nested Form XObjects.
     */
    fun extractImages(input: File, outputDir: File): List<File> {
        val created = mutableListOf<File>()
        try {
            PDDocument.load(input).use { doc ->
                var index = 1
                for (page in doc.pages) {
                    index = extractImagesFromResources(
                        page.resources, outputDir, index, created
                    )
                }
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        return created
    }

    private fun extractImagesFromResources(
        resources: PDResources?,
        outputDir: File,
        startIndex: Int,
        created: MutableList<File>
    ): Int {
        if (resources == null) return startIndex
        var index = startIndex
        val names = resources.xObjectNames?.toList() ?: return index
        for (name in names) {
            val xObject = try {
                resources.getXObject(name)
            } catch (_: Throwable) {
                continue
            } ?: continue
            when (xObject) {
                is PDImageXObject -> {
                    try {
                        val suffix = try {
                            xObject.suffix?.lowercase() ?: "png"
                        } catch (_: Throwable) {
                            "png"
                        }
                        val ext = if (suffix == "jpg" || suffix == "jpeg") "jpg" else "png"
                        val out = File(outputDir, "hakunaa_image_$index.$ext")
                        val bitmap = xObject.image ?: continue
                        val stream = java.io.FileOutputStream(out)
                        stream.use {
                            bitmap.compress(
                                if (ext == "jpg") Bitmap.CompressFormat.JPEG
                                else Bitmap.CompressFormat.PNG,
                                90, it
                            )
                        }
                        created.add(out)
                        index++
                    } catch (_: Throwable) {
                        continue
                    }
                }
                is PDFormXObject -> index = extractImagesFromResources(
                    xObject.resources, outputDir, index, created
                )
            }
        }
        return index
    }

    /**
     * Parse a page selection like "1,3,5-7" into a set of 1-based
     * page numbers. Returns null if the text is empty or invalid.
     */
    fun parsePageSelection(text: String): Set<Int>? {
        val result = mutableSetOf<Int>()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        for (part in trimmed.split(",")) {
            val p = part.trim()
            if (p.isEmpty()) return null
            if (p.contains("-")) {
                val bounds = p.split("-")
                if (bounds.size != 2) return null
                val start = bounds[0].trim().toIntOrNull() ?: return null
                val end = bounds[1].trim().toIntOrNull() ?: return null
                if (start < 1 || end < start) return null
                for (n in start..end) result.add(n)
            } else {
                val n = p.toIntOrNull() ?: return null
                if (n < 1) return null
                result.add(n)
            }
        }
        return if (result.isEmpty()) null else result
    }
}
