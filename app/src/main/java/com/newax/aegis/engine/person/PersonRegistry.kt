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

object PersonRegistry {

    // ── Alias resolution ──────────────────────────────────────────────────────

    /**
     * Resolve any identifier (name, nickname, phone, email) to a graph entity ID.
     * Order: entity alias table → exact name → phone ContactsManager → null.
     */
    fun resolve(db: AegisDatabase, identifier: String): Long? {
        val clean = identifier.trim()
        // 1. Exact entity name in graph
        GraphStore.resolve(db, clean)?.let { return it }
        // 2. Alias table lookup (already covers nicknames added via seedAliases)
        db.graphDao().findEntityByAlias(clean)?.let { return it.entityId }
        // 3. Phone-format alias (digits only)
        val digits = clean.filter { it.isDigit() }
        if (digits.length >= 7) db.graphDao().findEntityByAlias(digits)?.let { return it.entityId }
        return null
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
            runCatching { db.graphDao().insertAlias(com.newax.aegis.db.entity.EntityAlias(entityId, alias)) }
        }
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    fun snapshot(db: AegisDatabase, entityId: Long): PersonSnapshot? =
        db.personRegistryDao().snapshot(entityId)

    fun upsertSnapshot(db: AegisDatabase, snapshot: PersonSnapshot) {
        db.personRegistryDao().upsertSnapshot(snapshot)
    }

    fun refreshSnapshotCommitmentCount(db: AegisDatabase, entityId: Long) {
        val count = db.personRegistryDao().pendingCountByDebtor(entityId)
        db.personRegistryDao().updateCommitmentCount(entityId, count, System.currentTimeMillis())
    }

    // ── Policy ────────────────────────────────────────────────────────────────

    fun policy(db: AegisDatabase, entityId: Long): PersonPolicy =
        db.personRegistryDao().policy(entityId) ?: PersonPolicy(entityId)

    fun setPolicy(db: AegisDatabase, policy: PersonPolicy) =
        db.personRegistryDao().upsertPolicy(policy)

    // ── Channel preferences ───────────────────────────────────────────────────

    fun bestChannel(db: AegisDatabase, entityId: Long, taskContext: String = "default"): PersonChannelPref? =
        db.personRegistryDao().bestChannel(entityId, taskContext)
            ?: db.personRegistryDao().bestChannel(entityId, "default")

    fun recordChannelUsed(db: AegisDatabase, entityId: Long, taskContext: String, packageName: String, capability: String) {
        val now = System.currentTimeMillis()
        val existing = db.personRegistryDao().allChannels(entityId)
            .firstOrNull { it.taskContext == taskContext }
        if (existing == null) {
            db.personRegistryDao().upsertChannelPref(
                PersonChannelPref(entityId, taskContext, packageName, capability)
            )
        } else {
            if (existing.packageName == packageName) {
                db.personRegistryDao().reinforceChannel(entityId, taskContext, now)
            } else {
                db.personRegistryDao().penalizeChannel(entityId, taskContext, now)
                db.personRegistryDao().upsertChannelPref(
                    PersonChannelPref(entityId, taskContext, packageName, capability,
                        probability = 0.6f, lastUpdatedMs = now)
                )
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
        db.personRegistryDao().insertCommitment(commitment)

    fun commitmentsPendingFrom(db: AegisDatabase, personEntityId: Long): List<Commitment> =
        db.personRegistryDao().pendingByDebtor(personEntityId)

    fun commitmentsPendingTo(db: AegisDatabase, personEntityId: Long): List<Commitment> =
        db.personRegistryDao().pendingByCreditor(personEntityId)

    fun resolveCommitment(db: AegisDatabase, id: Long, done: Boolean) {
        val status = if (done) Commitment.STATUS_DONE else Commitment.STATUS_CANCELLED
        db.personRegistryDao().updateStatus(id, status, System.currentTimeMillis())
    }

    fun overdueCommitments(db: AegisDatabase): List<Commitment> =
        db.personRegistryDao().overdueCommitments(System.currentTimeMillis())

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
