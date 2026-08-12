package com.newax.aegis.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

fun getNewaxDatabase(context: Context, passphrase: ByteArray): NewaxDatabase {
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
