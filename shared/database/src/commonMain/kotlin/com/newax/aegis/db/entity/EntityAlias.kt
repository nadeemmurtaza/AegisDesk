package com.newax.aegis.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "entity_aliases",
    primaryKeys = ["entityId", "alias"],
    indices = [Index("alias")]
)
data class EntityAlias(
    val entityId: Long,
    val alias: String,
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
