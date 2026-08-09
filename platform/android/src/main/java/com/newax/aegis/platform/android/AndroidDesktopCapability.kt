package com.newax.aegis.platform.android

import android.content.Context
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

/**
 * The accessibility bridge the app's AegisAccessibilityService implements and
 * injects. All operations are *semantic* (labels), never coordinates —
 * ARCHITECTURE.md RULE 5. Returning false means "element not found / action failed",
 * which the capability maps to a typed failure.
 */
interface SemanticAutomation {
    fun listWindows(): List<AppWindow>
    fun click(label: String): Boolean
    fun typeText(label: String?, text: String): Boolean
    fun scroll(label: String, down: Boolean): Boolean
    fun waitFor(label: String, timeoutMs: Long): Boolean
}

/**
 * MediaProjection-based capture the app wires in after the user consents. Absent
 * this, [screenshot] fails honestly — capture genuinely requires the consent flow.
 */
fun interface ScreenCapturer {
    /** Returns a PNG-encoded capture, or null on failure. */
    fun capture(): ByteArray?
}

/**
 * Desktop capability on Android = UI automation through the accessibility service.
 * Without an attached [SemanticAutomation] the capability reports [CapabilityStatus.UNAVAILABLE]
 * and every interaction fails with a typed reason — this is the real platform state
 * (the user has not enabled the service), not a stub.
 */
class AndroidDesktopCapability(
    private val androidContext: Context,
    private val automation: SemanticAutomation?,
    private val screenCapturer: ScreenCapturer?,
) : DesktopCapability {

    override val id: CapabilityId get() = CapabilityId.DESKTOP

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "Desktop",
        description = "Semantic UI automation via the accessibility service; MediaProjection screenshots",
        privilegeLevel = PrivilegeLevel.HIGH_IMPACT_SYSTEM,
        requiredPermission = "android.permission.BIND_ACCESSIBILITY_SERVICE",
        status = if (automation != null) CapabilityStatus.READY else CapabilityStatus.UNAVAILABLE,
    )

    override fun status(): CapabilityStatus =
        if (automation != null) CapabilityStatus.READY else CapabilityStatus.UNAVAILABLE

    override fun listWindows(): CapabilityResult<List<AppWindow>> =
        automation?.let { CapabilityResult.Success(it.listWindows()) }
            ?: CapabilityResult.Failed("accessibility automation is not attached (enable the accessibility service)")

    override fun activateApp(appName: String, context: OperationContext): CapabilityResult<Unit> {
        val intent = try {
            androidContext.packageManager.getLaunchIntentForPackage(appName.trim())
        } catch (e: Exception) {
            null
        } ?: return CapabilityResult.Failed("no launcher activity for '$appName'")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            androidContext.startActivity(intent)
            CapabilityResult.Success(Unit)
        } catch (e: Exception) {
            CapabilityResult.Failed("cannot activate '$appName': ${e.message}")
        }
    }

    override fun click(target: UiTarget, context: OperationContext): CapabilityResult<Unit> {
        val auto = automation ?: return CapabilityResult.Failed("accessibility automation is not attached (enable the accessibility service)")
        return when (target) {
            is UiTarget.Semantic -> {
                if (auto.click(target.label)) CapabilityResult.Success(Unit)
                else CapabilityResult.Failed("element '${target.label}' not found or not clickable")
            }
            is UiTarget.AppElement -> CapabilityResult.Failed(
                "app-element targets require per-app accessibility mapping; use a semantic label"
            )
            is UiTarget.Coordinates -> CapabilityResult.Failed(
                "coordinate taps are not supported on Android without overlay permissions (RULE 5: semantic first)"
            )
        }
    }

    override fun typeText(target: UiTarget?, text: String, context: OperationContext): CapabilityResult<Unit> {
        val auto = automation ?: return CapabilityResult.Failed("accessibility automation is not attached (enable the accessibility service)")
        val label = when (target) {
            null -> null
            is UiTarget.Semantic -> target.label
            else -> return CapabilityResult.Failed("typing requires a semantic target")
        }
        return if (auto.typeText(label, text)) {
            CapabilityResult.Success(Unit)
        } else {
            CapabilityResult.Failed("could not type into ${label?.let { "'$it'" } ?: "focused field"}")
        }
    }

    override fun scroll(target: UiTarget, direction: ScrollDirection, context: OperationContext): CapabilityResult<Unit> {
        val auto = automation ?: return CapabilityResult.Failed("accessibility automation is not attached (enable the accessibility service)")
        val label = when (target) {
            is UiTarget.Semantic -> target.label
            else -> return CapabilityResult.Failed("scrolling requires a semantic target")
        }
        return if (auto.scroll(label, direction == ScrollDirection.DOWN)) {
            CapabilityResult.Success(Unit)
        } else {
            CapabilityResult.Failed("could not scroll '${target}'")
        }
    }

    override fun waitFor(target: UiTarget, timeoutMs: Long): CapabilityResult<Boolean> {
        val auto = automation ?: return CapabilityResult.Failed("accessibility automation is not attached (enable the accessibility service)")
        val label = when (target) {
            is UiTarget.Semantic -> target.label
            else -> return CapabilityResult.Failed("waiting requires a semantic target")
        }
        return CapabilityResult.Success(auto.waitFor(label, timeoutMs))
    }

    override fun screenshot(): CapabilityResult<ByteArray> {
        val capturer = screenCapturer ?: return CapabilityResult.Failed(
            "screen capture requires user consent via MediaProjection; the capturer is not attached"
        )
        val bytes = capturer.capture()
        return if (bytes != null) CapabilityResult.Success(bytes)
        else CapabilityResult.Failed("screen capture failed or was denied")
    }
}
