package com.newax.aegis.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SupportFactory

fun getAegisDatabase(context: Context, passphrase: ByteArray): AegisDatabase {
    val dbFile = context.getDatabasePath("aegis.db")
    
    val builder = Room.databaseBuilder<AegisDatabase>(
        context = context.applicationContext,
        name = dbFile.absolutePath
    )
    
    return builder
        .openHelperFactory(SupportFactory(passphrase))
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
            AegisDatabase.MIGRATION_13_14,
            AegisDatabase.MIGRATION_14_15,
            AegisDatabase.MIGRATION_15_16,
            AegisDatabase.MIGRATION_16_17
        )
        .build()
        .also {
            passphrase.fill(0)
        }
}
