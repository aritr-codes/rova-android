package com.aritr.rova.service.dualrecord.internal

import com.aritr.rova.service.dualrecord.VideoSide

/**
 * Phase 6.1a — pure state machine for the DualMuxer lifecycle. Drives
 * `DualMuxer` (Task 10) without coupling to the runtime MediaMuxer.
 *
 * States:
 *  - INIT: muxers not yet opened.
 *  - ADDING_TRACKS: both muxers opened; tracks not yet all added.
 *  - STARTED: both muxers started; writes accepted.
 *  - STOPPING: stop requested; encoders draining final samples.
 *  - RELEASED: stopped + released. Terminal.
 *
 * Per-side failure: a `SideFailed` event records the side in `failedSides`
 * without changing overall state — the live side keeps writing. It is valid
 * in any non-terminal state (a muxer can throw during `setOrientationHint`,
 * `addTrack`, or `start()` — all of which run while ADDING_TRACKS — as well
 * as during writes while STARTED). Only when BOTH sides have failed AND the
 * machine is STARTED does the state advance to STOPPING (the stop path then
 * transitions to RELEASED). A both-sides pre-START failure stays in the
 * current state; the caller's `stop()` releases the muxers regardless.
 *
 * Invalid transitions throw `IllegalStateException` synchronously.
 */
internal class DualMuxerStateMachine {

    enum class State { INIT, ADDING_TRACKS, STARTED, STOPPING, RELEASED }

    sealed class Event {
        object TracksOpened : Event()
        object AllTracksAdded : Event()
        object StopRequested : Event()
        object Stopped : Event()
        data class SideFailed(val side: VideoSide) : Event()
    }

    var state: State = State.INIT
        private set

    private val _failedSides = mutableSetOf<VideoSide>()
    val failedSides: Set<VideoSide> get() = _failedSides.toSet()

    fun dispatch(event: Event) {
        state = when (event) {
            Event.TracksOpened -> requireState(State.INIT, event).let { State.ADDING_TRACKS }
            Event.AllTracksAdded -> requireState(State.ADDING_TRACKS, event).let { State.STARTED }
            Event.StopRequested -> requireState(State.STARTED, event).let { State.STOPPING }
            Event.Stopped -> requireState(State.STOPPING, event).let { State.RELEASED }
            is Event.SideFailed -> handleSideFailed(event.side)
        }
    }

    private fun handleSideFailed(side: VideoSide): State {
        check(state != State.RELEASED) { "SideFailed not valid after RELEASED" }
        _failedSides.add(side)
        return if (_failedSides.size == 2 && state == State.STARTED) State.STOPPING else state
    }

    private fun requireState(expected: State, event: Event) {
        check(state == expected) { "invalid event $event in state $state (expected $expected)" }
    }
}
