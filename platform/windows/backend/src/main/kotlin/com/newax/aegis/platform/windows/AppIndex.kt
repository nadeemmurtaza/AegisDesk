package com.newax.aegis.platform.windows

/**
 * One installed Windows app, as the shell sees it: a shortcut under the Start
 * Menu "Programs" folders. The friendly [name] is the shortcut's file stem; the
 * [lnkPath] is the exact launch target — Windows resolves the shortcut natively
 * when opened, so `find_app` can hand `launch_app` a target that never needs a
 * second guess (Phase 5i).
 */
data class AppIndexEntry(
    /** Friendly app name — the shortcut's file name without the .lnk extension. */
    val name: String,
    /** Start Menu folder the shortcut lives in ("Accessories", "Startup", "Installed", …). */
    val category: String,
    /** Full path to the .lnk file — the exact, launchable target. */
    val lnkPath: String,
)

/**
 * The seam between [WindowsAppIndex] and the filesystem, mirroring
 * [WindowsUiaBridge]: the facade is unit-testable without a Windows OS by
 * injecting a fake bridge; the production implementation is
 * [Win32AppIndexBridge], which walks the two Start Menu "Programs" folders.
 */
interface AppIndexBridge {

    /** Every installed app currently indexed. */
    fun enumerate(): List<AppIndexEntry>
}
