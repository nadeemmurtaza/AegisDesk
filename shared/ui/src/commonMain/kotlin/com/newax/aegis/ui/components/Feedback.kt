package com.newax.aegis.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.newax.aegis.ui.a11y.liveRegionAssertive
import com.newax.aegis.ui.a11y.liveRegionPolite
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * Shared feedback surfaces (docs/UI_DESIGN.md §8 — Overlays).
 *
 * These are the components every screen currently hand-rolls as a private
 * Box/Column. One copy, in `commonMain`, used by all four bodies.
 *
 * Accessibility contract (docs/UI_DESIGN.md §3.4):
 *  - icons are decorative (`contentDescription = null`) unless they are the
 *    only label of a control — the text beside them carries the meaning;
 *  - failures announce assertively ([liveRegionAssertive]) — they interrupt;
 *    progress and streaming announce politely ([liveRegionPolite]);
 *  - every interactive element meets the 44 dp floor
 *    ([minimumTouchTarget]) and, where it is a custom clickable, declares its
 *    [Role] — see [TypeToConfirmDialog]'s confirm gate.
 */

/**
 * The empty surface: what a screen shows before there is anything to show.
 *
 * @param title what is missing, in the user's words ("No goals yet").
 * @param message an optional plain-language hint about how to change that.
 * @param icon a decorative illustration of the state — stays silent to screen
 *   readers; the title carries the meaning.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: ImageVector? = null,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = NewaxTheme.colors.textTertiary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(NewaxTheme.spacing.lg))
        }
        Text(
            title,
            style = NewaxTheme.typography.heading,
            color = NewaxTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(NewaxTheme.spacing.sm))
            Text(
                message,
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = NewaxTheme.spacing.xl),
            )
        }
    }
}

/**
 * A failure state: what a screen shows when something went wrong.
 *
 * The message announces **assertively** (docs/UI_DESIGN.md §3.4) — a failure
 * must interrupt whatever the user is reading, unlike progress which waits.
 * Colour is never the only signal: the icon and title carry the meaning, and
 * [retryLabel] + [onRetry] give the cause-and-remedy the spec requires
 * (SC 3.3.1/3.3.3) when a remedy exists.
 */@Composable
fun ErrorState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
    icon: ImageVector = Icons.Rounded.Warning,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = NewaxTheme.colors.error,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(NewaxTheme.spacing.md))
        Text(
            title,
            style = NewaxTheme.typography.heading,
            color = NewaxTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(NewaxTheme.spacing.sm))
            Text(
                message,
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .liveRegionAssertive()
                    .padding(horizontal = NewaxTheme.spacing.xl),
            )
        }
        if (retryLabel != null && onRetry != null) {
            Spacer(Modifier.height(NewaxTheme.spacing.lg))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = NewaxTheme.colors.textPrimary,
                    contentColor = NewaxTheme.colors.surface,
                ),
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(retryLabel) }
        }
    }
}

/**
 * An in-progress state: spinner + optional label, announced politely.
 *
 * [label] is optional because a spinner beside already-labelled content needs
 * no second announcement; when present it is a polite live region so progress
 * is announced without interrupting speech.
 */
@Composable
fun LoadingState(
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Row(
        modifier
            .liveRegionPolite(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.md),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = NewaxTheme.colors.textSecondary,
        )
        if (label != null) {
            Text(
                label,
                style = NewaxTheme.typography.caption,
                color = NewaxTheme.colors.textSecondary,
            )
        }
    }
}

/**
 * The standard destructive/confirm dialog (SC 3.3.4/3.3.6 — destructive
 * actions are confirmed before they run).
 *
 * @param destructive renders the confirm action in [NewaxTheme.colors.error].
 *   Callers choose the label: "Delete", "Clear all memory", "Replace all data".
 */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NewaxTheme.colors.surface,
        title = {
            Text(
                title,
                style = NewaxTheme.typography.heading,
                color = NewaxTheme.colors.textPrimary,
            )
        },
        text = {
            Text(
                body,
                style = NewaxTheme.typography.body,
                color = NewaxTheme.colors.textSecondary,
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.minimumTouchTarget(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (destructive) NewaxTheme.colors.error else NewaxTheme.colors.textPrimary,
                    contentColor = if (destructive) NewaxTheme.colors.surface else NewaxTheme.colors.surface,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(dismissLabel, color = NewaxTheme.colors.textSecondary) }
        },
        modifier = modifier,
    )
}

/**
 * The type-to-confirm variant of [ConfirmDialog]: the user must type [phrase]
 * before the confirm action enables. For the actions so destructive that a tap
 * on a button is not enough — replacing all data, erasing memory.
 *
 * The gate is [confirmPhraseMatches] (pure, unit-tested) so the "can this be
 * confirmed yet?" decision lives outside composition.
 */
@Composable
fun TypeToConfirmDialog(
    title: String,
    body: String,
    phrase: String,
    fieldLabel: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    var typed by remember { mutableStateOf("") }
    val enabled = confirmPhraseMatches(typed, phrase)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NewaxTheme.colors.surface,
        title = {
            Text(
                title,
                style = NewaxTheme.typography.heading,
                color = NewaxTheme.colors.textPrimary,
            )
        },
        text = {
            Column {
                Text(
                    body,
                    style = NewaxTheme.typography.body,
                    color = NewaxTheme.colors.textSecondary,
                )
                Spacer(Modifier.height(NewaxTheme.spacing.md))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(fieldLabel, style = NewaxTheme.typography.label) },
                    singleLine = true,
                    shape = NewaxTheme.shapes.card,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NewaxTheme.colors.textSecondary,
                        unfocusedBorderColor = NewaxTheme.colors.borderStrong,
                        focusedContainerColor = NewaxTheme.colors.surface,
                        unfocusedContainerColor = NewaxTheme.colors.surface,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = enabled,
                modifier = Modifier.minimumTouchTarget(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (destructive) NewaxTheme.colors.error else NewaxTheme.colors.textPrimary,
                    contentColor = if (destructive) NewaxTheme.colors.surface else NewaxTheme.colors.surface,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(dismissLabel, color = NewaxTheme.colors.textSecondary) }
        },
        modifier = modifier,
    )
}

/**
 * The type-to-confirm gate, as a pure function so it is testable without
 * Compose: confirm enables only when the phrase is non-blank AND the typed
 * text matches it (leading/trailing whitespace ignored on both sides).
 *
 * The blank-phrase guard matters: a caller that forgets to pass a phrase must
 * get a permanently disabled confirm, never a trivially confirmable one.
 */
fun confirmPhraseMatches(typed: String, phrase: String): Boolean =
    phrase.isNotBlank() && typed.trim() == phrase.trim()
