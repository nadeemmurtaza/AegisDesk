package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.KvStoreEntity

@Dao
interface KvStoreDao {

    @Query("SELECT value FROM kv_store WHERE key = :key")
    fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entry: KvStoreEntity)

    @Query("DELETE FROM kv_store WHERE key = :key")
    fun delete(key: String)

    @Query("SELECT * FROM kv_store WHERE key LIKE :prefix || '%'")
    fun getWithPrefix(prefix: String): List<KvStoreEntity>
}
