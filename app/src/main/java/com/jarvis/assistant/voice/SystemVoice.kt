package com.jarvis.assistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Android's built-in text-to-speech. Completion callbacks run on the main thread. */
class SystemVoice(context: Context) : VoiceEngine {
    private val main = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, () -> Unit>()
    private var initDone = false
    private var ready = false
    private val queued = mutableListOf<Pair<String, () -> Unit>>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post {
            initDone = true
            ready = status == TextToSpeech.SUCCESS
            queued.forEach { (t, cb) -> if (ready) speak(t, cb) else cb() }
            queued.clear()
        }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finish(utteranceId)
        })
    }

    private fun finish(id: String?) {
        val cb = id?.let { pending.remove(it) } ?: return
        main.post(cb)
    }

    override fun speak(text: String, onDone: () -> Unit) {
        main.post {
            if (!initDone) { queued += text to onDone; return@post }
            if (!ready) { onDone(); return@post }
            val id = UUID.randomUUID().toString()
            pending[id] = onDone
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    override fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
