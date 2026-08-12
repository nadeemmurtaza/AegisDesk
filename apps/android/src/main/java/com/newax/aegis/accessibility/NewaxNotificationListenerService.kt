package com.newax.aegis.accessibility

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.newax.aegis.engine.CommunicationLog
import com.newax.aegis.engine.DetoxBuffer
import com.newax.aegis.engine.DocumentClassifier
import com.newax.aegis.engine.SensitiveInfoDetector
import com.newax.aegis.engine.ToneAnalyzer
import com.newax.aegis.engine.TriggerEngine

/**
 * Listens for incoming notifications across 15+ messaging/productivity apps.
 * Applies tone analysis, sensitive info detection, document classification,
 * and context correlation before routing to TriggerEngine.
 *
 * SECURITY: raw text from high-sensitivity messages is NEVER written to logcat.
 * Sensitive content is always redacted before logging.
 */
class NewaxNotificationListenerService : NotificationListenerService() {

    private val systemReceiver = com.newax.aegis.engine.NewaxSystemReceiver()
    private var receiverRegistered = false

    override fun onCreate() {
        super.onCreate()
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_SCREEN_OFF)
            addAction(android.content.Intent.ACTION_SCREEN_ON)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, systemReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    override fun onDestroy() {
        if (receiverRegistered) { unregisterReceiver(systemReceiver); receiverRegistered = false }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return

        // Detox Mode: block non-urgent distraction apps first
        if (pkg in DETOX_APPS) {
            val extras = sbn.notification.extras
            val t  = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
            val tx = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
            DetoxBuffer.addNotification(pkg, "$t: $tx")
            cancelNotification(sbn.key)
            return
        }

        val appName = APP_NAMES[pkg] ?: return  // only process known apps

        val extras = sbn.notification.extras
        val sender = extras.getString(android.app.Notification.EXTRA_TITLE) ?: "Unknown"
        val rawText = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return
        if (rawText.isBlank()) return

        // --- Analysis pipeline ---
        val sensitiveResult = SensitiveInfoDetector.analyze(rawText)
        val safeText = if (sensitiveResult.isSafeToLog) rawText else sensitiveResult.redactedText
        val tone = ToneAnalyzer.analyze(rawText)
        val docType = DocumentClassifier.classify(rawText).type.label

        // Log to CommunicationLog with redacted text only
        CommunicationLog.addLog(
            contact   = sender,
            message   = safeText.take(300),
            direction = "IN",
            source    = appName
        )
        com.newax.aegis.engine.trigger.TriggerEngine.onNotification(sender, safeText.take(300), pkg)

        val entry = InboxEntry(
            key                  = sbn.key,
            appName              = appName,
            sender               = sender,
            text                 = safeText.take(500),
            rawText              = rawText,
            tone                 = tone,
            hasSensitiveContent  = !sensitiveResult.isSafeToLog,
            sensitivitySummary   = SensitiveInfoDetector.summary(sensitiveResult),
            documentType         = docType,
            timestampMs          = System.currentTimeMillis()
        )

        val actions = sbn.notification.actions
        if (actions != null) {
            for (action in actions) {
                val remoteInputs = action.remoteInputs
                if (remoteInputs != null && remoteInputs.isNotEmpty()) {
                    activeReplies[sbn.key] = ReplyAction(action.actionIntent, remoteInputs[0])
                    break
                }
            }
        }

        synchronized(inbox) {
            inbox.addLast(entry)
            while (inbox.size > MAX_INBOX) {
                val removed = inbox.removeFirst()
                activeReplies.remove(removed.key)
            }
        }

        // Safe logcat — never expose raw sensitive text
        if (sensitiveResult.isSafeToLog) {
            Log.d(TAG, "[$appName] $sender: ${safeText.take(80)}")
        } else {
            Log.d(TAG, "[$appName] $sender: [${sensitiveResult.detections.size} sensitive item(s) redacted]")
        }

        // Alarm path
        if (ToneAnalyzer.isAlarm(tone)) {
            Log.w(TAG, "⚠ ALARM from $sender via $appName: ${tone.summary}")
            TriggerEngine.initialize(this)
            TriggerEngine.evaluateEvent(
                "AlarmNotification",
                "[$appName] $sender sent an alarming message. Tone: ${tone.summary}"
            )
        }

        // OTP path
        if (sensitiveResult.detections.any { it.type == SensitiveInfoDetector.SensitiveType.OTP }) {
            TriggerEngine.initialize(this)
            TriggerEngine.evaluateEvent("OtpReceived", "OTP received from $sender via $appName")
        }

        // Normal routing
        val urgencyTag = if (tone.urgency > 0.3f) " [URGENT]" else ""
        TriggerEngine.initialize(this)
        TriggerEngine.evaluateEvent(
            "Notification",
            "[$appName] $sender: ${safeText.take(120)}$urgencyTag"
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    // Single companion object merging both state/helpers and configuration constants
    companion object {
        private const val TAG      = "NewaxInbox"
        private const val MAX_INBOX = 30

        data class ReplyAction(
            val pendingIntent: android.app.PendingIntent,
            val remoteInput: android.app.RemoteInput
        )

        data class InboxEntry(
            val key: String,
            val appName: String,
            val sender: String,
            val text: String,
            val rawText: String,              // NOT logged — only used for OTP extraction
            val tone: ToneAnalyzer.ToneProfile,
            val hasSensitiveContent: Boolean,
            val sensitivitySummary: String,
            val documentType: String,
            val timestampMs: Long
        )

        val inbox = ArrayDeque<InboxEntry>()
        val activeReplies = mutableMapOf<String, ReplyAction>()

        fun replyToNotification(context: android.content.Context, key: String, text: String): Boolean {
            val replyAction = activeReplies.remove(key) ?: return false
            val intent = android.content.Intent().apply {
                android.app.RemoteInput.addResultsToIntent(
                    arrayOf(replyAction.remoteInput),
                    this,
                    android.os.Bundle().apply {
                        putCharSequence(replyAction.remoteInput.resultKey, text)
                    }
                )
            }
            return try {
                replyAction.pendingIntent.send(context, 0, intent)
                true
            } catch (e: Exception) {
                false
            }
        }

        fun getInboxSummary(): String {
            if (inbox.isEmpty()) return "Your inbox is clear."
            return inbox.joinToString("\n\n") { entry ->
                buildString {
                    if (activeReplies.containsKey(entry.key)) {
                        appendLine("[${entry.appName}] ${entry.sender} (Key: ${entry.key})")
                    } else {
                        appendLine("[${entry.appName}] ${entry.sender}")
                    }
                    appendLine(entry.text)
                    if (entry.tone.urgency > 0.4f || entry.tone.phishingRisk > 0.3f)
                        appendLine("⚠ ${entry.tone.summary}")
                }
            }
        }

        fun clearInbox() = inbox.clear()

        /** Returns the most recently received OTP from any source. Value is never logged. */
        fun latestOtp(): String? {
            for (entry in inbox.reversed()) {
                val otp = SensitiveInfoDetector.extractOtp(entry.rawText)
                if (otp != null) return otp
            }
            return null
        }

        // All supported apps with display names
        private val APP_NAMES = mapOf(
            "com.google.android.gm"              to "Gmail",
            "com.microsoft.office.outlook"       to "Outlook",
            "com.Slack"                          to "Slack",
            "com.microsoft.teams"                to "Teams",
            "com.whatsapp"                       to "WhatsApp",
            "com.whatsapp.w4b"                   to "WhatsApp Business",
            "org.telegram.messenger"             to "Telegram",
            "org.thoughtcrime.securesms"         to "Signal",
            "com.facebook.orca"                  to "Messenger",
            "com.instagram.android"              to "Instagram DM",
            "com.twitter.android"                to "Twitter/X DM",
            "com.linkedin.android"               to "LinkedIn",
            "com.discord"                        to "Discord",
            "us.zoom.videomeetings"              to "Zoom",
            "com.google.android.apps.messaging"  to "SMS",
            "com.samsung.android.messaging"      to "Samsung Messages",
            "com.android.mms"                    to "Messages",
            "com.viber.voip"                     to "Viber",
            "com.snapchat.android"               to "Snapchat"
        )

        // Apps to suppress in Detox Mode
        private val DETOX_APPS = setOf(
            "com.zhiliaoapp.musically",
            "com.reddit.frontpage",
            "com.youtube.android"
        )
    }
}
