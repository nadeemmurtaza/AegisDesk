package com.newax.aegis.engine.intelligence

import com.newax.aegis.engine.bus.AegisEvent
import com.newax.aegis.engine.bus.AegisEventBus
import com.newax.aegis.engine.state.GoalState
import com.newax.aegis.engine.state.StateMachines
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
    var completedMs: Long? = null
)

enum class TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

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
    val warnings: List<String>
)

object GoalPlanner {

    private val goals = ConcurrentHashMap<String, Goal>()
    private val graphs = ConcurrentHashMap<String, TaskGraph>()
    private val stateMachines = ConcurrentHashMap<String, com.newax.aegis.engine.state.StateMachine<GoalState>>()

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

        goals[goal.id] = goal
        graphs[goal.id] = graph
        stateMachines[goal.id] = StateMachines.goal()

        AegisEventBus.emit(AegisEvent.GoalCreated(goal.id, description))

        return PlanResult(
            goal = goal,
            graph = graph,
            feasible = missingSkills.isEmpty(),
            missingSkills = missingSkills,
            warnings = if (deadlineMs != null && tasks.size > 5)
                listOf("Goal has ${tasks.size} tasks, deadline may be tight") else emptyList()
        )
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
        return sm.transition(GoalState.ACTIVE)
    }

    fun complete(goalId: String): Boolean {
        val sm = stateMachines[goalId] ?: return false
        val ok = sm.transition(GoalState.COMPLETED)
        if (ok) AegisEventBus.emit(AegisEvent.GoalCompleted(goalId))
        return ok
    }

    fun block(goalId: String) = stateMachines[goalId]?.transition(GoalState.BLOCKED)
    fun abandon(goalId: String) = stateMachines[goalId]?.transition(GoalState.ABANDONED)

    fun updateTask(goalId: String, taskId: String, status: TaskStatus, result: String? = null) {
        graphs[goalId]?.tasks?.find { it.id == taskId }?.let { task ->
            task.status = status
            task.result = result
            if (status == TaskStatus.RUNNING) task.startedMs = System.currentTimeMillis()
            if (status == TaskStatus.COMPLETED || status == TaskStatus.FAILED)
                task.completedMs = System.currentTimeMillis()
        }
        graphs[goalId]?.let { if (it.isComplete()) complete(goalId) }
    }

    fun getGoal(id: String): Goal? = goals[id]
    fun getGraph(id: String): TaskGraph? = graphs[id]
    fun getState(id: String): GoalState? = stateMachines[id]?.current

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
