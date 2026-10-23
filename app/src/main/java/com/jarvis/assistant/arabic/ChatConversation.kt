package com.jarvis.assistant.arabic

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jarvis.assistant.Settings
import com.jarvis.assistant.agent.ChatSession
import com.jarvis.assistant.agent.Llm
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Application-owned conversation: both UI surfaces observe this, never each other's views. */
class ChatConversation private constructor(private val app: Context) {
    data class Message(val id: String, val role: String, val text: String,
        val channel: String = "chat", val model: String = "")
    data class State(val messages: List<Message>, val busy: Boolean, val status: String,
        val modelName: String, val draft: String, val autoSpeak: Boolean)

    private val prefs = app.getSharedPreferences("arabic_chat", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private val listeners = linkedSetOf<(State) -> Unit>()
    private val clearListeners = linkedSetOf<() -> Unit>()
    private var speechChannel = "chat"
    private val messages = mutableListOf<Message>()
    @Volatile private var activeSession: ChatSession? = null
    private var busy = false
    private var status = "جاهز للكلام معاك"
    private var modelName = ""
    private var draft = ""
    private val speech = ArabicSpeech(app) { status = it; emit() }

    var autoSpeak: Boolean
        get() = prefs.getBoolean("auto_speak", false)
        set(value) { prefs.edit().putBoolean("auto_speak", value).apply(); if (!value) speech.stop(); emit() }

    init {
        runCatching {
            val saved = JSONArray(prefs.getString("history", "[]"))
            for (i in maxOf(0, saved.length() - 100) until saved.length()) {
                val m = saved.getJSONObject(i)
                if (m.optString("role") in setOf("user", "assistant", "error", "system"))
                    messages += Message(m.optString("id", UUID.randomUUID().toString()), m.getString("role"),
                        m.optString("text").take(12000), m.optString("channel", "chat"), m.optString("model"))
            }
        }.onFailure { messages.clear() }
    }

    fun refreshModel() { if (!busy) modelName = Settings(app).primary()?.name.orEmpty(); emit() }

    fun snapshot() = State(messages.toList(), busy, status, modelName, draft, autoSpeak)

    fun observe(listener: (State) -> Unit): () -> Unit {
        listeners += listener
        listener(snapshot())
        return { listeners -= listener }
    }

    fun onClear(listener: () -> Unit): () -> Unit {
        clearListeners += listener
        return { clearListeners -= listener }
    }

    private fun emit() { val state = snapshot(); listeners.toList().forEach { it(state) } }

    private fun add(role: String, text: String, channel: String = "chat", model: String = "") {
        messages += Message(UUID.randomUUID().toString(), role, text.take(12000), channel, model)
        while (messages.size > 100) messages.removeAt(0)
        val data = JSONArray()
        messages.forEach { m -> data.put(JSONObject().put("id", m.id).put("role", m.role)
            .put("text", m.text).put("channel", m.channel).put("model", m.model)) }
        prefs.edit().putString("history", data.toString()).apply()
    }

    fun updateDraft(text: String) {
        if (draft == text) return
        draft = text.take(12000); emit()
    }

    fun reviewDraft(text: String) {
        draft = text.take(12000); status = "راجع الكلام واضغط إرسال لما تكون جاهز"; emit()
    }

    fun recordScreen(role: String, text: String) { add(role, text, "screen"); emit() }

    fun speak(text: String, channel: String = "chat") { speechChannel = channel; speech.speak(text) }
    fun stopSpeech(channel: String? = null) { if (channel == null || channel == speechChannel) speech.stop() }

    /** Cancels the request identity before cancelling transport: late callbacks are inert. */
    fun cancel() {
        epoch.incrementAndGet()
        activeSession?.cancel(); activeSession = null
        speech.stop()
        if (busy) add("system", "تم إيقاف الرد.")
        busy = false; status = "متوقف — تقدر تبعت رسالة جديدة"; emit()
    }

    fun clear() {
        cancel()
        clearListeners.toList().forEach { it() }
        messages.clear(); draft = ""; modelName = ""
        prefs.edit().remove("history").apply()
        status = "محادثة جديدة"; emit()
    }

    fun send(raw: String): Boolean {
        val text = raw.trim()
        if (text.isBlank() || busy) return false
        if (text.length > 12000) { status = "الرسالة طويلة؛ ابعتها على أجزاء أصغر."; emit(); return false }
        val settings = Settings(app)
        val profiles = settings.modelsInOrder().let { if (settings.fallbackEnabled) it else it.take(1) }
        if (profiles.isEmpty()) { status = "أضف نموذج AI من الإعدادات عشان نبدأ الدردشة."; emit(); return false }
        speech.stop()
        val id = epoch.incrementAndGet()
        add("user", text)
        draft = ""; busy = true; modelName = profiles.first().name; status = "بفكر في ردك…"
        // Only completed chat turns are context. Screen observations and errors are excluded.
        val history = messages.filter { it.channel == "chat" && it.role in setOf("user", "assistant") }.takeLast(10)
        val context = JSONArray()
        var budget = 12000
        val bounded = history.asReversed().mapNotNull { m ->
            if (budget <= 0) null else m.copy(text = m.text.takeLast(budget)).also { budget -= it.text.length }
        }.asReversed()
        bounded.forEach { context.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val prompt = "سجل المحادثة التالية بالترتيب. أجب على آخر رسالة user فقط:\n" + context
        emit()
        worker.execute {
            var failure: Throwable? = null
            for (profile in profiles) {
                if (id != epoch.get()) return@execute
                var session: ChatSession? = null
                try {
                    session = Llm.newSession(profile.toConfig(), SYSTEM, emptyList())
                    activeSession = session
                    if (id != epoch.get()) { session.cancel(); return@execute }
                    main.post { if (id == epoch.get()) { modelName = profile.name; emit() } }
                    val turn = session.send(prompt, emptyList())
                    check(turn.toolCalls.isEmpty()) { "Unexpected tool call" }
                    check(turn.text.isNotBlank()) { "Empty reply" }
                    main.post {
                        if (id != epoch.get()) return@post
                        busy = false; activeSession = null; modelName = profile.name
                        status = "تم الرد"
                        add("assistant", turn.text, model = profile.name)
                        emit()
                        if (autoSpeak) speak(turn.text)
                    }
                    return@execute
                } catch (e: Exception) { failure = e }
                finally { session?.cancel() }
            }
            main.post {
                if (id != epoch.get()) return@post
                busy = false; activeSession = null
                status = connectionError(failure)
                add("error", status); emit()
            }
        }
        return true
    }

    companion object {
        private const val SYSTEM = "أنت مساعدك، مساعد عربي ودود ودقيق. أجب بالعربية ما لم يطلب المستخدم لغة أخرى. " +
            "هذه دردشة نصية فقط: لا تقرأ الشاشة ولا تنفذ أي إجراء ولا تدع أنك ضغطت أو فتحت تطبيقًا. " +
            "لو طلب المستخدم التحكم في الهاتف، وضح له استخدام وضع «الشاشة» حيث يوافق على كل خطوة."
        @Volatile private var instance: ChatConversation? = null

        @Synchronized fun get(context: Context): ChatConversation {
            val app = context.applicationContext
            val current = instance
            if (current != null && current.app === app) return current
            current?.cancel(); current?.worker?.shutdownNow(); current?.speech?.close()
            return ChatConversation(app).also { instance = it }
        }

        fun connectionError(error: Throwable?): String {
            val message = error?.message.orEmpty()
            return when {
                "401" in message || "403" in message -> "المفتاح مرفوض أو صلاحياته ناقصة. راجع بيانات المزود."
                "404" in message -> "النموذج أو رابط API غير متاح. راجع الاسم والرابط."
                "429" in message -> "وصلت لحد الاستخدام أو الرصيد خلص. راجع حساب المزود."
                Regex("HTTP 5[0-9]{2}").containsMatchIn(message) -> "خادم المزود مش متاح دلوقتي. جرّب بعد شوية."
                error is java.net.SocketTimeoutException -> "الاتصال أخد وقت أطول من المتوقع. جرّب تاني."
                message == "Empty reply" -> "النموذج رجّع رد فاضي. جرّب رسالة تانية أو نموذج تاني."
                message == "Unexpected tool call" -> "النموذج حاول يستدعي أداة؛ الدردشة العادية لا تنفذ أدوات."
                else -> "تعذّر الاتصال بالنموذج. راجع الإنترنت والرابط وإعدادات AI."
            }
        }
    }
}
