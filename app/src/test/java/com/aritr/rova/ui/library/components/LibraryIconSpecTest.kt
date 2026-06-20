package com.aritr.rova.ui.library.components

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
}
