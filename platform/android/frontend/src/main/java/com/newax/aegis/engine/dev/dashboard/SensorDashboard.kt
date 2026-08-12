package com.newax.aegis.engine.dev.dashboard

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.newax.aegis.engine.registry.SensorRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class SensorReading(
    val sensorType: Int,
    val sensorName: String,
    val values: List<Float>,
    val accuracyLabel: String,
    val timestampNs: Long,
    val wallClockMs: Long = System.currentTimeMillis()
)

data class SensorInfo(
    val type: Int,
    val name: String,
    val vendor: String,
    val version: Int,
    val maxRange: Float,
    val resolution: Float,
    val power: Float,
    val minDelay: Int,
    val isWakeUpSensor: Boolean,
    val isAvailable: Boolean
)

object SensorDashboard : SensorEventListener {

    private val latestReadings = ConcurrentHashMap<Int, SensorReading>()
    private val history = ConcurrentHashMap<Int, CopyOnWriteArrayList<SensorReading>>()
    private val HISTORY_MAX = 100

    private val SENSOR_NAMES = mapOf(
        Sensor.TYPE_ACCELEROMETER to "Accelerometer",
        Sensor.TYPE_GYROSCOPE to "Gyroscope",
        Sensor.TYPE_MAGNETIC_FIELD to "Magnetometer",
        Sensor.TYPE_ROTATION_VECTOR to "Rotation Vector",
        Sensor.TYPE_GRAVITY to "Gravity",
        Sensor.TYPE_LINEAR_ACCELERATION to "Linear Acceleration",
        Sensor.TYPE_LIGHT to "Light",
        Sensor.TYPE_PROXIMITY to "Proximity",
        Sensor.TYPE_PRESSURE to "Barometer",
        Sensor.TYPE_STEP_COUNTER to "Step Counter",
        Sensor.TYPE_STEP_DETECTOR to "Step Detector",
        17 to "Heart Rate"
    )

    fun start(context: Context, sensorTypes: List<Int> = SENSOR_NAMES.keys.toList()) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        for (type in sensorTypes) {
            sm.getDefaultSensor(type)?.let { sensor ->
                sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        SensorRegistry.scan(context)
    }

    fun stop(context: Context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val reading = SensorReading(
            sensorType = event.sensor.type,
            sensorName = SENSOR_NAMES[event.sensor.type] ?: event.sensor.name,
            values = event.values.toList(),
            accuracyLabel = accuracyLabel(event.accuracy),
            timestampNs = event.timestamp
        )
        latestReadings[event.sensor.type] = reading
        val buf = history.getOrPut(event.sensor.type) { CopyOnWriteArrayList() }
        buf.add(reading)
        if (buf.size > HISTORY_MAX) buf.removeAt(0)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    fun latest(): Map<Int, SensorReading> = latestReadings.toMap()

    fun latestForType(type: Int): SensorReading? = latestReadings[type]

    fun historyForType(type: Int, n: Int = 50): List<SensorReading> =
        history[type]?.takeLast(n) ?: emptyList()

    fun listAvailable(context: Context): List<SensorInfo> {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sm.getSensorList(Sensor.TYPE_ALL).map { s ->
            SensorInfo(
                type = s.type,
                name = s.name,
                vendor = s.vendor,
                version = s.version,
                maxRange = s.maximumRange,
                resolution = s.resolution,
                power = s.power,
                minDelay = s.minDelay,
                isWakeUpSensor = if (android.os.Build.VERSION.SDK_INT >= 21) s.isWakeUpSensor else false,
                isAvailable = true
            )
        }
    }

    fun report(): String = buildString {
        append("Sensor Dashboard: ${latestReadings.size} active sensors\n")
        for ((type, reading) in latestReadings) {
            val vals = reading.values.take(3).joinToString(", ") { "%.2f".format(it) }
            append("  ${reading.sensorName}: [$vals] acc=${reading.accuracyLabel}\n")
        }
    }

    private fun accuracyLabel(acc: Int) = when (acc) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MED"
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
        SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
        else -> "NO_CONTACT"
    }
}
