package com.jarvis.assistant.arabic

import android.accessibilityservice.AccessibilityService
import android.graphics.*
import android.content.Intent
import android.os.Build
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
    private var screenMode = false
    val isChat get() = !screenMode
    private var chatPage: ConversationView? = null
    private var screenPage: LinearLayout? = null
    private var screenScroll: ScrollView? = null
    private var chatTab: Button? = null
    private var screenTab: Button? = null
    private var panelStatus: TextView? = null
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

    fun openChat() { screenMode = false; open(); renderMode() }
    fun openScreen() { screenMode = true; open(); renderMode() }

    private fun renderMode() {
        chatPage?.visibility = if (screenMode) View.GONE else View.VISIBLE
        screenPage?.visibility = if (screenMode) View.VISIBLE else View.GONE
        chatTab?.setTextColor(if (screenMode) GlassUi.MUTED else GlassUi.TEAL)
        screenTab?.setTextColor(if (screenMode) GlassUi.TEAL else GlassUi.MUTED)
        panelStatus?.text = if (screenMode) "كل خطوة بموافقتك" else "صوت وكتابة، في نفس المحادثة"
    }

    fun open() {
        if (panel != null) { renderMode(); return }
        showBubble()
        val root = GlassUi.column(service).apply {
            background = GlassUi.bg(radius = dp(28).toFloat()); elevation = dp(12).toFloat()
        }
        val head = LinearLayout(service).apply { gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        head.addView(RobotView(service), LinearLayout.LayoutParams(dp(40), dp(40)))
        val title = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        title.addView(GlassUi.text(service, "مساعدك", 21f))
        panelStatus = GlassUi.text(service, "", 11f, GlassUi.MUTED).also { title.addView(it) }
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        head.addView(GlassUi.button(service, "↗") {
            onClose()
            service.startActivity(Intent(service, ArabicHomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }.apply { contentDescription = "افتح الدردشة في التطبيق" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        head.addView(GlassUi.button(service, "×") { onClose() }.apply { contentDescription = "تصغير الفقاعة" },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(head)
        val tabs = LinearLayout(service)
        chatTab = GlassUi.button(service, "دردشة") { if (screenMode) onStop(); screenMode = false; renderMode() }
        screenTab = GlassUi.button(service, "الشاشة") { screenMode = true; renderMode() }
        tabs.addView(chatTab, LinearLayout.LayoutParams(0, dp(48), 1f))
        tabs.addView(screenTab, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(tabs)
        val body = FrameLayout(service)
        chatPage = ConversationView(service).apply {
            onTalk = { this@AssistantOverlay.onTalk() }
            onScreen = { screenMode = true; renderMode() }
        }
        body.addView(chatPage, FrameLayout.LayoutParams(-1, -1))
        val screenRoot = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        screenPage = screenRoot
        val tools = LinearLayout(service).apply { layoutDirection = View.LAYOUT_DIRECTION_RTL }
        listOf("اتكلم" to onTalk, "حدد مكان" to onSelect, "إيقاف" to onStop).forEach { (label, action) ->
            tools.addView(GlassUi.button(service, label, stop = label == "إيقاف", action = action),
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) })
        }
        screenRoot.addView(tools)
        reply = GlassUi.text(service, cachedStatus)
        screenScroll = ScrollView(service).apply { addView(reply) }
        screenRoot.addView(screenScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        proposal = GlassUi.column(service).apply { visibility = View.GONE; background = GlassUi.bg(0xFFE4F6F7.toInt(), dp(18).toFloat()) }
        screenRoot.addView(proposal)
        editor = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        input = GlassUi.field(service, "اكتب طلبك أو سؤالك عن الشاشة…").apply { setText(cachedInput); maxLines = 3 }
        editor?.addView(input)
        val sendRow = LinearLayout(service)
        sendRow.addView(GlassUi.button(service, "إرسال", true) { onSend(input?.text?.toString().orEmpty()) },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        sendRow.addView(GlassUi.button(service, "اسمع الرد") { onSpeak() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        editor?.addView(sendRow)
        screenRoot.addView(editor)
        body.addView(screenRoot, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val m = service.resources.displayMetrics
        val normalHeight = min(dp(640), (m.heightPixels * 0.80f).toInt())
        val p = params(min(m.widthPixels - dp(24), dp(400)), normalHeight, true).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(12)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bottom = insets.getInsets(WindowInsets.Type.ime() or WindowInsets.Type.systemBars()).bottom
                val available = (m.heightPixels - bottom - dp(48)).coerceAtLeast(dp(240))
                val height = min(normalHeight, available)
                if (p.height != height) { p.height = height; if (panel === root) runCatching { wm.updateViewLayout(root, p) } }
                insets
            }
        }
        runCatching { wm.addView(root, p); panel = root; setBusy(busy); renderMode() }
    }

    fun status(text: String) { cachedStatus = text; reply?.text = text; bubble?.contentDescription = "مساعدك — " + text }
    fun setInput(text: String) { cachedInput = text; input?.setText(text) }
    fun setBusy(value: Boolean) { busy = value; editor?.visibility = if (value) View.GONE else View.VISIBLE }
    fun ask(text: String) { openScreen(); status(text); editor?.visibility = View.VISIBLE }
    fun propose(text: String, bounds: Rect?) {
        openScreen(); status("بانتظار موافقتك")
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
        screenScroll?.smoothScrollTo(0, 0)
    }
    fun clearProposal() { proposal?.removeAllViews(); proposal?.visibility = View.GONE; clearHighlights() }
    fun guide(text: String) { status(text); collapse() }
    fun collapse() {
        cachedInput = input?.text?.toString() ?: cachedInput
        panel?.let { runCatching { wm.removeView(it) } }; panel = null; input = null; reply = null; editor = null; proposal = null
        chatPage = null; screenPage = null; screenScroll = null; chatTab = null; screenTab = null; panelStatus = null
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
