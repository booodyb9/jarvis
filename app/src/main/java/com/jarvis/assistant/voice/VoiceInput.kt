package com.jarvis.assistant.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/** The "Hey Jarvis" keyword model: a 17 MB download, of which only ~5 MB of int8 files are kept. */
object WakeModel {
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
    const val ENCODER = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val DECODER = "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    const val JOINER = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
    val KEEP_FILES = setOf(ENCODER, DECODER, JOINER, "tokens.txt")
    /** BPE tokens from the model's bpe.model, with per-keyword boost (:) and threshold (#). Tuned: 18/18 hits, 0/60 false alarms. */
    const val KEYWORDS = "▁HE Y ▁JA R VI S :2.0 #0.1 @HEY_JARVIS\n▁JA R VI S :2.0 #0.15 @JARVIS\n"
    fun dir(context: Context) = File(context.filesDir, "kws")
    fun isInstalled(context: Context) = KEEP_FILES.all { File(dir(context), it).exists() }
}

/** Offline English speech recognition for commands (Kroko streaming zipformer, ~70 MB). */
object SpeechModel {
    const val URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2"
    val KEEP_FILES = setOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
    fun dir(context: Context) = File(context.filesDir, "asr")
    fun isInstalled(context: Context) = KEEP_FILES.all { File(dir(context), it).exists() }
}

/**
 * Owns the microphone. Listens for the wake word, then transcribes the command from the same
 * audio stream, so nothing is lost between "Hey Jarvis" and what follows. Everything runs on
 * the phone. Callbacks are delivered on the main thread.
 */
class VoiceInput(private val context: Context, private val onWake: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var spotter: KeywordSpotter? = null
    @Volatile private var recognizer: OnlineRecognizer? = null
    @Volatile private var running = false
    /** Set by [release]; a model load still in progress must not bring the microphone back. */
    @Volatile private var released = false
    private var thread: Thread? = null

    /** While true, microphone audio is ignored (Jarvis is talking and mustn't hear itself). */
    @Volatile var muted = false

    @Volatile var wakeEnabled = true

    // Command state, touched by the audio thread and main thread.
    private val lock = Any()
    private var command: CommandSession? = null

    /** Text recognised so far in the current command, or "" when not transcribing. */
    val partialText: String get() = synchronized(lock) { command?.text.orEmpty() }

    /**
     * True once the user has made speech-like sound since the wake word. Unlike [partialText] this
     * reacts instantly (recognition lags ~0.5 s behind), so it's what decides whether to say "Yes?".
     */
    @Volatile var speechSinceWake = false
        private set

    // Background noise level, learned while waiting for the wake word, so "speech" adapts to the room.
    @Volatile private var noiseFloor = 0.005f
    // Audio heard between the wake word and the command session starting, so no words are lost.
    private val afterWake = ArrayList<FloatArray>()
    private var capturing = false
    // The last 1.5 s heard while waiting for the wake word. The keyword is only confirmed a moment
    // after it's said, so this is replayed into the transcriber to catch words said straight after it.
    private val recent = ArrayDeque<FloatArray>()

    val canTranscribe get() = recognizer != null
    val hasWakeWord get() = spotter != null

    /** Loads whichever models are installed. Calls [ready] on the main thread with an error message or null. */
    fun load(ready: (error: String?) -> Unit) {
        if (released) return
        Thread {
            val errors = mutableListOf<String>()
            if (spotter == null && WakeModel.isInstalled(context)) {
                try {
                    val dir = WakeModel.dir(context)
                    File(dir, "jarvis_keywords.txt").writeText(WakeModel.KEYWORDS)
                    spotter = KeywordSpotter(
                        config = KeywordSpotterConfig(
                            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                            modelConfig = OnlineModelConfig(
                                transducer = OnlineTransducerModelConfig(
                                    encoder = File(dir, WakeModel.ENCODER).absolutePath,
                                    decoder = File(dir, WakeModel.DECODER).absolutePath,
                                    joiner = File(dir, WakeModel.JOINER).absolutePath,
                                ),
                                tokens = File(dir, "tokens.txt").absolutePath,
                                numThreads = 1,
                            ),
                            keywordsFile = File(dir, "jarvis_keywords.txt").absolutePath,
                            keywordsScore = 2.0f,
                            keywordsThreshold = 0.1f,
                        ),
                    )
                } catch (e: Throwable) { // native errors included: report, never crash
                    Log.e(TAG, "wake word model failed to load", e)
                    errors += "The wake word couldn't start. Try downloading it again in Settings."
                }
            }
            if (recognizer == null && SpeechModel.isInstalled(context)) {
                try {
                    val dir = SpeechModel.dir(context)
                    recognizer = OnlineRecognizer(
                        config = OnlineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                            modelConfig = OnlineModelConfig(
                                transducer = OnlineTransducerModelConfig(
                                    encoder = File(dir, "encoder.onnx").absolutePath,
                                    decoder = File(dir, "decoder.onnx").absolutePath,
                                    joiner = File(dir, "joiner.onnx").absolutePath,
                                ),
                                tokens = File(dir, "tokens.txt").absolutePath,
                                numThreads = 2,
                            ),
                            enableEndpoint = true,
                            endpointConfig = EndpointConfig(
                                rule1 = EndpointRule(false, 2.4f, 0f),
                                rule2 = EndpointRule(true, 1.2f, 0f),
                                rule3 = EndpointRule(false, 0f, 20f),
                            ),
                        ),
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "speech model failed to load", e)
                    errors += "Offline speech recognition couldn't start; using Google's instead."
                }
            }
            if (released) {
                // Turned off while loading: free what was just loaded and stay quiet.
                spotter?.release(); spotter = null
                recognizer?.release(); recognizer = null
                return@Thread
            }
            main.post { if (!released) ready(errors.firstOrNull()) }
        }.start()
    }

    /** Opens the microphone and starts listening (for the wake word, and for commands when asked). */
    fun start() {
        if (released || running || (spotter == null && recognizer == null)) return
        running = true
        thread = Thread({ loop() }, "jarvis-mic").apply { start() }
    }

    /** Closes the microphone (e.g. so Google's recognizer can use it). */
    fun stop() {
        running = false
        thread?.let { if (it !== Thread.currentThread()) it.join(1500) }
        thread = null
    }

    fun release() {
        released = true
        stop()
        cancelCommand()
        spotter?.release(); spotter = null
        recognizer?.release(); recognizer = null
    }

    /**
     * Transcribe what the user says next. [onResult] gets the text, or null if nothing was said
     * within [silenceTimeoutMs]. Requires the speech model ([canTranscribe]).
     */
    fun listenForCommand(silenceTimeoutMs: Long = 7000, onResult: (String?) -> Unit) {
        val asr = recognizer ?: run { onResult(null); return }
        synchronized(lock) {
            command?.stream?.release()
            command = CommandSession(asr.createStream(""), silenceTimeoutMs, onResult).apply {
                pending.addAll(afterWake) // replay what was said right after the wake word
            }
            afterWake.clear()
            capturing = false
        }
        start()
    }

    fun cancelCommand() {
        synchronized(lock) { command?.stream?.release(); command = null; capturing = false; afterWake.clear() }
    }

    private class CommandSession(val stream: OnlineStream, val silenceTimeoutMs: Long, val onResult: (String?) -> Unit) {
        val pending = ArrayList<FloatArray>()
        var text = ""
        var samples = 0L
        var lastChangeAt = 0L
    }

    private fun loop() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "no microphone permission")
            running = false
            return
        }
        var record: AudioRecord? = null
        var wakeStream: OnlineStream? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE / 5 * 2),
            )
            record.startRecording()
            val pcm = ShortArray(SAMPLE_RATE / 10) // 100 ms
            while (running) {
                val n = record.read(pcm, 0, pcm.size)
                if (n <= 0) { if (n < 0) Thread.sleep(20); continue }
                if (muted) continue
                val samples = FloatArray(n) { pcm[it] / 32768f }
                val level = rms(samples)
                if (level > maxOf(MIN_SPEECH_LEVEL, noiseFloor * 3)) speechSinceWake = true

                val (session, backlog) = synchronized(lock) {
                    val c = command
                    if (c == null && capturing) {
                        if (afterWake.size < 50) afterWake += samples // up to 5 s
                        return@synchronized null to null
                    }
                    c to c?.pending?.toList()?.also { c.pending.clear() }
                }
                if (session != null) {
                    backlog?.forEach { feedCommand(session, it) }
                    feedCommand(session, samples)
                    continue
                }
                if (capturing) continue
                noiseFloor = if (level < 0.05f) noiseFloor * 0.95f + level * 0.05f else noiseFloor
                recent.addLast(samples)
                while (recent.size > 15) recent.removeFirst()
                val kws = spotter
                if (kws != null && wakeEnabled) {
                    val stream = wakeStream ?: kws.createStream("").also { wakeStream = it }
                    stream.acceptWaveform(samples, SAMPLE_RATE)
                    while (kws.isReady(stream)) {
                        kws.decode(stream)
                        if (kws.getResult(stream).keyword.isNotBlank()) {
                            kws.reset(stream)
                            synchronized(lock) {
                                afterWake.clear()
                                afterWake.addAll(recent) // includes "Jarvis" itself; clean() strips it from the text
                                capturing = recognizer != null
                            }
                            recent.clear()
                            speechSinceWake = false
                            main.post { onWake() }
                            break
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "microphone loop failed", e)
            val session = synchronized(lock) { command.also { command = null } }
            session?.let { s -> main.post { s.onResult(null) } }
        } finally {
            runCatching { record?.stop() }
            record?.release()
            wakeStream?.release()
        }
    }

    private fun feedCommand(session: CommandSession, samples: FloatArray) {
        val asr = recognizer ?: return
        session.stream.acceptWaveform(samples, SAMPLE_RATE)
        while (asr.isReady(session.stream)) asr.decode(session.stream)
        session.samples += samples.size
        val text = clean(asr.getResult(session.stream).text)
        if (text != session.text) {
            synchronized(lock) { session.text = text }
            session.lastChangeAt = session.samples
        }
        val ms = { s: Long -> s * 1000 / SAMPLE_RATE }
        val quietFor = ms(session.samples - session.lastChangeAt)
        val done = when {
            text.isEmpty() -> ms(session.samples) >= session.silenceTimeoutMs        // nobody spoke
            asr.isEndpoint(session.stream) -> true                                   // model heard the end
            quietFor >= END_OF_SPEECH_MS -> true                                     // words stopped changing
            ms(session.samples) >= MAX_COMMAND_MS -> true
            else -> false
        }
        if (!done) return
        synchronized(lock) { if (command === session) command = null }
        session.stream.release()
        val result = text.ifEmpty { null }
        main.post { session.onResult(result) }
    }

    companion object {
        private const val TAG = "VoiceInput"
        const val SAMPLE_RATE = 16000
        private const val END_OF_SPEECH_MS = 1300L
        private const val MIN_SPEECH_LEVEL = 0.02f

        private fun rms(x: FloatArray): Float {
            var sum = 0.0
            for (v in x) sum += v * v
            return Math.sqrt(sum / x.size.coerceAtLeast(1)).toFloat()
        }
        private const val MAX_COMMAND_MS = 20_000L

        private val LEADING_YES = Regex("^(?i)yes[,.!?]*\\s+")

        /**
         * Removes the wake word (and anything before it) from the front of a transcript, plus Jarvis's
         * own "Yes?" prompt. The recogniser spells the wake word inconsistently ("Jarvis", "Arvis",
         * "Page Arvis"), so any of the first three words within one letter of "jarvis" counts.
         */
        fun clean(raw: String): String {
            val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            var cut = 0
            for (i in 0 until minOf(3, words.size)) {
                val one = letters(words[i])
                val two = if (i + 1 < words.size) one + letters(words[i + 1]) else ""
                when {
                    one.length >= 4 && editDistance(one, "jarvis") <= 1 -> { cut = i + 1; break }
                    two.length >= 5 && editDistance(two, "jarvis") <= 1 -> { cut = i + 2; break } // "jar vis"
                }
            }
            val rest = words.drop(cut).joinToString(" ").trimStart(',', '.', '!', '?', ' ')
            return LEADING_YES.replace(rest, "").trim().replaceFirstChar { it.uppercase() }
        }

        private fun letters(w: String) = w.lowercase().filter { it.isLetter() }

        private fun editDistance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[b.length]
        }
    }
}
