package com.newax.aegis.engine.model

import java.util.concurrent.ConcurrentHashMap

enum class ModelId {
    GEMMA, EMBEDDING, STT, TTS, VISION, OCR
}

enum class ModelState {
    UNLOADED, LOAD_REQUESTED, WARM, ACTIVE, IDLE, UNLOAD_REQUESTED
}

enum class ModelBackend {
    LITERT_CPU, LITERT_GPU, LITERT_NNAPI, ONNX_CPU, NATIVE
}

data class ModelEntry(
    val id: ModelId,
    val displayName: String,
    val filePath: String?,
    val sha256: String = "",
    val trusted: Boolean = false,
    val estimatedRamMb: Int,
    val backend: ModelBackend = ModelBackend.LITERT_CPU,
    val state: ModelState = ModelState.UNLOADED,
    val lastUsedMs: Long = 0,
    val benchmarkTokensPerSec: Float = 0f
)

object ModelManager {

    private val registry = ConcurrentHashMap<ModelId, ModelEntry>()
    private val stateListeners = ConcurrentHashMap<ModelId, MutableList<(ModelState) -> Unit>>()

    fun register(entry: ModelEntry) {
        registry[entry.id] = entry
    }

    fun state(id: ModelId): ModelState = registry[id]?.state ?: ModelState.UNLOADED

    fun isReady(id: ModelId): Boolean = state(id) == ModelState.ACTIVE || state(id) == ModelState.WARM

    fun requestLoad(id: ModelId) = transition(id, ModelState.LOAD_REQUESTED)
    fun markWarm(id: ModelId)    = transition(id, ModelState.WARM)
    fun markActive(id: ModelId)  = transition(id, ModelState.ACTIVE).also { touch(id) }
    fun markIdle(id: ModelId)    = transition(id, ModelState.IDLE)
    fun requestUnload(id: ModelId) = transition(id, ModelState.UNLOAD_REQUESTED)
    fun markUnloaded(id: ModelId)  = transition(id, ModelState.UNLOADED)

    fun unloadForPressure(pressureLevel: Int) {
        when {
            pressureLevel >= 5 -> {
                ModelId.values().filter { state(it) != ModelState.UNLOADED }
                    .forEach { markUnloaded(it) }
            }
            pressureLevel >= 4 -> {
                markUnloaded(ModelId.GEMMA)
                markUnloaded(ModelId.EMBEDDING)
            }
            pressureLevel >= 3 -> {
                markUnloaded(ModelId.EMBEDDING)
            }
        }
    }

    fun touch(id: ModelId) {
        registry.compute(id) { _, e -> e?.copy(lastUsedMs = System.currentTimeMillis()) }
    }

    fun setBenchmark(id: ModelId, tokensPerSec: Float) {
        registry.compute(id) { _, e -> e?.copy(benchmarkTokensPerSec = tokensPerSec) }
    }

    fun addStateListener(id: ModelId, listener: (ModelState) -> Unit) {
        stateListeners.getOrPut(id) { mutableListOf() }.add(listener)
    }

    fun snapshot(): Map<ModelId, ModelEntry> = registry.toMap()

    private fun transition(id: ModelId, newState: ModelState) {
        registry.compute(id) { _, e -> e?.copy(state = newState) }
        stateListeners[id]?.forEach { it(newState) }
    }
}
