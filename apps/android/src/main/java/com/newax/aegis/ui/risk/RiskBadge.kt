package com.newax.aegis.ui.risk

import androidx.compose.ui.graphics.Color
import com.newax.aegis.R
import com.newax.aegis.assistant.RiskLevel
import com.newax.aegis.ui.theme.NewaxColors

/**
 * The approval-card risk chip (T3.0a — the third risk vocabulary is gone).
 *
 * The chip reads the action's canonical [RiskLevel] via `ProposedAction.riskLevel`
 * (the single classification, shared/core `assistant.Models.kt`) instead of the
 * locally redefined `Risk {Routine, Sensitive, HighImpact}` enum that used to
 * bucket irreversible deletes with sends and under/over-classify calendar events
 * and searches. A badge that disagrees with the policy engine about how dangerous
 * something is is a safety-surface defect; deriving it from `riskOf` makes the
 * disagreement structurally impossible.
 *
 * Every foreground/background pair below is a contrast-verified pair from the
 * shared palette (docs/UI_DESIGN.md §4):
 *  - error on errorFill (5.35:1)            → CRITICAL
 *  - warning on warningFill (5.74:1)        → HIGH
 *  - textPrimary on surfaceStrong (13.89:1 — the sanctioned exception) → MEDIUM
 *  - textSecondary on surfaceMuted (≥4.5:1 — surfaceMuted is text-bearing) → LOW
 */
data class RiskBadgeStyle(
    /** String-resource id of the badge label (T3.2) — the copy lives in strings.xml. */
    val labelRes: Int,
    val background: Color,
    val foreground: Color,
)

fun riskBadgeStyle(risk: RiskLevel, colors: NewaxColors): RiskBadgeStyle = when (risk) {
    RiskLevel.CRITICAL -> RiskBadgeStyle(R.string.risk_critical, colors.errorFill, colors.error)
    RiskLevel.HIGH -> RiskBadgeStyle(R.string.risk_high, colors.warningFill, colors.warning)
    RiskLevel.MEDIUM -> RiskBadgeStyle(R.string.risk_medium, colors.surfaceStrong, colors.textPrimary)
    RiskLevel.LOW -> RiskBadgeStyle(R.string.risk_low, colors.surfaceMuted, colors.textSecondary)
}
