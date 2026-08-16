package com.newax.aegis

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
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
import com.newax.aegis.ui.components.ChoiceChips
import com.newax.aegis.ui.components.SettingsGroup
import com.newax.aegis.ui.components.SettingsRow
import com.newax.aegis.ui.state.SettingsScreenState
import com.newax.aegis.ui.theme.NewaxTheme

// against them) and must not be translated; only their chip labels resolve
// through strings.xml.
private fun ambientModeLabelRes(mode: String): Int? = when (mode) {
    "Meeting" -> R.string.settings_ambient_meeting
    "Lecture" -> R.string.settings_ambient_lecture
    else -> null
}

// ── Settings Screen ───────────────────────────────────────────────────────────
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    padding: PaddingValues,
    modelLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    onAccessibility: () -> Unit,
    onNotifications: () -> Unit,
    onNavigateToBackup: () -> Unit = {},
    onNavigateToPeople: () -> Unit = {},
    onNavigateToAppPermissions: () -> Unit = {},
    onNavigateToSync: () -> Unit = {},
    onNavigateToPolicyHistory: () -> Unit = {},
    onNavigateToNearby: () -> Unit = {},
    onNavigateToUpdates: () -> Unit = {}
) {
    // Model-readiness and the ambient-mode toggle come from the plain-Kotlin
    // holder (T3.1) so the decisions are unit-tested.
    val settingsState = remember { SettingsScreenState() }
    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
    ) {
        // ── Automation section (all groups + 2FA) ─────────────────────────
        item { AutomationSettingsSection(vm) }
        item { Spacer(Modifier.height(4.dp)) }

        // ── Self-Learning engine ───────────────────────────────────────────
        item { LearningSettingsSection(vm) }
        item { Spacer(Modifier.height(4.dp)) }

        item { SectionLabel(stringResource(R.string.settings_section_offline_ai_model)) }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isReady = settingsState.isModelReady(vm.modelStatus)
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (isReady) Color(0xFF22C55E) else Color(0xFF94A3B8)))
                        Spacer(Modifier.width(10.dp))
                        Text(vm.modelStatus, fontSize = 14.sp, color = NewaxTheme.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (vm.modelBusy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = NewaxTheme.colors.textSecondary)
                    }
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick  = { modelLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                        enabled  = !vm.modelBusy,
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.buttonColors(containerColor = NewaxTheme.colors.textPrimary)
                    ) { Text(stringResource(R.string.settings_import_model), fontSize = 14.sp) }
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel(stringResource(R.string.settings_section_ambient_mode)) }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_ambient_desc), fontSize = 13.sp, color = NewaxTheme.colors.textSecondary)
                    Spacer(Modifier.height(12.dp))
                    val currentMode = com.newax.aegis.voice.VoiceRecognitionService.ambientMode
                    // Storage key stays English; only the shown label localizes.
                    val labelOf: (String) -> String = { mode -> ambientModeLabelRes(mode)?.let { stringResource(it) } ?: mode }
                    val modeOf: (String) -> String = { label ->
                        settingsState.ambientModes.firstOrNull { labelOf(it) == label } ?: label
                    }
                    // T3.4c: the shared single-select chips (docs/UI_DESIGN.md §8 — ChoiceChips).
                    ChoiceChips(
                        options  = settingsState.ambientModes.map(labelOf),
                        selected = labelOf(currentMode),
                        onSelect = { label ->
                            val next = settingsState.ambientToggle(currentMode, modeOf(label))
                            if (next == null) com.newax.aegis.voice.VoiceRecognitionService.endAmbientMode()
                            else com.newax.aegis.voice.VoiceRecognitionService.ambientMode = next
                        }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel(stringResource(R.string.settings_section_permissions)) }
        item {
            // T3.4c: the shared settings group + rows (docs/UI_DESIGN.md §8 —
            // SettingsGroup/SettingsRow) — one focus stop per row, chevron
            // decorative, 44 dp targets.
            SettingsGroup {
                SettingsRow(stringResource(R.string.settings_perm_screen_access), subtitle = stringResource(R.string.settings_perm_screen_subtitle), onClick = onAccessibility)
                HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsRow(stringResource(R.string.settings_perm_inbox_access), subtitle = stringResource(R.string.settings_perm_inbox_subtitle), onClick = onNotifications)
                HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsRow(stringResource(R.string.settings_perm_apps), subtitle = stringResource(R.string.settings_perm_apps_subtitle), onClick = onNavigateToAppPermissions)
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel(stringResource(R.string.settings_section_people)) }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                SettingsRow(
                    title    = stringResource(R.string.settings_people_title),
                    subtitle = stringResource(R.string.settings_people_subtitle),
                    onClick  = onNavigateToPeople,
                    leading  = {
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFEDE9FE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Person, contentDescription = null, tint = Color(0xFF7C3AED), modifier = Modifier.size(20.dp))
                        }
                    }
                )
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel(stringResource(R.string.settings_section_device_sync)) }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                SettingsRow(
                    title    = stringResource(R.string.settings_sync_title),
                    subtitle = stringResource(R.string.settings_sync_subtitle),
                    onClick  = onNavigateToSync,
                    leading  = {
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFDCFCE7)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Sync, contentDescription = null, tint = Color(0xFF16A34A), modifier = Modifier.size(20.dp))
                        }
                    }
                )
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel(stringResource(R.string.settings_section_backup)) }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                SettingsRow(
                    title    = stringResource(R.string.settings_backup_title),
                    subtitle = stringResource(R.string.settings_backup_subtitle),
                    onClick  = onNavigateToBackup,
                    leading  = {
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFDBEAFE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.CloudSync, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(20.dp))
                        }
                    }
                )
            }
        }

        // T3.5d — the settings sub-routes that left the drawer (the compact-IA
        // rework) live here per the tree (5.3.1.3, 5.4.2, 5.6.2): Safety &
        // Privacy and System sections, same Card + SettingsRow pattern as the
        // people/sync/backup rows above.
        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel(stringResource(R.string.settings_section_safety)) }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                SettingsRow(
                    title    = stringResource(R.string.screen_title_policy_history),
                    subtitle = stringResource(R.string.policy_summary_subtitle),
                    onClick  = onNavigateToPolicyHistory,
                    leading  = {
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFFEE2E2)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Shield, contentDescription = null, tint = Color(0xFFDC2626), modifier = Modifier.size(20.dp))
                        }
                    }
                )
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel(stringResource(R.string.settings_section_system)) }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                SettingsRow(
                    title   = stringResource(R.string.screen_title_nearby_share),
                    onClick = onNavigateToNearby,
                    leading = {
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFE0F2FE)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.NearMe, contentDescription = null, tint = Color(0xFF0284C7), modifier = Modifier.size(20.dp))
                        }
                    }
                )
                HorizontalDivider(color = NewaxTheme.colors.border, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsRow(
                    title   = stringResource(R.string.screen_title_pending_updates),
                    onClick = onNavigateToUpdates,
                    leading = {
                        Box(
                            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Color(0xFFFEF3C7)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.Notifications, contentDescription = null, tint = Color(0xFFD97706), modifier = Modifier.size(20.dp))
                        }
                    }
                )
            }
        }

        item { Spacer(Modifier.height(4.dp)) }
        item { SectionLabel(stringResource(R.string.settings_section_about)) }
        item {
            Card(
                shape  = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = NewaxTheme.colors.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, NewaxTheme.colors.border),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow(stringResource(R.string.settings_about_version), stringResource(R.string.settings_about_version_value))
                    InfoRow(stringResource(R.string.settings_about_storage), stringResource(R.string.settings_about_storage_value))
                    InfoRow(stringResource(R.string.settings_about_network), stringResource(R.string.settings_about_network_value))
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize   = 11.sp,
        fontWeight = FontWeight.Medium,
        color      = NewaxTheme.colors.textTertiary,
        modifier   = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = NewaxTheme.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = NewaxTheme.colors.textPrimary, fontFamily = FontFamily.Monospace)
    }
}
