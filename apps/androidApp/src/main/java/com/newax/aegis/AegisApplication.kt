package com.newax.aegis

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.Configuration
import kotlinx.coroutines.runBlocking
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.DbKeyManager
import com.newax.aegis.db.migration.LegacyMigrationWorker
import com.newax.aegis.memory.SecureKeyVault
import com.newax.aegis.engine.trigger.TriggerEngine as CoreTriggerEngine
import com.newax.aegis.engine.intelligence.GoalPlanner
import com.newax.aegis.engine.audit.ExecutionAuditHolder
import com.newax.aegis.engine.registry.DbGoalSnapshotStore
import com.newax.aegis.engine.ContactScannerWorker
import com.newax.aegis.engine.background.IntelligenceWorker
import com.newax.aegis.engine.embedding.EmbeddingEngine
import com.newax.aegis.engine.embedding.EmbeddingIndexWorker
import com.newax.aegis.engine.model.ModelManager
import com.newax.aegis.engine.resource.ResourceGovernor
import com.newax.aegis.memory.EncryptedMemory
import com.newax.aegis.sync.AndroidSyncContext
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
        // The sync module's Android actuals (BLE/WiFi-Direct discovery, the
        // TEE-wrapped identity store) read their Context from here — must be
        // set before any sync API is touched.
        AndroidSyncContext.init(this)
        // Sync identity + memory target (the auto-sync loop, SyncWorker).
        SyncRuntime.init(this)
        // Initialize encrypted DB before any workers or viewmodels access it
        val memory = EncryptedMemory(this)
        SecureKeyVault.init(this)
        // Register the platform capability surface (files, processes, shell, desktop,
        // secrets, system) so the UI and future executor can query their state.
        PlatformCapabilitiesHolder.init(this)
        // The one policy engine per process (authority spine, Track A2): user
        // overrides persist encrypted; toggle reads degrade to "off" (approval)
        // until MainViewModel initializes AutomationSettings — the safe default.
        PolicyHolder.init(this)
        DbKeyManager.migrateFromMemoryIfNeeded(memory)
        AegisDatabase.init(com.newax.aegis.db.getAegisDatabase(this, DbKeyManager.getOrCreate()))
        // Goals survive restarts (Track A5): every planner mutation persists a JSON
        // snapshot to the existing kv_store table (no schema change), and restore
        // rehydrates the planner before any screen or executor reads it.
        val goalStore = DbGoalSnapshotStore(AegisDatabase.get.kvStoreDao())
        GoalPlanner.onChange = goalStore::save
        goalStore.restore()
        // Execution audit trail (Track A8): every goal run is recorded for the
        // Goals screen's "Recent runs" section; persisted to kv_store like goals.
        ExecutionAuditHolder.init(AegisDatabase.get.kvStoreDao())
        // Policy-decision history (Track A2 follow-up): every evaluation across
        // sessions, persisted to kv_store like the execution audit. Records made
        // before the DB was ready are merged in by initAuditPersistence.
        PolicyHolder.initAuditPersistence(AegisDatabase.get.kvStoreDao())
        CoreTriggerEngine.start(this, AegisDatabase.get) { _, _ -> }
        // One-shot migration from legacy EncryptedSharedPreferences storage
        if (runBlocking { AegisDatabase.get.kvStoreDao().get("migration_v1_done") } != "1") {
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
        scheduleSyncWork()
        // Item 7 — continuous listening: the foreground service keeps the sync
        // transport up between worker windows so a paired peer can reach this
        // device at any moment (auto-sync default on; the Sync screen toggles
        // it and stops/starts the service). START_STICKY restarts it if the
        // system kills it; the worker stays as the catch-up net.
        if (SyncRuntime.enabled()) {
            SyncForegroundService.start(this)
        }
    }

    /**
     * The automatic sync loop (docs/SYNC_DESIGN.md §4.2): periodic, network-
     * constrained, 15-min cadence. No-op until a peer is paired; pairing is
     * explicit in the Sync screen.
     */
    private fun scheduleSyncWork() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "aegis-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )
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
