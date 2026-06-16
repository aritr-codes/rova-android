package com.aritr.rova.ui.library

import com.aritr.rova.service.dualrecord.VideoSide

/** Orientation a Library tile represents — drives the orientation glyph (CropPortrait / CropLandscape). */
enum class LibraryOrientation { PORTRAIT, LANDSCAPE }

/**
 * Pure resolver for a tile's [LibraryOrientation] (owner orientation-glyph request, 2026-06-15).
 * Android-free so it is JVM-testable.
 *
 * Two sources, in priority order:
 *  1. [side] — a DualShot session fans into per-side rows; the side IS the orientation
 *     (PORTRAIT/LANDSCAPE) directly, authoritative, available immediately (before any thumbnail).
 *  2. [thumbWidthPx]/[thumbHeightPx] — a single-mode row has no side, so derive from the decoded
 *     thumbnail's pixel dimensions. The thumbnail comes from `MediaMetadataRetriever.getFrameAtTime`,
 *     which returns the frame already rotation-corrected to upright — so its width/height reflect the
 *     DISPLAY orientation (not the raw stored dimensions, which ignore the rotation flag). Bonus: the
 *     glyph then matches exactly what the tile's thumbnail looks like.
 *
 * Returns null when neither yields a verdict (no thumbnail yet, square frame, legacy/SAF rows) — the
 * caller renders no glyph rather than guessing.
 */
object OrientationResolver {

    fun resolve(side: VideoSide?, thumbWidthPx: Int, thumbHeightPx: Int): LibraryOrientation? {
        when (side) {
            VideoSide.PORTRAIT -> return LibraryOrientation.PORTRAIT
            VideoSide.LANDSCAPE -> return LibraryOrientation.LANDSCAPE
            null -> Unit
        }
        if (thumbWidthPx <= 0 || thumbHeightPx <= 0) return null
        return when {
            thumbHeightPx > thumbWidthPx -> LibraryOrientation.PORTRAIT
            thumbWidthPx > thumbHeightPx -> LibraryOrientation.LANDSCAPE
            else -> null // square — no meaningful orientation
        }
    }
}
