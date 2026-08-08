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
    version = 11,
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

        private val MIGRATION_1_2 = object : Migration(1, 2) {
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
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_emb_type ON embeddings(sourceType)")
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_emb_sourceid ON embeddings(sourceId)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
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
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_tri_subject ON triples(subject)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_tri_predicate ON triples(predicate)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_tri_subj_pred ON triples(subject, predicate)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_tri_object ON triples(objectValue)")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE ui_procedures ADD COLUMN prerequisites TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE ui_procedures ADD COLUMN recoveryPaths TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE ui_procedures ADD COLUMN successConditions TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE file_objects ADD COLUMN contentUriString TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE file_objects ADD COLUMN mediaStoreId INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
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
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_fo_path ON file_objects(path)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fo_sha256 ON file_objects(sha256)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fo_phash ON file_objects(pHash)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fo_ext ON file_objects(extension)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fo_folder ON file_objects(folder)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fo_mime ON file_objects(mimeType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fo_src ON file_objects(sourceApp)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fo_mod ON file_objects(modifiedMs)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fo_cre ON file_objects(createdMs)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fo_recv ON file_objects(receivedMs)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fo_state ON file_objects(indexState)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fo_geid ON file_objects(graphEntityId)")

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
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fel_label ON file_entity_links(entityLabel)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fel_type  ON file_entity_links(entityType)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_fel_geid  ON file_entity_links(graphEntityId)")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
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
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_tr_enabled ON trigger_rules(enabled)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_tr_cond ON trigger_rules(conditionType)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""CREATE TABLE IF NOT EXISTS person_snapshots (personEntityId INTEGER NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, canonicalPhone TEXT, canonicalEmail TEXT, preferredChannel TEXT, preferredLanguage TEXT NOT NULL DEFAULT '', preferredTone TEXT NOT NULL DEFAULT '', relationshipType TEXT NOT NULL DEFAULT '', activeProjectId TEXT, pendingCommitmentCount INTEGER NOT NULL DEFAULT 0, recentTopics TEXT NOT NULL DEFAULT '', lastInteractionMs INTEGER NOT NULL DEFAULT 0, importanceScore INTEGER NOT NULL DEFAULT 50, snapshotUpdatedMs INTEGER NOT NULL DEFAULT 0)""")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_ps_interaction ON person_snapshots(lastInteractionMs)")
                database.execSQL("""CREATE TABLE IF NOT EXISTS person_policies (personEntityId INTEGER NOT NULL PRIMARY KEY, canAutoOpenChat INTEGER NOT NULL DEFAULT 1, canAutoDraft INTEGER NOT NULL DEFAULT 1, canAutoSend INTEGER NOT NULL DEFAULT 0, canCallWithoutConfirm INTEGER NOT NULL DEFAULT 0, canShareFiles INTEGER NOT NULL DEFAULT 1, sensitiveActionsRequireConfirm INTEGER NOT NULL DEFAULT 1)""")
                database.execSQL("""CREATE TABLE IF NOT EXISTS person_channel_prefs (personEntityId INTEGER NOT NULL, taskContext TEXT NOT NULL, packageName TEXT NOT NULL, capability TEXT NOT NULL, probability REAL NOT NULL DEFAULT 0.8, evidenceCount INTEGER NOT NULL DEFAULT 1, lastUpdatedMs INTEGER NOT NULL, PRIMARY KEY (personEntityId, taskContext))""")
                database.execSQL("""CREATE TABLE IF NOT EXISTS commitments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, debtorPersonId INTEGER, creditorPersonId INTEGER, debtorLabel TEXT NOT NULL, creditorLabel TEXT NOT NULL, action TEXT NOT NULL, dueMs INTEGER, status TEXT NOT NULL DEFAULT 'pending', source TEXT NOT NULL DEFAULT '', confidence INTEGER NOT NULL DEFAULT 80, createdMs INTEGER NOT NULL, resolvedMs INTEGER)""")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_com_debtor ON commitments(debtorPersonId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_com_creditor ON commitments(creditorPersonId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_com_status ON commitments(status)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_com_due ON commitments(dueMs)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
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
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_ar_pkg ON app_records(packageName)")

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
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_acl_cap ON app_capability_links(capability)")

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
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_up_pkg ON ui_procedures(packageName)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_up_cap ON ui_procedures(taskCapability)")

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
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_ne_from ON nav_edges(fromSignature)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_ne_to   ON nav_edges(toSignature)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
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
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_mr_hash    ON memory_records(contentHash)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_mr_subject ON memory_records(subject)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_mr_type    ON memory_records(type)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_mr_created ON memory_records(createdAt)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_mr_valid   ON memory_records(validUntil)")

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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
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
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_ent_name ON entities(canonicalName)")

                // ── entity_aliases ────────────────────────────────────────────
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS entity_aliases (
                        entityId INTEGER NOT NULL,
                        alias TEXT NOT NULL,
                        PRIMARY KEY (entityId, alias)
                    )
                """)
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_alias_alias ON entity_aliases(alias)")

                // ── predicates ────────────────────────────────────────────────
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS predicates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL
                    )
                """)
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_pred_name ON predicates(name)")

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
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_edge_subj_pred ON edges(subjectId, predicateId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_edge_pred_obj ON edges(predicateId, objectId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_edge_subj_pred_obj ON edges(subjectId, predicateId, objectId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS idx_edge_valid ON edges(validUntil)")

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
                    .addCallback(FtsSetupCallback())
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                    .build()
                passphrase.fill(0)
            }
        }

        val get: AegisDatabase
            get() = INSTANCE ?: error("AegisDatabase.init() not called")
    }

    private class FtsSetupCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS person_facts_fts
                USING fts4(content="person_facts", fact, category, source)
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS pf_ai AFTER INSERT ON person_facts BEGIN
                    INSERT INTO person_facts_fts(rowid, fact, category, source)
                    VALUES(new.id, new.fact, new.category, new.source);
                END
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS pf_bu BEFORE UPDATE ON person_facts BEGIN
                    DELETE FROM person_facts_fts WHERE rowid = old.id;
                END
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS pf_au AFTER UPDATE ON person_facts BEGIN
                    INSERT INTO person_facts_fts(rowid, fact, category, source)
                    VALUES(new.id, new.fact, new.category, new.source);
                END
            """)
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS pf_bd BEFORE DELETE ON person_facts BEGIN
                    DELETE FROM person_facts_fts WHERE rowid = old.id;
                END
            """)
        }
    }
}
