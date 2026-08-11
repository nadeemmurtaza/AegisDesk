package com.newax.aegis.sync

/**
 * Path hygiene for received proximity files (R12 — untrusted input is data,
 * never instruction). A file name arrives inside the encrypted INIT meta,
 * but the sender is still an untrusted peer: the name is data, and the only
 * safe use is a single file name inside one directory we chose. [safeName]
 * strips everything that could escape that directory or confuse a shell.
 */
object ProximityFiles {

    /**
     * Reduce an arbitrary file name to a safe single path segment: no path
     * separators, no NUL, no leading dots (hides dotfiles and ".."), and a
     * sane length cap. Returns "unnamed" when nothing usable survives —
     * never null, never empty.
     */
    fun safeName(fileName: String): String {
        val cleaned = fileName
            .map { if (it == '/' || it == '\\' || it == '\u0000') '_' else it }
            .joinToString("")
            .trim()
            .trimStart('.')
            .take(120)
        return cleaned.ifBlank { "unnamed" }
    }
}
