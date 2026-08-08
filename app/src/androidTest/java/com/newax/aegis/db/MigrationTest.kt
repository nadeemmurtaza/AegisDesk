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
        helper.runMigrationsAndValidate(TEST_DB, 2, true)
    }

    @Test
    @Throws(IOException::class)
    fun migrate2To3() {
        helper.createDatabase(TEST_DB, 2).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 3, true)
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4() {
        helper.createDatabase(TEST_DB, 3).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 4, true)
    }

    @Test
    @Throws(IOException::class)
    fun migrate4To5() {
        helper.createDatabase(TEST_DB, 4).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 5, true)
    }

    @Test
    @Throws(IOException::class)
    fun migrate5To6() {
        helper.createDatabase(TEST_DB, 5).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 6, true)
    }

    @Test
    @Throws(IOException::class)
    fun migrate6To7() {
        helper.createDatabase(TEST_DB, 6).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 7, true)
    }

    @Test
    @Throws(IOException::class)
    fun migrate7To8() {
        helper.createDatabase(TEST_DB, 7).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 8, true)
    }

    @Test
    @Throws(IOException::class)
    fun migrate8To9() {
        helper.createDatabase(TEST_DB, 8).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 9, true)
    }

    @Test
    @Throws(IOException::class)
    fun migrate9To10() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL("INSERT INTO file_objects (path, filename, extension, mimeType, sizeBytes, indexState) VALUES ('test.pdf', 'test.pdf', 'pdf', 'application/pdf', 1024, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true)
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
        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true)
        val cursor = db.query("SELECT prerequisites, recoveryPaths, successConditions FROM ui_procedures WHERE packageName = 'com.test'")
        assert(cursor.moveToFirst())
        assert(cursor.getString(0) == "")
        assert(cursor.getString(1) == "")
        assert(cursor.getString(2) == "")
        cursor.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrateFullPath_1To11() {
        helper.createDatabase(TEST_DB, 1).apply { close() }
        helper.runMigrationsAndValidate(TEST_DB, 11, true)
    }

    @Test
    @Throws(IOException::class)
    fun validateCurrentSchema() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AegisDatabase::class.java).build()
        db.close()
    }
}
