package com.newax.aegis.engine.dev.db

import android.database.Cursor
import com.newax.aegis.db.AegisDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TableStats(
    val name: String,
    val rowCount: Long,
    val sizeEstimateKb: Long
)

data class QueryResult(
    val columns: List<String>,
    val rows: List<List<String>>,
    val rowCount: Int,
    val latencyMs: Long,
    val error: String? = null
)

data class OrphanReport(
    val orphanedEdges: Int,
    val missingEntityRefs: Int,
    val invalidBlobPointers: Int,
    val nullSubjectRecords: Int
)

data class IndexHealth(
    val tableName: String,
    val indexName: String,
    val isUnique: Boolean,
    val columns: String
)

data class MigrationRecord(val version: Int, val appliedMs: Long, val notes: String)

object DatabaseInspector {

    private val KNOWN_TABLES = listOf(
        "memory_records", "edges", "entities", "predicates", "blobs",
        "person_snapshots", "person_facts", "person_mentions",
        "file_objects", "file_entity_links", "embeddings", "triples"
    )

    suspend fun tableStats(db: AegisDatabase): List<TableStats> = withContext(Dispatchers.IO) {
        val rawDb = db.openHelper.readableDatabase
        KNOWN_TABLES.mapNotNull { table ->
            runCatching {
                val cursor = rawDb.rawQuery("SELECT COUNT(*) FROM $table", null)
                cursor.use { c ->
                    if (c.moveToFirst()) {
                        val count = c.getLong(0)
                        TableStats(table, count, count * 200 / 1024)
                    } else null
                }
            }.getOrNull()
        }
    }

    suspend fun rawQuery(db: AegisDatabase, sql: String, limit: Int = 200): QueryResult =
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            return@withContext try {
                val safeSql = if (sql.trim().uppercase().startsWith("SELECT") || sql.trim().uppercase().startsWith("PRAGMA")) {
                    if (sql.uppercase().contains("LIMIT")) sql else "$sql LIMIT $limit"
                } else {
                    return@withContext QueryResult(emptyList(), emptyList(), 0, 0, "Only SELECT and PRAGMA queries allowed")
                }
                val rawDb = db.openHelper.readableDatabase
                val cursor: Cursor = rawDb.rawQuery(safeSql, null)
                cursor.use { c ->
                    val cols = (0 until c.columnCount).map { c.getColumnName(it) }
                    val rows = mutableListOf<List<String>>()
                    while (c.moveToNext() && rows.size < limit) {
                        rows.add((0 until c.columnCount).map { i ->
                            when (c.getType(i)) {
                                Cursor.FIELD_TYPE_NULL -> "NULL"
                                Cursor.FIELD_TYPE_BLOB -> "<BLOB>"
                                else -> c.getString(i) ?: "NULL"
                            }
                        })
                    }
                    QueryResult(cols, rows, rows.size, System.currentTimeMillis() - start)
                }
            } catch (e: Exception) {
                QueryResult(emptyList(), emptyList(), 0, System.currentTimeMillis() - start, e.message)
            }
        }

    suspend fun detectOrphans(db: AegisDatabase): OrphanReport = withContext(Dispatchers.IO) {
        val rawDb = db.openHelper.readableDatabase
        fun count(sql: String): Int = runCatching {
            rawDb.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }.getOrDefault(0)

        OrphanReport(
            orphanedEdges = count("SELECT COUNT(*) FROM edges WHERE subjectId NOT IN (SELECT id FROM entities)"),
            missingEntityRefs = count("SELECT COUNT(*) FROM edges WHERE objectId IS NOT NULL AND objectId NOT IN (SELECT id FROM entities)"),
            invalidBlobPointers = count("SELECT COUNT(*) FROM blobs WHERE length(data) = 0"),
            nullSubjectRecords = count("SELECT COUNT(*) FROM memory_records WHERE subject = '' OR subject IS NULL")
        )
    }

    suspend fun indexHealth(db: AegisDatabase): List<IndexHealth> = withContext(Dispatchers.IO) {
        val rawDb = db.openHelper.readableDatabase
        val results = mutableListOf<IndexHealth>()
        runCatching {
            val cursor = rawDb.rawQuery(
                "SELECT m.tbl_name, m.name, il.'unique' FROM sqlite_master m JOIN pragma_index_list(m.tbl_name) il ON il.name = m.name WHERE m.type='index'",
                null
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    val table = c.getString(0)
                    val indexName = c.getString(1)
                    val isUnique = c.getInt(2) == 1
                    val colCursor = rawDb.rawQuery("PRAGMA index_info($indexName)", null)
                    val cols = mutableListOf<String>()
                    colCursor.use { cc -> while (cc.moveToNext()) cols.add(cc.getString(2)) }
                    results.add(IndexHealth(table, indexName, isUnique, cols.joinToString(", ")))
                }
            }
        }
        results
    }

    suspend fun sqlCipherState(db: AegisDatabase): String = withContext(Dispatchers.IO) {
        runCatching {
            val rawDb = db.openHelper.readableDatabase
            val cursor = rawDb.rawQuery("PRAGMA cipher_version", null)
            cursor.use { c ->
                if (c.moveToFirst()) "SQLCipher ${c.getString(0)}" else "SQLCipher (version unknown)"
            }
        }.getOrDefault("Unable to query SQLCipher state")
    }

    suspend fun migrationHistory(db: AegisDatabase): String = withContext(Dispatchers.IO) {
        runCatching {
            val rawDb = db.openHelper.readableDatabase
            val cursor = rawDb.rawQuery("PRAGMA user_version", null)
            cursor.use { c ->
                if (c.moveToFirst()) "Current schema version: ${c.getInt(0)}" else "Unknown"
            }
        }.getOrDefault("Unable to query migration history")
    }

    suspend fun memoryRecordSample(db: AegisDatabase, limit: Int = 20): List<Map<String, String>> =
        withContext(Dispatchers.IO) {
            db.memoryRecordDao().current(limit).map { r ->
                mapOf(
                    "id" to r.id.toString(),
                    "type" to r.type.toString(),
                    "subject" to r.subject,
                    "confidence" to r.confidence.toString(),
                    "importance" to r.importance.toString(),
                    "content" to r.content.take(80),
                    "validUntil" to (r.validUntil?.toString() ?: "active")
                )
            }
        }

    suspend fun graphEdgeSample(db: AegisDatabase, limit: Int = 20): QueryResult =
        rawQuery(db, "SELECT id, subjectId, predicateId, objectId, objectValue, confidence FROM edges WHERE validUntil IS NULL ORDER BY id DESC LIMIT $limit", limit)

    suspend fun personSnapshotSample(db: AegisDatabase, limit: Int = 10): QueryResult =
        rawQuery(db, "SELECT * FROM person_snapshots ORDER BY snapshotUpdatedMs DESC LIMIT $limit", limit)

    fun formatTableStats(stats: List<TableStats>): String = buildString {
        append("Database Tables:\n")
        stats.sortedByDescending { it.rowCount }.forEach { t ->
            append("  ${t.name.padEnd(25)} ${t.rowCount} rows (~${t.sizeEstimateKb}KB)\n")
        }
    }
}
