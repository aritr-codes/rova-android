package com.aritr.rova.ui.screens

import com.aritr.rova.ui.components.RecordHudState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the Record FAB state/visual derivation (UI Phase 2 PR-2). The
 * two regressions this PR fixes are asserted explicitly: Waiting must be visually
 * distinct from Recording, and Processing from Disabled, while the ACTION stays
 * truthful (Waiting cancellable, Processing inert).
 */
class RecordFabVisualSpecTest {

    @Test fun idle_unblocked_is_Idle() =
        assertEquals(RecordFabState.Idle, RecordFabVisualSpec.stateFor(RecordHudState.Idle, hardBlockActive = false))

    @Test fun idle_hardBlocked_is_Disabled() =
        assertEquals(RecordFabState.Disabled, RecordFabVisualSpec.stateFor(RecordHudState.Idle, hardBlockActive = true))

    @Test fun recording_and_starting_collapse_to_Recording() {
        assertEquals(RecordFabState.Recording, RecordFabVisualSpec.stateFor(RecordHudState.Recording, false))
        assertEquals(RecordFabState.Recording, RecordFabVisualSpec.stateFor(RecordHudState.Starting, false))
    }

    @Test fun waiting_is_its_own_state() =
        assertEquals(RecordFabState.Waiting, RecordFabVisualSpec.stateFor(RecordHudState.Waiting, false))

    @Test fun merging_maps_to_Processing() =
        assertEquals(RecordFabState.Processing, RecordFabVisualSpec.stateFor(RecordHudState.Merging(0.5f, 1, 2), false))

    @Test fun idle_is_accent_disc_with_rec_disc_and_starts() {
        val v = RecordFabVisualSpec.visualFor(RecordFabState.Idle)
        assertEquals(FabContainer.AccentDisc, v.container)
        assertEquals(FabGlyph.RecDisc, v.glyph)
        assertEquals(FabAction.Start, v.action)
        assertTrue(v.enabled)
    }

    @Test fun recording_is_red_disc_with_morph_and_stops() {
        val v = RecordFabVisualSpec.visualFor(RecordFabState.Recording)
        assertEquals(FabContainer.RedDisc, v.container)
        assertEquals(FabGlyph.RecMorph, v.glyph)
        assertEquals(FabAction.Stop, v.action)
    }

    @Test fun waiting_is_cancellable_but_visually_distinct_from_recording() {
        val w = RecordFabVisualSpec.visualFor(RecordFabState.Waiting)
        val r = RecordFabVisualSpec.visualFor(RecordFabState.Recording)
        assertEquals(FabAction.Stop, w.action) // still cancellable
        assertTrue(w.enabled)
        assertEquals(FabContainer.Ghost, w.container)
        assertEquals(FabGlyph.Waiting, w.glyph)
        assertNotEquals(w.container, r.container)
        assertNotEquals(w.glyph, r.glyph)
    }

    @Test fun processing_is_inert_spinner_distinct_from_disabled() {
        val p = RecordFabVisualSpec.visualFor(RecordFabState.Processing)
        val d = RecordFabVisualSpec.visualFor(RecordFabState.Disabled)
        assertEquals(FabAction.None, p.action) // merge is NonCancellable
        assertFalse(p.enabled)
        assertEquals(FabGlyph.ProcArc, p.glyph)
        // Both sit on the Ghost container; the GLYPH is what tells them apart (spinner vs ring).
        assertNotEquals(p.glyph, d.glyph)
    }

    @Test fun disabled_is_inert_ghost_ring() {
        val v = RecordFabVisualSpec.visualFor(RecordFabState.Disabled)
        assertEquals(FabContainer.Ghost, v.container)
        assertEquals(FabGlyph.RecRing, v.glyph)
        assertEquals(FabAction.None, v.action)
        assertFalse(v.enabled)
    }
}
