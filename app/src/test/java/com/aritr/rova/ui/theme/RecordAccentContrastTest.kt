package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-palette guards for the record-home selected-mode chip (ADR-0028 PR2 §d,
 * owner-signed 2026-06-08). The chip is a solid `accent -> accent2` gradient
 * (mockup `.lpill span.on`), so theme personality on the pinned-dark Record home
 * comes from BOTH gradient stops. This test guards the regression the owner
 * reported: a flat single `accentOnDark` collapsed Aurora==Eclipse and
 * Tide~=Jade. White-on-bright-accent contrast is intentionally below WCAG AA
 * here (the one owner-approved ADR-0020 exception, scoped to this decorative
 * selected state) — so it is deliberately NOT asserted.
 */
class RecordAccentContrastTest {

    private val concrete = ThemeSelection.entries.filter { it != ThemeSelection.FOLLOW_SYSTEM }

    private fun gradientPair(sel: ThemeSelection): Pair<Color, Color> =
        rovaPalettes.getValue(sel).let { it.accent to it.accent2 }

    @Test
    fun `every palette's selected-mode chip is a real two-stop gradient`() {
        concrete.forEach { sel ->
            val (a, b) = gradientPair(sel)
            assertNotEquals("$sel: accent == accent2 — chip would render as a flat fill", a, b)
        }
    }

    @Test
    fun `accent gradients are distinct across all themes (user-reported collision guard)`() {
        // Each concrete theme must own a unique (accent, accent2) pair, so no two
        // selected-mode chips look identical — the exact Aurora/Eclipse and
        // Tide/Jade flatness the owner flagged.
        val pairs = concrete.map { it to gradientPair(it) }
        pairs.forEach { (selA, pairA) ->
            pairs.forEach { (selB, pairB) ->
                if (selA != selB) {
                    assertTrue(
                        "$selA and $selB share an identical accent gradient $pairA",
                        pairA != pairB,
                    )
                }
            }
        }
    }
}
