package com.newax.aegis.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ── Design tokens (REFINED_THEME.md) — mirrors Android's CapabilitiesScreen /
// GoalsScreen palette so both bodies share the same visual language.
val SurfaceColor = Color(0xFFFFFFFF)
val SurfaceMutedColor = Color(0xFFF2F2EF)
val TextPrimaryColor = Color(0xFF1B1B1A)
val TextSecondaryColor = Color(0xFF686864)
val TextTertiaryColor = Color(0xFF8D8D87)
val BorderColor = Color(0xFFD8D8D3)

val ReadyColor = Color(0xFF22C55E)
val WarningColor = Color(0xFFF59E0B)
val ErrorColor = Color(0xFFEF4444)
val MutedColor = Color(0xFF94A3B8)
val NotSupportedColor = Color(0xFF9CA3AF)

private val AppColorScheme = lightColorScheme(
    primary = TextPrimaryColor,
    onPrimary = SurfaceColor,
    secondary = TextSecondaryColor,
    onSecondary = SurfaceColor,
    background = SurfaceColor,
    onBackground = TextPrimaryColor,
    surface = SurfaceColor,
    onSurface = TextPrimaryColor,
    surfaceVariant = SurfaceMutedColor,
    onSurfaceVariant = TextSecondaryColor,
    outline = BorderColor,
)

/** The desktop app theme — REFINED_THEME.md tokens wired into Material 3. */
@Composable
fun AegisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColorScheme, content = content)
}
