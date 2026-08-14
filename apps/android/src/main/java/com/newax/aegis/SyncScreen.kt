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
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.sync.PairedPeer
import org.json.JSONObject
import com.newax.aegis.ui.theme.NewaxLightColors

// ── Design tokens — same palette as the rest of the app ─────────────────────
private val Surface = NewaxLightColors.surface
private val SurfaceMuted = NewaxLightColors.surfaceMuted
private val Primary = NewaxLightColors.textPrimary
private val TextPri = NewaxLightColors.textPrimary
private val TextSec = NewaxLightColors.textSecondary
private val TextTer = NewaxLightColors.textTertiary
private val Border = NewaxLightColors.border
private val AccentGreen = NewaxLightColors.success
private val AccentRed = NewaxLightColors.error

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
    var relayInput by remember { mutableStateOf(SyncRuntime.relayUrl()) }
    var relayMessage by remember { mutableStateOf<String?>(null) }
    var catStates by remember {
        mutableStateOf(
            SyncRuntime.CATEGORY_TABLES.mapValues { (_, tables) -> tables.any { SyncRuntime.categoryEnabled(it) } }
        )
    }
    var sendCmdPeer by remember { mutableStateOf<PairedPeer?>(null) }
    var commandMessage by remember { mutableStateOf<String?>(null) }
    var permsPeer by remember { mutableStateOf<PairedPeer?>(null) }
    var history by remember { mutableStateOf(SyncRuntime.commandHistory()) }

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
                                // Item 7 — flip the continuous listener with the toggle.
                                if (it) SyncForegroundService.start(context)
                                else SyncForegroundService.stop(context)
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

        // ── Internet relay (WAN) ────────────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Internet relay (optional)", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Lets paired devices sync over the internet when they aren't on the same network.",
                        fontSize = 13.sp, color = TextSec
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = relayInput,
                        onValueChange = {
                            relayInput = it
                            relayMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Relay URL (ws://host:port or wss://…)", color = TextTer, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Border
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = TextPri, fontFamily = FontFamily.Monospace),
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            SyncRuntime.setRelayUrl(relayInput)
                            relayMessage = if (relayInput.isBlank()) "Relay disabled — LAN only" else "Relay URL saved"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) { Text("Save relay URL", fontSize = 13.sp) }
                    relayMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 13.sp, color = TextSec)
                    }
                }
            }
        }

        // ── Sync categories ──────────────────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Sync categories", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "What this device shares with — and accepts from — paired devices.",
                        fontSize = 13.sp, color = TextSec
                    )
                    Spacer(Modifier.height(6.dp))
                    SyncRuntime.CATEGORY_TABLES.forEach { (name, tables) ->
                        CategoryToggleRow(
                            name = name,
                            enabled = catStates[name] ?: true,
                            onToggle = {
                                val on = !(catStates[name] ?: true)
                                tables.forEach { t -> SyncRuntime.setCategoryEnabled(t, on) }
                                catStates = catStates.toMutableMap().apply { put(name, on) }
                            }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Device trust records always sync so revocations reach the mesh.",
                        fontSize = 11.sp, color = TextTer
                    )
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
                            cm.setPrimaryClip(ClipData.newPlainText("Newax pairing code", pairingCode))
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
                PairedPeerRow(
                    peer = peer,
                    onPermissions = { permsPeer = peer },
                    onSendCommand = { sendCmdPeer = peer },
                    onUnpair = {
                        SyncRuntime.unpair(peer.deviceId)
                        peers = SyncRuntime.peers()
                    }
                )
            }
            commandMessage?.let { msg ->
                item {
                    Text(
                        msg,
                        fontSize = 12.sp, color = TextSec,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // ── Command history (Fix B) ─────────────────────────────────────
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text(
                "Command history",
                fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextTer,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
        if (history.isEmpty()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(SurfaceMuted)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "No commands yet — use Send on a paired device row to ask it to perform an action.",
                        fontSize = 13.sp, color = TextTer
                    )
                }
            }
        } else {
            items(history) { h ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (h.sent) Primary else AccentGreen)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (h.sent) "Sent · " else "Received · ") + h.detail,
                            fontSize = 13.sp, color = TextPri, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            h.peerDeviceId.take(12) + " · " +
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                                ).format(java.util.Date(h.atMs)),
                            fontSize = 11.sp, color = TextTer
                        )
                    }
                }
            }
        }
    }

    permsPeer?.let { peer ->
        PeerPermissionsDialog(
            peer = peer,
            allowed = SyncRuntime.peerPermissions(peer.deviceId),
            onDismiss = { permsPeer = null },
            onSave = { classes ->
                SyncRuntime.setPeerPermissions(peer.deviceId, classes)
                permsPeer = null
            }
        )
    }

    sendCmdPeer?.let { peer ->
        SendCommandDialog(
            peer = peer,
            onDismiss = { sendCmdPeer = null },
            onSend = { commandClass, args ->
                SyncRuntime.sendCommand(peer.deviceId, commandClass, args)
                commandMessage = "Command sent to ${peer.displayName} ($commandClass) — journaled, relayed to the target"
                history = SyncRuntime.commandHistory()
                sendCmdPeer = null
            }
        )
    }
}

/**
 * The per-peer "Send command" authoring surface (docs/SYNC_DESIGN.md §6, item
 * 6): pick one of the mesh command classes and give it JSON args. The command
 * journals as a targeted LOG entry; ONLY the peer's CommandDispatcher may
 * process it, gated by the peer's per-peer allowlist + its policy spine as
 * AGENT origin. The peer acks back (executed/refused/expired).
 */
@Composable
private fun SendCommandDialog(
    peer: PairedPeer,
    onDismiss: () -> Unit,
    onSend: (String, Map<String, String>) -> Unit
) {
    var selected by remember { mutableStateOf("open_app") }
    var argsText by remember { mutableStateOf("{}") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send command to ${peer.displayName}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "The peer only processes this if you are allowlisted for its class (permissions dialog), and it runs through its policy spine as AGENT origin — it grants zero authority by itself.",
                    fontSize = 12.sp, color = TextSec
                )
                Spacer(Modifier.height(10.dp))
                Text("Command class", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPri)
                Spacer(Modifier.height(6.dp))
                SyncRuntime.COMMAND_CLASSES.forEach { cls ->
                    FilterChip(
                        selected = selected == cls,
                        onClick = { selected = cls },
                        label = { Text(cls, fontSize = 12.sp) },
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    label = { Text("Args (JSON)") },
                    textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 12.sp, color = AccentRed)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val args = runCatching {
                    val o = JSONObject(if (argsText.isBlank()) "{}" else argsText)
                    buildMap { o.keys().forEach { k -> put(k, o.optString(k)) } }
                }.getOrElse {
                    error = "Invalid JSON: ${it.message}"
                    return@TextButton
                }
                onSend(selected, args)
            }) { Text("Send") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CategoryToggleRow(name: String, enabled: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, fontSize = 14.sp, color = TextPri, modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Primary,
                uncheckedThumbColor = Color(0xFF8D8D87),
                uncheckedTrackColor = Color(0xFFE7E7E2)
            )
        )
    }
}

@Composable
private fun PeerPermissionsDialog(
    peer: PairedPeer,
    allowed: Set<String>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    var selected by remember { mutableStateOf(allowed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissions for ${peer.displayName}", fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    "Which command classes may this device send you? Unchecked means blocked.",
                    fontSize = 13.sp, color = TextSec
                )
                Spacer(Modifier.height(8.dp))
                SyncRuntime.COMMAND_CLASSES.forEach { cls ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = cls in selected,
                            onCheckedChange = {
                                selected = if (it) selected + cls else selected - cls
                            }
                        )
                        Text(cls, fontSize = 13.sp, color = TextPri, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (selected.isEmpty()) "Nothing allowed — the peer can only sync data, not send commands."
                    else "Restrictions apply to commands; data sync always follows the categories above.",
                    fontSize = 11.sp, color = TextTer
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) { Text("Save", color = Primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSec) }
        },
        containerColor = Surface
    )
}

@Composable
private fun PairedPeerRow(
    peer: PairedPeer,
    onPermissions: () -> Unit,
    onSendCommand: () -> Unit,
    onUnpair: () -> Unit
) {
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
            IconButton(onClick = onPermissions) {
                Icon(Icons.Outlined.Sync, contentDescription = "Permissions", tint = TextSec, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSendCommand) {
                Icon(Icons.Outlined.Send, contentDescription = "Send command", tint = TextSec, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onUnpair) {
                Icon(Icons.Rounded.Delete, contentDescription = "Unpair", tint = AccentRed, modifier = Modifier.size(18.dp))
            }
        }
    }
}
