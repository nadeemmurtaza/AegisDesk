package com.newax.aegis.engine.dev.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.newax.aegis.engine.dev.log.NewaxLogger
import java.util.concurrent.CopyOnWriteArrayList

data class NotificationRecord(
    val id: Long,
    val timestampMs: Long,
    val packageName: String,
    val title: String,
    val text: String,
    val channelId: String,
    val priority: Int,
    val isOngoing: Boolean,
    val isSilent: Boolean,
    val category: String?,
    val extras: Map<String, String>
)

data class NotificationChannelInfo(
    val id: String,
    val name: String,
    val importance: String,
    val sound: String?,
    val vibration: Boolean,
    val lights: Boolean,
    val bypassDnd: Boolean
)

object NotificationDebugger {

    private const val MAX_RECORDS = 200
    private val received = CopyOnWriteArrayList<NotificationRecord>()
    private val posted = CopyOnWriteArrayList<NotificationRecord>()
    private var recordIdCounter = 0L

    fun onNotificationReceived(
        packageName: String,
        title: String,
        text: String,
        channelId: String,
        priority: Int,
        isOngoing: Boolean,
        isSilent: Boolean,
        category: String? = null,
        extras: Map<String, String> = emptyMap()
    ) {
        val record = NotificationRecord(
            id = ++recordIdCounter,
            timestampMs = System.currentTimeMillis(),
            packageName = packageName,
            title = title,
            text = text,
            channelId = channelId,
            priority = priority,
            isOngoing = isOngoing,
            isSilent = isSilent,
            category = category,
            extras = extras
        )
        received.add(record)
        if (received.size > MAX_RECORDS) received.removeAt(0)
        NewaxLogger.d("NotifDebugger", "RX [$packageName] $title: $text")
    }

    fun onNotificationPosted(record: NotificationRecord) {
        posted.add(record)
        if (posted.size > MAX_RECORDS) posted.removeAt(0)
    }

    fun listChannels(context: Context): List<NotificationChannelInfo> {
        if (Build.VERSION.SDK_INT < 26) return emptyList()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.notificationChannels.map { ch ->
            NotificationChannelInfo(
                id = ch.id,
                name = ch.name.toString(),
                importance = importanceLabel(ch.importance),
                sound = ch.sound?.toString(),
                vibration = ch.shouldVibrate(),
                lights = ch.shouldShowLights(),
                bypassDnd = ch.canBypassDnd()
            )
        }
    }

    fun activeNotifications(context: Context): Int {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= 23) nm.activeNotifications.size else -1
    }

    fun postTestNotification(context: Context, title: String = "Newax Test", text: String = "Debug notification") {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "aegis_debug"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Newax Debug", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = Notification.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        nm.notify(9999, builder.build())
        NewaxLogger.i("NotifDebugger", "Test notification posted")
    }

    fun cancelTestNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(9999)
    }

    fun recentReceived(n: Int = 50): List<NotificationRecord> = received.takeLast(n)
    fun recentPosted(n: Int = 50): List<NotificationRecord> = posted.takeLast(n)
    fun byPackage(pkg: String): List<NotificationRecord> = received.filter { it.packageName == pkg }

    fun clearLogs() {
        received.clear()
        posted.clear()
    }

    fun report(context: Context): String = buildString {
        append("Notification Debugger:\n")
        append("  Received: ${received.size}  Posted: ${posted.size}  Active: ${activeNotifications(context)}\n")
        val channels = listChannels(context)
        append("  Channels: ${channels.size}\n")
        channels.take(5).forEach { ch -> append("    [${ch.id}] ${ch.name} importance=${ch.importance}\n") }
        received.takeLast(5).forEach { n -> append("  [${n.packageName}] ${n.title}: ${n.text.take(40)}\n") }
    }

    private fun importanceLabel(i: Int) = when (i) {
        NotificationManager.IMPORTANCE_NONE -> "NONE"
        NotificationManager.IMPORTANCE_MIN -> "MIN"
        NotificationManager.IMPORTANCE_LOW -> "LOW"
        NotificationManager.IMPORTANCE_DEFAULT -> "DEFAULT"
        NotificationManager.IMPORTANCE_HIGH -> "HIGH"
        NotificationManager.IMPORTANCE_MAX -> "MAX"
        else -> "UNKNOWN($i)"
    }
}
