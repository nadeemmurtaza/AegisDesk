package com.newax.aegis.db

import androidx.room.Room
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
fun getNewaxDatabase(file: File): NewaxDatabase {
    return Room.databaseBuilder<NewaxDatabase>(
        name = file.absolutePath
    )
        .setDriver(BundledSQLiteDriver())
        .addMigrations(
            NewaxDatabase.MIGRATION_1_2,
            NewaxDatabase.MIGRATION_2_3,
            NewaxDatabase.MIGRATION_3_4,
            NewaxDatabase.MIGRATION_4_5,
            NewaxDatabase.MIGRATION_5_6,
            NewaxDatabase.MIGRATION_6_7,
            NewaxDatabase.MIGRATION_7_8,
            NewaxDatabase.MIGRATION_8_9,
            NewaxDatabase.MIGRATION_9_10,
            NewaxDatabase.MIGRATION_10_11,
            NewaxDatabase.MIGRATION_11_12,
            NewaxDatabase.MIGRATION_12_13,
            NewaxDatabase.MIGRATION_13_14,
            NewaxDatabase.MIGRATION_14_15,
            NewaxDatabase.MIGRATION_15_16,
            NewaxDatabase.MIGRATION_16_17,
            NewaxDatabase.MIGRATION_17_18,
            NewaxDatabase.MIGRATION_18_19
        )
        .build()
}
