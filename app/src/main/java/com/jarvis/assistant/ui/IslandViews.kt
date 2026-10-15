package com.jarvis.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Root of the task island. Draws the black shape itself so it can morph smoothly between the
 * compact pill and the expanded card ([expansion] 0 → 1), clips the content to that shape, and
 * draws the moving border while Jarvis speaks.
 */
class IslandFrame @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density

    /** 0 = compact pill around [compact], 1 = card filling the frame. */
    var expansion = 0f
        set(v) { field = v; invalidate() }

    /** Loudness of Jarvis's voice (0..1), or null when the voice can't report it. Null provider = not speaking. */
    var speechLevel: (() -> Float?)? = null
        set(v) { field = v; invalidate() }

    /** Called once per frame while the island is visible, to refresh live content. */
    var onFrame: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shape = Path()
    private val rect = RectF()
    private val rim = RectF()
    private val rimPath = Path()
    private var radiusNow = 0f
    private val matrix = Matrix()
    private var smoothed = 0f
    private var gradientFor = 0f
    private var gradient: SweepGradient? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    private val compact: View? get() = findViewById(com.jarvis.assistant.R.id.compact)

    override fun dispatchDraw(canvas: Canvas) {
        onFrame?.invoke()
        val c = compact ?: return super.dispatchDraw(canvas)
        val pad = paddingLeft.toFloat()
        val p = expansion.coerceIn(0f, 1.1f)

        // Compact pill: wherever the compact row is laid out. Expanded: the whole frame minus padding.
        val l = lerp(c.left.toFloat(), pad, p)
        val t = c.top.toFloat()
        val r = lerp(c.right.toFloat(), width - pad, p)
        val b = lerp(c.bottom.toFloat(), height - pad, p)
        val radius = lerp(c.height / 2f, 34 * density, p.coerceAtMost(1f))

        // While speaking, the shape swells a little with the voice.
        val provider = speechLevel
        val target = if (provider == null) 0f else provider() ?: syntheticLevel()
        smoothed += (target - smoothed) * 0.35f
        val swell = smoothed * 2.5f * density
        rect.set(l - swell, t - swell * 0.6f, r + swell, b + swell * 0.6f)

        shape.reset()
        radiusNow = radius + swell
        shape.addRoundRect(rect, radiusNow, radiusNow, Path.Direction.CW)
        canvas.drawPath(shape, fill)

        canvas.save()
        canvas.clipPath(shape)
        super.dispatchDraw(canvas)
        canvas.restore()

        if (provider != null) drawSpeakingBorder(canvas)
        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }

    /** A bright arc that sweeps around the border, thicker and brighter with louder speech. */
    private fun drawSpeakingBorder(canvas: Canvas) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        if (gradient == null || gradientFor != cx) {
            gradientFor = cx
            gradient = SweepGradient(cx, cy,
                intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0xFFFFFFFF.toInt(), 0x55FFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.12f, 0.22f, 0.32f, 0.45f, 0.6f, 0.78f, 1f))
        }
        val angle = (SystemClock.uptimeMillis() % 2400L) / 2400f * 360f
        matrix.setRotate(angle, cx, cy)
        gradient!!.setLocalMatrix(matrix)

        val level = smoothed
        // Soft glow around the outside.
        glow.shader = gradient
        glow.strokeWidth = (3f + level * 6f) * density
        glow.alpha = (60 + level * 100).toInt()
        canvas.drawPath(shape, glow)
        // A crisp rim just inside the edge, so it shows on light and dark apps alike.
        val w = (1.6f + level * 2f) * density
        rim.set(rect)
        rim.inset(w / 2, w / 2)
        val r = (rect.height() / 2).coerceAtMost(radiusNow) - w / 2
        rimPath.reset()
        rimPath.addRoundRect(rim, r, r, Path.Direction.CW)
        border.shader = gradient
        border.strokeWidth = w
        border.alpha = (190 + level * 65).toInt()
        canvas.drawPath(rimPath, border)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}

/** Speech-like movement for voices that can't report their loudness (Android's built-in voice). */
internal fun syntheticLevel(): Float {
    val t = SystemClock.uptimeMillis() / 1000f
    return (0.25f + 0.45f * abs(sin(t * 7.3f) * sin(t * 2.1f + 1f))).coerceIn(0f, 1f)
}

/** Four small bars that bounce with a sound level, like the island's audio indicator. */
class LevelBars @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Current loudness, 0..1, or null to show a quiet resting state. */
    var level: (() -> Float?)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val heights = FloatArray(4) { 0.15f }
    private val shape = floatArrayOf(0.6f, 1f, 0.8f, 0.5f) // middle bars move most
    private val bar = RectF()

    override fun onDraw(canvas: Canvas) {
        val source = level
        val l = if (source == null) 0f else (source() ?: syntheticLevel())
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in heights.indices) {
            val wobble = 0.75f + 0.25f * sin(t * (9f + i * 2.3f) + i)
            val target = max(0.15f, min(1f, l * 1.6f * shape[i] * wobble))
            // Rise quickly, fall back gently.
            heights[i] += (target - heights[i]) * (if (target > heights[i]) 0.6f else 0.18f)
        }
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = w / 4f
        val bw = gap * 0.55f
        for (i in heights.indices) {
            val bh = max(bw, h * heights[i])
            val x = gap * i + (gap - bw) / 2
            bar.set(x, (h - bh) / 2, x + bw, (h + bh) / 2)
            canvas.drawRoundRect(bar, bw / 2, bw / 2, paint)
        }
        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }
}

/** A spring curve: overshoots a little and settles, like iOS animations. */
class SpringInterpolator(private val damping: Float = 0.72f) : android.view.animation.Interpolator {
    override fun getInterpolation(t: Float): Float {
        val w = 2 * Math.PI.toFloat() * 1.35f
        val zeta = damping
        val decay = exp(-zeta * w * t)
        val wd = w * kotlin.math.sqrt(1 - zeta * zeta)
        return 1 - decay * (kotlin.math.cos(wd * t) + (zeta * w / wd) * sin(wd * t))
    }
}
