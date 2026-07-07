package com.aritr.rova.ui.screens.player

import android.app.Application
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer

/**
 * perf/player-lifecycle — app-scoped lifecycle holder for the ONE shared
 * ExoPlayer behind the Player route.
 *
 * WHY: with a player per NavBackStackEntry-scoped VM, every Library→Player
 * paid a full `ExoPlayer.Builder().build()` (~210 ms main-thread) and every
 * Player→Library a synchronous `release()` with playback-thread join
 * (~450 ms main-thread — Media3's 500 ms release timeout nearly maxing out).
 * The engine decouples player lifetime from navigation: build once, park
 * between screens, destroy only when the player ceases to be reusable.
 *
 * OWNERSHIP CONTRACT (owner-mandated, 2026-07-07): the engine owns
 * LIFECYCLE ONLY — player lifetime, acquisition, ownership tokens, detach,
 * destruction, and the parking hygiene that returns the player to a
 * neutral state. ALL playback behavior (media item, prepare, playWhenReady,
 * listeners, seeking, speed, scrub, resume) stays in [PlayerViewModel].
 * Do not migrate playback logic here — route it through the VM.
 *
 * States (see [PlayerEngineLedger]): DESTROYED → ACTIVE ⇄ PARKED. The
 * player ceases to be reusable when (1) the app UI is going away for real
 * (host activity finishing), (2) the platform signals memory pressure
 * while parked, or (3) playback hits an unrecoverable engine fault. Those
 * conditions are the contract; `MainActivity.onDestroy`/`onTrimMemory`
 * are merely today's mapping of them.
 *
 * THREADING: all entry points are main-thread only ([requireMain]) — the
 * ExoPlayer application looper is explicitly pinned to the main looper at
 * build time (review Required Fix 2: a lazily-created engine must not
 * inherit whatever thread first touches it). The playback thread is a
 * shared pre-started [HandlerThread] via `setPlaybackLooper`, so build no
 * longer pays thread start and release no longer joins a thread exit on
 * the interaction path (SociaLite pattern; Media3 does not quit an
 * externally-supplied playback looper on `release()` — the engine quits
 * it in [destroy]).
 *
 * STALE-OWNER SAFETY (review Required Fix 1): Compose Navigation does not
 * order an outgoing entry's ViewModelStore.clear() against the incoming
 * entry's VM init. [acquire] therefore takes over from a still-attached
 * owner (snapshotting its position first); the stale token's later
 * [detach] mutates nothing and receives that snapshot exactly once.
 * Known benign window: the stale VM's [androidx.media3.common.Player.Listener]
 * stays registered on the shared player until its onCleared runs — its
 * callbacks only write the dying VM's own StateFlows.
 */
class PlayerEngine(private val app: Application) {

    /** Handle returned by [acquire]: the token is the VM's proof of ownership for [detach]. */
    class Lease(val token: Int, val player: ExoPlayer)

    private val ledger = PlayerEngineLedger()
    private var player: ExoPlayer? = null
    private var playbackThread: HandlerThread? = null

    /**
     * Hand the shared player to a new owner. Builds it fresh when
     * DESTROYED; reuses it when PARKED; takes it over when a stale owner
     * is still attached. The player arrives in the neutral parked state
     * (no media, no surface, stopped) — the VM applies all playback
     * configuration itself.
     */
    fun acquire(): Lease {
        requireMain()
        val d = ledger.acquire()
        d.parkStaleToken?.let { stale ->
            val p = requireNotNull(player) { "ACTIVE ledger state with no player" }
            ledger.recordTakeoverSnapshot(stale, p.currentPosition.coerceAtLeast(0L))
            park(p)
        }
        val p = if (d.needsBuild) build() else requireNotNull(player) { "reusable ledger state with no player" }
        return Lease(d.token, p)
    }

    /**
     * Release ownership. Current owner → player parked, its position
     * snapshot returned for resume persistence. Stale/unknown token →
     * player untouched; returns the takeover snapshot (once) if one is
     * owed, else null.
     */
    fun detach(token: Int): Long? {
        requireMain()
        val d = ledger.detach(token)
        if (!d.shouldPark) return d.staleSnapshotMs
        val p = player ?: return null
        val snapshot = p.currentPosition.coerceAtLeast(0L)
        park(p)
        return snapshot
    }

    /** End of reusability: release the player and quit the shared playback thread. */
    fun destroy() {
        requireMain()
        if (!ledger.destroy()) return
        player?.let {
            park(it)
            it.release()
        }
        player = null
        playbackThread?.quitSafely()
        playbackThread = null
    }

    /** Memory-pressure mapping: drop the cache only when nobody is playing. */
    fun destroyIfParked() {
        requireMain()
        if (ledger.state == PlayerEngineLedger.State.PARKED) destroy()
    }

    private fun build(): ExoPlayer {
        val thread = playbackThread ?: HandlerThread("RovaPlayback").also {
            it.start()
            playbackThread = it
        }
        return ExoPlayer.Builder(app)
            .setLooper(Looper.getMainLooper())
            .setPlaybackLooper(thread.looper)
            .build()
            .also { player = it }
    }

    /**
     * Parking hygiene — lifecycle-neutral state only. Clearing the video
     * surface BEFORE stop/release keeps the surface-detach wait (its own
     * 2000 ms budget in Media3) off the release path, and guarantees no
     * frame of the previous session's video can ever surface for the next
     * owner (vault/FLAG_SECURE observation from review).
     */
    private fun park(p: ExoPlayer) {
        p.clearVideoSurface()
        p.stop()
        p.clearMediaItems()
    }

    private fun requireMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "PlayerEngine is main-thread only"
        }
    }
}
