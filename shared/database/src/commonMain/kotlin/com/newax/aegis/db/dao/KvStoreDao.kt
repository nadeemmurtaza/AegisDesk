package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.KvStoreEntity

@Dao
interface KvStoreDao {

    @Query("SELECT value FROM kv_store WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: KvStoreEntity)

    @Query("DELETE FROM kv_store WHERE key = :key")
    suspend fun delete(key: String): Int

    @Query("SELECT * FROM kv_store WHERE key LIKE :prefix || '%'")
    suspend fun getWithPrefix(prefix: String): List<KvStoreEntity>
}
