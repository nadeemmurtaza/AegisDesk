package com.newax.aegis.chat

import com.newax.aegis.assistant.ChatMessage
import com.newax.aegis.db.dao.ConversationDao
import com.newax.aegis.db.entity.ConversationEntity
import com.newax.aegis.db.entity.MessageEntity
import com.newax.aegis.db.entity.MessageRole
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The chat-list title cap — one source, shared by the store and the rename UI. */
const val MAX_CONVERSATION_TITLE_CHARS = 80

/** The longest search snippet (route 1.11) — the leading part of the match. */
const val CONVERSATION_SNIPPET_CHARS = 120

/**
 * A chat-list row (UI_DESIGN route 1.1): id, title, recency. The plain-Kotlin
 * projection of [ConversationEntity] so the UI never imports the Room entity.
 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAtMs: Long,
)

/** A search hit (route 1.11): which conversation matched and the snippet. */
data class ConversationSearchHit(
    val conversationId: String,
    val title: String,
    val snippet: String,
    val updatedAtMs: Long,
)

/**
 * Persistence seam for the chat surface (T3.0b → T3.5a). The thread used to be
 * one hardwired conversation (`"main"`) that died with the process; this store
 * writes every turn to `conversations`/`messages` so threads survive restarts,
 * and — since T3.5a — addresses **any** conversation by id, which is what the
 * chat list (1.1), conversation actions (1.6) and conversation search (1.11)
 * build on.
 *
 * Messages are stacked content blocks (docs/UI_DESIGN.md §7), so the rules that
 * matter here are:
 *  - `messages.text` is the plain-text rendering — the whole message for a plain
 *    turn (the only kind the app produces today). Rich content arrives as
 *    `message_blocks` rows, which this store does not write: **a plain-text turn
 *    stores NO block rows** ("no blocks" reads as one implicit text block).
 *  - Deletion goes through [ConversationDao.deleteConversation] — the one
 *    transactional path that removes blocks, then messages, then the row, in
 *    that order. The pieces are never called from here.
 *  - A conversation row is created on demand at the first append
 *    ([ensureConversation]) and titled from the first user turn. Appending an
 *    assistant turn first (a background reply) still creates the row, with an
 *    empty title that the list shows as untitled.
 *  - Search (1.11) runs client-side over the recent conversations' transcripts:
 *    there is no FTS table, so a LIKE query would scan the same rows. This
 *    matches every message text case-insensitively and reports the most recent
 *    matching message as the snippet. Track 2 can add a proper search query
 *    later without changing this contract.
 */
interface ChatHistoryStore {

    /** The chat list, most recently active first (route 1.1). */
    fun observeConversations(): Flow<List<ConversationSummary>>

    /** One conversation's transcript, oldest first. Empty when none exists. */
    suspend fun loadTranscript(conversationId: String): List<ChatMessage>

    /** Appends a user turn, creating the conversation row (and its title) if needed. */
    suspend fun appendUser(conversationId: String, text: String, atMs: Long)

    /** Appends an assistant turn, creating the row (untitled) if needed. */
    suspend fun appendAssistant(conversationId: String, text: String, atMs: Long)

    /** The most recently active conversation, or null when there is no history. */
    suspend fun mostRecentConversationId(): String?

    /** Renames a conversation (route 1.6). Returns 1 when a row was renamed. */
    suspend fun renameConversation(conversationId: String, title: String, now: Long): Int

    /** Deletes a conversation through the single transactional path (route 1.6). */
    suspend fun deleteConversation(conversationId: String)

    /** Case-insensitive message search across the recent conversations (route 1.11). */
    suspend fun search(query: String, limit: Int): List<ConversationSearchHit>
}

/** Room-backed store over [ConversationDao]. Plain-Kotlin so it is unit-testable. */
class RoomChatHistoryStore(
    private val dao: ConversationDao,
) : ChatHistoryStore {

    override fun observeConversations(): Flow<List<ConversationSummary>> =
        dao.observeConversations().map { list -> list.map { it.toSummary() } }

    override suspend fun loadTranscript(conversationId: String): List<ChatMessage> =
        dao.messagesFor(conversationId).map { it.toChatMessage() }

    override suspend fun appendUser(conversationId: String, text: String, atMs: Long) {
        ensureConversation(conversationId, titleHint = text, atMs = atMs)
        dao.upsertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                fromUser = MessageRole.USER,
                text = text,
                timestampMs = atMs,
            )
        )
        dao.touchConversation(conversationId, atMs)
    }

    override suspend fun appendAssistant(conversationId: String, text: String, atMs: Long) {
        ensureConversation(conversationId, titleHint = "", atMs = atMs)
        dao.upsertMessage(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                fromUser = MessageRole.ASSISTANT,
                text = text,
                timestampMs = atMs,
            )
        )
        dao.touchConversation(conversationId, atMs)
    }

    override suspend fun mostRecentConversationId(): String? =
        dao.recentConversations(1).firstOrNull()?.id

    override suspend fun renameConversation(conversationId: String, title: String, now: Long): Int =
        dao.renameConversation(conversationId, title.take(MAX_CONVERSATION_TITLE_CHARS), now)

    override suspend fun deleteConversation(conversationId: String) {
        // The single correct delete path: blocks, then messages, then the row,
        // in one transaction. Never call the pieces directly.
        dao.deleteConversation(conversationId)
    }

    override suspend fun search(query: String, limit: Int): List<ConversationSearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<ConversationSearchHit>()
        for (conversation in dao.recentConversations(limit)) {
            val matches = dao.messagesFor(conversation.id)
                .filter { it.text.contains(q, ignoreCase = true) }
            if (matches.isEmpty()) continue
            hits += ConversationSearchHit(
                conversationId = conversation.id,
                title = conversation.title,
                snippet = matches.last().text.trim().take(CONVERSATION_SNIPPET_CHARS),
                updatedAtMs = conversation.updatedAtMs,
            )
        }
        return hits
    }

    private suspend fun ensureConversation(conversationId: String, titleHint: String, atMs: Long) {
        if (dao.conversationById(conversationId) != null) return
        dao.upsertConversation(
            ConversationEntity(
                id = conversationId,
                title = titleHint.take(MAX_CONVERSATION_TITLE_CHARS),
                createdAtMs = atMs,
                updatedAtMs = atMs,
            )
        )
    }

    private fun ConversationEntity.toSummary(): ConversationSummary =
        ConversationSummary(id = id, title = title, updatedAtMs = updatedAtMs)

    private fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage(
        text = text,
        fromUser = fromUser == MessageRole.USER,
        timestamp = timestampMs,
        id = id,
    )
}
