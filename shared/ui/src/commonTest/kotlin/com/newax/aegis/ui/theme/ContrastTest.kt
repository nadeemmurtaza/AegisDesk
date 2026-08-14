package com.newax.aegis.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The palette's accessibility guarantee, enforced by arithmetic rather than by
 * review.
 *
 * `docs/UI_DESIGN.md` §3.1 requires WCAG 2.2 AA: 4.5:1 for text, 3:1 for UI
 * component boundaries and meaningful graphics. The previous palette shipped
 * five values that failed — including the policy-blocked marker at 2.00:1 — and
 * nothing caught it, because nothing was checking.
 *
 * This test is that check. It runs on every CI build via `:shared:ui:jvmTest`.
 * If someone "improves" a brand colour and drops it below its floor, this fails
 * with the measured ratio.
 */
class ContrastTest {

    private companion object {
        const val TEXT_FLOOR = 4.5
        const val UI_FLOOR = 3.0
    }

    /** WCAG 2.1 relative luminance. */
    private fun luminance(color: Color): Double {
        fun channel(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(color.red) +
            0.7152 * channel(color.green) +
            0.0722 * channel(color.blue)
    }

    /** WCAG 2.1 contrast ratio. Order-independent. */
    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertContrast(name: String, fg: Color, bg: Color, floor: Double) {
        val actual = ratio(fg, bg)
        assertTrue(
            actual >= floor,
            "$name: contrast ${format(actual)}:1 is below the ${format(floor)}:1 floor " +
                "required by docs/UI_DESIGN.md §3.1",
        )
    }

    private fun format(v: Double): String {
        val scaled = (v * 100).toLong()
        return "${scaled / 100}.${(scaled % 100).toString().padStart(2, '0')}"
    }

    /**
     * Every foreground token must clear its floor against BOTH the page
     * background and raised surfaces — cards are where most text actually sits,
     * and `surface` is a different colour from `bg` in both themes.
     */
    private fun assertPaletteMeetsAA(label: String, c: NewaxColors) {
        val textBearing = listOf(
            "bg" to c.bg,
            "surface" to c.surface,
            "surfaceSelected" to c.surfaceSelected,
            "surfaceMuted" to c.surfaceMuted,
        )
        for ((surfaceName, background) in textBearing) {
            assertContrast("$label textPrimary on $surfaceName", c.textPrimary, background, TEXT_FLOOR)
            assertContrast("$label textSecondary on $surfaceName", c.textSecondary, background, TEXT_FLOOR)
            assertContrast("$label textTertiary on $surfaceName", c.textTertiary, background, TEXT_FLOOR)
            assertContrast("$label accent on $surfaceName", c.accent, background, TEXT_FLOOR)
            assertContrast("$label warning on $surfaceName", c.warning, background, TEXT_FLOOR)
            assertContrast("$label error on $surfaceName", c.error, background, TEXT_FLOOR)
            assertContrast("$label success on $surfaceName", c.success, background, TEXT_FLOOR)
            // Meaningful boundaries — input edges, unselected controls (SC 1.4.11).
            assertContrast("$label borderStrong on $surfaceName", c.borderStrong, background, UI_FLOOR)
        }
        // Paired fills: each carries its own designated foreground.
        assertContrast("$label onAccentFill on accentFill", c.onAccentFill, c.accentFill, TEXT_FLOOR)
        assertContrast("$label warning on warningFill", c.warning, c.warningFill, TEXT_FLOOR)

        // surfaceStrong is the strongest neutral fill — progress/switch tracks
        // and unselected chips. It is documented as NOT text-bearing, with one
        // sanctioned exception (textPrimary, on the risk chip), so that is the
        // only foreground asserted here. Asserting the full set would fail, and
        // weakening the palette to satisfy a fill that carries no body text
        // would be the wrong trade.
        assertContrast("$label textPrimary on surfaceStrong", c.textPrimary, c.surfaceStrong, TEXT_FLOOR)
    }

    @Test
    fun lightPaletteMeetsWcagAA() = assertPaletteMeetsAA("light", NewaxLightColors)

    @Test
    fun darkPaletteMeetsWcagAA() = assertPaletteMeetsAA("dark", NewaxDarkColors)

    /**
     * The ratio maths itself, pinned against values computed independently.
     * Without this, a bug in [luminance] could make every other assertion pass
     * vacuously.
     */
    @Test
    fun ratioMatchesKnownValues() {
        val blackOnWhite = ratio(Color(0xFF000000), Color(0xFFFFFFFF))
        assertTrue(blackOnWhite > 20.9 && blackOnWhite < 21.1, "black on white should be 21:1, got $blackOnWhite")

        val identical = ratio(Color(0xFF7F7F7F), Color(0xFF7F7F7F))
        assertTrue(identical > 0.99 && identical < 1.01, "identical colours should be 1:1, got $identical")

        // The old warning colour on the old background: the failure this palette fixed.
        val oldWarning = ratio(Color(0xFFF59E0B), Color(0xFFF7F7F5))
        assertTrue(oldWarning < 2.1, "expected the retired amber to measure ~2.00:1, got $oldWarning")
    }

    /**
     * `border` is exempt from the 3:1 floor because SC 1.4.11 exempts purely
     * decorative separators — but that exemption is the whole reason
     * [NewaxColors.borderStrong] exists. This test documents the split so a
     * future change cannot quietly start using `border` for input edges by
     * making the two identical.
     */
    @Test
    fun decorativeAndMeaningfulBordersAreDistinct() {
        for ((label, c) in listOf("light" to NewaxLightColors, "dark" to NewaxDarkColors)) {
            assertTrue(
                c.border != c.borderStrong,
                "$label: border and borderStrong must stay distinct — borderStrong carries the " +
                    "3:1 obligation for meaningful boundaries, border is decorative only",
            )
        }
    }
}
