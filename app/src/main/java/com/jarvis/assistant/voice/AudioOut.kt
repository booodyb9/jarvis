package com.jarvis.assistant.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * The speaker output shared by every voice that produces raw audio (Kokoro and the cloud voices):
 * one 24 kHz stream, with interruption and a loudness timeline the island can follow.
 *
 * Usage: call [interrupt] on the calling thread (it stops whatever is playing and returns a ticket),
 * then on a worker thread create a [Playback] with that ticket and [Playback.write] audio into it.
 * A newer [interrupt] or [stop] makes older playbacks refuse further audio.
 */
object AudioOut {
    const val SAMPLE_RATE = 24000

    private val generation = AtomicInteger()
    @Volatile private var track: AudioTrack? = null

    /** Diagnostics: samples sent to the speaker for the most recent utterance. */
    @Volatile var lastSamplesPlayed = 0L
        private set

    /** Loudness of queued speech over time (one value per 20 ms). */
    private class Envelope(val startMs: Long, val levels: FloatArray)
    private val envelopes = CopyOnWriteArrayList<Envelope>()

    /** Stops anything playing and returns a ticket for the next utterance. */
    fun interrupt(): Int {
        val gen = generation.incrementAndGet()
        envelopes.clear()
        track?.let { runCatching { it.pause(); it.flush() } } // also interrupts a blocking write
        return gen
    }

    fun stop() { interrupt() }

    fun isCurrent(ticket: Int) = generation.get() == ticket

    /** How loud the speech playing right now is, 0..1. */
    fun level(): Float {
        val now = SystemClock.elapsedRealtime()
        for (e in envelopes) {
            val i = ((now - e.startMs) / 20).toInt()
            if (i in e.levels.indices) return e.levels[i]
        }
        return 0f
    }

    /** Frees the speaker; it's recreated on the next utterance. */
    fun release() {
        track?.release()
        track = null
    }

    /** One utterance. Not thread-safe: use it from a single worker thread. */
    class Playback(private val ticket: Int) {
        private var endAt = 0L
        private var started = false
        private val out = audioTrack()

        init { if (isCurrent(ticket)) lastSamplesPlayed = 0 }

        /** Plays [samples] (24 kHz mono floats). Returns false once this speech has been interrupted. */
        fun write(samples: FloatArray): Boolean {
            if (!isCurrent(ticket)) return false
            if (samples.isEmpty()) return true
            if (!started) { out.play(); started = true }
            recordEnvelope(maxOf(endAt, SystemClock.elapsedRealtime()), samples)
            out.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            lastSamplesPlayed += samples.size
            // Track the end time on the wall clock rather than the playback head, which is
            // unreliable across pause/flush and for clips shorter than the start threshold.
            val now = SystemClock.elapsedRealtime()
            endAt = maxOf(endAt, now) + samples.size * 1000L / SAMPLE_RATE
            return isCurrent(ticket)
        }

        /** Plays 16-bit little-endian PCM at [rate] Hz, resampled to the speaker's rate. */
        fun writePcm16(bytes: ByteArray, length: Int, rate: Int): Boolean {
            val n = length / 2
            val samples = FloatArray(n) { i ->
                ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
            }
            return write(if (rate == SAMPLE_RATE) samples else resample(samples, rate))
        }

        /** Waits until everything written has been heard (or the speech is interrupted). */
        fun finish() {
            if (!started) return
            while (isCurrent(ticket) && SystemClock.elapsedRealtime() < endAt + 120) Thread.sleep(20)
            if (isCurrent(ticket)) out.stop() // also plays out anything still buffered
        }
    }

    private fun resample(x: FloatArray, from: Int): FloatArray {
        val ratio = from.toDouble() / SAMPLE_RATE
        val n = (x.size / ratio).toInt()
        return FloatArray(n) { i ->
            val pos = i * ratio
            val j = pos.toInt()
            val f = (pos - j).toFloat()
            val a = x[j.coerceAtMost(x.size - 1)]
            val b = x[(j + 1).coerceAtMost(x.size - 1)]
            a + (b - a) * f
        }
    }

    private fun recordEnvelope(startMs: Long, samples: FloatArray) {
        val step = SAMPLE_RATE / 50 // 20 ms
        val levels = FloatArray((samples.size + step - 1) / step) { i ->
            var sum = 0f
            val end = minOf(samples.size, (i + 1) * step)
            for (j in i * step until end) sum += samples[j] * samples[j]
            (sqrt(sum / (end - i * step)) * 6f).coerceAtMost(1f)
        }
        val now = SystemClock.elapsedRealtime()
        envelopes.removeAll { it.startMs + it.levels.size * 20L < now - 1000 }
        envelopes += Envelope(startMs, levels)
    }

    @Synchronized
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
}
