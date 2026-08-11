package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-peer sync progress — the version vector the anti-entropy loop uses to
 * answer "what do you have that I don't?" (docs/SYNC_DESIGN.md §4.2).
 * One row per paired peer: the highest journal (hlcWall, hlcCounter) this
 * device has applied from that peer. Diffing = send every journal entry with
 * hlc > the peer's vector row for my device id.
 */
@Entity(tableName = "sync_vector")
data class SyncVectorEntity(
    /** The paired peer's device id this watermark tracks. */
    @PrimaryKey val peerDeviceId: String,
    /** Highest hlc wall component applied from this peer. */
    @ColumnInfo(defaultValue = "0")
    val lastAppliedHlcWall: Long = 0,
    /** Highest hlc counter component applied from this peer. */
    @ColumnInfo(defaultValue = "0")
    val lastAppliedHlcCounter: Long = 0,
    /** When this watermark was last advanced. */
    val updatedAt: Long = currentTimeMillis()
)
