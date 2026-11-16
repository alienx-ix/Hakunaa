package com.hakunaa.app.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One collage cell: its rect (fractions of the canvas, 0..1),
 *  which picked photo it shows, and the pan offset of that photo
 *  inside the cell (fractions of the cell size, 0 = centred).
 *  The activity owns this list; the view mutates rect/pan in place,
 *  so Save always renders exactly what the preview shows. */
data class CollageCell(
    var rect: RectF,
    var photoIndex: Int,
    var panX: Float = 0f,
    var panY: Float = 0f
)

enum class CollageStyle { MINIMAL, FREESTYLE, POSTER, STITCHING }

/**
 * Interactive live collage preview (v0.1.119).
 *
 * Draws the collage itself from decoded bitmaps + the cell model —
 * no background bitmap render, so panning / moving / resizing
 * updates instantly on draw. Touch behaviour:
 *  - tap a cell to select it (teal highlight; in Freestyle the
 *    corner handles appear),
 *  - drag inside a cell to PAN the photo within it (clamped so
 *    the photo always covers the cell),
 *  - drag a photo from the strip onto a cell to ASSIGN that photo
 *    to the cell (drop highlight while hovering),
 *  - in Freestyle, drag an already-selected cell's body to MOVE
 *    the whole cell freely (clamped inside the canvas), and drag
 *    a cell edge/corner to RESIZE it. A first drag on a cell that
 *    was not selected yet pans its photo, so both gestures stay
 *    available: tap once to select, then drag to move.
 *
 * Poster mode reserves the bottom 12% of the canvas for the
 * caption (only when caption text is non-blank); Stitching draws
 * dashed "sewn" borders around every cell.
 */
class CollagePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** Fraction of canvas height kept for the Poster caption. */
        const val POSTER_CAPTION_FRACTION = 0.12f
        fun posterScaleY(style: CollageStyle, caption: String): Float =
            if (style == CollageStyle.POSTER && caption.isNotBlank())
                1f - POSTER_CAPTION_FRACTION else 1f
    }

    // ---- model (set by the activity; cells list is shared by reference) ----
    private var bitmaps: List<Bitmap?> = emptyList()
    private var cells: List<CollageCell> = emptyList()
    private var style: CollageStyle = CollageStyle.MINIMAL
    private var gapDp: Int = 8
    private var bgColor: Int = Color.WHITE
    private var rounded: Boolean = false
    private var ratioW: Int = 1
    private var ratioH: Int = 1
    private var posterText: String = ""

    /** (fromPhotoIndex, cellIndex) — activity swaps/assigns the photo. */
    var onPhotoDropped: ((Int, Int) -> Unit)? = null
    /** Fired after a pan / move / resize changed the model (activity:
     *  nothing to re-render — the view already shows it; kept for
     *  status text). */
    var onModelChanged: (() -> Unit)? = null

    var selectedIndex: Int = -1
        private set

    private var dropIndex: Int = -1

    // ---- paints (reused; nothing allocated in onDraw except RectFs) ----
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#D8DCE2") }
    private val placeholderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A94A6"); textSize = 44f; textAlign = Paint.Align.CENTER
    }
    private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5"); style = Paint.Style.STROKE; strokeWidth = 9f
    }
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5"); style = Paint.Style.STROKE; strokeWidth = 12f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5"); style = Paint.Style.FILL
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val stitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A6472"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val freestyleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 10f
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val clipPath = Path()
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val tmpRect = RectF()
    private val outlineRect = RectF()
    private val contentRect = RectF()

    init {
        isFocusable = true
        isClickable = true
        setOnDragListener { _, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_LOCATION -> {
                    val hit = cellAt(event.x, event.y)
                    if (hit != dropIndex) { dropIndex = hit; invalidate() }
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    if (dropIndex != -1) { dropIndex = -1; invalidate() }
                    true
                }
                DragEvent.ACTION_DROP -> {
                    val from = event.clipData?.getItemAt(0)?.text?.toString()?.toIntOrNull()
                    val target = cellAt(event.x, event.y)
                    dropIndex = -1
                    invalidate()
                    if (from != null && target != -1) {
                        selectedIndex = target
                        onPhotoDropped?.invoke(from, target)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    if (dropIndex != -1) { dropIndex = -1; invalidate() }
                    true
                }
                else -> true
            }
        }
    }

    fun setModel(
        bitmaps: List<Bitmap?>,
        cells: List<CollageCell>,
        style: CollageStyle,
        gapDp: Int,
        bgColor: Int,
        rounded: Boolean,
        ratioW: Int,
        ratioH: Int,
        posterText: String
    ) {
        this.bitmaps = bitmaps
        this.cells = cells
        this.style = style
        this.gapDp = gapDp
        this.bgColor = bgColor
        this.rounded = rounded
        this.ratioW = ratioW
        this.ratioH = ratioH
        this.posterText = posterText
        if (selectedIndex >= cells.size) selectedIndex = -1
        invalidate()
    }

    /** fitCenter content rect for the current ratio inside this view. */
    private fun computeContentRect(): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return RectF()
        val target = ratioW.toFloat() / ratioH.toFloat()
        val viewRatio = vw / vh
        return if (viewRatio > target) {
            val w = vh * target
            RectF((vw - w) / 2f, 0f, (vw + w) / 2f, vh)
        } else {
            val h = vw / target
            RectF(0f, (vh - h) / 2f, vw, (vh + h) / 2f)
        }
    }

    /** Cell rect in view px (gap already inset, poster caption reserved). */
    private fun cellPx(cell: CollageCell, out: RectF) {
        val gapPx = gapDp * contentRect.width() / 360f
        val half = gapPx / 2f
        val scaleY = posterScaleY(style, posterText)
        out.set(
            contentRect.left + cell.rect.left * contentRect.width() + half,
            contentRect.top + cell.rect.top * contentRect.height() * scaleY + half,
            contentRect.left + cell.rect.right * contentRect.width() - half,
            contentRect.top + cell.rect.bottom * contentRect.height() * scaleY - half
        )
    }

    private fun cellAt(x: Float, y: Float): Int {
        if (contentRect.isEmpty) {
            // Touch/drag can arrive before the first draw.
            computeContentRect().let { contentRect.set(it) }
            if (contentRect.isEmpty) return -1
        }
        // Topmost first: last-drawn cell is on top.
        for (i in cells.indices.reversed()) {
            cellPx(cells[i], tmpRect)
            if (tmpRect.contains(x, y)) return i
        }
        return -1
    }

    /** Max pan (fraction of cell size) before the photo stops covering the cell. */
    private fun maxPan(cell: CollageCell, bmp: Bitmap, dstW: Float, dstH: Float): Pair<Float, Float> {
        val scale = max(dstW / bmp.width, dstH / bmp.height)
        val maxX = ((bmp.width * scale - dstW) / 2f / dstW).coerceAtLeast(0f)
        val maxY = ((bmp.height * scale - dstH) / 2f / dstH).coerceAtLeast(0f)
        return maxX to maxY
    }

    private fun clampPan(cell: CollageCell, bmp: Bitmap?, dstW: Float, dstH: Float) {
        if (bmp == null) { cell.panX = 0f; cell.panY = 0f; return }
        val (mx, my) = maxPan(cell, bmp, dstW, dstH)
        cell.panX = cell.panX.coerceIn(-mx, mx)
        cell.panY = cell.panY.coerceIn(-my, my)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        computeContentRect().let { contentRect.set(it) }
        if (contentRect.isEmpty) return
        val density = resources.displayMetrics.density

        // Background.
        bgPaint.color = bgColor
        canvas.drawRect(contentRect, bgPaint)

        val gapPx = gapDp * contentRect.width() / 360f
        val cornerRadius = when {
            style == CollageStyle.FREESTYLE -> 14f * density
            rounded -> 12f * density
            else -> 0f
        }

        for ((index, cell) in cells.withIndex()) {
            cellPx(cell, tmpRect)
            if (tmpRect.width() <= 0f || tmpRect.height() <= 0f) continue
            val bmp = bitmaps.getOrNull(cell.photoIndex)
            dstRect.set(
                tmpRect.left.toInt(), tmpRect.top.toInt(),
                tmpRect.right.toInt(), tmpRect.bottom.toInt()
            )
            if (bmp != null && !bmp.isRecycled) {
                clampPan(cell, bmp, tmpRect.width(), tmpRect.height())
                // Centre-crop with pan, via src rect — no scaled copy, no jank.
                val scale = max(tmpRect.width() / bmp.width, tmpRect.height() / bmp.height)
                val srcW = tmpRect.width() / scale
                val srcH = tmpRect.height() / scale
                val cx = bmp.width / 2f + cell.panX * tmpRect.width() / scale
                val cy = bmp.height / 2f + cell.panY * tmpRect.height() / scale
                srcRect.set(
                    (cx - srcW / 2f).toInt().coerceIn(0, bmp.width - 1),
                    (cy - srcH / 2f).toInt().coerceIn(0, bmp.height - 1),
                    (cx + srcW / 2f).toInt().coerceIn(1, bmp.width),
                    (cy + srcH / 2f).toInt().coerceIn(1, bmp.height)
                )
                if (cornerRadius > 0f) {
                    clipPath.reset()
                    clipPath.addRoundRect(RectF(dstRect), cornerRadius, cornerRadius, Path.Direction.CW)
                    canvas.save()
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
                }
            } else {
                canvas.drawRect(tmpRect, placeholderPaint)
                placeholderTextPaint.textSize = 40f * density
                canvas.drawText(
                    "${index + 1}",
                    tmpRect.centerX(), tmpRect.centerY() + 14f * density,
                    placeholderTextPaint
                )
            }
            // Freestyle: white frame so freeform cells read as separate photos.
            if (style == CollageStyle.FREESTYLE) {
                outlineRect.set(tmpRect)
                freestyleBorderPaint.strokeWidth = 3f * density
                if (cornerRadius > 0f) {
                    canvas.drawRoundRect(outlineRect, cornerRadius, cornerRadius, freestyleBorderPaint)
                } else {
                    canvas.drawRect(outlineRect, freestyleBorderPaint)
                }
            }
            // Stitching: dashed "sewn" border just inside each cell.
            if (style == CollageStyle.STITCHING) {
                val inset = (gapPx / 2f + 5f * density).coerceAtLeast(4f)
                stitchPaint.strokeWidth = 2.5f * density
                stitchPaint.pathEffect = DashPathEffect(
                    floatArrayOf(9f * density, 6f * density), 0f
                )
                canvas.drawRect(
                    tmpRect.left + inset, tmpRect.top + inset,
                    tmpRect.right - inset, tmpRect.bottom - inset, stitchPaint
                )
                stitchPaint.pathEffect = null
            }
            // Selection highlight.
            if (index == selectedIndex) {
                selectPaint.strokeWidth = 3f * density
                canvas.drawRect(tmpRect, selectPaint)
                if (style == CollageStyle.FREESTYLE) {
                    val r = 7f * density
                    val corners = listOf(
                        tmpRect.left to tmpRect.top, tmpRect.right to tmpRect.top,
                        tmpRect.left to tmpRect.bottom, tmpRect.right to tmpRect.bottom
                    )
                    for ((cx, cy) in corners) {
                        canvas.drawCircle(cx, cy, r, handlePaint)
                        canvas.drawCircle(cx, cy, r, handleStrokePaint)
                    }
                }
            }
            // Drop target highlight.
            if (index == dropIndex) {
                dropPaint.strokeWidth = 4f * density
                canvas.drawRect(tmpRect, dropPaint)
            }
        }

        // Poster caption: centred bold text in the reserved bottom band.
        if (style == CollageStyle.POSTER && posterText.isNotBlank()) {
            val bandTop = contentRect.top + contentRect.height() * (1f - POSTER_CAPTION_FRACTION)
            val bandH = contentRect.height() * POSTER_CAPTION_FRACTION
            val darkBg = isDark(bgColor)
            captionPaint.color = if (darkBg) Color.WHITE else Color.parseColor("#1A1A1A")
            var textSize = bandH * 0.42f
            captionPaint.textSize = textSize
            while (textSize > bandH * 0.18f &&
                captionPaint.measureText(posterText) > contentRect.width() * 0.92f
            ) {
                textSize *= 0.9f
                captionPaint.textSize = textSize
            }
            val baseline = bandTop + bandH / 2f + textSize * 0.35f
            canvas.drawText(posterText, contentRect.centerX(), baseline, captionPaint)
        }
    }

    private fun isDark(color: Int): Boolean {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b < 0.5f
    }

    // ---- touch: tap-select, pan, freestyle move / edge-corner resize ----
    private enum class DragMode { NONE, PAN, MOVE, RESIZE }

    private var dragMode = DragMode.NONE
    private var dragCell = -1
    private var lastX = 0f
    private var lastY = 0f
    private var resizeLeft = false
    private var resizeRight = false
    private var resizeTop = false
    private var resizeBottom = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val density = resources.displayMetrics.density
        val edge = 26f * density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = cellAt(event.x, event.y)
                if (hit == -1) {
                    selectedIndex = -1
                    dragMode = DragMode.NONE
                    invalidate()
                    return true
                }
                val wasSelected = (selectedIndex == hit)
                selectedIndex = hit
                dragCell = hit
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
                moved = false
                parent?.requestDisallowInterceptTouchEvent(true)
                if (style == CollageStyle.FREESTYLE) {
                    cellPx(cells[hit], tmpRect)
                    resizeLeft = abs(event.x - tmpRect.left) < edge
                    resizeRight = abs(event.x - tmpRect.right) < edge
                    resizeTop = abs(event.y - tmpRect.top) < edge
                    resizeBottom = abs(event.y - tmpRect.bottom) < edge
                    dragMode = when {
                        resizeLeft || resizeRight || resizeTop || resizeBottom -> DragMode.RESIZE
                        // Already-selected cell: drag moves the whole cell.
                        wasSelected -> DragMode.MOVE
                        // First touch on a cell: drag pans the photo inside it.
                        else -> DragMode.PAN
                    }
                } else {
                    dragMode = DragMode.PAN
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE || dragCell !in cells.indices) return true
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x; lastY = event.y
                if (abs(event.x - downX) > 4f * density || abs(event.y - downY) > 4f * density) {
                    moved = true
                }
                val cell = cells[dragCell]
                when (dragMode) {
                    DragMode.PAN -> {
                        cellPx(cell, tmpRect)
                        if (tmpRect.width() > 0f && tmpRect.height() > 0f) {
                            cell.panX += dx / tmpRect.width()
                            cell.panY += dy / tmpRect.height()
                            val bmp = bitmaps.getOrNull(cell.photoIndex)
                            clampPan(cell, bmp, tmpRect.width(), tmpRect.height())
                        }
                    }
                    DragMode.MOVE -> {
                        // Freestyle move: shift the whole rect, keeping
                        // its size, clamped inside the canvas.
                        val dw = dx / contentRect.width()
                        val dh = dy / (contentRect.height() * posterScaleY(style, posterText))
                        val r = cell.rect
                        val w = r.width()
                        val h = r.height()
                        if (contentRect.width() > 0f && contentRect.height() > 0f) {
                            r.left = (r.left + dw).coerceIn(0f, 1f - w)
                            r.top = (r.top + dh).coerceIn(0f, 1f - h)
                            r.right = r.left + w
                            r.bottom = r.top + h
                        }
                    }
                    DragMode.RESIZE -> {
                        // Freestyle resize: move whichever edges the finger grabbed.
                        val dw = dx / contentRect.width()
                        val dh = dy / (contentRect.height() * posterScaleY(style, posterText))
                        val r = cell.rect
                        val minSize = 0.12f
                        if (resizeLeft) r.left = (r.left + dw).coerceIn(0f, r.right - minSize)
                        if (resizeRight) r.right = (r.right + dw).coerceIn(r.left + minSize, 1f)
                        if (resizeTop) r.top = (r.top + dh).coerceIn(0f, r.bottom - minSize)
                        if (resizeBottom) r.bottom = (r.bottom + dh).coerceIn(r.top + minSize, 1f)
                        // Keep the photo covering the (possibly smaller) cell.
                        cellPx(cell, tmpRect)
                        clampPan(cell, bitmaps.getOrNull(cell.photoIndex), tmpRect.width(), tmpRect.height())
                    }
                    DragMode.NONE -> { }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val wasDrag = moved
                dragMode = DragMode.NONE
                dragCell = -1
                if (wasDrag) onModelChanged?.invoke()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Exposed for the activity's Save path: the exact content rect
     *  mapping is reproduced there from the same model, so this is
     *  only a convenience for tests/debugging. */
    fun selectedCell(): CollageCell? =
        if (selectedIndex in cells.indices) cells[selectedIndex] else null
}
