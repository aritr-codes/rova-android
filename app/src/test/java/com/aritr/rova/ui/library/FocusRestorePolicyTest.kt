package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusRestorePolicyTest {

    // Lazy-list item order: [0]=recovery/warn header, then per group: (optional) day-header then rows.
    private val groups = listOf(
        listOf("a", "b"),   // group 0
        listOf("c"),        // group 1
    )
    private val allHeaders = listOf(true, true)

    @Test
    fun firstGroupRows() {
        // 0 hdr, 1 day-hdr(group0), 2 "a", 3 "b", 4 day-hdr(group1), 5 "c"
        assertEquals(2, FocusRestorePolicy.targetItemIndex("a", groups, allHeaders))
        assertEquals(3, FocusRestorePolicy.targetItemIndex("b", groups, allHeaders))
        assertEquals(5, FocusRestorePolicy.targetItemIndex("c", groups, allHeaders))
    }

    @Test
    fun missingKey_returnsNull() {
        assertNull(FocusRestorePolicy.targetItemIndex("zzz", groups, allHeaders))
    }

    @Test
    fun blankKey_returnsNull() {
        assertNull(FocusRestorePolicy.targetItemIndex("", groups, allHeaders))
    }

    @Test
    fun flatBucket_noHeader_indexSkipsNoSlot() {
        // LONGEST/LARGEST: one label=="" group, screen renders no day header → rows start at 1.
        val idx = FocusRestorePolicy.targetItemIndex("b", listOf(listOf("a", "b")), listOf(false))
        assertEquals(2, idx) // [0] hdr-recovery-warn, [1] "a", [2] "b"
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
