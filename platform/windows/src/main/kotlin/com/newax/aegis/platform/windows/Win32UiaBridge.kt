package com.newax.aegis.platform.windows

import com.newax.aegis.platform.desktop.AppWindow
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.win32.StdCallLibrary
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Production [WindowsUiaBridge] driving the Win32 native API tier of
 * ARCHITECTURE.md RULE 5 ("native API/CLI → browser DOM → UI Automation →
 * accessibility nodes → vision → coordinates").
 *
 * Why native Win32 instead of UIA COM patterns: the official jna-platform jar
 * ships NO UI Automation bindings (verified against the upstream repo), and the
 * only third-party JVM UIA wrapper (mmarquee/ui-automation 0.7.0) has been
 * dormant since 2020 — a dependency-liability signal per docs/rules/compatibility.md.
 * The operations below (window enumeration, control-text matching, WM_CHAR input,
 * WM_VSCROLL, BM_CLICK, capture) are genuine *semantic* automation of
 * standard Win32 controls and are the cheapest supported tier for them.
 *
 * Two functions jna-platform 5.13.0 does NOT map are declared locally:
 * [User32Ext.GetDlgCtrlID] and [User32Ext.mouse_event]. Screenshots use the
 * JDK's Robot instead of a GDI copy — same virtual-screen pixels, no HBITMAP
 * conversion plumbing (GDI32Util has no getBufferedImage in 5.13.0).
 *
 * Windows-only: on other OSes the JNA native library cannot load. Construct this
 * only when [isWindowsOs]; the capability guards construction.
 *
 * Every operation returns false/null on failure — no exceptions escape, so the
 * capability can report a typed failure instead of crashing.
 */
class Win32UiaBridge : WindowsUiaBridge {

    private val user32 = User32.INSTANCE
    private val user32Ext = Native.load("user32", User32Ext::class.java)

    /** USER32 entry points absent from jna-platform 5.13.0, declared locally. */
    interface User32Ext : StdCallLibrary {
        fun GetDlgCtrlID(hWnd: WinDef.HWND): Int

        fun mouse_event(dwFlags: Int, dx: Int, dy: Int, dwData: Int, dwExtraInfo: Pointer?)
    }

    // ── Window constants ────────────────────────────────────────────────────
    private val SW_RESTORE = 9
    private val WM_CHAR    = 0x0102
    private val WM_VSCROLL = 0x0115
    private val BM_CLICK   = 0x00F5
    private val SB_LINE_UP   = 0
    private val SB_LINE_DOWN = 1
    private val SB_PAGE_UP   = 2
    private val SB_PAGE_DOWN = 3
    private val MOUSEEVENTF_LEFTDOWN = 0x0002
    private val MOUSEEVENTF_LEFTUP   = 0x0004
    private val SM_XVIRTUALSCREEN = 76
    private val SM_YVIRTUALSCREEN = 77
    private val SM_CXVIRTUALSCREEN = 78
    private val SM_CYVIRTUALSCREEN = 79

    private val maxTextLen = 512

    // ── Window enumeration helpers ──────────────────────────────────────────

    private fun windowTitle(hwnd: WinDef.HWND): String {
        val buf = CharArray(maxTextLen)
        val n = user32.GetWindowText(hwnd, buf, maxTextLen)
        return if (n > 0) String(buf, 0, n) else ""
    }

    private fun windowClass(hwnd: WinDef.HWND): String {
        val buf = CharArray(256)
        val n = user32.GetClassName(hwnd, buf, 256)
        return if (n > 0) String(buf, 0, n) else ""
    }

    private fun topLevelWindows(visibleOnly: Boolean): List<WinDef.HWND> {
        val result = mutableListOf<WinDef.HWND>()
        user32.EnumWindows(WinUser.WNDENUMPROC { hwnd, _ ->
            if (!visibleOnly || user32.IsWindowVisible(hwnd)) result.add(hwnd)
            true // continue enumeration
        }, Pointer.NULL)
        return result
    }

    private fun childWindows(parent: WinDef.HWND): List<WinDef.HWND> {
        val result = mutableListOf<WinDef.HWND>()
        // Explicit WinUser.WNDENUMPROC: Kotlin cannot reliably resolve the
        // inherited nested type via User32 (the inherited-SAM inference quirk).
        val proc = object : WinUser.WNDENUMPROC {
            override fun callback(hwnd: WinDef.HWND, lParam: Pointer): Boolean {
                result.add(hwnd)
                return true
            }
        }
        user32.EnumChildWindows(parent, proc, Pointer.NULL)
        return result
    }

    /** First child control (or the window itself) whose text equals [label] (case-insensitive). */
    private fun findControlByText(root: WinDef.HWND, label: String): WinDef.HWND? {
        val needle = label.trim()
        if (needle.isEmpty()) return null
        if (windowTitle(root).equals(needle, ignoreCase = true)) return root
        return childWindows(root).firstOrNull { windowTitle(it).equals(needle, ignoreCase = true) }
    }

    /** First child control inside [app]'s window whose control id or text equals [elementId]. */
    private fun findControlByAppElement(app: String, elementId: String): WinDef.HWND? {
        val appWindow = topLevelWindows(visibleOnly = true)
            .firstOrNull { windowTitle(it).contains(app, ignoreCase = true) } ?: return null
        val id = elementId.trim().toIntOrNull()
        return childWindows(appWindow).firstOrNull { child ->
            (id != null && user32Ext.GetDlgCtrlID(child) == id) ||
                windowTitle(child).equals(elementId.trim(), ignoreCase = true)
        }
    }

    // ── WindowsUiaBridge ────────────────────────────────────────────────────

    override fun listWindows(): List<AppWindow> =
        topLevelWindows(visibleOnly = true).mapNotNull { hwnd ->
            val title = windowTitle(hwnd)
            if (title.isBlank()) null
            else AppWindow(
                appName  = windowClass(hwnd).ifBlank { title },
                title    = title,
                windowId = hwnd.toString(),
            )
        }

    override fun activateApp(appName: String): Boolean {
        val target = topLevelWindows(visibleOnly = true)
            .firstOrNull { windowTitle(it).contains(appName, ignoreCase = true) }
        if (target != null) {
            return runCatching {
                user32.ShowWindow(target, SW_RESTORE)
                user32.SetForegroundWindow(target)
            }.getOrDefault(false)
        }
        // No open window: launch via the shell (Windows `start` resolves the app
        // name against PATH / App Paths registry).
        return runCatching {
            val proc = ProcessBuilder("cmd", "/c", "start", "", appName)
                .redirectErrorStream(true)
                .start()
            proc.inputStream.close()
            true
        }.getOrDefault(false)
    }

    override fun clickSemantic(label: String): Boolean {
        val control = topLevelWindows(visibleOnly = true)
            .firstNotNullOfOrNull { findControlByText(it, label) } ?: return false
        return runCatching {
            user32.SendMessage(control, BM_CLICK, WinDef.WPARAM(0L), WinDef.LPARAM(0L))
            true
        }.getOrDefault(false)
    }

    override fun clickAppElement(app: String, elementId: String): Boolean {
        val control = findControlByAppElement(app, elementId) ?: return false
        return runCatching {
            user32.SendMessage(control, BM_CLICK, WinDef.WPARAM(0L), WinDef.LPARAM(0L))
            true
        }.getOrDefault(false)
    }

    override fun clickAt(x: Float, y: Float): Boolean {
        return runCatching {
            user32.SetCursorPos(x.toLong(), y.toLong())
            user32Ext.mouse_event(MOUSEEVENTF_LEFTDOWN, 0, 0, 0, Pointer.NULL)
            user32Ext.mouse_event(MOUSEEVENTF_LEFTUP, 0, 0, 0, Pointer.NULL)
            true
        }.getOrDefault(false)
    }

    override fun typeText(label: String?, text: String): Boolean {
        if (text.isEmpty()) return true
        val focus = when {
            // No target: the focused field (the common "type what I said" case).
            label == null -> user32.GetForegroundWindow()
            // A labeled target that cannot be found is a hard failure — never
            // silently type into the wrong window.
            else -> topLevelWindows(visibleOnly = true)
                .firstNotNullOfOrNull { findControlByText(it, label) } ?: return false
        }
        return runCatching {
            user32.SetFocus(focus)
            text.forEach { c ->
                user32.SendMessage(focus, WM_CHAR, WinDef.WPARAM(c.code.toLong()), WinDef.LPARAM(0L))
            }
            true
        }.getOrDefault(false)
    }

    override fun scroll(label: String, down: Boolean): Boolean {
        val target: WinDef.HWND? = when {
            label.isEmpty() -> user32.GetForegroundWindow()
            else -> topLevelWindows(visibleOnly = true)
                .firstNotNullOfOrNull { findControlByText(it, label) }
        }
        val hwnd = target ?: return false
        val sbAmount = when {
            down -> SB_PAGE_DOWN
            else -> SB_PAGE_UP
        }
        return runCatching {
            user32.SendMessage(hwnd, WM_VSCROLL, WinDef.WPARAM(sbAmount.toLong()), WinDef.LPARAM(0L))
            true
        }.getOrDefault(false)
    }

    override fun waitFor(label: String, timeoutMs: Long): Boolean {
        val needle = label.trim()
        if (needle.isEmpty()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            val found = topLevelWindows(visibleOnly = true).any { root ->
                windowTitle(root).contains(needle, ignoreCase = true) ||
                    findControlByText(root, needle) != null
            }
            if (found) return true
            Thread.sleep(200)
        }
        return false
    }

    override fun screenshot(): ByteArray? {
        return runCatching {
            val bounds = Rectangle(
                user32.GetSystemMetrics(SM_XVIRTUALSCREEN),
                user32.GetSystemMetrics(SM_YVIRTUALSCREEN),
                user32.GetSystemMetrics(SM_CXVIRTUALSCREEN),
                user32.GetSystemMetrics(SM_CYVIRTUALSCREEN),
            )
            if (bounds.width <= 0 || bounds.height <= 0) return@runCatching null

            val image: BufferedImage = Robot().createScreenCapture(bounds)
            ByteArrayOutputStream().use { out ->
                ImageIO.write(image, "png", out)
                out.toByteArray()
            }
        }.getOrNull()
    }
}
