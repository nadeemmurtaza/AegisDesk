package com.newax.aegis.db.dao

import androidx.room.*
import com.newax.aegis.db.entity.*

@Dao
interface AppRegistryDao {

    // ── AppRecord ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertRecord(record: AppRecord)

    @Query("SELECT * FROM app_records WHERE packageName = :pkg LIMIT 1")
    fun recordByPackage(pkg: String): AppRecord?

    @Query("SELECT * FROM app_records ORDER BY label ASC")
    fun allRecords(): List<AppRecord>

    @Query("UPDATE app_records SET needsValidation = 1 WHERE packageName = :pkg")
    fun markNeedsValidation(pkg: String)

    @Query("UPDATE app_records SET version = :version, needsValidation = :needsVal, lastScanMs = :now WHERE packageName = :pkg")
    fun updateVersion(pkg: String, version: String, needsVal: Boolean, now: Long)

    // ── AppCapabilityLink ─────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertLink(link: AppCapabilityLink)

    @Query("SELECT * FROM app_capability_links WHERE packageName = :pkg AND capability = :cap LIMIT 1")
    fun linkFor(pkg: String, cap: String): AppCapabilityLink?

    @Query("SELECT packageName FROM app_capability_links WHERE capability = :cap ORDER BY confidence DESC")
    fun packagesByCapability(cap: String): List<String>

    @Query("SELECT capability FROM app_capability_links WHERE packageName = :pkg")
    fun capabilitiesForPackage(pkg: String): List<String>

    // ── UiProcedure ───────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertProcedure(proc: UiProcedure): Long

    @Query("""
        SELECT * FROM ui_procedures
        WHERE packageName = :pkg AND taskCapability = :cap AND needsValidation = 0
        ORDER BY confidence DESC, successCount DESC
        LIMIT 1
    """)
    fun bestProcedure(pkg: String, cap: String): UiProcedure?

    @Query("UPDATE ui_procedures SET successCount = successCount + 1, confidence = MIN(99, confidence + 1), lastRunMs = :now, needsValidation = 0 WHERE id = :id")
    fun recordSuccess(id: Long, now: Long)

    @Query("UPDATE ui_procedures SET failureCount = failureCount + 1, confidence = MAX(0, confidence - 10), needsValidation = 1, lastRunMs = :now WHERE id = :id")
    fun recordFailure(id: Long, now: Long)

    // ── ScreenNode ────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertScreen(node: ScreenNode)

    @Query("SELECT * FROM screen_nodes WHERE packageName = :pkg AND screenSignature = :sig LIMIT 1")
    fun screenBySignature(pkg: String, sig: String): ScreenNode?

    @Query("SELECT * FROM screen_nodes WHERE packageName = :pkg")
    fun screensForApp(pkg: String): List<ScreenNode>

    // ── NavEdge ───────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertNavEdge(edge: NavEdge)

    @Query("SELECT * FROM nav_edges WHERE fromSignature = :sig")
    fun edgesFrom(sig: String): List<NavEdge>

    @Query("SELECT * FROM nav_edges WHERE toSignature = :sig")
    fun edgesTo(sig: String): List<NavEdge>
}
