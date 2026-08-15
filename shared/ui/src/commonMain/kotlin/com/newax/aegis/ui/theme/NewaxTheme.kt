package com.newax.aegis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

/**
 * The single theme for every Newax Aegis body — Android, iOS, Windows, macOS.
 *
 * Replaces the 88 duplicated colour constants that were declared privately
 * across 19 files. Read tokens through [NewaxTheme] rather than re-declaring
 * them:
 *
 * ```
 * Text(
 *     text = "…",
 *     color = NewaxTheme.colors.textSecondary,
 *     style = NewaxTheme.typography.body,
 * )
 * ```
 *
 * A Material 3 [MaterialTheme] is installed underneath with a colour scheme
 * derived from the same tokens, so components that still read
 * `MaterialTheme.colorScheme` during the migration stay visually consistent
 * instead of falling back to Material defaults.
 *
 * @param darkTheme defaults to the system setting. Pass explicitly for the
 *   in-app override (route 5.1.4) or for previews.
 */
@Composable
fun NewaxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) NewaxDarkColors else NewaxLightColors

    val materialScheme = remember(colors) {
        if (colors.isLight) {
            lightColorScheme(
                primary = colors.textPrimary,
                onPrimary = colors.surface,
                secondary = colors.accent,
                onSecondary = colors.onAccentFill,
                background = colors.bg,
                onBackground = colors.textPrimary,
                surface = colors.surface,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.surfaceSelected,
                onSurfaceVariant = colors.textSecondary,
                error = colors.error,
                onError = colors.surface,
                outline = colors.borderStrong,
                outlineVariant = colors.border,
            )
        } else {
            darkColorScheme(
                primary = colors.textPrimary,
                onPrimary = colors.bg,
                secondary = colors.accent,
                onSecondary = colors.onAccentFill,
                background = colors.bg,
                onBackground = colors.textPrimary,
                surface = colors.surface,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.surfaceSelected,
                onSurfaceVariant = colors.textSecondary,
                error = colors.error,
                onError = colors.bg,
                outline = colors.borderStrong,
                outlineVariant = colors.border,
            )
        }
    }

    CompositionLocalProvider(
        LocalNewaxColors provides colors,
        LocalNewaxTypography provides NewaxDefaultTypography,
        LocalNewaxSpacing provides NewaxDefaultSpacing,
        LocalNewaxShapes provides NewaxDefaultShapes,
    ) {
        MaterialTheme(colorScheme = materialScheme, content = content)
    }
}

/** Token accessors. Valid only inside a [NewaxTheme]; each throws otherwise. */
object NewaxTheme {
    val colors: NewaxColors
        @Composable @ReadOnlyComposable get() = LocalNewaxColors.current

    val typography: NewaxTypography
        @Composable @ReadOnlyComposable get() = LocalNewaxTypography.current

    val spacing: NewaxSpacing
        @Composable @ReadOnlyComposable get() = LocalNewaxSpacing.current

    val shapes: NewaxShapes
        @Composable @ReadOnlyComposable get() = LocalNewaxShapes.current
}
