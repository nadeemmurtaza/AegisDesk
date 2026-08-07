package com.newax.aegis.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Canonical record — the single authoritative copy of any piece of knowledge.
 * All other stores (EncryptedMemory, PersonFact, VectorStore, GraphStore,
 * TypeaheadTrie) are derived indexes that point back to this ID.
 *
 * Fact text lives here once. Indexes are rebuilt from these rows.
 */
@Entity(
    tableName = "memory_records",
    indices = [
        Index("contentHash"),
        Index("subject"),
        Index("type"),
        Index("createdAt"),
        Index("validUntil")
    ]
)
data class MemoryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: Int,
    val content: String,
    val category: String  = "",
    val subject: String   = "",
    val source: String    = "",
    val confidence: Int   = 80,
    val importance: Int   = 50,
    val createdAt: Long   = System.currentTimeMillis(),
    val updatedAt: Long   = System.currentTimeMillis(),
    val validFrom: Long?  = null,
    /** Null means still current. Stamped when this fact is superseded. */
    val validUntil: Long? = null,
    /** First 24 hex chars of SHA-256(lower(content)) for deduplication. */
    val contentHash: String = "",
    /** FK → edges.id if this record is backed by a normalized graph edge. */
    val graphEdgeId: Long?  = null,
    /** VectorStore sourceId (e.g. "record:123") for embedding pointer. */
    val embeddingId: String? = null
)

object RecordType {
    const val FACT        = 1
    const val PREFERENCE  = 2
    const val CONTACT     = 3
    const val PROJECT     = 4
    const val COMM_LOG    = 5
    const val GRAPH_EDGE  = 6
    const val EVENT       = 7
    const val HABIT       = 8
    const val OBSERVATION = 9
}
