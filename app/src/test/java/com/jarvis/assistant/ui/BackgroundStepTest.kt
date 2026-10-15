package com.jarvis.assistant.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.view.View
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.graphics.Insets
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Regression tests for getting stuck on the "Stay awake for you" step. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundStepTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().clear().commit()
        // Pretend the earlier steps were done so the walkthrough moves past them.
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun manifestDeclaresBatteryPermission() {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions!!.contains(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS))
    }

    @Test
    fun allowOpensPromptThenContinueAnywayMovesOn() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val primary = a.findViewById<TextView>(R.id.primaryButton)
                val title = a.findViewById<TextView>(R.id.stepTitle)
                primary.performClick() // welcome
                ShadowLooper.idleMainLooper()
                // Microphone is granted, so it auto-advances; phone control isn't, so skip it.
                shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
                a.findViewById<View>(R.id.skipButton).performClick()
                assertEquals("Stay awake for you", title.text)

                primary.performClick() // ALLOW
                assertEquals(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, shadowOf(a).nextStartedActivity.action)

                // User comes back without allowing it: they must not be stuck.
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { a ->
                val primary = a.findViewById<TextView>(R.id.primaryButton)
                assertEquals("Continue anyway", primary.text.toString())
                primary.performClick()
                assertEquals("Ears and a voice", a.findViewById<TextView>(R.id.stepTitle).text)
            }
        }
    }

    @Test
    fun bottomButtonsClearTheNavigationBar() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val root = a.findViewById<View>(android.R.id.content).let { (it as android.view.ViewGroup).getChildAt(0) }
                val before = root.paddingBottom
                val navBar = 132
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 96, 0, navBar))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(root, insets)
                assertEquals(before + navBar, root.paddingBottom)
            }
        }
    }
}
