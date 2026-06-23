package com.aritr.rova.ui.library.components

import com.aritr.rova.ui.library.LibraryBadge
import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.RovaIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LibraryIconSpecTest {

    @Test fun favorite_set_is_the_filled_star() {
        assertSame(RovaIcons.FavoriteOn, LibraryIconSpec.favoriteGlyph(true))
    }

    @Test fun favorite_unset_is_the_outline_star() {
        assertSame(RovaIcons.Favorite, LibraryIconSpec.favoriteGlyph(false))
    }

    @Test fun delete_in_a_destructive_context_is_danger() {
        assertEquals(IconStatus.Danger, LibraryIconSpec.deleteStatus(destructive = true))
    }

    @Test fun delete_as_a_neutral_batch_action_has_no_status() {
        assertNull(LibraryIconSpec.deleteStatus(destructive = false))
    }

    // ── Badge glyph mapper (ADR-0031 icon 5b-4) ──────────────────────────────────────────────────

    @Test fun recovered_badge_maps_to_Recovered_icon() {
        assertSame(RovaIcons.Recovered, LibraryIconSpec.badgeGlyph(LibraryBadge.RECOVERED))
    }

    @Test fun interrupted_badge_maps_to_Interrupted_icon() {
        assertSame(RovaIcons.Interrupted, LibraryIconSpec.badgeGlyph(LibraryBadge.INTERRUPTED))
    }

    @Test fun recovered_badge_carries_Recovered_status_lock() {
        assertEquals(IconStatus.Recovered, LibraryIconSpec.badgeGlyph(LibraryBadge.RECOVERED).status)
    }

    @Test fun interrupted_badge_carries_Interrupted_status_lock() {
        assertEquals(IconStatus.Interrupted, LibraryIconSpec.badgeGlyph(LibraryBadge.INTERRUPTED).status)
    }
}
