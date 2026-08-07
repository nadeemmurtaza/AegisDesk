package com.newax.aegis

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.net.Uri
import android.content.ContentValues
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.speech.tts.TextToSpeech
import java.util.Locale
import com.newax.aegis.accessibility.AegisAccessibilityService
import com.newax.aegis.assistant.*
import com.newax.aegis.engine.AutomationSettings
import com.newax.aegis.engine.ContactsManager
import com.newax.aegis.engine.TotpManager
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.embedding.VectorMemorySearch
import com.newax.aegis.engine.learning.DraftStore
import com.newax.aegis.engine.learning.LlmFactExtractor
import com.newax.aegis.engine.learning.LlmTripleExtractor
import com.newax.aegis.engine.learning.LearningDraft
import com.newax.aegis.engine.learning.LearningWorker
import com.newax.aegis.engine.learning.MemoryConsolidator
import com.newax.aegis.engine.learning.PersonFactStore
import com.newax.aegis.engine.learning.ScanProgress
import com.newax.aegis.engine.apps.AppCapability
import com.newax.aegis.engine.apps.AppIntelligence
import com.newax.aegis.engine.apps.AppScanner
import com.newax.aegis.engine.files.FileIntelligence
import com.newax.aegis.engine.person.PersonRegistry
import com.newax.aegis.engine.planner.CandidateMerger
import com.newax.aegis.engine.procedure.ProcedureExecutor
import com.newax.aegis.engine.procedure.StepSerializer
import com.newax.aegis.engine.planner.DeterministicResolver
import com.newax.aegis.engine.planner.QueryPlanner
import com.newax.aegis.engine.resource.AegisJob
import com.newax.aegis.engine.resource.JobPriority
import com.newax.aegis.engine.resource.OpportunisticScheduler
import com.newax.aegis.engine.resource.ResourceClass
import com.newax.aegis.engine.resource.ResourceGovernor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.newax.aegis.memory.EncryptedMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = LocalAssistantEngine()
    val memory = EncryptedMemory(application)
    val db = AegisDatabase.get
    private val modelImporter = ModelImporter(application)
    private var offlineModel: LiteRtOfflineModel? = null

    private val memoryCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            offlineModel?.onMemoryPressure(level)
            ResourceGovernor.onMemoryPressure(pressureFromTrim(level))
        }
        override fun onLowMemory() {
            offlineModel?.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            ResourceGovernor.onMemoryPressure(5)
        }
        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    private fun pressureFromTrim(level: Int): Int = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE        -> 5
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL-> 4
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW     -> 3
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN       -> 2
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND      -> 1
        else                                                      -> 0
    }

    val messages = mutableStateListOf(ChatMessage("Aegis is ready in offline basic mode.", false))
    var pendingAction by mutableStateOf<ProposedAction?>(null); private set
    var biometricAuthRequested by mutableStateOf(false)
    private val queuedActions = ArrayDeque<ProposedAction>()
    var modelStatus by mutableStateOf("No model installed"); private set
    var modelBusy by mutableStateOf(false); private set
    var memoryVersion by mutableIntStateOf(0); private set
    var automationVersion by mutableIntStateOf(0); private set
    private var tts: TextToSpeech? = null

    private val _pendingDrafts = MutableStateFlow<List<LearningDraft>>(emptyList())
    val pendingDrafts: StateFlow<List<LearningDraft>> = _pendingDrafts.asStateFlow()

    fun refreshDrafts() {
        _pendingDrafts.value = DraftStore.pending(db)
    }

    init {
        application.registerComponentCallbacks(memoryCallback)
        AutomationSettings.init(application)
        TotpManager.init(application)
        ScanProgress.init(application)
        refreshDrafts()
        // Start new TriggerEngine (DB-backed, replaces old text-rule engine)
        com.newax.aegis.engine.trigger.TriggerEngine.start(application, db) { rule, ctx ->
            // NOTIFY_USER action: post an Android notification
            if (rule.actionType == com.newax.aegis.db.entity.TriggerRule.ACTION_NOTIFY_USER) {
                val nm = application.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                val channelId = "aegis_triggers"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    nm?.createNotificationChannel(android.app.NotificationChannel(channelId, "Aegis Triggers", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                }
                val n = androidx.core.app.NotificationCompat.Builder(application, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(rule.label)
                    .setContentText(ctx["text"] ?: "")
                    .setAutoCancel(true).build()
                nm?.notify(rule.id.toInt(), n)
            }
        }
        viewModelScope.launch {
            // Single collection point — old TriggerEngine.triggerEvents delegates here
            com.newax.aegis.engine.trigger.TriggerEngine.triggerEvents.collect { systemPrompt ->
                messages += ChatMessage("Processing background event…", true)
                submit(systemPrompt, isBackground = true)
            }
        }
        modelImporter.current()?.let { file ->
            modelStatus = "Loading ${file.name}…"
            loadModel(file)
        }
        memory.getRaw("knowledge_graph")?.let { com.newax.aegis.engine.KnowledgeGraph.load(it) }
        memory.getRaw("comm_log")?.let { com.newax.aegis.engine.CommunicationLog.load(it) }
        memory.getRaw("project_tracker")?.let { com.newax.aegis.engine.ProjectTracker.load(it) }
        com.newax.aegis.engine.MemoryIndexer.reindexAll()
        com.newax.aegis.engine.CommunicationLog.onInteraction = { contact, ts ->
            PersonRegistry.resolve(db, contact)?.let { eid ->
                db.personRegistryDao().touchInteraction(eid, ts, ts)
            }
        }
        OpportunisticScheduler.register {
            // Refresh PersonSnapshot commitment counts for all known persons
            db.personRegistryDao().hotPersons(50).forEach { snap ->
                PersonRegistry.refreshSnapshotCommitmentCount(db, snap.personEntityId)
            }
        }
        OpportunisticScheduler.start(application)
        ResourceGovernor.fire("app-scan", ResourceClass.LIGHT, JobPriority.P3_INDEXING) {
            AppScanner.scan(application, db)
            AppScanner.seedGraphTriples(db)
        }
        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("ur", "PK")
        }
    }

    fun bumpMemoryVersion() { memoryVersion++ }
    fun bumpAutomationVersion() { automationVersion++ }

    /**
     * Route an action: if its automation toggle is ON, fire immediately without
     * showing the approval card. Otherwise queue it for manual approval.
     */
    private fun processAction(action: ProposedAction) {
        // PersonPolicy gate: send actions always require approval (policy-enforced)
        if (action is ProposedAction.Send || action is ProposedAction.SendImage) {
            if (pendingAction == null) pendingAction = action
            else queuedActions.addLast(action)
            return
        }
        val toggle = AutomationSettings.toggleForAction(action)
        if (toggle != null && AutomationSettings.isEnabled(toggle)) {
            viewModelScope.launch {
                val ok = withContext(Dispatchers.IO) { runAction(action) }
                if (!ok) messages += ChatMessage("Auto-action failed: ${action.summary}", false)
            }
        } else {
            if (pendingAction == null) pendingAction = action
            else queuedActions.addLast(action)
        }
    }

    fun importModel(uri: Uri) {
        if (modelBusy) return
        modelBusy = true
        modelStatus = "Importing and verifying model…"
        viewModelScope.launch {
            try {
                val imported = modelImporter.import(uri)
                modelStatus = "Verified ${imported.sha256.take(12)}…; initializing…"
                loadModel(imported.file)
            } catch (error: Throwable) {
                modelStatus = "Import failed: ${error.message ?: error.javaClass.simpleName}"
                modelBusy = false
            }
        }
    }

    private fun loadModel(file: java.io.File) {
        viewModelScope.launch {
            modelBusy = true
            try {
                offlineModel?.close()
                val model = LiteRtOfflineModel(getApplication(), file)
                model.initialize()
                offlineModel = model
                LlmFactExtractor.bind(model)
                LlmTripleExtractor.bind(model)
                modelStatus = "Offline AI ready • ${file.name}"
            } catch (error: Throwable) {
                offlineModel?.close(); offlineModel = null
                LlmFactExtractor.bind(null)
                LlmTripleExtractor.bind(null)
                modelStatus = "Model unavailable: ${error.message ?: error.javaClass.simpleName}"
            } finally { modelBusy = false }
        }
    }

    fun submit(text: String, isBackground: Boolean = false) {
        if (text.isBlank()) return
        if (!isBackground) messages += ChatMessage(text.trim(), true)
        val lower = text.trim().lowercase()
        if (lower.startsWith("remember that ")) {
            val fact = text.trim().substringAfter(" ").substringAfter(" ").trim()
            memory.remember("personal", fact)
            messages += ChatMessage("Saved privately on this device: $fact", false)
            return
        }
        if (lower == "forget everything" || lower == "clear memory") {
            memory.forgetAll()
            bumpMemoryVersion()
            messages += ChatMessage("All saved personal facts were deleted.", false)
            return
        }
        // ── Commitment/person fast path ───────────────────────────────────────
        val commitmentTriggers = listOf("what am i waiting for", "what do i owe", "overdue", "pending commitment", "commitments from", "commitments to")
        if (commitmentTriggers.any { lower.contains(it) }) {
            viewModelScope.launch {
                val reply = withContext(Dispatchers.IO) {
                    val overdue = PersonRegistry.overdueCommitments(db)
                    if (overdue.isNotEmpty()) {
                        "Overdue commitments:\n" + overdue.take(5).joinToString("\n") { "• ${it.action} (${it.debtorLabel} → ${it.creditorLabel})" }
                    } else {
                        val userOwes = db.personRegistryDao().userCommitments(5)
                        if (userOwes.isNotEmpty())
                            "Your pending commitments:\n" + userOwes.joinToString("\n") { "• ${it.action} → ${it.creditorLabel}" }
                        else "No pending commitments found."
                    }
                }
                messages += ChatMessage(reply, false)
            }
            return
        }
        // ── File search fast path ─────────────────────────────────────────────
        val fileTriggers = listOf("find file", "find document", "find the file", "find the doc", "recent files", "recent documents", "show files", "list files", "open file", "share file")
        if (fileTriggers.any { lower.contains(it) }) {
            viewModelScope.launch {
                val reply = withContext(Dispatchers.IO) {
                    val query = lower
                        .replace(Regex("find (the )?file|find (the )?doc(ument)?|recent files|recent documents|show files|list files|open file|share file"), "")
                        .trim()
                    val files = if (query.isBlank()) FileIntelligence.recentFiles(getApplication(), 10)
                                else FileIntelligence.findBest(getApplication(), query, 8)
                    if (files.isEmpty()) "No files found for \"$query\"."
                    else "Found ${files.size} file(s):\n${FileIntelligence.describeResults(files)}"
                }
                messages += ChatMessage(reply, false)
            }
            return
        }
        // ── Send file fast path: "send [file query] to [person]" ─────────────
        val sendFileRegex = Regex("""send (.+?) to (.+)""", RegexOption.IGNORE_CASE)
        val sendFileMatch = sendFileRegex.find(lower)
        if (sendFileMatch != null) {
            val fileQuery  = sendFileMatch.groupValues[1].trim()
            val personName = sendFileMatch.groupValues[2].trim()
            viewModelScope.launch {
                val reply = withContext(Dispatchers.IO) {
                    val files = FileIntelligence.findBest(getApplication(), fileQuery, 3)
                    if (files.isEmpty()) return@withContext "No file found matching \"$fileQuery\"."
                    val file = files.first()
                    val task = PersonRegistry.resolveTask(db, getApplication(), personName, AppCapability.SEND_FILE)
                    if (task == null) return@withContext "Don't know who \"$personName\" is."
                    val pol = task.policy
                    if (pol.canShareFiles == 0) return@withContext "Blocked by policy: sharing files with ${task.personName} is disabled."
                    val sendIntent = FileIntelligence.buildSendIntent(file, task.appResolution.packageName)
                    if (pol.canShareFiles == 2) {
                        getApplication<android.app.Application>().startActivity(sendIntent)
                        "Sending ${file.name} to ${task.personName}."
                    } else {
                        "Ready to send ${file.name} (${file.humanSize}) to ${task.personName} via ${task.appResolution.packageName.substringAfterLast('.')}. Confirm?"
                    }
                }
                messages += ChatMessage(reply, false)
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
                runCatching { PersonRegistry.resolveTask(db, getApplication(), it, cap) }.getOrNull()
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
                                getApplication<Application>().startActivity(res.intent)
                                messages += ChatMessage("${cap.name.lowercase().replace('_',' ')} ${personTask.personName}.", false)
                                PersonRegistry.recordChannelUsed(db, personTask.personEntityId, "default", res.packageName, cap.name)
                            } catch (_: Exception) {
                                messages += ChatMessage("Could not complete action for ${personTask.personName}.", false)
                            }
                            return
                        }
                        res.procedure != null -> {
                            viewModelScope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    ProcedureExecutor.executeFromJson(res.procedure.steps, getApplication(), db, res.procedure.id)
                                }
                                if (result.success) {
                                    AppIntelligence.recordProcedureSuccess(db, res.procedure.id)
                                    PersonRegistry.recordChannelUsed(db, personTask.personEntityId, "default", res.packageName, cap.name)
                                    messages += ChatMessage("${cap.name.lowercase().replace('_',' ')} ${personTask.personName}.", false)
                                } else {
                                    AppIntelligence.recordProcedureFailure(db, res.procedure.id)
                                    messages += ChatMessage("Procedure failed: ${result.failReason}", false)
                                }
                            }
                            return
                        }
                    }
                }
            }
            val appLabel = quickPlan.entityNames.firstOrNull() ?: quickPlan.keywords.firstOrNull()
            if (appLabel != null) {
                val pkg = AppIntelligence.packageForLabel(db, appLabel)
                val resolution = pkg?.let { AppIntelligence.resolve(db, getApplication(), cap, it) }
                    ?: AppIntelligence.resolve(db, getApplication(), cap)
                if (resolution != null) {
                    when {
                        resolution.intent != null -> {
                            try {
                                getApplication<Application>().startActivity(resolution.intent)
                                messages += ChatMessage("Opened $appLabel.", false)
                            } catch (_: Exception) {
                                messages += ChatMessage("Could not open $appLabel.", false)
                            }
                            return
                        }
                        resolution.procedure != null -> {
                            viewModelScope.launch {
                                messages += ChatMessage("Executing procedure for $appLabel…", false)
                                val result = withContext(Dispatchers.IO) {
                                    ProcedureExecutor.executeFromJson(
                                        resolution.procedure.steps, getApplication(), db, resolution.procedure.id
                                    )
                                }
                                if (result.success) {
                                    AppIntelligence.recordProcedureSuccess(db, resolution.procedure.id)
                                    messages += ChatMessage("Done (${result.stepsCompleted} steps).", false)
                                } else {
                                    AppIntelligence.recordProcedureFailure(db, resolution.procedure.id)
                                    messages += ChatMessage("Procedure failed at step ${result.failedStep}: ${result.failReason}", false)
                                }
                            }
                            return
                        }
                    }
                }
            }
        }
        // ─────────────────────────────────────────────────────────────────────
        if (!engine.canHandle(text) && !text.contains(Regex("\\s+then\\s+", RegexOption.IGNORE_CASE))) {
            val model = offlineModel
            if (model == null || !model.isReady) {
                messages += ChatMessage("No verified offline model is ready. Use Import model, or use a deterministic device command.", false)
                return
            }
            modelBusy = true
            viewModelScope.launch {
                try {
                    // ── Deterministic retrieval before LLM ────────────────────
                    val plan   = QueryPlanner.plan(text)
                    val merged = withContext(Dispatchers.IO) {
                        val resolved = DeterministicResolver.resolve(plan, db, memory, getApplication())
                        CandidateMerger.merge(resolved, plan)
                    }
                    if (!merged.requiresLlm && merged.topFacts.isNotEmpty()) {
                        messages += ChatMessage(merged.summary, false)
                        modelBusy = false
                        return@launch
                    }
                    // ─────────────────────────────────────────────────────────
                    val resultDeferred = CompletableDeferred<String>()
                    val llmJob = AegisJob(
                        id            = ResourceGovernor.newId(),
                        label         = "llm-inference",
                        resourceClass = ResourceClass.CRITICAL,
                        priority      = JobPriority.P0_USER_VISIBLE,
                        ramBudgetMb   = 512,
                        cancellable   = true
                    ) {
                        val screen = AegisAccessibilityService.instance?.screenSummary().orEmpty().take(2000)
                        val ocrText = com.newax.aegis.vision.ScreenCaptureService.latestOcrResult.value
                            ?.let { com.newax.aegis.vision.OcrEngine.formatForContext(it) }
                            .orEmpty().take(1000)
                        val unread = com.newax.aegis.accessibility.AegisNotificationListenerService.getInboxSummary()
                        val conversationHistory = messages
                            .takeLast(10)
                            .filter { !it.text.startsWith("[System") && !it.text.startsWith("Processing background") }
                            .joinToString("\n") { if (it.fromUser) "User: ${it.text}" else "Assistant: ${it.text}" }
                        val prompt = buildString {
                            val profile = memory.getAllCategories().entries
                                .filter { it.value.isNotEmpty() }
                                .joinToString("\n") { "${it.key.uppercase()}:\n- " + it.value.joinToString("\n- ") }
                            if (profile.isNotBlank()) append("User Profile:\n$profile\n\n")
                            if (merged.llmContext.isNotBlank()) append("${merged.llmContext}\n\n")
                            if (unread != "Your inbox is clear.") append("Unread Notifications:\n$unread\n\n")
                            if (screen.isNotBlank()) append("Current screen:\n$screen\n\n")
                            if (ocrText.isNotBlank()) append("Screen OCR:\n$ocrText\n\n")
                            if (conversationHistory.isNotBlank()) append("Recent conversation:\n$conversationHistory\n\n")
                            append("User: ${text.trim().take(3000)}")
                        }.take(7000)
                        val frame = com.newax.aegis.vision.ScreenCaptureService.latestFrame.value
                        resultDeferred.complete(model.complete(prompt, frame))
                    }
                    ResourceGovernor.preemptForUser(llmJob)
                    val replyText = resultDeferred.await()

                    val screen = AegisAccessibilityService.instance?.screenSummary().orEmpty()
                    val firstLine = replyText.trim().lineSequence().firstOrNull()?.trim().orEmpty()
                    if (firstLine.isNotBlank() && engine.canHandle(firstLine)) {
                        val commandReply = engine.generateReply(firstLine, screen, VectorMemorySearch.search(db, memory, firstLine))
                        val explanation = replyText.trim().removePrefix(firstLine).trim()
                        messages += ChatMessage(if (explanation.isNotBlank()) explanation else commandReply.text, false)
                        commandReply.proposedAction?.let { processAction(it) }
                    } else {
                        messages += ChatMessage(replyText, false)
                    }
                    if (isBackground && text.contains("[Live Call]")) {
                        tts?.speak(replyText, TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                } catch (error: Throwable) {
                    messages += ChatMessage("Offline model error: ${error.message ?: error.javaClass.simpleName}", false)
                } finally { modelBusy = false }
            }
            return
        }
        val screen = AegisAccessibilityService.instance?.screenSummary().orEmpty()
        val parts = text.split(Regex("\\s+then\\s+", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
        val replies = parts.map { part ->
            engine.generateReply(
                part, screen,
                if (lower in setOf("what do you remember", "show memory", "recall")) memory.getAllCategories().values.flatten() else VectorMemorySearch.search(db, memory, part)
            )
        }
        messages += ChatMessage(replies.joinToString("\n") { it.text }, false)
        val actions = replies.mapNotNull { it.proposedAction }
        val needsApproval = actions.filter { a ->
            val t = AutomationSettings.toggleForAction(a); t == null || !AutomationSettings.isEnabled(t)
        }
        val autoActions = actions.filter { a ->
            val t = AutomationSettings.toggleForAction(a); t != null && AutomationSettings.isEnabled(t)
        }
        autoActions.forEach { processAction(it) }
        if (needsApproval.isNotEmpty()) {
            if (pendingAction == null) {
                queuedActions.clear()
                queuedActions.addAll(needsApproval.drop(1))
                pendingAction = needsApproval.firstOrNull()
            } else {
                queuedActions.addAll(needsApproval)
            }
        }
        val approvalCount = needsApproval.size
        val autoCount = autoActions.size
        if (approvalCount > 1) messages += ChatMessage("Plan: $approvalCount steps need approval, $autoCount auto-executed.", false)
        else if (autoCount > 0 && approvalCount == 0) messages += ChatMessage("All $autoCount steps auto-executed.", false)
    }

    fun approve() {
        val action = pendingAction ?: return
        val requiresBiometric = action is ProposedAction.Send || action is ProposedAction.UpdateMemory ||
            action is ProposedAction.DeleteFile || action is ProposedAction.DeleteContact ||
            action is ProposedAction.RunScript || action is ProposedAction.PostSocialMedia ||
            action is ProposedAction.ForgetFact || action is ProposedAction.DeleteProject
        if (requiresBiometric) {
            biometricAuthRequested = true
            messages += ChatMessage("Awaiting biometric authentication…", false)
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
        val action = pendingAction ?: return
        val ghostModeActive = needsGhostMode(action)
        val ghostIntent = android.content.Intent(getApplication(), com.newax.aegis.accessibility.GhostModeService::class.java)
        if (ghostModeActive) getApplication<Application>().startService(ghostIntent)

        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runAction(action) }
            if (ghostModeActive) getApplication<Application>().stopService(ghostIntent)
            messages += ChatMessage(if (ok) "Action completed." else "Action failed. Check the active app and Accessibility access.", false)
            pendingAction = if (ok) queuedActions.removeFirstOrNull() else null
            if (!ok) queuedActions.clear()
            biometricAuthRequested = false
        }
    }

    private suspend fun runAction(action: ProposedAction): Boolean = when (action) {
        is ProposedAction.UpdateMemory -> {
            memory.remember(action.category, action.info)
            bumpMemoryVersion()
            true
        }
        is ProposedAction.QueryCalendar -> {
            queryCalendar(action.timeframe); true
        }
        is ProposedAction.CreateEvent -> {
            createCalendarEvent(action.title, action.time); true
        }
        is ProposedAction.DeleteFile -> {
            val file = java.io.File(action.path)
            file.exists() && file.delete()
        }
        is ProposedAction.DeleteContact -> {
            try {
                val uri = android.provider.ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                    .appendQueryParameter(android.provider.ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
                getApplication<Application>().contentResolver.delete(
                    uri, "${android.provider.ContactsContract.RawContacts.CONTACT_ID}=?", arrayOf(action.id)
                ) > 0
            } catch (_: Exception) { false }
        }
        is ProposedAction.TakeScreenshot -> {
            val frame = com.newax.aegis.vision.ScreenCaptureService.latestFrame.value
            if (frame == null) false
            else try {
                val file = java.io.File(
                    getApplication<Application>().getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES),
                    "aegis_ss_${System.currentTimeMillis()}.png"
                )
                java.io.FileOutputStream(file).use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                true
            } catch (_: Exception) { false }
        }
        is ProposedAction.RunScript -> {
            val result = com.newax.aegis.engine.CodeSandbox.executeJs(action.code, getApplication())
            com.newax.aegis.engine.TriggerEngine.triggerEvents.tryEmit("[Script Output]\n$result")
            true
        }
        is ProposedAction.AuditSecurity -> {
            withContext(Dispatchers.IO) { com.newax.aegis.engine.SecurityAuditor.auditApps(getApplication()) }
            true
        }
        is ProposedAction.UpdateGraph -> {
            com.newax.aegis.engine.KnowledgeGraph.addEdge(action.from, action.relation, action.to)
            memory.storeRaw("knowledge_graph", com.newax.aegis.engine.KnowledgeGraph.serialize())
            withContext(Dispatchers.IO) {
                LlmTripleExtractor.saveEdge(db, action.from, action.relation, action.to)
            }
            bumpMemoryVersion()
            true
        }
        is ProposedAction.UpdateNode -> {
            com.newax.aegis.engine.KnowledgeGraph.updateNodeProperty(action.id, action.key, action.value)
            memory.storeRaw("knowledge_graph", com.newax.aegis.engine.KnowledgeGraph.serialize())
            bumpMemoryVersion()
            true
        }
        is ProposedAction.LogCommunication -> {
            com.newax.aegis.engine.CommunicationLog.logInteraction(action.contact, action.summaryText)
            val now = System.currentTimeMillis()
            PersonRegistry.resolve(db, action.contact)?.let { eid ->
                db.personRegistryDao().touchInteraction(eid, now, now)
            }
            memory.storeRaw("comm_log", com.newax.aegis.engine.CommunicationLog.serialize())
            bumpMemoryVersion()
            true
        }
        is ProposedAction.UpdateProject -> {
            com.newax.aegis.engine.ProjectTracker.updateProject(action.id, action.status, action.notes)
            memory.storeRaw("project_tracker", com.newax.aegis.engine.ProjectTracker.serialize())
            bumpMemoryVersion()
            true
        }
        is ProposedAction.PrefixSearch -> {
            val result = com.newax.aegis.engine.SemanticSearchEngine.instantPrefixSearch(action.prefix)
            withContext(Dispatchers.Main) { messages += ChatMessage(result, false) }
            true
        }
        is ProposedAction.SearchAll -> {
            val result = withContext(Dispatchers.IO) {
                com.newax.aegis.engine.SemanticSearchEngine.searchAll(action.query)
            }
            withContext(Dispatchers.Main) { messages += ChatMessage(result, false) }
            true
        }
        is ProposedAction.ForgetFact -> {
            memory.forget(action.category, action.fact)
            bumpMemoryVersion()
            true
        }
        is ProposedAction.DeleteProject -> {
            val deleted = com.newax.aegis.engine.ProjectTracker.deleteProject(action.id)
            memory.storeRaw("project_tracker", com.newax.aegis.engine.ProjectTracker.serialize())
            bumpMemoryVersion()
            deleted
        }
        // ── Self-learning ────────────────────────────────────────────────────
        is ProposedAction.StartLearning -> {
            LearningWorker.schedule(getApplication())
            withContext(Dispatchers.Main) {
                messages += ChatMessage(
                    "Self-learning enabled. Scanning in 2 minutes. Sources: contacts → SMS → call logs → gallery → downloads (cycling every 20 min). All extracted facts will appear as drafts for your approval.",
                    false
                )
                refreshDrafts()
            }
            true
        }
        is ProposedAction.StopLearning -> {
            LearningWorker.cancel(getApplication())
            withContext(Dispatchers.Main) {
                messages += ChatMessage("Self-learning stopped. Your existing drafts and memory are unchanged.", false)
            }
            true
        }
        is ProposedAction.ScanNow -> {
            LearningWorker.runOnce(getApplication())
            withContext(Dispatchers.Main) {
                messages += ChatMessage("Scan batch queued. New drafts will appear shortly.", false)
            }
            true
        }
        is ProposedAction.ShowDrafts -> {
            val drafts = DraftStore.pending(db)
            withContext(Dispatchers.Main) {
                refreshDrafts()
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
                messages += ChatMessage(text, false)
            }
            true
        }
        is ProposedAction.ApproveDraft -> {
            val approved = DraftStore.approveDraft(db, action.id)
            withContext(Dispatchers.Main) {
                if (approved != null) {
                    val result = MemoryConsolidator.processApproval(memory, approved)
                    val msg = when (result.action) {
                        MemoryConsolidator.Action.STORE_NEW -> {
                            memory.remember(approved.category, result.resolvedFact ?: approved.fact)
                            approved.subjectName?.let { PersonFactStore.addFact(db, it, approved) }
                            bumpMemoryVersion()
                            "Saved [${approved.category}]: ${approved.fact.take(70)}"
                        }
                        MemoryConsolidator.Action.SKIP_DUPLICATE -> {
                            "Already in memory — duplicate skipped."
                        }
                        MemoryConsolidator.Action.REPLACE_EXISTING -> {
                            result.conflictingFact?.let { memory.forget(approved.category, it) }
                            memory.remember(approved.category, result.resolvedFact ?: approved.fact)
                            approved.subjectName?.let { PersonFactStore.addFact(db, it, approved) }
                            bumpMemoryVersion()
                            "Memory updated — replaced outdated fact."
                        }
                        MemoryConsolidator.Action.PRESENT_CONFLICT -> {
                            memory.remember(approved.category, result.resolvedFact ?: approved.fact)
                            approved.subjectName?.let { PersonFactStore.addFact(db, it, approved) }
                            bumpMemoryVersion()
                            "Saved — note: similar fact exists: \"${result.conflictingFact?.take(60)}\""
                        }
                    }
                    refreshDrafts()
                    messages += ChatMessage(msg, false)
                } else {
                    messages += ChatMessage("Draft not found: ${action.id.take(8)}", false)
                }
            }
            approved != null
        }
        is ProposedAction.RejectDraft -> {
            DraftStore.rejectDraft(db, action.id)
            withContext(Dispatchers.Main) {
                refreshDrafts()
                messages += ChatMessage("Draft rejected and discarded.", false)
            }
            true
        }
        is ProposedAction.ApproveAllDrafts -> {
            val approved = DraftStore.approveAll(db)
            withContext(Dispatchers.Main) {
                var stored = 0; var skipped = 0; var replaced = 0
                approved.forEach { d ->
                    val result = MemoryConsolidator.processApproval(memory, d)
                    when (result.action) {
                        MemoryConsolidator.Action.STORE_NEW -> {
                            memory.remember(d.category, result.resolvedFact ?: d.fact)
                            d.subjectName?.let { PersonFactStore.addFact(db, it, d) }
                            stored++
                        }
                        MemoryConsolidator.Action.SKIP_DUPLICATE -> skipped++
                        MemoryConsolidator.Action.REPLACE_EXISTING -> {
                            result.conflictingFact?.let { memory.forget(d.category, it) }
                            memory.remember(d.category, result.resolvedFact ?: d.fact)
                            d.subjectName?.let { PersonFactStore.addFact(db, it, d) }
                            replaced++
                        }
                        MemoryConsolidator.Action.PRESENT_CONFLICT -> {
                            memory.remember(d.category, result.resolvedFact ?: d.fact)
                            d.subjectName?.let { PersonFactStore.addFact(db, it, d) }
                            stored++
                        }
                    }
                }
                bumpMemoryVersion()
                refreshDrafts()
                messages += ChatMessage(
                    "Approved ${approved.size} drafts — $stored saved, $replaced updated, $skipped duplicates skipped.",
                    false
                )
            }
            true
        }
        is ProposedAction.RejectAllDrafts -> {
            DraftStore.rejectAll(db)
            withContext(Dispatchers.Main) {
                refreshDrafts()
                messages += ChatMessage("All pending drafts rejected.", false)
            }
            true
        }

        // ── Contacts ─────────────────────────────────────────────────────────
        is ProposedAction.AnalyzeContacts -> {
            withContext(Dispatchers.IO) {
                try {
                    val mgr = ContactsManager(getApplication(), memory)
                    val report = mgr.scanAndClean(dryRun = false, autoMerge = false)
                    val summary = mgr.formatScanReport(report)
                    withContext(Dispatchers.Main) { messages += ChatMessage(summary, false) }
                    true
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        messages += ChatMessage("Contact scan failed: ${e.message}", false)
                    }
                    false
                }
            }
        }
        is ProposedAction.ShowPersonProfile -> {
            withContext(Dispatchers.IO) {
                try {
                    val mgr = ContactsManager(getApplication(), memory)
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
                    withContext(Dispatchers.Main) { messages += ChatMessage(text, false) }
                    true
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        messages += ChatMessage("Profile load failed: ${e.message}", false)
                    }
                    false
                }
            }
        }
        is ProposedAction.BuildPersonProfile -> {
            withContext(Dispatchers.IO) {
                try {
                    val mgr = ContactsManager(getApplication(), memory)
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
                    withContext(Dispatchers.Main) { messages += ChatMessage(text, false) }
                    true
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        messages += ChatMessage("Profile build failed: ${e.message}", false)
                    }
                    false
                }
            }
        }
        is ProposedAction.MergeContacts -> {
            withContext(Dispatchers.IO) {
                try {
                    val mgr = ContactsManager(getApplication(), memory)
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
                    withContext(Dispatchers.Main) { messages += ChatMessage(text, false) }
                    true
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        messages += ChatMessage("Merge failed: ${e.message}", false)
                    }
                    false
                }
            }
        }
        is ProposedAction.PostSocialMedia -> {
            try {
                val context = getApplication<Application>()
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
                AegisAccessibilityService.instance?.execute(finalizeAction)
                true
            } catch (_: Exception) { false }
        }
        else -> withContext(Dispatchers.Main) { AegisAccessibilityService.instance?.execute(action) == true }
    }

    private suspend fun queryCalendar(timeframe: String) {
        val context = getApplication<Application>()
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
        messages += ChatMessage(
            if (events.isEmpty()) "No events found for $timeframe."
            else "Found: " + events.joinToString(" | ") { it.formatted() },
            false
        )
    }

    private suspend fun createCalendarEvent(title: String, timeString: String) {
        val context = getApplication<Application>()
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
            messages += ChatMessage("Created calendar event: '$title' for $timeString", false)
        } catch (e: Exception) {
            messages += ChatMessage("Failed to create event. Permissions missing.", false)
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
                val h = Regex("\\d+").find(lower)?.value?.toLongOrNull() ?: 1L
                target += h * 3600000L
            }
            lower.contains("minute") -> {
                val m = Regex("\\d+").find(lower)?.value?.toLongOrNull() ?: 30L
                target += m * 60000L
            }
            else -> target += 3600000L
        }
        return target
    }

    fun reject() {
        pendingAction = null
        queuedActions.clear()
        messages += ChatMessage("Action plan cancelled.", false)
    }

    override fun onCleared() {
        getApplication<Application>().unregisterComponentCallbacks(memoryCallback)
        offlineModel?.close()
        tts?.shutdown()
        super.onCleared()
    }
}
