package com.newax.aegis.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The Newax Aegis colour palette.
 *
 * Values and their contrast ratios come from `docs/UI_DESIGN.md` §4.1 and §4.2,
 * where every foreground/background pair was computed from WCAG relative
 * luminance and re-verified (18 pairs, 0 mismatches). **Do not adjust a value
 * without recomputing its ratio** — five colours in the previous palette failed
 * WCAG AA, and one of them ([warning], then `#F59E0B` at 2.00:1) was the marker
 * for policy-blocked actions.
 *
 * Ratios in the comments below are against that theme's [bg].
 */
@Immutable
data class NewaxColors(
    /** App background. */
    val bg: Color,
    /** Raised surfaces: cards, sheets, composer. Always lighter than [bg] in
     *  light mode AND darker-to-lighter in dark mode — "surface = raised" holds
     *  in both themes. */
    val surface: Color,
    /** Selected rows, user message bubbles. */
    val surfaceSelected: Color,
    /**
     * A recessed surface one step from [bg] — section fills, inset rows, code
     * wells. **Text-bearing**: every foreground token clears 4.5:1 here, and
     * [ContrastTest][../../../../../commonTest] enforces it. 64 call sites in
     * the Android screens rely on this level.
     */
    val surfaceMuted: Color,
    /**
     * The strongest neutral fill — progress tracks, switch tracks, unselected
     * chip fills.
     *
     * **Not text-bearing**, with one sanctioned exception: [textPrimary], which
     * clears 13.89:1 here (the "Sensitive" risk chip). Do not place
     * [textTertiary], [accent], or [success] on it — they do not clear 4.5:1
     * against a fill this dark, which is why the contrast test asserts only
     * [textPrimary] for this level. If you need text on a neutral fill, use
     * [surfaceMuted].
     */
    val surfaceStrong: Color,
    /** Body copy, titles, icons. */
    val textPrimary: Color,
    /** Supporting text. */
    val textSecondary: Color,
    /** Timestamps, hints. Still ≥ 4.5:1 — this is the value that used to fail. */
    val textTertiary: Color,
    /** Links, focus ring, verified marks. Safe as text and as a 3:1 UI boundary. */
    val accent: Color,
    /** Background of filled accent chips. Pair only with [onAccentFill]. */
    val accentFill: Color,
    /** The only foreground permitted on [accentFill]. */
    val onAccentFill: Color,
    /** Policy-blocked text, icon, and border. */
    val warning: Color,
    /** Background of a blocked card. Pair with [warning]. */
    val warningFill: Color,
    /** Failures, hard deny. */
    val error: Color,
    /** Background of an error card. Pair with [error]. */
    val errorFill: Color,
    /** Ready, online, in sync. */
    val success: Color,
    /** Background of a success card. Pair with [success]. */
    val successFill: Color,
    /**
     * Informational emphasis — neutral notices that are neither a warning nor a
     * failure. The screens used a blue for this and it has no equivalent in the
     * green/amber/red set, so it stays a distinct token rather than being
     * collapsed into [accent] (which carries "verified/active", a different
     * meaning).
     */
    val info: Color,
    /** Background of an info card. Pair with [info]. */
    val infoFill: Color,
    /**
     * Decorative dividers ONLY. Deliberately below 3:1 — WCAG 2.2 SC 1.4.11
     * exempts purely decorative separators. Anything a user must perceive to
     * operate a control uses [borderStrong] instead.
     */
    val border: Color,
    /** Meaningful boundaries: composer, text fields, unselected controls. ≥ 3:1. */
    val borderStrong: Color,
    /** True for the light palette. Lets callers branch without comparing colours. */
    val isLight: Boolean,
)

/** Light palette — background `#F7F7F5`. See `docs/UI_DESIGN.md` §4.1. */
val NewaxLightColors: NewaxColors = NewaxColors(
    bg = Color(0xFFF7F7F5),
    surface = Color(0xFFFFFFFF),
    surfaceSelected = Color(0xFFEFEFEC),
    surfaceMuted = Color(0xFFF2F2EF),
    surfaceStrong = Color(0xFFE7E7E2),
    textPrimary = Color(0xFF1B1B1A),      // 16.07:1
    textSecondary = Color(0xFF4A4A45),    //  8.31:1
    textTertiary = Color(0xFF6B6B65),     //  5.00:1
    accent = Color(0xFF0B7A5F),           //  4.94:1
    accentFill = Color(0xFF10A37F),       //  5.39:1 vs onAccentFill
    onAccentFill = Color(0xFF1B1B1A),
    warning = Color(0xFF8A5200),          //  5.96:1
    warningFill = Color(0xFFFEF3C7),      //  5.74:1 vs warning
    error = Color(0xFFB3261E),            //  6.09:1
    errorFill = Color(0xFFFEE2E2),        //  5.35:1 vs error
    // Darkened from the #15803D in UI_DESIGN §4.1: that value measured 4.47:1
    // on surfaceMuted — passing on bg but failing on the recessed surface it is
    // routinely drawn on. This clears 5.11:1 there and 5.35:1 on bg.
    success = Color(0xFF14762F),          //  5.35:1
    successFill = Color(0xFFDCFCE7),      //  5.22:1 vs success
    // Not the #2563EB the screens used: that measures 4.49:1 on
    // surfaceSelected, a hair under the floor. This clears 5.82:1 there.
    info = Color(0xFF1D4ED8),             //  6.25:1
    infoFill = Color(0xFFDBEAFE),         //  5.49:1 vs info
    border = Color(0xFFD8D8D3),           //  1.33:1 — decorative only
    borderStrong = Color(0xFF767671),     //  4.26:1
    isLight = true,
)

/**
 * Dark palette — background `#171717`. See `docs/UI_DESIGN.md` §4.2.
 *
 * Note [surface] is *lighter* than [bg], mirroring the light theme. An earlier
 * draft had this inverted, which made cards darker than the page.
 *
 * [borderStrong] is deliberately the same value as the light theme's: `#767671`
 * clears 3:1 on both backgrounds and — the case that actually matters, since
 * inputs sit on cards — 3.53:1 on this theme's [surface].
 */
val NewaxDarkColors: NewaxColors = NewaxColors(
    bg = Color(0xFF171717),
    surface = Color(0xFF212121),
    surfaceSelected = Color(0xFF2E2E2E),
    surfaceMuted = Color(0xFF1E1E1E),
    surfaceStrong = Color(0xFF333333),
    textPrimary = Color(0xFFECECEC),      // 15.18:1
    textSecondary = Color(0xFFA8A8A2),    //  7.50:1
    // Lightened from #8A8A85: that value cleared bg and surface but measured
    // 3.92:1 on surfaceSelected — the user-bubble background. Worst case is now
    // 4.80:1 across all four text-bearing surfaces.
    textTertiary = Color(0xFF9A9A95),     //  6.34:1 on bg, 4.80:1 worst case
    accent = Color(0xFF3DD9A8),           //  9.98:1
    accentFill = Color(0xFF10A37F),
    onAccentFill = Color(0xFF1B1B1A),
    warning = Color(0xFFF2B233),          //  9.55:1
    warningFill = Color(0xFF3A2A08),
    error = Color(0xFFFF8A80),            //  7.85:1
    errorFill = Color(0xFF3A1412),        //  7.14:1 vs error
    success = Color(0xFF4ADE80),          // 10.29:1
    successFill = Color(0xFF0C2A16),      //  8.87:1 vs success
    info = Color(0xFF7AB7FF),             //  8.58:1
    infoFill = Color(0xFF0E2440),         //  7.47:1 vs info
    border = Color(0xFF2E2E2E),           // decorative only
    // Not the light theme's #767671: that measured 2.97:1 on surfaceSelected,
    // just under the SC 1.4.11 floor. Worst case is now 3.61:1.
    borderStrong = Color(0xFF84847F),     //  4.77:1 on bg, 3.61:1 worst case
    isLight = false,
)

internal val LocalNewaxColors = staticCompositionLocalOf<NewaxColors> {
    error("NewaxColors requested outside NewaxTheme — wrap the tree in NewaxTheme { }")
}
