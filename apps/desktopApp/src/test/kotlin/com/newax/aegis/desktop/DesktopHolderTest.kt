package com.newax.aegis.desktop

import com.newax.aegis.model.FallbackModelProvider
import com.newax.aegis.model.ModelDescriptor
import com.newax.aegis.model.ModelFormat
import com.newax.aegis.model.ModelProvider
import com.newax.aegis.model.ModelRequest
import com.newax.aegis.model.ModelResponse
import com.newax.aegis.model.ModelState
import com.newax.aegis.platform.CapabilityId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5e — desktop process-wide surfaces:
 *  - [DesktopCapabilitiesHolder] registers the Windows desktop capability once.
 *  - [DesktopModelProviderHolder] starts at the deterministic fallback and swaps
 *    to a real provider only when the runner installs one.
 *
 * All tests are pure JVM and OS-independent: the Windows desktop capability
 * reports NOT_SUPPORTED on non-Windows CI runners, which the holder surfaces
 * honestly rather than stubbing.
 */
class DesktopHolderTest {

    @Test
    fun `capabilities holder registers desktop capability once`() {
        DesktopCapabilitiesHolder.init()
        DesktopCapabilitiesHolder.init() // idempotent — same registry, no duplicates

        val registry = DesktopCapabilitiesHolder.registry()
        assertNotNull(registry)
        val all = registry!!.all()
        assertEquals(1, all.size)
        assertEquals(CapabilityId.DESKTOP, all[0].id)
        // OS-independent assertion: the capability is registered; its operational
        // status is READY on Windows and NOT_SUPPORTED elsewhere — either is valid.
        assertTrue(all[0].descriptor().id == CapabilityId.DESKTOP)
    }

    @Test
    fun `model holder starts at the deterministic fallback`() {
        DesktopModelProviderHolder.clear() // deterministic start regardless of test order
        val provider = DesktopModelProviderHolder.current()
        assertEquals(ModelState.NOT_INSTALLED, provider.state.value)
        assertEquals(ModelFormat.UNKNOWN, provider.descriptor.format)
        assertEquals(
            FallbackModelProvider.FALLBACK_TEXT,
            runBlocking { provider.complete(ModelRequest(text = "hi")) }.text,
        )
    }

    @Test
    fun `model holder swaps to installed provider and clears back to fallback`() {
        DesktopModelProviderHolder.clear() // reset in case a prior test swapped

        val installed = FakeInstalledProvider()
        DesktopModelProviderHolder.set(installed)
        assertSame(installed, DesktopModelProviderHolder.current())
        assertEquals(ModelState.READY, DesktopModelProviderHolder.current().state.value)

        DesktopModelProviderHolder.clear()
        assertTrue(DesktopModelProviderHolder.current() is FallbackModelProvider)
        assertEquals(ModelState.NOT_INSTALLED, DesktopModelProviderHolder.current().state.value)
    }

    /** Minimal ModelProvider standing in for GgufModelProvider in holder tests. */
    private class FakeInstalledProvider : ModelProvider {
        override val descriptor: ModelDescriptor = ModelDescriptor(
            modelName = "fake-gguf",
            format = ModelFormat.GGUF,
            sizeBytes = 1_000,
            sha256 = "deadbeef",
        )
        override val state: StateFlow<ModelState> = MutableStateFlow(ModelState.READY)
        override suspend fun complete(request: ModelRequest): ModelResponse = ModelResponse("ok")
        override fun stream(request: ModelRequest): Flow<String> = flowOf("ok")
        override fun cancel() = Unit
        override fun close() = Unit
    }
}
