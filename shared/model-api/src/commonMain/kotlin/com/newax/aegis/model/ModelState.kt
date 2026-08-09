package com.newax.aegis.model

/**
 * Lifecycle of a loaded model. A provider without an installed model pack stays
 * [NOT_INSTALLED] forever — deterministic command parsing is that state's answer,
 * never an error.
 */
enum class ModelState {
    /** No model pack installed; only the deterministic fallback path is available. */
    NOT_INSTALLED,

    /** Model pack verified and being loaded into memory. */
    LOADING,

    /** Model loaded and accepting requests. */
    READY,

    /** Load or inference failed; a retry may still succeed. */
    ERROR,

    /** Provider closed; no further requests are accepted. */
    CLOSED,
}
