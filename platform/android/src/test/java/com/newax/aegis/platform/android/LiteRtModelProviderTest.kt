package com.newax.aegis.platform.android

import android.graphics.Bitmap
import com.newax.aegis.model.ModelFormat
import com.newax.aegis.model.ModelRequest
import com.newax.aegis.model.ModelState
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * LiteRtModelProvider is tested against a fake [LiteRtEngine] so its lifecycle
 * (NOT_INSTALLED → LOADING → READY/ERROR → CLOSED), descriptor mapping, and
 * delegation are verified without the LiteRT-LM runtime. The engine's own
 * behaviour is a device-tested concern, not a JVM one.
 */
class LiteRtModelProviderTest {

    private lateinit var modelFile: File
    private val engine = FakeEngine()
    private var decodeCalls = 0

    @Before
    fun setUp() {
        modelFile = Files.createTempFile("aegis-model", ".litertlm").toFile()
        modelFile.writeBytes(ByteArray(4096))
    }

    private fun provider(sha256: String = "abc123") = LiteRtModelProvider(
        descriptor = com.newax.aegis.model.ModelDescriptor(
            modelName = modelFile.name,
            format    = ModelFormat.LITERTLM,
            sizeBytes = modelFile.length(),
            sha256    = sha256,
        ),
        engine     = engine,
        decodeImage = { decodeCalls++; null }, // fake decoder: no real Bitmap on JVM
    )

    @Test
    fun `descriptor maps the installed pack`() = runBlocking {
        val d = provider(sha256 = "deadbeef").descriptor
        assertEquals(modelFile.name, d.modelName)
        assertEquals(ModelFormat.LITERTLM, d.format)
        assertEquals(modelFile.length(), d.sizeBytes)
        assertEquals("deadbeef", d.sha256)
        assertEquals(false, d.supportsVision)
    }

    @Test
    fun `state starts NOT_INSTALLED and reaches READY after load`() = runBlocking {
        val p = provider()
        assertEquals(ModelState.NOT_INSTALLED, p.state.value)
        p.load()
        assertEquals(ModelState.READY, p.state.value)
        assertTrue(engine.loaded)
    }

    @Test
    fun `failed load leaves ERROR and rethrows`() = runBlocking {
        engine.loadError = IllegalStateException("engine exploded")
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
    fun `complete delegates prompt and decodes attached image bytes`() = runBlocking {
        val p = provider()
        p.load()

        p.complete(ModelRequest(text = "hello"))
        assertEquals("hello", engine.lastPrompt)
        assertNull(engine.lastImage)
        assertEquals(0, decodeCalls)

        p.complete(ModelRequest(text = "look at this", imageBytes = byteArrayOf(1, 2, 3)))
        assertEquals("look at this", engine.lastPrompt)
        assertNull(engine.lastImage) // fake decoder returns null; the decode step still ran
        assertEquals(1, decodeCalls)
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
    fun `stream emits the full reply as a single chunk`() = runBlocking {
        engine.reply = "one shot reply"
        val p = provider()
        p.load()
        val chunks = p.stream(ModelRequest(text = "tell me")).toList()
        assertEquals(listOf("one shot reply"), chunks)
    }

    @Test
    fun `cancel is a no-op that leaves state intact`() = runBlocking {
        val p = provider()
        p.load()
        p.cancel()
        assertEquals(ModelState.READY, p.state.value)
    }

    @Test
    fun `memory pressure is forwarded to the engine`() = runBlocking {
        val p = provider()
        p.load()
        p.onMemoryPressure(80)
        assertEquals(listOf(80), engine.pressureLevels)
    }

    private class FakeEngine : LiteRtEngine {
        var loaded = false
        var loadError: Throwable? = null
        var lastPrompt: String? = null
        var lastImage: Bitmap? = null
        var closeCalls = 0
        val pressureLevels = mutableListOf<Int>()
        var reply = "fake reply"

        override suspend fun load() {
            loadError?.let { throw it }
            loaded = true
        }

        override suspend fun complete(prompt: String, image: Bitmap?): String {
            lastPrompt = prompt
            lastImage = image
            return reply
        }

        override fun onMemoryPressure(level: Int) { pressureLevels += level }

        override fun close() {
            closeCalls++
            loaded = false
        }
    }
}
