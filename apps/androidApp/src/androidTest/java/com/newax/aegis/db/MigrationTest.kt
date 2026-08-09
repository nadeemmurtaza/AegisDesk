package com.newax.aegis.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB = "aegis-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AegisDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    @Throws(IOException::class)
    fun migrate1To2() {
        helper.createDatabase(TEST_DB, 1).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 2, true, AegisDatabase.MIGRATION_1_2)
    }

    @Test
    @Throws(IOException::class)
    fun migrate2To3() {
        helper.createDatabase(TEST_DB, 2).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 3, true, AegisDatabase.MIGRATION_2_3)
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4() {
        helper.createDatabase(TEST_DB, 3).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 4, true, AegisDatabase.MIGRATION_3_4)
    }

    @Test
    @Throws(IOException::class)
    fun migrate4To5() {
        helper.createDatabase(TEST_DB, 4).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 5, true, AegisDatabase.MIGRATION_4_5)
    }

    @Test
    @Throws(IOException::class)
    fun migrate5To6() {
        helper.createDatabase(TEST_DB, 5).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 6, true, AegisDatabase.MIGRATION_5_6)
    }

    @Test
    @Throws(IOException::class)
    fun migrate6To7() {
        helper.createDatabase(TEST_DB, 6).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 7, true, AegisDatabase.MIGRATION_6_7)
    }

    @Test
    @Throws(IOException::class)
    fun migrate7To8() {
        helper.createDatabase(TEST_DB, 7).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 8, true, AegisDatabase.MIGRATION_7_8)
    }

    @Test
    @Throws(IOException::class)
    fun migrate8To9() {
        helper.createDatabase(TEST_DB, 8).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 9, true, AegisDatabase.MIGRATION_8_9)
    }

    @Test
    @Throws(IOException::class)
    fun migrate9To10() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL("INSERT INTO file_objects (path, filename, extension, mimeType, sizeBytes, indexState) VALUES ('test.pdf', 'test.pdf', 'pdf', 'application/pdf', 1024, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, AegisDatabase.MIGRATION_9_10)
        val cursor = db.query("SELECT contentUriString, mediaStoreId FROM file_objects WHERE path = 'test.pdf'")
        assert(cursor.moveToFirst())
        assert(cursor.getString(0) == "")
        assert(cursor.getLong(1) == 0L)
        cursor.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrate10To11() {
        helper.createDatabase(TEST_DB, 10).apply {
            execSQL("INSERT INTO ui_procedures (packageName, versionRange, taskCapability, steps, confidence, successCount, failureCount, lastRunMs, needsValidation) VALUES ('com.test', '*', 'SEND_MESSAGE', '[]', 80, 0, 0, 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, AegisDatabase.MIGRATION_10_11)
        val cursor = db.query("SELECT prerequisites, recoveryPaths, successConditions FROM ui_procedures WHERE packageName = 'com.test'")
        assert(cursor.moveToFirst())
        assert(cursor.getString(0) == "")
        assert(cursor.getString(1) == "")
        assert(cursor.getString(2) == "")
        cursor.close()
    }

    /**
     * v12 replaces the Callback-created person_facts_fts with a Room-managed FTS entity.
     * Seeds a fact first so the rebuild is exercised, not just the CREATE.
     */
    @Test
    @Throws(IOException::class)
    fun migrate11To12() {
        helper.createDatabase(TEST_DB, 11).apply {
            execSQL("INSERT INTO persons (name, importanceScore, sourceCount, totalMentions, lastSeenMs, profileBuilt) VALUES ('Ayesha', 0.5, 1, 1, 0, 0)")
            execSQL("INSERT INTO person_facts (personId, fact, category, confidence, source, timestampMs) VALUES (1, 'lives in Karachi', 'personal', 0.9, 'test', 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, AegisDatabase.MIGRATION_11_12)
        val cursor = db.query(
            "SELECT rowid FROM person_facts_fts WHERE person_facts_fts MATCH 'Karachi'"
        )
        cursor.use { assert(it.moveToFirst()) { "FTS index was not rebuilt from person_facts" } }
    }

    @Test
    @Throws(IOException::class)
    fun migrateFullPath_1To12() {
        helper.createDatabase(TEST_DB, 1).apply { close() }
        helper.runMigrationsAndValidate(
            TEST_DB, 12, true,
            AegisDatabase.MIGRATION_1_2,
            AegisDatabase.MIGRATION_2_3,
            AegisDatabase.MIGRATION_3_4,
            AegisDatabase.MIGRATION_4_5,
            AegisDatabase.MIGRATION_5_6,
            AegisDatabase.MIGRATION_6_7,
            AegisDatabase.MIGRATION_7_8,
            AegisDatabase.MIGRATION_8_9,
            AegisDatabase.MIGRATION_9_10,
            AegisDatabase.MIGRATION_10_11,
            AegisDatabase.MIGRATION_11_12
        )
    }

    @Test
    @Throws(IOException::class)
    fun validateCurrentSchema() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AegisDatabase::class.java).build()
        db.close()
    }
}
