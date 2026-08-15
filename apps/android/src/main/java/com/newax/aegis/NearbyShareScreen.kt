package com.newax.aegis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.ContextCompat
import com.newax.aegis.sync.AndroidProximityDiscovery
import com.newax.aegis.sync.IncomingRequest
import com.newax.aegis.sync.ProximityFiles
import com.newax.aegis.sync.ProximityListener
import com.newax.aegis.sync.ProximityEndpoint
import com.newax.aegis.sync.ProximityProfile
import com.newax.aegis.sync.ProximityTransfer
import com.newax.aegis.sync.proximityDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import com.newax.aegis.ui.theme.NewaxLightColors

// ── Design tokens — same palette as MainActivity's screens ──────────────────
private val Surface = NewaxLightColors.surface
private val SurfaceMuted = NewaxLightColors.surfaceMuted
private val SurfaceSel = NewaxLightColors.surfaceSelected
private val Primary = NewaxLightColors.textPrimary
private val TextPri = NewaxLightColors.textPrimary
private val TextSec = NewaxLightColors.textSecondary
private val TextTer = NewaxLightColors.textTertiary
private val Border = NewaxLightColors.border
private val AccentGreen = NewaxLightColors.success
private val AccentAmber = NewaxLightColors.warning

/** Runtime permissions the Nearby flow needs, by API level. */
private fun nearbyPermissions(): List<String> {
    val perms = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        perms += Manifest.permission.BLUETOOTH_SCAN
        perms += Manifest.permission.BLUETOOTH_ADVERTISE
        perms += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        perms += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.NEARBY_WIFI_DEVICES
    }
    return perms.distinct()
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(Locale.US, bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(Locale.US, bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * The encrypted Quick Share screen (docs/SYNC_DESIGN.md §10.1 / P2): toggle
 * Nearby sharing → BLE discovery + WiFi-Direct receive mode; pick a nearby
 * device and a file to send it end-to-end encrypted (progress shown); accept
 * or decline incoming transfers (the confirmation gate); received files land
 * in the app's Downloads directory. Every state has its surface: permission
 * prompt, loading, empty, error, transfer progress, and results.
 */
@Composable
fun NearbyShareScreen(padding: androidx.compose.foundation.layout.PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val discovery = remember {
        (proximityDiscovery() as? AndroidProximityDiscovery)
            ?: error("Android proximity actual missing — is AndroidSyncContext initialized?")
    }

    var sharing by remember { mutableStateOf(false) }
    var nearby by remember { mutableStateOf<List<ProximityEndpoint>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingTarget by remember { mutableStateOf<ProximityEndpoint?>(null) }
    var sendStatus by remember { mutableStateOf<String?>(null) }
    var sendProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var incoming by remember { mutableStateOf<IncomingRequest?>(null) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    fun startNearby() {
        error = null
        status = "Advertising + listening — other Newax devices nearby will appear below"
        discovery.startAdvertising(ProximityProfile(discovery.deviceId, discovery.displayName))
        discovery.startScanning(object : ProximityListener {
            override fun onPeerFound(endpoint: ProximityEndpoint) {
                mainHandler.post {
                    nearby = discovery.nearby()
                    // Receive-mode failures (e.g. WiFi-Direct unavailable) arrive
                    // on the main thread after startReceiving returns — re-read.
                    discovery.receiveError?.let { error = it }
                    discovery.error?.let { error = it }
                }
            }
        })
        discovery.startReceiving(
            onIncoming = { request ->
                mainHandler.post {
                    incoming = request
                    status = "Incoming transfer awaiting your decision"
                }
            },
            onResult = { result ->
                mainHandler.post {
                    when (result) {
                        is ProximityTransfer.Result.Received -> {
                            val dir = File(
                                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                                "aegis-shared"
                            ).apply { mkdirs() }
                            val file = File(dir, ProximityFiles.safeName(result.fileName))
                            try {
                                file.writeBytes(result.content)
                                lastResult = "Received ${result.fileName} (${formatSize(result.content.size.toLong())}) → ${file.absolutePath}"
                            } catch (e: Exception) {
                                lastResult = "Received but could not save: ${e.message}"
                            }
                            status = ""
                        }
                        is ProximityTransfer.Result.Failed ->
                            lastResult = "Incoming failed at ${result.stage}: ${result.reason}"
                        is ProximityTransfer.Result.Sent -> Unit
                    }
                }
            }
        )
        discovery.receiveError?.let { error = it }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = nearbyPermissions().all { results[it] == true }
        if (allGranted) {
            startNearby()
        } else {
            error = "Nearby sharing needs Bluetooth (and WiFi) permissions to advertise and transfer."
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val target = pendingTarget
        pendingTarget = null
        if (target == null || uri == null) return@rememberLauncherForActivityResult
        val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "shared-file"
        scope.launch(Dispatchers.IO) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) {
                mainHandler.post { sendStatus = "Could not read the selected file." }
                return@launch
            }
            val result = discovery.sendTo(
                target, name, bytes,
                object : ProximityTransfer.Progress {
                    override fun onChunk(index: Int, of: Int) {
                        mainHandler.post { sendProgress = index + 1 to of }
                    }
                }
            )
            mainHandler.post {
                sendProgress = null
                sendStatus = when (result) {
                    is ProximityTransfer.Result.Sent ->
                        "Sent ${result.chunks} chunk(s) — SHA-256 ${result.sha256Hex.take(12)}…"
                    is ProximityTransfer.Result.Failed ->
                        "Send failed at ${result.stage}: ${result.reason}"
                    is ProximityTransfer.Result.Received ->
                        "Unexpected: received instead of sent"
                }
            }
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, Border)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(if (sharing) Color(0xFFDCFCE7) else SurfaceMuted),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.NearMe,
                                contentDescription = null,
                                tint = if (sharing) AccentGreen else TextSec,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Nearby sharing", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPri)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (sharing) "Advertising + receiving — E2E encrypted, relay never involved"
                                else "Quick Share, but every byte sealed",
                                fontSize = 12.sp,
                                color = TextSec
                            )
                        }
                        Switch(
                            checked = sharing,
                            onCheckedChange = { on ->
                                sharing = on
                                if (on) {
                                    val missing = nearbyPermissions().filter {
                                        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                                    }
                                    if (missing.isEmpty()) startNearby()
                                    else permissionLauncher.launch(nearbyPermissions().toTypedArray())
                                } else {
                                    discovery.stop()
                                    nearby = emptyList()
                                    status = ""
                                    lastResult = null
                                }
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Primary)
                        )
                    }
                    if (status.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(status, fontSize = 12.sp, color = TextSec)
                    }
                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, fontSize = 12.sp, color = AccentAmber, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        item { SectionLabel("Nearby devices") }
        if (!sharing) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceMuted)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Turn on Nearby sharing to see Newax devices around you.", fontSize = 13.sp, color = TextTer)
                }
            }
        } else if (nearby.isEmpty()) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceMuted)
                        .padding(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TextTer)
                        Spacer(Modifier.height(8.dp))
                        Text("Scanning for nearby devices…", fontSize = 13.sp, color = TextSec)
                    }
                }
            }
        } else {
            items(nearby) { endpoint ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = sendProgress == null) {
                            pendingTarget = endpoint
                            filePicker.launch(arrayOf("*/*"))
                        },
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(SurfaceSel),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                endpoint.displayName.take(1).uppercase(Locale.US),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = TextPri
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(endpoint.displayName, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPri, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                endpoint.deviceId,
                                fontSize = 11.sp,
                                color = TextTer,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text("Send →", fontSize = 12.sp, color = TextSec, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        sendProgress?.let { (chunk, total) ->
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Encrypting + sending…", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPri)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { chunk.toFloat() / total },
                            modifier = Modifier.fillMaxWidth(),
                            color = Primary,
                            trackColor = SurfaceMuted
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("chunk $chunk of $total", fontSize = 11.sp, color = TextTer)
                    }
                }
            }
        }

        sendStatus?.let {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                ) {
                    Text(
                        it,
                        Modifier.padding(14.dp),
                        fontSize = 12.sp,
                        color = if (it.startsWith("Send failed")) AccentAmber else TextSec
                    )
                }
            }
        }

        lastResult?.let {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Surface),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Border)
                ) {
                    Text(
                        it,
                        Modifier.padding(14.dp),
                        fontSize = 12.sp,
                        color = TextSec,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }

    // ── incoming transfer confirmation gate (Quick Share semantics) ─────────
    incoming?.let { request ->
        AlertDialog(
            onDismissRequest = {
                request.answer(false)
                incoming = null
            },
            containerColor = Surface,
            shape = RoundedCornerShape(20.dp),
            title = { Text("Incoming transfer", fontWeight = FontWeight.SemiBold, color = TextPri) },
            text = {
                Column {
                    Text(request.peerDeviceId, fontSize = 12.sp, color = TextTer, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
                    Text(request.fileName, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = TextPri)
                    Spacer(Modifier.height(4.dp))
                    Text("${formatSize(request.sizeBytes)} · SHA-256 ${request.sha256Hex.take(12)}…", fontSize = 12.sp, color = TextSec)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        request.answer(true)
                        incoming = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text("Accept") }
            },
            dismissButton = {
                TextButton(onClick = {
                    request.answer(false)
                    incoming = null
                }) { Text("Decline", color = TextSec) }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = TextTer,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
}
