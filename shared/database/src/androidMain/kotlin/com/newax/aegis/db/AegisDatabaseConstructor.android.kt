package com.newax.aegis.db

import androidx.room.RoomDatabaseConstructor

actual object AegisDatabaseConstructor : RoomDatabaseConstructor<AegisDatabase> {
    override fun initialize(): AegisDatabase = AegisDatabase_Impl()
}
