package com.jarvis.assistant.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import com.jarvis.assistant.R
import com.jarvis.assistant.databinding.ViewIslandBinding

/**
 * A Dynamic-Island-style pill at the top of the screen that shows what Jarvis is doing while it
 * works on a task. Drawn as an accessibility overlay, so it needs no extra permission. Tapping it
 * reveals a stop button. All methods must be called on the main thread.
 */
class TaskIsland(private val service: AccessibilityService) {

    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var ui: ViewIslandBinding? = null
    private val hideStop = Runnable {
        ui?.let { it.islandStop.visibility = View.GONE; it.root.setPaddingRelative(it.root.paddingStart, 0, endPadding, 0) }
    }
    private val endPadding = (14 * service.resources.displayMetrics.density).toInt()
    private val dismiss = Runnable { hide() }

    var onStop: () -> Unit = {}

    fun show(text: String) {
        main.removeCallbacks(dismiss)
        val b = ui ?: create() ?: return
        b.islandReactor.visibility = View.VISIBLE
        b.islandReactor.mode = ArcReactorView.Mode.WORKING
        b.islandResult.visibility = View.GONE
        b.islandText.text = text
    }

    /** Shows the outcome for a few seconds, then shrinks away. */
    fun finish(success: Boolean, text: String) {
        val b = ui ?: return
        b.islandReactor.visibility = View.GONE
        b.islandResult.visibility = View.VISIBLE
        b.islandResult.setImageResource(if (success) R.drawable.ic_check else R.drawable.ic_stop)
        b.islandResult.imageTintList = android.content.res.ColorStateList.valueOf(
            service.color(if (success) R.color.ok else R.color.warn))
        hideStop.run()
        b.islandText.text = text
        main.removeCallbacks(dismiss)
        main.postDelayed(dismiss, 4000)
    }

    fun hide() {
        val b = ui ?: return
        ui = null
        main.removeCallbacks(hideStop)
        b.root.animate().scaleX(0.4f).scaleY(0.4f).alpha(0f).setDuration(180).withEndAction {
            runCatching { wm.removeView(b.root) }
        }.start()
    }

    private fun create(): ViewIslandBinding? {
        // Inflate with the app's theme: the service context has none, which drops icon tints.
        val b = ViewIslandBinding.inflate(LayoutInflater.from(android.view.ContextThemeWrapper(service, R.style.Theme_Jarvis)))
        b.root.setOnClickListener {
            // First tap reveals the stop button; it hides again after a few seconds.
            b.islandStop.visibility = View.VISIBLE
            b.root.setPaddingRelative(b.root.paddingStart, 0, b.root.paddingStart, 0)
            main.removeCallbacks(hideStop)
            main.postDelayed(hideStop, 4000)
        }
        b.islandStop.setOnClickListener { onStop() }
        // Overlays aren't inflated through AppCompat, so XML tints don't apply; set them here.
        b.islandStop.imageTintList = android.content.res.ColorStateList.valueOf(service.color(R.color.danger))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = topOffset(service)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            title = "Jarvis task island"
        }
        return try {
            wm.addView(b.root, params)
            // Spring in, like the iPhone's island.
            b.root.scaleX = 0.4f; b.root.scaleY = 0.4f; b.root.alpha = 0f
            b.root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.6f)).start()
            ui = b
            b
        } catch (e: Exception) {
            null // e.g. the accessibility service was just turned off
        }
    }

    companion object {
        /**
         * Places the pill just below the status bar and camera cut-out, so it never covers the
         * notification icons or the camera.
         */
        fun topOffset(context: Context): Int {
            val res = context.resources
            val gap = (10 * res.displayMetrics.density).toInt()
            var top = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    val insets = context.getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                    top = insets.getInsetsIgnoringVisibility(
                        android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout()
                    ).top
                }
            }
            if (top <= 0) {
                val id = res.getIdentifier("status_bar_height", "dimen", "android")
                top = if (id > 0) res.getDimensionPixelSize(id) else (24 * res.displayMetrics.density).toInt()
            }
            return top + gap
        }
    }
}
