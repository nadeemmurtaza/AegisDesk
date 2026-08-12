package com.newax.aegis.platform.windows

import com.newax.aegis.platform.desktop.AppWindow

/** True when the JVM is running on Windows (os.name contains "windows"). */
internal fun isWindowsOs(): Boolean =
    System.getProperty("os.name")?.lowercase()?.contains("windows") == true

/**
 * The native automation bridge the [WindowsDesktopCapability] delegates to — the
 * Windows analogue of Android's [com.newax.aegis.platform.android.SemanticAutomation].
 *
 * The seam keeps the capability logic unit-testable without a Windows OS. The
 * production implementation is [Win32UiaBridge], which drives the Win32 native
 * API tier of ARCHITECTURE.md RULE 5 (EnumWindows + control messaging + GDI
 * capture) — the cheapest available tier above UIA, which jna-platform does not
 * ship bindings for.
 *
 * Every method returns false / null on failure instead of throwing, so callers
 * get an honest typed failure rather than a crash.
 */
interface WindowsUiaBridge {

    /** Top-level visible windows: title + handle-based id. */
    fun listWindows(): List<AppWindow>

    /** Bring the first window whose title contains [appName] to the foreground;
     *  if none is open, launch it via the shell. */
    fun activateApp(appName: String): Boolean

    /** Click the first child control whose window text equals [label]. */
    fun clickSemantic(label: String): Boolean

    /** Click a control inside [app]'s window identified by [elementId] (control id or text). */
    fun clickAppElement(app: String, elementId: String): Boolean

    /** Physically click at screen coordinates — last resort (RULE 5). */
    fun clickAt(x: Float, y: Float): Boolean

    /** Type [text] into the focused control (target == null) or a matched control. */
    fun typeText(label: String?, text: String): Boolean

    /** Scroll a matched control (or the foreground window) vertically. */
    fun scroll(label: String, down: Boolean): Boolean

    /** Poll up to [timeoutMs] for a window/control matching [label]. */
    fun waitFor(label: String, timeoutMs: Long): Boolean

    /** PNG-encoded capture of the virtual screen, or null on failure. */
    fun screenshot(): ByteArray?
}
