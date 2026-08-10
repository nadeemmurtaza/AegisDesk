package com.newax.aegis.desktop.execution

import com.newax.aegis.desktop.planner.DesktopGoalPlanner
import com.newax.aegis.desktop.planner.GoalState
import com.newax.aegis.desktop.planner.SkillRegistry
import com.newax.aegis.desktop.planner.TaskNode
import com.newax.aegis.desktop.planner.TaskStatus
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResolver
import com.newax.aegis.platform.PlatformCapabilityRegistry
import com.newax.aegis.platform.desktop.DesktopCapability
import com.newax.aegis.platform.windows.WindowsAppIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop counterpart of Android's `GoalExecutor`: runs a goal's plan one task
 * at a time in topological order, replacing the runner's manual `task` command
 * with real execution (Phase 5h).
 *
 * Every run re-checks the capability pre-flight live — the same
 * [CapabilityResolver] the planner uses — so a goal blocked by an unready
 * capability fails with the exact reason the goals board shows, and re-running
 * after enabling the capability works without re-planning (the executor
 * re-activates BLOCKED goals and re-runs FAILED tasks). Task state
 * (RUNNING → COMPLETED/FAILED) is pushed through
 * [DesktopGoalPlanner.updateTask], which the runner surfaces via [onProgress].
 *
 * Execution is a capability ladder owned by [DesktopExecutionRouter]: the exact
 * Start Menu shortcut target from the app index first (Phase 5i), then process
 * launch, then [DesktopCapability.activateApp] (Win32) as the semantic
 * fallback. Task outputs pipe into the inputs of later tasks — find_app's
 * resolved exact target (name + .lnk path) becomes launch_app's launch target —
 * completing the ladder end to end. This is the single path to the app-launch
 * sink (R11): the only caller of the router is this executor, and every task
 * passes the live capability gate first.
 */
object DesktopGoalExecutor {

    /**
     * Executes the plan for [goalId]. Activates the goal (re-activating a
     * BLOCKED goal after its blocker clears is legal), walks its pending/failed
     * tasks in topological order, and returns failure with the blocker on the
     * first failed task — which also BLOCKs the goal, exactly Android's sequence.
     */
    suspend fun run(
        goalId: String,
        registry: PlatformCapabilityRegistry?,
        router: DesktopExecutionRouter = DesktopExecutionRouter(),
        appIndex: WindowsAppIndex? = null,
        onProgress: (String) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.Default) {
        val graph = DesktopGoalPlanner.getGraph(goalId)
            ?: return@withContext Result.failure(
                IllegalStateException("No plan for goal $goalId — plan it first")
            )
        val label = DesktopGoalPlanner.getGoal(goalId)?.description ?: goalId

        val activated = DesktopGoalPlanner.activate(goalId)
        val state = DesktopGoalPlanner.getState(goalId) ?: GoalState.OPEN
        if (!activated && state != GoalState.ACTIVE) {
            return@withContext Result.failure(
                IllegalStateException("\"$label\" cannot start (state: $state)")
            )
        }
        onProgress("activated \"$label\" — executing ${graph.tasks.size} task(s)")

        val tasks = graph.topologicalOrder()
            .filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.FAILED }

        // The data pipe between tasks: every task's outputs land here and become
        // inputs for the tasks that follow (find_app's exact target → launch_app).
        val carry = mutableMapOf<String, Any>("goalId" to goalId)

        for (task in tasks) {
            val outcome = runTask(goalId, task, carry, registry, router, appIndex, onProgress)
            if (outcome.isFailure) {
                val reason = outcome.exceptionOrNull()?.message ?: "Task '${task.description}' failed"
                DesktopGoalPlanner.block(goalId)
                onProgress("task \"${task.description}\" FAILED — goal BLOCKED: $reason")
                return@withContext Result.failure(
                    outcome.exceptionOrNull() ?: IllegalStateException(reason)
                )
            }
        }

        onProgress("all ${tasks.size} task(s) finished — \"$label\" ${DesktopGoalPlanner.getState(goalId)}")
        Result.success(Unit)
    }

    private suspend fun runTask(
        goalId: String,
        task: TaskNode,
        carry: MutableMap<String, Any>,
        registry: PlatformCapabilityRegistry?,
        router: DesktopExecutionRouter,
        appIndex: WindowsAppIndex?,
        onProgress: (String) -> Unit,
    ): Result<Unit> {
        val skillId = task.skillId
        if (skillId == null) {
            return finishFailed(goalId, task, "Task '${task.description}' has no skill assigned", onProgress)
        }
        val skill = SkillRegistry.get(skillId)
            ?: return finishFailed(goalId, task, "Skill '$skillId' is not registered", onProgress)

        // Capability gate — live, per task. The same resolver the planner
        // pre-flight uses, so execution-time truth matches the goals board.
        val blocked = registry?.let { r ->
            CapabilityResolver.resolveAll(r, skill.requiredCapabilities).filter { it.isBlocked }
        }.orEmpty()
        if (blocked.isNotEmpty()) {
            val cap = blocked.first().requested
            return finishFailed(
                goalId, task,
                "Capability '$cap' is not ready — resolve it first (see \"status\" / \"skills\")",
                onProgress,
            )
        }

        DesktopGoalPlanner.updateTask(goalId, task.id, TaskStatus.RUNNING)
        onProgress("RUNNING  ${task.description}")

        // Inputs = task identity + the goal's description (so find_app sees the
        // actual target, not its own step label) + everything earlier tasks
        // produced through the pipe.
        val goalDescription = DesktopGoalPlanner.getGoal(goalId)?.description ?: task.description
        val query = if (skillId == "find_app") targetOf(goalDescription) else goalDescription
        val inputs = LinkedHashMap<String, Any>()
        inputs["goalId"] = goalId
        inputs["task"] = task.description
        inputs["query"] = query
        inputs.putAll(carry)

        val invoke: Result<Map<String, Any>> = when (skillId) {
            // Ladder rung 1 (find_app): resolve the goal's target against the
            // app index (Start Menu enumeration) so launch_app gets an exact
            // target — friendly name + .lnk path — instead of a guessed name.
            // An index miss falls back to the stripped target itself (the app
            // may still be launchable by name, and the launch fails honestly
            // with the real reason if it is not installed).
            "find_app" -> {
                val matches = appIndex?.search(query).orEmpty()
                val best = matches.firstOrNull()
                val resolvedName = best?.name?.takeIf { it.isNotBlank() } ?: query
                val summary = if (best != null) {
                    "index match '${best.name}' (${best.category})"
                } else {
                    "no index match — using '$query'"
                }
                val out = mutableMapOf<String, Any>("appName" to resolvedName, "summary" to summary)
                best?.lnkPath?.takeIf { it.isNotBlank() }?.let { out["lnkPath"] = it }
                Result.success(out)
            }

            // Ladder rung 2 (launch_app): the real launch, through the router's
            // shortcut → process → Win32-activateApp ladder. The app name and
            // exact .lnk path come from find_app's piped output, falling back to
            // the stripped goal target (e.g. re-running after a blocked launch,
            // when find_app is already complete and the pipe starts fresh).
            "launch_app" -> {
                val piped = inputs["appName"]?.toString()?.trim().takeIf { !it.isNullOrBlank() }
                val appName = (piped ?: targetOf(goalDescription)).trim()
                val lnkPath = inputs["lnkPath"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                launchApp(goalId, task, appName, lnkPath, registry, router, onProgress)
            }

            // Every other skill has no desktop executor yet — its real
            // implementation belongs to the LLM/memory slices. Honest typed
            // failure, never a silent success.
            else -> Result.failure(
                IllegalStateException(
                    "Skill '$skillId' has no desktop executor yet — only find_app and launch_app can run on desktop"
                )
            )
        }

        if (invoke.isFailure) {
            return finishFailed(
                goalId, task,
                invoke.exceptionOrNull()?.message ?: "Skill '$skillId' failed",
                onProgress,
            )
        }

        val result = invoke.getOrDefault(emptyMap())
        // The pipe: this task's outputs become inputs for the tasks that follow.
        result.forEach { (key, value) -> carry[key] = value }
        val message = buildString {
            append("via ")
            append(result["tier"]?.toString() ?: "skill")
            result["summary"]?.let { append(" · ").append(it) }
        }
        finishCompleted(goalId, task, message, onProgress)
        return Result.success(Unit)
    }

    /** The launch rung: route [appName] (+ exact .lnk target when indexed) through the router's ladder and report the tier. */
    private suspend fun launchApp(
        goalId: String,
        task: TaskNode,
        appName: String,
        lnkPath: String?,
        registry: PlatformCapabilityRegistry?,
        router: DesktopExecutionRouter,
        onProgress: (String) -> Unit,
    ): Result<Map<String, Any>> {
        if (appName.isBlank()) {
            return Result.failure(
                IllegalStateException("launch_app: no app to launch — find_app produced no target")
            )
        }
        val desktop = registry?.get(CapabilityId.DESKTOP) as? DesktopCapability
        val plan = router.resolveLaunch(appName, desktop, lnkPath)
        onProgress("→ ${plan.description}")
        return when (val outcome = plan.executor()) {
            is DesktopLaunchOutcome.Launched -> Result.success(
                mapOf(
                    "launched" to true,
                    "appName" to appName,
                    "tier" to outcome.tier.name,
                    "summary" to outcome.detail,
                )
            )
            is DesktopLaunchOutcome.Failed -> Result.failure(IllegalStateException(outcome.reason))
        }
    }

    private fun finishCompleted(goalId: String, task: TaskNode, message: String, onProgress: (String) -> Unit) {
        DesktopGoalPlanner.updateTask(goalId, task.id, TaskStatus.COMPLETED, message)
        onProgress("DONE     ${task.description} — $message")
    }

    private fun finishFailed(goalId: String, task: TaskNode, reason: String, onProgress: (String) -> Unit): Result<Unit> {
        DesktopGoalPlanner.updateTask(goalId, task.id, TaskStatus.FAILED, reason)
        onProgress("FAILED   ${task.description} — $reason")
        return Result.failure(IllegalStateException(reason))
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
}
