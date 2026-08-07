package com.newax.aegis.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newax.aegis.db.dao.*
import com.newax.aegis.db.entity.*
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
        EntityAlias::class
    ],
    version = 4,
    exportSchema = false
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
                val passphrase = DbKeyManager.getOrCreate(memory)
                INSTANCE = Room.databaseBuilder(
                    context.applicationContext,
                    AegisDatabase::class.java,
                    "aegis.db"
                )
                    .openHelperFactory(SupportFactory(passphrase))
                    .addCallback(FtsSetupCallback())
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
