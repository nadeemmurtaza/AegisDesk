package com.newax.aegis.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        KvStoreEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AegisDatabase : RoomDatabase() {

    abstract fun personDao(): PersonDao
    abstract fun personMentionDao(): PersonMentionDao
    abstract fun personFactDao(): PersonFactDao
    abstract fun learningDraftDao(): LearningDraftDao
    abstract fun kvStoreDao(): KvStoreDao

    companion object {
        @Volatile private var INSTANCE: AegisDatabase? = null

        /**
         * Initialize the database. Must be called once before [get] is used.
         * Safe to call multiple times — subsequent calls are no-ops.
         */
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
                    .build()
                passphrase.fill(0)  // zero passphrase from memory after use
            }
        }

        val get: AegisDatabase
            get() = INSTANCE ?: error("AegisDatabase.init() not called")
    }

    /** Creates the FTS4 virtual table and triggers on first open. */
    private class FtsSetupCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            // FTS4 external-content table backed by person_facts
            db.execSQL("""
                CREATE VIRTUAL TABLE IF NOT EXISTS person_facts_fts
                USING fts4(content="person_facts", fact, category, source)
            """)
            // Keep FTS index in sync via triggers
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
