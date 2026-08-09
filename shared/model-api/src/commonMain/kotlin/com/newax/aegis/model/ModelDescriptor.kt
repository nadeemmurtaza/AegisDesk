package com.newax.aegis.model

/** The runtime a model pack is built for. Used for import/verification decisions. */
enum class ModelFormat {
    /** Google LiteRT-LM bundle (`.litertlm`), the Android runtime. */
    LITERTLM,

    /** llama.cpp GGUF file, the desktop runtime. */
    GGUF,

    /** No pack installed, or a format the runtime does not know. */
    UNKNOWN,
}

/**
 * Static facts about the installed model pack: what it is, how big it is, and how it
 * was verified (sha256). Platform-free — the provider that owns the pack fills it in.
 */
data class ModelDescriptor(
    val modelName: String,
    val format: ModelFormat,
    val sizeBytes: Long,
    val sha256: String,
    val supportsVision: Boolean = false,
    val ramEstimateBytes: Long = 0,
)
