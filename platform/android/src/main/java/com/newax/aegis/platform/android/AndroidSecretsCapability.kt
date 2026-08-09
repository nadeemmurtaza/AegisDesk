package com.newax.aegis.platform.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.platform.secrets.SecretAvailability
import com.newax.aegis.platform.secrets.SecretSource
import com.newax.aegis.platform.secrets.SecretsCapability

/**
 * Secrets capability on Android: [EncryptedSharedPreferences] with a Keystore-backed
 * AES-256 master key (the same primitive the app's SecureKeyVault uses).
 *
 * Contract discipline (AGENTS.md invariant 4): [availability] is all the model layer
 * sees; [read] is the single guarded path (R11) and returns the raw value only to
 * the executor, never into prompt context, source, or logs. All mutations use
 * `commit()` so failures are surfaced as typed results instead of vanishing.
 */
class AndroidSecretsCapability(context: Context) : SecretsCapability {

    override val id: CapabilityId get() = CapabilityId.SECRETS

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "Secrets",
        description = "Keystore-encrypted credential storage; values are references, never prompt content",
        privilegeLevel = PrivilegeLevel.HIGH_IMPACT_SYSTEM,
        requiredCredentialKey = null, // self-contained vault, no external credential needed
    )

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "aegis_platform_secrets",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun availability(key: String): CapabilityResult<SecretAvailability> =
        CapabilityResult.Success(
            SecretAvailability(
                key = key,
                available = prefs.contains(key),
                source = SecretSource.ANDROID_KEYSTORE,
            )
        )

    override fun listKeys(): CapabilityResult<List<String>> =
        CapabilityResult.Success(prefs.all.keys.sorted())

    override fun store(key: String, value: String, context: OperationContext): CapabilityResult<Unit> =
        try {
            if (prefs.edit().putString(key, value).commit()) {
                CapabilityResult.Success(Unit)
            } else {
                CapabilityResult.Failed("failed to persist secret '$key'")
            }
        } catch (e: Exception) {
            CapabilityResult.Failed("cannot store secret '$key': ${e.message}")
        }

    override fun read(key: String, context: OperationContext): CapabilityResult<String> {
        val value = prefs.getString(key, null)
        return if (value != null) {
            CapabilityResult.Success(value)
        } else {
            CapabilityResult.Failed("no secret stored for '$key'")
        }
    }

    override fun delete(key: String, context: OperationContext): CapabilityResult<Unit> =
        try {
            if (prefs.edit().remove(key).commit()) {
                CapabilityResult.Success(Unit)
            } else {
                CapabilityResult.Failed("failed to delete secret '$key'")
            }
        } catch (e: Exception) {
            CapabilityResult.Failed("cannot delete secret '$key': ${e.message}")
        }
}
