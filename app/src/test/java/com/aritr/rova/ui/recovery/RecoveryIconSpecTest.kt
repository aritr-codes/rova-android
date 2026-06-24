package com.aritr.rova.ui.recovery

import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.RovaIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RecoveryIconSpecTest {

    @Test fun every_kind_resolves_without_throwing() {
        RecoveryCardKind.entries.forEach { RecoveryIconSpec.statusGlyphFor(it) }
    }

    @Test fun killed_by_system_is_interrupted_icon() {
        assertSame(RovaIcons.Interrupted, RecoveryIconSpec.statusGlyphFor(RecoveryCardKind.KILLED_BY_SYSTEM))
    }

    @Test fun killed_force_stop_is_interrupted_icon() {
        assertSame(RovaIcons.Interrupted, RecoveryIconSpec.statusGlyphFor(RecoveryCardKind.KILLED_FORCE_STOP))
    }

    @Test fun user_stopped_is_null_keeps_generic_emblem() {
        assertNull(RecoveryIconSpec.statusGlyphFor(RecoveryCardKind.USER_STOPPED))
    }

    @Test fun safety_stopped_is_null_keeps_generic_emblem() {
        assertNull(RecoveryIconSpec.statusGlyphFor(RecoveryCardKind.SAFETY_STOPPED))
    }

    @Test fun scheduled_end_is_null_keeps_generic_emblem() {
        assertNull(RecoveryIconSpec.statusGlyphFor(RecoveryCardKind.SCHEDULED_END))
    }

    @Test fun error_stopped_is_null_keeps_generic_emblem() {
        assertNull(RecoveryIconSpec.statusGlyphFor(RecoveryCardKind.ERROR_STOPPED))
    }

    @Test fun interrupted_icon_carries_locked_interrupted_status() {
        val icon = RecoveryIconSpec.statusGlyphFor(RecoveryCardKind.KILLED_BY_SYSTEM)!!
        assertEquals(IconStatus.Interrupted, icon.status)
    }

    @Test fun force_stop_icon_also_carries_locked_interrupted_status() {
        val icon = RecoveryIconSpec.statusGlyphFor(RecoveryCardKind.KILLED_FORCE_STOP)!!
        assertEquals(IconStatus.Interrupted, icon.status)
    }

    /**
     * Exhaustive check: every non-null result must carry a locked status (never a bare ImageVector
     * with status = null, which would silently lose the color lock at the render site).
     */
    @Test fun all_non_null_results_carry_a_locked_status() {
        RecoveryCardKind.entries.forEach { kind ->
            val icon = RecoveryIconSpec.statusGlyphFor(kind)
            if (icon != null) {
                assertEquals(
                    "Kind $kind returned a non-null RovaIcon with no locked status — color lock lost",
                    true,
                    icon.status != null,
                )
            }
        }
    }
}
