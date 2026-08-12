package com.newax.aegis.vision

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.newax.aegis.engine.DocumentClassifier
import com.newax.aegis.engine.ToneAnalyzer
import com.newax.aegis.engine.TriggerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_UNDERSTAND_SCREEN = "com.newax.aegis.action.UNDERSTAND_SCREEN"
        private const val TAG = "NewaxCapture"

        @Volatile var projectionData: Intent? = null
        @Volatile var resultCode: Int = 0
        @Volatile var instance: ScreenCaptureService? = null

        private val _latestFrame = MutableStateFlow<Bitmap?>(null)
        val latestFrame: StateFlow<Bitmap?> = _latestFrame

        private val _latestOcrResult = MutableStateFlow<OcrEngine.OcrResult?>(null)
        val latestOcrResult: StateFlow<OcrEngine.OcrResult?> = _latestOcrResult

        private val activeConsumers = java.util.concurrent.atomic.AtomicInteger(0)

        fun acquireVisualContext(): AutoCloseable {
            activeConsumers.incrementAndGet()
            return AutoCloseable { activeConsumers.decrementAndGet() }
        }

        fun hasConsumer(): Boolean = activeConsumers.get() > 0

        private fun currentSourceApp(): String =
            com.newax.aegis.accessibility.NewaxAccessibilityService.instance?.currentPackage
                ?.ifBlank { "unknown" } ?: "unknown"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var ocrJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        OcrEngine.init()

        val channel = NotificationChannel("vision", "Vision Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, NotificationCompat.Builder(this, "vision")
            .setContentTitle("Newax Vision")
            .setContentText("Capturing screen for visual context")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = projectionData ?: return START_NOT_STICKY
        val mpm = getSystemService(MediaProjectionManager::class.java)
        mediaProjection = mpm.getMediaProjection(resultCode, data)

        val metrics = resources.displayMetrics
        val width   = metrics.widthPixels
        val height  = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image: Image? = reader.acquireLatestImage()
            if (image != null) {
                try {
                    if (!hasConsumer()) { image.close(); return@setOnImageAvailableListener }
                    val planes = image.planes
                    val buffer      = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride   = planes[0].rowStride
                    val rowPadding  = rowStride - pixelStride * width
                    val raw = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
                    raw.copyPixelsFromBuffer(buffer)
                    val scale = 0.5f
                    val scaled = Bitmap.createScaledBitmap(raw, (width * scale).toInt(), (height * scale).toInt(), false)
                    raw.recycle()
                    _latestFrame.value?.recycle()
                    _latestFrame.value = scaled
                } catch (e: Exception) {
                    Log.w(TAG, "Frame error: ${e.message}")
                } finally {
                    image.close()
                }
            }
        }, null)

        if (intent?.action == ACTION_UNDERSTAND_SCREEN) {
            triggerSingleCapture()
        }

        return START_NOT_STICKY
    }

    private fun triggerSingleCapture() {
        serviceScope.launch {
            // Force a subscription so hasConsumer() returns true temporarily
            val subJob = launch { _latestFrame.collect {} }
            
            // Wait for a fresh frame
            delay(500) // Give ImageReader time to acquire a frame
            
            val bitmap = _latestFrame.value
            if (bitmap != null) {
                val sourceApp = currentSourceApp()
                val result = OcrEngine.analyzeAsync(bitmap, sourceApp)
                if (result != null) {
                    _latestOcrResult.value = result
                    if (shouldNotify(result)) {
                        OcrEngine.notifyTriggerEngine(this@ScreenCaptureService, result, sourceApp)
                    }
                }
            }
            subJob.cancel()
        }
    }

    // Removed startOcrLoop

    /**
     * Force an immediate OCR analysis of the current frame, outside the sampling loop.
     * Returns null if no frame is available or OCR fails.
     * Caller blocks the calling coroutine — wrap in withContext(Dispatchers.Default).
     */
    suspend fun analyzeCurrentFrame(): OcrEngine.OcrResult? {
        val bitmap = _latestFrame.value ?: return null
        return OcrEngine.analyzeAsync(bitmap, currentSourceApp())
    }

    /** Returns the last OCR result synchronously (may be slightly stale). */
    fun latestOcrSnapshot(): OcrEngine.OcrResult? = _latestOcrResult.value

    /**
     * Returns a merged screen summary combining the AccessibilityService node tree
     * with any OCR text found in the current frame. Call from MainViewModel.
     */
    fun mergedScreenText(): String {
        val ocrResult = _latestOcrResult.value ?: return ""
        return OcrEngine.formatForContext(ocrResult)
    }

    // --- Notification decision ---

    private fun shouldNotify(result: OcrEngine.OcrResult): Boolean {
        // Always notify on alarms or high-sensitivity docs
        if (ToneAnalyzer.isAlarm(result.tone)) return true
        if (result.sensitiveInfo.sensitivityScore >= 0.7f) return true
        if (result.documentType.sensitivity.rank >= DocumentClassifier.SensitivityLevel.CONFIDENTIAL.rank) return true
        // Notify on significant text appearing in otherwise silent apps
        return result.blocks.size >= 3 && result.fullText.length >= 100
    }

    // --- Lifecycle ---

    override fun onDestroy() {
        instance = null
        ocrJob?.cancel()
        serviceScope.cancel()
        virtualDisplay?.release()
        imageReader?.close()
        _latestFrame.value?.recycle()
        _latestFrame.value = null
        mediaProjection?.stop()
        OcrEngine.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
