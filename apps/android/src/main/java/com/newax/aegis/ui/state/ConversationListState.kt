package com.newax.aegis.ui.state

import com.newax.aegis.chat.MAX_CONVERSATION_TITLE_CHARS

/**
 * Conversation-list state (T3.5a — the chat shell, routes 1.1/1.6/1.11): the
 * plain-Kotlin half of [com.newax.aegis.ConversationsScreen], following the
 * desktop `GoalsScreenState` pattern — decision logic here, Compose rendering
 * only. All of it is unit-tested without an Android runtime.
 *
 * The data itself (the conversation rows, the search hits) lives in the
 * ViewModel/Store; this holder owns the *decisions* the list surface used to
 * make inline: what a row's time label says, what counts as a valid rename,
 * and when the search field is active.
 */
class ConversationListState {

    /**
     * The relative time label for a chat-list row ("just now", "5m ago",
     * "3h ago", "2d ago", …). Takes [now] explicitly so tests are
     * deterministic. Mirrors the pre-existing convention in PeopleScreen —
     * deliberately literal English, per the T3.2 content boundary (runtime
     * relative-date formatting is not translated).
     */
    fun relativeTimeLabel(updatedAtMs: Long, now: Long): String {
        val minutes = (now - updatedAtMs).coerceAtLeast(0) / MINUTE_MS
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 24 * 60 -> "${minutes / 60}h ago"
            minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)}d ago"
            minutes < 30 * 24 * 60 -> "${minutes / (7 * 24 * 60)}w ago"
            minutes < 365 * 24 * 60 -> "${minutes / (30 * 24 * 60)}mo ago"
            else -> "${minutes / (365 * 24 * 60)}y ago"
        }
    }

    /**
     * Validates a rename (route 1.6): trimmed, capped at
     * [MAX_CONVERSATION_TITLE_CHARS], and null when blank — the caller disables
     * Save on null. The same cap the store applies when creating titles, so the
     * UI can never offer a name the store would silently truncate further.
     */
    fun renameTitle(title: String): String? =
        title.trim().takeIf { it.isNotEmpty() }?.take(MAX_CONVERSATION_TITLE_CHARS)

    /** The search field is live once the user has typed something (route 1.11). */
    fun isSearchActive(query: String): Boolean = query.isNotBlank()

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
