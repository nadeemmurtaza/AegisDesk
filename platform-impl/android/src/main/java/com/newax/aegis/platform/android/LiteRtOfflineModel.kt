package com.newax.aegis.platform.android

import android.content.ComponentCallbacks2
import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class LiteRtOfflineModel(private val context: Context, private val modelFile: File) : LiteRtEngine {

    enum class ModelState { UNLOADED, WARM, ACTIVE }

    private companion object {
        const val KEEPALIVE_MS = 60_000L
    }

    private val lifecycleMutex = Mutex()
    private val generationMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var keepaliveJob: Job? = null
    private var state: ModelState = ModelState.UNLOADED

    /** True once the engine is mapped — WARM and ACTIVE can both serve requests. */
    val isReady: Boolean get() = state != ModelState.UNLOADED

    override suspend fun load() = lifecycleMutex.withLock {
        when (state) {
            ModelState.ACTIVE   -> return@withLock
            ModelState.WARM     -> warmToActive()
            ModelState.UNLOADED -> coldStart()
        }
    }

    override suspend fun complete(prompt: String, image: android.graphics.Bitmap?): String {
        load()
        return generationMutex.withLock {
            withContext(Dispatchers.IO) {
                val finalPrompt = if (image != null)
                    "$prompt\n[System Note: User has attached a screen image. Visual processing requires a Multimodal model.]"
                else prompt
                // Uses blocking sendMessage() — see LiteRtOfflineModel comment in original file
                // re: NoSuchMethodError on the Flow-based sendMessageAsync path.
                val reply = conversation!!.sendMessage(finalPrompt)
                resetKeepalive()
                reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    .trim().ifBlank { "The local model returned an empty response." }
            }
        }
    }

    /**
     * Forward Android memory-trim level.
     * RUNNING_LOW (10) → release KV cache (conversation), keep engine mapped (WARM).
     * COMPLETE (80)    → fully unmap engine (UNLOADED).
     */
    override fun onMemoryPressure(level: Int) {
        scope.launch {
            lifecycleMutex.withLock {
                when {
                    level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE && state != ModelState.UNLOADED -> {
                        keepaliveJob?.cancel()
                        conversation?.close(); conversation = null
                        engine?.close();       engine = null
                        state = ModelState.UNLOADED
                    }
                    level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && state == ModelState.ACTIVE -> {
                        keepaliveJob?.cancel()
                        conversation?.close(); conversation = null
                        state = ModelState.WARM
                    }
                }
            }
        }
    }

    override fun close() {
        keepaliveJob?.cancel()
        scope.cancel()
        conversation?.close(); conversation = null
        engine?.close();       engine = null
        state = ModelState.UNLOADED
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun coldStart() {
        withContext(Dispatchers.IO) {
            val eng = Engine(EngineConfig(
                modelPath    = modelFile.absolutePath,
                backend      = Backend.CPU(),
                maxNumTokens = DeviceModelProfile.maxContextTokens,
                cacheDir     = context.cacheDir.absolutePath
            ))
            try {
                eng.initialize()
                engine       = eng
                conversation = eng.createConversation(buildConversationConfig())
                state        = ModelState.ACTIVE
            } catch (error: Throwable) {
                eng.close()
                throw error
            }
        }
    }

    private suspend fun warmToActive() {
        withContext(Dispatchers.IO) {
            conversation = engine!!.createConversation(buildConversationConfig())
            state = ModelState.ACTIVE
        }
    }

    private fun buildConversationConfig() = ConversationConfig(
        systemInstruction  = Contents.of(DeviceModelProfile.systemPrompt),
        samplerConfig      = SamplerConfig(
            topK        = DeviceModelProfile.topK,
            topP        = 0.9,
            temperature = DeviceModelProfile.temperature.toDouble()
        ),
        automaticToolCalling = false
    )

    private fun resetKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            delay(KEEPALIVE_MS)
            lifecycleMutex.withLock {
                if (state == ModelState.ACTIVE) {
                    conversation?.close(); conversation = null
                    state = ModelState.WARM
                }
            }
        }
    }
}
