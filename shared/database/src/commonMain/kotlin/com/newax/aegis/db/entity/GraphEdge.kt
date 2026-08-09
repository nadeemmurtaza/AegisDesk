package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "edges",
    indices = [
        Index(value = ["subjectId", "predicateId"]),
        Index(value = ["predicateId", "objectId"]),
        Index(value = ["subjectId", "predicateId", "objectId"]),
        Index("validUntil")
    ]
)
data class GraphEdge(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subjectId: Long,
    val predicateId: Long,
    /** FK → entities.id; null when objectValue holds the value inline. */
    val objectId: Long? = null,
    /** Inline primitive (text, number, date string). Null when objectId is set. */
    val objectValue: String? = null,
    /** 0–100 integer (triple float × 100). */
    @ColumnInfo(defaultValue = "80")
    val confidence: Int = 80,
    @ColumnInfo(defaultValue = "50")
    val importance: Int = 50,
    val createdAt: Long = currentTimeMillis(),
    /** Epoch ms when this fact became valid; null = valid from createdAt. */
    val validFrom: Long? = null,
    /** Epoch ms when this fact expired; null = still current. */
    val validUntil: Long? = null,
    /** FK → entities.id of the source (e.g. a Conversation entity). */
    val sourceId: Long? = null
)
