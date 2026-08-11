package com.newax.aegis.db.entity

import com.newax.aegis.db.currentTimeMillis

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "app_records", indices = [Index("packageName", unique = true)])
data class AppRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val label: String,
    val version: String,
    @ColumnInfo(defaultValue = "''")
    val category: String = "",
    val launchActivity: String? = null,
    @ColumnInfo(defaultValue = "0")
    val needsValidation: Boolean = false,
    val lastScanMs: Long = currentTimeMillis(),
    // ── sync metadata (docs/SYNC_DESIGN.md §4): LWW merge ordering + tombstone ──
    @ColumnInfo(defaultValue = "0")
    val syncHcWall: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val syncHcCounter: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val syncDeviceId: String = "",
    @ColumnInfo(defaultValue = "0")
    val syncTombstone: Boolean = false
)

@Entity(
    tableName = "app_capability_links",
    primaryKeys = ["packageName", "capability"],
    indices = [Index("capability")]
)
data class AppCapabilityLink(
    val packageName: String,
    val capability: String,
    val intentAction: String? = null,
    val deepLinkPattern: String? = null,
    val mimeTypes: String? = null,
    @ColumnInfo(defaultValue = "80")
    val confidence: Int = 80,
    // ── sync metadata (docs/SYNC_DESIGN.md §4): LWW merge ordering + tombstone ──
    @ColumnInfo(defaultValue = "0")
    val syncHcWall: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val syncHcCounter: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val syncDeviceId: String = "",
    @ColumnInfo(defaultValue = "0")
    val syncTombstone: Boolean = false
)

@Entity(tableName = "ui_procedures", indices = [Index("packageName"), Index("taskCapability")])
data class UiProcedure(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val versionRange: String,
    val taskCapability: String,
    val steps: String,
    @ColumnInfo(defaultValue = "''")
    val screenSignature: String = "",
    @ColumnInfo(defaultValue = "''")
    val prerequisites: String = "",
    @ColumnInfo(defaultValue = "''")
    val recoveryPaths: String = "",
    @ColumnInfo(defaultValue = "''")
    val successConditions: String = "",
    @ColumnInfo(defaultValue = "80")
    val confidence: Int = 80,
    @ColumnInfo(defaultValue = "0")
    val successCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val failureCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val lastRunMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val needsValidation: Boolean = false,
    // ── sync metadata (docs/SYNC_DESIGN.md §4): LWW merge ordering + tombstone ──
    @ColumnInfo(defaultValue = "0")
    val syncHcWall: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val syncHcCounter: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val syncDeviceId: String = "",
    @ColumnInfo(defaultValue = "0")
    val syncTombstone: Boolean = false
)

@Entity(tableName = "screen_nodes", primaryKeys = ["packageName", "screenSignature"])
data class ScreenNode(
    val packageName: String,
    val screenSignature: String,
    @ColumnInfo(defaultValue = "''")
    val screenType: String = "",
    @ColumnInfo(defaultValue = "''")
    val nodes: String = "",
    @ColumnInfo(defaultValue = "''")
    val appVersion: String = ""
)

@Entity(tableName = "nav_edges", indices = [Index("fromSignature"), Index("toSignature")])
data class NavEdge(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromSignature: String,
    val toSignature: String,
    @ColumnInfo(defaultValue = "''")
    val actionViewId: String = "",
    @ColumnInfo(defaultValue = "''")
    val actionContentDesc: String = "",
    @ColumnInfo(defaultValue = "''")
    val actionText: String = ""
)
