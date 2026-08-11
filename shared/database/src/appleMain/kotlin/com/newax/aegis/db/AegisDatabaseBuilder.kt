package com.newax.aegis.db

import androidx.room.Room
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.NativeSQLiteDriver
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Apple (macOS + iOS) construction seam for the Aegis memory fabric — the
 * "native driver" of the ARCHITECTURE.md platform matrix. The database is
 * backed by the OS-provided SQLite (libsqlite3) via [NativeSQLiteDriver]
 * from androidx.sqlite:sqlite-framework, instead of the bundled SQLite the
 * desktop uses or the SQLCipher that Android uses.
 *
 * The schema, DAOs, and the eleven migrations are the same shared commonMain
 * surface as every other platform: version 12 with the identical migration
 * chain, so a database created on Android, Windows, or macOS opens unchanged
 * here. This is the [RoomDatabaseConstructor] the commonMain
 * `@ConstructedBy(AegisDatabaseConstructor::class)` expects — required on
 * native targets (Room KMP's findDatabaseConstructorAndInitDatabaseImpl).
 */
actual object AegisDatabaseConstructor : RoomDatabaseConstructor<AegisDatabase> {
    override fun initialize(): AegisDatabase = getAegisDatabase()
}

/** Opens the Aegis database at the platform default location (Application Support). */
fun getAegisDatabase(): AegisDatabase = getAegisDatabase(defaultDatabasePath())

/** Opens the Aegis database at an explicit path (tests, custom locations). */
fun getAegisDatabase(path: String): AegisDatabase =
    Room.databaseBuilder<AegisDatabase>(
        name = path,
    )
        .setDriver(NativeSQLiteDriver())
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
            AegisDatabase.MIGRATION_11_12
        )
        .build()

private fun defaultDatabasePath(): String {
    val dirs = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
    val base = dirs.firstOrNull()
        ?: error("Cannot resolve the Application Support directory on this Apple platform")
    return "$base/aegis.db"
}
