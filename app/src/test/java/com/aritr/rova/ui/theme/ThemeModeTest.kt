package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `resolveDarkTheme SYSTEM follows the system flag`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.SYSTEM, systemDark = false))
    }

    @Test
    fun `resolveDarkTheme DARK is always dark`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemDark = false))
    }

    @Test
    fun `resolveDarkTheme LIGHT is always light`() {
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemDark = true))
    }

    @Test
    fun `themeModeFromStorage maps each valid name`() {
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage("SYSTEM"))
        assertEquals(ThemeMode.DARK, themeModeFromStorage("DARK"))
        assertEquals(ThemeMode.LIGHT, themeModeFromStorage("LIGHT"))
    }

    @Test
    fun `themeModeFromStorage coerces null and unknown to SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage(null))
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage(""))
        assertEquals(ThemeMode.SYSTEM, themeModeFromStorage("PURPLE"))
    }
}
