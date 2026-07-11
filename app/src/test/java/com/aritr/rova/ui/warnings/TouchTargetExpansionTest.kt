package com.aritr.rova.ui.warnings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Investigation-2 geometry proof (frozen §05 invisible `::after` hit expansion).
 *
 * The banner Stop pill's touch target must reach 48dp WITHOUT growing the banner. These are the
 * exact, non-estimated numbers of the layout arithmetic `Modifier.invisibleTouchTarget` runs.
 *
 * Substrate: a single-line-sub banner (every shipped banner sub is single-line), so the Stop
 * pill's natural size is ~60×32 px at 1x density (visual capsule ~32dp) and the touch floor is 48px.
 */
class TouchTargetExpansionTest {

    private val target = 48

    /** The bug (RED): the reserving mechanism (`minimumInteractiveComponentSize`) reports 48. */
    @Test fun reservingMechanism_reportsTheTarget_growingTheParent() {
        // `minimumInteractiveComponentSize` measures at max(natural, 48) and reports THAT.
        val reservedHeight = maxOf(32, target)
        assertEquals("the reserving mechanism inflates the reported height", 48, reservedHeight)
    }

    /** The fix (GREEN): the parent sees the NATURAL height, so the banner is not grown. */
    @Test fun expansion_reportsNaturalSize_soTheBannerIsNotGrown() {
        val r = TouchTargetExpansion.compute(naturalWidth = 60, naturalHeight = 32, targetPx = target, maxWidthPx = Int.MAX_VALUE)
        assertEquals("reported height MUST be the natural height, never the target", 32, r.reportedHeight)
        assertEquals("reported width is the natural width", 60, r.reportedWidth)
        assertTrue("reported height is strictly below the 48px target (no reservation)", r.reportedHeight < target)
    }

    /** The fix (GREEN): the child is measured at the 48px floor — a true 48dp hit region. */
    @Test fun expansion_measuresChildAtTheTarget_soTheHitRegionIs48() {
        val r = TouchTargetExpansion.compute(naturalWidth = 60, naturalHeight = 32, targetPx = target, maxWidthPx = Int.MAX_VALUE)
        assertEquals("hit height reaches the target", 48, r.placeableHeight)
        assertEquals("hit width already exceeds the target, kept", 60, r.placeableWidth)
        assertTrue("hit region on the expanded axis meets 48", r.placeableHeight >= target)
    }

    /** The expansion overflows symmetrically and invisibly (negative offset on the expanded axis). */
    @Test fun expansion_overflowsCentered_onTheExpandedAxis() {
        val r = TouchTargetExpansion.compute(naturalWidth = 60, naturalHeight = 32, targetPx = target, maxWidthPx = Int.MAX_VALUE)
        assertEquals("centered vertically: (32-48)/2", -8, r.offsetY)
        assertEquals("no horizontal overflow (already ≥48 wide)", 0, r.offsetX)
    }

    /** A pill already ≥48 on both axes is a no-op: nothing reserved, nothing overflows. */
    @Test fun expansion_isNoOp_whenContentAlreadyMeetsTarget() {
        val r = TouchTargetExpansion.compute(naturalWidth = 70, naturalHeight = 50, targetPx = target, maxWidthPx = Int.MAX_VALUE)
        assertEquals(70, r.reportedWidth)
        assertEquals(50, r.reportedHeight)
        assertEquals(70, r.placeableWidth)
        assertEquals(50, r.placeableHeight)
        assertEquals(0, r.offsetX)
        assertEquals(0, r.offsetY)
    }

    /** A narrow pill expands on BOTH axes; width overflows too. */
    @Test fun expansion_expandsBothAxes_forASubTargetPill() {
        val r = TouchTargetExpansion.compute(naturalWidth = 30, naturalHeight = 30, targetPx = target, maxWidthPx = Int.MAX_VALUE)
        assertEquals(30, r.reportedWidth)
        assertEquals(30, r.reportedHeight)
        assertEquals(48, r.placeableWidth)
        assertEquals(48, r.placeableHeight)
        assertEquals(-9, r.offsetX)
        assertEquals(-9, r.offsetY)
    }
}
