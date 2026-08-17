package com.newax.aegis.desktop.ui.state

import com.newax.aegis.db.dao.ConversationDao
import com.newax.aegis.db.entity.ConversationEntity
import com.newax.aegis.db.entity.MessageEntity
import com.newax.aegis.db.entity.MessageRole
import com.newax.aegis.model.ModelProvider
import com.newax.aegis.model.ModelRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// ── UI models (routes 1.1 conversation list + 1.2 thread) ───────────────────

/** One row in the conversation list — title, two-line preview, relative time. */
data class ConversationRowUi(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAtMs: Long,
)

/** A dated group in the conversation list (Today / Yesterday / MMM d). */
data class ConversationGroupUi(
    val label: String,
    val conversations: List<ConversationRowUi>,
)

/** The sidebar list state — loading, groups, or an honest named failure. */
sealed interface ConversationListUiModel {
    data object Loading : ConversationListUiModel
    data class Content(val groups: List<ConversationGroupUi>) : ConversationListUiModel
    data class Error(val message: String) : ConversationListUiModel
}

/** One bubble in the thread. */
data class ThreadMessageUi(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val timestampMs: Long,
    /** True while an assistant reply is streaming in; the bubble shows live text. */
    val streaming: Boolean = false,
    /** True when the stream failed; the bubble shows the failure block with Retry. */
    val failed: Boolean = false,
)

/** The thread pane state — loading, transcript, or an honest named failure. */
sealed interface ThreadUiModel {
    data object Loading : ThreadUiModel
    data class Content(
        val messages: List<ThreadMessageUi>,
        /** True while an assistant reply is in flight (drives Send ↔ Stop). */
        val streaming: Boolean,
    ) : ThreadUiModel
    data class Error(val message: String) : ThreadUiModel
}

// ── Persistence seam (testable without Room) ────────────────────────────────

/**
 * The persistence surface the chat needs. A narrow seam over
 * [ConversationDao] so [ChatState]'s send/stop/rename/delete logic is plain
 * Kotlin — the same injectable-seam pattern as the other desktop state
 * holders.
 */
interface ChatStore {
    suspend fun recentConversations(limit: Int): List<ConversationRowUi>
    suspend fun messagesFor(conversationId: String): List<MessageEntity>
    suspend fun createConversation(id: String, title: String, now: Long)
    suspend fun upsertMessage(message: MessageEntity)
    suspend fun touchConversation(conversationId: String, now: Long)
    suspend fun renameConversation(conversationId: String, title: String, now: Long)
    suspend fun deleteConversation(conversationId: String)
}

/** Room-backed [ChatStore] — conversation rows live in the desktop app DB
 *  (`~/.aegis/aegis.db`), previews come from each conversation's last message. */
class RoomChatStore(private val dao: ConversationDao) : ChatStore {

    override suspend fun recentConversations(limit: Int): List<ConversationRowUi> =
        dao.recentConversations(limit).map { conversation ->
            val last = dao.messagesFor(conversation.id).lastOrNull()
            ConversationRowUi(
                id = conversation.id,
                title = conversation.title.ifBlank { "New chat" },
                preview = last?.text?.let(::previewText).orEmpty(),
                updatedAtMs = conversation.updatedAtMs,
            )
        }

    override suspend fun messagesFor(conversationId: String): List<MessageEntity> =
        dao.messagesFor(conversationId)

    override suspend fun createConversation(id: String, title: String, now: Long) =
        dao.upsertConversation(ConversationEntity(id = id, title = title, createdAtMs = now, updatedAtMs = now))

    override suspend fun upsertMessage(message: MessageEntity) = dao.upsertMessage(message)

    override suspend fun touchConversation(conversationId: String, now: Long) {
        dao.touchConversation(conversationId, now)
    }

    override suspend fun renameConversation(conversationId: String, title: String, now: Long) {
        dao.renameConversation(conversationId, title, now)
    }

    override suspend fun deleteConversation(conversationId: String) {
        dao.deleteConversation(conversationId)
    }

    companion object {
        /** The two-line preview: first line of the last message, truncated. */
        fun previewText(text: String, maxLength: Int = 80): String =
            text.lineSequence().firstOrNull().orEmpty()
                .replace('\n', ' ').trim()
                .let { if (it.length > maxLength) it.take(maxLength - 1).trimEnd() + "…" else it }
    }
}

// ── The state holder ────────────────────────────────────────────────────────

/**
 * The chat state (UI_DESIGN.md §6.3, routes 1.1 + 1.2): the conversation list,
 * the active thread, and the composer — including live streaming through the
 * shared [ModelProvider] contract.
 *
 * Plain Kotlin against the [ChatStore] seam and a [ModelProvider], so every
 * decision (grouping, title derivation, send/stop/retry, delete, rename) is
 * unit-testable without Room or the process-wide holders.
 *
 * @param store null when the database failed to open — the thread then shows
 *   an honest error state instead of crashing (R9).
 */
class ChatState(
    private val scope: CoroutineScope,
    private val store: ChatStore?,
    private val modelProvider: () -> ModelProvider,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {
    private val _conversations = MutableStateFlow<ConversationListUiModel>(ConversationListUiModel.Loading)
    val conversations: StateFlow<ConversationListUiModel> = _conversations.asStateFlow()

    private val _thread = MutableStateFlow<ThreadUiModel>(ThreadUiModel.Loading)
    val thread: StateFlow<ThreadUiModel> = _thread.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private var streamJob: Job? = null

    /** In-memory transcript of the active thread; persisted rows are loaded from the store. */
    private var threadMessages: List<ThreadMessageUi> = emptyList()
    private var streaming: Boolean = false

    /** The prompt being streamed — kept for Retry when the stream fails. */
    private var pendingPrompt: String? = null

    // ── list (route 1.1) ───────────────────────────────────────────────────

    fun refreshConversations() {
        scope.launch {
            val store = store ?: run {
                _conversations.value = ConversationListUiModel.Error("Chat database unavailable")
                return@launch
            }
            runCatching { store.recentConversations(50) }
                .onSuccess { rows ->
                    val groups = groupByDay(rows, clock())
                    _conversations.value = if (groups.isEmpty()) {
                        ConversationListUiModel.Content(emptyList())
                    } else {
                        ConversationListUiModel.Content(groups)
                    }
                }
                .onFailure { e ->
                    _conversations.value = ConversationListUiModel.Error(e.message ?: "Could not load conversations")
                }
        }
    }

    fun newChat() {
        stopStreaming(savePartial = true)
        threadMessages = emptyList()
        streaming = false
        _activeId.value = null
        _draft.value = ""
        _thread.value = ThreadUiModel.Content(messages = emptyList(), streaming = false)
    }

    fun openConversation(conversationId: String) {
        stopStreaming(savePartial = true)
        _activeId.value = conversationId
        _draft.value = ""
        _thread.value = ThreadUiModel.Loading
        scope.launch {
            val store = store ?: run {
                _thread.value = ThreadUiModel.Error("Chat database unavailable")
                return@launch
            }
            runCatching { store.messagesFor(conversationId) }
                .onSuccess { messages ->
                    threadMessages = messages.map { it.toUi() }
                    _thread.value = ThreadUiModel.Content(messages = threadMessages, streaming = false)
                }
                .onFailure { e ->
                    _thread.value = ThreadUiModel.Error(e.message ?: "Could not load this conversation")
                }
        }
    }

    fun deleteConversation(conversationId: String) {
        scope.launch {
            store?.deleteConversation(conversationId)
            if (_activeId.value == conversationId) {
                newChat()
            } else {
                refreshConversations()
            }
        }
    }

    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        scope.launch {
            store?.renameConversation(conversationId, trimmed, clock())
            refreshConversations()
        }
    }

    // ── composer (route 1.2) ───────────────────────────────────────────────

    fun setDraft(text: String) {
        _draft.value = text
    }

    /** Sends the draft as a user turn, then streams the assistant reply. */
    fun send() {
        val prompt = _draft.value.trim()
        if (prompt.isBlank() || streamJob?.isActive == true) return
        val store = store ?: run {
            _thread.value = ThreadUiModel.Error("Chat database unavailable — restart the app to retry")
            return
        }
        _draft.value = ""

        scope.launch {
            // A conversation is created lazily on the first message; its title
            // is the first user turn, truncated (route 1.1 row title).
            val conversationId = _activeId.value ?: run {
                val id = idGen()
                val title = prompt.lineSequence().firstOrNull().orEmpty().take(TITLE_MAX)
                store.createConversation(id, title, clock())
                _activeId.value = id
                id
            }
            val userMessage = MessageEntity(
                id = idGen(),
                conversationId = conversationId,
                fromUser = MessageRole.USER,
                text = prompt,
                timestampMs = clock(),
            )
            store.upsertMessage(userMessage)
            store.touchConversation(conversationId, clock())
            threadMessages = threadMessages + userMessage.toUi()
            pushThread()

            // The assistant reply streams into an in-memory bubble; it is
            // persisted once the stream completes (or Stop cuts it short).
            val assistantId = idGen()
            val assistantTimestamp = clock()
            streaming = true
            pendingPrompt = prompt
            threadMessages = threadMessages + ThreadMessageUi(
                id = assistantId, fromUser = false, text = "", timestampMs = assistantTimestamp, streaming = true,
            )
            pushThread()

            val text = StringBuilder()
            streamJob = scope.launch {
                try {
                    modelProvider().stream(ModelRequest(text = prompt)).collect { chunk ->
                        text.append(chunk)
                        threadMessages = threadMessages.map {
                            if (it.id == assistantId) it.copy(text = text.toString()) else it
                        }
                        pushThread()
                    }
                    // Stream completed — persist the finished reply.
                    val full = text.toString()
                    store.upsertMessage(
                        MessageEntity(
                            id = assistantId,
                            conversationId = conversationId,
                            fromUser = MessageRole.ASSISTANT,
                            text = full,
                            timestampMs = assistantTimestamp,
                            truncated = 0,
                        )
                    )
                    store.touchConversation(conversationId, clock())
                    threadMessages = threadMessages.map {
                        if (it.id == assistantId) it.copy(streaming = false) else it
                    }
                    pendingPrompt = null
                    streaming = false
                    pushThread()
                    refreshConversations()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Honest failure block: keep the bubble, mark it failed,
                    // keep the prompt for Retry (UI_DESIGN.md §1.2).
                    threadMessages = threadMessages.map {
                        if (it.id == assistantId) it.copy(streaming = false, failed = true, text = text.toString()) else it
                    }
                    streaming = false
                    pushThread()
                }
            }
        }
    }

    /** Cancels the in-flight stream; the partial reply is persisted as truncated. */
    fun stop() = stopStreaming(savePartial = true)

    /** Retries the last failed assistant reply (the bubble's Retry action). */
    fun retryFailed() {
        val prompt = pendingPrompt ?: return
        if (streamJob?.isActive == true) return
        val store = store ?: return
        val conversationId = _activeId.value ?: return

        // Clear the failed bubble so the retry streams into a fresh one.
        val failedId = threadMessages.firstOrNull { it.failed }?.id
        if (failedId != null) {
            threadMessages = threadMessages.filterNot { it.id == failedId }
        }
        pendingPrompt = null
        scope.launch {
            val assistantId = idGen()
            val assistantTimestamp = clock()
            streaming = true
            pendingPrompt = prompt
            threadMessages = threadMessages + ThreadMessageUi(
                id = assistantId, fromUser = false, text = "", timestampMs = assistantTimestamp, streaming = true,
            )
            pushThread()

            val text = StringBuilder()
            streamJob = scope.launch {
                try {
                    modelProvider().stream(ModelRequest(text = prompt)).collect { chunk ->
                        text.append(chunk)
                        threadMessages = threadMessages.map {
                            if (it.id == assistantId) it.copy(text = text.toString()) else it
                        }
                        pushThread()
                    }
                    store.upsertMessage(
                        MessageEntity(
                            id = assistantId,
                            conversationId = conversationId,
                            fromUser = MessageRole.ASSISTANT,
                            text = text.toString(),
                            timestampMs = assistantTimestamp,
                            truncated = 0,
                        )
                    )
                    store.touchConversation(conversationId, clock())
                    threadMessages = threadMessages.map {
                        if (it.id == assistantId) it.copy(streaming = false) else it
                    }
                    pendingPrompt = null
                    streaming = false
                    pushThread()
                    refreshConversations()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    threadMessages = threadMessages.map {
                        if (it.id == assistantId) it.copy(streaming = false, failed = true, text = text.toString()) else it
                    }
                    streaming = false
                    pushThread()
                }
            }
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    /** Persists any partial stream as a truncated assistant message, then stops. */
    private fun stopStreaming(savePartial: Boolean) {
        val job = streamJob ?: return
        streamJob = null
        job.cancel()
        streaming = false
        pendingPrompt = null
        if (savePartial) {
            val store = store ?: return
            val conversationId = _activeId.value ?: return
            val partial = threadMessages.firstOrNull { it.streaming } ?: return
            if (partial.text.isNotBlank()) {
                scope.launch {
                    store.upsertMessage(
                        MessageEntity(
                            id = partial.id,
                            conversationId = conversationId,
                            fromUser = MessageRole.ASSISTANT,
                            text = partial.text,
                            timestampMs = partial.timestampMs,
                            truncated = 1,
                        )
                    )
                    store.touchConversation(conversationId, clock())
                    refreshConversations()
                }
            }
        }
        threadMessages = threadMessages.map { if (it.streaming) it.copy(streaming = false) else it }
        pushThread()
    }

    private fun pushThread() {
        _thread.value = ThreadUiModel.Content(messages = threadMessages, streaming = streaming)
    }

    private fun MessageEntity.toUi() = ThreadMessageUi(
        id = id,
        fromUser = fromUser == MessageRole.USER,
        text = text,
        timestampMs = timestampMs,
    )

    companion object {
        const val TITLE_MAX = 40

        private val DAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")

        /**
         * Groups conversation rows into Today / Yesterday / dated groups, most
         * recently active first. Tested for the day boundaries.
         */
        fun groupByDay(rows: List<ConversationRowUi>, nowMs: Long): List<ConversationGroupUi> {
            val zone = ZoneId.systemDefault()
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            return rows
                .sortedByDescending { it.updatedAtMs }
                .groupBy { row ->
                    val date = Instant.ofEpochMilli(row.updatedAtMs).atZone(zone).toLocalDate()
                    when (date) {
                        today -> "Today"
                        today.minusDays(1) -> "Yesterday"
                        else -> DAY_FORMATTER.format(date)
                    }
                }
                .map { (label, group) -> ConversationGroupUi(label, group) }
                .sortedWith(compareByDescending<ConversationGroupUi> { groupOrder(it.label) })
        }

        private fun groupOrder(label: String): Int = when (label) {
            "Today" -> 2
            "Yesterday" -> 1
            else -> 0
        }
    }
}
