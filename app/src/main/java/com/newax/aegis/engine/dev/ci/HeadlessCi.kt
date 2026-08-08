package com.newax.aegis.engine.dev.ci

import android.content.Context
import androidx.sqlite.db.SimpleSQLiteQuery
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.MemoryRecord
import com.newax.aegis.db.entity.PersonEntity
import com.newax.aegis.db.entity.PersonFactEntity
import com.newax.aegis.db.entity.RecordType
import com.newax.aegis.engine.compiler.ProcedureCompiler
import com.newax.aegis.engine.dev.db.DatabaseInspector
import com.newax.aegis.engine.dev.log.AegisLogger
import com.newax.aegis.engine.dev.search.SearchLaboratory
import com.newax.aegis.engine.dev.trace.DecisionInspector
import com.newax.aegis.engine.metrics.MetricsEngine
import kotlinx.coroutines.runBlocking

object HeadlessCi {

    suspend fun execute(context: Context, db: AegisDatabase?, cmd: String, arg1: String, arg2: String, arg3: String): String {
        return try {
            when (cmd) {
                "ping" -> "pong"
                "reset_test_state" -> resetTestState(db)
                "seed_person" -> seedPerson(db, arg1.ifBlank { "TestPerson" }, arg2)
                "seed_memory" -> seedMemory(db, arg1.ifBlank { "test" }, arg2.ifBlank { "TestSubject" }, arg3.ifBlank { "50" }.toIntOrNull() ?: 50)
                "seed_file" -> "seed_file: not yet automated (requires MediaStore)"
                "run_query" -> runQuery(db, arg1)
                "run_procedure" -> runProcedure(db, arg1)
                "dump_trace" -> dumpTrace(arg1)
                "dump_metrics" -> MetricsEngine.summary()
                "dump_logs" -> AegisLogger.export().take(2000)
                "dump_db_stats" -> dumpDbStats(db)
                "assert_record_count" -> assertRecordCount(db, arg1, arg2.toIntOrNull() ?: 0)
                "assert_person_exists" -> assertPersonExists(db, arg1)
                "clear_test_data" -> clearTestData(db)
                "compile_procedure" -> compileProcedure(arg1)
                "search" -> search(db, arg1, arg2)
                "version" -> "AegisCI/1.0 db_version=${dbVersion(db)}"
                else -> "UNKNOWN_CMD:$cmd"
            }
        } catch (e: Exception) {
            "ERROR:${e::class.simpleName}:${e.message}"
        }
    }

    private fun resetTestState(db: AegisDatabase?): String {
        if (db == null) return "ERROR:db_null"
        DecisionInspector.clear()
        AegisLogger.clear()
        return "OK:reset_test_state"
    }

    private fun seedPerson(db: AegisDatabase?, name: String, facts: String): String {
        if (db == null) return "ERROR:db_null"
        val personId = db.personDao().insertIfAbsent(PersonEntity(name = name, importanceScore = 70f))
        if (facts.isNotBlank()) {
            facts.split(";").filter { it.isNotBlank() }.forEach { fact ->
                val parts = fact.split(":")
                val category = if (parts.size > 1) parts[0] else "general"
                val content = if (parts.size > 1) parts[1] else parts[0]
                db.personFactDao().insert(PersonFactEntity(personId = personId, fact = content, category = category, confidence = 0.8f, source = "ci"))
            }
        }
        return "OK:person_id=$personId name=$name"
    }

    private fun seedMemory(db: AegisDatabase?, content: String, subject: String, confidence: Int): String {
        if (db == null) return "ERROR:db_null"
        val id = db.memoryRecordDao().insert(MemoryRecord(
            type = RecordType.FACT,
            content = content,
            subject = subject,
            source = "ci",
            confidence = confidence
        ))
        return "OK:memory_id=$id"
    }

    private fun runQuery(db: AegisDatabase?, sql: String): String {
        if (db == null) return "ERROR:db_null"
        if (sql.isBlank()) return "ERROR:empty_sql"
        val result = runBlocking { DatabaseInspector.rawQuery(db, sql, 20) }
        return if (result.error != null) "ERROR:${result.error}" else "OK:${result.rowCount} rows cols=${result.columns}"
    }

    private fun runProcedure(db: AegisDatabase?, procedureId: String): String {
        if (db == null) return "ERROR:db_null"
        return try {
            val rawDb = db.openHelper.readableDatabase
            val cursor = rawDb.query("SELECT steps FROM ui_procedures WHERE id = ?", arrayOf(procedureId))
            cursor.use { c ->
                if (c.moveToFirst()) {
                    val stepsJson = c.getString(0) ?: return "ERROR:no_steps"
                    "OK:procedure_steps_found length=${stepsJson.length}"
                } else {
                    "ERROR:procedure_not_found id=$procedureId"
                }
            }
        } catch (e: Exception) {
            "ERROR:${e.message}"
        }
    }

    private fun dumpTrace(traceId: String): String {
        if (traceId.isBlank()) {
            val recent = DecisionInspector.recent(5)
            return if (recent.isEmpty()) "NO_TRACES" else recent.joinToString("\n") { t ->
                "[${t.id}] ${t.queryText.take(30)} intent=${t.intent} ok=${t.success} ${t.totalMs}ms"
            }
        }
        val trace = DecisionInspector.get(traceId) ?: return "TRACE_NOT_FOUND:$traceId"
        return buildString {
            append("id=${trace.id} query=${trace.queryText}\n")
            append("intent=${trace.intent}(${(trace.intentConfidence * 100).toInt()}%)\n")
            append("entities=${trace.entities.size} capability=${trace.capabilityChosen}\n")
            append("executor=${trace.executorChosen} conf=${(trace.confidenceScore * 100).toInt()}%\n")
            append("success=${trace.success} error=${trace.errorMessage}\n")
            append("steps=${trace.steps.size} totalMs=${trace.totalMs}\n")
        }
    }

    private fun dumpDbStats(db: AegisDatabase?): String {
        if (db == null) return "ERROR:db_null"
        val stats = runBlocking { DatabaseInspector.tableStats(db) }
        return stats.joinToString("\n") { "${it.name}=${it.rowCount}" }
    }

    private fun assertRecordCount(db: AegisDatabase?, table: String, expected: Int): String {
        if (db == null) return "ERROR:db_null"
        val result = runBlocking { DatabaseInspector.rawQuery(db, "SELECT COUNT(*) FROM $table", 1) }
        val actual = result.rows.firstOrNull()?.firstOrNull()?.toIntOrNull() ?: -1
        return if (actual == expected) "PASS:count=$actual" else "FAIL:expected=$expected actual=$actual"
    }

    private fun assertPersonExists(db: AegisDatabase?, name: String): String {
        if (db == null) return "ERROR:db_null"
        val person = db.personDao().findByName(name)
        return if (person != null) "PASS:person_id=${person.id}" else "FAIL:person_not_found name=$name"
    }

    private fun clearTestData(db: AegisDatabase?): String {
        if (db == null) return "ERROR:db_null"
        val rawDb = db.openHelper.writableDatabase
        rawDb.execSQL("DELETE FROM memory_records WHERE source = 'ci'")
        rawDb.execSQL("DELETE FROM person_facts WHERE source = 'ci'")
        return "OK:test_data_cleared"
    }

    private fun compileProcedure(steps: String): String {
        if (steps.isBlank()) return "ERROR:empty_steps"
        val result = ProcedureCompiler.compile(steps)
        return "OK:steps=${result.steps.size} conf=${(result.confidence * 100).toInt()}% warnings=${result.warnings.size}"
    }

    private fun search(db: AegisDatabase?, query: String, strategy: String): String {
        if (db == null) return "ERROR:db_null"
        if (query.isBlank()) return "ERROR:empty_query"
        val result = runBlocking {
            when (strategy.lowercase()) {
                "exact" -> SearchLaboratory.runExact(query, db)
                "fts" -> SearchLaboratory.runFts(query, db)
                "graph" -> SearchLaboratory.runGraph(query, db)
                "temporal" -> SearchLaboratory.runTemporal(query, db)
                "vector" -> SearchLaboratory.runVector(query, db)
                else -> SearchLaboratory.runHybrid(query, db)
            }
        }
        return "OK:strategy=${result.strategy} count=${result.candidateCount} latency=${result.latencyMs}ms${result.error?.let { " err=$it" } ?: ""}"
    }

    private fun dbVersion(db: AegisDatabase?): String = try {
        val cursor = db?.openHelper?.readableDatabase?.query(SimpleSQLiteQuery("PRAGMA user_version"))
        cursor?.use { c -> if (c.moveToFirst()) c.getInt(0).toString() else "?" } ?: "?"
    } catch (_: Exception) { "?" }
}
