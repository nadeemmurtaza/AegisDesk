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
    /**
     * The message's plain-text rendering — the whole message for a plain turn,
     * and otherwise the flattened text used for the chat-list snippet, search,
     * and any surface that cannot render blocks.
     *
     * It is **not** the whole content of a rich message. That lives in
     * [MessageBlockEntity]; see the note there.
     */
    val text: String,
    @ColumnInfo(defaultValue = "0")
    val timestampMs: Long = currentTimeMillis(),
    /** 1 when the model output was cut short (ModelResponse.truncated) — 0 otherwise. */
    val truncated: Int = 0
)

/**
 * One content block of a message (`docs/UI_DESIGN.md` §7).
 *
 * §7 is explicit that "an assistant message can stack several blocks" and names
 * ten kinds — code, image, documents, MCQ, thought, approval and the rest — so a
 * message is a *list* of blocks, not a string. Modelling it as a string now is
 * the mistake the T2.4 brief specifically warned against ("designing for plain
 * text now means a second migration later"), and the first message carrying a
 * code block or an approval card would force exactly that migration.
 *
 * ### Why a child table rather than a JSON column on `messages`
 *
 * Blocks are queried and rendered independently — the artifact panel (§7.1) and
 * the step list (§7.2) both want to find blocks of one kind across a
 * conversation, and a JSON blob makes that a full scan plus a parse. Rows also
 * let a streaming assistant append a block without rewriting the message.
 *
 * ### Why `type` is a string
 *
 * An unknown kind must round-trip rather than fail: a message written by a newer
 * build, restored from backup into an older one, keeps its blocks and renders
 * the unknown ones as their [content]. An integer enum ordinal cannot do that
 * safely — reordering the enum silently reinterprets stored rows.
 *
 * A plain-text message needs no row here at all: [MessageEntity.text] is the
 * whole message, and a reader treats "no blocks" as one implicit text block.
 * That keeps the common case one row, as it was before this table existed.
 */
@Entity(
    tableName = "message_blocks",
    indices = [Index("messageId", "position")]
)
data class MessageBlockEntity(
    @PrimaryKey val id: String,
    /** Owning message ([MessageEntity.id]). */
    val messageId: String,
    /** 0-based render order within the message. Stacked top to bottom (§7). */
    val position: Int,
    /** A [MessageBlockType] value. Unknown values are preserved, not dropped. */
    val type: String,
    /** The block's primary content: the paragraph, the source, the caption, the question. */
    @ColumnInfo(defaultValue = "")
    val content: String = "",
    /**
     * Kind-specific detail as JSON — code language, image alt text and URI, MCQ
     * options, step status and the policy rule that matched, artifact id/size.
     * JSON here (and not columns) because each kind carries a different shape and
     * §7's list is explicitly open-ended; the *identity* of a block is `type` +
     * `position`, which are columns.
     */
    @ColumnInfo(defaultValue = "")
    val metadata: String = ""
)

/**
 * The block kinds `docs/UI_DESIGN.md` §7 defines. String constants rather than
 * an enum so that persistence tolerates a value it does not know — see
 * [MessageBlockEntity].
 */
object MessageBlockType {
    /** A plain paragraph. Never boxed (§7). */
    const val TEXT = "text"
    const val COPYABLE_TEXT = "copyable_text"
    const val CODE = "code"
    const val IMAGE = "image"
    const val IMAGE_GENERATION = "image_generation"
    const val DOCUMENTS = "documents"
    /** MCQ / choice card; `metadata` carries the options, last one always Custom…. */
    const val CHOICE = "choice"
    /** Collapsible reasoning block. */
    const val THOUGHT = "thought"
    /** Compact chip that opens the artifact panel (§7.1). */
    const val ARTIFACT = "artifact"
    /** One unit of FLOW C, backed by AgentStream (§7.2). */
    const val STEP = "step"
    /** Typed action summary with Approve / Reject (§7). */
    const val APPROVAL = "approval"

    val known: Set<String> = setOf(
        TEXT, COPYABLE_TEXT, CODE, IMAGE, IMAGE_GENERATION,
        DOCUMENTS, CHOICE, THOUGHT, ARTIFACT, STEP, APPROVAL,
    )
}

object MessageRole {
    const val USER = 1
    const val ASSISTANT = 0
}
