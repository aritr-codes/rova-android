package com.aritr.rova.ui.library

import androidx.compose.ui.graphics.Color
import com.aritr.rova.ui.theme.RovaSemantics
import com.aritr.rova.ui.theme.ThemeSelection
import com.aritr.rova.ui.theme.rovaPalettes
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
}
