package com.hakunaa.app.scanner

/**
 * The overlays (drawing, signature, text) for a scanned page,
 * kept across Enhance sessions (v0.1.84).
 *
 * The problem it fixes: Enhance runs more than once per page
 * (a thumbnail review with "Continue", then Done with
 * "Finish"), and each run is a NEW activity with empty
 * in-memory overlays. The drawing made in the review was
 * baked into the enhanced file, but the next Enhance loaded
 * the RAW file — so the drawing vanished and the user saw
 * "everything disappear after Continue". Now the overlays
 * live here, keyed by the page's RAW path (which never
 * changes), so every Enhance run loads them on open and
 * writes them back on save. The filter still comes from the
 * raw file exactly once (v0.1.77) — only the overlays persist.
 *
 * Signatures and texts are stored as PNG files (not Bitmaps)
 * so there is no bitmap-lifecycle fight between activities:
 * the file is the source of truth, and each activity decodes
 * it when it needs it. Strokes are plain data and are kept
 * in memory for the session.
 */
object OverlayStore {

    /** One page's overlays. */
    data class OverlayData(
        /** Freehand strokes (normalized coords). */
        val strokes: List<DrawView.Stroke> = emptyList(),
        /** Signature PNG file paths (v0.1.92: a page can hold
         *  MORE than one signature), parallel to [signatureRects]. */
        val signatureFiles: List<String> = emptyList(),
        /** Signature normalized rects [l, t, r, b], one per file. */
        val signatureRects: List<FloatArray> = emptyList(),
        /** Text PNG file path, or null when none. */
        val textFile: String? = null,
        /** Text normalized rect [l, t, r, b], or null. */
        val textRect: FloatArray? = null
    )

    private val store = mutableMapOf<String, OverlayData>()

    fun get(rawPath: String): OverlayData? = store[rawPath]

    fun put(rawPath: String, data: OverlayData) {
        store[rawPath] = data
    }

    fun remove(rawPath: String) {
        store.remove(rawPath)
    }

    fun clear() {
        store.clear()
    }
}
