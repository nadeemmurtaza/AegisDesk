package com.newax.aegis.db

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

/**
 * Desktop (JVM) database entry point — the KMP Room builder over the bundled
 * SQLite driver. Android keeps its SQLCipher path in androidMain; on the JVM
 * the database is plain bundled SQLite for now (the sync journal payloads are
 * already session-encrypted in transit; at-rest encryption on desktop is a
 * listed next step in docs/SYNC_DESIGN.md). Same migrations as Android — the
 * two platforms share one schema.
 */
fun getAegisDatabase(file: File): AegisDatabase {
    return RoomDatabase.Builder<AegisDatabase>(
        constructor = AegisDatabaseConstructor,
        name = file.absolutePath
    )
        .setDriver(BundledSQLiteDriver())
        .addMigrations(
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
            AegisDatabase.MIGRATION_11_12,
            AegisDatabase.MIGRATION_12_13,
            AegisDatabase.MIGRATION_13_14
        )
        .build()
}
