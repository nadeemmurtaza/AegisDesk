package com.newax.aegis.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.newax.aegis.engine.CommunicationLog
import com.newax.aegis.engine.DocumentClassifier
import com.newax.aegis.engine.SensitiveInfoDetector
import com.newax.aegis.engine.ToneAnalyzer
import com.newax.aegis.engine.TriggerEngine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device OCR engine using ML Kit Text Recognition (Latin script, fully offline).
 * Applies the full intelligence pipeline to extracted text: sensitive info detection,
 * tone analysis, document classification, and change detection to avoid redundant work.
 *
 * Thread-safety: analyze() is callback-based; analyzeAsync() is a suspend function.
 * Both are safe to call from any thread.
 */
object OcrEngine {

    private const val TAG = "AegisOCR"

    // Minimum Levenshtein-distance ratio to consider text "changed" vs previous result
    private const val CHANGE_THRESHOLD = 0.15f

    data class OcrTextBlock(
        val text: String,               // text for this block (safe — may contain sensitive context)
        val bounds: Rect?,              // bounding box on screen in pixels
        val confidence: Float,          // 0.0–1.0 average confidence across elements in block
        val lineCount: Int
    )

    data class OcrResult(
        val fullText: String,           // complete extracted text (may be large)
        val safeText: String,           // fullText with sensitive values redacted
        val blocks: List<OcrTextBlock>,
        val sensitiveInfo: SensitiveInfoDetector.AnalysisResult,
        val tone: ToneAnalyzer.ToneProfile,
        val documentType: DocumentClassifier.ClassificationResult,
        val imageQuality: ImageQuality,
        val timestampMs: Long,
        val processingMs: Long,
        val isChanged: Boolean          // false if text is nearly identical to previous result
    )

    data class ImageQuality(
        val brightness: Float,          // 0.0–1.0
        val isTooBlurry: Boolean,
        val isTooLight: Boolean,
        val isTooDrawn: Boolean,        // luminance too low / dark
        val isUsable: Boolean           // true if OCR is worth attempting
    )

    private var recognizer: TextRecognizer? = null
    private var lastFullText: String = ""

    /** Must be called once before use (e.g., in Application.onCreate or service onCreate). */
    fun init() {
        if (recognizer != null) return
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Log.d(TAG, "TextRecognizer initialized")
    }

    /**
     * Analyzes a bitmap with ML Kit OCR, then runs the full intelligence pipeline.
     * [callback] receives null on failure or if the image quality is too poor.
     * Callback fires on ML Kit's internal thread pool — not the main thread.
     */
    fun analyze(bitmap: Bitmap, sourceApp: String = "", callback: (OcrResult?) -> Unit) {
        val r = recognizer
        if (r == null) {
            Log.w(TAG, "OcrEngine.analyze() called before init()")
            callback(null)
            return
        }

        val quality = assessQuality(bitmap)
        if (!quality.isUsable) {
            Log.d(TAG, "Skipping OCR — image quality unusable: $quality")
            callback(null)
            return
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val startMs = SystemClock.elapsedRealtime()

        r.process(inputImage)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text
                if (fullText.isBlank()) {
                    callback(null)
                    return@addOnSuccessListener
                }

                val isChanged = textChangedSignificantly(fullText, lastFullText)
                lastFullText = fullText

                val blocks = visionText.textBlocks.map { block ->
                    OcrTextBlock(
                        text = block.text,
                        bounds = block.boundingBox,
                        confidence = 1f,    // ML Kit on-device doesn't expose per-element confidence
                        lineCount = block.lines.size
                    )
                }

                // Run full analysis pipeline — always use redacted text for logging
                val sensitiveResult = SensitiveInfoDetector.analyze(fullText)
                val safeText = if (sensitiveResult.isSafeToLog) fullText else sensitiveResult.redactedText
                val tone = ToneAnalyzer.analyze(fullText)
                val docType = DocumentClassifier.classify(fullText)
                val elapsed = SystemClock.elapsedRealtime() - startMs

                val result = OcrResult(
                    fullText = fullText,
                    safeText = safeText,
                    blocks = blocks,
                    sensitiveInfo = sensitiveResult,
                    tone = tone,
                    documentType = docType,
                    imageQuality = quality,
                    timestampMs = System.currentTimeMillis(),
                    processingMs = elapsed,
                    isChanged = isChanged
                )

                // Log safe summary only
                if (sensitiveResult.isSafeToLog) {
                    Log.d(TAG, "[$sourceApp] OCR ${fullText.length} chars in ${elapsed}ms | ${docType.type.label}")
                } else {
                    Log.d(TAG, "[$sourceApp] OCR [sensitive content — ${sensitiveResult.detections.size} items] in ${elapsed}ms")
                }

                // Alarm path: phishing/threat detected in screen text
                if (ToneAnalyzer.isAlarm(tone)) {
                    Log.w(TAG, "⚠ Alarm tone in screen text [$sourceApp]: ${tone.summary}")
                }

                callback(result)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "OCR failed: ${e.message}")
                callback(null)
            }
    }

    /** Suspend wrapper around [analyze]. Safe to call from any coroutine. */
    suspend fun analyzeAsync(bitmap: Bitmap, sourceApp: String = ""): OcrResult? =
        suspendCancellableCoroutine { cont ->
            analyze(bitmap, sourceApp) { result -> if (cont.isActive) cont.resume(result) }
        }

    fun close() {
        recognizer?.close()
        recognizer = null
    }

    // --- Image quality assessment ---

    /**
     * Samples a 32×32 thumbnail to quickly estimate brightness.
     * Skips OCR on very dark, very bright, or nearly-blank frames.
     */
    fun assessQuality(bitmap: Bitmap): ImageQuality {
        val thumb = Bitmap.createScaledBitmap(bitmap, 32, 32, false)
        var totalLum = 0.0
        var pixelCount = 0

        for (x in 0 until thumb.width) {
            for (y in 0 until thumb.height) {
                val px = thumb.getPixel(x, y)
                val r = Color.red(px) / 255f
                val g = Color.green(px) / 255f
                val b = Color.blue(px) / 255f
                // Relative luminance (ITU-R BT.709)
                totalLum += 0.2126 * r + 0.7152 * g + 0.0722 * b
                pixelCount++
            }
        }
        thumb.recycle()

        val brightness = if (pixelCount == 0) 0f else (totalLum / pixelCount).toFloat()
        val isTooLight = brightness > 0.97f   // mostly white / overexposed
        val isTooDrawn  = brightness < 0.02f  // mostly black / blank

        // Simple blur estimate: if most pixels are the same color the image is "flat"
        // (heuristic only — Laplacian variance would be better but expensive here)
        val isTooBlurry = false  // reserved for future: add Laplacian via RenderScript or NNAPI

        val isUsable = !isTooLight && !isTooDrawn && !isTooBlurry

        return ImageQuality(brightness, isTooBlurry, isTooLight, isTooDrawn, isUsable)
    }

    // --- Change detection ---

    /**
     * Returns true if the new text differs from previous by more than [CHANGE_THRESHOLD].
     * Uses a character-level Jaccard similarity to avoid re-processing identical screens.
     */
    private fun textChangedSignificantly(newText: String, prevText: String): Boolean {
        if (prevText.isEmpty()) return true
        if (newText == prevText) return false

        // Trigram Jaccard similarity
        val newTrigrams = trigrams(newText)
        val prevTrigrams = trigrams(prevText)
        if (newTrigrams.isEmpty() && prevTrigrams.isEmpty()) return false

        val intersection = (newTrigrams intersect prevTrigrams).size.toFloat()
        val union = (newTrigrams union prevTrigrams).size.toFloat()
        val similarity = if (union == 0f) 1f else intersection / union
        return (1f - similarity) > CHANGE_THRESHOLD
    }

    private fun trigrams(text: String): Set<String> {
        val clean = text.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }
        if (clean.length < 3) return setOf(clean)
        return (0..clean.length - 3).map { clean.substring(it, it + 3) }.toSet()
    }

    // --- Utility ---

    /** Formats an OcrResult for display or AI context injection. */
    fun formatForContext(result: OcrResult): String = buildString {
        appendLine("=== OCR ANALYSIS ===")
        appendLine("Doc type: ${result.documentType.type.label} | ${result.documentType.sensitivity.name}")
        appendLine("Tone: ${result.tone.summary}")
        if (!result.sensitiveInfo.isSafeToLog)
            appendLine("⚠ Sensitive: ${SensitiveInfoDetector.summary(result.sensitiveInfo)}")
        appendLine("Blocks: ${result.blocks.size} | Chars: ${result.fullText.length}")
        appendLine("--- Text (redacted) ---")
        appendLine(result.safeText.take(2000))
    }

    /**
     * Logs the OCR result to CommunicationLog when the source is a messaging app.
     * Only the safeText (redacted) is stored — never the raw fullText.
     */
    fun logToCommLog(result: OcrResult, contact: String, sourceApp: String) {
        if (result.safeText.isBlank()) return
        CommunicationLog.addLog(
            contact   = contact,
            message   = result.safeText.take(400),
            direction = "IN",
            source    = "$sourceApp (OCR)"
        )
    }

    /**
     * Fires a TriggerEngine event with the OCR context.
     * Called by ScreenCaptureService when significant new text is found.
     */
    fun notifyTriggerEngine(context: android.content.Context, result: OcrResult, sourceApp: String) {
        TriggerEngine.initialize(context)
        val summary = "${result.documentType.type.label} detected on screen via OCR" +
            if (result.tone.urgency > 0.4f) " [URGENT]" else ""
        TriggerEngine.evaluateEvent("ScreenText", "[$sourceApp] $summary")
    }
}
