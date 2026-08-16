package com.newax.aegis.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pure decision logic behind the T3.4c data rows, tested without Compose
 * (mirrors `ComponentLogicTest` and `BlocksLogicTest`).
 */
class DataRowsLogicTest {

    // ── PersonRow ──────────────────────────────────────────────────────────

    @Test
    fun personRowAccessibleNameJoinsEveryPart() {
        assertEquals(
            "Ali Raza, 12 sources · 40 mentions, 78%",
            personRowAccessibleName("Ali Raza", "12 sources · 40 mentions", "78%"),
        )
    }

    @Test
    fun personRowAccessibleNameDropsMissingScore() {
        assertEquals(
            "Ali Raza, 12 sources",
            personRowAccessibleName("Ali Raza", "12 sources", null),
            "a caller without a score must not leave a dangling separator",
        )
    }

    @Test
    fun personRowAccessibleNameDropsBlankDetail() {
        assertEquals("Ali Raza", personRowAccessibleName("Ali Raza", "", null))
    }
}
