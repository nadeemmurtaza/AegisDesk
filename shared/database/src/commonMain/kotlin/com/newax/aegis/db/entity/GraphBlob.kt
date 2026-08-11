package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blobs")
data class GraphBlob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: Int,
    val content: String,
    val createdAt: Long = currentTimeMillis(),
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
