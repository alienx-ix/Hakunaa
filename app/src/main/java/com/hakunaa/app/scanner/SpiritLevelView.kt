package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Spirit level — the bubble level professional scanners show
 * while framing a page. A horizontal track with a bubble that
 * slides left and right with the phone's tilt; when the phone
 * is level (within about 2 degrees) the bubble turns teal.
 *
 * The tilt is the roll around the phone's long axis, from the
 * accelerometer: roll = atan2(x, sqrt(y^2 + z^2)). That works
 * whether the phone is held upright (portrait) or flat over
 * the page (face down) — the two ways a scanner is held.
 * The bubble is smoothed (low-pass) so it does not jitter
 * with every hand tremor.
 */
class SpiritLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    /** Tilt in degrees; 0 = level. Clamped to +-15 for display.
     *  [targetRoll] is what the sensor reports; [roll] is what
     *  is drawn, eased toward the target a little every frame
     *  — so the bubble glides smoothly instead of jumping with
     *  every sensor sample. */
    private var targetRoll = 0f
    private var roll = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 60
        style = Paint.Style.FILL
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 160
        style = Paint.Style.FILL
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val bubbleLevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00BFA5")
        style = Paint.Style.FILL
    }

    /** The tilt, in degrees. Call from the sensor listener.
     *  The bubble eases toward it (see onDraw) — smooth
     *  animation, not a jump. */
    fun setRoll(degrees: Float) {
        val clamped = degrees.coerceIn(-15f, 15f)
        if (abs(clamped - targetRoll) > 0.05f) {
            targetRoll = clamped
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        // Ease the drawn bubble toward the target — a little
        // closer every frame, so it glides. Keep animating
        // until it has settled on the target.
        roll += (targetRoll - roll) * 0.25f
        if (abs(targetRoll - roll) > 0.05f) {
            postInvalidateOnAnimation()
        } else {
            roll = targetRoll
        }
        val cy = h / 2f
        val trackH = 6f * density
        val track = RectF(0f, cy - trackH / 2f, w, cy + trackH / 2f)
        val radius = 8f * density
        canvas.drawRoundRect(track, trackH / 2f, trackH / 2f, trackPaint)
        // Centre tick — the bubble sits on it when level.
        val tickW = 2f * density
        canvas.drawRect(
            w / 2f - tickW / 2f, cy - 10f * density,
            w / 2f + tickW / 2f, cy + 10f * density, tickPaint
        )
        // Bubble: slides with the tilt, teal when level.
        val bubbleR = 11f * density
        val travel = (w / 2f) - bubbleR - 4f * density
        val bx = w / 2f + (roll / 15f) * travel
        val paint = if (abs(roll) < 2f) bubbleLevelPaint else bubblePaint
        canvas.drawCircle(bx, cy, bubbleR, paint)
        // A small dark dot in the bubble's centre, so it reads
        // as a bubble even when it is teal.
        canvas.drawCircle(bx, cy, 3f * density, tickPaint)
    }

    companion object {
        /** Roll around the phone's long axis, in degrees, from
         *  one accelerometer sample. 0 = level, positive = the
         *  phone's right side is down. Works upright and flat. */
        fun rollFromAccelerometer(x: Float, y: Float, z: Float): Float {
            val denom = sqrt(y * y + z * z)
            return Math.toDegrees(atan2(x.toDouble(), denom.toDouble())).toFloat()
        }
    }
}
