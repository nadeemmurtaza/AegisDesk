package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Conversation persistence (Track 2.4 — the schema slice behind the chat
 * routes). Two tables:
 *
 *  [ConversationEntity] — one row per chat (`conversations`): the chat-list
 *      row with a [title] (the first user turn, truncated) and created/updated
 *      timestamps. [updatedAtMs] drives the recent-first ordering.
 *  [MessageEntity] — one row per turn (`messages`): who said it ([fromUser]),
 *      the [text], when ([timestampMs]), and whether the model output was cut
 *      short ([truncated], mirroring `ModelResponse.truncated`).
 *
 * Device-local by design, like `agents`/`skills`/`sessions`: a conversation is
 * a record of what THIS device's assistant said and did — never synced in this
 * slice. Chat-history sync (the four sync columns + SyncPolicy entries) is a
 * later slice and can be added with plain ALTER TABLEs, no table rebuild.
 *
 * Room conventions as elsewhere: property names are verbatim column names
 * (no snake_case); fields with a plain Kotlin default get NO DEFAULT clause in
 * the generated schema, fields with `@ColumnInfo(defaultValue)` carry one —
 * the v20 migration must mirror that exactly.
 */
@Entity(
    tableName = "conversations",
    indices = [Index("updatedAtMs")]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    /** First user turn, truncated — the chat-list title. */
    val title: String = "",
    @ColumnInfo(defaultValue = "0")
    val createdAtMs: Long = currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val updatedAtMs: Long = currentTimeMillis()
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId", "timestampMs")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    /** Owning conversation ([ConversationEntity.id]). */
    val conversationId: String,
    /** [MessageRole.USER] (1) or [MessageRole.ASSISTANT] (0) — mirrors ChatMessage.fromUser. */
    val fromUser: Int,
    val text: String,
    @ColumnInfo(defaultValue = "0")
    val timestampMs: Long = currentTimeMillis(),
    /** 1 when the model output was cut short (ModelResponse.truncated) — 0 otherwise. */
    val truncated: Int = 0
)

object MessageRole {
    const val USER = 1
    const val ASSISTANT = 0
}
