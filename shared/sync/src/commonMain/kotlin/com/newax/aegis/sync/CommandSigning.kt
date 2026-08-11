package com.newax.aegis.sync

/**
 * Per-entry signing for targeted commands (docs/SYNC_DESIGN.md §6 — the
 * "sender identity is transport-level" deviation, closed). The sender signs
 * the command's own fields with its Ed25519 sign key; the target verifies
 * against the *paired* peer's stored public key before the TTL/allowlist/
 * policy checks. A command that fails signature verification is refused with
 * an explicit ack — a forged or replayed entry can never reach the policy
 * spine.
 *
 * The signed message is a canonical rendering of the command fields, NOT the
 * JSON payload bytes: JSONObject serialization order is not stable across
 * platforms, so signing the raw JSON would make every payload unverifiable.
 * Both sides rebuild the identical canonical bytes from the parsed fields,
 * which keeps the signature valid end-to-end through the journal.
 */
object CommandSigning {

    /** Rebuild the exact bytes the sender signed — must stay in sync with [sign]. */
    fun canonical(commandClass: String, ttlMs: Long, args: Map<String, String>): ByteArray =
        buildString {
            append("aegis-command-v1\n")
            append(commandClass)
            append('\n')
            append(ttlMs)
            args.toSortedMap().forEach { (k, v) ->
                append('\n')
                append(k)
                append('=')
                append(v)
            }
        }.encodeToByteArray()

    /** Ed25519 signature over the canonical command fields. */
    fun sign(crypto: Crypto, signPrivateKey: ByteArray, commandClass: String, ttlMs: Long, args: Map<String, String>): ByteArray =
        crypto.sign(signPrivateKey, canonical(commandClass, ttlMs, args))

    /**
     * Verify against the sender's public key. [signatureHex] is the hex of the
     * sender's signature; false on any malformed input (never throws).
     */
    fun verify(
        crypto: Crypto,
        signPublicKey: ByteArray,
        commandClass: String,
        ttlMs: Long,
        args: Map<String, String>,
        signatureHex: String?
    ): Boolean {
        if (signatureHex.isNullOrBlank()) return false
        val signature = runCatching { Hex.decode(signatureHex) }.getOrNull() ?: return false
        return crypto.verify(signPublicKey, canonical(commandClass, ttlMs, args), signature)
    }
}
