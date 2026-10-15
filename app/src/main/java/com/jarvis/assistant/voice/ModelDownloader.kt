package com.jarvis.assistant.voice

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Downloads and unpacks the on-device voice models. */
object ModelDownloader {

    /** The "Hey Jarvis" keyword model: a 17 MB download, of which only ~5 MB of int8 files are kept. */
    fun downloadWakeWord(context: Context, progress: (Int) -> Unit) {
        download(context, WakeModel.URL, WakeModel.dir(context), progress, ::openTarBz2)
        prune(WakeModel.dir(context), WakeModel.KEEP_FILES)
    }

    /** Offline speech recognition for commands (57 MB download). */
    fun downloadSpeech(context: Context, progress: (Int) -> Unit) {
        download(context, SpeechModel.URL, SpeechModel.dir(context), progress, ::openTarBz2)
        prune(SpeechModel.dir(context), SpeechModel.KEEP_FILES)
    }

    /** Deletes everything in [dir] except [keep] (sample audio, unused model variants). */
    internal fun prune(dir: File, keep: Set<String>) =
        dir.walkBottomUp().filter { it != dir && it.name !in keep }.forEach { it.delete() }

    /** The ~100 MB Kokoro voice. */
    fun downloadKokoro(context: Context, progress: (Int) -> Unit) =
        download(context, Kokoro.MODEL_URL, Kokoro.modelDir(context), progress, ::openTarBz2)

    private fun download(
        context: Context,
        url: String,
        target: File,
        progress: (Int) -> Unit,
        open: (InputStream) -> ArchiveInputStream<*>,
    ) {
        val tmp = File(context.cacheDir, target.name + ".download")
        OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            body.byteStream().use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // The last 10% of the bar covers unpacking.
                        val pct = if (total > 0) (done * 90 / total).toInt() else 0
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        }
        try {
            unpack(tmp, target, open)
        } finally {
            tmp.delete()
        }
        progress(100)
    }

    /** Unpacks [file] into [target], dropping the archive's top-level folder. */
    internal fun unpack(file: File, target: File, open: (InputStream) -> ArchiveInputStream<*>) {
        // Unpack next to the target, then swap in, so a failed unpack never leaves a half-installed model.
        val staging = File(target.parentFile, target.name + ".new").apply { deleteRecursively(); mkdirs() }
        val root = staging.canonicalPath + File.separator
        try {
            open(file.inputStream()).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    // Strip the archive's top-level folder.
                    val relative = entry.name.substringAfter('/', "")
                    if (relative.isEmpty()) continue
                    val file = File(staging, relative)
                    if (!file.canonicalPath.startsWith(root)) throw IOException("Bad archive entry ${entry.name}")
                    if (entry.isDirectory) file.mkdirs()
                    else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { archive.copyTo(it) }
                    }
                }
            }
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw IOException("Could not install model")
        } finally {
            staging.deleteRecursively()
        }
    }

    internal fun openTarBz2(input: InputStream): ArchiveInputStream<*> =
        TarArchiveInputStream(BZip2CompressorInputStream(input.buffered()))
}
