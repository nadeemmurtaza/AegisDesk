package com.newax.aegis.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Three radii — see `docs/UI_DESIGN.md` §4.4.
 *
 * Deliberately three, down from the seven distinct values
 * (12/14/16/18/20/24/999) that were in use across the Android screens.
 */
@Immutable
data class NewaxShapes(
    val card: Shape = RoundedCornerShape(12.dp),
    val sheet: Shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    val pill: Shape = RoundedCornerShape(999.dp),
)

val NewaxDefaultShapes: NewaxShapes = NewaxShapes()

internal val LocalNewaxShapes = staticCompositionLocalOf<NewaxShapes> {
    error("NewaxShapes requested outside NewaxTheme — wrap the tree in NewaxTheme { }")
}
