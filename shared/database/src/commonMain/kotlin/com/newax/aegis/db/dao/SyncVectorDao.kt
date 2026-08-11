package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.SyncVectorEntity

/** Per-peer version-vector persistence (docs/SYNC_DESIGN.md §4.2). */
@Dao
interface SyncVectorDao {

    /** Insert or advance a peer's watermark. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(vector: SyncVectorEntity)

    @Query("SELECT * FROM sync_vector")
    suspend fun getAll(): List<SyncVectorEntity>

    @Query("SELECT * FROM sync_vector WHERE peerDeviceId = :peerDeviceId LIMIT 1")
    suspend fun getByPeer(peerDeviceId: String): SyncVectorEntity?

    @Query("DELETE FROM sync_vector WHERE peerDeviceId = :peerDeviceId")
    suspend fun remove(peerDeviceId: String): Int
}
