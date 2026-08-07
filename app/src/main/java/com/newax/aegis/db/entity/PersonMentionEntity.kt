package com.newax.aegis.db.entity

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
    val count: Int = 1
)
