package com.hakunaa.app.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Spirit level — the bubble level professional scanners show
 * while framing a page. A round bullseye (v0.1.59): the bubble
 * moves in TWO directions — left / right AND top / bottom —
 * so the user can level the phone in every direction, not just
 * sideways. The old version was a horizontal track that only
 * showed left / right; the user asked for top / bottom too,
 * with a different animation. When the phone is level (within
 * about 2 degrees on both axes) the bubble sits in the centre
 * and turns teal.
 *
 * Style 01 Classic Bullseye (v0.1.62): the user picked this
 * style from the animation preview page — a classic bullseye
 * with a yellow bubble (the way a real spirit level looks),
 * gliding smoothly.
 *
 * Traffic-light colours (v0.1.63, the user's idea): the
 * bubble is RED when the phone is far off level (worst
 * angle), YELLOW when it is close (balanced), and GREEN
 * when it sits in the centre (best angle, level) — so the
 * colour alone tells the user how level the phone is. The
 * circle is a little bigger too (v0.1.63).
 *
 * Tilt comes from the accelerometer, two axes:
 *  roll  = atan2(x, sqrt(y^2 + z^2)) — left / right tilt.
 *  pitch = atan2(y, sqrt(x^2 + z^2)) — top / bottom tilt.
 * That works whether the phone is held upright (portrait) or
 * flat over the page (face down) — the two ways a scanner is
 * held. The bubble is smoothed (low-pass) so it does not
 * jitter with every hand tremor.
 */
class SpiritLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    /** Tilt in degrees; 0 = level. Clamped to +-15 for display.
     *  [targetRoll] / [targetPitch] are what the sensor reports;
     *  [roll] / [pitch] are what is drawn, eased toward the
     *  target a little every frame — so the bubble glides
     *  smoothly instead of jumping with every sensor sample. */
    private var targetRoll = 0f
    private var targetPitch = 0f
    private var roll = 0f
    private var pitch = 0f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 90
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 160
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Style 01 Classic Bullseye (v0.1.62): a yellow bubble,
        // like a real spirit level — the style the user picked
        // from the animation preview page.
        color = Color.parseColor("#FFD600")
        style = Paint.Style.FILL
    }

    // Traffic-light colours (v0.1.63): red = far off level
    // (worst angle), yellow = close (balanced), green = in
    // the centre (best angle, level). The old teal "level"
    // paint is gone — green is the level colour now.
    private val bubbleWorstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252")
        style = Paint.Style.FILL
    }

    private val bubbleLevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00C853")
        style = Paint.Style.FILL
    }

    private val bubbleDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 90
        style = Paint.Style.FILL
    }

    /** The tilt, in degrees — roll is left / right, pitch is
     *  top / bottom. Call from the sensor listener. The bubble
     *  eases toward it (see onDraw) — smooth animation, not a
     *  jump. */
    fun setTilt(rollDegrees: Float, pitchDegrees: Float) {
        val clampedRoll = rollDegrees.coerceIn(-15f, 15f)
        val clampedPitch = pitchDegrees.coerceIn(-15f, 15f)
        if (abs(clampedRoll - targetRoll) > 0.05f ||
            abs(clampedPitch - targetPitch) > 0.05f
        ) {
            targetRoll = clampedRoll
            targetPitch = clampedPitch
            invalidate()
        }
    }

    /** Backwards-compatible single-axis setter (left / right
     *  only) — kept so old callers still compile. */
    fun setRoll(degrees: Float) {
        setTilt(degrees, targetPitch)
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
        pitch += (targetPitch - pitch) * 0.25f
        if (abs(targetRoll - roll) > 0.05f || abs(targetPitch - pitch) > 0.05f) {
            postInvalidateOnAnimation()
        } else {
            roll = targetRoll
            pitch = targetPitch
        }
        val cx = w / 2f
        val cy = h / 2f
        // The bullseye: an outer ring and a small centre ring
        // the bubble must sit inside when the phone is level.
        val outerR = (minOf(w, h) / 2f) - 3f * density
        val centreR = 7f * density
        canvas.drawCircle(cx, cy, outerR, ringPaint)
        canvas.drawCircle(cx, cy, centreR, centrePaint)
        // Bubble: moves with the tilt in both directions —
        // roll left / right, pitch top / bottom. Traffic-light
        // colour (v0.1.63): GREEN when level (within 2 degrees
        // on both axes — the bubble sits in the centre ring),
        // YELLOW when close (within 6 degrees — balanced),
        // RED when far off (worst angle). Clamped inside
        // the ring.
        val bubbleR = 11f * density
        val travel = outerR - bubbleR - 2f * density
        val bx = cx + (roll / 15f) * travel
        val by = cy + (pitch / 15f) * travel
        val worst = maxOf(abs(roll), abs(pitch))
        val paint = when {
            worst < 2f -> bubbleLevelPaint
            worst < 6f -> bubblePaint
            else -> bubbleWorstPaint
        }
        canvas.drawCircle(bx, by, bubbleR, paint)
        // A small dark dot in the bubble's centre, so it reads
        // as a bubble in any colour.
        canvas.drawCircle(bx, by, 3f * density, bubbleDotPaint)
    }

    companion object {
        /** Roll around the phone's long axis, in degrees, from
         *  one accelerometer sample. 0 = level, positive = the
         *  phone's right side is down. Works upright and flat. */
        fun rollFromAccelerometer(x: Float, y: Float, z: Float): Float {
            val denom = sqrt(y * y + z * z)
            return Math.toDegrees(atan2(x.toDouble(), denom.toDouble())).toFloat()
        }

        /** Pitch — the top / bottom tilt, in degrees, from one
         *  accelerometer sample. 0 = level, positive = the
         *  phone's top is tipped forward (away from the user).
         *  Works upright and flat. */
        fun pitchFromAccelerometer(x: Float, y: Float, z: Float): Float {
            val denom = sqrt(x * x + z * z)
            return Math.toDegrees(atan2(y.toDouble(), denom.toDouble())).toFloat()
        }
    }
}
