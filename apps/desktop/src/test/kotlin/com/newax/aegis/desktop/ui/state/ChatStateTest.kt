package com.newax.aegis.desktop.ui.state

import com.newax.aegis.db.entity.ConversationEntity
import com.newax.aegis.db.entity.MessageEntity
import com.newax.aegis.db.entity.MessageRole
import com.newax.aegis.model.ModelDescriptor
import com.newax.aegis.model.ModelFormat
import com.newax.aegis.model.ModelProvider
import com.newax.aegis.model.ModelRequest
import com.newax.aegis.model.ModelResponse
import com.newax.aegis.model.ModelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** In-memory ChatStore for tests — the same seam RoomChatStore implements. */
class FakeChatStore : ChatStore {
    val conversations = mutableMapOf<String, ConversationEntity>()
    val messages = mutableListOf<MessageEntity>()
    var failReads = false

    override suspend fun recentConversations(limit: Int): List<ConversationRowUi> {
        if (failReads) throw IllegalStateException("corrupt database")
        return conversations.values
            .sortedByDescending { it.updatedAtMs }
            .take(limit)
            .map { c ->
                val last = messages.filter { it.conversationId == c.id }.maxByOrNull { it.timestampMs }
                ConversationRowUi(c.id, c.title.ifBlank { "New chat" }, last?.text ?: "", c.updatedAtMs)
            }
    }

    override suspend fun messagesFor(conversationId: String): List<MessageEntity> =
        messages.filter { it.conversationId == conversationId }.sortedBy { it.timestampMs }

    override suspend fun createConversation(id: String, title: String, now: Long) {
        conversations[id] = ConversationEntity(id = id, title = title, createdAtMs = now, updatedAtMs = now)
    }

    override suspend fun upsertMessage(message: MessageEntity) {
        messages.removeAll { it.id == message.id }
        messages.add(message)
    }

    override suspend fun touchConversation(conversationId: String, now: Long) {
        conversations[conversationId]?.let { conversations[conversationId] = it.copy(updatedAtMs = now) }
    }

    override suspend fun renameConversation(conversationId: String, title: String, now: Long) {
        conversations[conversationId]?.let { conversations[conversationId] = it.copy(title = title, updatedAtMs = now) }
    }

    override suspend fun deleteConversation(conversationId: String) {
        conversations.remove(conversationId)
        messages.removeAll { it.conversationId == conversationId }
    }
}

/** A ModelProvider that emits a scripted sequence of chunks. */
open class ScriptedModelProvider(
    private val chunks: List<String>,
    private val failAfter: Int = Int.MAX_VALUE,
) : ModelProvider {
    override val descriptor: ModelDescriptor = ModelDescriptor("scripted", ModelFormat.UNKNOWN, 0, "")
    override val state: StateFlow<ModelState> = MutableStateFlow(ModelState.READY)

    override fun stream(request: ModelRequest): Flow<String> = flow {
        chunks.forEachIndexed { index, chunk ->
            if (index >= failAfter) throw IllegalStateException("scripted failure")
            emit(chunk)
        }
    }

    override fun complete(request: ModelRequest): ModelResponse = ModelResponse(chunks.joinToString(""))
    override fun cancel() = Unit
    override fun close() = Unit
}

/** A ModelProvider that streams one chunk then blocks until cancelled. */
class HangModelProvider : ModelProvider {
    override val descriptor: ModelDescriptor = ModelDescriptor("hang", ModelFormat.UNKNOWN, 0, "")
    override val state: StateFlow<ModelState> = MutableStateFlow(ModelState.READY)
    override fun stream(request: ModelRequest): Flow<String> = flow {
        emit("partial ")
        kotlinx.coroutines.delay(Long.MAX_VALUE)
    }
    override fun complete(request: ModelRequest): ModelResponse = ModelResponse("partial")
    override fun cancel() = Unit
    override fun close() = Unit
}

class ChatStateTest {

    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }

    private fun state(
        store: FakeChatStore,
        provider: ModelProvider,
        nowMs: () -> Long = { 1_000L },
        ids: ArrayDeque<String> = ArrayDeque(listOf("a", "b", "c", "d", "e", "f")),
    ) = ChatState(
        scope = newScope(),
        store = store,
        modelProvider = { provider },
        clock = nowMs,
        idGen = { ids.removeFirstOrNull() ?: "auto" },
    )

    @Test
    fun `send persists the user turn and streams the assistant reply`() = runBlocking {
        val store = FakeChatStore()
        val chat = state(store, ScriptedModelProvider(listOf("Hel", "lo")))

        chat.setDraft("hi")
        chat.send()

        // The user message lands immediately; the reply streams in after.
        kotlinx.coroutines.delay(100)
        val thread = chat.thread.value as ThreadUiModel.Content
        assertEquals(2, thread.messages.size)
        assertTrue(thread.messages[0].fromUser)
        assertEquals("hi", thread.messages[0].text)
        assertFalse(thread.messages[1].fromUser)

        kotlinx.coroutines.delay(100)
        assertFalse(thread.messages[1].streaming)
        assertEquals("Hello", thread.messages[1].text)
        assertFalse(chat.draft.value.isNotBlank())
    }

    @Test
    fun `send creates the conversation with the first user turn as title`() = runBlocking {
        val store = FakeChatStore()
        val chat = state(store, ScriptedModelProvider(listOf("ok")))

        chat.setDraft("remember to buy milk")
        chat.send()
        kotlinx.coroutines.delay(100)

        assertEquals(1, store.conversations.size)
        val conversation = store.conversations.values.first()
        assertEquals("remember to buy milk", conversation.title)
        assertNotNull(chat.activeId.value)
    }

    @Test
    fun `stop cuts the stream and persists the partial reply as truncated`() = runBlocking {
        val store = FakeChatStore()
        val chat = state(store, HangModelProvider())

        chat.setDraft("stream this")
        chat.send()
        kotlinx.coroutines.delay(100)
        assertTrue((chat.thread.value as ThreadUiModel.Content).streaming)

        chat.stop()
        kotlinx.coroutines.delay(100)

        val thread = chat.thread.value as ThreadUiModel.Content
        assertFalse(thread.streaming)
        val persisted = store.messages.filter { it.fromUser == MessageRole.ASSISTANT }
        assertEquals(1, persisted.size)
        assertEquals("partial ", persisted.first().text)
        assertEquals(1, persisted.first().truncated)
    }

    @Test
    fun `a failed stream marks the bubble failed and retry re-streams`() = runBlocking {
        val store = FakeChatStore()
        // Fails on the first call, succeeds afterwards.
        val provider = object : ScriptedModelProvider(listOf("ok")) {
            var attempts = 0
            override fun stream(request: ModelRequest): Flow<String> = flow {
                attempts++
                if (attempts == 1) throw IllegalStateException("boom")
                emit("recovered")
            }
        }
        val chat = state(store, provider)

        chat.setDraft("hello")
        chat.send()
        kotlinx.coroutines.delay(150)

        var thread = chat.thread.value as ThreadUiModel.Content
        assertTrue("failed bubble present", thread.messages.any { it.failed })
        assertTrue("nothing persisted for a failed stream", store.messages.none { it.fromUser == MessageRole.ASSISTANT })

        chat.retryFailed()
        kotlinx.coroutines.delay(150)

        thread = chat.thread.value as ThreadUiModel.Content
        assertTrue("retry clears the failure", thread.messages.none { it.failed })
        assertEquals("retried reply persisted", "recovered", thread.messages.last().text)
        assertTrue(store.messages.any { it.fromUser == MessageRole.ASSISTANT && it.text == "recovered" })
    }

    @Test
    fun `delete removes the conversation and its messages`() = runBlocking {
        val store = FakeChatStore()
        store.createConversation("c1", "title", 1_000L)
        store.upsertMessage(MessageEntity("m1", "c1", MessageRole.USER, "hello", 1_000L, 0))

        val chat = state(store, ScriptedModelProvider(listOf("ok")))
        chat.refreshConversations()
        kotlinx.coroutines.delay(50)
        assertEquals(1, (chat.conversations.value as ConversationListUiModel.Content).groups.first().conversations.size)

        chat.deleteConversation("c1")
        kotlinx.coroutines.delay(100)

        assertTrue(store.conversations.isEmpty())
        assertTrue(store.messages.isEmpty())
        assertNull(chat.activeId.value)
    }

    @Test
    fun `rename updates the row title`() = runBlocking {
        val store = FakeChatStore()
        store.createConversation("c1", "old title", 1_000L)

        val chat = state(store, ScriptedModelProvider(listOf("ok")))
        chat.renameConversation("c1", "  new title  ")
        kotlinx.coroutines.delay(100)

        assertEquals("new title", store.conversations["c1"]?.title)
    }

    @Test
    fun `rename with a blank title is a no-op`() = runBlocking {
        val store = FakeChatStore()
        store.createConversation("c1", "old title", 1_000L)

        val chat = state(store, ScriptedModelProvider(listOf("ok")))
        chat.renameConversation("c1", "   ")
        kotlinx.coroutines.delay(100)

        assertEquals("old title", store.conversations["c1"]?.title)
    }

    @Test
    fun `groupByDay splits Today, Yesterday and older dates`() {
        val zone = ZoneId.systemDefault()
        val now = Instant.parse("2026-08-16T12:00:00Z").atZone(zone).toInstant().toEpochMilli()
        val day = 86_400_000L
        val rows = listOf(
            ConversationRowUi("old", "old", "", now - 5 * day),
            ConversationRowUi("yest", "yest", "", now - day),
            ConversationRowUi("today", "today", "", now - 3_600_000L),
        )
        val groups = ChatState.groupByDay(rows, now)
        assertEquals(listOf("Today", "Yesterday"), groups.map { it.label })
        assertEquals(listOf("today"), groups[0].conversations.map { it.id })
        assertEquals(listOf("yest"), groups[1].conversations.map { it.id })
        // The dated group exists and contains only the old row.
        assertEquals(1, groups.count { it.label != "Today" && it.label != "Yesterday" })
    }

    @Test
    fun `groupByDay orders each group newest first`() {
        val now = 1_000_000_000L
        val rows = listOf(
            ConversationRowUi("a", "a", "", now - 100),
            ConversationRowUi("b", "b", "", now - 50),
            ConversationRowUi("c", "c", "", now - 200),
        )
        val groups = ChatState.groupByDay(rows, now)
        assertEquals(listOf("b", "a", "c"), groups.first().conversations.map { it.id })
    }

    @Test
    fun `preview text takes the first line and truncates`() {
        assertEquals("hello", RoomChatStore.previewText("hello\nworld"))
        assertEquals("a", RoomChatStore.previewText("a\n\nb"))
        val long = "x".repeat(100)
        assertEquals(80, RoomChatStore.previewText(long).length)
        assertTrue(RoomChatStore.previewText(long).endsWith("…"))
        assertEquals("", RoomChatStore.previewText(""))
    }

    @Test
    fun `null store surfaces an honest error instead of crashing`() = runBlocking {
        val chat = ChatState(
            scope = newScope(),
            store = null,
            modelProvider = { ScriptedModelProvider(listOf("ok")) },
            clock = { 1_000L },
        )
        chat.send()
        kotlinx.coroutines.delay(50)
        val error = chat.thread.value
        assertTrue(error is ThreadUiModel.Error)
    }

    @Test
    fun `refresh with a failing store reports the named failure`() = runBlocking {
        val store = FakeChatStore()
        store.failReads = true
        val chat = ChatState(
            scope = newScope(),
            store = store,
            modelProvider = { ScriptedModelProvider(listOf("ok")) },
            clock = { 1_000L },
        )
        chat.refreshConversations()
        kotlinx.coroutines.delay(50)
        assertTrue(chat.conversations.value is ConversationListUiModel.Error)
    }
}
