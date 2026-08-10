package com.newax.aegis.authority

import com.newax.aegis.engine.SecureSettings

/**
 * [PolicyStore] backed by [SecureSettings] — the Android wiring persists the
 * user's per-action-class mode overrides and hard denies in the encrypted
 * settings store, so the user-controllable policy mapping survives restarts.
 *
 * Failure modes are named (R9): a stored mode value that is corrupt or was
 * written by a newer build resolves to null — the caller falls back to the
 * default risk mapping instead of crashing. `putString(key, null)` removes the
 * key (SecureSettings semantics), which is exactly "clear override".
 */
class SecureSettingsPolicyStore(private val settings: SecureSettings) : PolicyStore {

    override fun modeOverride(actionClass: String): PolicyMode? =
        settings.getString("policy_mode_$actionClass")?.let { raw ->
            runCatching { PolicyMode.valueOf(raw) }.getOrNull()
        }

    override fun setModeOverride(actionClass: String, mode: PolicyMode) {
        settings.putString("policy_mode_$actionClass", mode.name)
    }

    override fun clearModeOverride(actionClass: String) {
        settings.putString("policy_mode_$actionClass", null)
    }

    override fun isDenied(actionClass: String): Boolean =
        settings.getBoolean("policy_deny_$actionClass", false)

    override fun setDenied(actionClass: String, denied: Boolean) {
        settings.putBoolean("policy_deny_$actionClass", denied)
    }
}
