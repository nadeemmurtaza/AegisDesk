package com.newax.aegis.platform

/**
 * Typed outcome of a capability operation. Every capability method returns one of
 * these instead of throwing or returning nullable values, so the executor and
 * verifier can distinguish *why* an operation did not run: the OS denied a
 * permission, a credential is only a reference and is missing, the user disabled
 * the capability, or it genuinely failed.
 */
sealed interface CapabilityResult<out T> {
    data class Success<T>(val value: T) : CapabilityResult<T>
    data class MissingPermission<T>(val permission: String) : CapabilityResult<T>
    data class MissingCredential<T>(val credentialKey: String) : CapabilityResult<T>
    data class Disabled<T>(val reason: String) : CapabilityResult<T>
    data class Failed<T>(val error: String) : CapabilityResult<T>
}

/** The value on [CapabilityResult.Success], otherwise null. */
fun <T> CapabilityResult<T>.getOrNull(): T? = when (this) {
    is CapabilityResult.Success -> value
    else -> null
}

/** True only for [CapabilityResult.Success]. */
fun <T> CapabilityResult<T>.isSuccess(): Boolean = this is CapabilityResult.Success
