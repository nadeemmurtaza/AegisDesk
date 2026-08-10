package com.newax.aegis.platform

/**
 * Resolves skill-level capability requirements against the platform capability
 * registry, without knowing anything about the adapter implementations.
 *
 * Skills declare their needs as free-form capability names (e.g. "OPEN_APP",
 * "SEND_TEXT"); the planner must never match those against [CapabilityId] by
 * hand, because a name like "SEND_TEXT" can be backed by several capabilities
 * and the correct one depends on what the platform actually registered. This
 * resolver owns that mapping — skills → candidate [CapabilityId]s → live
 * registry status — so every consumer (planner, executor, policy) resolves
 * through the contract, not through ad-hoc string matching (ARCHITECTURE.md:
 * typed actions, never free-form capability references).
 *
 * Platform-free: no android/java imports, so it compiles for every target and
 * the desktop/ios/linux planners reuse the same resolution semantics.
 */
object CapabilityResolver {

    /**
     * Skill-level capability names that do not contain a [CapabilityId] enum
     * name, mapped to their preferred backing capabilities in order. Names that
     * do contain an enum name (e.g. "FILE_WRITE" → FILES, "SYSTEM_INFO" →
     * SYSTEM) are handled by the deterministic fallback in [candidateIds].
     */
    private val EXPLICIT: Map<String, List<CapabilityId>> = mapOf(
        "OPEN_APP" to listOf(CapabilityId.PROCESSES, CapabilityId.DESKTOP),
        "SEND_TEXT" to listOf(CapabilityId.DESKTOP, CapabilityId.SMS, CapabilityId.NOTIFICATIONS, CapabilityId.CONTACTS),
        "SEND_MESSAGE" to listOf(CapabilityId.DESKTOP, CapabilityId.SMS, CapabilityId.NOTIFICATIONS, CapabilityId.CONTACTS),
        "READ_MESSAGE" to listOf(CapabilityId.SMS, CapabilityId.NOTIFICATIONS, CapabilityId.CONTACTS),
        "READ_NOTIFICATIONS" to listOf(CapabilityId.NOTIFICATIONS),
        "PLAY_MEDIA" to listOf(CapabilityId.DESKTOP, CapabilityId.VOICE),
        "READ_CONTACTS" to listOf(CapabilityId.CONTACTS),
        "WRITE_CALENDAR" to listOf(CapabilityId.CALENDAR),
        "SEND_SMS" to listOf(CapabilityId.SMS),
        "PLACE_CALL" to listOf(CapabilityId.VOICE, CapabilityId.CONTACTS),
        "SHELL_EXEC" to listOf(CapabilityId.SHELL),
        "PROCESS" to listOf(CapabilityId.PROCESSES),
        // Singular forms that the contains-enum-name fallback cannot match
        // (enum names are plural: FILES, PROCESSES, NOTIFICATIONS).
        "FILE_READ" to listOf(CapabilityId.FILES),
        "FILE_WRITE" to listOf(CapabilityId.FILES),
        "FILE_LIST" to listOf(CapabilityId.FILES),
        "FILE_SEARCH" to listOf(CapabilityId.FILES),
        "FILE_DELETE" to listOf(CapabilityId.FILES),
        "FILE_MOVE" to listOf(CapabilityId.FILES),
        "PROCESS_KILL" to listOf(CapabilityId.PROCESSES),
        "PROCESS_LIST" to listOf(CapabilityId.PROCESSES),
        "PROCESS_INFO" to listOf(CapabilityId.PROCESSES),
        "NOTIFICATION_READ" to listOf(CapabilityId.NOTIFICATIONS),
        "DEVICE_INFO" to listOf(CapabilityId.SYSTEM, CapabilityId.DEVICE),
        // Model-tier capabilities (LLM, OCR, TTS) are not backed by the platform
        // registry — the model layer reports its own availability. They resolve
        // to no candidates, which isBlocked treats as "not platform-gated".
        "LLM" to emptyList(),
        "OCR" to emptyList(),
        "TTS" to emptyList(),
    )

    /** The candidate [CapabilityId]s that could back a skill capability, in preference order. */
    fun candidateIds(capability: String): List<CapabilityId> {
        val normalized = capability.uppercase().replace('-', '_').trim()
        EXPLICIT[normalized]?.let { return it }
        return CapabilityId.entries.filter { normalized.contains(it.name) }
    }

    /**
     * Resolves one skill capability against the registry. The resolution reports
     * the first operationally-ready candidate; if none is ready it reports the
     * status of the first registered candidate (so callers see *why* it is
     * blocked — a missing permission, a disabled toggle, an unsupported surface).
     */
    fun resolve(registry: PlatformCapabilityRegistry, capability: String): CapabilityResolution {
        val ids = candidateIds(capability)
        if (ids.isEmpty()) return CapabilityResolution(capability, ids, null)
        for (id in ids) {
            val registered = registry.get(id) ?: continue
            val status = registered.status()
            if (status.isOperational) return CapabilityResolution(capability, ids, status)
        }
        for (id in ids) {
            registry.get(id)?.let { return CapabilityResolution(capability, ids, it.status()) }
        }
        return CapabilityResolution(capability, ids, null)
    }

    /** Resolves every requested capability, preserving input order and deduplication. */
    fun resolveAll(registry: PlatformCapabilityRegistry, capabilities: Collection<String>): List<CapabilityResolution> =
        capabilities.distinct().map { resolve(registry, it) }

    /**
     * The capability names that are platform-gated and currently blocked. Names
     * with no platform backing (model-tier, future ids) are intentionally not
     * reported — the platform registry cannot gate what it does not own.
     */
    fun missing(registry: PlatformCapabilityRegistry, capabilities: Collection<String>): List<String> =
        resolveAll(registry, capabilities).filter { it.isBlocked }.map { it.requested }
}

/**
 * Outcome of resolving one skill-level capability name against the registry.
 */
data class CapabilityResolution(
    /** The capability name exactly as the skill requested it. */
    val requested: String,
    /** Candidate [CapabilityId]s that could back it, in preference order. Empty for model-tier names. */
    val candidates: List<CapabilityId>,
    /** Status of the winning candidate: first ready, else first registered, else null. */
    val status: CapabilityStatus?,
) {
    /**
     * True when the requirement is platform-gated and no candidate is
     * operationally ready. Unmapped names (empty [candidates]) are never
     * blocked: the platform registry does not own them.
     */
    val isBlocked: Boolean get() = candidates.isNotEmpty() && status?.isOperational != true
}
