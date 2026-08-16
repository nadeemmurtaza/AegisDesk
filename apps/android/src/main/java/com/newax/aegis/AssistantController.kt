package com.newax.aegis

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.CalendarContract
import com.newax.aegis.accessibility.NewaxAccessibilityService
import com.newax.aegis.assistant.*
import com.newax.aegis.engine.AutomationSettings
import com.newax.aegis.engine.ContactsManager
import com.newax.aegis.engine.apps.AppCapability
import com.newax.aegis.engine.apps.AppIntelligence
import com.newax.aegis.engine.embedding.VectorMemorySearch
import com.newax.aegis.engine.files.FileIntelligence
import com.newax.aegis.engine.files.WorldRegistry
import com.newax.aegis.engine.learning.DraftStore
import com.newax.aegis.engine.learning.LlmFactExtractor
import com.newax.aegis.engine.learning.LlmTripleExtractor
import com.newax.aegis.engine.learning.LearningWorker
import com.newax.aegis.engine.learning.MemoryConsolidator
import com.newax.aegis.engine.learning.PersonFactStore
import com.newax.aegis.engine.person.PersonRegistry
import com.newax.aegis.engine.planner.CandidateMerger
import com.newax.aegis.engine.planner.DeterministicResolver
import com.newax.aegis.engine.planner.QueryPlanner
import com.newax.aegis.engine.procedure.ProcedureExecutor
import com.newax.aegis.engine.resource.JobPriority
import com.newax.aegis.engine.resource.NewaxJob
import com.newax.aegis.engine.resource.ResourceClass
import com.newax.aegis.engine.resource.ResourceGovernor
import com.newax.aegis.model.ModelRequest
import com.newax.aegis.model.ModelState
import com.newax.aegis.platform.android.LiteRtModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Calendar

/**
 * Resolves a string resource outside composition (T3.2): the chat pipeline is
 * plain Kotlin with no @Composable access, so the ViewModel injects a resolver
 * backed by Application.getString(). `resolve` takes the same vararg format
 * args as getString — pass nothing for resources without placeholders.
 */
fun interface StringResolver {
    fun resolve(resId: Int, vararg args: Any): String
}

/**
 * The chat inference pipeline (T3.1) — the half of MainViewModel that turns a
 * submitted turn into a reply. It owns the deterministic fast-paths (facts,
 * commitments, files, send-file, app launch), the multi-agent orchestration and
 * the streamed LLM generation, the typed-action executor, the calendar helpers
 * and the offline-model import/load lifecycle.
 *
 * The ViewModel remains the single owner of the Compose-observable state and
 * the DB/chat-history seams; the controller writes through that surface
 * ([MainViewModel.appendChat], the state properties, [MainViewModel.launch])
 * so the UI and the pipeline cannot drift. All decision logic here is plain
 * Kotlin against injectable seams, so the fast-path routing is unit-testable
 * without Android (the fast-path trigger inventory is a Track 5 handover, see
 * the T3.1 PR notes).
 *
 * **The regex fast-paths below are preserved verbatim from the god object —
 * this is a move, not a rewrite.** The trigger lists (commitments, files,
 * send-file, app launch, correction markers) are exactly what they were; Track
 * 5 takes ownership of them in a later slice.
 */
class AssistantController(
    private val app: Application,
    private val vm: MainViewModel,
    private val strings: StringResolver,
) {

    private val engine = LocalAssistantEngine()
    private val modelImporter = ModelImporter(app)
    private var modelProvider: LiteRtModelProvider? = null
    private var activeGenerationJob: Job? = null
    private val queuedActions = ArrayDeque<ProposedAction>()

    /**
     * The agent-run session id, when one is live — slice 12's inline step
     * block in the chat thread consumes this to render the run's AgentStream
     * events while they happen, and collapses to the final state when it
     * clears. Set when an agent session starts, cleared on every terminal
     * path (success, abandon, error) in the job's `finally`.
     */
    private val _activeAgentSessionId = MutableStateFlow<String?>(null)
    val activeAgentSessionId: StateFlow<String?> = _activeAgentSessionId.asStateFlow()

    /**
     * Prefixes that mark system-generated chat lines and must never reach the
     * model prompt as if they were conversation. `[System` is a legacy marker;
     * the background-processing line matches its (localized) text so the
     * filter survives translation (T3.2).
     */
    private val systemLinePrefixes = listOf("[System", strings.resolve(R.string.chat_processing_background))

    /**
     * Explicit user corrections — the CRITIC protocol's trigger set
     * (docs/AGENTS_DESIGN.md §evolution). Conservative on purpose: only
     * unambiguous corrections of the assistant's output register a Negative
     * Reward Signal ("that research report is completely wrong…"), never
     * ordinary requests that merely contain a negated word.
     */
    private val correctionMarkers = listOf(
        "you're wrong", "you are wrong", "that's wrong", "that was wrong",
        "thats wrong", "wrong answer", "your answer is wrong", "this is wrong",
        "that is wrong", "that's incorrect", "that is incorrect", "you got it wrong"
    )

    fun submit(text: String, isBackground: Boolean = false) {
        if (text.isBlank()) return
        if (!isBackground) vm.appendChat(text.trim(), true)
        val lower = text.trim().lowercase()
        if (lower.startsWith("remember that ")) {
            val fact = text.trim().substringAfter(" ").substringAfter(" ").trim()
            vm.memory.remember("personal", fact)
            vm.appendChat(strings.resolve(R.string.chat_saved_fact, fact), false)
            return
        }
        if (lower == "forget everything" || lower == "clear memory") {
            vm.memory.forgetAll()
            vm.bumpMemoryVersion()
            vm.appendChat(strings.resolve(R.string.chat_memory_cleared), false)
            return
        }
        // ── Commitment/person fast path ───────────────────────────────────────
        val commitmentTriggers = listOf("what am i waiting for", "what do i owe", "overdue", "pending commitment", "commitments from", "commitments to")
        if (commitmentTriggers.any { lower.contains(it) }) {
            vm.launch {
                val reply = withContext(Dispatchers.IO) {
                    val overdue = PersonRegistry.overdueCommitments(vm.db)
                    if (overdue.isNotEmpty()) {
                        "Overdue commitments:\n" + overdue.take(5).joinToString("\n") { "• ${it.action} (${it.debtorLabel} → ${it.creditorLabel})" }
                    } else {
                        val userOwes = vm.db.personRegistryDao().userCommitments(5)
                        if (userOwes.isNotEmpty())
                            "Your pending commitments:\n" + userOwes.joinToString("\n") { "• ${it.action} → ${it.creditorLabel}" }
                        else "No pending commitments found."
                    }
                }
                vm.appendChat(reply, false)
            }
            return
        }
        // ── File search fast path ─────────────────────────────────────────────
        val fileTriggers = listOf("find file", "find document", "find the file", "find the doc", "recent files", "recent documents", "show files", "list files", "open file", "share file")
        if (fileTriggers.any { lower.contains(it) }) {
            vm.launch {
                val reply = withContext(Dispatchers.IO) {
                    val query = lower
                        .replace(Regex("find (the )?file|find (the )?doc(ument)?|recent files|recent documents|show files|list files|open file|share file"), "")
                        .trim()
                    if (query.isBlank()) {
                        val files = FileIntelligence.recentIndexed(app, vm.db, 10)
                        if (files.isEmpty()) "No recent files."
                        else "Recent files:\n${FileIntelligence.describeResults(files)}"
                    } else {
                        // Try multi-index first, then fall back to WorldRegistry resolveFiles
                        val indexed = WorldRegistry.resolveFiles(app, vm.db, query, 8)
                        if (indexed.isNotEmpty()) {
                            "Found ${indexed.size} file(s):\n${FileIntelligence.describeFileObjects(indexed)}"
                        } else {
                            val files = FileIntelligence.findBest(app, query, 8)
                            if (files.isEmpty()) "No files found for \"$query\"."
                            else "Found ${files.size} file(s):\n${FileIntelligence.describeResults(files)}"
                        }
                    }
                }
                vm.appendChat(reply, false)
            }
            return
        }
        // ── Send file fast path: "send [file query] to [person]" ─────────────
        val sendFileRegex = Regex("""send (.+?) to (.+)""", RegexOption.IGNORE_CASE)
        val sendFileMatch = sendFileRegex.find(lower)
        if (sendFileMatch != null) {
            val fileQuery  = sendFileMatch.groupValues[1].trim()
            val personName = sendFileMatch.groupValues[2].trim()
            vm.launch {
                val reply = withContext(Dispatchers.IO) {
                    val result = WorldRegistry.resolveFileTask(
                        app, vm.db,
                        WorldRegistry.FileTaskQuery(fileQuery, personName, AppCapability.SEND_FILE)
                    )
                    if (result == null) return@withContext "No file found matching \"$fileQuery\" or person \"$personName\" unknown."
                    val topFile = result.files.firstOrNull()
                    val filename = topFile?.filename ?: "file"
                    val sizeStr = topFile?.let {
                        when {
                            it.sizeBytes > 1_048_576 -> "${"%.1f".format(it.sizeBytes / 1_048_576.0)} MB"
                            it.sizeBytes > 1024 -> "${it.sizeBytes / 1024} KB"
                            else -> "${it.sizeBytes} B"
                        }
                    } ?: ""
                    val appShort = result.packageName.substringAfterLast('.')
                    if (!result.requiresConfirm && result.sendIntent != null) {
                        app.startActivity(result.sendIntent)
                        "Sending $filename to ${result.personName}."
                    } else if (result.sendIntent != null) {
                        "Ready to send $filename ($sizeStr) to ${result.personName} via $appShort. Confirm?"
                    } else {
                        "Found $filename but couldn't build send intent for ${result.personName}."
                    }
                }
                vm.appendChat(reply, false)
            }
            return
        }
        // ── App registry fast path (no LLM, no screenshot) ───────────────────
        val quickPlan = QueryPlanner.plan(text)
        if (quickPlan.intent == QueryPlanner.Intent.APP_LAUNCH) {
            val cap = quickPlan.appCapabilityHint ?: AppCapability.OPEN_APP
            // Person+capability combined resolution (e.g. "Message Ali", "Call Sara")
            val personName = quickPlan.entityNames.firstOrNull()
            val personTask = personName?.let {
                runCatching { PersonRegistry.resolveTask(vm.db, app, it, cap) }.getOrNull()
            }
            if (personTask != null) {
                val pol = personTask.policy
                val needsConfirm = (cap == AppCapability.CALL && !pol.canCallWithoutConfirm) ||
                    (cap == AppCapability.SEND_TEXT && !pol.canAutoSend) ||
                    pol.sensitiveActionsRequireConfirm
                val res = personTask.appResolution
                if (!needsConfirm) {
                    when {
                        res.intent != null -> {
                            try {
                                app.startActivity(res.intent)
                                vm.appendChat(strings.resolve(R.string.chat_capability_done, cap.name.lowercase().replace('_', ' '), personTask.personName), false)
                                PersonRegistry.recordChannelUsed(vm.db, personTask.personEntityId, "default", res.packageName, cap.name)
                            } catch (_: Exception) {
                                vm.appendChat(strings.resolve(R.string.chat_could_not_complete, personTask.personName), false)
                            }
                            return
                        }
                        res.procedure != null -> {
                            vm.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ProcedureExecutor.executeFromJson(
                                        res.procedure.steps, app, vm.db, res.procedure.id,
                                        res.procedure.packageName,
                                    )
                                }
                                if (result.success) {
                                    AppIntelligence.recordProcedureSuccess(vm.db, res.procedure.id)
                                    PersonRegistry.recordChannelUsed(vm.db, personTask.personEntityId, "default", res.packageName, cap.name)
                                    vm.appendChat(strings.resolve(R.string.chat_capability_done, cap.name.lowercase().replace('_', ' '), personTask.personName), false)
                                } else {
                                    AppIntelligence.recordProcedureFailure(vm.db, res.procedure.id)
                                    vm.appendChat(strings.resolve(R.string.chat_procedure_failed, result.failReason), false)
                                }
                            }
                            return
                        }
                    }
                }
            }
            val appLabel = quickPlan.entityNames.firstOrNull() ?: quickPlan.keywords.firstOrNull()
            if (appLabel != null) {
                val pkg = AppIntelligence.packageForLabel(vm.db, appLabel)
                val resolution = pkg?.let { AppIntelligence.resolve(vm.db, app, cap, it) }
                    ?: AppIntelligence.resolve(vm.db, app, cap)
                if (resolution != null) {
                    when {
                        resolution.intent != null -> {
                            try {
                                app.startActivity(resolution.intent)
                                vm.appendChat(strings.resolve(R.string.chat_opened_app, appLabel), false)
                            } catch (_: Exception) {
                                vm.appendChat(strings.resolve(R.string.chat_could_not_open_app, appLabel), false)
                            }
                            return
                        }
                        resolution.procedure != null -> {
                            vm.launch {
                                vm.appendChat(strings.resolve(R.string.chat_executing_procedure, appLabel), false)
                                val result = withContext(Dispatchers.IO) {
                                    ProcedureExecutor.executeFromJson(
                                        resolution.procedure.steps, app, vm.db,
                                        resolution.procedure.id, resolution.procedure.packageName,
                                    )
                                }
                                if (result.success) {
                                    AppIntelligence.recordProcedureSuccess(vm.db, resolution.procedure.id)
                                    vm.appendChat(strings.resolve(R.string.chat_procedure_done, result.stepsCompleted), false)
                                } else {
                                    AppIntelligence.recordProcedureFailure(vm.db, resolution.procedure.id)
                                    vm.appendChat(strings.resolve(R.string.chat_procedure_failed_step, result.failedStep, result.failReason), false)
                                }
                            }
                            return
                        }
                    }
                }
            }
        }
        // ─────────────────────────────────────────────────────────────────────
        // Multi-agent orchestration (docs/AGENTS_DESIGN.md): route the request
        // (per step), chain handoffs between the dominant agents (they
        // communicate through the L3 shared-write layer), and record the
        // orchestration as episodes. The active-agent block is injected into
        // the model prompt below; disabled agents never route.
        val agentPlan = com.newax.aegis.agents.AgentOrchestrator.planFor(text)
        runCatching { com.newax.aegis.agents.AgentOrchestrator.assemble(agentPlan) }
        // RLAIF-E critic protocol (docs/AGENTS_DESIGN.md §evolution): an
        // explicit correction registers a Negative Reward Signal and stages a
        // knowledge update behind the gate ("Based on your correction earlier…").
        if (correctionMarkers.any { lower.contains(it) }) {
            val target = agentPlan.steps.firstOrNull()?.dominant?.agentId ?: "assistant"
            runCatching { com.newax.aegis.agents.LearningEngine.ingestUserFeedback(target, "", text, negative = true) }
        }
        if (!engine.canHandle(text) && !text.contains(Regex("""\s+then\s+""", RegexOption.IGNORE_CASE))) {
            val provider = modelProvider
            if (provider == null || provider.state.value != ModelState.READY) {
                vm.appendChat(strings.resolve(R.string.chat_no_verified_model), false)
                return
            }
            // Agent runtime (docs/AGENTS_DESIGN.md §runtime): the dominant
            // agent of step 1 runs this request through the standard controller
            // — run() → live phases → strict result/error block in the ledger.
            val dominantAgent = agentPlan.steps.firstOrNull()?.dominant
            // RLAIF-E (docs/AGENTS_DESIGN.md §evolution, skill.sys.self_learn):
            // pick the method variant this run exploits/explores — the ledger
            // seeds a baseline per agent pseudo-skill; exploration trades
            // confidence for variety; the outcome feeds back below.
            val learnKey = dominantAgent?.let { "agent:${it.agentId}" }
            val learnStartedAt = System.currentTimeMillis()
            val learnMethod = learnKey?.let { k ->
                runCatching { com.newax.aegis.agents.LearningEngine.chooseMethod(k) }.getOrNull()
            }
            val sessionId = dominantAgent?.let { agent ->
                val ctx = com.newax.aegis.agents.AgentContext(
                    taskPrompt = text.take(2000),
                    planSummary = agentPlan.steps.joinToString("; ") { s ->
                        (s.dominant?.name ?: "assistant") + " → " + s.text.take(80)
                    }.take(500),
                    memoryPointers = listOf("library", "episodes", "handoffs"),
                    skills = runCatching { com.newax.aegis.agents.SkillManager.skillsForAgent(agent.agentId).map { it.skillId } }
                        .getOrDefault(emptyList())
                )
                com.newax.aegis.agents.AgentRuntimeEngine.start(agent.agentId, text, ctx.toJson())
            }
            vm.modelBusy = true
            vm.streamingActive = true
            vm.streamingText = ""
            _activeAgentSessionId.value = sessionId
            activeGenerationJob = vm.launch {
                // One outcome per run — the reinforcement step of RLAIF-E.
                fun recordLearnOutcome(success: Boolean, error: String = "") {
                    val key = learnKey ?: return
                    val method = learnMethod ?: return
                    runCatching {
                        com.newax.aegis.agents.LearningEngine.recordExecution(
                            key, method.methodId, success,
                            System.currentTimeMillis() - learnStartedAt, error
                        )
                    }
                }
                try {
                    // ── Deterministic retrieval before LLM ────────────────────
                    val plan   = QueryPlanner.plan(text)
                    val merged = withContext(Dispatchers.IO) {
                        val resolved = DeterministicResolver.resolve(plan, vm.db, vm.memory, app)
                        CandidateMerger.merge(resolved, plan)
                    }
                    if (!merged.requiresLlm && merged.topFacts.isNotEmpty()) {
                        vm.streamingActive = false
                        vm.streamingText = ""
                        sessionId?.let { com.newax.aegis.agents.AgentRuntimeEngine.complete(it, merged.summary) }
                        recordLearnOutcome(success = true)
                        vm.appendChat(merged.summary, false)
                        vm.modelBusy = false
                        return@launch
                    }
                    // ─────────────────────────────────────────────────────────
                    val resultDeferred = CompletableDeferred<String>()
                    val llmJob = NewaxJob(
                        id            = ResourceGovernor.newId(),
                        label         = "llm-inference",
                        resourceClass = ResourceClass.CRITICAL,
                        priority      = JobPriority.P0_USER_VISIBLE,
                        ramBudgetMb   = 512,
                        cancellable   = true
                    ) {
                        val screen = NewaxAccessibilityService.instance?.screenSummary().orEmpty().take(2000)
                        val ocrText = com.newax.aegis.vision.ScreenCaptureService.latestOcrResult.value
                            ?.let { com.newax.aegis.vision.OcrEngine.formatForContext(it) }
                            .orEmpty().take(1000)
                        val unread = com.newax.aegis.accessibility.NewaxNotificationListenerService.getInboxSummary()
                        val conversationHistory = vm.messages
                            .takeLast(10)
                            .filter { line -> systemLinePrefixes.none { line.text.startsWith(it) } }
                            .joinToString("\n") { if (it.fromUser) "User: ${it.text}" else "Assistant: ${it.text}" }
                        val prompt = buildString {
                            val agentContext = com.newax.aegis.agents.AgentOrchestrator.contextFor(agentPlan)
                            if (agentContext.isNotBlank()) append("$agentContext\n")
                            // RLAIF-E: the selected method variant's guidance rides
                            // into the prompt — exploitation runs the best-known
                            // configuration, exploration tests a variation, and
                            // the outcome updates that method's confidence.
                            learnMethod?.payloadJson?.takeIf { it.isNotBlank() && it != "{}" }?.let { payload ->
                                val guidance = runCatching { org.json.JSONObject(payload).optString("method_guidance") }.getOrDefault("")
                                if (guidance.isNotBlank()) {
                                    append("[Execution method ${learnMethod.methodId} — self-learned variant]: $guidance\n")
                                }
                            }
                            // Function-calling readiness (docs/AGENTS_DESIGN.md §runtime):
                            // the active agent's permitted tool schemas ride in the
                            // prompt so the model can invoke them with exact parameters;
                            // a cloud model with true function calling binds the same
                            // schemas from SkillManager.toolSchemasForAgent.
                            val agentSchemas = dominantAgent?.let { a ->
                                runCatching { com.newax.aegis.agents.SkillManager.toolSchemasForAgent(a.agentId) }.getOrDefault(emptyList())
                            } ?: emptyList()
                            if (agentSchemas.isNotEmpty()) {
                                append("Available tools for the active agent (JSON function schemas — call them with the exact parameters):\n")
                                agentSchemas.take(3).forEach { append(it.take(600)); append('\n') }
                            }
                            val profile = vm.memory.getAllCategories().entries
                                .filter { it.value.isNotEmpty() }
                                .joinToString("\n") { "${it.key.uppercase()}:\n- " + it.value.joinToString("\n- ") }
                            if (profile.isNotBlank()) append("User Profile:\n$profile\n\n")
                            if (merged.llmContext.isNotBlank()) append("${merged.llmContext}\n\n")
                            if (unread != "Your inbox is clear.") append("Unread Notifications:\n$unread\n\n")
                            if (screen.isNotBlank()) append("Current screen:\n$screen\n\n")
                            if (ocrText.isNotBlank()) append("Screen OCR:\n$ocrText\n\n")
                            if (conversationHistory.isNotBlank()) append("Recent conversation:\n$conversationHistory\n\n")
                            append("If you suggest replying to a notification, output EXACTLY on the first line: reply notification <key> ::: <reply_text>\n\n")
                            append("User: ${text.trim().take(3000)}")
                        }.take(7000)
                        val frame = com.newax.aegis.vision.ScreenCaptureService.latestFrame.value
                        val imageBytes = frame?.let { bmp ->
                            ByteArrayOutputStream().use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                                out.toByteArray()
                            }
                        }
                        sessionId?.let { com.newax.aegis.agents.AgentRuntimeEngine.phase(it, com.newax.aegis.db.entity.SessionPhase.THINKING) }
                        // T3.0c — collect the stream directly. `complete()` IS the
                        // collected stream (T2.5), so this is the same single
                        // inference path, rendered incrementally: each emitted
                        // chunk grows the streaming bubble. Cancelling this
                        // collection (Stop) stops the UI updating; the engine
                        // call itself cannot be interrupted, so the model keeps
                        // burning tokens until it returns — generation is
                        // ABANDONED, not aborted (ModelProvider.cancel() is a
                        // documented no-op on both real providers).
                        val request = ModelRequest(
                            text          = prompt,
                            imageBytes    = imageBytes,
                            imageMimeType = if (imageBytes != null) "image/png" else null
                        )
                        val sb = StringBuilder()
                        provider.stream(request).collect { chunk ->
                            sb.append(chunk)
                            if (vm.streamingActive) vm.streamingText = sb.toString()
                        }
                        resultDeferred.complete(sb.toString())
                    }
                    ResourceGovernor.preemptForUser(llmJob)
                    val replyText = resultDeferred.await()
                    // The stream completed — the bubble is done; the reply is
                    // appended (and persisted) below.
                    vm.streamingActive = false
                    vm.streamingText = ""

                    // Abort check (docs/AGENTS_DESIGN.md §runtime): if the user hit
                    // Cancel mid-inference (Agents screen), the run ledger is ABORTED
                    // — the late result is discarded, never surfaced as a reply.
                    if (sessionId != null &&
                        com.newax.aegis.agents.AgentRuntimeEngine.status(sessionId)?.status == com.newax.aegis.db.entity.SessionStatus.ABORTED
                    ) {
                        vm.appendChat(strings.resolve(R.string.chat_task_aborted), false)
                        return@launch
                    }

                    val screen = NewaxAccessibilityService.instance?.screenSummary().orEmpty()
                    val firstLine = replyText.trim().lineSequence().firstOrNull()?.trim().orEmpty()
                    if (firstLine.isNotBlank() && engine.canHandle(firstLine)) {
                        val commandReply = engine.generateReply(firstLine, screen, VectorMemorySearch.search(vm.db, vm.memory, firstLine))
                        val explanation = replyText.trim().removePrefix(firstLine).trim()
                        vm.appendChat(if (explanation.isNotBlank()) explanation else commandReply.text, false)
                        commandReply.proposedAction?.let { processAction(it) }
                        sessionId?.let { com.newax.aegis.agents.AgentRuntimeEngine.complete(it, explanation.ifBlank { commandReply.text }) }
                        recordLearnOutcome(success = true)
                    } else {
                        vm.appendChat(replyText, false)
                        sessionId?.let { com.newax.aegis.agents.AgentRuntimeEngine.complete(it, replyText) }
                        recordLearnOutcome(success = true)
                    }
                    vm.speakLive(replyText, isBackground && text.contains("[Live Call]"))
                } catch (e: CancellationException) {
                    // User hit Stop (or the ViewModel was torn down). The stream
                    // was cut mid-collection: the partial reply is discarded and
                    // never persisted. This is ABANDONED, not ABORTED — the
                    // engine call itself cannot be interrupted; it finishes
                    // burning tokens in the background.
                    vm.streamingActive = false
                    vm.streamingText = ""
                    sessionId?.let { runCatching { com.newax.aegis.agents.AgentRuntimeEngine.abortSession(it) } }
                    recordLearnOutcome(success = false, error = "user_abandoned")
                    vm.appendChat(strings.resolve(R.string.chat_generation_abandoned), false)
                } catch (error: Throwable) {
                    vm.streamingActive = false
                    vm.streamingText = ""
                    sessionId?.let {
                        com.newax.aegis.agents.AgentRuntimeEngine.fail(
                            it,
                            com.newax.aegis.db.entity.AgentErrorType.MODEL_ERROR,
                            error.message ?: error.javaClass.simpleName
                        )
                    }
                    recordLearnOutcome(success = false, error = error.message ?: error.javaClass.simpleName)
                    vm.appendChat(strings.resolve(R.string.chat_model_error, error.message ?: error.javaClass.simpleName), false)
                } finally { vm.modelBusy = false; activeGenerationJob = null; _activeAgentSessionId.value = null }
            }
            return
        }
        val screen = NewaxAccessibilityService.instance?.screenSummary().orEmpty()
        val parts = text.split(Regex("""\s+then\s+""", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
        val replies = parts.mapIndexed { index, part ->
            // Per-step agent dominance (docs/AGENTS_DESIGN.md): each step's
            // dominant agent context rides into the engine as a memory line.
            val stepAgent = agentPlan.steps.getOrNull(index)?.dominant?.let {
                listOf("[Agent: ${it.name} (${it.category})] ${it.description}")
            } ?: emptyList()
            engine.generateReply(
                part, screen,
                if (lower in setOf("what do you remember", "show memory", "recall")) vm.memory.getAllCategories().values.flatten() else VectorMemorySearch.search(vm.db, vm.memory, part) + stepAgent
            )
        }
        vm.appendChat(replies.joinToString("\n") { it.text }, false)

        val actions = replies.mapNotNull { it.proposedAction }
        val origin = if (isBackground) ActionOrigin.BACKGROUND else ActionOrigin.USER
        fun autoAllowed(a: ProposedAction): Boolean {
            val t = AutomationSettings.toggleForAction(a)
            return mayAutoExecute(a, origin, t != null && AutomationSettings.isEnabled(t))
        }
        val needsApproval = actions.filterNot { autoAllowed(it) }
        val autoActions = actions.filter { autoAllowed(it) }
        autoActions.forEach { processAction(it, origin) }
        if (isBackground && needsApproval.any { riskOf(it) >= RiskLevel.HIGH }) {
            vm.appendChat(
                strings.resolve(R.string.chat_plan_summary_background, needsApproval.count { riskOf(it) >= RiskLevel.HIGH }),
                false
            )
        }
        if (needsApproval.isNotEmpty()) {
            if (vm.pendingAction == null) {
                queuedActions.clear()
                queuedActions.addAll(needsApproval.drop(1))
                vm.pendingAction = needsApproval.firstOrNull()
            } else {
                queuedActions.addAll(needsApproval)
            }
        }
        val approvalCount = needsApproval.size
        val autoCount = autoActions.size
        if (approvalCount > 1) vm.appendChat(strings.resolve(R.string.chat_plan_summary, approvalCount, autoCount), false)
        else if (autoCount > 0 && approvalCount == 0) vm.appendChat(strings.resolve(R.string.chat_all_auto, autoCount), false)
    }

    /**
     * Route an action through the authority spine (ARCHITECTURE.md rule 3).
     *
     * The PolicyEngine resolves the user's policy mode for the action class
     * (override or risk default), applies the decision table (AUTO_EXECUTE /
     * REQUIRE_APPROVAL / REQUIRE_STRONG / DENY), and audits the evaluation;
     * [AuthorityManager.apply] maps the decision onto the same approval UI flow
     * (Approved / RequestApproval / RequestBiometric / Rejected). The PersonPolicy
     * gate stays: send actions always require approval, enforced before the engine.
     */
    internal fun processAction(action: ProposedAction, origin: ActionOrigin = ActionOrigin.USER) {
        // PersonPolicy gate: send actions always require approval (policy-enforced)
        if (action is ProposedAction.Send || action is ProposedAction.SendImage) {
            enqueueForApproval(action)
            return
        }
        vm.authorityManager.apply(PolicyHolder.engine().evaluate(action, origin))
    }

    internal fun enqueueForApproval(action: ProposedAction) {
        if (vm.pendingAction == null) vm.pendingAction = action
        else queuedActions.addLast(action)
    }

    fun approve() {
        val action = vm.pendingAction ?: return
        // Derived from riskOf() rather than a parallel hand-maintained list, so a new
        // destructive action can't be added without inheriting the auth requirement.
        if (requiresBiometric(action)) {
            vm.biometricAuthRequested = true
            vm.appendChat(strings.resolve(R.string.chat_awaiting_biometric), false)
            return
        }
        executeApprovedAction()
    }

    private fun needsGhostMode(action: ProposedAction): Boolean = when (action) {
        is ProposedAction.Tap, is ProposedAction.TapPixels, is ProposedAction.Type,
        is ProposedAction.Send, is ProposedAction.SendImage, is ProposedAction.Scroll,
        is ProposedAction.OpenApp, is ProposedAction.PostSocialMedia,
        ProposedAction.ToggleConnectivity, ProposedAction.Home,
        ProposedAction.Recents, ProposedAction.Back -> true
        else -> false
    }

    fun executeApprovedAction() {
        val action = vm.pendingAction ?: return
        val ghostModeActive = needsGhostMode(action)
        val ghostIntent = android.content.Intent(app, com.newax.aegis.accessibility.GhostModeService::class.java)
        if (ghostModeActive) app.startService(ghostIntent)

        vm.launch {
            val ok = withContext(Dispatchers.IO) { runAction(action) }
            if (ghostModeActive) app.stopService(ghostIntent)
            vm.appendChat(strings.resolve(if (ok) R.string.chat_action_completed else R.string.chat_action_failed), false)
            // RLAIF-E feedback: the user's approve/deny of an executed action is
            // a reward signal for the assistant (no memory rule staged).
            runCatching {
                com.newax.aegis.agents.LearningEngine.ingestUserFeedback(
                    "assistant", "",
                    if (ok) "Approved action completed successfully" else "Approved action failed to execute",
                    negative = !ok, stageMemoryRule = false
                )
            }
            vm.pendingAction = if (ok) queuedActions.removeFirstOrNull() else null
            if (!ok) queuedActions.clear()
            vm.biometricAuthRequested = false
        }
    }

    internal suspend fun runAction(action: ProposedAction): Boolean = when (action) {
        is ProposedAction.UpdateMemory -> {
            vm.memory.remember(action.category, action.info)
            vm.bumpMemoryVersion()
            true
        }
        is ProposedAction.QueryCalendar -> {
            queryCalendar(action.timeframe); true
        }
        is ProposedAction.CreateEvent -> {
            createCalendarEvent(action.title, action.time); true
        }
        is ProposedAction.ReplyNotification -> {
            val success = com.newax.aegis.accessibility.NewaxNotificationListenerService.replyToNotification(app, action.key, action.text)
            withContext(Dispatchers.Main) {
                if (success) vm.appendChat(strings.resolve(R.string.chat_replied_notification), false)
                else vm.appendChat(strings.resolve(R.string.chat_reply_failed), false)
            }
            success
        }
        is ProposedAction.DeleteFile -> {
            val file = java.io.File(action.path)
            file.exists() && file.delete()
        }
        is ProposedAction.DeleteContact -> {
            try {
                val uri = android.provider.ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                    .appendQueryParameter(android.provider.ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
                app.contentResolver.delete(
                    uri, "${android.provider.ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(action.id)
                ) > 0
            } catch (_: Exception) { false }
        }
        is ProposedAction.TakeScreenshot -> {
            val frame = com.newax.aegis.vision.ScreenCaptureService.latestFrame.value
            if (frame == null) false
            else try {
                val file = java.io.File(
                    app.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
                    "aegis_ss_${System.currentTimeMillis()}.png"
                )
                java.io.FileOutputStream(file).use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                true
            } catch (_: Exception) { false }
        }
        is ProposedAction.RunScript -> {
            val result = com.newax.aegis.engine.CodeSandbox.executeJs(action.code, app)
            com.newax.aegis.engine.TriggerEngine.triggerEvents.tryEmit("[Script Output]\n$result")
            true
        }
        is ProposedAction.AuditSecurity -> {
            withContext(Dispatchers.IO) { com.newax.aegis.engine.SecurityAuditor.auditApps(app) }
            true
        }
        is ProposedAction.UpdateGraph -> {
            com.newax.aegis.engine.KnowledgeGraph.addEdge(action.from, action.relation, action.to)
            vm.memory.storeRaw("knowledge_graph", com.newax.aegis.engine.KnowledgeGraph.serialize())
            withContext(Dispatchers.IO) {
                LlmTripleExtractor.saveEdge(vm.db, action.from, action.relation, action.to)
            }
            vm.bumpMemoryVersion()
            true
        }
        is ProposedAction.UpdateNode -> {
            com.newax.aegis.engine.KnowledgeGraph.updateNodeProperty(action.id, action.key, action.value)
            vm.memory.storeRaw("knowledge_graph", com.newax.aegis.engine.KnowledgeGraph.serialize())
            vm.bumpMemoryVersion()
            true
        }
        is ProposedAction.LogCommunication -> {
            com.newax.aegis.engine.CommunicationLog.logInteraction(action.contact, action.summaryText)
            val now = System.currentTimeMillis()
            PersonRegistry.resolve(vm.db, action.contact)?.let { eid ->
                vm.db.personRegistryDao().touchInteraction(eid, now, now)
            }
            vm.memory.storeRaw("comm_log", com.newax.aegis.engine.CommunicationLog.serialize())
            vm.bumpMemoryVersion()
            true
        }
        is ProposedAction.UpdateProject -> {
            com.newax.aegis.engine.ProjectTracker.updateProject(action.id, action.status, action.notes)
            vm.memory.storeRaw("project_tracker", com.newax.aegis.engine.ProjectTracker.serialize())
            vm.bumpMemoryVersion()
            true
        }
        is ProposedAction.PrefixSearch -> {
            val result = com.newax.aegis.engine.SemanticSearchEngine.instantPrefixSearch(action.prefix)
            withContext(Dispatchers.Main) { vm.appendChat(result, false) }
            true
        }
        is ProposedAction.SearchAll -> {
            val result = withContext(Dispatchers.IO) {
                com.newax.aegis.engine.SemanticSearchEngine.searchAll(action.query)
            }
            withContext(Dispatchers.Main) { vm.appendChat(result, false) }
            true
        }
        is ProposedAction.ForgetFact -> {
            vm.memory.forget(action.category, action.fact)
            vm.bumpMemoryVersion()
            true
        }
        is ProposedAction.DeleteProject -> {
            val deleted = com.newax.aegis.engine.ProjectTracker.deleteProject(action.id)
            vm.memory.storeRaw("project_tracker", com.newax.aegis.engine.ProjectTracker.serialize())
            vm.bumpMemoryVersion()
            deleted
        }
        // ── Self-learning ────────────────────────────────────────────────────
        is ProposedAction.StartLearning -> {
            LearningWorker.schedule(app)
            withContext(Dispatchers.Main) {
                vm.appendChat(strings.resolve(R.string.chat_learning_enabled), false)
                vm.refreshDrafts()
            }
            true
        }
        is ProposedAction.StopLearning -> {
            LearningWorker.cancel(app)
            withContext(Dispatchers.Main) {
                vm.appendChat(strings.resolve(R.string.chat_learning_stopped), false)
            }
            true
        }
        is ProposedAction.ScanNow -> {
            LearningWorker.runOnce(app)
            withContext(Dispatchers.Main) {
                vm.appendChat(strings.resolve(R.string.chat_scan_queued), false)
            }
            true
        }
        is ProposedAction.ShowDrafts -> {
            val drafts = DraftStore.pending(vm.db)
            withContext(Dispatchers.Main) {
                vm.refreshDrafts()
                val text = if (drafts.isEmpty()) {
                    "No pending drafts. Say 'start learning' to begin scanning."
                } else {
                    buildString {
                        appendLine("${drafts.size} pending draft(s) — say 'approve all' or 'reject all', or approve/reject individually:")
                        drafts.take(10).forEachIndexed { i, d ->
                            appendLine("${i + 1}. [${d.category.uppercase()}] ${d.fact}")
                            appendLine("   Source: ${d.source} | Confidence: ${"%.0f".format(d.confidence * 100)}% | ID: ${d.id.take(8)}")
                            if (d.sourceSnippet.isNotBlank()) appendLine("   \"${d.sourceSnippet}\"")
                        }
                        if (drafts.size > 10) appendLine("... and ${drafts.size - 10} more.")
                    }
                }
                vm.appendChat(text, false)
            }
            true
        }
        is ProposedAction.ApproveDraft -> {
            val approved = DraftStore.approveDraft(vm.db, action.id)
            withContext(Dispatchers.Main) {
                if (approved != null) {
                    val result = MemoryConsolidator.processApproval(vm.memory, approved)
                    val msg = when (result.action) {
                        MemoryConsolidator.Action.STORE_NEW -> {
                            vm.memory.remember(approved.category, result.resolvedFact ?: approved.fact)
                            approved.subjectName?.let { PersonFactStore.addFact(vm.db, it, approved) }
                            vm.bumpMemoryVersion()
                            "Saved [${approved.category}]: ${approved.fact.take(70)}"
                        }
                        MemoryConsolidator.Action.SKIP_DUPLICATE -> {
                            "Already in memory — duplicate skipped."
                        }
                        MemoryConsolidator.Action.REPLACE_EXISTING -> {
                            result.conflictingFact?.let { vm.memory.forget(approved.category, it) }
                            vm.memory.remember(approved.category, result.resolvedFact ?: approved.fact)
                            approved.subjectName?.let { PersonFactStore.addFact(vm.db, it, approved) }
                            vm.bumpMemoryVersion()
                            "Memory updated — replaced outdated fact."
                        }
                        MemoryConsolidator.Action.PRESENT_CONFLICT -> {
                            vm.memory.remember(approved.category, result.resolvedFact ?: approved.fact)
                            approved.subjectName?.let { PersonFactStore.addFact(vm.db, it, approved) }
                            vm.bumpMemoryVersion()
                            "Saved — note: similar fact exists: \"${result.conflictingFact?.take(60)}\""
                        }
                    }
                    vm.refreshDrafts()
                    vm.appendChat(msg, false)
                } else {
                    vm.appendChat(strings.resolve(R.string.chat_draft_not_found, action.id.take(8)), false)
                }
            }
            approved != null
        }
        is ProposedAction.RejectDraft -> {
            DraftStore.rejectDraft(vm.db, action.id)
            withContext(Dispatchers.Main) {
                vm.refreshDrafts()
                vm.appendChat(strings.resolve(R.string.chat_draft_rejected), false)
            }
            true
        }
        is ProposedAction.ApproveAllDrafts -> {
            val approved = DraftStore.approveAll(vm.db)
            withContext(Dispatchers.Main) {
                var stored = 0; var skipped = 0; var replaced = 0
                approved.forEach { d ->
                    val result = MemoryConsolidator.processApproval(vm.memory, d)
                    when (result.action) {
                        MemoryConsolidator.Action.STORE_NEW -> {
                            vm.memory.remember(d.category, result.resolvedFact ?: d.fact)
                            d.subjectName?.let { PersonFactStore.addFact(vm.db, it, d) }
                            stored++
                        }
                        MemoryConsolidator.Action.SKIP_DUPLICATE -> skipped++
                        MemoryConsolidator.Action.REPLACE_EXISTING -> {
                            result.conflictingFact?.let { vm.memory.forget(d.category, it) }
                            vm.memory.remember(d.category, result.resolvedFact ?: d.fact)
                            d.subjectName?.let { PersonFactStore.addFact(vm.db, it, d) }
                            replaced++
                        }
                        MemoryConsolidator.Action.PRESENT_CONFLICT -> {
                            vm.memory.remember(d.category, result.resolvedFact ?: d.fact)
                            d.subjectName?.let { PersonFactStore.addFact(vm.db, it, d) }
                            stored++
                        }
                    }
                }
                vm.bumpMemoryVersion()
                vm.refreshDrafts()
                vm.appendChat(
                    strings.resolve(R.string.chat_drafts_approved, approved.size, stored, replaced, skipped),
                    false
                )
            }
            true
        }
        is ProposedAction.RejectAllDrafts -> {
            DraftStore.rejectAll(vm.db)
            withContext(Dispatchers.Main) {
                vm.refreshDrafts()
                vm.appendChat(strings.resolve(R.string.chat_drafts_all_rejected), false)
            }
            true
        }

        // ── Contacts ─────────────────────────────────────────────────────────
        is ProposedAction.AnalyzeContacts -> {
            withContext(Dispatchers.IO) {
                try {
                    val mgr = ContactsManager(app, vm.memory)
                    val report = mgr.scanAndClean(dryRun = false, autoMerge = false)
                    val summary = mgr.formatScanReport(report)
                    withContext(Dispatchers.Main) { vm.appendChat(summary, false) }
                    true
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        vm.appendChat(strings.resolve(R.string.chat_contact_scan_failed, e.message), false)
                    }
                    false
                }
            }
        }
        is ProposedAction.ShowPersonProfile -> {
            withContext(Dispatchers.IO) {
                try {
                    val mgr = ContactsManager(app, vm.memory)
                    val profile = mgr.getPersonProfileByName(action.contactName)
                    val text = if (profile == null) {
                        "No intelligence profile found for '${action.contactName}'. Try 'build profile for ${action.contactName}' first."
                    } else buildString {
                        appendLine("=== ${profile.displayName} ===")
                        appendLine("Relationship: ${profile.relationship.name.replace('_', ' ')}")
                        appendLine("Intimacy: ${"%.0f".format(profile.intimacyScore * 100)}% | Trust: ${"%.0f".format(profile.trustScore * 100)}%")
                        appendLine("Frequency: ${profile.communicationFrequency}")
                        appendLine("Languages: ${profile.languagesDetected.joinToString(", ")}")
                        appendLine("Traits: ${profile.personalityTraits.joinToString(", ")}")
                        appendLine("Topics: ${profile.topicKeywords.take(5).joinToString(", ")}")
                        appendLine("Avg response time: ${"%.1f".format(profile.avgResponseGapHours)}h")
                        if (profile.dominantIntent.isNotBlank()) appendLine("Dominant intent: ${profile.dominantIntent}")
                        if (profile.aiSummary.isNotBlank()) appendLine("\n${profile.aiSummary}")
                    }
                    withContext(Dispatchers.Main) { vm.appendChat(text, false) }
                    true
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        vm.appendChat(strings.resolve(R.string.chat_profile_load_failed, e.message), false)
                    }
                    false
                }
            }
        }
        is ProposedAction.BuildPersonProfile -> {
            withContext(Dispatchers.IO) {
                try {
                    val mgr = ContactsManager(app, vm.memory)
                    val profile = mgr.getPersonProfileByName(action.contactName)?.let {
                        // Contact exists — rebuild profile
                        val contacts = mgr.loadAllContacts()
                        val match = contacts.firstOrNull { c ->
                            c.displayName.equals(action.contactName, ignoreCase = true)
                        }
                        match?.let { mgr.buildPersonProfile(it.contactId) }
                    } ?: run {
                        // Try to find by name and build
                        val contacts = mgr.loadAllContacts()
                        val match = contacts.firstOrNull { c ->
                            c.displayName.contains(action.contactName, ignoreCase = true)
                        }
                        match?.let { mgr.buildPersonProfile(it.contactId) }
                    }
                    val text = if (profile == null) {
                        "Contact '${action.contactName}' not found."
                    } else {
                        "Profile built for ${profile.displayName}. ${profile.totalMessagesIn + profile.totalMessagesOut} messages analyzed. Relationship: ${profile.relationship.name.replace('_', ' ')}. ${profile.personalityTraits.joinToString(", ")}."
                    }
                    withContext(Dispatchers.Main) { vm.appendChat(text, false) }
                    true
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        vm.appendChat(strings.resolve(R.string.chat_profile_build_failed, e.message), false)
                    }
                    false
                }
            }
        }
        is ProposedAction.MergeContacts -> {
            withContext(Dispatchers.IO) {
                try {
                    val mgr = ContactsManager(app, vm.memory)
                    val allContacts = mgr.loadAllContacts()
                    val c1 = allContacts.firstOrNull { it.displayName.equals(action.contact1, ignoreCase = true) }
                    val c2 = allContacts.firstOrNull { it.displayName.equals(action.contact2, ignoreCase = true) }
                    val text = if (c1 == null || c2 == null) {
                        "Could not find both contacts: '${action.contact1}' and '${action.contact2}'."
                    } else {
                        val ok = mgr.mergeContacts(c1.rawContactId, c2.rawContactId)
                        if (ok) "Merged '${c1.displayName}' and '${c2.displayName}' into one contact."
                        else "Merge failed — contacts may be on different accounts."
                    }
                    withContext(Dispatchers.Main) { vm.appendChat(text, false) }
                    true
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        vm.appendChat(strings.resolve(R.string.chat_merge_failed, e.message), false)
                    }
                    false
                }
            }
        }
        is ProposedAction.PostSocialMedia -> {
            try {
                val context = app
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "image/*"
                    setPackage(action.packageTarget)
                    putExtra(android.content.Intent.EXTRA_TEXT, action.caption)
                    if (action.imagePath.isNotBlank() && action.imagePath != "null") {
                        val imageUri = if (action.imagePath.startsWith("content://") || action.imagePath.startsWith("http")) {
                            Uri.parse(action.imagePath)
                        } else {
                            androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", java.io.File(action.imagePath)
                            )
                        }
                        putExtra(android.content.Intent.EXTRA_STREAM, imageUri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) { context.startActivity(intent) }
                val finalizeAction = ProposedAction.Type("FINALIZE_POST|${action.altTag}")
                NewaxAccessibilityService.instance?.execute(finalizeAction)
                true
            } catch (_: Exception) { false }
        }
        else -> withContext(Dispatchers.Main) { NewaxAccessibilityService.instance?.execute(action) == true }
    }

    fun importModel(uri: Uri) {
        if (vm.modelBusy) return
        vm.modelBusy = true
        vm.modelStatus = strings.resolve(R.string.model_importing)
        vm.launch {
            try {
                val imported = modelImporter.import(uri)
                vm.modelStatus = strings.resolve(R.string.model_verified_init, imported.sha256.take(12))
                loadModel(imported)
            } catch (error: Throwable) {
                vm.modelStatus = strings.resolve(R.string.model_import_failed, error.message ?: error.javaClass.simpleName)
                vm.modelBusy = false
            }
        }
    }

    /** Reloads the last imported model at boot (was inline in the ViewModel init). */
    internal fun restoreModelIfPresent() {
        modelImporter.current()?.let { imported ->
            vm.modelStatus = strings.resolve(R.string.model_loading, imported.file.name)
            loadModel(imported)
        }
    }

    private fun loadModel(imported: ImportedModel) {
        vm.launch {
            vm.modelBusy = true
            val previous = modelProvider
            modelProvider = null
            ModelProviderHolder.clear()
            LlmFactExtractor.bind(null)
            LlmTripleExtractor.bind(null)
            var provider: LiteRtModelProvider? = null
            try {
                previous?.close()
                provider = LiteRtModelProvider(app, imported.file, imported.sha256)
                provider.load()
                modelProvider = provider
                ModelProviderHolder.set(provider)
                LlmFactExtractor.bind(provider)
                LlmTripleExtractor.bind(provider)
                vm.modelStatus = strings.resolve(R.string.model_ready, imported.file.name)
            } catch (error: Throwable) {
                provider?.close()
                vm.modelStatus = strings.resolve(R.string.model_unavailable, error.message ?: error.javaClass.simpleName)
            } finally { vm.modelBusy = false }
        }
    }

    /** The last imported model's identity (model sheet 1.4) — file, SHA-256, size. */
    fun currentModel(): ImportedModel? = modelImporter.current()

    /**
     * Unloads the running model, returning to basic mode (model sheet 1.4 —
     * Unload). Mirrors [close]'s model half: the imported file stays on disk,
     * so [reloadModel] can bring it back. The status line names the state
     * honestly ("Model unloaded") rather than claiming no model is installed.
     */
    fun unloadModel() {
        activeGenerationJob?.cancel()
        vm.streamingActive = false
        vm.streamingText = ""
        modelProvider?.close()
        modelProvider = null
        ModelProviderHolder.clear()
        LlmFactExtractor.bind(null)
        LlmTripleExtractor.bind(null)
        vm.modelStatus = strings.resolve(R.string.model_unloaded)
        vm.modelBusy = false
    }

    /** Reloads the last imported model (model sheet 1.4 — Reload). */
    fun reloadModel() = restoreModelIfPresent()

    /**
     * Stop button (T3.0c). Cancels the collecting coroutine: the streaming
     * bubble disappears immediately and the handler appends the honest
     * "abandoned" line. The inference call itself keeps running — it cannot be
     * interrupted — so generation is ABANDONED, never ABORTED.
     */
    internal fun stopGeneration() {
        if (!vm.streamingActive) return
        vm.streamingActive = false
        vm.streamingText = ""
        activeGenerationJob?.cancel()
    }

    fun reject() {
        vm.pendingAction = null
        queuedActions.clear()
        // RLAIF-E: a rejected action plan is a negative reward signal.
        runCatching {
            com.newax.aegis.agents.LearningEngine.ingestUserFeedback(
                "assistant", "", "User rejected the proposed action plan", negative = true, stageMemoryRule = false
            )
        }
        vm.appendChat(strings.resolve(R.string.chat_action_cancelled), false)
    }

    /** ViewModel teardown: cancels generation and unloads the model. */
    internal fun close() {
        activeGenerationJob?.cancel()
        modelProvider?.close()
        ModelProviderHolder.clear()
    }

    private suspend fun queryCalendar(timeframe: String) {
        val context = app
        val now = Calendar.getInstance().timeInMillis
        val endRange = when {
            timeframe.contains("tomorrow", true) -> now + 86400000L * 2
            timeframe.contains("week", true)     -> now + 86400000L * 7
            timeframe.contains("month", true)    -> now + 86400000L * 30
            else                                 -> now + 86400000L
        }
        val events = withContext(Dispatchers.IO) {
            com.newax.aegis.engine.CalendarQueries.query(context, now, endRange, 10)
        }
        vm.appendChat(
            if (events.isEmpty()) strings.resolve(R.string.chat_no_events, timeframe)
            else "Found: " + events.joinToString(" | ") { it.formatted() },
            false
        )
    }

    private suspend fun createCalendarEvent(title: String, timeString: String) {
        val context = app
        try {
            val calendarId = withContext(Dispatchers.IO) { resolveCalendarId(context) }
            val targetTime = parseEventTime(timeString)
            withContext(Dispatchers.IO) {
                context.contentResolver.insert(
                    CalendarContract.Events.CONTENT_URI,
                    ContentValues().apply {
                        put(CalendarContract.Events.DTSTART, targetTime)
                        put(CalendarContract.Events.DTEND, targetTime + 3600000L)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.CALENDAR_ID, calendarId)
                        put(CalendarContract.Events.EVENT_TIMEZONE, Calendar.getInstance().timeZone.id)
                    }
                )
            }
            vm.appendChat(strings.resolve(R.string.chat_event_created, title, timeString), false)
        } catch (e: Exception) {
            vm.appendChat(strings.resolve(R.string.chat_event_failed), false)
        }
    }

    private fun resolveCalendarId(context: android.content.Context): Long {
        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC"
            )
            cursor?.use { if (it.moveToFirst()) it.getLong(0) else 1L } ?: 1L
        } catch (_: Exception) { 1L }
    }

    private fun parseEventTime(timeString: String): Long {
        var target = Calendar.getInstance().timeInMillis
        val lower = timeString.lowercase()
        when {
            lower.contains("tomorrow")                -> target += 86400000L
            lower.contains("hour")  -> {
                val h = Regex("""\d+""").find(lower)?.value?.toLongOrNull() ?: 1L
                target += h * 3600000L
            }
            lower.contains("minute") -> {
                val m = Regex("""\d+""").find(lower)?.value?.toLongOrNull() ?: 30L
                target += m * 60000L
            }
            else -> target += 3600000L
        }
        return target
    }
}
