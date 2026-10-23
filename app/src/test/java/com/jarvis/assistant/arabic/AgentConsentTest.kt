package com.jarvis.assistant.arabic

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.jarvis.assistant.ModelProfile
import com.jarvis.assistant.Settings
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.agent.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentConsentTest {
    private fun turn(calls: List<Pair<String, JSONObject>>): String {
        val tools = JSONArray()
        calls.forEachIndexed { i, (name, args) -> tools.put(JSONObject().put("id", "call_$i").put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))) }
        return JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", JSONObject().put("role", "assistant").put("content", "").put("tool_calls", tools)))).toString()
    }
    private fun exercise(calls: List<Pair<String, JSONObject>>, expectedApprovals: Int) {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(turn(calls)))
            server.enqueue(MockResponse().setBody(turn(listOf("finish" to JSONObject().put("success", true).put("summary", "تمت المراجعة")))))
            val context = ApplicationProvider.getApplicationContext<Context>()
            val settings = Settings(context)
            val profile = ModelProfile(name = "test", providerId = "custom", baseUrl = server.url("/v1").toString().trimEnd('/'), apiKey = "", model = "mock")
            settings.saveProfile(profile); settings.primaryId = profile.id; settings.fallbackEnabled = false; settings.allowScreenshots = false
            val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
            JarvisAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
            var approved = 0
            val host = object : AgentHost {
                override val canReadScreen = true
                override val isCancelled = false
                override fun status(text: String) = Unit
                override fun ask(question: String): String? = null
                override fun performApproved(call: ToolCall, action: () -> String): String { approved++; return action() }
            }
            try {
                val result = PhoneAgent(settings, host).run("ارجع خطوة")
                assertEquals(expectedApprovals, approved)
                assertTrue(result.success)
                assertEquals(2, server.requestCount)
                server.takeRequest()
                val followup = JSONObject(server.takeRequest().body.readUtf8())
                val messages = followup.getJSONArray("messages")
                val outputs = (0 until messages.length()).map { messages.getJSONObject(it) }.filter { it.optString("role") == "tool" }
                if (calls.size > 1 || expectedApprovals == 0) assertTrue(outputs.any { it.getString("content").startsWith("ERROR:") })
            } finally { service.onDestroy() }
        }
    }
    @Test fun modelBatchCannotExecuteTwoActionsAgainstOneScreen() {
        exercise(listOf("press_key" to JSONObject().put("key", "back"), "press_key" to JSONObject().put("key", "home")), 1)
    }
    @Test fun modelCannotRequestGuessedCoordinates() {
        exercise(listOf("tap_point" to JSONObject().put("x", 100).put("y", 100)), 0)
    }
    @Test fun modelCannotBundleTextAndSubmission() {
        exercise(listOf("type_text" to JSONObject().put("element_id", 1).put("text", "hello").put("press_enter", true)), 0)
    }
}
