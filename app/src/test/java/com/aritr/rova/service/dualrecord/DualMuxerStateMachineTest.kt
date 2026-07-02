package com.aritr.rova.service.dualrecord

import com.aritr.rova.service.dualrecord.internal.DualMuxerStateMachine
import com.aritr.rova.service.dualrecord.internal.DualMuxerStateMachine.Event
import com.aritr.rova.service.dualrecord.internal.DualMuxerStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Test

class DualMuxerStateMachineTest {

    @Test
    fun `initial state is INIT`() {
        assertEquals(State.INIT, DualMuxerStateMachine().state)
    }

    @Test
    fun `INIT -- TracksOpened -- ADDING_TRACKS`() {
        val sm = DualMuxerStateMachine()
        sm.dispatch(Event.TracksOpened)
        assertEquals(State.ADDING_TRACKS, sm.state)
    }

    @Test
    fun `ADDING_TRACKS -- AllTracksAdded -- STARTED`() {
        val sm = DualMuxerStateMachine().also { it.dispatch(Event.TracksOpened) }
        sm.dispatch(Event.AllTracksAdded)
        assertEquals(State.STARTED, sm.state)
    }

    @Test
    fun `STARTED -- StopRequested -- STOPPING`() {
        val sm = DualMuxerStateMachine().also {
            it.dispatch(Event.TracksOpened); it.dispatch(Event.AllTracksAdded)
        }
        sm.dispatch(Event.StopRequested)
        assertEquals(State.STOPPING, sm.state)
    }

    @Test
    fun `STOPPING -- Stopped -- RELEASED`() {
        val sm = DualMuxerStateMachine().also {
            it.dispatch(Event.TracksOpened); it.dispatch(Event.AllTracksAdded); it.dispatch(Event.StopRequested)
        }
        sm.dispatch(Event.Stopped)
        assertEquals(State.RELEASED, sm.state)
    }

    @Test(expected = IllegalStateException::class)
    fun `INIT -- AllTracksAdded -- throws`() {
        DualMuxerStateMachine().dispatch(Event.AllTracksAdded)
    }

    @Test(expected = IllegalStateException::class)
    fun `STARTED -- TracksOpened -- throws`() {
        val sm = DualMuxerStateMachine().also {
            it.dispatch(Event.TracksOpened); it.dispatch(Event.AllTracksAdded)
        }
        sm.dispatch(Event.TracksOpened)
    }

    @Test(expected = IllegalStateException::class)
    fun `RELEASED -- StopRequested -- throws`() {
        val sm = DualMuxerStateMachine().also {
            it.dispatch(Event.TracksOpened); it.dispatch(Event.AllTracksAdded)
            it.dispatch(Event.StopRequested); it.dispatch(Event.Stopped)
        }
        sm.dispatch(Event.StopRequested)
    }

    @Test
    fun `per-side failure during STARTED does not change overall state`() {
        val sm = DualMuxerStateMachine().also {
            it.dispatch(Event.TracksOpened); it.dispatch(Event.AllTracksAdded)
        }
        sm.dispatch(Event.SideFailed(VideoSide.PORTRAIT))
        assertEquals(State.STARTED, sm.state)
        assertEquals(setOf(VideoSide.PORTRAIT), sm.failedSides)
    }

    @Test
    fun `per-side failure during ADDING_TRACKS stays ADDING_TRACKS`() {
        // A muxer can throw in setOrientationHint/addTrack/start() before the
        // machine reaches STARTED; SideFailed must record the side, not throw.
        val sm = DualMuxerStateMachine().also { it.dispatch(Event.TracksOpened) }
        sm.dispatch(Event.SideFailed(VideoSide.PORTRAIT))
        assertEquals(State.ADDING_TRACKS, sm.state)
        assertEquals(setOf(VideoSide.PORTRAIT), sm.failedSides)
    }

    @Test
    fun `both sides failing pre-START does not jump to STOPPING`() {
        val sm = DualMuxerStateMachine().also { it.dispatch(Event.TracksOpened) }
        sm.dispatch(Event.SideFailed(VideoSide.PORTRAIT))
        sm.dispatch(Event.SideFailed(VideoSide.LANDSCAPE))
        // STOPPING advance is STARTED-only; pre-START both-fail stays put and
        // the caller's stop() releases the muxers.
        assertEquals(State.ADDING_TRACKS, sm.state)
        assertEquals(setOf(VideoSide.PORTRAIT, VideoSide.LANDSCAPE), sm.failedSides)
    }

    @Test
    fun `both sides failed transitions to STOPPING with both-failed marker`() {
        val sm = DualMuxerStateMachine().also {
            it.dispatch(Event.TracksOpened); it.dispatch(Event.AllTracksAdded)
        }
        sm.dispatch(Event.SideFailed(VideoSide.PORTRAIT))
        sm.dispatch(Event.SideFailed(VideoSide.LANDSCAPE))
        assertEquals(State.STOPPING, sm.state)
        assertEquals(setOf(VideoSide.PORTRAIT, VideoSide.LANDSCAPE), sm.failedSides)
    }
}
