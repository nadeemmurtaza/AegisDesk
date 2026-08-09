package com.newax.aegis.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "embeddings",
    indices = [
        Index("sourceType"),
        Index("sourceId", unique = true)
    ]
)
data class EmbeddingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceType: String,   // "fact" | "memory"
    val sourceId: String,     // person_facts.id or "memory:category:hashCode"
    val text: String,         // original text (allows re-embedding after model upgrade)
    val embedding: ByteArray  // FloatArray serialized as LE bytes
) {
    override fun equals(other: Any?) = other is EmbeddingEntity && id == other.id
    override fun hashCode() = id.hashCode()
}
