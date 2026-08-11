package com.newax.aegis.desktop.ui.state

import com.newax.aegis.platform.windows.AppIndexEntry
import com.newax.aegis.platform.windows.WindowsAppIndex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Apps screen state — the Start Menu app index behind the search box (the
 * `apps [query]` CLI logic lifted into a state holder). Plain Kotlin: the query
 * lives in a [StateFlow] the Compose screen collects; [matches] is a pure
 * function of the live index, so the whole surface is testable with a fake
 * [WindowsAppIndex] bridge.
 */
class AppsScreenState(
    private val indexProvider: () -> WindowsAppIndex?,
) {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
    }

    /** The live index, or null when uninitialized — the Windows-only empty state. */
    fun index(): WindowsAppIndex? = indexProvider()

    /**
     * The current match set: every indexed app when the query is blank, else the
     * index's ranked search results. Empty when there is no index or no match —
     * the screen renders the empty state.
     */
    fun matches(): List<AppIndexEntry> {
        val index = indexProvider() ?: return emptyList()
        val q = _query.value.trim()
        return if (q.isEmpty()) index.all() else index.search(q)
    }
}
