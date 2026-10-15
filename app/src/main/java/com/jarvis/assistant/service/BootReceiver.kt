package com.jarvis.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.assistant.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !Settings(context).startOnBoot) return
        // Newer Android versions may refuse to start a microphone service at boot; the
        // accessibility service retries once it connects.
        runCatching { JarvisService.start(context) }.onFailure { Log.w("Jarvis", "boot start refused", it) }
    }
}
