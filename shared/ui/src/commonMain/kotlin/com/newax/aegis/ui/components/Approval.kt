package com.newax.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newax.aegis.ui.a11y.liveRegionAssertive
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * The approval surface (docs/UI_DESIGN.md §7 — Approval; §8 — Blocks).
 *
 * The one place an action waiting on the user is shown, in three states:
 *  - [ApprovalCard] — approvable now: Approve / Reject;
 *  - [BlockedCard] — refused by policy: the "why" line is the point, there is
 *    no approve path (PLAN is never EXECUTE — a blocked action stays blocked);
 *  - [FailureCard] — the attempt failed: message + Retry / dismiss.
 *
 * Accessibility contract (docs/UI_DESIGN.md §3.4, §7):
 *  - the card announces **assertively** on appearance ([liveRegionAssertive])
 *    — an approval request or a refusal must interrupt whatever is being read;
 *  - the risk chip is a [StatusChip]: word + colour, never colour alone
 *    (SC 1.4.1) — the caller supplies both from the policy-engine risk level;
 *  - Approve is never the default focus: the card requests no focus, so a
 *    screen-reader user chooses which control to activate;
 *  - every button meets the 44 dp floor.
 */

/**
 * The approvable action card: typed summary, risk chip, Approve / Reject.
 *
 * @param summary the typed action summary ("Send message: …") — the caller's
 *   `ProposedAction.summary`.
 * @param riskLabel the risk in the user's words ("Critical", "High") —
 *   localized by the caller; paired with [riskColor] so the chip is never
 *   colour-only.
 * @param riskFill the chip's fill; defaults to a tint of [riskColor] (see
 *   [StatusChip]) unless the caller has a contrast-verified pair (the Android
 *   body passes `riskBadgeStyle(...).background`).
 * @param title the optional card heading ("Approval Required") — localized by
 *   the caller; omitted for inline/compact renderings.
 */
@Composable
fun ApprovalCard(
    summary: String,
    riskLabel: String,
    riskColor: Color,
    approveLabel: String,
    rejectLabel: String,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    riskFill: Color? = null,
    detailsLabel: String? = null,
    onDetails: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surface)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card)
            .padding(NewaxTheme.spacing.lg)
            .liveRegionAssertive(),
        verticalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.md),
    ) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null, // decorative — the title carries the meaning
                    tint = NewaxTheme.colors.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(NewaxTheme.spacing.sm))
                Text(
                    title,
                    style = NewaxTheme.typography.label,
                    color = NewaxTheme.colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(riskLabel, riskColor, fill = riskFill)
            }
            HorizontalDivider(color = NewaxTheme.colors.border)
        }
        Text(
            summary,
            style = NewaxTheme.typography.body,
            color = NewaxTheme.colors.textSecondary,
        )
        // Route 1.9 — the step-detail sheet's entry: a quiet "Details" link on
        // the approval card itself, so the user can see the risk class, the
        // policy gate, and the audit link before deciding. 44 dp target like
        // every control; the label is the caller's localized word.
        if (detailsLabel != null && onDetails != null) {
            TextButton(
                onClick = onDetails,
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(detailsLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary) }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm, Alignment.End),
        ) {
            TextButton(
                onClick = onReject,
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(rejectLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary) }
            Button(
                onClick = onApprove,
                modifier = Modifier.minimumTouchTarget(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NewaxTheme.colors.textPrimary,
                    contentColor = NewaxTheme.colors.surface,
                ),
            ) { Text(approveLabel, style = NewaxTheme.typography.label) }
        }
    }
}

/**
 * The policy-blocked card: the action was refused and stays refused — there is
 * no approve path, only the "why" line ([reason]) and a dismiss.
 *
 * Rendered on [NewaxTheme.colors.warningFill] with a warning border; both
 * warning foreground pairs are contrast-verified (docs/UI_DESIGN.md §4 —
 * warning on warningFill clears 5.74:1).
 */
@Composable
fun BlockedCard(
    summary: String,
    reason: String,
    riskLabel: String,
    riskColor: Color,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    riskFill: Color? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.warningFill)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.warning, NewaxTheme.shapes.card)
            .padding(NewaxTheme.spacing.lg)
            .liveRegionAssertive(),
        verticalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null, // decorative — the reason text carries the meaning
                tint = NewaxTheme.colors.warning,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            StatusChip(riskLabel, riskColor, fill = riskFill)
            Spacer(Modifier.weight(1f))
        }
        Text(
            summary,
            style = NewaxTheme.typography.body,
            color = NewaxTheme.colors.textPrimary,
        )
        Text(
            reason,
            style = NewaxTheme.typography.caption,
            color = NewaxTheme.colors.warning,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(dismissLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.warning) }
        }
    }
}

/**
 * The failed-step card: what broke, the error, and Retry / dismiss.
 *
 * Rendered on [NewaxTheme.colors.errorFill] with an error border — the failure
 * pair (error on errorFill) clears 5.35:1 in both themes. Announces assertively:
 * a failure interrupts (docs/UI_DESIGN.md §3.4), unlike progress which waits.
 */
@Composable
fun FailureCard(
    summary: String,
    message: String,
    retryLabel: String,
    dismissLabel: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.errorFill)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.error, NewaxTheme.shapes.card)
            .padding(NewaxTheme.spacing.lg)
            .liveRegionAssertive(),
        verticalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = NewaxTheme.colors.error,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(NewaxTheme.spacing.sm))
            Text(
                summary,
                style = NewaxTheme.typography.label,
                color = NewaxTheme.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            message,
            style = NewaxTheme.typography.body,
            color = NewaxTheme.colors.textPrimary,
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm, Alignment.End),
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(dismissLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textSecondary) }
            TextButton(
                onClick = onRetry,
                modifier = Modifier.minimumTouchTarget(),
            ) { Text(retryLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.error) }
        }
    }
}
