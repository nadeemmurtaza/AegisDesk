package com.newax.aegis.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
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

/**
 * External-content FTS4 index over [PersonFactEntity]. Declaring it as a Room entity
 * (rather than creating it by raw SQL in a Callback) means Room owns the CREATE and the
 * content-sync triggers, so the table exists for migrated installs and not just fresh ones.
 */
@Fts4(contentEntity = PersonFactEntity::class)
@Entity(tableName = "person_facts_fts")
data class PersonFactFts(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val fact: String,
    val category: String,
    val source: String
)
