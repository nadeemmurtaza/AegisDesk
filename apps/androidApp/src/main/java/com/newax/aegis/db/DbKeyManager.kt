package com.newax.aegis.db

import android.util.Base64
import com.newax.aegis.memory.EncryptedMemory
import com.newax.aegis.memory.SecureKeyVault
import java.security.SecureRandom

object DbKeyManager {
    private const val KEY = "aegis_db_passphrase"

    fun migrateFromMemoryIfNeeded(memory: EncryptedMemory) {
        if (!SecureKeyVault.contains(KEY)) {
            val oldKey = memory.getRaw(KEY)
            if (oldKey != null) {
                SecureKeyVault.store(KEY, oldKey)
            }
        }
    }

    fun getOrCreate(): ByteArray {
        val stored = SecureKeyVault.get(KEY)
        if (stored != null) return Base64.decode(stored, Base64.NO_WRAP)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        SecureKeyVault.store(KEY, Base64.encodeToString(key, Base64.NO_WRAP))
        return key
    }
}
