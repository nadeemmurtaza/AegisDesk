package com.newax.aegis.engine.dev.people

import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.engine.graph.GraphStore
import com.newax.aegis.engine.person.PersonRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PersonProfile(
    val entityId: Long,
    val name: String,
    val aliases: List<String>,
    val facts: List<PersonFact>,
    val mentions: Int,
    val sources: Int,
    val snapshot: PersonSnapshotInfo?,
    val policy: PersonPolicyInfo,
    val commitments: List<CommitmentInfo>,
    val graphRelations: List<GraphRelation>,
    val communicationProfile: CommProfile,
    val confidence: Float
)

data class PersonFact(val id: Long, val category: String, val fact: String, val confidence: Float, val source: String)
data class PersonSnapshotInfo(
    val displayName: String,
    val canonicalPhone: String?,
    val canonicalEmail: String?,
    val preferredChannel: String?,
    val relationshipType: String,
    val recentTopics: List<String>,
    val importanceScore: Int,
    val pendingCommitments: Int,
    val lastInteractionMs: Long
)
data class PersonPolicyInfo(val canAutoOpenChat: Boolean, val canAutoDraft: Boolean, val canAutoSend: Boolean, val canCallWithoutConfirm: Boolean)
data class CommitmentInfo(val id: Long, val action: String, val direction: String, val dueMs: Long?, val resolved: Boolean)
data class GraphRelation(val predicate: String, val objectValue: String, val confidence: Int)
data class CommProfile(val packageName: String?, val preferredTone: String, val preferredLanguage: String)

data class MergeProposal(
    val entityIdA: Long,
    val nameA: String,
    val entityIdB: Long,
    val nameB: String,
    val sharedFacts: Int,
    val confidence: Float
)

object PersonInspector {

    suspend fun inspect(entityId: Long, db: AegisDatabase): PersonProfile? = withContext(Dispatchers.IO) {
        val person = db.personDao().getTopPeople(1000).find { it.id == entityId } ?: return@withContext null
        val facts = db.personFactDao().forPerson(entityId).map {
            PersonFact(it.id, it.category, it.fact, it.confidence, it.source)
        }
        val snapshotEntity = PersonRegistry.snapshot(db, entityId)
        val snapshot = snapshotEntity?.let {
            PersonSnapshotInfo(
                displayName = it.displayName,
                canonicalPhone = it.canonicalPhone,
                canonicalEmail = it.canonicalEmail,
                preferredChannel = it.preferredChannel,
                relationshipType = it.relationshipType,
                recentTopics = it.recentTopics.split(",").filter { t -> t.isNotBlank() },
                importanceScore = it.importanceScore,
                pendingCommitments = it.pendingCommitmentCount,
                lastInteractionMs = it.lastInteractionMs
            )
        }
        val policy = PersonRegistry.policy(db, entityId)
        val policyInfo = PersonPolicyInfo(policy.canAutoOpenChat, policy.canAutoDraft, policy.canAutoSend, policy.canCallWithoutConfirm)

        val commitments = try {
            val from = PersonRegistry.commitmentsPendingFrom(db, entityId).map { c -> CommitmentInfo(c.id, c.action, "FROM_ME", c.dueMs, c.resolvedMs != null) }
            val to = PersonRegistry.commitmentsPendingTo(db, entityId).map { c -> CommitmentInfo(c.id, c.action, "TO_ME", c.dueMs, c.resolvedMs != null) }
            from + to
        } catch (_: Exception) { emptyList() }

        val graphRelations = try {
            val graphEntityId = GraphStore.resolveOrCreate(db, person.name, GraphStore.EntityType.PERSON)
            GraphStore.edgesFrom(db, graphEntityId).map { e ->
                GraphRelation("edge:${e.predicateId}", e.objectValue ?: "entity:${e.objectId}", e.confidence)
            }
        } catch (_: Exception) { emptyList() }

        val mentions = db.personMentionDao().totalMentions(entityId)
        val sources = db.personMentionDao().sourceCount(entityId)
        val avgConf = facts.map { it.confidence }.average().toFloat().coerceIn(0f, 1f)

        val preferred = PersonRegistry.bestChannel(db, entityId)
        val commProfile = CommProfile(
            packageName = preferred?.packageName,
            preferredTone = snapshotEntity?.preferredTone ?: "",
            preferredLanguage = snapshotEntity?.preferredLanguage ?: ""
        )

        PersonProfile(
            entityId = entityId,
            name = person.name,
            aliases = emptyList(),
            facts = facts,
            mentions = mentions,
            sources = sources,
            snapshot = snapshot,
            policy = policyInfo,
            commitments = commitments,
            graphRelations = graphRelations,
            communicationProfile = commProfile,
            confidence = avgConf
        )
    }

    suspend fun inspectByName(name: String, db: AegisDatabase): PersonProfile? = withContext(Dispatchers.IO) {
        val entityId = PersonRegistry.resolve(db, name) ?: return@withContext null
        inspect(entityId, db)
    }

    suspend fun findMergeCandidates(db: AegisDatabase, minSharedFacts: Int = 2): List<MergeProposal> = withContext(Dispatchers.IO) {
        val people = db.personDao().getTopPeople(100)
        val proposals = mutableListOf<MergeProposal>()
        for (i in people.indices) {
            for (j in i + 1 until people.size) {
                val a = people[i]
                val b = people[j]
                val nameTokensA = a.name.lowercase().split(" ").toSet()
                val nameTokensB = b.name.lowercase().split(" ").toSet()
                val nameOverlap = nameTokensA.intersect(nameTokensB).size
                if (nameOverlap < 1) continue
                val factsA = db.personFactDao().forPerson(a.id).map { it.fact.lowercase() }.toSet()
                val factsB = db.personFactDao().forPerson(b.id).map { it.fact.lowercase() }.toSet()
                val sharedFacts = factsA.intersect(factsB).size
                if (sharedFacts >= minSharedFacts || nameOverlap >= 2) {
                    val conf = (sharedFacts * 0.3f + nameOverlap * 0.2f).coerceIn(0f, 1f)
                    proposals.add(MergeProposal(a.id, a.name, b.id, b.name, sharedFacts, conf))
                }
            }
        }
        proposals.sortedByDescending { it.confidence }
    }

    suspend fun listAll(db: AegisDatabase, limit: Int = 50): List<Map<String, String>> = withContext(Dispatchers.IO) {
        db.personDao().getTopPeople(limit).map { p ->
            val mentions = db.personMentionDao().totalMentions(p.id)
            mapOf(
                "id" to p.id.toString(),
                "name" to p.name,
                "mentions" to mentions.toString(),
                "importance" to p.importanceScore.toString()
            )
        }
    }

    suspend fun evidenceSummary(entityId: Long, db: AegisDatabase): String = withContext(Dispatchers.IO) {
        val facts = db.personFactDao().forPerson(entityId)
        val mentions = db.personMentionDao().totalMentions(entityId)
        val sources = db.personMentionDao().sourceCount(entityId)
        buildString {
            append("Evidence for entity $entityId:\n")
            append("  ${facts.size} facts across ${facts.map { it.category }.distinct().size} categories\n")
            append("  $mentions total mentions across $sources sources\n")
            val avgConf = facts.map { it.confidence }.average()
            append("  avg confidence: ${(avgConf * 100).toInt()}%\n")
            facts.groupBy { it.category }.forEach { (cat, fs) ->
                append("  [$cat] ${fs.size} facts\n")
            }
        }
    }
}
