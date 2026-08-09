package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trigger_rules",
    indices = [Index("enabled"), Index("conditionType")]
)
data class TriggerRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val conditionType: String,   // NOTIFICATION_FROM | APP_OPENED | TIME_OF_DAY | BATTERY_BELOW | KEYWORD_IN_NOTIFICATION | CALENDAR_SOON | SCREEN_CONTENT
    val conditionParams: String, // JSON
    val actionType: String,      // SUBMIT_TO_AI | LAUNCH_APP | EXECUTE_PROCEDURE | REMEMBER_FACT | NOTIFY_USER
    val actionParams: String,    // JSON
    val enabled: Boolean = true,
    val debounceMs: Long = 30_000L,
    val lastFiredMs: Long = 0L,
    val createdMs: Long = currentTimeMillis()
) {
    companion object {
        // conditionType constants
        const val COND_NOTIFICATION_FROM     = "NOTIFICATION_FROM"
        const val COND_APP_OPENED            = "APP_OPENED"
        const val COND_TIME_OF_DAY           = "TIME_OF_DAY"
        const val COND_BATTERY_BELOW         = "BATTERY_BELOW"
        const val COND_KEYWORD_IN_NOTIF      = "KEYWORD_IN_NOTIFICATION"
        const val COND_CALENDAR_SOON         = "CALENDAR_SOON"
        const val COND_SCREEN_CONTENT        = "SCREEN_CONTENT"

        // actionType constants
        const val ACTION_SUBMIT_TO_AI        = "SUBMIT_TO_AI"
        const val ACTION_LAUNCH_APP          = "LAUNCH_APP"
        const val ACTION_EXECUTE_PROCEDURE   = "EXECUTE_PROCEDURE"
        const val ACTION_REMEMBER_FACT       = "REMEMBER_FACT"
        const val ACTION_NOTIFY_USER         = "NOTIFY_USER"
    }
}
