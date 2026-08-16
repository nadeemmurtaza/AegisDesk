package com.newax.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.newax.aegis.ui.a11y.liveRegionAssertive
import com.newax.aegis.ui.a11y.liveRegionPolite
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * Shared overlay surfaces (docs/UI_DESIGN.md §8 — Overlays): the bottom
 * [Sheet] and the [BiometricGate].
 *
 * Accessibility contract (docs/UI_DESIGN.md §3.4):
 *  - the scrim is a tap target but carries no semantics — the sheet's close
 *    control (when present) is the named control; the scrim is not read aloud;
 *  - the gate's status announces assertively on failure (it interrupts) and
 *    politely while authenticating/succeeding (it waits for a pause);
 *  - icons are decorative (`contentDescription = null`) where the text beside
 *    them carries the meaning; interactive elements meet the 44 dp floor.
 *
 * Back-dismiss of a [Sheet] is the platform's job (the `SystemBackHandler`
 * seam, docs/UI_DESIGN.md §9) and is deliberately left to the caller — this
 * module is platform-free.
 */

/**
 * A bottom sheet: scrim + grab handle + optional title row with a close
 * control, then [content]. The standard overlay for edit-value, enrollment,
 * and pairing surfaces — [EditValueSheet] and [VoiceEnrollSheet] are built on
 * it.
 *
 * @param onDismiss fired by the scrim — and by callers when the platform back
 *   button or an in-content cancel is pressed.
 * @param title optional sheet heading.
 * @param closeLabel names the close button; when null (with [onClose] also
 *   null) no close button is drawn — the caller supplies its own control in
 *   [content] (e.g. a "Cancel" text button).
 */
@Composable
fun Sheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    closeLabel: String? = null,
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        // Scrim: full-screen tap target, no semantics — the sheet's controls
        // are the named ones (docs/UI_DESIGN.md §3.4).
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(NewaxTheme.shapes.sheet)
                .background(NewaxTheme.colors.surface),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = NewaxTheme.spacing.sm)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(NewaxTheme.shapes.pill)
                    .background(NewaxTheme.colors.borderStrong),
            )
            if (title != null || onClose != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NewaxTheme.spacing.lg, vertical = NewaxTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (title != null) {
                        Text(
                            title,
                            style = NewaxTheme.typography.heading,
                            color = NewaxTheme.colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (onClose != null) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.minimumTouchTarget(),
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = closeLabel,
                                tint = NewaxTheme.colors.textSecondary,
                            )
                        }
                    }
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = NewaxTheme.spacing.lg, end = NewaxTheme.spacing.lg, bottom = NewaxTheme.spacing.lg),
                content = content,
            )
        }
    }
}

/** The lifecycle of a biometric confirmation (docs/UI_DESIGN.md §8). */
enum class BiometricGatePhase { IDLE, AUTHENTICATING, SUCCESS, FAILED }

/**
 * The in-app biometric gate: the overlay shown while a `STRONG_CONFIRMATION`
 * action awaits the platform prompt (docs/UI_DESIGN.md §8 — Overlays).
 *
 * This component renders the *gate UX* — status, retry, cancel. It does **not**
 * invoke the platform biometric prompt: that is the `BiometricPrompt` platform
 * seam (docs/UI_DESIGN.md §9), which stays in the caller (today: `MainActivity`
 * and the settings sections). The caller drives [phase] from the prompt's
 * callbacks.
 *
 * @param statusLabel announced as a live region: assertively on [BiometricGatePhase.FAILED]
 *   (it interrupts), politely otherwise (it waits for a pause in speech).
 * @param retryLabel with [onRetry] shows the retry path on failure; omitted on
 *   failures where retrying is pointless (e.g. the key was invalidated — that
 *   needs the recovery path, not a retry button).
 */
@Composable
fun BiometricGate(
    phase: BiometricGatePhase,
    title: String,
    message: String,
    cancelLabel: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    statusLabel: String? = null,
    retryLabel: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    val color = when (phase) {
        BiometricGatePhase.SUCCESS -> NewaxTheme.colors.success
        BiometricGatePhase.FAILED -> NewaxTheme.colors.error
        else -> NewaxTheme.colors.textSecondary
    }
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .padding(NewaxTheme.spacing.xl)
                .clip(NewaxTheme.shapes.card)
                .background(NewaxTheme.colors.surface)
                .padding(NewaxTheme.spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (phase == BiometricGatePhase.SUCCESS) Icons.Rounded.CheckCircle else Icons.Rounded.Lock,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(NewaxTheme.spacing.md))
            Text(
                title,
                style = NewaxTheme.typography.heading,
                color = NewaxTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(NewaxTheme.spacing.sm))
            Text(
                message,
                style = NewaxTheme.typography.body,
                color = NewaxTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (statusLabel != null) {
                Spacer(Modifier.height(NewaxTheme.spacing.md))
                Text(
                    statusLabel,
                    style = NewaxTheme.typography.label,
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = if (phase == BiometricGatePhase.FAILED) {
                        Modifier.liveRegionAssertive()
                    } else {
                        Modifier.liveRegionPolite()
                    },
                )
            }
            if (phase == BiometricGatePhase.AUTHENTICATING) {
                Spacer(Modifier.height(NewaxTheme.spacing.md))
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = NewaxTheme.colors.textSecondary,
                )
            }
            if (phase == BiometricGatePhase.FAILED && retryLabel != null && onRetry != null) {
                Spacer(Modifier.height(NewaxTheme.spacing.md))
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.minimumTouchTarget(),
                ) { Text(retryLabel, color = NewaxTheme.colors.error) }
            }
            Spacer(Modifier.height(NewaxTheme.spacing.md))
            TextButton(
                onClick = onCancel,
                modifier = Modifier.minimumTouchTarget().semantics { role = Role.Button },
            ) { Text(cancelLabel, color = NewaxTheme.colors.textSecondary) }
        }
    }
}
