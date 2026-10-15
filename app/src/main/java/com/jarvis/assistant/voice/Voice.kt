package com.jarvis.assistant.voice

import android.content.Context
import com.jarvis.assistant.Settings

interface VoiceEngine {
    /** Speak [text], replacing anything currently being said. [onDone] runs on the main thread. */
    fun speak(text: String, onDone: () -> Unit)
    fun shutdown()
}

/**
 * The voice Jarvis talks with: Kokoro when it's selected and downloaded, otherwise (or if Kokoro
 * can't load on this phone) Android's built-in voice. Settings are read on every call.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private val settings = Settings(app)
    private val system by lazy { SystemVoice(app) }
    private var usedSystem = false

    val usingKokoro get() = settings.voiceEngine == "kokoro" && Kokoro.isInstalled(app) && !Kokoro.failed

    fun speak(text: String, onDone: () -> Unit = {}) {
        if (usingKokoro) {
            Kokoro.speak(app, text, settings.kokoroSpeaker, settings.voiceSpeed, onDone)
        } else {
            usedSystem = true
            system.speak(text, onDone)
        }
    }

    /** Load the voice now so the next reply starts without delay. */
    fun warmUp() { if (usingKokoro) Kokoro.warmUp(app) }

    fun stop() = Kokoro.stop()

    fun shutdown() {
        Kokoro.stop()
        if (usedSystem) system.shutdown()
    }
}
