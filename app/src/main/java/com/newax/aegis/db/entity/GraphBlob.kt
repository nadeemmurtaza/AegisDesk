package com.newax.aegis.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blobs")
data class GraphBlob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: Int,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
