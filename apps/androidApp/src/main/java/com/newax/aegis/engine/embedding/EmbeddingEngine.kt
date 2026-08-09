package com.newax.aegis.engine.embedding

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder.TextEmbedderOptions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

/**
 * On-device text embedding via MediaPipe Universal Sentence Encoder.
 * Model is ~25 MB, downloaded once to app's internal storage on first call
 * to [downloadModelIfNeeded]. All subsequent runs load from disk — no internet needed.
 *
 * Call order:
 *   1. [init]                  — in Application.onCreate() (loads if already present)
 *   2. [downloadModelIfNeeded] — once, when network is available
 *   3. [embed]                 — on any background thread
 */
object EmbeddingEngine {

    private const val TAG          = "AegisEmbedding"
    private const val MODEL_FILE   = "use_lite.tflite"
    private const val MODEL_URL    = "https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/1/universal_sentence_encoder.tflite"
    private const val MIN_MODEL_SZ = 1_000_000L   // sanity: model must be > 1 MB
    const val DIMS                 = 512

    @Volatile private var embedder: TextEmbedder? = null

    /** Load model if it's already on disk. No-op otherwise. */
    fun init(context: Context) {
        val file = File(context.filesDir, MODEL_FILE)
        if (file.exists() && file.length() > MIN_MODEL_SZ) {
            loadEmbedder(context, file)
        }
    }

    /**
     * Download the USE lite model (once) then initialize the embedder.
     * Skips download if model already exists. Runs on a background thread;
     * [onComplete](success) is called when done.
     */
    fun downloadModelIfNeeded(context: Context, onComplete: (Boolean) -> Unit = {}) {
        val file = File(context.filesDir, MODEL_FILE)
        if (file.exists() && file.length() > MIN_MODEL_SZ) {
            if (embedder == null) loadEmbedder(context, file)
            onComplete(true)
            return
        }
        Thread {
            try {
                Log.d(TAG, "Downloading USE model (~25 MB)…")
                val tmp = File(context.filesDir, "$MODEL_FILE.tmp")
                val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout    = 180_000
                conn.connect()
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out -> out.write(input.readBytes()) }
                }
                tmp.renameTo(file)
                loadEmbedder(context, file)
                Log.d(TAG, "USE model ready (${file.length() / 1_048_576} MB)")
                onComplete(true)
            } catch (e: Exception) {
                Log.w(TAG, "Model download failed: ${e.message}")
                onComplete(false)
            }
        }.start()
    }

    fun isReady(): Boolean = embedder != null

    /**
     * Embed [text] and return a 512-dim L2-normalized FloatArray.
     * Returns null if engine not ready or on failure — caller falls back to BM25.
     */
    fun embed(text: String): FloatArray? {
        val e = embedder ?: return null
        return try {
            e.embed(text).embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
        } catch (ex: Exception) {
            Log.w(TAG, "Embed error: ${ex.message}")
            null
        }
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var normA = 0f; var normB = 0f
        for (i in a.indices) { dot += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else (dot / denom).coerceIn(-1f, 1f)
    }

    private fun loadEmbedder(context: Context, file: File) {
        try {
            val options = TextEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(file.absolutePath).build())
                .setL2Normalize(true)
                .build()
            embedder = TextEmbedder.createFromOptions(context, options)
            Log.d(TAG, "Embedder initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Embedder load failed: ${e.message}")
            embedder = null
        }
    }
}
