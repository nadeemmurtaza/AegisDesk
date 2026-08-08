package com.newax.aegis

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.DbKeyManager
import com.newax.aegis.db.migration.LegacyMigrationWorker
import com.newax.aegis.memory.SecureKeyVault
import com.newax.aegis.engine.trigger.TriggerEngine as CoreTriggerEngine
import com.newax.aegis.engine.ContactScannerWorker
import com.newax.aegis.engine.background.IntelligenceWorker
import com.newax.aegis.engine.embedding.EmbeddingEngine
import com.newax.aegis.engine.embedding.EmbeddingIndexWorker
import com.newax.aegis.engine.model.ModelManager
import com.newax.aegis.engine.resource.ResourceGovernor
import com.newax.aegis.memory.EncryptedMemory
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class AegisApplication : Application() {

    private val memoryPressureCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            val pressureLevel = when {
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 5
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW      -> 4
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE         -> 3
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND       -> 2
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN        -> 1
                else                                                       -> 0
            }
            ResourceGovernor.onMemoryPressure(pressureLevel)
            ModelManager.unloadForPressure(pressureLevel)
        }
        override fun onLowMemory() { ResourceGovernor.onMemoryPressure(5); ModelManager.unloadForPressure(5) }
        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    override fun onCreate() {
        super.onCreate()
        registerComponentCallbacks(memoryPressureCallbacks)
        // Initialize encrypted DB before any workers or viewmodels access it
        val memory = EncryptedMemory(this)
        SecureKeyVault.init(this)
        DbKeyManager.migrateFromMemoryIfNeeded(memory)
        AegisDatabase.init(this, memory)
        CoreTriggerEngine.start(this, AegisDatabase.get) { _, _ -> }
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
        IntelligenceWorker.schedule(this)
    }

    private fun scheduleNightlyWork() {
        val constraints = Constraints.Builder()
            .setRequiresDeviceIdle(true)
            .setRequiresCharging(true)
            .build()
        WorkManager.getInstance(this).apply {
            // GalleryScanner is intentionally gone: it asked a text-only model to judge
            // images it never received, and fed it filenames inside a delete instruction.
            // Real gallery indexing is FileIndexer's job.
            cancelUniqueWork("GalleryScanner")
            enqueueUniquePeriodicWork(
                "ContactScanner",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ContactScannerWorker>(1, TimeUnit.DAYS)
                    .setConstraints(constraints).build()
            )
        }
    }
}
