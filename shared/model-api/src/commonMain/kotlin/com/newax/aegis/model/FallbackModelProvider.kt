package com.newax.aegis.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Deterministic provider for the no-model case: state is [ModelState.NOT_INSTALLED],
 * and every request is answered with [FALLBACK_TEXT] — the same honest "basic
 * commands remain available" behavior the app's NoModelInstalled implements.
 * [cancel] and [close] are no-ops because nothing is ever loaded or running.
 */
class FallbackModelProvider(
    override val descriptor: ModelDescriptor = ModelDescriptor(
        modelName = "Basic command engine",
        format = ModelFormat.UNKNOWN,
        sizeBytes = 0,
        sha256 = "",
    ),
) : ModelProvider {

    override val state: StateFlow<ModelState> = MutableStateFlow(ModelState.NOT_INSTALLED)

    override suspend fun complete(request: ModelRequest): ModelResponse {
        require(request.text.isNotBlank()) { "ModelRequest.text must not be blank" }
        return ModelResponse(FALLBACK_TEXT)
    }

    override fun stream(request: ModelRequest): Flow<String> {
        require(request.text.isNotBlank()) { "ModelRequest.text must not be blank" }
        return flowOf(FALLBACK_TEXT)
    }

    override fun cancel() = Unit

    override fun close() = Unit

    companion object {
        const val FALLBACK_TEXT =
            "No language-model pack is installed. Basic offline commands remain available."
    }
}
