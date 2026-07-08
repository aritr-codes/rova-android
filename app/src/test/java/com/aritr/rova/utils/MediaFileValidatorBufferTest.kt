package com.aritr.rova.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RF1 (frozen spec 2026-07-08) — pure test for [chooseInspectionBufferSize],
 * the validity-probe read-buffer sizing. Guards against the class of bug the
 * fixed 64 KB buffer created: truncating a large FHD/4K IDR keyframe and
 * false-dropping a HEALTHY segment. The buffer must never fall below the 1 MB
 * floor, and must grow to the track's advertised max input size when larger.
 * (The MediaExtractor read itself is device-verified; this pins the arithmetic.)
 */
class MediaFileValidatorBufferTest {

    private val floor = 1024 * 1024

    @Test
    fun `absent max input size yields the 1MB floor`() {
        assertEquals(floor, chooseInspectionBufferSize(0))
    }

    @Test
    fun `sub-floor max input size (old 64KB) is raised to the floor`() {
        assertEquals(floor, chooseInspectionBufferSize(64 * 1024))
    }

    @Test
    fun `exactly-floor max input size stays the floor`() {
        assertEquals(floor, chooseInspectionBufferSize(floor))
    }

    @Test
    fun `above-floor max input size (4K keyframe) is honored whole`() {
        val fourKKeyframe = 3 * 1024 * 1024
        assertEquals(fourKKeyframe, chooseInspectionBufferSize(fourKKeyframe))
    }

    @Test
    fun `negative max input size yields the floor`() {
        assertEquals(floor, chooseInspectionBufferSize(-5))
    }
}
