package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "kv_store")
data class KvStoreEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedMs: Long = currentTimeMillis()
)
