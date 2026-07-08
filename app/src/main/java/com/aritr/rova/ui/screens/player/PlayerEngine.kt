package com.aritr.rova.ui.screens.player

import android.app.Application
import android.os.HandlerThread
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer

/**
 * perf/player-lifecycle — app-scoped lifecycle holder for the ExoPlayer
 * behind the Player route: one lease at a time, each lease a fresh
 * instance on a shared warm playback thread (see [acquire] for why
 * instances are not reused across leases).
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
 * The stale VM's [androidx.media3.common.Player.Listener] dies with its
 * released instance — the new owner's fresh player never sees it.
 */
class PlayerEngine(private val app: Application) {

    /** Handle returned by [acquire]: the token is the VM's proof of ownership for [detach]. */
    class Lease(val token: Int, val player: ExoPlayer)

    private val ledger = PlayerEngineLedger()
    private var player: ExoPlayer? = null
    private var playbackThread: HandlerThread? = null

    /**
     * Retired instances awaiting their deferred release. park() hygiene
     * (clear surface → stop → clear media) is ~2 ms on the nav path; the
     * actual release() still costs ~25-100 ms of main-thread time
     * (codec teardown, measured RZCYA1VBQ2H) — so it is posted
     * [RELEASE_DELAY_MS] later, after the pop transition has finished,
     * where the block lands on an idle Library frame instead of the
     * navigation animation. Release must stay on the main thread (it is
     * the player's application thread); deferring, not off-threading, is
     * the only Media3-legal move. [destroy] flushes these immediately.
     *
     * Reviewed tradeoff (round 3 R2): a re-acquire inside this window
     * puts TWO live ExoPlayers on the one shared playback looper for up
     * to [RELEASE_DELAY_MS] — the retired one stopped+cleared (idle
     * message pump), the new one active. Device-verified RZCYA1VBQ2H:
     * the rapid-renav pass re-acquired ~350 ms after park (inside the
     * window) and rendered 3/3.
     */
    private val pendingReleases = mutableListOf<Pair<ExoPlayer, Runnable>>()
    private val mainHandler = android.os.Handler(Looper.getMainLooper())

    /**
     * Hand a player to a new owner. The lease always carries a FRESH
     * ExoPlayer instance built on the warm shared playback thread; when a
     * stale owner is still attached its playback is snapshotted and its
     * instance released first (takeover). The player arrives neutral —
     * the VM applies all playback configuration itself.
     *
     * Device-verified pivot (RZCYA1VBQ2H, 2026-07-07): the original
     * design REUSED one player instance across leases, but rapid
     * Library→Player→Library→Player navigation reproducibly wedged the
     * reused video codec into black output on this Exynos (Media3 swaps
     * the codec's output surface via MediaCodec.setOutputSurface, which
     * is unreliable on some OMX/Exynos decoders; once wedged, every
     * subsequent lease stayed black). A fresh player per lease gets a
     * fresh codec + fresh surface attach — the wedge is structurally
     * impossible — while the expensive parts stay amortized: the
     * playback HandlerThread is warm ([setPlaybackLooper]) and classes/
     * codec service connections are process-warm, so build cost is ~10 ms
     * (vs ~150 ms cold) and release never joins a thread exit.
     */
    fun acquire(): Lease {
        requireMain()
        val d = ledger.acquire()
        d.parkStaleToken?.let { stale ->
            val p = requireNotNull(player) { "ACTIVE ledger state with no player" }
            ledger.recordTakeoverSnapshot(stale, p.currentPosition.coerceAtLeast(0L))
            park(p)
        }
        player?.let { park(it) } // PARKED leftover (defensive; park() nulls the field)
        return Lease(d.token, build())
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

    /** End of reusability: release any live player and quit the shared playback thread. */
    fun destroy() {
        requireMain()
        if (!ledger.destroy()) return
        player?.let { park(it) }
        flushPendingReleases()
        playbackThread?.quitSafely()
        playbackThread = null
    }

    /**
     * Review round 2 Required Fix — ownership query for the VM's
     * player-mutating call sites (pause/seek/scrub/persist). Pure read,
     * no player mutation: stays inside the lifecycle-only boundary.
     */
    fun isOwner(token: Int): Boolean {
        requireMain()
        return ledger.isOwner(token)
    }

    /**
     * Review round 2 Recommended Improvement — true once [destroy] has
     * released the player. A VM's late onCleared must not call even
     * `removeListener` on a released instance (Media3 documents released
     * players as unusable; post-release tolerance is implementation-
     * defined at 1.4.1).
     */
    val isDestroyed: Boolean
        get() = ledger.state == PlayerEngineLedger.State.DESTROYED

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
     * Parking hygiene — releases the instance (see [acquire] KDoc for why
     * instances are not reused). Clearing the video surface BEFORE
     * stop/release keeps the surface-detach wait (its own 2000 ms budget
     * in Media3) off the release path, and guarantees no frame of the
     * previous session's video can ever surface for the next owner
     * (vault/FLAG_SECURE observation from review). release() here never
     * joins a thread exit: the playback looper is externally owned
     * ([playbackThread]) and Media3 does not quit external loopers.
     */
    private fun park(p: ExoPlayer) {
        p.clearVideoSurface()
        p.stop()
        p.clearMediaItems()
        player = null
        val runnable = Runnable {
            pendingReleases.removeAll { it.first === p }
            p.release()
        }
        pendingReleases.add(p to runnable)
        mainHandler.postDelayed(runnable, RELEASE_DELAY_MS)
    }

    private fun flushPendingReleases() {
        // Iterate over a copy: each runnable mutates pendingReleases.
        pendingReleases.toList().forEach { (p, r) ->
            mainHandler.removeCallbacks(r)
            p.release()
        }
        pendingReleases.clear()
    }

    private companion object {
        /**
         * Longer than the pop-transition animation (~300 ms default nav
         * transition) so the deferred release lands on an idle frame.
         */
        const val RELEASE_DELAY_MS = 400L
    }

    private fun requireMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "PlayerEngine is main-thread only"
        }
    }
}
