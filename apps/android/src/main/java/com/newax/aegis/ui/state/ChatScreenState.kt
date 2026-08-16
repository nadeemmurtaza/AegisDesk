package com.newax.aegis.ui.state

import com.newax.aegis.R
import com.newax.aegis.assistant.ChatMessage

/**
 * Chat screen state — the plain-Kotlin half of the chat surface (T3.1),
 * mirroring the desktop `GoalsScreenState` pattern: all decision logic lives
 * here where it is unit-testable; the Compose screen only renders.
 *
 * The holder is stateless today (the thread itself lives in the ViewModel,
 * which owns the DB seam and the inference pipeline). Its methods are the
 * decisions the chat surface used to make inline: which chips to suggest, when
 * the thread counts as empty, where the list should scroll, what counts as a
 * sendable turn, and how a persisted transcript merges back into the live
 * list after process death (T3.0b).
 */
class ChatScreenState {

    /**
     * The starter prompts shown on the empty thread, as string-resource ids
     * (T3.2): the copy lives in strings.xml; the screen resolves each id with
     * stringResource() and submits the resolved text, so a translated chip is
     * also what the model receives.
     */
    val suggestionChips: List<Int> = listOf(
        R.string.chat_suggestion_screen,
        R.string.chat_suggestion_open_app,
        R.string.chat_suggestion_draft_reply,
        R.string.chat_suggestion_what_remember,
    )

    /**
     * The thread is "empty" when it holds at most the boot greeting and nothing
     * is being generated — the state that shows [suggestionChips] instead of
     * the message list.
     */
    fun shouldShowEmptyState(messageCount: Int, modelBusy: Boolean): Boolean =
        messageCount <= 1 && !modelBusy

    /**
     * The LazyColumn target while following the thread: the last message index,
     * or one past it while the streaming bubble is live (the bubble is not part
     * of `messages` yet). Returns -1 for an empty list, which callers treat as
     * "nothing to scroll to".
     */
    fun scrollTarget(messageCount: Int, streamingActive: Boolean): Int =
        messageCount - 1 + if (streamingActive) 1 else 0

    /**
     * What a send submits: the trimmed turn, or null for blank input. The send
     * button is disabled for blanks anyway; this is the guard for programmatic
     * submits (voice, chips).
     */
    fun submitText(input: String): String? = input.takeIf { it.isNotBlank() }?.trim()

    /**
     * Merges a persisted transcript into the live list (T3.0b) — dedup by id
     * (the live message wins), drop the boot greeting once real history exists,
     * and time-order the result. An empty transcript leaves the live list
     * untouched so the greeting stays visible on first launch.
     */
    fun mergeTranscript(
        transcript: List<ChatMessage>,
        live: List<ChatMessage>,
        bootGreetingId: String,
    ): List<ChatMessage> {
        if (transcript.isEmpty()) return live
        val existingIds = live.mapTo(HashSet()) { it.id }
        return (transcript.filter { it.id !in existingIds } + live)
            .filter { it.id != bootGreetingId }
            .sortedBy { it.timestamp }
    }
}
