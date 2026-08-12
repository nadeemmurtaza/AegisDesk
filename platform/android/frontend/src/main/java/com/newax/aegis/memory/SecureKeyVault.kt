package com.newax.aegis.memory

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecureKeyVault {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "aegis_secure_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun store(key: String, value: String) {
        requirePrefs().edit().putString(key, value).apply()
    }

    fun get(key: String): String? = requirePrefs().getString(key, null)

    fun contains(key: String): Boolean = requirePrefs().contains(key)

    private fun requirePrefs(): SharedPreferences =
        checkNotNull(prefs) { "SecureKeyVault.init(context) must be called before use" }
}
