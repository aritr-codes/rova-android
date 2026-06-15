package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.aritr.rova.R

/**
 * Muted, looping autoplay preview (Slice 3 hero + Slice 4.2 visible cards). Loops a recording
 * silently behind a center-cropped surface. The static [fallback] thumbnail renders underneath so
 * there is no black flash before the first frame. ONE ExoPlayer, built per [uri]; released on
 * dispose — when the host card/hero scrolls out of the LazyGrid/Column it is disposed (and
 * released) automatically. Paused on ON_PAUSE, resumed on ON_RESUME. Callers gate this behind
 * reduce-motion (ADR-0020) and a decoder-safe concurrency cap (AutoplayPolicy): under reduce-motion
 * (or over cap / off-screen) render a static [VideoFrame] instead. If [uri] is null, falls back to
 * the static frame.
 */
@Composable
fun LibraryAutoplayVideo(uri: Uri?, fallback: Bitmap?, modifier: Modifier = Modifier) {
    if (uri == null) {
        VideoFrame(fallback, modifier)
        return
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // applicationContext (not the Activity) so the player can't pin a destroyed Activity (codex).
    val appContext = context.applicationContext
    val player = remember(uri) {
        ExoPlayer.Builder(appContext).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = Player.REPEAT_MODE_ONE
            volume = 0f
            // Slice 4.2: muting != disabling the audio decoder. Drop the audio track so each muted
            // preview holds only a video codec (frees scarce hardware audio-decoder instances).
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            // Start PAUSED: the DisposableEffect below seeds playback from the real lifecycle state, so a
            // preview composed while the screen isn't resumed doesn't decode in the background (codex race).
            playWhenReady = false
            prepare()
        }
    }

    // The static VideoFrame under-layer masks the transparent shutter so there is no black flash before
    // the first decoded frame; once a frame renders, drop it (HeroUnderlayPolicy). Keyed on [uri] so a
    // recycled host (pooled card) doesn't inherit a stale true (HeroUnderlayPolicy, JVM-tested).
    // NB: the real DualShot grey-strip / partial-frame-bleed was NOT this under-layer (P2's theory) — it
    // was the default SurfaceView overlay leaking a cold-launch first-frame strip, fixed by rendering to
    // a TextureView (R.layout.library_autoplay_preview). The under-layer is purely anti-flash now.
    var firstFrameRendered by remember(uri) { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, player) {
        // Drive playback off one lifecycle-derived flag instead of imperative play()/pause() (codex).
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.playWhenReady = false
                Lifecycle.Event.ON_RESUME -> player.playWhenReady = true
                else -> Unit
            }
        }
        // Flip the under-layer gate once the player has a real frame on screen. Re-fires harmlessly
        // under REPEAT_MODE_ONE (monotonic set per media item).
        val frameListener = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrameRendered = true }
        }
        player.addListener(frameListener)
        // Seed: ON_RESUME won't re-fire if we're already resumed at first composition.
        player.playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(frameListener)
            player.release()
        }
    }

    Box(modifier) {
        // Static frame underneath: masks the transparent shutter only until the first frame renders,
        // then dropped (see HeroUnderlayPolicy) so the Crop-vs-ZOOM divergence can't leak a band.
        if (com.aritr.rova.ui.library.HeroUnderlayPolicy.showStaticUnderlay(firstFrameRendered)) {
            VideoFrame(fallback, Modifier.fillMaxSize())
        }
        AndroidView(
            factory = { ctx ->
                // Inflate (not `PlayerView(ctx)`) so the surface is a TextureView — see
                // R.layout.library_autoplay_preview. SurfaceView's overlay layer ignores the Compose
                // RoundedCornerShape clip AND leaks a cold-launch first-frame strip on this device
                // class (DualShot grey-strip RCA); TextureView composites through the GPU, honoring the
                // host clip and the codec crop rect. surface_type can only be set via XML attr, not a
                // PlayerView setter — hence the layout. resize_mode/use_controller live in the XML too.
                val view = android.view.LayoutInflater.from(ctx)
                    .inflate(R.layout.library_autoplay_preview, null) as PlayerView
                view.apply {
                    setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                    // No setKeepContentOnPlayerReset: the player is fresh per [uri] (no reset to bridge),
                    // and keeping a stale last frame on reuse is worse than the static under-layer (codex P2).
                    this.player = player
                }
            },
            // Rebind when [uri] changes: remember(uri) builds a new player, so the view must drop the old
            // (already released by DisposableEffect) and adopt the new one — else it holds a dead player.
            update = { it.player = player },
            // Detach before the view leaves: don't let a recycled PlayerView hold a released player (codex).
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
