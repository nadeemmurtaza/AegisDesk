package com.newax.aegis.platform.windows

import com.newax.aegis.model.ModelDescriptor
import com.newax.aegis.model.ModelFormat
import com.newax.aegis.model.ModelProvider
import com.newax.aegis.model.ModelRequest
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

    // No `complete` override (T2.5). The interface default collects [stream], so
    // completion and streaming are one path here and cannot answer differently.
    //
    // This also fixes a real defect the override carried: `GgufEngine.complete`
    // hardcodes 512 tokens and temperature 0.7, so every caller's
    // [ModelRequest.maxTokens] and [ModelRequest.temperature] were silently
    // discarded on the completion path while [stream] honoured them. Routing
    // through the stream means the request's sampler settings are now applied.
    //
    // One deliberate behaviour change: the old override trimmed the reply. The
    // collected stream does not, and must not — a streaming UI renders chunks as
    // they arrive and cannot retroactively trim what it already drew, so a
    // trimming `complete()` would disagree with the text the user watched being
    // typed. Trim at the render boundary if it matters, not at one of two paths.

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

    // No `complete` on this seam (T2.5). There is one inference entry point —
    // [stream] — and `ModelProvider.complete` is that stream collected. A second
    // full-sequence method here is what let the two drift: the old one pinned
    // 512 tokens and temperature 0.7 while [stream] honoured the request.

    /** Token-level streaming. Each element emitted by the returned [Flow] is a
     *  single output token from the llama.cpp generation loop. */
    fun stream(prompt: String, maxTokens: Int, temperature: Float): Flow<String>

    /** Releases all native resources; the engine must not be used afterwards. */
    fun close()
}