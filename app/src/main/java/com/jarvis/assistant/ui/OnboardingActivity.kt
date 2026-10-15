package com.jarvis.assistant.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.view.View
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.jarvis.assistant.R
import com.jarvis.assistant.Settings
import com.jarvis.assistant.databinding.ActivityOnboardingBinding
import com.jarvis.assistant.service.JarvisService

/**
 * First-run walkthrough. Asks for what Android lets an app request directly, and takes the user
 * to the right system screen for what it doesn't (phone control), ticking each step off itself.
 */
class OnboardingActivity : AppCompatActivity() {

    private val DOWNLOADS = listOf(Setup.Item.WAKE_WORD, Setup.Item.SPEECH, Setup.Item.VOICE)

    private enum class Step { WELCOME, MICROPHONE, PHONE_CONTROL, BACKGROUND, DOWNLOADS, MODEL, DONE }

    private lateinit var ui: ActivityOnboardingBinding
    private lateinit var settings: Settings
    private var step = Step.WELCOME
    private var refreshDownloads: (() -> Unit)? = null
    private val onDownload: () -> Unit = { refreshDownloads?.invoke(); render() }
    private var autoAdvance: Runnable? = null
    private val askedFor = mutableSetOf<Step>()
    private val attempted = mutableSetOf<Step>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(ui.root)
        ui.root.padForSystemBars()
        settings = Settings(this)
        step = savedInstanceState?.getString("step")?.let { Step.valueOf(it) } ?: Step.WELCOME
        ui.primaryButton.setOnClickListener { primaryAction() }
        ui.skipButton.setOnClickListener { next() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("step", step.name)
    }

    override fun onResume() {
        super.onResume()
        Downloads.listen(onDownload)
        render()
    }

    override fun onPause() {
        super.onPause()
        Downloads.unlisten(onDownload)
        autoAdvance?.let { ui.root.removeCallbacks(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun isDone(s: Step): Boolean = when (s) {
        Step.MICROPHONE -> Setup.isDone(this, Setup.Item.MICROPHONE)
        Step.PHONE_CONTROL -> Setup.isDone(this, Setup.Item.PHONE_CONTROL)
        Step.BACKGROUND -> Setup.isDone(this, Setup.Item.BACKGROUND)
        Step.DOWNLOADS -> DOWNLOADS.all { Setup.isDone(this, it) }
        Step.MODEL -> settings.primary() != null
        else -> false
    }

    private fun render() {
        val total = Step.entries.size - 1
        ui.stepProgress.setProgressCompat(step.ordinal * 100 / total, true)
        ui.stepCount.text = if (step == Step.WELCOME) "" else "${step.ordinal} / $total"
        ui.reactor.visibility = if (step == Step.WELCOME || step == Step.DONE) View.VISIBLE else View.GONE
        ui.reactor.mode = if (step == Step.DONE) ArcReactorView.Mode.LISTENING else ArcReactorView.Mode.IDLE
        ui.stepIcon.visibility = if (ui.reactor.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        ui.stepExtra.removeAllViews()
        refreshDownloads = null
        val done = isDone(step)
        ui.stepDone.visibility = if (done) View.VISIBLE else View.GONE
        ui.skipButton.visibility = if (step == Step.WELCOME || done) View.GONE else View.VISIBLE
        ui.skipButton.text = if (step == Step.DONE) "Go to the app without activating" else "Skip for now"

        when (step) {
            Step.WELCOME -> show(0, "Hello, I'm Jarvis",
                "I can run your phone for you. Say \"Hey Jarvis\" and tell me what you need: send a message, open an app, change a setting.\n\nLet's get me set up. It takes about two minutes.",
                "Get started")
            Step.MICROPHONE -> {
                show(R.drawable.ic_mic, "Let me hear you",
                    "I need the microphone to hear \"Hey Jarvis\" and your commands. Listening for the wake word happens entirely on your phone.",
                    if (done) "Continue" else "Allow microphone")
                // Ask straight away; Android shows its own permission prompt.
                if (!done && askedFor.add(step)) requestPermissions(Setup.PERMISSIONS, 1)
            }
            Step.PHONE_CONTROL -> {
                show(R.drawable.ic_touch, "Let me use your phone",
                    "To tap, type and scroll for you, Android needs you to switch on Jarvis in Accessibility settings:\n\n1. Tap Open settings below\n2. Find Installed apps (or Downloaded apps)\n3. Tap Jarvis phone control and switch it on",
                    if (done) "Continue" else "Open settings")
                if (!done && Build.VERSION.SDK_INT >= 33) {
                    addNote("Greyed out or says \"Restricted setting\"? Open Jarvis's app info, tap ⋮ in the top corner, choose \"Allow restricted settings\", then try again.")
                    addButton("Open Jarvis app info") {
                        startActivity(Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                }
            }
            Step.BACKGROUND -> show(R.drawable.ic_battery, "Stay awake for you",
                "Allow Jarvis to run in the background so I can still hear \"Hey Jarvis\" when your screen is off.\n\nOn Samsung, Xiaomi and some other phones, also set Jarvis's battery setting to \"Unrestricted\".",
                when { done -> "Continue"; step in attempted -> "Continue anyway"; else -> "Allow" })
            Step.DOWNLOADS -> {
                val downloading = Downloads.progress.isNotEmpty()
                show(R.drawable.ic_voice, "Ears and a voice",
                    "Three downloads that run entirely on your phone: the \"Hey Jarvis\" listener, speech recognition for your commands, and Kokoro, a natural-sounding voice. Use Wi-Fi if you can. You can carry on while they download.",
                    when { done || downloading -> "Continue"; else -> "Download all (175 MB)" })
                refreshDownloads = Setup.bindRows(this, ui.stepExtra, DOWNLOADS).also { it() }
            }
            Step.MODEL -> {
                val primary = settings.primary()
                show(R.drawable.ic_memory, "Connect my brain",
                    if (primary == null) "Choose the AI that does the thinking: Claude, OpenAI, or a model on your own computer with LM Studio or Ollama. You can add more later and pick a primary one."
                    else "I'll think with ${primary.name}. You can add more models and change the primary one on the Models page.",
                    if (primary == null) "Add a model" else "Continue")
            }
            Step.DONE -> show(0, "All systems online",
                "Say \"Hey Jarvis\" any time, or tap the mic in the app. Try: \"Hey Jarvis, message my wife on WhatsApp and tell her I love her.\"",
                "Turn on Jarvis")
        }

        // Tick a step off as soon as the user comes back having done it.
        autoAdvance?.let { ui.root.removeCallbacks(it) }
        if (done && step in setOf(Step.MICROPHONE, Step.PHONE_CONTROL, Step.BACKGROUND, Step.DOWNLOADS)) {
            autoAdvance = Runnable { next() }.also { ui.root.postDelayed(it, 900) }
        }
    }

    private fun show(@DrawableRes icon: Int, title: String, body: String, button: String) {
        if (icon != 0) ui.stepIcon.setImageResource(icon)
        ui.stepTitle.text = title
        ui.stepBody.text = body
        ui.primaryButton.text = button
    }

    private fun addNote(text: String) {
        ui.stepExtra.addView(TextView(this).apply {
            this.text = text
            setTextColor(color(R.color.text_dim))
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        })
    }

    private fun addButton(text: String, onClick: () -> Unit) {
        ui.stepExtra.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            isAllCaps = false
            setTextColor(color(R.color.accent))
            setOnClickListener { onClick() }
        })
    }

    private fun primaryAction() {
        // Optional steps can't trap the user: after one attempt the button moves on.
        if (step == Step.BACKGROUND && step in attempted && !isDone(step)) { next(); return }
        if (isDone(step) || step == Step.WELCOME) {
            if (step == Step.DONE) finishOnboarding(activate = true) else next()
            return
        }
        when (step) {
            Step.MICROPHONE -> Setup.act(this, Setup.Item.MICROPHONE)
            Step.PHONE_CONTROL -> Setup.act(this, Setup.Item.PHONE_CONTROL)
            Step.BACKGROUND -> { attempted += step; Setup.act(this, Setup.Item.BACKGROUND) }
            Step.DOWNLOADS -> {
                if (Downloads.progress.isNotEmpty()) { next(); return }
                DOWNLOADS.filter { !Setup.isDone(this, it) }.forEach { Setup.act(this, it) }
            }
            Step.MODEL -> startActivity(Intent(this, ModelEditActivity::class.java))
            Step.DONE -> finishOnboarding(activate = true)
            else -> next()
        }
    }

    private fun next() {
        if (step == Step.DONE) { finishOnboarding(activate = false); return }
        step = Step.entries[step.ordinal + 1]
        render()
    }

    private fun finishOnboarding(activate: Boolean) {
        settings.onboarded = true
        if (activate) JarvisService.start(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
}
