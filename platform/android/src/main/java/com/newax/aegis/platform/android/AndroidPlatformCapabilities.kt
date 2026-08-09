package com.newax.aegis.platform.android

import android.content.Context
import com.newax.aegis.platform.PlatformCapabilities
import com.newax.aegis.platform.PlatformCapabilityRegistry
import com.newax.aegis.platform.desktop.DesktopCapability
import com.newax.aegis.platform.files.FileCapability
import com.newax.aegis.platform.processes.ProcessCapability
import com.newax.aegis.platform.secrets.SecretsCapability
import com.newax.aegis.platform.shell.ShellCapability
import com.newax.aegis.platform.system.SystemCapability

/**
 * The Android implementation of [PlatformCapabilities]. Constructed via
 * [create]; the app bootstrap injects the optional seams ([SemanticAutomation],
 * [ScreenCapturer], [PermissionRequester]) once the accessibility service,
 * MediaProjection consent, and activity-scoped permission flow are available.
 */
class AndroidPlatformCapabilities(
    override val files: FileCapability,
    override val processes: ProcessCapability,
    override val shell: ShellCapability,
    override val desktop: DesktopCapability,
    override val secrets: SecretsCapability,
    override val system: SystemCapability,
) : PlatformCapabilities {

    companion object {

        /**
         * Builds the full Android capability surface. Seams are optional and
         * default to "not attached", which the capabilities report honestly
         * (e.g. [AndroidDesktopCapability] → UNAVAILABLE until the accessibility
         * service is wired in).
         */
        fun create(
            context: Context,
            automation: SemanticAutomation? = null,
            screenCapturer: ScreenCapturer? = null,
            permissionRequester: PermissionRequester? = null,
        ): AndroidPlatformCapabilities {
            val filesDir = context.filesDir
            return AndroidPlatformCapabilities(
                files = AndroidFileCapability(filesDir, context.contentResolver),
                processes = AndroidProcessCapability(context),
                shell = AndroidShellCapability(filesDir),
                desktop = AndroidDesktopCapability(context, automation, screenCapturer),
                secrets = AndroidSecretsCapability(context),
                system = AndroidSystemCapability(context, permissionRequester),
            )
        }

        /** Builds the surface and registers every capability into [registry]. */
        fun register(
            registry: PlatformCapabilityRegistry,
            context: Context,
            automation: SemanticAutomation? = null,
            screenCapturer: ScreenCapturer? = null,
            permissionRequester: PermissionRequester? = null,
        ): PlatformCapabilityRegistry {
            create(context, automation, screenCapturer, permissionRequester).all.forEach { registry.register(it) }
            return registry
        }
    }
}
