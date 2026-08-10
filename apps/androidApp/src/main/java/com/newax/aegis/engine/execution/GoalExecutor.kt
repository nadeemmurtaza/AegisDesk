package com.newax.aegis.engine.execution

import android.content.Context
import com.newax.aegis.PlatformCapabilitiesHolder
import com.newax.aegis.PolicyHolder
import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.apps.AppCapability
import com.newax.aegis.engine.audit.ExecutionAuditEntry
import com.newax.aegis.engine.audit.ExecutionAuditHolder
import com.newax.aegis.engine.audit.RunOutcome
import com.newax.aegis.engine.audit.TaskRunRecord
import com.newax.aegis.engine.bus.AegisEvent
import com.newax.aegis.engine.bus.AegisEventBus
import com.newax.aegis.engine.intelligence.GoalPlanner
import com.newax.aegis.engine.intelligence.SkillRegistry
import com.newax.aegis.engine.intelligence.TaskFailureKind
import com.newax.aegis.engine.intelligence.TaskNode
import com.newax.aegis.engine.intelligence.TaskStatus
import com.newax.aegis.platform.CapabilityResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Runs a goal's plan through the app's execution machinery, one task at a time in
 * topological order (the same shape as WorkflowManager's step loop — SkillRegistry
 * is the single execution path; ExecutionRouter picks the cheapest tier for
 * app-automation tasks and owns the real app-launch executor when a package is known).
 *
 * Every run re-checks the capability pre-flight live (the same CapabilityResolver the
 * planner uses), so a goal blocked by an unready capability fails with the exact
 * reason the Goals screen shows, and re-activating after enabling the capability
 * works without re-planning. Task state (RUNNING → COMPLETED/FAILED) is pushed through
 * GoalPlanner.updateTask and the event bus, which the Goals screen renders live.
 *
 * Each task's outputs are piped into the inputs of later tasks (find_app's resolved
 * package → launch_app's ExecutionRouter launch), completing the capability ladder.
 *
 * Every run is recorded in the execution audit trail (Track A8): goal, outcome,
 * per-task tiers, statuses and timestamps — the "Recent runs" section on the Goals
 * screen, persisted to kv_store (rule 6: consequential modifications are auditable).
 */
object GoalExecutor {

    /**
     * Executes the plan for [goalId]. Returns success when every pending task
     * completed; failure carries the blocker (unready capability, missing skill,
     * handler exception). The goal state machine is advanced in place:
     * activate → (all tasks) complete, or → blocked on the first failure.
     */
    suspend fun run(goalId: String, context: Context): Result<Unit> = withContext(Dispatchers.Default) {
        val graph = GoalPlanner.getGraph(goalId)
            ?: return@withContext Result.failure(IllegalStateException("No plan for goal $goalId"))

        GoalPlanner.activate(goalId)
        val db = runCatching { AegisDatabase.get }.getOrNull()
        val tasks = graph.topologicalOrder()
            .filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.FAILED }

        // The data pipe between tasks: every skill's outputs land here and become
        // inputs for the tasks that follow (find_app's package → launch_app's
        // ExecutionRouter launch — the capability ladder, end to end).
        val carry = mutableMapOf<String, Any>("goalId" to goalId)

        val runStartedMs = System.currentTimeMillis()
        val taskRecords = mutableListOf<TaskRunRecord>()

        for (task in tasks) {
            val outcome = runTask(goalId, task, carry, context, db)
            taskRecords += TaskRunRecord(
                taskId = task.id,
                description = task.description,
                skillId = task.skillId,
                tier = outcome.getOrNull()?.name,
                status = task.status,
                result = task.result,
                startedMs = task.startedMs ?: runStartedMs,
                finishedMs = task.completedMs
            )
            if (outcome.isFailure) {
                val reason = outcome.exceptionOrNull()?.message ?: "Task '${task.description}' failed"
                blockGoal(goalId, reason)
                recordRun(goalId, RunOutcome.FAILED, runStartedMs, taskRecords)
                return@withContext Result.failure(outcome.exceptionOrNull() ?: IllegalStateException(reason))
            }
        }

        recordRun(goalId, RunOutcome.COMPLETED, runStartedMs, taskRecords)
        Result.success(Unit)
    }

    private fun recordRun(
        goalId: String,
        outcome: RunOutcome,
        startedMs: Long,
        tasks: List<TaskRunRecord>
    ) {
        ExecutionAuditHolder.record(
            ExecutionAuditEntry(
                id = UUID.randomUUID().toString(),
                goalId = goalId,
                goalDescription = GoalPlanner.getGoal(goalId)?.description ?: goalId,
                outcome = outcome,
                startedMs = startedMs,
                finishedMs = System.currentTimeMillis(),
                tasks = tasks
            )
        )
    }

    /**
     * Runs one task. Returns the tier used on success (null when the skill ran
     * without an ExecutionRouter tier) — the caller captures it into the audit.
     */
    private suspend fun runTask(
        goalId: String,
        task: TaskNode,
        carry: MutableMap<String, Any>,
        context: Context,
        db: AegisDatabase?
    ): Result<ExecutionTier?> {
        val skillId = task.skillId
        if (skillId == null) {
            val reason = "Task '${task.description}' has no skill assigned"
            return finishFailed(goalId, task, reason)
        }

        val skill = SkillRegistry.get(skillId)
            ?: return finishFailed(goalId, task, "Skill '$skillId' is not registered")

        // Capability gate — live, per task. The same resolver the planner pre-flight
        // uses, so the execution-time truth matches what the Goals screen explains.
        val blocked = PlatformCapabilitiesHolder.registry()?.let { registry ->
            CapabilityResolver.resolveAll(registry, skill.requiredCapabilities)
                .filter { it.isBlocked }
        }.orEmpty()
        if (blocked.isNotEmpty()) {
            val cap = blocked.first().requested
            return finishFailed(
                goalId, task,
                "Capability '$cap' is not ready — enable it on the Capabilities screen",
                kind = TaskFailureKind.CAPABILITY
            )
        }

        // Policy gate — rule 10 (PLAN is never EXECUTE): the goal/plan grants zero
        // execution authority. Every privileged task is evaluated through the policy
        // spine as AGENT origin (stricter than user). Only AUTO_EXECUTE runs
        // autonomously; approval/strong/deny fail the task with the policy reason —
        // the user then adjusts the mode (Capabilities → Policy modes) or runs the
        // action from chat, where the approval flow exists.
        val policyAction = SkillRegistry.policyActionFor(skillId)
        if (policyAction != null) {
            val evaluation = PolicyHolder.engine().evaluate(policyAction, ActionOrigin.AGENT)
            if (!evaluation.decision.allowsAutonomousExecution) {
                return finishFailed(
                    goalId, task,
                    "Skill '$skillId' cannot run autonomously — policy ${evaluation.decision.name} " +
                        "(${evaluation.reason}). Change its mode in Capabilities → Policy modes, " +
                        "or run it from chat where approval is possible.",
                    kind = TaskFailureKind.POLICY
                )
            }
        }

        markRunning(goalId, task)

        // Tier selection through ExecutionRouter: an app-automation capability maps to
        // the cheapest available implementation tier (ANDROID_API → … → LLM_REASONING).
        val tier = if (db != null) {
            skill.requiredCapabilities
                .mapNotNull { runCatching { AppCapability.valueOf(it) }.getOrNull() }
                .firstOrNull()
                ?.let { ExecutionRouter.resolveCapability(context, db, it) }
        } else {
            null
        }

        // Inputs = task identity + the goal's description (so find_app sees the
        // actual target, not its own step label) + everything earlier tasks produced.
        val goalDescription = GoalPlanner.getGoal(goalId)?.description ?: task.description
        val query = if (skillId == "find_app") targetOf(goalDescription) else goalDescription
        val inputs = LinkedHashMap<String, Any>()
        inputs["goalId"] = goalId
        inputs["task"] = task.description
        inputs["query"] = query
        inputs.putAll(carry)

        // Capability ladder, find_app rung: resolve the target against the app index
        // (ANDROID_API tier) so the real package flows downstream. Package-name
        // queries fall through to the handler, which echoes them verbatim.
        if (skillId == "find_app" && db != null) {
            resolvePackage(db, query)?.let { inputs["packageName"] = it }
        }

        // Real app launch goes through ExecutionRouter's concrete executor: the
        // package piped in from find_app makes this the ladder's final rung.
        val packageName = inputs["packageName"]?.toString().orEmpty()
        val launched = if (skillId == "launch_app" && packageName.isNotBlank() && db != null) {
            ExecutionRouter.resolveOpenApp(context, db, packageName).executor()
        } else {
            null
        }

        val invoke = if (launched != null) {
            if (launched) Result.success(mapOf("launched" to true, "packageName" to packageName))
            else Result.failure(IllegalStateException("Could not launch $packageName"))
        } else {
            SkillRegistry.invoke(skillId, inputs)
        }

        if (invoke.isFailure) {
            return finishFailed(
                goalId, task,
                invoke.exceptionOrNull()?.message ?: "Skill '$skillId' failed"
            )
        }

        val result = invoke.getOrDefault(emptyMap())
        // The pipe: this task's outputs become inputs for the tasks that follow.
        result.forEach { (k, v) -> carry[k] = v }
        val message = buildString {
            append("via ")
            append(tier?.name ?: "skill")
            result["summary"]?.let { append(" · ").append(it) }
        }
        finishCompleted(goalId, task, message)
        return Result.success(tier)
    }

    private fun markRunning(goalId: String, task: TaskNode) {
        GoalPlanner.updateTask(goalId, task.id, TaskStatus.RUNNING)
        AegisEventBus.emit(AegisEvent.TaskUpdated(goalId, task.id, TaskStatus.RUNNING.name, null))
    }

    private fun finishCompleted(goalId: String, task: TaskNode, message: String) {
        GoalPlanner.updateTask(goalId, task.id, TaskStatus.COMPLETED, message)
        AegisEventBus.emit(AegisEvent.TaskUpdated(goalId, task.id, TaskStatus.COMPLETED.name, message))
    }

    private fun finishFailed(
        goalId: String,
        task: TaskNode,
        reason: String,
        kind: TaskFailureKind? = null
    ): Result<Nothing> {
        GoalPlanner.updateTask(goalId, task.id, TaskStatus.FAILED, reason, kind)
        AegisEventBus.emit(AegisEvent.TaskUpdated(goalId, task.id, TaskStatus.FAILED.name, reason))
        return Result.failure(IllegalStateException(reason))
    }

    private fun blockGoal(goalId: String, reason: String) {
        GoalPlanner.block(goalId)
        AegisEventBus.emit(AegisEvent.GoalBlocked(goalId, reason))
    }

    private val INTENT_VERBS = setOf(
        "send", "open", "search", "book", "find", "play", "share", "summarize",
        "remind", "call", "navigate", "create", "delete", "backup", "index",
        "launch", "start", "show"
    )

    /** Strips the leading intent verb so "open whatsapp" resolves to "whatsapp". */
    private fun targetOf(description: String): String {
        val words = description.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return description.trim()
        return if (words.first().lowercase() in INTENT_VERBS) {
            words.drop(1).joinToString(" ")
        } else {
            description.trim()
        }
    }

    /**
     * ANDROID_API rung of the ladder: match a fuzzy app name against the app index
     * (label or package). Returns null when the index has no match, in which case
     * the raw target still flows downstream and launch fails honestly.
     */
    private suspend fun resolvePackage(db: AegisDatabase, target: String): String? {
        val q = target.trim().lowercase()
        if (q.isEmpty()) return null
        val records = db.appRegistryDao().allRecords()
        return records.firstOrNull { r ->
            r.label.lowercase().contains(q) || r.packageName.lowercase().contains(q)
        }?.packageName
    }
}
