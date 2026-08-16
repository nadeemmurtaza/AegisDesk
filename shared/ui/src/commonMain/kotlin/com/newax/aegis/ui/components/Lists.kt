package com.newax.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import com.newax.aegis.ui.a11y.heading
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.a11y.statusSemantics
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * Shared list & data components (docs/UI_DESIGN.md §8 — Lists & data).
 *
 * Accessibility contract (docs/UI_DESIGN.md §3.4):
 *  - section headers are navigable via `heading()`;
 *  - expand/collapse state lives on the control as a `stateDescription`, never
 *    on the chevron glyph (the glyph stays decorative, `contentDescription =
 *    null`) — swapping an icon conveys nothing to a screen reader;
 *  - status chips pair a word with their colour (`statusSemantics`) — colour
 *    is never the only signal (SC 1.4.1);
 *  - interactive rows and buttons meet the 44 dp floor.
 */

/**
 * A section header, navigable by heading (SC 1.4.1 + TalkBack heading
 * navigation). Replaces the private `SectionLabel*` composables every screen
 * defined for itself.
 *
 * @param trailing an optional action anchored to the end of the header row —
 *   "See all", a refresh button, a count.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .heading()
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = NewaxTheme.typography.caption,
            color = NewaxTheme.colors.textTertiary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * A row that expands/collapses something below it.
 *
 * The expand state is announced as [stateDescription] **on the row** (the
 * control), per docs/UI_DESIGN.md §3.4 — the chevron glyph is decorative and
 * stays silent. The row declares its [Role.Button] because it is a custom
 * clickable, and enforces the 44 dp touch floor.
 *
 * @param stateLabel the state in the user's words, e.g. "Expanded" /
 *   "Collapsed" — passed by the caller so it can be localized.
 */
@Composable
fun ChevronRow(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    stateLabel: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .minimumTouchTarget()
            .semantics {
                role = Role.Button
                stateDescription = stateLabel
            }
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(NewaxTheme.spacing.md))
        }
        Text(
            title,
            style = NewaxTheme.typography.body,
            color = NewaxTheme.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (secondary != null) {
            Text(
                secondary,
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.textTertiary,
            )
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = NewaxTheme.colors.textTertiary,
            modifier = Modifier
                .size(20.dp)
                .rotate(if (expanded) 90f else 0f),
        )
    }
}

/**
 * A status pill: word + colour, announced to screen readers via
 * [statusSemantics] so the colour is never the only signal (SC 1.4.1).
 *
 * Display-only — a screen reader gets the label as its state description; the
 * surrounding text carries the rest. Interactive variants are built at the call
 * site by wrapping with `clickable` + [minimumTouchTarget], because the 44 dp
 * floor applies to touch targets, not to every chip on the screen.
 *
 * @param color the semantic colour — error/success/warning/info/textTertiary
 *   from the token palette, never a raw hex at the call site.
 * @param fill the chip's background; defaults to a 12% tint of [color], which
 *   every token foreground clears against in both themes (ContrastTest).
 */
@Composable
fun StatusChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    fill: Color? = null,
) {
    Box(
        modifier
            .statusSemantics(label)
            .clip(NewaxTheme.shapes.pill)
            .background(fill ?: color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = NewaxTheme.typography.caption,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

/**
 * A small neutral tag — category labels, priority, "Offline", "sandbox".
 * Recessed `surfaceMuted` fill, coloured text; display-only.
 */
@Composable
fun InfoTag(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(NewaxTheme.shapes.pill)
            .background(NewaxTheme.colors.surfaceMuted)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = NewaxTheme.typography.caption,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

/**
 * A timeline row: status dot · title (+ time) · optional body. The dot is
 * decorative and paired with the title text, so colour is never the only
 * signal; the caller supplies the colour from the token palette.
 */
@Composable
fun TimelineItem(
    dotColor: Color,
    title: String,
    modifier: Modifier = Modifier,
    time: String? = null,
    body: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(NewaxTheme.spacing.md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = NewaxTheme.typography.body,
                    color = NewaxTheme.colors.textPrimary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (time != null) {
                    Text(
                        time,
                        style = NewaxTheme.typography.caption,
                        color = NewaxTheme.colors.textTertiary,
                    )
                }
            }
            if (body != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.textSecondary,
                )
            }
        }
    }
}

/**
 * The search input: leading search icon (decorative — the field is the
 * control), a trailing clear button that appears once there is something to
 * clear. The clear button is a real icon button: 44 dp target, named by
 * [clearLabel] (localized by the caller).
 */
@Composable
fun SearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(
                placeholder,
                style = NewaxTheme.typography.body,
                color = NewaxTheme.colors.textTertiary,
            )
        },
        singleLine = true,
        leadingIcon = {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = NewaxTheme.colors.textSecondary,
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.minimumTouchTarget(),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = clearLabel,
                        tint = NewaxTheme.colors.textSecondary,
                    )
                }
            }
        },
        shape = NewaxTheme.shapes.card,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NewaxTheme.colors.textSecondary,
            unfocusedBorderColor = NewaxTheme.colors.borderStrong,
            focusedContainerColor = NewaxTheme.colors.surface,
            unfocusedContainerColor = NewaxTheme.colors.surface,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}
