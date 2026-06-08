package com.aritr.rova.ui.screens.chrome

import com.aritr.rova.ui.screens.RecordChromeMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PR-β (ADR-0029 §B) — pins every slot×orientation placement. */
class ChromeSlotPlacementTest {

    private fun p(slot: ChromeSlot, o: ChromeOrientation) = placementFor(slot, o)

    @Test fun status_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 16), p(ChromeSlot.STATUS_OVERLAY, ChromeOrientation.PORTRAIT))
    }
    @Test fun status_landscape_unchanged() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 16), p(ChromeSlot.STATUS_OVERLAY, ChromeOrientation.LANDSCAPE))
    }

    @Test fun recovery_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 70), p(ChromeSlot.RECOVERY_CHIP, ChromeOrientation.PORTRAIT))
    }
    @Test fun recovery_landscape_pulled_up() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 54), p(ChromeSlot.RECOVERY_CHIP, ChromeOrientation.LANDSCAPE))
    }

    @Test fun warning_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 110), p(ChromeSlot.WARNING_CENTER, ChromeOrientation.PORTRAIT))
    }
    @Test fun warning_landscape_pulled_up() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_START, startDp = 16, topDp = 92), p(ChromeSlot.WARNING_CENTER, ChromeOrientation.LANDSCAPE))
    }

    @Test fun camControls_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_END, topDp = 7, endDp = 7), p(ChromeSlot.CAMERA_CONTROLS, ChromeOrientation.PORTRAIT))
    }
    @Test fun camControls_landscape() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_END, topDp = 12, endDp = 12), p(ChromeSlot.CAMERA_CONTROLS, ChromeOrientation.LANDSCAPE))
    }

    @Test fun activeHud_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_CENTER, topDp = 16), p(ChromeSlot.ACTIVE_HUD, ChromeOrientation.PORTRAIT))
    }
    @Test fun activeHud_landscape_capped() {
        assertEquals(SlotPlacement(SlotAnchor.TOP_CENTER, topDp = 12, maxWidthDp = 360), p(ChromeSlot.ACTIVE_HUD, ChromeOrientation.LANDSCAPE))
    }

    @Test fun paramsCard_portrait_matches_today() {
        assertEquals(SlotPlacement(SlotAnchor.BOTTOM_CENTER, startDp = 16, endDp = 16, bottomDp = 120), p(ChromeSlot.PARAMS_CARD, ChromeOrientation.PORTRAIT))
    }
    @Test fun paramsCard_portrait_bottom_tracks_dock_metrics() {
        // Drift guard: the 120 literal must equal the live dock metric sum.
        val expected = (RecordChromeMetrics.bottomNavClearance + RecordChromeMetrics.settingsCardLift).value.toInt()
        assertEquals(expected, p(ChromeSlot.PARAMS_CARD, ChromeOrientation.PORTRAIT).bottomDp)
    }
    @Test fun paramsCard_landscape_capped_and_centered() {
        assertEquals(SlotPlacement(SlotAnchor.BOTTOM_CENTER, bottomDp = 12, maxWidthDp = 360), p(ChromeSlot.PARAMS_CARD, ChromeOrientation.LANDSCAPE))
    }

    @Test fun navRail_landscape_left() {
        assertEquals(SlotPlacement(SlotAnchor.CENTER_START, startDp = 12), p(ChromeSlot.NAV_RAIL, ChromeOrientation.LANDSCAPE))
    }
    @Test fun recordAction_landscape_right() {
        assertEquals(SlotPlacement(SlotAnchor.CENTER_END, endDp = 14), p(ChromeSlot.RECORD_ACTION, ChromeOrientation.LANDSCAPE))
    }

    // Advisory slots: portrait renders the intact bottom bar, not these slots. Pin
    // that portrait == landscape so a future accidental divergence fails fast.
    @Test fun navRail_portrait_advisory_same_as_landscape() {
        assertEquals(p(ChromeSlot.NAV_RAIL, ChromeOrientation.LANDSCAPE), p(ChromeSlot.NAV_RAIL, ChromeOrientation.PORTRAIT))
    }
    @Test fun recordAction_portrait_advisory_same_as_landscape() {
        assertEquals(p(ChromeSlot.RECORD_ACTION, ChromeOrientation.LANDSCAPE), p(ChromeSlot.RECORD_ACTION, ChromeOrientation.PORTRAIT))
    }

    @Test fun simpleSlots_have_no_maxWidth_in_portrait() {
        listOf(ChromeSlot.STATUS_OVERLAY, ChromeSlot.RECOVERY_CHIP, ChromeSlot.WARNING_CENTER, ChromeSlot.CAMERA_CONTROLS)
            .forEach { assertNull(placementFor(it, ChromeOrientation.PORTRAIT).maxWidthDp) }
    }
}
