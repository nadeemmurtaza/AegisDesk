package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

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
    val preferredLanguage: String = "",
    val preferredTone: String = "",         // casual / formal
    val relationshipType: String = "",
    val activeProjectId: String? = null,
    val pendingCommitmentCount: Int = 0,
    val recentTopics: String = "",          // comma-separated
    val lastInteractionMs: Long = 0,
    val importanceScore: Int = 50,
    val snapshotUpdatedMs: Long = 0
)

@Entity(tableName = "person_policies", primaryKeys = ["personEntityId"])
data class PersonPolicy(
    val personEntityId: Long,
    val canAutoOpenChat: Boolean = true,
    val canAutoDraft: Boolean = true,
    val canAutoSend: Boolean = false,
    val canCallWithoutConfirm: Boolean = false,
    val canShareFiles: Int = 1,             // 0=block 1=ask 2=allow
    val sensitiveActionsRequireConfirm: Boolean = true
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
    val probability: Float = 0.8f,
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
    val status: String = STATUS_PENDING,
    val source: String = "",
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
