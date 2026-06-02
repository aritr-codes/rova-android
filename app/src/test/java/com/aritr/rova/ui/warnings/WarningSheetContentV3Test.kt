package com.aritr.rova.ui.warnings

import com.aritr.rova.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the v3 sheet-content helpers (Phase 4 re-skin).
 * Pins which ids carry an overflow ⋯ menu and which advisory ids show
 * the "Why this matters" expander.
 *
 * Mirrors the spec appendix table verbatim. A failure here usually means
 * either: (1) a new id was added without updating overflow/whyThisMatters
 * wiring, or (2) an existing id's wiring was accidentally flipped.
 */
class WarningSheetContentV3Test {

    // ───────── hasOverflow ─────────

    @Test fun hasOverflow_for_BATTERY_OPTIMIZATION_ON_is_true() {
        assertTrue(hasOverflow(WarningId.BATTERY_OPTIMIZATION_ON))
    }

    @Test fun hasOverflow_for_POWER_SAVE_MODE_is_true() {
        assertTrue(hasOverflow(WarningId.POWER_SAVE_MODE))
    }

    @Test fun hasOverflow_for_NOTIFICATIONS_DENIED_is_true() {
        assertTrue(hasOverflow(WarningId.NOTIFICATIONS_DENIED))
    }

    @Test fun hasOverflow_for_CAMERA_PERMISSION_DENIED_is_false() {
        assertFalse(hasOverflow(WarningId.CAMERA_PERMISSION_DENIED))
    }

    @Test fun hasOverflow_for_EXACT_ALARM_DENIED_is_false() {
        assertFalse(hasOverflow(WarningId.EXACT_ALARM_DENIED))
    }

    @Test fun hasOverflow_for_STORAGE_INSUFFICIENT_is_false() {
        assertFalse(hasOverflow(WarningId.STORAGE_INSUFFICIENT))
    }

    @Test fun hasOverflow_for_MICROPHONE_DENIED_is_false() {
        assertFalse(hasOverflow(WarningId.MICROPHONE_DENIED))
    }

    @Test fun hasOverflow_for_all_topBanner_ids_is_false() {
        // TopBanner ids never render the sheet, so overflow is irrelevant — pinned false.
        val topBannerIds = WarningId.values().filter { warningSurfaceFor(it) == WarningSurface.TopBanner }
        topBannerIds.forEach { id ->
            assertFalse("expected hasOverflow($id) == false, got true", hasOverflow(id))
        }
    }

    // ───────── shouldShowWhy ─────────

    @Test fun shouldShowWhy_for_NOTIFICATIONS_DENIED_is_true() {
        assertTrue(shouldShowWhy(WarningId.NOTIFICATIONS_DENIED))
    }

    @Test fun shouldShowWhy_for_BATTERY_OPTIMIZATION_ON_is_true() {
        assertTrue(shouldShowWhy(WarningId.BATTERY_OPTIMIZATION_ON))
    }

    @Test fun shouldShowWhy_for_POWER_SAVE_MODE_is_true() {
        assertTrue(shouldShowWhy(WarningId.POWER_SAVE_MODE))
    }

    @Test fun shouldShowWhy_for_CAMERA_PERMISSION_DENIED_is_false() {
        assertFalse(shouldShowWhy(WarningId.CAMERA_PERMISSION_DENIED))
    }

    @Test fun shouldShowWhy_for_EXACT_ALARM_DENIED_is_false() {
        assertFalse(shouldShowWhy(WarningId.EXACT_ALARM_DENIED))
    }

    @Test fun shouldShowWhy_for_STORAGE_INSUFFICIENT_is_false() {
        assertFalse(shouldShowWhy(WarningId.STORAGE_INSUFFICIENT))
    }

    @Test fun shouldShowWhy_for_MICROPHONE_DENIED_is_false() {
        // v1 scope keeps "Why" to advisory-tier only.
        assertFalse(shouldShowWhy(WarningId.MICROPHONE_DENIED))
    }

    @Test fun whyThisMatters_string_is_nonBlank_when_set() {
        listOf(
            WarningId.NOTIFICATIONS_DENIED,
            WarningId.BATTERY_OPTIMIZATION_ON,
            WarningId.POWER_SAVE_MODE,
        ).forEach { id ->
            val why = warningSheetContent(id).whyThisMatters
            assertNotNull("expected whyThisMatters for $id to be non-null", why)
            assertNotEquals("expected whyThisMatters @StringRes for $id to be set", 0, why)
        }
    }

    // ───────── overflow content sanity ─────────

    @Test fun overflow_includes_dontShowAgain_for_advisory_sheet_ids() {
        listOf(
            WarningId.BATTERY_OPTIMIZATION_ON,
            WarningId.POWER_SAVE_MODE,
            WarningId.NOTIFICATIONS_DENIED,
        ).forEach { id ->
            val overflow = warningSheetContent(id).overflow
            assertTrue("expected $id to have at least one overflow item", overflow.isNotEmpty())
            assertTrue(
                "expected $id overflow to include SNOOZE_FOREVER target",
                overflow.any { it.target == ActionTarget.SNOOZE_FOREVER }
            )
        }
    }

    // ───────── midRecBannerContent.autoAction ─────────

    @Test fun midRecBanner_autoAction_for_THERMAL_EMERGENCY_is_set() {
        val auto = midRecBannerContent(WarningId.THERMAL_EMERGENCY).autoAction
        assertNotNull(auto)
        assertTrue("expected secondsRemaining > 0", auto!!.secondsRemaining > 0)
        assertNotEquals("expected description @StringRes set", 0, auto.description)
    }

    @Test fun midRecBanner_autoAction_for_THERMAL_SHUTDOWN_is_set() {
        val auto = midRecBannerContent(WarningId.THERMAL_SHUTDOWN).autoAction
        assertNotNull(auto)
    }

    @Test fun midRecBanner_autoAction_for_BATTERY_CRITICAL_is_null() {
        // Non-thermal critical ids keep the CTA pill, not the countdown.
        assertNull(midRecBannerContent(WarningId.BATTERY_CRITICAL).autoAction)
    }

    @Test fun midRecBanner_autoAction_for_STORAGE_LOW_MID_REC_is_null() {
        assertNull(midRecBannerContent(WarningId.STORAGE_LOW_MID_REC).autoAction)
    }

    @Test fun midRecBanner_autoAction_for_CAMERA_IN_USE_is_null() {
        assertNull(midRecBannerContent(WarningId.CAMERA_IN_USE).autoAction)
    }

    @Test fun midRecBanner_autoAction_for_THERMAL_SEVERE_is_null() {
        // Only EMERGENCY / SHUTDOWN auto-stop; SEVERE keeps CTA.
        assertNull(midRecBannerContent(WarningId.THERMAL_SEVERE).autoAction)
    }

    @Test
    fun `WarningAction defaults to Primary style`() {
        val a = WarningAction(R.string.warning_action_ok, ActionTarget.APP_DETAILS_SETTINGS)
        assertEquals(WarningActionStyle.Primary, a.style)
    }
}
