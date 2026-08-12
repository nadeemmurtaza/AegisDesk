package com.newax.aegis.engine.learning

import com.newax.aegis.db.AegisDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ForgettingEngine {

    private const val HIGH_IMPORTANCE_KEEP = 80
    private const val VERY_LOW_CONFIDENCE = 20
    private const val AGE_EXPIRE_DAYS = 180L
    private const val UNUSED_EXPIRE_DAYS = 90L

    data class ForgettingResult(
        val scanned: Int,
        val expired: Int,
        val kept: Int,
        val durationMs: Long
    )

    suspend fun run(db: AegisDatabase): ForgettingResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val dao = db.memoryRecordDao()
        val now = System.currentTimeMillis()
        val ageThreshold = now - AGE_EXPIRE_DAYS * 24 * 3600 * 1000
        val unusedThreshold = now - UNUSED_EXPIRE_DAYS * 24 * 3600 * 1000

        val records = dao.current(5000)
        var expired = 0
        var kept = 0

        for (record in records) {
            if (record.importance >= HIGH_IMPORTANCE_KEEP) {
                kept++
                continue
            }
            if (record.createdAt < ageThreshold && record.confidence < VERY_LOW_CONFIDENCE) {
                dao.invalidate(record.id, now)
                expired++
                continue
            }
            if (record.updatedAt < unusedThreshold && record.confidence < 40) {
                dao.bumpImportance(record.id, (record.importance - 5).coerceAtLeast(0), now)
                kept++
                continue
            }
            kept++
        }

        ForgettingResult(
            scanned = records.size,
            expired = expired,
            kept = kept,
            durationMs = System.currentTimeMillis() - startMs
        )
    }

    fun shouldForget(
        confidence: Int,
        importance: Int,
        ageMs: Long,
        usageCount: Int
    ): Boolean {
        if (importance >= HIGH_IMPORTANCE_KEEP) return false
        val ageDays = ageMs / (24 * 3600 * 1000f)
        if (confidence < VERY_LOW_CONFIDENCE && ageDays > AGE_EXPIRE_DAYS) return true
        if (usageCount == 0 && confidence < 35 && ageDays > UNUSED_EXPIRE_DAYS) return true
        return false
    }

    fun ebbinghausRetention(initialStrength: Float, stabilityDays: Float, elapsedDays: Float): Float =
        initialStrength * Math.exp(-elapsedDays.toDouble() / stabilityDays).toFloat()
}
