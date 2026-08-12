package com.newax.aegis.engine.dev.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class NetworkSnapshot(
    val timestampMs: Long,
    val isConnected: Boolean,
    val isWifi: Boolean,
    val isCellular: Boolean,
    val isVpn: Boolean,
    val downstreamBandwidthKbps: Int,
    val upstreamBandwidthKbps: Int,
    val rxBytesDelta: Long,
    val txBytesDelta: Long,
    val rxPacketsDelta: Long,
    val txPacketsDelta: Long,
    val networkType: String
)

data class NetworkRequest(
    val id: Long,
    val timestampMs: Long,
    val method: String,
    val url: String,
    val statusCode: Int,
    val responseTimeMs: Long,
    val responseSizeBytes: Long,
    val error: String? = null
)

object NetworkInspector {

    private val requestLog = CopyOnWriteArrayList<NetworkRequest>()
    private const val MAX_REQUESTS = 500
    private var requestIdCounter = AtomicLong(0)
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastRxPackets = 0L
    private var lastTxPackets = 0L

    fun snapshot(context: Context): NetworkSnapshot {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }

        val rxBytes = TrafficStats.getTotalRxBytes()
        val txBytes = TrafficStats.getTotalTxBytes()
        val rxPackets = TrafficStats.getTotalRxPackets()
        val txPackets = TrafficStats.getTotalTxPackets()

        val rxDelta = if (lastRxBytes > 0) rxBytes - lastRxBytes else 0L
        val txDelta = if (lastTxBytes > 0) txBytes - lastTxBytes else 0L
        val rxPktDelta = if (lastRxPackets > 0) rxPackets - lastRxPackets else 0L
        val txPktDelta = if (lastTxPackets > 0) txPackets - lastTxPackets else 0L

        lastRxBytes = rxBytes
        lastTxBytes = txBytes
        lastRxPackets = rxPackets
        lastTxPackets = txPackets

        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

        return NetworkSnapshot(
            timestampMs = System.currentTimeMillis(),
            isConnected = caps != null,
            isWifi = isWifi,
            isCellular = isCellular,
            isVpn = isVpn,
            downstreamBandwidthKbps = caps?.linkDownstreamBandwidthKbps ?: 0,
            upstreamBandwidthKbps = caps?.linkUpstreamBandwidthKbps ?: 0,
            rxBytesDelta = rxDelta,
            txBytesDelta = txDelta,
            rxPacketsDelta = rxPktDelta,
            txPacketsDelta = txPktDelta,
            networkType = when {
                isVpn -> "VPN"
                isWifi -> "WIFI"
                isCellular -> "CELLULAR"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                else -> "NONE"
            }
        )
    }

    fun recordRequest(
        method: String,
        url: String,
        statusCode: Int,
        responseTimeMs: Long,
        responseSizeBytes: Long,
        error: String? = null
    ): Long {
        val id = requestIdCounter.incrementAndGet()
        requestLog.add(NetworkRequest(id, System.currentTimeMillis(), method, url, statusCode, responseTimeMs, responseSizeBytes, error))
        if (requestLog.size > MAX_REQUESTS) requestLog.removeAt(0)
        return id
    }

    fun recentRequests(n: Int = 50): List<NetworkRequest> = requestLog.takeLast(n)
    fun failedRequests(n: Int = 20): List<NetworkRequest> = requestLog.filter { it.error != null || it.statusCode >= 400 }.takeLast(n)
    fun slowRequests(thresholdMs: Long = 2000L): List<NetworkRequest> = requestLog.filter { it.responseTimeMs >= thresholdMs }
    fun clearLog() { requestLog.clear() }

    fun report(context: Context): String {
        val snap = snapshot(context)
        return buildString {
            append("Network: ${snap.networkType} connected=${snap.isConnected} vpn=${snap.isVpn}\n")
            append("  Bandwidth: down=${snap.downstreamBandwidthKbps}kbps up=${snap.upstreamBandwidthKbps}kbps\n")
            append("  Traffic delta: rx=${snap.rxBytesDelta}B tx=${snap.txBytesDelta}B\n")
            append("  Requests logged: ${requestLog.size} (${failedRequests().size} failed)\n")
        }
    }
}
