package com.newax.aegis.desktop.planner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal generic state machine with a validated transition table — a faithful
 * desktop port of Android's `engine/state/StateMachine.kt`. Unknown transitions
 * return false instead of silently moving, so goal/task lifecycle mistakes are
 * loud (AGENTS.md R9: name the failure modes).
 */
class StateMachine<S : Enum<S>>(
    initial: S,
    private val transitions: Map<S, Set<S>>,
    private val onTransition: ((from: S, to: S) -> Unit)? = null
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()
    val current: S get() = _state.value

    @Synchronized
    fun transition(to: S): Boolean {
        val allowed = transitions[_state.value] ?: return false
        if (to !in allowed) return false
        val from = _state.value
        _state.value = to
        onTransition?.invoke(from, to)
        return true
    }

    fun canTransition(to: S): Boolean = to in (transitions[_state.value] ?: emptySet())

    fun allowedTransitions(): Set<S> = transitions[_state.value] ?: emptySet()
}

/**
 * Goal lifecycle — identical to Android's `GoalState`. Initial state is OPEN
 * (a planned goal is not active until the user activates it); COMPLETED and
 * ABANDONED are terminal; BLOCKED can be re-activated once the blocker clears.
 */
enum class GoalState { OPEN, ACTIVE, BLOCKED, COMPLETED, ABANDONED }

object StateMachines {

    /**
     * Goal state machine — same transition table as Android's `StateMachines.goal()`:
     *   OPEN      → {ACTIVE, ABANDONED}
     *   ACTIVE    → {BLOCKED, COMPLETED, ABANDONED}
     *   BLOCKED   → {ACTIVE, ABANDONED}
     *   COMPLETED → {}      (terminal)
     *   ABANDONED → {}      (terminal)
     */
    fun goal(onTransition: ((GoalState, GoalState) -> Unit)? = null) =
        StateMachine(
            initial = GoalState.OPEN,
            transitions = mapOf(
                GoalState.OPEN      to setOf(GoalState.ACTIVE, GoalState.ABANDONED),
                GoalState.ACTIVE    to setOf(GoalState.BLOCKED, GoalState.COMPLETED, GoalState.ABANDONED),
                GoalState.BLOCKED   to setOf(GoalState.ACTIVE, GoalState.ABANDONED),
                GoalState.COMPLETED to emptySet(),
                GoalState.ABANDONED to emptySet()
            ),
            onTransition = onTransition
        )
}
