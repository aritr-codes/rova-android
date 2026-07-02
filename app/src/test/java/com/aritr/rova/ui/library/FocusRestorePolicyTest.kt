package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusRestorePolicyTest {

    // Lazy-list item order: [0]=recovery/warn header, [1]=hero (if present),
    // then per group: day-header then the group's rows.
    private val groups = listOf(
        listOf("a", "b"),   // group 0
        listOf("c"),        // group 1
    )

    @Test
    fun heroRow_resolvesToIndex1() {
        assertEquals(1, FocusRestorePolicy.targetItemIndex("hero1", "hero1", groups))
    }

    @Test
    fun firstGroupRows_withHero() {
        // 0 hdr, 1 hero, 2 day-hdr(group0), 3 "a", 4 "b", 5 day-hdr(group1), 6 "c"
        assertEquals(3, FocusRestorePolicy.targetItemIndex("a", "hero1", groups))
        assertEquals(4, FocusRestorePolicy.targetItemIndex("b", "hero1", groups))
        assertEquals(6, FocusRestorePolicy.targetItemIndex("c", "hero1", groups))
    }

    @Test
    fun noHero_shiftsIndicesDownByOne() {
        // 0 hdr, 1 day-hdr(group0), 2 "a", 3 "b", 4 day-hdr(group1), 5 "c"
        assertEquals(2, FocusRestorePolicy.targetItemIndex("a", null, groups))
        assertEquals(5, FocusRestorePolicy.targetItemIndex("c", null, groups))
    }

    @Test
    fun missingKey_returnsNull() {
        assertNull(FocusRestorePolicy.targetItemIndex("zzz", "hero1", groups))
    }

    @Test
    fun blankKey_returnsNull() {
        assertNull(FocusRestorePolicy.targetItemIndex("", "hero1", groups))
    }

    @Test
    fun shouldScroll_targetAlreadyVisible_false() {
        // Normal return: saveable lazy state restored the pre-open position, opened tile on screen.
        assertEquals(false, FocusRestorePolicy.shouldScroll("b", listOf("a", "b", "c")))
    }

    @Test
    fun shouldScroll_targetNotVisible_true() {
        assertEquals(true, FocusRestorePolicy.shouldScroll("z", listOf("a", "b", "c")))
    }

    @Test
    fun shouldScroll_nothingLaidOut_true() {
        assertEquals(true, FocusRestorePolicy.shouldScroll("a", emptyList()))
    }
}
