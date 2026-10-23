package com.jarvis.assistant.arabic

import org.junit.Assert.*
import org.junit.Test

class ConsentTicketTest {
    @Test fun approvalIsOneUseEvenAfterDoubleTap() {
        val t = ConsentTicket("screen-A", 7, 1000)
        assertTrue(t.consume("screen-A", 7, 999))
        assertFalse(t.consume("screen-A", 7, 999))
    }
    @Test fun movedTargetAndChangedScreensInvalidateApproval() {
        val t = ConsentTicket("target-at-100", 7, 1000)
        assertFalse(t.consume("target-at-120", 7, 900))
        // Returning to the old position cannot restore the consumed approval.
        assertFalse(t.consume("target-at-100", 7, 901))
    }
    @Test fun screenChangeThenReturnStillRequiresFreshApproval() {
        assertFalse(ConsentTicket("screen", 7, 1000).consume("screen", 9, 900))
    }
    @Test fun expiryAndStopDenyExecution() {
        assertFalse(ConsentTicket("screen", 1, 1000).consume("screen", 1, 1000))
        val t = ConsentTicket("screen", 1, 1000)
        t.revoke()
        assertFalse(t.consume("screen", 1, 900))
    }
    @Test fun simultaneousApprovalsExecuteOnlyOnce() {
        val t = ConsentTicket("screen", 1, 1000)
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..20).map { Thread { if (t.consume("screen", 1, 900)) winners.incrementAndGet() } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(1, winners.get())
    }
}
