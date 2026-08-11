package com.newax.aegis.sync

import android.content.Context

/**
 * Process-wide Android application context for the sync module's actuals
 * (BLE, WiFi-P2P, the Android keystore). The parameterless expect seams
 * (proximityDiscovery(), platformKeyStore()) cannot receive a Context, so the
 * app calls [init] once from Application.onCreate and the actuals read it
 * here.
 */
object AndroidSyncContext {

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun requireContext(): Context =
        appContext ?: error(
            "AndroidSyncContext.init(context) must be called from Application.onCreate before sync APIs"
        )
}
