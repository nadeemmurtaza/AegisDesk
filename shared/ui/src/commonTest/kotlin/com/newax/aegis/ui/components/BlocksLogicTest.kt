package com.newax.aegis.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure decision logic behind the T3.4b content blocks, tested without
 * Compose (mirrors `ComponentLogicTest` — the UI harness is a Track 1 ask).
 *
 * Each test pins a behaviour the spec (docs/UI_DESIGN.md §7) states outright:
 * the custom option is always last, progress never leaves [0,1], and the
 * one-focus-stop accessible names join only the parts that exist.
 */
class BlocksLogicTest {

    // ── McqCard ────────────────────────────────────────────────────────────

    @Test
    fun mcqOptionsAppendsCustomLast() {
        assertEquals(
            listOf("Now", "Tonight", "Custom…"),
            mcqOptions(listOf("Now", "Tonight"), "Custom…"),
            "the custom option is always last (docs/UI_DESIGN.md §7 — MCQ)",
        )
    }

    @Test
    fun mcqOptionsKeepsOrderAndDoesNotDuplicateCustom() {
        // A caller that already appended the custom label must not get it twice.
        assertEquals(
            listOf("Now", "Tonight", "Custom…"),
            mcqOptions(listOf("Now", "Tonight", "Custom…"), "Custom…"),
        )
    }

    @Test
    fun mcqOptionsHandlesSingleAndEmptyOptions() {
        assertEquals(listOf("Only", "Custom…"), mcqOptions(listOf("Only"), "Custom…"))
        assertEquals(listOf("Custom…"), mcqOptions(emptyList(), "Custom…"))
    }

    // ── ImageGenBlock ──────────────────────────────────────────────────────

    @Test
    fun clampProgressKeepsInRangeValues() {
        assertEquals(0f, clampProgress(0f))
        assertEquals(0.5f, clampProgress(0.5f))
        assertEquals(1f, clampProgress(1f))
    }

    @Test
    fun clampProgressClampsOutOfRangeValues() {
        assertEquals(0f, clampProgress(-0.1f), "a negative emission must not leave the bar negative")
        assertEquals(1f, clampProgress(1.4f), "an over-1.0 emission must not stretch the bar")
        assertEquals(0f, clampProgress(Float.NEGATIVE_INFINITY))
        assertEquals(1f, clampProgress(Float.POSITIVE_INFINITY))
    }

    // ── ArtifactChip ───────────────────────────────────────────────────────

    @Test
    fun artifactAccessibleNameJoinsTitleTypeAndSize() {
        assertEquals(
            "Q3 report, PDF, 2.4 MB",
            artifactAccessibleName("Q3 report", "PDF", "2.4 MB"),
        )
    }

    @Test
    fun artifactAccessibleNameDropsBlankParts() {
        assertEquals(
            "Q3 report, PDF",
            artifactAccessibleName("Q3 report", "PDF", ""),
            "an unknown size must not leave a dangling separator",
        )
        assertEquals("Q3 report", artifactAccessibleName("Q3 report", "", ""))
    }

    // ── DocumentsContainer ─────────────────────────────────────────────────

    @Test
    fun documentRowAccessibleNameJoinsEveryPart() {
        val row = DocumentRow(
            filename = "report.pdf",
            typeLabel = "PDF",
            sizeLabel = "2.4 MB",
            detailLabel = "12 pages",
            onClick = {},
        )
        assertEquals("report.pdf, PDF, 2.4 MB, 12 pages", documentRowAccessibleName(row))
    }

    @Test
    fun documentRowAccessibleNameDropsMissingDetail() {
        val row = DocumentRow(
            filename = "photo.jpg",
            typeLabel = "JPG",
            sizeLabel = "1.1 MB",
            onClick = {},
        )
        assertEquals("photo.jpg, JPG, 1.1 MB", documentRowAccessibleName(row))
    }
}
