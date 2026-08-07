package com.newax.aegis.engine

import kotlin.math.sqrt

/**
 * Voice authenticator using cosine similarity on Vosk SpkModel embeddings.
 *
 * Enrollment: call enroll() with the float array from Vosk's speaker embedding output.
 * Verification: pass the embedding extracted from the live audio segment to verify().
 *
 * THRESHOLD is set conservatively at 0.85 (Vosk documentation recommends 0.85–0.95).
 * Increase toward 0.95 for stricter security at the cost of higher false-reject rate.
 */
object VoiceAuthenticator {

    private const val THRESHOLD = 0.85f

    private val lock = Any()
    private var enrolledEmbedding: FloatArray? = null
    val isEnrolled: Boolean get() = synchronized(lock) { enrolledEmbedding != null }

    /** Store the user's voiceprint. Call this once during initial setup. */
    fun enroll(embedding: FloatArray) {
        require(embedding.isNotEmpty()) { "Embedding must not be empty." }
        synchronized(lock) { enrolledEmbedding = embedding.copyOf() }
    }

    fun clearEnrollment() = synchronized(lock) { enrolledEmbedding = null }

    /**
     * Verifies the speaker embedding against the enrolled voiceprint.
     * Returns false if no voiceprint is enrolled (fail-secure).
     *
     * @param liveEmbedding Float array from Vosk SpkModel.getSpeakerVector()
     */
    fun verify(liveEmbedding: FloatArray): Boolean {
        val enrolled = synchronized(lock) { enrolledEmbedding?.copyOf() }
            ?: return false
        if (liveEmbedding.size != enrolled.size) return false
        return cosineSimilarity(enrolled, liveEmbedding) >= THRESHOLD
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot  += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0.0) 0f else (dot / denom).toFloat()
    }

    /** Legacy suspend wrapper kept for call-site compatibility. Pass the live embedding. */
    suspend fun verifyIdentity(liveEmbedding: FloatArray? = null): Boolean =
        if (liveEmbedding != null) verify(liveEmbedding) else !isEnrolled
}
