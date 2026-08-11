package com.newax.aegis.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "person_mentions",
    primaryKeys = ["personId", "sourceName"],
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("personId")]
)
data class PersonMentionEntity(
    val personId: Long,
    val sourceName: String,
    val count: Int = 1,
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
