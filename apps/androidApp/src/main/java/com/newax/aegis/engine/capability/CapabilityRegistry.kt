package com.newax.aegis.engine.capability

import java.util.concurrent.ConcurrentHashMap

enum class Capability {
    CAN_OPEN_APP,
    CAN_READ_SCREEN,
    CAN_CLICK,
    CAN_TYPE,
    CAN_GESTURE,
    CAN_CAPTURE_SCREEN,
    CAN_READ_NOTIFICATIONS,
    CAN_READ_FILES,
    CAN_READ_CONTACTS,
    CAN_WRITE_CONTACTS,
    CAN_CREATE_EVENT,
    CAN_CALL,
    CAN_ANSWER_CALL,
    CAN_SHARE_FILE,
    CAN_SEND_MESSAGE,
    CAN_READ_MESSAGES,
    CAN_READ_CALL_LOG,
    CAN_READ_LOCATION,
    CAN_RUN_PROCEDURE,
    CAN_USE_LLM,
    CAN_RECORD_AUDIO,
    CAN_TAKE_PHOTO
}

enum class CapabilityStatus { AVAILABLE, PERMISSION_DENIED, HARDWARE_MISSING, DISABLED }

data class CapabilityEntry(
    val capability: Capability,
    val status: CapabilityStatus,
    val requiredPermissions: List<String>,
    val executor: String,
    val riskLevel: Int,
    val lastSuccessMs: Long = 0,
    val lastFailureMs: Long = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0
)

object CapabilityRegistry {

    private val entries = ConcurrentHashMap<Capability, CapabilityEntry>()

    fun register(entry: CapabilityEntry) {
        entries[entry.capability] = entry
    }

    fun get(capability: Capability): CapabilityEntry? = entries[capability]

    fun isAvailable(capability: Capability): Boolean =
        entries[capability]?.status == CapabilityStatus.AVAILABLE

    fun setStatus(capability: Capability, status: CapabilityStatus) {
        entries.compute(capability) { _, existing ->
            existing?.copy(status = status) ?: CapabilityEntry(
                capability = capability,
                status = status,
                requiredPermissions = emptyList(),
                executor = "unknown",
                riskLevel = 0
            )
        }
    }

    fun recordSuccess(capability: Capability) {
        entries.compute(capability) { _, existing ->
            existing?.copy(lastSuccessMs = System.currentTimeMillis(), successCount = existing.successCount + 1)
        }
    }

    fun recordFailure(capability: Capability) {
        entries.compute(capability) { _, existing ->
            existing?.copy(lastFailureMs = System.currentTimeMillis(), failureCount = existing.failureCount + 1)
        }
    }

    fun allAvailable(): List<CapabilityEntry> =
        entries.values.filter { it.status == CapabilityStatus.AVAILABLE }

    fun snapshot(): Map<Capability, CapabilityEntry> = entries.toMap()
}
