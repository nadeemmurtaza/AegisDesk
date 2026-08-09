package com.newax.aegis.assistant

/** Stable boundary for MediaPipe/LiteRT/llama.cpp implementations and model upgrades. */
interface OfflineModel {
    val modelName: String
    val isReady: Boolean
    suspend fun complete(prompt: String, image: android.graphics.Bitmap? = null): String
}

class NoModelInstalled : OfflineModel {
    override val modelName = "Basic command engine"
    override val isReady = false
    override suspend fun complete(prompt: String, image: android.graphics.Bitmap?): String =
        "No language-model pack is installed. Basic offline commands remain available."
}
