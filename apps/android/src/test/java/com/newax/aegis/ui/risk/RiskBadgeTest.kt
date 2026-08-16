package com.newax.aegis.ui.risk

import com.newax.aegis.R
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.ui.theme.NewaxLightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3.0a — the approval-card risk chip derives from the canonical [RiskLevel].
 * These tests pin the label/colour mapping so a later change to the vocabulary
 * (or an accidental second classification) fails here, on the JVM, before any
 * device build.
 */
class RiskBadgeTest {

    // The badge resolves its colours from the active theme at call time; the
    // JVM test pins the light palette explicitly so the contrast-verified pairs
    // (docs/UI_DESIGN.md §4) stay regression-guarded without a composable host.
    private val lightColors = NewaxLightColors

    @Test
    fun `critical maps to the error pair and the most severe label`() {
        val style = riskBadgeStyle(RiskLevel.CRITICAL, lightColors)
        assertEquals(R.string.risk_critical, style.labelRes)
        assertEquals(NewaxLightColors.errorFill, style.background)
        assertEquals(NewaxLightColors.error, style.foreground)
    }

    @Test
    fun `high maps to the warning pair`() {
        val style = riskBadgeStyle(RiskLevel.HIGH, lightColors)
        assertEquals(R.string.risk_high, style.labelRes)
        assertEquals(NewaxLightColors.warningFill, style.background)
        assertEquals(NewaxLightColors.warning, style.foreground)
    }

    @Test
    fun `medium maps to the neutral strong pair`() {
        val style = riskBadgeStyle(RiskLevel.MEDIUM, lightColors)
        assertEquals(R.string.risk_medium, style.labelRes)
        assertEquals(NewaxLightColors.surfaceStrong, style.background)
        assertEquals(NewaxLightColors.textPrimary, style.foreground)
    }

    @Test
    fun `low maps to the neutral muted pair`() {
        val style = riskBadgeStyle(RiskLevel.LOW, lightColors)
        assertEquals(R.string.risk_low, style.labelRes)
        assertEquals(NewaxLightColors.surfaceMuted, style.background)
        assertEquals(NewaxLightColors.textSecondary, style.foreground)
    }

    @Test
    fun `all four levels resolve and no label resource is missing`() {
        RiskLevel.entries.forEach { level ->
            val style = riskBadgeStyle(level, lightColors)
            assertTrue("labelRes for $level must be a real resource", style.labelRes != 0)
            assertTrue("background for $level must not be transparent", style.background != androidx.compose.ui.graphics.Color.Unspecified)
            assertTrue("foreground for $level must not be transparent", style.foreground != androidx.compose.ui.graphics.Color.Unspecified)
        }
    }
}
