package com.newax.aegis.engine.learning

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.bus.AegisEvent
import com.newax.aegis.engine.bus.AegisEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MemoryCompiler {

    private const val MAX_AGE_DAYS = 365L
    private const val LOW_CONFIDENCE_THRESHOLD = 30
    private const val HIGH_IMPORTANCE_THRESHOLD = 70

    data class CompilationResult(
        val memoriesScanned: Int,
        val contradictionsResolved: Int,
        val lowConfidenceExpired: Int,
        val summariesGenerated: Int,
        val durationMs: Long
    )

    suspend fun compile(db: AegisDatabase): CompilationResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val dao = db.memoryRecordDao()
        val now = System.currentTimeMillis()
        val allRecords = dao.current(5000)
        var contradictions = 0
        var lowConfExpired = 0

        val bySubject = allRecords.groupBy { it.subject.lowercase().trim() }
        for ((subject, records) in bySubject) {
            if (subject.isBlank()) continue
            val byPredicate = records.groupBy { extractPredicateKey(it.content) }
            for ((_, group) in byPredicate) {
                if (group.size < 2) continue
                val sorted = group.sortedByDescending { it.updatedAt }
                sorted.drop(1).forEach { old ->
                    dao.invalidate(old.id, now)
                    contradictions++
                }
            }
        }

        val staleMs = now - MAX_AGE_DAYS * 24 * 3600 * 1000
        allRecords.filter {
            it.updatedAt < staleMs &&
            it.confidence < LOW_CONFIDENCE_THRESHOLD &&
            it.importance < HIGH_IMPORTANCE_THRESHOLD
        }.forEach {
            dao.invalidate(it.id, now)
            lowConfExpired++
        }

        val result = CompilationResult(
            memoriesScanned = allRecords.size,
            contradictionsResolved = contradictions,
            lowConfidenceExpired = lowConfExpired,
            summariesGenerated = bySubject.size,
            durationMs = System.currentTimeMillis() - startMs
        )
        AegisEventBus.emit(AegisEvent.MemoryConsolidated(allRecords.size, result.durationMs))
        result
    }

    fun extractPredicateKey(content: String): String {
        val lower = content.lowercase()
        return when {
            lower.contains("works at") || lower.contains("job") || lower.contains("employer") -> "employer"
            lower.contains("lives in") || lower.contains("located in") || lower.contains("based in") -> "location"
            lower.contains("phone") || lower.contains("mobile") -> "phone"
            lower.contains("email") -> "email"
            lower.contains("born") || lower.contains("birthday") || lower.contains("age") -> "birthday"
            lower.contains("married") || lower.contains("spouse") || lower.contains("partner") -> "relationship"
            lower.contains("likes") || lower.contains("enjoys") || lower.contains("prefers") -> "preference"
            lower.contains("dislikes") || lower.contains("hates") || lower.contains("avoids") -> "aversion"
            lower.contains("project") -> "project"
            lower.contains("meeting") || lower.contains("appointment") -> "meeting"
            else -> content.take(40).lowercase()
        }
    }

    suspend fun generateSubjectSummary(subject: String, db: AegisDatabase): String? =
        withContext(Dispatchers.IO) {
            val records = db.memoryRecordDao().findBySubject(subject, 10)
            if (records.isEmpty()) return@withContext null
            buildString {
                append("$subject:\n")
                records.forEach { append("• ${it.content}\n") }
            }
        }
}
