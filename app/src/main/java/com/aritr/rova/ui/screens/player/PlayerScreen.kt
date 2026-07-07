package com.aritr.rova.ui.screens.player

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import com.aritr.rova.ui.components.rememberReduceMotion
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.aritr.rova.R
import com.aritr.rova.RovaApp
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.LocalSecureFlagController
import com.aritr.rova.ui.components.RecordHudFormatters
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.screens.HistoryRowFormatters
import com.aritr.rova.ui.theme.IconRole
import com.aritr.rova.ui.theme.RovaIcons
import com.aritr.rova.ui.text.UiText
import com.aritr.rova.ui.text.resolve
import java.util.concurrent.TimeUnit

/**
 * Phase 2.5 — In-app Player route at `player/{sessionId}`.
 *
 * Mounts an [androidx.media3.ui.PlayerView] over the merged MP4 resolved
 * by [PlayerViewModel] / [PlayerUriResolver]. Layout follows
 * `mockups/new_uiux/04-video-player.html` (portrait variant): top
 * gradient with back chevron + title + sub-title; full-bleed video;
 * bottom gradient with info row, segmented timeline, and 5-button
 * control row (Trim, −10 s, Play/Pause primary, +10 s, Edit).
 *
 * Bottom navigation hidden for this route via the Option-A guard in
 * [com.aritr.rova.ui.MainScreen]: that Scaffold's bottom-bar slot
 * collapses to empty on any non-top-level route.
 *
 * Trim and Edit do NOT navigate — they fire a snackbar
 * `"Editor coming in a future release"`. The editor is NO-GO for v1.0
 * (UI_NAV_GRAPH §6.2).
 */
@Composable
fun PlayerScreen(
    sessionId: String,
    side: VideoSide? = null,
    // B5 / ADR-0025 — deterministic secure flag passed by the vault list
    // (true from the first composition). See the `shouldSecure` block below.
    secure: Boolean = false,
    segmentIndex: Int? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as RovaApp
    // Phase 6.1b smoke-fix #3 — `side` is part of the VM identity for
    // P+L sessions: a single sessionId can produce two cards (one per
    // side), each routing to a distinct VM/ExoPlayer instance. Keying
    // by sessionId alone would force the second nav to re-bind the
    // first side's player to the second URI mid-composition.
    // Task 4 — segmentIndex also joins the key so a kept-raw segment gets
    // its own VM/ExoPlayer instance (ADR-0037 §3).
    val viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.factory(app, sessionId, side, segmentIndex),
        key = "player-$sessionId-${side?.name ?: "single"}-${segmentIndex ?: "merged"}"
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val isVaulted by viewModel.isVaulted.collectAsStateWithLifecycle()
    val firstFrameRendered by viewModel.firstFrameRendered.collectAsStateWithLifecycle()
    // Poster hand-off from the Library tile — painted over the black shutter until the first video
    // frame renders, so entry doesn't flash a black "block". Taken once (clears the slot); null for
    // deep-link / process-death entries, which fall back to the default shutter.
    val entryPoster = remember { PlayerPosterHandoff.take(sessionId) }

    // B5 / ADR-0025 — block screenshots / recents-thumbnail capture while
    // playing a VAULTED recording, via the ref-counted window controller.
    //
    // `secure` is the deterministic nav-arg the vault list passes (true from
    // the very first composition); `isVaulted` is the async manifest-read
    // fallback for any non-vault entry point. We secure on EITHER — `secure`
    // closes the on-device race where the vault destination released its
    // FLAG_SECURE ref (as the player covered it) before the slower isVaulted
    // StateFlow had flipped true, leaving steady-state playback screenshottable.
    // shouldSecure is a stable key, so the ref is acquired exactly once and
    // released exactly once. Public playback (both false) is unaffected.
    val secureFlag = LocalSecureFlagController.current
    val shouldSecure = secure || isVaulted
    DisposableEffect(secureFlag, shouldSecure) {
        if (shouldSecure) secureFlag?.acquire()
        onDispose { if (shouldSecure) secureFlag?.release() }
    }
    // Audit F#1 — pause ExoPlayer when the host activity goes to the
    // background. Without this, audio keeps decoding from a detached
    // surface (Home / lock-screen / Recents picker). Compose's
    // collectAsStateWithLifecycle stops UI collection on STOPPED but
    // viewModelScope survives, and `playWhenReady = true` keeps the
    // ExoPlayer pipeline running. Hooking the lifecycle directly is
    // the v1.0 substitute for a MediaSession (NO-GO this slice).
    //
    // Deliberately does NOT auto-resume on ON_START — the contract is
    // "user explicitly taps play to resume." Auto-resuming would
    // surprise users who background the app intending to stop audio.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.pauseForBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = Color.Black
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            when (val state = uiState) {
                is PlayerUiState.Loading -> PlayerLoading()
                is PlayerUiState.Unavailable -> PlayerUnavailable(state.reason, onBack)
                is PlayerUiState.Ready -> PlayerReady(
                    state = state,
                    progress = progress,
                    onBack = onBack,
                    onTogglePlay = viewModel::togglePlayPause,
                    onSeekRelative = viewModel::seekRelative,
                    onSeek = viewModel::seekTo,
                    onScrubStart = viewModel::beginScrub,
                    onScrubUpdate = viewModel::updateScrub,
                    onScrubEnd = viewModel::endScrub,
                    onPrevSegment = viewModel::jumpPrevSegment,
                    onNextSegment = viewModel::jumpNextSegment,
                    onSetSpeed = viewModel::setPlaybackSpeed,
                    entryPoster = entryPoster,
                    firstFrameRendered = firstFrameRendered,
                    bindPlayerView = { playerView ->
                        playerView.player = viewModel.getOrCreatePlayer()
                    }
                )
            }
        }
    }
}

@Composable
private fun PlayerLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.player_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun PlayerUnavailable(reason: UiText, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = reason.resolve(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack) {
                Text(stringResource(R.string.player_back))
            }
        }
    }
}

@Composable
private fun PlayerReady(
    state: PlayerUiState.Ready,
    progress: PlaybackProgress,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekRelative: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubUpdate: (Long) -> Unit,
    onScrubEnd: (Long) -> Unit,
    onPrevSegment: () -> Unit,
    onNextSegment: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    entryPoster: android.graphics.Bitmap?,
    firstFrameRendered: Boolean,
    bindPlayerView: (PlayerView) -> Unit
) {
    var surfaceWidthPx by remember { mutableFloatStateOf(0f) }
    val showControlsCd = stringResource(R.string.player_show_controls_cd)

    var chromeVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val reduceMotion = rememberReduceMotion()

    // PR-7 — when TalkBack touch-exploration is active, auto-hide is
    // suppressed entirely: removing a focused control from composition
    // (AnimatedVisibility) would trap/lose the screen-reader's focus (codex).
    // Read once at composition — a mid-session TalkBack toggle is a rare edge
    // that re-enters the screen anyway.
    val context = LocalContext.current
    val touchExplorationActive = remember {
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .isTouchExplorationEnabled
    }

    // PR-7 — auto-hide countdown. Runs only while playing + visible + not
    // scrubbing (AutoHideChromePolicy) AND not under touch-exploration.
    // Pausing / scrubbing changes a key and cancels-and-restarts this effect;
    // interactionTick re-arms the timer on every chrome interaction even when
    // chromeVisible was already true (a tap/seek/speed-cycle that doesn't flip
    // a key would otherwise let an in-flight timer hide chrome mid-interaction).
    LaunchedEffect(progress.isPlaying, progress.isScrubbing, chromeVisible, interactionTick) {
        if (!touchExplorationActive &&
            AutoHideChromePolicy.shouldRunHideTimer(
                isPlaying = progress.isPlaying,
                isScrubbing = progress.isScrubbing,
                chromeVisible = chromeVisible
            )
        ) {
            kotlinx.coroutines.delay(AutoHideChromePolicy.DEFAULT_TIMEOUT_MS)
            chromeVisible = false
        }
    }

    // PR-7 — pausing always reveals controls (spec C4). Reactive on isPlaying.
    LaunchedEffect(progress.isPlaying) {
        if (!progress.isPlaying) chromeVisible = AutoHideChromePolicy.onPlaybackPaused()
    }

    val onSingleTap: () -> Unit = {
        chromeVisible = AutoHideChromePolicy.onUserTap(chromeVisible)
        interactionTick++
    }

    // Entry-poster state: wrap once (not per recomposition) and bound its lifetime — if the first
    // frame never renders (no video track / odd Ready-without-render), drop the poster after a
    // ceiling so it can't stick over playback (codex Q4). The error path already routes to
    // Unavailable, so this only covers the rare never-renders case.
    val posterBitmap = remember(entryPoster) { entryPoster?.asImageBitmap() }
    var posterTimedOut by remember { mutableStateOf(false) }
    if (posterBitmap != null) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(PLAYER_ENTRY_POSTER_MAX_MS)
            posterTimedOut = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Full-bleed video surface. AndroidView handles attach /
        // detach; the DisposableEffect releases the surface reference
        // back to the VM so a subsequent player instance does not
        // inherit a stale Surface.
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { surfaceWidthPx = it.width.toFloat() }
                // PR-7 — single-tap shows chrome (onTap fires only after the
                // ~300 ms double-tap window elapses; owner Q5=always-show,
                // Q6=latency accepted). Double-tap maps the x onto an
                // EdgeSeekZones band: left=−10s, right=+10s, center=play/pause,
                // and reveals chrome so the playhead jump is visible (Q4=no flash).
                // The gesture sits on the full-bleed video Box (z-below the
                // chrome) so taps on real control buttons are consumed by
                // those buttons (spec C5). The semantics Role+CD+onClick keeps
                // the surface an activatable labeled control for TalkBack
                // (checkA11yClickableHasRole); the ±10s accessible path stays
                // the explicit Replay10/Forward10 buttons (double-tap is
                // TalkBack-invisible, §3.4).
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onSingleTap() },
                        onDoubleTap = { offset ->
                            when (EdgeSeekZones.zoneFor(offset.x, surfaceWidthPx)) {
                                EdgeSeekZones.Zone.SEEK_BACK -> onSeekRelative(-SEEK_DELTA_MS)
                                EdgeSeekZones.Zone.SEEK_FORWARD -> onSeekRelative(SEEK_DELTA_MS)
                                EdgeSeekZones.Zone.TOGGLE -> onTogglePlay()
                            }
                            onSingleTap() // reveal chrome + restart hide timer
                        }
                    )
                }
                .semantics {
                    contentDescription = showControlsCd
                    role = Role.Button
                    onClick { onSingleTap(); true }
                },
            factory = { ctx ->
                // Inflate from XML (not `PlayerView(ctx)`) so the surface is a
                // TextureView — surface_type is an XML-only attr. The default
                // SurfaceView renders on a hardware overlay the Compose entry
                // poster can't cover (device-verified black block); a TextureView
                // composites in the view layer so the poster masks the build/
                // prepare gap. See res/layout/player_surface.xml.
                android.view.LayoutInflater.from(ctx)
                    .inflate(com.aritr.rova.R.layout.player_surface, null) as PlayerView
            },
            update = { view ->
                bindPlayerView(view)
                // Audit F#2 — keep the screen on while playback is
                // active so a long merged session (e.g. 20 clips × 30 s
                // = 10 min) doesn't black out at the device's screen
                // timeout. Bound to `progress.isPlaying` rather than
                // statically true so the screen sleeps normally while
                // paused (saves battery on a left-open player).
                view.keepScreenOn = progress.isPlaying
            }
        )

        // Entry poster — mask the black PlayerView shutter during ExoPlayer build/prepare with the
        // Library tile thumbnail (a player-owned copy) until the first frame renders, so entering
        // the player doesn't flash a black "block". Removed the instant onRenderedFirstFrame lands
        // (revealing the just-decoded video frame underneath) or after the ceiling above.
        if (posterBitmap != null && !firstFrameRendered && !posterTimedOut) {
            Image(
                bitmap = posterBitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize()
            )
        }

        // Top gradient + bar
        AnimatedVisibility(
            visible = chromeVisible,
            enter = if (reduceMotion) EnterTransition.None else fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.82f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        SemanticIcon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.player_back_cd),
                            role = IconRole.Default
                        )
                    }
                    Spacer(modifier = Modifier.size(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = HistoryRowFormatters.formatPrimaryDateTime(state.startedAt),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White.copy(alpha = 0.88f)
                        )
                        val topSubTotalMs = effectiveTotalMs(
                            totalClips = state.totalClips,
                            // Audit F#4 — manifest-derived sum is the
                            // authoritative total. ExoPlayer-reported
                            // duration is only consulted when the
                            // manifest sum is 0 (defensive — segment
                            // durations populate at finalize time, so
                            // this fallback should never fire).
                            authoritativeTotalMs = state.totalDurationFromSegmentsMs,
                            playerReportedTotalMs = progress.durationMs,
                            fallbackPerClipMs = state.perClipDurationMs
                        )
                        Text(
                            text = pluralStringResource(
                                R.plurals.player_top_sub,
                                state.totalClips,
                                state.totalClips,
                                formatMmSs(topSubTotalMs)
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            // WCAG 2.2 AA SC 1.4.3 (ADR-0020, PLR-01): 0.45α was
                            // ~3.3:1 over the dark player scrim; 0.72α clears 4.5:1.
                            color = Color.White.copy(alpha = 0.72f)
                        )
                    }
                }
            }
        }

        // PR-7 — every chrome interaction bumps interactionTick so the hide timer
        // restarts (codex). togglePlay flips isPlaying and scrub flips isScrubbing,
        // which are already keys — but seek/seekTo/speed/segment-jumps are not.
        val onSeekRelativeBump: (Long) -> Unit = { onSeekRelative(it); interactionTick++ }
        val onSeekBump: (Long) -> Unit = { onSeek(it); interactionTick++ }
        val onPrevSegmentBump: () -> Unit = { onPrevSegment(); interactionTick++ }
        val onNextSegmentBump: () -> Unit = { onNextSegment(); interactionTick++ }
        val onCycleSpeedBump: () -> Unit = { onSetSpeed(PlaybackSpeedPolicy.next(progress.speed)); interactionTick++ }

        // Bottom panel — info row + timeline + controls
        AnimatedVisibility(
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = chromeVisible,
            enter = if (reduceMotion) EnterTransition.None else fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.90f)
                            )
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoRow(state = state, progress = progress)
                SegmentedTimeline(
                    segmentDurationsMs = state.segmentDurationsMs,
                    positionMs = progress.positionMs,
                    isScrubbing = progress.isScrubbing,
                    onSeek = onSeekBump,
                    onScrubStart = onScrubStart,
                    onScrubUpdate = onScrubUpdate,
                    onScrubEnd = onScrubEnd,
                    onPrevSegment = onPrevSegmentBump,
                    onNextSegment = onNextSegmentBump
                )
                ControlsRow(
                    isPlaying = progress.isPlaying,
                    speed = progress.speed,
                    onTogglePlay = onTogglePlay,
                    onSeekBack = { onSeekRelativeBump(-SEEK_DELTA_MS) },
                    onSeekForward = { onSeekRelativeBump(SEEK_DELTA_MS) },
                    onCycleSpeed = onCycleSpeedBump
                )
            }
        }
    }
}

@Composable
private fun InfoRow(state: PlayerUiState.Ready, progress: PlaybackProgress) {
    val math = remember(state.segmentDurationsMs, progress.positionMs) {
        SegmentedTimelineMath.compute(state.segmentDurationsMs, progress.positionMs)
    }
    // Show the CURRENT segment's actual recorded length, not the
    // configured per-clip length — an early-stopped last clip is
    // shorter than what the user configured, and this line should
    // agree with the timeline fill and the header total. Resolver
    // guarantees a non-empty list; the getOrElse default is defensive.
    val currentClipDurationMs =
        state.segmentDurationsMs.getOrElse(math.currentClipIndex - 1) { state.perClipDurationMs }
    val perClipText = formatPerClip(currentClipDurationMs)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.player_position_per_clip,
                    formatMmSs(progress.positionMs),
                    perClipText
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.45f)
            )
            Text(
                text = stringResource(
                    R.string.player_clip_n_of_m,
                    math.currentClipIndex,
                    math.totalClips
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f)
            )
        }
        WallClockReadout(state = state, positionMs = progress.positionMs)
    }
}

/**
 * PR-6b (ADR-0032) — wall-clock time-of-day readout for the current playhead
 * position. Shows the real-world recording time (e.g. "2:47:03 PM") and an
 * optional inter-clip gap chip when the player crosses a gap between segments.
 *
 * Approx prefix (~) is shown when the current segment's wall-start was
 * synthesized (legacy schema or recovered orphan) — see [PlayerUiState.Ready.wallStartIsApproxMask].
 *
 * The readout is display-only (no clickable) to avoid requiring a semantics Role.
 * The gap chip is static (no animation). Any future animated transition must gate
 * on [com.aritr.rova.ui.components.rememberReduceMotion] per ADR-0020 /
 * checkA11yAnimationGated.
 */
@Composable
private fun WallClockReadout(state: PlayerUiState.Ready, positionMs: Long) {
    val context = LocalContext.current

    val zone = remember { java.util.TimeZone.getDefault() }
    val locale = remember(context) { context.resources.configuration.locales[0] }
    val is24h = remember(context) { android.text.format.DateFormat.is24HourFormat(context) }

    val starts = state.segmentWallStartsMs
    val durations = state.segmentDurationsMs
    val approxMask = state.wallStartIsApproxMask

    val spansMidnight = remember(starts, durations, zone) {
        if (starts.isEmpty()) false
        else WallClockTimeline.spansMidnight(
            starts.first(),
            starts.last() + (durations.lastOrNull() ?: 0L),
            zone
        )
    }

    val readout = remember(starts, durations, approxMask, positionMs) {
        WallClockTimeline.readoutAt(starts, durations, approxMask, positionMs)
    }

    val timeText = remember(readout.instantMs, zone, locale, is24h, spansMidnight) {
        RecordHudFormatters.formatTimeOfDay(readout.instantMs, zone, locale, is24h, spansMidnight)
    }

    val displayText = if (readout.isApprox) {
        stringResource(R.string.player_wallclock_approx_prefix, timeText)
    } else {
        timeText
    }
    val cdText = stringResource(R.string.player_wallclock_cd, timeText)

    val gapBeforeMs = readout.gapBeforeMs

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = displayText,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f),
            modifier = Modifier.semantics { contentDescription = cdText }
        )
        if (gapBeforeMs != null) {
            val gapText = RecordHudFormatters.formatWallClockGap(gapBeforeMs).resolve()
            // Gap chip is static (no animation); if a future slice adds a
            // fade-in it must read rememberReduceMotion() and gate with
            // snap() when true (ADR-0020 / checkA11yAnimationGated).
            Text(
                text = gapText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun ControlsRow(
    isPlaying: Boolean,
    speed: Float,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onCycleSpeed: () -> Unit
) {
    val playPauseCd = if (isPlaying) {
        stringResource(R.string.player_pause_cd)
    } else {
        stringResource(R.string.player_play_cd)
    }
    val locale = LocalConfiguration.current.locales[0]
    val speedLabel = PlaybackSpeedPolicy.label(speed, locale)
    val speedCd = stringResource(R.string.player_speed_cd, speedLabel)

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSeekBack) {
                SemanticIcon(
                    imageVector = Icons.Default.Replay10,
                    contentDescription = stringResource(R.string.player_rewind_cd),
                    role = IconRole.Default
                )
            }
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.10f),
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = playPauseCd }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    SemanticIcon(
                        imageVector = PlayerIconSpec.transportGlyph(isPlaying),
                        contentDescription = null,
                        role = IconRole.Default
                    )
                }
            }
            IconButton(onClick = onSeekForward) {
                SemanticIcon(
                    imageVector = Icons.Default.Forward10,
                    contentDescription = stringResource(R.string.player_forward_cd),
                    role = IconRole.Default
                )
            }
        }
        // PR-7 speed chip — cycling 1×→1.5×→2×→0.5×→1× (owner Q1=A). Mirrors
        // the play/pause Surface(onClick) a11y pattern: ≥48dp target,
        // contentDescription announces current speed + that tapping changes it.
        // Surface(onClick) supplies Role.Button (checkA11yClickableHasRole).
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.10f),
            onClick = onCycleSpeed,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(48.dp)
                .semantics { contentDescription = speedCd }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = speedLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.88f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun formatMmSs(millis: Long): String {
    val total = millis.coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(total)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(total) % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun formatPerClip(perClipDurationMs: Long): String {
    if (perClipDurationMs <= 0L) return "—"
    val totalSec = TimeUnit.MILLISECONDS.toSeconds(perClipDurationMs)
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun effectiveTotalMs(
    totalClips: Int,
    authoritativeTotalMs: Long,
    playerReportedTotalMs: Long,
    fallbackPerClipMs: Long
): Long = when {
    authoritativeTotalMs > 0L -> authoritativeTotalMs
    playerReportedTotalMs > 0L -> playerReportedTotalMs
    else -> fallbackPerClipMs * totalClips.coerceAtLeast(1)
}

private const val SEEK_DELTA_MS: Long = 10_000L

// Ceiling for the entry poster: if ExoPlayer never renders a first frame within this window, drop
// the poster so it can't stick over playback. Comfortably above a normal build+prepare+decode.
private const val PLAYER_ENTRY_POSTER_MAX_MS: Long = 1_500L
