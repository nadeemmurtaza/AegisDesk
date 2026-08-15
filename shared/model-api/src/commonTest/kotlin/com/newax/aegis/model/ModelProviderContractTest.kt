package com.newax.aegis.model

import com.newax.aegis.assistant.ActionOrigin
import com.newax.aegis.platform.OperationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelProviderContractTest {

    @Test
    fun fallbackStartsNotInstalledAndNeverClaimsReady() {
        val provider = FallbackModelProvider()
        assertEquals(ModelState.NOT_INSTALLED, provider.state.value)
        assertEquals(ModelFormat.UNKNOWN, provider.descriptor.format)
        assertEquals(0, provider.descriptor.sizeBytes)
    }

    @Test
    fun fallbackAnswersWithTheNoModelMessage() = runTest {
        val provider = FallbackModelProvider()
        val response = provider.complete(ModelRequest("hello"))
        assertEquals(FallbackModelProvider.FALLBACK_TEXT, response.text)
        assertFalse(response.truncated)
    }

    @Test
    fun fallbackRejectsBlankText() = runTest {
        val provider = FallbackModelProvider()
        assertFailsWith<IllegalArgumentException> { provider.complete(ModelRequest("   ")) }
        assertFailsWith<IllegalArgumentException> { provider.stream(ModelRequest("")) }
    }

    @Test
    fun fallbackStreamsExactlyOneChunk() = runTest {
        val chunks = FallbackModelProvider().stream(ModelRequest("hi")).toList()
        assertEquals(listOf(FallbackModelProvider.FALLBACK_TEXT), chunks)
    }

    @Test
    fun completeIsTheStreamCollected() = runTest {
        // T2.5 — the single inference path: complete() is the collected stream().
        // The fallback now relies on the interface default, so this pins the
        // wiring: the reply must equal exactly what stream() emits.
        val provider = FallbackModelProvider()
        val request = ModelRequest("hi")
        assertEquals(
            provider.stream(request).toList().joinToString(""),
            provider.complete(request).text,
        )
        assertEquals(FallbackModelProvider.FALLBACK_TEXT, provider.complete(request).text)
    }

    @Test
    fun aProviderThatOverridesOnlyStreamGetsCompleteForFree() = runTest {
        // A provider implementing only stream() (and nothing else) must still
        // answer complete() correctly — the default is the production caller of
        // stream(), so a new provider cannot accidentally leave complete() dead.
        val response = StreamOnlyProvider().complete(ModelRequest("hello"))
        assertEquals("hello!", response.text)
        assertFalse(response.truncated)
    }

    @Test
    fun fallbackCancelAndCloseAreSafeRepeatedly() {
        val provider = FallbackModelProvider()
        provider.cancel()
        provider.cancel()
        provider.close()
        provider.close()
    }

    @Test
    fun descriptorDefaultsAreOfflineFirstAndMinimal() {
        val descriptor = ModelDescriptor(
            modelName = "Gemma 3 1B INT4",
            format = ModelFormat.LITERTLM,
            sizeBytes = 2_500_000_000L,
            sha256 = "ab12cd34",
        )
        assertEquals("Gemma 3 1B INT4", descriptor.modelName)
        assertFalse(descriptor.supportsVision)
        assertEquals(0, descriptor.ramEstimateBytes)
    }

    @Test
    fun contractIsImplementableByARealProvider() = runTest {
        val provider = RecordingProvider()
        val ctx = OperationContext.create("test-agent", ActionOrigin.BACKGROUND)

        val response = provider.complete(ModelRequest("summarize", context = ctx))
        assertEquals("echo: summarize", response.text)
        assertEquals("test-agent", provider.lastContext?.caller)
        assertEquals(ActionOrigin.BACKGROUND, provider.lastContext?.origin)

        val chunks = provider.stream(ModelRequest("tokens")).toList()
        assertEquals(listOf("tokens", "!"), chunks)

        provider.cancel()
        provider.close()
        assertEquals(1, provider.cancelCount)
        assertEquals(1, provider.closeCount)
    }

    @Test
    fun providerStateIsObservableAsAFlow() {
        val provider = RecordingProvider()
        assertNull(provider.lastObserved)
        provider.setState(ModelState.READY)
        assertEquals(ModelState.READY, provider.state.value)
        assertEquals(ModelState.READY, provider.lastObserved)
        provider.setState(ModelState.CLOSED)
        assertEquals(ModelState.CLOSED, provider.state.value)
        assertEquals(ModelState.CLOSED, provider.lastObserved)
    }

    private class RecordingProvider : ModelProvider {
        override val descriptor = ModelDescriptor(
            modelName = "Recording stub",
            format = ModelFormat.GGUF,
            sizeBytes = 1,
            sha256 = "stub",
        )

        private val internalState = MutableStateFlow(ModelState.NOT_INSTALLED)
        override val state: StateFlow<ModelState> = internalState

        var lastContext: OperationContext? = null
            private set
        var cancelCount = 0
            private set
        var closeCount = 0
            private set

        private var observed: ModelState? = null

        val lastObserved: ModelState?
            get() = observed

        override suspend fun complete(request: ModelRequest): ModelResponse {
            lastContext = request.context
            return ModelResponse("echo: ${request.text}")
        }

        override fun stream(request: ModelRequest): Flow<String> = flowOf(request.text, "!")

        override fun cancel() {
            cancelCount += 1
        }

        override fun close() {
            closeCount += 1
        }

        fun setState(newState: ModelState) {
            internalState.value = newState
            observed = newState
        }
    }

    /** Proves the interface default: overrides stream() only, inherits complete(). */
    private class StreamOnlyProvider : ModelProvider {
        override val descriptor = ModelDescriptor(
            modelName = "Stream only",
            format = ModelFormat.UNKNOWN,
            sizeBytes = 0,
            sha256 = "",
        )

        override val state: StateFlow<ModelState> = MutableStateFlow(ModelState.NOT_INSTALLED)

        override fun stream(request: ModelRequest): Flow<String> = flowOf(request.text, "!")

        override fun cancel() = Unit

        override fun close() = Unit
    }
}
