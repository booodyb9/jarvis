package com.jarvis.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.ModelProfile
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Renders every page to PNGs in build/screenshots for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    private val out = File("build/screenshots").apply { mkdirs() }
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun withModels() = Settings(ctx).apply {
        onboarded = true
        saveProfile(ModelProfile(name = "Claude Opus", providerId = "anthropic", baseUrl = "https://api.anthropic.com", apiKey = "k", model = "claude-opus-5-5"))
        saveProfile(ModelProfile(name = "Home PC (LM Studio)", providerId = "lmstudio", baseUrl = "http://192.168.1.50:1234/v1", apiKey = "", model = "qwen3-14b"))
        saveProfile(ModelProfile(name = "GPT", providerId = "openai", baseUrl = "https://api.openai.com/v1", apiKey = "k", model = "gpt-5"))
    }

    @Test
    fun mainPages() {
        withModels()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                for ((tab, name) in listOf(R.id.nav_home to "home", R.id.nav_models to "models", R.id.nav_settings to "settings")) {
                    a.goTo(tab)
                    ShadowLooper.idleMainLooper()
                    a.findViewById<ArcReactorView?>(R.id.reactor)?.animate = false
                    capturePage(a, name)
                }
            }
        }
    }

    @Test
    fun cloudVoiceSettings() {
        withModels().voiceEngine = "elevenlabs"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                a.goTo(R.id.nav_settings)
                ShadowLooper.idleMainLooper()
                capturePage(a, "settings_cloud")
            }
        }
    }

    @Test
    fun modelEditor() {
        withModels()
        val id = Settings(ctx).profiles().first().id
        ActivityScenario.launch<ModelEditActivity>(Intent(ctx, ModelEditActivity::class.java).putExtra(ModelEditActivity.EXTRA_ID, id)).use {
            it.onActivity { a -> capturePage(a, "edit_claude") }
        }
        val local = Settings(ctx).profiles().first { p -> p.providerId == "lmstudio" }.id
        ActivityScenario.launch<ModelEditActivity>(Intent(ctx, ModelEditActivity::class.java).putExtra(ModelEditActivity.EXTRA_ID, local)).use {
            it.onActivity { a -> capturePage(a, "edit_lmstudio") }
        }
    }

    @Test
    fun onboarding() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val primary = a.findViewById<View>(R.id.primaryButton)
                val skip = a.findViewById<View>(R.id.skipButton)
                a.findViewById<ArcReactorView>(R.id.reactor).animate = false
                capturePage(a, "onboard_1_welcome")
                primary.performClick()
                skip.performClick() // past microphone (the permission prompt is a system dialog)
                capturePage(a, "onboard_2_phone_control")
                skip.performClick(); skip.performClick()
                capturePage(a, "onboard_3_downloads")
                skip.performClick()
                capturePage(a, "onboard_4_model")
                skip.performClick()
                capturePage(a, "onboard_5_done")
            }
        }
    }

    @Test
    fun launcherIcon() {
        val size = 432
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.clipPath(Path().apply { addCircle(size / 2f, size / 2f, size / 2f * 0.92f, Path.Direction.CW) })
        for (res in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground)) {
            ContextCompat.getDrawable(ctx, res)!!.apply { setBounds(-size / 6, -size / 6, size + size / 6, size + size / 6) }.draw(canvas)
        }
        write(bmp, "icon.png")
    }

    /** Draws the whole page, expanding scroll views so off-screen content is included, then slices it per screen. */
    private fun capturePage(activity: Activity, name: String) {
        ShadowLooper.idleMainLooper()
        val root = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val w = activity.resources.displayMetrics.widthPixels
        val screenH = activity.resources.displayMetrics.heightPixels
        val extra = findScroll(root)?.let { s -> (s.getChildAt(0).let { c -> measureH(c, w) } - s.height).coerceAtLeast(0) } ?: 0
        val h = screenH + extra
        forceLayout(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).also { it.drawColor(ContextCompat.getColor(activity, R.color.bg)); root.draw(it) }
        var top = 0
        var i = 0
        while (top < h) {
            write(Bitmap.createBitmap(bmp, 0, top, w, minOf(screenH, h - top)), "${name}_$i.png")
            top += screenH; i++
        }
    }

    private fun forceLayout(v: View) {
        v.forceLayout()
        if (v is ViewGroup) for (i in 0 until v.childCount) forceLayout(v.getChildAt(i))
    }

    private fun measureH(v: View, w: Int): Int {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return v.measuredHeight
    }

    private fun findScroll(v: View): ScrollView? = when (v) {
        is ScrollView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findScroll(v.getChildAt(it)) }
        else -> null
    }

    private fun write(bmp: Bitmap, name: String) =
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
