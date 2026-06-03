package com.aritr.rova.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
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
     *
     * B4c (ADR-0024) — [key] is either an absolute file path (file
     * rows) or a `content://` URI string (SAF rows). When it is a
     * content URI the retriever is opened via
     * `setDataSource(context, uri)` so SAF artifacts (no java.io.File)
     * still yield a thumbnail + resolution. Any failure degrades to the
     * `(null, UNKNOWN_RESOLUTION)` placeholder — a SAF row stays visible
     * and tappable even with no thumbnail.
     */
    fun extractMetadata(context: Context, key: String): Pair<Bitmap?, String> {
        val retriever = MediaMetadataRetriever()
        return try {
            if (key.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(key))
            } else {
                retriever.setDataSource(key)
            }
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
