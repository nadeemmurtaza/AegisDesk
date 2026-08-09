package com.newax.aegis.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "persons",
    indices = [Index(value = ["name"], unique = true)]
)
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val importanceScore: Float = 0f,
    val sourceCount: Int = 0,
    val totalMentions: Int = 0,
    val lastSeenMs: Long = 0L,
    val profileBuilt: Boolean = false
)
