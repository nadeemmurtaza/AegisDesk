package com.newax.aegis.engine.registry

import com.newax.aegis.engine.intelligence.Goal
import com.newax.aegis.engine.intelligence.GoalPlanner
import com.newax.aegis.engine.state.GoalState as GoalStateEnum
import java.util.concurrent.ConcurrentHashMap

object GoalRegistry {

    private val goals = ConcurrentHashMap<String, Goal>()
    private val tags = ConcurrentHashMap<String, MutableSet<String>>()

    fun add(goal: Goal) {
        goals[goal.id] = goal
        goal.tags.forEach { tag -> tags.getOrPut(tag) { mutableSetOf() }.add(goal.id) }
        GoalPlanner.plan(goal.description, goal.intent, goal.priority, goal.deadlineMs, goal.tags)
    }

    fun get(id: String): Goal? = goals[id]

    fun remove(id: String) {
        val goal = goals.remove(id) ?: return
        goal.tags.forEach { tag -> tags[tag]?.remove(id) }
    }

    fun byTag(tag: String): List<Goal> =
        tags[tag]?.mapNotNull { goals[it] } ?: emptyList()

    fun active(): List<Goal> = GoalPlanner.activeGoals()

    fun all(): List<Goal> = goals.values.toList()

    fun highPriority(minPriority: Int = 7): List<Goal> =
        goals.values.filter { it.priority >= minPriority }
            .sortedByDescending { it.priority }

    fun withDeadline(): List<Goal> =
        goals.values.filter { it.deadlineMs != null }
            .sortedBy { it.deadlineMs }

    fun overdue(): List<Goal> {
        val now = System.currentTimeMillis()
        return goals.values.filter { g ->
            g.deadlineMs != null && g.deadlineMs < now &&
            GoalPlanner.getState(g.id) !in listOf(GoalStateEnum.COMPLETED, GoalStateEnum.ABANDONED)
        }
    }

    fun count(): Int = goals.size

    fun clear() {
        goals.clear()
        tags.clear()
    }
}
