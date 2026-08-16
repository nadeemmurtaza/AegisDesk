package com.newax.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newax.aegis.ui.a11y.describedAs
import com.newax.aegis.ui.a11y.liveRegionPolite
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * The rest of the chat surface (docs/UI_DESIGN.md §8 — Chat): the composer,
 * the suggestion grid, and the chrome rows that will hang off routes 1.x.
 *
 * Every label is a parameter — the caller localizes; `commonMain` stays
 * string-free. Icons are restricted to the `material-icons-core` set, so this
 * module compiles without the extended set; platform-specific actions (voice)
 * arrive through the [Composer.leading] slot, never as an icon dependency.
 *
 * Accessibility contract (docs/UI_DESIGN.md §3.4):
 *  - the send control is a named icon button with a 44 dp target; the busy
 *    state replaces it with a described spinner — the state is announced, not
 *    implied by a colour swap;
 *  - suggestion chips meet the 44 dp floor and carry their own click labels;
 *  - unread state is announced as [stateDescription] on the row, never as the
 *    dot alone (SC 1.4.1);
 *  - [DegradedBanner] announces politely: it is a status, not a failure.
 */

/**
 * The composer bar: input + send (+ optional leading action such as voice).
 *
 * The send control becomes a described spinner while [busy] — the user hears
 * what is happening instead of watching a button change colour. Sending is
 * gated by [sendEnabled] (callers default it to `value.isNotBlank()`) and the
 * send action is never invoked while busy.
 *
 * @param leading the optional leading slot (mic, attach) — a full control the
 *   caller owns, including its label and touch target.
 * @param busyLabel the screen-reader description of the busy state ("Newax
 *   Aegis is working") — omitted when null.
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sendLabel: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    busyLabel: String? = null,
    sendEnabled: Boolean = value.isNotBlank(),
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = NewaxTheme.colors.bg,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier
                .padding(horizontal = NewaxTheme.spacing.lg, vertical = 10.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(NewaxTheme.colors.surface)
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(2.dp))
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        placeholder,
                        style = NewaxTheme.typography.body,
                        color = NewaxTheme.colors.textTertiary,
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    cursorColor = NewaxTheme.colors.textPrimary,
                ),
                textStyle = NewaxTheme.typography.body.copy(color = NewaxTheme.colors.textPrimary),
                singleLine = false,
                maxLines = 5,
            )
            if (busy) {
                Box(Modifier.size(NewaxTheme.spacing.minTouchTarget), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(22.dp)
                            .then(if (busyLabel != null) Modifier.describedAs(busyLabel) else Modifier),
                        strokeWidth = 2.dp,
                        color = NewaxTheme.colors.textSecondary,
                    )
                }
            } else {
                IconButton(
                    onClick = onSubmit,
                    enabled = sendEnabled,
                    modifier = Modifier.minimumTouchTarget(),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = sendLabel,
                        tint = if (sendEnabled) NewaxTheme.colors.textPrimary else NewaxTheme.colors.textTertiary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * A two-column grid of starter/suggestion chips, one per row on the emptiest
 * screens. Each chip is a real 44 dp target with its own click label; the
 * caller passes already-localized text and submits the same text it shows.
 */
@Composable
fun SuggestionGrid(
    suggestions: List<String>,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm),
    ) {
        suggestions.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm),
            ) {
                val first = row.getOrNull(0)
                val second = row.getOrNull(1)
                if (first != null) {
                    SuggestionChip(
                        onClick = { onSuggestion(first) },
                        label = { Text(first, style = NewaxTheme.typography.caption) },
                        modifier = Modifier
                            .weight(1f)
                            .minimumTouchTarget(),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = NewaxTheme.colors.border,
                        ),
                    )
                }
                if (second != null) {
                    SuggestionChip(
                        onClick = { onSuggestion(second) },
                        label = { Text(second, style = NewaxTheme.typography.caption) },
                        modifier = Modifier
                            .weight(1f)
                            .minimumTouchTarget(),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = NewaxTheme.colors.border,
                        ),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * A chat-list row (route 1.11 — the conversation list): snippet, time, unread.
 *
 * The unread state is announced as [stateDescription] on the row itself, never
 * as the accent dot alone (SC 1.4.1). [leading] is the avatar/icon slot; the
 * row is one focus stop with its own label.
 *
 * @param unreadStateLabel announced when [unread] ("Unread") — localized.
 */
@Composable
fun ConversationRow(
    title: String,
    timeLabel: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unread: Boolean = false,
    unreadStateLabel: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .minimumTouchTarget()
            .semantics {
                role = Role.Button
                if (unread && unreadStateLabel != null) stateDescription = unreadStateLabel
            }
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
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
                color = NewaxTheme.colors.textPrimary,
                fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (timeLabel != null) {
                Text(
                    timeLabel,
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.textTertiary,
                )
            }
        }
        if (unread) {
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(NewaxTheme.colors.accent),
            )
        }
    }
}

/**
 * A removable attachment chip (route 1.2 — composer attachments): the file
 * name and a named remove control with a 44 dp target.
 */
@Composable
fun AttachmentChip(
    label: String,
    removeLabel: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(NewaxTheme.shapes.pill)
            .background(NewaxTheme.colors.surfaceSelected)
            .padding(start = NewaxTheme.spacing.md, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = NewaxTheme.typography.caption,
            color = NewaxTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(
            onClick = onRemove,
            modifier = Modifier.minimumTouchTarget(),
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = removeLabel,
                tint = NewaxTheme.colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Model name + state chip (the model sheet's trigger line): the state is
 * [StatusChip] word + colour, never colour alone. Clickable when [onOpen] is
 * provided, with an optional visible affordance ([openLabel]) alongside.
 */
@Composable
fun ModelStatusLine(
    modelName: String,
    stateLabel: String,
    stateColor: Color,
    modifier: Modifier = Modifier,
    stateFill: Color? = null,
    openLabel: String? = null,
    onOpen: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(
                if (onOpen != null) {
                    Modifier
                        .minimumTouchTarget()
                        .semantics { role = Role.Button }
                        .clickable(onClick = onOpen)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modelName,
            style = NewaxTheme.typography.body,
            color = NewaxTheme.colors.textPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (openLabel != null && onOpen != null) {
            Text(
                openLabel,
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.accent,
            )
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
        }
        StatusChip(stateLabel, stateColor, fill = stateFill)
    }
}

/**
 * The degraded-mode banner: a neutral-but-visible status ("Offline — responses
 * come from the fallback engine") on the warning fill. Announces politely —
 * it is a status the user should hear at a pause, not a failure that
 * interrupts (docs/UI_DESIGN.md §3.4).
 */
@Composable
fun DegradedBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.warningFill)
            .padding(horizontal = NewaxTheme.spacing.md, vertical = NewaxTheme.spacing.sm)
            .liveRegionPolite(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Warning,
            contentDescription = null,
            tint = NewaxTheme.colors.warning,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(NewaxTheme.spacing.sm))
        Text(
            message,
            style = NewaxTheme.typography.caption,
            color = NewaxTheme.colors.warning,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(actionLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.warning) }
        }
    }
}
