package com.newax.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.newax.aegis.ui.a11y.liveRegionAssertive
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * The pairing family (docs/UI_DESIGN.md §8 — Pairing; docs/SYNC_DESIGN.md
 * §pairing): role choice, QR exchange, SAS human-verification, nearby device
 * rows, and the success card.
 *
 * The SAS helpers ([sasGrouped], [sasCodesMatch]) are pure so the
 * human-verification decision is unit-tested. QR rendering is a platform
 * concern — [PairQrCard] takes a caller-supplied [qr] slot, exactly like
 * [ImageBlock], so `commonMain` stays loader-free.
 */

/**
 * Normalizes + groups a SAS code for display: uppercases, drops
 * non-alphanumerics, and groups in threes ("2c4k7q" → "2C4-K7Q"). A blank
 * code stays blank; a short code is shown as-is after normalization.
 */
fun sasGrouped(code: String): String =
    code.filter { it.isLetterOrDigit() }.uppercase().chunked(3).joinToString("-")

/**
 * True when the two devices' SAS codes match, ignoring case and formatting —
 * the comparison the human-verification step performs
 * (docs/SYNC_DESIGN.md §pairing).
 */
fun sasCodesMatch(first: String, second: String): Boolean =
    sasGrouped(first) == sasGrouped(second) && first.isNotBlank()

/**
 * A role-choice card (docs/SYNC_DESIGN.md — "this device" / "the other
 * device"): title, description, optional icon slot, selected state. The
 * selected state is announced by the [Role.RadioButton] semantics — never by
 * the border colour alone.
 */
@Composable
fun PairRoleCard(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .minimumTouchTarget()
            .clip(NewaxTheme.shapes.card)
            .background(if (selected) NewaxTheme.colors.surfaceSelected else NewaxTheme.colors.surface)
            .border(
                width = if (selected) 2.dp else NewaxTheme.spacing.hairline,
                color = if (selected) NewaxTheme.colors.borderStrong else NewaxTheme.colors.border,
                shape = NewaxTheme.shapes.card,
            )
            .semantics { role = Role.RadioButton }
            .clickable(onClick = onSelect)
            .padding(NewaxTheme.spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(NewaxTheme.spacing.md))
            }
            Column {
                Text(title, style = NewaxTheme.typography.body, fontWeight = FontWeight.SemiBold, color = NewaxTheme.colors.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(description, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textSecondary)
            }
        }
    }
}

/**
 * The QR exchange card (docs/SYNC_DESIGN.md — QR pairing): a caller-supplied
 * [qr] renderer, the raw code below it, and a Copy control. The QR image is
 * decorative — [code] is the accessible text.
 */
@Composable
fun PairQrCard(
    code: String,
    copyLabel: String,
    copiedLabel: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    qr: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surface)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card)
            .padding(NewaxTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (qr != null) qr()
        Spacer(Modifier.height(NewaxTheme.spacing.md))
        Text(
            sasGrouped(code),
            style = NewaxTheme.typography.mono.copy(fontWeight = FontWeight.Bold),
            color = NewaxTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(NewaxTheme.spacing.sm))
        CopyButton(copyLabel, copiedLabel, onCopy)
    }
}

/**
 * The SAS human-verification card (docs/SYNC_DESIGN.md §pairing): the grouped
 * code on both devices, Confirm / Reject. The code is shown in the mono face
 * so digits cannot be confused; the code line itself is the accessible name.
 */
@Composable
fun SasConfirmCard(
    code: String,
    confirmLabel: String,
    rejectLabel: String,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
    hintLabel: String? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surface)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.border, NewaxTheme.shapes.card)
            .padding(NewaxTheme.spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (hintLabel != null) {
            Text(hintLabel, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(NewaxTheme.spacing.md))
        }
        Text(
            sasGrouped(code),
            style = NewaxTheme.typography.title.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            color = NewaxTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(NewaxTheme.spacing.lg))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NewaxTheme.spacing.sm)) {
            TextButton(
                onClick = onReject,
                modifier = Modifier
                    .weight(1f)
                    .minimumTouchTarget(),
            ) { Text(rejectLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.error) }
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .minimumTouchTarget(),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = NewaxTheme.colors.success, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(confirmLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.success)
            }
        }
    }
}

/**
 * A discovered device row (docs/SYNC_DESIGN.md — nearby discovery): name,
 * detail, status chip, Connect control. The status word is the caller's —
 * colour never stands alone.
 */
@Composable
fun NearbyDeviceRow(
    name: String,
    stateLabel: String,
    stateColor: Color,
    connectLabel: String,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    detailLabel: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = NewaxTheme.typography.body, fontWeight = FontWeight.Medium, color = NewaxTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detailLabel != null) {
                Spacer(Modifier.height(2.dp))
                Text(detailLabel, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(NewaxTheme.spacing.sm))
        StatusChip(stateLabel, stateColor)
        Spacer(Modifier.width(NewaxTheme.spacing.sm))
        TextButton(
            onClick = onConnect,
            modifier = Modifier.minimumTouchTarget(),
        ) { Text(connectLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textPrimary) }
    }
}

/**
 * The pairing-success card: the check mark announces assertively — pairing
 * completed is something the user must hear, and the message repeats the
 * consequence (docs/SYNC_DESIGN.md — unpair revokes; pairing is physical).
 */
@Composable
fun PairSuccessCard(
    title: String,
    message: String,
    doneLabel: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.successFill)
            .border(NewaxTheme.spacing.hairline, NewaxTheme.colors.success.copy(alpha = 0.5f), NewaxTheme.shapes.card)
            .padding(NewaxTheme.spacing.lg)
            .liveRegionAssertive(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = NewaxTheme.colors.success, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(NewaxTheme.spacing.sm))
        Text(title, style = NewaxTheme.typography.heading, color = NewaxTheme.colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Text(message, style = NewaxTheme.typography.caption, color = NewaxTheme.colors.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(NewaxTheme.spacing.md))
        TextButton(
            onClick = onDone,
            modifier = Modifier.minimumTouchTarget(),
        ) { Text(doneLabel, style = NewaxTheme.typography.label, color = NewaxTheme.colors.textPrimary) }
    }
}
