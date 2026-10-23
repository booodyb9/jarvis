package com.jarvis.assistant.arabic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.accessibility.JarvisAccessibilityService

/** Recognition runs only while a visible activity owns the microphone. Never sends a command. */
class ArabicVoiceActivity : AppCompatActivity(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private lateinit var transcript: TextView
    private var heard = ""
    private var cancelled = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); current = this
        val column = GlassUi.column(this)
        column.addView(GlassUi.text(this, "أنا سامعك", 28f))
        column.addView(GlassUi.text(this, "اتكلم بالمصري. كلامك هيتعرض للمراجعة قبل الإرسال."))
        transcript = GlassUi.text(this, "جاري تجهيز الميكروفون…", 22f); column.addView(transcript)
        column.addView(GlassUi.button(this, "خلصت كلام — راجع النص", true) { recognizer?.stopListening() })
        column.addView(GlassUi.button(this, "إعادة المحاولة") { startRecognition() })
        column.addView(GlassUi.button(this, "إيقاف", stop = true) { cancel() })
        setContentView(column)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecognition()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 81)
    }
    private fun startRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            transcript.text = "إذن الميكروفون غير مفعّل. تقدر تكتب طلبك من الفقاعة."; return
        }
        recognizer?.destroy(); heard = ""; cancelled = false
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { transcript.text = "خدمة التعرف على الصوت مش متاحة. تقدر تستخدم الكتابة."; return }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            runCatching { it.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)) }
                .onFailure { transcript.text = "تعذّر بدء الصوت. جرّب الكتابة." }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 81 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecognition()
        else transcript.text = "الإذن لم يُمنح. تقدر تكتب من الفقاعة."
    }
    fun cancel() { cancelled = true; recognizer?.cancel(); heard = ""; finish() }
    override fun onReadyForSpeech(params: Bundle?) { transcript.text = "اتفضل، قول طلبك…" }
    override fun onPartialResults(partialResults: Bundle?) {
        heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        transcript.text = heard
    }
    override fun onResults(results: Bundle?) {
        if (cancelled || isFinishing || isDestroyed) return
        heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        val text = heard.trim()
        if (text.isBlank()) { transcript.text = "مسمعتش كلام واضح. جرّب تاني."; return }
        finish()
        JarvisAccessibilityService.instance?.arabic?.reviewVoice(text)
    }
    override fun onError(error: Int) { transcript.text = "التسجيل وقف. جرّب تاني أو استخدم الكتابة." }
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
    override fun onStop() { cancelled = true; recognizer?.cancel(); super.onStop() }
    override fun onDestroy() { recognizer?.destroy(); if (current === this) current = null; super.onDestroy() }
    companion object { var current: ArabicVoiceActivity? = null; private set }
}
