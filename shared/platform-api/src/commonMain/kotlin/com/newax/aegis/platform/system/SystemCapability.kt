package com.newax.aegis.platform.system

import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityResult
import com.newax.aegis.platform.OperationContext
import com.newax.aegis.platform.PlatformCapability

data class SystemInfo(
    val osName: String,
    val osVersion: String,
    val deviceModel: String? = null,
    val locale: String,
    val timezone: String? = null,
    val totalMemoryBytes: Long? = null,
)

enum class ConnectivityState { ONLINE, OFFLINE, LIMITED }

/**
 * Device-level information and OS interaction. Permission requests and settings
 * navigation are privileged; reads are not.
 */
interface SystemCapability : PlatformCapability {
    override val id: CapabilityId get() = CapabilityId.SYSTEM

    fun info(): CapabilityResult<SystemInfo>
    fun connectivity(): CapabilityResult<ConnectivityState>
    fun batteryPercent(): CapabilityResult<Int?>
    fun requestPermission(permission: String, context: OperationContext): CapabilityResult<Unit>
    fun openSettings(section: String? = null, context: OperationContext): CapabilityResult<Unit>
}
