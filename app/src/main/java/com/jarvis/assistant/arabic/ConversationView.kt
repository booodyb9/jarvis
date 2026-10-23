package com.jarvis.assistant.arabic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*

/** Same chat UI in the home activity and the accessibility overlay. */
class ConversationView(context: Context) : LinearLayout(context) {
    private val chat = ChatConversation.get(context)
    private val model = GlassUi.text(context, "", 13f, GlassUi.MUTED)
    private val status = GlassUi.text(context, "", 13f, GlassUi.MUTED)
    private val transcript = LinearLayout(context).apply { orientation = VERTICAL }
    private val scroll = ScrollView(context).apply { isFillViewport = true; addView(transcript) }
    val input = GlassUi.field(context, "اكتب رسالتك…")
    private val send = GlassUi.button(context, "إرسال", true) { chat.send(input.text.toString()) }
    private val stop = GlassUi.button(context, "إيقاف", stop = true) { chat.cancel() }
    private val microphone = GlassUi.button(context, "اتكلم") { onTalk() }
    private val autoVoice = Switch(context).apply { text = "رد صوتي تلقائي"; textSize = 13f; setTextColor(GlassUi.MUTED) }
    private var unsubscribe: (() -> Unit)? = null
    private var rendering = false
    private var rendered: List<ChatConversation.Message>? = null
    var onTalk: () -> Unit = {}
    var onScreen: () -> Unit = {}

    init {
        orientation = VERTICAL
        layoutDirection = LAYOUT_DIRECTION_RTL
        addView(model)
        addView(scroll, LayoutParams(-1, 0, 1f))
        addView(status)
        val options = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        options.addView(autoVoice, LayoutParams(0, GlassUi.dp(context, 48), 1f))
        options.addView(GlassUi.button(context, "الشاشة") { onScreen() }, LayoutParams(GlassUi.dp(context, 88), GlassUi.dp(context, 48)))
        addView(options)
        input.apply {
            maxLines = 4
            minHeight = GlassUi.dp(context, 52)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEND && event == null) { chat.send(text.toString()); true } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!rendering) chat.updateDraft(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(input, LayoutParams(-1, -2))
        val row = LinearLayout(context)
        row.addView(send, LayoutParams(0, GlassUi.dp(context, 52), 1f).apply { marginEnd = GlassUi.dp(context, 6) })
        row.addView(microphone, LayoutParams(GlassUi.dp(context, 74), GlassUi.dp(context, 52)).apply { marginEnd = GlassUi.dp(context, 6) })
        row.addView(stop, LayoutParams(GlassUi.dp(context, 74), GlassUi.dp(context, 52)))
        addView(row)
        autoVoice.setOnCheckedChangeListener { _, enabled -> if (!rendering) chat.autoSpeak = enabled }
        render(chat.snapshot())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        unsubscribe?.invoke()
        unsubscribe = chat.observe { render(it) }
    }

    override fun onDetachedFromWindow() {
        unsubscribe?.invoke(); unsubscribe = null
        super.onDetachedFromWindow()
    }

    private fun render(state: ChatConversation.State) {
        rendering = true
        model.text = "النموذج: " + state.modelName.ifBlank {
            com.jarvis.assistant.Settings(context).primary()?.name ?: "أضف نموذجًا من الإعدادات"
        }
        status.text = state.status
        autoVoice.isChecked = state.autoSpeak
        if (input.text.toString() != state.draft) { input.setText(state.draft); input.setSelection(input.length()) }
        send.isEnabled = !state.busy
        microphone.isEnabled = !state.busy
        stop.visibility = if (state.busy) VISIBLE else GONE
        if (rendered != state.messages) {
            rendered = state.messages
            transcript.removeAllViews()
            if (state.messages.isEmpty()) {
                transcript.addView(GlassUi.text(context, "أهلًا، أنا مساعدك", 25f))
                transcript.addView(GlassUi.text(context, "اكتب أو اتكلم، ونفكر سوا.\nوللمساعدة داخل التطبيقات اختار «الشاشة».", 16f, GlassUi.MUTED))
                listOf("ساعدني أكتب رسالة", "اشرحلي فكرة ببساطة").forEach { example ->
                    transcript.addView(GlassUi.button(context, example) { chat.updateDraft(example); input.requestFocus() })
                }
            }
            state.messages.forEach { m ->
                val user = m.role == "user"
                val card = GlassUi.column(context).apply {
                    background = GlassUi.bg(if (user) 0xFFDDF3F2.toInt() else Color.WHITE, GlassUi.dp(context, 18).toFloat())
                    setPadding(GlassUi.dp(context, 14), GlassUi.dp(context, 8), GlassUi.dp(context, 14), GlassUi.dp(context, 8))
                }
                val label = (if (user) "أنت" else if (m.role == "assistant") "مساعدك" else "تنبيه") +
                    if (m.channel == "screen") " · الشاشة" else ""
                card.addView(GlassUi.text(context, label, 12f, GlassUi.MUTED))
                card.addView(GlassUi.text(context, m.text, 16f,
                    if (m.role == "error") GlassUi.RED else GlassUi.INK).apply { setTextIsSelectable(true) })
                if (m.model.isNotBlank()) card.addView(GlassUi.text(context, m.model, 11f, GlassUi.MUTED))
                if (m.role == "assistant") {
                    val actions = LinearLayout(context)
                    actions.addView(GlassUi.button(context, "اسمع") { chat.speak(m.text) }, LayoutParams(0, GlassUi.dp(context, 48), 1f))
                    actions.addView(GlassUi.button(context, "إيقاف الصوت") { chat.stopSpeech() }, LayoutParams(0, GlassUi.dp(context, 48), 1f))
                    actions.addView(GlassUi.button(context, "نسخ") {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("رد مساعدك", m.text))
                        Toast.makeText(context, "تم نسخ الرد", Toast.LENGTH_SHORT).show()
                    }, LayoutParams(0, GlassUi.dp(context, 48), 1f))
                    card.addView(actions)
                }
                transcript.addView(card, LayoutParams(-1, -2).apply {
                    setMargins(if (user) 0 else GlassUi.dp(context, 14), GlassUi.dp(context, 7),
                        if (user) GlassUi.dp(context, 14) else 0, GlassUi.dp(context, 7))
                })
            }
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        rendering = false
    }
}
