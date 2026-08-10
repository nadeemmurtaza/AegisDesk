package com.newax.aegis.platform.windows

import java.io.File

/**
 * The desktop app index: installed Windows apps enumerated from the Start Menu
 * "Programs" folders (the canonical "installed apps" list the shell itself
 * uses), searched by [search], and consumed by the planner's `find_app` so
 * `launch_app` receives an exact target instead of a guessed name.
 *
 * The [AppIndexBridge] seam is constructed only on Windows; on any other OS the
 * index is honestly empty (there is no Windows Start Menu to enumerate). The
 * enumeration is cached on first use — a Start Menu scan is a few hundred files
 * at most, and the runner queries the index per `find_app` task.
 */
class WindowsAppIndex(private val bridge: AppIndexBridge?) {

    /** Production constructor: walks the real Start Menu on Windows, empty elsewhere. */
    constructor() : this(if (isWindowsOs()) Win32AppIndexBridge() else null)

    private val cache: List<AppIndexEntry> by lazy { bridge?.enumerate().orEmpty() }

    /** Every indexed app, in name order. */
    fun all(): List<AppIndexEntry> = cache

    /**
     * Best matches for [query]: every entry whose name contains all query tokens
     * (case-insensitive), ranked exact-name first, then name-prefix, then by
     * name — so "spotify" resolves to the Spotify shortcut, not to a random
     * app with "spotify" somewhere in its name. Blank queries return nothing
     * (the caller decides how to handle "no target").
     */
    fun search(query: String): List<AppIndexEntry> {
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val needle = query.trim().lowercase()
        return cache
            .filter { entry -> tokens.all { token -> entry.name.lowercase().contains(token) } }
            .sortedWith(
                compareBy(
                    { !it.name.equals(needle, ignoreCase = true) },
                    { !it.name.lowercase().startsWith(needle) },
                    { it.name.lowercase() },
                )
            )
    }
}

/**
 * Production [AppIndexBridge] — walks the per-user and all-users Start Menu
 * "Programs" folders for `.lnk` shortcuts. Each shortcut is one installed app:
 * name = file stem, category = its Start Menu folder ("Installed" when the
 * shortcut sits directly in Programs), and the .lnk path is the exact target
 * `launch_app` launches via the shell.
 *
 * Pure JVM (no native libraries), so it is safe to construct on any OS — on
 * non-Windows the two env-var roots simply do not exist and the index is empty.
 */
class Win32AppIndexBridge : AppIndexBridge {

    override fun enumerate(): List<AppIndexEntry> {
        val roots = listOfNotNull(
            System.getenv("APPDATA")?.let { "$it\\Microsoft\\Windows\\Start Menu\\Programs" },
            System.getenv("ProgramData")?.let { "$it\\Microsoft\\Windows\\Start Menu\\Programs" },
        )
        val result = mutableListOf<AppIndexEntry>()
        roots.forEach { root ->
            runCatching { walk(root, result) }
        }
        return result
            .distinctBy { it.lnkPath.lowercase() }
            .sortedBy { it.name.lowercase() }
    }

    private fun walk(root: String, out: MutableList<AppIndexEntry>) {
        File(root).takeIf { it.isDirectory }?.walkTopDown()?.forEach { file ->
            if (file.isFile && file.extension.equals("lnk", ignoreCase = true)) {
                val name = file.nameWithoutExtension.trim()
                if (name.isNotEmpty()) {
                    val category = file.parentFile?.name
                        ?.takeIf { !it.equals("Programs", ignoreCase = true) }
                        ?: "Installed"
                    out.add(AppIndexEntry(name = name, category = category, lnkPath = file.absolutePath))
                }
            }
        }
    }
}
