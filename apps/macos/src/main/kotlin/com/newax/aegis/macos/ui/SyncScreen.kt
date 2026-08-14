package com.newax.aegis.macos.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.desktopsync.DesktopSync
import com.newax.aegis.sync.PairedPeer
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import com.newax.aegis.ui.theme.NewaxLightColors

// ── Design tokens — same palette as the rest of the Newax apps ──────────────
private val Background = NewaxLightColors.bg
private val Surface = NewaxLightColors.surface
private val SurfaceMuted = NewaxLightColors.surfaceMuted
private val Primary = NewaxLightColors.textPrimary
private val TextPrimary = NewaxLightColors.textPrimary
private val TextSecondary = NewaxLightColors.textSecondary
private val TextTertiary = NewaxLightColors.textTertiary
private val Border = NewaxLightColors.border
private val Green = NewaxLightColors.success
private val Red = NewaxLightColors.error
private val Amber = NewaxLightColors.warning

/**
 * The macOS body's sync surface (docs/SYNC_DESIGN.md §2, Track M) — the same
 * capability as Android's Sync screen and the Windows Status card, backed by
 * the shared [DesktopSync] engine: automatic-sync status, this device's
 * pairing code (copyable), SAS-confirmed pairing, paired devices with unpair,
 * and the memory profile synced from paired devices.
 */
@Composable
fun SyncScreen() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Background) {
            var status by remember { mutableStateOf(DesktopSync.status()) }
            var peers by remember { mutableStateOf(DesktopSync.peers()) }
            var codeInput by remember { mutableStateOf("") }
            var addressInput by remember { mutableStateOf("") }
            var verifiedSas by remember { mutableStateOf<String?>(null) }
            var message by remember { mutableStateOf<String?>(null) }
            val pairingCode = remember { DesktopSync.pairingCode() }

            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 20.dp)
            ) {
                item {
                    Text(
                        "Newax Aegis — Device Sync",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        color = TextPrimary
                    )
                }

                // ── Status ────────────────────────────────────────────────
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        border = BorderStroke(1.dp, Border),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Green)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Automatic sync", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(status, fontSize = 13.sp, color = TextSecondary)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${DesktopSync.displayName()} · ${DesktopSync.deviceId()}",
                                fontSize = 12.sp,
                                color = TextTertiary,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { status = DesktopSync.status(); peers = DesktopSync.peers() },
                                border = BorderStroke(1.dp, Border),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                            ) { Text("Refresh", fontSize = 13.sp) }
                        }
                    }
                }

                // ── Sync categories ───────────────────────────────────────
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        border = BorderStroke(1.dp, Border),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Sync categories", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "What this device shares with — and accepts from — paired devices.",
                                fontSize = 13.sp, color = TextSecondary
                            )
                            Spacer(Modifier.height(6.dp))
                            DesktopSync.CATEGORY_TABLES.forEach { (name, tables) ->
                                val enabled = tables.any { DesktopSync.categoryEnabled(it) }
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(name, fontSize = 14.sp, color = TextPrimary, modifier = Modifier.weight(1f))
                                    androidx.compose.material3.Switch(
                                        checked = enabled,
                                        onCheckedChange = { on -> tables.forEach { t -> DesktopSync.setCategoryEnabled(t, on) }; status = DesktopSync.status() },
                                        colors = androidx.compose.material3.SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Primary
                                        )
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Device trust records always sync so revocations reach the mesh.",
                                fontSize = 11.sp, color = TextTertiary
                            )
                        }
                    }
                }

                // ── This device / pairing code ────────────────────────────
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        border = BorderStroke(1.dp, Border),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("This device", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                            Spacer(Modifier.height(6.dp))
                            Text(DesktopSync.displayName(), fontSize = 14.sp, color = TextPrimary)
                            Text(DesktopSync.deviceId(), fontSize = 12.sp, color = TextSecondary, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Pairing code — copy it to the other device and paste theirs below.",
                                fontSize = 12.sp, color = TextTertiary
                            )
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SurfaceMuted)
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    pairingCode,
                                    fontSize = 11.sp,
                                    color = TextSecondary,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    Toolkit.getDefaultToolkit().systemClipboard
                                        .setContents(StringSelection(pairingCode), null)
                                    message = "Pairing code copied"
                                },
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, Border),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                            ) { Text("Copy pairing code", fontSize = 13.sp) }
                        }
                    }
                }

                // ── Pair a device ─────────────────────────────────────────
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        border = BorderStroke(1.dp, Border),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Pair a device", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = codeInput,
                                onValueChange = { codeInput = it; verifiedSas = null },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Their pairing code", color = TextTertiary, fontSize = 12.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary,
                                    unfocusedBorderColor = Border
                                ),
                                textStyle = androidx.compose.ui.text.TextStyle(
                                    fontSize = 12.sp,
                                    color = TextPrimary,
                                    fontFamily = FontFamily.Monospace
                                ),
                                maxLines = 4
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = addressInput,
                                onValueChange = { addressInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Optional address (host:port) — direct connect when mDNS is blocked", color = TextTertiary, fontSize = 12.sp) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Primary,
                                    unfocusedBorderColor = Border
                                ),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPrimary),
                                singleLine = true
                            )
                            Spacer(Modifier.height(10.dp))
                            verifiedSas?.let { sas ->
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFFFEF3C7))
                                        .padding(12.dp)
                                ) {
                                    Text(
                                        "Both devices should show the same code:  $sas\n" +
                                            "Confirm it matches on the other device before pairing.",
                                        fontSize = 13.sp,
                                        color = Amber
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val sas = DesktopSync.sasFor(pairingCode, codeInput)
                                        if (sas == null) {
                                            verifiedSas = null
                                            message = "That doesn't look like a valid pairing code."
                                        } else {
                                            verifiedSas = sas
                                            message = null
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    border = BorderStroke(1.dp, Border),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                                ) { Text("Verify code", fontSize = 13.sp) }
                                Button(
                                    onClick = {
                                        val peer = DesktopSync.pairWith(codeInput)
                                        if (peer == null) {
                                            message = "Pairing failed — code invalid or it's this device."
                                        } else {
                                            DesktopSync.setPeerAddress(peer.deviceId, addressInput.trim())
                                            peers = DesktopSync.peers()
                                            message = "Paired with ${peer.displayName}"
                                            codeInput = ""
                                            addressInput = ""
                                            verifiedSas = null
                                        }
                                    },
                                    enabled = verifiedSas != null,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                                ) { Text("Confirm & pair", fontSize = 13.sp) }
                            }
                            message?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, fontSize = 13.sp, color = TextSecondary)
                            }
                        }
                    }
                }

                // ── Paired devices ────────────────────────────────────────
                item { Text("Paired devices", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextTertiary) }
                if (peers.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(999.dp))
                                .background(SurfaceMuted)
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("No devices paired yet — sync stays a no-op until you pair.", fontSize = 13.sp, color = TextTertiary)
                        }
                    }
                } else {
                    items(peers, key = { it.deviceId }) { peer ->
                        PairedPeerRow(peer) {
                            DesktopSync.unpair(peer.deviceId)
                            peers = DesktopSync.peers()
                        }
                    }
                }

                // ── Synced memory ─────────────────────────────────────────
                item { Text("Synced memory", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextTertiary) }
                val memory = DesktopSync.memory()
                if (memory.isEmpty()) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(999.dp))
                                .background(SurfaceMuted)
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text("Nothing synced yet — pair a device and wait for a sync round.", fontSize = 13.sp, color = TextTertiary)
                        }
                    }
                } else {
                    items(memory.toSortedMap().toList(), key = { it.first }) { (category, facts) ->
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = Surface),
                            border = BorderStroke(1.dp, Border),
                            elevation = CardDefaults.cardElevation(0.dp)
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(category, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimary)
                                facts.forEach { fact ->
                                    Text("· $fact", fontSize = 12.sp, color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairedPeerRow(peer: PairedPeer, onUnpair: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Sync, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimary)
                Text(peer.deviceId, fontSize = 12.sp, color = TextTertiary, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = onUnpair) {
                Icon(Icons.Rounded.Delete, contentDescription = "Unpair", tint = Red, modifier = Modifier.size(18.dp))
            }
        }
    }
}
