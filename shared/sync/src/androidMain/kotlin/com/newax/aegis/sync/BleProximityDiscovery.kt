package com.newax.aegis.sync

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BLE advertise/scan proximity discovery for Android (docs/SYNC_DESIGN.md
 * §10.1, P2). The device's [ProximityProfile] travels in a manufacturer-data
 * field encoded with [ProximityAdCodec] — the same byte format iOS's
 * CoreBluetooth actual can reuse. Scans are wildcarded and each result is
 * decoded; discovery carries identity only, the bulk transfer runs over the
 * WiFi-Direct group socket ([P2pProximityChannel]).
 *
 * Permissions: BLUETOOTH_SCAN/BLUETOOTH_ADVERTISE on API 31+;
 * BLUETOOTH/BLUETOOTH_ADMIN (advertise) and ACCESS_FINE_LOCATION (scan) on
 * API 26–30. A missing permission turns discovery OFF with a named [error]
 * (surfaced in the UI, never a crash).
 */
class BleProximityDiscovery(private val context: Context) : ProximityDiscovery {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val found = CopyOnWriteArrayList<ProximityEndpoint>()

    @Volatile
    private var listener: ProximityListener? = null

    @Volatile
    private var ownDeviceId: String? = null

    @Volatile
    override var error: String? = null
        private set

    override fun startAdvertising(profile: ProximityProfile) {
        ownDeviceId = profile.deviceId
        if (!canAdvertise()) return
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            error = "BLE advertising not supported on this device"
            return
        }
        val payload = ProximityAdCodec.encode(profile.deviceId, profile.displayName)
        if (payload == null) {
            error = "device id too long for a BLE advertisement"
            return
        }
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(ProximityAdCodec.COMPANY_ID, payload)
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .build()
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
        } catch (e: Exception) {
            error = "BLE advertise failed: ${e.message}"
        }
    }

    override fun startScanning(listener: ProximityListener) {
        this.listener = listener
        if (!canScan()) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            error = "BLE scanning not supported on this device"
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            error = "BLE scan failed: ${e.message}"
        }
    }

    override fun stop() {
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        found.clear()
    }

    override fun nearby(): List<ProximityEndpoint> = found.toList()

    private fun canAdvertise(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                error = "missing BLUETOOTH_ADVERTISE permission"
                return false
            }
            return true
        }
        if (!hasPermission(Manifest.permission.BLUETOOTH_ADMIN)) {
            error = "missing BLUETOOTH_ADMIN permission"
            return false
        }
        return true
    }

    private fun canScan(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                error = "missing BLUETOOTH_SCAN permission"
                return false
            }
            return true
        }
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            error = "missing ACCESS_FINE_LOCATION permission for BLE scanning"
            return false
        }
        return true
    }

    private fun hasPermission(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun endpointFrom(result: ScanResult): ProximityEndpoint? {
        val bytes = result.scanRecord?.getManufacturerSpecificData(ProximityAdCodec.COMPANY_ID) ?: return null
        val decoded = ProximityAdCodec.decode(bytes) ?: return null
        val (deviceId, displayName) = decoded
        return ProximityEndpoint(deviceId, displayName.ifBlank { deviceId }, null, null)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            error = null
        }

        override fun onStartFailure(errorCode: Int) {
            error = "BLE advertise failed (code $errorCode)"
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val endpoint = endpointFrom(result) ?: return
            if (endpoint.deviceId == ownDeviceId) return
            found.removeAll { it.deviceId == endpoint.deviceId }
            found.add(endpoint)
            listener?.onPeerFound(endpoint)
        }
    }
}
