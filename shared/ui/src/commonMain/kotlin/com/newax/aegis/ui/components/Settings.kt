package com.newax.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newax.aegis.ui.a11y.heading
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * The settings family (docs/UI_DESIGN.md §8 — Settings): groups of rows,
 * choice chips, a tag editor, the edit-value sheet, and the profile/device
 * rows. All labels are parameters — the caller localizes.
 *
 * Accessibility contract (docs/UI_DESIGN.md §3.4):
 *  - a [SettingsRow] is one focus stop with its own label; the chevron is
 *    decorative (`contentDescription = null`) — the row's name carries the
 *    meaning, and the label is announced by the caller;
 *  - [ChoiceChips] are real filter chips with their own selected state
 *    (Material announces selected/unselected) — colour is never the only
 *    signal;
 *  - every tag's remove control is a named 44 dp target.
 */

/**
 * A card that groups settings rows (docs/UI_DESIGN.md §8 — SettingsGroup).
 * The caller lays out [content] (typically [SettingsRow]s separated by
 * [HorizontalDivider]s); the group supplies the surface and the optional
 * heading, which is a navigable heading for screen-reader users.
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surface)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card),
    ) {
        if (title != null) {
            Text(
                title,
                style = NewaxTheme.typography.heading,
                color = NewaxTheme.colors.textPrimary,
                modifier = Modifier
                    .heading()
                    .padding(horizontal = NewaxTheme.spacing.lg, vertical = NewaxTheme.spacing.md),
            )
            HorizontalDivider(color = NewaxTheme.colors.border)
        }
        content()
    }
}

/**
 * One settings row (docs/UI_DESIGN.md §8 — SettingsRow): optional leading
 * slot, title (+ subtitle), and a trailing chevron when clickable. The
 * chevron is decorative — the row is one focus stop whose name is [title]
 * (the Material clickable provides the role).
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.minimumTouchTarget() else Modifier)
            .then(
                if (onClick != null) {
                    Modifier
                        .semantics { role = Role.Button }
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(NewaxTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(NewaxTheme.spacing.md))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = NewaxTheme.typography.body,
                fontWeight = FontWeight.Medium,
                color = NewaxTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = NewaxTheme.colors.textTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Single-select choice chips (docs/UI_DESIGN.md §8 — ChoiceChips): the
 * settings picker. The selected chip uses the token fills that clear 4.5:1 in
 * both themes ([surfaceStrong] + [textPrimary]); unselected chips are
 * [surfaceMuted] + [textSecondary].
 */
@Composable
fun ChoiceChips(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(option, style = NewaxTheme.typography.caption) },
                modifier = Modifier.minimumTouchTarget(),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = NewaxTheme.colors.surfaceMuted,
                    labelColor = NewaxTheme.colors.textSecondary,
                    selectedContainerColor = NewaxTheme.colors.surfaceStrong,
                    selectedLabelColor = NewaxTheme.colors.textPrimary,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = option == selected,
                    borderColor = NewaxTheme.colors.border,
                    selectedBorderColor = NewaxTheme.colors.borderStrong,
                ),
            )
        }
    }
}

/**
 * Parses a tag input for [TagEditor]: trims, drops blanks, dedupes
 * case-insensitively keeping the first spelling, and caps the result at
 * [maxTags]. Pure so the "what gets added" decision is unit-tested.
 */
fun tagsAfterAdd(input: String, tags: List<String>, maxTags: Int): List<String> {
    val cleaned = input.trim()
    if (cleaned.isEmpty()) return tags
    val existing = tags.map { it.lowercase() }
    val merged = tags.toMutableList()
    if (cleaned.lowercase() !in existing) merged.add(cleaned)
    return merged.take(maxTags.coerceAtLeast(0))
}

/**
 * The tag editor (docs/UI_DESIGN.md §8 — TagEditor): an input field with an
 * Add control, then the tags as removable chips. The add decision is
 * [tagsAfterAdd] (pure); each chip's remove control is a named 44 dp target.
 *
 * @param removeLabel per-tag remove label; the tag name is appended so the
 *   reader hears which tag is being removed.
 */
@Composable
fun TagEditor(
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
    inputPlaceholder: String,
    addLabel: String,
    removeLabel: String,
    modifier: Modifier = Modifier,
    maxTags: Int = Int.MAX_VALUE,
) {
    var input by remember { mutableStateOf("") }
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(inputPlaceholder, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textTertiary)
                },
                singleLine = true,
                shape = NewaxTheme.shapes.card,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NewaxTheme.colors.textSecondary,
                    unfocusedBorderColor = NewaxTheme.colors.borderStrong,
                    focusedContainerColor = NewaxTheme.colors.surface,
                    unfocusedContainerColor = NewaxTheme.colors.surface,
                ),
            )
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            TextButton(
                onClick = {
                    val next = tagsAfterAdd(input, tags, maxTags)
                    if (next != tags) {
                        onTagsChange(next)
                        input = ""
                    }
                },
                enabled = input.isNotBlank(),
                modifier = Modifier.minimumTouchTarget(),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = NewaxTheme.colors.textPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(addLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textPrimary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            tags.forEach { tag ->
                Row(
                    Modifier
                        .clip(NewaxTheme.shapes.pill)
                        .background(NewaxTheme.colors.surfaceSelected)
                        .padding(start = NewaxTheme.spacing.md, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tag, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(
                        onClick = { onTagsChange(tags.filterNot { it == tag }) },
                        modifier = Modifier.minimumTouchTarget(),
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "$removeLabel $tag",
                            tint = NewaxTheme.colors.textSecondary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The edit-value sheet (docs/UI_DESIGN.md §5.1.x): a [Sheet] with a single
 * text field and Save/Cancel. The save decision is the caller's — this
 * component only renders and reports.
 */
@Composable
fun EditValueSheet(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    fieldLabel: String,
    saveLabel: String,
    cancelLabel: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    saveEnabled: Boolean = true,
    supportingText: String? = null,
) {
    Sheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = title,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.md)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(fieldLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textTertiary) },
                singleLine = true,
                shape = NewaxTheme.shapes.card,
                supportingText = if (supportingText != null) {
                    { Text(supportingText, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textTertiary) }
                } else {
                    null
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NewaxTheme.colors.textSecondary,
                    unfocusedBorderColor = NewaxTheme.colors.borderStrong,
                    focusedContainerColor = NewaxTheme.colors.surface,
                    unfocusedContainerColor = NewaxTheme.colors.surface,
                ),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.minimumTouchTarget(),
                ) { Text(cancelLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary) }
                Spacer(Modifier.width(NewaxTheme.spacing.sm))
                TextButton(
                    onClick = onSave,
                    enabled = saveEnabled,
                    modifier = Modifier.minimumTouchTarget(),
                ) { Text(saveLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textPrimary) }
            }
        }
    }
}

/**
 * The profile header (docs/UI_DESIGN.md §8 — ProfileHeader): avatar slot,
 * name, subtitle, optional edit action. The avatar is decorative and silent —
 * the name is the header's meaning.
 */
@Composable
fun ProfileHeader(
    name: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    avatar: (@Composable () -> Unit)? = null,
    editLabel: String? = null,
    onEdit: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(NewaxTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (avatar != null) {
            avatar()
            Spacer(Modifier.width(NewaxTheme.spacing.md))
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = NewaxTheme.typography.heading, color = NewaxTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textSecondary)
            }
        }
        if (editLabel != null && onEdit != null) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier.minimumTouchTarget(),
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = editLabel, tint = NewaxTheme.colors.textSecondary)
            }
        }
    }
}

/**
 * This device's card (docs/UI_DESIGN.md §8 — DeviceCard): name, platform and
 * device-id lines, and a [StatusChip] state (the tier word + colour, never
 * colour alone). Optional action slot (sync now, refresh).
 */
@Composable
fun DeviceCard(
    name: String,
    stateLabel: String,
    stateColor: Color,
    modifier: Modifier = Modifier,
    platformLabel: String? = null,
    deviceIdLabel: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surface)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card)
            .padding(NewaxTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = NewaxTheme.typography.body, fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (platformLabel != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(platformLabel, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textTertiary)
                }
            }
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            StatusChip(stateLabel, stateColor)
        }
        if (deviceIdLabel != null) {
            Spacer(Modifier.height(4.dp))
            Text(deviceIdLabel, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textTertiary)
        }
        if (action != null) {
            Spacer(Modifier.height(NewaxTheme.spacing.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
        }
    }
}

/**
 * A paired device row (docs/UI_DESIGN.md §5.2.x — PairedDeviceRow): name,
 * last-seen line, status chip, and a trailing slot for the unpair/forget
 * control. The state word comes from the caller; the colour never stands
 * alone.
 */
@Composable
fun PairedDeviceRow(
    name: String,
    stateLabel: String,
    stateColor: Color,
    modifier: Modifier = Modifier,
    lastSeenLabel: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(stateColor),
        )
        Spacer(Modifier.width(NewaxTheme.spacing.md))
        Column(Modifier.weight(1f)) {
            Text(name, style = NewaxTheme.typography.body, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (lastSeenLabel != null) {
                Spacer(Modifier.height(2.dp))
                Text(lastSeenLabel, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textTertiary)
            }
        }
        Spacer(Modifier.width(NewaxTheme.spacing.sm))
        StatusChip(stateLabel, stateColor)
        if (trailing != null) {
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            trailing()
        }
    }
}
