package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionCryptoTest {

    private val crypto = FakeCrypto()

    private class Parties(crypto: Crypto) {
        val initiator = Identity.generate(crypto, "Windows")
        val responder = Identity.generate(crypto, "Mac")

        val initPeer = PairedPeer(
            responder.identity.deviceId, "Mac",
            responder.identity.signPublicKey, responder.identity.ecdhPublicKey, 1
        )
        val respPeer = PairedPeer(
            initiator.identity.deviceId, "Windows",
            initiator.identity.signPublicKey, initiator.identity.ecdhPublicKey, 1
        )
    }

    private fun handshake(crypto: Crypto, p: Parties): Pair<SessionCrypto.Session, SessionCrypto.Session> {
        val (eph, helloI) = SessionCrypto.initiatorHello(crypto, p.initiator)
        val resp = assertIs<SessionCrypto.ResponderResult.Success>(
            SessionCrypto.responderProcess(crypto, p.responder, p.respPeer, helloI)
        )
        val init = assertIs<SessionCrypto.InitiatorResult.Success>(
            SessionCrypto.initiatorProcess(crypto, p.initiator, p.initPeer, eph, helloI, resp.helloR)
        )
        return init.session to resp.session
    }

    @Test
    fun handshakeRoundTripDerivesMatchingSessions() {
        val (initSession, respSession) = handshake(crypto, Parties(crypto))

        // Key cross-consistency: initiator's send == responder's receive, and vice versa.
        assertContentEquals(initSession.sendKey, respSession.receiveKey)
        assertContentEquals(respSession.sendKey, initSession.receiveKey)

        // Finished proof of key possession verifies.
        val finished = SessionCrypto.buildFinished(crypto, initSession)
        assertTrue(SessionCrypto.verifyFinished(crypto, respSession, finished))

        // App messages both ways.
        val toResp = SessionCrypto.seal(crypto, initSession, "hello".encodeToByteArray())
        assertContentEquals("hello".encodeToByteArray(), SessionCrypto.open(crypto, respSession, toResp)!!)
        val toInit = SessionCrypto.seal(crypto, respSession, "reply".encodeToByteArray())
        assertContentEquals("reply".encodeToByteArray(), SessionCrypto.open(crypto, initSession, toInit)!!)
    }

    @Test
    fun tamperedCiphertextFailsOpen() {
        val (initSession, respSession) = handshake(crypto, Parties(crypto))
        val ct = SessionCrypto.seal(crypto, initSession, "secret".encodeToByteArray())
        val tampered = ct.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        assertNull(SessionCrypto.open(crypto, respSession, tampered))
    }

    @Test
    fun replayedMessageFailsOpen() {
        val (initSession, respSession) = handshake(crypto, Parties(crypto))
        val ct = SessionCrypto.seal(crypto, initSession, "once".encodeToByteArray())
        assertContentEquals("once".encodeToByteArray(), SessionCrypto.open(crypto, respSession, ct)!!)
        assertNull(SessionCrypto.open(crypto, respSession, ct)) // replay: counter already consumed
    }

    @Test
    fun wrongPeerFailsHandshake() {
        val p = Parties(crypto)
        val (_, helloI) = SessionCrypto.initiatorHello(crypto, p.initiator)
        val impostor = PairedPeer(
            "dev-ffffffff", "Impostor",
            p.initiator.identity.signPublicKey, p.initiator.identity.ecdhPublicKey, 1
        )
        val result = SessionCrypto.responderProcess(crypto, p.responder, impostor, helloI)
        assertIs<SessionCrypto.ResponderResult.Failure>(result)
        assertIs<SessionCrypto.HandshakeError.DeviceIdMismatch>(result.error)
    }

    @Test
    fun tamperedHelloSignatureFailsHandshake() {
        val p = Parties(crypto)
        val (_, helloI) = SessionCrypto.initiatorHello(crypto, p.initiator)
        val forged = helloI.copy(signature = helloI.signature.copyOf().also { it[0] = (it[0].toInt() xor 0xFF).toByte() })
        val result = SessionCrypto.responderProcess(crypto, p.responder, p.respPeer, forged)
        assertIs<SessionCrypto.ResponderResult.Failure>(result)
        assertIs<SessionCrypto.HandshakeError.InvalidSignature>(result.error)
    }

    @Test
    fun eachSessionUsesFreshEphemerals() {
        // Distinct crypto states → distinct session keys (forward secrecy).
        val keyA = handshake(FakeCrypto(100), Parties(FakeCrypto(100))).first.sendKey
        val keyB = handshake(FakeCrypto(200), Parties(FakeCrypto(200))).first.sendKey
        assertNotEquals(keyA.toList(), keyB.toList())
    }

    @Test
    fun countersAdvanceIndependently() {
        val (initSession, respSession) = handshake(crypto, Parties(crypto))
        assertEquals(1L, initSession.sendCounter)
        assertEquals(1L, respSession.receiveCounter)
        repeat(3) { SessionCrypto.seal(crypto, initSession, byteArrayOf(0)) }
        assertEquals(4L, initSession.sendCounter)
        val cts = (0 until 3).map { SessionCrypto.seal(crypto, respSession, byteArrayOf(1)) }
        cts.forEach { assertTrue(SessionCrypto.open(crypto, initSession, it) != null) }
        // Garbage Finished proof never verifies.
        assertTrue(!SessionCrypto.verifyFinished(crypto, respSession, SessionCrypto.Finished(ByteArray(0))))
    }
}
