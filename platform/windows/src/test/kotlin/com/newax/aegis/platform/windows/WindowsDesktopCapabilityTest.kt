package com.newax.aegis.platform.windows

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.desktop.AppWindow
import com.newax.aegis.platform.desktop.ScrollDirection
import com.newax.aegis.platform.desktop.UiTarget
import com.newax.aegis.platform.getOrNull
import com.newax.aegis.platform.isSuccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WindowsDesktopCapability is tested against a fake [WindowsUiaBridge] (and the
 * null-bridge NOT_SUPPORTED path), so the capability logic is verified without a
 * Windows OS. The JNA-heavy [Win32UiaBridge] itself is a device/OS-tested concern.
 */
class WindowsDesktopCapabilityTest {

    private val context = OperationContext.create("test", ActionOrigin.USER)

    @Test
    fun `null bridge reports NOT_SUPPORTED and every operation fails honestly`() {
        val cap = WindowsDesktopCapability(bridge = null)
        assertEquals(CapabilityStatus.NOT_SUPPORTED, cap.status())

        assertFalse(cap.listWindows().isSuccess())
        assertFalse(cap.activateApp("notepad", context).isSuccess())
        assertFalse(cap.click(UiTarget.Semantic("OK"), context).isSuccess())
        assertFalse(cap.typeText(UiTarget.Semantic("box"), "hi", context).isSuccess())
        assertFalse(cap.scroll(UiTarget.Semantic("list"), ScrollDirection.DOWN, context).isSuccess())
        assertFalse(cap.waitFor(UiTarget.Semantic("dlg"), 100L).isSuccess())
        assertFalse(cap.screenshot().isSuccess())
        assertNull(cap.listWindows().getOrNull())
    }

    @Test
    fun `bridge present reports READY`() {
        val cap = WindowsDesktopCapability(bridge = FakeBridge())
        assertEquals(CapabilityStatus.READY, cap.status())
    }

    @Test
    fun `listWindows delegates to the bridge`() {
        val bridge = FakeBridge().apply {
            windows = listOf(AppWindow("notepad", "Untitled - Notepad", "0x1234"))
        }
        val result = WindowsDesktopCapability(bridge).listWindows()
        assertTrue(result.isSuccess())
        assertEquals("Untitled - Notepad", result.getOrNull()!!.single().title)
    }

    @Test
    fun `semantic click succeeds when the bridge finds the control`() {
        val bridge = FakeBridge().apply { clickResult = true }
        val cap = WindowsDesktopCapability(bridge)
        assertTrue(cap.click(UiTarget.Semantic("Save"), context).isSuccess())
        assertEquals("Save", bridge.lastClickLabel)
    }

    @Test
    fun `semantic click failure maps to a typed failure`() {
        val bridge = FakeBridge().apply { clickResult = false }
        val cap = WindowsDesktopCapability(bridge)
        val result = cap.click(UiTarget.Semantic("Missing"), context)
        assertTrue(result is CapabilityResult.Failed)
    }

    @Test
    fun `coordinate click is delegated as the last resort`() {
        val bridge = FakeBridge().apply { clickResult = true }
        val cap = WindowsDesktopCapability(bridge)
        assertTrue(cap.click(UiTarget.Coordinates(100f, 200f), context).isSuccess())
        assertEquals(100f, bridge.lastClickX, 0.001f)
        assertEquals(200f, bridge.lastClickY, 0.001f)
    }

    @Test
    fun `typeText without target goes to the focused field`() {
        val bridge = FakeBridge().apply { typeResult = true }
        val cap = WindowsDesktopCapability(bridge)
        assertTrue(cap.typeText(null, "hello", context).isSuccess())
        assertNull(bridge.lastTypeLabel)
        assertEquals("hello", bridge.lastTypeText)
    }

    @Test
    fun `scroll down maps to down direction`() {
        val bridge = FakeBridge().apply { scrollResult = true }
        val cap = WindowsDesktopCapability(bridge)
        assertTrue(cap.scroll(UiTarget.Semantic("Feed"), ScrollDirection.DOWN, context).isSuccess())
        assertEquals(true, bridge.lastScrollDown)
    }

    @Test
    fun `waitFor returns the bridge verdict`() {
        val bridge = FakeBridge().apply { waitResult = true }
        val cap = WindowsDesktopCapability(bridge)
        assertTrue(cap.waitFor(UiTarget.Semantic("dialog"), 2000L).getOrNull() == true)
        assertEquals("dialog", bridge.lastWaitLabel)
    }

    @Test
    fun `screenshot returns PNG bytes when the bridge captures`() {
        val bridge = FakeBridge().apply { screenshotBytes = ByteArray(8) { 1 } }
        val cap = WindowsDesktopCapability(bridge)
        assertTrue(cap.screenshot().isSuccess())
        assertEquals(8, cap.screenshot().getOrNull()!!.size)
    }

    @Test
    fun `screenshot failure maps to a typed failure`() {
        val bridge = FakeBridge().apply { screenshotBytes = null }
        val cap = WindowsDesktopCapability(bridge)
        assertTrue(cap.screenshot() is CapabilityResult.Failed)
    }

    @Test
    fun `activateApp delegates to the bridge`() {
        val bridge = FakeBridge().apply { activateResult = true }
        val cap = WindowsDesktopCapability(bridge)
        assertTrue(cap.activateApp("notepad", context).isSuccess())
        assertEquals("notepad", bridge.lastActivateApp)
    }

    private class FakeBridge : WindowsUiaBridge {
        var windows: List<AppWindow> = emptyList()
        var clickResult = false
        var typeResult = false
        var scrollResult = false
        var waitResult = false
        var activateResult = false
        var screenshotBytes: ByteArray? = null
        var lastClickLabel: String? = null
        var lastClickX = 0f
        var lastClickY = 0f
        var lastTypeLabel: String? = null
        var lastTypeText: String? = null
        var lastScrollDown = false
        var lastWaitLabel: String? = null
        var lastActivateApp: String? = null

        override fun listWindows(): List<AppWindow> = windows
        override fun activateApp(appName: String): Boolean {
            lastActivateApp = appName
            return activateResult
        }
        override fun clickSemantic(label: String): Boolean {
            lastClickLabel = label
            return clickResult
        }
        override fun clickAppElement(app: String, elementId: String): Boolean = clickResult
        override fun clickAt(x: Float, y: Float): Boolean {
            lastClickX = x
            lastClickY = y
            return clickResult
        }
        override fun typeText(label: String?, text: String): Boolean {
            lastTypeLabel = label
            lastTypeText = text
            return typeResult
        }
        override fun scroll(label: String, down: Boolean): Boolean {
            lastScrollDown = down
            return scrollResult
        }
        override fun waitFor(label: String, timeoutMs: Long): Boolean {
            lastWaitLabel = label
            return waitResult
        }
        override fun screenshot(): ByteArray? = screenshotBytes
    }
}
