package com.newax.aegis.engine.registry

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

enum class PermissionStatus { GRANTED, DENIED, NOT_REQUESTED, PERMANENTLY_DENIED }

data class PermissionRecord(
    val permission: String,
    val status: PermissionStatus,
    val lastCheckedMs: Long = System.currentTimeMillis(),
    val requiredBy: List<String> = emptyList(),
    val rationale: String = ""
)

object PermissionRegistry {

    private val records = ConcurrentHashMap<String, PermissionRecord>()

    val CRITICAL_PERMISSIONS = listOf(
        android.Manifest.permission.READ_CONTACTS,
        android.Manifest.permission.WRITE_CONTACTS,
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.ANSWER_PHONE_CALLS,
        android.Manifest.permission.RECEIVE_SMS,
        android.Manifest.permission.READ_SMS,
        android.Manifest.permission.ACCESS_COARSE_LOCATION,
        android.Manifest.permission.POST_NOTIFICATIONS
    )

    fun sync(context: Context) {
        CRITICAL_PERMISSIONS.forEach { permission ->
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            val status = if (granted) PermissionStatus.GRANTED else {
                records[permission]?.status?.let { existing ->
                    if (existing == PermissionStatus.GRANTED) PermissionStatus.DENIED else existing
                } ?: PermissionStatus.NOT_REQUESTED
            }
            records[permission] = PermissionRecord(
                permission = permission,
                status = status,
                lastCheckedMs = System.currentTimeMillis()
            )
        }
    }

    fun isGranted(permission: String): Boolean =
        records[permission]?.status == PermissionStatus.GRANTED

    fun status(permission: String): PermissionStatus =
        records[permission]?.status ?: PermissionStatus.NOT_REQUESTED

    fun onGranted(permission: String) {
        records[permission] = (records[permission] ?: PermissionRecord(permission, PermissionStatus.GRANTED))
            .copy(status = PermissionStatus.GRANTED, lastCheckedMs = System.currentTimeMillis())
    }

    fun onDenied(permission: String, permanent: Boolean = false) {
        val status = if (permanent) PermissionStatus.PERMANENTLY_DENIED else PermissionStatus.DENIED
        records[permission] = (records[permission] ?: PermissionRecord(permission, status))
            .copy(status = status, lastCheckedMs = System.currentTimeMillis())
    }

    fun granted(): List<String> = records.values
        .filter { it.status == PermissionStatus.GRANTED }.map { it.permission }

    fun denied(): List<String> = records.values
        .filter { it.status in listOf(PermissionStatus.DENIED, PermissionStatus.PERMANENTLY_DENIED) }
        .map { it.permission }

    fun notRequested(): List<String> = records.values
        .filter { it.status == PermissionStatus.NOT_REQUESTED }.map { it.permission }

    fun all(): List<PermissionRecord> = records.values.toList()

    fun missingCritical(): List<String> = CRITICAL_PERMISSIONS.filter { !isGranted(it) }
}
