package com.newax.aegis.platform.windows

import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.platform.desktop.AppWindow
import com.newax.aegis.platform.desktop.DesktopCapability
import com.newax.aegis.platform.desktop.ScrollDirection
import com.newax.aegis.platform.desktop.UiTarget
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Desktop capability on Windows, implemented directly over User32/GDI32 via JNA
 * (the platform matrix's "UIA (JNA)" row; the UI Automation COM bridge that would
 * enable true semantic targets is a later slice).
 *
 * Window-level operations are semantic: [listWindows], [activateApp], and
 * [waitFor] match processes, window titles, and module names — never coordinates.
 * Input ([click], [typeText], [scroll]) is injected with [User32.SendInput]
 * (absolute-screen coordinates, or the focused field for typing); coordinates are
 * the last resort per ARCHITECTURE.md RULE 5, and semantic UI-tree targeting
 * ([UiTarget.Semantic]) honestly reports that the UIA bridge is not attached yet
 * rather than pretending.
 *
 * All Win32 calls are guarded by an OS check: on a non-Windows JVM the capability
 * reports [CapabilityStatus.NOT_SUPPORTED] and every call returns a typed
 * [CapabilityResult.Failed] instead of crashing with a native load error.
 */
class WindowsDesktopCapability : DesktopCapability {

    override val id: CapabilityId get() = CapabilityId.DESKTOP

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "Desktop",
        description = "Window-level UI automation via User32; SendInput input; GDI screenshots",
        privilegeLevel = PrivilegeLevel.HIGH_IMPACT_SYSTEM,
    )

    override fun status(): CapabilityStatus =
        if (isWindows()) CapabilityStatus.READY else CapabilityStatus.NOT_SUPPORTED

    // ── Window enumeration ───────────────────────────────────────────────────

    override fun listWindows(): CapabilityResult<List<AppWindow>> {
        if (!isWindows()) return CapabilityResult.Failed("window enumeration requires Windows")
        val windows = mutableListOf<AppWindow>()
        val callback = object : WinUser.WNDENUMPROC {
            override fun callback(hWnd: WinDef.HWND, arg: Pointer): Boolean {
                if (!User32.INSTANCE.IsWindowVisible(hWnd)) return true
                val title = windowTitle(hWnd)
                val appName = windowModuleName(hWnd)?.ifBlank { null }
                    ?: title.ifBlank { null }
                    ?: "(unknown)"
                windows.add(
                    AppWindow(
                        appName = appName,
                        title = title.ifBlank { null },
                        windowId = Pointer.nativeValue(hWnd.getPointer()).toString(),
                    )
                )
                return true
            }
        }
        return try {
            User32.INSTANCE.EnumWindows(callback, null)
            CapabilityResult.Success(windows)
        } catch (e: Exception) {
            CapabilityResult.Failed("cannot enumerate windows: ${e.message}")
        }
    }

    override fun activateApp(appName: String, context: OperationContext): CapabilityResult<Unit> {
        if (!isWindows()) return CapabilityResult.Failed("window activation requires Windows")
        val needle = appName.trim()
        if (needle.isEmpty()) return CapabilityResult.Failed("empty app name")
        var target: WinDef.HWND? = null
        val callback = object : WinUser.WNDENUMPROC {
            override fun callback(hWnd: WinDef.HWND, arg: Pointer): Boolean {
                if (target != null) return false // already found — stop enumerating
                if (!User32.INSTANCE.IsWindowVisible(hWnd)) return true
                val title = windowTitle(hWnd)
                val exe = windowModuleName(hWnd)
                if (exe?.equals(needle, ignoreCase = true) == true ||
                    title.contains(needle, ignoreCase = true)
                ) {
                    target = hWnd
                    return false
                }
                return true
            }
        }
        try {
            User32.INSTANCE.EnumWindows(callback, null)
        } catch (e: Exception) {
            return CapabilityResult.Failed("cannot enumerate windows: ${e.message}")
        }
        val hwnd = target ?: return CapabilityResult.Failed("no visible window found for '$appName'")
        User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE)
        return if (User32.INSTANCE.SetForegroundWindow(hwnd)) {
            CapabilityResult.Success(Unit)
        } else {
            CapabilityResult.Failed(
                "cannot bring '$appName' to the foreground (window may be on another desktop or elevated)"
            )
        }
    }

    // ── Input (SendInput) ────────────────────────────────────────────────────

    override fun click(target: UiTarget, context: OperationContext): CapabilityResult<Unit> {
        if (!isWindows()) return CapabilityResult.Failed("mouse input requires Windows")
        return when (target) {
            is UiTarget.Coordinates -> {
                if (clickAt(target.x.toInt(), target.y.toInt())) {
                    CapabilityResult.Success(Unit)
                } else {
                    CapabilityResult.Failed("cannot click at (${target.x}, ${target.y})")
                }
            }
            is UiTarget.Semantic -> CapabilityResult.Failed(
                "semantic clicks require the UI Automation bridge (element tree); use UiTarget.Coordinates"
            )
            is UiTarget.AppElement -> CapabilityResult.Failed(
                "app-element clicks require per-app accessibility mapping; use UiTarget.Coordinates"
            )
        }
    }

    override fun typeText(target: UiTarget?, text: String, context: OperationContext): CapabilityResult<Unit> {
        if (!isWindows()) return CapabilityResult.Failed("keyboard input requires Windows")
        return when (target) {
            null -> typeToFocusedField(text)
            is UiTarget.Coordinates -> {
                if (!clickAt(target.x.toInt(), target.y.toInt())) {
                    CapabilityResult.Failed("cannot focus (${target.x}, ${target.y})")
                } else {
                    typeToFocusedField(text)
                }
            }
            is UiTarget.Semantic -> CapabilityResult.Failed(
                "semantic typing requires the UI Automation bridge; use null (focused field) or UiTarget.Coordinates"
            )
            is UiTarget.AppElement -> CapabilityResult.Failed(
                "app-element typing requires per-app accessibility mapping; use null (focused field) or UiTarget.Coordinates"
            )
        }
    }

    override fun scroll(target: UiTarget, direction: ScrollDirection, context: OperationContext): CapabilityResult<Unit> {
        if (!isWindows()) return CapabilityResult.Failed("mouse input requires Windows")
        return when (target) {
            is UiTarget.Coordinates -> {
                val delta = if (direction == ScrollDirection.DOWN) -WHEEL_STEP else WHEEL_STEP
                if (!scrollAt(target.x.toInt(), target.y.toInt(), delta)) {
                    CapabilityResult.Failed("cannot scroll at (${target.x}, ${target.y})")
                } else {
                    CapabilityResult.Success(Unit)
                }
            }
            else -> CapabilityResult.Failed(
                "scrolling requires a UiTarget.Coordinates position; semantic scroll targets need the UI Automation bridge"
            )
        }
    }

    override fun waitFor(target: UiTarget, timeoutMs: Long): CapabilityResult<Boolean> {
        if (!isWindows()) return CapabilityResult.Failed("window waiting requires Windows")
        val needle = when (target) {
            is UiTarget.Semantic -> target.label.trim()
            else -> return CapabilityResult.Failed("waiting requires a semantic target (window title)")
        }
        if (needle.isEmpty()) return CapabilityResult.Failed("empty semantic label")
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (true) {
            if (hasWindowWithTitle(needle)) return CapabilityResult.Success(true)
            if (System.currentTimeMillis() >= deadline) return CapabilityResult.Success(false)
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return CapabilityResult.Failed("interrupted while waiting for '$needle'")
            }
        }
    }

    override fun screenshot(): CapabilityResult<ByteArray> {
        if (!isWindows()) return CapabilityResult.Failed("screenshot requires Windows")
        val screenDC = try {
            User32.INSTANCE.GetDC(null)
        } catch (e: UnsatisfiedLinkError) {
            return CapabilityResult.Failed("Win32 GDI unavailable: ${e.message}")
        }
        if (screenDC == null) return CapabilityResult.Failed("cannot get the screen device context")
        try {
            val width = GDI32.INSTANCE.GetDeviceCaps(screenDC, HORZRES)
            val height = GDI32.INSTANCE.GetDeviceCaps(screenDC, VERTRES)
            if (width <= 0 || height <= 0) return CapabilityResult.Failed("screen size unavailable")

            val memDC = GDI32.INSTANCE.CreateCompatibleDC(screenDC)
            if (memDC == null) return CapabilityResult.Failed("cannot create a memory device context")
            try {
                // Top-down 32-bit DIB; CreateDIBSection gives us a direct pointer to the
                // pixel bytes, so no GetDIBits call is needed.
                val bmi = WinGDI.BITMAPINFO()
                bmi.bmiHeader.biSize = bmi.bmiHeader.size().toInt()
                bmi.bmiHeader.biWidth = width
                bmi.bmiHeader.biHeight = -height
                bmi.bmiHeader.biPlanes = 1
                bmi.bmiHeader.biBitCount = 32
                bmi.bmiHeader.biCompression = WinGDI.BI_RGB
                val bitsRef = PointerByReference()
                val dib = GDI32.INSTANCE.CreateDIBSection(memDC, bmi, WinGDI.DIB_RGB_COLORS, bitsRef, null, 0)
                if (dib == null || bitsRef.value == null) {
                    return CapabilityResult.Failed("cannot create a DIB section for capture")
                }
                val previous = GDI32.INSTANCE.SelectObject(memDC, dib)
                try {
                    if (!GDI32.INSTANCE.BitBlt(memDC, 0, 0, width, height, screenDC, 0, 0, GDI32.SRCCOPY)) {
                        return CapabilityResult.Failed("BitBlt failed")
                    }
                    val pixels = bitsRef.value.getByteArray(0, width * height * 4)
                    return CapabilityResult.Success(encodePng(width, height, pixels))
                } finally {
                    if (previous != null) GDI32.INSTANCE.SelectObject(memDC, previous)
                    GDI32.INSTANCE.DeleteObject(dib)
                }
            } finally {
                GDI32.INSTANCE.DeleteDC(memDC)
            }
        } catch (e: Exception) {
            return CapabilityResult.Failed("screen capture failed: ${e.message}")
        } finally {
            User32.INSTANCE.ReleaseDC(null, screenDC)
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun windowTitle(hWnd: WinDef.HWND): String {
        val buffer = CharArray(512)
        User32.INSTANCE.GetWindowText(hWnd, buffer, buffer.size)
        return Native.toString(buffer)
    }

    /** Module (exe) name for the window, or null when it cannot be read. */
    private fun windowModuleName(hWnd: WinDef.HWND): String? {
        val buffer = CharArray(1024)
        val length = User32.INSTANCE.GetWindowModuleFileName(hWnd, buffer, buffer.size)
        if (length <= 0) return null
        val path = Native.toString(buffer)
        return if (path.isBlank()) null else File(path).nameWithoutExtension
    }

    private fun hasWindowWithTitle(needle: String): Boolean {
        var found = false
        val callback = object : WinUser.WNDENUMPROC {
            override fun callback(hWnd: WinDef.HWND, arg: Pointer): Boolean {
                if (found) return false
                if (User32.INSTANCE.IsWindowVisible(hWnd) &&
                    windowTitle(hWnd).contains(needle, ignoreCase = true)
                ) {
                    found = true
                    return false
                }
                return true
            }
        }
        return try {
            User32.INSTANCE.EnumWindows(callback, null)
            found
        } catch (e: Exception) {
            false
        }
    }

    private fun clickAt(x: Int, y: Int): Boolean {
        if (!sendMouse(mouseMoveInput(x, y))) return false
        try {
            Thread.sleep(20)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return sendMouse(mouseButtonInput(MOUSEEVENTF_LEFTDOWN)) &&
            sendMouse(mouseButtonInput(MOUSEEVENTF_LEFTUP))
    }

    private fun scrollAt(x: Int, y: Int, delta: Int): Boolean {
        if (!sendMouse(mouseMoveInput(x, y))) return false
        try {
            Thread.sleep(20)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        val input = WinUser.INPUT()
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
        input.input.setType(WinUser.MOUSEINPUT::class.java)
        input.input.mi = WinUser.MOUSEINPUT()
        input.input.mi.mouseData = WinDef.DWORD(delta.toLong()) // negative delta wraps to the unsigned DWORD
        input.input.mi.dwFlags = WinDef.DWORD(MOUSEEVENTF_WHEEL.toLong())
        input.input.mi.time = WinDef.DWORD(0)
        input.input.mi.dwExtraInfo = BaseTSD.ULONG_PTR(0)
        return sendMouse(input)
    }

    private fun typeToFocusedField(text: String): CapabilityResult<Unit> {
        if (text.isEmpty()) return CapabilityResult.Success(Unit)
        for (char in text) {
            val (down, up) = if (char == '\n') {
                // Newlines become Return presses; everything else goes through
                // KEYEVENTF_UNICODE so any character (including non-Latin) is typed.
                keyInput(vk = VK_RETURN, scan = 0, flags = 0) to
                    keyInput(vk = VK_RETURN, scan = 0, flags = KEYEVENTF_KEYUP)
            } else {
                keyInput(vk = 0, scan = char.code, flags = KEYEVENTF_UNICODE) to
                    keyInput(vk = 0, scan = char.code, flags = KEYEVENTF_UNICODE or KEYEVENTF_KEYUP)
            }
            if (!sendKey(down) || !sendKey(up)) {
                return CapabilityResult.Failed("SendInput failed while typing")
            }
            try {
                Thread.sleep(5)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return CapabilityResult.Failed("interrupted while typing")
            }
        }
        return CapabilityResult.Success(Unit)
    }

    private fun mouseMoveInput(x: Int, y: Int): WinUser.INPUT {
        val input = WinUser.INPUT()
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
        input.input.setType(WinUser.MOUSEINPUT::class.java)
        input.input.mi = WinUser.MOUSEINPUT()
        input.input.mi.dx = WinDef.LONG(normalizeAxis(x, screenWidth()).toLong())
        input.input.mi.dy = WinDef.LONG(normalizeAxis(y, screenHeight()).toLong())
        input.input.mi.mouseData = WinDef.DWORD(0)
        input.input.mi.dwFlags = WinDef.DWORD((MOUSEEVENTF_MOVE or MOUSEEVENTF_ABSOLUTE).toLong())
        input.input.mi.time = WinDef.DWORD(0)
        input.input.mi.dwExtraInfo = BaseTSD.ULONG_PTR(0)
        return input
    }

    private fun mouseButtonInput(flags: Int): WinUser.INPUT {
        val input = WinUser.INPUT()
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_MOUSE.toLong())
        input.input.setType(WinUser.MOUSEINPUT::class.java)
        input.input.mi = WinUser.MOUSEINPUT()
        input.input.mi.mouseData = WinDef.DWORD(0)
        input.input.mi.dwFlags = WinDef.DWORD(flags.toLong())
        input.input.mi.time = WinDef.DWORD(0)
        input.input.mi.dwExtraInfo = BaseTSD.ULONG_PTR(0)
        return input
    }

    private fun keyInput(vk: Int, scan: Int, flags: Int): WinUser.INPUT {
        val input = WinUser.INPUT()
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())
        input.input.setType(WinUser.KEYBDINPUT::class.java)
        input.input.ki = WinUser.KEYBDINPUT()
        input.input.ki.wVk = WinDef.WORD(vk.toLong())
        input.input.ki.wScan = WinDef.WORD(scan.toLong())
        input.input.ki.dwFlags = WinDef.DWORD(flags.toLong())
        input.input.ki.time = WinDef.DWORD(0)
        input.input.ki.dwExtraInfo = BaseTSD.ULONG_PTR(0)
        return input
    }

    private fun sendMouse(input: WinUser.INPUT): Boolean =
        sendInput(arrayOf(input))

    private fun sendKey(input: WinUser.INPUT): Boolean =
        sendInput(arrayOf(input))

    private fun sendInput(inputs: Array<WinUser.INPUT>): Boolean {
        if (inputs.isEmpty()) return true
        return try {
            val sent = User32.INSTANCE.SendInput(WinDef.DWORD(inputs.size.toLong()), inputs, inputs[0].size().toInt())
            // toInt() (kotlin.Number) rather than Java intValue() — see WindowsProcessCapability.
            sent.toInt() == inputs.size
        } catch (e: Exception) {
            false
        }
    }

    /** SendInput absolute coordinates are 0..65535 across the primary screen. */
    private fun normalizeAxis(value: Int, screenExtent: Int): Int {
        if (screenExtent <= 1) return 0
        return ((value.toLong() * 65535L) / (screenExtent - 1).toLong()).toInt().coerceIn(0, 65535)
    }

    private fun screenWidth(): Int = try {
        User32.INSTANCE.GetSystemMetrics(SM_CXSCREEN)
    } catch (e: Exception) {
        0
    }

    private fun screenHeight(): Int = try {
        User32.INSTANCE.GetSystemMetrics(SM_CYSCREEN)
    } catch (e: Exception) {
        0
    }

    /** Converts top-down BGRA scanlines to a PNG-encoded [ByteArray]. */
    private fun encodePng(width: Int, height: Int, bgra: ByteArray): ByteArray {
        val argb = IntArray(width * height)
        for (i in 0 until argb.size) {
            val offset = i * 4
            val b = bgra[offset].toInt() and 0xFF
            val g = bgra[offset + 1].toInt() and 0xFF
            val r = bgra[offset + 2].toInt() and 0xFF
            argb[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, width, height, argb, 0, width)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private companion object {
        const val VK_RETURN = 0x0D

        // GetDeviceCaps indexes (WinGDI does not name these).
        const val HORZRES = 8
        const val VERTRES = 10

        // GetSystemMetrics indexes (WinUser does not name these; both are 0/1).
        const val SM_CXSCREEN = 0
        const val SM_CYSCREEN = 1

        // Mouse-event flags — jna-platform's WinUser declares MOUSEEVENTF_* nowhere,
        // so they are defined here against WinUser.h.
        const val MOUSEEVENTF_MOVE = 0x0001
        const val MOUSEEVENTF_LEFTDOWN = 0x0002
        const val MOUSEEVENTF_LEFTUP = 0x0004
        const val MOUSEEVENTF_WHEEL = 0x0800
        const val MOUSEEVENTF_ABSOLUTE = 0x8000

        // Keyboard-event flags — declared inside WinUser.KEYBDINPUT in jna-platform.
        private const val KEYEVENTF_KEYUP = WinUser.KEYBDINPUT.KEYEVENTF_KEYUP
        private const val KEYEVENTF_UNICODE = WinUser.KEYBDINPUT.KEYEVENTF_UNICODE

        const val WHEEL_STEP = 120
    }
}
