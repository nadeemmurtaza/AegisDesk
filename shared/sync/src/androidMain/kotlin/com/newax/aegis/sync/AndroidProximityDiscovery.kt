package com.newax.aegis.sync

import android.content.Context
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The Android proximity actual (docs/SYNC_DESIGN.md §10.1, P2): BLE
 * advertise/scan for discovery ([BleProximityDiscovery]) and a WiFi-Direct
 * group for the bulk socket ([P2pProximityChannel]), running the SAME
 * [ProximityTransfer] protocol — with the shared [ProximityHandshake] key
 * exchange first — over [TcpTransferChannel]. mDNS
 * ([LanProximityDiscovery]) remains available on Android as the LAN fallback
 * for apps that construct it directly.
 *
 * Receive mode: this device becomes the group owner and accepts one transfer
 * at a time; [IncomingRequest] carries the user-confirmation gate (Quick
 * Share semantics) and the result is delivered to [onResult]. Send mode:
 * [sendTo] joins the peer's group, exchanges keys, and runs the transfer
 * synchronously (call from a background thread).
 */
actual fun proximityDiscovery(): ProximityDiscovery =
    AndroidProximityDiscovery(AndroidSyncContext.requireContext())

class AndroidProximityDiscovery(
    private val context: Context
) : ProximityDiscovery {

    companion object {
        private const val MAX_SEND_BYTES = 1L * 1024 * 1024 * 1024
        private const val SEND_TIMEOUT_MS = 120_000L
        private const val ACCEPT_TIMEOUT_MS = 30_000L
    }

    private val crypto: Crypto = platformCrypto()
    private val ble = BleProximityDiscovery(context)
    private val p2p = P2pProximityChannel(context)
    private val identity: StoredIdentity by lazy {
        platformKeyStore().loadIdentity()?.let { return@lazy it }
        val created = Identity.generate(crypto, "Newax " + android.os.Build.MODEL)
        platformKeyStore().saveIdentity(created)
        created
    }

    /** This device's stable id (from the keystore identity; generated on first use). */
    val deviceId: String
        get() = identity.identity.deviceId

    val displayName: String
        get() = identity.identity.displayName

    /** Latest receive-mode failure, surfaced in the UI (null when idle/healthy). */
    @Volatile
    var receiveError: String? = null
        private set

    /** Optional receive-side progress hook (set by the UI; called on the accept thread). */
    @Volatile
    var receiveProgress: ProximityTransfer.Progress = NoProgress

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    // ── discovery (BLE) ──────────────────────────────────────────────────────

    override fun startAdvertising(profile: ProximityProfile) = ble.startAdvertising(profile)

    override fun startScanning(listener: ProximityListener) = ble.startScanning(listener)

    override fun stop() {
        ble.stop()
        p2p.stopGroupOwner()
        serverSocket?.let {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        serverSocket = null
        acceptThread = null
    }

    override fun nearby(): List<ProximityEndpoint> = ble.nearby()

    /** Current BLE discovery error, if any. */
    val discoveryError: String?
        get() = ble.error

    // ── receive mode ─────────────────────────────────────────────────────────

    /**
     * Start accepting encrypted transfers: this device becomes the WiFi-Direct
     * group owner and listens for senders. [onIncoming] is called once per
     * transfer with the confirmation gate (must be answered — Accept/Decline);
     * [onResult] receives the outcome after the gate closes. Idempotent while
     * already listening.
     */
    fun startReceiving(
        onIncoming: (IncomingRequest) -> Unit,
        onResult: (ProximityTransfer.Result) -> Unit
    ) {
        if (serverSocket != null) return
        val ecdh = ecdhKey(identity)
        p2p.startGroupOwner(
            identity.identity.deviceId,
            onReady = { server ->
                serverSocket = server
                acceptThread = Thread {
                    while (!server.isClosed) {
                        val tcp = TcpTransferChannel.accept(server, ACCEPT_TIMEOUT_MS) ?: continue
                        handleIncoming(tcp, ecdh, onIncoming, onResult)
                    }
                }.apply {
                    isDaemon = true
                    name = "aegis-proximity-receive"
                    start()
                }
            },
            onError = { receiveError = it }
        )
    }

    // ── send ─────────────────────────────────────────────────────────────────

    /**
     * Send [content] as [fileName] to a nearby [endpoint] — joins the peer's
     * WiFi-Direct group, exchanges ECDH keys, and runs the encrypted transfer.
     * Synchronous: call from a background thread; returns an explicit
     * [ProximityTransfer.Result] (never throws).
     */
    fun sendTo(
        endpoint: ProximityEndpoint,
        fileName: String,
        content: ByteArray,
        progress: ProximityTransfer.Progress = NoProgress
    ): ProximityTransfer.Result {
        if (content.size.toLong() > MAX_SEND_BYTES) {
            return ProximityTransfer.Result.Failed("init", "file too large for v1 proximity transfer")
        }
        val ecdh = ecdhKey(identity)
        val latch = CountDownLatch(1)
        val result = AtomicReference<ProximityTransfer.Result>()
        val error = AtomicReference<String?>()
        p2p.connectToGroupOwner(
            targetDeviceId = endpoint.deviceId,
            onChannel = { tcp ->
                val peerKey = ProximityHandshake.exchangeKeys(tcp, ecdh.publicKey)
                if (peerKey == null) {
                    tcp.close()
                    result.set(ProximityTransfer.Result.Failed("handshake", "key exchange failed"))
                } else {
                    result.set(
                        ProximityTransfer.send(
                            crypto, tcp, ecdh, peerKey,
                            identity.identity.deviceId, fileName, content, progress
                        )
                    )
                    tcp.close()
                }
                latch.countDown()
            },
            onError = { message ->
                error.set(message)
                latch.countDown()
            }
        )
        val released = try {
            latch.await(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!released) return ProximityTransfer.Result.Failed("p2p", "timed out reaching ${endpoint.deviceId}")
        error.get()?.let { return ProximityTransfer.Result.Failed("p2p", it) }
        return result.get() ?: ProximityTransfer.Result.Failed("p2p", "no result")
    }

    // ── internals ────────────────────────────────────────────────────────────

    private fun ecdhKey(identity: StoredIdentity): KeyPair =
        KeyPair(identity.ecdhPrivateKey, identity.identity.ecdhPublicKey)

    private fun handleIncoming(
        tcp: TcpTransferChannel,
        ecdh: KeyPair,
        onIncoming: (IncomingRequest) -> Unit,
        onResult: (ProximityTransfer.Result) -> Unit
    ) {
        val peerKey = ProximityHandshake.exchangeKeys(tcp, ecdh.publicKey)
        if (peerKey == null) {
            tcp.close()
            return
        }
        val gate = TransferGate()
        val result = ProximityTransfer.receive(
            crypto, tcp, ecdh, identity.identity.deviceId,
            accept = { meta ->
                onIncoming(IncomingRequest(meta.senderDeviceId, meta.fileName, meta.sizeBytes, meta.sha256Hex, gate))
                gate.await()
            },
            progress = receiveProgress
        )
        tcp.close()
        onResult(result)
    }

    private object NoProgress : ProximityTransfer.Progress
}

/**
 * An incoming encrypted transfer awaiting the user's decision. The UI shows
 * the details and calls [answer] — until then the transfer thread blocks on
 * the confirmation gate (decline/timeout abort cleanly).
 */
class IncomingRequest internal constructor(
    val peerDeviceId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    private val gate: TransferGate
) {
    fun answer(accept: Boolean) = gate.answer(accept)
}
