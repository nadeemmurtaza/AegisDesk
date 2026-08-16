package com.newax.aegis.ui.state

import com.newax.aegis.assistant.ChatMessage

/**
 * The export formats route 1.12 offers (docs/UI_DESIGN.md §6 — 1.12 Export
 * conversation). The mime type drives the SAF `CreateDocument` contract; the
 * extension drives the suggested file name.
 */
enum class ChatExportFormat(val mimeType: String, val extension: String) {
    MARKDOWN("text/markdown", "md"),
    TEXT("text/plain", "txt"),
    JSON("application/json", "json"),
}

/**
 * Chat-export decisions (route 1.12) — the plain-Kotlin half of the export
 * sheet, mirroring the T3.1 state-holder pattern: rendering and naming are
 * pure and unit-tested; the Compose sheet only hands the bytes to the
 * platform (SAF `CreateDocument` → `contentResolver.openOutputStream`).
 *
 * The exported file is *content*, not chrome: the renderers emit the
 * transcript as Markdown / plain text / JSON. Untrusted text (the user's and
 * the model's messages) is data, never structure — the JSON renderer escapes
 * every string via [jsonEscape] (R12), and the Markdown/text renderers never
 * interpret message content as markup beyond the role heading.
 *
 * Role labels are parameters so the caller can localize ("You" / the product
 * name); the header line names the product fully (R14).
 */
class ChatExportState {

    /**
     * Renders the transcript for [format]. Returns "" for an empty transcript
     * — callers treat that as the honest "nothing to export" state instead of
     * writing an empty file.
     */
    fun render(
        messages: List<ChatMessage>,
        format: ChatExportFormat,
        title: String = "",
        roleLabels: Pair<String, String> = DEFAULT_ROLE_LABELS,
    ): String {
        if (messages.isEmpty()) return ""
        return when (format) {
            ChatExportFormat.MARKDOWN -> renderMarkdown(messages, title, roleLabels)
            ChatExportFormat.TEXT -> renderText(messages, roleLabels)
            ChatExportFormat.JSON -> renderJson(messages)
        }
    }

    /**
     * The suggested file name: a filesystem-safe version of the conversation
     * title plus the epoch (guarantees uniqueness across exports of the same
     * thread), with the format's extension. A blank/unusable title falls back
     * to "conversation".
     */
    fun exportFileName(title: String, format: ChatExportFormat, nowMs: Long): String {
        val safe = sanitizeTitle(title).ifBlank { FALLBACK_TITLE }
        return "$safe-${nowMs}.${format.extension}"
    }

    /**
     * Proper JSON string escaping — quotes, backslashes, control characters,
     * and the rest of the C0 range as \uXXXX. Message text is untrusted data
     * and must never be interpolated raw (R12).
     */
    fun jsonEscape(text: String): String = buildString {
        append('"')
        text.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private fun renderMarkdown(
        messages: List<ChatMessage>,
        title: String,
        roleLabels: Pair<String, String>,
    ): String = buildString {
        append("# ").append(sanitizeTitle(title).ifBlank { FALLBACK_TITLE }).append("\n\n")
        append("_Exported from Newax Aegis_").append("\n\n")
        messages.forEachIndexed { index, msg ->
            if (index > 0) append("\n\n")
            append("**").append(roleLabel(msg, roleLabels)).append(":** ")
            // A message containing its own paragraphs stays readable: blank
            // lines are preserved, nothing else is interpreted.
            append(msg.text.replace("\n", "\n\n"))
        }
    }

    private fun renderText(
        messages: List<ChatMessage>,
        roleLabels: Pair<String, String>,
    ): String = buildString {
        messages.forEachIndexed { index, msg ->
            if (index > 0) append("\n")
            append(roleLabel(msg, roleLabels)).append(": ")
            append(msg.text)
        }
    }

    private fun renderJson(messages: List<ChatMessage>): String = buildString {
        append('[')
        messages.forEachIndexed { index, msg ->
            if (index > 0) append(',')
            append("{\"role\":")
            append(jsonEscape(if (msg.fromUser) "user" else "assistant"))
            append(",\"text\":")
            append(jsonEscape(msg.text))
            append(",\"timestamp\":")
            append(msg.timestamp)
            append('}')
        }
        append(']')
    }

    private fun roleLabel(msg: ChatMessage, roleLabels: Pair<String, String>): String =
        if (msg.fromUser) roleLabels.first else roleLabels.second

    /** Filesystem-safe title: letters/digits/spaces/hyphens only, collapsed, capped. */
    private fun sanitizeTitle(title: String): String = title
        .map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else ' ' }
        .joinToString("")
        .trim()
        .replace(WHITESPACE_RUN, " ")
        .take(MAX_TITLE_CHARS)

    private companion object {
        const val FALLBACK_TITLE = "conversation"
        const val MAX_TITLE_CHARS = 40
        val DEFAULT_ROLE_LABELS: Pair<String, String> = "You" to "Newax Aegis"
        val WHITESPACE_RUN = Regex("\\s+")
    }
}
