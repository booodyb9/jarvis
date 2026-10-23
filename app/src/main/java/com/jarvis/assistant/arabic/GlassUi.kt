package com.jarvis.assistant.arabic

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*

object GlassUi {
    const val INK = 0xFF102B49.toInt()
    const val TEAL = 0xFF008D94.toInt()
    const val MUTED = 0xFF536E83.toInt()
    const val RED = 0xFFB43240.toInt()
    fun dp(c: Context, n: Int) = (n * c.resources.displayMetrics.density).toInt()
    fun bg(color: Int = 0xF5F7FCFF.toInt(), radius: Float = 24f) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(color, color)
    ).apply { cornerRadius = radius; setStroke(1, 0xFFD1E4EC.toInt()) }
    fun column(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16))
    }
    fun text(c: Context, value: String, size: Float = 16f, color: Int = INK) = TextView(c).apply {
        text = value; textSize = size; setTextColor(color)
        textDirection = View.TEXT_DIRECTION_RTL; setPadding(0, dp(c, 7), 0, dp(c, 7))
        setLineSpacing(dp(c, 3).toFloat(), 1f)
    }
    fun button(c: Context, label: String, primary: Boolean = false, stop: Boolean = false, action: () -> Unit) = Button(c).apply {
        text = label; textSize = 16f; isAllCaps = false
        minHeight = dp(c, 50); minimumWidth = 0; minWidth = 0
        setTextColor(if (stop) RED else if (primary) Color.WHITE else INK)
        backgroundTintList = null
        background = bg(if (primary) TEAL else if (stop) 0xFFFFF1F2.toInt() else 0xFFEAF5FA.toInt(), dp(c, 16).toFloat())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(c, 5), 0, dp(c, 5)) }
        setOnClickListener { action() }
    }
    fun field(c: Context, hintValue: String) = EditText(c).apply {
        hint = hintValue; textSize = 16f; setTextColor(INK); setHintTextColor(MUTED)
        textDirection = View.TEXT_DIRECTION_RTL
        setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12))
        minHeight = dp(c, 56); maxLines = 5; background = bg(Color.WHITE, dp(c, 16).toFloat())
    }
}

/** Small vector-drawn companion; no image model or animation download is required. */
class RobotView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { contentDescription = "مساعدك — اضغط لفتح المحادثة، واسحب لتحريك الفقاعة" }
    override fun onDraw(canvas: Canvas) {
        val s = width.coerceAtMost(height).toFloat(); val k = s / 64f
        canvas.save(); canvas.scale(k, k)
        p.shader = LinearGradient(0f, 0f, 64f, 64f, Color.WHITE, 0xFFB7E4EF.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(1f, 1f, 63f, 63f, 25f, 25f, p); p.shader = null
        p.color = GlassUi.INK; canvas.drawRoundRect(11f, 15f, 53f, 49f, 16f, 16f, p)
        p.color = 0xFF29E1D6.toInt(); canvas.drawCircle(24f, 29f, 4f, p); canvas.drawCircle(41f, 29f, 4f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f
        canvas.drawArc(25f, 32f, 40f, 41f, 15f, 150f, false, p); p.style = Paint.Style.FILL
        canvas.restore()
    }
}
