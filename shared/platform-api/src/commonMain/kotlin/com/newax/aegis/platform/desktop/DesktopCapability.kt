package com.newax.aegis.platform.desktop

import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PlatformCapability

/**
 * Semantic UI automation target. Prefer [Semantic] or [AppElement] and treat
 * [Coordinates] as the last resort (ARCHITECTURE.md RULE 5: semantic APIs before
 * coordinates).
 */
sealed interface UiTarget {
    data class Semantic(val label: String) : UiTarget
    data class AppElement(val app: String, val elementId: String) : UiTarget
    data class Coordinates(val x: Float, val y: Float) : UiTarget
}

data class AppWindow(
    val appName: String,
    val title: String? = null,
    val windowId: String? = null,
)

enum class ScrollDirection { UP, DOWN }

/**
 * Desktop UI automation. Every interaction is privileged and requires
 * [OperationContext]; waiting and screenshots are read-only.
 */
interface DesktopCapability : PlatformCapability {
    override val id: CapabilityId get() = CapabilityId.DESKTOP

    fun listWindows(): CapabilityResult<List<AppWindow>>
    fun activateApp(appName: String, context: OperationContext): CapabilityResult<Unit>
    fun click(target: UiTarget, context: OperationContext): CapabilityResult<Unit>
    fun typeText(target: UiTarget?, text: String, context: OperationContext): CapabilityResult<Unit>
    fun scroll(target: UiTarget, direction: ScrollDirection, context: OperationContext): CapabilityResult<Unit>
    fun waitFor(target: UiTarget, timeoutMs: Long = 4000L): CapabilityResult<Boolean>
    fun screenshot(): CapabilityResult<ByteArray>
}
