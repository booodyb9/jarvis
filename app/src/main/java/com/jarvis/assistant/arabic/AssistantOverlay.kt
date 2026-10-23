package com.jarvis.assistant.arabic

import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlin.math.abs
import kotlin.math.min

/** Small windows: touches outside the sheet continue to the underlying app. */
class AssistantOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var bubble: View? = null
    private var panel: View? = null
    private var marker: View? = null
    private var selector: View? = null
    private var input: EditText? = null
    private var reply: TextView? = null
    private var proposal: LinearLayout? = null
    private var editor: LinearLayout? = null
    private var cachedInput = ""
    private var cachedStatus = "أهلًا! تحب أساعدك في إيه؟"
    private var busy = false
    private val prefs = service.getSharedPreferences("arabic_overlay", 0)
    var onSend: (String) -> Unit = {}
    var onTalk: () -> Unit = {}
    var onStop: () -> Unit = {}
    var onSelect: () -> Unit = {}
    var onSpeak: () -> Unit = {}
    var onExecute: () -> Unit = {}
    var onGuide: () -> Unit = {}
    var onClose: () -> Unit = {}

    private fun dp(n: Int) = GlassUi.dp(service, n)
    private fun params(width: Int, height: Int, focus: Boolean = false) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (focus) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE), PixelFormat.TRANSLUCENT
    ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }

    fun showBubble() {
        if (bubble != null) return
        val robot = RobotView(service)
        val m = service.resources.displayMetrics
        val p = params(dp(60), dp(60)).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = prefs.getInt("x", m.widthPixels - dp(64)).coerceIn(0, (m.widthPixels - dp(60)).coerceAtLeast(0))
            y = prefs.getInt("y", m.heightPixels / 3).coerceIn(dp(28), (m.heightPixels - dp(100)).coerceAtLeast(dp(28)))
        }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        robot.setOnClickListener { if (panel == null) open() else onClose() }
        robot.setOnTouchListener { v, e ->
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - downX) + abs(e.rawY - downY) > dp(8)) dragging = true
                    if (dragging) {
                        p.x = (startX + e.rawX - downX).toInt().coerceIn(0, (m.widthPixels - dp(60)).coerceAtLeast(0))
                        p.y = (startY + e.rawY - downY).toInt().coerceIn(dp(28), (m.heightPixels - dp(100)).coerceAtLeast(dp(28)))
                        wm.updateViewLayout(v, p)
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) v.performClick() else {
                        p.x = if (p.x < m.widthPixels / 2) dp(4) else m.widthPixels - dp(64)
                        wm.updateViewLayout(v, p); prefs.edit().putInt("x", p.x).putInt("y", p.y).apply()
                    }; true
                }
                else -> true
            }
        }
        runCatching { wm.addView(robot, p); bubble = robot }
    }

    fun open() {
        if (panel != null) return
        showBubble()
        val root = GlassUi.column(service).apply { background = GlassUi.bg(radius = dp(28).toFloat()); elevation = dp(12).toFloat() }
        val scroll = ScrollView(service).apply { isFillViewport = false; addView(root) }
        val head = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        head.addView(RobotView(service), LinearLayout.LayoutParams(dp(46), dp(46)))
        head.addView(GlassUi.text(service, "مساعدك", 23f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        head.addView(GlassUi.button(service, "×") { onClose() }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(head)
        val tools = LinearLayout(service).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        listOf("اتكلم" to onTalk, "اكتب" to { input?.requestFocus(); input?.let { service.getSystemService(InputMethodManager::class.java).showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }; Unit },
            "حدد مكان" to onSelect, "إيقاف" to onStop).forEach { (label, action) ->
            tools.addView(GlassUi.button(service, label, stop = label == "إيقاف", action = action), LinearLayout.LayoutParams(0, dp(58), 1f).apply { setMargins(dp(2), dp(6), dp(2), dp(6)) })
        }
        root.addView(tools)
        proposal = GlassUi.column(service).apply { visibility = View.GONE; background = GlassUi.bg(0xFFE4F6F7.toInt(), dp(18).toFloat()) }
        root.addView(proposal)
        reply = GlassUi.text(service, cachedStatus).also { root.addView(it) }
        editor = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        input = GlassUi.field(service, "اكتب طلبك أو سؤالك…").apply { setText(cachedInput) }
        editor?.addView(input)
        editor?.addView(GlassUi.text(service, "راجع كلامك قبل الإرسال", 14f, GlassUi.MUTED))
        editor?.addView(GlassUi.button(service, "إرسال", true) { onSend(input?.text?.toString().orEmpty()) })
        root.addView(editor)
        root.addView(GlassUi.button(service, "اسمع الرد") { onSpeak() })
        root.addView(GlassUi.button(service, "إيقاف", stop = true) { onStop() })
        val m = service.resources.displayMetrics
        val p = params(min(m.widthPixels - dp(24), dp(400)), (m.heightPixels * 0.68f).toInt(), true).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(28)
        }
        runCatching { wm.addView(scroll, p); panel = scroll; setBusy(busy) }
    }

    fun status(text: String) { cachedStatus = text; reply?.text = text }
    fun setInput(text: String) { cachedInput = text; input?.setText(text) }
    fun setBusy(value: Boolean) { busy = value; editor?.visibility = if (value) View.GONE else View.VISIBLE }
    fun ask(text: String) { open(); status(text); editor?.visibility = View.VISIBLE }
    fun propose(text: String, bounds: Rect?) {
        open(); status("بانتظار موافقتك")
        proposal?.apply {
            removeAllViews(); visibility = View.VISIBLE
            addView(GlassUi.text(service, text, 18f))
            addView(GlassUi.text(service, "موافقتك للفعل المعروض فقط — تنتهي بعد دقيقة", 14f, GlassUi.MUTED))
            val row = LinearLayout(service).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
            row.addView(GlassUi.button(service, "نفّذ مرة", true) { onExecute() }.apply { filterTouchesWhenObscured = true }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            row.addView(GlassUi.button(service, "ورّيني") { onGuide() }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(row)
        }
        bounds?.let { showMark(it) }
        (panel as? ScrollView)?.smoothScrollTo(0, 0)
    }
    fun clearProposal() { proposal?.removeAllViews(); proposal?.visibility = View.GONE; clearHighlights() }
    fun guide(text: String) { status(text); collapse() }
    fun collapse() {
        cachedInput = input?.text?.toString() ?: cachedInput
        panel?.let { runCatching { wm.removeView(it) } }; panel = null; input = null; reply = null; editor = null; proposal = null
    }
    fun clearHighlights() {
        marker?.let { runCatching { wm.removeView(it) } }; marker = null
        selector?.let { runCatching { wm.removeView(it) } }; selector = null
    }
    fun hide() { collapse(); clearHighlights(); bubble?.let { runCatching { wm.removeView(it) } }; bubble = null }

    private fun showMark(bounds: Rect) {
        clearHighlights()
        val mark = object : View(service) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlassUi.TEAL; strokeWidth = dp(3).toFloat(); style = Paint.Style.STROKE }
            init { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            override fun onDraw(c: Canvas) {
                val r = RectF(bounds).apply { inset(-dp(4).toFloat(), -dp(4).toFloat()) }
                c.drawRoundRect(r, dp(18).toFloat(), dp(18).toFloat(), p)
                val cy = r.centerY(); val x = (r.left - dp(24)).coerceAtLeast(dp(4).toFloat())
                c.drawLine(x, cy, r.left, cy, p); c.drawLine(r.left - dp(9), cy - dp(8), r.left, cy, p)
                c.drawLine(r.left - dp(9), cy + dp(8), r.left, cy, p)
            }
        }
        val p = params(-1, -1).apply { gravity = Gravity.TOP or Gravity.LEFT; flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN }
        runCatching { wm.addView(mark, p); marker = mark }
    }

    fun select(done: (Rect) -> Unit) {
        collapse(); clearHighlights()
        val canvas = object : View(service) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            var sx = 0f; var sy = 0f; var ex = 0f; var ey = 0f
            override fun onDraw(c: Canvas) {
                c.drawColor(0x550B2239); p.color = GlassUi.TEAL; p.strokeWidth = dp(3).toFloat(); p.style = Paint.Style.STROKE
                c.drawRect(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex), maxOf(sy, ey), p)
                p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = dp(17).toFloat(); p.textAlign = Paint.Align.CENTER
                c.drawText("اسحب حول المكان المطلوب • اضغط رجوع للإلغاء", width / 2f, dp(110).toFloat(), p)
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.x; sy = e.y; ex = sx; ey = sy }
                    MotionEvent.ACTION_MOVE -> { ex = e.x; ey = e.y }
                    MotionEvent.ACTION_UP -> {
                        ex = e.x; ey = e.y
                        val rect = Rect(minOf(sx, ex).toInt(), minOf(sy, ey).toInt(), maxOf(sx, ex).toInt(), maxOf(sy, ey).toInt())
                        if (rect.width() < dp(8)) rect.inset(-dp(12), 0)
                        if (rect.height() < dp(8)) rect.inset(0, -dp(12))
                        clearHighlights(); done(rect)
                    }
                    MotionEvent.ACTION_CANCEL -> { clearHighlights(); open() }
                }
                invalidate(); return true
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) { clearHighlights(); open() }; return true }
                return super.dispatchKeyEvent(event)
            }
        }.apply { isFocusableInTouchMode = true; contentDescription = "اسحب لتحديد جزء من الشاشة. اضغط رجوع للإلغاء" }
        val p = params(-1, -1, true).apply { gravity = Gravity.TOP or Gravity.LEFT; flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN }
        runCatching { wm.addView(canvas, p); selector = canvas; canvas.requestFocus() }
    }
}
