package com.newax.aegis.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Loads SQLCipher's native library. **Required** — nothing else does it.
 *
 * `net.zetetic:sqlcipher-android` (4.x) ships `libsqlcipher.so` for all four
 * ABIs but, unlike the retired `net.sqlcipher:android-database-sqlcipher`, has
 * no static initializer that loads it: not one class in the 4.17.0 artifact
 * calls `System.loadLibrary`. Without this call the classes resolve, the
 * `.so` sits unused in the APK, and the first database open dies with
 *
 *     UnsatisfiedLinkError: No implementation found for
 *     net.zetetic.database.sqlcipher.SQLiteConnection.nativeOpen(...)
 *
 * on **every** device and every ABI. It was found by the API-29 emulator once
 * an earlier crash-on-launch stopped masking it.
 *
 * Idempotent — `System.loadLibrary` is a no-op after the first success, and the
 * flag keeps repeated opens off the JNI lookup path.
 */
@Volatile
private var sqlCipherLoaded = false

@Synchronized
private fun loadSqlCipher() {
    if (sqlCipherLoaded) return
    try {
        System.loadLibrary("sqlcipher")
    } catch (e: UnsatisfiedLinkError) {
        // Unlike sync, the database is not optional — there is no degraded mode
        // to fall back to. Fail with something actionable instead of the raw
        // linker message, which names a method rather than the real problem.
        throw IllegalStateException(
            "SQLCipher's native library could not be loaded. The app cannot open " +
                "its encrypted database without it. Check that libsqlcipher.so is " +
                "packaged for this device's ABI (${android.os.Build.SUPPORTED_ABIS.joinToString()}).",
            e,
        )
    }
    sqlCipherLoaded = true
}

fun getNewaxDatabase(context: Context, passphrase: ByteArray): NewaxDatabase {
    loadSqlCipher()

    val dbFile = context.getDatabasePath("aegis.db")

    val builder = Room.databaseBuilder<NewaxDatabase>(
        context = context.applicationContext,
        name = dbFile.absolutePath
    )

    return builder
        .openHelperFactory(SupportOpenHelperFactory(passphrase))
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
        .also {
            passphrase.fill(0)
        }
}
