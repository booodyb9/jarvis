package com.jarvis.assistant.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.R
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import org.junit.Assert.assertEquals
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
        shadowOf(service.getSystemService(WindowManager::class.java) as android.view.WindowManager)
            .let { (it as org.robolectric.shadows.ShadowWindowManagerImpl).views }

    @Test
    fun appearsWhileWorkingThenGoesAway() {
        val service = Robolectric.buildService(JarvisAccessibilityService::class.java).create().get()
        val island = service.island
        island.show("Opening WhatsApp…")
        val pill = views(service).single()
        val text = pill.findViewById<android.widget.TextView>(R.id.islandText)
        assertEquals("Opening WhatsApp…", text.text.toString())
        snapshot(pill, "island_working.png")

        pill.performClick() // reveals the stop button
        assertEquals(View.VISIBLE, pill.findViewById<View>(R.id.islandStop).visibility)
        snapshot(pill, "island_stop.png")
        var stopped = false
        island.onStop = { stopped = true }
        pill.findViewById<View>(R.id.islandStop).performClick()
        assertTrue(stopped)

        island.finish(true, "Sent your wife 'I love you' on WhatsApp")
        snapshot(pill, "island_done.png")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue("hidden after showing the result", views(service).isEmpty())
    }

    private fun snapshot(v: View, name: String) {
        (v.findViewById<ArcReactorView?>(R.id.islandReactor))?.animate = false
        v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f
        v.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val pad = 40
        val bmp = Bitmap.createBitmap(v.measuredWidth + pad * 2, v.measuredHeight + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF2B3440.toInt()) // a typical app behind it
        c.translate(pad.toFloat(), pad.toFloat())
        v.draw(c)
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
