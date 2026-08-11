package com.newax.aegis.db

import androidx.room.RoomDatabaseConstructor

/**
 * Desktop actual for the Room KMP constructor (the expect in AegisDatabase.kt
 * was previously suppressed with no actual — the desktop app never opened the
 * database). The generated `AegisDatabase_Impl` comes from the desktop KSP
 * run and instantiates the Room schema for the JVM target; the driver is
 * applied by [getAegisDatabase].
 */
actual object AegisDatabaseConstructor : RoomDatabaseConstructor<AegisDatabase> {
    override fun initialize(): AegisDatabase = AegisDatabase_Impl()
}
