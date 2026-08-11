/**
 * Entry point for the Aegis Desktop app.
 *
 * Default mode (Phase B1): opens the Compose Desktop window — Status
 * (capability + model state), Apps (Start Menu index + search), and the Goals
 * board (plan / run / abandon). The model is imported and loaded in the
 * background while the window is up; the Status screen reflects
 * NOT_INSTALLED → LOADING → READY/ERROR live.
 *
 * CLI mode (unchanged Phase 5e–5i behavior, kept behind `--cli`):
 *   ./gradlew :apps:desktopApp:run --args="--cli"                          # scans ~/.aegis/models/
 *   ./gradlew :apps:desktopApp:run --args="--cli /path/to/model.gguf"      # uses the given file
 *
 * In CLI mode on startup the app:
 *   1. Bootstraps the desktop process-wide surfaces:
 *      [DesktopCapabilitiesHolder] — registers the platform capability registry
 *      (WindowsDesktopCapability today); [DesktopModelProviderHolder] — the one
 *      active ModelProvider, starting at the deterministic fallback.
 *   2. Locates a .gguf model file (CLI arg or auto-discover)
 *   3. Validates GGUF magic + computes SHA-256 via [DesktopModelImporter]
 *   4. Builds a [GgufModelProvider] behind the shared [ModelProvider] contract
 *      and swaps it into the holder
 *   5. Loads the model (may take 10–30 s for large GGUF files)
 *   6. Runs an interactive prompt loop — each line is sent to the model via
 *      [ModelProvider.complete] and the full reply is printed; "status" prints
 *      the capability + model state block, "skills" lists the planner skill
 *      registry, "plan <goal>" plans a goal through the capability contract,
 *      "apps [query]" lists the Start Menu app index, and "goals" /
 *      "run <goal>" / "abandon <goal>" drive the goal lifecycle: "run"
 *      activates the goal and executes its tasks through the real
 *      DesktopGoalExecutor — find_app resolves the target against the app index
 *      and launch_app runs the exact shortcut target, then the process →
 *      Win32-activateApp ladder (Phase 5i). "audit" prints the full execution
 *      audit trail; "audit export" writes it to CSV under ~/.aegis/. "proximity
 *      listen" / "proximity send <file> <deviceId>" / "proximity nearby" drive
 *      the encrypted Quick Share (P2): mDNS discovery, direct TCP, ECDH key
 *      exchange + ProximityTransfer sealing (receive confirms per transfer;
 *      files land in ~/.aegis/shared/). "policy" prints the per-class effective
 *      policy modes and the decision trail; "policy set <Class> <MODE>",
 *      "policy deny/allow <Class>", "policy reset <Class>", "policy clear",
 *      and "policy export" (writes the trail to ~/.aegis/policy-audit-<timestamp>.csv)
 *      drive the same one engine the Policy tab uses (persisted under ~/.aegis/)
 *   7. On empty input, "exit", or Ctrl+D the model is closed, the holder returns
 *      to the fallback, and the app exits
 *
 * Desktop companion to Android's [MainViewModel]-based chat UI, sharing the same
 * [ModelProvider] contract and [GgufModelProvider] implementation.
 */
package com.newax.aegis.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.assistant.riskLevel
import com.newax.aegis.authority.PolicyEngine
import com.newax.aegis.authority.PolicyMode
import com.newax.aegis.desktop.execution.DesktopCommandDispatcher
import com.newax.aegis.desktop.execution.DesktopGoalExecutor
import com.newax.aegis.desktop.planner.DesktopGoalPlanner
import com.newax.aegis.desktop.planner.Goal
import com.newax.aegis.desktop.planner.GoalState
import com.newax.aegis.desktop.planner.SkillRegistry
import com.newax.aegis.desktop.planner.TaskStatus
import com.newax.aegis.desktop.ui.AegisDesktopApp
import com.newax.aegis.desktopsync.DesktopSync
import com.newax.aegis.model.ModelRequest
import com.newax.aegis.platform.windows.GgufHeaderParser
import com.newax.aegis.platform.windows.GgufModelProvider
import com.newax.aegis.platform.windows.WindowsAppIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File

fun main(args: Array<String>) {
    if (args.contains("--cli")) {
        runBlocking { cliMain(args) }
    } else {
        windowMain()
    }
}

/**
 * Window mode — the Compose Desktop surface (Phase B1). Bootstraps the process
 * surfaces, imports and loads the model in the background (the Status screen
 * reflects the provider's live state), then runs the window until it closes.
 */
private fun windowMain() {
    DesktopCapabilitiesHolder.init()
    DesktopPolicyHolder.init()
    // Automatic encrypted sync with paired devices (docs/SYNC_DESIGN.md §4.2):
    // the shared desktop engine (Room-backed journal + LAN transport) runs
    // behind the window — `sync` commands in CLI mode drive it too. The
    // command dispatcher (Fix C) is the desktop leg of the mesh's remote-action
    // channel: incoming `to:<me>` commands verify signature → allowlist →
    // policy and execute open_app/run_shell.
    DesktopSync.setCommandDispatcher(DesktopCommandDispatcher::onIncoming)
    DesktopSync.start()
    val goalsStore = FileGoalsStore()
    restorePersistedState(goalsStore)
    val appIndex = WindowsAppIndex()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var modelProvider: GgufModelProvider? = null
    appScope.launch {
        val file = firstModelFile() ?: return@launch
        try {
            val imported = DesktopModelImporter.importAsync(file)
            val provider = GgufModelProvider(imported.file, imported.sha256)
            modelProvider = provider
            DesktopModelProviderHolder.set(provider)
            provider.load()
        } catch (e: Exception) {
            // The provider reports ERROR in its state; the Status screen shows
            // the honest reason instead of the app failing to open.
            println("[model] not loaded: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Aegis Assistant — Desktop",
            state = rememberWindowState(size = DpSize(1120.dp, 760.dp)),
        ) {
            AegisDesktopApp(appScope = appScope, appIndex = appIndex, store = goalsStore)
        }
    }

    appScope.cancel()
    modelProvider?.close()
    DesktopModelProviderHolder.clear()
    println("Done.")
}

/**
 * Deterministic model pick for window mode: the single discovered model, or the
 * largest one when several are present (the CLI asks interactively; the window
 * must not block on stdin). Null when no valid model is installed — the app
 * then runs on the deterministic fallback and the Status screen says so.
 */
private suspend fun firstModelFile(): File? = withContext(Dispatchers.IO) {
    val models = DesktopModelImporter.discover()
    when {
        models.isEmpty() -> null
        models.size == 1 -> models.first().file
        else -> models.maxByOrNull { it.bytes }?.file
    }
}

private suspend fun cliMain(args: Array<String>) {
    println()
    println("  █████╗ ███████╗ ██████╗ ██╗███████╗")
    println(" ██╔══██╗██╔════╝██╔════╝ ██║██╔════╝")
    println(" ███████║█████╗  ██║  ███╗██║███████╗")
    println(" ██╔══██║██╔══╝  ██║   ██║██║╚════██║")
    println(" ██║  ██║███████╗╚██████╔╝██║███████║")
    println(" ╚═╝  ╚═╝╚══════╝ ╚═════╝ ╚═╝╚══════╝")
    println()
    println("  Desktop — offline GGUF model runner")
    println("  shared/model-api · platform:windows · Phase 5i · window UI (B1) — --cli for this loop")
    println()

    // ── 0. Bootstrap the process-wide surfaces ────────────────────────────
    DesktopCapabilitiesHolder.init()
    DesktopPolicyHolder.init()
    // The command dispatcher (Fix C) — the desktop leg of the mesh's remote
    // action channel; registered before start so no inbound command is missed.
    DesktopSync.setCommandDispatcher(DesktopCommandDispatcher::onIncoming)
    DesktopSync.start()
    val goalsStore = FileGoalsStore()
    restorePersistedState(goalsStore)
    val appIndex = WindowsAppIndex()
    printStatusBlock()

    // ── 1. Find the model file ─────────────────────────────────────────────
    val modelArg = args.firstOrNull { it != "--cli" }
    val modelFile: File = when {
        modelArg != null -> {
            File(modelArg).also { f ->
                require(f.isFile) { "Model file not found: ${f.absolutePath}" }
            }
        }
        else -> {
            val models = DesktopModelImporter.discover()
            when {
                models.isEmpty() -> {
                    println("  ✗ No .gguf model found.")
                    println("    Place a model in ~/.aegis/models/ or pass the path as argument:")
                    println("    ./gradlew :apps:desktopApp:run --args=\"/path/to/model.gguf\"")
                    println()
                    return
                }
                models.size == 1 -> {
                    val m = models.first()
                    println("  ✓ Found: ${m.file.name}  (${m.sha256.take(10)}…)")
                    m.file
                }
                else -> {
                    println("  Multiple models found. Select one:")
                    models.forEachIndexed { i, m ->
                        println("    [$i] ${m.file.name}  — ${formatGb(m.file.length())}")
                    }
                    print("  > ")
                    val choice = (readLine()?.trim()?.toIntOrNull() ?: 0)
                        .coerceIn(0, models.lastIndex)
                    models[choice].file
                }
            }
        }
    }

    // ── 2. Import (validate + hash) ─────────────────────────────────────────
    val imported = DesktopModelImporter.importAsync(modelFile)
    val desc = GgufHeaderParser.readDescriptor(imported.file, imported.sha256)
    println()
    println("  Model  : ${desc.modelName}")
    println("  Format : ${desc.format}")
    println("  Size   : ${formatGb(imported.bytes)}")
    println("  SHA-256: ${imported.sha256.take(16)}…")

    // ── 3. Load the provider (swapped into the process-wide holder) ─────────
    val provider = GgufModelProvider(imported.file, imported.sha256)
    DesktopModelProviderHolder.set(provider)
    println()
    print("  Loading model… ")
    try {
        provider.load()
        println("READY")
        println()
        println("  Enter prompts below. \"status\" prints the capability/model block,")
        println("  empty line or Ctrl+D to exit.")
        println("  ───────────────────────────────────────────────────────")
        println()

        // ── 4. Interactive inference loop ──────────────────────────────────
        while (true) {
            print("  > ")
            val prompt = readLine()?.trim() ?: break
            if (prompt.isBlank() || prompt.equals("exit", ignoreCase = true)) break
            if (prompt.equals("status", ignoreCase = true)) {
                printStatusBlock()
                continue
            }
            if (prompt.equals("skills", ignoreCase = true)) {
                printSkills()
                continue
            }
            if (prompt.startsWith("plan ", ignoreCase = true)) {
                printPlan(prompt.substring(5).trim())
                goalsStore.save(DesktopGoalPlanner.snapshot())
                continue
            }
            if (prompt.startsWith("apps", ignoreCase = true)) {
                printApps(if (prompt.length > 4) prompt.substring(4).trim() else "", appIndex)
                continue
            }
            if (prompt.equals("goals", ignoreCase = true)) {
                printGoals()
                continue
            }
            if (prompt.equals("audit", ignoreCase = true) || prompt.startsWith("audit ", ignoreCase = true)) {
                printAudit(if (prompt.length > 5) prompt.substring(5) else "")
                continue
            }
            if (prompt.equals("sync", ignoreCase = true) || prompt.startsWith("sync ", ignoreCase = true)) {
                printSyncCommand(if (prompt.length > 4) prompt.substring(4).trim() else "")
                continue
            }
            if (prompt.equals("policy", ignoreCase = true) || prompt.startsWith("policy ", ignoreCase = true)) {
                printPolicy(if (prompt.length > 6) prompt.substring(6).trim() else "")
                continue
            }
            if (prompt.startsWith("run ", ignoreCase = true)) {
                printRunGoal(prompt.substring(4).trim(), appIndex)
                goalsStore.save(DesktopGoalPlanner.snapshot())
                continue
            }
            if (prompt.startsWith("abandon ", ignoreCase = true)) {
                printAbandon(prompt.substring(8).trim())
                goalsStore.save(DesktopGoalPlanner.snapshot())
                continue
            }
            if (prompt.equals("proximity", ignoreCase = true) || prompt.startsWith("proximity ", ignoreCase = true)) {
                printProximity(prompt.substring("proximity".length).trim())
                continue
            }

            print("  ─ ")
            try {
                val response = DesktopModelProviderHolder.current().complete(ModelRequest(text = prompt))
                println(response.text)
                if (response.truncated) println("  [truncated]")
            } catch (e: Exception) {
                println("[error] ${e.message ?: e.javaClass.simpleName}")
            }
            println()
        }
    } catch (e: Exception) {
        println("FAILED")
        println("  Error: ${e.message ?: e.javaClass.simpleName}")
        println()
        println("  Diagnostics:")
        println("    • File  : ${imported.file.absolutePath}")
        println("    • Size  : ${imported.bytes}")
        println("    • SHA-256: ${imported.sha256.take(16)}…")
        println("    • State : ${provider.state.value}")
        println("    • Classpath: (verify de.kherud:java-llama.cpp is on the classpath)")
    } finally {
        provider.close()
        DesktopModelProviderHolder.clear()
        println("  Done.")
    }
}

/**
 * Prints the platform capability registry + active model provider state — the
 * desktop equivalent of Android's Capabilities screen (Phase 5e). Re-read on
 * every call so the "status" command reflects live state.
 */
private fun printStatusBlock() {
    println()
    println("  ── Platform capabilities ──────────────────────────────")
    val capabilities = DesktopCapabilitiesHolder.registry()?.all().orEmpty()
    if (capabilities.isEmpty()) {
        println("    (registry not initialized)")
    } else {
        capabilities.forEach { capability ->
            val descriptor = capability.descriptor()
            println("    ${descriptor.displayName} (${descriptor.id})  ${descriptor.status}")
        }
    }
    val model = DesktopModelProviderHolder.current()
    println("  ── Model ───────────────────────────────────────────────")
    println("    ${model.descriptor.modelName}  [${model.descriptor.format}]")
    println("    state: ${model.state.value}  ·  sha256: ${model.descriptor.sha256.take(16)}…")
    println()
}

/**
 * The `sync` CLI surface — the twin of the window's Status card
 * (docs/SYNC_DESIGN.md §4.2), backed by the shared [DesktopSync] engine:
 * status, this device's pairing code, SAS-confirmed pairing, unpair, direct
 * `host:port` bootstrap, and the synced memory profile.
 */
private fun printSyncCommand(arg: String) {
    println()
    println("  ── Sync ────────────────────────────────────────────────")
    when {
        arg.isEmpty() || arg.equals("status", ignoreCase = true) -> {
            println("    device : ${DesktopSync.displayName()} (${DesktopSync.deviceId()})")
            println("    peers  : ${DesktopSync.peers().size}")
            println("    status : ${DesktopSync.status()}")
            println("    memory : ${DesktopSync.memoryCategories().size} category(ies) synced")
        }
        arg.equals("code", ignoreCase = true) -> {
            println("    This device's pairing code — paste it into the other device's pair field:")
            println("    ${DesktopSync.pairingCode()}")
        }
        arg.startsWith("pair ", ignoreCase = true) -> {
            val code = arg.substring(5).trim()
            if (code.isEmpty()) {
                println("    usage: sync pair <their-code>")
            } else {
                val sas = DesktopSync.sasFor(DesktopSync.pairingCode(), code)
                if (sas == null) {
                    println("    ✗ That doesn't look like a valid pairing code.")
                } else {
                    print("    Both devices show SAS $sas — confirm it matches (y/N): ")
                    System.out.flush()
                    if (readLine()?.trim()?.equals("y", ignoreCase = true) == true) {
                        val peer = DesktopSync.pairWith(code)
                        if (peer == null) println("    ✗ Pairing failed (invalid code or self-pair).")
                        else println("    ✓ Paired with ${peer.displayName} (${peer.deviceId})")
                    } else {
                        println("    cancelled")
                    }
                }
            }
        }
        arg.startsWith("unpair ", ignoreCase = true) -> {
            DesktopSync.unpair(arg.substring(7).trim())
            println("    ✓ removed")
        }
        arg.startsWith("peer ", ignoreCase = true) -> {
            val parts = arg.substring(5).trim().split(Regex("\\s+"))
            if (parts.size != 2) {
                println("    usage: sync peer <deviceId> <host:port>")
            } else {
                DesktopSync.setPeerAddress(parts[0], parts[1])
                println("    ✓ direct address stored for ${parts[0]}")
            }
        }
        arg.startsWith("memory", ignoreCase = true) -> {
            val memory = DesktopSync.memory()
            if (memory.isEmpty()) {
                println("    No synced memory yet — pair a device and wait for a sync round.")
            } else {
                memory.toSortedMap().forEach { (category, facts) ->
                    println("    · $category")
                    facts.forEach { println("        - $it") }
                }
            }
        }
        arg.equals("categories", ignoreCase = true) -> {
            DesktopSync.categories().forEach { (name, enabled) ->
                println("    ${if (enabled) "on " else "off"}  $name")
            }
        }
        arg.startsWith("category ", ignoreCase = true) -> {
            val parts = arg.substring(9).trim().split(Regex("\\s+"))
            if (parts.size != 2 || (parts[1] != "on" && parts[1] != "off")) {
                println("    usage: sync category <Memory profile|Knowledge graph|People|Settings & preferences> on|off")
            } else {
                val name = parts.slice(0 until parts.size - 1).joinToString(" ")
                DesktopSync.setCategory(name, parts[1] == "on")
                println("    ✓ $name ${parts[1]}")
            }
        }
        arg.startsWith("perms", ignoreCase = true) -> {
            val peers = DesktopSync.peers()
            if (peers.isEmpty()) {
                println("    No paired devices.")
            } else {
                peers.forEach { p ->
                    val allowed = DesktopSync.peerPermissions(p.deviceId)
                    println("    ${p.displayName} (${p.deviceId}): " +
                        (if (allowed.isEmpty()) "all commands" else allowed.sorted().joinToString(", ")))
                }
            }
        }
        arg.startsWith("perm ", ignoreCase = true) -> {
            val parts = arg.substring(5).trim().split(Regex("\\s+"))
            if (parts.size != 3 || (parts[2] != "on" && parts[2] != "off")) {
                println("    usage: sync perm <deviceId> <commandClass> on|off")
            } else {
                val current = DesktopSync.peerPermissions(parts[0]).toMutableSet()
                if (parts[2] == "on") current.add(parts[1]) else current.remove(parts[1])
                DesktopSync.setPeerPermissions(parts[0], current)
                println("    ✓ ${parts[1]} ${parts[2]} for ${parts[0]}")
            }
        }
        arg.startsWith("send ", ignoreCase = true) -> {
            val parts = arg.substring(5).trim().split(Regex("\\s+"))
            if (parts.size < 3) {
                println("    usage: sync send <deviceId> <class> <json-args>  (classes: ${DesktopSync.COMMAND_CLASSES.joinToString(", ")})")
            } else {
                val deviceId = parts[0]
                val commandClass = parts[1]
                val jsonArgs = parts.drop(2).joinToString(" ")
                val args = runCatching {
                    val o = org.json.JSONObject(jsonArgs)
                    buildMap { o.keys().forEach { k -> put(k, o.optString(k)) } }
                }.getOrDefault(emptyMap())
                DesktopSync.sendCommand(deviceId, commandClass, args)
                println("    ✓ command sent to $deviceId ($commandClass) — journaled, relays to the target")
            }
        }
        arg.startsWith("episodes", ignoreCase = true) -> {
            val episodes = DesktopSync.recentEpisodes(20)
            if (episodes.isEmpty()) {
                println("    No episodes yet — record one with: sync know ... / sync lesson ...")
            } else {
                episodes.forEach { ep ->
                    println("    [${ep.outcome}] ${ep.agentId} · ${ep.category}: ${ep.summary}" +
                        (if (ep.lesson.isNotBlank()) " — lesson: ${ep.lesson}" else ""))
                }
            }
        }
        arg.startsWith("library", ignoreCase = true) -> {
            val entries = DesktopSync.library()
            if (entries.isEmpty()) {
                println("    Library empty — submit with: sync know <category> <title> <content>")
            } else {
                entries.forEach { e ->
                    println("    [${e.category}] ${e.title} (${e.confidence}): ${e.content}")
                }
            }
        }
        arg.startsWith("know ", ignoreCase = true) -> {
            val parts = arg.substring(5).trim().split("|", limit = 4)
            if (parts.size < 3) {
                println("    usage: sync know <category> | <title> | <content>  (lands PENDING — approve with: sync approve <entryId>)")
            } else {
                DesktopSync.submitKnowledge(parts[0].trim(), parts[1].trim(), parts[2].trim())
                println("    ✓ submitted to the human gate (PENDING_APPROVAL)")
            }
        }
        arg.startsWith("approve ", ignoreCase = true) -> {
            DesktopSync.approveKnowledge(arg.substring(8).trim())
            println("    ✓ approved (ACTIVE — visible to all agents)")
        }
        arg.startsWith("lesson ", ignoreCase = true) -> {
            val parts = arg.substring(7).trim().split("|", limit = 4)
            if (parts.size < 3) {
                println("    usage: sync lesson <category> | <summary> | <lesson>  (FAILURE episode — propagates the fix)")
            } else {
                DesktopSync.recordEpisode("desktop", parts[0].trim(), parts[1].trim(), "FAILURE", parts[2].trim())
                println("    ✓ lesson recorded + journaled into the mesh")
            }
        }
        arg.startsWith("handoff ", ignoreCase = true) -> {
            val parts = arg.substring(8).trim().split("|", limit = 4)
            if (parts.size < 3) {
                println("    usage: sync handoff <toAgent> | <task> | <summary>")
            } else {
                DesktopSync.createHandoff("desktop", parts[0].trim(), parts[1].trim(), parts[2].trim())
                println("    ✓ handoff written to ${parts[0].trim()} — ack with: sync ack <id>")
            }
        }
        arg.startsWith("ack ", ignoreCase = true) -> {
            DesktopSync.ackHandoff(arg.substring(4).trim())
            println("    ✓ acked")
        }
        arg.startsWith("history", ignoreCase = true) -> {
            val history = DesktopSync.commandHistory()
            if (history.isEmpty()) {
                println("    No commands sent or received yet — send one with: sync send <id> <class> <json>")
            } else {
                history.forEach { h ->
                    println("    ${if (h.sent) "sent" else "recv"}  ${h.detail}  ·  ${h.peerDeviceId.take(12)}  ·  " +
                        java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                        ).format(java.util.Date(h.atMs)))
                }
            }
        }
        else -> println("    commands: (empty|status), code, pair <code>, unpair <id>, peer <id> <host:port>, memory, categories, category <name> on|off, perms, perm <id> <class> on|off, send <id> <class> <json>, history, episodes, library, know <cat>|<title>|<content>, approve <id>, lesson <cat>|<summary>|<lesson>, handoff <to>|<task>|<summary>, ack <id>")
    }
    println()
}

/**
 * Lists the planner skill registry — what the planner can put in a plan and what
 * platform surface each skill requires (Phase 5f).
 */
private fun printSkills() {
    println()
    println("  ── Skills (planner registry) ───────────────────────────")
    SkillRegistry.allSkills().forEach { skill ->
        val requirements = if (skill.requiredCapabilities.isEmpty())
            "no platform requirements"
        else
            skill.requiredCapabilities.joinToString(", ")
        println("    ${skill.id}  — ${skill.description}  [requires: $requirements]")
    }
    println()
}

/**
 * Plans one goal through the capability contract and prints the resolution —
 * the runner's surface for why a goal is or isn't feasible (the desktop
 * counterpart of Android's Goals screen missing-capabilities block).
 */
private fun printPlan(description: String) {
    println()
    println("  ── Plan ────────────────────────────────────────────────")
    if (description.isBlank()) {
        println("    usage: plan <goal>  (e.g. \"plan open spotify\")")
        return
    }
    val plan = DesktopGoalPlanner.plan(description, DesktopCapabilitiesHolder.registry())
    println("    Goal     : ${plan.goal.description}")
    println("    Intent   : ${plan.goal.intent}")
    println("    Tasks    :")
    plan.tasks.forEachIndexed { index, task ->
        println("      [${index + 1}] ${task.description}  (${task.skillId})")
    }
    if (plan.missingSkills.isNotEmpty()) {
        println("    Missing skills   : ${plan.missingSkills.joinToString(", ")}")
    }
    plan.warnings.forEach { warning -> println("    ⚠ $warning") }
    println(
        "    Feasible : " +
            (if (plan.feasible) "YES — all skills and platform capabilities are ready"
            else "NO — blocked capabilities above must be resolved first")
    )
    if (plan.feasible) println("    Next     : run \"goals\", then \"run <goal>\" to execute it")
    println()
}

/**
 * The goal board — desktop counterpart of Android's Goals screen. Lists every
 * stored goal with its state, task progress bar, and plan pre-flight (blocked
 * capabilities with reasons). Goal references in commands accept the 1-based
 * number shown here or the goal id.
 */
private fun printGoals() {
    println()
    println("  ── Goals ──────────────────────────────────────────────")
    val goals = sortedGoals()
    if (goals.isEmpty()) {
        println("    No goals yet. Use \"plan <goal>\" to plan one.")
        println()
        return
    }
    val active = DesktopGoalPlanner.activeGoals().size
    val blocked = goals.count { g -> DesktopGoalPlanner.planOf(g.id)?.let { !it.feasible } == true }
    val summary = buildString {
        append("    ${goals.size} ${if (goals.size == 1) "goal" else "goals"}")
        if (active > 0) append(" · $active active")
        append(if (blocked == 0) " · all plans feasible" else " · $blocked blocked by platform capabilities")
    }
    println(summary)
    println()
    goals.forEachIndexed { index, goal ->
        val state = DesktopGoalPlanner.getState(goal.id) ?: GoalState.OPEN
        println("    [${index + 1}] ${goal.description}   ${stateLabel(state)}")
        DesktopGoalPlanner.getGraph(goal.id)?.let { graph ->
            val done = graph.tasks.count { it.status != TaskStatus.PENDING }
            println("        ${progressBar(graph.progress())} $done/${graph.tasks.size} tasks")
        }
        val plan = DesktopGoalPlanner.planOf(goal.id)
        if (plan != null && !plan.feasible) {
            plan.warnings.forEach { warning -> println("        ⚠ $warning") }
            if (state == GoalState.BLOCKED) {
                println("        \"run ${index + 1}\" re-checks capabilities live once the blocker clears")
            }
        } else if (state == GoalState.OPEN || state == GoalState.BLOCKED) {
            println("        feasible — \"run ${index + 1}\" to execute")
        }
    }
    val runs = ExecutionAudit.recent()
    if (runs.isNotEmpty()) {
        println("  ── Recent runs (execution audit) ────────────────────────")
        runs.take(5).forEach { run ->
            val tierText = if (run.tiers.isEmpty()) "no tier" else run.tiers.joinToString(" · ")
            println("    ${run.outcome.lowercase().replaceFirstChar { it.uppercase() }}  ${run.goalDescription}  [$tierText]  ${auditTime(run.completedMs)}")
            run.reason?.let { println("        ✗ $it") }
        }
        println()
    }
    println()
}

/**
 * Executes a goal's plan for real — [DesktopGoalExecutor] replacing the manual
 * task command (Phase 5h). The runner is the surface: pre-flight warnings print
 * first, then each task's RUNNING/DONE/FAILED line as the executor walks the
 * plan through the process-launch → Win32-activateApp ladder (the capability
 * gate is re-checked live per task, and a FAILED task blocks the goal).
 */
private suspend fun printRunGoal(ref: String, appIndex: WindowsAppIndex) {
    println()
    println("  ── Execute ─────────────────────────────────────────────")
    if (ref.isBlank()) {
        println("    usage: run <goal>  (e.g. \"run 1\", \"run open spotify\")")
        return
    }
    val goal = resolveGoal(ref)
    if (goal == null) {
        println("    Unknown goal: $ref (run \"goals\" to list them)")
        return
    }
    val plan = DesktopGoalPlanner.planOf(goal.id)
    if (plan != null && !plan.feasible) {
        plan.warnings.forEach { warning -> println("    ⚠ $warning") }
        println("    (the executor re-checks each capability live — a blocked task fails the goal honestly)")
    }
    val result = DesktopGoalExecutor.run(
        goal.id,
        DesktopCapabilitiesHolder.registry(),
        appIndex = appIndex,
        onProgress = { line -> println("    $line") },
    )
    if (result.isSuccess) {
        println("    ✓ \"${goal.description}\" COMPLETED")
    } else {
        println("    ✗ ${result.exceptionOrNull()?.message ?: "execution failed"}")
    }
    println()
}

/**
 * Lists the Start Menu app index the planner's find_app resolves against —
 * "apps" shows everything, "apps <query>" searches (exact name first). Empty
 * on non-Windows, where there is no Windows Start Menu to enumerate (Phase 5i).
 */
private fun printApps(query: String, index: WindowsAppIndex) {
    println()
    println("  ── Apps (Start Menu index) ──────────────────────────────")
    val entries = if (query.isBlank()) index.all() else index.search(query)
    if (entries.isEmpty()) {
        println(
            if (query.isBlank())
                "    No installed apps indexed (the Start Menu index is Windows-only)."
            else
                "    No apps match \"$query\"."
        )
        println()
        return
    }
    entries.take(40).forEach { entry ->
        println("    ${entry.name}  [${entry.category}]")
        println("      ${entry.lnkPath}")
    }
    if (entries.size > 40) println("    … and ${entries.size - 40} more")
    println()
}

/**
 * The desktop Quick Share surface (docs/SYNC_DESIGN.md §10.1 / P2) — CLI
 * twin of Android's Nearby screen: listen (mDNS advertise + encrypted TCP
 * receive), send <file> <deviceId> (discover, connect, encrypt, transfer),
 * and nearby (list LAN peers).
 */
private fun printProximity(args: String) {
    println()
    println("  ── Proximity (encrypted Quick Share) ────────────────────")
    when {
        args == "listen" -> ProximityCli.listen()
        args == "nearby" -> ProximityCli.nearby()
        args.startsWith("send ") -> {
            val rest = args.substring(5).trim()
            val lastSpace = rest.lastIndexOf(' ')
            if (lastSpace <= 0) {
                println("    usage: proximity send <file> <deviceId>")
            } else {
                val file = rest.substring(0, lastSpace).trim().removeSurrounding("\"")
                val deviceId = rest.substring(lastSpace + 1).trim()
                ProximityCli.send(file, deviceId)
            }
        }
        else -> println("    usage: proximity listen | send <file> <deviceId> | nearby")
    }
    println()
}

/** Gives up on a goal — OPEN/ACTIVE/BLOCKED → ABANDONED (mirrors the Goals screen button). */
private fun printAbandon(ref: String) {
    println()
    println("  ── Abandon ─────────────────────────────────────────────")
    val goal = resolveGoal(ref)
    if (goal == null) {
        println("    Unknown goal: $ref (run \"goals\" to list them)")
        return
    }
    val ok = DesktopGoalPlanner.abandon(goal.id)
    println(
        if (ok) "    \"${goal.description}\" → ABANDONED"
        else "    Could not abandon \"${goal.description}\" " +
            "(state: ${stateLabel(DesktopGoalPlanner.getState(goal.id) ?: GoalState.OPEN)})"
    )
    println()
}

/**
 * The full execution audit trail — every recorded run, newest first, with the
 * tiers used, task count, window, and reason; \"audit export\" writes the same
 * trail to a CSV under ~/.aegis/ (audit-<timestamp>.csv). The CLI twin of the
 * Audit tab in the window.
 */
private fun printAudit(command: String) {
    println()
    println("  ── Execution audit ───────────────────────────────────")
    if (command.trim().equals("export", ignoreCase = true)) {
        val runs = ExecutionAudit.all()
        AuditExporter.exportCsv(runs).fold(
            onSuccess = { file ->
                println("    ✓ Exported ${runs.size} ${if (runs.size == 1) "run" else "runs"} to ${file.toAbsolutePath()}")
            },
            onFailure = { e ->
                println("    ✗ Export failed: ${e.message ?: e.javaClass.simpleName}")
            }
        )
        println()
        return
    }
    val runs = ExecutionAudit.all().sortedByDescending { it.completedMs }
    if (runs.isEmpty()) {
        println("    No runs recorded yet. Run a goal (\"run <goal>\") to build the trail.")
        println()
        return
    }
    println("    ${runs.size} ${if (runs.size == 1) "run" else "runs"} · \"audit export\" writes CSV to ~/.aegis/")
    val summary = AuditSummary.of(runs)
    println(
        "    Success rate: ${summary.successRatePercent}% (${summary.completedRuns}/${summary.totalRuns})" +
            "  ·  Average duration: ${formatDuration(summary.avgDurationMs)}"
    )
    summary.tierBreakdown.forEach { tier ->
        println(
            "    ${tier.tier}  — ${tier.runs} ${if (tier.runs == 1) "run" else "runs"} (${tier.successRatePercent}% complete)"
        )
    }
    runs.forEach { run ->
        val tierText = if (run.tiers.isEmpty()) "no tier" else run.tiers.joinToString(" · ")
        println(
            "    ${run.outcome.lowercase().replaceFirstChar { it.uppercase() }}  ${run.goalDescription}" +
                "  [$tierText]  ${run.taskCount} ${if (run.taskCount == 1) "task" else "tasks"}  ${auditTime(run.completedMs)}" +
                (if (run.durationMs > 0) "  (${run.durationMs} ms)" else "")
        )
        run.reason?.let { println("        ✗ $it") }
    }
    println()
}

/**
 * The `policy` CLI family — the desktop twin of the Policy tab and of Android's
 * policy settings: list effective modes + the decision trail ("policy"), set a
 * mode override ("policy set <Class> <MODE>"), hard-deny ("policy deny <Class>"),
 * lift a deny ("policy allow <Class>"), reset to defaults ("policy reset <Class>"),
 * clear the decision history ("policy clear"), and export the trail to CSV
 * ("policy export" → ~/.aegis/policy-audit-<timestamp>.csv). Everything routes
 * through the one process engine ([DesktopPolicyHolder]) — the same store the
 * window uses.
 */
private fun printPolicy(command: String) {
    val engine = DesktopPolicyHolder.engineOrNull()
    if (engine == null) {
        println("  ✗ Policy holder not initialized.")
        return
    }

    if (command.isNotBlank()) {
        val parts = command.split(" ")
        when (parts[0].lowercase()) {
            "set" -> {
                val cls = parts.getOrNull(1) ?: return println("  usage: policy set <ActionClass> <AUTO|CONFIGURABLE|APPROVAL|STRONG_CONFIRMATION>")
                val mode = PolicyMode.entries.firstOrNull { it.name.equals(parts.getOrNull(2), ignoreCase = true) }
                if (mode == null) {
                    println("  ✗ Unknown mode '${parts.getOrNull(2)}' — use AUTO, CONFIGURABLE, APPROVAL, or STRONG_CONFIRMATION.")
                    return
                }
                engine.setModeOverride(cls, mode)
                println("  ✓ $cls → ${mode.name} (persisted under ~/.aegis/policy-settings.json)")
                return
            }
            "deny" -> {
                val cls = parts.getOrNull(1) ?: return println("  usage: policy deny <ActionClass>")
                engine.setDenied(cls, true)
                println("  ✓ $cls hard-denied — every evaluation now refuses.")
                return
            }
            "allow" -> {
                val cls = parts.getOrNull(1) ?: return println("  usage: policy allow <ActionClass>")
                engine.setDenied(cls, false)
                println("  ✓ $cls deny lifted.")
                return
            }
            "reset" -> {
                val cls = parts.getOrNull(1) ?: return println("  usage: policy reset <ActionClass>")
                engine.clearModeOverride(cls)
                engine.setDenied(cls, false)
                println("  ✓ $cls back to its risk-based default mode.")
                return
            }
            "clear" -> {
                DesktopPolicyHolder.clearAuditHistory()
                println("  ✓ Policy-decision history cleared (memory + ~/.aegis/policy-audit.json).")
                return
            }
            "export" -> {
                val history = DesktopPolicyHolder.auditHistory().sortedByDescending { it.auditedAtMs }
                if (history.isEmpty()) {
                    println("  ✗ Nothing to export — no policy decisions recorded yet.")
                    return
                }
                PolicyExporter.exportCsv(history).fold(
                    onSuccess = { file ->
                        println(
                            "  ✓ Exported ${history.size} ${if (history.size == 1) "decision" else "decisions"} → $file"
                        )
                    },
                    onFailure = { e ->
                        println("  ✗ Export failed: ${e.message ?: e.javaClass.simpleName}")
                    },
                )
                return
            }
            else -> {
                println("  ✗ Unknown policy command '${parts[0]}' — try: set, deny, allow, reset, export, clear.")
                return
            }
        }
    }

    // ── policy (no subcommand): modes + decision summary ──────────────────
    println()
    println("  ── Policy ────────────────────────────────────────────")
    println("    Per-class effective modes (override → risk-based default):")
    POLICY_CLASSES.forEach { cls ->
        val sample = policySampleFor(cls)
        val default = PolicyEngine.defaultModeFor(sample.riskLevel)
        val effective = engine.effectiveMode(sample)
        val custom = engine.hasModeOverride(cls)
        val denied = engine.isDenied(cls)
        val label = when {
            denied -> "DENIED"
            custom -> "${effective.name} (custom)"
            else -> "${effective.name} (default ${default.name})"
        }
        println("    ${cls.padEnd(20)} $label")
    }
    val history = DesktopPolicyHolder.auditHistory()
    if (history.isEmpty()) {
        println("    No policy decisions recorded yet — \"policy set/deny\" above or run a goal to start the trail.")
    } else {
        println()
        println("    ${history.size} ${if (history.size == 1) "decision" else "decisions"} recorded:")
        history.sortedByDescending { it.auditedAtMs }.forEach { r ->
            println(
                "    ${r.decision.name.lowercase().replaceFirstChar { it.uppercase() }}  ${r.actionClass}" +
                    "  · ${r.actionSummary}  · ${r.mode.name} · ${r.origin.name.lowercase()}  ${policyTime(r.auditedAtMs)}"
            )
        }
    }
    println()
}

/** The curated policy rows, shared with the window's Policy tab (mirror of Android). */
private val POLICY_CLASSES = listOf(
    "OpenApp", "Send", "SendImage", "DeleteFile", "DeleteContact", "DeleteProject",
    "ForgetFact", "RunScript", "PostSocialMedia", "CreateEvent", "ReplyNotification", "UpdateMemory",
)

/** A sample action for a class so its risk-based default mode can be read (desktop mirror). */
private fun policySampleFor(actionClass: String): ProposedAction = when (actionClass) {
    "Send" -> ProposedAction.Send("")
    "SendImage" -> ProposedAction.SendImage("")
    "DeleteFile" -> ProposedAction.DeleteFile("")
    "DeleteContact" -> ProposedAction.DeleteContact("")
    "DeleteProject" -> ProposedAction.DeleteProject("")
    "ForgetFact" -> ProposedAction.ForgetFact("", "")
    "RunScript" -> ProposedAction.RunScript("")
    "PostSocialMedia" -> ProposedAction.PostSocialMedia("", "", "", "")
    "CreateEvent" -> ProposedAction.CreateEvent("", "")
    "ReplyNotification" -> ProposedAction.ReplyNotification("", "")
    "UpdateMemory" -> ProposedAction.UpdateMemory("", "")
    "OpenApp" -> ProposedAction.OpenApp("")
    else -> ProposedAction.Tap("")
}

private val POLICY_TIME_FORMATTER_CLI: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm")

private fun policyTime(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault())
        .format(POLICY_TIME_FORMATTER_CLI)

/** Goals in board order (priority desc — the order the board numbers them). */
private fun sortedGoals(): List<Goal> =
    DesktopGoalPlanner.allGoals().sortedByDescending { it.priority }

/** Resolves a goal reference: 1-based board number, full id, or id prefix. */
private fun resolveGoal(ref: String): Goal? {
    val goals = sortedGoals()
    ref.toIntOrNull()?.let { index -> return goals.getOrNull(index - 1) }
    return goals.firstOrNull { it.id == ref || it.id.startsWith(ref) }
}

private fun stateLabel(state: GoalState): String = when (state) {
    GoalState.OPEN -> "Open"
    GoalState.ACTIVE -> "Active"
    GoalState.BLOCKED -> "Blocked"
    GoalState.COMPLETED -> "Completed"
    GoalState.ABANDONED -> "Abandoned"
}

private fun progressBar(progress: Float, width: Int = 10): String {
    val filled = (progress.coerceIn(0f, 1f) * width).roundToInt()
    return "[" + "█".repeat(filled) + "░".repeat(width - filled) + "]"
}

/** Restores the persisted goals + audit snapshot on bootstrap (Phase B3). A missing or corrupt store is an honest empty start. */
private fun restorePersistedState(store: GoalsStore) {
    val snapshot = store.load() ?: return
    DesktopGoalPlanner.restore(snapshot)
    ExecutionAudit.replaceAll(snapshot.runs)
    println("[goals] restored ${snapshot.goals.size} goal(s), ${snapshot.runs.size} audit run(s) from ${FileGoalsStore.defaultFile()}")
}

private val AUDIT_TIME_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

private fun auditTime(epochMs: Long): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).format(AUDIT_TIME_FORMATTER)

private fun formatDuration(ms: Long): String = when {
    ms >= 1_000 -> "${"%.1f".format(ms / 1000.0)} s"
    else -> "$ms ms"
}

private fun formatGb(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "${ "%.2f".format(bytes / 1_000_000_000.0) } GB"
    bytes >= 1_000_000     -> "${ "%.0f".format(bytes / 1_000_000.0) } MB"
    bytes >= 1_000          -> "${bytes / 1000} KB"
    else                    -> "$bytes B"
}
