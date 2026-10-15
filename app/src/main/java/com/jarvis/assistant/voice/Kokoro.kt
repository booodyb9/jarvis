package com.jarvis.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kokoro-82M neural voice, run on the phone with sherpa-onnx. Kept light:
 * - one engine for the whole app (service and settings preview share it);
 * - loaded only when needed, and unloaded after [IDLE_UNLOAD_MS] without speech to free ~200 MB;
 * - stock phrases ("Yes?", "On it.") are rendered once and stored on disk, so they play instantly
 *   even while the model is unloaded;
 * - longer text streams sentence by sentence, so speech starts before the whole reply is generated.
 */
object Kokoro {
    private const val TAG = "Kokoro"
    const val SAMPLE_RATE = 24000
    private const val IDLE_UNLOAD_MS = 120_000L
    const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-en-v0_19.tar.bz2"
    val STOCK_PHRASES = listOf("Yes?", "On it.", "Please unlock your phone and I'll get started.")

    fun modelDir(context: Context) = File(context.filesDir, "kokoro")
    fun isInstalled(context: Context) =
        File(modelDir(context), "voices.bin").exists() && File(modelDir(context), "model.int8.onnx").exists()

    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kokoro").apply { priority = Thread.MAX_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var track: AudioTrack? = null
    private var unloadTask: java.util.concurrent.ScheduledFuture<*>? = null

    /** Loudness of queued speech over time (one value per 20 ms), so the island can move with the voice. */
    private class Envelope(val startMs: Long, val levels: FloatArray)
    private val envelopes = java.util.concurrent.CopyOnWriteArrayList<Envelope>()

    /** How loud the speech playing right now is, 0..1. */
    fun level(): Float {
        val now = SystemClock.elapsedRealtime()
        for (e in envelopes) {
            val i = ((now - e.startMs) / 20).toInt()
            if (i in e.levels.indices) return e.levels[i]
        }
        return 0f
    }

    /** Diagnostics: samples sent to the speaker for the most recent utterance, and the last error. */
    @Volatile var lastSamplesPlayed = 0L
        private set
    @Volatile var lastError: String? = null
        private set

    /** Set when the model can't load on this phone, so callers fall back to the system voice. */
    @Volatile var failed = false
        private set

    /** Start loading in the background so the next reply has no delay. Safe to call often. */
    fun warmUp(context: Context) {
        val app = context.applicationContext
        if (!isInstalled(app) || failed) return
        worker.execute { engine(app); scheduleUnload() }
    }

    /**
     * Speak [text]. Anything already being said is cut off. [onDone] runs on the main thread when
     * playback finishes (not if it was interrupted by newer speech).
     */
    fun speak(context: Context, text: String, speaker: Int, speed: Float, onDone: () -> Unit) {
        val app = context.applicationContext
        val myGen = generation.incrementAndGet()
        track?.let { runCatching { it.pause(); it.flush() } } // interrupts a blocking write in progress
        worker.execute {
            try {
                lastSamplesPlayed = 0
                envelopes.clear()
                val out = audioTrack()
                val player = Player(out, myGen)
                val cached = cacheFile(app, text, speaker, speed).takeIf { it.exists() }?.let { readPcm(it) }
                if (cached != null) {
                    player.write(cached)
                } else {
                    val engine = engine(app) ?: throw IllegalStateException("Kokoro unavailable")
                    val audio = engine.generateWithCallback(text, speaker, speed, ChunkCallback(player))
                    if (text in STOCK_PHRASES && generation.get() == myGen) writePcm(cacheFile(app, text, speaker, speed), audio.samples)
                }
                player.finish()
            } catch (e: Throwable) {
                Log.e(TAG, "speak failed", e)
                lastError = "Speaking failed: $e"
            }
            scheduleUnload()
            if (generation.get() == myGen) main.post(onDone)
        }
    }

    /** Stop speaking now. */
    fun stop() {
        generation.incrementAndGet()
        envelopes.clear()
        track?.let { runCatching { it.pause(); it.flush() } }
    }

    /** Renders the stock phrases for a voice ahead of time (after the model is downloaded or the voice changes). */
    fun prerender(context: Context, speaker: Int, speed: Float) {
        val app = context.applicationContext
        if (!isInstalled(app)) return
        worker.execute {
            for (phrase in STOCK_PHRASES) {
                val f = cacheFile(app, phrase, speaker, speed)
                if (f.exists()) continue
                val engine = engine(app) ?: return@execute
                runCatching { writePcm(f, engine.generate(phrase, speaker, speed).samples) }
            }
            scheduleUnload()
        }
    }

    /**
     * Receives each generated sentence. This must be a real class, not a lambda: sherpa-onnx's native
     * code looks up `invoke(float[]): Integer` by name, and Kotlin 2 compiles lambdas without that method
     * (calling it then fails with NoSuchMethodError and no audio plays).
     */
    private class ChunkCallback(private val player: Player) : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int = if (player.write(samples)) 1 else 0 // 0 = stop generating
    }

    /** Writes audio and keeps track of when it will have finished playing. */
    private class Player(val out: AudioTrack, val gen: Int) {
        private var endAt = 0L
        private var started = false

        /** Returns false if this speech has been interrupted. */
        fun write(samples: FloatArray): Boolean {
            if (generation.get() != gen) return false
            if (!started) { out.play(); started = true }
            recordEnvelope(maxOf(endAt, SystemClock.elapsedRealtime()), samples)
            out.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            lastSamplesPlayed += samples.size
            // Track the end time on the wall clock rather than the playback head, which is
            // unreliable across pause/flush and for clips shorter than the start threshold.
            val now = SystemClock.elapsedRealtime()
            endAt = maxOf(endAt, now) + samples.size * 1000L / SAMPLE_RATE
            return generation.get() == gen
        }

        fun finish() {
            if (!started) return
            while (generation.get() == gen && SystemClock.elapsedRealtime() < endAt + 120) Thread.sleep(20)
            if (generation.get() == gen) out.stop() // also plays out anything still buffered
        }
    }

    private fun recordEnvelope(startMs: Long, samples: FloatArray) {
        val step = SAMPLE_RATE / 50 // 20 ms
        val levels = FloatArray((samples.size + step - 1) / step) { i ->
            var sum = 0f
            val end = minOf(samples.size, (i + 1) * step)
            for (j in i * step until end) sum += samples[j] * samples[j]
            (kotlin.math.sqrt(sum / (end - i * step)) * 6f).coerceAtMost(1f)
        }
        val now = SystemClock.elapsedRealtime()
        envelopes.removeAll { it.startMs + it.levels.size * 20L < now - 1000 }
        envelopes += Envelope(startMs, levels)
    }

    private fun engine(context: Context): OfflineTts? {
        tts?.let { return it }
        if (failed) return null
        return try {
            val dir = modelDir(context)
            val t0 = SystemClock.elapsedRealtime()
            OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = File(dir, "model.int8.onnx").absolutePath,
                            voices = File(dir, "voices.bin").absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                        ),
                        numThreads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4),
                    ),
                    maxNumSentences = 1, // stream one sentence at a time
                ),
            ).also {
                tts = it
                Log.i(TAG, "loaded in ${SystemClock.elapsedRealtime() - t0} ms")
            }
        } catch (e: Throwable) { // native errors included: never crash, fall back to the system voice
            Log.e(TAG, "load failed", e)
            lastError = "Loading failed: $e"
            failed = true
            null
        }
    }

    private fun audioTrack(): AudioTrack {
        track?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, SAMPLE_RATE * 4 / 4)) // ~250 ms
            .build()
        // Start playing as soon as there is any audio, so short clips like "Yes?" aren't held back.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { t.setStartThresholdInFrames(256) }
        track = t
        return t
    }

    private fun scheduleUnload() {
        unloadTask?.cancel(false)
        unloadTask = worker.schedule({
            tts?.release(); tts = null
            track?.release(); track = null
            Log.i(TAG, "unloaded after idle")
        }, IDLE_UNLOAD_MS, TimeUnit.MILLISECONDS)
    }

    private fun cacheFile(context: Context, text: String, speaker: Int, speed: Float): File =
        File(context.cacheDir, "kokoro-phrases/${speaker}_${(speed * 100).toInt()}_${text.hashCode().toUInt()}.pcm")

    private fun writePcm(f: File, samples: FloatArray) {
        f.parentFile?.mkdirs()
        DataOutputStream(f.outputStream().buffered()).use { out ->
            out.writeInt(samples.size)
            samples.forEach { out.writeFloat(it) }
        }
    }

    private fun readPcm(f: File): FloatArray? = runCatching {
        DataInputStream(f.inputStream().buffered()).use { inp -> FloatArray(inp.readInt()) { inp.readFloat() } }
    }.getOrNull()
}
