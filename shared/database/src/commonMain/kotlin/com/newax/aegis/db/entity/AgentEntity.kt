package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One installed agent in the multi-agent management system (docs/AGENTS_DESIGN.md).
 * Device-local configuration (like the keystore) — deliberately NO sync columns:
 * an agent package is imported per device, and the mesh syncs the *memory* the
 * agents produce (episodes / handoffs / library), not the binaries.
 *
 * [keywords] and [skills] are comma-separated routing vocabulary — the
 * deterministic, offline-first signal AgentRouter scores user input against.
 */
@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val agentId: String,
    val name: String,
    val version: String,
    val description: String,
    /** Routing category: coding / planning / research / organizer / custom. */
    val category: String,
    /** Comma-separated routing keywords (matched word-boundary, case-insensitive). */
    val keywords: String = "",
    /** Comma-separated capability names the agent declares. */
    val skills: String = "",
    /** Disabled agents never route, never dominate. */
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    /** "builtin" or "zip:<packageName>". */
    val source: String = "builtin",
    @ColumnInfo(defaultValue = "0")
    val installedAtMs: Long = currentTimeMillis(),
    /** Path to the extracted package under filesDir/agents/<agentId>. */
    val packageDir: String = ""
)
