package com.jarvis.assistant.arabic

import android.Manifest
import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.*
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.accessibility.ScreenSnapshot
import com.jarvis.assistant.agent.*
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArabicUiTest {
    private fun roots(s: JarvisAccessibilityService) = (shadowOf(s.getSystemService(WindowManager::class.java)) as ShadowWindowManagerImpl).views.toList()
    private fun texts(v: View): List<TextView> = if (v is ViewGroup) (0 until v.childCount).flatMap { texts(v.getChildAt(it)) } else listOfNotNull(v as? TextView)
    @Test fun oldHostCannotExecuteWithoutNativeConsent() {
        val host = object : AgentHost {
            override fun status(text: String) = Unit
            override fun ask(question: String): String? = null
            override val isCancelled = false
        }
        var executed = false
        try { host.performApproved(ToolCall("1", "tap", JSONObject())) { executed = true; "ok" }; fail("Must deny") }
        catch (_: IllegalStateException) { assertFalse(executed) }
    }
    @Test fun overlayHasInputStopAndSeparatedApprovalControls() {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        val overlay = AssistantOverlay(service)
        overlay.showBubble(); assertEquals(1, roots(service).size)
        overlay.open(); assertEquals(2, roots(service).size)
        val panel = roots(service).last()
        assertTrue(texts(panel).any { it.text == "إيقاف" })
        assertTrue(texts(panel).any { it.text == "إرسال" })
        assertFalse(texts(panel).any { it.text == "نفّذ مرة" })
        var count = 0; overlay.onExecute = { count++ }
        overlay.propose("اضغط على الإعدادات", Rect(30, 250, 400, 320))
        assertEquals(3, roots(service).size)
        texts(panel).first { it.text == "نفّذ مرة" }.performClick()
        assertEquals(1, count)
        overlay.clearProposal(); assertEquals(2, roots(service).size)
        overlay.hide(); assertTrue(roots(service).isEmpty())
    }
    @Test fun selectionCanBeCancelledAndRemovedCompletely() {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        val overlay = AssistantOverlay(service)
        var selected = false
        overlay.select { selected = true }
        val picker = roots(service).single()
        picker.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        assertFalse(selected)
        overlay.hide(); assertTrue(roots(service).isEmpty())
    }
    @Test fun screenProofChangesForBoundsAndPasswordDoesNotBecomePlaintext() {
        val node = android.view.accessibility.AccessibilityNodeInfo.obtain().apply {
            text = "secret"; isPassword = true; setBoundsInScreen(Rect(1, 2, 3, 4))
        }
        val first = ScreenProof.of(ScreenSnapshot("protected", mapOf(1 to node), "pkg"))
        node.text = "new secret"
        assertEquals(first, ScreenProof.of(ScreenSnapshot("protected", mapOf(1 to node), "pkg")))
        node.setBoundsInScreen(Rect(2, 2, 4, 4))
        assertNotEquals(first, ScreenProof.of(ScreenSnapshot("protected", mapOf(1 to node), "pkg")))
    }
    @Test fun homeAndOverlayRenderAtMobileWidths() {
        val activity = Robolectric.buildActivity(ArabicHomeActivity::class.java).setup().get()
        val root = activity.window.decorView
        val folder = File("build/screenshots/arabic").apply { mkdirs() }
        for (width in listOf(360, 390, 430)) {
            val w = GlassUi.dp(activity, width); val h = GlassUi.dp(activity, 900)
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); root.layout(0, 0, w, h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap)); File(folder, "home-$width.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        activity.finish()
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        val overlay = AssistantOverlay(service); overlay.open()
        overlay.propose("افتح النظام والتحديثات", null)
        val panel = roots(service).last()
        val w = GlassUi.dp(service, 336); val h = GlassUi.dp(service, 544)
        panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)); panel.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); panel.draw(Canvas(bitmap))
        File(folder, "approval-360.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        overlay.hide()
    }
}
