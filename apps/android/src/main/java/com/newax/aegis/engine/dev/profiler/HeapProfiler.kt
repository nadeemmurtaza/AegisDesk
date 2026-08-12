package com.newax.aegis.engine.dev.profiler

import android.os.Debug
import com.newax.aegis.engine.dev.log.NewaxLogger
import java.io.File

data class HeapSnapshot(
    val timestampMs: Long,
    val nativeHeapMb: Long,
    val javaHeapAllocatedMb: Long,
    val javaHeapFreeMb: Long,
    val javaHeapMaxMb: Long,
    val gcCount: Long,
    val objectCount: Int
)

object HeapProfiler {

    private var traceActive = false
    private var traceFile: String? = null

    fun snapshot(): HeapSnapshot {
        val rt = Runtime.getRuntime()
        return HeapSnapshot(
            timestampMs = System.currentTimeMillis(),
            nativeHeapMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024),
            javaHeapAllocatedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024),
            javaHeapFreeMb = rt.freeMemory() / (1024 * 1024),
            javaHeapMaxMb = rt.maxMemory() / (1024 * 1024),
            gcCount = 0L,
            objectCount = 0
        )
    }

    fun dumpHprof(outputPath: String): String {
        return try {
            val file = File(outputPath)
            file.parentFile?.mkdirs()
            Debug.dumpHprofData(file.absolutePath)
            NewaxLogger.i("HeapProfiler", "HPROF dumped to $outputPath")
            "OK:${file.absolutePath} size=${file.length() / 1024}KB"
        } catch (e: Exception) {
            "ERROR:${e.message}"
        }
    }

    fun startMethodTracing(outputPath: String, bufferSizeMb: Int = 8): String {
        return try {
            if (traceActive) return "ERROR:already_tracing"
            Debug.startMethodTracing(outputPath, bufferSizeMb * 1024 * 1024)
            traceActive = true
            traceFile = outputPath
            NewaxLogger.i("HeapProfiler", "Method tracing started: $outputPath")
            "OK:tracing_started path=$outputPath"
        } catch (e: Exception) {
            "ERROR:${e.message}"
        }
    }

    fun stopMethodTracing(): String {
        return try {
            if (!traceActive) return "ERROR:not_tracing"
            Debug.stopMethodTracing()
            traceActive = false
            val path = traceFile ?: "?"
            traceFile = null
            NewaxLogger.i("HeapProfiler", "Method tracing stopped: $path")
            "OK:trace_saved path=$path"
        } catch (e: Exception) {
            "ERROR:${e.message}"
        }
    }

    fun forceGc(): HeapSnapshot {
        val before = snapshot()
        System.gc()
        Thread.sleep(200)
        return snapshot()
    }

    fun report(): String {
        val snap = snapshot()
        return buildString {
            append("Heap Report:\n")
            append("  Java heap: ${snap.javaHeapAllocatedMb}/${snap.javaHeapMaxMb} MB\n")
            append("  Java free: ${snap.javaHeapFreeMb} MB\n")
            append("  Native heap: ${snap.nativeHeapMb} MB\n")
            val pct = if (snap.javaHeapMaxMb > 0) snap.javaHeapAllocatedMb * 100 / snap.javaHeapMaxMb else 0
            append("  Pressure: $pct%\n")
            if (traceActive) append("  Method tracing: ACTIVE ($traceFile)\n")
        }
    }

    val isTracing: Boolean get() = traceActive
}
