package com.newax.aegis.platform.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * The LiteRT-LM platform implementation of the shared [ModelProvider] contract
 * (shared/model-api, Phase 5a — ARCHITECTURE.md Part 3). Planner and engine code
 * depend on [ModelProvider], never on this class or the LiteRT runtime.
 *
 * Lifecycle mapping onto the contract:
 *   NOT_INSTALLED → [load] → LOADING → READY, or ERROR (the failure is rethrown so
 *   callers can report it; the state stays ERROR for UI) → [close] → CLOSED.
 *
 * Images travel as encoded bytes on [ModelRequest.imageBytes] (platform-neutral
 * contract); [decodeImage] turns them into the Bitmap the engine expects.
 * Sampler/max-token parameters on the request are accepted for contract
 * compatibility but the engine applies the DeviceModelProfile sampler configured
 * at load time — the same contract-fidelity stance as FallbackModelProvider.
 */
class LiteRtModelProvider internal constructor(
    override val descriptor: ModelDescriptor,
    private val engine: LiteRtEngine,
    private val decodeImage: (ByteArray) -> Bitmap?,
) : ModelProvider {

    /** Production constructor: owns a real [LiteRtOfflineModel] over [file]. */
    constructor(context: Context, file: File, sha256: String) : this(
        descriptor = ModelDescriptor(
            modelName       = file.name,
            format          = ModelFormat.LITERTLM,
            sizeBytes       = file.length(),
            sha256          = sha256,
            supportsVision  = false, // engine is text-only; attached images become a system note, not real vision
        ),
        engine    = LiteRtOfflineModel(context, file),
        decodeImage = { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) },
    )

    private val _state = MutableStateFlow(ModelState.NOT_INSTALLED)
    override val state: StateFlow<ModelState> = _state.asStateFlow()

    /** Loads the engine and moves to READY; on failure sets ERROR and rethrows. */
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
        val reply = engine.complete(request.text, request.imageBytes?.let(decodeImage))
        return ModelResponse(reply)
    }

    override fun stream(request: ModelRequest): Flow<String> {
        require(request.text.isNotBlank()) { "ModelRequest.text must not be blank" }
        // The LiteRT engine serves blocking sendMessage() with no token streaming, so
        // the honest implementation emits the complete reply as a single chunk.
        return flow {
            emit(engine.complete(request.text, request.imageBytes?.let(decodeImage)))
        }
    }

    override fun cancel() {
        // A running sendMessage() call cannot be interrupted and nothing else is
        // cancellable between calls — documented no-op, same as the fallback.
    }

    override fun close() {
        engine.close()
        _state.value = ModelState.CLOSED
    }

    /** Forwards Android memory trims to the engine (not part of the contract). */
    fun onMemoryPressure(level: Int) = engine.onMemoryPressure(level)
}

/**
 * Narrow engine seam so [LiteRtModelProvider]'s lifecycle and delegation are
 * unit-testable without the LiteRT-LM runtime. The production implementation is
 * [LiteRtOfflineModel]; tests provide a fake.
 */
interface LiteRtEngine {
    /** Loads the engine into a servable state (cold start or warm-up). */
    suspend fun load()

    /** Runs one blocking inference. [image] is the decoded vision input or null. */
    suspend fun complete(prompt: String, image: Bitmap?): String

    /** Forward Android memory-trim levels (WARM/UNLOAD). */
    fun onMemoryPressure(level: Int)

    /** Releases the runtime; the engine must not be used afterwards. */
    fun close()
}
