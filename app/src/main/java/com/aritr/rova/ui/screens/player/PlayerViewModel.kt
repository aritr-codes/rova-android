package com.aritr.rova.ui.screens.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aritr.rova.R
import com.aritr.rova.RovaApp
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.text.UiText
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 2.5 — VM for the in-app Player route.
 *
 * Owns:
 *  - the manifest-driven [PlayerUiState] (Loading → Ready / Unavailable)
 *  - ALL playback behavior on the leased [ExoPlayer]: media item,
 *    prepare, playWhenReady, listener, seeking, scrub, speed, resume
 *  - a 250 ms position-poll loop that updates [PlaybackProgress] while
 *    playback is live; the loop is cheap (one `currentPosition` read per
 *    tick) and does not run while paused / unavailable
 *
 * perf/player-lifecycle (2026-07-07): the VM no longer owns the player's
 * LIFETIME — it leases the app-scoped shared player from [PlayerEngine]
 * in [attachExoPlayer] and detaches (never releases) in [onCleared].
 * This moved the per-navigation `Builder().build()` (~210 ms) and the
 * synchronous `release()` thread-join (~450 ms) off the nav transition.
 * The ownership boundary is owner-mandated: engine = lifecycle only,
 * VM = playback only. If the user navigates to a different
 * `player/{sessionId}`, a fresh VM is created via the NavHost composable
 * scope; the prior VM detaches its lease (the engine parks — or hands
 * over — the shared player). MainScreen.kt uses `launchSingleTop = true`
 * for tab routes only; argumented routes get a fresh entry per id.
 *
 * The [loadManifest] seam mirrors the [com.aritr.rova.ui.recovery.RecoveryViewModel]
 * pattern so JVM tests can pass a synthetic loader. The manifest read
 * runs on Dispatchers.IO because [com.aritr.rova.data.SessionStore.loadManifest]
 * does synchronous filesystem I/O.
 *
 * Trim / Edit are explicit NO-GO: the screen surfaces buttons but they
 * fire a snackbar at the call site. The VM has no `trim()` / `edit()`
 * entry point — adding one would invite scope creep into the editor
 * NO-GO (UI_NAV_GRAPH §6.2).
 */
class PlayerViewModel(
    application: Application,
    private val sessionId: String,
    private val side: VideoSide?,
    private val segmentIndex: Int? = null,
    private val loadManifest: suspend (String) -> SessionManifest?,
    private val readResume: suspend (String, VideoSide?, Int?) -> Long? = { _, _, _ -> null },
    private val writeResume: (String, VideoSide?, Int?, Long) -> Unit = { _, _, _, _ -> },
    private val engine: PlayerEngine,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    /**
     * B5 / ADR-0025 — true when the resolved manifest is VAULTED. The screen
     * collects this to apply FLAG_SECURE so vault playback can't be
     * screenshotted or surfaced in the recents thumbnail. Stays false for
     * PUBLIC (and for in-flight VAULTING/UNVAULTING, which the vault list does
     * not route into the player). Resolved once in [init] from the same
     * manifest read that drives uiState.
     */
    private val _isVaulted = MutableStateFlow(false)
    val isVaulted: StateFlow<Boolean> = _isVaulted.asStateFlow()

    /**
     * True once ExoPlayer has decoded and pushed the first video frame to the surface. The screen
     * paints a hand-off poster (the Library thumbnail) over the black PlayerView shutter until this
     * flips, so entering the player doesn't flash a black "block" during build/prepare. Reset to
     * false per [attachExoPlayer] so a re-attach re-shows the poster.
     */
    private val _firstFrameRendered = MutableStateFlow(false)
    val firstFrameRendered: StateFlow<Boolean> = _firstFrameRendered.asStateFlow()

    private var exoPlayer: ExoPlayer? = null

    /**
     * perf/player-lifecycle — proof of ownership for [PlayerEngine.detach].
     * -1 until [attachExoPlayer] takes a lease; a never-attached VM (cleared
     * before the init coroutine reached attach) detaches with -1, which the
     * engine treats as unknown → guaranteed no-op.
     */
    private var leaseToken: Int = -1
    private var pollJob: Job? = null

    /**
     * Task 4 — per-segment durations captured at resolve time so the
     * segment-jump helpers ([jumpNextSegment] / [jumpPrevSegment]) can
     * map a playback position onto a segment boundary without re-reading
     * uiState. Populated once in [init] from the Ready value; stays
     * empty for Loading / Unavailable (jumps then no-op safely because
     * the math returns null on an empty list).
     */
    private var segmentDurationsMs: List<Long> = emptyList()

    // ---- Task 4: drag-scrub session state -------------------------------
    private var scrubWasPlaying = false
    private var lastScrubSeekAt = 0L
    private var lastScrubTarget = -1L

    // Final-review M-1 — guards the scrub lifecycle so a stale gesture
    // can't run with a prior gesture's state. Set true in beginScrub only
    // after a successful pause/setSeekParameters; cleared unconditionally
    // in endScrub. updateScrub/endScrub no-op (harmlessly) when false.
    private var scrubActive = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            pushProgress(isPlaying = isPlaying)
            if (isPlaying) startPolling() else stopPolling()
        }

        override fun onRenderedFirstFrame() {
            _firstFrameRendered.value = true
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // STATE_READY signals the first frame is decodable —
            // duration becomes meaningful here. STATE_ENDED collapses
            // the timeline to all-Done via the position == duration
            // path in [SegmentedTimelineMath].
            pushProgress()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            // ExoPlayer surface error — the artifact is on disk but
            // unplayable (codec/container issue). Fall back to
            // Unavailable rather than leaving the user staring at a
            // blank surface. The screen pops back via the standard
            // Unavailable path.
            RovaLog.w(
                "PlayerViewModel: playback error for sessionId=$sessionId",
                error
            )
            _uiState.value =
                PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_playback_failed))
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            // PR-7 — the active speed reported by the player is authoritative;
            // reconcile the optimistic setPlaybackSpeed write so the chip
            // reflects what is actually applied.
            _progress.update { it.copy(speed = playbackParameters.speed) }
        }
    }

    init {
        viewModelScope.launch {
            val manifest = withContext(Dispatchers.IO) {
                runCatching { loadManifest(sessionId) }.getOrNull()
            }
            _isVaulted.value =
                manifest?.vaultState == com.aritr.rova.data.VaultState.VAULTED
            val resolved = PlayerUriResolver.resolve(manifest, side, segmentIndex)
            // Task 8 — read the saved resume position before attaching so
            // ExoPlayer can seek to it as the initial position (no first-frame
            // flash). totalDurationFromSegmentsMs is available on the Ready
            // value; use 0L for Unavailable (ResumePolicy returns 0 for that).
            val totalDurationMs = (resolved as? PlayerUiState.Ready)
                ?.totalDurationFromSegmentsMs ?: 0L
            val savedPosition = withContext(Dispatchers.IO) { runCatching { readResume(sessionId, side, segmentIndex) }.getOrNull() }
            val startMs = PlayerResumeMath.startPositionMs(savedPosition, totalDurationMs)
            // Audit F#9 — attach the ExoPlayer instance BEFORE flipping
            // uiState to Ready. The screen's `update` block reads
            // `viewModel.getOrCreatePlayer()` on every recomposition; if
            // recomposition fires after `_uiState` becomes Ready but
            // before `exoPlayer` is assigned, `update` binds a `null`
            // player on the PlayerView and the surface stays blank
            // until the next position-poll tick recomposes. Routing
            // through [PlayerStateEmitter] preserves this ordering
            // (the helper invokes `attach` synchronously and only
            // returns the Ready value after it completes — the single
            // `_uiState.value = …` write therefore still fires *after*
            // the side effect).
            //
            // Audit F#R1 — `attachExoPlayer` can throw (ExoPlayer init
            // failure, surface error, malformed `MediaItem`, OOM).
            // Without the catch inside the emitter, uiState wedges on
            // Loading forever and the user stares at an unresolvable
            // spinner. The emitter coerces any thrown attach into
            // [PlayerUiState.Unavailable] so the standard
            // Unavailable-screen path takes over.
            _uiState.value = PlayerStateEmitter.emit(resolved) { uri ->
                attachExoPlayer(uri, startMs)
            }
            // Task 4 — capture the per-segment durations once the Ready
            // value is in hand so the segment-jump helpers can use them.
            // Stays empty for the Unavailable path (jumps no-op safely).
            (_uiState.value as? PlayerUiState.Ready)?.let {
                segmentDurationsMs = it.segmentDurationsMs
            }
        }
    }

    /**
     * Lazily acquired by [PlayerScreen] via [getOrCreatePlayer]. The VM
     * keeps the reference so it can detach its lease in [onCleared]
     * regardless of how many recompositions / configuration changes the
     * screen goes through. Called on EVERY recomposition (incl. 250 ms
     * poll ticks) — must stay a cheap cached-reference read.
     */
    fun getOrCreatePlayer(): ExoPlayer? = exoPlayer

    private fun attachExoPlayer(uri: String, startMs: Long = 0L) {
        val app = getApplication<Application>()
        _firstFrameRendered.value = false
        // perf/player-lifecycle — lease the shared player instead of building
        // one. The engine hands it over in the neutral parked state (no media,
        // no surface, stopped); everything below is playback configuration and
        // stays the VM's job.
        val lease = engine.acquire()
        leaseToken = lease.token
        val player = lease.player.apply {
            // A reused player retains the previous session's playback
            // parameters; reset the session-scoped ones so a fresh nav is
            // byte-identical to the old fresh-build behavior (speed chip
            // starts 1×, seeks start on the default policy).
            setPlaybackParameters(PlaybackParameters.DEFAULT)
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
            setMediaItem(MediaItem.fromUri(resolvePlaybackUri(app, uri)))
            addListener(playerListener)
            if (startMs > 0L) seekTo(startMs) // honored as the initial position on prepare
            playWhenReady = true
            prepare()
        }
        exoPlayer = player
        pushProgress()
    }

    /**
     * Task 14 / ADR-0025 — turn the pure resolver's `mediaUri` string
     * into the actual [Uri] ExoPlayer consumes. Tier 1 / SAF produce a
     * `content://` URI and Tier 2/3 a `file://` URI — both parse
     * directly. A VAULTED recording instead arrives tagged with
     * [PlayerUriResolver.VAULT_FILE_SCHEME]: the app-private file path
     * MUST be exposed as a FileProvider `content://` URI (not the raw
     * `file://`, which throws `FileUriExposedException` once a media
     * source touches the private path). The FileProvider authority is
     * `${applicationId}.provider`, declared in AndroidManifest.xml and
     * rooted at `res/xml/file_paths.xml`'s `videos/` entry.
     */
    private fun resolvePlaybackUri(app: Application, uri: String): Uri {
        if (uri.startsWith(PlayerUriResolver.KEPT_SEGMENT_SCHEME)) {
            // ADR-0037 §5 V2 — kept-raw segment file lives in the app-private
            // session dir; FileProvider round-trip for the same reason as vault.
            val filename = uri.removePrefix(PlayerUriResolver.KEPT_SEGMENT_SCHEME)
            val dir = (app as RovaApp).sessionStore.sessionDir(sessionId)
            return androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.provider", java.io.File(dir, filename)
            )
        }
        if (!uri.startsWith(PlayerUriResolver.VAULT_FILE_SCHEME)) {
            return Uri.parse(uri)
        }
        val path = uri.removePrefix(PlayerUriResolver.VAULT_FILE_SCHEME)
        return androidx.core.content.FileProvider.getUriForFile(
            app,
            "${app.packageName}.provider",
            java.io.File(path)
        )
    }

    private fun persistPosition() {
        val pos = exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: return
        writeResume(sessionId, side, segmentIndex, pos)
    }

    fun togglePlayPause() {
        val p = exoPlayer ?: return
        if (p.isPlaying) { p.pause(); persistPosition() } else p.play()
        pushProgress()
    }

    /**
     * Audit F#1 — pause playback when the screen leaves the
     * foreground. Consumed by [PlayerScreen]'s `LifecycleEventObserver`
     * on `Lifecycle.Event.ON_STOP`. Distinct from [togglePlayPause] so
     * the screen never re-triggers `play()` automatically on
     * `ON_START` — letting the user tap play again is the intended
     * post-background contract (no MediaSession means there is no
     * notification surface to drive ambient playback safely).
     */
    fun pauseForBackground() {
        exoPlayer?.takeIf { it.isPlaying }?.pause()
        persistPosition()
        pushProgress(isPlaying = false)
    }

    fun seekRelative(deltaMs: Long) {
        val p = exoPlayer ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0L, p.duration.coerceAtLeast(0L))
        p.seekTo(target)
        pushProgress()
    }

    /**
     * Task 4 — absolute seek to a clamped position. Backs the timeline
     * tap-to-seek and is the single seek primitive the segment-jump
     * helpers route through. No-ops if the player isn't attached or the
     * duration isn't yet known (pre-STATE_READY).
     */
    fun seekTo(positionMs: Long) {
        val p = exoPlayer ?: return
        val dur = p.duration.takeIf { it > 0L } ?: return
        p.seekTo(positionMs.coerceIn(0L, dur))
        pushProgress()
    }

    /**
     * PR-7 — set the playback speed. Coerces arbitrary input onto the
     * supported set ([PlaybackSpeedPolicy.clampToSupported]) before applying
     * to ExoPlayer (Media3 1.4.1 `Player.setPlaybackSpeed` — no MediaSession
     * needed; no STATE_READY requirement). Gated on
     * COMMAND_SET_SPEED_AND_PITCH per the Media3 contract — a no-op (and no
     * UI write) if the command is unavailable. The optimistic
     * [PlaybackProgress.speed] write is reconciled by
     * [onPlaybackParametersChanged] (the player reports the active params, so
     * the chip never drifts if the player adjusts/rejects). Session-only:
     * nothing is persisted.
     */
    fun setPlaybackSpeed(speed: Float) {
        val p = exoPlayer ?: return
        if (!p.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return
        val applied = PlaybackSpeedPolicy.clampToSupported(speed)
        p.setPlaybackSpeed(applied)
        _progress.update { it.copy(speed = applied) }
    }

    /**
     * Task 4 — jump to the start of the next segment relative to the
     * current playback position. No-ops when already in the last segment
     * (math returns null).
     */
    fun jumpNextSegment() {
        SegmentedTimelineMath.nextSegmentStart(progress.value.positionMs, segmentDurationsMs)
            ?.let { seekTo(it) }
    }

    /**
     * Task 4 — jump to the start of the previous segment (or restart the
     * current one if already past its start, per the math's
     * `restartCurrentAfterMs` rule).
     */
    fun jumpPrevSegment() {
        SegmentedTimelineMath.prevSegmentStart(progress.value.positionMs, segmentDurationsMs)
            ?.let { seekTo(it) }
    }

    /**
     * Task 4 — begin a drag-scrub session. Pauses playback (remembering
     * whether it was playing so [endScrub] can resume), switches the
     * player to fast CLOSEST_SYNC seeks for responsive scrubbing, and
     * raises the [PlaybackProgress.isScrubbing] flag so the UI hands
     * position authority to the user's finger.
     */
    fun beginScrub() {
        val p = exoPlayer ?: return
        scrubWasPlaying = p.isPlaying
        p.pause()
        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        // M-2 — reset the throttle bookkeeping so this gesture's first
        // preview seek isn't delayed/suppressed by the previous gesture's
        // timestamp/target.
        lastScrubSeekAt = 0L
        lastScrubTarget = -1L
        // M-1 — only now is the gesture truly armed (pause + seek params
        // applied). updateScrub/endScrub gate on this.
        scrubActive = true
        _progress.update { it.copy(isScrubbing = true) }
    }

    /**
     * Task 4 — drive an in-flight scrub. The UI position updates
     * immediately on every call (so the thumb tracks the finger 1:1),
     * but the actual decoder seek is throttled to at most ~once / 180 ms
     * and only when the target has moved a meaningful amount — this
     * keeps the seek pipeline from thrashing during a fast drag while
     * still showing live frames.
     */
    fun updateScrub(targetMs: Long) {
        // M-1 — ignore stray updates not bracketed by an active beginScrub.
        if (!scrubActive) return
        val p = exoPlayer ?: return
        val dur = p.duration.takeIf { it > 0L } ?: return
        val target = targetMs.coerceIn(0L, dur)
        _progress.update { it.copy(positionMs = target) }
        val now = android.os.SystemClock.elapsedRealtime()
        val moved = lastScrubTarget < 0 ||
            kotlin.math.abs(target - lastScrubTarget) >= maxOf(500L, dur / 100)
        if (now - lastScrubSeekAt >= 180L && moved) {
            p.seekTo(target)
            lastScrubSeekAt = now
            lastScrubTarget = target
        }
    }

    /**
     * Task 4 — finish a drag-scrub session. Switches back to EXACT seeks,
     * performs the final committed seek, resumes playback if it was
     * playing when the scrub began, and lowers the
     * [PlaybackProgress.isScrubbing] flag.
     */
    fun endScrub(finalMs: Long) {
        // M-1 — capture whether a scrub was actually active; a spurious
        // endScrub without a matching beginScrub must not seek/resume.
        val wasActive = scrubActive
        val p = exoPlayer
        val dur = p?.duration?.takeIf { it > 0L }
        // I-1 — the committed seek + EXACT restore + resume-play are
        // conditional on a valid player/duration AND an active scrub…
        if (p != null && dur != null && wasActive) {
            p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            p.seekTo(finalMs.coerceIn(0L, dur))
            if (scrubWasPlaying) p.play()
        }
        // …but the STATE cleanup is UNCONDITIONAL so isScrubbing / seek
        // params / throttle state can never wedge if the player is null or
        // the duration is invalid at end-of-gesture.
        scrubActive = false
        lastScrubTarget = -1L
        _progress.update { it.copy(isScrubbing = false) }
        // pushProgress no-ops when no player exists; only call it when one
        // does so the cleared snapshot is published from a real position.
        if (p != null) pushProgress()
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (true) {
                pushProgress()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun pushProgress(isPlaying: Boolean? = null) {
        val p = exoPlayer ?: return
        val dur = if (p.duration > 0L) p.duration else 0L
        _progress.value = PlaybackProgress(
            // I-2 — during an active scrub the optimistic, finger-driven
            // position is authoritative; a poll tick / seek-discontinuity
            // callback must not yank the playhead back to the decoder's
            // currentPosition mid-gesture. Preserve the scrubbed value;
            // resume reading currentPosition once isScrubbing clears.
            positionMs = if (_progress.value.isScrubbing) _progress.value.positionMs
                         else p.currentPosition.coerceAtLeast(0L),
            durationMs = dur,
            isPlaying = isPlaying ?: p.isPlaying,
            // Task 4 — pushProgress rebuilds the whole snapshot, so it must
            // carry the in-flight scrub flag forward. Without this, a poll
            // tick or play/pause callback during an active drag-scrub would
            // reset isScrubbing to its default (false) and the UI would
            // wrongly hand position authority back to the poll loop
            // mid-gesture. The flag is owned exclusively by
            // beginScrub/endScrub; everyone else preserves it.
            isScrubbing = _progress.value.isScrubbing,
            // PR-7 — speed is owned by setPlaybackSpeed; pushProgress rebuilds
            // the whole snapshot, so it must carry the current speed forward or
            // a poll tick would reset the chip to 1×.
            speed = _progress.value.speed
        )
    }

    override fun onCleared() {
        stopPolling()
        // perf/player-lifecycle — detach the lease instead of releasing.
        // The VM removes ITS listener (playback ownership); the engine
        // parks the player (lifecycle ownership) and returns the position
        // snapshot it took at park — or, if a newer VM already took the
        // player over (stale-token race, review Required Fix 1), the
        // snapshot captured at takeover. Either way the persisted resume
        // position is the position of THIS VM's session, never the new
        // owner's. writeResume runs on app scope (sidecarWriteScope) —
        // NOT viewModelScope — so it is not dropped here.
        exoPlayer?.removeListener(playerListener)
        val snapshotMs = engine.detach(leaseToken)
        if (snapshotMs != null) {
            writeResume(sessionId, side, segmentIndex, snapshotMs.coerceAtLeast(0L))
        }
        exoPlayer = null
        super.onCleared()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L

        /**
         * Factory that pulls the [SessionManifest] loader off [RovaApp]
         * — same pattern as [com.aritr.rova.ui.recovery.RecoveryViewModel]
         * (see HistoryScreen.kt:107-135). Storage-unavailable boots
         * pass a no-op loader so the VM falls cleanly into Unavailable
         * instead of NPE-ing on `app.sessionStore`.
         */
        fun factory(
            app: RovaApp,
            sessionId: String,
            side: VideoSide? = null,
            segmentIndex: Int? = null
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val sessionStoreAvailable = app.videosRoot != null
                    val loader: suspend (String) -> SessionManifest? = if (sessionStoreAvailable) {
                        { id -> app.sessionStore.loadManifest(id) }
                    } else {
                        { _ -> null }
                    }
                    return PlayerViewModel(
                        application = app,
                        sessionId = sessionId,
                        side = side,
                        segmentIndex = segmentIndex,
                        loadManifest = loader,
                        readResume = { sid, s, seg -> app.readResumePosition(sid, s, seg) },
                        writeResume = { sid, s, seg, pos -> app.writeResumePosition(sid, s, seg, pos) },
                        engine = app.playerEngine,
                    ) as T
                }
            }
    }
}
