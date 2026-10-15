package com.jarvis.assistant.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.Settings
import com.jarvis.assistant.agent.AgentHost
import com.jarvis.assistant.agent.PhoneAgent
import com.jarvis.assistant.ui.MainActivity
import com.jarvis.assistant.voice.CommandListener
import com.jarvis.assistant.voice.Speaker
import com.jarvis.assistant.voice.VoiceInput
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Long-running foreground service: waits for "Hey Jarvis", listens for the command, runs the
 * phone agent on a worker thread, and speaks the result. Voice work happens on the main thread.
 */
class JarvisService : Service() {

    private enum class State { IDLE, LISTENING_COMMAND, WORKING }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var settings: Settings
    private lateinit var speaker: Speaker
    private lateinit var voice: VoiceInput
    /** Fallback when the offline speech model isn't downloaded. */
    private lateinit var google: CommandListener
    @Volatile private var state = State.IDLE
    @Volatile private var cancelled = false
    private var startFailed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Models from earlier versions that are no longer used.
        listOf("vosk-model", "kws.old").forEach { java.io.File(filesDir, it).takeIf { f -> f.exists() }?.deleteRecursively() }
        settings = Settings(this)
        speaker = Speaker(this)
        voice = VoiceInput(this) { onWakeWord() }
        google = CommandListener(this)
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification("Starting…"),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
        } catch (e: Exception) {
            // Android refuses microphone services without the permission, or when started from the background.
            Log.w(TAG, "could not start in the foreground", e)
            startFailed = true
            stopSelf()
            return
        }
        running = true
        resumeListening()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startFailed) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP_TASK -> {
                cancelled = true
                voice.cancelCommand(); google.cancel(); speaker.stop()
                update("Stopping…")
            }
            ACTION_SHUTDOWN -> { stopSelf(); return START_NOT_STICKY }
            ACTION_RUN -> intent.getStringExtra(EXTRA_TASK)?.let { runTask(it) }
            ACTION_LISTEN -> if (state == State.IDLE) onWakeWord()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        phase = Phase.OFF
        status = "Offline"
        statusListener?.invoke(status)
        cancelled = true
        island { it.hide() }
        if (!startFailed) {
            voice.release()
            google.cancel()
            speaker.shutdown()
        }
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---- Voice flow ----

    /** Speaks with the microphone muted, so Jarvis never hears (or transcribes) itself. */
    private fun say(text: String, then: () -> Unit = {}) {
        voice.muted = true
        speaker.speak(text) {
            main.postDelayed({ voice.muted = false; then() }, 150) // let the speaker's echo die away
        }
    }

    private fun onWakeWord() {
        if (state != State.IDLE) return
        state = State.LISTENING_COMMAND
        update("Listening…")
        speaker.warmUp() // load the voice now, so the reply has no delay
        if (voice.canTranscribe) {
            // Keep transcribing straight after the wake word, so "Hey Jarvis, call mum" works in one breath.
            voice.listenForCommand { text -> onHeard(text) }
            // If the user paused after "Hey Jarvis", prompt them; if they're already talking, just listen.
            main.postDelayed({
                // Not muted: if the user starts talking over it, nothing is lost (a stray "yes" is stripped).
                if (state == State.LISTENING_COMMAND && !voice.speechSinceWake && voice.partialText.isEmpty()) speaker.speak("Yes?")
            }, 700)
        } else {
            voice.stop() // Google's recognizer needs the microphone
            say("Yes?") { google.listen { text -> onHeard(text) } }
        }
    }

    private fun onHeard(text: String?) {
        if (state != State.LISTENING_COMMAND) return
        if (text.isNullOrBlank()) {
            state = State.IDLE
            update("Didn't catch that")
            say("Sorry, I didn't catch that.") { resumeListening() }
        } else {
            runTask(text)
        }
    }

    private fun runTask(task: String) {
        if (state == State.WORKING) return
        state = State.WORKING
        cancelled = false
        voice.cancelCommand()
        voice.stop()
        lastTask = task
        island { it.onStop = { start(this, ACTION_STOP_TASK) }; it.show(task) }
        update("Working on: $task")
        say("On it.")
        worker.execute {
            val result = try {
                waitForUnlock()
                PhoneAgent(settings, host).run(task)
            } catch (e: Throwable) {
                Log.e(TAG, "task failed", e)
                com.jarvis.assistant.agent.AgentResult(false, "Something went wrong: ${e.message}")
            }
            main.post {
                lastResult = result.summary
                island { it.finish(result.success, result.summary) }
                update(result.summary)
                say(result.summary) {
                    state = State.IDLE
                    resumeListening()
                }
            }
        }
    }

    /** Goes back to waiting for "Hey Jarvis" (loading any models downloaded since). */
    private fun resumeListening() {
        if (state != State.IDLE) return
        voice.load { error ->
            if (state != State.IDLE) return@load
            voice.wakeEnabled = settings.wakeWordEnabled
            when {
                error != null -> update(error)
                !settings.wakeWordEnabled -> { voice.stop(); update("Ready. Tap the mic to talk.") }
                !voice.hasWakeWord -> update("Ready. Download the wake word in Settings to use \"Hey Jarvis\".")
                else -> { voice.start(); update("Say \"Hey Jarvis\"") }
            }
        }
    }

    /** Accessibility services can't act behind the lock screen, so wake the screen and wait for an unlock. */
    @Suppress("DEPRECATION")
    private fun waitForUnlock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) return
        val pm = getSystemService(PowerManager::class.java)
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "jarvis:wake")
            .acquire(3000)
        speakAndWait("Please unlock your phone and I'll get started.")
        val deadline = System.currentTimeMillis() + 30_000
        while (keyguard.isKeyguardLocked && System.currentTimeMillis() < deadline && !cancelled) Thread.sleep(300)
    }

    /** For the worker thread: speak and block until done. */
    private fun speakAndWait(text: String) {
        val done = CountDownLatch(1)
        main.post { say(text) { done.countDown() } }
        done.await(30, TimeUnit.SECONDS)
    }

    /** Bridges the agent (worker thread) to the voice UI (main thread). */
    private val host = object : AgentHost {
        override val isCancelled get() = cancelled
        override fun status(text: String) { main.post { update(text) } }
        override fun ask(question: String): String? {
            val latch = CountDownLatch(1)
            var answer: String? = null
            main.post {
                update("Asking: $question")
                say(question) {
                    val got: (String?) -> Unit = { text -> answer = text; latch.countDown() }
                    if (voice.canTranscribe) voice.listenForCommand(10_000) { voice.stop(); got(it) } else google.listen(got)
                }
            }
            latch.await(60, TimeUnit.SECONDS)
            return answer
        }
    }

    // ---- Notification ----

    /** Runs [block] on the task island, if phone control is on and the island is enabled. */
    private fun island(block: (com.jarvis.assistant.ui.TaskIsland) -> Unit) {
        if (!settings.showIsland) return
        JarvisAccessibilityService.instance?.island?.let { runCatching { block(it) } }
    }

    private fun update(text: String) {
        status = text
        if (state == State.WORKING && !text.startsWith("Working on:")) island { it.show(text) }
        phase = when (state) {
            State.IDLE -> Phase.IDLE
            State.LISTENING_COMMAND -> Phase.LISTENING
            State.WORKING -> Phase.WORKING
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        statusListener?.invoke(text)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(action: String, code: Int) =
            PendingIntent.getService(this, code, Intent(this, JarvisService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Talk", action(ACTION_LISTEN, 1))
            .addAction(0, "Stop task", action(ACTION_STOP_TASK, 2))
            .addAction(0, "Turn off", action(ACTION_SHUTDOWN, 3))
            .build()
    }

    companion object {
        private const val TAG = "JarvisService"
        private const val CHANNEL = "jarvis"
        private const val NOTIFICATION_ID = 1
        const val ACTION_RUN = "com.jarvis.assistant.RUN"
        const val ACTION_LISTEN = "com.jarvis.assistant.LISTEN"
        const val ACTION_STOP_TASK = "com.jarvis.assistant.STOP_TASK"
        const val ACTION_SHUTDOWN = "com.jarvis.assistant.SHUTDOWN"
        const val EXTRA_TASK = "task"

        enum class Phase { OFF, IDLE, LISTENING, WORKING }

        @Volatile var running = false
            private set
        @Volatile var phase = Phase.OFF
            private set
        @Volatile var status = "Off"
            private set
        @Volatile var lastTask: String? = null
            private set
        @Volatile var lastResult: String? = null
            private set
        @androidx.annotation.VisibleForTesting
        fun resetForTest() { lastTask = null; lastResult = null; status = "Off"; phase = Phase.OFF }

        /** Set by the visible activity to mirror status changes. Called on the main thread. */
        var statusListener: ((String) -> Unit)? = null

        /** Returns false if Jarvis can't start yet because microphone permission is missing. */
        fun start(context: Context, action: String? = null, task: String? = null): Boolean {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            val intent = Intent(context, JarvisService::class.java).setAction(action).putExtra(EXTRA_TASK, task)
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        fun stop(context: Context) {
            context.startService(Intent(context, JarvisService::class.java).setAction(ACTION_SHUTDOWN))
        }
    }
}
