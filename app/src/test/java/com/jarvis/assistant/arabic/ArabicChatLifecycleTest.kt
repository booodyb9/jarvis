package com.jarvis.assistant.arabic

import android.content.Context
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.ModelProfile
import com.jarvis.assistant.Settings
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.agent.ToolCall
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArabicChatLifecycleTest {
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun await(test: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!test() && System.nanoTime() < end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("Timed out", test())
    }
    private fun configure(context: Context, server: MockWebServer) {
        val settings = Settings(context)
        val p = ModelProfile(name = "test", providerId = "custom", baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = "", model = "test")
        settings.saveProfile(p); settings.primaryId = p.id; settings.fallbackEnabled = false
    }
    private fun panel(service: JarvisAccessibilityService) =
        (shadowOf(service.getSystemService(WindowManager::class.java)) as ShadowWindowManagerImpl).views.last()
    private fun layout(root: View, w: Int, h: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
    }
    private fun inside(root: View, child: View) {
        val rect = Rect(0, 0, child.width, child.height)
        (root as ViewGroup).offsetDescendantRectToMyCoords(child, rect)
        assertTrue("Control must have a usable touch height", child.height >= GlassUi.dp(root.context, 48))
        assertTrue("Control extends above viewport: " + rect, rect.top >= 0)
        assertTrue("Control extends below viewport: " + rect + " viewport=" + root.height, rect.bottom <= root.height)
    }

    @Test fun serviceRotationDoesNotCancelAnOrdinaryChatRequest() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"رد بعد الدوران"},"finish_reason":"stop"}]}""").setBodyDelay(300, TimeUnit.MILLISECONDS))
            val context = ApplicationProvider.getApplicationContext<Context>()
            configure(context, server)
            val chat = ChatConversation.get(context); chat.clear()
            val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
            chat.send("أهلًا")
            await { server.requestCount == 1 }
            service.arabic.configurationChanged()
            assertTrue("Screen-service recreation must not cancel text chat", chat.snapshot().busy)
            await { chat.snapshot().messages.any { it.text == "رد بعد الدوران" } }
            assertEquals(1, server.requestCount)
            assertFalse(chat.snapshot().messages.any { it.text == "تم إيقاف الرد." })
            service.arabic.close()
        }
    }

    @Test fun clearingConversationRevokesAnOutstandingNativeApproval() {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        val assistant = service.arabic
        val chat = ChatConversation.get(service); chat.clear()
        service.readScreen()
        val method = ArabicAssistant::class.java.getDeclaredMethod("awaitApproval", java.lang.Long.TYPE, ToolCall::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
        val hits = AtomicInteger()
        val currentEpoch = (ArabicAssistant::class.java.getDeclaredField("epoch").apply { isAccessible = true }.get(assistant) as java.util.concurrent.atomic.AtomicLong).get()
        val thread = Thread { runCatching { method.invoke(assistant, currentEpoch, ToolCall("1", "press_key", JSONObject().put("key", "back")), { hits.incrementAndGet(); "done" }) } }
        thread.start()
        await { (shadowOf(service.getSystemService(WindowManager::class.java)) as ShadowWindowManagerImpl).views.any { root -> views(root).filterIsInstance<TextView>().any { it.text == "نفّذ مرة" } } }
        val button = views(panel(service)).filterIsInstance<TextView>().first { it.text == "نفّذ مرة" }
        chat.clear()
        button.performClick()
        thread.join(2000)
        assertEquals("A new conversation revokes the previous conversation's actions", 0, hits.get())
        assertFalse(thread.isAlive)
        assistant.close()
    }

    @Test fun clearingConversationCannotBeRepopulatedByAnOlderScreenResult() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"","tool_calls":[{"id":"f","type":"function","function":{"name":"finish","arguments":"{\"success\":true,\"summary\":\"نتيجة شاشة قديمة\"}"}}]},"finish_reason":"tool_calls"}]}""").setBodyDelay(300, TimeUnit.MILLISECONDS))
            val context = ApplicationProvider.getApplicationContext<Context>()
            configure(context, server)
            context.getSharedPreferences("arabic_consent", 0).edit().putBoolean("screen_text", true).commit()
            val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
            JarvisAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
            val assistant = service.arabic
            val chat = ChatConversation.get(context); chat.clear()
            ArabicAssistant::class.java.getDeclaredMethod("submit", String::class.java).apply { isAccessible = true }.invoke(assistant, "اشرح الشاشة")
            await { server.requestCount == 1 }
            chat.clear()
            Thread.sleep(800)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Old screen completions must not survive New conversation", chat.snapshot().messages.isEmpty())
            service.onDestroy()
        }
    }

    @Test @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun bubbleChatComposerFitsLandscapeAndKeyboardHeight() {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        val overlay = AssistantOverlay(service); overlay.openChat()
        val root = panel(service)
        layout(root, 400, 240)
        val chat = views(root).filterIsInstance<ConversationView>().single()
        chat.input.setText("سطر أول\nسطر ثاني\nسطر ثالث\nسطر رابع")
        layout(root, 400, 240)
        val send = views(chat).filterIsInstance<TextView>().first { it.text == "إرسال" }
        inside(root, send)
        overlay.hide()
    }

    @Test @Config(qualifiers = "w800dp-h360dp-mdpi")
    fun homeComposerFitsALandscapeWindowWithKeyboard() {
        val controller = Robolectric.buildActivity(ArabicHomeActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        layout(root, 640, 240)
        val chat = views(root).filterIsInstance<ConversationView>().single()
        chat.input.setText("سطر أول\nسطر ثاني\nسطر ثالث\nسطر رابع")
        layout(root, 640, 240)
        val send = views(chat).filterIsInstance<TextView>().first { it.text == "إرسال" }
        inside(root, send)
        controller.pause().stop().destroy()
    }

    @Test fun longApprovalKeepsBothActionsReachableWithoutDroppingTheDescription() {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        val overlay = AssistantOverlay(service)
        val description = "اكتب في الرسالة:\n" + "هذا نص عربي طويل للمراجعة قبل التنفيذ. ".repeat(90)
        overlay.propose(description, null)
        val root = panel(service)
        layout(root, 336, 400)
        assertTrue(views(root).filterIsInstance<TextView>().any { it.text.toString() == description })
        listOf("نفّذ مرة", "ورّيني").forEach { label -> inside(root, views(root).filterIsInstance<TextView>().first { it.text == label }) }
        overlay.hide()
    }
}
