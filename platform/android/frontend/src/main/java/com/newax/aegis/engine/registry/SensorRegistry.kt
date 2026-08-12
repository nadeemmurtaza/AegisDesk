package com.newax.aegis.engine.registry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import java.util.concurrent.ConcurrentHashMap

data class SensorRecord(
    val type: Int,
    val name: String,
    val vendor: String,
    val version: Int,
    val maxRange: Float,
    val resolution: Float,
    val power: Float,
    val minDelay: Int,
    val available: Boolean
)

object SensorRegistry {

    private val sensors = ConcurrentHashMap<Int, SensorRecord>()

    val IMPORTANT_SENSOR_TYPES = listOf(
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_PROXIMITY,
        Sensor.TYPE_LIGHT,
        Sensor.TYPE_PRESSURE,
        Sensor.TYPE_GRAVITY,
        Sensor.TYPE_LINEAR_ACCELERATION,
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_STEP_COUNTER,
        Sensor.TYPE_STEP_DETECTOR,
        Sensor.TYPE_HEART_RATE
    )

    fun scan(context: Context) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        IMPORTANT_SENSOR_TYPES.forEach { type ->
            val sensor = sm.getDefaultSensor(type)
            sensors[type] = if (sensor != null) {
                SensorRecord(
                    type = type,
                    name = sensor.name,
                    vendor = sensor.vendor,
                    version = sensor.version,
                    maxRange = sensor.maximumRange,
                    resolution = sensor.resolution,
                    power = sensor.power,
                    minDelay = sensor.minDelay,
                    available = true
                )
            } else {
                SensorRecord(type, sensorTypeName(type), "", 0, 0f, 0f, 0f, 0, false)
            }
        }
    }

    fun isAvailable(type: Int): Boolean = sensors[type]?.available == true

    fun get(type: Int): SensorRecord? = sensors[type]

    fun available(): List<SensorRecord> = sensors.values.filter { it.available }

    fun unavailable(): List<SensorRecord> = sensors.values.filter { !it.available }

    fun all(): List<SensorRecord> = sensors.values.sortedBy { it.type }

    fun hasAccelerometer(): Boolean = isAvailable(Sensor.TYPE_ACCELEROMETER)
    fun hasGyroscope(): Boolean = isAvailable(Sensor.TYPE_GYROSCOPE)
    fun hasHeartRate(): Boolean = isAvailable(Sensor.TYPE_HEART_RATE)
    fun hasStepCounter(): Boolean = isAvailable(Sensor.TYPE_STEP_COUNTER)
    fun hasProximity(): Boolean = isAvailable(Sensor.TYPE_PROXIMITY)
    fun hasLight(): Boolean = isAvailable(Sensor.TYPE_LIGHT)

    private fun sensorTypeName(type: Int) = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
        Sensor.TYPE_GYROSCOPE -> "Gyroscope"
        Sensor.TYPE_MAGNETIC_FIELD -> "Magnetic Field"
        Sensor.TYPE_PROXIMITY -> "Proximity"
        Sensor.TYPE_LIGHT -> "Light"
        Sensor.TYPE_PRESSURE -> "Pressure"
        Sensor.TYPE_GRAVITY -> "Gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "Linear Acceleration"
        Sensor.TYPE_ROTATION_VECTOR -> "Rotation Vector"
        Sensor.TYPE_STEP_COUNTER -> "Step Counter"
        Sensor.TYPE_STEP_DETECTOR -> "Step Detector"
        Sensor.TYPE_HEART_RATE -> "Heart Rate"
        else -> "Sensor#$type"
    }
}
