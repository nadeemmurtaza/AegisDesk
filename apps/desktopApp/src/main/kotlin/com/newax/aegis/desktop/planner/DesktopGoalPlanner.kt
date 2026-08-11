package com.newax.aegis.desktop.planner

import com.newax.aegis.desktop.ExecutionAudit
import com.newax.aegis.desktop.GoalsSnapshot
import com.newax.aegis.desktop.PlanVerdict
import com.newax.aegis.desktop.TaskFailureKind
import com.newax.aegis.platform.CapabilityResolution
import com.newax.aegis.platform.CapabilityResolver
import com.newax.aegis.platform.PlatformCapabilityRegistry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Goal(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val intent: String,
    val priority: Int = 5,
    val createdMs: Long = System.currentTimeMillis(),
)

enum class TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

/**
 * One step of a decomposed goal, backed by a registered skill — mirrors
 * Android's `TaskNode` (id, dependencies, mutable status/result/timestamps).
 */
data class TaskNode(
    val id: String = UUID.randomUUID().toString(),
    val goalId: String,
    val description: String,
    val skillId: String?,
    val dependencies: List<String> = emptyList(),
    var status: TaskStatus = TaskStatus.PENDING,
    var result: String? = null,
    var startedMs: Long? = null,
    var completedMs: Long? = null,
    /** Why a failed task failed (POLICY/CAPABILITY) — mirrors Android's TaskNode. */
    var failureKind: TaskFailureKind? = null,
)

data class TaskGraph(
    val goalId: String,
    val tasks: List<TaskNode>,
    val createdMs: Long = System.currentTimeMillis()
) {
    fun topologicalOrder(): List<TaskNode> {
        val visited = mutableSetOf<String>()
        val result = mutableListOf<TaskNode>()
        val byId = tasks.associateBy { it.id }

        fun visit(node: TaskNode) {
            if (node.id in visited) return
            node.dependencies.forEach { depId -> byId[depId]?.let { visit(it) } }
            visited.add(node.id)
            result.add(node)
        }

        tasks.forEach { visit(it) }
        return result
    }

    fun isComplete(): Boolean = tasks.all { it.status == TaskStatus.COMPLETED || it.status == TaskStatus.SKIPPED }
    fun hasFailed(): Boolean = tasks.any { it.status == TaskStatus.FAILED }
    fun progress(): Float = if (tasks.isEmpty()) 1f else
        tasks.count { it.status in setOf(TaskStatus.COMPLETED, TaskStatus.SKIPPED, TaskStatus.FAILED) }.toFloat() / tasks.size
}

/**
 * The outcome of planning one goal — desktop mirror of Android's `PlanResult`.
 *
 * `feasible` is true only when every task skill exists *and* every capability
 * those skills require is operationally ready. The capability half is computed
 * by [CapabilityResolver] against the process registry (the caller passes the
 * registry in), so skills resolve through the platform contract — on Windows the
 * registered Desktop capability makes OPEN_APP/SEND_TEXT/PLAY_MEDIA ready; on
 * other OSes they report NOT_SUPPORTED and the plan reports exactly which
 * capability is blocked and why ([warnings]).
 */
data class DesktopPlan(
    val goal: Goal,
    val tasks: List<TaskNode>,
    val feasible: Boolean,
    val missingSkills: List<String>,
    /** Skill capability names that are platform-gated but no registered capability is ready for. */
    val missingCapabilities: List<String>,
    /** One human-readable reason per blocked capability (status + candidate surface). */
    val warnings: List<String>,
)

/**
 * Desktop counterpart of Android's `GoalPlanner` — the same decomposition, the
 * same capability pre-flight through the shared contract resolver, and the same
 * stored lifecycle: goals, task graphs, per-goal state machines and plan
 * results, advanced via [activate]/[block]/[abandon]/[updateTask].
 *
 * [updateTask] mutates the graph in place and auto-completes the goal when every
 * task is finished (Android's exact semantics). Blocking on task failure is the
 * executor's job — [com.newax.aegis.desktop.execution.DesktopGoalExecutor] does
 * `updateTask(FAILED)` then `block(goalId)`, Android's GoalExecutor sequence.
 */
object DesktopGoalPlanner {

    private val goals = ConcurrentHashMap<String, Goal>()
    private val graphs = ConcurrentHashMap<String, TaskGraph>()
    private val stateMachines = ConcurrentHashMap<String, StateMachine<GoalState>>()
    private val plans = ConcurrentHashMap<String, DesktopPlan>()

    /**
     * Intent keyword → task steps. Every step references a skill registered in
     * [SkillRegistry], so [DesktopPlan.missingSkills] stays a meaningful signal
     * (a genuinely unregistered skill) instead of always-populated noise.
     */
    private val DECOMPOSITION_RULES: Map<String, List<String>> = mapOf(
        "send" to listOf("find_contact", "send_message"),
        "open" to listOf("find_app", "launch_app"),
        "search" to listOf("execute_search"),
        "find" to listOf("find_file", "execute_search"),
        "play" to listOf("play_media"),
        "summarize" to listOf("analyze_request", "generate_summary"),
        "remind" to listOf("set_reminder"),
        "call" to listOf("find_contact"),
        "share" to listOf("find_file"),
    )

    private const val DEFAULT_STEPS = "analyze_request"

    /**
     * Plans one goal and stores it (goal, task graph, state machine, plan
     * result). The registry is passed in (the runner passes
     * [com.newax.aegis.desktop.DesktopCapabilitiesHolder]'s instance) so the
     * planner is a pure function of the live registry and stays testable.
     */
    fun plan(description: String, registry: PlatformCapabilityRegistry?): DesktopPlan {
        val goal = Goal(description = description, intent = inferIntent(description))
        val tasks = decompose(goal)
        val missingSkills = tasks.mapNotNull { it.skillId }
            .filter { !SkillRegistry.has(it) }

        // Capability pre-flight — the core of the planner slice: every task
        // skill declares what it needs (e.g. "OPEN_APP"), and the platform
        // registry is the single source of truth for whether any registered
        // capability can back it right now. Skills resolve through CapabilityIds
        // via the shared CapabilityResolver — never ad-hoc string matching.
        val requiredCapabilities = tasks.mapNotNull { it.skillId }
            .mapNotNull { SkillRegistry.get(it) }
            .flatMap { it.requiredCapabilities }
            .distinct()
        val resolutions = if (registry == null) {
            // No registry at all = no registered capability = every platform-gated
            // requirement is blocked ("no registered capability"), with the
            // candidate surfaces named so the warning explains what could back it.
            requiredCapabilities.flatMap { capability ->
                CapabilityResolver.candidateIds(capability)
                    .takeIf { it.isNotEmpty() }
                    ?.let { ids -> listOf(CapabilityResolution(capability, ids, null)) }
                    ?: emptyList()
            }
        } else {
            CapabilityResolver.resolveAll(registry, requiredCapabilities)
        }
        val missingCapabilities = resolutions.filter { it.isBlocked }.map { it.requested }
        val warnings = resolutions.filter { it.isBlocked }.map { resolution ->
            "Capability '${resolution.requested}' is not ready " +
                "(${resolution.status?.name ?: "no registered capability"}; " +
                "candidates: ${resolution.candidates.joinToString { it.name }})"
        }

        goals[goal.id] = goal
        graphs[goal.id] = TaskGraph(goalId = goal.id, tasks = tasks)
        stateMachines[goal.id] = StateMachines.goal()

        val result = DesktopPlan(
            goal = goal,
            tasks = tasks,
            feasible = missingSkills.isEmpty() && missingCapabilities.isEmpty(),
            missingSkills = missingSkills,
            missingCapabilities = missingCapabilities,
            warnings = warnings,
        )
        plans[goal.id] = result
        return result
    }

    private fun decompose(goal: Goal): List<TaskNode> {
        val steps = DECOMPOSITION_RULES.entries
            .firstOrNull { (key, _) -> goal.intent.lowercase().startsWith(key) }?.value
            ?: listOf(DEFAULT_STEPS)
        val nodes = mutableListOf<TaskNode>()
        steps.forEachIndexed { index, step ->
            nodes.add(
                TaskNode(
                    goalId = goal.id,
                    description = step.replace('_', ' '),
                    skillId = step,
                    dependencies = if (index > 0) listOf(nodes[index - 1].id) else emptyList()
                )
            )
        }
        return nodes
    }

    /** OPEN → ACTIVE. Returns false for unknown goals or illegal transitions. */
    fun activate(goalId: String): Boolean =
        stateMachines[goalId]?.transition(GoalState.ACTIVE) ?: false

    /** ACTIVE → COMPLETED (terminal). Auto-invoked by [updateTask] when the graph is complete. */
    fun complete(goalId: String): Boolean =
        stateMachines[goalId]?.transition(GoalState.COMPLETED) ?: false

    /** ACTIVE → BLOCKED. The executor's response to a failed task (mirrors Android's GoalExecutor). */
    fun block(goalId: String): Boolean =
        stateMachines[goalId]?.transition(GoalState.BLOCKED) ?: false

    /** OPEN/ACTIVE/BLOCKED → ABANDONED (terminal). The user's "give up" — mirrors the Goals screen button. */
    fun abandon(goalId: String): Boolean =
        stateMachines[goalId]?.transition(GoalState.ABANDONED) ?: false

    /**
     * Advances one task in the goal's graph. Sets started/completed timestamps
     * and auto-completes the goal when every task is COMPLETED/SKIPPED — the
     * exact semantics of Android's `GoalPlanner.updateTask`.
     */
    fun updateTask(
        goalId: String,
        taskId: String,
        status: TaskStatus,
        result: String? = null,
        failureKind: TaskFailureKind? = null,
    ) {
        graphs[goalId]?.tasks?.find { it.id == taskId }?.let { task ->
            task.status = status
            task.result = result
            // A re-run clears the stale failure kind (Android's exact semantics:
            // RUNNING/COMPLETED wipe it; FAILED carries the fresh one).
            task.failureKind = if (status == TaskStatus.FAILED) failureKind else null
            if (status == TaskStatus.RUNNING) task.startedMs = System.currentTimeMillis()
            if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED)
                task.completedMs = System.currentTimeMillis()
        }
        graphs[goalId]?.let { if (it.isComplete()) complete(goalId) }
    }

    fun getGoal(id: String): Goal? = goals[id]
    fun getGraph(id: String): TaskGraph? = graphs[id]
    fun getState(id: String): GoalState? = stateMachines[id]?.current

    /** The plan pre-flight for a goal: feasibility, missing skills, missing capabilities, warnings. */
    fun planOf(id: String): DesktopPlan? = plans[id]

    fun activeGoals(): List<Goal> = goals.values
        .filter { stateMachines[it.id]?.current == GoalState.ACTIVE }
        .sortedByDescending { it.priority }

    fun allGoals(): List<Goal> = goals.values.toList()

    /**
     * Captures the full planner state (Phase B3 persistence) — goals, task
     * graphs, state machines, plan verdicts — plus the execution audit trail,
     * for [com.newax.aegis.desktop.FileGoalsStore] to write on every mutation.
     */
    fun snapshot(): GoalsSnapshot = GoalsSnapshot(
        // Deterministic ordering so snapshot equality is stable across saves.
        goals = goals.values.sortedWith(compareBy({ it.createdMs }, { it.id })),
        graphs = graphs.values.sortedWith(compareBy({ it.createdMs }, { it.goalId })),
        states = stateMachines.mapValues { (_, machine) -> machine.current },
        plans = plans.mapValues { (_, plan) ->
            PlanVerdict(plan.feasible, plan.missingSkills, plan.missingCapabilities, plan.warnings)
        },
        runs = ExecutionAudit.all(),
    )

    /**
     * Replaces the in-memory state with a persisted snapshot (bootstrap only —
     * never mid-session). State machines are rehydrated directly ([StateMachine.restore])
     * because persisted states like BLOCKED/COMPLETED are unreachable through the
     * live transition table; plan verdicts are rebuilt from goal + graph + verdict.
     */
    fun restore(snapshot: GoalsSnapshot) {
        goals.clear()
        graphs.clear()
        stateMachines.clear()
        plans.clear()
        snapshot.goals.forEach { goals[it.id] = it }
        snapshot.graphs.forEach { graphs[it.id] = it }
        snapshot.states.forEach { (id, state) ->
            stateMachines[id] = StateMachines.goal().apply { restore(state) }
        }
        snapshot.plans.forEach { (id, verdict) ->
            val goal = goals[id] ?: return@forEach
            val graph = graphs[id] ?: return@forEach
            plans[id] = DesktopPlan(
                goal = goal,
                tasks = graph.tasks,
                feasible = verdict.feasible,
                missingSkills = verdict.missingSkills,
                missingCapabilities = verdict.missingCapabilities,
                warnings = verdict.warnings,
            )
        }
    }

    private fun inferIntent(description: String): String {
        val lower = description.lowercase()
        return DECOMPOSITION_RULES.keys.firstOrNull { lower.contains(it) } ?: "general"
    }
}
