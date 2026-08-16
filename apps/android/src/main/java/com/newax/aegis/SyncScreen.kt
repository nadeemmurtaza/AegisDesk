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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.sync.PairedPeer
import org.json.JSONObject
import com.newax.aegis.ui.theme.NewaxTheme

// ── Design tokens — same palette as the rest of the app ─────────────────────
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

    // Every control below needs the device identity, and on a platform without
    // Ed25519 (below Android 12) there is none. Say so plainly rather than
    // showing a screen whose every button throws.
    if (!SyncRuntime.isAvailable) {
        SyncUnavailable(padding, SyncRuntime.unavailableReason)
        return
    }

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
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.sync_automatic), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (autoOn) stringResource(R.string.sync_auto_on_desc)
                                else stringResource(R.string.sync_auto_off_desc),
                                fontSize = 13.sp, color = NewaxTheme.colors.textSecondary
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
                                checkedTrackColor = NewaxTheme.colors.textPrimary,
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
                                .background(if (autoOn) NewaxTheme.colors.success else NewaxTheme.colors.textTertiary)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(statusText, fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }

        // ── Internet relay (WAN) ────────────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.sync_relay), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.sync_relay_desc),
                        fontSize = 13.sp, color = NewaxTheme.colors.textSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = relayInput,
                        onValueChange = {
                            relayInput = it
                            relayMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.sync_relay_url_label), color = NewaxTheme.colors.textTertiary, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NewaxTheme.colors.textPrimary,
                            unfocusedBorderColor = NewaxTheme.colors.border
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = NewaxTheme.colors.textPrimary, fontFamily = FontFamily.Monospace),
                        singleLine = true
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            SyncRuntime.setRelayUrl(relayInput)
                            relayMessage = if (relayInput.isBlank()) context.getString(R.string.sync_relay_disabled) else context.getString(R.string.sync_relay_saved)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary)
                    ) { Text(stringResource(R.string.action_save_relay_url), fontSize = 13.sp) }
                    relayMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
                    }
                }
            }
        }

        // ── Sync categories ──────────────────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.sync_categories), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.sync_categories_desc),
                        fontSize = 13.sp, color = NewaxTheme.colors.textSecondary
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
                        stringResource(R.string.sync_trust_note),
                        fontSize = 11.sp, color = NewaxTheme.colors.textTertiary
                    )
                }
            }
        }

        // ── This device ──────────────────────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.sync_this_device), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(SyncRuntime.displayName(), fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
                    Text(SyncRuntime.deviceId(), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.sync_pairing_hint), fontSize = 12.sp, color = NewaxTheme.colors.textTertiary)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(NewaxTheme.colors.surfaceMuted)
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Text(
                            pairingCode,
                            fontSize = 11.sp,
                            color = NewaxTheme.colors.textSecondary,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.sync_pairing_code_label), pairingCode))
                            pairMessage = context.getString(R.string.sync_pairing_copied)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textSecondary)
                    ) { Text(stringResource(R.string.action_copy_pairing_code), fontSize = 13.sp) }
                }
            }
        }

        // ── Pair a device ────────────────────────────────────────────────
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.sync_pair_device), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = {
                            codeInput = it
                            verifiedSas = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.sync_their_code), color = NewaxTheme.colors.textTertiary, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NewaxTheme.colors.textPrimary,
                            unfocusedBorderColor = NewaxTheme.colors.border
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = NewaxTheme.colors.textPrimary, fontFamily = FontFamily.Monospace),
                        maxLines = 4
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addressInput,
                        onValueChange = { addressInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.sync_optional_address), color = NewaxTheme.colors.textTertiary, fontSize = 12.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NewaxTheme.colors.textPrimary,
                            unfocusedBorderColor = NewaxTheme.colors.border
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = NewaxTheme.colors.textPrimary),
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
                                stringResource(R.string.sync_sas_confirm, sas),
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
                                    pairMessage = context.getString(R.string.sync_invalid_code)
                                } else {
                                    verifiedSas = sas
                                    pairMessage = null
                                }
                            },
                            modifier = Modifier.weight(1f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textSecondary)
                        ) { Text(stringResource(R.string.action_verify_code), fontSize = 13.sp) }
                        Button(
                            onClick = {
                                val peer = SyncRuntime.pairWith(codeInput)
                                if (peer == null) {
                                    pairMessage = context.getString(R.string.sync_pair_failed)
                                } else {
                                    SyncRuntime.setPeerAddress(peer.deviceId, addressInput.trim())
                                    peers = SyncRuntime.peers()
                                    pairMessage = context.getString(R.string.sync_paired_with, peer.displayName)
                                    codeInput = ""
                                    addressInput = ""
                                    verifiedSas = null
                                }
                            },
                            enabled = verifiedSas != null,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary)
                        ) { Text(stringResource(R.string.action_confirm_pair), fontSize = 13.sp) }
                    }
                    pairMessage?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
                    }
                }
            }
        }

        // ── Paired devices ───────────────────────────────────────────────
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text(
                stringResource(R.string.sync_section_paired),
                fontSize = 11.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
        if (peers.isEmpty()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(NewaxTheme.colors.surfaceMuted)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.sync_no_devices), fontSize = 13.sp, color = NewaxTheme.colors.textTertiary)
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
                        fontSize = 12.sp, color = NewaxTheme.colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // ── Command history (Fix B) ─────────────────────────────────────
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text(
                stringResource(R.string.sync_section_command_history),
                fontSize = 11.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
        if (history.isEmpty()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(999.dp))
                        .background(NewaxTheme.colors.surfaceMuted)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.sync_no_commands),
                        fontSize = 13.sp, color = NewaxTheme.colors.textTertiary
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
                            .background(if (h.sent) NewaxTheme.colors.textPrimary else NewaxTheme.colors.success)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (h.sent) context.getString(R.string.sync_sent_prefix) else context.getString(R.string.sync_received_prefix)) + h.detail,
                            fontSize = 13.sp, color = NewaxTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            h.peerDeviceId.take(12) + " · " +
                                java.text.DateFormat.getDateTimeInstance(
                                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
                                ).format(java.util.Date(h.atMs)),
                            fontSize = 11.sp, color = NewaxTheme.colors.textTertiary
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
                commandMessage = context.getString(R.string.sync_command_sent, peer.displayName, commandClass)
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
    val context = LocalContext.current
    var selected by remember { mutableStateOf("open_app") }
    var argsText by remember { mutableStateOf("{}") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_send_command_title, peer.displayName), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sync_send_command_desc),
                    fontSize = 12.sp, color = NewaxTheme.colors.textSecondary
                )
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.sync_command_class), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textPrimary)
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
                    label = { Text(stringResource(R.string.sync_args_label)) },
                    textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, fontSize = 12.sp, color = NewaxTheme.colors.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val args = runCatching {
                    val o = JSONObject(if (argsText.isBlank()) "{}" else argsText)
                    buildMap { o.keys().forEach { k -> put(k, o.optString(k)) } }
                }.getOrElse {
                    error = context.getString(R.string.sync_invalid_json, it.message)
                    return@TextButton
                }
                onSend(selected, args)
            }) { Text(stringResource(R.string.action_send)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
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
        Text(name, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = enabled,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NewaxTheme.colors.textPrimary,
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
        title = { Text(stringResource(R.string.sync_permissions_title, peer.displayName), fontWeight = FontWeight.SemiBold, fontSize = 16.sp) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sync_permissions_desc),
                    fontSize = 13.sp, color = NewaxTheme.colors.textSecondary
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
                        Text(cls, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (selected.isEmpty()) stringResource(R.string.sync_permissions_none)
                    else stringResource(R.string.sync_permissions_restricted),
                    fontSize = 11.sp, color = NewaxTheme.colors.textTertiary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) { Text(stringResource(R.string.action_save), color = NewaxTheme.colors.textPrimary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = NewaxTheme.colors.textSecondary) }
        },
        containerColor = NewaxTheme.colors.surface
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
        colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.Sync, contentDescription = null, tint = NewaxTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary)
                Text(peer.deviceId, fontSize = 12.sp, color = NewaxTheme.colors.textTertiary, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = onPermissions) {
                Icon(Icons.Outlined.Sync, contentDescription = stringResource(R.string.cd_permissions), tint = NewaxTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onSendCommand) {
                Icon(Icons.Outlined.Send, contentDescription = stringResource(R.string.cd_send_command), tint = NewaxTheme.colors.textSecondary, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onUnpair) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.cd_unpair), tint = NewaxTheme.colors.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * Shown instead of the Sync screen when this device cannot hold a sync identity.
 *
 * Names the limit and its boundary. A user on Android 10 seeing "sync is off"
 * with no explanation reasonably concludes the app is broken; the fix for the
 * crash is only half a fix if the replacement is silence.
 */
@Composable
private fun SyncUnavailable(
    padding: androidx.compose.foundation.layout.PaddingValues,
    reason: String?,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(NewaxTheme.colors.bg)
            .padding(padding)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Sync,
                contentDescription = null,
                tint = NewaxTheme.colors.textTertiary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.sync_unavailable_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = NewaxTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                reason ?: stringResource(R.string.sync_unavailable_reason),
                fontSize = 14.sp,
                color = NewaxTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.sync_unavailable_body),
                fontSize = 13.sp,
                color = NewaxTheme.colors.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
