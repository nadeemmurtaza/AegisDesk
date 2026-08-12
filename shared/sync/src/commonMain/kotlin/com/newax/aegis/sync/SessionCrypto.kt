package com.newax.aegis.sync

/**
 * Noise-style mutual-authentication session handshake (docs/SYNC_DESIGN.md §8):
 * ephemeral X25519 keys, a triple-DH (ee, es, se), HKDF key derivation pinned
 * to the full handshake transcript, and Ed25519 signatures over the transcript
 * by each side's long-term identity. Forward secrecy: ephemerals are fresh per
 * session, so compromise of a long-term key never decrypts past sessions.
 *
 * Flow: initiatorHello → responderProcess (derives session, replies HelloR) →
 * initiatorProcess (derives matching session) → responder verifies the
 * initiator's Finished proof of key possession.
 *
 * App messages are AEAD-sealed with a 96-bit nonce = 8-byte big-endian
 * send/receive counter + 4 zero bytes, AAD = the handshake transcript
 * (channel binding). Counter 0 is consumed by the Finished proof; app messages
 * start at 1. Replay = counter mismatch = open returns null.
 */
object SessionCrypto {

    const val LABEL = "aegis-sync-session-v1"
    private val ZERO_NONCE = ByteArray(12)

    // ── wire messages ─────────────────────────────────────────────────────────

    data class HelloI(val deviceId: String, val ephemeralPublicKey: ByteArray, val signature: ByteArray)
    data class HelloR(val deviceId: String, val ephemeralPublicKey: ByteArray, val signature: ByteArray)
    data class Finished(val ciphertext: ByteArray)

    /** An established, transport-ready session. Counters are mutable state. */
    data class Session(
        val peerDeviceId: String,
        val sendKey: ByteArray,
        val receiveKey: ByteArray,
        val transcript: ByteArray,
        var sendCounter: Long = 1,
        var receiveCounter: Long = 1
    )

    sealed interface HandshakeError {
        data class InvalidSignature(val stage: String) : HandshakeError
        data class DeviceIdMismatch(val expected: String, val got: String) : HandshakeError
    }

    sealed interface ResponderResult {
        data class Success(val helloR: HelloR, val session: Session) : ResponderResult
        data class Failure(val error: HandshakeError) : ResponderResult
    }

    sealed interface InitiatorResult {
        data class Success(val session: Session) : InitiatorResult
        data class Failure(val error: HandshakeError) : InitiatorResult
    }

    // ── handshake ─────────────────────────────────────────────────────────────

    /** Step 1 — initiator: ephemeral keypair + signed HelloI. */
    fun initiatorHello(crypto: Crypto, my: StoredIdentity): Pair<KeyPair, HelloI> {
        val eph = crypto.newEcdhKeyPair()
        val transcript = Sha256.digest((LABEL + "I" + my.identity.deviceId).encodeToByteArray() + eph.publicKey)
        val signature = crypto.sign(my.signPrivateKey, transcript)
        return eph to HelloI(my.identity.deviceId, eph.publicKey, signature)
    }

    /** Step 2 — responder: verify, derive, reply HelloR + session. */
    fun responderProcess(
        crypto: Crypto,
        my: StoredIdentity,
        peer: PairedPeer,
        hello: HelloI
    ): ResponderResult {
        if (hello.deviceId != peer.deviceId) {
            return ResponderResult.Failure(HandshakeError.DeviceIdMismatch(peer.deviceId, hello.deviceId))
        }
        // t1 is the INITIATOR's transcript — its id (hello.deviceId), not mine.
        val t1 = Sha256.digest((LABEL + "I" + hello.deviceId).encodeToByteArray() + hello.ephemeralPublicKey)
        if (!crypto.verify(peer.signPublicKey, t1, hello.signature)) {
            return ResponderResult.Failure(HandshakeError.InvalidSignature("helloI"))
        }
        val myEph = crypto.newEcdhKeyPair()
        val t2 = Sha256.digest(t1 + (LABEL + "R" + my.identity.deviceId).encodeToByteArray() + myEph.publicKey)
        val dh = tripleDh(
            crypto,
            ephPriv = myEph.privateKey,
            longTermPriv = my.ecdhPrivateKey,
            peerEphPub = hello.ephemeralPublicKey,
            peerLongTermPub = peer.ecdhPublicKey
        )
        // tripleDh yields [ee, myEph·peerLT, myLT·peerEph]. As the responder the
        // last two are the initiator's (se, es) — the canonical [ee, es, se]
        // requires swapping them so both sides derive identical session keys.
        val ikm = dh.copyOfRange(0, 32) + dh.copyOfRange(64, 96) + dh.copyOfRange(32, 64)
        val keys = derive(crypto, t2, ikm, hello.deviceId, my.identity.deviceId)
        val helloR = HelloR(my.identity.deviceId, myEph.publicKey, crypto.sign(my.signPrivateKey, t2))
        return ResponderResult.Success(
            helloR = helloR,
            session = Session(
                peerDeviceId = hello.deviceId,
                sendKey = keys.responderSend,
                receiveKey = keys.initiatorSend,
                transcript = t2
            )
        )
    }

    /** Step 3 — initiator: verify HelloR, derive the matching session. */
    fun initiatorProcess(
        crypto: Crypto,
        my: StoredIdentity,
        peer: PairedPeer,
        eph: KeyPair,
        helloI: HelloI,
        helloR: HelloR
    ): InitiatorResult {
        if (helloR.deviceId != peer.deviceId) {
            return InitiatorResult.Failure(HandshakeError.DeviceIdMismatch(peer.deviceId, helloR.deviceId))
        }
        val t1 = Sha256.digest((LABEL + "I" + my.identity.deviceId).encodeToByteArray() + eph.publicKey)
        if (!crypto.verify(my.identity.signPublicKey, t1, helloI.signature)) {
            return InitiatorResult.Failure(HandshakeError.InvalidSignature("helloI"))
        }
        val t2 = Sha256.digest(t1 + (LABEL + "R" + helloR.deviceId).encodeToByteArray() + helloR.ephemeralPublicKey)
        if (!crypto.verify(peer.signPublicKey, t2, helloR.signature)) {
            return InitiatorResult.Failure(HandshakeError.InvalidSignature("helloR"))
        }
        val ikm = tripleDh(
            crypto,
            ephPriv = eph.privateKey,
            longTermPriv = my.ecdhPrivateKey,
            peerEphPub = helloR.ephemeralPublicKey,
            peerLongTermPub = peer.ecdhPublicKey
        )
        val keys = derive(crypto, t2, ikm, my.identity.deviceId, helloR.deviceId)
        return InitiatorResult.Success(
            Session(
                peerDeviceId = helloR.deviceId,
                sendKey = keys.initiatorSend,
                receiveKey = keys.responderSend,
                transcript = t2
            )
        )
    }

    /** Step 4 — initiator proves key possession: seal the transcript. */
    fun buildFinished(crypto: Crypto, session: Session): Finished =
        Finished(crypto.aeadSeal(session.sendKey, ZERO_NONCE, session.transcript, aad = session.transcript))

    /** Step 4 verify — responder checks the Finished proof (consumes slot 0). */
    fun verifyFinished(crypto: Crypto, session: Session, finished: Finished): Boolean {
        val pt = crypto.aeadOpen(session.receiveKey, ZERO_NONCE, finished.ciphertext, aad = session.transcript)
            ?: return false
        return pt.contentEquals(session.transcript)
    }

    // ── app-message framing ───────────────────────────────────────────────────

    /** Seal one app message (consumes the next send counter). */
    fun seal(crypto: Crypto, session: Session, plaintext: ByteArray): ByteArray {
        val nonce = nonceFor(session.sendCounter)
        session.sendCounter++
        return crypto.aeadSeal(session.sendKey, nonce, plaintext, aad = session.transcript)
    }

    /** Open one app message; null on tamper or replay (counter mismatch). */
    fun open(crypto: Crypto, session: Session, ciphertext: ByteArray): ByteArray? {
        val nonce = nonceFor(session.receiveCounter)
        val pt = crypto.aeadOpen(session.receiveKey, nonce, ciphertext, aad = session.transcript) ?: return null
        session.receiveCounter++
        return pt
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Triple-DH in canonical order so both sides concatenate identically:
     *   dh1 = eph · peerEph   (ee)
     *   dh2 = eph · peerLT    (es — my ephemeral, their long-term)
     *   dh3 = LT · peerEph    (se — my long-term, their ephemeral)
     *
     * The INITIATOR's output is already canonical. The RESPONDER's (eph=its
     * own ephemeral) yields [ee, se, es] — its two middle secrets are the
     * initiator's swapped — so the responder reassembles [dh1, dh3, dh2]
     * before key derivation. Only then do both sides derive identical keys.
     */
    private fun tripleDh(
        crypto: Crypto,
        ephPriv: ByteArray,
        longTermPriv: ByteArray,
        peerEphPub: ByteArray,
        peerLongTermPub: ByteArray
    ): ByteArray {
        val dh1 = crypto.ecdh(ephPriv, peerEphPub)
        val dh2 = crypto.ecdh(ephPriv, peerLongTermPub)
        val dh3 = crypto.ecdh(longTermPriv, peerEphPub)
        return dh1 + dh2 + dh3
    }

    private fun derive(
        crypto: Crypto,
        transcript: ByteArray,
        ikm: ByteArray,
        initiatorId: String,
        responderId: String
    ): Keys {
        val salt = Sha256.digest(LABEL.encodeToByteArray() + transcript)
        val okm = crypto.hkdf(ikm, salt, (initiatorId + "|" + responderId).encodeToByteArray(), 64)
        return Keys(initiatorSend = okm.copyOfRange(0, 32), responderSend = okm.copyOfRange(32, 64))
    }

    private fun nonceFor(counter: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) {
            n[7 - i] = (counter ushr (i * 8)).toByte()
        }
        return n
    }

    private data class Keys(val initiatorSend: ByteArray, val responderSend: ByteArray)
}
