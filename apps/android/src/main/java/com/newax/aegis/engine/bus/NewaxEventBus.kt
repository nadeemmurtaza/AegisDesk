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

sealed class NewaxEvent {
    data class AppOpened(val packageName: String, val activityName: String) : NewaxEvent()
    data class AppClosed(val packageName: String) : NewaxEvent()
    data class NotificationReceived(val packageName: String, val sender: String, val text: String) : NewaxEvent()
    data class ScreenChanged(val packageName: String, val windowTitle: String) : NewaxEvent()
    data class UserQuery(val text: String, val sessionId: String) : NewaxEvent()
    data class AssistantResponse(val text: String, val sessionId: String, val latencyMs: Long) : NewaxEvent()
    data class FileIndexed(val fileId: Long, val path: String, val stage: String) : NewaxEvent()
    data class PersonMerged(val personId: Long, val name: String) : NewaxEvent()
    data class FactLearned(val subject: String, val predicate: String, val obj: String, val confidence: Float) : NewaxEvent()
    data class TriggerFired(val ruleId: Long, val label: String, val actionType: String) : NewaxEvent()
    data class ProcedureExecuted(val procedureId: Long, val success: Boolean, val stepsCompleted: Int) : NewaxEvent()
    data class SkillInvoked(val skillId: String, val success: Boolean, val durationMs: Long) : NewaxEvent()
    data class GoalCreated(val goalId: String, val description: String) : NewaxEvent()
    data class GoalCompleted(val goalId: String) : NewaxEvent()
    /** Emitted by GoalExecutor as each plan task flips state (status = TaskStatus.name). */
    data class TaskUpdated(val goalId: String, val taskId: String, val status: String, val message: String?) : NewaxEvent()
    /** Emitted when a goal run stops on a blocker (unready capability, missing skill, task failure). */
    data class GoalBlocked(val goalId: String, val reason: String) : NewaxEvent()
    data class ErrorOccurred(val module: String, val message: String, val throwable: Throwable? = null) : NewaxEvent()
    data class PermissionGranted(val permission: String) : NewaxEvent()
    data class PermissionDenied(val permission: String) : NewaxEvent()
    data class MemoryConsolidated(val count: Int, val durationMs: Long) : NewaxEvent()
    data class SnapshotCompiled(val entityType: String, val entityId: String) : NewaxEvent()
    data class IndexingStarted(val stage: String) : NewaxEvent()
    data class IndexingComplete(val stage: String, val count: Int, val durationMs: Long) : NewaxEvent()
    data class DeviceConnected(val deviceId: String, val type: String) : NewaxEvent()
    data class DeviceDisconnected(val deviceId: String) : NewaxEvent()
    data class ModelLoaded(val modelId: String, val sizeBytes: Long) : NewaxEvent()
    data class ModelUnloaded(val modelId: String) : NewaxEvent()
    data class WorkflowStarted(val workflowId: String, val name: String) : NewaxEvent()
    data class WorkflowCompleted(val workflowId: String, val success: Boolean) : NewaxEvent()
    data class HabitDetected(val packageName: String, val pattern: String, val confidence: Float) : NewaxEvent()
    data class FailureRecorded(val module: String, val operationId: String, val reason: String) : NewaxEvent()
    data class CacheEvicted(val scope: String, val count: Int) : NewaxEvent()
    data class Custom(val tag: String, val payload: Map<String, Any> = emptyMap()) : NewaxEvent()
}

object NewaxEventBus {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _flow = MutableSharedFlow<NewaxEvent>(replay = 64, extraBufferCapacity = 256)
    val flow: SharedFlow<NewaxEvent> = _flow.asSharedFlow()

    fun emit(event: NewaxEvent) {
        scope.launch { _flow.emit(event) }
    }

    suspend fun emitSuspend(event: NewaxEvent) = _flow.emit(event)

    inline fun <reified T : NewaxEvent> on(
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
        handler: suspend (NewaxEvent.Custom) -> Unit
    ) {
        scope.launch {
            flow.filterIsInstance<NewaxEvent.Custom>()
                .filter { it.tag == tag }
                .collect { handler(it) }
        }
    }

    fun recent(n: Int = 64): List<NewaxEvent> = _flow.replayCache.takeLast(n)
}
