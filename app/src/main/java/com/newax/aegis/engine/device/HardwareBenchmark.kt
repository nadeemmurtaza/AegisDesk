package com.newax.aegis.engine.device

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DevicePerformanceProfile(
    val cpuCoreCount: Int,
    val availableRamMb: Long,
    val embeddingThroughputSentPerSec: Float,
    val hasNnapi: Boolean,
    val hasGpuDelegate: Boolean,
    val thermalHeadroom: Float,
    val benchmarkTimestampMs: Long
)

object HardwareBenchmark {

    private const val PREFS_FILE = "aegis_hw_benchmark"
    private const val KEY_CPU_CORES = "cpu_cores"
    private const val KEY_RAM_MB = "ram_mb"
    private const val KEY_EMB_THROUGHPUT = "emb_throughput"
    private const val KEY_HAS_NNAPI = "has_nnapi"
    private const val KEY_HAS_GPU = "has_gpu"
    private const val KEY_THERMAL = "thermal"
    private const val KEY_TIMESTAMP = "timestamp"

    fun hasProfile(context: Context): Boolean {
        val prefs = prefs(context)
        return prefs.getLong(KEY_TIMESTAMP, 0L) > 0
    }

    fun loadProfile(context: Context): DevicePerformanceProfile? {
        val prefs = prefs(context)
        val ts = prefs.getLong(KEY_TIMESTAMP, 0L)
        if (ts == 0L) return null
        return DevicePerformanceProfile(
            cpuCoreCount = prefs.getInt(KEY_CPU_CORES, 4),
            availableRamMb = prefs.getLong(KEY_RAM_MB, 2048L),
            embeddingThroughputSentPerSec = prefs.getFloat(KEY_EMB_THROUGHPUT, 0f),
            hasNnapi = prefs.getBoolean(KEY_HAS_NNAPI, false),
            hasGpuDelegate = prefs.getBoolean(KEY_HAS_GPU, false),
            thermalHeadroom = prefs.getFloat(KEY_THERMAL, 1.0f),
            benchmarkTimestampMs = ts
        )
    }

    suspend fun runAndSave(context: Context): DevicePerformanceProfile = withContext(Dispatchers.Default) {
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val actMgr = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actMgr.getMemoryInfo(memInfo)
        val ramMb = memInfo.totalMem / (1024 * 1024)
        val hasNnapi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        // getThermalHeadroom was added in API 30 (R), not Q.
        val thermalHeadroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pm = context.getSystemService(android.os.PowerManager::class.java)
                pm?.getThermalHeadroom(5) ?: 1.0f
            } catch (_: Exception) { 1.0f }
        } else 1.0f

        val profile = DevicePerformanceProfile(
            cpuCoreCount = cpuCores,
            availableRamMb = ramMb,
            embeddingThroughputSentPerSec = 0f,
            hasNnapi = hasNnapi,
            hasGpuDelegate = false,
            thermalHeadroom = thermalHeadroom,
            benchmarkTimestampMs = System.currentTimeMillis()
        )
        save(context, profile)
        profile
    }

    private fun save(context: Context, p: DevicePerformanceProfile) {
        prefs(context).edit()
            .putInt(KEY_CPU_CORES, p.cpuCoreCount)
            .putLong(KEY_RAM_MB, p.availableRamMb)
            .putFloat(KEY_EMB_THROUGHPUT, p.embeddingThroughputSentPerSec)
            .putBoolean(KEY_HAS_NNAPI, p.hasNnapi)
            .putBoolean(KEY_HAS_GPU, p.hasGpuDelegate)
            .putFloat(KEY_THERMAL, p.thermalHeadroom)
            .putLong(KEY_TIMESTAMP, p.benchmarkTimestampMs)
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
