package com.jarvis.assistant.arabic

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArabicVoiceChatTest {
    private fun requestSpeech(assistant: ArabicAssistant, text: String) {
        ArabicAssistant::class.java.getDeclaredMethod("speak", String::class.java)
            .apply { isAccessible = true }.invoke(assistant, text)
    }

    @Test fun firstSpeakRequestIsReadAfterEngineInitializationWithoutSecondTap() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("ar-EG"))
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        requestSpeech(service.arabic, "أهلًا بيك")
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotNull(tts)
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("أهلًا بيك", shadowOf(tts).lastSpokenText)
        service.arabic.close()
    }

    @Test fun stoppingDuringInitializationCannotSpeakTheQueuedReplyLater() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("ar-EG"))
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        requestSpeech(service.arabic, "ما تقراش ده")
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        service.arabic.stop()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(shadowOf(tts).lastSpokenText)
        service.arabic.close()
    }

    @Test fun unavailableArabicVoiceKeepsTheWrittenReplyAndExplainsTheProblem() {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        val chat = ChatConversation.get(service); chat.clear()
        chat.recordScreen("assistant", "رد مكتوب")
        requestSpeech(service.arabic, "رد مكتوب")
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(chat.snapshot().messages.any { it.text == "رد مكتوب" })
        assertTrue(chat.snapshot().status.contains("الصوت العربي غير متاح"))
        assertNull(shadowOf(tts).lastSpokenText)
        service.arabic.close()
    }

    @Test fun homeVoiceResultReturnsToEditableChatDraftWithoutAccessibility() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val chat = ChatConversation.get(context); chat.clear()
        val intent = Intent(context, ArabicVoiceActivity::class.java)
            .putExtra(ArabicVoiceActivity.EXTRA_DESTINATION, ArabicVoiceActivity.DEST_HOME)
        val controller = Robolectric.buildActivity(ArabicVoiceActivity::class.java, intent).setup()
        val result = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf("عاوز أفهم الفكرة"))
        }
        controller.get().onResults(result)
        assertEquals("عاوز أفهم الفكرة", chat.snapshot().draft)
        assertTrue("Voice recognition must not send a message before review", chat.snapshot().messages.isEmpty())
        controller.pause().stop().destroy()
    }

    @Test fun longRepliesAreSplitIntoLegalSpeechRequestsWithoutLosingText() {
        ShadowTextToSpeech.addLanguageAvailability(Locale.forLanguageTag("ar-EG"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val speech = ArabicSpeech(context)
        val text = "كلام عربي طويل. ".repeat(800)
        speech.speak(text)
        val tts = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(tts).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        val parts = shadowOf(tts).spokenTextList
        assertTrue("Android rejects synthesis input longer than its limit", parts.all { it.length <= TextToSpeech.getMaxSpeechInputLength() })
        assertEquals(text, parts.joinToString(""))
        speech.close()
    }
}
