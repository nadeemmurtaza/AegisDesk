package com.newax.aegis.engine.dev.profiler

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import com.newax.aegis.engine.resource.ResourceGovernor
import java.io.BufferedReader
import java.io.FileReader
import java.util.concurrent.CopyOnWriteArrayList

data class ResourceSnapshot(
    val timestampMs: Long,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val ramAvailMb: Long,
    val nativeHeapMb: Long,
    val javaHeapMb: Long,
    val cpuPercent: Float,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val thermalStatus: Int,
    val pressureLevel: Int,
    val heavyWorkerActive: Boolean,
    val criticalWorkerActive: Boolean,
    val queueDepth: Int,
    val completedJobs: Long,
    val failedJobs: Long
)

data class LowMemorySimResult(
    val pressureBefore: Int,
    val pressureApplied: Int,
    val queueBefore: Int,
    val queueAfter: Int,
    val heavyWasCancelled: Boolean
)

object ResourceProfiler {

    private const val TIMELINE_MAX = 120
    private val timeline = CopyOnWriteArrayList<ResourceSnapshot>()

    fun snapshot(context: Context): ResourceSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val battery = context.registerReceiver(null, ifilter)
        val batLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val batScale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: 1
        val batPct = if (batScale > 0) (batLevel * 100 / batScale) else -1
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: 0) != 0

        val thermalStatus = try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.currentThermalStatus
        } catch (_: Exception) { 0 }

        val stats = ResourceGovernor.devStats()

        val snap = ResourceSnapshot(
            timestampMs = System.currentTimeMillis(),
            ramUsedMb = (mi.totalMem - mi.availMem) / (1024 * 1024),
            ramTotalMb = mi.totalMem / (1024 * 1024),
            ramAvailMb = mi.availMem / (1024 * 1024),
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024),
            javaHeapMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024),
            cpuPercent = readCpuPercent(),
            batteryPercent = batPct,
            isCharging = plugged,
            thermalStatus = thermalStatus,
            pressureLevel = stats.pressure,
            heavyWorkerActive = stats.heavyRunning,
            criticalWorkerActive = stats.critRunning,
            queueDepth = stats.queued,
            completedJobs = stats.completed,
            failedJobs = stats.failed
        )

        timeline.add(snap)
        if (timeline.size > TIMELINE_MAX) timeline.removeAt(0)
        return snap
    }

    fun timeline(): List<ResourceSnapshot> = timeline.toList()

    fun simulateLowMemory(level: Int): LowMemorySimResult {
        val before = ResourceGovernor.pressureLevel.get()
        val qBefore = ResourceGovernor.queueDepth()
        val heavyBefore = ResourceGovernor.isHeavyRunning()
        ResourceGovernor.onMemoryPressure(level)
        val qAfter = ResourceGovernor.queueDepth()
        val heavyAfter = ResourceGovernor.isHeavyRunning()
        return LowMemorySimResult(
            pressureBefore = before,
            pressureApplied = level,
            queueBefore = qBefore,
            queueAfter = qAfter,
            heavyWasCancelled = heavyBefore && !heavyAfter
        )
    }

    fun resetPressure() {
        ResourceGovernor.onMemoryPressure(0)
    }

    private fun readCpuPercent(): Float = try {
        val stat = BufferedReader(FileReader("/proc/stat")).readLine() ?: return 0f
        val tokens = stat.split(" ").filter { it.isNotBlank() }.drop(1).map { it.toLongOrNull() ?: 0L }
        if (tokens.size < 4) return 0f
        val idle = tokens[3]
        val total = tokens.sum()
        if (total == 0L) 0f else ((total - idle).toFloat() / total) * 100f
    } catch (_: Exception) { 0f }

    fun report(context: Context): String {
        val snap = snapshot(context)
        return buildString {
            append("RAM: ${snap.ramUsedMb}/${snap.ramTotalMb} MB used (${snap.ramAvailMb} MB free)\n")
            append("Heap: java=${snap.javaHeapMb}MB native=${snap.nativeHeapMb}MB\n")
            append("CPU: ${snap.cpuPercent.toInt()}%\n")
            append("Battery: ${snap.batteryPercent}% ${if (snap.isCharging) "charging" else "discharging"}\n")
            append("Thermal: ${thermalLabel(snap.thermalStatus)}\n")
            append("Pressure: ${snap.pressureLevel}\n")
            append("Workers: heavy=${snap.heavyWorkerActive} critical=${snap.criticalWorkerActive} queue=${snap.queueDepth}\n")
            append("Jobs: done=${snap.completedJobs} failed=${snap.failedJobs}\n")
        }
    }

    private fun thermalLabel(status: Int) = when (status) {
        0 -> "NONE"
        1 -> "LIGHT"
        2 -> "MODERATE"
        3 -> "SEVERE"
        4 -> "CRITICAL"
        5 -> "EMERGENCY"
        6 -> "SHUTDOWN"
        else -> "UNKNOWN($status)"
    }
}
