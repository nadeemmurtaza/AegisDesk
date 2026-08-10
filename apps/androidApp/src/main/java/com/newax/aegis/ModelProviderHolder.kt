package com.newax.aegis

import com.newax.aegis.model.FallbackModelProvider
import com.newax.aegis.model.ModelProvider

/**
 * Process-wide holder for the active [ModelProvider] — the app's on-device brain
 * behind the shared model-api contract.
 *
 * Starts as the deterministic [FallbackModelProvider] (contract state
 * NOT_INSTALLED, "basic commands remain available"). MainViewModel swaps it for
 * the LiteRT provider once a verified pack finishes loading, and calls [clear]
 * when the model becomes unavailable. The Capabilities screen reads [current] to
 * surface model state through the contract; planner/engine code consumes the same
 * instance, so there is exactly one provider in the process.
 */
object ModelProviderHolder {

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
