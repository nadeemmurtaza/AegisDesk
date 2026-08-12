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

private const val NOT_ON_WINDOWS =
    "Desktop automation is not supported on this OS — Windows only"

/**
 * Desktop capability on Windows = Win32 native UI automation (the RULE 5 tier
 * directly below the contract). All operations are *semantic* where possible —
 * window titles and control text via EnumWindows/EnumChildWindows — and
 * [UiTarget.Coordinates] is the explicit last resort.
 *
 * The [WindowsUiaBridge] seam is constructed only when running on Windows; on
 * any other OS (developer machines, CI) the capability reports
 * [CapabilityStatus.NOT_SUPPORTED] and every operation returns a typed failure —
 * the real platform state, not a stub.
 */
class WindowsDesktopCapability(
    private val bridge: WindowsUiaBridge?,
) : DesktopCapability {

    /** Production constructor: uses the Win32 bridge on Windows, null elsewhere. */
    constructor() : this(if (isWindowsOs()) Win32UiaBridge() else null)

    override val id: CapabilityId get() = CapabilityId.DESKTOP

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "Desktop",
        description = "Windows UI automation via Win32 native APIs (EnumWindows, control messaging, GDI capture); semantic labels before coordinates (RULE 5)",
        privilegeLevel = PrivilegeLevel.HIGH_IMPACT_SYSTEM,
        status = if (bridge != null) CapabilityStatus.READY else CapabilityStatus.NOT_SUPPORTED,
    )

    override fun status(): CapabilityStatus =
        if (bridge != null) CapabilityStatus.READY else CapabilityStatus.NOT_SUPPORTED

    override fun listWindows(): CapabilityResult<List<AppWindow>> =
        bridge?.let { CapabilityResult.Success(it.listWindows()) }
            ?: CapabilityResult.Failed(NOT_ON_WINDOWS)

    override fun activateApp(appName: String, context: OperationContext): CapabilityResult<Unit> {
        val b = bridge ?: return CapabilityResult.Failed(NOT_ON_WINDOWS)
        return if (b.activateApp(appName.trim())) CapabilityResult.Success(Unit)
        else CapabilityResult.Failed("could not activate '$appName' (no matching window and the shell launch failed)")
    }

    override fun click(target: UiTarget, context: OperationContext): CapabilityResult<Unit> {
        val b = bridge ?: return CapabilityResult.Failed(NOT_ON_WINDOWS)
        return when (target) {
            is UiTarget.Semantic -> {
                if (b.clickSemantic(target.label)) CapabilityResult.Success(Unit)
                else CapabilityResult.Failed("control '${target.label}' not found or not clickable")
            }
            is UiTarget.AppElement -> {
                if (b.clickAppElement(target.app, target.elementId)) CapabilityResult.Success(Unit)
                else CapabilityResult.Failed("element '${target.elementId}' not found in '${target.app}'")
            }
            is UiTarget.Coordinates -> {
                if (b.clickAt(target.x, target.y)) CapabilityResult.Success(Unit)
                else CapabilityResult.Failed("coordinate click at (${target.x}, ${target.y}) failed")
            }
        }
    }

    override fun typeText(target: UiTarget?, text: String, context: OperationContext): CapabilityResult<Unit> {
        val b = bridge ?: return CapabilityResult.Failed(NOT_ON_WINDOWS)
        val label = when (target) {
            null -> null
            is UiTarget.Semantic -> target.label
            else -> return CapabilityResult.Failed("typing requires a semantic target or no target (focused field)")
        }
        return if (b.typeText(label, text)) CapabilityResult.Success(Unit)
        else CapabilityResult.Failed("could not type into ${label?.let { "'$it'" } ?: "focused field"}")
    }

    override fun scroll(target: UiTarget, direction: ScrollDirection, context: OperationContext): CapabilityResult<Unit> {
        val b = bridge ?: return CapabilityResult.Failed(NOT_ON_WINDOWS)
        val label = when (target) {
            is UiTarget.Semantic -> target.label
            else -> return CapabilityResult.Failed("scrolling requires a semantic target")
        }
        return if (b.scroll(label, direction == ScrollDirection.DOWN)) CapabilityResult.Success(Unit)
        else CapabilityResult.Failed("could not scroll '$label'")
    }

    override fun waitFor(target: UiTarget, timeoutMs: Long): CapabilityResult<Boolean> {
        val b = bridge ?: return CapabilityResult.Failed(NOT_ON_WINDOWS)
        val label = when (target) {
            is UiTarget.Semantic -> target.label
            else -> return CapabilityResult.Failed("waiting requires a semantic target")
        }
        return CapabilityResult.Success(b.waitFor(label, timeoutMs))
    }

    override fun screenshot(): CapabilityResult<ByteArray> {
        val b = bridge ?: return CapabilityResult.Failed(NOT_ON_WINDOWS)
        val bytes = b.screenshot()
        return if (bytes != null) CapabilityResult.Success(bytes)
        else CapabilityResult.Failed("screen capture failed (GDI capture error)")
    }
}
