package com.newax.aegis.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.desktop.ui.state.CapabilityUiRow
import com.newax.aegis.desktop.ui.state.StatusScreenState
import com.newax.aegis.model.ModelDescriptor
import com.newax.aegis.model.ModelState
import com.newax.aegis.platform.CapabilityStatus

/**
 * Status / capabilities screen — the desktop face of the platform contract
 * (the `printStatusBlock` CLI output lifted into a window). Shows every
 * registered capability with live status, privilege and requirements, plus the
 * active model provider lifecycle (NOT_INSTALLED → LOADING → READY/ERROR).
 * States: error (registry not initialized), empty, content.
 */
@Composable
fun StatusScreen(state: StatusScreenState) {
    var refreshKey by remember { mutableStateOf(0) }
    val rows = remember(refreshKey) { state.capabilityRows() }
    val modelState by state.modelState.collectAsState()
    val descriptor = state.modelDescriptor

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // ── Header / summary ───────────────────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceColor),
                border = BorderStroke(1.dp, BorderColor),
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
                            fontSize = 15.sp,
                            color = TextPrimaryColor
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
                            color = TextSecondaryColor
                        )
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Refresh status",
                            tint = TextSecondaryColor
                        )
                    }
                }
            }
        }

        // ── Model provider card ─────────────────────────────────────────────
        item { ModelProviderCard(modelState, descriptor) }

        // ── States ─────────────────────────────────────────────────────────
        when {
            rows == null -> item {
                EmptyState(
                    title = "Capabilities not initialized",
                    hint = "Restart the app to register the platform surface",
                    iconColor = TextTertiaryColor,
                )
            }
            rows.isEmpty() -> item {
                EmptyState("No capabilities registered", null)
            }
            else -> items(rows, key = { it.id.name }) { CapabilityCard(it) }
        }
    }
}

@Composable
private fun CapabilityCard(row: CapabilityUiRow) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(statusColor(row.status))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.displayName, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = TextPrimaryColor)
                    Text(row.id.name, fontSize = 11.sp, color = TextTertiaryColor, fontFamily = FontFamily.Monospace)
                }
                StatusChip(row.status.label(), statusColor(row.status))
            }

            Spacer(Modifier.height(8.dp))
            Text(row.description, fontSize = 13.sp, color = TextSecondaryColor, lineHeight = 19.sp)

            if (row.requiredPermission != null || row.requiredCredentialKey != null) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.requiredPermission?.let { MetaRow("Permission", it) }
                    row.requiredCredentialKey?.let { MetaRow("Credential", it) }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag("Privilege · ${row.privilegeLevel.label()}", TextSecondaryColor)
                if (row.offline) Tag("Offline", ReadyColor)
            }
        }
    }
}

@Composable
private fun ModelProviderCard(modelState: ModelState, descriptor: ModelDescriptor) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        border = BorderStroke(1.dp, BorderColor),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(modelStateColor(modelState))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Model provider", fontWeight = FontWeight.Medium, fontSize = 15.sp, color = TextPrimaryColor)
                    Text("shared/model-api · on-device brain", fontSize = 11.sp, color = TextTertiaryColor, fontFamily = FontFamily.Monospace)
                }
                StatusChip(modelState.label(), modelStateColor(modelState))
            }

            Spacer(Modifier.height(8.dp))
            when (modelState) {
                ModelState.NOT_INSTALLED -> Text(
                    "No model pack installed — the deterministic fallback is active. Place a .gguf under ~/.aegis/models/ and restart to enable open-ended reasoning.",
                    fontSize = 13.sp, color = TextSecondaryColor, lineHeight = 19.sp
                )
                ModelState.LOADING -> Text(
                    "Loading ${descriptor.modelName} into memory…",
                    fontSize = 13.sp, color = TextSecondaryColor, lineHeight = 19.sp
                )
                ModelState.READY -> Text(
                    "${descriptor.modelName} is loaded and accepting requests on this device.",
                    fontSize = 13.sp, color = TextSecondaryColor, lineHeight = 19.sp
                )
                ModelState.ERROR -> Text(
                    "Model unavailable — load failed or the pack was removed. Re-import to retry.",
                    fontSize = 13.sp, color = TextSecondaryColor, lineHeight = 19.sp
                )
                ModelState.CLOSED -> Text(
                    "Model provider closed — no further requests are accepted.",
                    fontSize = 13.sp, color = TextSecondaryColor, lineHeight = 19.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(descriptor.format.label(), TextSecondaryColor)
                if (descriptor.sha256.isNotBlank()) Tag("sha256 ${descriptor.sha256.take(10)}…", TextSecondaryColor)
                if (descriptor.sizeBytes > 0) Tag(formatBytes(descriptor.sizeBytes), TextSecondaryColor)
                if (descriptor.modelName.isNotBlank() && modelState != ModelState.NOT_INSTALLED) {
                    Tag(descriptor.modelName, TextSecondaryColor)
                }
            }
        }
    }
}
