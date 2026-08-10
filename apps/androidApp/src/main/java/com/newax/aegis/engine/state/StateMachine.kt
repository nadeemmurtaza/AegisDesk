package com.newax.aegis.engine.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /**
     * Restore-only: force the machine to a previously saved state without firing
     * transition callbacks or validating edges. The saved state was legal when it
     * was captured; this is hydration, not a transition. Never used for live moves.
     */
    @Synchronized
    fun seed(to: S) {
        _state.value = to
    }
}

enum class IndexerState { IDLE, SCANNING, EXTRACTING_TEXT, EXTRACTING_ENTITIES, VISUAL_INDEXING, PAUSED, FAILED, COMPLETED }
enum class LearnerState { IDLE, LISTENING, ANALYZING, DRAFTING, CONSOLIDATING, PAUSED, ERROR }
enum class AssistantState { IDLE, LISTENING, PROCESSING, RESPONDING, EXECUTING, ERROR }
enum class SchedulerState { IDLE, RUNNING, PAUSED, SHUTDOWN }
enum class ModelState { UNLOADED, LOADING, READY, INFERRING, UNLOADING, ERROR }
enum class WorkflowState { PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
enum class GoalState { OPEN, ACTIVE, BLOCKED, COMPLETED, ABANDONED }

object StateMachines {

    fun indexer(onTransition: ((IndexerState, IndexerState) -> Unit)? = null) =
        StateMachine(
            initial = IndexerState.IDLE,
            transitions = mapOf(
                IndexerState.IDLE          to setOf(IndexerState.SCANNING, IndexerState.PAUSED),
                IndexerState.SCANNING      to setOf(IndexerState.EXTRACTING_TEXT, IndexerState.IDLE, IndexerState.PAUSED, IndexerState.FAILED),
                IndexerState.EXTRACTING_TEXT to setOf(IndexerState.EXTRACTING_ENTITIES, IndexerState.IDLE, IndexerState.PAUSED, IndexerState.FAILED),
                IndexerState.EXTRACTING_ENTITIES to setOf(IndexerState.VISUAL_INDEXING, IndexerState.IDLE, IndexerState.PAUSED, IndexerState.FAILED),
                IndexerState.VISUAL_INDEXING to setOf(IndexerState.COMPLETED, IndexerState.IDLE, IndexerState.PAUSED, IndexerState.FAILED),
                IndexerState.PAUSED        to setOf(IndexerState.IDLE, IndexerState.SCANNING),
                IndexerState.FAILED        to setOf(IndexerState.IDLE),
                IndexerState.COMPLETED     to setOf(IndexerState.IDLE)
            ),
            onTransition = onTransition
        )

    fun learner(onTransition: ((LearnerState, LearnerState) -> Unit)? = null) =
        StateMachine(
            initial = LearnerState.IDLE,
            transitions = mapOf(
                LearnerState.IDLE         to setOf(LearnerState.LISTENING, LearnerState.PAUSED),
                LearnerState.LISTENING    to setOf(LearnerState.ANALYZING, LearnerState.IDLE, LearnerState.PAUSED),
                LearnerState.ANALYZING    to setOf(LearnerState.DRAFTING, LearnerState.IDLE, LearnerState.ERROR),
                LearnerState.DRAFTING     to setOf(LearnerState.CONSOLIDATING, LearnerState.IDLE),
                LearnerState.CONSOLIDATING to setOf(LearnerState.IDLE, LearnerState.ERROR),
                LearnerState.PAUSED       to setOf(LearnerState.IDLE, LearnerState.LISTENING),
                LearnerState.ERROR        to setOf(LearnerState.IDLE)
            ),
            onTransition = onTransition
        )

    fun assistant(onTransition: ((AssistantState, AssistantState) -> Unit)? = null) =
        StateMachine(
            initial = AssistantState.IDLE,
            transitions = mapOf(
                AssistantState.IDLE       to setOf(AssistantState.LISTENING, AssistantState.PROCESSING),
                AssistantState.LISTENING  to setOf(AssistantState.PROCESSING, AssistantState.IDLE),
                AssistantState.PROCESSING to setOf(AssistantState.RESPONDING, AssistantState.EXECUTING, AssistantState.ERROR, AssistantState.IDLE),
                AssistantState.RESPONDING to setOf(AssistantState.IDLE, AssistantState.EXECUTING),
                AssistantState.EXECUTING  to setOf(AssistantState.RESPONDING, AssistantState.IDLE, AssistantState.ERROR),
                AssistantState.ERROR      to setOf(AssistantState.IDLE)
            ),
            onTransition = onTransition
        )

    fun model(onTransition: ((ModelState, ModelState) -> Unit)? = null) =
        StateMachine(
            initial = ModelState.UNLOADED,
            transitions = mapOf(
                ModelState.UNLOADED   to setOf(ModelState.LOADING),
                ModelState.LOADING    to setOf(ModelState.READY, ModelState.ERROR),
                ModelState.READY      to setOf(ModelState.INFERRING, ModelState.UNLOADING),
                ModelState.INFERRING  to setOf(ModelState.READY, ModelState.ERROR),
                ModelState.UNLOADING  to setOf(ModelState.UNLOADED),
                ModelState.ERROR      to setOf(ModelState.UNLOADED, ModelState.LOADING)
            ),
            onTransition = onTransition
        )

    fun workflow(onTransition: ((WorkflowState, WorkflowState) -> Unit)? = null) =
        StateMachine(
            initial = WorkflowState.PENDING,
            transitions = mapOf(
                WorkflowState.PENDING   to setOf(WorkflowState.RUNNING, WorkflowState.CANCELLED),
                WorkflowState.RUNNING   to setOf(WorkflowState.PAUSED, WorkflowState.COMPLETED, WorkflowState.FAILED, WorkflowState.CANCELLED),
                WorkflowState.PAUSED    to setOf(WorkflowState.RUNNING, WorkflowState.CANCELLED),
                WorkflowState.COMPLETED to emptySet(),
                WorkflowState.FAILED    to setOf(WorkflowState.PENDING),
                WorkflowState.CANCELLED to emptySet()
            ),
            onTransition = onTransition
        )

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
