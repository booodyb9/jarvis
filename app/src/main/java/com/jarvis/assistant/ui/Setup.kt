package com.jarvis.assistant.ui

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.provider.Settings.Secure
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.databinding.ItemCheckBinding
import com.jarvis.assistant.voice.Kokoro
import com.jarvis.assistant.voice.ModelDownloader
import com.jarvis.assistant.voice.SpeechModel
import com.jarvis.assistant.voice.WakeModel

/** The things Jarvis needs before it can work, shared by onboarding, home and settings. */
object Setup {

    enum class Item(
        @DrawableRes val icon: Int,
        val title: String,
        val subtitle: String,
        val action: String,
        val required: Boolean,
    ) {
        MICROPHONE(R.drawable.ic_mic, "Microphone", "So Jarvis can hear you", "Allow", true),
        PHONE_CONTROL(R.drawable.ic_touch, "Phone control", "Lets Jarvis tap, type and scroll for you", "Enable", true),
        BACKGROUND(R.drawable.ic_battery, "Background power", "Keeps Jarvis listening with the screen off", "Allow", false),
        WAKE_WORD(R.drawable.ic_wave, "Wake word", "Offline \"Hey Jarvis\" detector · 17 MB", "Download", false),
        SPEECH(R.drawable.ic_mic, "Speech recognition", "Understands your commands offline · 57 MB", "Download", false),
        VOICE(R.drawable.ic_voice, "Kokoro voice", "Natural on-device voice · 100 MB", "Download", false),
    }

    val PERMISSIONS: Array<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun isDone(context: Context, item: Item): Boolean = when (item) {
        Item.MICROPHONE -> ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Item.PHONE_CONTROL -> isAccessibilityEnabled(context)
        Item.BACKGROUND -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        Item.WAKE_WORD -> WakeModel.isInstalled(context)
        Item.SPEECH -> SpeechModel.isInstalled(context)
        Item.VOICE -> Kokoro.isInstalled(context)
    }

    fun remaining(context: Context) = Item.entries.filter { !isDone(context, it) }

    fun act(activity: Activity, item: Item) {
        when (item) {
            Item.MICROPHONE -> activity.requestPermissions(PERMISSIONS, 1)
            Item.PHONE_CONTROL -> {
                Toast.makeText(
                    activity,
                    "Tap \"Installed apps\" (or \"Downloaded apps\"), then \"Jarvis phone control\", and switch it on.",
                    Toast.LENGTH_LONG,
                ).show()
                activity.startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS))
            }
            Item.BACKGROUND -> {
                // Some phones block the direct prompt; fall back to the battery list, then the app's own page.
                val attempts = listOf(
                    Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")),
                    Intent(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")),
                )
                val opened = attempts.indexOfFirst { runCatching { activity.startActivity(it) }.isSuccess }
                if (opened > 0) {
                    Toast.makeText(activity, "Find Jarvis in the list and set it to \"Don't optimise\" or \"Unrestricted\".", Toast.LENGTH_LONG).show()
                }
            }
            Item.WAKE_WORD, Item.SPEECH, Item.VOICE -> Downloads.start(activity.applicationContext, item)
        }
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        if (JarvisAccessibilityService.instance != null) return true
        val enabled = Secure.getString(context.contentResolver, Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, JarvisAccessibilityService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    /** Adds one row per item to [container]; call the returned function to refresh them. */
    fun bindRows(activity: Activity, container: ViewGroup, items: List<Item>): () -> Unit {
        val rows = items.map { item ->
            val row = ItemCheckBinding.inflate(LayoutInflater.from(activity), container, true)
            row.icon.setImageResource(item.icon)
            row.title.text = item.title
            row.subtitle.text = item.subtitle
            row.action.text = item.action
            row.action.setOnClickListener { act(activity, item) }
            item to row
        }
        return {
            for ((item, row) in rows) {
                val progress = Downloads.progress[item]
                val done = isDone(activity, item) && progress == null
                row.done.visibility = if (done) View.VISIBLE else View.GONE
                row.action.visibility = if (done || progress != null) View.GONE else View.VISIBLE
                row.progress.visibility = if (progress != null) View.VISIBLE else View.GONE
                if (progress != null) {
                    row.progress.setProgressCompat(progress, true)
                    row.subtitle.text = if (progress >= 90) "Unpacking…" else "Downloading… $progress%"
                } else {
                    row.subtitle.text = Downloads.errors[item] ?: item.subtitle
                }
                row.icon.alpha = if (done) 1f else 0.6f
            }
        }
    }
}

/** Model downloads keep running (and reporting) when the user moves between pages. */
object Downloads {
    val progress = mutableMapOf<Setup.Item, Int>()
    val errors = mutableMapOf<Setup.Item, String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() = listeners.toList().forEach { it() }

    fun start(context: Context, item: Setup.Item) {
        if (progress.containsKey(item)) return
        progress[item] = 0
        errors.remove(item)
        notifyListeners()
        Thread {
            val report: (Int) -> Unit = { p -> main.post { if (progress.containsKey(item)) { progress[item] = p; notifyListeners() } } }
            val error = try {
                when (item) {
                    Setup.Item.VOICE -> ModelDownloader.downloadKokoro(context, report)
                    Setup.Item.SPEECH -> ModelDownloader.downloadSpeech(context, report)
                    else -> ModelDownloader.downloadWakeWord(context, report)
                }
                null
            } catch (e: Throwable) {
                "Download failed: ${e.message}"
            }
            if (error == null && item == Setup.Item.VOICE) {
                val settings = com.jarvis.assistant.Settings(context)
                Kokoro.prerender(context, settings.kokoroSpeaker, settings.voiceSpeed)
            }
            main.post {
                progress.remove(item)
                if (error != null) errors[item] = error
                notifyListeners()
                Toast.makeText(context, error ?: "${item.title} installed", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }
}
