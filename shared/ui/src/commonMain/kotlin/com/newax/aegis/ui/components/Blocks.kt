package com.newax.aegis.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import com.newax.aegis.ui.a11y.minimumTouchTarget
import com.newax.aegis.ui.a11y.statusSemantics
import com.newax.aegis.ui.theme.NewaxTheme
import kotlinx.coroutines.delay

/**
 * Shared content-block components (docs/UI_DESIGN.md §8 — Blocks; §7's ten
 * kinds of message content). Copy, code, and the numbered step blocks the
 * action pipeline renders.
 *
 * Accessibility contract (docs/UI_DESIGN.md §3.4):
 *  - copy is a labelled button with a 44 dp target and a changing label
 *    ("Copy" → "Copied ✓"), so the state is announced rather than implied;
 *  - statuses pair a colour with a word (SC 1.4.1) — [StepBlock] carries its
 *    status as [stateLabel] text next to the coloured index.
 */

/**
 * The copy affordance: a labelled button that flips to [copiedLabel] for two
 * seconds after [onCopy] runs. The visible word IS the accessible label — a
 * screen reader announces it without a contentDescription — and the state
 * change ("Copy" → "Copied ✓") is a change to visible text, not just an icon
 * swap. The clipboard itself is a platform seam, so the caller owns it; this
 * component owns the state and the announcement.
 */
@Composable
fun CopyButton(
    copyLabel: String,
    copiedLabel: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    TextButton(
        onClick = {
            onCopy()
            copied = true
        },
        modifier = modifier.minimumTouchTarget(),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (copied) NewaxTheme.colors.success else NewaxTheme.colors.textSecondary,
        ),
    ) {
        Text(
            if (copied) copiedLabel else copyLabel,
            style = NewaxTheme.typography.label,
        )
    }
}

/**
 * A code well: mono text on a recessed `surfaceMuted` fill with a copy action.
 * Text scales with the font (no fixed-height container); wide content should
 * be scrolled inside its own horizontal scroll container by the caller rather
 * than wrapping the page (SC 1.4.10).
 */
@Composable
fun CodeBlock(
    code: String,
    copyLabel: String,
    copiedLabel: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(NewaxTheme.shapes.card)
            .background(NewaxTheme.colors.surfaceMuted)
            .padding(NewaxTheme.spacing.md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            CopyButton(copyLabel, copiedLabel, onCopy)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            code,
            style = NewaxTheme.typography.mono,
            color = NewaxTheme.colors.textPrimary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The lifecycle of a plan step (docs/UI_DESIGN.md §7 — StepBlock). */
enum class StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

/**
 * A numbered step in a plan/execution trace: coloured index, title, optional
 * detail. Colour is never the only signal (SC 1.4.1) — [stateLabel] is the
 * word the reader hears (`statusSemantics`) and, when provided, the word that
 * is shown.
 *
 * @param stateLabel the status in the user's words ("Done", "Running",
 *   "Failed", "Waiting on approval") — localized by the caller.
 */
@Composable
fun StepBlock(
    index: Int,
    title: String,
    status: StepStatus,
    stateLabel: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    showStateWord: Boolean = false,
) {
    val color = when (status) {
        StepStatus.DONE -> NewaxTheme.colors.success
        StepStatus.RUNNING -> NewaxTheme.colors.warning
        StepStatus.FAILED -> NewaxTheme.colors.error
        StepStatus.PENDING, StepStatus.SKIPPED -> NewaxTheme.colors.textTertiary
    }
    Row(
        modifier
            .fillMaxWidth()
            .statusSemantics(stateLabel),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                index.toString(),
                style = NewaxTheme.typography.caption,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
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
                if (showStateWord) {
                    Text(
                        stateLabel,
                        style = NewaxTheme.typography.caption,
                        color = color,
                    )
                }
            }
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    style = NewaxTheme.typography.caption,
                    color = NewaxTheme.colors.textSecondary,
                )
            }
        }
    }
}
