package com.newax.aegis.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure geometry and wording behind [GraphCanvas]/[GraphListFallback],
 * tested without Compose: positions stay in bounds and deterministic, and the
 * one-focus-stop summary says what the canvas draws.
 */
class GraphLogicTest {

    // ── graphNodePositions ─────────────────────────────────────────────────

    @Test
    fun singleNodeIsCentred() {
        val positions = graphNodePositions(listOf("Ali"), 200f, 100f)
        assertEquals(1, positions.size)
        assertEquals(100f, positions[0].x, 0.001f)
        assertEquals(50f, positions[0].y, 0.001f)
    }

    @Test
    fun twoNodesSitOnTheHorizontalDiameter() {
        val positions = graphNodePositions(listOf("A", "B"), 200f, 100f)
        assertEquals(2, positions.size)
        assertEquals(50f, positions[0].y, 0.001f, "both on the canvas centre line y=cy")
        assertEquals(50f, positions[1].y, 0.001f)
        assertTrue(positions[0].x < positions[1].x, "A on the left, B on the right")
    }

    @Test
    fun positionsStayWithinBounds() {
        val width = 200f
        val height = 120f
        val nodes = (1..8).map { "node$it" }
        graphNodePositions(nodes, width, height).forEach { p ->
            assertTrue(p.x >= 0f && p.x <= width, "x=${p.x} inside [0, $width]")
            assertTrue(p.y >= 0f && p.y <= height, "y=${p.y} inside [0, $height]")
        }
    }

    @Test
    fun positionsAreDeterministic() {
        val nodes = listOf("A", "B", "C", "D")
        assertEquals(
            graphNodePositions(nodes, 300f, 200f),
            graphNodePositions(nodes, 300f, 200f),
            "the same input must produce the same layout, or the canvas flickers",
        )
    }

    @Test
    fun emptyNodesProduceNoPositions() {
        assertTrue(graphNodePositions(emptyList(), 100f, 100f).isEmpty())
    }

    // ── graphAccessibleSummary ─────────────────────────────────────────────

    @Test
    fun summaryCountsNodesAndEdges() {
        val summary = graphAccessibleSummary(
            nodes = listOf("Ali", "Work", "Family"),
            edges = listOf("Ali" to "Work", "Ali" to "Family"),
            connectionLabel = "connected to",
        )
        assertEquals("N=3, E=2. Ali connected to Work. Ali connected to Family", summary)
    }

    @Test
    fun summaryWithoutEdgesIsCountsOnly() {
        assertEquals(
            "N=2, E=0",
            graphAccessibleSummary(listOf("A", "B"), emptyList(), "connected to"),
        )
    }

    @Test
    fun summaryOfEmptyGraphIsEmpty() {
        assertEquals(
            "",
            graphAccessibleSummary(emptyList(), emptyList(), "connected to"),
        )
    }
}
