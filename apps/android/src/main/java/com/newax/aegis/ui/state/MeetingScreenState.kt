package com.newax.aegis.ui.state

/** One stored meeting row, split into its display halves. */
data class MeetingEntry(
    val title: String,
    val timestampMillis: Long?,
)

/**
 * Meeting screen state — the plain-Kotlin half of the Meeting surface (T3.1).
 * The "title :: timestamp" wire format, its parsing, the blank-title guard and
 * the prepend used to live inline in the composable; they are here so the
 * decisions are unit-testable and the screen only renders.
 *
 * The holder is stateless: the meeting list itself lives in the encrypted
 * memory category behind `MainViewModel.memory`.
 */
class MeetingScreenState {

    /**
     * The stored wire format for a meeting row: `"<title> :: <epochMillis>"`.
     * The title is trimmed so a leading/trailing space never survives into the
     * stored key.
     */
    fun newEntry(title: String, nowMillis: Long): String = "${title.trim()} :: $nowMillis"

    /** Splits a stored row into its title and timestamp (null when malformed). */
    fun parseEntry(entry: String): MeetingEntry {
        val parts = entry.split(" :: ")
        return MeetingEntry(
            title = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: entry,
            timestampMillis = parts.getOrNull(1)?.toLongOrNull(),
        )
    }

    /** A meeting can only start with a non-blank title. */
    fun canStart(title: String): Boolean = title.isNotBlank()

    /** New meetings land at the top of the list (most recent first). */
    fun addMeeting(meetings: List<String>, entry: String): List<String> = listOf(entry) + meetings
}
