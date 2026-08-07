package com.newax.aegis

import android.app.Application
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.newax.aegis.engine.ContactScannerWorker
import com.newax.aegis.engine.GalleryScannerWorker
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class AegisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            try {
                startActivity(
                    Intent(this, CrashReporterActivity::class.java).apply {
                        putExtra("CRASH_LOG", sw.toString())
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                )
            } catch (_: Exception) {}
            exitProcess(1)
        }
        scheduleNightlyWork()
    }

    private fun scheduleNightlyWork() {
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .setRequiresCharging(true)
            .build()
        WorkManager.getInstance(this).apply {
            enqueueUniquePeriodicWork(
                "GalleryScanner",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<GalleryScannerWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints).build()
            )
            enqueueUniquePeriodicWork(
                "ContactScanner",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ContactScannerWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints).build()
            )
        }
    }
}
