package com.aritr.rova.ui.screens.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.aritr.rova.RovaApp
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.utils.RovaLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 2.5 — VM for the in-app Player route.
 *
 * Owns:
 *  - the manifest-driven [PlayerUiState] (Loading → Ready / Unavailable)
 *  - the singleton [ExoPlayer] for the screen, released in [onCleared]
 *  - a 250 ms position-poll loop that updates [PlaybackProgress] while
 *    playback is live; the loop is cheap (one `currentPosition` read per
 *    tick) and does not run while paused / unavailable
 *
 * Deliberately non-singleton: a player instance per VM is the contract.
 * If the user navigates to a different `player/{sessionId}`, a fresh VM
 * is created via the NavHost composable scope and the prior VM is
 * cleared (releasing the prior `ExoPlayer`). MainScreen.kt uses
 * `launchSingleTop = true` for tab routes only; argumented routes get
 * a fresh entry per id.
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
    private val loadManifest: suspend (String) -> SessionManifest?
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private var exoPlayer: ExoPlayer? = null
    private var pollJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            pushProgress(isPlaying = isPlaying)
            if (isPlaying) startPolling() else stopPolling()
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
            _uiState.value = PlayerUiState.Unavailable("Playback failed")
        }
    }

    init {
        viewModelScope.launch {
            val manifest = withContext(Dispatchers.IO) {
                runCatching { loadManifest(sessionId) }.getOrNull()
            }
            val resolved = PlayerUriResolver.resolve(manifest)
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
                attachExoPlayer(uri)
            }
        }
    }

    /**
     * Lazily acquired by [PlayerScreen] via [getOrCreatePlayer]. The VM
     * keeps the reference so it can release in [onCleared] regardless
     * of how many recompositions / configuration changes the screen
     * goes through.
     */
    fun getOrCreatePlayer(): ExoPlayer? = exoPlayer

    private fun attachExoPlayer(uri: String) {
        val app = getApplication<Application>()
        val player = ExoPlayer.Builder(app).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
            addListener(playerListener)
            playWhenReady = true
            prepare()
        }
        exoPlayer = player
        pushProgress()
    }

    fun togglePlayPause() {
        val p = exoPlayer ?: return
        if (p.isPlaying) p.pause() else p.play()
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
        pushProgress(isPlaying = false)
    }

    fun seekRelative(deltaMs: Long) {
        val p = exoPlayer ?: return
        val target = (p.currentPosition + deltaMs).coerceIn(0L, p.duration.coerceAtLeast(0L))
        p.seekTo(target)
        pushProgress()
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
            positionMs = p.currentPosition.coerceAtLeast(0L),
            durationMs = dur,
            isPlaying = isPlaying ?: p.isPlaying
        )
    }

    override fun onCleared() {
        stopPolling()
        exoPlayer?.let {
            it.removeListener(playerListener)
            it.release()
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
        fun factory(app: RovaApp, sessionId: String): ViewModelProvider.Factory =
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
                        loadManifest = loader
                    ) as T
                }
            }
    }
}
