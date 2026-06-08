package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeMigrationTest {

    @Test
    fun `system migrates to follow-system`() {
        assertEquals(ThemeSelection.FOLLOW_SYSTEM, ThemeMigration.migrate("SYSTEM"))
    }

    @Test
    fun `dark migrates to aurora`() {
        assertEquals(ThemeSelection.AURORA, ThemeMigration.migrate("DARK"))
    }

    @Test
    fun `light migrates to daylight`() {
        assertEquals(ThemeSelection.DAYLIGHT, ThemeMigration.migrate("LIGHT"))
    }

    @Test
    fun `missing or unknown migrates to follow-system`() {
        assertEquals(ThemeSelection.FOLLOW_SYSTEM, ThemeMigration.migrate(null))
        assertEquals(ThemeSelection.FOLLOW_SYSTEM, ThemeMigration.migrate(""))
        assertEquals(ThemeSelection.FOLLOW_SYSTEM, ThemeMigration.migrate("PURPLE"))
    }

    @Test
    fun `resolve prefers a stored new-key selection over migrating the old key`() {
        assertEquals(
            ThemeSelection.TIDE,
            ThemeMigration.resolve(newRaw = "TIDE", oldRaw = "DARK"),
        )
        assertEquals(
            ThemeSelection.AURORA,
            ThemeMigration.resolve(newRaw = null, oldRaw = "DARK"),
        )
        assertEquals(
            ThemeSelection.FOLLOW_SYSTEM,
            ThemeMigration.resolve(newRaw = null, oldRaw = null),
        )
    }
}
