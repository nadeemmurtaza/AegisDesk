package com.newax.aegis.ui.a11y

import androidx.compose.runtime.Composable

/**
 * Desktop (Windows + macOS, both Compose Desktop on the JVM).
 *
 * Returns `false`: neither the JDK nor Compose Desktop exposes the OS
 * reduce-motion preference (Windows `SPI_GETCLIENTAREAANIMATION`, macOS
 * `NSWorkspace.accessibilityDisplayShouldReduceMotion`) through a common API,
 * and reading them needs the per-OS native seams in `platform-impl`.
 *
 * This is an honest "not wired yet", not a claim that desktop users never want
 * reduced motion. Until it is wired, the desktop in-app motion toggle
 * (Settings → About → Theme, route 5.1.4) is the escape hatch. Tracked as a
 * gap in `docs/UI_DESIGN.md` §3.2.
 */
@Composable
actual fun reducedMotionEnabled(): Boolean = false
