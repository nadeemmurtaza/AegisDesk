package com.newax.aegis.engine.memory

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.MemoryRecord
import com.newax.aegis.db.entity.RecordType
import com.newax.aegis.engine.TypeaheadTrie
import com.newax.aegis.engine.embedding.VectorStore

object CanonicalStore {

    fun write(
        db: AegisDatabase,
        content: String,
        type: Int = RecordType.FACT,
        category: String = "",
        subject: String = "",
        source: String = "",
        confidence: Int = 80,
        importance: Int = 50
    ): Long {
        val hash = sha256prefix(content)
        val now  = System.currentTimeMillis()
        val existing = kotlinx.coroutines.runBlocking { db.memoryRecordDao().findByHash(hash) }
        if (existing != null) {
            kotlinx.coroutines.runBlocking { db.memoryRecordDao().bumpImportance(existing.id, maxOf(existing.importance, importance), now) }
            return existing.id
        }
        val recordId = kotlinx.coroutines.runBlocking {
            db.memoryRecordDao().insert(
            MemoryRecord(
                type        = type,
                content     = content,
                category    = category,
                subject     = subject,
                source      = source,
                confidence  = confidence,
                importance  = importance,
                createdAt   = now,
                updatedAt   = now,
                contentHash = hash
            )
        )
        }
        // Derive: vector embedding (governor-gated — queues behind LLM if critical running)
        val embId = "record:$recordId"
        VectorStore.submitIndexFact(db, recordId, content)
        kotlinx.coroutines.runBlocking { db.memoryRecordDao().updateEmbeddingId(recordId, embId) }
        // Derive: trie (subject words + first 4 content words)
        val triePath = "RECORD:$recordId"
        (subject.split("\\s+".toRegex()) + content.split("\\s+".toRegex()).take(4))
            .filter { it.length >= 3 }
            .forEach { w -> TypeaheadTrie.insert(w.lowercase(), triePath) }
        return recordId
    }

    fun invalidate(db: AegisDatabase, recordId: Long) {
        kotlinx.coroutines.runBlocking { db.memoryRecordDao().invalidate(recordId, System.currentTimeMillis()) }
    }

    private fun sha256prefix(content: String): String = try {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.trim().lowercase().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    } catch (_: Exception) { content.hashCode().toString() }
}
