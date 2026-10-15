package com.jarvis.assistant.ui

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Buttons must never sit under the phone's own navigation buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayoutSafetyTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun themeOptsOutOfEdgeToEdge() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { s ->
            s.onActivity { a ->
                val v = TypedValue()
                assertTrue(a.theme.resolveAttribute(android.R.attr.windowOptOutEdgeToEdgeEnforcement, v, true))
                assertEquals(TypedValue.TYPE_INT_BOOLEAN, v.type)
                assertTrue(v.data != 0)
            }
        }
    }

    @Test
    fun mainScreenKeepsBottomBarAboveNavigationButtons() {
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().clear().commit()
        Settings(ctx).onboarded = true
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { a ->
                val root = (a.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
                val nav = a.findViewById<View>(R.id.nav)
                ViewCompat.dispatchApplyWindowInsets(root, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 96, 0, 132)).build())
                assertEquals("whole screen lifted above the navigation buttons", 132, root.paddingBottom)
                assertEquals("bottom bar not padded twice", 0, nav.paddingBottom)
            }
        }
    }
}
