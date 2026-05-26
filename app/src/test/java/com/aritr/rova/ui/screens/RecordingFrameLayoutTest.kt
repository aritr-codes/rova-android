package com.aritr.rova.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Milestone 1 — pure-JVM cover for [recordingFrameLayout]. The function
 * computes the centred recording rectangle inside a P+L preview zone
 * plus the surrounding non-recorded scrim regions for the given recording
 * aspect ratio. No Compose, no Android — straight math.
 *
 * Pattern matches [com.aritr.rova.ui.screens.RecordModeCycleTest] (pure
 * JVM, JUnit 4). Spec:
 * `docs/superpowers/specs/2026-05-26-dualshot-frame-polish-design.md` §8.1.
 */
class RecordingFrameLayoutTest {

    private val EPS = 0.5f

    @Test
    fun portrait_zone_produces_side_scrims() {
        // Portrait zone is taller than recording rect → side scrims.
        val layout = recordingFrameLayout(
            zoneWidth = 352f, zoneHeight = 625f, recordingAspect = 9f / 16f,
        )
        assertEquals(2, layout.scrimRegions.size)
        assertEquals(625f, layout.recordingRect.height, EPS)
    }

    @Test
    fun landscape_zone_produces_top_bottom_scrims() {
        // Landscape zone is wider than recording rect → top/bottom scrims.
        val layout = recordingFrameLayout(
            zoneWidth = 352f, zoneHeight = 225f, recordingAspect = 16f / 9f,
        )
        assertEquals(2, layout.scrimRegions.size)
        assertEquals(352f, layout.recordingRect.width, EPS)
    }

    @Test
    fun zone_matching_recording_aspect_produces_no_scrims() {
        val layout = recordingFrameLayout(
            zoneWidth = 16f, zoneHeight = 9f, recordingAspect = 16f / 9f,
        )
        assertTrue("expected no scrim regions, got ${layout.scrimRegions.size}",
            layout.scrimRegions.isEmpty())
        assertEquals(16f, layout.recordingRect.width, EPS)
        assertEquals(9f, layout.recordingRect.height, EPS)
    }

    @Test
    fun zero_size_zone_produces_empty_layout() {
        val layout = recordingFrameLayout(
            zoneWidth = 0f, zoneHeight = 0f, recordingAspect = 9f / 16f,
        )
        assertTrue(layout.scrimRegions.isEmpty())
        assertEquals(0f, layout.recordingRect.width, EPS)
        assertEquals(0f, layout.recordingRect.height, EPS)
    }

    @Test
    fun negative_size_zone_produces_empty_layout() {
        // Defensive: layout pass may briefly report a non-positive size before
        // the first real measure; the helper must not crash or compute garbage.
        val layout = recordingFrameLayout(
            zoneWidth = -10f, zoneHeight = 100f, recordingAspect = 9f / 16f,
        )
        assertTrue(layout.scrimRegions.isEmpty())
        assertEquals(0f, layout.recordingRect.width, EPS)
        assertEquals(0f, layout.recordingRect.height, EPS)
    }

    @Test
    fun portrait_scrim_regions_sum_to_non_recorded_area() {
        val layout = recordingFrameLayout(
            zoneWidth = 352f, zoneHeight = 625f, recordingAspect = 9f / 16f,
        )
        val zoneArea = 352f * 625f
        val recArea = layout.recordingRect.width * layout.recordingRect.height
        val scrimArea = layout.scrimRegions.sumOf {
            (it.width * it.height).toDouble()
        }.toFloat()
        assertEquals(zoneArea - recArea, scrimArea, 1.0f)
    }

    @Test
    fun recording_rect_centred_horizontally_for_portrait() {
        val layout = recordingFrameLayout(
            zoneWidth = 352f, zoneHeight = 625f, recordingAspect = 9f / 16f,
        )
        val centreX = layout.recordingRect.left + layout.recordingRect.width / 2f
        assertTrue("recording rect not centred horizontally: left=${layout.recordingRect.left}, width=${layout.recordingRect.width}, expected centreX≈176f",
            abs(centreX - 352f / 2f) < EPS)
    }

    @Test
    fun recording_rect_centred_vertically_for_landscape() {
        val layout = recordingFrameLayout(
            zoneWidth = 352f, zoneHeight = 225f, recordingAspect = 16f / 9f,
        )
        val centreY = layout.recordingRect.top + layout.recordingRect.height / 2f
        assertTrue("recording rect not centred vertically: top=${layout.recordingRect.top}, height=${layout.recordingRect.height}, expected centreY≈112.5f",
            abs(centreY - 225f / 2f) < EPS)
    }
}
