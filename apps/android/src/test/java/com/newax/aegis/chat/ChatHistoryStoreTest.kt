package com.newax.aegis.chat

import com.newax.aegis.db.dao.ConversationDao
import com.newax.aegis.db.entity.ConversationEntity
import com.newax.aegis.db.entity.MessageBlockEntity
import com.newax.aegis.db.entity.MessageEntity
import com.newax.aegis.db.entity.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3.0b/T3.5a — chat survives process death, and since T3.5a the store is
 * conversation-scoped so the chat list (1.1), actions (1.6) and search (1.11)
 * can build on it. The Room DAO round-trips are covered instrumented
 * (`MigrationTest.conversationDaosRoundTrip` / `messageBlocksRoundTrip`); these
 * JVM tests exercise the real store mapping (ChatMessage ↔ entities, per-
 * conversation isolation, the no-blocks rule, the single delete path, rename,
 * and search) against an in-memory fake DAO — no emulator needed.
 */
class ChatHistoryStoreTest {

    @Test
    fun `empty store loads an empty transcript`() = runBlocking {
        assertTrue(RoomChatHistoryStore(FakeConversationDao()).loadTranscript("c1").isEmpty())
    }

    @Test
    fun `appended turns round-trip oldest-first with correct roles and timestamps`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)

        store.appendUser("c1", "hello", 100L)
        store.appendAssistant("c1", "hi there", 200L)
        store.appendUser("c1", "what can you do", 300L)

        val transcript = store.loadTranscript("c1")
        assertEquals(listOf("hello", "hi there", "what can you do"), transcript.map { it.text })
        assertEquals(listOf(true, false, true), transcript.map { it.fromUser })
        assertEquals(listOf(100L, 200L, 300L), transcript.map { it.timestamp })

        // Stable ids — the chat list keys on them.
        assertEquals(3, transcript.map { it.id }.distinct().size)

        // The conversation row exists and is titled from the first user turn.
        assertEquals("hello", dao.conversations.single().title)
        assertEquals(100L, dao.conversations.single().createdAtMs)
    }

    @Test
    fun `a plain text turn stores no block rows`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)

        store.appendUser("c1", "plain", 1L)
        store.appendAssistant("c1", "still plain", 2L)

        // UI_DESIGN §7: "no blocks" reads as one implicit text block — the common
        // case stays one row and a TEXT block is never written for it.
        assertTrue(dao.blocks.isEmpty())
    }

    @Test
    fun `a fresh store over the same dao reads the persisted transcript - restart`() = runBlocking {
        val dao = FakeConversationDao()

        RoomChatHistoryStore(dao).appendUser("c1", "before restart", 1L)
        RoomChatHistoryStore(dao).appendAssistant("c1", "survived", 2L)

        assertEquals(
            listOf("before restart", "survived"),
            RoomChatHistoryStore(dao).loadTranscript("c1").map { it.text }
        )
    }

    @Test
    fun `conversations are isolated - transcripts never mix`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)

        store.appendUser("work", "quarterly report", 100L)
        store.appendAssistant("work", "drafted", 200L)
        store.appendUser("personal", "buy milk", 300L)
        store.appendAssistant("personal", "added to list", 400L)

        assertEquals(listOf("quarterly report", "drafted"), store.loadTranscript("work").map { it.text })
        assertEquals(listOf("buy milk", "added to list"), store.loadTranscript("personal").map { it.text })
        assertEquals(2, dao.conversations.size)
    }

    @Test
    fun `deleteConversation removes the conversation and its messages through the one delete path`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)
        store.appendUser("c1", "to be deleted", 1L)
        store.appendAssistant("c1", "gone", 2L)

        store.deleteConversation("c1")

        assertTrue(store.loadTranscript("c1").isEmpty())
        assertTrue(dao.conversations.isEmpty())
        assertTrue(dao.messages.isEmpty())
        assertTrue(dao.blocks.isEmpty())
    }

    @Test
    fun `deleting one conversation leaves the others intact`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)
        store.appendUser("keep", "important", 1L)
        store.appendUser("drop", "disposable", 2L)

        store.deleteConversation("drop")

        assertEquals(listOf("important"), store.loadTranscript("keep").map { it.text })
        assertEquals(listOf("keep"), dao.conversations.map { it.id })
    }

    @Test
    fun `appending assistant first still creates the conversation`() = runBlocking {
        val dao = FakeConversationDao()
        RoomChatHistoryStore(dao).appendAssistant("c1", "orphan reply", 1L)

        assertEquals(1, dao.conversations.size)
        assertEquals(1, dao.messages.size)
        assertEquals(MessageRole.ASSISTANT, dao.messages.single().fromUser)
        // No user turn yet, so no title — the list shows it untitled.
        assertEquals("", dao.conversations.single().title)
    }

    @Test
    fun `turns are ordered by timestamp then id, never by insertion order`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)

        store.appendUser("c1", "second", 20L)
        store.appendAssistant("c1", "first", 10L)
        store.appendUser("c1", "third", 30L)

        assertEquals(listOf("first", "second", "third"), store.loadTranscript("c1").map { it.text })
    }

    @Test
    fun `mostRecentConversationId is the last touched conversation`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)

        assertNull(store.mostRecentConversationId())
        store.appendUser("old", "first chat", 100L)
        store.appendUser("new", "second chat", 200L)

        assertEquals("new", store.mostRecentConversationId())
    }

    @Test
    fun `rename updates the title and keeps the transcript`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)
        store.appendUser("c1", "auto title", 1L)

        val renamed = store.renameConversation("c1", "Renamed thread", 50L)

        assertEquals(1, renamed)
        assertEquals("Renamed thread", dao.conversations.single().title)
        assertEquals(listOf("auto title"), store.loadTranscript("c1").map { it.text })
    }

    @Test
    fun `rename of an unknown conversation returns zero`() = runBlocking {
        val store = RoomChatHistoryStore(FakeConversationDao())
        assertEquals(0, store.renameConversation("missing", "x", 1L))
    }

    @Test
    fun `search finds matching messages across conversations with the latest snippet`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)
        store.appendUser("work", "Quarterly report due Friday", 100L)
        store.appendAssistant("work", "I drafted the report", 200L)
        store.appendUser("personal", "buy milk and eggs", 300L)

        val hits = store.search("report", limit = 10)

        assertEquals(1, hits.size)
        assertEquals("work", hits.single().conversationId)
        // The snippet comes from the most recent matching message.
        assertEquals("I drafted the report", hits.single().snippet)
    }

    @Test
    fun `search is case-insensitive and matches inside words`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)
        store.appendUser("c1", "The MEETING is at noon", 1L)

        assertEquals(1, store.search("meeting", 10).size)
        assertEquals(1, store.search("MEETING", 10).size)
        assertEquals(1, store.search("eeting", 10).size)
    }

    @Test
    fun `search - blank query and no matches both return empty`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)
        store.appendUser("c1", "nothing relevant here", 1L)

        assertTrue(store.search("", 10).isEmpty())
        assertTrue(store.search("   ", 10).isEmpty())
        assertTrue(store.search("zzzz", 10).isEmpty())
    }

    @Test
    fun `observeConversations emits recent-first summaries`() = runBlocking {
        val dao = FakeConversationDao()
        val store = RoomChatHistoryStore(dao)
        store.appendUser("old", "first", 100L)
        store.appendUser("new", "second", 200L)

        val summaries = store.observeConversations().first()

        assertEquals(listOf("new", "old"), summaries.map { it.id })
        assertEquals(listOf("second", "first"), summaries.map { it.title })
    }
}

/** In-memory [ConversationDao] — Room's generated implementation needs an Android context. */
private class FakeConversationDao : ConversationDao {

    val conversations = mutableListOf<ConversationEntity>()
    val messages = mutableListOf<MessageEntity>()
    val blocks = mutableListOf<MessageBlockEntity>()

    override suspend fun upsertConversation(conversation: ConversationEntity) {
        conversations.removeAll { it.id == conversation.id }
        conversations += conversation
    }

    override suspend fun conversationById(conversationId: String): ConversationEntity? =
        conversations.firstOrNull { it.id == conversationId }

    override fun observeConversations(): Flow<List<ConversationEntity>> =
        flowOf(conversations.sortedByDescending { it.updatedAtMs })

    override suspend fun recentConversations(limit: Int): List<ConversationEntity> =
        conversations.sortedByDescending { it.updatedAtMs }.take(limit)

    override suspend fun renameConversation(conversationId: String, title: String, now: Long): Int {
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) return 0
        conversations[index] = conversations[index].copy(title = title, updatedAtMs = now)
        return 1
    }

    override suspend fun touchConversation(conversationId: String, now: Long): Int {
        val index = conversations.indexOfFirst { it.id == conversationId }
        if (index < 0) return 0
        conversations[index] = conversations[index].copy(updatedAtMs = now)
        return 1
    }

    override suspend fun upsertMessage(message: MessageEntity) {
        messages.removeAll { it.id == message.id }
        messages += message
    }

    override suspend fun messagesFor(conversationId: String): List<MessageEntity> =
        orderedMessages(conversationId)

    override fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        flowOf(orderedMessages(conversationId))

    override suspend fun messageCount(conversationId: String): Long =
        messages.count { it.conversationId == conversationId }.toLong()

    override suspend fun upsertBlocks(blocks: List<MessageBlockEntity>) {
        this.blocks.removeAll { b -> blocks.any { it.id == b.id } }
        this.blocks += blocks
    }

    override suspend fun blocksFor(messageId: String): List<MessageBlockEntity> =
        blocks.filter { it.messageId == messageId }.sortedBy { it.position }

    override fun observeBlocks(conversationId: String): Flow<List<MessageBlockEntity>> {
        val ids = messages.filter { it.conversationId == conversationId }.map { it.id }.toSet()
        return flowOf(blocks.filter { it.messageId in ids }.sortedBy { it.position })
    }

    override suspend fun blocksOfType(conversationId: String, type: String): List<MessageBlockEntity> {
        val ids = messages.filter { it.conversationId == conversationId }.map { it.id }.toSet()
        return blocks.filter { it.messageId in ids && it.type == type }.sortedBy { it.position }
    }

    override suspend fun deleteBlocks(messageId: String): Int {
        val count = blocks.count { it.messageId == messageId }
        blocks.removeAll { it.messageId == messageId }
        return count
    }

    override suspend fun deleteBlocksIn(conversationId: String): Int {
        val ids = messages.filter { it.conversationId == conversationId }.map { it.id }.toSet()
        val count = blocks.count { it.messageId in ids }
        blocks.removeAll { it.messageId in ids }
        return count
    }

    override suspend fun deleteMessages(conversationId: String): Int {
        val count = messages.count { it.conversationId == conversationId }
        messages.removeAll { it.conversationId == conversationId }
        return count
    }

    override suspend fun deleteConversationRow(conversationId: String): Int {
        val count = conversations.count { it.id == conversationId }
        conversations.removeAll { it.id == conversationId }
        return count
    }

    private fun orderedMessages(conversationId: String): List<MessageEntity> =
        messages
            .filter { it.conversationId == conversationId }
            .sortedWith(compareBy({ it.timestampMs }, { it.id }))
}
