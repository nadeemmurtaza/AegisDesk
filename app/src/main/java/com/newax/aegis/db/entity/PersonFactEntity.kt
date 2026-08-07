package com.newax.aegis.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "person_facts",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("personId"),
        Index("category"),
        Index(value = ["personId", "category"])
    ]
)
data class PersonFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val fact: String,
    val category: String,
    val confidence: Float = 0.7f,
    val source: String = "",
    val timestampMs: Long = 0L
)
