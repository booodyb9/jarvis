package com.jarvis.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.R
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class TaskIslandTest {
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun views(service: JarvisAccessibilityService): List<View> =
        (shadowOf(service.getSystemService(WindowManager::class.java) as WindowManager) as org.robolectric.shadows.ShadowWindowManagerImpl).views

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun text(root: View, id: Int) = root.findViewById<TextView>(id).text.toString()

    @Test
    fun listensWorksSpeaksAndGoesAway() {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        val island = service.island

        var heard = ""
        island.listening({ heard }, { 0.6f })
        val pill = views(service).single()
        idle(600)
        assertEquals("Listening…", text(pill, R.id.islandText))
        snapshot(pill, "island_listening.png")
        heard = "Send my wife a message"
        pill.invalidate(); pill.draw(Canvas(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))) // one frame
        idle(50)
        assertEquals("Send my wife a message", text(pill, R.id.islandText))

        island.working("Send my wife a message on WhatsApp and tell her I love her")
        island.status("Opening WhatsApp…")
        assertEquals("Opening WhatsApp…", text(pill, R.id.islandText))
        snapshot(pill, "island_working.png")

        island.speaking("On it.") { 0.8f }
        warmUp(pill)
        snapshot(pill, "island_speaking.png")
        island.speakingDone()

        island.finish(true, "Sent your wife 'I love you' on WhatsApp")
        snapshot(pill, "island_done.png")
        idle(5000)
        assertTrue("hidden after showing the result", views(service).isEmpty())
    }

    @Test
    fun tapExpandsIntoACardWithButtons() {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        val island = service.island
        island.working("Send my wife a message on WhatsApp and tell her I love her")
        island.status("Typing the message…")
        val root = views(service).single()
        idle(600)

        root.findViewById<View>(R.id.compact).performClick()
        idle(800)
        assertTrue(island.expanded)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.expanded).visibility)
        assertEquals("Stop", text(root, R.id.expPrimary))
        assertEquals("Send my wife a message on WhatsApp and tell her I love her", text(root, R.id.expTask))
        snapshot(root, "island_expanded.png")

        island.speaking("I've opened WhatsApp", { 0.7f })
        warmUp(root)
        snapshot(root, "island_expanded_speaking.png")
        island.speakingDone()

        var stopped = false
        island.onStop = { stopped = true }
        root.findViewById<View>(R.id.expPrimary).performClick()
        assertTrue(stopped)

        root.findViewById<View>(R.id.expHeader).performClick()
        idle(800)
        assertFalse(island.expanded)
        assertEquals(View.GONE, root.findViewById<View>(R.id.expanded).visibility)
    }

    @Test
    fun listeningCanBeCancelled() {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        val island = service.island
        island.listening(null, { 0.3f })
        val root = views(service).single()
        idle(600)
        root.findViewById<View>(R.id.compact).performClick()
        idle(800)
        assertEquals("Cancel", text(root, R.id.expPrimary))
        var cancelled = false
        island.onStop = { cancelled = true }
        root.findViewById<View>(R.id.expPrimary).performClick()
        assertTrue(cancelled)
    }

    @Test
    fun sitsBelowTheStatusBarAndCamera() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val res = ctx.resources
        val statusBar = res.getDimensionPixelSize(res.getIdentifier("status_bar_height", "dimen", "android"))
        assertTrue("island must clear the status bar", TaskIsland.topOffset(ctx) > statusBar)
    }

    /** Draws a few frames so animated levels settle, as they would on screen. */
    private fun warmUp(v: View) {
        val scratch = Canvas(Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888))
        repeat(15) { v.draw(scratch); idle(16) }
    }

    private fun snapshot(v: View, name: String) {
        listOf(R.id.islandOrb, R.id.expOrb).forEach { v.findViewById<ArcReactorView?>(it)?.animate = false }
        v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
        val lp = v.layoutParams as? WindowManager.LayoutParams
        val wSpec = if (lp != null && lp.width > 0) View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY)
            else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        v.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val pad = 30
        val bmp = Bitmap.createBitmap(v.measuredWidth + pad * 2, v.measuredHeight + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFFE9E9EE.toInt()) // a light app behind it, so the black island stands out
        c.translate(pad.toFloat(), pad.toFloat())
        v.draw(c)
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
