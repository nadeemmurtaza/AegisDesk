package com.newax.aegis.sync

import com.newax.aegis.sync.Pairing.PairingRequest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {

    private val crypto = FakeCrypto()

    @Test
    fun requestRoundTripsThroughQr() {
        val identity = Identity.generate(crypto, "Windows").identity
        val request = Pairing.createRequest(crypto, identity)
        val decoded = PairingRequest.decode(request.encode())!!
        assertEquals(Pairing.PROTOCOL_VERSION, decoded.version)
        assertEquals("Windows", decoded.displayName)
        assertContentEquals(identity.signPublicKey, decoded.signPublicKey)
        assertContentEquals(identity.ecdhPublicKey, decoded.ecdhPublicKey)
        assertContentEquals(request.nonce, decoded.nonce)
    }

    @Test
    fun qrEscapesDisplayName() {
        val identity = Identity.generate(crypto, "Dev|A\\B").identity
        val request = Pairing.createRequest(crypto, identity)
        val decoded = PairingRequest.decode(request.encode())!!
        assertEquals("Dev|A\\B", decoded.displayName)
    }

    @Test
    fun sasMatchesOnBothRoles() {
        val initiator = Identity.generate(crypto, "Windows").identity
        val responder = Identity.generate(crypto, "Mac").identity
        val request = Pairing.createRequest(crypto, initiator)

        // Initiator: sas(myKey, theirKey, nonce); responder: sas(theirKey(from QR), myKey, nonce)
        val sasInitiator = Pairing.sas(initiator.signPublicKey, responder.signPublicKey, request.nonce)
        val sasResponder = Pairing.sas(request.signPublicKey, responder.signPublicKey, request.nonce)

        assertEquals(sasResponder, sasInitiator)
        assertEquals(6, sasInitiator.length)
        assertTrue(sasInitiator.all { it.isDigit() })
    }

    @Test
    fun sasChangesWithNonceOrKeys() {
        val a = Identity.generate(crypto, "A").identity
        val b = Identity.generate(crypto, "B").identity
        val nonce = crypto.randomBytes(16)
        val baseline = Pairing.sas(a.signPublicKey, b.signPublicKey, nonce)
        assertNotEquals(baseline, Pairing.sas(a.signPublicKey, b.signPublicKey, crypto.randomBytes(16)))
        val c = Identity.generate(crypto, "C").identity
        assertNotEquals(baseline, Pairing.sas(a.signPublicKey, c.signPublicKey, nonce))
    }

    @Test
    fun garbageQrDecodesToNull() {
        assertNull(PairingRequest.decode(""))
        assertNull(PairingRequest.decode("not-a-pairing"))
        assertNull(PairingRequest.decode("aegis-pair-v1|1|name|zz|zz|zz"))          // bad hex
        assertNull(PairingRequest.decode("aegis-pair-v1|1|name|00|00"))             // wrong field count
        assertNull(PairingRequest.decode("aegis-pair-v1|9|name|00|00|00"))          // unsupported version
        assertNull(PairingRequest.decode("aegis-pair-v1|1|name|00|00|00\\"))       // dangling escape
    }

    @Test
    fun confirmResponderBuildsPeer() {
        val initiator = Identity.generate(crypto, "Windows").identity
        val responder = Identity.generate(crypto, "Mac").identity
        val request = Pairing.createRequest(crypto, initiator)

        val peer = Pairing.confirmResponder(request, myDeviceId = responder.deviceId, mySignPublicKey = responder.signPublicKey, nowMs = 5)
        assertEquals(initiator.deviceId, peer.deviceId)
        assertEquals("Windows", peer.displayName)
        assertContentEquals(initiator.signPublicKey, peer.signPublicKey)
        assertContentEquals(initiator.ecdhPublicKey, peer.ecdhPublicKey)
    }

    @Test
    fun confirmRejectsSelfPairing() {
        val identity = Identity.generate(crypto, "A").identity
        val request = Pairing.createRequest(crypto, identity)
        // Device id derived from the same keys == my device id → must throw.
        val selfDeviceId = "dev-" + Hex.encode(Sha256.digest(identity.signPublicKey + identity.ecdhPublicKey)).take(10)
        val threw = try {
            Pairing.confirmResponder(request, myDeviceId = selfDeviceId, mySignPublicKey = identity.signPublicKey, nowMs = 0)
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(threw)
    }
}
