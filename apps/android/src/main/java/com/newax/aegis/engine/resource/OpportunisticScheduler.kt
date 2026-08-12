package com.newax.aegis.engine.resource

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object OpportunisticScheduler {

    data class DevStats(val registered: Int, val lastRunMs: Long, val runCount: Long)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tasks = mutableListOf<suspend () -> Unit>()
    @Volatile private var lastRunMs = 0L
    @Volatile private var runCount  = 0L

    fun devStats() = DevStats(tasks.size, lastRunMs, runCount)

    fun register(task: suspend () -> Unit) { tasks += task }

    fun start(context: Context) {
        scope.launch {
            while (isActive) {
                delay(60_000L)
                if (isIdle(context)) runAll()
            }
        }
    }

    private suspend fun runAll() {
        if (ResourceGovernor.isCriticalRunning()) return
        lastRunMs = System.currentTimeMillis()
        runCount++
        tasks.forEach { task ->
            if (!ResourceGovernor.isCriticalRunning()) {
                ResourceGovernor.submit(NewaxJob(
                    id            = ResourceGovernor.newId(),
                    label         = "opportunistic",
                    resourceClass = ResourceClass.HEAVY,
                    priority      = JobPriority.P6_COMPACTION,
                    cancellable   = true,
                    block         = task
                ))
            }
        }
    }

    private fun isIdle(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val screenOff = pm?.isInteractive == false
        val notThermal = ResourceGovernor.pressureLevel.get() <= 1
        val batteryOk  = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0 > 20
        return screenOff && notThermal && batteryOk
    }
}
