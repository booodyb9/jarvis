package com.jarvis.assistant.arabic

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.os.Looper
import android.view.WindowManager
import com.jarvis.assistant.Settings
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.util.concurrent.TimeUnit
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

    private fun reply(text: String) = MockResponse().setBody(
        JSONObject().put("choices", org.json.JSONArray().put(JSONObject()
            .put("message", JSONObject().put("role", "assistant").put("content", text))
            .put("finish_reason", "stop"))).toString())

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("Timed out waiting for the observable result", condition())
    }

    private fun configure(context: Context, server: MockWebServer): Settings {
        val settings = Settings(context)
        val profile = ModelProfile(name = "نموذج التجربة", providerId = "custom",
            baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = "", model = "chat-test")
        settings.saveProfile(profile); settings.primaryId = profile.id; settings.fallbackEnabled = false
        return settings
    }

    @Test fun typedHomeChatAndBubbleShareRepliesAndDraftWithoutScreenConsent() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(reply("أهلًا يا سامي"))
            val context = ApplicationProvider.getApplicationContext<Context>()
            configure(context, server)
            val chat = ChatConversation.get(context); chat.clear()
            val controller = Robolectric.buildActivity(ArabicHomeActivity::class.java).setup()
            val activity = controller.get()
            val views = descendants(activity.window.decorView)
            val input = views.filterIsInstance<EditText>().first { it.hint?.toString() == "اكتب رسالتك…" }
            input.setText("أنا اسمي سامي")
            views.filterIsInstance<TextView>().first { it.text.toString() == "إرسال" }.performClick()
            await { chat.snapshot().messages.any { it.role == "assistant" && it.text == "أهلًا يا سامي" } }
            assertTrue(descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "أهلًا يا سامي" })
            assertFalse(context.getSharedPreferences("arabic_consent", 0).getBoolean("screen_text", false))
            val first = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(first.has("tools"))

            val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
            val overlay = AssistantOverlay(service)
            overlay.open()
            fun roots() = (shadowOf(service.getSystemService(WindowManager::class.java)) as ShadowWindowManagerImpl).views.toList()
            assertTrue(roots().flatMap { descendants(it) }.filterIsInstance<TextView>().any { it.text.toString() == "أهلًا يا سامي" })
            val bubbleInput = roots().flatMap { descendants(it) }.filterIsInstance<EditText>().first { it.hint?.toString() == "اكتب رسالتك…" }
            bubbleInput.setText("اسمي إيه؟")
            overlay.collapse(); overlay.open()
            assertTrue(roots().flatMap { descendants(it) }.filterIsInstance<EditText>().any { it.text.toString() == "اسمي إيه؟" })
            overlay.hide()
            controller.pause().stop().destroy()
        }
    }

    @Test fun followupIncludesCompletedChatButNeverRawScreenContext() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(reply("أهلًا يا سامي")); server.enqueue(reply("اسمك سامي"))
            val context = ApplicationProvider.getApplicationContext<Context>()
            configure(context, server)
            val chat = ChatConversation.get(context); chat.clear()
            chat.recordScreen("assistant", "SCREEN_PRIVATE_DATA")
            assertTrue(chat.send("أنا اسمي سامي"))
            await { !chat.snapshot().busy }
            assertTrue(chat.send("اسمي إيه؟"))
            await { !chat.snapshot().busy }
            server.takeRequest()
            val body = JSONObject(server.takeRequest().body.readUtf8())
            val text = body.getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(text.contains("أنا اسمي سامي"))
            assertTrue(text.contains("أهلًا يا سامي"))
            assertTrue(text.contains("اسمي إيه؟"))
            assertFalse(text.contains("SCREEN_PRIVATE_DATA"))
        }
    }

    @Test fun cancellingThenSendingAgainCannotPublishAnOlderReply() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(reply("رد متأخر").setBodyDelay(1, TimeUnit.SECONDS))
            server.enqueue(reply("الرد الجديد"))
            val context = ApplicationProvider.getApplicationContext<Context>()
            configure(context, server)
            val chat = ChatConversation.get(context); chat.clear()
            chat.send("الطلب الأول")
            await { server.requestCount == 1 }
            chat.cancel()
            assertTrue(chat.send("الطلب الجديد"))
            await { !chat.snapshot().busy && chat.snapshot().messages.any { it.text == "الرد الجديد" } }
            assertFalse(chat.snapshot().messages.any { it.text == "رد متأخر" })
        }
    }

    @Test fun disabledFallbackDoesNotContactAnotherSavedProvider() {
        MockWebServer().use { failing ->
            MockWebServer().use { backup ->
                failing.start(); backup.start()
                failing.enqueue(MockResponse().setResponseCode(401).setBody("SECRET_PROVIDER_DETAIL"))
                backup.enqueue(reply("احتياطي"))
                val context = ApplicationProvider.getApplicationContext<Context>()
                val settings = configure(context, failing)
                settings.saveProfile(ModelProfile(name = "backup", providerId = "custom",
                    baseUrl = backup.url("/v1").toString().trimEnd('/'), apiKey = "", model = "backup"))
                val chat = ChatConversation.get(context); chat.clear()
                chat.send("أهلًا")
                await { !chat.snapshot().busy }
                assertEquals(1, failing.requestCount)
                assertEquals(0, backup.requestCount)
                assertTrue(chat.snapshot().status.contains("المفتاح مرفوض"))
                assertFalse(chat.snapshot().messages.any { it.text.contains("SECRET_PROVIDER_DETAIL") })
            }
        }
    }

    @Test fun localHistoryIsBoundedAndCanBeLoadedWithoutAScreenService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val chat = ChatConversation.get(context); chat.clear()
        for (i in 1..105) chat.recordScreen("user", "رسالة " + i)
        assertEquals(100, chat.snapshot().messages.size)
        assertEquals("رسالة 6", chat.snapshot().messages.first().text)
        val constructor = ChatConversation::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val reloaded = constructor.newInstance(context)
        assertEquals(100, reloaded.snapshot().messages.size)
        chat.clear()
        assertFalse(context.getSharedPreferences("arabic_chat", 0).contains("history"))
        assertTrue(chat.snapshot().messages.isEmpty())
    }
}
