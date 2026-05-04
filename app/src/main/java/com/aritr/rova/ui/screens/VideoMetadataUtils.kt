package com.aritr.rova.ui.screens

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.aritr.rova.data.QualityLabels

object VideoMetadataUtils {

    /**
     * Returns a thumbnail frame from the video at [path], taken at the 1-second mark.
     * Returns null if the file is unreadable or has no video track.
     * Must be called off the main thread.
     */
    fun getThumbnail(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Returns the canonical actual-output quality label
     * (`"SD" / "HD" / "FHD" / "4K"`) for the recording at [path],
     * derived from the file's real video dimensions via
     * `MediaMetadataRetriever`. Falls back to `"—"` when the file
     * is unreadable or has no decodable dimensions.
     *
     * The label intentionally matches the picker vocabulary so a
     * CameraX QualitySelector fallback (e.g. `"FHD"` requested but
     * the device only honored `"HD"`) is visible to the user as a
     * mismatch between Settings/Record (requested) and History
     * (actual). The classification rule lives in [QualityLabels];
     * this function is just the I/O boundary.
     *
     * Must be called off the main thread.
     */
    fun getResolutionLabel(path: String): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            QualityLabels.forDimensions(width, height) ?: "—"
        } catch (e: Exception) {
            "—"
        } finally {
            retriever.release()
        }
    }
}
