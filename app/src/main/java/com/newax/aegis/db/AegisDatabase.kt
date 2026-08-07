package com.newax.aegis.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.newax.aegis.db.dao.*
import com.newax.aegis.db.entity.*
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
        TripleEntity::class
    ],
    version = 3,
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
