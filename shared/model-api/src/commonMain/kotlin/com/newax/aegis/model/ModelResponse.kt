package com.newax.aegis.model

/**
 * The model's answer to a [ModelRequest]. [truncated] is set when the output was cut
 * short (max tokens reached, context limit, runtime tokenizer cap) so callers never
 * mistake a truncated reply for a complete one.
 */
data class ModelResponse(
    val text: String,
    val truncated: Boolean = false,
)
