package com.jarvis.assistant.arabic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Queues the first request until Android finishes binding its speech engine. */
class ArabicSpeech(context: Context, private val notice: (String) -> Unit = {}) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var generation = 0L
    private var closed = false

    fun speak(text: String) {
        if (closed || text.isBlank()) return
        pending = text
        if (ready) { flush(); return }
        if (engine != null) return
        engine = TextToSpeech(app) { status ->
            main.post {
                if (closed) return@post
                val language = if (status == TextToSpeech.SUCCESS)
                    engine?.setLanguage(Locale.forLanguageTag("ar-EG")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                else TextToSpeech.LANG_NOT_SUPPORTED
                ready = language >= TextToSpeech.LANG_AVAILABLE
                if (ready) flush() else {
                    pending = null
                    engine?.shutdown(); engine = null
                    notice("الصوت العربي غير متاح. نزّل صوت عربي من إعدادات الهاتف؛ تقدر تقرأ الرد هنا.")
                }
            }
        }
    }

    private fun flush() {
        val text = pending ?: return
        pending = null
        val limit = TextToSpeech.getMaxSpeechInputLength()
        val id = ++generation
        var start = 0
        while (start < text.length && id == generation && !closed) {
            var end = minOf(text.length, start + limit)
            if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
            val result = engine?.speak(text.substring(start, end),
                if (start == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, "arabic-" + id + "-" + start)
            if (result == TextToSpeech.ERROR) {
                engine?.stop(); notice("تعذّر تشغيل الصوت؛ الرد المكتوب موجود."); break
            }
            start = end
        }
    }

    fun stop() { generation++; pending = null; engine?.stop() }
    fun close() { closed = true; stop(); engine?.shutdown(); engine = null }
}
