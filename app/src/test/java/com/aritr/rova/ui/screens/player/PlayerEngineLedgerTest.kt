package com.aritr.rova.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * perf/player-lifecycle — pure ownership state machine for the shared
 * [PlayerEngine]. The ledger decides; the engine executes on the real
 * ExoPlayer. Review Fix 1 (stale-VM teardown race) is pinned here: a
 * stale token must never produce a player-mutating decision, and the
 * takeover snapshot captured when a newer owner steals the player is
 * handed back to the stale token exactly once so its VM can still
 * persist an accurate resume position.
 */
class PlayerEngineLedgerTest {

    // ---- acquire ---------------------------------------------------------

    @Test
    fun `acquire from DESTROYED builds and goes ACTIVE`() {
        val ledger = PlayerEngineLedger()
        assertEquals(PlayerEngineLedger.State.DESTROYED, ledger.state)
        val d = ledger.acquire()
        assertTrue(d.needsBuild)
        assertNull(d.parkStaleToken)
        assertEquals(PlayerEngineLedger.State.ACTIVE, ledger.state)
    }

    @Test
    fun `acquire from PARKED reuses without build`() {
        val ledger = PlayerEngineLedger()
        val a = ledger.acquire()
        ledger.detach(a.token)
        assertEquals(PlayerEngineLedger.State.PARKED, ledger.state)
        val b = ledger.acquire()
        assertFalse(b.needsBuild)
        assertNull(b.parkStaleToken)
        assertEquals(PlayerEngineLedger.State.ACTIVE, ledger.state)
    }

    @Test
    fun `acquire during ACTIVE is a takeover parking the stale owner`() {
        val ledger = PlayerEngineLedger()
        val old = ledger.acquire()
        val new = ledger.acquire()
        assertFalse(new.needsBuild)
        assertEquals(old.token, new.parkStaleToken)
        assertNotEquals(old.token, new.token)
        assertEquals(PlayerEngineLedger.State.ACTIVE, ledger.state)
    }

    @Test
    fun `tokens are unique across the ledger lifetime`() {
        val ledger = PlayerEngineLedger()
        val a = ledger.acquire()
        ledger.detach(a.token)
        val b = ledger.acquire()
        ledger.detach(b.token)
        ledger.destroy()
        val c = ledger.acquire()
        assertEquals(3, setOf(a.token, b.token, c.token).size)
    }

    // ---- detach ----------------------------------------------------------

    @Test
    fun `detach by current owner parks`() {
        val ledger = PlayerEngineLedger()
        val a = ledger.acquire()
        val d = ledger.detach(a.token)
        assertTrue(d.shouldPark)
        assertNull(d.staleSnapshotMs)
        assertEquals(PlayerEngineLedger.State.PARKED, ledger.state)
    }

    @Test
    fun `detach by stale token never mutates and returns takeover snapshot once`() {
        val ledger = PlayerEngineLedger()
        val old = ledger.acquire()
        ledger.acquire() // takeover
        ledger.recordTakeoverSnapshot(old.token, 4321L)
        val d = ledger.detach(old.token)
        assertFalse(d.shouldPark)
        assertEquals(4321L, d.staleSnapshotMs)
        assertEquals(PlayerEngineLedger.State.ACTIVE, ledger.state) // new owner untouched
        // exactly once — a second stale detach yields nothing
        val d2 = ledger.detach(old.token)
        assertFalse(d2.shouldPark)
        assertNull(d2.staleSnapshotMs)
    }

    @Test
    fun `detach with never-minted token is a no-op`() {
        val ledger = PlayerEngineLedger()
        ledger.acquire()
        val d = ledger.detach(-99)
        assertFalse(d.shouldPark)
        assertNull(d.staleSnapshotMs)
        assertEquals(PlayerEngineLedger.State.ACTIVE, ledger.state)
    }

    @Test
    fun `double detach by owner parks once then no-ops`() {
        val ledger = PlayerEngineLedger()
        val a = ledger.acquire()
        assertTrue(ledger.detach(a.token).shouldPark)
        val d2 = ledger.detach(a.token)
        assertFalse(d2.shouldPark)
        assertNull(d2.staleSnapshotMs)
        assertEquals(PlayerEngineLedger.State.PARKED, ledger.state)
    }

    // ---- predictive-back / rapid-nav shape --------------------------------

    @Test
    fun `rapid A-B-A sequence keeps every stale token harmless`() {
        val ledger = PlayerEngineLedger()
        val a = ledger.acquire()
        val b = ledger.acquire() // B takes over A
        ledger.recordTakeoverSnapshot(a.token, 100L)
        val a2 = ledger.acquire() // A' takes over B
        ledger.recordTakeoverSnapshot(b.token, 200L)
        // late onCleared of A then B — neither may park A''s live player
        assertFalse(ledger.detach(a.token).shouldPark)
        assertEquals(200L, ledger.detach(b.token).staleSnapshotMs)
        assertEquals(PlayerEngineLedger.State.ACTIVE, ledger.state)
        // A' remains the true owner and can park normally
        assertTrue(ledger.detach(a2.token).shouldPark)
        assertEquals(PlayerEngineLedger.State.PARKED, ledger.state)
    }

    // ---- destroy ---------------------------------------------------------

    @Test
    fun `destroy from PARKED releases and goes DESTROYED`() {
        val ledger = PlayerEngineLedger()
        val a = ledger.acquire()
        ledger.detach(a.token)
        assertTrue(ledger.destroy())
        assertEquals(PlayerEngineLedger.State.DESTROYED, ledger.state)
    }

    @Test
    fun `destroy from ACTIVE releases and invalidates the owner`() {
        val ledger = PlayerEngineLedger()
        val a = ledger.acquire()
        assertTrue(ledger.destroy())
        assertEquals(PlayerEngineLedger.State.DESTROYED, ledger.state)
        // late detach after destroy: harmless
        val d = ledger.detach(a.token)
        assertFalse(d.shouldPark)
        assertNull(d.staleSnapshotMs)
        assertEquals(PlayerEngineLedger.State.DESTROYED, ledger.state)
    }

    @Test
    fun `destroy when already DESTROYED reports nothing to release`() {
        val ledger = PlayerEngineLedger()
        assertFalse(ledger.destroy())
    }

    @Test
    fun `acquire after destroy rebuilds`() {
        val ledger = PlayerEngineLedger()
        val a = ledger.acquire()
        ledger.destroy()
        val b = ledger.acquire()
        assertTrue(b.needsBuild)
        assertEquals(PlayerEngineLedger.State.ACTIVE, ledger.state)
        // stale pre-destroy token still harmless against the rebuilt player
        assertFalse(ledger.detach(a.token).shouldPark)
    }
}
