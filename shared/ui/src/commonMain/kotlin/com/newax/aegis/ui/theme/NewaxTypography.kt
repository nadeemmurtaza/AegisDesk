package com.newax.aegis.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Named type roles — see `docs/UI_DESIGN.md` §4.3.
 *
 * Every size is in `sp` so it scales with the system font setting to 200%
 * (WCAG 2.2 SC 1.4.4). Never hard-code a text size at a call site, and never
 * put text in a fixed-height container.
 */
@Immutable
data class NewaxTypography(
    val display: TextStyle,
    val title: TextStyle,
    val heading: TextStyle,
    val body: TextStyle,
    /** Reading-grade leading for streamed answers and long documents. */
    val bodyLong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
)

val NewaxDefaultTypography: NewaxTypography = NewaxTypography(
    display = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    heading = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    bodyLong = TextStyle(fontSize = 15.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal),
    label = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    mono = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
    ),
)

internal val LocalNewaxTypography = staticCompositionLocalOf<NewaxTypography> {
    error("NewaxTypography requested outside NewaxTheme — wrap the tree in NewaxTheme { }")
}
