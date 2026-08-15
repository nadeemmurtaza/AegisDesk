package com.newax.aegis.platform.windows

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.LlamaOutput
import de.kherud.llama.ModelParameters
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Production [GgufEngine] that wraps the `de.kherud:java-llama.cpp` JNI binding.
 *
 * ## Lifecycle
 * 1. Create: `KherudGgufEngine(file)` — no native resources allocated.
 * 2. Load: calls `LlamaModel(ModelParameters)` — JNI native library loads and
 *    the model file is mapped into memory. Heavy (RAM + time).
 * 3. Infer: `stream()` — a synchronous JNI call into llama.cpp, driven as a Flow.
 *    It is the only inference entry point; `ModelProvider.complete` collects it.
 * 4. Close: `LlamaModel.close()` — releases native memory and unloads the library.
 *
 * ## Platform support
 * The `java-llama.cpp` artifact bundles native `.dll` (Windows), `.dylib` (macOS),
 * and `.so` (Linux) binaries for x86-64 and aarch64, so the same build works on
 * all three desktop OSes.
 *
 * ## Thread safety
 * LlamaModel is NOT thread-safe for concurrent inference. [stream] runs its
 * generation loop on [Dispatchers.IO] via `flowOn`, and single access is ensured
 * by the provider-level caller serialising through the state machine.
 */
class KherudGgufEngine(
    private val modelFile: File,
) : GgufEngine {

    private var model: LlamaModel? = null

    override suspend fun load() {
        if (model != null) return // already loaded
        withContext(Dispatchers.IO) {
            val params = ModelParameters()
                .setModel(modelFile.absolutePath)
                .setGpuLayers(0) // CPU-only by default; override via setGpuLayers(n)
            model = LlamaModel(params)
        }
    }

    override fun stream(prompt: String, maxTokens: Int, temperature: Float): Flow<String> {
        val m = requireModel()
        return flow {
            val params = InferenceParameters(prompt)
                .setNPredict(maxTokens)
                .setTemperature(temperature)
            for (output: LlamaOutput in m.generate(params)) {
                emit(output.toString())
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun close() {
        model?.close()
        model = null
    }

    private fun requireModel(): LlamaModel =
        model ?: throw IllegalStateException(
            "KherudGgufEngine not loaded — call load() before inference"
        )
}