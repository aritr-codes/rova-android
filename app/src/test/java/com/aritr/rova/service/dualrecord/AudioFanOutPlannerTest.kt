package com.aritr.rova.service.dualrecord

import com.aritr.rova.service.dualrecord.internal.AudioFanOutPlanner
import com.aritr.rova.service.dualrecord.internal.AudioFanOutPlanner.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFanOutPlannerTest {

    @Test
    fun `initial plan routes to both sides`() {
        val p = AudioFanOutPlanner()
        assertEquals(Decision.Route(setOf(VideoSide.PORTRAIT, VideoSide.LANDSCAPE)), p.planWrite())
    }

    @Test
    fun `after stopping portrait, routes to landscape only`() {
        val p = AudioFanOutPlanner().also { it.stopSide(VideoSide.PORTRAIT) }
        assertEquals(Decision.Route(setOf(VideoSide.LANDSCAPE)), p.planWrite())
    }

    @Test
    fun `after stopping both sides, write is rejected`() {
        val p = AudioFanOutPlanner().also {
            it.stopSide(VideoSide.PORTRAIT); it.stopSide(VideoSide.LANDSCAPE)
        }
        assertTrue(p.planWrite() is Decision.Reject)
    }

    @Test
    fun `EOS routes to all live sides`() {
        val p = AudioFanOutPlanner()
        assertEquals(Decision.Route(setOf(VideoSide.PORTRAIT, VideoSide.LANDSCAPE)), p.planEos())
    }

    @Test
    fun `EOS routes only to live side after one stop`() {
        val p = AudioFanOutPlanner().also { it.stopSide(VideoSide.LANDSCAPE) }
        assertEquals(Decision.Route(setOf(VideoSide.PORTRAIT)), p.planEos())
    }

    @Test
    fun `write after EOS is rejected`() {
        val p = AudioFanOutPlanner().also { it.markEosSent() }
        assertTrue(p.planWrite() is Decision.Reject)
    }

    @Test
    fun `EOS-after-EOS is rejected`() {
        val p = AudioFanOutPlanner().also { it.markEosSent() }
        assertTrue(p.planEos() is Decision.Reject)
    }

    @Test
    fun `stopping a side twice is idempotent`() {
        val p = AudioFanOutPlanner().also {
            it.stopSide(VideoSide.PORTRAIT); it.stopSide(VideoSide.PORTRAIT)
        }
        assertEquals(Decision.Route(setOf(VideoSide.LANDSCAPE)), p.planWrite())
    }
}
