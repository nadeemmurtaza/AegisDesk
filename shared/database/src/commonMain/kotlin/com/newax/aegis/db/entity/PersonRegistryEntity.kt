package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "person_snapshots",
    indices = [Index("personEntityId", unique = true), Index("lastInteractionMs")]
)
data class PersonSnapshot(
    @PrimaryKey val personEntityId: Long,
    val displayName: String,
    val canonicalPhone: String? = null,
    val canonicalEmail: String? = null,
    val preferredChannel: String? = null,   // packageName
    @ColumnInfo(defaultValue = "''")
    val preferredLanguage: String = "",
    @ColumnInfo(defaultValue = "''")
    val preferredTone: String = "",         // casual / formal
    @ColumnInfo(defaultValue = "''")
    val relationshipType: String = "",
    val activeProjectId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val pendingCommitmentCount: Int = 0,
    @ColumnInfo(defaultValue = "''")
    val recentTopics: String = "",          // comma-separated
    @ColumnInfo(defaultValue = "0")
    val lastInteractionMs: Long = 0,
    @ColumnInfo(defaultValue = "50")
    val importanceScore: Int = 50,
    @ColumnInfo(defaultValue = "0")
    val snapshotUpdatedMs: Long = 0,
    // ── sync metadata (docs/SYNC_DESIGN.md §4): LWW merge ordering + tombstone ──
    @ColumnInfo(defaultValue = "0")
    val syncHcWall: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val syncHcCounter: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val syncDeviceId: String = "",
    @ColumnInfo(defaultValue = "0")
    val syncTombstone: Boolean = false
)

@Entity(tableName = "person_policies", primaryKeys = ["personEntityId"])
data class PersonPolicy(
    val personEntityId: Long,
    @ColumnInfo(defaultValue = "1")
    val canAutoOpenChat: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    val canAutoDraft: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val canAutoSend: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val canCallWithoutConfirm: Boolean = false,
    @ColumnInfo(defaultValue = "1")
    val canShareFiles: Int = 1,             // 0=block 1=ask 2=allow
    @ColumnInfo(defaultValue = "1")
    val sensitiveActionsRequireConfirm: Boolean = true,
    // ── sync metadata (docs/SYNC_DESIGN.md §4): LWW merge ordering + tombstone ──
    @ColumnInfo(defaultValue = "0")
    val syncHcWall: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val syncHcCounter: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val syncDeviceId: String = "",
    @ColumnInfo(defaultValue = "0")
    val syncTombstone: Boolean = false
)

@Entity(
    tableName = "person_channel_prefs",
    primaryKeys = ["personEntityId", "taskContext"]
)
data class PersonChannelPref(
    val personEntityId: Long,
    val taskContext: String,                // casual | document | urgent | default
    val packageName: String,
    val capability: String,
    @ColumnInfo(defaultValue = "0.8")
    val probability: Float = 0.8f,
    @ColumnInfo(defaultValue = "1")
    val evidenceCount: Int = 1,
    val lastUpdatedMs: Long = currentTimeMillis()
)

@Entity(
    tableName = "commitments",
    indices = [
        Index("debtorPersonId"),
        Index("creditorPersonId"),
        Index("status"),
        Index("dueMs")
    ]
)
data class Commitment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val debtorPersonId: Long? = null,   // null = user
    val creditorPersonId: Long? = null, // null = user
    val debtorLabel: String,
    val creditorLabel: String,
    val action: String,
    val dueMs: Long? = null,
    @ColumnInfo(defaultValue = "'pending'")
    val status: String = STATUS_PENDING,
    @ColumnInfo(defaultValue = "''")
    val source: String = "",
    @ColumnInfo(defaultValue = "80")
    val confidence: Int = 80,
    val createdMs: Long = currentTimeMillis(),
    val resolvedMs: Long? = null
) {
    companion object {
        const val STATUS_PENDING   = "pending"
        const val STATUS_DONE      = "done"
        const val STATUS_CANCELLED = "cancelled"
        const val ACTOR_USER       = "user"
    }
}
