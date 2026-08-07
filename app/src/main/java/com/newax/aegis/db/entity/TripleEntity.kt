package com.newax.aegis.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "triples",
    indices = [
        Index("subject"),
        Index("predicate"),
        Index(value = ["subject", "predicate"]),
        Index("objectValue")
    ]
)
data class TripleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subject: String,
    val predicate: String,
    val objectValue: String,
    val confidence: Float,
    val source: String,
    val createdMs: Long
)
