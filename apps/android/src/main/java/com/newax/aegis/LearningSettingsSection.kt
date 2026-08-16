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
import androidx.compose.ui.res.stringResource
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
import com.newax.aegis.ui.theme.NewaxTheme

// ── Design tokens ─────────────────────────────────────────────────────────────
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
    var draftStats      by remember { mutableStateOf(DraftStore.stats(vm.db)) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    fun refresh() {
        isEnabled       = ScanProgress.isEnabled()
        isScheduled     = LearningWorker.isScheduled(context)
        currentInterval = ScanProgress.getIntervalMinutes()
        lastRunMs       = ScanProgress.getLastRunMs()
        currentSource   = ScanProgress.currentSource()
        totalCreated    = ScanProgress.getTotalDraftsCreated()
        draftStats      = DraftStore.stats(vm.db)
    }

    // Section label
    LearnLabel(stringResource(R.string.learn_section_title))

    // ── Master ON/OFF card ───────────────────────────────────────────────────
    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isEnabled && isScheduled) Color(0xFF86EFAC) else NewaxTheme.colors.border
        ),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Status dot
                val dotColor by animateColorAsState(
                    if (isEnabled && isScheduled) NewaxTheme.colors.success else NewaxTheme.colors.textTertiary,
                    animationSpec = tween(300), label = "dot"
                )
                Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        if (isEnabled && isScheduled) stringResource(R.string.learn_status_active) else stringResource(R.string.learn_status_off),
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary
                    )
                    Spacer(Modifier.height(2.dp))
                    AnimatedContent(
                        targetState = if (isEnabled && isScheduled)
                            stringResource(R.string.learn_runs_every, currentInterval)
                        else
                            stringResource(R.string.learn_scan_sources_desc),
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                        label = "status"
                    ) { txt ->
                        Text(txt, fontSize = 12.sp, color = NewaxTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        checkedTrackColor  = NewaxTheme.colors.success,
                        uncheckedThumbColor = Color(0xFF8D8D87),
                        uncheckedTrackColor = NewaxTheme.colors.surfaceStrong
                    )
                )
            }

            if (isEnabled && isScheduled) {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = NewaxTheme.colors.border)
                Spacer(Modifier.height(12.dp))

                // Last run + next run
                val lastRunText = if (lastRunMs == 0L) stringResource(R.string.learn_never_run)
                else stringResource(R.string.learn_last, relativeTime(context, lastRunMs))
                val nextRunText = if (lastRunMs == 0L) stringResource(R.string.learn_running_soon)
                else stringResource(R.string.learn_next, relativeTime(context, lastRunMs + currentInterval * 60_000L))

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
                        .background(NewaxTheme.colors.surfaceMuted)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        SOURCE_ICONS[currentSource] ?: Icons.Outlined.Radar,
                        contentDescription = null,
                        tint = NewaxTheme.colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.learn_next_batch), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
                    Spacer(Modifier.width(4.dp))
                    Text(currentSource.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.learn_offset, ScanProgress.getOffset(currentSource)),
                        fontSize = 11.sp, color = NewaxTheme.colors.textTertiary
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textSecondary),
                    shape  = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.learn_run_batch), fontSize = 14.sp)
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── Statistics card ───────────────────────────────────────────────────────
    LearnLabel(stringResource(R.string.learn_section_stats))
    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox(stringResource(R.string.learn_stat_created), "$totalCreated", Icons.Outlined.AutoAwesome, Color(0xFF6366F1), Modifier.weight(1f))
                StatBox(stringResource(R.string.learn_stat_pending), "${draftStats.pending}", Icons.Outlined.Pending, NewaxTheme.colors.warning, Modifier.weight(1f))
                StatBox(stringResource(R.string.learn_stat_saved), "${draftStats.approved}", Icons.Outlined.CheckCircle, NewaxTheme.colors.success, Modifier.weight(1f))
                StatBox(stringResource(R.string.learn_stat_skipped), "${draftStats.rejected}", Icons.Outlined.Cancel, NewaxTheme.colors.textTertiary, Modifier.weight(1f))
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── Scan interval ─────────────────────────────────────────────────────────
    LearnLabel(stringResource(R.string.learn_section_interval))
    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.learn_interval_desc),
                fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp
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
                        label  = { Text(stringResource(R.string.learn_interval_min, min), fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NewaxTheme.colors.textPrimary,
                            selectedLabelColor     = Color.White
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = selected,
                            borderColor = NewaxTheme.colors.border, selectedBorderColor = Color.Transparent
                        )
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── Data sources ──────────────────────────────────────────────────────────
    LearnLabel(stringResource(R.string.learn_section_sources))
    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
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
                            .background(if (srcEnabled) NewaxTheme.colors.surfaceMuted else NewaxTheme.colors.surfaceStrong),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            SOURCE_ICONS[source] ?: Icons.Outlined.Storage,
                            contentDescription = null,
                            tint     = if (srcEnabled) NewaxTheme.colors.textSecondary else NewaxTheme.colors.textTertiary,
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
                                color      = if (srcEnabled) NewaxTheme.colors.textPrimary else NewaxTheme.colors.textTertiary
                            )
                            if (isCurrent && srcEnabled) {
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(NewaxTheme.colors.successFill)
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(stringResource(R.string.learn_next_badge), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.success)
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            buildString {
                                if (!srcEnabled) { append(context.getString(R.string.learn_source_disabled)); return@buildString }
                                if (lastSeenMs > 0L) append(context.getString(R.string.learn_last_seen, relativeTime(context, lastSeenMs)))
                                else append(context.getString(R.string.learn_not_scanned))
                                if (offset > 0) append(context.getString(R.string.learn_scanned_count, offset))
                                append(context.getString(R.string.learn_batch_size, source.batchSize))
                            },
                            fontSize = 11.sp,
                            color    = NewaxTheme.colors.textTertiary,
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
                            checkedTrackColor   = NewaxTheme.colors.textPrimary,
                            uncheckedThumbColor = Color(0xFF8D8D87),
                            uncheckedTrackColor = NewaxTheme.colors.surfaceStrong
                        ),
                        modifier = Modifier.size(height = 24.dp, width = 44.dp)
                    )
                }
                if (i < ScanSource.entries.lastIndex) {
                    HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // ── Danger zone ───────────────────────────────────────────────────────────
    LearnLabel(stringResource(R.string.learn_section_maintenance))
    Card(
        shape     = RoundedCornerShape(18.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
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
                Icon(Icons.Outlined.RestartAlt, contentDescription = null, tint = NewaxTheme.colors.warning, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.learn_reset_title), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.learn_reset_desc), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = NewaxTheme.colors.textTertiary, modifier = Modifier.size(18.dp))
            }

            HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = 16.dp))

            // Clear all drafts
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showClearDialog = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, tint = NewaxTheme.colors.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.learn_clear_title), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.learn_clear_desc), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
                }
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = NewaxTheme.colors.textTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor   = NewaxTheme.colors.surface,
            shape            = RoundedCornerShape(20.dp),
            title  = { Text(stringResource(R.string.learn_reset_dialog_title), fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textPrimary) },
            text   = {
                Text(
                    stringResource(R.string.learn_reset_dialog_body),
                    color = NewaxTheme.colors.textSecondary, lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        ScanProgress.resetAll()
                        refresh()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.warning)
                ) { Text(stringResource(R.string.action_reset), color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text(stringResource(R.string.action_cancel), color = NewaxTheme.colors.textSecondary) }
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor   = NewaxTheme.colors.surface,
            shape            = RoundedCornerShape(20.dp),
            title  = { Text(stringResource(R.string.learn_clear_dialog_title), fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textPrimary) },
            text   = {
                Text(
                    stringResource(R.string.learn_clear_dialog_body, draftStats.total),
                    color = NewaxTheme.colors.textSecondary, lineHeight = 20.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        DraftStore.clearOld(vm.db, keepPending = false)
                        vm.refreshDrafts()
                        refresh()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.error)
                ) { Text(stringResource(R.string.action_clear_all), color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.action_cancel), color = NewaxTheme.colors.textSecondary) }
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
        color      = NewaxTheme.colors.textTertiary,
        modifier   = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
    )
}

@Composable
private fun StatBox(label: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier            = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(NewaxTheme.colors.surfaceMuted)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = NewaxTheme.colors.textPrimary)
        Text(label, fontSize = 10.sp, color = NewaxTheme.colors.textTertiary)
    }
}

@Composable
private fun TimeChip(icon: ImageVector, text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(NewaxTheme.colors.surfaceMuted)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = NewaxTheme.colors.textSecondary, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp, color = NewaxTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Utility ───────────────────────────────────────────────────────────────────

private fun relativeTime(context: android.content.Context, ms: Long): String {
    if (ms <= 0L) return context.getString(R.string.learn_relative_never)
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 0              -> context.getString(R.string.learn_relative_just_now)
        diff < 60_000         -> context.getString(R.string.learn_relative_s_ago, diff / 1000)
        diff < 3_600_000      -> context.getString(R.string.learn_relative_m_ago, diff / 60_000)
        diff < 86_400_000     -> context.getString(R.string.learn_relative_h_ago, diff / 3_600_000)
        else                  -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(ms))
    }
}
