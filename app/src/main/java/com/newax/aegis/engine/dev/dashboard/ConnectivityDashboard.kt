package com.newax.aegis.engine.dev.dashboard

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager

data class ConnectivityState(
    val wifi: WifiState,
    val cellular: CellularState,
    val bluetooth: BluetoothState,
    val internet: InternetState
)

data class WifiState(
    val isEnabled: Boolean,
    val isConnected: Boolean,
    val ssid: String,
    val bssid: String,
    val signalStrength: Int,
    val linkSpeed: Int,
    val frequency: Int,
    val ipAddress: String
)

data class CellularState(
    val isDataEnabled: Boolean,
    val networkType: String,
    val carrierName: String,
    val signalStrength: Int,
    val isRoaming: Boolean,
    val simState: String
)

data class BluetoothState(
    val isEnabled: Boolean,
    val isDiscovering: Boolean,
    val pairedDeviceCount: Int,
    val pairedDeviceNames: List<String>,
    val scanMode: String
)

data class InternetState(
    val hasInternet: Boolean,
    val hasValidated: Boolean,
    val isMetered: Boolean,
    val downstreamBandwidthKbps: Int
)

object ConnectivityDashboard {

    fun snapshot(context: Context): ConnectivityState {
        return ConnectivityState(
            wifi = wifiState(context),
            cellular = cellularState(context),
            bluetooth = bluetoothState(context),
            internet = internetState(context)
        )
    }

    private fun wifiState(context: Context): WifiState {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info: WifiInfo? = if (Build.VERSION.SDK_INT < 31) wm.connectionInfo else null
        val isEnabled = wm.isWifiEnabled
        val ssid = info?.ssid?.removeSurrounding("\"") ?: ""
        val bssid = info?.bssid ?: ""
        val rssi = info?.rssi ?: 0
        val linkSpeed = info?.linkSpeed ?: 0
        val freq = info?.frequency ?: 0
        val ip = info?.ipAddress?.let { intToIp(it) } ?: ""
        val signal = WifiManager.calculateSignalLevel(rssi, 5)
        return WifiState(isEnabled, ssid.isNotBlank(), ssid, bssid, signal, linkSpeed, freq, ip)
    }

    private fun cellularState(context: Context): CellularState {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val dataEnabled = runCatching { tm.isDataEnabled }.getOrDefault(false)
        val networkType = networkTypeName(runCatching { tm.networkType }.getOrDefault(0))
        val carrier = tm.networkOperatorName ?: ""
        val roaming = runCatching { tm.isNetworkRoaming }.getOrDefault(false)
        val simState = simStateName(tm.simState)
        return CellularState(dataEnabled, networkType, carrier, 0, roaming, simState)
    }

    private fun bluetoothState(context: Context): BluetoothState {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        val isEnabled = adapter?.isEnabled == true
        val isDiscovering = runCatching { adapter?.isDiscovering == true }.getOrDefault(false)
        val paired = runCatching { adapter?.bondedDevices?.toList() ?: emptyList() }.getOrDefault(emptyList())
        val scanMode = when (adapter?.scanMode) {
            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "DISCOVERABLE"
            BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "CONNECTABLE"
            BluetoothAdapter.SCAN_MODE_NONE -> "NONE"
            else -> "UNKNOWN"
        }
        return BluetoothState(isEnabled, isDiscovering, paired.size, paired.take(5).map { it.name ?: it.address }, scanMode)
    }

    private fun internetState(context: Context): InternetState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        return InternetState(
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            hasValidated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            isMetered = cm.isActiveNetworkMetered,
            downstreamBandwidthKbps = caps?.linkDownstreamBandwidthKbps ?: 0
        )
    }

    fun report(context: Context): String {
        val state = snapshot(context)
        return buildString {
            append("Connectivity Dashboard:\n")
            with(state.wifi) { append("  WiFi: ${if (isConnected) "CONNECTED ssid=$ssid signal=$signalStrength/4 ${linkSpeed}Mbps" else "disconnected"}\n") }
            with(state.cellular) { append("  Cellular: ${networkType} carrier=$carrierName data=$isDataEnabled roaming=$isRoaming\n") }
            with(state.bluetooth) { append("  Bluetooth: ${if (isEnabled) "ON" else "OFF"} paired=$pairedDeviceCount scanning=$isDiscovering\n") }
            with(state.internet) { append("  Internet: validated=$hasValidated metered=$isMetered down=${downstreamBandwidthKbps}kbps\n") }
        }
    }

    private fun intToIp(ip: Int): String =
        "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"

    private fun networkTypeName(type: Int) = when (type) {
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "TYPE_$type"
    }

    private fun simStateName(state: Int) = when (state) {
        TelephonyManager.SIM_STATE_READY -> "READY"
        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
        3 -> "LOCKED"
        TelephonyManager.SIM_STATE_UNKNOWN -> "UNKNOWN"
        else -> "STATE_$state"
    }
}
