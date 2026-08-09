package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "entities",
    indices = [Index("canonicalName")]
)
data class GraphEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val type: Int = 0,
    val canonicalName: String,
    val payloadPointer: Long? = null,
    val createdAt: Long = currentTimeMillis()
)
