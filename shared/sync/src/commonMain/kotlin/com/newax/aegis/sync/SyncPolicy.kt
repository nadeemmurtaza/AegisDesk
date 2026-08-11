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

    /** The 17 tables that carry sync metadata (schema v13, slice S0). */
    val SYNCABLE_TABLES: Set<String> = setOf(
        "memory_records", "triples", "entities", "predicates", "edges",
        "blobs", "entity_aliases", "persons", "person_facts",
        "person_mentions", "person_snapshots", "person_policies",
        "ui_procedures", "app_records", "app_capability_links",
        "trigger_rules", "file_objects"
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
