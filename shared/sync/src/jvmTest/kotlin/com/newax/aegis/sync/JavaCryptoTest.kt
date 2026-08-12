package com.newax.aegis.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the REAL platform crypto (JDK Ed25519/X25519/AES-GCM) through the
 * exact protocol paths the engine will run in production. Runs only on a
 * JDK 15+ / Android 12+ runtime.
 */
class JavaCryptoTest {

    private val crypto = platformCrypto() // the expect/actual seam

    @Test
    fun signVerifyRoundTrip() {
        val kp = crypto.newSignKeyPair()
        val message = "journal entry".encodeToByteArray()
        val sig = crypto.sign(kp.privateKey, message)
        assertTrue(crypto.verify(kp.publicKey, message, sig))
        assertFalse(crypto.verify(kp.publicKey, "tampered".encodeToByteArray(), sig))
        assertFalse(crypto.verify(ByteArray(64), message, sig)) // garbage key
    }

    @Test
    fun ecdhIsSymmetric() {
        val a = crypto.newEcdhKeyPair()
        val b = crypto.newEcdhKeyPair()
        assertContentEquals(crypto.ecdh(a.privateKey, b.publicKey), crypto.ecdh(b.privateKey, a.publicKey))
        assertEquals(32, crypto.ecdh(a.privateKey, b.publicKey).size)
    }

    @Test
    fun aeadRoundTripAndTamper() {
        val key = crypto.randomBytes(32)
        val nonce = crypto.randomBytes(12)
        val ct = crypto.aeadSeal(key, nonce, "payload".encodeToByteArray(), aad = "ctx".encodeToByteArray())
        assertContentEquals("payload".encodeToByteArray(), crypto.aeadOpen(key, nonce, ct, "ctx".encodeToByteArray())!!)
        assertNull(crypto.aeadOpen(key, nonce, ct, "wrong-aad".encodeToByteArray()))
        val tampered = ct.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(crypto.aeadOpen(key, nonce, tampered, "ctx".encodeToByteArray()))
        assertNull(crypto.aeadOpen(key, crypto.randomBytes(12), ct, "ctx".encodeToByteArray()))
    }

    @Test
    fun fullHandshakeWithRealCrypto() {
        val initiator = Identity.generate(crypto, "Windows")
        val responder = Identity.generate(crypto, "Mac")
        val initPeer = PairedPeer(responder.identity.deviceId, "Mac",
            responder.identity.signPublicKey, responder.identity.ecdhPublicKey, 1)
        val respPeer = PairedPeer(initiator.identity.deviceId, "Windows",
            initiator.identity.signPublicKey, initiator.identity.ecdhPublicKey, 1)

        val (eph, helloI) = SessionCrypto.initiatorHello(crypto, initiator)
        val resp = assertIs<SessionCrypto.ResponderResult.Success>(
            SessionCrypto.responderProcess(crypto, responder, respPeer, helloI)
        )
        val init = assertIs<SessionCrypto.InitiatorResult.Success>(
            SessionCrypto.initiatorProcess(crypto, initiator, initPeer, eph, helloI, resp.helloR)
        )
        val finished = SessionCrypto.buildFinished(crypto, init.session)
        assertTrue(SessionCrypto.verifyFinished(crypto, resp.session, finished))

        val ct = SessionCrypto.seal(crypto, init.session, "over the wire".encodeToByteArray())
        assertContentEquals("over the wire".encodeToByteArray(), SessionCrypto.open(crypto, resp.session, ct)!!)
    }

    @Test
    fun blobWrapWithRealCrypto() {
        val bob = Identity.generate(crypto, "B")
        val blob = BlobCrypto.seal(crypto, "file chunk".encodeToByteArray(), bob.identity.ecdhPublicKey)
        assertContentEquals("file chunk".encodeToByteArray(), BlobCrypto.open(crypto, blob, bob.ecdhPrivateKey)!!)
        assertNull(BlobCrypto.open(crypto, blob, Identity.generate(crypto, "C").ecdhPrivateKey))
    }

    @Test
    fun pairingSasWithRealCrypto() {
        val a = Identity.generate(crypto, "A").identity
        val b = Identity.generate(crypto, "B").identity
        val request = Pairing.createRequest(crypto, a)
        assertEquals(
            Pairing.sas(a.signPublicKey, b.signPublicKey, request.nonce),
            Pairing.sas(request.signPublicKey, b.signPublicKey, request.nonce)
        )
        assertTrue(Pairing.sas(a.signPublicKey, b.signPublicKey, request.nonce).length == 6)
    }
}
