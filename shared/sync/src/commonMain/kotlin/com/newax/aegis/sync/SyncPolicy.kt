package com.newax.aegis.sync

/**
 * What syncs and under what keys (docs/SYNC_DESIGN.md §5).
 *
 * - [SYNCABLE_TABLES] mirrors the 17 tables that received sync columns in
 *   schema v13 (slice S0). Derived/device-local tables are never synced.
 * - [SYNCABLE_PREFIX] is the `kv_store` namespacing contract: only keys
 *   starting with `syncable:` leave the device. Everything else in kv_store
 *   stays device-local by design.
 */
object SyncPolicy {

    /** kv_store keys under this prefix are syncable; all others stay local. */
    const val SYNCABLE_PREFIX = "syncable:"

    /**
     * Tables that carry sync metadata. The 17 v13 tables plus the three synced
     * layers of the hierarchical agent memory (schema v14,
     * docs/MEMORY_DESIGN.md): episodes (collective learning), handoffs (shared
     * write), library_entries (the gated Global Library). `agent_scratchpad`
     * and `work_log` are deliberately NOT here — scratchpad is private by
     * design, work_log is device-scoped dedupe.
     */
    val SYNCABLE_TABLES: Set<String> = setOf(
        "memory_records", "triples", "entities", "predicates", "edges",
        "blobs", "entity_aliases", "persons", "person_facts",
        "person_mentions", "person_snapshots", "person_policies",
        "ui_procedures", "app_records", "app_capability_links",
        "trigger_rules", "file_objects",
        "episodes", "handoffs", "library_entries"
    )

    fun isSyncableTable(table: String): Boolean = table in SYNCABLE_TABLES

    /** Only `syncable:`-prefixed kv_store keys participate in sync. */
    fun isSyncableKey(key: String): Boolean = key.startsWith(SYNCABLE_PREFIX)

    /** Build a syncable kv_store key: `syncable:<namespace>:<id>`. */
    fun syncKey(namespace: String, id: String): String = "$SYNCABLE_PREFIX$namespace:$id"

    /** Prefix for listing one namespace: `syncable:<namespace>:`. */
    fun syncablePrefix(namespace: String): String = "$SYNCABLE_PREFIX$namespace:"

    /** Strip the syncable prefix; null when [key] is not syncable. */
    fun localKey(key: String): String? =
        if (isSyncableKey(key)) key.removePrefix(SYNCABLE_PREFIX) else null
}
