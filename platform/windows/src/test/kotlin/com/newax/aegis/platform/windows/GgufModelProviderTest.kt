package com.newax.aegis.platform.windows

import com.newax.aegis.model.ModelDescriptor
import com.newax.aegis.model.ModelFormat
import com.newax.aegis.model.ModelRequest
import com.newax.aegis.model.ModelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [GgufModelProvider] is tested against a fake [GgufEngine], so its lifecycle
 * (NOT_INSTALLED → LOADING → READY/ERROR → CLOSED), descriptor mapping, and
 * delegation are verified without the native llama.cpp runtime.
 */
class GgufModelProviderTest {

    private val engine = FakeGgufEngine()

    private fun provider(
        name: String = "test-model",
        sha256: String = "abc123",
        size: Long = 4_000_000_000L,
    ) = GgufModelProvider(
        descriptor = ModelDescriptor(
            modelName = name,
            format    = ModelFormat.GGUF,
            sizeBytes = size,
            sha256    = sha256,
        ),
        engine = engine,
    )

    @Test
    fun `descriptor maps the installed pack`() {
        val d = provider(name = "desktop-model", sha256 = "deadbeef", size = 7_000_000_000).descriptor
        assertEquals("desktop-model", d.modelName)
        assertEquals(ModelFormat.GGUF, d.format)
        assertEquals(7_000_000_000L, d.sizeBytes)
        assertEquals("deadbeef", d.sha256)
        assertEquals(false, d.supportsVision)
    }

    @Test
    fun `state starts NOT_INSTALLED and reaches READY after load`() = runBlocking {
        val p = provider()
        assertEquals(ModelState.NOT_INSTALLED, p.state.value)
        p.load()
        assertEquals(ModelState.READY, p.state.value)
        assertEquals(true, engine.loaded)
    }

    @Test
    fun `failed load leaves ERROR and rethrows`() = runBlocking {
        engine.loadError = IllegalStateException("binding crashed")
        val p = provider()
        assertThrows(IllegalStateException::class.java) { p.load() }
        assertEquals(ModelState.ERROR, p.state.value)
    }

    @Test
    fun `close releases the engine and moves to CLOSED`() = runBlocking {
        val p = provider()
        p.load()
        p.close()
        assertEquals(ModelState.CLOSED, p.state.value)
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun `complete delegates prompt to engine`() = runBlocking {
        engine.reply = "Once upon a time"
        val p = provider()
        p.load()
        val response = p.complete(ModelRequest(text = "tell me a story"))
        assertEquals("Once upon a time", response.text)
        assertEquals("tell me a story", engine.lastPrompt)
    }

    @Test
    fun `blank text is rejected`() = runBlocking {
        val p = provider()
        p.load()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { p.complete(ModelRequest(text = "   ")) }
        }
    }

    @Test
    fun `stream emits tokens from engine flow`() = runBlocking {
        engine.streamTokens = listOf("hello", " ", "world")
        val p = provider()
        p.load()
        val chunks = p.stream(ModelRequest(text = "say hi")).toList()
        assertEquals(listOf("hello", " ", "world"), chunks)
        assertEquals("say hi", engine.lastStreamPrompt)
    }

    @Test
    fun `cancel is a no-op that leaves state intact`() = runBlocking {
        val p = provider()
        p.load()
        p.cancel()
        assertEquals(ModelState.READY, p.state.value)
    }

    private class FakeGgufEngine : GgufEngine {
        var loaded = false
        var loadError: Throwable? = null
        var lastPrompt: String? = null
        var lastStreamPrompt: String? = null
        var closeCalls = 0
        var reply = "fake reply"
        var streamTokens: List<String> = listOf("fake")

        override suspend fun load() {
            loadError?.let { throw it }
            loaded = true
        }

        override suspend fun complete(prompt: String): String {
            lastPrompt = prompt
            return reply
        }

        override fun stream(prompt: String, maxTokens: Int, temperature: Float): Flow<String> {
            lastStreamPrompt = prompt
            return flow { streamTokens.forEach { emit(it) } }
        }

        override fun close() {
            closeCalls++
            loaded = false
        }
    }
}