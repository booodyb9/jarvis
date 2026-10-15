package com.jarvis.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Status orb: a plain disc that breathes when ready, sends out rings while listening and has a
 * thin spinning arc while working.
 */
class ArcReactorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { OFF, IDLE, LISTENING, WORKING }

    var mode: Mode = Mode.OFF
        set(value) { field = value; invalidate() }

    /** Disable for screenshots/tests so a single static frame is drawn. */
    var animate = true

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arc = RectF()
    private val startTime = System.currentTimeMillis()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = if (animate) (System.currentTimeMillis() - startTime) / 1000f else 0.4f
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val core = r * 0.5f

        val color = when (mode) {
            Mode.OFF -> Color.rgb(58, 58, 62)
            Mode.WORKING -> Color.rgb(217, 164, 74)
            else -> Color.rgb(242, 242, 242)
        }

        when (mode) {
            Mode.LISTENING -> {
                // Two rings expanding outwards and fading.
                ring.strokeWidth = r * 0.02f
                for (i in 0 until 2) {
                    val p = ((t * 0.8f + i * 0.5f) % 1f)
                    ring.color = withAlpha(color, (110 * (1 - p)).toInt())
                    canvas.drawCircle(cx, cy, core + (r * 0.95f - core) * p, ring)
                }
            }
            Mode.WORKING -> {
                ring.strokeWidth = r * 0.03f
                ring.color = withAlpha(color, 40)
                val ar = core + r * 0.18f
                canvas.drawCircle(cx, cy, ar, ring)
                ring.color = color
                arc.set(cx - ar, cy - ar, cx + ar, cy + ar)
                canvas.drawArc(arc, t * 240f, 80f, false, ring)
            }
            else -> {}
        }

        val breathe = if (mode == Mode.IDLE) 1f + 0.03f * sin(t * 1.6f) else 1f
        fill.color = color
        canvas.drawCircle(cx, cy, core * breathe, fill)

        if (animate && mode != Mode.OFF && isAttachedToWindow) postInvalidateOnAnimation()
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
}
