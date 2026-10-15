package com.jarvis.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanTranscriptTest {
    private fun c(s: String) = VoiceInput.clean(s)

    @Test fun stripsWakeWordVariants() {
        assertEquals("Send my wife a message", c("hey jarvis send my wife a message"))
        assertEquals("Turn on the flashlight", c("Page Arvis turn on the flashlight"))
        assertEquals("Open YouTube", c("Jarvis, open YouTube"))
        assertEquals("Call mum", c("okay hey jar vis call mum"))
        assertEquals("", c("Hey Jervis."))
    }

    @Test fun stripsOwnPrompt() {
        assertEquals("Open settings", c("Jarvis yes? open settings"))
        assertEquals("Open settings", c("yes, open settings"))
    }

    @Test fun leavesRealCommandsAlone() {
        assertEquals("Text Travis that I'm late", c("text Travis that I'm late"))
        assertEquals("Turn on the flashlight", c("turn on the flashlight"))
        assertEquals("Play the harvest playlist", c("play the harvest playlist"))
    }
}
