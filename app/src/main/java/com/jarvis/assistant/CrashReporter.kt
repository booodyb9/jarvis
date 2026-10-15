package com.jarvis.assistant

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Keeps a report of the last crash so the user can share it, since we can't attach a debugger to their phone. */
object CrashReporter {
    private fun file(context: Context) = File(context.filesDir, "last_crash.txt")
    private fun prefs(context: Context) = context.getSharedPreferences("crash", Context.MODE_PRIVATE)

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                file(app).writeText(header(app) + "Thread: ${thread.name}\n\n$trace")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last unreported crash, including native crashes Android recorded (Android 11+), or null. */
    fun pending(context: Context): String? {
        file(context).takeIf { it.exists() }?.readText()?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java)
        val exit = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() }.getOrNull() ?: return null
        val crashed = exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            exit.reason == ApplicationExitInfo.REASON_CRASH || exit.reason == ApplicationExitInfo.REASON_ANR
        if (!crashed || exit.timestamp <= prefs(context).getLong("seen", 0)) return null
        val kind = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
            ApplicationExitInfo.REASON_ANR -> "App not responding"
            else -> "Crash"
        }
        val trace = if (exit.reason == ApplicationExitInfo.REASON_ANR)
            runCatching { exit.traceInputStream?.bufferedReader()?.readText()?.take(6000) }.getOrNull().orEmpty() else ""
        return header(context) + "$kind at ${fmt(exit.timestamp)}\nProcess: ${exit.processName}\n" +
            "Details: ${exit.description ?: "none"}\nStatus: ${exit.status}\n" + (if (trace.isNotEmpty()) "\n$trace" else "")
    }

    fun dismiss(context: Context) {
        file(context).delete()
        prefs(context).edit().putLong("seen", System.currentTimeMillis()).apply()
    }

    private fun header(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return "Jarvis $version crash report\nPhone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Time: ${fmt(System.currentTimeMillis())}\n"
    }

    private fun fmt(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))
}

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
