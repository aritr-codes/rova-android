package com.aritr.rova.ui.screens

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever

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
     * Returns a human-readable resolution label ("1080p", "720p", "4K", etc.) for the
     * video at [path]. Falls back to "WxH" if the height doesn't match a known tier,
     * or "—" on error.
     * Must be called off the main thread.
     */
    fun getResolutionLabel(path: String): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            when {
                height >= 2160 -> "4K"
                height >= 1080 -> "1080p"
                height >= 720  -> "720p"
                height >= 480  -> "480p"
                width > 0 && height > 0 -> "${width}×${height}"
                else -> "—"
            }
        } catch (e: Exception) {
            "—"
        } finally {
            retriever.release()
        }
    }
}
