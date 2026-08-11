package com.newax.aegis.engine.intelligence

import com.newax.aegis.PlatformCapabilitiesHolder
import com.newax.aegis.PolicyHolder
import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.authority.PolicyEvaluation
import com.newax.aegis.engine.bus.AegisEvent
import com.newax.aegis.engine.bus.AegisEventBus
import com.newax.aegis.engine.state.GoalState
import com.newax.aegis.engine.state.StateMachines
import com.newax.aegis.platform.CapabilityResolution
import com.newax.aegis.platform.CapabilityResolver
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Goal(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val intent: String,
    val subGoals: List<String> = emptyList(),
    val requiredSkills: List<String> = emptyList(),
    val priority: Int = 5,
    val createdMs: Long = System.currentTimeMillis(),
    val deadlineMs: Long? = null,
    val tags: List<String> = emptyList()
)

data class TaskNode(
    val id: String = UUID.randomUUID().toString(),
    val goalId: String,
    val description: String,
    val skillId: String?,
    val dependencies: List<String> = emptyList(),
    val estimatedMs: Long = 0L,
    var status: TaskStatus = TaskStatus.PENDING,
    var result: String? = null,
    var startedMs: Long? = null,
    var completedMs: Long? = null,
    var failureKind: TaskFailureKind? = null
)

enum class TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

/**
 * Why a task failed, when the executor can classify it. Drives distinct UI
 * treatment (a policy-blocked task gets its own affordance to fix the mode):
 * [POLICY] — the authority spine refused autonomous execution (rule 10);
 * [CAPABILITY] — no registered capability was ready to back the task's skill.
 * Null means a generic failure (missing skill, handler exception, launch error).
 */
enum class TaskFailureKind { POLICY, CAPABILITY }

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

data class PlanResult(
    val goal: Goal,
    val graph: TaskGraph,
    val feasible: Boolean,
    val missingSkills: List<String>,
    /** Skill capability names that are platform-gated but no registered capability is ready for. */
    val missingCapabilities: List<String> = emptyList(),
    val warnings: List<String>
)

/**
 * A point-in-time capture of one goal's full planner state — the goal itself, its
 * task graph (with live task statuses/results), its goal-level state, and the plan
 * pre-flight it was planned with. This is the unit of persistence: snapshots are
 * encoded to JSON (org.json) and stored in the existing kv_store table, then
 * rehydrated through [GoalPlanner.restore] so goals survive process death.
 */
data class GoalSnapshot(
    val goal: Goal,
    val graph: TaskGraph,
    val state: GoalState,
    val plan: PlanResult?
)

/**
 * Track A7 — plan-time policy pre-flight. Evaluates every privileged task's
 * representative action through the policy spine as AGENT origin — the same
 * evaluation the executor performs at run time (rule 10). A task whose decision
 * is not AUTO_EXECUTE will be refused when the goal runs, so the user is warned
 * at planning time instead of discovering the refusal after activation. Pure:
 * the evaluator is injected so this is testable without the Android holder.
 */
internal fun policyPreflightWarnings(
    tasks: List<TaskNode>,
    evaluateAsAgent: (ProposedAction) -> PolicyEvaluation?
): List<String> = tasks.mapNotNull { task ->
    val skillId = task.skillId ?: return@mapNotNull null
    val action = SkillRegistry.policyActionFor(skillId) ?: return@mapNotNull null
    val evaluation = evaluateAsAgent(action) ?: return@mapNotNull null
    if (evaluation.decision.allowsAutonomousExecution) null
    else "Task '${task.description}' will be refused autonomously — policy ${evaluation.decision.name} " +
        "(${evaluation.reason}). Change its mode in Capabilities → Policy modes to let it run."
}

object GoalPlanner {

    private val goals = ConcurrentHashMap<String, Goal>()
    private val graphs = ConcurrentHashMap<String, TaskGraph>()
    private val stateMachines = ConcurrentHashMap<String, com.newax.aegis.engine.state.StateMachine<GoalState>>()
    private val plans = ConcurrentHashMap<String, PlanResult>()

    /**
     * Persistence sink, wired at bootstrap (AegisApplication). Fired after every
     * mutating call so the latest state is durably captured; the Android store
     * encodes the snapshot list to JSON and writes it to the kv_store table.
     */
    @Volatile
    var onChange: ((List<GoalSnapshot>) -> Unit)? = null

    private val DECOMPOSITION_RULES: Map<String, List<String>> = mapOf(
        "send" to listOf("find_recipient", "compose_content", "select_app", "send_message"),
        "open" to listOf("find_app", "launch_app"),
        "search" to listOf("determine_scope", "execute_search", "present_results"),
        "book" to listOf("find_calendar_slot", "create_event", "invite_attendees"),
        "find" to listOf("determine_scope", "execute_search", "filter_results"),
        "play" to listOf("find_media", "launch_player", "play_media"),
        "share" to listOf("find_content", "select_target", "share_content"),
        "summarize" to listOf("gather_content", "analyze_content", "generate_summary"),
        "remind" to listOf("set_reminder_time", "compose_reminder", "schedule_reminder"),
        "call" to listOf("find_contact", "initiate_call"),
        "navigate" to listOf("get_location", "plan_route", "start_navigation"),
        "create" to listOf("gather_inputs", "compose_content", "save_output"),
        "delete" to listOf("find_target", "confirm_deletion", "execute_deletion"),
        "backup" to listOf("identify_data", "compress_data", "store_backup", "verify_backup"),
        "index" to listOf("scan_filesystem", "hash_files", "extract_text", "extract_entities"),
    )

    fun plan(
        description: String,
        intent: String = inferIntent(description),
        priority: Int = 5,
        deadlineMs: Long? = null,
        tags: List<String> = emptyList()
    ): PlanResult {
        val goal = Goal(
            description = description,
            intent = intent,
            priority = priority,
            deadlineMs = deadlineMs,
            tags = tags
        )
        val tasks = decompose(goal)
        val missingSkills = tasks.mapNotNull { it.skillId }
            .filter { !SkillRegistry.has(it) }
        val graph = TaskGraph(goalId = goal.id, tasks = tasks)

        // Capability pre-flight: every task skill declares what it needs (e.g.
        // "OPEN_APP"), and the platform registry is the single source of truth for
        // whether any registered capability can back it right now (CapabilityResolver
        // in the contract module — skills resolve through CapabilityIds, never ad-hoc
        // string matching). A skill that exists but cannot run is still infeasible.
        val requiredCapabilities = tasks.mapNotNull { it.skillId }
            .mapNotNull { SkillRegistry.get(it) }
            .flatMap { it.requiredCapabilities }
            .distinct()
        val registry = PlatformCapabilitiesHolder.registry()
        val resolutions = if (registry == null) {
            // No registry at all = no registered capability = every platform-gated
            // requirement is blocked. The candidates are named so the warning
            // explains what could back it — desktop-parity: a missing registry is
            // an honest "blocked", never a silent "feasible" (A3 named failure
            // mode; unmapped tiers like LLM stay unblocked — not platform-owned).
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
        val capabilityWarnings = resolutions.filter { it.isBlocked }.map { resolution ->
            "Capability '${resolution.requested}' is not ready " +
                "(${resolution.status?.name ?: "no registered capability"}; " +
                "candidates: ${resolution.candidates.joinToString { it.name }})"
        }

        // Policy pre-flight (Track A7): a privileged task the policy spine will
        // refuse autonomously is not a blocker (the user can change the mode, and
        // policy is re-checked live at execution), but the user should know before
        // running. Skipped entirely when the engine isn't initialized yet (tests,
        // early bootstrap) — a missing engine means no warning, never a crash.
        val policyEngine = PolicyHolder.engineOrNull()
        val policyWarnings = if (policyEngine == null) {
            emptyList()
        } else {
            policyPreflightWarnings(tasks) { action -> policyEngine.evaluate(action, ActionOrigin.AGENT) }
        }

        goals[goal.id] = goal
        graphs[goal.id] = graph
        stateMachines[goal.id] = StateMachines.goal()

        AegisEventBus.emit(AegisEvent.GoalCreated(goal.id, description))

        val result = PlanResult(
            goal = goal,
            graph = graph,
            feasible = missingSkills.isEmpty() && missingCapabilities.isEmpty(),
            missingSkills = missingSkills,
            missingCapabilities = missingCapabilities,
            warnings = buildList {
                if (deadlineMs != null && tasks.size > 5)
                    add("Goal has ${tasks.size} tasks, deadline may be tight")
                addAll(capabilityWarnings)
                addAll(policyWarnings)
            }
        )
        plans[goal.id] = result
        notifyChanged()
        return result
    }

    private fun notifyChanged() {
        onChange?.invoke(snapshot())
    }

    private fun decompose(goal: Goal): List<TaskNode> {
        val intent = goal.intent.lowercase()
        val steps = DECOMPOSITION_RULES.entries
            .firstOrNull { (k, _) -> intent.startsWith(k) }?.value
            ?: listOf("analyze_request", "execute_primary_action", "confirm_result")

        val nodes = mutableListOf<TaskNode>()
        steps.forEachIndexed { idx, step ->
            nodes.add(
                TaskNode(
                    goalId = goal.id,
                    description = step.replace('_', ' '),
                    skillId = step,
                    dependencies = if (idx > 0) listOf(nodes[idx - 1].id) else emptyList()
                )
            )
        }
        return nodes
    }

    fun activate(goalId: String): Boolean {
        val sm = stateMachines[goalId] ?: return false
        val ok = sm.transition(GoalState.ACTIVE)
        if (ok) notifyChanged()
        return ok
    }

    fun complete(goalId: String): Boolean {
        val sm = stateMachines[goalId] ?: return false
        val ok = sm.transition(GoalState.COMPLETED)
        if (ok) {
            AegisEventBus.emit(AegisEvent.GoalCompleted(goalId))
            notifyChanged()
        }
        return ok
    }

    fun block(goalId: String): Boolean {
        val ok = stateMachines[goalId]?.transition(GoalState.BLOCKED) ?: false
        if (ok) notifyChanged()
        return ok
    }

    fun abandon(goalId: String): Boolean {
        val ok = stateMachines[goalId]?.transition(GoalState.ABANDONED) ?: false
        if (ok) notifyChanged()
        return ok
    }

    fun updateTask(
        goalId: String,
        taskId: String,
        status: TaskStatus,
        result: String? = null,
        failureKind: TaskFailureKind? = null
    ) {
        graphs[goalId]?.tasks?.find { it.id == taskId }?.let { task ->
            task.status = status
            task.result = result
            if (status == TaskStatus.RUNNING) task.startedMs = System.currentTimeMillis()
            if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED)
                task.completedMs = System.currentTimeMillis()
            // A retry clears the stale classification: once a task runs or completes
            // again, an old policy/capability chip must not linger on the screen.
            if (status == TaskStatus.RUNNING || status == TaskStatus.COMPLETED) task.failureKind = null
            if (status == TaskStatus.FAILED) task.failureKind = failureKind
        }
        graphs[goalId]?.let { if (it.isComplete()) complete(goalId) }
        notifyChanged()
    }

    /**
     * Full-state capture for persistence: one snapshot per known goal, in map order.
     * Pure — never emits events and never mutates.
     */
    fun snapshot(): List<GoalSnapshot> = goals.values.map { goal ->
        GoalSnapshot(
            goal = goal,
            graph = graphs[goal.id] ?: TaskGraph(goalId = goal.id, tasks = emptyList()),
            state = stateMachines[goal.id]?.current ?: GoalState.OPEN,
            plan = plans[goal.id]
        )
    }

    /**
     * Rehydrate planner state from previously captured snapshots (process restart).
     *
     * Semantics that matter: a task left RUNNING when the process died was never
     * completed, so it reverts to PENDING (a re-run of the goal picks it up — the
     * executor only touches PENDING/FAILED tasks and re-checks capability/policy
     * gates live). Goal-level states (ACTIVE/BLOCKED/COMPLETED/ABANDONED) are
     * seeded exactly as saved, so a BLOCKED goal stays blocked until re-run and a
     * COMPLETED goal stays complete. No events are emitted on restore.
     */
    fun restore(snapshots: List<GoalSnapshot>) {
        snapshots.forEach { s ->
            val goal = s.goal
            val graph = s.graph.let { g ->
                TaskGraph(
                    goalId = g.goalId,
                    tasks = g.tasks.map { task ->
                        if (task.status == TaskStatus.RUNNING) {
                            task.copy(status = TaskStatus.PENDING, startedMs = null, result = null)
                        } else {
                            task
                        }
                    },
                    createdMs = g.createdMs
                )
            }
            goals[goal.id] = goal
            graphs[goal.id] = graph
            s.plan?.let { plans[goal.id] = it }
            val machine = StateMachines.goal()
            machine.seed(s.state)
            stateMachines[goal.id] = machine
        }
    }

    fun getGoal(id: String): Goal? = goals[id]
    fun getGraph(id: String): TaskGraph? = graphs[id]
    fun getState(id: String): GoalState? = stateMachines[id]?.current

    /** The plan pre-flight for a goal: feasibility, missing skills, missing capabilities, warnings. */
    fun planOf(id: String): PlanResult? = plans[id]

    fun activeGoals(): List<Goal> = goals.values
        .filter { stateMachines[it.id]?.current == GoalState.ACTIVE }
        .sortedByDescending { it.priority }

    fun allGoals(): List<Goal> = goals.values.toList()

    private fun inferIntent(description: String): String {
        val lower = description.lowercase()
        return DECOMPOSITION_RULES.keys.firstOrNull { lower.contains(it) } ?: "general"
    }

    private val INTENT_KEYWORDS = listOf(
        "send", "open", "search", "book", "find", "play", "share",
        "summarize", "remind", "call", "navigate", "create", "delete", "backup", "index"
    )
}
