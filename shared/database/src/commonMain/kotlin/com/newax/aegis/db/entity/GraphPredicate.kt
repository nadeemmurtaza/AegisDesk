package com.newax.aegis.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "predicates",
    indices = [Index("name", unique = true)]
)
data class GraphPredicate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)
