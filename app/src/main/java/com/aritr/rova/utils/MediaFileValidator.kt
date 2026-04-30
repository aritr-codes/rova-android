package com.aritr.rova.utils

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * Phase 1.5 media-file validity check (ADR 0005 §"Media Validity Rules").
 *
 * Used for: every segment file the recovery classifier touches — both
 * in-manifest segments and orphan segments. The earlier "skip in-manifest
 * validation to avoid flapping" reasoning was dropped because the per-session
 * live-re-check (concurrency invariant 5) excludes any session owned by a
 * live ServiceController, so a tick cannot race the scan on a session the
 * scan is processing.
 *
 * NOT used for merged output files — that scope belongs to ADR 0003 / Phase 1.7.
 */

/**
 * Combined validity + duration result. Single extractor open per file —
 * the scanner needs both pieces of information for orphan-append (duration
 * goes into the new SegmentRecord), so coupling them avoids re-opening.
 */
data class MediaFileInspection(
    val isValid: Boolean,
    val durationMs: Long
) {
    companion object {
        val INVALID = MediaFileInspection(isValid = false, durationMs = 0L)
    }
}

/**
 * Inspect a media file: validity (ADR 0005 §"Media Validity Rules") plus
 * video-track duration in milliseconds (or `0L` if KEY_DURATION absent).
 *
 * Returns [MediaFileInspection.INVALID] on any failure. `extractor.release()`
 * always runs in `finally`.
 */
fun inspectMediaFile(file: File): MediaFileInspection {
    if (!file.exists() || file.length() <= 0L) return MediaFileInspection.INVALID
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(file.absolutePath)
        if (extractor.trackCount <= 0) return MediaFileInspection.INVALID

        var videoTrackIndex = -1
        var durationUs = 0L
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = format.getLong(MediaFormat.KEY_DURATION)
                }
                break
            }
        }
        if (videoTrackIndex < 0) return MediaFileInspection.INVALID

        extractor.selectTrack(videoTrackIndex)
        val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
        val read = extractor.readSampleData(buffer, 0)
        if (read <= 0) MediaFileInspection.INVALID
        else MediaFileInspection(isValid = true, durationMs = durationUs / 1000L)
    } catch (t: Throwable) {
        RovaLog.w("inspectMediaFile: extractor failure for ${file.name}", t)
        MediaFileInspection.INVALID
    } finally {
        try {
            extractor.release()
        } catch (t: Throwable) {
            RovaLog.w("inspectMediaFile: extractor.release() failed for ${file.name}", t)
        }
    }
}

/**
 * Boolean-only validity check. Re-uses [inspectMediaFile] under the hood.
 * Provided for callers that don't need duration (and for the ADR 0005
 * acceptance criterion that names `validateMediaFile` directly).
 */
fun validateMediaFile(file: File): Boolean = inspectMediaFile(file).isValid

private const val SAMPLE_BUFFER_BYTES = 64 * 1024
