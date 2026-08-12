package com.newax.aegis.ui.devconsole

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.database.Cursor
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.db.entity.FileObject
import com.newax.aegis.db.entity.TriggerRule
import com.newax.aegis.engine.dev.DevLogger
import com.newax.aegis.engine.files.FileIndexer
import com.newax.aegis.engine.resource.JobPriority
import com.newax.aegis.engine.resource.OpportunisticScheduler
import com.newax.aegis.engine.resource.ResourceClass
import com.newax.aegis.engine.resource.ResourceGovernor
import com.newax.aegis.engine.trigger.TriggerEngine
import com.newax.aegis.memory.EncryptedMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DevConsoleViewModel(app: Application) : AndroidViewModel(app) {

    private val db     = NewaxDatabase.get
    private val memory = EncryptedMemory(app)
    private var poller: Job? = null

    data class EngineSnapshot(
        val heavyRunning: Boolean   = false,
        val critRunning: Boolean    = false,
        val queued: Int             = 0,
        val pressure: Int           = 0,
        val completed: Long         = 0,
        val failed: Long            = 0,
        val schedulerRegistered: Int = 0,
        val schedulerLastRunMs: Long = 0,
        val schedulerRunCount: Long  = 0
    )

    data class MemoryEntry(val key: String, val type: String, val preview: String, val sizeBytes: Int)

    data class DbTableStat(val table: String, val rows: Int)

    data class SqlResult(
        val query: String,
        val columns: List<String>,
        val rows: List<List<String>>,
        val error: String?,
        val durationMs: Long
    )

    data class FileIndexSnapshot(
        val total: Int        = 0,
        val duplicates: Int   = 0,
        val unindexed: Int    = 0,
        val needsText: Int    = 0,
        val needsEntities: Int = 0,
        val needsVisual: Int  = 0,
        val textContent: Int  = 0,
        val entityLinks: Int  = 0
    )

    private val _engine   = MutableStateFlow(EngineSnapshot())
    val engine: StateFlow<EngineSnapshot> = _engine.asStateFlow()

    private val _memEntries = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val memEntries: StateFlow<List<MemoryEntry>> = _memEntries.asStateFlow()

    val logEntries = DevLogger.entries

    private val _dbStats = MutableStateFlow<List<DbTableStat>>(emptyList())
    val dbStats: StateFlow<List<DbTableStat>> = _dbStats.asStateFlow()

    private val _sqlResult = MutableStateFlow<SqlResult?>(null)
    val sqlResult: StateFlow<SqlResult?> = _sqlResult.asStateFlow()

    private val _triggerRules = MutableStateFlow<List<TriggerRule>>(emptyList())
    val triggerRules: StateFlow<List<TriggerRule>> = _triggerRules.asStateFlow()

    private val _lastFired = MutableStateFlow<String?>(null)
    val lastFired: StateFlow<String?> = _lastFired.asStateFlow()

    private val _fileStats = MutableStateFlow(FileIndexSnapshot())
    val fileStats: StateFlow<FileIndexSnapshot> = _fileStats.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<FileObject>>(emptyList())
    val recentFiles: StateFlow<List<FileObject>> = _recentFiles.asStateFlow()

    private val _indexStatus = MutableStateFlow<String?>(null)
    val indexStatus: StateFlow<String?> = _indexStatus.asStateFlow()

    private val ALL_TABLES = listOf(
        "file_objects", "file_text_content", "file_entity_links",
        "trigger_rules", "person_snapshots", "person_policies",
        "person_channel_prefs", "commitments",
        "entities", "predicates", "edges", "blobs",
        "app_capabilities", "app_procedures",
        "learning_drafts"
    )

    init {
        DevLogger.i("DevConsole", "console opened")
        startPoller()
    }

    private fun startPoller() {
        poller = viewModelScope.launch {
            while (isActive) {
                refreshAll()
                delay(4000L)
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshEngine()
            refreshMemory()
            refreshDb()
            refreshTriggers()
            refreshFiles()
        }
    }

    private fun refreshEngine() {
        val rg = ResourceGovernor.devStats()
        val sc = OpportunisticScheduler.devStats()
        _engine.value = EngineSnapshot(
            heavyRunning        = rg.heavyRunning,
            critRunning         = rg.critRunning,
            queued              = rg.queued,
            pressure            = rg.pressure,
            completed           = rg.completed,
            failed              = rg.failed,
            schedulerRegistered = sc.registered,
            schedulerLastRunMs  = sc.lastRunMs,
            schedulerRunCount   = sc.runCount
        )
    }

    private fun refreshMemory() {
        val entries = mutableListOf<MemoryEntry>()
        runCatching {
            val (strings, sets) = memory.exportAll()
            strings.forEach { (k, v) ->
                entries += MemoryEntry(k, "string", v.take(140), v.length)
            }
            sets.forEach { (k, v) ->
                val preview = v.take(3).joinToString(" | ")
                entries += MemoryEntry(k, "set[${v.size}]", preview.take(140), preview.length)
            }
            val categories = memory.getAllCategories()
            categories.forEach { (cat, facts) ->
                val key = "profile_$cat"
                if (entries.none { it.key == key }) {
                    val preview = facts.take(3).joinToString(" | ")
                    entries += MemoryEntry(key, "category[${facts.size}]", preview.take(140), preview.length)
                }
            }
        }
        _memEntries.value = entries.sortedBy { it.key }
    }

    private suspend fun refreshDb() {
        // Disabled openHelper usage for now as it's not available in KMP Room.
        // We will just show 0 for all tables.
        val stats = ALL_TABLES.map { table ->
            DbTableStat(table, 0)
        }
        _dbStats.value = stats
    }

    private suspend fun refreshTriggers() {
        _triggerRules.value = runCatching { db.triggerDao().allRules() }.getOrDefault(emptyList())
    }

    private suspend fun refreshFiles() {
        _fileStats.value = runCatching {
            FileIndexSnapshot(
                total        = db.fileDao().totalFiles(),
                duplicates   = db.fileDao().duplicateCount(),
                unindexed    = db.fileDao().unindexedCount(),
                needsText    = db.fileDao().needsTextExtractionCount(),
                needsEntities = db.fileDao().needsEntityExtractionCount(),
                needsVisual  = db.fileDao().needsVisualIndexCount(),
                textContent  = db.fileDao().textContentCount(),
                entityLinks  = db.fileDao().entityLinkCount()
            )
        }.getOrDefault(FileIndexSnapshot())
        _recentFiles.value = runCatching { db.fileDao().recentUniqueFiles(15) }.getOrDefault(emptyList())
    }

    fun runSql(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            // Temporarily disabled due to KMP Room migration removing openHelper
            val result = SqlResult(query, emptyList(), emptyList(), "runSql is temporarily disabled in KMP", System.currentTimeMillis() - t0)
            _sqlResult.value = result
        }
    }

    fun clearSqlResult() { _sqlResult.value = null }

    fun fireNotificationEvent(sender: String, text: String, pkg: String) {
        viewModelScope.launch {
            TriggerEngine.onNotification(sender, text, pkg)
            _lastFired.value = "NOTIFICATION sender=$sender text=${text.take(30)}"
            DevLogger.i("DevConsole", "injected NOTIFICATION sender=$sender pkg=$pkg")
        }
    }

    fun fireWindowChanged(pkg: String) {
        viewModelScope.launch {
            TriggerEngine.onWindowChanged(pkg)
            _lastFired.value = "APP_OPENED pkg=$pkg"
            DevLogger.i("DevConsole", "injected APP_OPENED pkg=$pkg")
        }
    }

    fun fireScreenContent(text: String) {
        viewModelScope.launch {
            TriggerEngine.onScreenContent(text)
            _lastFired.value = "SCREEN_CONTENT ${text.take(40)}"
            DevLogger.i("DevConsole", "injected SCREEN_CONTENT len=${text.length}")
        }
    }

    fun toggleRule(id: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.triggerDao().setEnabled(id, enabled) }
            refreshTriggers()
        }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.triggerDao().deleteById(id) }
            refreshTriggers()
        }
    }

    fun runScanAll() = launchIndexStage("Scanning all files…", "Scan complete.") {
        FileIndexer.scanAll(getApplication(), db)
    }

    fun runTextExtraction() = launchIndexStage("Extracting text…", "Text extraction done.") {
        FileIndexer.runTextExtraction(getApplication(), db, 100)
    }

    fun runEntityExtraction() = launchIndexStage("Extracting entities…", "Entity extraction done.") {
        FileIndexer.runEntityExtraction(db, 100)
    }

    fun runVisualIndexing() = launchIndexStage("Visual hashing…", "Visual indexing done.") {
        FileIndexer.runVisualIndexing(getApplication(), db, 100)
    }

    private fun launchIndexStage(start: String, done: String, block: suspend () -> Unit) {
        _indexStatus.value = start
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }
            refreshFiles()
            _indexStatus.value = done
        }
    }

    fun clearIndexStatus() { _indexStatus.value = null }

    fun copyLogs(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("aegis_logs", DevLogger.export()))
    }

    fun clearLogs() { DevLogger.clear() }

    override fun onCleared() {
        poller?.cancel()
        super.onCleared()
    }
}
