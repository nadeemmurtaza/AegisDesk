package com.newax.aegis.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

/**
 * Receives incoming SMS messages and runs the full analysis pipeline:
 * sensitive info detection (OTP/credentials), tone analysis (phishing/urgency),
 * document classification, and contact lookup before routing to TriggerEngine.
 *
 * Raw SMS body is NEVER written to logcat — only redacted text.
 */
class NewaxSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        TriggerEngine.initialize(context)

        for (sms in Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
            val sender = sms.originatingAddress ?: "Unknown"
            val body   = sms.messageBody ?: continue

            // --- Sensitive info detection ---
            val sensitiveResult = SensitiveInfoDetector.analyze(body)
            val safeBody = if (sensitiveResult.isSafeToLog) body else sensitiveResult.redactedText

            // --- Tone analysis ---
            val tone = ToneAnalyzer.analyze(body)

            // --- Document classification ---
            val docType = DocumentClassifier.classify(body)

            // --- Log to CommunicationLog (redacted text only) ---
            CommunicationLog.addLog(
                contact   = sender,
                message   = safeBody.take(300),
                direction = "IN",
                source    = "SMS"
            )

            // --- Safe logcat output ---
            if (sensitiveResult.isSafeToLog) {
                Log.d(TAG, "SMS from $sender: ${safeBody.take(60)}")
            } else {
                Log.d(TAG, "SMS from $sender: [${sensitiveResult.detections.size} sensitive item(s) — redacted]")
            }

            // --- OTP path: fire dedicated event ---
            if (sensitiveResult.detections.any { it.type == SensitiveInfoDetector.SensitiveType.OTP }) {
                Log.i(TAG, "OTP SMS detected from $sender")
                TriggerEngine.evaluateEvent("OtpSms",
                    "OTP received via SMS from $sender — use latestOtp() to retrieve it safely"
                )
            }

            // --- Phishing / alarm path ---
            if (ToneAnalyzer.isAlarm(tone)) {
                Log.w(TAG, "⚠ ALARM SMS from $sender: ${tone.summary}")
                TriggerEngine.evaluateEvent("AlarmSms",
                    "SMS from $sender flagged: ${tone.summary}"
                )
            }

            // --- Normal routing ---
            val docLabel = if (docType.type != DocumentClassifier.DocType.UNKNOWN)
                " [${docType.type.label}]" else ""
            val urgencyLabel = if (tone.urgency > 0.4f) " [URGENT]" else ""
            val eventText = "SMS from $sender: ${safeBody.take(120)}$docLabel$urgencyLabel"
            TriggerEngine.evaluateEvent("SMS", eventText)
        }
    }

    companion object {
        private const val TAG = "NewaxSms"
    }
}
