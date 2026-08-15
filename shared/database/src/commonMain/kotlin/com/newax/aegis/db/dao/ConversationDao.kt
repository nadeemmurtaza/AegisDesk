package com.newax.aegis.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.newax.aegis.db.entity.ConversationEntity
import com.newax.aegis.db.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for the chat surface (Track 2.4): `conversations` (chat-list
 * rows, recent-first by [ConversationEntity.updatedAtMs]) and `messages`
 * (per-turn rows, oldest-first within a conversation). Device-local like the
 * agents/skills tables — see [ConversationEntity] for the sync rationale.
 *
 * The reactive queries (`observe*`) feed the chat screens directly (Room Flow);
 * the suspend queries serve one-shot reads. Deleting a conversation removes its
 * messages in one transaction — the single path to that sink ([deleteConversation]).
 */
@Dao
interface ConversationDao {

    // ── conversations ─────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun conversationById(conversationId: String): ConversationEntity?

    /** The chat list, most recently active first. */
    @Query("SELECT * FROM conversations ORDER BY updatedAtMs DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY updatedAtMs DESC LIMIT :limit")
    suspend fun recentConversations(limit: Int = 50): List<ConversationEntity>

    @Query("UPDATE conversations SET title = :title, updatedAtMs = :now WHERE id = :conversationId")
    suspend fun renameConversation(conversationId: String, title: String, now: Long): Int

    /** Bubbles the conversation to the top of the recent list after a new message. */
    @Query("UPDATE conversations SET updatedAtMs = :now WHERE id = :conversationId")
    suspend fun touchConversation(conversationId: String, now: Long): Int

    // ── messages ──────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: MessageEntity)

    /** The transcript, oldest first. */
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMs ASC, id ASC")
    suspend fun messagesFor(conversationId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestampMs ASC, id ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun messageCount(conversationId: String): Long

    // ── delete (one transaction — no orphan messages) ─────────────────────────

    @Transaction
    suspend fun deleteConversation(conversationId: String) {
        deleteMessages(conversationId)
        deleteConversationRow(conversationId)
    }

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessages(conversationId: String): Int

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversationRow(conversationId: String): Int
}
