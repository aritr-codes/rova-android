package com.aritr.rova.ui.library.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Hero-only muted autoplay preview (polish pass). Loops the newest recording silently behind a
 * center-cropped surface. The static [fallback] thumbnail renders underneath so there is no black flash
 * before the first frame. ONE ExoPlayer, built per [uri]; released on dispose — when the hero scrolls out
 * of the LazyGrid/Column it is disposed (and released) automatically. Paused on ON_PAUSE, resumed on
 * ON_RESUME. Callers gate this behind reduce-motion (ADR-0020): under reduce-motion render a static
 * [VideoFrame] instead. If [uri] is null, falls back to the static frame.
 */
@Composable
fun LibraryHeroVideo(uri: Uri?, fallback: Bitmap?, modifier: Modifier = Modifier) {
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
            // Start PAUSED: the DisposableEffect below seeds playback from the real lifecycle state, so a
            // hero composed while the screen isn't resumed doesn't decode in the background (codex race).
            playWhenReady = false
            prepare()
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        // Drive playback off one lifecycle-derived flag instead of imperative play()/pause() (codex).
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> player.playWhenReady = false
                Lifecycle.Event.ON_RESUME -> player.playWhenReady = true
                else -> Unit
            }
        }
        // Seed: ON_RESUME won't re-fire if we're already resumed at first composition.
        player.playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.release()
        }
    }

    Box(modifier) {
        // Static frame underneath: shown until the first video frame renders (shutter is transparent).
        VideoFrame(fallback, Modifier.fillMaxSize())
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(AndroidColor.TRANSPARENT)
                    setKeepContentOnPlayerReset(true)
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
