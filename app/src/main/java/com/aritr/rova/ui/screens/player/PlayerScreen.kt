package com.aritr.rova.ui.screens.player

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.aritr.rova.RovaApp
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.ui.screens.HistoryRowFormatters
import kotlinx.coroutines.launch
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
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as RovaApp
    // Phase 6.1b smoke-fix #3 — `side` is part of the VM identity for
    // P+L sessions: a single sessionId can produce two cards (one per
    // side), each routing to a distinct VM/ExoPlayer instance. Keying
    // by sessionId alone would force the second nav to re-bind the
    // first side's player to the second URI mid-composition.
    val viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.factory(app, sessionId, side),
        key = "player-$sessionId-${side?.name ?: "single"}"
    )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

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
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) }
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
                    onTrimOrEditPlaceholder = {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(EDITOR_PLACEHOLDER_MESSAGE)
                        }
                    },
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
            text = "Loading…",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun PlayerUnavailable(reason: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = reason,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onBack) {
                Text("Back")
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
    onTrimOrEditPlaceholder: () -> Unit,
    bindPlayerView: (PlayerView) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Full-bleed video surface. AndroidView handles attach /
        // detach; the DisposableEffect releases the surface reference
        // back to the VM so a subsequent player instance does not
        // inherit a stale Surface.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    // PlayerView default shutter background is black —
                    // configuring it explicitly via `setShutterBackgroundColor`
                    // would require @OptIn(UnstableApi::class) for a
                    // purely cosmetic match. Default is fine.
                    useController = false
                }
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

        // Top gradient + bar
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
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
                Spacer(modifier = Modifier.size(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = HistoryRowFormatters.formatPrimaryDateTime(state.startedAt),
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White.copy(alpha = 0.88f)
                    )
                    Text(
                        text = formatTopSub(
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
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        // WCAG 2.2 AA SC 1.4.3 (ADR-0020, PLR-01): 0.45α was
                        // ~3.3:1 over the dark player scrim; 0.72α clears 4.5:1.
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }
        }

        // Center play overlay (only when paused)
        if (!progress.isPlaying) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    // WCAG 2.2 AA SC 1.4.11 (ADR-0020, PLR-02): the play
                    // affordance is a functional UI component — its fill must
                    // clear 3:1 against the dark backdrop. 0.12α was ~1.4:1;
                    // 0.35α clears 3:1 over the dark-scene reference.
                    color = Color.White.copy(alpha = 0.35f),
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .size(64.dp)
                        .semantics { contentDescription = "Play" }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }

        // Bottom panel — info row + timeline + controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
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
                positionMs = progress.positionMs
            )
            ControlsRow(
                isPlaying = progress.isPlaying,
                onTogglePlay = onTogglePlay,
                onSeekBack = { onSeekRelative(-SEEK_DELTA_MS) },
                onSeekForward = { onSeekRelative(SEEK_DELTA_MS) },
                onTrim = onTrimOrEditPlaceholder,
                onEdit = onTrimOrEditPlaceholder
            )
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${formatMmSs(progress.positionMs)} / $perClipText per clip",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.45f)
        )
        Text(
            text = "Clip ${math.currentClipIndex} of ${math.totalClips}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.72f)
        )
    }
}

@Composable
private fun ControlsRow(
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onTrim: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onTrim) {
            Icon(
                imageVector = Icons.Default.ContentCut,
                contentDescription = "Trim (coming soon)",
                tint = Color.White.copy(alpha = 0.6f)
            )
        }
        IconButton(onClick = onSeekBack) {
            Icon(
                imageVector = Icons.Default.Replay10,
                contentDescription = "Rewind 10 seconds",
                tint = Color.White.copy(alpha = 0.85f)
            )
        }
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.10f),
            onClick = onTogglePlay,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = if (isPlaying) "Pause" else "Play" }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f)
                )
            }
        }
        IconButton(onClick = onSeekForward) {
            Icon(
                imageVector = Icons.Default.Forward10,
                contentDescription = "Forward 10 seconds",
                tint = Color.White.copy(alpha = 0.85f)
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit (coming soon)",
                tint = Color.White.copy(alpha = 0.6f)
            )
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

private fun formatTopSub(
    totalClips: Int,
    authoritativeTotalMs: Long,
    playerReportedTotalMs: Long,
    fallbackPerClipMs: Long
): String {
    val effectiveTotal = when {
        authoritativeTotalMs > 0L -> authoritativeTotalMs
        playerReportedTotalMs > 0L -> playerReportedTotalMs
        else -> fallbackPerClipMs * totalClips.coerceAtLeast(1)
    }
    val countWord = if (totalClips == 1) "clip" else "clips"
    return "$totalClips $countWord · ${formatMmSs(effectiveTotal)} total"
}

private const val SEEK_DELTA_MS: Long = 10_000L
private const val EDITOR_PLACEHOLDER_MESSAGE = "Editor coming in a future release"
