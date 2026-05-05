package com.aritr.rova.ui.screens

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.aritr.rova.data.QualityLabels

object VideoMetadataUtils {

    /**
     * Placeholder shown while metadata extraction is in flight or
     * when extraction fails outright. Matches the existing failure
     * fallback so a transient placeholder is indistinguishable from
     * a permanent extraction failure (the user cannot tell the
     * difference visually, which is the right outcome for a row
     * whose metadata is about to load).
     */
    const val UNKNOWN_RESOLUTION = "—"

    /**
     * Combined thumbnail + resolution extraction sharing a single
     * `MediaMetadataRetriever` lifecycle: one `setDataSource` +
     * `release` for both reads, halving per-file overhead versus
     * separate retriever instances.
     *
     * The resolution label is the canonical actual-output quality
     * (`"SD" / "HD" / "FHD" / "4K"`) derived from the file's real
     * dimensions via [QualityLabels.forDimensions], and intentionally
     * matches the picker vocabulary so a CameraX QualitySelector
     * fallback (e.g. `"FHD"` requested but the device only honored
     * `"HD"`) is visible to the user as a mismatch between
     * Settings/Record (requested) and History (actual).
     *
     * Returns `(null, UNKNOWN_RESOLUTION)` when the file is
     * unreadable or has no decodable dimensions. Must be called off
     * the main thread.
     */
    fun extractMetadata(path: String): Pair<Bitmap?, String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val thumb = retriever.getFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val res = QualityLabels.forDimensions(width, height) ?: UNKNOWN_RESOLUTION
            Pair(thumb, res)
        } catch (e: Exception) {
            Pair(null, UNKNOWN_RESOLUTION)
        } finally {
            retriever.release()
        }
    }
}
