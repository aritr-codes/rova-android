package com.aritr.rova.ui.screens.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import com.aritr.rova.ui.components.rememberReduceTransparency
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
import com.aritr.rova.ui.theme.PlayerTokens
import com.aritr.rova.ui.theme.RovaIcons
import com.aritr.rova.ui.theme.RovaMotion
import com.aritr.rova.ui.theme.RovaTrustTokens
import com.aritr.rova.ui.text.UiText
import com.aritr.rova.ui.text.resolve
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/**
 * In-app Player route at `player/{sessionId}`.
 *
 * Mounts an [androidx.media3.ui.PlayerView] over the merged MP4 resolved
 * by [PlayerViewModel] / [PlayerUriResolver]. The over-media chrome is the
 * token-governed dark shell transcribed from `docs/design/player-core.html`
 * (the canonical visual authority): a 96dp top scrim band with back chevron +
 * grouped date/time + "N clips · total" sub-title; full-bleed hero video with
 * no mid-frame scrim; a bottom scrim band carrying InfoRow → SegmentedTimeline
 * → ControlsRow (−10 s · play/pause primary · +10 s, with a speed chip pinned
 * to CenterEnd). Colours come from [com.aritr.rova.ui.theme.RovaTrustTokens]
 * (over-media ink) + [com.aritr.rova.ui.theme.PlayerTokens] (scrims / glyph
 * fill / bar); contrast is proven centrally by `PlayerOverlayContrastTest`,
 * not per-literal.
 *
 * Bottom navigation hidden for this route via the Option-A guard in
 * [com.aritr.rova.ui.MainScreen]: that Scaffold's bottom-bar slot
 * collapses to empty on any non-top-level route.
 *
 * There is no in-player editor in V1 (Media3 1.4.1 envelope; editing is its
 * own later spec). The ControlsRow carries transport only.
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
    // player-states.html §04/§06 — the resume cue + the runtime Ready→Unavailable
    // flip travel on their own flows so the surface is never torn down for a
    // runtime error (§06: no hard-cut to black).
    val playbackError by viewModel.playbackError.collectAsStateWithLifecycle()
    val resumeCue by viewModel.resumeCue.collectAsStateWithLifecycle()
    // player-sharing.html §03/§05 — the Share target for the reviewed identity.
    // Drives the trailing Share slot's presence + the DualShot side-sheet fork.
    val sharePlan by viewModel.sharePlan.collectAsStateWithLifecycle()
    // player-info.html §03 — the read-only Info model. Non-null iff Ready; drives
    // the leading Info slot's presence + the Info sheet contents.
    val infoModel by viewModel.infoModel.collectAsStateWithLifecycle()
    // Poster hand-off from the Library tile — painted over the black shutter until the first video
    // frame renders, so entry doesn't flash a black "block". Taken once (clears the slot); null for
    // deep-link / process-death entries, which fall back to the default shutter.
    // player-states.html §03 — the SAME poster is the Loading backdrop too
    // (poster-first, never a bare spinner), so the conversion is hoisted here and
    // shared by the Loading and Ready faces across the Loading→Ready handoff.
    val entryPoster = remember { PlayerPosterHandoff.take(sessionId) }
    val posterBitmap: ImageBitmap? = remember(entryPoster) { entryPoster?.asImageBitmap() }

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
                is PlayerUiState.Loading -> PlayerLoading(poster = posterBitmap)
                is PlayerUiState.Unavailable -> PlayerUnavailable(
                    reason = state.reason,
                    onRetry = viewModel::retry,
                    onBack = onBack
                )
                is PlayerUiState.Ready -> PlayerReady(
                    state = state,
                    progress = progress,
                    playbackError = playbackError,
                    resumeCue = resumeCue,
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
                    onRetry = viewModel::retry,
                    onStartOver = viewModel::startOver,
                    sharePlan = sharePlan,
                    onResolveShareUris = viewModel::resolveShareUris,
                    infoModel = infoModel,
                    poster = posterBitmap,
                    firstFrameRendered = firstFrameRendered,
                    bindPlayerView = { playerView ->
                        playerView.player = viewModel.getOrCreatePlayer()
                    }
                )
            }
        }
    }
}

/**
 * player-states.html §03 — poster-first Loading. The warm entry poster (the
 * Library handoff thumbnail) IS the loading backdrop; on the cold path (no
 * poster — vault / legacy / deep-link) the black Scaffold ground shows through.
 * A loading cue (spinner + label) is **grace-gated**: suppressed under
 * `--loading-grace` (400 ms) so a fast resolve never flashes a spinner. The
 * escalated cue announces politely (§08).
 */
@Composable
private fun PlayerLoading(poster: ImageBitmap?) {
    Box(modifier = Modifier.fillMaxSize()) {
        poster?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSize()
            )
        }
        var showCue by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(LOADING_GRACE_MS)
            showCue = true
        }
        if (showCue) {
            val loadingLabel = stringResource(R.string.player_loading)
            Column(
                modifier = Modifier.matchParentSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                PlayerLoadingSpinner()
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = loadingLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    // player-states.html §03: loading label → --media-ink-body (.55).
                    color = RovaTrustTokens.mediaInkBody,
                    // §08: an escalated Loading announces politely — once, when the
                    // cue crosses the grace window. A sub-grace resolve stays silent
                    // and Ready announces instead.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
    }
}

/**
 * player-states.html §01 `.spinner` (:170–:174) — a ring with a rotating head
 * arc. Reduce-motion collapses it to a static even ring (no head, no motion) so
 * the cue never conveys meaning by motion (SC 2.3.3, ADR-0020 /
 * checkA11yAnimationGated — the same-file `rememberReduceMotion()` read
 * satisfies the gate).
 */
@Composable
private fun PlayerLoadingSpinner() {
    val reduceMotion = rememberReduceMotion()
    val strokePx = with(LocalDensity.current) { PlayerTokens.spinnerStrokeWidth.toPx() }
    if (reduceMotion) {
        Canvas(modifier = Modifier.size(PlayerTokens.spinnerSize)) {
            val inset = strokePx / 2f
            drawArc(
                color = PlayerTokens.spinnerStatic,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokePx, size.height - strokePx),
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    } else {
        val transition = rememberInfiniteTransition(label = "player-loading-spinner")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(SPINNER_PERIOD_MS, easing = LinearEasing)
            ),
            label = "spinner-angle"
        )
        Canvas(modifier = Modifier.size(PlayerTokens.spinnerSize)) {
            val inset = strokePx / 2f
            val arcTopLeft = Offset(inset, inset)
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            drawArc(
                color = PlayerTokens.spinnerTrack,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = PlayerTokens.spinnerHead,
                startAngle = angle,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * player-states.html §05 — the Unavailable face over black (an ENTRY-time
 * failure: the resolver failed closed before a surface existed). The runtime
 * Ready→Unavailable flip renders the SAME [PlayerUnavailableCard] over the
 * frozen frame instead (§06, see [PlayerReady]).
 */
@Composable
private fun PlayerUnavailable(reason: UiText, onRetry: () -> Unit, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        PlayerUnavailableCard(reason = reason, onRetry = onRetry, onBack = onBack)
    }
}

/**
 * player-states.html §01/§05 — the neutral **triad** card: neutral glyph + one
 * plain line + the right action. Opaque `--pin-surface` substrate, `--media-ink`
 * title, `--media-ink-body` detail. The action class (Retry+Back vs Back-only)
 * and glyph archetype come from the pure [PlayerUnavailablePresentation]
 * classifier. Shared by the entry-time face ([PlayerUnavailable]) and the
 * runtime flip overlay (§06) — one design, two reach-points. Announces
 * assertively (§08).
 */
@Composable
private fun PlayerUnavailableCard(reason: UiText, onRetry: () -> Unit, onBack: () -> Unit) {
    val view = PlayerUnavailablePresentation.of(reason)
    val title = reason.resolve()
    val detail = stringResource(view.detailRes)
    val cardShape = RoundedCornerShape(14.dp) // player-core --r-md
    Column(
        modifier = Modifier
            .widthIn(max = PlayerTokens.errCardMaxWidth)
            .clip(cardShape)
            .background(RovaTrustTokens.pinSurface)
            .border(1.dp, RovaTrustTokens.mediaEdgeTop, cardShape)
            .padding(PlayerTokens.errCardPadding)
            // §08: a failure is announced ASSERTIVELY — never silent. Merge the
            // title + detail into one node so TalkBack speaks the whole cause.
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Neutral state glyph on a hairline ring — NEVER severity red (§05 frozen).
        // Routed through SemanticIcon (role=Secondary = the dimmed neutral tint,
        // the gate-clean realization of --media-ink-dim; no raw-alpha Icon).
        Box(
            modifier = Modifier
                .size(PlayerTokens.errGlyphSize)
                .border(1.5.dp, RovaTrustTokens.mediaEdgeTop, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            SemanticIcon(
                imageVector = playerUnavailableGlyph(view.glyph),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                role = IconRole.Secondary
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = RovaTrustTokens.mediaInk,
            textAlign = TextAlign.Center
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = RovaTrustTokens.mediaInkBody,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (view.action) {
                PlayerUnavailablePresentation.Action.RETRY -> {
                    PlayerStateButton(
                        label = stringResource(R.string.player_retry),
                        primary = true,
                        onClick = onRetry
                    )
                    PlayerStateButton(
                        label = stringResource(R.string.player_back),
                        primary = false,
                        onClick = onBack
                    )
                }
                // Permanent — Back is the sole path, promoted to the neutral primary.
                PlayerUnavailablePresentation.Action.DISMISS -> {
                    PlayerStateButton(
                        label = stringResource(R.string.player_back),
                        primary = true,
                        onClick = onBack
                    )
                }
            }
        }
    }
}

/** Maps the classifier's neutral glyph archetype to a Material vector (§01 specimens). */
private fun playerUnavailableGlyph(glyph: PlayerUnavailablePresentation.Glyph): ImageVector =
    when (glyph) {
        PlayerUnavailablePresentation.Glyph.RETRY -> Icons.Default.Refresh
        PlayerUnavailablePresentation.Glyph.NOT_FOUND -> Icons.Default.VideocamOff
        PlayerUnavailablePresentation.Glyph.PLAYBACK_ALERT -> Icons.Outlined.ErrorOutline
    }

/**
 * player-states.html §05 — a state action button. One neutral primary
 * (`--glyph-fill`, no accent — the core rule), Back demoted to a ghost. Min
 * height 44 dp with a 48 dp interactive target (`minimumInteractiveComponentSize`).
 * Surface(onClick) supplies Role.Button (checkA11yClickableHasRole).
 */
@Composable
private fun PlayerStateButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50) // --r-pill
    val sizing = Modifier
        .minimumInteractiveComponentSize()
        .heightIn(min = PlayerTokens.stateButtonMinHeight)
    if (primary) {
        Surface(shape = shape, color = PlayerTokens.glyphFill, onClick = onClick, modifier = sizing) {
            Box(modifier = Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = RovaTrustTokens.mediaInk)
            }
        }
    } else {
        Surface(
            shape = shape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, RovaTrustTokens.mediaEdgeTop),
            onClick = onClick,
            modifier = sizing
        ) {
            Box(modifier = Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                Text(text = label, style = MaterialTheme.typography.labelLarge, color = RovaTrustTokens.mediaInkDim)
            }
        }
    }
}

/**
 * player-states.html §04 — the quiet, non-modal resume cue. A pill naming the
 * restored point + a Start-over peer. When [PlayerResumeCue.atEnd] (parked at
 * the clip end) it drops the "Resuming · m:ss" label — there is nothing to
 * resume — leaving Start over as the sole path. Announces politely (§08); the
 * announcement is suppressed at end.
 */
@Composable
private fun ResumeCue(cue: PlayerResumeCue, onStartOver: () -> Unit) {
    val pillShape = RoundedCornerShape(percent = 50)
    val posText = formatMmSs(cue.positionMs)
    val resumingText = stringResource(R.string.player_resuming) + " · " + posText
    val resumeAnnounce = stringResource(R.string.player_resume_cd, posText)
    val startOverLabel = stringResource(R.string.player_start_over)
    Row(
        modifier = Modifier
            .clip(pillShape)
            .background(RovaTrustTokens.pinSurface)
            .border(1.dp, RovaTrustTokens.mediaEdgeTop, pillShape)
            .padding(start = 14.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
            .then(
                if (cue.atEnd) {
                    Modifier
                } else {
                    Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = resumeAnnounce
                    }
                }
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!cue.atEnd) {
            Text(
                text = resumingText,
                style = MaterialTheme.typography.bodySmall,
                color = RovaTrustTokens.mediaInk
            )
        }
        Surface(
            shape = pillShape,
            color = PlayerTokens.glyphFill,
            onClick = onStartOver,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .heightIn(min = 36.dp)
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = startOverLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = RovaTrustTokens.mediaInk
                )
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
    onRetry: () -> Unit,
    onStartOver: () -> Unit,
    sharePlan: PlayerSharePlan,
    onResolveShareUris: suspend (List<PlayerShareArtifact>) -> List<Uri>,
    infoModel: PlayerInfoModel?,
    playbackError: UiText?,
    resumeCue: PlayerResumeCue?,
    poster: ImageBitmap?,
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

    // player-gestures.html §06 — swipe-down-to-dismiss. A vertical drag that
    // begins on the media body rubber-bands the frame (translate 1:1 + a gentle
    // scale); release commits via [SwipeDismissPolicy] → the EXISTING nav-pop
    // (onBack), else springs back. Lives on the video body only (z-below chrome),
    // so a drag on the timeline/controls belongs to that control by region
    // ownership (§08 Rule 1) and never reaches here.
    val scope = rememberCoroutineScope()
    val dragOffsetY = remember { Animatable(0f) }

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

    // Entry-poster state: converted once in PlayerScreen and shared with the
    // Loading face. Bound its lifetime — if the first frame never renders (no
    // video track / odd Ready-without-render), drop the poster after a ceiling
    // so it can't stick over playback (codex Q4). The error path already routes
    // to the runtime overlay / Unavailable, so this only covers the rare
    // never-renders case.
    val posterBitmap = poster
    var posterTimedOut by remember { mutableStateOf(false) }
    if (posterBitmap != null) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(PLAYER_ENTRY_POSTER_MAX_MS)
            posterTimedOut = true
        }
    }

    // player-states.html §04 — resume-cue lifetime. Visible on appearance; self-
    // dismisses after --resume-cue-dwell (4 s) OR on the first chrome interaction
    // (interactionTick > 0). Re-keyed on the cue so a fresh cue re-arms.
    var resumeCueVisible by remember(resumeCue) { mutableStateOf(resumeCue != null) }
    if (resumeCue != null && resumeCueVisible) {
        LaunchedEffect(resumeCue) {
            kotlinx.coroutines.delay(RESUME_CUE_DWELL_MS)
            resumeCueVisible = false
        }
    }
    LaunchedEffect(interactionTick) {
        if (interactionTick > 0) resumeCueVisible = false
    }

    // player-states.html §06 — retain the last runtime error reason so the card
    // stays rendered through its fade-OUT after Retry clears playbackError.
    var lastPlaybackError by remember { mutableStateOf<UiText?>(null) }
    if (playbackError != null) lastPlaybackError = playbackError

    // player-sharing.html §04/§08 — DualShot side sheet (non-null = shown) + the
    // fail-closed toast strings (read in composition, never inside a lambda).
    var shareSheet by remember { mutableStateOf<PlayerSharePlan.DualChoice?>(null) }
    // player-info.html §01 — the read-only Info sheet is open (true) or dismissed.
    var infoSheetOpen by remember { mutableStateOf(false) }
    val shareNotReadyMsg = stringResource(R.string.player_share_not_ready)
    val shareNoAppMsg = stringResource(R.string.history_share_no_app)

    Box(modifier = Modifier.fillMaxSize()) {
        // §08 — Ready announces politely (the clip title), once on entry. A tiny
        // off-layout live-region node; TalkBack speaks it without interrupting.
        val readyAnnounce = HistoryRowFormatters.formatPrimaryDateTime(state.startedAt)
        Box(
            modifier = Modifier
                .size(1.dp)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = readyAnnounce
                }
        )
        // Full-bleed video surface. AndroidView handles attach /
        // detach; the DisposableEffect releases the surface reference
        // back to the VM so a subsequent player instance does not
        // inherit a stale Surface.
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                // §06 — the rubber-band transform tracks the finger 1:1 (§11: this
                // is function, not decoration — it survives reduced motion); the
                // gentle scale is decorative and dropped under reduced motion.
                .graphicsLayer {
                    translationY = dragOffsetY.value
                    val s = if (reduceMotion) 1f
                    else SwipeDismissPolicy.dismissScale(dragOffsetY.value / density)
                    scaleX = s
                    scaleY = s
                }
                // §06/§08 — vertical drag-to-dismiss on the body. Separate
                // pointerInput from the tap/double-tap block: detectVerticalDrag's
                // vertical touch-slop is the axis lock (§08 Rule 2) and the
                // tap-vs-drag split (Rule 3) — a tap has no slop travel and stays a
                // tap. Suspended under TalkBack (Rule 5): Back / system-back are the
                // accessible dismiss path (§10).
                .pointerInput(touchExplorationActive) {
                    if (touchExplorationActive) return@pointerInput
                    val d = density
                    val velocityTracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { velocityTracker.resetTracking() },
                        onDragCancel = {
                            scope.launch {
                                if (reduceMotion) dragOffsetY.snapTo(0f)
                                else dragOffsetY.animateTo(0f, RovaMotion.containerSpring())
                            }
                        },
                        onDragEnd = {
                            val velocityDpS = velocityTracker.calculateVelocity().y / d
                            val dragDp = dragOffsetY.value / d
                            if (SwipeDismissPolicy.shouldCommit(dragDp, velocityDpS)) {
                                // Commit = the same nav-pop as Back (ADR-0038
                                // teardown unchanged: park → 400 ms deferred release).
                                onBack()
                            } else {
                                scope.launch {
                                    if (reduceMotion) dragOffsetY.snapTo(0f)
                                    else dragOffsetY.animateTo(0f, RovaMotion.containerSpring())
                                }
                            }
                        }
                    ) { change, dragAmount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        change.consume()
                        // Downward only (§06 swipe DOWN): clamp so an upward drag
                        // never lifts the frame or accrues travel toward commit.
                        scope.launch {
                            dragOffsetY.snapTo((dragOffsetY.value + dragAmount).coerceAtLeast(0f))
                        }
                    }
                }
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
            // perf/player-lifecycle — the ExoPlayer is now app-scoped and
            // shared (PlayerEngine); a disposed PlayerView must not keep a
            // reference to it. setPlayer(null) only clears the player's
            // output if it still points at THIS view's surface, so a late
            // onRelease can't blank a newer screen's rebind.
            onRelease = { view -> view.player = null },
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
        // the player doesn't flash a black "block". player-states.html §03: the poster is present
        // from 0 ms (enter = None) and CROSS-FADES out to the just-decoded live frame on
        // onRenderedFirstFrame (--t-small), or hard-swaps at the --poster-ceiling. Reduce-motion →
        // hard cut, no fade.
        AnimatedVisibility(
            visible = posterBitmap != null && !firstFrameRendered && !posterTimedOut,
            enter = EnterTransition.None,
            exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(200)),
            modifier = Modifier.matchParentSize()
        ) {
            posterBitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Top gradient + bar. Suppressed during a runtime error (§06) — the freeze
        // overlay owns the frame; chrome fades out as the card fades in.
        AnimatedVisibility(
            visible = chromeVisible && playbackError == null,
            enter = if (reduceMotion) EnterTransition.None else fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PlayerTokens.scrimTopHeight)
                    .background(PlayerTokens.scrimTop)
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
                    // player-accessibility.html §02 (frozen · tree hygiene) — the
                    // one grounded correction the transcription owes: the date/time
                    // title + "N clips · total" sub-title (+ the DualShot angle line
                    // when present — §07 "TalkBack reads the current side from the
                    // header") are read as ONE grouped semantic node, not 2–3
                    // ungrouped Texts. Back and Info stay separate sibling buttons.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .semantics(mergeDescendants = true) {}
                    ) {
                        Text(
                            text = HistoryRowFormatters.formatPrimaryDateTime(state.startedAt),
                            style = MaterialTheme.typography.titleSmall,
                            // player-core.html §02: top-bar title → --media-ink (.94).
                            color = RovaTrustTokens.mediaInk
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
                            // player-core.html §01/§03: sub-title is de-emphasised
                            // nav metadata → --media-ink-dim (.48). The pre-migration
                            // literal was a bespoke 0.72α AA-lift; the locked dim ink
                            // still clears AA over the scrim-composite dark reference
                            // (proven by PlayerOverlayContrastTest, §07).
                            color = RovaTrustTokens.mediaInkDim
                        )
                        // player-dualshot.html §01/§06/§07 — the read-only angle
                        // INDICATOR: which side is on screen, plus the honest
                        // "X only · Y didn't finish" when the sibling never finalized
                        // (§06). DualShot-owned top-overlay chrome this spec ships
                        // today; the in-player angle SWITCH is Future/gated behind the
                        // RK3 ADR amendment + owner sign-off (§03/§04) and is NOT built
                        // — flipping `side` in-player would be a sole-minter (ADR-0037)
                        // violation. Absent for single-mode + kept-raw (PlayerAngle
                        // Indicator.from → null). Rides the chrome fade + auto-hide;
                        // TalkBack reads the current side from the header, not a
                        // separate stop (§07 header semantics), via contentDescription.
                        PlayerAngleIndicator.from(infoModel)?.let { angle ->
                            val current = stringResource(
                                if (angle.currentSide == VideoSide.PORTRAIT)
                                    R.string.player_dualshot_side_portrait
                                else R.string.player_dualshot_side_landscape
                            )
                            val sibling = stringResource(
                                if (angle.siblingSide == VideoSide.PORTRAIT)
                                    R.string.player_dualshot_side_portrait
                                else R.string.player_dualshot_side_landscape
                            )
                            val line = if (angle.siblingFinalized) {
                                stringResource(
                                    R.string.player_dualshot_indicator_both, current, angle.position
                                )
                            } else {
                                stringResource(
                                    R.string.player_dualshot_indicator_only, current, sibling
                                )
                            }
                            val cd = if (angle.siblingFinalized) {
                                stringResource(
                                    R.string.player_dualshot_angle_cd_both, current, angle.position
                                )
                            } else {
                                stringResource(
                                    R.string.player_dualshot_angle_cd_only, sibling, current
                                )
                            }
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                // §01/§00/§10 — quiet, over-media ink-dim; NO new token.
                                color = RovaTrustTokens.mediaInkDim,
                                modifier = Modifier.semantics { contentDescription = cd }
                            )
                        }
                    }
                    // player-actions.html §01 — the LEADING Info slot: top-trailing
                    // corner, opposite Back. Present whenever there is a resolved
                    // artifact to describe (infoModel != null ⟺ Ready); vault opens
                    // Info like any resolved artifact (player-info §10 — FLAG_SECURE
                    // already applied). Opens the read-only sheet; the framework
                    // (transport / timeline / Share) is untouched.
                    if (infoModel != null) {
                        IconButton(onClick = {
                            interactionTick++
                            infoSheetOpen = true
                        }) {
                            SemanticIcon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.player_info_cd),
                                role = IconRole.Default
                            )
                        }
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

        // player-sharing.html §05/§07/§08 — resolve the share URI(s) off-main via
        // the VM, then launch the SYSTEM chooser; Rova owns nothing past
        // createChooser (§07). Fail-closed: an empty resolution (no shareable
        // artifact / a side vanished — no partial send) or no handler app → one
        // calm toast, playback untouched. Cancellation + success stay silent (§06).
        val launchShare: (List<PlayerShareArtifact>) -> Unit = { artifacts ->
            scope.launch {
                val uris = onResolveShareUris(artifacts)
                if (uris.isEmpty()) {
                    Toast.makeText(context, shareNotReadyMsg, Toast.LENGTH_SHORT).show()
                } else {
                    val intent = (
                        if (uris.size == 1) {
                            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
                        } else {
                            Intent(Intent.ACTION_SEND_MULTIPLE)
                                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        }
                        )
                        .setType("video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(Intent.createChooser(intent, null))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, shareNoAppMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        // §04 — single artifact goes straight to the chooser; a two-sided
        // DualShot opens the Rova-owned side sheet first. Unavailable never
        // renders the trigger, so its branch is unreachable.
        val onShareClick: () -> Unit = {
            interactionTick++
            when (val p = sharePlan) {
                is PlayerSharePlan.DualChoice -> shareSheet = p
                is PlayerSharePlan.Single -> launchShare(listOf(p.artifact))
                PlayerSharePlan.Unavailable -> Unit
            }
        }

        // Bottom panel — info row + timeline + controls. Suppressed during a
        // runtime error (§06), like the top band.
        AnimatedVisibility(
            modifier = Modifier.align(Alignment.BottomCenter),
            visible = chromeVisible && playbackError == null,
            enter = if (reduceMotion) EnterTransition.None else fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else fadeOut()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PlayerTokens.scrimBottom)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // player-actions.html §01/§02 — the Share TRAILING slot, present
                // only when a shareable artifact exists (§05, plan != Unavailable;
                // vault/not-finalized are already Unavailable). Trailing-aligned
                // and separate from the transport cluster (the primary is never
                // touched). Geometry note: the frozen §01 bottom-trailing corner
                // coincides with the shipped speed chip (player-core §05); this
                // renders Share at the trailing edge above the cluster to avoid
                // overlapping it — the exact corner is a flagged HTML-reconciliation
                // item for pixel parity (see the Phase-3 review).
                if (sharePlan !is PlayerSharePlan.Unavailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        PlayerShareButton(onShare = onShareClick)
                    }
                }
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

        // player-states.html §04 — the quiet resume cue, over the bottom scrim
        // area. Hidden during a runtime error. Dismisses on Start over.
        if (playbackError == null && resumeCue != null && resumeCueVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = PlayerTokens.resumeCueBottomOffset)
            ) {
                ResumeCue(
                    cue = resumeCue,
                    onStartOver = {
                        onStartOver()
                        resumeCueVisible = false
                    }
                )
            }
        }

        // player-states.html §06 (RK7) — runtime Ready→Unavailable flip. The video
        // surface above stays mounted showing its LAST frame (uiState is still
        // Ready); this overlay dims that frozen frame (never black) and FADES the
        // SAME triad card in over it. Kept mounted (visible-toggled) so the card
        // animates in on the flip and out on Retry; lastPlaybackError holds the
        // reason through the fade-out. Reduce-motion → instant.
        AnimatedVisibility(
            modifier = Modifier.matchParentSize(),
            visible = playbackError != null,
            enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(200)),
            exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(PlayerTokens.runtimeFreezeDim)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                lastPlaybackError?.let { reason ->
                    PlayerUnavailableCard(reason = reason, onRetry = onRetry, onBack = onBack)
                }
            }
        }

        // player-sharing.html §04 — the Rova-owned DualShot side sheet. Shown
        // only for a two-sided [PlayerSharePlan.DualChoice]; picking an option
        // dismisses the sheet and launches the system chooser (§07).
        shareSheet?.let { plan ->
            PlayerShareSideSheet(
                plan = plan,
                onShare = { targets -> shareSheet = null; launchShare(targets) },
                onDismiss = { shareSheet = null },
            )
        }

        // player-info.html §01 — the read-only Info sheet. Same pinned idiom as the
        // share sheet; no controls, dismissal only. Rendered over everything so it
        // reads the frozen last state; playback continues underneath (§00 read-only).
        if (infoSheetOpen && infoModel != null) {
            PlayerInfoSheet(
                model = infoModel,
                onDismiss = { infoSheetOpen = false },
            )
        }
    }
}

/**
 * player-actions.html §01/§02 — the Share trailing slot control: a 48dp
 * over-media glyph button on `--glyph-fill`, matching the transport pills. The
 * labelled `Surface(onClick)` supplies Role.Button (checkA11yClickableHasRole)
 * and the "Share recording" description (§09).
 */
@Composable
private fun PlayerShareButton(onShare: () -> Unit) {
    val shareCd = stringResource(R.string.player_share_cd)
    Surface(
        shape = CircleShape,
        // §09 — pinned over-video pill; opaque under reduce-transparency.
        color = PlayerTokens.pinnedPillFill(rememberReduceTransparency()),
        onClick = onShare,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = shareCd }
    ) {
        Box(contentAlignment = Alignment.Center) {
            SemanticIcon(
                glyph = RovaIcons.Share,
                contentDescription = null,
                role = IconRole.Default,
                modifier = Modifier.size(24.dp)
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
                // player-core.html §03: InfoRow position → --media-ink-dim (.48).
                color = RovaTrustTokens.mediaInkDim
            )
            Text(
                text = stringResource(
                    R.string.player_clip_n_of_m,
                    math.currentClipIndex,
                    math.totalClips
                ),
                style = MaterialTheme.typography.bodySmall,
                // player-core.html §03: "Clip N of M" is the loudest nav
                // metadata → --media-ink (.94).
                color = RovaTrustTokens.mediaInk
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
            // player-core.html §01/§03: wall-clock time-of-day → --media-ink (.94).
            color = RovaTrustTokens.mediaInk,
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
                // player-core.html §01/§03: gap chip (tertiary) → --media-ink-body (.55).
                color = RovaTrustTokens.mediaInkBody
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
    // player-accessibility.html §09 / player-core §04 (:521) — pinned pills go
    // opaque under OS reduce-transparency (default byte-identical to --glyph-fill).
    val pillFill = PlayerTokens.pinnedPillFill(rememberReduceTransparency())

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
                // player-core.html §05: the one primary — 48dp pinned pill on --glyph-fill
                // (opaque under reduce-transparency, §09).
                color = pillFill,
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
            // player-core.html §05: speed chip — demoted peer, 48dp pill on --glyph-fill
            // (opaque under reduce-transparency, §09).
            color = pillFill,
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
                    // player-core.html §02: speed label → --media-ink (.94).
                    color = RovaTrustTokens.mediaInk,
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

// player-states.html §02 — state-timing constants (UI-timing only, the class of
// --auto-hide / --poster-ceiling; they gate WHEN a cue appears, never WHAT the
// resolver decides). Grace suppresses any loading cue under it so a fast resolve
// never flashes a spinner; spinner-period is the rotation; resume-dwell is the
// resume-cue self-dismiss window.
private const val LOADING_GRACE_MS: Long = 400L
private const val SPINNER_PERIOD_MS: Int = 900
private const val RESUME_CUE_DWELL_MS: Long = 4_000L
