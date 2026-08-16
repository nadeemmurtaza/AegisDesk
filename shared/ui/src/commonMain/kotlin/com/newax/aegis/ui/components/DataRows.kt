package com.newax.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * Shared list & data rows beyond the primitives in [Lists.kt]
 * (docs/UI_DESIGN.md §8 — Lists & data): the people/agent/skill/task rows and
 * the swipe-vs-overflow action pair.
 *
 * Every label is a parameter — the caller localizes; `commonMain` stays
 * string-free. The accessible-name helpers ([personRowAccessibleName]) exist
 * so a screen reader hears one focus stop per row, never a wall of text.
 */

/**
 * The row's full accessible name — name, detail and score, blank parts
 * dropped ("Ali Raza, 12 sources · 40 mentions, 78%").
 */
fun personRowAccessibleName(name: String, detailLabel: String, scoreLabel: String?): String =
    listOf(name, detailLabel, scoreLabel)
        .filter { it.isNotBlank() }
        .joinToString(", ")

/**
 * A people row (docs/UI_DESIGN.md §8 — PersonRow): avatar slot, name, detail
 * line, optional score chip, chevron. One focus stop with its own name —
 * never the avatar alone (SC 1.4.1).
 *
 * @param scoreLabel e.g. "78%" — shown as a [StatusChip] with [scoreColor];
 *   omitted when the caller has no score to show.
 */
@Composable
fun PersonRow(
    name: String,
    detailLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    scoreLabel: String? = null,
    scoreColor: Color? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .minimumTouchTarget()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surface)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card)
            .semantics(mergeDescendants = true) {
                contentDescription = personRowAccessibleName(name, detailLabel, scoreLabel)
                role = Role.Button
            }
            .clickable(onClick = onClick)
            .padding(NewaxTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(NewaxTheme.spacing.md))
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = NewaxTheme.typography.body,
                fontWeight = FontWeight.Medium,
                color = NewaxTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detailLabel.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detailLabel,
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (scoreLabel != null && scoreColor != null) {
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            StatusChip(scoreLabel, scoreColor)
        }
        Spacer(Modifier.width(NewaxTheme.spacing.sm))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = NewaxTheme.colors.textTertiary,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * A managed-agent card (docs/UI_DESIGN.md §8 — AgentCard): enable switch,
 * title + meta line, description, optional keywords, uninstall for imported
 * agents. The switch is its own control — the card is not a single tap target,
 * so the name and switch are announced separately.
 */
@Composable
fun AgentCard(
    title: String,
    metaLabel: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    tagsLabel: String? = null,
    uninstallLabel: String? = null,
    onUninstall: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(if (enabled) NewaxTheme.colors.surface else NewaxTheme.colors.surfaceMuted)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card)
            .padding(NewaxTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (enabled) NewaxTheme.colors.success else NewaxTheme.colors.textTertiary),
            )
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = NewaxTheme.typography.body,
                    fontWeight = FontWeight.SemiBold,
                    color = NewaxTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    metaLabel,
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = NewaxTheme.colors.textPrimary,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = NewaxTheme.colors.textTertiary,
                ),
            )
        }
        Spacer(Modifier.height(NewaxTheme.spacing.sm))
        Text(
            description,
            style = NewaxTheme.typography.caption,
            color = NewaxTheme.colors.textSecondary,
        )
        if (tagsLabel != null && tagsLabel.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                tagsLabel,
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (uninstallLabel != null && onUninstall != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onUninstall,
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(uninstallLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.error) }
        }
    }
}

/**
 * A skill row (docs/UI_DESIGN.md §8 — SkillRow): title, id line, optional
 * flag line, description, enable switch, uninstall for imported skills.
 * The flag colour is the caller's call (red when the skill demands approval
 * or a sandbox, neutral otherwise) — the component never decides semantics.
 */
@Composable
fun SkillRow(
    title: String,
    idLabel: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    flagsLabel: String? = null,
    flagsColor: Color? = null,
    uninstallLabel: String? = null,
    onUninstall: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(if (enabled) NewaxTheme.colors.surface else NewaxTheme.colors.surfaceMuted)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card)
            .padding(NewaxTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = NewaxTheme.typography.body,
                    fontWeight = FontWeight.SemiBold,
                    color = NewaxTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    idLabel,
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (flagsLabel != null && flagsLabel.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        flagsLabel,
                        style = NewaxTheme.typography.caption,
                        color = flagsColor ?: NewaxTheme.colors.textTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = NewaxTheme.colors.textPrimary,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = NewaxTheme.colors.textTertiary,
                ),
            )
        }
        Spacer(Modifier.height(NewaxTheme.spacing.sm))
        Text(
            description,
            style = NewaxTheme.typography.caption,
            color = NewaxTheme.colors.textSecondary,
        )
        if (uninstallLabel != null && onUninstall != null) {
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = onUninstall,
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(uninstallLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.error) }
        }
    }
}

/**
 * An amber task card (docs/UI_DESIGN.md §8 — AmberTaskCard): a goal task with
 * its state chip, meta line and action slot. "Amber" is the accent the task
 * rows use; the caller passes the state word + colour ([statusSemantics]
 * ensures the colour is never the only signal).
 */
@Composable
fun AmberTaskCard(
    title: String,
    stateLabel: String,
    stateColor: Color,
    modifier: Modifier = Modifier,
    metaLabel: String? = null,
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
            Icon(
                Icons.Rounded.Star,
                contentDescription = null,
                tint = NewaxTheme.colors.warning,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            Text(
                title,
                style = NewaxTheme.typography.body,
                fontWeight = FontWeight.Medium,
                color = NewaxTheme.colors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            StatusChip(stateLabel, stateColor)
        }
        if (metaLabel != null && metaLabel.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                metaLabel,
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.textTertiary,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(NewaxTheme.spacing.sm))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { action() }
        }
    }
}

/**
 * One swipe-to-act row (docs/UI_DESIGN.md §8 — ListSwipeActions): swiping
 * left reveals [actionLabel] on [actionColor] and firing it on the full swipe.
 * The row always snaps back — the action fires, the row stays — and is meant
 * to be **paired with an overflow equivalent** ([OverflowActions]) so the
 * same action is reachable without gesture discovery.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeActionRow(
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
    actionColor: Color = NewaxTheme.colors.error,
    actionIcon: ImageVector = Icons.Rounded.Delete,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onAction()
            false // always snap back — the action already fired
        },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(NewaxTheme.shapes.card)
                    .background(actionColor)
                    .padding(horizontal = NewaxTheme.spacing.lg),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        actionLabel,
                        style = NewaxTheme.typography.label,
                        fontWeight = FontWeight.SemiBold,
                        color = NewaxTheme.colors.surface,
                    )
                    Spacer(Modifier.width(NewaxTheme.spacing.sm))
                    Icon(actionIcon, contentDescription = null, tint = NewaxTheme.colors.surface)
                }
            }
        },
    ) { content() }
}

/** One overflow-menu action. */
data class OverflowAction(
    val label: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

/**
 * The overflow equivalent of [SwipeActionRow] (docs/UI_DESIGN.md §8): a
 * `More` button opening a [DropdownMenu] of the same actions, so every swipe
 * action stays reachable without gestures. Destructive actions render in
 * [NewaxTheme.colors.error].
 */
@Composable
fun OverflowActions(
    actions: List<OverflowAction>,
    moreLabel: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { open = true },
            modifier = Modifier.minimumTouchTarget(),
        ) {
            Icon(
                Icons.Rounded.MoreVert,
                contentDescription = moreLabel,
                tint = NewaxTheme.colors.textSecondary,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label,
                            style = NewaxTheme.typography.body,
                            color = if (action.destructive) NewaxTheme.colors.error else NewaxTheme.colors.textPrimary,
                        )
                    },
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
        }
    }
}
