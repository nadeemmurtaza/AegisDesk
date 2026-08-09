package com.newax.aegis.model

import com.newax.aegis.platform.OperationContext

/**
 * One inference request. [text] must not be blank. [imageBytes] carries an already
 * encoded image (platform-neutral — never a platform bitmap type) when the model
 * supports vision; the `ByteArray` contents are not part of structural equality.
 */
data class ModelRequest(
    val text: String,
    val imageBytes: ByteArray? = null,
    val imageMimeType: String? = null,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,

    /** Authority metadata for the ledger (ARCHITECTURE.md RULE 4); optional but encouraged. */
    val context: OperationContext? = null,
)
