package com.newax.aegis.desktop.ui.state

import com.newax.aegis.desktop.DesktopCapabilitiesHolder
import com.newax.aegis.desktop.DesktopModelProviderHolder
import com.newax.aegis.model.ModelDescriptor
import com.newax.aegis.model.ModelProvider
import com.newax.aegis.model.ModelState
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.PlatformCapabilityRegistry
import com.newax.aegis.platform.PrivilegeLevel
import kotlinx.coroutines.flow.StateFlow

/** One capability row on the desktop Status screen — mirror of Android's CapabilityRow. */
data class CapabilityUiRow(
    val id: CapabilityId,
    val displayName: String,
    val description: String,
    val status: CapabilityStatus,
    val privilegeLevel: PrivilegeLevel,
    val requiredPermission: String?,
    val requiredCredentialKey: String?,
    val offline: Boolean,
)

/**
 * Status / capabilities screen state — the plain-Kotlin, testable core of the
 * desktop Status screen (the `printStatusBlock` CLI logic lifted into a state
 * holder). Reads are live snapshots of the process-wide surfaces: the capability
 * registry ([registry]) and the active model provider ([model]). The model
 * lifecycle is reactive through the provider's [StateFlow], so the screen shows
 * NOT_INSTALLED → LOADING → READY/ERROR live while the model loads behind the
 * window.
 */
class StatusScreenState(
    private val registry: () -> PlatformCapabilityRegistry? = { DesktopCapabilitiesHolder.registry() },
    private val model: () -> ModelProvider = { DesktopModelProviderHolder.current() },
) {

    /**
     * Every registered capability with its live status, or null before the
     * holder's [DesktopCapabilitiesHolder.init] has run (the "not initialized"
     * state the screen renders as an error state, mirroring Android).
     */
    fun capabilityRows(): List<CapabilityUiRow>? =
        registry()?.all()?.map { capability ->
            val d = capability.descriptor()
            CapabilityUiRow(
                id = capability.id,
                displayName = d.displayName,
                description = d.description,
                status = capability.status(),
                privilegeLevel = d.privilegeLevel,
                requiredPermission = d.requiredPermission,
                requiredCredentialKey = d.requiredCredentialKey,
                offline = d.offline,
            )
        }

    /** Live state of the active provider — re-collected on every provider swap. */
    val modelState: StateFlow<ModelState> get() = model().state

    /** Static facts about the active pack (UNKNOWN/empty for the fallback). */
    val modelDescriptor: ModelDescriptor get() = model().descriptor
}
