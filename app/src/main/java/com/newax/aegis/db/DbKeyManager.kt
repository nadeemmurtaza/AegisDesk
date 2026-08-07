package com.newax.aegis.db

import android.util.Base64
import com.newax.aegis.memory.EncryptedMemory
import java.security.SecureRandom

object DbKeyManager {
    private const val KEY = "aegis_db_passphrase"

    fun getOrCreate(memory: EncryptedMemory): ByteArray {
        val stored = memory.getRaw(KEY)
        if (stored != null) return Base64.decode(stored, Base64.NO_WRAP)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        memory.storeRaw(KEY, Base64.encodeToString(key, Base64.NO_WRAP))
        return key
    }
}
