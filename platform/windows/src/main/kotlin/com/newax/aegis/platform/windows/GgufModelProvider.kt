package com.newax.aegis.platform.windows

import com.newax.aegis.model.ModelDescriptor
import com.newax.aegis.model.ModelFormat
import com.newax.aegis.model.ModelProvider
import com.newax.aegis.model.ModelRequest
import com.newax.aegis.model.ModelResponse
import com.newax.aegis.model.ModelState
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * The GGUF/llama.cpp desktop platform implementation of the shared [ModelProvider]
 * contract (ARCHITECTURE.md Part 3, Phase 5c). Planner and engine code depend on
 * [ModelProvider], never on this class or the native llama.cpp binding.
 *
 * Lifecycle mapping onto the contract:
 *   NOT_INSTALLED → [load] → LOADING → READY, or ERROR (the failure is rethrown so
 *   callers can report it; the state stays ERROR for UI) → [close] → CLOSED.
 *
 * The [GgufEngine] seam abstracts the underlying llama.cpp binding so the provider's
 * lifecycle and delegation logic are unit-testable without native libraries. The
 * production engine ([KherudGgufEngine]) wraps `de.kherud:java-llama.cpp`.
 *
 * Sampler/max-token parameters on the request are forwarded to the engine when
 * possible; the engine may clamp or ignore values not supported by the loaded model.
 */
class GgufModelProvider internal constructor(
    override val descriptor: ModelDescriptor,
    private val engine: GgufEngine,
) : ModelProvider {

    /** Production constructor: reads the GGUF header for descriptor metadata and
     *  wraps [file] with a [KherudGgufEngine]. */
    constructor(file: File, sha256: String) : this(
        descriptor = GgufHeaderParser.readDescriptor(file, sha256),
        engine     = KherudGgufEngine(file),
    )

    private val _state = MutableStateFlow(ModelState.NOT_INSTALLED)
    override val state: StateFlow<ModelState> = _state.asStateFlow()

    /** Loads the model engine and moves to READY; on failure sets ERROR and rethrows. */
    suspend fun load() {
        _state.value = ModelState.LOADING
        try {
            engine.load()
            _state.value = ModelState.READY
        } catch (error: Throwable) {
            _state.value = ModelState.ERROR
            throw error
        }
    }

    override suspend fun complete(request: ModelRequest): ModelResponse {
        require(request.text.isNotBlank()) { "ModelRequest.text must not be blank" }
        val reply = engine.complete(request.text)
        return ModelResponse(reply)
    }

    override fun stream(request: ModelRequest): Flow<String> {
        require(request.text.isNotBlank()) { "ModelRequest.text must not be blank" }
        // The GC++ engine supports token-level streaming via its Iterable-based
        // generate() API. Each element from the llama.cpp iterator is emitted as
        // a separate chunk.
        return engine.stream(request.text, request.maxTokens, request.temperature)
    }

    override fun cancel() {
        // The kherud binding's generate() call is blocking and not interruptible.
        // cancel() is a documented no-op — the same stance as the Android LiteRT
        // provider. A future cancellation path would need a thread-interrupt or
        // session-abort mechanism in the native binding.
    }

    override fun close() {
        engine.close()
        _state.value = ModelState.CLOSED
    }
}

/**
 * Narrow engine seam so [GgufModelProvider]'s lifecycle and delegation are
 * unit-testable without the native llama.cpp runtime. The production
 * implementation is [KherudGgufEngine]; tests provide a fake.
 */
interface GgufEngine {
    /** Loads the native model into memory so the engine is ready to infer. */
    suspend fun load()

    /** Runs one blocking full-sequence inference. Returns the complete text. */
    suspend fun complete(prompt: String): String

    /** Token-level streaming. Each element emitted by the returned [Flow] is a
     *  single output token from the llama.cpp generation loop. */
    fun stream(prompt: String, maxTokens: Int, temperature: Float): Flow<String>

    /** Releases all native resources; the engine must not be used afterwards. */
    fun close()
}