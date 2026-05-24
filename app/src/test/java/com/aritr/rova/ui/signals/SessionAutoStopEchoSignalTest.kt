package com.aritr.rova.ui.signals

import com.aritr.rova.data.StopReason
import com.aritr.rova.ui.warnings.TerminalEcho
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionAutoStopEchoSignalTest {

    @Test fun `state is null when source returns null`() {
        val signal = SessionAutoStopEchoSignal(terminalEchoSource = { null })
        assertNull(signal.state.value)
    }

    @Test fun `state emits TerminalEcho when source returns a terminal session`() {
        val echo = TerminalEcho("session-a", StopReason.LOW_STORAGE)
        val signal = SessionAutoStopEchoSignal(terminalEchoSource = { echo })
        assertEquals(echo, signal.state.value)
    }

    @Test fun `state is null when sessionId in initialDismissedIds seed`() {
        val echo = TerminalEcho("session-a", StopReason.LOW_STORAGE)
        val signal = SessionAutoStopEchoSignal(
            terminalEchoSource = { echo },
            initialDismissedIds = setOf("session-a"),
        )
        assertNull(signal.state.value)
    }

    @Test fun `markDismissed flips state to null when current echo matches`() {
        val echo = TerminalEcho("session-a", StopReason.LOW_STORAGE)
        val signal = SessionAutoStopEchoSignal(terminalEchoSource = { echo })
        assertEquals(echo, signal.state.value)
        signal.markDismissed("session-a")
        assertNull(signal.state.value)
    }

    @Test fun `markDismissed does nothing when current echo is a different sessionId`() {
        val echo = TerminalEcho("session-a", StopReason.LOW_STORAGE)
        val signal = SessionAutoStopEchoSignal(terminalEchoSource = { echo })
        signal.markDismissed("session-b")
        assertEquals(echo, signal.state.value)
    }

    @Test fun `refresh re-reads source and emits new TerminalEcho when latest changes`() {
        var current: TerminalEcho? = null
        val signal = SessionAutoStopEchoSignal(terminalEchoSource = { current })
        assertNull(signal.state.value)
        val newEcho = TerminalEcho("session-b", StopReason.USER)
        current = newEcho
        signal.refresh()
        assertEquals(newEcho, signal.state.value)
    }
}
