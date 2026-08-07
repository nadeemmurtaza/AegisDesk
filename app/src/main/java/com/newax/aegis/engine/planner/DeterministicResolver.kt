package com.newax.aegis.engine.planner

import android.content.Context
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.CalendarQueries
import com.newax.aegis.engine.CommunicationLog
import com.newax.aegis.engine.ContactsManager
import com.newax.aegis.engine.ProjectTracker
import com.newax.aegis.engine.SemanticSearchEngine
import com.newax.aegis.engine.embedding.VectorStore
import com.newax.aegis.engine.graph.GraphStore
import com.newax.aegis.engine.person.PersonRegistry
import com.newax.aegis.engine.planner.QueryPlanner.RetrievalPath.*
import com.newax.aegis.memory.EncryptedMemory

object DeterministicResolver {

    data class ResolvedResult(
        val content: String,
        val source: QueryPlanner.RetrievalPath,
        val confidence: Float,
        val recordId: Long? = null
    )

    fun resolve(
        plan: QueryPlanner.QueryPlan,
        db: AegisDatabase,
        memory: EncryptedMemory,
        context: Context
    ): List<ResolvedResult> {
        val results = mutableListOf<ResolvedResult>()
        for (path in plan.paths) {
            when (path) {
                KV_EXACT         -> resolveKv(plan, memory, results)
                CONTACT_LOOKUP   -> resolveContacts(plan, db, context, results)
                CALENDAR         -> resolveCalendar(plan, context, results)
                FTS_BM25         -> resolveFts(plan, db, results)
                GRAPH_TRAVERSAL  -> resolveGraph(plan, db, results)
                GRAPH_MULTIHOP   -> resolveMultihop(plan, db, results)
                VECTOR_SEMANTIC  -> resolveVector(plan, db, results)
                PREFIX_TRIE      -> resolvePrefix(plan, results)
                TEMPORAL_FILTER  -> resolveTemporal(plan, db, results)
                OBJECT_STORE     -> resolveObjects(plan, results)
            }
            if (results.any { it.confidence >= 0.90f } && results.size >= 2) break
        }
        return results
    }

    // ── Retrieval implementations ─────────────────────────────────────────────

    private fun resolveKv(plan: QueryPlanner.QueryPlan, memory: EncryptedMemory, out: MutableList<ResolvedResult>) {
        val q = (plan.entityNames + plan.keywords).joinToString(" ")
        memory.relevant(q).forEach { out += ResolvedResult(it, KV_EXACT, 0.80f) }
    }

    private fun resolveContacts(plan: QueryPlanner.QueryPlan, db: AegisDatabase, context: Context, out: MutableList<ResolvedResult>) {
        for (name in plan.entityNames.take(3)) {
            // PersonRegistry first (graph alias resolution), then ContactsManager fallback
            val entityId = PersonRegistry.resolve(db, name)
            val snap = entityId?.let { PersonRegistry.snapshot(db, it) }
            val phone = snap?.canonicalPhone ?: ContactsManager.phoneByName(context, name)
            val email = snap?.canonicalEmail ?: ContactsManager.emailByName(context, name)
            phone?.let { out += ResolvedResult("$name phone: $it", CONTACT_LOOKUP, 0.95f) }
            email?.let { out += ResolvedResult("$name email: $it", CONTACT_LOOKUP, 0.92f) }
            snap?.let { s ->
                if (s.pendingCommitmentCount > 0)
                    out += ResolvedResult("$name has ${s.pendingCommitmentCount} pending commitments", CONTACT_LOOKUP, 0.88f)
                if (s.recentTopics.isNotBlank())
                    out += ResolvedResult("$name recent topics: ${s.recentTopics}", CONTACT_LOOKUP, 0.80f)
            }
        }
    }

    private fun resolveCalendar(plan: QueryPlanner.QueryPlan, context: Context, out: MutableList<ResolvedResult>) {
        val now   = System.currentTimeMillis()
        val start = plan.temporalFilter?.fromMs  ?: now
        val end   = plan.temporalFilter?.untilMs ?: (now + 7 * 86_400_000L)
        CalendarQueries.query(context, start, end, 10)
            .forEach { ev -> out += ResolvedResult("Event: ${ev.formatted("MMM dd HH:mm")}", CALENDAR, 0.90f) }
    }

    private fun resolveFts(plan: QueryPlanner.QueryPlan, db: AegisDatabase, out: MutableList<ResolvedResult>) {
        val q = plan.keywords.joinToString(" ").trim()
        if (q.isBlank()) return
        try { db.personFactDao().searchFts(q, 5).forEach { out += ResolvedResult(it.fact, FTS_BM25, 0.72f) } }
        catch (_: Exception) {}
        SemanticSearchEngine.searchCommunicationLogs(q, 3)
            .forEach { log -> out += ResolvedResult("[${log.contact}] ${log.summary}", FTS_BM25, 0.65f) }
    }

    private fun resolveGraph(plan: QueryPlanner.QueryPlan, db: AegisDatabase, out: MutableList<ResolvedResult>) {
        if (plan.entityNames.isEmpty()) return
        val ctx = GraphStore.contextFor(db, plan.entityNames)
        if (ctx.isNotBlank()) out += ResolvedResult(ctx, GRAPH_TRAVERSAL, 0.75f)
    }

    private fun resolveMultihop(plan: QueryPlanner.QueryPlan, db: AegisDatabase, out: MutableList<ResolvedResult>) {
        for (name in plan.entityNames.take(2)) {
            val entityId = GraphStore.resolve(db, name) ?: continue
            val hops = GraphStore.multihop(db, entityId, maxDepth = 3, maxNodes = 15)
            if (hops.size <= 1) continue
            val sb = StringBuilder("$name graph:\n")
            hops.drop(1).take(8).forEach { node ->
                node.edges.take(3).forEach { edge ->
                    val pred = db.graphDao().predicateById(edge.predicateId)?.name ?: return@forEach
                    val obj  = edge.objectId?.let { db.graphDao().entityById(it)?.canonicalName } ?: edge.objectValue ?: "?"
                    sb.appendLine("  ${node.entityName} ${pred.replace('_', ' ')} $obj")
                }
            }
            out += ResolvedResult(sb.toString().trim(), GRAPH_MULTIHOP, 0.70f)
        }
    }

    private fun resolveVector(plan: QueryPlanner.QueryPlan, db: AegisDatabase, out: MutableList<ResolvedResult>) {
        val q = (plan.entityNames + plan.keywords).joinToString(" ").trim()
        if (q.isBlank()) return
        VectorStore.search(db, q, 5).forEach { r ->
            out += ResolvedResult(r.text, VECTOR_SEMANTIC, (r.score * 0.9f).coerceIn(0f, 1f))
        }
    }

    private fun resolvePrefix(plan: QueryPlanner.QueryPlan, out: MutableList<ResolvedResult>) {
        val prefix = plan.entityNames.firstOrNull() ?: plan.keywords.firstOrNull() ?: return
        val r = SemanticSearchEngine.instantPrefixSearch(prefix)
        if (!r.startsWith("No instant")) out += ResolvedResult(r, PREFIX_TRIE, 0.60f)
    }

    private fun resolveTemporal(plan: QueryPlanner.QueryPlan, db: AegisDatabase, out: MutableList<ResolvedResult>) {
        val tf = plan.temporalFilter ?: return
        db.memoryRecordDao().findByTimeRange(tf.fromMs, tf.untilMs, 10)
            .forEach { rec -> out += ResolvedResult(rec.content, TEMPORAL_FILTER, 0.70f, rec.id) }
    }

    private fun resolveObjects(plan: QueryPlanner.QueryPlan, out: MutableList<ResolvedResult>) {
        val q = (plan.entityNames + plan.keywords).joinToString(" ").lowercase()
        ProjectTracker.getAllProjects()
            .filter { p -> p.id.lowercase().contains(q) || p.notes.lowercase().contains(q) }
            .take(3)
            .forEach { p -> out += ResolvedResult("[Project:${p.id}] ${p.status} — ${p.notes.take(80)}", OBJECT_STORE, 0.78f) }
        plan.entityNames.forEach { name ->
            CommunicationLog.getLogsForContact(name, 3)
                .forEach { log -> out += ResolvedResult("[Comm:${log.contact}] ${log.summary}", OBJECT_STORE, 0.70f) }
        }
    }

}
