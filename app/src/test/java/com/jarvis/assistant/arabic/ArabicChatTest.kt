package com.jarvis.assistant.arabic

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.jarvis.assistant.ModelProfile
import com.jarvis.assistant.agent.Llm
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArabicChatTest {
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    @Test fun normalChatDoesNotAdvertisePhoneControlTools() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"أهلًا بيك"},"finish_reason":"stop"}]}"""))
            val profile = ModelProfile(name = "chat", providerId = "custom",
                baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = "", model = "test")
            val turn = Llm.newSession(profile.toConfig(), "Reply in Arabic.", emptyList()).send("أهلًا", emptyList())
            assertEquals("أهلًا بيك", turn.text)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse("Ordinary chat must work with models without tool support", body.has("tools"))
            assertFalse("No tool choice in text chat", body.has("tool_choice"))
        }
    }

    @Test fun homeStartsWithChatWithoutAccessibilityOrMicrophonePermission() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("arabic_consent", 0).edit().clear().commit()
        val activity = Robolectric.buildActivity(ArabicHomeActivity::class.java).setup().get()
        try {
            val views = descendants(activity.window.decorView)
            assertTrue("Home must have a message composer", views.filterIsInstance<EditText>()
                .any { it.hint?.toString() == "اكتب رسالتك…" })
            assertTrue("Home needs send without granting screen control", views.filterIsInstance<TextView>()
                .any { it.text.toString() == "إرسال" })
        } finally { activity.finish() }
    }
}
