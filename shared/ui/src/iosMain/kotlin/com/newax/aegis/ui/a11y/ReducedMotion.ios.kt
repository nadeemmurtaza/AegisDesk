package com.newax.aegis.ui.a11y

import androidx.compose.runtime.Composable
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

/**
 * Reads iOS Settings → Accessibility → Motion → Reduce Motion.
 *
 * Not verified on a device: Apple targets cannot be compiled from the Linux
 * development sandbox (`docs/UI_DESIGN.md` §12), and `apple-compile` is red on
 * an unrelated pre-existing failure. Treat as unverified until it builds.
 */
@Composable
actual fun reducedMotionEnabled(): Boolean = UIAccessibilityIsReduceMotionEnabled()
