package com.newax.aegis.engine.person

import android.content.Context
import com.newax.aegis.db.AegisDatabase
import com.newax.aegis.db.entity.Commitment
import com.newax.aegis.db.entity.PersonChannelPref
import com.newax.aegis.db.entity.PersonPolicy
import com.newax.aegis.db.entity.PersonSnapshot
import com.newax.aegis.engine.apps.AppCapability
import com.newax.aegis.engine.apps.AppIntelligence
import com.newax.aegis.engine.graph.GraphStore

sealed class PersonResolution {
    data class Resolved(val entityId: Long, val displayName: String) : PersonResolution()
    data class Ambiguous(val candidates: List<Pair<Long, String>>) : PersonResolution()
    object NotFound : PersonResolution()
}

object PersonRegistry {

    // ── Alias resolution ──────────────────────────────────────────────────────

    fun resolve(db: AegisDatabase, identifier: String): Long? =
        when (val r = resolveAmbiguous(db, identifier)) {
            is PersonResolution.Resolved  -> r.entityId
            is PersonResolution.Ambiguous -> null
            PersonResolution.NotFound     -> null
        }

    fun resolveAmbiguous(db: AegisDatabase, identifier: String): PersonResolution {
        val clean = identifier.trim()
        val candidates = mutableListOf<Pair<Long, String>>()

        GraphStore.resolve(db, clean)?.let { id ->
            val name = kotlinx.coroutines.runBlocking { db.graphDao().findEntityById(id) }?.canonicalName ?: clean
            candidates.add(id to name)
        }

        kotlinx.coroutines.runBlocking { db.graphDao().findEntitiesByAlias(clean) }.forEach { id ->
            val name = kotlinx.coroutines.runBlocking { db.graphDao().findEntityById(id) }?.canonicalName ?: clean
            if (candidates.none { it.first == id }) candidates.add(id to name)
        }

        val digits = clean.filter { it.isDigit() }
        if (digits.length >= 7) {
            kotlinx.coroutines.runBlocking { db.graphDao().findEntitiesByAlias(digits) }.forEach { id ->
                val name = kotlinx.coroutines.runBlocking { db.graphDao().findEntityById(id) }?.canonicalName ?: clean
                if (candidates.none { it.first == id }) candidates.add(id to name)
            }
        }

        return when {
            candidates.isEmpty() -> PersonResolution.NotFound
            candidates.size == 1 -> PersonResolution.Resolved(candidates[0].first, candidates[0].second)
            else -> PersonResolution.Ambiguous(candidates)
        }
    }

    /**
     * Resolve identifier → entity ID, creating a new person entity if needed.
     * Also registers phone/email as aliases.
     */
    fun resolveOrCreate(
        db: AegisDatabase,
        displayName: String,
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList(),
        nicknames: List<String> = emptyList()
    ): Long {
        val entityId = resolve(db, displayName)
            ?: GraphStore.resolveOrCreate(db, displayName, GraphStore.EntityType.PERSON)
        seedAliases(db, entityId, displayName, phones, emails, nicknames)
        return entityId
    }

    fun seedAliases(
        db: AegisDatabase,
        entityId: Long,
        displayName: String,
        phones: List<String>,
        emails: List<String>,
        nicknames: List<String>
    ) {
        val aliases = buildList {
            add(displayName.lowercase())
            addAll(nicknames.map { it.lowercase() })
            addAll(phones.map { it.filter { c -> c.isDigit() } })
            addAll(phones)
            addAll(emails.map { it.lowercase() })
            displayName.split(" ").firstOrNull()?.lowercase()?.let { add(it) }
        }.filter { it.isNotBlank() }.distinct()
        aliases.forEach { alias ->
            runCatching { kotlinx.coroutines.runBlocking { db.graphDao().insertAlias(com.newax.aegis.db.entity.EntityAlias(entityId, alias)) } }
        }
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    fun snapshot(db: AegisDatabase, entityId: Long): PersonSnapshot? =
        kotlinx.coroutines.runBlocking { db.personRegistryDao().snapshot(entityId) }

    fun upsertSnapshot(db: AegisDatabase, snapshot: PersonSnapshot) {
        kotlinx.coroutines.runBlocking { db.personRegistryDao().upsertSnapshot(snapshot) }
    }

    fun refreshSnapshotCommitmentCount(db: AegisDatabase, entityId: Long) {
        val count = kotlinx.coroutines.runBlocking { db.personRegistryDao().pendingCountByDebtor(entityId) }
        kotlinx.coroutines.runBlocking { db.personRegistryDao().updateCommitmentCount(entityId, count, System.currentTimeMillis()) }
    }

    // ── Policy ────────────────────────────────────────────────────────────────

    fun policy(db: AegisDatabase, entityId: Long): PersonPolicy =
        kotlinx.coroutines.runBlocking { db.personRegistryDao().policy(entityId) } ?: PersonPolicy(entityId)

    fun setPolicy(db: AegisDatabase, policy: PersonPolicy) =
        kotlinx.coroutines.runBlocking { db.personRegistryDao().upsertPolicy(policy) }

    // ── Channel preferences ───────────────────────────────────────────────────

    fun bestChannel(db: AegisDatabase, entityId: Long, taskContext: String = "default"): PersonChannelPref? =
        kotlinx.coroutines.runBlocking {
            db.personRegistryDao().bestChannel(entityId, taskContext)
                ?: db.personRegistryDao().bestChannel(entityId, "default")
        }

    fun recordChannelUsed(db: AegisDatabase, entityId: Long, taskContext: String, packageName: String, capability: String) {
        val now = System.currentTimeMillis()
        val existing = kotlinx.coroutines.runBlocking { db.personRegistryDao().allChannels(entityId) }
            .firstOrNull { it.taskContext == taskContext }
        if (existing == null) {
            kotlinx.coroutines.runBlocking {
                db.personRegistryDao().upsertChannelPref(
                    PersonChannelPref(entityId, taskContext, packageName, capability)
                )
            }
        } else {
            if (existing.packageName == packageName) {
                kotlinx.coroutines.runBlocking { db.personRegistryDao().reinforceChannel(entityId, taskContext, now) }
            } else {
                kotlinx.coroutines.runBlocking {
                    db.personRegistryDao().penalizeChannel(entityId, taskContext, now)
                    db.personRegistryDao().upsertChannelPref(
                        PersonChannelPref(entityId, taskContext, packageName, capability,
                            probability = 0.6f, lastUpdatedMs = now)
                    )
                }
            }
        }
        // Update snapshot preferred channel (default context only)
        if (taskContext == "default") {
            snapshot(db, entityId)?.let {
                upsertSnapshot(db, it.copy(preferredChannel = packageName, snapshotUpdatedMs = now))
            }
        }
    }

    // ── Commitments ───────────────────────────────────────────────────────────

    fun addCommitment(db: AegisDatabase, commitment: Commitment): Long =
        kotlinx.coroutines.runBlocking { db.personRegistryDao().insertCommitment(commitment) }

    fun commitmentsPendingFrom(db: AegisDatabase, personEntityId: Long): List<Commitment> =
        kotlinx.coroutines.runBlocking { db.personRegistryDao().pendingByDebtor(personEntityId) }

    fun commitmentsPendingTo(db: AegisDatabase, personEntityId: Long): List<Commitment> =
        kotlinx.coroutines.runBlocking { db.personRegistryDao().pendingByCreditor(personEntityId) }

    fun resolveCommitment(db: AegisDatabase, id: Long, done: Boolean) {
        val status = if (done) Commitment.STATUS_DONE else Commitment.STATUS_CANCELLED
        kotlinx.coroutines.runBlocking { db.personRegistryDao().updateStatus(id, status, System.currentTimeMillis()) }
    }

    fun overdueCommitments(db: AegisDatabase): List<Commitment> =
        kotlinx.coroutines.runBlocking { db.personRegistryDao().overdueCommitments(System.currentTimeMillis()) }

    // ── Person × App × Task resolution ───────────────────────────────────────

    data class TaskResolution(
        val personEntityId: Long,
        val personName: String,
        val appResolution: AppIntelligence.Resolution,
        val channel: PersonChannelPref?,
        val policy: PersonPolicy,
        val pendingCommitments: List<Commitment>
    )

    fun resolveTask(
        db: AegisDatabase,
        context: Context,
        personIdentifier: String,
        capability: AppCapability,
        taskContext: String = "default"
    ): TaskResolution? {
        val entityId = resolve(db, personIdentifier) ?: return null
        val snap = snapshot(db, entityId)
        val name = snap?.displayName ?: personIdentifier
        val channelPref = bestChannel(db, entityId, taskContext)
        val pkgHint = channelPref?.packageName
        val appRes = AppIntelligence.resolve(db, context, capability, pkgHint)
            ?: AppIntelligence.resolve(db, context, capability)
            ?: return null
        return TaskResolution(
            personEntityId      = entityId,
            personName          = name,
            appResolution       = appRes,
            channel             = channelPref,
            policy              = policy(db, entityId),
            pendingCommitments  = commitmentsPendingFrom(db, entityId)
        )
    }

    // ── Contextual summary for LLM prompt augmentation ────────────────────────

    fun contextSnippet(db: AegisDatabase, entityId: Long): String {
        val snap = snapshot(db, entityId) ?: return ""
        val pending = commitmentsPendingFrom(db, entityId).take(3)
        val waiting = commitmentsPendingTo(db, entityId).take(3)
        return buildString {
            append("[${snap.displayName}] ${snap.relationshipType}")
            if (snap.preferredTone.isNotBlank()) append(", ${snap.preferredTone}")
            if (snap.preferredLanguage.isNotBlank()) append(", ${snap.preferredLanguage}")
            snap.preferredChannel?.let { append(", prefers $it") }
            snap.activeProjectId?.let { append(", active project: $it") }
            if (snap.recentTopics.isNotBlank()) append(", topics: ${snap.recentTopics}")
            if (pending.isNotEmpty()) append(", OWES YOU: ${pending.joinToString("; ") { it.action }}")
            if (waiting.isNotEmpty()) append(", YOU OWE: ${waiting.joinToString("; ") { it.action }}")
        }
    }
}
