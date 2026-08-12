package com.newax.aegis.desktop.execution

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.desktop.DesktopCapabilitiesHolder
import com.newax.aegis.desktop.DesktopPolicyHolder
import com.newax.aegis.desktopsync.DesktopSync
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.desktop.DesktopCapability
import com.newax.aegis.platform.shell.ShellCapability
import com.newax.aegis.platform.shell.ShellCommand
import com.newax.aegis.sync.CommandSigning
import com.newax.aegis.sync.SyncEntry
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

/**
 * The desktop leg of the mesh's command dispatcher — the twin of Android's
 * [com.newax.aegis.CommandDispatcher] (docs/SYNC_DESIGN.md §6; Fix C). Registered
 * into [DesktopSync] during bootstrap via [DesktopSync.setCommandDispatcher];
 * every incoming `to:<me>` command lands here and nowhere else (rule 11).
 *
 * Validation order is identical to Android's:
 *  0. sender signature verifies against the PAIRED peer's Ed25519 key
 *     (CommandSigning — forged/unknown senders are refused first),
 *  1. target == me (already enforced by DesktopSync's key routing),
 *  2. ttl not expired,
 *  3. command class allowlisted by the sender's per-peer permissions
 *     (`sync:peerperm:<senderId>`; empty set = unrestricted),
 *  4. class maps to a known typed action,
 *  5. the SAME policy spine as any local agent-origin action
 *     (DesktopPolicyHolder.engine().evaluate(action, ActionOrigin.AGENT) —
 *     rule 10: a command grants zero authority by itself).
 *
 * Executors (AUTO_EXECUTE only): open_app goes through the launch ladder
 * ([DesktopExecutionRouter]: shortcut → process → Win32 activateApp) and
 * run_shell through the one bounded [ShellCapability] — the guard lives inside
 * the capability (rule 11). Every other class has no desktop action body yet
 * (Track M) and is refused with an explicit ack, never a silent drop. Every
 * evaluation lands in the desktop policy audit trail via the engine's
 * auditSink.
 */
object DesktopCommandDispatcher {

    private fun dispatch(entry: SyncEntry) {
        val payload = runCatching { JSONObject(entry.payload.decodeToString()) }.getOrNull() ?: return
        val commandClass = payload.optString("class").takeIf { it.isNotBlank() } ?: return
        val args = payload.keys().asSequence()
            .filter { it != "class" && it != "ttl" && it != "sig" }
            .associateWith { payload.optString(it) }

        // 0. Sender signature — verify against the paired peer's key.
        val ttl = payload.optLong("ttl", 0L)
        val signPublicKey = DesktopSync.peerSignPublicKey(entry.deviceId)
        val signatureOk = signPublicKey != null && CommandSigning.verify(
            com.newax.aegis.sync.platformCrypto(), signPublicKey, commandClass, ttl, args, payload.optString("sig")
        )
        if (!signatureOk) {
            DesktopSync.sendCommandAck(entry.deviceId, entry.opId, "refused", "bad-signature")
            return
        }

        // 2. TTL.
        if (ttl > 0 && ttl < System.currentTimeMillis()) {
            DesktopSync.sendCommandAck(entry.deviceId, entry.opId, "expired", "ttl")
            return
        }

        // 3. Per-peer allowlist — empty set = unrestricted (the pairing default).
        val allowed = DesktopSync.peerPermissions(entry.deviceId)
        if (allowed.isNotEmpty() && commandClass !in allowed) {
            DesktopSync.sendCommandAck(entry.deviceId, entry.opId, "refused", "class-not-allowlisted")
            return
        }

        // 4. Known class → typed action.
        val action = actionFor(commandClass, payload) ?: run {
            DesktopSync.sendCommandAck(entry.deviceId, entry.opId, "refused", "unsupported-class")
            return
        }

        // 5. The one policy spine, AGENT origin. The engine's auditSink records
        //    every evaluation — nothing is silent.
        val engine = DesktopPolicyHolder.engineOrNull()
        if (engine == null) {
            DesktopSync.sendCommandAck(entry.deviceId, entry.opId, "refused", "policy-unavailable")
            return
        }
        val evaluation = engine.evaluate(action, ActionOrigin.AGENT)
        if (!evaluation.decision.allowsAutonomousExecution) {
            DesktopSync.sendCommandAck(entry.deviceId, entry.opId, "refused", evaluation.decision.name)
            return
        }

        val executed = execute(commandClass, action)
        DesktopSync.sendCommandAck(entry.deviceId, entry.opId, if (executed) "executed" else "execution-failed")
    }

    /** Maps a command class to its typed action (shared/core ProposedAction). */
    private fun actionFor(commandClass: String, payload: JSONObject): ProposedAction? = when (commandClass) {
        "send_email" -> payload.optString("text").takeIf { it.isNotBlank() }
            ?.let { ProposedAction.Send(it) }
        "open_app" -> payload.optString("name").ifBlank { payload.optString("app") }
            .takeIf { it.isNotBlank() }
            ?.let { ProposedAction.OpenApp(it) }
        "run_shell" -> payload.optString("code").takeIf { it.isNotBlank() }
            ?.let { ProposedAction.RunScript(it) }
        "system_query" -> payload.optString("query").takeIf { it.isNotBlank() }
            ?.let { ProposedAction.SearchAll(it) }
        // open_file / browse_files / run_goal have no service-safe action body
        // yet (assistant-body work) — refuse with an explicit ack below.
        else -> null
    }

    /** Executes only AUTO_EXECUTE-approved commands; false = could not run. */
    private fun execute(commandClass: String, action: ProposedAction): Boolean {
        val registry = DesktopCapabilitiesHolder.registry() ?: return false
        return when (commandClass) {
            "open_app" -> {
                val name = (action as? ProposedAction.OpenApp)?.name ?: return false
                runCatching {
                    val desktop = registry.get(CapabilityId.DESKTOP) as? DesktopCapability
                    val plan = DesktopExecutionRouter().resolveLaunch(name, desktop)
                    runBlocking { plan.executor() } is DesktopLaunchOutcome.Launched
                }.getOrDefault(false)
            }
            "run_shell" -> {
                val code = (action as? ProposedAction.RunScript)?.code ?: return false
                val shell = registry.get(CapabilityId.SHELL) as? ShellCapability ?: return false
                val isWindows = System.getProperty("os.name").lowercase().contains("win")
                val command = if (isWindows) {
                    ShellCommand(executable = "cmd.exe", args = listOf("/c", code))
                } else {
                    ShellCommand(executable = "/bin/sh", args = listOf("-c", code))
                }
                val result = shell.run(command, OperationContext.create("sync-command:mesh", ActionOrigin.AGENT))
                result is CapabilityResult.Success
            }
            else -> false
        }
    }

    /** The registered entry point — handed to DesktopSync. */
    fun onIncoming(entry: SyncEntry) {
        runCatching { dispatch(entry) }
    }
}
