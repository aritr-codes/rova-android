package com.aritr.rova.ui.library

import androidx.compose.ui.graphics.Color
import com.aritr.rova.ui.theme.DialogActionColors
import com.aritr.rova.ui.theme.RovaSemantics
import com.aritr.rova.ui.theme.ThemeSelection
import com.aritr.rova.ui.theme.rovaPalettes
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Theme Foundation (M1–M3) — proves the two colour families behave as designed:
 *  - identity edges TRACK the active palette (so a theme swap retints them),
 *  - overlay-over-media tokens are LOCKED (palette-independent) for the contrast contract,
 *  - status dots resolve to the locked [RovaSemantics] colours.
 */
class LibraryColorSpecTest {

    private val aurora = rovaPalettes.getValue(ThemeSelection.AURORA)
    private val daylight = rovaPalettes.getValue(ThemeSelection.DAYLIGHT)

    @Test fun `identity edge follows the palette`() {
        assertEquals(aurora.edge, LibraryColorSpec.identityEdge(aurora))
        assertEquals(daylight.edge, LibraryColorSpec.identityEdge(daylight))
    }

    @Test fun `identity edge differs across themes (proves it retints)`() {
        // Aurora is dark (light edge), Daylight is light (dark edge) — a real swap must change the slot.
        assertNotEquals(
            LibraryColorSpec.identityEdge(aurora),
            LibraryColorSpec.identityEdge(daylight),
        )
    }

    @Test fun `overlay scrim and text match the AA-proven CaptionScrim source`() {
        assertEquals(
            Color(
                red = CaptionScrim.SCRIM_R / 255f,
                green = CaptionScrim.SCRIM_G / 255f,
                blue = CaptionScrim.SCRIM_B / 255f,
                alpha = CaptionScrim.SCRIM_ALPHA.toFloat(),
            ),
            LibraryColorSpec.OVERLAY_SCRIM,
        )
        assertEquals(Color.White, LibraryColorSpec.OVERLAY_TEXT)
    }

    @Test fun `locked overlay tokens never vary by theme`() {
        // They are constants — restate the contract explicitly so a future "make it per-palette" edit fails here.
        assertEquals(Color.White.copy(alpha = 0.30f), LibraryColorSpec.SELECTION_RING)
        assertEquals(Color.Black.copy(alpha = 0.34f), LibraryColorSpec.PLAY_GLYPH_SCRIM)
        assertEquals(Color.Black.copy(alpha = 0.32f), LibraryColorSpec.CHECK_CHIP_SCRIM)
    }

    // ── Bento accent + state layers (bento Task 3) ────────────────────────

    private fun Color.toRgb() = intArrayOf(
        (red * 255).roundToInt(),
        (green * 255).roundToInt(),
        (blue * 255).roundToInt(),
    )

    @Test fun `accent fill and ink equal DialogActionColors resolve(accent, accent)`() {
        for (p in listOf(aurora, daylight)) {
            val rgb = p.accent.toRgb()
            val cta = DialogActionColors.resolve(rgb, rgb)
            val expectedFill = Color(cta.start[0], cta.start[1], cta.start[2])
            val expectedInk = if (cta.contentWhite) Color.White else Color(0xFF0E1116)
            assertEquals(expectedFill, LibraryColorSpec.accentFill(p))
            assertEquals(expectedInk, LibraryColorSpec.accentInk(p))
        }
    }

    @Test fun `media-progress resolves to the accent fill (v3_3 — distinct role, same value today)`() {
        // Its own contrast contract (>=3:1 vs the bottom-scrim composite) lives in TokenContrastTest.
        for (p in listOf(aurora, daylight)) {
            assertEquals(LibraryColorSpec.accentFill(p), LibraryColorSpec.mediaProgress(p))
        }
    }

    @Test fun `state layers are textHigh at the frozen alphas`() {
        for (p in listOf(aurora, daylight)) {
            assertEquals(p.textHigh.copy(alpha = 0.05f), LibraryColorSpec.fill1(p))
            assertEquals(p.textHigh.copy(alpha = 0.08f), LibraryColorSpec.fill2(p))
            assertEquals(p.textHigh.copy(alpha = 0.12f), LibraryColorSpec.press(p))
            assertEquals(p.textHigh.copy(alpha = 0.06f), LibraryColorSpec.hairline(p))
        }
    }
}
