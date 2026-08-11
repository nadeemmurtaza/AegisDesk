package com.newax.aegis.db

import com.newax.aegis.db.entity.*
import com.newax.aegis.db.dao.*
import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [
        PersonEntity::class,
        PersonMentionEntity::class,
        PersonFactEntity::class,
        PersonFactFts::class,
        LearningDraftEntity::class,
        KvStoreEntity::class,
        EmbeddingEntity::class,
        TripleEntity::class,
        GraphEntity::class,
        GraphPredicate::class,
        GraphEdge::class,
        GraphBlob::class,
        EntityAlias::class,
        MemoryRecord::class,
        AppRecord::class,
        AppCapabilityLink::class,
        UiProcedure::class,
        ScreenNode::class,
        NavEdge::class,
        PersonSnapshot::class,
        PersonPolicy::class,
        PersonChannelPref::class,
        Commitment::class,
        TriggerRule::class,
        FileObject::class,
        FileTextContent::class,
        FileTextFts::class,
        FileEntityLink::class,
        SyncJournalEntity::class,
        SyncVectorEntity::class,
        AgentScratchpad::class,
        Episode::class,
        HandoffEntry::class,
        WorkLogEntry::class,
        LibraryEntry::class
    ],
    version = 14,
    exportSchema = true
)
@ConstructedBy(AegisDatabaseConstructor::class)
abstract class AegisDatabase : RoomDatabase() {

    abstract fun personDao(): PersonDao
    abstract fun personMentionDao(): PersonMentionDao
    abstract fun personFactDao(): PersonFactDao
    abstract fun learningDraftDao(): LearningDraftDao
    abstract fun kvStoreDao(): KvStoreDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun tripleDao(): TripleDao
    abstract fun graphDao(): GraphDao
    abstract fun memoryRecordDao(): MemoryRecordDao
    abstract fun appRegistryDao(): AppRegistryDao
    abstract fun personRegistryDao(): PersonRegistryDao
    abstract fun triggerDao(): TriggerDao
    abstract fun fileDao(): FileDao
    abstract fun syncJournalDao(): SyncJournalDao
    abstract fun syncVectorDao(): SyncVectorDao
    abstract fun agentMemoryDao(): AgentMemoryDao

    companion object {
        @Volatile private var INSTANCE: AegisDatabase? = null
        val get: AegisDatabase get() = INSTANCE ?: error("Not initialized")
        fun init(db: AegisDatabase) { INSTANCE = db }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS embeddings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceType TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        embedding BLOB NOT NULL
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_sourceType ON embeddings(sourceType)")
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_embeddings_sourceId ON embeddings(sourceId)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS triples (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        subject TEXT NOT NULL,
                        predicate TEXT NOT NULL,
                        objectValue TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        source TEXT NOT NULL,
                        createdMs INTEGER NOT NULL
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_triples_subject ON triples(subject)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_triples_predicate ON triples(predicate)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_triples_subject_predicate ON triples(subject, predicate)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_triples_objectValue ON triples(objectValue)")
            }
        }

        /**
         * Replaces the hand-rolled `person_facts_fts` table (previously created only by
         * FtsSetupCallback, i.e. only on fresh installs) with the Room-managed FTS entity.
         * Installs that migrated up from an earlier version never had the table at all, so
         * this drops whatever is there and rebuilds from `person_facts`.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(connection: SQLiteConnection) {
                listOf("pf_ai", "pf_bu", "pf_au", "pf_bd").forEach {
                    connection.execSQL("DROP TRIGGER IF EXISTS $it")
                }
                connection.execSQL("DROP TABLE IF EXISTS person_facts_fts")
                connection.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `person_facts_fts` USING FTS4(" +
                        "`fact` TEXT NOT NULL, `category` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "content=`person_facts`)"
                )
                connection.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_person_facts_fts_BEFORE_UPDATE " +
                        "BEFORE UPDATE ON `person_facts` BEGIN " +
                        "DELETE FROM `person_facts_fts` WHERE `docid`=OLD.`rowid`; END"
                )
                connection.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_person_facts_fts_BEFORE_DELETE " +
                        "BEFORE DELETE ON `person_facts` BEGIN " +
                        "DELETE FROM `person_facts_fts` WHERE `docid`=OLD.`rowid`; END"
                )
                connection.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_person_facts_fts_AFTER_UPDATE " +
                        "AFTER UPDATE ON `person_facts` BEGIN " +
                        "INSERT INTO `person_facts_fts`(`docid`, `fact`, `category`, `source`) " +
                        "VALUES (NEW.`rowid`, NEW.`fact`, NEW.`category`, NEW.`source`); END"
                )
                connection.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_person_facts_fts_AFTER_INSERT " +
                        "AFTER INSERT ON `person_facts` BEGIN " +
                        "INSERT INTO `person_facts_fts`(`docid`, `fact`, `category`, `source`) " +
                        "VALUES (NEW.`rowid`, NEW.`fact`, NEW.`category`, NEW.`source`); END"
                )
                connection.execSQL("INSERT INTO person_facts_fts(person_facts_fts) VALUES('rebuild')")
            }
        }

        /**
         * v13 — the sync substrate (docs/SYNC_DESIGN.md §13, slice S0):
         * the append-only CRDT journal + per-peer version vectors, plus the
         * four sync metadata columns (syncHcWall, syncHcCounter, syncDeviceId,
         * syncTombstone — verbatim Room property names) on every syncable
         * table. Existing rows get the defaults (0/'') — they are the pre-sync
         * baseline and never win an LWW merge. Derived/device-local tables
         * (FTS, embeddings, file_text_content, screen_nodes/nav_edges,
         * learning_drafts, kv_store, commitments, person_channel_prefs)
         * deliberately get NO columns — kv_store sync is namespaced by key,
         * the rest never sync.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(connection: SQLiteConnection) {
                // NOTE: Room keeps property names verbatim as column names (no
                // snake_case — see schemas/*.json: packageName, personId, ...).
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_journal (
                        opId TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        hlcWall INTEGER NOT NULL DEFAULT 0,
                        hlcCounter INTEGER NOT NULL DEFAULT 0,
                        kind TEXT NOT NULL,
                        tableName TEXT NOT NULL,
                        key TEXT NOT NULL,
                        payload BLOB NOT NULL,
                        tombstone INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        PRIMARY KEY (opId)
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_sync_journal_tableName ON sync_journal(tableName)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_sync_journal_tableName_key ON sync_journal(tableName, key)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_sync_journal_deviceId ON sync_journal(deviceId)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_sync_journal_hlcWall_hlcCounter ON sync_journal(hlcWall, hlcCounter)")

                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_vector (
                        peerDeviceId TEXT NOT NULL,
                        lastAppliedHlcWall INTEGER NOT NULL DEFAULT 0,
                        lastAppliedHlcCounter INTEGER NOT NULL DEFAULT 0,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY (peerDeviceId)
                    )
                """)

                // 17 syncable tables × 4 sync columns. Table names are compile-time
                // constants (never runtime input — R12), same pattern as the FTS
                // trigger drops in MIGRATION_11_12.
                val syncable = listOf(
                    "memory_records", "triples", "entities", "predicates", "edges",
                    "blobs", "entity_aliases", "persons", "person_facts",
                    "person_mentions", "person_snapshots", "person_policies",
                    "ui_procedures", "app_records", "app_capability_links",
                    "trigger_rules", "file_objects"
                )
                syncable.forEach { t ->
                    connection.execSQL("ALTER TABLE $t ADD COLUMN syncHcWall INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("ALTER TABLE $t ADD COLUMN syncHcCounter INTEGER NOT NULL DEFAULT 0")
                    connection.execSQL("ALTER TABLE $t ADD COLUMN syncDeviceId TEXT NOT NULL DEFAULT ''")
                    connection.execSQL("ALTER TABLE $t ADD COLUMN syncTombstone INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /**
         * v14 — the three-layer hierarchical agent memory (docs/MEMORY_DESIGN.md):
         * L2 `agent_scratchpad` (private per-agent, TTL-scoped, LOCAL ONLY — no
         * sync columns, isolation is the point), the `work_log` dedupe ledger
         * (device-local — the swarm shares one DB), and the three SYNCED layers:
         * `episodes` (episodic memory with outcome + lesson — collective
         * learning), `handoffs` (shared-write structured artifacts + pointers),
         * `library_entries` (the shared read-only Global Library behind the
         * PENDING_APPROVAL human-in-the-loop gate). All three synced tables get
         * the same four sync columns as the v13 syncable set.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(connection: SQLiteConnection) {
                // L2 — local only, no sync columns.
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS agent_scratchpad (
                        agentId TEXT NOT NULL,
                        `key` TEXT NOT NULL,
                        value TEXT NOT NULL,
                        updatedAtMs INTEGER NOT NULL DEFAULT 0,
                        expiresAtMs INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (agentId, `key`)
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_agent_scratchpad_agentId ON agent_scratchpad(agentId)")

                // Zero-duplication ledger — local only. Kotlin defaults, no
                // @ColumnInfo → the migration must match Room's generated
                // schema exactly (no DEFAULT clauses it won't emit).
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS work_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        action TEXT NOT NULL,
                        resource TEXT NOT NULL,
                        agentId TEXT NOT NULL,
                        status TEXT NOT NULL,
                        atMs INTEGER NOT NULL
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_work_log_action ON work_log(action)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_work_log_resource ON work_log(resource)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_work_log_status ON work_log(status)")

                // Episodic — synced. Kotlin defaults, no @ColumnInfo → no
                // DEFAULT clauses except the sync columns (which carry
                // @ColumnInfo(defaultValue) in the entity).
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS episodes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        episodeId TEXT NOT NULL,
                        agentId TEXT NOT NULL,
                        category TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        outcome TEXT NOT NULL,
                        lesson TEXT NOT NULL,
                        occurredAtMs INTEGER NOT NULL,
                        contextRef TEXT NOT NULL,
                        syncHcWall INTEGER NOT NULL DEFAULT 0,
                        syncHcCounter INTEGER NOT NULL DEFAULT 0,
                        syncDeviceId TEXT NOT NULL DEFAULT '',
                        syncTombstone INTEGER NOT NULL DEFAULT 0
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_agentId ON episodes(agentId)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_occurredAtMs ON episodes(occurredAtMs)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_episodes_outcome ON episodes(outcome)")

                // Handoffs — synced.
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS handoffs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        handoffId TEXT NOT NULL,
                        fromAgent TEXT NOT NULL,
                        toAgent TEXT NOT NULL,
                        task TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        artifactJson TEXT NOT NULL,
                        status TEXT NOT NULL,
                        refId TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        syncHcWall INTEGER NOT NULL DEFAULT 0,
                        syncHcCounter INTEGER NOT NULL DEFAULT 0,
                        syncDeviceId TEXT NOT NULL DEFAULT '',
                        syncTombstone INTEGER NOT NULL DEFAULT 0
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_handoffs_fromAgent ON handoffs(fromAgent)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_handoffs_toAgent ON handoffs(toAgent)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_handoffs_status ON handoffs(status)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_handoffs_createdAtMs ON handoffs(createdAtMs)")

                // Global Library — synced.
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS library_entries (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        entryId TEXT NOT NULL,
                        category TEXT NOT NULL,
                        title TEXT NOT NULL,
                        content TEXT NOT NULL,
                        confidence INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        status TEXT NOT NULL,
                        createdAtMs INTEGER NOT NULL,
                        decidedAtMs INTEGER NOT NULL,
                        syncHcWall INTEGER NOT NULL DEFAULT 0,
                        syncHcCounter INTEGER NOT NULL DEFAULT 0,
                        syncDeviceId TEXT NOT NULL DEFAULT '',
                        syncTombstone INTEGER NOT NULL DEFAULT 0
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_library_entries_category ON library_entries(category)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_library_entries_status ON library_entries(status)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_library_entries_title ON library_entries(title)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE ui_procedures ADD COLUMN prerequisites TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE ui_procedures ADD COLUMN recoveryPaths TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE ui_procedures ADD COLUMN successConditions TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE file_objects ADD COLUMN contentUriString TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE file_objects ADD COLUMN mediaStoreId INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS file_objects (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        path TEXT NOT NULL,
                        filename TEXT NOT NULL,
                        extension TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        createdMs INTEGER NOT NULL DEFAULT 0,
                        modifiedMs INTEGER NOT NULL DEFAULT 0,
                        receivedMs INTEGER NOT NULL DEFAULT 0,
                        lastOpenedMs INTEGER NOT NULL DEFAULT 0,
                        sourceApp TEXT NOT NULL DEFAULT '',
                        folder TEXT NOT NULL DEFAULT '',
                        sha256 TEXT NOT NULL DEFAULT '',
                        pHash TEXT NOT NULL DEFAULT '',
                        metadataJson TEXT NOT NULL DEFAULT '',
                        thumbnailPath TEXT,
                        entitiesJson TEXT NOT NULL DEFAULT '',
                        conceptsJson TEXT NOT NULL DEFAULT '',
                        graphEntityId INTEGER,
                        embeddingId INTEGER,
                        indexState INTEGER NOT NULL DEFAULT 0,
                        isDuplicate INTEGER NOT NULL DEFAULT 0,
                        canonicalId INTEGER
                    )
                """)
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_file_objects_path ON file_objects(path)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_sha256 ON file_objects(sha256)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_pHash ON file_objects(pHash)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_extension ON file_objects(extension)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_folder ON file_objects(folder)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_mimeType ON file_objects(mimeType)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_sourceApp ON file_objects(sourceApp)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_modifiedMs ON file_objects(modifiedMs)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_createdMs ON file_objects(createdMs)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_receivedMs ON file_objects(receivedMs)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_indexState ON file_objects(indexState)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_graphEntityId ON file_objects(graphEntityId)")

                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS file_text_content (
                        fileId INTEGER NOT NULL PRIMARY KEY,
                        text TEXT NOT NULL,
                        language TEXT NOT NULL DEFAULT '',
                        pageCount INTEGER NOT NULL DEFAULT 0,
                        wordCount INTEGER NOT NULL DEFAULT 0,
                        extractedMs INTEGER NOT NULL
                    )
                """)

                // Must match the Room-generated schema exactly (schemas/*.json): self-contained
                // FTS4 keyed by rowid, column declared NOT NULL. The previous external-content
                // form (content="file_text_content") failed Room's schema validation with
                // "Migration didn't properly handle: file_text_fts".
                connection.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS `file_text_fts`
                    USING FTS4(`text` TEXT NOT NULL)
                """)

                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS file_entity_links (
                        fileId INTEGER NOT NULL,
                        entityLabel TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        graphEntityId INTEGER,
                        PRIMARY KEY (fileId, entityLabel)
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_entity_links_entityLabel ON file_entity_links(entityLabel)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_entity_links_entityType  ON file_entity_links(entityType)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_file_entity_links_graphEntityId  ON file_entity_links(graphEntityId)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS trigger_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        label TEXT NOT NULL,
                        conditionType TEXT NOT NULL,
                        conditionParams TEXT NOT NULL,
                        actionType TEXT NOT NULL,
                        actionParams TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        debounceMs INTEGER NOT NULL DEFAULT 30000,
                        lastFiredMs INTEGER NOT NULL DEFAULT 0,
                        createdMs INTEGER NOT NULL
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_rules_enabled ON trigger_rules(enabled)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_rules_conditionType ON trigger_rules(conditionType)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("""CREATE TABLE IF NOT EXISTS person_snapshots (personEntityId INTEGER NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, canonicalPhone TEXT, canonicalEmail TEXT, preferredChannel TEXT, preferredLanguage TEXT NOT NULL DEFAULT '', preferredTone TEXT NOT NULL DEFAULT '', relationshipType TEXT NOT NULL DEFAULT '', activeProjectId TEXT, pendingCommitmentCount INTEGER NOT NULL DEFAULT 0, recentTopics TEXT NOT NULL DEFAULT '', lastInteractionMs INTEGER NOT NULL DEFAULT 0, importanceScore INTEGER NOT NULL DEFAULT 50, snapshotUpdatedMs INTEGER NOT NULL DEFAULT 0)""")
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_person_snapshots_personEntityId ON person_snapshots(personEntityId)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_person_snapshots_lastInteractionMs ON person_snapshots(lastInteractionMs)")
                connection.execSQL("""CREATE TABLE IF NOT EXISTS person_policies (personEntityId INTEGER NOT NULL PRIMARY KEY, canAutoOpenChat INTEGER NOT NULL DEFAULT 1, canAutoDraft INTEGER NOT NULL DEFAULT 1, canAutoSend INTEGER NOT NULL DEFAULT 0, canCallWithoutConfirm INTEGER NOT NULL DEFAULT 0, canShareFiles INTEGER NOT NULL DEFAULT 1, sensitiveActionsRequireConfirm INTEGER NOT NULL DEFAULT 1)""")
                connection.execSQL("""CREATE TABLE IF NOT EXISTS person_channel_prefs (personEntityId INTEGER NOT NULL, taskContext TEXT NOT NULL, packageName TEXT NOT NULL, capability TEXT NOT NULL, probability REAL NOT NULL DEFAULT 0.8, evidenceCount INTEGER NOT NULL DEFAULT 1, lastUpdatedMs INTEGER NOT NULL, PRIMARY KEY (personEntityId, taskContext))""")
                connection.execSQL("""CREATE TABLE IF NOT EXISTS commitments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, debtorPersonId INTEGER, creditorPersonId INTEGER, debtorLabel TEXT NOT NULL, creditorLabel TEXT NOT NULL, action TEXT NOT NULL, dueMs INTEGER, status TEXT NOT NULL DEFAULT 'pending', source TEXT NOT NULL DEFAULT '', confidence INTEGER NOT NULL DEFAULT 80, createdMs INTEGER NOT NULL, resolvedMs INTEGER)""")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_debtorPersonId ON commitments(debtorPersonId)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_creditorPersonId ON commitments(creditorPersonId)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_status ON commitments(status)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_dueMs ON commitments(dueMs)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        label TEXT NOT NULL,
                        version TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT '',
                        launchActivity TEXT,
                        needsValidation INTEGER NOT NULL DEFAULT 0,
                        lastScanMs INTEGER NOT NULL
                    )
                """)
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_app_records_packageName ON app_records(packageName)")

                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_capability_links (
                        packageName TEXT NOT NULL,
                        capability TEXT NOT NULL,
                        intentAction TEXT,
                        deepLinkPattern TEXT,
                        mimeTypes TEXT,
                        confidence INTEGER NOT NULL DEFAULT 80,
                        PRIMARY KEY (packageName, capability)
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_app_capability_links_capability ON app_capability_links(capability)")

                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS ui_procedures (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        versionRange TEXT NOT NULL,
                        taskCapability TEXT NOT NULL,
                        steps TEXT NOT NULL,
                        screenSignature TEXT NOT NULL DEFAULT '',
                        confidence INTEGER NOT NULL DEFAULT 80,
                        successCount INTEGER NOT NULL DEFAULT 0,
                        failureCount INTEGER NOT NULL DEFAULT 0,
                        lastRunMs INTEGER NOT NULL DEFAULT 0,
                        needsValidation INTEGER NOT NULL DEFAULT 0
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_ui_procedures_packageName ON ui_procedures(packageName)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_ui_procedures_taskCapability ON ui_procedures(taskCapability)")

                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS screen_nodes (
                        packageName TEXT NOT NULL,
                        screenSignature TEXT NOT NULL,
                        screenType TEXT NOT NULL DEFAULT '',
                        nodes TEXT NOT NULL DEFAULT '',
                        appVersion TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY (packageName, screenSignature)
                    )
                """)

                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS nav_edges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fromSignature TEXT NOT NULL,
                        toSignature TEXT NOT NULL,
                        actionViewId TEXT NOT NULL DEFAULT '',
                        actionContentDesc TEXT NOT NULL DEFAULT '',
                        actionText TEXT NOT NULL DEFAULT ''
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_nav_edges_fromSignature ON nav_edges(fromSignature)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_nav_edges_toSignature   ON nav_edges(toSignature)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS memory_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        category TEXT NOT NULL DEFAULT '',
                        subject TEXT NOT NULL DEFAULT '',
                        source TEXT NOT NULL DEFAULT '',
                        confidence INTEGER NOT NULL DEFAULT 80,
                        importance INTEGER NOT NULL DEFAULT 50,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        validFrom INTEGER,
                        validUntil INTEGER,
                        contentHash TEXT NOT NULL DEFAULT '',
                        graphEdgeId INTEGER,
                        embeddingId TEXT
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_contentHash    ON memory_records(contentHash)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_subject ON memory_records(subject)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_type    ON memory_records(type)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_createdAt ON memory_records(createdAt)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_validUntil   ON memory_records(validUntil)")

                // Backfill: migrate person_facts → memory_records (type=FACT=1)
                val now = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
                connection.execSQL("""
                    INSERT INTO memory_records (type, content, category, subject, source, confidence, importance, createdAt, updatedAt, contentHash)
                    SELECT
                        1,
                        fact,
                        COALESCE(category, ''),
                        COALESCE((SELECT name FROM persons WHERE id = personId), ''),
                        COALESCE(source, ''),
                        80,
                        50,
                        $now,
                        $now,
                        ''
                    FROM person_facts
                """)
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                // ── entities ─────────────────────────────────────────────────
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS entities (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type INTEGER NOT NULL DEFAULT 0,
                        canonicalName TEXT NOT NULL,
                        payloadPointer INTEGER,
                        createdAt INTEGER NOT NULL
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_entities_canonicalName ON entities(canonicalName)")

                // ── entity_aliases ────────────────────────────────────────────
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS entity_aliases (
                        entityId INTEGER NOT NULL,
                        alias TEXT NOT NULL,
                        PRIMARY KEY (entityId, alias)
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_entity_aliases_alias ON entity_aliases(alias)")

                // ── predicates ────────────────────────────────────────────────
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS predicates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL
                    )
                """)
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_predicates_name ON predicates(name)")

                // ── edges ─────────────────────────────────────────────────────
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS edges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        subjectId INTEGER NOT NULL,
                        predicateId INTEGER NOT NULL,
                        objectId INTEGER,
                        objectValue TEXT,
                        confidence INTEGER NOT NULL DEFAULT 80,
                        importance INTEGER NOT NULL DEFAULT 50,
                        createdAt INTEGER NOT NULL,
                        validFrom INTEGER,
                        validUntil INTEGER,
                        sourceId INTEGER
                    )
                """)
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_edges_subjectId_predicateId ON edges(subjectId, predicateId)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_edges_predicateId_objectId ON edges(predicateId, objectId)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_edges_subjectId_predicateId_objectId ON edges(subjectId, predicateId, objectId)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_edges_validUntil ON edges(validUntil)")

                // ── blobs ─────────────────────────────────────────────────────
                connection.execSQL("""
                    CREATE TABLE IF NOT EXISTS blobs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)

                // ── seed standard predicates ──────────────────────────────────
                StandardPredicates.ALL.forEach { name ->
                    connection.execSQL("INSERT OR IGNORE INTO predicates(name) VALUES ('$name')")
                }

                // ── migrate triples → normalized graph ────────────────────────
                // Create one entity per unique triple subject
                connection.execSQL("""
                    INSERT INTO entities (type, canonicalName, createdAt)
                    SELECT 0, subject, MIN(createdMs) FROM triples GROUP BY subject
                """)

                // Create edges: join subject→entity, predicate→predicates table
                connection.execSQL("""
                    INSERT INTO edges (subjectId, predicateId, objectValue, confidence, importance, createdAt)
                    SELECT
                        e.id,
                        COALESCE(
                            (SELECT p.id FROM predicates p WHERE p.name = t.predicate),
                            (SELECT p2.id FROM predicates p2 WHERE p2.name = 'related_to')
                        ),
                        t.objectValue,
                        CAST(t.confidence * 100 AS INTEGER),
                        50,
                        t.createdMs
                    FROM triples t
                    JOIN entities e ON LOWER(e.canonicalName) = LOWER(t.subject)
                """)
            }
        }

    }
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object AegisDatabaseConstructor : RoomDatabaseConstructor<AegisDatabase> {
    override fun initialize(): AegisDatabase
}



