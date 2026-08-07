package com.newax.aegis

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.engine.learning.DraftStore
import com.newax.aegis.engine.learning.LearningWorker
import com.newax.aegis.engine.learning.ScanProgress
import com.newax.aegis.engine.learning.ScanSource
import java.text.SimpleDateFormat
import java.util.*

// ── Design tokens ─────────────────────────────────────────────────────────────
private val LS_Surface      = Color(0xFFFFFFFF)
private val LS_SurfaceMuted = Color(0xFFF2F2EF)
private val LS_SurfaceStr   = Color(0xFFE7E7E2)
private val LS_Primary      = Color(0xFF1B1B1A)
private val LS_TextPri      = Color(0xFF1B1B1A)
private val LS_TextSec      = Color(0xFF686864)
private val LS_TextTer      = Color(0xFF8D8D87)
private val LS_Border       = Color(0xFFD8D8D3)
private val LS_Green        = Color(0xFF16A34A)
private val LS_GreenBg      = Color(0xFFDCFCE7)
private val LS_Amber        = Color(0xFFD97706)
private val LS_AmberBg      = Color(0xFFFEF3C7)
private val LS_Red          = Color(0xFFDC2626)

private val SOURCE_ICONS: Map<ScanSource, ImageVector> = mapOf(
    ScanSource.CONTACTS    to Icons.Outlined.Contacts,
    ScanSource.SMS_INBOX   to Icons.Outlined.Inbox,
    ScanSource.SMS_SENT    to Icons.Outlined.Send,
    ScanSource.CALL_LOGS   to Icons.Outlined.Phone,
    ScanSource.GALLERY_OCR to Icons.Outlined.Image,
    ScanSource.DOWNLOADS   to Icons.Outlined.Download
)

private val INTERVAL_OPTIONS = listOf(15L, 20L, 30L, 60L)

@Composable
fun LearningSettingsSection(vm: MainViewModel) {
    val context = LocalContext.current

    // ── Live state ────────────────────────────────────────────────────────────
    var isEnabled       by remember { mutableStateOf(ScanProgress.isEnabled()) }
    var isScheduled     by remember { mutableStateOf(LearningWorker.isScheduled(context)) }
    var currentInterval by remember { mutableStateOf(ScanProgress.getIntervalMinutes()) }
    var lastRunMs       by remember { mutableStateOf(ScanProgress.getLastRunMs()) }
    var currentSource   by remember { mutableStateOf(ScanProgress.currentSource()) }
    var totalCreated    by remember { mutableIntStateOf(ScanProgress.getTotalDraftsCreated()) }
    var draftStats      by remember { mutableStateOf(DraftStore.stats(vm.memory)) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    fun refresh() {
        isEnabled       = ScanProgress.isEnabled()
        isScheduled     = LearningWorker.isScheduled(context)
        currentInterval = ScanProgress.getIntervalMinutes()
        lastRunMs       = ScanProgress.getLastRunMs()
        currentSource   = ScanProgress.currentSource()
        totalCreated    = ScanProgress.getTotalDraftsCreated()
        draftStats      = DraftStore.stats(vm.memory)
    }

    // Section label
    LearnLabel("Self-Learning Engine")

    // ── Master ON/OFF card ───────────────────────────────────────────────────
    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = LS_Surface),
        border    = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isEnabled && isScheduled) Color(0xFF86EFAC) else LS_Border
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status dot
                val dotColor by animateColorAsState(
                    if (isEnabled && isScheduled) LS_Green else LS_TextTer,
                    animationSpec = tween(300), label = "dot"
                )
                Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        if (isEnabled && isScheduled) "Self-Learning Active" else "Self-Learning Off",
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = LS_TextPri
                    )
                    Spacer(Modifier.height(2.dp))
                    AnimatedContent(
                        targetState = if (isEnabled && isScheduled)
                            "Runs every ${currentInterval}min  •  battery-safe"
                        else
                            "Scan contacts, messages, call logs, gallery, files",
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                        label = "status"
                    ) { txt ->
                        Text(txt, fontSize = 12.sp, color = LS_TextSec, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = isEnabled && isScheduled,
                    onCheckedChange = { on ->
                        if (on) {
                            LearningWorker.schedule(context)
                        } else {
                            LearningWorker.cancel(context)
                        }
                        refresh()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor  = Color.White,
                        checkedTrackColor  = LS_Green,
                        uncheckedThumbColor = Color(0xFF8D8D87),
                        uncheckedTrackColor = LS_SurfaceStr
                    )
                )
            }

            if (isEnabled && isScheduled) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = LS_Border)
                Spacer(Modifier.height(12.dp))

                // Last run + next run
                val lastRunText = if (lastRunMs == 0L) "Never run yet"
                else "Last: ${relativeTime(lastRunMs)}"
                val nextRunText = if (lastRunMs == 0L) "Running soon (2 min delay)"
                else "Next: ~${relativeTime(lastRunMs + currentInterval * 60_000L)} from now"

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeChip(Icons.Outlined.History, lastRunText)
                    TimeChip(Icons.Outlined.Schedule, nextRunText)
                }

                Spacer(Modifier.height(12.dp))

                // Currently queued source
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(LS_SurfaceMuted)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        SOURCE_ICONS[currentSource] ?: Icons.Outlined.Radar,
                        contentDescription = null,
                        tint = LS_TextSec,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Next batch:", fontSize = 12.sp, color = LS_TextSec)
                    Spacer(Modifier.width(4.dp))
                    Text(currentSource.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = LS_TextPri)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "offset ${ScanProgress.getOffset(currentSource)}",
                        fontSize = 11.sp, color = LS_TextTer
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Scan now button
                OutlinedButton(
                    onClick = {
                        LearningWorker.runOnce(context)
                        vm.refreshDrafts()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    border = androidx.compose.foundation.BorderStroke(1.dp, LS_Border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = LS_TextSec),
                    shape  = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run one batch now", fontSize = 14.sp)
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── Statistics card ───────────────────────────────────────────────────────
    LearnLabel("Statistics")
    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = LS_Surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, LS_Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("Created", "$totalCreated", Icons.Outlined.AutoAwesome, Color(0xFF6366F1), Modifier.weight(1f))
                StatBox("Pending", "${draftStats.pending}", Icons.Outlined.Pending, LS_Amber, Modifier.weight(1f))
                StatBox("Saved", "${draftStats.approved}", Icons.Outlined.CheckCircle, LS_Green, Modifier.weight(1f))
                StatBox("Skipped", "${draftStats.rejected}", Icons.Outlined.Cancel, LS_TextTer, Modifier.weight(1f))
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── Scan interval ─────────────────────────────────────────────────────────
    LearnLabel("Scan Interval")
    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = LS_Surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, LS_Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "How often to run one scan batch. Lower = more frequent but more battery.",
                fontSize = 13.sp, color = LS_TextSec, lineHeight = 19.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INTERVAL_OPTIONS.forEach { min ->
                    val selected = currentInterval == min
                    FilterChip(
                        selected = selected,
                        onClick  = {
                            ScanProgress.setIntervalMinutes(min)
                            currentInterval = min
                            if (isEnabled && isScheduled) {
                                // Re-schedule with new interval
                                LearningWorker.schedule(context, min)
                                refresh()
                            }
                        },
                        label  = { Text("${min}m", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = LS_Primary,
                            selectedLabelColor     = Color.White
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = selected,
                            borderColor = LS_Border, selectedBorderColor = Color.Transparent
                        )
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── Data sources ──────────────────────────────────────────────────────────
    LearnLabel("Data Sources")
    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = LS_Surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, LS_Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            ScanSource.entries.forEachIndexed { i, source ->
                var srcEnabled by remember { mutableStateOf(ScanProgress.isSourceEnabled(source)) }
                val isCurrent   = source == currentSource
                val lastSeenMs  = ScanProgress.getLastSeenMs(source)
                val offset      = ScanProgress.getOffset(source)

                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = !srcEnabled
                            ScanProgress.setSourceEnabled(source, next)
                            srcEnabled = next
                        }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Icon
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (srcEnabled) LS_SurfaceMuted else LS_SurfaceStr),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            SOURCE_ICONS[source] ?: Icons.Outlined.Storage,
                            contentDescription = null,
                            tint     = if (srcEnabled) LS_TextSec else LS_TextTer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                source.label,
                                fontSize   = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color      = if (srcEnabled) LS_TextPri else LS_TextTer
                            )
                            if (isCurrent && srcEnabled) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(LS_GreenBg)
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text("next", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = LS_Green)
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            buildString {
                                if (!srcEnabled) { append("Disabled"); return@buildString }
                                if (lastSeenMs > 0L) append("Last: ${relativeTime(lastSeenMs)}")
                                else append("Not scanned yet")
                                if (offset > 0) append("  •  ${offset} scanned")
                                append("  •  batch ${source.batchSize}")
                            },
                            fontSize = 11.sp,
                            color    = LS_TextTer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked  = srcEnabled,
                        onCheckedChange = { on ->
                            ScanProgress.setSourceEnabled(source, on)
                            srcEnabled = on
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = Color.White,
                            checkedTrackColor   = LS_Primary,
                            uncheckedThumbColor = Color(0xFF8D8D87),
                            uncheckedTrackColor = LS_SurfaceStr
                        ),
                        modifier = Modifier.size(height = 24.dp, width = 44.dp)
                    )
                }
                if (i < ScanSource.entries.lastIndex) {
                    HorizontalDivider(color = LS_Border, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── Danger zone ───────────────────────────────────────────────────────────
    LearnLabel("Reset & Maintenance")
    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = LS_Surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, LS_Border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            // Reset progress
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showResetDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.RestartAlt, contentDescription = null, tint = LS_Amber, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Reset Scan Progress", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LS_TextPri)
                    Spacer(Modifier.height(2.dp))
                    Text("Re-scan all sources from the beginning", fontSize = 12.sp, color = LS_TextSec)
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = LS_TextTer, modifier = Modifier.size(18.dp))
            }

            HorizontalDivider(color = LS_Border, modifier = Modifier.padding(horizontal = 16.dp))

            // Clear all drafts
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showClearDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = LS_Red, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Clear All Drafts", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LS_TextPri)
                    Spacer(Modifier.height(2.dp))
                    Text("Remove all pending, approved, and rejected drafts", fontSize = 12.sp, color = LS_TextSec)
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = LS_TextTer, modifier = Modifier.size(18.dp))
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor   = LS_Surface,
            shape            = RoundedCornerShape(20.dp),
            title  = { Text("Reset scan progress?", fontWeight = FontWeight.SemiBold, color = LS_TextPri) },
            text   = {
                Text(
                    "All scan offsets and timestamps will be cleared. Aegis will re-scan everything from scratch. Existing drafts and memory are unaffected.",
                    color = LS_TextSec, lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        ScanProgress.resetAll()
                        refresh()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LS_Amber)
                ) { Text("Reset", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel", color = LS_TextSec) }
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor   = LS_Surface,
            shape            = RoundedCornerShape(20.dp),
            title  = { Text("Clear all drafts?", fontWeight = FontWeight.SemiBold, color = LS_TextPri) },
            text   = {
                Text(
                    "All ${draftStats.total} drafts will be permanently deleted. Facts already approved into memory are unaffected.",
                    color = LS_TextSec, lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        DraftStore.clearOld(vm.memory, keepPending = false)
                        vm.refreshDrafts()
                        refresh()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = LS_Red)
                ) { Text("Clear all", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel", color = LS_TextSec) }
            }
        )
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun LearnLabel(text: String) {
    Text(
        text,
        fontSize   = 11.sp,
        fontWeight = FontWeight.Medium,
        color      = LS_TextTer,
        modifier   = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
    )
}

@Composable
private fun StatBox(label: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LS_SurfaceMuted)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LS_TextPri)
        Text(label, fontSize = 10.sp, color = LS_TextTer)
    }
}

@Composable
private fun TimeChip(icon: ImageVector, text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(LS_SurfaceMuted)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = LS_TextSec, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp, color = LS_TextSec, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Utility ───────────────────────────────────────────────────────────────────

private fun relativeTime(ms: Long): String {
    if (ms <= 0L) return "never"
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 0              -> "just now"
        diff < 60_000         -> "${diff / 1000}s ago"
        diff < 3_600_000      -> "${diff / 60_000}m ago"
        diff < 86_400_000     -> "${diff / 3_600_000}h ago"
        else                  -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
    }
}
