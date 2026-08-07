package com.newax.aegis

import android.app.Application
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.migration.LegacyMigrationWorker
import com.newax.aegis.engine.ContactScannerWorker
import com.newax.aegis.engine.GalleryScannerWorker
import com.newax.aegis.engine.embedding.EmbeddingEngine
import com.newax.aegis.engine.embedding.EmbeddingIndexWorker
import com.newax.aegis.memory.EncryptedMemory
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class AegisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize encrypted DB before any workers or viewmodels access it
        val memory = EncryptedMemory(this)
        AegisDatabase.init(this, memory)
        // One-shot migration from legacy EncryptedSharedPreferences storage
        if (AegisDatabase.get.kvStoreDao().get("migration_v1_done") != "1") {
            LegacyMigrationWorker.schedule(this)
        }
        // Load USE model from disk if already downloaded; try to download if not
        EmbeddingEngine.init(this)
        if (!EmbeddingEngine.isReady()) {
            EmbeddingEngine.downloadModelIfNeeded(this) { success ->
                if (success && EmbeddingIndexWorker.isNeeded(AegisDatabase.get)) {
                    EmbeddingIndexWorker.schedule(this)
                }
            }
        } else if (EmbeddingIndexWorker.isNeeded(AegisDatabase.get)) {
            EmbeddingIndexWorker.schedule(this)
        }
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
