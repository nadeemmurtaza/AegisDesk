package com.newax.aegis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.newax.aegis.assistant.ProposedAction
import com.newax.aegis.engine.learning.LearningDraft
import com.newax.aegis.engine.learning.LearningWorker
import com.newax.aegis.engine.learning.ScanProgress
import kotlinx.coroutines.launch

// ── Design tokens (matches MainActivity palette) ──────────────────────────────
private val BG_D           = Color(0xFFF7F7F5)
private val Surface_D      = Color(0xFFFFFFFF)
private val SurfaceMuted_D = Color(0xFFF2F2EF)
private val SurfaceStr_D   = Color(0xFFE7E7E2)
private val Primary_D      = Color(0xFF1B1B1A)
private val TextPri_D      = Color(0xFF1B1B1A)
private val TextSec_D      = Color(0xFF686864)
private val TextTer_D      = Color(0xFF8D8D87)
private val Border_D       = Color(0xFFD8D8D3)

private fun categoryColor(cat: String): Color = when (cat.lowercase()) {
    "personal"  -> Color(0xFF3B82F6)
    "family"    -> Color(0xFFF97316)
    "work"      -> Color(0xFF8B5CF6)
    "health"    -> Color(0xFF22C55E)
    "finance"   -> Color(0xFFF59E0B)
    "events"    -> Color(0xFF14B8A6)
    "places"    -> Color(0xFF6366F1)
    "habits"    -> Color(0xFF78716C)
    "contacts"  -> Color(0xFF64748B)
    else        -> Color(0xFF94A3B8)
}

private fun categoryLabel(cat: String) = cat.replace('_', ' ')
    .split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

@Composable
fun DraftsScreen(vm: MainViewModel, padding: PaddingValues) {
    val context    = androidx.compose.ui.platform.LocalContext.current
    val scope      = rememberCoroutineScope()
    val drafts     by vm.pendingDrafts.collectAsStateWithLifecycle()
    val listState  = rememberLazyListState()

    val learnerRunning = remember { mutableStateOf(LearningWorker.isScheduled(context)) }
    var showApproveAll by remember { mutableStateOf(false) }
    var showRejectAll  by remember { mutableStateOf(false) }

    // Refresh drafts every time this screen is entered
    LaunchedEffect(Unit) { vm.refreshDrafts() }

    LazyColumn(
        modifier            = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        state               = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding      = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        // ── Status / control card ─────────────────────────────────────────────
        item {
            Card(
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = Surface_D),
                border    = androidx.compose.foundation.BorderStroke(1.dp, Border_D),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${drafts.size} pending draft${if (drafts.size != 1) "s" else ""}",
                            fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPri_D
                        )
                        Spacer(Modifier.height(2.dp))
                        val statusText = if (learnerRunning.value)
                            "Scanning every 20 min  •  ${ScanProgress.statusSummary()}"
                        else
                            "Self-learning is off"
                        Text(statusText, fontSize = 12.sp, color = TextSec_D, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    // Toggle learning on/off
                    val toggleColor by animateColorAsState(
                        if (learnerRunning.value) Color(0xFF22C55E) else SurfaceStr_D, label = "toggle"
                    )
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(toggleColor)
                            .padding(0.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = {
                                if (learnerRunning.value) {
                                    LearningWorker.cancel(context)
                                    learnerRunning.value = false
                                } else {
                                    LearningWorker.schedule(context)
                                    learnerRunning.value = true
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                if (learnerRunning.value) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (learnerRunning.value) "Pause learning" else "Start learning",
                                tint = if (learnerRunning.value) Color.White else TextSec_D,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                    // Scan now
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(SurfaceMuted_D),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = { LearningWorker.runOnce(context); vm.refreshDrafts() },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "Scan now",
                                tint = TextSec_D,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── Batch actions (only if drafts exist) ──────────────────────────────
        if (drafts.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick  = { showApproveAll = true },
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(containerColor = Primary_D),
                        shape    = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Approve all", fontSize = 14.sp)
                    }
                    OutlinedButton(
                        onClick  = { showRejectAll = true },
                        modifier = Modifier.weight(1f),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, Border_D),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = TextSec_D),
                        shape    = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reject all", fontSize = 14.sp)
                    }
                }
            }

            // ── Category filter chips ─────────────────────────────────────────
            val allCategories = remember(drafts) { drafts.map { it.category }.distinct().sorted() }
            var selectedCat   by remember { mutableStateOf<String?>(null) }

            if (allCategories.size > 1) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        allCategories.forEach { cat ->
                            val sel = selectedCat == cat
                            FilterChip(
                                selected = sel,
                                onClick  = { selectedCat = if (sel) null else cat },
                                label    = { Text(categoryLabel(cat), fontSize = 12.sp) },
                                colors   = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = categoryColor(cat),
                                    selectedLabelColor     = Color.White,
                                    selectedLeadingIconColor = Color.White
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = sel,
                                    borderColor = Border_D, selectedBorderColor = Color.Transparent
                                )
                            )
                        }
                    }
                }
            }

            // ── Draft cards ───────────────────────────────────────────────────
            val visible = remember(drafts, selectedCat) {
                if (selectedCat == null) drafts else drafts.filter { it.category == selectedCat }
            }

            items(visible, key = { it.id }) { draft ->
                AnimatedVisibility(
                    visible = true,
                    enter   = slideInVertically(tween(180)) { it / 4 } + fadeIn(tween(180)),
                    exit    = fadeOut(tween(120))
                ) {
                    DraftCard(
                        draft     = draft,
                        onApprove = {
                            scope.launch {
                                vm.submit("approve draft ${draft.id}")
                            }
                        },
                        onReject  = {
                            scope.launch {
                                vm.submit("reject draft ${draft.id}")
                            }
                        }
                    )
                }
            }
        } else {
            // ── Empty state ───────────────────────────────────────────────────
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Lightbulb,
                            contentDescription = null,
                            tint     = TextTer_D,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No pending drafts",
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = TextSec_D
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (learnerRunning.value)
                                "Aegis is scanning in the background.\nNew facts will appear here for your review."
                            else
                                "Press ▶ to start self-learning.\nAegis will scan your data and extract facts for approval.",
                            fontSize  = 13.sp,
                            color     = TextTer_D,
                            lineHeight = 20.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier  = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }
        }
    }

    // ── Confirm dialogs ───────────────────────────────────────────────────────
    if (showApproveAll) {
        AlertDialog(
            onDismissRequest = { showApproveAll = false },
            containerColor   = Surface_D,
            shape            = RoundedCornerShape(20.dp),
            title  = { Text("Approve all ${drafts.size} drafts?", fontWeight = FontWeight.SemiBold, color = TextPri_D) },
            text   = { Text("All facts will be saved to your encrypted memory.", color = TextSec_D) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { vm.submit("approve all drafts") }
                        showApproveAll = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary_D)
                ) { Text("Approve all") }
            },
            dismissButton = {
                TextButton(onClick = { showApproveAll = false }) { Text("Cancel", color = TextSec_D) }
            }
        )
    }

    if (showRejectAll) {
        AlertDialog(
            onDismissRequest = { showRejectAll = false },
            containerColor   = Surface_D,
            shape            = RoundedCornerShape(20.dp),
            title  = { Text("Reject all ${drafts.size} drafts?", fontWeight = FontWeight.SemiBold, color = TextPri_D) },
            text   = { Text("All pending drafts will be discarded. Nothing will be saved.", color = TextSec_D) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { vm.submit("reject all drafts") }
                        showRejectAll = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) { Text("Reject all", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showRejectAll = false }) { Text("Cancel", color = TextSec_D) }
            }
        )
    }
}

// ── Draft Card ────────────────────────────────────────────────────────────────
@Composable
private fun DraftCard(
    draft: LearningDraft,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    val catColor = categoryColor(draft.category)

    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Surface_D),
        border    = androidx.compose.foundation.BorderStroke(1.dp, Border_D),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier  = Modifier.fillMaxWidth()
    ) {
        Column {
            // ── Top stripe with category color + badges ───────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(catColor.copy(alpha = 0.06f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Category badge
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(catColor)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        categoryLabel(draft.category),
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = catColor
                    )
                }
                // Confidence pill
                val confPct = (draft.confidence * 100).toInt()
                val confColor = when {
                    confPct >= 80 -> Color(0xFF22C55E)
                    confPct >= 60 -> Color(0xFFF59E0B)
                    else          -> TextTer_D
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(confColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        "$confPct% confidence",
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color      = confColor
                    )
                }
            }

            // ── Fact text ─────────────────────────────────────────────────────
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    draft.fact,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color      = TextPri_D,
                    lineHeight = 22.sp
                )

                // Source
                Spacer(Modifier.height(6.dp))
                Text(
                    draft.source,
                    fontSize = 12.sp,
                    color    = TextSec_D
                )

                // Snippet (context excerpt)
                if (draft.sourceSnippet.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "\"${draft.sourceSnippet}\"",
                        fontSize   = 11.sp,
                        color      = TextTer_D,
                        fontStyle  = FontStyle.Italic,
                        maxLines   = 2,
                        overflow   = TextOverflow.Ellipsis
                    )
                }
            }

            // ── Action buttons ────────────────────────────────────────────────
            HorizontalDivider(color = Border_D)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                // Reject
                TextButton(
                    onClick = onReject,
                    colors  = ButtonDefaults.textButtonColors(contentColor = TextSec_D),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "Reject", modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reject", fontSize = 13.sp)
                }
                // Approve
                TextButton(
                    onClick = onApprove,
                    colors  = ButtonDefaults.textButtonColors(contentColor = Color(0xFF16A34A)),
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Icon(Icons.Rounded.CheckCircle, contentDescription = "Approve", modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Approve", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
