package com.newax.aegis.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing scale on a 4 dp base — see `docs/UI_DESIGN.md` §4.4.
 *
 * [minTouchTarget] is not a spacing value but belongs with the layout
 * constants: it is the 44 dp floor every interactive element must meet
 * (WCAG 2.2 SC 2.5.8 requires 24 dp; 44 is the platform floor on both mobile
 * OSes and the value this project holds to).
 */
@Immutable
data class NewaxSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val hairline: Dp = 1.dp,
    val minTouchTarget: Dp = 44.dp,
)

val NewaxDefaultSpacing: NewaxSpacing = NewaxSpacing()

internal val LocalNewaxSpacing = staticCompositionLocalOf<NewaxSpacing> {
    error("NewaxSpacing requested outside NewaxTheme — wrap the tree in NewaxTheme { }")
}
