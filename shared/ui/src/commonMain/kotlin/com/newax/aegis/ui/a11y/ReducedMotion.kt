package com.newax.aegis.ui.a11y

import androidx.compose.runtime.Composable

/**
 * Whether the user has asked the system to reduce motion.
 *
 * WCAG 2.2 SC 2.3.3 (Animation from Interactions): motion triggered by
 * interaction must be disableable unless it is essential. Nothing in this app's
 * motion is essential, so every animation is expected to honour this.
 *
 * The contract for callers is not "skip the animation" but "reach the same end
 * state without the movement" — a typing indicator becomes a static label, a
 * slide-in becomes an immediate appearance. Never leave a control absent or a
 * status unannounced because motion was suppressed.
 *
 * See `docs/UI_DESIGN.md` §3.2.
 */
@Composable
expect fun reducedMotionEnabled(): Boolean
