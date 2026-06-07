package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeSelectionTest {

    @Test
    fun `enum has follow-system plus twelve palettes in declared order`() {
        assertEquals(13, ThemeSelection.entries.size)
        assertEquals(ThemeSelection.FOLLOW_SYSTEM, ThemeSelection.entries.first())
        assertEquals(ThemeSelection.AURORA, ThemeSelection.DEFAULT)
    }

    @Test
    fun `wave1 picker list is follow-system plus the signature six in order`() {
        assertEquals(
            listOf(
                ThemeSelection.FOLLOW_SYSTEM,
                ThemeSelection.AURORA,
                ThemeSelection.TIDE,
                ThemeSelection.JADE,
                ThemeSelection.DUSK,
                ThemeSelection.ECLIPSE,
                ThemeSelection.DAYLIGHT,
            ),
            ThemeSelection.wave1Picker,
        )
    }

    @Test
    fun `follow-system resolves to aurora in dark and daylight in light`() {
        assertEquals(ThemeSelection.AURORA, ThemeSelection.FOLLOW_SYSTEM.resolveConcrete(systemDark = true))
        assertEquals(ThemeSelection.DAYLIGHT, ThemeSelection.FOLLOW_SYSTEM.resolveConcrete(systemDark = false))
    }

    @Test
    fun `a concrete selection resolves to itself regardless of system`() {
        assertEquals(ThemeSelection.TIDE, ThemeSelection.TIDE.resolveConcrete(systemDark = true))
        assertEquals(ThemeSelection.TIDE, ThemeSelection.TIDE.resolveConcrete(systemDark = false))
    }
}
