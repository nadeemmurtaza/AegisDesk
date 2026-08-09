package com.newax.aegis.platform.secrets

import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PlatformCapability

enum class SecretSource { KEYCHAIN, ANDROID_KEYSTORE, OS_VAULT, ENV }

/**
 * Whether a credential exists and where it lives — the only secret information the
 * model layer ever sees. The raw value never appears here, in prompts, in source,
 * or in logs (AGENTS.md invariant 4: credentials are references, not content).
 */
data class SecretAvailability(
    val key: String,
    val available: Boolean,
    val source: SecretSource? = null,
)

/**
 * Credential storage. The raw value leaves this interface only through [read],
 * which is the single guarded path (R11) for the executor — never for prompt
 * context. Store, read, and delete are privileged and require [OperationContext].
 */
interface SecretsCapability : PlatformCapability {
    override val id: CapabilityId get() = CapabilityId.SECRETS

    fun availability(key: String): CapabilityResult<SecretAvailability>
    fun listKeys(): CapabilityResult<List<String>>

    fun store(key: String, value: String, context: OperationContext): CapabilityResult<Unit>
    fun read(key: String, context: OperationContext): CapabilityResult<String>
    fun delete(key: String, context: OperationContext): CapabilityResult<Unit>
}
