package com.newax.aegis.engine.compiler

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.graph.GraphStore
import com.newax.aegis.db.StandardPredicates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GraphCompiler {

    data class CompileResult(
        val entitiesAdded: Int,
        val edgesAdded: Int,
        val skipped: Int,
        val durationMs: Long
    )

    suspend fun compileFromPersonFacts(db: AegisDatabase): CompileResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        var entitiesAdded = 0
        var edgesAdded = 0
        var skipped = 0

        val persons = db.personDao().getTopPeople(200)
        for (person in persons) {
            val personEntityId = GraphStore.resolveOrCreate(db, person.name, GraphStore.EntityType.PERSON)
            val facts = db.personFactDao().forPerson(person.id).filter { it.confidence > 0.6f }

            for (fact in facts) {
                runCatching {
                    val predicate = categoryToPredicate(fact.category)
                    val objectText = extractObjectFromFact(fact.fact, fact.category)
                    if (objectText.isNotBlank() && predicate.isNotBlank()) {
                        val objectType = typeFromCategory(fact.category)
                        val objectId = GraphStore.resolveOrCreate(db, objectText, objectType)
                        GraphStore.addEdge(db, personEntityId, predicate, objectId,
                            confidence = (fact.confidence * 100).toInt())
                        edgesAdded++
                    } else skipped++
                }.onFailure { skipped++ }
            }
            entitiesAdded++
        }

        CompileResult(entitiesAdded, edgesAdded, skipped, System.currentTimeMillis() - startMs)
    }

    suspend fun compileFromMemoryRecords(db: AegisDatabase): CompileResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        var edgesAdded = 0
        var skipped = 0

        val records = db.memoryRecordDao().current(5000)
            .filter { it.subject.isNotBlank() }

        for (record in records) {
            runCatching {
                val subjectId = GraphStore.resolveOrCreate(db, record.subject, GraphStore.EntityType.UNKNOWN)
                val predicate = record.category.ifBlank { StandardPredicates.ABOUT }
                GraphStore.addEdge(
                    db, subjectId, predicate,
                    objectValue = record.content.take(200),
                    confidence = record.confidence
                )
                edgesAdded++
            }.onFailure { skipped++ }
        }

        CompileResult(0, edgesAdded, skipped, System.currentTimeMillis() - startMs)
    }

    suspend fun compileFromFileEntities(db: AegisDatabase): CompileResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        var edgesAdded = 0
        var skipped = 0

        val files = db.fileDao().recentUniqueFiles(100)
        for (file in files) {
            runCatching {
                val geid = file.graphEntityId
                if (geid != null && geid > 0) {
                    val entities = db.fileDao().entitiesForFile(file.id)
                    for (link in entities) {
                        val entityId = GraphStore.resolveOrCreate(db, link.entityLabel, GraphStore.EntityType.UNKNOWN)
                        GraphStore.addEdge(db, geid, StandardPredicates.MENTIONED_IN, entityId)
                        edgesAdded++
                    }
                } else skipped++
            }.onFailure { skipped++ }
        }

        CompileResult(0, edgesAdded, skipped, System.currentTimeMillis() - startMs)
    }

    suspend fun compileAll(db: AegisDatabase): CompileResult = withContext(Dispatchers.IO) {
        val r1 = compileFromPersonFacts(db)
        val r2 = compileFromMemoryRecords(db)
        val r3 = compileFromFileEntities(db)
        CompileResult(
            entitiesAdded = r1.entitiesAdded + r2.entitiesAdded + r3.entitiesAdded,
            edgesAdded = r1.edgesAdded + r2.edgesAdded + r3.edgesAdded,
            skipped = r1.skipped + r2.skipped + r3.skipped,
            durationMs = r1.durationMs + r2.durationMs + r3.durationMs
        )
    }

    private fun categoryToPredicate(category: String): String = when (category.lowercase()) {
        "work", "job", "employer" -> StandardPredicates.WORKS_AT
        "location", "home", "city", "country" -> StandardPredicates.LIVES_IN
        "interest", "hobby", "likes" -> StandardPredicates.LIKES
        "project" -> StandardPredicates.WORKS_ON
        "knows", "friend", "family" -> StandardPredicates.KNOWS
        else -> StandardPredicates.ABOUT
    }

    private fun typeFromCategory(category: String): Int = when (category.lowercase()) {
        "work", "job", "employer" -> GraphStore.EntityType.COMPANY
        "location", "home", "city" -> GraphStore.EntityType.PLACE
        else -> GraphStore.EntityType.UNKNOWN
    }

    private fun extractObjectFromFact(fact: String, category: String): String {
        val separators = listOf(" is ", " at ", " in ", " works at ", ":", " - ", " = ")
        for (sep in separators) {
            if (fact.contains(sep, ignoreCase = true)) {
                return fact.substringAfterLast(sep).trim().take(80)
            }
        }
        return fact.take(80).trim()
    }
}
