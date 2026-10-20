package com.jarvis.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * Self-contained, lightweight robot companion artwork.
 * No third-party assets, network calls, permissions or external animation engine.
 * Intended for an opt-in accessibility overlay in a later integration step.
 */
class RobotCompanionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class Mood { IDLE, LISTENING, THINKING, SPEAKING, SUCCESS, ERROR }

    var mood: Mood = Mood.IDLE
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = RectF()
    private val visor = RectF()
    private val antenna = Path()
    private val blue = Color.rgb(76, 210, 245)
    private val white = Color.rgb(238, 247, 255)
    private val navy = Color.rgb(20, 31, 53)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (104 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val save = canvas.save()
        val s = minOf(width, height) / 100f
        canvas.translate((width - 100f * s) / 2f, (height - 100f * s) / 2f)
        canvas.scale(s, s)

        val t = SystemClock.uptimeMillis() / 1000f
        val floatY = sin(t * 2.1f) * 2.4f
        canvas.translate(0f, floatY)

        paint.style = Paint.Style.FILL
        paint.color = navy
        paint.alpha = 75
        canvas.drawOval(23f, 86f, 77f, 94f, paint)
        paint.alpha = 255

        // Rounded shoulders and body
        paint.color = 0xFFCAD8E8.toInt()
        body.set(31f, 57f, 69f, 84f)
        canvas.drawRoundRect(body, 15f, 15f, paint)
        paint.color = white
        body.set(17f, 23f, 83f, 68f)
        canvas.drawRoundRect(body, 23f, 23f, paint)

        // Antenna
        paint.color = blue
        paint.strokeWidth = 3f
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        antenna.reset()
        antenna.moveTo(50f, 24f)
        antenna.lineTo(50f, 14f)
        canvas.drawPath(antenna, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(50f, 11f, 5f, paint)

        // Dark glass visor
        paint.color = navy
        visor.set(24f, 34f, 76f, 59f)
        canvas.drawRoundRect(visor, 12f, 12f, paint)

        val eyeColor = when (mood) {
            Mood.SUCCESS -> 0xFF58E3A0.toInt()
            Mood.ERROR -> 0xFFFF8290.toInt()
            else -> blue
        }
        paint.color = eyeColor
        val blink = sin(t * 0.9f) > 0.995f
        val eyeHeight = if (blink) 1f else if (mood == Mood.LISTENING) 6.5f else 4.5f
        canvas.drawRoundRect(34f, 43f - eyeHeight, 42f, 43f + eyeHeight, 4f, 4f, paint)
        canvas.drawRoundRect(58f, 43f - eyeHeight, 66f, 43f + eyeHeight, 4f, 4f, paint)

        // Heart / status light
        paint.color = eyeColor
        val pulse = if (mood == Mood.THINKING || mood == Mood.SPEAKING) 2f * sin(t * 6f) else 0f
        canvas.drawCircle(50f, 73f, 4.5f + pulse, paint)

        canvas.restoreToCount(save)
        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateDelayed(33L)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // No handlers or animators survive detachment.
    }
}
