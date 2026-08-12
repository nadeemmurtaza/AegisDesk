package com.newax.aegis.sync

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.ServerSocket

/**
 * The WiFi-Direct half of the Android proximity transfer (docs/SYNC_DESIGN.md
 * §10.1, P2): a fixed-port TCP channel over a WiFi-P2P group, used by
 * [AndroidProximityDiscovery] — the RECEIVER creates the group (group owner,
 * listens on [PORT]); the SENDER discovers it by its P2P device name
 * ("aegis-<deviceId>", set via [setDeviceName] — API 30+) and joins, then
 * both sides run the shared [ProximityHandshake] + [ProximityTransfer]
 * protocol over [TcpTransferChannel].
 *
 * Failure modes are explicit callbacks (reason strings), never exceptions:
 * WiFi-Direct unavailable, API < 30 (setDeviceName does not exist), peer
 * never found, group/connect failure, socket failure.
 */
class P2pProximityChannel(private val context: Context) {

    companion object {
        /** Fixed transfer port — the group owner's listener, the client's target. */
        const val PORT = 47991

        const val DEVICE_NAME_PREFIX = "aegis-"

        private const val DISCOVER_TIMEOUT_MS = 30_000L
    }

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? =
        manager?.initialize(context, Looper.getMainLooper(), null)

    @Volatile
    private var registered = false

    /** Peer id the client is still looking for (null once found / no request). */
    @Volatile
    private var targetName: String? = null

    @Volatile
    private var connectRequested = false

    @Volatile
    private var connectCallback: ((TcpTransferChannel) -> Unit)? = null

    @Volatile
    private var errorCallback: ((String) -> Unit)? = null

    private val watchdog = Handler(Looper.getMainLooper())

    private val filter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
    }

    val available: Boolean
        get() = manager != null && channel != null

    fun deviceNameFor(deviceId: String): String = DEVICE_NAME_PREFIX + deviceId.removePrefix("dev-")

    // ── group owner (receive mode) ───────────────────────────────────────────

    /**
     * Name the P2P interface after this device and create a group, then hand
     * the ready [ServerSocket] to [onReady]. [onError] carries the named
     * failure. Only one group per device — call [stopGroupOwner] before
     * leaving receive mode.
     */
    fun startGroupOwner(deviceId: String, onReady: (ServerSocket) -> Unit, onError: (String) -> Unit) {
        val manager = manager ?: return onError("WiFi-Direct unavailable on this device")
        val channel = channel ?: return onError("WiFi-Direct unavailable on this device")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return onError("WiFi-Direct transfer requires Android 11+ (API 30) — BLE discovery still works")
        }
        setDeviceName(manager, channel, deviceNameFor(deviceId), object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                manager.createGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val server = try {
                            ServerSocket(PORT)
                        } catch (e: IOException) {
                            return onError("cannot listen on port $PORT: ${e.message}")
                        }
                        onReady(server)
                    }

                    override fun onFailure(reason: Int) = onError("createGroup failed (reason $reason)")
                })
            }

            override fun onFailure(reason: Int) = onError("setDeviceName failed (reason $reason)")
        })
    }

    /**
     * Name this device's P2P interface. [WifiP2pManager.setDeviceName] is a
     * `@hide @SystemApi` method — absent from the public SDK — so it is invoked
     * reflectively (the method exists in the framework since API 30, which the
     * callers gate on). Failure surfaces through [listener], matching the other
     * explicit-callback error paths.
     */
    private fun setDeviceName(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        deviceName: String,
        listener: WifiP2pManager.ActionListener
    ) {
        try {
            val method = WifiP2pManager::class.java.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            method.invoke(manager, channel, deviceName, listener)
        } catch (_: Exception) {
            // NoSuchMethod (pre-API-30 device or OEM removal), SecurityException
            // (hidden-API policy / system-app restriction), or the framework
            // rejecting the call — all funnel into the named-failure path.
            listener.onFailure(WifiP2pManager.ERROR)
        }
    }

    fun stopGroupOwner() {
        // The coordinator owns the ServerSocket (it must close it to unblock
        // the accept loop); this only leaves the P2P group.
        val manager = manager ?: return
        val channel = channel ?: return
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            })
        } catch (_: Exception) {
        }
    }

    // ── client (send mode) ───────────────────────────────────────────────────

    /**
     * Find the group owner whose P2P name is "aegis-<targetDeviceId>", join
     * its group, and deliver the [TcpTransferChannel] to [onChannel]
     * (invoked on a background thread — run the transfer there). [onError]
     * carries the named failure; exactly one of the two fires.
     */
    fun connectToGroupOwner(
        targetDeviceId: String,
        onChannel: (TcpTransferChannel) -> Unit,
        onError: (String) -> Unit
    ) {
        val manager = manager ?: return onError("WiFi-Direct unavailable on this device")
        val channel = channel ?: return onError("WiFi-Direct unavailable on this device")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return onError("WiFi-Direct transfer requires Android 11+ (API 30)")
        }
        targetName = deviceNameFor(targetDeviceId)
        connectRequested = false
        connectCallback = onChannel
        errorCallback = onError
        register()
        watchdog.postDelayed({
            if (targetName != null || connectRequested) {
                targetName = null
                connectRequested = false
                unregister()
                onError("peer $targetDeviceId not found via WiFi-Direct (timeout)")
            }
        }, DISCOVER_TIMEOUT_MS)
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit

            override fun onFailure(reason: Int) {
                cancelWatchdog()
                targetName = null
                connectRequested = false
                unregister()
                onError("WiFi-Direct peer discovery failed (reason $reason)")
            }
        })
    }

    // ── broadcast plumbing ───────────────────────────────────────────────────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                    manager?.requestPeers(channel) { peers -> onPeers(peers) }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                    manager?.requestConnectionInfo(channel) { info -> onConnectionInfo(info) }

                else -> Unit
            }
        }
    }

    private fun onPeers(peers: WifiP2pDeviceList) {
        val expected = targetName ?: return
        val device = peers.deviceList.firstOrNull {
            it.deviceName == expected && it.status != WifiP2pDevice.UNAVAILABLE
        } ?: return // keep waiting — the group owner may not be discoverable yet
        targetName = null
        val manager = manager ?: return
        val channel = channel ?: return
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        connectRequested = true
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit

            override fun onFailure(reason: Int) {
                cancelWatchdog()
                connectRequested = false
                unregister()
                errorCallback?.invoke("WiFi-Direct connect failed (reason $reason)")
            }
        })
    }

    private fun onConnectionInfo(info: WifiP2pInfo) {
        if (!connectRequested) return
        if (!info.groupFormed || info.groupOwnerAddress == null) return
        connectRequested = false
        val host = info.groupOwnerAddress.hostAddress
        if (host == null) {
            cancelWatchdog()
            unregister()
            errorCallback?.invoke("WiFi-Direct group owner has no address")
            return
        }
        cancelWatchdog()
        unregister()
        Thread {
            val tcp = TcpTransferChannel.connect(host, PORT)
            if (tcp == null) {
                errorCallback?.invoke("could not open the transfer socket to $host:$PORT")
            } else {
                connectCallback?.invoke(tcp)
            }
        }.apply {
            isDaemon = true
            name = "aegis-p2p-socket"
            start()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun register() {
        if (registered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        registered = false
    }

    private fun cancelWatchdog() {
        watchdog.removeCallbacksAndMessages(null)
    }
}
