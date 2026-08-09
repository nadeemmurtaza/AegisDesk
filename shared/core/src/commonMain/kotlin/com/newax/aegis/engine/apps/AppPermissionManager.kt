package com.newax.aegis.engine.apps

import com.newax.aegis.engine.SecureSettings

enum class AppPermissionState {
    ALLOWED, DENIED, ASK_EVERY_TIME
}

object AppPermissionManager {
    private const val PREFIX = "app_perm_"
    private var secureSettings: SecureSettings? = null

    fun init(settings: SecureSettings) {
        secureSettings = settings
    }

    fun getPermission(packageName: String): AppPermissionState {
        val stateString = secureSettings?.getString("$PREFIX$packageName") ?: AppPermissionState.ASK_EVERY_TIME.name
        return try {
            AppPermissionState.valueOf(stateString)
        } catch (e: Exception) {
            AppPermissionState.ASK_EVERY_TIME
        }
    }

    fun setPermission(packageName: String, state: AppPermissionState) {
        secureSettings?.putString("$PREFIX$packageName", state.name)
    }

    fun isActionAllowed(packageName: String): Boolean {
        val perm = getPermission(packageName)
        return perm == AppPermissionState.ALLOWED
    }

    fun requiresApproval(packageName: String): Boolean {
        return getPermission(packageName) == AppPermissionState.ASK_EVERY_TIME
    }

    fun isDenied(packageName: String): Boolean {
        return getPermission(packageName) == AppPermissionState.DENIED
    }
}
