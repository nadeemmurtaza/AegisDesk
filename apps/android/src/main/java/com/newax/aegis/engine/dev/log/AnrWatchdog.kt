package com.newax.aegis.engine.dev.log

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object AnrWatchdog {

    private const val DEFAULT_TIMEOUT_MS = 4000L
    private const val CHECK_INTERVAL_MS = 500L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ticker = AtomicLong(0L)
    private val running = AtomicBoolean(false)
    private var watchThread: Thread? = null
    private var timeoutMs: Long = DEFAULT_TIMEOUT_MS

    fun start(anrTimeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        if (running.getAndSet(true)) return
        timeoutMs = anrTimeoutMs
        tick()
        watchThread = Thread({
            while (running.get()) {
                val before = ticker.get()
                Thread.sleep(timeoutMs)
                if (!running.get()) break
                if (ticker.get() == before) {
                    val blockedMs = timeoutMs
                    CrashReporter.recordAnr("main", blockedMs)
                    NewaxLogger.e("AnrWatchdog", "ANR detected: main thread blocked for ${blockedMs}ms")
                }
            }
        }, "AnrWatchdog").also { it.isDaemon = true }
        watchThread?.start()
        scheduleTickCheck()
    }

    fun stop() {
        running.set(false)
        watchThread?.interrupt()
        watchThread = null
    }

    private fun tick() {
        ticker.incrementAndGet()
    }

    private fun scheduleTickCheck() {
        if (!running.get()) return
        mainHandler.postDelayed({
            tick()
            scheduleTickCheck()
        }, CHECK_INTERVAL_MS)
    }

    val isRunning: Boolean get() = running.get()
}
