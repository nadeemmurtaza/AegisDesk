package com.newax.aegis.engine.bus

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch

sealed class AegisEvent {
    data class AppOpened(val packageName: String, val activityName: String) : AegisEvent()
    data class AppClosed(val packageName: String) : AegisEvent()
    data class NotificationReceived(val packageName: String, val sender: String, val text: String) : AegisEvent()
    data class ScreenChanged(val packageName: String, val windowTitle: String) : AegisEvent()
    data class UserQuery(val text: String, val sessionId: String) : AegisEvent()
    data class AssistantResponse(val text: String, val sessionId: String, val latencyMs: Long) : AegisEvent()
    data class FileIndexed(val fileId: Long, val path: String, val stage: String) : AegisEvent()
    data class PersonMerged(val personId: Long, val name: String) : AegisEvent()
    data class FactLearned(val subject: String, val predicate: String, val obj: String, val confidence: Float) : AegisEvent()
    data class TriggerFired(val ruleId: Long, val label: String, val actionType: String) : AegisEvent()
    data class ProcedureExecuted(val procedureId: Long, val success: Boolean, val stepsCompleted: Int) : AegisEvent()
    data class SkillInvoked(val skillId: String, val success: Boolean, val durationMs: Long) : AegisEvent()
    data class GoalCreated(val goalId: String, val description: String) : AegisEvent()
    data class GoalCompleted(val goalId: String) : AegisEvent()
    /** Emitted by GoalExecutor as each plan task flips state (status = TaskStatus.name). */
    data class TaskUpdated(val goalId: String, val taskId: String, val status: String, val message: String?) : AegisEvent()
    /** Emitted when a goal run stops on a blocker (unready capability, missing skill, task failure). */
    data class GoalBlocked(val goalId: String, val reason: String) : AegisEvent()
    data class ErrorOccurred(val module: String, val message: String, val throwable: Throwable? = null) : AegisEvent()
    data class PermissionGranted(val permission: String) : AegisEvent()
    data class PermissionDenied(val permission: String) : AegisEvent()
    data class MemoryConsolidated(val count: Int, val durationMs: Long) : AegisEvent()
    data class SnapshotCompiled(val entityType: String, val entityId: String) : AegisEvent()
    data class IndexingStarted(val stage: String) : AegisEvent()
    data class IndexingComplete(val stage: String, val count: Int, val durationMs: Long) : AegisEvent()
    data class DeviceConnected(val deviceId: String, val type: String) : AegisEvent()
    data class DeviceDisconnected(val deviceId: String) : AegisEvent()
    data class ModelLoaded(val modelId: String, val sizeBytes: Long) : AegisEvent()
    data class ModelUnloaded(val modelId: String) : AegisEvent()
    data class WorkflowStarted(val workflowId: String, val name: String) : AegisEvent()
    data class WorkflowCompleted(val workflowId: String, val success: Boolean) : AegisEvent()
    data class HabitDetected(val packageName: String, val pattern: String, val confidence: Float) : AegisEvent()
    data class FailureRecorded(val module: String, val operationId: String, val reason: String) : AegisEvent()
    data class CacheEvicted(val scope: String, val count: Int) : AegisEvent()
    data class Custom(val tag: String, val payload: Map<String, Any> = emptyMap()) : AegisEvent()
}

object AegisEventBus {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _flow = MutableSharedFlow<AegisEvent>(replay = 64, extraBufferCapacity = 256)
    val flow: SharedFlow<AegisEvent> = _flow.asSharedFlow()

    fun emit(event: AegisEvent) {
        scope.launch { _flow.emit(event) }
    }

    suspend fun emitSuspend(event: AegisEvent) = _flow.emit(event)

    inline fun <reified T : AegisEvent> on(
        scope: CoroutineScope,
        crossinline handler: suspend (T) -> Unit
    ) {
        scope.launch {
            flow.filterIsInstance<T>().collect { handler(it) }
        }
    }

    fun onTagged(
        scope: CoroutineScope,
        tag: String,
        handler: suspend (AegisEvent.Custom) -> Unit
    ) {
        scope.launch {
            flow.filterIsInstance<AegisEvent.Custom>()
                .filter { it.tag == tag }
                .collect { handler(it) }
        }
    }

    fun recent(n: Int = 64): List<AegisEvent> = _flow.replayCache.takeLast(n)
}
