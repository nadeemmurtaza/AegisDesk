package com.newax.aegis.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newax.aegis.db.dao.*
import com.newax.aegis.db.entity.*
import com.newax.aegis.db.entity.AppRecord
import com.newax.aegis.db.entity.AppCapabilityLink
import com.newax.aegis.db.entity.UiProcedure
import com.newax.aegis.db.entity.ScreenNode
import com.newax.aegis.db.entity.NavEdge
import com.newax.aegis.db.entity.PersonSnapshot
import com.newax.aegis.db.entity.PersonPolicy
import com.newax.aegis.db.entity.PersonChannelPref
import com.newax.aegis.db.entity.Commitment
import com.newax.aegis.db.entity.FileEntityLink
import com.newax.aegis.db.entity.FileObject
import com.newax.aegis.db.entity.FileTextContent
import com.newax.aegis.db.entity.FileTextFts
import com.newax.aegis.db.entity.TriggerRule
import com.newax.aegis.engine.graph.StandardPredicates
import com.newax.aegis.memory.EncryptedMemory
import net.sqlcipher.database.SupportFactory

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
        FileEntityLink::class
    ],
    version = 12,
    exportSchema = true
)
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

    companion object {
        @Volatile private var INSTANCE: AegisDatabase? = null

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS embeddings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sourceType TEXT NOT NULL,
                        sourceId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        embedding BLOB NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_sourceType ON embeddings(sourceType)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_embeddings_sourceId ON embeddings(sourceId)")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
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
                database.execSQL("CREATE INDEX IF NOT EXISTS index_triples_subject ON triples(subject)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_triples_predicate ON triples(predicate)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_triples_subject_predicate ON triples(subject, predicate)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_triples_objectValue ON triples(objectValue)")
            }
        }

        /**
         * Replaces the hand-rolled `person_facts_fts` table (previously created only by
         * FtsSetupCallback, i.e. only on fresh installs) with the Room-managed FTS entity.
         * Installs that migrated up from an earlier version never had the table at all, so
         * this drops whatever is there and rebuilds from `person_facts`.
         */
        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                listOf("pf_ai", "pf_bu", "pf_au", "pf_bd").forEach {
                    database.execSQL("DROP TRIGGER IF EXISTS $it")
                }
                database.execSQL("DROP TABLE IF EXISTS person_facts_fts")
                database.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `person_facts_fts` USING FTS4(" +
                        "`fact` TEXT NOT NULL, `category` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "content=`person_facts`)"
                )
                database.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_person_facts_fts_BEFORE_UPDATE " +
                        "BEFORE UPDATE ON `person_facts` BEGIN " +
                        "DELETE FROM `person_facts_fts` WHERE `docid`=OLD.`rowid`; END"
                )
                database.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_person_facts_fts_BEFORE_DELETE " +
                        "BEFORE DELETE ON `person_facts` BEGIN " +
                        "DELETE FROM `person_facts_fts` WHERE `docid`=OLD.`rowid`; END"
                )
                database.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_person_facts_fts_AFTER_UPDATE " +
                        "AFTER UPDATE ON `person_facts` BEGIN " +
                        "INSERT INTO `person_facts_fts`(`docid`, `fact`, `category`, `source`) " +
                        "VALUES (NEW.`rowid`, NEW.`fact`, NEW.`category`, NEW.`source`); END"
                )
                database.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_person_facts_fts_AFTER_INSERT " +
                        "AFTER INSERT ON `person_facts` BEGIN " +
                        "INSERT INTO `person_facts_fts`(`docid`, `fact`, `category`, `source`) " +
                        "VALUES (NEW.`rowid`, NEW.`fact`, NEW.`category`, NEW.`source`); END"
                )
                database.execSQL("INSERT INTO person_facts_fts(person_facts_fts) VALUES('rebuild')")
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE ui_procedures ADD COLUMN prerequisites TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE ui_procedures ADD COLUMN recoveryPaths TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE ui_procedures ADD COLUMN successConditions TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE file_objects ADD COLUMN contentUriString TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE file_objects ADD COLUMN mediaStoreId INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
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
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_file_objects_path ON file_objects(path)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_sha256 ON file_objects(sha256)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_pHash ON file_objects(pHash)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_extension ON file_objects(extension)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_folder ON file_objects(folder)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_mimeType ON file_objects(mimeType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_sourceApp ON file_objects(sourceApp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_modifiedMs ON file_objects(modifiedMs)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_createdMs ON file_objects(createdMs)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_receivedMs ON file_objects(receivedMs)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_indexState ON file_objects(indexState)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_objects_graphEntityId ON file_objects(graphEntityId)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS file_text_content (
                        fileId INTEGER NOT NULL PRIMARY KEY,
                        text TEXT NOT NULL,
                        language TEXT NOT NULL DEFAULT '',
                        pageCount INTEGER NOT NULL DEFAULT 0,
                        wordCount INTEGER NOT NULL DEFAULT 0,
                        extractedMs INTEGER NOT NULL
                    )
                """)

                database.execSQL("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS file_text_fts
                    USING fts4(content="file_text_content", text)
                """)

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS file_entity_links (
                        fileId INTEGER NOT NULL,
                        entityLabel TEXT NOT NULL,
                        entityType TEXT NOT NULL,
                        graphEntityId INTEGER,
                        PRIMARY KEY (fileId, entityLabel)
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_entity_links_entityLabel ON file_entity_links(entityLabel)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_entity_links_entityType  ON file_entity_links(entityType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_file_entity_links_graphEntityId  ON file_entity_links(graphEntityId)")
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
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
                database.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_rules_enabled ON trigger_rules(enabled)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_trigger_rules_conditionType ON trigger_rules(conditionType)")
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""CREATE TABLE IF NOT EXISTS person_snapshots (personEntityId INTEGER NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, canonicalPhone TEXT, canonicalEmail TEXT, preferredChannel TEXT, preferredLanguage TEXT NOT NULL DEFAULT '', preferredTone TEXT NOT NULL DEFAULT '', relationshipType TEXT NOT NULL DEFAULT '', activeProjectId TEXT, pendingCommitmentCount INTEGER NOT NULL DEFAULT 0, recentTopics TEXT NOT NULL DEFAULT '', lastInteractionMs INTEGER NOT NULL DEFAULT 0, importanceScore INTEGER NOT NULL DEFAULT 50, snapshotUpdatedMs INTEGER NOT NULL DEFAULT 0)""")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_person_snapshots_personEntityId ON person_snapshots(personEntityId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_person_snapshots_lastInteractionMs ON person_snapshots(lastInteractionMs)")
                database.execSQL("""CREATE TABLE IF NOT EXISTS person_policies (personEntityId INTEGER NOT NULL PRIMARY KEY, canAutoOpenChat INTEGER NOT NULL DEFAULT 1, canAutoDraft INTEGER NOT NULL DEFAULT 1, canAutoSend INTEGER NOT NULL DEFAULT 0, canCallWithoutConfirm INTEGER NOT NULL DEFAULT 0, canShareFiles INTEGER NOT NULL DEFAULT 1, sensitiveActionsRequireConfirm INTEGER NOT NULL DEFAULT 1)""")
                database.execSQL("""CREATE TABLE IF NOT EXISTS person_channel_prefs (personEntityId INTEGER NOT NULL, taskContext TEXT NOT NULL, packageName TEXT NOT NULL, capability TEXT NOT NULL, probability REAL NOT NULL DEFAULT 0.8, evidenceCount INTEGER NOT NULL DEFAULT 1, lastUpdatedMs INTEGER NOT NULL, PRIMARY KEY (personEntityId, taskContext))""")
                database.execSQL("""CREATE TABLE IF NOT EXISTS commitments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, debtorPersonId INTEGER, creditorPersonId INTEGER, debtorLabel TEXT NOT NULL, creditorLabel TEXT NOT NULL, action TEXT NOT NULL, dueMs INTEGER, status TEXT NOT NULL DEFAULT 'pending', source TEXT NOT NULL DEFAULT '', confidence INTEGER NOT NULL DEFAULT 80, createdMs INTEGER NOT NULL, resolvedMs INTEGER)""")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_debtorPersonId ON commitments(debtorPersonId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_creditorPersonId ON commitments(creditorPersonId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_status ON commitments(status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_commitments_dueMs ON commitments(dueMs)")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
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
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_app_records_packageName ON app_records(packageName)")

                database.execSQL("""
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
                database.execSQL("CREATE INDEX IF NOT EXISTS index_app_capability_links_capability ON app_capability_links(capability)")

                database.execSQL("""
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
                database.execSQL("CREATE INDEX IF NOT EXISTS index_ui_procedures_packageName ON ui_procedures(packageName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_ui_procedures_taskCapability ON ui_procedures(taskCapability)")

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS screen_nodes (
                        packageName TEXT NOT NULL,
                        screenSignature TEXT NOT NULL,
                        screenType TEXT NOT NULL DEFAULT '',
                        nodes TEXT NOT NULL DEFAULT '',
                        appVersion TEXT NOT NULL DEFAULT '',
                        PRIMARY KEY (packageName, screenSignature)
                    )
                """)

                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS nav_edges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        fromSignature TEXT NOT NULL,
                        toSignature TEXT NOT NULL,
                        actionViewId TEXT NOT NULL DEFAULT '',
                        actionContentDesc TEXT NOT NULL DEFAULT '',
                        actionText TEXT NOT NULL DEFAULT ''
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_nav_edges_fromSignature ON nav_edges(fromSignature)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_nav_edges_toSignature   ON nav_edges(toSignature)")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
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
                database.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_contentHash    ON memory_records(contentHash)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_subject ON memory_records(subject)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_type    ON memory_records(type)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_createdAt ON memory_records(createdAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_validUntil   ON memory_records(validUntil)")

                // Backfill: migrate person_facts → memory_records (type=FACT=1)
                val now = System.currentTimeMillis()
                database.execSQL("""
                    INSERT INTO memory_records (type, content, category, subject, source, confidence, importance, createdAt, updatedAt, contentHash)
                    SELECT
                        1,
                        fact,
                        COALESCE(category, ''),
                        COALESCE((SELECT name FROM people WHERE id = personId), ''),
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

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // ── entities ─────────────────────────────────────────────────
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS entities (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type INTEGER NOT NULL DEFAULT 0,
                        canonicalName TEXT NOT NULL,
                        payloadPointer INTEGER,
                        createdAt INTEGER NOT NULL
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_entities_canonicalName ON entities(canonicalName)")

                // ── entity_aliases ────────────────────────────────────────────
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS entity_aliases (
                        entityId INTEGER NOT NULL,
                        alias TEXT NOT NULL,
                        PRIMARY KEY (entityId, alias)
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS index_entity_aliases_alias ON entity_aliases(alias)")

                // ── predicates ────────────────────────────────────────────────
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS predicates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL
                    )
                """)
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_predicates_name ON predicates(name)")

                // ── edges ─────────────────────────────────────────────────────
                database.execSQL("""
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
                database.execSQL("CREATE INDEX IF NOT EXISTS index_edges_subjectId_predicateId ON edges(subjectId, predicateId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_edges_predicateId_objectId ON edges(predicateId, objectId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_edges_subjectId_predicateId_objectId ON edges(subjectId, predicateId, objectId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_edges_validUntil ON edges(validUntil)")

                // ── blobs ─────────────────────────────────────────────────────
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS blobs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        type INTEGER NOT NULL,
                        content TEXT NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                """)

                // ── seed standard predicates ──────────────────────────────────
                StandardPredicates.ALL.forEach { name ->
                    database.execSQL("INSERT OR IGNORE INTO predicates(name) VALUES ('$name')")
                }

                // ── migrate triples → normalized graph ────────────────────────
                // Create one entity per unique triple subject
                database.execSQL("""
                    INSERT INTO entities (type, canonicalName, createdAt)
                    SELECT 0, subject, MIN(createdMs) FROM triples GROUP BY subject
                """)

                // Create edges: join subject→entity, predicate→predicates table
                database.execSQL("""
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

        fun init(context: Context, memory: EncryptedMemory) {
            if (INSTANCE != null) return
            synchronized(this) {
                if (INSTANCE != null) return
                val passphrase = DbKeyManager.getOrCreate()
                INSTANCE = Room.databaseBuilder(
                    context.applicationContext,
                    AegisDatabase::class.java,
                    "aegis.db"
                )
                    .openHelperFactory(SupportFactory(passphrase))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12)
                    .build()
                passphrase.fill(0)
            }
        }

        val get: AegisDatabase
            get() = INSTANCE ?: error("AegisDatabase.init() not called")
    }

}
