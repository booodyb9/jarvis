package com.jarvis.assistant

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.service.JarvisService
import com.jarvis.assistant.voice.Kokoro
import com.jarvis.assistant.voice.SpeechModel
import com.jarvis.assistant.voice.WakeModel
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowAudioRecord
import com.jarvis.assistant.voice.ModelDownloader
import com.jarvis.assistant.voice.Speaker
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import java.io.File
import java.nio.ByteBuffer
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Runs the app's real voice code against the real sherpa-onnx engine (Linux build) and real models.
 * Skipped unless test-native/ is set up (see test-native/README.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RealEngineTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val native = File(System.getProperty("jarvis.nativeDir") ?: "test-native")
    private val models = File(native, "models")

    @Before
    fun loadNative() {
        org.robolectric.shadows.ShadowLog.stream = System.out
        val lib = File(native, "sherpa-onnx-v1.13.8-linux-x64-jni/lib")
        assumeTrue("real engine not set up", File(lib, "libsherpa-onnx-jni.so").exists() && File(models, "kokoro.tar.bz2").exists())
        System.load(File(lib, "libonnxruntime.so").absolutePath)
        System.load(File(lib, "libsherpa-onnx-jni.so").absolutePath)
        ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE).edit().clear().commit()
        org.robolectric.shadows.ShadowLog.stream = System.out
        JarvisService.resetForTest()
        seenListening = false
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (JarvisService.status == "Listening…") seenListening = true
            if (cond()) return true
            Thread.sleep(20)
        }
        return false
    }

    private fun installAll() {
        ModelDownloader.unpack(File(models, "kokoro.tar.bz2"), Kokoro.modelDir(ctx), ModelDownloader::openTarBz2)
        ModelDownloader.unpack(File(models, "kws.tar.bz2"), WakeModel.dir(ctx), ModelDownloader::openTarBz2)
        ModelDownloader.prune(WakeModel.dir(ctx), WakeModel.KEEP_FILES)
        ModelDownloader.unpack(File(models, "asr.tar.bz2"), SpeechModel.dir(ctx), ModelDownloader::openTarBz2)
        ModelDownloader.prune(SpeechModel.dir(ctx), SpeechModel.KEEP_FILES)
        assertTrue(Kokoro.isInstalled(ctx) && WakeModel.isInstalled(ctx) && SpeechModel.isInstalled(ctx))
    }

    /** Kokoro speech resampled to the microphone's 16 kHz, for feeding the simulated mic. */
    private val tts by lazy {
        val dir = Kokoro.modelDir(ctx)
        OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
            kokoro = OfflineTtsKokoroModelConfig(
                model = File(dir, "model.int8.onnx").path, voices = File(dir, "voices.bin").path,
                tokens = File(dir, "tokens.txt").path, dataDir = File(dir, "espeak-ng-data").path,
            ), numThreads = 4)))
    }

    private fun say(text: String, voice: Int = 5): FloatArray {
        val a = tts.generate(text, voice, 1.0f)
        val ratio = a.sampleRate / 16000.0
        return FloatArray((a.samples.size / ratio).toInt()) { i ->
            val x = i * ratio; val j = x.toInt(); val f = (x - j).toFloat()
            a.samples[j] * (1 - f) + a.samples[minOf(j + 1, a.samples.size - 1)] * f
        }
    }

    private fun silence(seconds: Double) = FloatArray((seconds * 16000).toInt())

    /** A simulated microphone that plays [script] in real time, then silence. */
    private fun playIntoMic(vararg parts: FloatArray) {
        val script = parts.reduce { a, b -> a + b }
        var pos = 0
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                Thread.sleep(sizeInShorts * 1000L / 16000) // real time
                for (i in 0 until sizeInShorts) {
                    val v = if (pos < script.size) script[pos] else 0f
                    audioData[offsetInShorts + i] = (v.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                    pos++
                }
                return sizeInShorts
            }
        })
    }

    /** Status at the moment the scenario ended (before the service is shut down). */
    private var finalStatus = ""

    private fun runService(timeoutMs: Long, until: () -> Boolean): Boolean {
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val controller = Robolectric.buildService(JarvisService::class.java).create().startCommand(0, 1)
        try {
            return waitFor(timeoutMs, until)
        } finally {
            finalStatus = JarvisService.status
            println("STATUS: $finalStatus | task=${JarvisService.lastTask}")
            controller.destroy()
        }
    }

    @Test
    fun turningOffWhileModelsLoadKeepsTheMicClosed() {
        installAll()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                reads.incrementAndGet()
                Thread.sleep(sizeInShorts * 1000L / 16000)
                return sizeInShorts
            }
        })
        shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        // Turn on, then off straight away while the models are still loading.
        Robolectric.buildService(JarvisService::class.java).create().startCommand(0, 1).destroy()
        waitFor(5000) { false } // plenty of time for the models to finish loading
        val before = reads.get()
        waitFor(1000) { false }
        assertEquals("the microphone must stay closed after turning off", before, reads.get())
        assertEquals(0, before)
    }

    @Test
    fun oneBreathCommandIsHeardAndStarted() {
        installAll()
        playIntoMic(silence(1.5), say("Hey Jarvis, send my wife a message on WhatsApp and tell her I love her."), silence(6.0))
        val started = runService(40_000) { JarvisService.lastTask != null }
        assertTrue("command reached the agent: ${JarvisService.lastTask}", started)
        val task = JarvisService.lastTask!!.lowercase()
        assertTrue(task, "send my wife" in task && "app" in task && "love her" in task)
        assertFalse("wake word stripped: $task", task.startsWith("hey"))
    }

    @Test
    fun pauseAfterWakeWordStillGetsTheCommand() {
        installAll()
        playIntoMic(silence(1.0), say("Hey Jarvis."), silence(2.5), say("Turn on the flashlight."), silence(6.0))
        assertTrue(runService(40_000) { JarvisService.lastTask != null })
        assertTrue(JarvisService.lastTask!!, "turn on the flashlight" in JarvisService.lastTask!!.lowercase())
    }

    @Test
    fun saysSorryWhenNothingFollowsTheWakeWord() {
        installAll()
        playIntoMic(silence(1.0), say("Hey Jarvis."), silence(14.0))
        assertTrue(runService(40_000) { JarvisService.status == "Didn't catch that" })
        assertTrue("no task started", JarvisService.lastTask == null)
    }

    private var seenListening = false

    @Test
    fun soundAlikesDoNotWakeJarvis() {
        installAll()
        playIntoMic(silence(1.0), say("Hey Travis, how are you?"), silence(1.0), say("The harvest is almost ready."),
            silence(1.0), say("Have you seen my car keys?"), silence(2.0))
        runService(16_000) { JarvisService.status == "Listening…" }
        assertTrue("never woke: $finalStatus", finalStatus.startsWith("Say") && !seenListening)
    }

    @Test
    fun kokoroSpeaksWithTheRealModel() {
        installAll()
        val speaker = Speaker(ctx)
        assertTrue(speaker.usingKokoro)
        var done = false
        val t0 = System.currentTimeMillis()
        speaker.speak("Good evening. All systems are online.") { done = true }
        val finished = waitFor(60_000) { done }
        val ms = System.currentTimeMillis() - t0
        println("KOKORO finished=$finished samples=${Kokoro.lastSamplesPlayed} error=${Kokoro.lastError} ms=$ms")
        assertFalse("Kokoro failed to load: ${Kokoro.lastError}", Kokoro.failed)
        assertTrue("speech finished", finished)
        assertTrue("no error: ${Kokoro.lastError}", Kokoro.lastError == null)
        assertTrue("about 2+ seconds of speech played", Kokoro.lastSamplesPlayed > Kokoro.SAMPLE_RATE * 2)
        assertTrue("waited for playback to finish", ms >= Kokoro.lastSamplesPlayed * 1000 / Kokoro.SAMPLE_RATE)

        // Stock phrases are cached on disk after the first time and play from the cache.
        done = false
        speaker.speak("Yes?") { done = true }
        assertTrue(waitFor(30_000) { done })
        val firstYes = Kokoro.lastSamplesPlayed
        assertTrue("'Yes?' played", firstYes > Kokoro.SAMPLE_RATE / 4)
        val cache = File(ctx.cacheDir, "kokoro-phrases")
        assertTrue("'Yes?' cached", cache.listFiles().orEmpty().isNotEmpty())
        done = false
        speaker.speak("Yes?") { done = true }
        assertTrue(waitFor(10_000) { done })
        assertTrue("cached copy is identical", Kokoro.lastSamplesPlayed == firstYes)

        // New speech interrupts old speech; only the newest reports done.
        var firstDone = false
        var secondDone = false
        speaker.speak("This is a long sentence that will be interrupted before it finishes playing.") { firstDone = true }
        Thread.sleep(300)
        speaker.speak("Stop.") { secondDone = true }
        assertTrue(waitFor(30_000) { secondDone })
        assertFalse("interrupted speech doesn't report done", firstDone)
    }
}
