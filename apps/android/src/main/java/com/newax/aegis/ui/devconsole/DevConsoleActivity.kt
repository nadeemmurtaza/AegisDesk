package com.newax.aegis.ui.devconsole

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import kotlin.math.sqrt

class DevConsoleActivity : ComponentActivity() {

    private val vm: DevConsoleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DevConsoleScreen(vm = vm, onClose = { finish() })
            }
        }
    }

    companion object {
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, DevConsoleActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        fun shakeListener(context: Context): ShakeDetector =
            ShakeDetector(threshold = 14f) { launch(context) }
    }
}

class ShakeDetector(
    private val threshold: Float = 14f,
    private val cooldownMs: Long = 2000L,
    private val onShake: () -> Unit
) : SensorEventListener {

    private var lastShakeMs = 0L

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat() - SensorManager.GRAVITY_EARTH
        if (magnitude > threshold) {
            val now = System.currentTimeMillis()
            if (now - lastShakeMs > cooldownMs) {
                lastShakeMs = now
                onShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun register(sensorManager: SensorManager) {
        sensorManager.registerListener(
            this,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_UI
        )
    }

    fun unregister(sensorManager: SensorManager) {
        sensorManager.unregisterListener(this)
    }
}
