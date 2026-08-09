package com.newax.aegis.db.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "entity_aliases",
    primaryKeys = ["entityId", "alias"],
    indices = [Index("alias")]
)
data class EntityAlias(
    val entityId: Long,
    val alias: String
)
