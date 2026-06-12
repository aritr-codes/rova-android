package com.aritr.rova.ui.screens.chrome

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemNavEdgeTest {
    @Test fun rightInset_trailing() =
        assertEquals(NavEdge.Trailing, systemNavEdge(NavBarInsetsPx(left = 0, right = 84)))
    @Test fun leftInset_leading() =
        assertEquals(NavEdge.Leading, systemNavEdge(NavBarInsetsPx(left = 84, right = 0)))
    @Test fun gestureNav_bothZero_defaultsTrailing() =
        assertEquals(NavEdge.Trailing, systemNavEdge(NavBarInsetsPx(0, 0)))
    @Test fun equalNonZero_defaultsTrailing() =
        assertEquals(NavEdge.Trailing, systemNavEdge(NavBarInsetsPx(40, 40)))
}
