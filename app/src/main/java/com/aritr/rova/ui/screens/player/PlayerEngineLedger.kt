package com.aritr.rova.ui.screens.player

/**
 * perf/player-lifecycle — pure ownership state machine for the shared
 * app-scoped ExoPlayer (see [PlayerEngine]). House pure-helper pattern:
 * no Android/Media3 types, fully JVM-testable.
 *
 * States: DESTROYED (no player) → ACTIVE (one owner token) ⇄ PARKED
 * (player alive, neutral, reusable). The ledger only *decides*; the
 * engine executes the decisions on the real player, on the main thread.
 *
 * Review Fix 1 (stale-VM teardown race): Compose Navigation does not
 * order an outgoing entry's ViewModelStore.clear() against the incoming
 * entry's VM init (rapid re-taps, predictive back). A newer acquire()
 * therefore *takes over* the player from a still-attached stale owner;
 * the stale token's later detach must never mutate the player. The
 * position snapshot the engine captures at takeover is stashed per
 * stale token ([recordTakeoverSnapshot]) and handed back exactly once
 * on that token's detach, so the stale VM can still persist an
 * accurate resume position.
 *
 * Single-threaded by contract: the engine confines all calls to the
 * main thread, so no synchronization here.
 */
class PlayerEngineLedger {

    enum class State { DESTROYED, PARKED, ACTIVE }

    /**
     * @param needsBuild engine must build a fresh player before use
     * @param parkStaleToken non-null when this acquire is a takeover:
     *   the engine must snapshot+park the previous owner's playback and
     *   stash the snapshot via [recordTakeoverSnapshot] for this token.
     */
    data class AcquireDecision(val token: Int, val needsBuild: Boolean, val parkStaleToken: Int?)

    /**
     * @param shouldPark true only for the current owner — engine parks
     *   the player (snapshot, clear surface/media, stop)
     * @param staleSnapshotMs takeover snapshot owed to a stale token,
     *   delivered exactly once; null otherwise
     */
    data class DetachDecision(val shouldPark: Boolean, val staleSnapshotMs: Long?)

    var state: State = State.DESTROYED
        private set

    private var nextToken = 1
    private var currentOwner: Int? = null
    private val takeoverSnapshots = mutableMapOf<Int, Long>()

    fun acquire(): AcquireDecision {
        val stale = if (state == State.ACTIVE) currentOwner else null
        val needsBuild = state == State.DESTROYED
        val token = nextToken++
        currentOwner = token
        state = State.ACTIVE
        return AcquireDecision(token = token, needsBuild = needsBuild, parkStaleToken = stale)
    }

    /** Engine stashes the position it snapshotted while parking [staleToken] during a takeover. */
    fun recordTakeoverSnapshot(staleToken: Int, positionMs: Long) {
        takeoverSnapshots[staleToken] = positionMs
    }

    /**
     * Review round 2 Required Fix — true only while [token] is the live
     * owner. The VM gates every player-mutating call on this so a stale
     * VM (outgoing nav entry whose ON_STOP / late UI callbacks fire after
     * a newer VM took the player over) can neither pause the new owner's
     * playback nor persist the new session's position under its own
     * resume identity.
     */
    fun isOwner(token: Int): Boolean = state == State.ACTIVE && token == currentOwner

    fun detach(token: Int): DetachDecision {
        if (state == State.ACTIVE && token == currentOwner) {
            currentOwner = null
            state = State.PARKED
            return DetachDecision(shouldPark = true, staleSnapshotMs = null)
        }
        return DetachDecision(shouldPark = false, staleSnapshotMs = takeoverSnapshots.remove(token))
    }

    /** @return true if a player existed (engine must release it + quit the playback thread). */
    fun destroy(): Boolean {
        val hadPlayer = state != State.DESTROYED
        currentOwner = null
        takeoverSnapshots.clear()
        state = State.DESTROYED
        return hadPlayer
    }
}
