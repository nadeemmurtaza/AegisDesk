package com.newax.aegis.desktop

/**
 * One audited goal execution — the desktop twin of Android's audit/event-bus
 * surface (ARCHITECTURE.md invariant 8: every consequential modification is
 * auditable). Records who/what ran, which execution tiers fired
 * (EXACT_TARGET / PROCESS_LAUNCH / WIN32_AUTOMATION), the outcome, and when.
 * Never contains credentials or command content — references only.
 */
data class ExecutionAuditEntry(
    val goalId: String,
    val goalDescription: String,
    /** "COMPLETED" or "BLOCKED" — the terminal state the run left the goal in. */
    val outcome: String,
    /** The failure reason when the run BLOCKED the goal; null on success. */
    val reason: String?,
    /** Execution tiers used (from the task results — e.g. ["EXACT_TARGET"]). */
    val tiers: List<String>,
    val taskCount: Int,
    val startedMs: Long,
    val completedMs: Long,
) {
    val durationMs: Long get() = (completedMs - startedMs).coerceAtLeast(0L)
}

/**
 * The process-wide execution audit log — one per desktop process, matching the
 * holder pattern of [DesktopCapabilitiesHolder]/[DesktopModelProviderHolder].
 *
 * Entries are appended by [com.newax.aegis.desktop.execution.DesktopGoalExecutor]
 * on every run and surfaced as "Recent runs" on the Goals board; they persist
 * through [FileGoalsStore] (part of the [GoalsSnapshot]) so the trail survives
 * restarts. [replaceAll] is the snapshot-restore path only.
 */
object ExecutionAudit {

    const val RECENT_LIMIT = 20

    private val entries = mutableListOf<ExecutionAuditEntry>()

    @Synchronized
    fun record(entry: ExecutionAuditEntry) {
        entries.add(entry)
    }

    /** Newest first, capped at [limit]. */
    @Synchronized
    fun recent(limit: Int = RECENT_LIMIT): List<ExecutionAuditEntry> =
        entries.takeLast(limit).reversed()

    @Synchronized
    fun all(): List<ExecutionAuditEntry> = entries.toList()

    /** Restores persisted entries (bootstrap only — never called mid-session). */
    @Synchronized
    fun replaceAll(restored: List<ExecutionAuditEntry>) {
        entries.clear()
        entries.addAll(restored)
    }
}
