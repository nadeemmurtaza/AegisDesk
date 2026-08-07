package com.newax.aegis.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "app_records", indices = [Index("packageName", unique = true)])
data class AppRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val label: String,
    val version: String,
    val category: String = "",
    val launchActivity: String? = null,
    val needsValidation: Boolean = false,
    val lastScanMs: Long = System.currentTimeMillis()
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
    val confidence: Int = 80
)

@Entity(tableName = "ui_procedures", indices = [Index("packageName"), Index("taskCapability")])
data class UiProcedure(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val versionRange: String,
    val taskCapability: String,
    val steps: String,
    val screenSignature: String = "",
    val confidence: Int = 80,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val lastRunMs: Long = 0,
    val needsValidation: Boolean = false
)

@Entity(tableName = "screen_nodes", primaryKeys = ["packageName", "screenSignature"])
data class ScreenNode(
    val packageName: String,
    val screenSignature: String,
    val screenType: String = "",
    val nodes: String = "",
    val appVersion: String = ""
)

@Entity(tableName = "nav_edges", indices = [Index("fromSignature"), Index("toSignature")])
data class NavEdge(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fromSignature: String,
    val toSignature: String,
    val actionViewId: String = "",
    val actionContentDesc: String = "",
    val actionText: String = ""
)
