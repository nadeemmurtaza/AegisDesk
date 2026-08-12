package com.newax.aegis.engine.registry

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Task(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val goalId: String? = null,
    val priority: Int = 5,
    val dueMs: Long? = null,
    val tags: List<String> = emptyList(),
    val status: TaskStatus = TaskStatus.PENDING,
    val assignedSkillId: String? = null,
    val result: String? = null,
    val createdMs: Long = System.currentTimeMillis(),
    val startedMs: Long? = null,
    val completedMs: Long? = null,
    val estimatedMs: Long = 0L
)

enum class TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, BLOCKED }

object TaskRegistry {

    private val tasks = ConcurrentHashMap<String, Task>()

    fun add(task: Task): Task {
        tasks[task.id] = task
        return task
    }

    fun create(
        title: String,
        description: String = "",
        goalId: String? = null,
        priority: Int = 5,
        dueMs: Long? = null,
        tags: List<String> = emptyList(),
        skillId: String? = null
    ): Task {
        val task = Task(
            title = title,
            description = description,
            goalId = goalId,
            priority = priority,
            dueMs = dueMs,
            tags = tags,
            assignedSkillId = skillId
        )
        tasks[task.id] = task
        return task
    }

    fun get(id: String): Task? = tasks[id]

    fun update(id: String, block: Task.() -> Task): Task? {
        val existing = tasks[id] ?: return null
        val updated = existing.block()
        tasks[id] = updated
        return updated
    }

    fun start(id: String): Task? = update(id) {
        copy(status = TaskStatus.RUNNING, startedMs = System.currentTimeMillis())
    }

    fun complete(id: String, result: String? = null): Task? = update(id) {
        copy(status = TaskStatus.COMPLETED, result = result, completedMs = System.currentTimeMillis())
    }

    fun fail(id: String, reason: String? = null): Task? = update(id) {
        copy(status = TaskStatus.FAILED, result = reason, completedMs = System.currentTimeMillis())
    }

    fun cancel(id: String): Task? = update(id) {
        copy(status = TaskStatus.CANCELLED, completedMs = System.currentTimeMillis())
    }

    fun block(id: String): Task? = update(id) { copy(status = TaskStatus.BLOCKED) }

    fun remove(id: String) = tasks.remove(id)

    fun pending(): List<Task> = tasks.values
        .filter { it.status == TaskStatus.PENDING }
        .sortedByDescending { it.priority }

    fun running(): List<Task> = tasks.values.filter { it.status == TaskStatus.RUNNING }

    fun byGoal(goalId: String): List<Task> = tasks.values.filter { it.goalId == goalId }

    fun byTag(tag: String): List<Task> = tasks.values.filter { tag in it.tags }

    fun overdue(): List<Task> {
        val now = System.currentTimeMillis()
        return tasks.values.filter {
            it.dueMs != null && it.dueMs < now &&
            it.status !in listOf(TaskStatus.COMPLETED, TaskStatus.CANCELLED)
        }.sortedBy { it.dueMs }
    }

    fun all(): List<Task> = tasks.values.sortedByDescending { it.createdMs }

    fun count(status: TaskStatus? = null): Int =
        if (status == null) tasks.size
        else tasks.values.count { it.status == status }

    fun clear() = tasks.clear()
}
