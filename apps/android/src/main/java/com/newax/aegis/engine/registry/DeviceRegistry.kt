package com.newax.aegis.engine.registry

import android.bluetooth.BluetoothDevice
import android.annotation.SuppressLint
import android.content.Context
import com.newax.aegis.engine.bus.NewaxEvent
import com.newax.aegis.engine.bus.NewaxEventBus
import java.util.concurrent.ConcurrentHashMap

data class DeviceRecord(
    val id: String,
    val name: String,
    val type: DeviceType,
    val address: String = "",
    val capabilities: List<String> = emptyList(),
    val firstSeenMs: Long = System.currentTimeMillis(),
    val lastSeenMs: Long = System.currentTimeMillis(),
    val isConnected: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
)

enum class DeviceType {
    BLUETOOTH_AUDIO, BLUETOOTH_HID, BLUETOOTH_PHONE, WIFI_DEVICE,
    USB_DEVICE, SMARTWATCH, SMART_TV, SPEAKER, HEADPHONE, KEYBOARD,
    MOUSE, PRINTER, CAMERA, UNKNOWN
}

object DeviceRegistry {

    private val devices = ConcurrentHashMap<String, DeviceRecord>()

    fun seen(device: DeviceRecord) {
        val existing = devices[device.id]
        val updated = if (existing != null) {
            existing.copy(lastSeenMs = System.currentTimeMillis(), isConnected = device.isConnected)
        } else device
        devices[device.id] = updated
        if (device.isConnected && existing?.isConnected != true) {
            NewaxEventBus.emit(NewaxEvent.DeviceConnected(device.id, device.type.name))
        }
    }

    fun disconnect(id: String) {
        devices[id]?.let { device ->
            devices[id] = device.copy(isConnected = false)
            NewaxEventBus.emit(NewaxEvent.DeviceDisconnected(id))
        }
    }

    fun get(id: String): DeviceRecord? = devices[id]

    fun connected(): List<DeviceRecord> = devices.values.filter { it.isConnected }

    fun byType(type: DeviceType): List<DeviceRecord> = devices.values.filter { it.type == type }

    fun all(): List<DeviceRecord> = devices.values.sortedByDescending { it.lastSeenMs }

    fun remove(id: String) = devices.remove(id)

    fun count(): Int = devices.size

    /**
     * bluetoothClass and name both need BLUETOOTH_CONNECT on Android 12+ and
     * throw SecurityException without it. The one internal caller wraps this in
     * runCatching, but the function is public — so it carries its own guard
     * rather than depending on every future caller remembering.
     *
     * The address is safe: it comes from the already-bonded device record.
     */
    @SuppressLint("MissingPermission")
    fun fromBluetooth(btDevice: BluetoothDevice): DeviceRecord {
        val type = runCatching {
            when (btDevice.bluetoothClass?.majorDeviceClass) {
                224 -> DeviceType.BLUETOOTH_AUDIO
                1280 -> DeviceType.BLUETOOTH_HID
                512 -> DeviceType.BLUETOOTH_PHONE
                else -> DeviceType.UNKNOWN
            }
        }.getOrDefault(DeviceType.UNKNOWN)
        return DeviceRecord(
            id = btDevice.address,
            name = runCatching { btDevice.name }.getOrNull() ?: "Unknown",
            type = type,
            address = btDevice.address,
            isConnected = true
        )
    }

    /**
     * bondedDevices needs BLUETOOTH_CONNECT on Android 12+; the whole body is
     * inside runCatching, which catches the SecurityException. Lint cannot see
     * through runCatching.
     */
    @SuppressLint("MissingPermission")
    fun scanBluetooth(context: Context) {
        runCatching {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = bm?.adapter ?: return
            if (!adapter.isEnabled) return
            adapter.bondedDevices?.forEach { btDevice ->
                seen(fromBluetooth(btDevice).copy(isConnected = false))
            }
        }
    }
}
