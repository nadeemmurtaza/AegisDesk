package com.newax.aegis.engine.registry

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class WorkflowStep(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val skillId: String,
    val inputs: Map<String, Any> = emptyMap(),
    val outputKey: String? = null,
    val condition: String? = null,
    val retryCount: Int = 0
)

data class WorkflowDefinition(
    val id: String,
    val name: String,
    val description: String,
    val steps: List<WorkflowStep>,
    val category: String = "general",
    val tags: List<String> = emptyList(),
    val version: Int = 1,
    val createdMs: Long = System.currentTimeMillis(),
    val updatedMs: Long = System.currentTimeMillis()
)

data class WorkflowRun(
    val id: String = UUID.randomUUID().toString(),
    val workflowId: String,
    val status: String = "running",
    val currentStepIndex: Int = 0,
    val stepResults: Map<String, Any> = emptyMap(),
    val startedMs: Long = System.currentTimeMillis(),
    val completedMs: Long? = null,
    val error: String? = null
)

object WorkflowRegistry {

    private val workflows = ConcurrentHashMap<String, WorkflowDefinition>()
    private val runs = ConcurrentHashMap<String, WorkflowRun>()

    init {
        registerBuiltins()
    }

    fun register(workflow: WorkflowDefinition) {
        workflows[workflow.id] = workflow
    }

    fun unregister(id: String) = workflows.remove(id)

    fun get(id: String): WorkflowDefinition? = workflows[id]

    fun findByName(name: String): WorkflowDefinition? =
        workflows.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun byCategory(category: String): List<WorkflowDefinition> =
        workflows.values.filter { it.category == category }

    fun byTag(tag: String): List<WorkflowDefinition> =
        workflows.values.filter { tag in it.tags }

    fun all(): List<WorkflowDefinition> = workflows.values.sortedBy { it.name }

    fun search(query: String): List<WorkflowDefinition> {
        val lower = query.lowercase()
        return workflows.values.filter {
            it.name.lowercase().contains(lower) || it.description.lowercase().contains(lower)
        }
    }

    fun startRun(workflowId: String): WorkflowRun {
        val run = WorkflowRun(workflowId = workflowId)
        runs[run.id] = run
        return run
    }

    fun updateRun(runId: String, block: WorkflowRun.() -> WorkflowRun): WorkflowRun? {
        val run = runs[runId] ?: return null
        val updated = run.block()
        runs[runId] = updated
        return updated
    }

    fun getRun(runId: String): WorkflowRun? = runs[runId]
    fun activeRuns(): List<WorkflowRun> = runs.values.filter { it.status == "running" }
    fun runsFor(workflowId: String): List<WorkflowRun> = runs.values.filter { it.workflowId == workflowId }

    private fun registerBuiltins() {
        register(WorkflowDefinition(
            id = "send_file_to_person",
            name = "Send File to Person",
            description = "Find a file and send it to a contact via their preferred app",
            category = "communication",
            tags = listOf("file", "share", "contact"),
            steps = listOf(
                WorkflowStep("s1", "Find File", "find_file", outputKey = "file"),
                WorkflowStep("s2", "Find Contact", "find_contact", outputKey = "contact"),
                WorkflowStep("s3", "Share File", "share_file")
            )
        ))
        register(WorkflowDefinition(
            id = "backup_important_files",
            name = "Backup Important Files",
            description = "Find high-importance files and back them up",
            category = "files",
            tags = listOf("backup", "file"),
            steps = listOf(
                WorkflowStep("s1", "Scan Filesystem", "scan_filesystem"),
                WorkflowStep("s2", "Identify Important", "execute_search", inputs = mapOf("filter" to "high_importance")),
                WorkflowStep("s3", "Backup", "backup_files")
            )
        ))
        register(WorkflowDefinition(
            id = "morning_briefing",
            name = "Morning Briefing",
            description = "Summarize calendar, messages, and tasks for the day",
            category = "productivity",
            tags = listOf("calendar", "briefing", "daily"),
            steps = listOf(
                WorkflowStep("s1", "Get Calendar Events", "get_calendar_events"),
                WorkflowStep("s2", "Get Pending Tasks", "get_pending_tasks"),
                WorkflowStep("s3", "Get Unread Messages", "get_unread_messages"),
                WorkflowStep("s4", "Generate Summary", "generate_summary")
            )
        ))
    }
}
