package com.newax.aegis.assistant

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LiteRtOfflineModel(private val context: Context, private val modelFile: File) : OfflineModel, AutoCloseable {
    private val lifecycleMutex = Mutex()
    private val generationMutex = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    override val modelName: String = modelFile.name
    override val isReady: Boolean get() = engine != null && conversation != null

    suspend fun initialize() = lifecycleMutex.withLock {
        if (isReady) return@withLock
        withContext(Dispatchers.IO) {
            val createdEngine = Engine(EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                maxNumTokens = DeviceModelProfile.maxContextTokens,
                cacheDir = context.cacheDir.absolutePath
            ))
            try {
                createdEngine.initialize()
                val createdConversation = createdEngine.createConversation(ConversationConfig(
                    systemInstruction = Contents.of(DeviceModelProfile.systemPrompt),
                    samplerConfig = SamplerConfig(
                        topK = DeviceModelProfile.topK,
                        topP = 0.9,
                        temperature = DeviceModelProfile.temperature.toDouble()
                    ),
                    automaticToolCalling = false
                ))
                engine = createdEngine
                conversation = createdConversation
            } catch (error: Throwable) {
                createdEngine.close()
                throw error
            }
        }
    }

    override suspend fun complete(prompt: String, image: android.graphics.Bitmap?): String {
        initialize()
        return generationMutex.withLock {
            withContext(Dispatchers.IO) {
                // Placeholder: litertlm 0.14.0/0.15.0 text-only backend doesn't support Bitmap natively.
                // For a multimodal model, use LlmInference or a newer multimodal Conversation API here.
                val finalPrompt = if (image != null) "$prompt\n[System Note: User has attached a screen image. Visual processing requires a Multimodal model.]" else prompt
                // Uses the blocking sendMessage(String) overload rather than the Flow-based
                // sendMessageAsync(...): the installed litertlm-android build's callbackFlow
                // closes its channel via a kotlinx-coroutines interface-static bridge that
                // doesn't exist in the publicly published kotlinx-coroutines-core artifact,
                // crashing with NoSuchMethodError on every completion. sendMessage() never
                // touches that codepath.
                val reply = conversation!!.sendMessage(finalPrompt)
                reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    .trim().ifBlank { "The local model returned an empty response." }
            }
        }
    }

    override fun close() {
        conversation?.close(); conversation = null
        engine?.close(); engine = null
    }
}
