package com.newax.aegis.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "predicates",
    indices = [Index("name", unique = true)]
)
data class GraphPredicate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
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
