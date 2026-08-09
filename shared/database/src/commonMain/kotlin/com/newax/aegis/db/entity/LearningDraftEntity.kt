package com.newax.aegis.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "learning_drafts",
    indices = [
        Index("status"),
        Index("subjectName"),
        Index(value = ["status", "timestampMs"])
    ]
)
data class LearningDraftEntity(
    @PrimaryKey val id: String,
    val category: String,
    val fact: String,
    val source: String = "",
    val sourceSnippet: String = "",
    val confidence: Float = 0.7f,
    val status: String = "PENDING",
    val subjectName: String? = null,
    val timestampMs: Long = 0L
)
