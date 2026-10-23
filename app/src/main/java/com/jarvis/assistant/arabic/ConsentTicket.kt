package com.jarvis.assistant.arabic

/** Native, expiring, single-use approval. Model output cannot mint or consume consent. */
class ConsentTicket(private val proof: String, private val revision: Long, private val expiresAt: Long) {
    private var used = false
    @Synchronized fun consume(currentProof: String, currentRevision: Long, now: Long): Boolean {
        if (used) return false
        used = true
        return now < expiresAt && currentProof == proof && currentRevision == revision
    }
    @Synchronized fun revoke() { used = true }
}
