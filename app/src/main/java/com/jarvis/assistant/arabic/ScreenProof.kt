package com.jarvis.assistant.arabic

import android.graphics.Rect
import com.jarvis.assistant.accessibility.ScreenSnapshot
import java.security.MessageDigest

object ScreenProof {
    fun of(screen: ScreenSnapshot): String {
        val data = buildString {
            append(screen.foregroundPackage).append('|').append(screen.text)
            screen.elements.forEach { (id, n) ->
                val b = Rect().also { n.getBoundsInScreen(it) }
                append(id).append('|').append(n.windowId).append('|').append(n.viewIdResourceName)
                append('|').append(n.className).append('|').append(b.toShortString())
                append('|').append(n.isEnabled).append('|').append(n.isVisibleToUser)
                append('|').append(n.isChecked).append('|').append(n.isPassword)
                if (!n.isPassword) append('|').append(n.text).append('|').append(n.contentDescription)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
