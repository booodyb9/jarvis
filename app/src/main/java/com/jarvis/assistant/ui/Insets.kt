package com.jarvis.assistant.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15 draws apps edge to edge, behind the status and navigation bars. Pads [this] so its
 * content (especially bottom buttons) stays clear of them and of the keyboard.
 */
fun View.padForSystemBars(bottom: Boolean = true) {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        v.setPadding(
            base[0] + bars.left,
            base[1] + bars.top,
            base[2] + bars.right,
            base[3] + if (bottom) maxOf(bars.bottom, ime.bottom) else 0,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
