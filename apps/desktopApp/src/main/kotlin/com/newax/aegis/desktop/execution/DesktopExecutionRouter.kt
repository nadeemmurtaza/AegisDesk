package com.newax.aegis.desktop.execution

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.desktop.DesktopCapability

/**
 * Desktop execution tiers for app launch — most exact first. Mirror of Android's
 * [ExecutionTier] ladder (ANDROID_API → INTENT → …): the direct tier first, the
 * semantic tier as the fallback (ARCHITECTURE.md RULE 5: semantic APIs before
 * coordinates, direct before indirect).
 */
enum class DesktopExecutionTier(val rank: Int) {
    /** Exact Start Menu shortcut target from the app index — no name guessing (Phase 5i). */
    EXACT_TARGET(0),

    /** Direct OS process launch via ProcessBuilder — resolves a bare name via PATH. */
    PROCESS_LAUNCH(1),

    /** Win32 semantic activation — activate the app's existing window, else shell-launch it. */
    WIN32_AUTOMATION(2),
}

/** Outcome of running a [DesktopExecutionPlan]: which tier launched, or why it could not. */
sealed interface DesktopLaunchOutcome {
    data class Launched(val tier: DesktopExecutionTier, val detail: String) : DesktopLaunchOutcome
    data class Failed(val reason: String) : DesktopLaunchOutcome
}

/**
 * One resolved launch, ready to run. [description] names the ladder so the
 * runner can surface what will happen before the side effect; [executor] runs
 * it and reports which rung actually fired ([DesktopLaunchOutcome.Launched]) or
 * why none did ([DesktopLaunchOutcome.Failed]).
 */
data class DesktopExecutionPlan(
    val description: String,
    val executor: suspend () -> DesktopLaunchOutcome,
)

/**
 * Owns the desktop app-launch ladder: try the exact Start Menu shortcut target
 * from the app index first ([DesktopExecutionTier.EXACT_TARGET] — Windows
 * resolves the .lnk natively), then [DesktopExecutionTier.PROCESS_LAUNCH]
 * (ProcessBuilder resolves a bare name via PATH), and when the OS refuses —
 * unknown executable, Store-installed app — fall back to
 * [DesktopExecutionTier.WIN32_AUTOMATION] via [DesktopCapability.activateApp]
 * (semantic window activation, then shell launch).
 *
 * [launchProcess] and [launchShortcut] are injectable so the tier fallback is
 * verifiable on any OS; the production defaults are the real ProcessBuilder
 * start and the shell launch of a shortcut path (the same `cmd start` mechanism
 * the Win32 bridge uses).
 */
class DesktopExecutionRouter(
    private val launchProcess: (String) -> Boolean = ::tryLaunchProcess,
    private val launchShortcut: (String) -> Boolean = ::tryLaunchShortcut,
) {

    fun resolveLaunch(
        appName: String,
        desktop: DesktopCapability?,
        lnkPath: String? = null,
    ): DesktopExecutionPlan {
        val name = appName.trim()
        val exact = lnkPath?.trim()?.takeIf { it.isNotEmpty() }
        return DesktopExecutionPlan(
            description = buildString {
                append("Launch ladder for '$name'")
                if (exact != null) append(" (exact target: $exact)")
                append(": shortcut → process → Win32 activateApp")
            },
            executor = {
                if (exact != null && launchShortcut(exact)) {
                    DesktopLaunchOutcome.Launched(
                        DesktopExecutionTier.EXACT_TARGET,
                        "started Start Menu target '$exact'",
                    )
                } else if (launchProcess(name)) {
                    DesktopLaunchOutcome.Launched(
                        DesktopExecutionTier.PROCESS_LAUNCH,
                        "process started '$name'",
                    )
                } else {
                    val capability = desktop
                    when {
                        capability == null ->
                            DesktopLaunchOutcome.Failed(
                                "no Desktop capability registered — cannot launch '$name'"
                            )
                        !capability.status().isOperational ->
                            DesktopLaunchOutcome.Failed(
                                "Desktop capability not ready (${capability.status()}) — cannot launch '$name'"
                            )
                        else -> {
                            val result = capability.activateApp(
                                name,
                                OperationContext.create("GoalExecutor", ActionOrigin.AGENT),
                            )
                            when (result) {
                                is CapabilityResult.Success -> DesktopLaunchOutcome.Launched(
                                    DesktopExecutionTier.WIN32_AUTOMATION,
                                    "Win32 activateApp on '$name'",
                                )
                                else -> DesktopLaunchOutcome.Failed(
                                    "Win32 activation failed for '$name': ${result.describe()}"
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    companion object {
        /**
         * Starts [name] as an OS process. On Windows, CreateProcess resolves the
         * name via PATH (appending .exe); on other OSes the shell's PATH search
         * applies. Returns false when the name is not an executable — the caller
         * then falls to the semantic tier.
         */
        private fun tryLaunchProcess(name: String): Boolean = runCatching {
            ProcessBuilder(name).start()
        }.isSuccess

        /**
         * Launches a Start Menu shortcut by opening its .lnk path through the
         * shell — Windows resolves the shortcut natively, so this is the exact
         * launch for an indexed app. The path is passed explicitly quoted
         * because `cmd start` re-parses its command line and most shortcuts
         * live under "Program Files" (spaces). Same `cmd start` mechanism the
         * Win32 bridge uses for name-based shell launches.
         */
        private fun tryLaunchShortcut(lnkPath: String): Boolean = runCatching {
            val proc = ProcessBuilder("cmd", "/c", "start", "", "\"$lnkPath\"")
                .redirectErrorStream(true)
                .start()
            proc.inputStream.close()
            true
        }.getOrDefault(false)
    }
}

private fun CapabilityResult<*>.describe(): String = when (this) {
    is CapabilityResult.Success -> "ok"
    is CapabilityResult.MissingPermission -> "missing permission '${permission}'"
    is CapabilityResult.MissingCredential -> "missing credential '${credentialKey}'"
    is CapabilityResult.Disabled -> "disabled: $reason"
    is CapabilityResult.Failed -> error
}
