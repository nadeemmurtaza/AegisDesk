package com.newax.aegis.engine

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

object SecurityAuditor {

    private const val MAX_APPS_IN_REPORT = 20

    private val SENSITIVE_PERMISSIONS = mapOf(
        "android.permission.CAMERA"                    to "Camera",
        "android.permission.ACCESS_FINE_LOCATION"      to "Location(Fine)",
        "android.permission.ACCESS_COARSE_LOCATION"    to "Location(Coarse)",
        "android.permission.RECORD_AUDIO"              to "Microphone",
        "android.permission.READ_CONTACTS"             to "Contacts",
        "android.permission.READ_SMS"                  to "SMS",
        "android.permission.RECEIVE_SMS"               to "SMS(Recv)",
        "android.permission.READ_CALL_LOG"             to "CallLog",
        "android.permission.PROCESS_OUTGOING_CALLS"    to "OutgoingCalls",
        "android.permission.READ_PHONE_STATE"          to "PhoneState",
        "android.permission.READ_EXTERNAL_STORAGE"     to "Storage",
        "android.permission.MANAGE_EXTERNAL_STORAGE"   to "Storage(All)",
        "android.permission.INTERNET"                  to "Internet"
    )

    /** Higher-risk combos: an app having these together is especially suspicious. */
    private val HIGH_RISK_COMBOS = listOf(
        setOf("Camera", "Internet"),
        setOf("Microphone", "Internet"),
        setOf("Location(Fine)", "Internet"),
        setOf("SMS", "Internet"),
        setOf("Contacts", "Internet")
    )

    fun auditApps(context: Context): String {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val lines = mutableListOf<String>()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            if ((appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue

            val requested = pkg.requestedPermissions ?: continue
            val flags = requested.mapNotNull { SENSITIVE_PERMISSIONS[it] }
            if (flags.isEmpty()) continue

            val name = pm.getApplicationLabel(appInfo).toString()
            val flagStr = flags.joinToString(" ")
            val highRisk = HIGH_RISK_COMBOS.any { combo -> flags.toSet().containsAll(combo) }
            lines += "${if (highRisk) "⚠ HIGH-RISK" else "App"}: $name | $flagStr"
        }

        if (lines.isNotEmpty()) {
            lines.sortByDescending { it.startsWith("⚠") }
            val capped = lines.take(MAX_APPS_IN_REPORT)
            val omitted = lines.size - capped.size
            val report = capped.joinToString("\n") +
                if (omitted > 0) "\n(+$omitted more apps omitted for brevity)" else ""
            TriggerEngine.triggerEvents.tryEmit(
                "[Security Audit]\nAnalyze these 3rd-party apps and their permissions. " +
                "High-risk combos are marked ⚠. Warn the user about suspicious apps " +
                "(e.g., a calculator with Camera+Internet):\n$report"
            )
            return "Audit started — ${lines.size} apps with sensitive permissions found."
        }
        return "No suspicious permissions found."
    }
}
