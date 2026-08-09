package com.newax.aegis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.PlatformCapability
import com.newax.aegis.platform.PrivilegeLevel

// ── Design tokens (REFINED_THEME.md) ────────────────────────────────────────
private val Surface      = Color(0xFFFFFFFF)
private val SurfaceMuted = Color(0xFFF2F2EF)
private val TextPri      = Color(0xFF1B1B1A)
private val TextSec      = Color(0xFF686864)
private val TextTer      = Color(0xFF8D8D87)
private val Border       = Color(0xFFD8D8D3)

private val ReadyColor      = Color(0xFF22C55E)
private val MissingPermCol  = Color(0xFFF59E0B)
private val MissingCredCol  = Color(0xFFF97316)
private val DisabledCol     = Color(0xFF94A3B8)
private val UnavailableCol  = Color(0xFFEF4444)
private val NotSupportedCol = Color(0xFF9CA3AF)

private data class CapabilityRow(
    val id: CapabilityId,
    val displayName: String,
    val description: String,
    val status: CapabilityStatus,
    val privilegeLevel: PrivilegeLevel,
    val requiredPermission: String?,
    val requiredCredentialKey: String?,
    val offline: Boolean,
)

private fun statusColor(status: CapabilityStatus): Color = when (status) {
    CapabilityStatus.READY              -> ReadyColor
    CapabilityStatus.MISSING_PERMISSION -> MissingPermCol
    CapabilityStatus.MISSING_CREDENTIAL -> MissingCredCol
    CapabilityStatus.DISABLED           -> DisabledCol
    CapabilityStatus.UNAVAILABLE        -> UnavailableCol
    CapabilityStatus.NOT_SUPPORTED      -> NotSupportedCol
}

private fun CapabilityStatus.label(): String =
    name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

private fun PrivilegeLevel.label(): String = when (this) {
    PrivilegeLevel.READ_ONLY          -> "Read-only"
    PrivilegeLevel.STANDARD           -> "Standard"
    PrivilegeLevel.HIGH_IMPACT_SYSTEM -> "High-impact"
    PrivilegeLevel.CRITICAL           -> "Critical"
}

private fun PlatformCapability.toRow(): CapabilityRow {
    val d = descriptor()
    return CapabilityRow(
        id          = id,
        displayName = d.displayName,
        description = d.description,
        status      = status(),
        privilegeLevel = d.privilegeLevel,
        requiredPermission   = d.requiredPermission,
        requiredCredentialKey = d.requiredCredentialKey,
        offline = d.offline,
    )
}

private fun readSnapshot(): List<CapabilityRow>? =
    PlatformCapabilitiesHolder.registry()?.all()?.map { it.toRow() }

/**
 * Capability status screen — the UI face of the shared platform contract. Shows
 * every registered capability (files, processes, shell, desktop, secrets, system)
 * with its live status, privilege level, and any permission/credential requirement.
 * States: error (registry not initialized), empty (nothing registered), content.
 */
@Composable
fun CapabilitiesScreen(padding: PaddingValues) {
    var refreshKey by remember { mutableStateOf(0) }
    val rows = remember(refreshKey) { readSnapshot() }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding      = PaddingValues(vertical = 12.dp)
    ) {
        // ── Header / summary ───────────────────────────────────────────────
        item {
            Card(
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = Surface),
                border    = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (rows == null) "Capabilities" else "${rows.size} capabilities",
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 15.sp,
                            color      = TextPri
                        )
                        Spacer(Modifier.height(2.dp))
                        val ready = rows?.count { it.status == CapabilityStatus.READY } ?: 0
                        Text(
                            when {
                                rows == null -> "Not initialized"
                                ready == rows.size -> "All ready · runs on this device"
                                else -> "$ready ready · ${rows.size - ready} need attention"
                            },
                            fontSize = 13.sp,
                            color    = TextSec
                        )
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Refresh status",
                            tint = TextSec
                        )
                    }
                }
            }
        }

        // ── States ─────────────────────────────────────────────────────────
        when {
            rows == null -> item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, tint = TextTer, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("Capabilities not initialized", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextSec)
                        Spacer(Modifier.height(4.dp))
                        Text("Restart the app to register the platform surface", fontSize = 13.sp, color = TextTer)
                    }
                }
            }
            rows.isEmpty() -> item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Shield, contentDescription = null, tint = TextTer, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("No capabilities registered", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextSec)
                    }
                }
            }
            else -> items(rows, key = { it.id.name }) { CapabilityCard(it) }
        }
    }
}

@Composable
private fun CapabilityCard(row: CapabilityRow) {
    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor(row.status))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.displayName, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = TextPri)
                    Text(row.id.name, fontSize = 11.sp, color = TextTer, fontFamily = FontFamily.Monospace)
                }
                StatusChip(row.status)
            }

            Spacer(Modifier.height(8.dp))
            Text(row.description, fontSize = 13.sp, color = TextSec, lineHeight = 19.sp)

            if (row.requiredPermission != null || row.requiredCredentialKey != null) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.requiredPermission?.let { MetaRow("Permission", it) }
                    row.requiredCredentialKey?.let { MetaRow("Credential", it) }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("Privilege · ${row.privilegeLevel.label()}", TextSec)
                if (row.offline) Tag("Offline", ReadyColor)
            }
        }
    }
}

@Composable
private fun StatusChip(status: CapabilityStatus) {
    val color = statusColor(status)
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(status.label(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(SurfaceMuted)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = TextTer, modifier = Modifier.weight(0.35f))
        Text(
            value,
            fontSize = 12.sp,
            color    = TextSec,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.65f)
        )
    }
}
