package com.newax.aegis.engine.manager

import android.content.Context
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.engine.bus.NewaxEvent
import com.newax.aegis.engine.bus.NewaxEventBus
import com.newax.aegis.engine.dev.FeatureFlags
import com.newax.aegis.engine.files.FileIndexer
import com.newax.aegis.engine.resource.JobPriority
import com.newax.aegis.engine.resource.ResourceClass
import com.newax.aegis.engine.resource.ResourceGovernor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object IndexManager {

    data class IndexStats(
        val totalFiles: Int,
        val pendingText: Int,
        val pendingEntities: Int,
        val pendingVisual: Int,
        val textContentRows: Int,
        val entityLinkRows: Int,
        val duplicateCount: Int,
        val unindexedCount: Int,
        val lastScanMs: Long
    )

    @Volatile private var lastScanMs = 0L
    @Volatile var isRunning = false
        private set

    suspend fun getStats(db: NewaxDatabase): IndexStats = withContext(Dispatchers.IO) {
        val dao = db.fileDao()
        IndexStats(
            totalFiles = dao.totalFiles(),
            pendingText = dao.needsTextExtractionCount(),
            pendingEntities = dao.needsEntityExtractionCount(),
            pendingVisual = dao.needsVisualIndexCount(),
            textContentRows = dao.textContentCount(),
            entityLinkRows = dao.entityLinkCount(),
            duplicateCount = dao.duplicateCount(),
            unindexedCount = dao.unindexedCount(),
            lastScanMs = lastScanMs
        )
    }

    fun scheduleScanAll(context: Context, db: NewaxDatabase, priority: JobPriority = JobPriority.P3_INDEXING) {
        if (!FeatureFlags.isEnabled(FeatureFlags.Flag.OPPORTUNISTIC_INDEXING)) return
        ResourceGovernor.fire("index-scan-all", ResourceClass.LIGHT, priority) {
            NewaxEventBus.emit(NewaxEvent.IndexingStarted("scan_all"))
            val start = System.currentTimeMillis()
            FileIndexer.scanAll(context, db)
            lastScanMs = System.currentTimeMillis()
            NewaxEventBus.emit(NewaxEvent.IndexingComplete("scan_all", db.fileDao().totalFiles(), System.currentTimeMillis() - start))
        }
    }

    fun scheduleTextExtraction(context: Context, db: NewaxDatabase) {
        if (!FeatureFlags.isEnabled(FeatureFlags.Flag.TEXT_EXTRACTION)) return
        ResourceGovernor.fire("index-text", ResourceClass.HEAVY, JobPriority.P3_INDEXING) {
            NewaxEventBus.emit(NewaxEvent.IndexingStarted("text_extraction"))
            val start = System.currentTimeMillis()
            FileIndexer.runTextExtraction(context, db)
            NewaxEventBus.emit(NewaxEvent.IndexingComplete("text_extraction", 0, System.currentTimeMillis() - start))
        }
    }

    fun scheduleEntityExtraction(context: Context, db: NewaxDatabase) {
        if (!FeatureFlags.isEnabled(FeatureFlags.Flag.ENTITY_EXTRACTION)) return
        ResourceGovernor.fire("index-entities", ResourceClass.HEAVY, JobPriority.P3_INDEXING) {
            NewaxEventBus.emit(NewaxEvent.IndexingStarted("entity_extraction"))
            val start = System.currentTimeMillis()
            FileIndexer.runEntityExtraction(db)
            NewaxEventBus.emit(NewaxEvent.IndexingComplete("entity_extraction", 0, System.currentTimeMillis() - start))
        }
    }

    fun scheduleVisualIndexing(context: Context, db: NewaxDatabase) {
        if (!FeatureFlags.isEnabled(FeatureFlags.Flag.VISUAL_HASHING)) return
        ResourceGovernor.fire("index-visual", ResourceClass.HEAVY, JobPriority.P3_INDEXING) {
            NewaxEventBus.emit(NewaxEvent.IndexingStarted("visual_indexing"))
            val start = System.currentTimeMillis()
            FileIndexer.runVisualIndexing(context, db)
            NewaxEventBus.emit(NewaxEvent.IndexingComplete("visual_indexing", 0, System.currentTimeMillis() - start))
        }
    }
}
