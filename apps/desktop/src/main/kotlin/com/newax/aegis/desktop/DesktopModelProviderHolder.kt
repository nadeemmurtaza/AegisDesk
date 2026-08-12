package com.newax.aegis.desktop

import com.newax.aegis.model.FallbackModelProvider
import com.newax.aegis.model.ModelProvider

/**
 * Desktop counterpart of Android's [com.newax.aegis.ModelProviderHolder]: the one
 * active [ModelProvider] in the desktop process.
 *
 * Starts as the deterministic [FallbackModelProvider] (contract state NOT_INSTALLED,
 * "basic commands remain available"). The runner swaps it for a [com.newax.aegis.platform.windows.GgufModelProvider]
 * once a verified GGUF model is imported and loaded, and calls [clear] when the
 * model becomes unavailable or the session ends. Planner and engine code consume
 * [current] so there is exactly one provider per process — same invariant as the
 * Android app.
 */
object DesktopModelProviderHolder {

    @Volatile
    private var provider: ModelProvider = FallbackModelProvider()

    fun current(): ModelProvider = provider

    fun set(provider: ModelProvider) {
        this.provider = provider
    }

    /** Returns to the deterministic fallback (unload, load failure, or close). */
    fun clear() {
        provider = FallbackModelProvider()
    }
}
