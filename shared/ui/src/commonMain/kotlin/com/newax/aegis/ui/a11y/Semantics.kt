package com.newax.aegis.ui.a11y

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.newax.aegis.ui.theme.NewaxTheme

/**
 * The accessibility contract from `docs/UI_DESIGN.md` §3.4, as modifiers.
 *
 * These exist so the requirement is one call rather than a remembered
 * convention. The repo previously had **zero** uses of `Modifier.semantics`,
 * `stateDescription`, `heading()`, or `liveRegion` anywhere, so every screen
 * reader announced an undifferentiated wall of text.
 */

/**
 * Marks a section header so screen-reader users can navigate by heading
 * instead of reading linearly. Apply to every `SectionHeader`-equivalent.
 */
fun Modifier.heading(): Modifier = semantics { heading() }

/**
 * Attaches a state to a control whose appearance carries meaning colour alone
 * cannot convey (WCAG SC 1.4.1) — a status dot, a connection badge, a risk chip.
 *
 * @param state what the control currently *is*, in the user's words:
 *   "Ready", "Failed to load", "Blocked by policy". Not a colour name.
 */
fun Modifier.statusSemantics(state: String): Modifier =
    semantics { stateDescription = state }

/**
 * Announces content as it changes, without moving focus.
 *
 * Use [liveRegionPolite] for streamed assistant text and progress: it waits for
 * a pause in speech. Use [liveRegionAssertive] **only** for things the user must
 * hear immediately — an approval request or a failure — because it interrupts
 * whatever is being read.
 */
fun Modifier.liveRegionPolite(): Modifier =
    semantics { liveRegion = LiveRegionMode.Polite }

/** See [liveRegionPolite]. Interrupts; reserve for approvals and failures. */
fun Modifier.liveRegionAssertive(): Modifier =
    semantics { liveRegion = LiveRegionMode.Assertive }

/**
 * Names an element for assistive technology.
 *
 * Use on meaningful icons and any control whose visible label is an icon alone.
 * Do **not** use it on decorative imagery — that takes `contentDescription =
 * null` at the call site so the reader skips it. The current code has this
 * backwards in several places: nulls on meaningful icons.
 */
fun Modifier.describedAs(label: String): Modifier =
    semantics { contentDescription = label }

/**
 * Enforces the minimum touch target.
 *
 * WCAG 2.2 SC 2.5.8 requires 24 dp; both mobile platforms specify 44 dp, and
 * that is the value this project holds to (`docs/UI_DESIGN.md` §3.2). Applies to
 * the *touch* area — an icon can still be drawn smaller inside it.
 */
@Composable
fun Modifier.minimumTouchTarget(): Modifier {
    val min = NewaxTheme.spacing.minTouchTarget
    return sizeIn(minWidth = min, minHeight = min)
}
