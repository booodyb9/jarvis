package com.jarvis.assistant.voice

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tarBz2(vararg entries: Pair<String, String?>): File {
        val f = tmp.newFile("model.tar.bz2")
        TarArchiveOutputStream(BZip2CompressorOutputStream(f.outputStream())).use { tar ->
            for ((name, content) in entries) {
                val bytes = content?.toByteArray() ?: ByteArray(0)
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return f
    }

    @Test
    fun unpacksKokoroLayoutWithoutTopFolder() {
        val archive = tarBz2(
            "kokoro-int8-en-v0_19/" to null,
            "kokoro-int8-en-v0_19/voices.bin" to "v",
            "kokoro-int8-en-v0_19/model.int8.onnx" to "m",
            "kokoro-int8-en-v0_19/espeak-ng-data/" to null,
            "kokoro-int8-en-v0_19/espeak-ng-data/en_dict" to "d",
        )
        val target = File(tmp.root, "kokoro").apply { mkdirs(); resolve("old.txt").writeText("stale") }
        ModelDownloader.unpack(archive, target, ModelDownloader::openTarBz2)
        assertEquals("v", target.resolve("voices.bin").readText())
        assertEquals("d", target.resolve("espeak-ng-data/en_dict").readText())
        assertFalse("old install replaced", target.resolve("old.txt").exists())
        assertFalse("staging cleaned up", File(tmp.root, "kokoro.new").exists())
    }

    @Test
    fun rejectsPathTraversal() {
        val archive = tarBz2("top/../../evil.txt" to "x")
        val target = File(tmp.root, "kokoro").apply { mkdirs(); resolve("keep.txt").writeText("keep") }
        val failed = runCatching { ModelDownloader.unpack(archive, target, ModelDownloader::openTarBz2) }.isFailure
        assertTrue(failed)
        assertFalse(File(tmp.root.parentFile, "evil.txt").exists())
        assertTrue("existing install untouched", target.resolve("keep.txt").exists())
    }
}
