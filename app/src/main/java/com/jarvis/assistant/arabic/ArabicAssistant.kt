package com.jarvis.assistant.arabic

import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.Settings
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.ScreenReader
import com.jarvis.assistant.agent.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Only this native controller can grant one action. The LLM never receives a permission token. */
class ArabicAssistant(private val service: JarvisAccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val epoch = AtomicLong()
    private val overlay = AssistantOverlay(service)
    private var voice: android.speech.tts.TextToSpeech? = null
    @Volatile private var activeSession: ChatSession? = null
    @Volatile private var pending: Pending? = null
    @Volatile private var question: Question? = null
    private var closed = false
    private var enabled = false
    private var busy = false
    private var lastReply = "أهلًا! تحب أساعدك في إيه؟"
    private var selection = ""
    private var hint: Rect? = null
    private var hintProof = ""

    private class Pending(val id: Long, val ticket: ConsentTicket, val proof: String, val revision: Long,
        val action: () -> String, val result: CountDownLatch = CountDownLatch(1)) {
        @Volatile var value: String? = null
        @Volatile var error: String? = null
        var manual = false
    }
    private class Question(val id: Long, val latch: CountDownLatch = CountDownLatch(1)) {
        @Volatile var answer: String? = null
    }

    init {
        overlay.onSend = { text -> submit(text) }
        overlay.onStop = { stop() }
        overlay.onTalk = {
            stop()
            service.startActivity(Intent(service, ArabicVoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        overlay.onSelect = { selectRegion() }
        overlay.onSpeak = { speak(lastReply) }
        overlay.onExecute = { executeOnce() }
        overlay.onGuide = { guideOnly() }
        overlay.onClose = { stop(); overlay.collapse() }
    }

    fun enable() {
        if (closed || !service.getSharedPreferences("arabic_consent", 0).getBoolean("screen_text", false)) return
        enabled = true
        overlay.showBubble()
    }
    fun disable() { enabled = false; stop(); overlay.hide() }
    fun reviewVoice(text: String) {
        enable(); overlay.open(); overlay.setInput(text)
        overlay.status("راجع الكلام، وبعدها اضغط إرسال")
    }
    fun stop() {
        epoch.incrementAndGet()
        activeSession?.cancel(); activeSession = null
        pending?.let { it.ticket.revoke(); it.error = "تم الإيقاف"; it.result.countDown() }; pending = null
        question?.latch?.countDown(); question = null
        voice?.stop()
        ArabicVoiceActivity.current?.cancel()
        busy = false; hint = null; selection = ""
        overlay.clearHighlights(); overlay.clearProposal(); overlay.setBusy(false)
        overlay.status("متوقف — اضغط إرسال لطلب جديد")
    }
    fun close() { closed = true; disable(); voice?.shutdown(); worker.shutdownNow() }

    fun screenChanged() {
        if (!enabled) return
        val p = pending
        if (p != null) {
            p.ticket.revoke()
            p.value = if (p.manual) "المستخدم ضغط بنفسه. راجع الشاشة الجديدة قبل اقتراح أي خطوة." else null
            p.error = if (p.manual) null else "الشاشة اتغيّرت. لازم مراجعة جديدة وموافقة جديدة."
            pending = null; overlay.clearProposal(); p.result.countDown()
        }
        if (hint != null) { hint = null; overlay.clearHighlights() }
    }

    private fun submit(text: String) {
        if (text.isBlank()) return
        if (!service.getSharedPreferences("arabic_consent", 0).getBoolean("screen_text", false)) {
            overlay.status("راجع إذن مشاركة نص الشاشة من إعدادات التطبيق الأول"); return
        }
        question?.let { q -> q.answer = text; q.latch.countDown(); question = null; overlay.setInput(""); return }
        if (busy) { overlay.status("في طلب شغال؛ اضغط إيقاف قبل طلب جديد"); return }
        if (Settings(service).modelsInOrder().isEmpty()) {
            overlay.status("أضف نموذج AI من إعدادات التطبيق الأول"); return
        }
        val id = epoch.incrementAndGet(); busy = true; overlay.setBusy(true); overlay.clearHighlights()
        overlay.setInput(""); overlay.status("بفهم طلبك…")
        val task = text + if (selection.isNotBlank()) "\nالمستخدم حدد هذا الجزء للسؤال عنه: $selection" else ""
        selection = ""
        worker.execute {
            val host = object : AgentHost {
                override val canReadScreen get() = service.getSharedPreferences("arabic_consent", 0).getBoolean("screen_text", false)
                override val isCancelled get() = id != epoch.get() || closed
                override fun session(session: ChatSession) {
                    activeSession = session
                    if (isCancelled) session.cancel()
                }
                override fun status(text: String) { main.post { if (!isCancelled) overlay.status("براجع الخطوة المناسبة…") } }
                override fun ask(question: String): String? {
                    val q = Question(id)
                    main.post { if (!isCancelled) { this@ArabicAssistant.question = q; overlay.ask(question) } else q.latch.countDown() }
                    while (!q.latch.await(100, TimeUnit.MILLISECONDS)) {
                        if (isCancelled) return null
                    }
                    return if (isCancelled) null else q.answer
                }
                override fun performApproved(call: ToolCall, action: () -> String): String = awaitApproval(id, call, action)
            }
            val result = runCatching { PhoneAgent(Settings(service), host).run(task) }
            main.post {
                if (id != epoch.get() || closed) return@post
                busy = false; activeSession = null; overlay.setBusy(false); overlay.clearProposal()
                lastReply = result.getOrNull()?.summary ?: "حصل خطأ في الاتصال. راجع إعدادات النموذج وحاول تاني."
                overlay.open(); overlay.status(lastReply)
            }
        }
    }

    private fun awaitApproval(id: Long, call: ToolCall, action: () -> String): String {
        val observed = service.lastSnapshot() ?: error("مفيش شاشة مقروءة")
        val proof = service.observedProof
        val revision = service.observedRevision
        val elementId = if (call.arguments.has("element_id")) call.arguments.getInt("element_id") else null
        val node = elementId?.let { observed.elements[it] ?: error("العنصر مش موجود؛ حدد مكانه") }
        require(node == null || (!node.isPassword && node.isEnabled && node.isVisibleToUser)) { "العنصر محمي أو غير متاح" }
        val bounds = node?.let { Rect().also(it::getBoundsInScreen) }
        val label = node?.let { it.text?.toString()?.takeIf(String::isNotBlank) ?: it.contentDescription?.toString() }
        if (call.name in setOf("tap", "long_press", "type_text")) require(!label.isNullOrBlank()) { "مش قادر أحدد الزر بثقة. حدده بنفسك." }
        require(!service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "افتح قفل الهاتف الأول" }
        val description = describe(call, label)
        val p = Pending(id, ConsentTicket(proof, revision, SystemClock.elapsedRealtime() + 60_000), proof, revision, action)
        main.post {
            if (id != epoch.get() || closed) { p.error = "تم الإيقاف"; p.result.countDown(); return@post }
            val fresh = ScreenReader.capture(service)
            if (revision != service.screenRevision || proof != ScreenProof.of(fresh)) {
                p.error = "الشاشة اتغيّرت أثناء التفكير؛ راجعها واقترح خطوة جديدة"; p.result.countDown(); return@post
            }
            pending = p
            overlay.propose(description, bounds)
        }
        val end = SystemClock.elapsedRealtime() + 60_000
        while (!p.result.await(100, TimeUnit.MILLISECONDS)) {
            if (id != epoch.get() || closed || SystemClock.elapsedRealtime() >= end) {
                p.ticket.revoke()
                main.post { if (pending === p) { pending = null; overlay.clearProposal(); overlay.clearHighlights() } }
                error("انتهت مهلة الموافقة أو تم الإيقاف")
            }
        }
        p.error?.let { error(it) }
        return p.value ?: error("لم تتم الموافقة")
    }

    private fun executeOnce() {
        val p = pending ?: return
        pending = null // Consume first; double taps cannot execute twice.
        overlay.clearProposal(); overlay.clearHighlights()
        if (p.id != epoch.get() || p.manual || service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            p.ticket.revoke(); p.error = "الموافقة لم تعد صالحة"
        } else runCatching {
            val fresh = ScreenReader.capture(service)
            check(p.ticket.consume(ScreenProof.of(fresh), service.screenRevision, SystemClock.elapsedRealtime())) {
                "الهدف أو الشاشة اتغيّر. مطلوب موافقة جديدة."
            }
            // Fresh check and the one action run in the SAME main-thread callback, without a posted gap.
            p.value = p.action()
        }.onFailure { p.error = it.message ?: "تعذر التنفيذ" }
        p.result.countDown()
    }

    private fun guideOnly() {
        val p = pending ?: return
        p.ticket.revoke(); p.manual = true
        overlay.guide("اضغط بنفسك على المكان المحدد. لو الإشارة اختفت، افتح الفقاعة للمراجعة.")
        // Keep waiting for a real foreign screen event, or the original timeout/Stop.
    }

    private fun selectRegion() {
        if (busy) { overlay.status("أوقف الطلب الحالي الأول عشان تحدد مكان جديد"); return }
        overlay.collapse()
        val id = epoch.get()
        main.postDelayed({
        if (id != epoch.get() || closed || !enabled) return@postDelayed
        val screen = service.readScreen(); hintProof = service.observedProof
        overlay.select { region ->
            val fresh = ScreenReader.capture(service)
            if (hintProof != ScreenProof.of(fresh)) { overlay.status("الشاشة اتغيرت، حدد المكان تاني"); return@select }
            val selected = screen.elements.values.filter { n ->
                !n.isPassword && Rect.intersects(region, Rect().also(n::getBoundsInScreen))
            }.mapNotNull { it.text?.toString()?.takeIf(String::isNotBlank) ?: it.contentDescription?.toString() }.distinct()
            selection = selected.joinToString("، ").take(2500)
            overlay.open()
            overlay.status(if (selection.isBlank()) "المكان ده مش بيعرض نص قابل للقراءة. تقدر تفعّل الصور من الإعدادات؛ مش هخمن زر." else "تم تحديد: ${selection.take(180)}. اكتب سؤالك عنه.")
        }
        }, 250)
    }

    private fun describe(call: ToolCall, label: String?): String = when (call.name) {
        "tap" -> "اضغط على «$label»"
        "long_press" -> "ضغطة مطولة على «$label»"
        "type_text" -> "اكتب في «$label»:\n${call.arguments.getString("text")}"
        "scroll" -> "مرّر ${when(call.arguments.optString("direction")) { "up" -> "لأعلى"; "left" -> "لليسار"; "right" -> "لليمين"; else -> "لأسفل" }} في ${label ?: "القائمة الظاهرة"}"
        "open_app" -> "افتح تطبيق «${call.arguments.getString("name")}»"
        "press_key" -> when (call.arguments.getString("key")) { "back" -> "ارجع خطوة واحدة"; "home" -> "افتح الشاشة الرئيسية"; "recents" -> "افتح التطبيقات الأخيرة"; "notifications" -> "افتح الإشعارات"; "quick_settings" -> "افتح الإعدادات السريعة"; else -> error("فعل غير معروف") }
        "look_at_screen" -> "التقط صورة الشاشة الحالية وأرسلها لنموذج AI المحدد لفهمها"
        else -> error("الفعل ده غير مسموح")
    }

    private fun speak(text: String) {
        if (voice == null) {
            voice = android.speech.tts.TextToSpeech(service) { status ->
                main.post { if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    voice?.language = java.util.Locale.forLanguageTag("ar-EG")
                    overlay.status("الصوت جاهز — اضغط اسمع الرد")
                } else overlay.status("الصوت غير متاح؛ ثبّت صوت عربي من إعدادات الهاتف") }
            }
            return
        }
        val lang = voice?.setLanguage(java.util.Locale.forLanguageTag("ar-EG")) ?: -1
        if (lang < 0) { overlay.status("نزّل صوت عربي من إعدادات تحويل النص إلى كلام"); return }
        voice?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "arabic-reply")
    }
}
