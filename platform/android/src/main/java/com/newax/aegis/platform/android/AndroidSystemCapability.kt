package com.newax.aegis.platform.android

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.newax.aegis.platform.CapabilityDescriptor
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.platform.system.ConnectivityState
import com.newax.aegis.platform.system.SystemCapability
import com.newax.aegis.platform.system.SystemInfo
import java.util.Locale
import java.util.TimeZone

/**
 * Hook the app's UI wires in so a runtime permission can actually be requested from
 * an Activity. Absent this, [requestPermission] reports the honest state: the
 * permission is missing and nothing can request it yet.
 */
fun interface PermissionRequester {
    /** Requests [permission]; returns true when it is granted or the request was launched. */
    fun request(permission: String): Boolean
}

/**
 * System capability on Android: device/build info, connectivity, battery, and OS
 * navigation. Reads are real; [requestPermission] needs a UI hook ([PermissionRequester]).
 */
class AndroidSystemCapability(
    private val androidContext: Context,
    private val permissionRequester: PermissionRequester? = null,
) : SystemCapability {

    override val id: CapabilityId get() = CapabilityId.SYSTEM

    override fun descriptor(): CapabilityDescriptor = CapabilityDescriptor(
        id = id,
        version = 1,
        displayName = "System",
        description = "Device info, connectivity, battery, permission and settings access",
        privilegeLevel = PrivilegeLevel.READ_ONLY,
    )

    override fun info(): CapabilityResult<SystemInfo> {
        val memory = try {
            val activityManager = androidContext.getSystemService(Context.ACTIVITY_SERVICE)
                as? android.app.ActivityManager
            activityManager?.memoryInfo?.totalMem
        } catch (e: Exception) {
            null
        }
        return CapabilityResult.Success(
            SystemInfo(
                osName = "Android",
                osVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                deviceModel = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { null },
                locale = Locale.getDefault().toLanguageTag(),
                timezone = TimeZone.getDefault().id,
                totalMemoryBytes = memory,
            )
        )
    }

    override fun connectivity(): CapabilityResult<ConnectivityState> {
        val manager = try {
            androidContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        } catch (e: Exception) {
            null
        } ?: return CapabilityResult.Failed("ConnectivityManager unavailable")
        val network = manager.activeNetwork
        val capabilities = network?.let { manager.getNetworkCapabilities(it) }
        return when {
            network == null || capabilities == null ->
                CapabilityResult.Success(ConnectivityState.OFFLINE)
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ->
                CapabilityResult.Success(ConnectivityState.OFFLINE)
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ->
                CapabilityResult.Success(ConnectivityState.LIMITED)
            else ->
                CapabilityResult.Success(ConnectivityState.ONLINE)
        }
    }

    override fun batteryPercent(): CapabilityResult<Int?> {
        val percent = try {
            val manager = androidContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            null
        } ?: return CapabilityResult.Success(null)
        return CapabilityResult.Success(percent.coerceIn(0, 100))
    }

    override fun requestPermission(permission: String, context: OperationContext): CapabilityResult<Unit> {
        val requester = permissionRequester
            ?: return CapabilityResult.MissingPermission(permission)
        return if (requester.request(permission)) {
            CapabilityResult.Success(Unit)
        } else {
            CapabilityResult.MissingPermission(permission)
        }
    }

    override fun openSettings(section: String?, context: OperationContext): CapabilityResult<Unit> {
        val intent = if (section == null) {
            Intent(Settings.ACTION_SETTINGS)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${androidContext.packageName}"),
            ).putExtra(Intent.EXTRA_SUBJECT, section)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            androidContext.startActivity(intent)
            CapabilityResult.Success(Unit)
        } catch (e: Exception) {
            CapabilityResult.Failed("cannot open settings: ${e.message}")
        }
    }
}
