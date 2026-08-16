package com.newax.aegis.ui.state

/**
 * Apps index (route 4.3) — the plain-Kotlin half of the installed-apps surface.
 *
 * The enumeration itself is a PackageManager query (platform); this holder is
 * the filter half: every query word must match against the app name or its
 * package name, so "mes whats" finds "Messages" whose package is
 * `com.google.android.apps.messaging` as surely as "wh" does. Matching is
 * substring, not fuzzy — the list is short enough that substring over the
 * union of name + package is honest and predictable.
 *
 * Stateless: the app list is fetched by the screen, which only renders.
 */
class AppsIndexState {

    /** A row in the index — name is what the user reads, package is the path. */
    data class AppRow(val name: String, val packageName: String)

    /**
     * All rows when the query is blank; otherwise rows whose name or package
     * contains every non-empty query word (case-insensitive).
     */
    fun filter(query: String, apps: List<AppRow>): List<AppRow> {
        val words = query.trim()
            .lowercase()
            .split(Regex("\\W+"))
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return apps
        return apps.filter { row ->
            val hay = "${row.name} ${row.packageName}".lowercase()
            words.all { hay.contains(it) }
        }
    }
}
