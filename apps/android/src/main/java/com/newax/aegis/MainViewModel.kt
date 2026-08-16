package com.newax.aegis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.speech.tts.TextToSpeech
import java.util.Locale
import com.newax.aegis.assistant.*
import com.newax.aegis.db.NewaxDatabase
import com.newax.aegis.engine.AutomationSettings
import com.newax.aegis.engine.TotpManager
import com.newax.aegis.engine.apps.AppScanner
import com.newax.aegis.engine.files.FileIndexer
import com.newax.aegis.engine.learning.DraftStore
import com.newax.aegis.engine.learning.LearningDraft
import com.newax.aegis.engine.learning.ScanProgress
import com.newax.aegis.engine.person.PersonRegistry
import com.newax.aegis.engine.resource.JobPriority
import com.newax.aegis.engine.resource.NewaxJob
import com.newax.aegis.engine.resource.OpportunisticScheduler
import com.newax.aegis.engine.resource.ResourceClass
import com.newax.aegis.engine.resource.ResourceGovernor
import com.newax.aegis.memory.EncryptedMemory
import com.newax.aegis.authority.AuthorityManager
import com.newax.aegis.authority.AuthorityEvent
import com.newax.aegis.chat.ChatHistoryStore
import com.newax.aegis.chat.ConversationSearchHit
import com.newax.aegis.chat.ConversationSummary
import com.newax.aegis.chat.MAX_CONVERSATION_TITLE_CHARS
import com.newax.aegis.chat.RoomChatHistoryStore
import com.newax.aegis.ui.state.ChatScreenState
import com.newax.aegis.ui.state.SettingsScreenState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The app's view-model facade (T3.1) — the wiring half of the old god object.
 *
 * It owns the Compose-observable state the screens read, the process lifecycle
 * (memory pressure, teardown), the chat-history seam, and the per-screen state
 * holders ([ChatScreenState]). All inference/action logic lives in
 * [AssistantController]; the ViewModel delegates the public entry points
 * (submit/approve/reject/execute/stop/import) so no screen signature changed.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val memory = EncryptedMemory(application)
    val db = NewaxDatabase.get
    private val chatHistory: ChatHistoryStore = RoomChatHistoryStore(db.conversationDao())
    private val chatState = ChatScreenState()

    // T3.5a — the chat shell: the conversation list (route 1.1) is a live flow
    // from the DAO (recent-first), and the thread surface has a conversation
    // context. `null` means "fresh thread, no row yet" — the row is created by
    // the first appended turn.
    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()
    var activeConversationId by mutableStateOf<String?>(null); private set

    /**
     * The chat pipeline — see [AssistantController]. The controller is plain
     * Kotlin (no @Composable access), so it gets a string-resource resolver
     * backed by the application context (T3.2).
     */
    internal val controller = AssistantController(
        application,
        this,
        StringResolver { resId, args -> application.getString(resId, *args) }
    )

    /**
     * Slice 12 — the live agent-run session id, when one is running. The chat
     * thread's inline step block (spec §7.2) renders that session's
     * [com.newax.aegis.agents.AgentStream] events while they happen and
     * collapses to the final state when the id clears.
     */
    val agentSessionId: StateFlow<String?> = controller.activeAgentSessionId

    private val memoryCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            // Order matters: heavier pressure should be handled first. Using
            // if-chain to ensure CRITICAL is caught even if enum values change.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                // Lowest priority — kill everything that can be rebuilt
            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                // Clear caches, drop in-memory indexes
            } else if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                // Release UI resources
            }
        }

        override fun onLowMemory() {}

        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    /** Resolves a string resource outside composition (T3.2 — the pipeline is plain Kotlin). */
    private fun str(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    private fun pressureFromTrim(level: Int): Int = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> 4
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW     -> 3
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN       -> 2
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND      -> 1
        else                                                      -> 0
    }

    val messages = mutableStateListOf(ChatMessage(str(R.string.chat_boot_greeting), false, id = BOOT_GREETING_ID))

    // T3.5c — route 1.2: the composer draft. Lifted out of ChatScreen so the
    // shell-level voice-capture sheet (route 1.10) can insert its transcript
    // into the field; the screen owns the keystrokes, the sheet owns the
    // inserts. `internal set` — only this module writes it.
    var composerText by mutableStateOf("")
        internal set

    // T3.0c — the in-progress assistant reply. `streamingActive` drives the
    // streaming bubble in the chat surface; `streamingText` grows per emitted
    // chunk from ModelProvider.stream(). Stopping cancels the collecting
    // coroutine — the UI stops updating, but the model call itself cannot be
    // interrupted, so the reply is ABANDONED, never aborted
    // (ModelProvider.cancel() is a documented no-op on both real providers).
    var streamingActive by mutableStateOf(false); internal set
    var streamingText by mutableStateOf(""); internal set

    var pendingAction by mutableStateOf<ProposedAction?>(null); internal set
    var biometricAuthRequested by mutableStateOf(false)
    var modelStatus by mutableStateOf(str(R.string.status_no_model_installed)); internal set
    var modelBusy by mutableStateOf(false); internal set
    var memoryVersion by mutableIntStateOf(0); private set
    var automationVersion by mutableIntStateOf(0); private set
    private var tts: TextToSpeech? = null
    val authorityManager = AuthorityManager()

    private val _pendingDrafts = MutableStateFlow<List<LearningDraft>>(emptyList())
    val pendingDrafts: StateFlow<List<LearningDraft>> = _pendingDrafts.asStateFlow()

    fun refreshDrafts() {
        _pendingDrafts.value = DraftStore.pending(db)
    }

    init {
        application.registerComponentCallbacks(memoryCallback)
        // T3.5a — the conversation list is a live flow; the thread surface
        // restores the most recently active conversation so chat survives
        // process death. The restore merges rather than replaces: if the user
        // already submitted a message while this loaded, both stay (deduped by
        // id, time-ordered), and the boot greeting is dropped once real history
        // exists. The merge decision lives in the ChatScreenState holder
        // (T3.1) and is unit-tested.
        viewModelScope.launch {
            chatHistory.observeConversations().collect { _conversations.value = it }
        }
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                chatHistory.mostRecentConversationId()?.let { id ->
                    id to chatHistory.loadTranscript(id)
                }
            }
            if (restored == null) return@launch
            activeConversationId = restored.first
            val merged = chatState.mergeTranscript(restored.second, messages.toList(), BOOT_GREETING_ID)
            messages.clear()
            messages.addAll(merged)
        }
        AutomationSettings.init(com.newax.aegis.engine.AndroidSecureSettings(application))
        TotpManager.init(application)
        ScanProgress.init(application)
        refreshDrafts()
        // Start new TriggerEngine (DB-backed, replaces old text-rule engine)
        com.newax.aegis.engine.trigger.TriggerEngine.start(application, db) { rule, ctx ->
            // NOTIFY_USER action: post an Android notification
        }
        viewModelScope.launch {
            // Single collection point — old TriggerEngine.triggerEvents delegates here
            com.newax.aegis.engine.trigger.TriggerEngine.triggerEvents.collect { systemPrompt ->
                appendChat(str(R.string.chat_processing_background), true)
                submit(systemPrompt, isBackground = true)
            }
        }
        viewModelScope.launch {
            authorityManager.events.collect { event ->
                when (event) {
                    is AuthorityEvent.Approved -> {
                        val ok = withContext(Dispatchers.IO) { controller.runAction(event.action) }
                        if (!ok) appendChat(str(R.string.auth_auto_action_failed, event.action.summary), false)
                    }
                    is AuthorityEvent.RequestBiometric -> {
                        pendingAction = event.action
                        biometricAuthRequested = true
                    }
                    is AuthorityEvent.RequestApproval -> {
                        if (event.warning != null) {
                            appendChat(str(R.string.auth_held_approval_warning, event.action.summary, event.warning), false)
                        } else {
                            appendChat(str(R.string.auth_held_approval, event.action.summary), false)
                        }
                        controller.enqueueForApproval(event.action)
                    }
                    is AuthorityEvent.Rejected -> {
                        appendChat(str(R.string.auth_action_rejected, event.reason), false)
                    }
                }
            }
        }
        controller.restoreModelIfPresent()
        memory.getRaw("knowledge_graph")?.let { com.newax.aegis.engine.KnowledgeGraph.load(it) }
        memory.getRaw("comm_log")?.let { com.newax.aegis.engine.CommunicationLog.load(it) }
        memory.getRaw("project_tracker")?.let { com.newax.aegis.engine.ProjectTracker.load(it) }
        com.newax.aegis.engine.MemoryIndexer.reindexAll()
        com.newax.aegis.engine.CommunicationLog.onInteraction = { contact, ts ->
            runBlocking {
                PersonRegistry.resolve(db, contact)?.let { eid ->
                    db.personRegistryDao().touchInteraction(eid, ts, ts)
                }
            }
        }
        OpportunisticScheduler.register {
            // Refresh PersonSnapshot commitment counts for all known persons
            db.personRegistryDao().hotPersons(50).forEach { snap ->
                PersonRegistry.refreshSnapshotCommitmentCount(db, snap.personEntityId)
            }
        }
        FileIndexer.registerOpportunisticTasks(application, db)
        FileIndexer.startWatching(application, db)
        ResourceGovernor.fire("file-scan-init", ResourceClass.LIGHT, JobPriority.P3_INDEXING) {
            FileIndexer.scanAll(application, db)
        }
        OpportunisticScheduler.start(application)
        ResourceGovernor.fire("app-scan", ResourceClass.LIGHT, JobPriority.P3_INDEXING) {
            AppScanner.scan(application, db)
            AppScanner.seedGraphTriples(db)
        }
        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ur", "PK")
        }
    }

    fun bumpMemoryVersion() { memoryVersion++ }
    fun bumpAutomationVersion() { automationVersion++ }

    /**
     * Coroutine seam for [AssistantController]: every pipeline launch runs on
     * the ViewModel scope, so generation is cancelled on teardown exactly as it
     * was before the split.
     */
    internal fun launch(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch { block() }

    /** Live-call TTS seam for [AssistantController] (speaks only background [Live Call] replies). */
    internal fun speakLive(text: String, enabled: Boolean) {
        if (enabled) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /**
     * The single place a turn enters the chat list (T3.0b). Every message the
     * chat surface shows — user turn or assistant reply — is appended here and
     * mirrored to `conversations`/`messages` through [ChatHistoryStore], so the
     * thread survives process death. The boot greeting never goes through this
     * path: it is not a turn and is not persisted.
     *
     * Persistence is best-effort and never blocks the UI: a failed write (DB
     * locked, disk full) must not drop the message from the screen.
     */
    internal fun appendChat(text: String, fromUser: Boolean) {
        val msg = ChatMessage(text, fromUser)
        messages += msg
        // Route the turn to the active conversation, creating the row on the
        // first turn of a fresh thread. The id is assigned synchronously so two
        // rapid turns (a submit plus a background reply) share one conversation.
        val conversationId = activeConversationId ?: UUID.randomUUID().toString()
        activeConversationId = conversationId
        viewModelScope.launch {
            runCatching {
                if (fromUser) chatHistory.appendUser(conversationId, text, msg.timestamp)
                else chatHistory.appendAssistant(conversationId, text, msg.timestamp)
            }
        }
    }

    // ── T3.5a — conversation shell (routes 1.1 / 1.6 / 1.11) ─────────────────

    /** Opens a conversation's transcript in the thread (1.1 → 1.2). */
    fun openConversation(conversationId: String) {
        controller.stopGeneration()
        pendingAction = null
        streamingActive = false
        activeConversationId = conversationId
        viewModelScope.launch {
            val transcript = withContext(Dispatchers.IO) {
                runCatching { chatHistory.loadTranscript(conversationId) }.getOrDefault(emptyList())
            }
            messages.clear()
            messages.addAll(transcript)
        }
    }

    /** Drops the thread back to the fresh state; the row appears on the first turn. */
    fun newChat() = resetToFreshThread()

    /** Deletes a conversation through the single transactional path (1.6). */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { chatHistory.deleteConversation(conversationId) } }
            if (activeConversationId == conversationId) resetToFreshThread()
        }
    }

    /** Renames a conversation (1.6). Blank or over-long titles are rejected here. */
    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim().take(MAX_CONVERSATION_TITLE_CHARS)
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { chatHistory.renameConversation(conversationId, trimmed, System.currentTimeMillis()) }
            }
        }
    }

    /**
     * Debounced by the screen; client-side transcript scan (1.11). A failed
     * scan (DB locked, disk error) degrades to "no results" — it must never
     * crash the search field's effect.
     */
    suspend fun searchChats(query: String): List<ConversationSearchHit> =
        withContext(Dispatchers.IO) {
            runCatching { chatHistory.search(query, SEARCH_LIMIT) }.getOrDefault(emptyList())
        }

    /**
     * Clears the chat thread (T3.0b → T3.5a): deletes the active conversation.
     * Deletion goes through [ChatHistoryStore.deleteConversation] →
     * `ConversationDao.deleteConversation` — the one transactional path (blocks,
     * then messages, then the row). Memory and saved facts are untouched.
     */
    fun clearChat() {
        activeConversationId?.let { deleteConversation(it) }
    }

    private fun resetToFreshThread() {
        controller.stopGeneration()
        pendingAction = null
        streamingActive = false
        activeConversationId = null
        messages.clear()
        messages += ChatMessage(str(R.string.chat_boot_greeting), false, id = BOOT_GREETING_ID)
    }

    // ── Model sheet (route 1.4) ────────────────────────────────────────────

    /** The imported model's display name, or "" when none is installed. */
    val modelName: String get() = controller.currentModel()?.file?.name.orEmpty()

    /** The imported model's SHA-256 (identity for the sheet; never a secret). */
    val modelSha256: String get() = controller.currentModel()?.sha256.orEmpty()

    /**
     * The model is usable when its status line reports ready. One definition,
     * shared with the Settings screen ([SettingsScreenState.isModelReady]) —
     * the thread banner (1.2) and the model sheet (1.4) read it from here.
     */
    val modelReady: Boolean get() = SettingsScreenState().isModelReady(modelStatus)

    fun unloadModel() = controller.unloadModel()

    fun reloadModel() = controller.reloadModel()

    /**
     * The transcript the export sheet (route 1.12) writes: the live thread
     * minus the boot greeting (which is not a turn and was never persisted).
     */
    fun transcriptForExport(): List<ChatMessage> = messages.filter { it.id != BOOT_GREETING_ID }

    // ── Public entry points (delegate to the pipeline; signatures unchanged) ──
    fun submit(text: String, isBackground: Boolean = false) = controller.submit(text, isBackground)

    fun approve() = controller.approve()

    fun reject() = controller.reject()

    fun executeApprovedAction() = controller.executeApprovedAction()

    fun stopGeneration() = controller.stopGeneration()

    fun importModel(uri: Uri) = controller.importModel(uri)

    override fun onCleared() {
        getApplication<Application>().unregisterComponentCallbacks(memoryCallback)
        controller.close()
        tts?.shutdown()
        super.onCleared()
    }

    private companion object {
        /** Stable id for the boot greeting, so history restore can drop it. */
        const val BOOT_GREETING_ID = "boot-greeting"
        /** How many recent conversations the client-side search scans (1.11). */
        const val SEARCH_LIMIT = 30
    }
}
