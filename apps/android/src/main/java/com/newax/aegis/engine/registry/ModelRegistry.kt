package com.newax.aegis.engine.registry

import com.newax.aegis.engine.bus.NewaxEvent
import com.newax.aegis.engine.bus.NewaxEventBus
import com.newax.aegis.engine.state.ModelState
import com.newax.aegis.engine.state.StateMachines
import java.util.concurrent.ConcurrentHashMap

data class ModelDefinition(
    val id: String,
    val name: String,
    val family: String,
    val sizeBytes: Long,
    val contextTokens: Int,
    val quantization: String = "q4",
    val filePath: String = "",
    val capabilities: List<ModelCapability> = emptyList(),
    val languages: List<String> = listOf("en"),
    val speedScore: Float = 0.5f,
    val qualityScore: Float = 0.5f,
    val ramRequiredMb: Int = 0
)

enum class ModelCapability {
    TEXT_GENERATION, SUMMARIZATION, EXTRACTION, CLASSIFICATION,
    INSTRUCTION_FOLLOWING, CONVERSATION, CODE, REASONING
}

data class ModelStats(
    val modelId: String,
    val totalInferences: Long = 0,
    val avgLatencyMs: Long = 0,
    val avgTokensPerSec: Float = 0f,
    val errorCount: Long = 0,
    val lastUsedMs: Long = 0
)

object ModelRegistry {

    private val models = ConcurrentHashMap<String, ModelDefinition>()
    private val stats = ConcurrentHashMap<String, ModelStats>()
    private val stateMachines = ConcurrentHashMap<String, com.newax.aegis.engine.state.StateMachine<ModelState>>()

    fun register(model: ModelDefinition) {
        models[model.id] = model
        stats[model.id] = ModelStats(model.id)
        stateMachines[model.id] = StateMachines.model { from, to ->
            when (to) {
                ModelState.READY -> NewaxEventBus.emit(NewaxEvent.ModelLoaded(model.id, model.sizeBytes))
                ModelState.UNLOADED -> NewaxEventBus.emit(NewaxEvent.ModelUnloaded(model.id))
                else -> Unit
            }
        }
    }

    fun unregister(id: String) {
        models.remove(id)
        stats.remove(id)
        stateMachines.remove(id)
    }

    fun get(id: String): ModelDefinition? = models[id]

    fun getState(id: String): ModelState? = stateMachines[id]?.current

    fun transition(id: String, to: ModelState): Boolean =
        stateMachines[id]?.transition(to) ?: false

    fun ready(): List<ModelDefinition> = models.values
        .filter { stateMachines[it.id]?.current == ModelState.READY }

    fun loaded(): List<ModelDefinition> = models.values
        .filter { stateMachines[it.id]?.current in setOf(ModelState.READY, ModelState.INFERRING) }

    fun all(): List<ModelDefinition> = models.values.toList()

    fun byCapability(capability: ModelCapability): List<ModelDefinition> =
        models.values.filter { capability in it.capabilities }

    fun bestFor(capability: ModelCapability, maxRamMb: Int = Int.MAX_VALUE): ModelDefinition? =
        models.values
            .filter { capability in it.capabilities && it.ramRequiredMb <= maxRamMb }
            .maxByOrNull { it.qualityScore * 0.7f + it.speedScore * 0.3f }

    fun recordInference(modelId: String, latencyMs: Long, tokensPerSec: Float, error: Boolean = false) {
        val s = stats.getOrPut(modelId) { ModelStats(modelId) }
        val n = s.totalInferences + 1
        val newAvgLatency = (s.avgLatencyMs * s.totalInferences + latencyMs) / n
        val newAvgTPS = (s.avgTokensPerSec * s.totalInferences + tokensPerSec) / n
        stats[modelId] = s.copy(
            totalInferences = n,
            avgLatencyMs = newAvgLatency,
            avgTokensPerSec = newAvgTPS,
            errorCount = if (error) s.errorCount + 1 else s.errorCount,
            lastUsedMs = System.currentTimeMillis()
        )
    }

    fun statsFor(modelId: String): ModelStats? = stats[modelId]
}
