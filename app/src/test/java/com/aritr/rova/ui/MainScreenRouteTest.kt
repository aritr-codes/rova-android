package com.aritr.rova.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MainScreenRouteTest {

    @Test
    fun `pinned-dark routes are recognized`() {
        assertEquals(true, isPinnedDarkRoute("record"))
        assertEquals(true, isPinnedDarkRoute("onboarding"))
        assertEquals(true, isPinnedDarkRoute("player/{sessionId}?side={side}"))
        assertEquals(true, isPinnedDarkRoute("player/abc123"))
        assertEquals(true, isPinnedDarkRoute("player/abc123?side=PORTRAIT"))
    }

    @Test
    fun `chrome routes are not pinned dark`() {
        assertEquals(false, isPinnedDarkRoute("settings"))
        assertEquals(false, isPinnedDarkRoute("history"))
    }

    @Test
    fun `null route is not pinned dark`() {
        assertEquals(false, isPinnedDarkRoute(null))
    }
}
