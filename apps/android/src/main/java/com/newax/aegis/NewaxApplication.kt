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
import com.newax.aegis.db.NewaxDatabase
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
import com.newax.aegis.startup.StartupReport
import com.newax.aegis.startup.StepCriticality
import com.newax.aegis.agents.AgentRegistry
import com.newax.aegis.agents.AgentRuntimeEngine
import com.newax.aegis.agents.LearningEngine
import com.newax.aegis.agents.SkillManager
import com.newax.aegis.engine.learning.EvolutionWorker
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class NewaxApplication : Application() {

    companion object {
        /**
         * Which optional startup steps failed, if any.
         *
         * Read by the dev console and the Settings diagnostics row. Exposed
         * because containing a failure silently is its own defect: an app that
         * quietly loses its learning engine looks like an app whose learning
         * engine does not work, which is a bug report nobody can act on.
         */
        val startup = StartupReport()
    }

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

    /**
     * Startup begins here, and the order below is deliberate.
     *
     * This method runs 22 eager initializers. Until recently every one was
     * unguarded, so a single throw anywhere in it was a crash on launch — and
     * two shipped that way, each hiding the next:
     *
     *  - an eager Ed25519 call that killed every device below Android 12,
     *  - an unloaded SQLCipher native library that killed *every* device.
     *
     * Neither was in something the app needs in order to start. So steps are now
     * classified: [StepCriticality.ESSENTIAL] still aborts, everything else is
     * contained and recorded in [startup] so a degraded start is visible.
     */
    override fun onCreate() {
        super.onCreate()

        // FIRST, before anything that can fail. This handler used to be
        // installed two-thirds of the way down onCreate, which meant the one
        // class of crash most worth reporting — a crash during startup — was the
        // one class it could not catch.
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

        registerComponentCallbacks(memoryPressureCallbacks)

        // ── Essential: the app is not safe to run without these ──────────────
        // The sync module's Android actuals (BLE/WiFi-Direct discovery, the
        // TEE-wrapped identity store) read their Context from here — must be
        // set before any sync API is touched.
        AndroidSyncContext.init(this)
        val memory = EncryptedMemory(this)
        SecureKeyVault.init(this)
        // The one policy engine per process (authority spine, Track A2). This is
        // ESSENTIAL in the strict sense: an assistant that can drive the device
        // with no policy engine is more dangerous than one that will not start.
        PolicyHolder.init(this)
        DbKeyManager.migrateFromMemoryIfNeeded(memory)
        NewaxDatabase.init(com.newax.aegis.db.getNewaxDatabase(this, DbKeyManager.getOrCreate()))

        // ── Optional: a failure costs one feature, not the app ───────────────
        with(startup) {
            // Never throws now (SyncAvailability), but wrapped so a future
            // regression degrades sync rather than bricking launch again.
            step("Sync identity") { SyncRuntime.init(this@NewaxApplication) }
            // The platform capability surface (files, processes, shell, secrets).
            step("Platform capabilities") { PlatformCapabilitiesHolder.init(this@NewaxApplication) }
            // Multi-agent registry — seeds the built-in agents so routing works
            // before any package import.
            step("Agent registry") { AgentRegistry.init(this@NewaxApplication) }
            // Skills: shared skills, per-agent grants, named sets.
            step("Skill manager") { SkillManager.init(this@NewaxApplication) }
            // PRAM controller surface, run ledger, State Archiver.
            step("Agent runtime") { AgentRuntimeEngine.init(this@NewaxApplication) }
            // RLAIF-E: evolution ledger, HITL staging gatekeeper, critic protocols.
            step("Learning engine") { LearningEngine.init(this@NewaxApplication) }
            // Goals survive restarts: planner mutations persist to kv_store.
            step("Goal persistence") {
                val goalStore = DbGoalSnapshotStore(NewaxDatabase.get.kvStoreDao())
                GoalPlanner.onChange = goalStore::save
                goalStore.restore()
            }
            step("Execution audit") { ExecutionAuditHolder.init(NewaxDatabase.get.kvStoreDao()) }
            // Policy-decision history. Optional because the spine still gates
            // every action without it — what is lost is the durable record, not
            // the enforcement. It is the closest of these to essential, so a
            // failure here deserves attention rather than a shrug.
            step("Policy audit persistence") {
                PolicyHolder.initAuditPersistence(NewaxDatabase.get.kvStoreDao())
            }
            step("Trigger engine") {
                CoreTriggerEngine.start(this@NewaxApplication, NewaxDatabase.get) { _, _ -> }
            }
            // One-shot migration from legacy EncryptedSharedPreferences storage.
            step("Legacy migration") {
                if (runBlocking { NewaxDatabase.get.kvStoreDao().get("migration_v1_done") } != "1") {
                    LegacyMigrationWorker.schedule(this@NewaxApplication)
                }
            }
            // Load the USE model from disk if downloaded; try to fetch if not.
            step("Embeddings") {
                EmbeddingEngine.init(this@NewaxApplication)
                if (!EmbeddingEngine.isReady()) {
                    EmbeddingEngine.downloadModelIfNeeded(this@NewaxApplication) { success ->
                        if (success && EmbeddingIndexWorker.isNeeded(NewaxDatabase.get)) {
                            EmbeddingIndexWorker.schedule(this@NewaxApplication)
                        }
                    }
                } else if (EmbeddingIndexWorker.isNeeded(NewaxDatabase.get)) {
                    EmbeddingIndexWorker.schedule(this@NewaxApplication)
                }
            }
            step("Nightly work") { scheduleNightlyWork() }
            step("Intelligence worker") { IntelligenceWorker.schedule(this@NewaxApplication) }
            step("Sync worker") { scheduleSyncWork() }
            // RLAIF-E continuous fuzzing: proposes alternative methods when idle
            // and charging; everything still pauses at the user gate.
            step("Evolution worker") { EvolutionWorker.schedule(this@NewaxApplication) }
            // The foreground service keeps the sync transport up between worker
            // windows so a paired peer can reach this device at any moment.
            step("Sync service") {
                if (SyncRuntime.isAvailable && SyncRuntime.enabled()) {
                    SyncForegroundService.start(this@NewaxApplication)
                }
            }
        }
    }

    /**
     * The automatic sync loop (docs/SYNC_DESIGN.md §4.2): periodic, network-
     * constrained, 15-min cadence. No-op until a peer is paired; pairing is
     * explicit in the Sync screen.
     */
    private fun scheduleSyncWork() {
        // Don't enqueue a 15-minute periodic job that can only ever no-op.
        // The worker bails too, but scheduling it would still wake the device.
        if (!SyncRuntime.isAvailable) return
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
