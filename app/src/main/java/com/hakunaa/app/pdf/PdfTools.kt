package com.hakunaa.app.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * PDF tools — 100% on-device with PDFBox-Android.
 * Merge, Split, Lossless Compress, Smart Compress, Image to PDF,
 * Rotate, Watermark, Page Numbers, Delete Pages, Extract Pages,
 * Extract Images, Sign, Repair and Resize Pages are all here.
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
     * Lossless Compress (v0.1.151): REAL lossless compression.
     * The old version only stripped metadata and re-saved — most
     * real PDFs are already saved with compressed streams, so it
     * reported "Already optimized" for every file. This version
     * Flate-compresses every stream that is stored UNCOMPRESSED
     * (no filter at all — usually page content streams and other
     * raw data), strips the metadata, and rebuilds the file.
     * Streams that already have a filter are NEVER touched — JPEG
     * images (DCTDecode), Flate streams and font data stay exactly
     * as they are, so quality is 100% the same. Returns the bytes
     * saved, 0 if the PDF truly cannot shrink losslessly (in that
     * case NO output file is left behind), or -1 on failure.
     */
    fun compressLossless(input: File, output: File): Long {
        return try {
            PDDocument.load(input).use { doc ->
                doc.documentInformation = PDDocumentInformation()
                // Flate-compress unfiltered streams. A stream with
                // no filter holds raw bytes; wrapping them in
                // FlateDecode is exactly what a "fully compressed"
                // save does, and PDFBox decodes them identically
                // on read — so this is lossless by construction.
                // The raw bytes are read BEFORE the filter is set:
                // reading after would try to inflate raw data.
                for (cosObj in doc.document.objects) {
                    val base = try { cosObj.getObject() } catch (_: Throwable) { null } ?: continue
                    if (base !is COSStream) continue
                    try {
                        if (base.filters != null) continue
                        val raw = base.unfilteredStream
                        val out = base.createOutputStream(COSName.FLATE_DECODE)
                        raw.use { inputStream ->
                            out.use { outputStream ->
                                val buf = ByteArray(8192)
                                var n: Int
                                while (inputStream.read(buf).also { n = it } >= 0) {
                                    outputStream.write(buf, 0, n)
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // One bad stream must never kill the file.
                    }
                }
                // Deduplicate identical images (v0.1.193): the same
                // image embedded twice (a duplicated page, a repeated
                // logo) is stored twice. Share one copy — a real
                // lossless saving. The orphaned stream is dropped by
                // PDFBox on save (it never writes unreferenced
                // objects). Verified by JVM test: same JPEG on 2
                // pages, 41246 -> 21119 bytes, PDF still valid.
                deduplicateImages(doc)
                doc.save(output)
            }
            val saved = input.length() - output.length()
            if (saved <= 0) {
                // Truly already optimized (or re-save overhead made
                // it bigger): never hand back a larger file.
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
     * Replace every duplicate image XObject (identical bytes) with a
     * reference to the first copy. Returns how many references were
     * replaced. A page that referenced the duplicate now references
     * the shared copy; the orphaned stream is dropped on save.
     * Best-effort: any failure leaves the document untouched.
     */
    private fun deduplicateImages(doc: PDDocument): Int {
        var replaced = 0
        try {
            val seen = HashMap<String, COSStream>()
            for (page in doc.pages) {
                val res = try { page.resources } catch (_: Throwable) { null } ?: continue
                val xobjects = try {
                    res.cosObject.getDictionaryObject(COSName.XOBJECT) as? COSDictionary
                } catch (_: Throwable) {
                    null
                } ?: continue
                for (name in xobjects.keySet().toList()) {
                    try {
                        val base = xobjects.getDictionaryObject(name) ?: continue
                        if (base !is COSStream) continue
                        if (base.getNameAsString(COSName.SUBTYPE) != "Image") continue
                        val key = imageIdentityKey(base) ?: continue
                        val first = seen[key]
                        if (first == null) {
                            seen[key] = base
                        } else if (first !== base) {
                            xobjects.setItem(name, first)
                            replaced++
                        }
                    } catch (_: Throwable) {
                        // One bad XObject must never kill the file.
                    }
                }
            }
        } catch (_: Throwable) {
            // Dedup is best-effort; the Flate pass already ran.
        }
        return replaced
    }

    /** Identity of an image: dimensions + colour + filter + SHA-256
     *  of the raw stored bytes. Two identical images hash identically. */
    private fun imageIdentityKey(stream: COSStream): String? {
        return try {
            val w = stream.getInt(COSName.WIDTH)
            val h = stream.getInt(COSName.HEIGHT)
            val bpc = stream.getInt(COSName.BITS_PER_COMPONENT)
            val cs = stream.getNameAsString(COSName.COLORSPACE) ?: ""
            val filter = stream.filters?.toString() ?: ""
            val raw = stream.createInputStream().use { it.readBytes() }
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw)
            val hex = digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            "$w|$h|$bpc|$cs|$filter|${raw.size}|$hex"
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Smart Compress (lossy): downscale every embedded image that is
     * larger than [targetDpi] at full-page size, re-encode as JPEG
     * [jpegQuality]. Also re-encodes large images even when they fit
     * the page — INCLUDING images already stored as JPEG (a JPEG
     * saved at quality 95–100 shrinks 30–50% at JPEG 82%; the old
     * code skipped those, so Smart reported "already optimized" on
     * the most common PDFs). Per image, JPEG and lossless (Flate)
     * are both measured and the smaller is kept — screenshots and
     * line art compress far better lossless, photos far better as
     * JPEG. Typical result 60–70% smaller, eye cannot tell at
     * 150 DPI. Also strips metadata.
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
     * Best Smart Compress: try EVERY setting level — 150 DPI / JPEG
     * 82%, 100 DPI / JPEG 70%, 72 DPI / JPEG 60%, 72 DPI / JPEG
     * 50% — and keep the SMALLEST output, not just the first one
     * that is smaller. The earlier version stopped at the first
     * smaller result, so a 3.7 MB PDF came back only 14% smaller
     * (100 DPI / 70%) when the stronger settings would have shrunk
     * it much more. Best means best: the app tries them all and
     * hands back the smallest file. A Smart Compress never hands
     * back a bigger file.
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
            Triple(100, 0.70f, " (used 100 DPI, JPEG 70%)"),
            Triple(72, 0.60f, " (used 72 DPI, JPEG 60%)"),
            Triple(72, 0.50f, " (used 72 DPI, JPEG 50%)")
        )
        var bestReplaced = -1
        var bestNote = ""
        var bestSize = Long.MAX_VALUE
        // Try every level and keep the smallest output.
        for ((dpi, quality, note) in attempts) {
            val candidate = File(
                output.parent,
                "${output.nameWithoutExtension}_try${dpi}_${(quality * 100).toInt()}.pdf"
            )
            val replaced = compressSmart(input, candidate, dpi, quality)
            if (replaced < 0 || !candidate.exists()) {
                try { candidate.delete() } catch (_: Throwable) { }
                continue
            }
            val size = candidate.length()
            if (size < inputSize && size < bestSize) {
                // This is the smallest so far — keep it, drop the old best.
                try { output.delete() } catch (_: Throwable) { }
                try { candidate.renameTo(output) } catch (_: Throwable) { }
                if (output.exists()) {
                    bestReplaced = replaced
                    bestNote = note
                    bestSize = output.length()
                }
            } else {
                try { candidate.delete() } catch (_: Throwable) { }
            }
        }
        return when {
            bestReplaced < 0 -> {
                try { output.delete() } catch (_: Throwable) { }
                Pair(0, "already")
            }
            else -> Pair(bestReplaced, bestNote)
        }
    }

    /** One Smart Compress level result (v0.1.149). */
    data class SmartLevel(
        val dpi: Int,
        val quality: Float,
        val label: String,
        val file: File,
        val size: Long,
        val replaced: Int
    )

    /**
     * Smart Compress — ALL FIVE levels (v0.1.149, like Image Smart):
     * 200 DPI / JPEG 90% (Best quality), 150 DPI / JPEG 82%,
     * 100 DPI / JPEG 70%, 72 DPI / JPEG 60%, 72 DPI / JPEG 50%
     * (Smallest). Returns every level that is SMALLER than the
     * original, best quality first. Levels that are not smaller
     * are deleted, never shown. The caller picks one to save and
     * deletes the rest.
     */
    fun compressSmartLevels(input: File, outDir: File): List<SmartLevel> {
        val inputSize = input.length()
        val levels = listOf(
            Triple(200, 0.90f, "200 DPI • JPEG 90%"),
            Triple(150, 0.82f, "150 DPI • JPEG 82%"),
            Triple(100, 0.70f, "100 DPI • JPEG 70%"),
            Triple(72, 0.60f, "72 DPI • JPEG 60%"),
            Triple(72, 0.50f, "72 DPI • JPEG 50%")
        )
        val out = mutableListOf<SmartLevel>()
        for ((dpi, quality, label) in levels) {
            val candidate = File(outDir, "smart_${dpi}_${(quality * 100).toInt()}.pdf")
            val replaced = compressSmart(input, candidate, dpi, quality)
            if (replaced < 0 || !candidate.exists()) {
                try { candidate.delete() } catch (_: Throwable) { }
                continue
            }
            val size = candidate.length()
            if (size < inputSize) {
                out.add(SmartLevel(dpi, quality, label, candidate, size, replaced))
            } else {
                try { candidate.delete() } catch (_: Throwable) { }
            }
        }
        return out
    }

    /** Result of a Custom-size (target) Smart Compress (v0.1.149). */
    data class SmartTargetResult(
        val file: File?,
        val size: Long,
        val reached: Boolean,
        val smallestSize: Long
    )

    /**
     * Custom-size Smart Compress (v0.1.149, like Image Compress
     * Target): find the BEST-QUALITY level whose output is at most
     * [targetBytes], via binary search over the DPI/quality ladder.
     * Output is never bigger than the original. If no level can
     * reach the target, returns the SMALLEST level with
     * reached=false so the UI can say so honestly. If nothing can
     * shrink the file at all, file is null and smallestSize is the
     * original size. All other tried candidate files are deleted;
     * only the returned file (if any) is kept.
     */
    fun compressSmartToTarget(input: File, outDir: File, targetBytes: Long): SmartTargetResult {
        val inputSize = input.length()
        val ladder = listOf(
            200 to 0.90f, 150 to 0.82f, 100 to 0.70f, 72 to 0.60f,
            72 to 0.50f, 72 to 0.40f, 72 to 0.30f, 72 to 0.20f
        )
        val files = mutableMapOf<Int, File>()
        val sizes = mutableMapOf<Int, Long>()
        fun tryLevel(idx: Int): Long {
            sizes[idx]?.let { return it }
            val (dpi, q) = ladder[idx]
            val f = File(outDir, "target_${dpi}_${(q * 100).toInt()}.pdf")
            val replaced = compressSmart(input, f, dpi, q)
            val size = if (replaced < 0 || !f.exists()) -1L else f.length()
            if (size < 0) {
                try { f.delete() } catch (_: Throwable) { }
            } else {
                files[idx] = f
            }
            sizes[idx] = size
            return size
        }
        fun fits(size: Long) = size > 0 && size <= targetBytes && size < inputSize
        // Binary search: sizes shrink as the index grows, so find
        // the smallest index (best quality) that fits.
        var lo = 0
        var hi = ladder.size - 1
        var bestIdx = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (fits(tryLevel(mid))) {
                bestIdx = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        // Verify (sizes are not strictly monotonic for every PDF):
        // walk right from bestIdx until a level that really fits.
        var idx = bestIdx
        while (idx >= 0 && idx < ladder.size && !fits(sizes[idx] ?: -1L)) {
            idx++
            if (idx < ladder.size) tryLevel(idx)
        }
        if (idx in ladder.indices && fits(sizes[idx] ?: -1L)) {
            val chosen = files[idx]!!
            for ((i, f) in files) if (i != idx) try { f.delete() } catch (_: Throwable) { }
            return SmartTargetResult(chosen, sizes[idx]!!, true, sizes[idx]!!)
        }
        // No level reached the target — find the smallest produced.
        var smallestIdx = -1
        var smallestSize = Long.MAX_VALUE
        for (i in ladder.indices.reversed()) {
            val size = tryLevel(i)
            if (size > 0 && size < inputSize && size < smallestSize) {
                smallestIdx = i
                smallestSize = size
            }
        }
        for ((i, f) in files) if (i != smallestIdx) try { f.delete() } catch (_: Throwable) { }
        return if (smallestIdx >= 0) {
            SmartTargetResult(files[smallestIdx], smallestSize, false, smallestSize)
        } else {
            SmartTargetResult(null, inputSize, false, inputSize)
        }
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
     * Also re-encodes a large image as JPEG even when it fits the
     * page — INCLUDING images already stored as JPEG. A JPEG saved
     * at quality 95–100 (camera / scanner PDFs) shrinks 30–50% at
     * JPEG 82%, and the old code skipped those entirely, so Smart
     * Compress found "0 images" on the most common PDFs. Returns
     * null if the image was left unchanged.
     */
    private fun downscaleImage(
        doc: PDDocument,
        image: PDImageXObject,
        maxW: Int,
        maxH: Int,
        quality: Float
    ): PDImageXObject? {
        val needsDownscale = image.width > maxW || image.height > maxH
        // v0.1.177 FIX (poor compression): re-encode EVERY large image
        // as JPEG at [quality], including images already stored as
        // JPEG. The old code skipped JPEGs entirely (isJpeg → no
        // re-encode), so Smart Compress reported "already optimized"
        // on JPEG-heavy PDFs — the most common kind. Callers only
        // keep outputs smaller than the original, so a re-encode
        // that grows the file is discarded, never handed back.
        val pixelCount = image.width.toLong() * image.height.toLong()
        val needsReencode = pixelCount > 50_000L
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
            // v0.1.178 FIX (v0.1.177 regression — 26% became 1%):
            // pick the better codec per image by comparing the
            // ACTUAL factory outputs. v0.1.177 measured PNG bytes
            // (which use per-row filters) as a proxy for
            // LosslessFactory, but LosslessFactory writes raw RGB +
            // Flate with NO predictor, so it was far bigger than the
            // proxy said. Now both real outputs are created and
            // their true stream lengths compared; the loser is
            // closed (the pattern LosslessFactory itself uses, and
            // PDFBox does not write unreferenced objects, verified
            // by test — so no bloat). Photos win as JPEG;
            // screenshots / line art win lossless. Lossless is only
            // attempted for modest opaque sizes (<= 3 MP, no alpha)
            // — for huge photos JPEG wins anyway and Flate would be
            // very slow.
            val jpeg = try { JPEGFactory.createFromImage(doc, scaled, quality) } catch (_: Throwable) { null }
            var result = jpeg
            val scaledPixels = scaled.width.toLong() * scaled.height.toLong()
            if (!scaled.hasAlpha() && scaledPixels <= 3_000_000L) {
                val lossless = try { LosslessFactory.createFromImage(doc, scaled) } catch (_: Throwable) { null }
                if (lossless != null) {
                    if (jpeg == null) {
                        result = lossless
                    } else {
                        val jpegSize = try { jpeg.getCOSStream().getLength() } catch (_: Throwable) { -1L }
                        val losslessSize = try { lossless.getCOSStream().getLength() } catch (_: Throwable) { -1L }
                        if (losslessSize > 0 && (jpegSize <= 0 || losslessSize < jpegSize)) {
                            try { jpeg.getCOSStream().close() } catch (_: Throwable) { }
                            result = lossless
                        } else {
                            try { lossless.getCOSStream().close() } catch (_: Throwable) { }
                        }
                    }
                }
            }
            if (scaled !== bitmap) scaled.recycle()
            result
        } catch (_: Throwable) {
            null
        }
    }

    /** One image per page, fit to an A4 page. Returns true on success. */
    fun imageToPdf(imageFile: File, output: File): Boolean {
        return imageToPdf(listOf(imageFile), output)
    }

    /**
     * Image to PDF (multi-page): one A4 page per image, each image
     * scaled to fit with a small margin. Used by the scanner, which
     * combines several scanned photos into a single PDF.
     */
    fun imageToPdf(imageFiles: List<File>, output: File): Boolean {
        return imageToPdf(imageFiles, output, PDRectangle.A4)
    }

    /**
     * Image to PDF (multi-page) with a chosen page size (v0.1.132):
     * A4 or Letter, portrait or landscape (the caller passes the
     * rectangle already oriented). Used by Image to PDF's
     * multi-page screen. Same lossless engine as the A4 version —
     * one page per image, scaled to fit with a small margin.
     */
    fun imageToPdf(imageFiles: List<File>, output: File, pageRect: PDRectangle): Boolean {
        return try {
            PDDocument().use { doc ->
                for (imageFile in imageFiles) {
                    val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return false
                    val page = PDPage(pageRect)
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
                    bitmap.recycle()
                }
                doc.save(output)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Image to PDF, optimized for scanned pages.
     *
     * The plain imageToPdf uses LosslessFactory, which embeds each
     * photo as lossless PNG — a 12 MP phone photo becomes ~10 MB
     * in the PDF, so 2 photos = 22 MB. That is what the user
     * reported ("capture panuna pdf 22 mb pothu 2 photo kae").
     *
     * This version downscales each page to A4 print resolution
     * (longest side 2000 px, about 200 DPI) and embeds it as
     * JPEG quality 80. A 2-page scan is about 1 MB instead of
     * 22 MB, and the text stays crisp at print size.
     *
     * It is the "Compressed" tier of the export dialog — see
     * [imageToPdfOriginal] and [imageToPdfExtreme].
     */
    fun imageToPdfOptimized(imageFiles: List<File>, output: File): Boolean {
        return imageToPdfQuality(imageFiles, output, 2000, 0.8f)
    }

    /**
     * "Original Quality" export: the pages at FULL camera
     * resolution, JPEG quality 95 — no downscaling at all.
     * Biggest file, best quality. A 2-page 12 MP scan is
     * roughly 4–8 MB instead of 22 MB (lossless PNG).
     */
    fun imageToPdfOriginal(imageFiles: List<File>, output: File): Boolean {
        return imageToPdfQuality(imageFiles, output, Int.MAX_VALUE, 0.95f)
    }

    /**
     * "Extreme Compress" export: longest side 1200 px, JPEG
     * quality 60. Smallest file, for sharing over WhatsApp or
     * slow connections. A 2-page scan is a few hundred KB.
     */
    fun imageToPdfExtreme(imageFiles: List<File>, output: File): Boolean {
        return imageToPdfQuality(imageFiles, output, 1200, 0.6f)
    }

    /** Shared engine for the three export tiers. [maxSidePx] is
     *  the longest side allowed (Int.MAX_VALUE = no downscale),
     *  [jpegQuality] is the JPEG quality (0–1). */
    fun imageToPdfQuality(
        imageFiles: List<File>,
        output: File,
        maxSidePx: Int,
        jpegQuality: Float
    ): Boolean {
        return imageToPdfQuality(imageFiles, output, maxSidePx, jpegQuality, PDRectangle.A4)
    }

    /** Same tier engine with a chosen page size (v0.1.133,
     *  Batch 17): Image to PDF's size chooser builds all three
     *  tiers on the user's A4/Letter + Portrait/Landscape page,
     *  so the sizes shown are the real sizes for that page. */
    fun imageToPdfQuality(
        imageFiles: List<File>,
        output: File,
        maxSidePx: Int,
        jpegQuality: Float,
        pageRect: PDRectangle
    ): Boolean {
        return try {
            PDDocument().use { doc ->
                for (imageFile in imageFiles) {
                    val original = BitmapFactory.decodeFile(imageFile.absolutePath) ?: return false
                    val bitmap = downscaleForPrint(original, maxSidePx)
                    if (bitmap != original) original.recycle()
                    val page = PDPage(pageRect)
                    doc.addPage(page)
                    val image = JPEGFactory.createFromImage(doc, bitmap, jpegQuality)
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
                    bitmap.recycle()
                }
                doc.save(output)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Downscale a bitmap so its longest side is at most [maxSide]
     *  px (A4 at about 200 DPI for 2000 — crisp for print, small
     *  in a PDF). Returns the original bitmap unchanged if it is
     *  already small, or if [maxSide] is Int.MAX_VALUE. */
    private fun downscaleForPrint(bitmap: Bitmap, maxSide: Int = 2000): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * Rotate every page by [degrees] (90, 180 or 270). Adds to the
     * page's existing rotation, so rotating twice works as expected.
     * Returns true on success.
     */
    fun rotate(input: File, output: File, degrees: Int): Boolean {
        return rotate(input, output, degrees, null)
    }

    /** Rotate only [pageOneBased] (1-based), or every page when null. */
    fun rotate(input: File, output: File, degrees: Int, pageOneBased: Int?): Boolean {
        return try {
            PDDocument.load(input).use { doc ->
                if (pageOneBased == null) {
                    for (page in doc.pages) {
                        page.rotation = (page.rotation + degrees) % 360
                    }
                } else {
                    val page = doc.getPage(pageOneBased - 1)
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
    /**
     * Watermark (v0.1.153, Fix #28 — options version).
     * [opts.position]: 0 = centre diagonal, 1 = centre horizontal,
     * 2 = top centre, 3 = bottom centre, 4 = top-left, 5 = top-right,
     * 6 = bottom-left, 7 = bottom-right. Opacity 0..1, rotation in
     * degrees, colour as 0xRRGGBB. Pages outside fromPage..toPage are
     * left untouched. The old 3-arg call keeps working via defaults.
     */
    data class WatermarkOptions(
        val position: Int = 0,
        val opacity: Float = 0.35f,
        val fontSizePt: Float = 48f,
        val colorRgb: Int = 0xB4B4B4,
        val rotationDeg: Float = 45f,
        val fromPage: Int = 1,
        val toPage: Int = Int.MAX_VALUE
    )

    fun watermark(
        input: File, output: File, text: String,
        opts: WatermarkOptions = WatermarkOptions()
    ): Boolean {
        return try {
            var stamped = 0
            PDDocument.load(input).use { doc ->
                val total = doc.numberOfPages
                val to = minOf(opts.toPage, total)
                val font = PDType1Font.HELVETICA_BOLD
                val size = opts.fontSizePt
                val r = (opts.colorRgb shr 16) and 0xFF
                val g = (opts.colorRgb shr 8) and 0xFF
                val bl = opts.colorRgb and 0xFF
                val angle = Math.toRadians(opts.rotationDeg.toDouble())
                val cos = kotlin.math.cos(angle).toFloat()
                val sin = kotlin.math.sin(angle).toFloat()
                for (index in 0 until total) {
                    val pageNum = index + 1
                    if (pageNum < opts.fromPage || pageNum > to) continue
                    try {
                        val page = doc.getPage(index)
                        val box = page.cropBox
                        val textW = font.getStringWidth(text) / 1000f * size
                        // Centre point of the text, in page coordinates.
                        val margin = 36f
                        val cx = when (opts.position) {
                            4, 6 -> box.lowerLeftX + margin + textW / 2f
                            5, 7 -> box.lowerLeftX + box.width - margin - textW / 2f
                            else -> box.lowerLeftX + box.width / 2f
                        }
                        val cy = when (opts.position) {
                            2 -> box.lowerLeftY + box.height - margin - size / 2f
                            3 -> box.lowerLeftY + margin + size / 2f
                            4, 5 -> box.lowerLeftY + box.height - margin - size / 2f
                            6, 7 -> box.lowerLeftY + margin + size / 2f
                            else -> box.lowerLeftY + box.height / 2f
                        }
                        // Text origin = centre moved back half the width
                        // along the rotated text direction, so the text is
                        // centred on (cx, cy) whatever the rotation is.
                        val ox = cx - (textW / 2f) * cos
                        val oy = cy - (textW / 2f) * sin
                        PDPageContentStream(
                            doc, page,
                            PDPageContentStream.AppendMode.APPEND, true, true
                        ).use { content ->
                            val gs = PDExtendedGraphicsState()
                            gs.nonStrokingAlphaConstant = opts.opacity.coerceIn(0.05f, 1f)
                            content.setGraphicsStateParameters(gs)
                            content.setNonStrokingColor(r, g, bl)
                            content.beginText()
                            content.setFont(font, size)
                            content.setTextMatrix(
                                Matrix.getRotateInstance(angle, ox, oy)
                            )
                            content.showText(text)
                            content.endText()
                        }
                        stamped++
                    } catch (_: Throwable) {
                        // One bad page must not kill the whole file
                        // (v0.1.193: was missing — a single bad page
                        // made the entire watermark fail).
                    }
                }
                if (stamped > 0) doc.save(output)
            }
            stamped > 0 && output.exists() && output.length() > 0
        } catch (_: Throwable) {
            false
        }
    }


    /**
     * Page Numbers: stamp "Page X of Y" at the bottom centre of
     * every page. Returns true on success.
     */
    /**
     * Page Numbers (v0.1.153, Fix #24 — rewritten).
     * ROOT CAUSE of "suthama work aagala": the old version stamped
     * from the page's mediaBox with newLineAtOffset, ignoring the
     * cropBox AND the page rotation. On PDFs whose cropBox is offset
     * or whose pages carry rotation (very common for scanned / phone
     * PDFs), the number landed OUTSIDE the visible area — the file
     * saved fine, but the user saw nothing. This version positions
     * from the cropBox, converts the wanted visual position into PDF
     * coordinates for the page's rotation, draws the text upright
     * (text matrix counter-rotated), isolates per-page failures, and
     * returns false unless the output file exists and is non-empty.
     */
    data class PageNumberOptions(
        val format: Int = 2, // 0 = "1", 1 = "Page 1", 2 = "Page 1 of 10"
        val position: Int = 4, // 0 TL, 1 TC, 2 TR, 3 BL, 4 BC, 5 BR
        val fontSizePt: Float = 12f,
        val colorRgb: Int = 0x111827,
        val skipCover: Boolean = false,
        val startNumber: Int = 1,
        val fromPage: Int = 1,
        val toPage: Int = Int.MAX_VALUE
    )

    fun pageNumbers(
        input: File, output: File,
        opts: PageNumberOptions = PageNumberOptions()
    ): Boolean {
        return try {
            var stamped = 0
            PDDocument.load(input).use { doc ->
                val total = doc.numberOfPages
                val to = minOf(opts.toPage, total)
                for (index in 0 until total) {
                    val pageNum = index + 1
                    if (pageNum < opts.fromPage || pageNum > to) continue
                    if (opts.skipCover && pageNum == 1) continue
                    val displayNum = opts.startNumber + (pageNum - 1)
                    val label = when (opts.format) {
                        0 -> "$displayNum"
                        1 -> "Page $displayNum"
                        else -> "Page $displayNum of $total"
                    }
                    try {
                        val page = doc.getPage(index)
                        val crop = page.cropBox
                        val rot = ((page.rotation % 360) + 360) % 360
                        val w = crop.width
                        val h = crop.height
                        // Visual (display) size after rotation.
                        val dispW = if (rot == 90 || rot == 270) h else w
                        val dispH = if (rot == 90 || rot == 270) w else h
                        val font = PDType1Font.HELVETICA
                        val size = opts.fontSizePt
                        val textW = font.getStringWidth(label) / 1000f * size
                        val margin = 24f
                        // Wanted visual point of the label's LEFT edge,
                        // measured from the top-left of the displayed page.
                        val dx = when (opts.position % 3) {
                            0 -> margin
                            1 -> (dispW - textW) / 2f
                            else -> dispW - margin - textW
                        }
                        val dy = if (opts.position < 3) margin + size else dispH - margin
                        // Convert display point -> PDF user-space point.
                        val pdfX: Float
                        val pdfY: Float
                        when (rot) {
                            90 -> { pdfX = crop.lowerLeftX + (dispH - dy); pdfY = crop.lowerLeftY + dx }
                            180 -> { pdfX = crop.lowerLeftX + (dispW - dx - textW); pdfY = crop.lowerLeftY + dy }
                            270 -> { pdfX = crop.lowerLeftX + dy; pdfY = crop.lowerLeftY + (dispW - dx - textW) }
                            else -> { pdfX = crop.lowerLeftX + dx; pdfY = crop.lowerLeftY + (dispH - dy) }
                        }
                        PDPageContentStream(
                            doc, page,
                            PDPageContentStream.AppendMode.APPEND, true, true
                        ).use { content ->
                            content.beginText()
                            content.setFont(font, size)
                            val r = (opts.colorRgb shr 16) and 0xFF
                            val g = (opts.colorRgb shr 8) and 0xFF
                            val bl = opts.colorRgb and 0xFF
                            content.setNonStrokingColor(r, g, bl)
                            // Counter-rotate so the text reads upright
                            // after the viewer applies the page rotation.
                            content.setTextMatrix(
                                Matrix.getRotateInstance(
                                    Math.toRadians((-rot).toDouble()),
                                    pdfX, pdfY
                                )
                            )
                            content.showText(label)
                            content.endText()
                        }
                        stamped++
                    } catch (_: Throwable) {
                        // One bad page must not kill the whole file.
                    }
                }
                if (stamped > 0) doc.save(output)
            }
            stamped > 0 && output.exists() && output.length() > 0
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

    /**
     * PDF to JPG: render every page to a JPG image at [dpi] (default
     * 150). Returns the list of image files created, or an empty
     * list on failure. Files are named <base>_page1.jpg, etc.
     */
    fun pdfToJpg(input: File, outputDir: File, dpi: Float = 150f): List<File> {
        val created = mutableListOf<File>()
        return try {
            if (!outputDir.exists()) outputDir.mkdirs()
            PDDocument.load(input).use { doc ->
                val renderer = PDFRenderer(doc)
                val base = input.nameWithoutExtension
                for (page in 0 until doc.numberOfPages) {
                    val bitmap = renderer.renderImageWithDPI(page, dpi)
                    val out = File(outputDir, "${base}_page${page + 1}.jpg")
                    FileOutputStream(out).use { stream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    }
                    bitmap.recycle()
                    created.add(out)
                }
            }
            created
        } catch (_: Throwable) {
            created
        }
    }

    /**
     * Extract TXT: pull all text out of the PDF. Returns the text,
     * or null on failure. The caller saves it as a .txt file.
     */

    /**
     * Protect PDF: lock the PDF with a user [password]. Anyone
     * opening it must type the password. Returns true on success.
     */
    fun protect(input: File, output: File, password: String): Boolean {
        return try {
            PDDocument.load(input).use { doc ->
                val permission = AccessPermission()
                val policy = StandardProtectionPolicy(password, password, permission)
                policy.encryptionKeyLength = 128
                doc.protect(policy)
                doc.save(output)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Unlock PDF: remove the password from a locked PDF. The caller
     * must supply the current [password]. Returns true on success.
     */
    fun unlock(input: File, output: File, password: String): Boolean {
        return try {
            PDDocument.load(input, password).use { doc ->
                // Saving without a policy strips the protection.
                doc.isAllSecurityToBeRemoved = true
                doc.save(output)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Organize PDF: reorder the pages. [order] is a list of 1-based
     * page numbers in the new order, e.g. [3, 1, 2] puts page 3
     * first. Must list every page exactly once. Returns true on
     * success.
     */
    fun organize(input: File, output: File, order: List<Int>): Boolean {
        return try {
            PDDocument.load(input).use { doc ->
                val total = doc.numberOfPages
                if (order.size != total) return false
                if (order.toSet().size != total) return false
                if (order.any { it < 1 || it > total }) return false
                val pages = (0 until total).map { doc.getPage(it) }
                val newDoc = PDDocument()
                try {
                    for (n in order) {
                        newDoc.importPage(pages[n - 1])
                    }
                    newDoc.save(output)
                } finally {
                    newDoc.close()
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Repair PDF: load with PDFBox's lenient parser (it recovers
     * broken xref tables in most cases) and re-save a clean copy
     * with a rebuilt xref. Fixes xref/structure damage in roughly
     * 50-70% of won't-open PDFs; a file whose content itself is
     * destroyed cannot be repaired. Returns true on success.
     */
    fun repair(input: File, output: File): Boolean {
        return try {
            PDDocument.load(input).use { doc ->
                doc.save(output)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Resize Pages: rebuild every page at [target] size, scaling
     * the original page content to fit (centred, aspect kept).
     * Vector and text content stays vector — nothing is rasterised.
     * [fit] = true scales to fit the new size (default). When
     * false the content is never enlarged — pages are placed at
     * their original size, centred, and only shrunk if they do
     * not fit (v0.1.152 "Keep size" option).
     * Returns true on success.
     */
    fun resizePages(input: File, output: File, target: PDRectangle, fit: Boolean = true): Boolean {
        return try {
            PDDocument.load(input).use { src ->
                PDDocument().use { dst ->
                    val layer = LayerUtility(dst)
                    for (page in src.pages) {
                        val srcBox = page.mediaBox
                        val form = layer.importPageAsForm(src, page)
                        val newPage = PDPage(target)
                        dst.addPage(newPage)
                        val fitScale = minOf(
                            target.width / srcBox.width,
                            target.height / srcBox.height
                        )
                        val scale = if (fit) fitScale else minOf(fitScale, 1f)
                        val w = srcBox.width * scale
                        val h = srcBox.height * scale
                        val x = (target.width - w) / 2f
                        val y = (target.height - h) / 2f
                        PDPageContentStream(dst, newPage).use { cs ->
                            cs.saveGraphicsState()
                            cs.transform(Matrix.getTranslateInstance(x, y))
                            cs.transform(Matrix.getScaleInstance(scale, scale))
                            cs.drawForm(form)
                            cs.restoreGraphicsState()
                        }
                    }
                    dst.save(output)
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Page info for the Sign screen: (pageCount, widthPt, heightPt)
     * of [pageIndex] (0-based), or null if the file won't open. */
    fun pageInfo(input: File, pageIndex: Int): Triple<Int, Float, Float>? {
        return try {
            PDDocument.load(input).use { doc ->
                val total = doc.numberOfPages
                if (pageIndex !in 0 until total) return null
                val box = doc.getPage(pageIndex).mediaBox
                Triple(total, box.width, box.height)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Sign PDF: draw the [signaturePng] (transparent PNG from the
     * signature pad) onto page [pageIndex] (0-based) at (x, y)
     * PDF points from the bottom-left corner, sized w x h points.
     * Returns true on success.
     */
    fun signPdf(
        input: File, output: File, signaturePng: File,
        pageIndex: Int, x: Float, y: Float, w: Float, h: Float
    ): Boolean {
        return try {
            val bitmap = BitmapFactory.decodeFile(signaturePng.absolutePath)
                ?: return false
            PDDocument.load(input).use { doc ->
                val page = doc.getPage(pageIndex)
                val img = LosslessFactory.createFromImage(doc, bitmap)
                PDPageContentStream(
                    doc, page,
                    PDPageContentStream.AppendMode.APPEND, true, true
                ).use { cs ->
                    cs.drawImage(img, x, y, w, h)
                }
                doc.save(output)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** One placed signature for [signMultiple]: [x]/[y] are PDF
     * points from the bottom-left corner of [page]. */
    data class SignPlacement(
        val bitmap: Bitmap,
        val page: Int,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float
    )

    /**
     * Sign PDF with MULTIPLE signatures (v0.1.156, Fix #47):
     * stamps every placement in ONE pass — one document load,
     * one save — so adding a second signature costs no extra
     * re-encode. Placements on out-of-range pages are skipped.
     * Returns true on success.
     */
    fun signMultiple(input: File, output: File, placements: List<SignPlacement>): Boolean {
        return try {
            PDDocument.load(input).use { doc ->
                for (p in placements) {
                    if (p.page !in 0 until doc.numberOfPages) continue
                    val page = doc.getPage(p.page)
                    val img = LosslessFactory.createFromImage(doc, p.bitmap)
                    PDPageContentStream(
                        doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true
                    ).use { cs ->
                        cs.drawImage(img, p.x, p.y, p.w, p.h)
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
     * First-page thumbnail (v0.1.150, for the Smart / Lossless
     * preview at the top of the result screens): renders page 0
     * at a DPI chosen so the width is about [maxWidthPx]. Returns
     * the bitmap, or null on failure. Call off the UI thread —
     * rendering a page takes a moment.
     */
    /**
     * Page thumbnail for ANY page (v0.1.153, for Organize / Sign /
     * Delete Pages grids): renders page [pageIndex] (0-based) at a
     * DPI chosen so the width is about [maxWidthPx]. Returns the
     * bitmap, or null on failure. Call off the UI thread.
     */
    fun renderPageThumb(input: File, pageIndex: Int, maxWidthPx: Int): Bitmap? {
        return try {
            PDDocument.load(input).use { doc ->
                if (pageIndex !in 0 until doc.numberOfPages) return null
                val pageW = doc.getPage(pageIndex).mediaBox.width
                if (pageW <= 0) return null
                val dpi = (72f * maxWidthPx / pageW).coerceIn(36f, 150f)
                PDFRenderer(doc).renderImageWithDPI(pageIndex, dpi)
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * First-page thumbnail (v0.1.150, for the Smart / Lossless
     * preview at the top of the result screens): renders page 0
     * at a DPI chosen so the width is about [maxWidthPx]. Returns
     * the bitmap, or null on failure. Call off the UI thread —
     * rendering a page takes a moment.
     */
    fun renderFirstPageThumb(input: File, maxWidthPx: Int): Bitmap? {
        return renderPageThumb(input, 0, maxWidthPx)
    }

    /** One page reference for rebuildPages: [file], 0-based [page],
     * and a clockwise [rotation] delta (0/90/180/270) to apply. */
    data class PageRef(val file: File, val page: Int, val rotation: Int)

    /**
     * Rebuild a PDF from an ordered list of pages (v0.1.153, for
     * Organize): pages may come from more than one file, may repeat
     * (duplicate), may be omitted (delete), and each may carry its
     * own rotation. Vector/text content stays vector — pages are
     * imported as forms, never rasterised. Returns true on success.
     */
    fun rebuildPages(items: List<PageRef>, output: File): Boolean {
        return try {
            if (items.isEmpty()) return false
            PDDocument().use { dst ->
                val layer = LayerUtility(dst)
                val docs = mutableMapOf<File, PDDocument>()
                try {
                    for (item in items) {
                        val src = docs.getOrPut(item.file) { PDDocument.load(item.file) }
                        val srcPage = src.getPage(item.page)
                        val box = srcPage.mediaBox
                        val rot = ((item.rotation % 360) + 360) % 360
                        val newPage = if (rot == 90 || rot == 270) {
                            PDPage(PDRectangle(box.height, box.width))
                        } else {
                            PDPage(PDRectangle(box.width, box.height))
                        }
                        dst.addPage(newPage)
                        val form = layer.importPageAsForm(src, srcPage)
                        PDPageContentStream(dst, newPage).use { cs ->
                            cs.saveGraphicsState()
                            if (rot != 0) {
                                val cx = newPage.mediaBox.width / 2f
                                val cy = newPage.mediaBox.height / 2f
                                cs.transform(Matrix.getTranslateInstance(cx, cy))
                                cs.transform(Matrix.getRotateInstance(Math.toRadians((-rot).toDouble()), 0f, 0f))
                                cs.transform(Matrix.getTranslateInstance(-box.width / 2f, -box.height / 2f))
                            }
                            cs.drawForm(form)
                            cs.restoreGraphicsState()
                        }
                    }
                    dst.save(output)
                } finally {
                    for (d in docs.values) {
                        try { d.close() } catch (_: Throwable) { }
                    }
                }
            }
            output.exists() && output.length() > 0
        } catch (_: Throwable) {
            false
        }
    }

}
