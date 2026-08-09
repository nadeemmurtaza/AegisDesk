package com.newax.aegis.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The on-device inference boundary — platform-free by contract (no android.*, no
 * java.awt, no OS imports; images travel as encoded bytes in [ModelRequest]).
 *
 * Implementations: LiteRT-LM on Android, llama.cpp/GGUF on desktop (ARCHITECTURE.md
 * Part 3). Planner and engine code depend on this interface, never on a runtime.
 * A provider without a pack answers via the deterministic [FallbackModelProvider]
 * semantics instead of failing.
 */
interface ModelProvider {

    /** Static facts about the installed pack (empty/UNKNOWN for the fallback). */
    val descriptor: ModelDescriptor

    /** Lifecycle as a hot flow; starts at NOT_INSTALLED for the fallback. */
    val state: StateFlow<ModelState>

    /** Runs one inference to completion. [ModelRequest.text] must not be blank. */
    suspend fun complete(request: ModelRequest): ModelResponse

    /** Streaming variant; emits text chunks as they are produced, then completes. */
    fun stream(request: ModelRequest): Flow<String>

    /** Requests the running inference to stop; safe to call when none is running. */
    fun cancel()

    /** Releases the runtime and resources; the provider must not be used afterwards. */
    fun close()
}
