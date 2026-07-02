package com.aritr.rova.ui.screens.player

import android.graphics.Bitmap

/**
 * One-shot in-memory poster hand-off from Library → Player. The Library already holds the tapped
 * row's decoded thumbnail (`VideoItem.thumbnail`); it stashes that here just before navigating so
 * [PlayerScreen] can paint it over the video surface until the real first frame renders — masking
 * the black PlayerView shutter "block" and the ExoPlayer build/prepare delay on entry.
 *
 * Main-thread only, single slot (only one player opens at a time), cleared on [take]. When absent
 * (deep link, process death, legacy PreviewActivity path) the player falls back to the default
 * black shutter — no regression.
 *
 * [set] stores a COPY: the source thumbnail belongs to HistoryViewModel's cache, which
 * `recycle()`s thumbnails on clear, so a shared ref could be recycled out from under the player's
 * render-thread draw (codex). The copy is a small tile-sized bitmap (sub-ms), owned by the taker
 * and reclaimed by GC once the poster is dropped.
 *
 * ponytail: a single mutable slot keyed by sessionId, not a map/DI channel — one player at a time,
 * transient UI state, no lifecycle to manage. Promote to a keyed map only if concurrent players
 * ever exist.
 */
object PlayerPosterHandoff {
    private var forSessionId: String? = null
    private var poster: Bitmap? = null

    fun set(sessionId: String?, bitmap: Bitmap?) {
        forSessionId = sessionId
        poster = bitmap?.takeUnless { it.isRecycled }
            ?.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
    }

    /** Returns the stashed poster iff it was set for [sessionId], and clears the slot either way. */
    fun take(sessionId: String?): Bitmap? {
        val hit = if (sessionId != null && sessionId == forSessionId) poster else null
        forSessionId = null
        poster = null
        return hit
    }
}
