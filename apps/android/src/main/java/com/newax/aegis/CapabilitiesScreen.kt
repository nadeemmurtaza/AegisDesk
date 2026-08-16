package com.newax.aegis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newax.aegis.model.ModelFormat
import com.newax.aegis.model.ModelState
import com.newax.aegis.platform.CapabilityId
import com.newax.aegis.platform.CapabilityStatus
import com.newax.aegis.platform.PlatformCapability
import com.newax.aegis.platform.PrivilegeLevel
import com.newax.aegis.ui.a11y.statusSemantics
import com.newax.aegis.ui.components.EmptyState
import com.newax.aegis.ui.components.ErrorState
import com.newax.aegis.ui.components.InfoTag
import com.newax.aegis.ui.components.StatusChip as NewaxStatusChip
import com.newax.aegis.ui.theme.NewaxTheme
import kotlinx.coroutines.delay

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

@Composable
private fun statusColor(status: CapabilityStatus): Color = when (status) {
    CapabilityStatus.READY              -> NewaxTheme.colors.success
    CapabilityStatus.MISSING_PERMISSION -> NewaxTheme.colors.warning
    CapabilityStatus.MISSING_CREDENTIAL -> NewaxTheme.colors.warning
    CapabilityStatus.DISABLED           -> NewaxTheme.colors.textTertiary
    CapabilityStatus.UNAVAILABLE        -> NewaxTheme.colors.error
    CapabilityStatus.NOT_SUPPORTED      -> NewaxTheme.colors.textTertiary
}

private fun CapabilityStatus.label(): String =
    name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

private fun PrivilegeLevel.labelRes(): Int = when (this) {
    PrivilegeLevel.READ_ONLY          -> R.string.privilege_read_only
    PrivilegeLevel.STANDARD           -> R.string.privilege_standard
    PrivilegeLevel.HIGH_IMPACT_SYSTEM -> R.string.privilege_high_impact
    PrivilegeLevel.CRITICAL           -> R.string.privilege_critical
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
fun CapabilitiesScreen(
    padding: PaddingValues,
    /**
     * One-shot jump to the Policy modes section (the last list item). The Goals
     * screen bumps this when a policy-blocked task asks for the fix; the signal
     * resets itself via [onScrollHandled] so a later manual visit doesn't re-scroll.
     */
    policyScrollSignal: Int = 0,
    onScrollHandled: () -> Unit = {},
    /** Opens the full policy-decision history screen (See all on the audit card). */
    onOpenPolicyHistory: () -> Unit = {},
    /**
     * Action class to jump to (policy-history "jump to row"): the list scrolls
     * to that class's PolicyRowCard and highlights it, then [onTargetHandled]
     * consumes the signal (a small delay keeps the highlight visible).
     */
    policyScrollTarget: String? = null,
    onTargetHandled: () -> Unit = {},
    /** Route 4.1 item 3 — the Apps index (4.3) opens from here. */
    onOpenAppsIndex: () -> Unit = {},
    /** Route 4.2 remedies — the app's Permissions screen (5.6.1). */
    onOpenAppPermissions: () -> Unit = {},
    /** Route 4.2 remedies — the Settings page (5). */
    onOpenSettings: () -> Unit = {}
) {
    var refreshKey by remember { mutableStateOf(0) }
    var policyVersion by remember { mutableIntStateOf(0) }
    val rows = remember(refreshKey) { readSnapshot() }
    val listState = rememberLazyListState()

    // Items before the policy section: header card (0), model card (1), then one
    // item per capability row (or the single un-initialized/empty state box).
    val policySectionStart = 2 + (rows?.size ?: 1)

    LaunchedEffect(policyScrollSignal) {
        if (policyScrollSignal > 0 && listState.layoutInfo.totalItemsCount > 0) {
            listState.animateScrollToItem(
                policySectionStart.coerceAtMost(listState.layoutInfo.totalItemsCount - 1)
            )
            onScrollHandled()
        }
    }

    LaunchedEffect(policyScrollTarget) {
        if (policyScrollTarget != null && listState.layoutInfo.totalItemsCount > 0) {
            val pos = policyClassPosition(policyScrollTarget!!)
            val index = if (pos != null) policySectionStart + 1 + pos else policySectionStart
            listState.animateScrollToItem(index.coerceAtMost(listState.layoutInfo.totalItemsCount - 1))
            // Keep the amber highlight visible briefly, then consume the signal.
            delay(2500)
            onTargetHandled()
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        state               = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding      = PaddingValues(vertical = 12.dp)
    ) {
        // ── Header / summary ───────────────────────────────────────────────
        item {
            Card(
                shape     = RoundedCornerShape(18.dp),
                colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (rows == null) stringResource(R.string.nav_capabilities)
                            else stringResource(R.string.capabilities_count, rows.size),
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 15.sp,
                            color      = NewaxTheme.colors.textPrimary
                        )
                        Spacer(Modifier.height(2.dp))
                        val ready = rows?.count { it.status == CapabilityStatus.READY } ?: 0
                        Text(
                            when {
                                rows == null -> stringResource(R.string.capabilities_not_initialized_status)
                                ready == rows.size -> stringResource(R.string.capabilities_all_ready)
                                else -> stringResource(R.string.capabilities_ready_status, ready, rows.size - ready)
                            },
                            fontSize = 13.sp,
                            color    = NewaxTheme.colors.textSecondary
                        )
                    }
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_status),
                            tint = NewaxTheme.colors.textSecondary
                        )
                    }
                }
            }
        }

        // ── Model provider (shared/model-api contract, Phase 5b) ────────────
        item { ModelProviderCard() }

        // ── Route 4.3 — Apps index ─────────────────────────────────────────
        item {
            Card(
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenAppsIndex)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.capabilities_apps_row), fontWeight = FontWeight.Medium, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                        Text(stringResource(R.string.capabilities_apps_desc), fontSize = 12.sp, color = NewaxTheme.colors.textSecondary)
                    }
                    Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = NewaxTheme.colors.textSecondary)
                }
            }
        }

        // ── States ─────────────────────────────────────────────────────────
        when {
            // T3.4: the shared error surface — a registry that never
            // initialized is a failure state, announced assertively.
            rows == null -> item {
                ErrorState(
                    title   = stringResource(R.string.capabilities_not_initialized),
                    message = stringResource(R.string.capabilities_restart),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 56.dp)
                )
            }
            rows.isEmpty() -> item {
                EmptyState(
                    title   = stringResource(R.string.capabilities_empty),
                    icon    = Icons.Rounded.Shield,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 56.dp)
                )
            }
            else -> items(rows, key = { it.id.name }) {
                // Route 4.2 — each capability card expands into its detail:
                // what it enables (the description), the backing adapter, the
                // remedy for a non-operational status, and a retry that
                // re-reads the registry snapshot.
                CapabilityCard(
                    row = it,
                    onRetry = { refreshKey++ },
                    onOpenAppPermissions = onOpenAppPermissions,
                    onOpenSettings = onOpenSettings
                )
            }
        }

        // ── Policy settings — authority spine (Track A2) ────────────────────
        policySectionItems(
            policyVersion = policyVersion,
            onPolicyChanged = { policyVersion++ },
            highlightedClass = policyScrollTarget,
            onOpenPolicyHistory = onOpenPolicyHistory
        )
    }
}

@Composable
private fun CapabilityCard(
    row: CapabilityRow,
    onRetry: () -> Unit,
    onOpenAppPermissions: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var expanded by remember(row.id) { mutableStateOf(false) }
    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    // SC 4.1.2: the expand/collapse state lives on the control,
                    // never only in the chevron glyph (same pattern as the
                    // memory category cards).
                    .statusSemantics(if (expanded) stringResource(R.string.a11y_expanded) else stringResource(R.string.a11y_collapsed)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(statusColor(row.status))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.displayName, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                    Text(row.id.name, fontSize = 11.sp, color = NewaxTheme.colors.textTertiary, fontFamily = FontFamily.Monospace)
                }
                StatusChip(row.status)
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = NewaxTheme.colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(row.description, fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp)

            if (row.requiredPermission != null || row.requiredCredentialKey != null) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.requiredPermission?.let { MetaRow(stringResource(R.string.capabilities_meta_permission), it) }
                    row.requiredCredentialKey?.let { MetaRow(stringResource(R.string.capabilities_meta_credential), it) }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(stringResource(R.string.capabilities_tag_privilege, stringResource(row.privilegeLevel.labelRes())), NewaxTheme.colors.textSecondary)
                if (row.offline) Tag(stringResource(R.string.capabilities_tag_offline), NewaxTheme.colors.success)
            }

            if (expanded) {
                // Route 4.2 — the expanded detail: what it enables (above),
                // the remedy for a non-operational status, and a retry that
                // re-reads the registry snapshot. Remedies point at real
                // destinations — the Permissions screen for a missing platform
                // grant, the Settings page for a missing credential.
                HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(top = 12.dp, bottom = 10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.capabilities_remedy_title), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textTertiary)
                    when (row.status) {
                        CapabilityStatus.MISSING_PERMISSION -> {
                            Text(stringResource(R.string.capabilities_remedy_permission_body), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp)
                            Button(
                                onClick  = onOpenAppPermissions,
                                modifier = Modifier.fillMaxWidth(),
                                colors   = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary)
                            ) { Text(stringResource(R.string.capabilities_remedy_permission), fontSize = 14.sp) }
                        }
                        CapabilityStatus.MISSING_CREDENTIAL -> {
                            Text(stringResource(R.string.capabilities_remedy_credential_body), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp)
                            Button(
                                onClick  = onOpenSettings,
                                modifier = Modifier.fillMaxWidth(),
                                colors   = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary)
                            ) { Text(stringResource(R.string.capabilities_remedy_credential), fontSize = 14.sp) }
                        }
                        else -> {
                            Text(stringResource(R.string.capabilities_remedy_operational), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp)
                        }
                    }
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.fillMaxWidth(),
                        border   = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = NewaxTheme.colors.textSecondary)
                    ) { Text(stringResource(R.string.capabilities_retry), fontSize = 14.sp) }
                }
            }
        }
    }
}

// ── Model provider card ────────────────────────────────────────────────────

@Composable
private fun modelStateColor(state: ModelState): Color = when (state) {
    ModelState.READY         -> NewaxTheme.colors.success
    ModelState.LOADING       -> NewaxTheme.colors.warning
    ModelState.ERROR         -> NewaxTheme.colors.error
    ModelState.NOT_INSTALLED -> NewaxTheme.colors.textTertiary
    ModelState.CLOSED        -> NewaxTheme.colors.textTertiary
}

private fun ModelState.labelRes(): Int = when (this) {
    ModelState.NOT_INSTALLED -> R.string.capabilities_state_not_installed
    ModelState.LOADING       -> R.string.capabilities_state_loading
    ModelState.READY         -> R.string.capabilities_state_ready
    ModelState.ERROR         -> R.string.capabilities_state_error
    ModelState.CLOSED        -> R.string.capabilities_state_closed
}

private fun ModelFormat.labelRes(): Int = when (this) {
    ModelFormat.LITERTLM -> R.string.capabilities_format_litertlm
    ModelFormat.GGUF     -> R.string.capabilities_format_gguf
    ModelFormat.UNKNOWN  -> R.string.capabilities_format_unknown
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "${"%.1f".format(bytes / 1_073_741_824.0)} GB"
    bytes >= 1_048_576     -> "${"%.0f".format(bytes / 1_048_576.0)} MB"
    bytes >= 1024          -> "${bytes / 1024} KB"
    else                   -> "$bytes B"
}

/**
 * The UI face of the shared model contract: live provider state (NOT_INSTALLED →
 * LOADING → READY/ERROR → CLOSED) plus the installed pack's descriptor. State is
 * reactive via the provider's StateFlow; the descriptor is static per provider.
 */
@Composable
private fun ModelProviderCard() {
    // Re-read on every recomposition (not remembered) so a swap to the LiteRT
    // provider is picked up; collectAsState re-collects when the flow changes.
    val provider = ModelProviderHolder.current()
    val state by provider.state.collectAsState()
    val d = provider.descriptor

    Card(
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
        border    = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(modelStateColor(state))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.capabilities_model_provider), fontWeight = FontWeight.Medium, fontSize = 15.sp, color = NewaxTheme.colors.textPrimary)
                    Text(stringResource(R.string.capabilities_model_desc), fontSize = 11.sp, color = NewaxTheme.colors.textTertiary, fontFamily = FontFamily.Monospace)
                }
                // T3.4: shared status pill — the model's state as a word +
                // colour, never colour alone.
                NewaxStatusChip(
                    label = stringResource(state.labelRes()),
                    color = modelStateColor(state)
                )
            }

            Spacer(Modifier.height(8.dp))
            when (state) {
                ModelState.NOT_INSTALLED -> Text(
                    stringResource(R.string.capabilities_empty_model),
                    fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp
                )
                ModelState.LOADING -> Text(
                    stringResource(R.string.capabilities_model_loading, d.modelName),
                    fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp
                )
                ModelState.READY -> Text(
                    stringResource(R.string.capabilities_model_ready, d.modelName),
                    fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp
                )
                ModelState.ERROR -> Text(
                    stringResource(R.string.capabilities_model_unavailable),
                    fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp
                )
                ModelState.CLOSED -> Text(
                    stringResource(R.string.capabilities_model_closed),
                    fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, lineHeight = 19.sp
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(stringResource(d.format.labelRes()), NewaxTheme.colors.textSecondary)
                if (d.sha256.isNotBlank()) Tag("sha256 ${d.sha256.take(10)}…", NewaxTheme.colors.textSecondary)
                if (d.sizeBytes > 0) Tag(formatBytes(d.sizeBytes), NewaxTheme.colors.textSecondary)
                if (d.modelName.isNotBlank() && state != ModelState.NOT_INSTALLED) Tag(d.modelName, NewaxTheme.colors.textSecondary)
            }
        }
    }
}

@Composable
private fun StatusChip(status: CapabilityStatus) {
    // T3.4: shared status pill.
    NewaxStatusChip(
        label = status.label(),
        color = statusColor(status)
    )
}

@Composable
private fun Tag(text: String, color: Color) {
    // T3.4: shared neutral tag.
    InfoTag(text = text, color = color)
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 12.sp, color = NewaxTheme.colors.textTertiary, modifier = Modifier.weight(0.35f))
        Text(
            value,
            fontSize = 12.sp,
            color    = NewaxTheme.colors.textSecondary,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.65f)
        )
    }
}
