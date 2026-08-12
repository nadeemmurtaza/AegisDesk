package com.newax.aegis.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Service that continuously listens for a Wake Word ("hey aegis") using the open-source Vosk engine.
 * Requires a Vosk acoustic model bundled in the app assets.
 */
class VoiceRecognitionService : Service(), RecognitionListener {
    companion object {
        var isListening = false
        var ambientMode = "None"
        val ambientTranscript = StringBuilder()
        
        fun endAmbientMode() {
            if (ambientMode == "None") return
            val transcript = ambientTranscript.toString()
            ambientTranscript.clear()
            
            val systemPrompt = if (ambientMode == "Meeting") {
                "[Meeting Transcript]\n$transcript\n\nPlease summarize this meeting and extract action items. Save them to memory."
            } else {
                "[Lecture Transcript]\n$transcript\n\nPlease create detailed study notes and key concepts from this lecture. Save them to memory."
            }
            
            com.newax.aegis.engine.TriggerEngine.triggerEvents.tryEmit(systemPrompt)
            ambientMode = "None"
        }
    }

    private var speechService: SpeechService? = null
    private var model: Model? = null
    private var spkModel: org.vosk.SpeakerModel? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("voice", "Voice Recognition", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(2, NotificationCompat.Builder(this, "voice")
            .setContentTitle("Newax Voice")
            .setContentText("Listening for 'Hey Newax' (Vosk)")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (model != null && spkModel != null) {
            startListening()
            return START_STICKY
        }
        
        StorageService.unpack(this, "model", "model",
            { m ->
                model = m
                Thread {
                    try {
                        val spkPath = StorageService.sync(this, "spk", "spk")
                        spkModel = org.vosk.SpeakerModel(spkPath)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            startListening()
                        }
                    } catch (e: Exception) {
                        Log.e("NewaxVoice", "Failed to unpack SPK model: ${e.message}")
                    }
                }.start()
            },
            { e ->
                Log.e("NewaxVoice", "Failed to unpack Vosk model: ${e.message}")
                stopSelf()
            }
        )
        return START_STICKY
    }
    
    private fun startListening() {
        if (speechService != null) return
        try {
            // Initialize with Speaker Verification model
            val recognizer = Recognizer(model, 16000.0f, spkModel)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)
            isListening = true
            Log.i("NewaxVoice", "Vosk Full-Text listener started.")
        } catch (e: Exception) {
            Log.e("NewaxVoice", "Failed to start speech service", e)
            stopSelf()
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        // Ignored for wake word
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        
        val text = hypothesis.substringAfter("\"text\" : \"").substringBefore("\"")
        
        if (text.isNotBlank()) {
            if (ambientMode == "Meeting" || ambientMode == "Lecture") {
                ambientTranscript.append(text).append(" ")
                Log.d("NewaxVoice", "[$ambientMode] Appended: $text")
            } else if (text.contains("hey aegis") || text.contains("ہیلو ایجس")) {
                Log.i("NewaxVoice", "Wake word detected!")
            } else {
                Log.i("NewaxVoice", "Transcribed caller: $text")
                com.newax.aegis.engine.TriggerEngine.initialize(this)
                val systemPrompt = "[Live Call] Caller said: $text\nReply briefly and conversationally."
                com.newax.aegis.engine.TriggerEngine.triggerEvents.tryEmit(systemPrompt)
            }
        }
    }

    override fun onFinalResult(hypothesis: String?) {}

    override fun onError(e: Exception?) {
        Log.e("NewaxVoice", "Vosk error", e)
    }

    override fun onTimeout() {
        speechService?.startListening(this) // Restart listening on timeout
    }

    override fun onDestroy() {
        speechService?.stop()
        speechService?.shutdown()
        model?.close()
        spkModel?.close()
        isListening = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
