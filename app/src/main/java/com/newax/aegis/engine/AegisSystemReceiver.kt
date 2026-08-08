package com.newax.aegis.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.telecom.VideoProfile
import android.telephony.TelephonyManager

/**
 * Listens for system-level events (Battery, Calls).
 */
class AegisSystemReceiver : BroadcastReceiver() {

    private fun setSpeakerphoneOn(audioManager: AudioManager, on: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                val speaker = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) audioManager.setCommunicationDevice(speaker)
            } else {
                audioManager.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION") // No AudioManager.setCommunicationDevice() equivalent before API 31.
            audioManager.isSpeakerphoneOn = on
        }
    }
    override fun onReceive(context: Context, intent: Intent) {
        TriggerEngine.initialize(context)
        
        when (intent.action) {
            Intent.ACTION_SCREEN_OFF -> {
                // Task 2: Exception Logic
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                
                // Exceptions: Music is playing or a call is active
                var exceptionActive = false
                try {
                    exceptionActive = audioManager.isMusicActive || telecomManager.isInCall
                } catch (e: SecurityException) {
                    exceptionActive = audioManager.isMusicActive
                }
                
                if (!exceptionActive) {
                    TriggerEngine.evaluateEvent("ScreenOff", "Screen locked. Evaluate if connectivity needs to be turned off.")
                } else {
                    android.util.Log.i("AegisSystem", "Screen off but exception active (Music/Call). Keeping connectivity on.")
                }
            }
            Intent.ACTION_BATTERY_LOW -> {
                TriggerEngine.evaluateEvent("Battery", "Battery is running low.")
            }
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                @Suppress("DEPRECATION") // No non-deprecated way to read the incoming number from a plain BroadcastReceiver; the full replacement is a CallScreeningService.
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"
                if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                    TriggerEngine.evaluateEvent("Call", "Incoming call from $number")

                    // Auto-answer logic for Call Agent — requires all gates to pass
                    AutomationSettings.init(context)
                    val callAgentEnabled = AutomationSettings.isEnabled(AutomationToggle.CALL_AGENT)
                    val callerKnown = number != "Unknown" && number.isNotBlank()
                    val hasPermission = context.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    if (callAgentEnabled && callerKnown && hasPermission) {
                        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                        @Suppress("DEPRECATION")
                        telecomManager.acceptRingingCall(VideoProfile.STATE_AUDIO_ONLY)

                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        audioManager.mode = AudioManager.MODE_IN_CALL
                        setSpeakerphoneOn(audioManager, true)

                        context.startService(Intent(context, com.newax.aegis.voice.VoiceRecognitionService::class.java))
                    }
                } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
                    // Call ended, turn off speakerphone and stop Vosk
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.mode = AudioManager.MODE_NORMAL
                    setSpeakerphoneOn(audioManager, false)
                    context.stopService(Intent(context, com.newax.aegis.voice.VoiceRecognitionService::class.java))
                }
            }
        }
    }
}
