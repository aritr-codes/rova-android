package com.aritr.rova.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SemanticIconSpecTest {

    private val aurora = rovaPalettes.getValue(ThemeSelection.AURORA)
    private val daylight = rovaPalettes.getValue(ThemeSelection.DAYLIGHT)

    @Test fun default_role_is_palette_textHigh() {
        assertEquals(aurora.textHigh, SemanticIconSpec.tint(aurora, IconRole.Default))
    }

    @Test fun secondary_role_is_palette_textDim() {
        assertEquals(aurora.textDim, SemanticIconSpec.tint(aurora, IconRole.Secondary))
    }

    @Test fun accent_role_is_palette_accent() {
        assertEquals(aurora.accent, SemanticIconSpec.tint(aurora, IconRole.Accent))
    }

    @Test fun disabled_role_dims_textHigh_to_30pct() {
        // Compose Color quantizes sRGB alpha to 8 bits → 0.30f round-trips to ~0.302.
        assertEquals(0.30f, SemanticIconSpec.tint(aurora, IconRole.Disabled).alpha, 0.01f)
    }

    @Test fun identity_roles_retint_per_palette() {
        assertNotEquals(
            SemanticIconSpec.tint(aurora, IconRole.Accent),
            SemanticIconSpec.tint(daylight, IconRole.Accent),
        )
    }

    @Test fun status_tints_are_locked_to_RovaSemantics() {
        assertEquals(RovaSemantics.success, SemanticIconSpec.statusTint(IconStatus.Recovered))
        assertEquals(RovaSemantics.escalating, SemanticIconSpec.statusTint(IconStatus.Interrupted))
        assertEquals(RovaSemantics.escalating, SemanticIconSpec.statusTint(IconStatus.Processing))
        assertEquals(RovaSemantics.success, SemanticIconSpec.statusTint(IconStatus.Success))
        assertEquals(RovaSemantics.warning, SemanticIconSpec.statusTint(IconStatus.Warning))
        assertEquals(RovaSemantics.rec, SemanticIconSpec.statusTint(IconStatus.Rec))
        assertEquals(RovaSemantics.error, SemanticIconSpec.statusTint(IconStatus.Danger))
    }

    @Test fun interrupted_isEscalatingOrange() {
        // Interrupted now reads as escalating-orange; Recovered/Warning stay green/amber.
        assertEquals(RovaSemantics.escalating, SemanticIconSpec.statusTint(IconStatus.Interrupted))
        assertEquals(RovaSemantics.success, SemanticIconSpec.statusTint(IconStatus.Recovered))
        assertEquals(RovaSemantics.warning, SemanticIconSpec.statusTint(IconStatus.Warning))
    }

    @Test fun danger_status_maps_to_locked_error_red() {
        assertEquals(RovaSemantics.error, SemanticIconSpec.statusTint(IconStatus.Danger))
    }

    @Test fun danger_is_distinct_from_recording_red() {
        // destructive (0xFFEF4444) must never collapse onto recording (0xFFFF4D4D)
        assertNotEquals(
            SemanticIconSpec.statusTint(IconStatus.Rec),
            SemanticIconSpec.statusTint(IconStatus.Danger),
        )
    }

    @Test fun status_tints_carry_no_per_call_alpha_dilution() {
        IconStatus.values().forEach { s ->
            assertEquals(1.0f, SemanticIconSpec.statusTint(s).alpha, 0.001f)
        }
    }

    @Test fun status_is_identical_across_all_palettes() {
        val rec = SemanticIconSpec.statusTint(IconStatus.Rec)
        assertEquals(RovaSemantics.rec, rec)
    }
}
