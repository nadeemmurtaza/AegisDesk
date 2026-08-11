package com.newax.aegis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.sync.PairedPeer

// ── Design tokens — same palette as the rest of the app ─────────────────────
private val Surface = Color(0xFFFFFFFF)
private val SurfaceMuted = Color(0xFFF2F2EF)
private val Primary = Color(0xFF1B1B1A)
private val TextPri = Color(0xFF1B1B1A)
private val TextSec = Color(0xFF686864)
private val TextTer = Color(0xFF8D8D87)
private val Border = Color(0xFFD8D8D3)
private val AccentGreen = Color(0xFF22C55E)
private val AccentRed = Color(0xFFDC2626)

/**
 * The sync control surface (docs/SYNC_DESIGN.md §3, §9 — the wiring slice's
 * UI twin): automatic sync toggle + last-run status, this device's pairing
 * code (copyable text — the QR rendered as a string, no camera needed), the
 * SAS-verified pair flow, and the paired-device list with unpair. Every state
 * is surfaced: empty (no peers), error (bad code), and success paths.
 */
@Composable
fun SyncScreen(padding: androidx.compose.foundation.layout.PaddingValues) {
    val context = LocalContext.current
    var autoOn by remember { mutableStateOf(SyncRuntime.enabled()) }
    var statusText by remember { mutableStateOf(SyncRuntime.status()) }
    var peers by remember { mutableStateOf(SyncRuntime.peers()) }

    var codeInput by remember { mutableStateOf("") }
    var addressInput by remember { mutableStateOf("") }
    var verifiedSas by remember { mutableStateOf<String?>(null) }
    var pairMessage by remember { mutableStateOf<String?>(null) }

    val pairingCode = remember { SyncRuntime.pairingCode() }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
    ) {
        // ── Automatic sync ───────────────────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatic sync", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (autoOn) "Syncs memory to paired devices on the same network"
                                else "Off — memory stays on this device",
                                fontSize = 13.sp, color = TextSec
                            )
                        }
                        Switch(
                            checked = autoOn,
                            onCheckedChange = {
                                autoOn = it
                                SyncRuntime.setEnabled(it)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Primary,
                                uncheckedThumbColor = Color(0xFF8D8D87),
                                uncheckedTrackColor = Color(0xFFE7E7E2)
                            )
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (autoOn) AccentGreen else TextTer)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(statusText, fontSize = 13.sp, color = TextSec, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // ── This device ──────────────────────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("This device", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri)
                    Spacer(Modifier.height(6.dp))
                    Text(SyncRuntime.displayName(), fontSize = 14.sp, color = TextPri)
                    Text(SyncRuntime.deviceId(), fontSize = 12.sp, color = TextSec, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
                    Text("Pairing code — copy it to the other device, and paste theirs below.", fontSize = 12.sp, color = TextTer)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(SurfaceMuted)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            pairingCode,
                            fontSize = 11.sp,
                            color = TextSec,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Aegis pairing code", pairingCode))
                            pairMessage = "Pairing code copied"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSec)
                    ) { Text("Copy pairing code", fontSize = 13.sp) }
                }
            }
        }

        // ── Pair a device ────────────────────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Pair a device", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = {
                            codeInput = it
                            verifiedSas = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Their pairing code", color = TextTer, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Border
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = TextPri, fontFamily = FontFamily.Monospace),
                        maxLines = 4
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Optional address (host:port) — direct connect when mDNS is blocked", color = TextTer, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Border
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPri),
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    verifiedSas?.let { sas ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFFEF3C7))
                                .padding(12.dp)
                        ) {
                            Text(
                                "Both devices should show the same code:  $sas\n" +
                                    "Confirm it matches on the other device before pairing.",
                                fontSize = 13.sp,
                                color = Color(0xFF92400E)
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val sas = SyncRuntime.sasFor(pairingCode, codeInput)
                                if (sas == null) {
                                    verifiedSas = null
                                    pairMessage = "That doesn't look like a valid pairing code."
                                } else {
                                    verifiedSas = sas
                                    pairMessage = null
                                }
                            },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSec)
                        ) { Text("Verify code", fontSize = 13.sp) }
                        Button(
                            onClick = {
                                val peer = SyncRuntime.pairWith(codeInput)
                                if (peer == null) {
                                    pairMessage = "Pairing failed — code invalid or it's this device."
                                } else {
                                    SyncRuntime.setPeerAddress(peer.deviceId, addressInput.trim())
                                    peers = SyncRuntime.peers()
                                    pairMessage = "Paired with ${peer.displayName}"
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
                    pairMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 13.sp, color = TextSec)
                    }
                }
            }
        }

        // ── Paired devices ───────────────────────────────────────────────
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text(
                "Paired devices",
                fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextTer,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
        if (peers.isEmpty()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(SurfaceMuted)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("No devices paired yet — sync stays a no-op until you pair.", fontSize = 13.sp, color = TextTer)
                }
            }
        } else {
            items(peers, key = { it.deviceId }) { peer ->
                PairedPeerRow(peer) {
                    SyncRuntime.unpair(peer.deviceId)
                    peers = SyncRuntime.peers()
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
        border = androidx.compose.foundation.BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Sync, contentDescription = null, tint = TextSec, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPri)
                Text(peer.deviceId, fontSize = 12.sp, color = TextTer, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = onUnpair) {
                Icon(Icons.Rounded.Delete, contentDescription = "Unpair", tint = AccentRed, modifier = Modifier.size(18.dp))
            }
        }
    }
}
