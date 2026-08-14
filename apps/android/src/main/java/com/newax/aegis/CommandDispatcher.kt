package com.newax.aegis

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.shell.ShellCapability
import com.newax.aegis.platform.shell.ShellCommand
import com.newax.aegis.sync.AndroidSyncContext
import com.newax.aegis.sync.CommandSigning
import com.newax.aegis.sync.SyncEntry
import org.json.JSONObject

/**
 * The only component that may process an incoming command — the design's
 * "command dispatcher on the target" (docs/SYNC_DESIGN.md §6; item 6 — per-peer
 * command permissions are now enforced, not just stored). Every hop that
 * carried the journal entry stored it without executing; this object is where
 * the mesh's store-and-forward chain ends.
 *
 * Validation order (each failure produces an ack + an audit entry, never a
 * silent drop):
 *  0. sender signature verifies against the PAIRED peer's Ed25519 key
 *     (CommandSigning — a forged/unknown sender is refused before anything
 *     else),
 *  1. target == me (the journal key is `to:<myDeviceId>`),
 *  2. ttl not expired,
 *  3. command class allowlisted by the SENDER's per-peer permissions
 *     (`sync:peerperm:<senderId>`; empty set = unrestricted),
 *  4. class maps to a known action,
 *  5. the SAME policy spine as any local agent-origin action
 *     (PolicyEngine.evaluate(action, ActionOrigin.AGENT) — rule 10: a command
 *     grants zero authority by itself; a REQUIRE_APPROVAL command is refused
 *     exactly like the goal-execution gate).
 *
 * Only AUTO_EXECUTE-approved commands reach an executor: open_app launches
 * the app (the app's own launch path) and run_shell goes through the one
 * bounded ShellCapability (rule 11 — the sink's guard lives in the
 * capability). The remaining classes' action bodies are assistant-body work
 * (Track M) — they are refused with an explicit `execution-not-wired` ack
 * rather than silently dropped. Every evaluation is recorded in the policy
 * audit trail by the engine's auditSink (surfaced in the Policy History
 * screen).
 */
object CommandDispatcher {

    private const val TARGET_PREFIX = "to:"
    private const val ACK_PREFIX = "ack:"

    /** Routes an incoming LOG entry from the command journal namespace. */
    fun onIncoming(entry: SyncEntry) {
        // Unreachable while sync is down — no transport delivers entries — but
        // both calls below need the identity, so fail closed rather than throw.
        if (!SyncRuntime.isAvailable) return
        val myId = SyncRuntime.deviceId()
        val key = entry.key
        when {
            key.startsWith(ACK_PREFIX) -> if (key.removePrefix(ACK_PREFIX) == myId) recordAck(entry)
            key.startsWith(TARGET_PREFIX) -> if (key.removePrefix(TARGET_PREFIX) == myId) dispatch(entry)
            else -> Unit
        }
    }

    private fun dispatch(entry: SyncEntry) {
        val payload = runCatching { JSONObject(entry.payload.decodeToString()) }.getOrNull() ?: return
        val commandClass = payload.optString("class").takeIf { it.isNotBlank() } ?: return
        val args = payload.keys().asSequence()
            .filter { it != "class" && it != "ttl" && it != "sig" }
            .associateWith { payload.optString(it) }

        // 0. Sender signature — verify against the paired peer's key.
        val ttl = payload.optLong("ttl", 0L)
        val signPublicKey = SyncRuntime.peerSignPublicKey(entry.deviceId)
        val signatureOk = signPublicKey != null && CommandSigning.verify(
            SyncRuntime.crypto(), signPublicKey, commandClass, ttl, args, payload.optString("sig")
        )
        if (!signatureOk) {
            SyncRuntime.sendCommandAck(entry.deviceId, entry.opId, "refused", "bad-signature")
            return
        }

        // 2. TTL.
        if (ttl > 0 && ttl < System.currentTimeMillis()) {
            SyncRuntime.sendCommandAck(entry.deviceId, entry.opId, "expired", "ttl")
            return
        }

        // 3. Per-peer allowlist — empty set = unrestricted (the pairing default).
        val allowed = SyncRuntime.peerPermissions(entry.deviceId)
        if (allowed.isNotEmpty() && commandClass !in allowed) {
            SyncRuntime.sendCommandAck(entry.deviceId, entry.opId, "refused", "class-not-allowlisted")
            return
        }

        // 4. Known class → typed action.
        val action = actionFor(commandClass, payload) ?: run {
            SyncRuntime.sendCommandAck(entry.deviceId, entry.opId, "refused", "unsupported-class")
            return
        }

        // 5. The one policy spine, AGENT origin. The engine's auditSink records
        //    every evaluation (Policy History screen) — nothing is silent.
        val engine = PolicyHolder.engineOrNull()
        if (engine == null) {
            SyncRuntime.sendCommandAck(entry.deviceId, entry.opId, "refused", "policy-unavailable")
            return
        }
        val evaluation = engine.evaluate(action, ActionOrigin.AGENT)
        if (!evaluation.decision.allowsAutonomousExecution) {
            SyncRuntime.sendCommandAck(entry.deviceId, entry.opId, "refused", evaluation.decision.name)
            return
        }

        val executed = execute(commandClass, action, payload)
        SyncRuntime.sendCommandAck(entry.deviceId, entry.opId, if (executed) "executed" else "execution-failed")
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
    private fun execute(commandClass: String, action: ProposedAction, payload: JSONObject): Boolean {
        val context = runCatching { AndroidSyncContext.requireContext() }.getOrNull() ?: return false
        return when (commandClass) {
            "open_app" -> {
                val name = (action as? ProposedAction.OpenApp)?.name ?: return false
                runCatching {
                    val intent = context.packageManager.getLaunchIntentForPackage(name)
                        ?.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                    if (intent != null) {
                        context.startActivity(intent)
                        true
                    } else {
                        false
                    }
                }.getOrDefault(false)
            }
            "run_shell" -> {
                val code = (action as? ProposedAction.RunScript)?.code ?: return false
                val shell = PlatformCapabilitiesHolder.registry()?.get(CapabilityId.SHELL) as? ShellCapability
                    ?: return false
                val result = shell.run(
                    ShellCommand(executable = "/system/bin/sh", args = listOf("-c", code)),
                    OperationContext.create("sync-command:mesh", ActionOrigin.AGENT)
                )
                result is com.newax.aegis.platform.CapabilityResult.Success
            }
            else -> false
        }
    }

    /** Inbound acks from the target of a command we sent — surfaced in status. */
    private fun recordAck(entry: SyncEntry) {
        val payload = runCatching { JSONObject(entry.payload.decodeToString()) }.getOrNull() ?: return
        val result = payload.optString("result", "?")
        val reason = payload.optString("reason")
        SyncRuntime.recordStatus(
            "command ${payload.optString("ref").take(8)} " +
                "→ $result" + (if (reason.isNotBlank()) " ($reason)" else "")
        )
    }
}
