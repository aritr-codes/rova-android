package com.aritr.rova.utils

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Phase 1.5 media-file validity check (ADR 0005 §"Media Validity Rules").
 *
 * Used for:
 * - Phase 1.5 recovery classifier — every segment file (in-manifest and
 *   orphan). The earlier "skip in-manifest validation to avoid flapping"
 *   reasoning was dropped because the per-session live-re-check
 *   (concurrency invariant 5) excludes any session owned by a live
 *   ServiceController, so a tick cannot race the scan on a session the
 *   scan is processing.
 * - Phase 1.7 commit-4 (NO-GO patch) — Tier 1's `validatePending`
 *   recovery probe consumes [validateMediaFromFd] over the
 *   pending-row's read-only PFD. A `trackCount > 0` check alone is
 *   too weak (accepts a corrupt MP4 with a populated track table but
 *   no decodable samples); the FD overload mirrors the file-path
 *   validator's full discipline: video track present + at least one
 *   readable sample.
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
        var maxInputSize = 0
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    durationUs = format.getLong(MediaFormat.KEY_DURATION)
                }
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                }
                break
            }
        }
        if (videoTrackIndex < 0) return MediaFileInspection.INVALID

        extractor.selectTrack(videoTrackIndex)
        // RF1: size the probe buffer to the track's max input size (floored
        // at 1 MB) so a large FHD/4K IDR keyframe is read whole — a fixed
        // 64 KB buffer risked truncating it and false-dropping a HEALTHY
        // segment. See [chooseInspectionBufferSize].
        val buffer = ByteBuffer.allocate(chooseInspectionBufferSize(maxInputSize))
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

/**
 * Phase 1.7 commit-4 (NO-GO patch) — FD-based validity probe used by
 * Tier 1 recovery to verify a `MediaStore` pending row is a real,
 * decodable artifact before flipping `IS_PENDING=0`. The `trackCount > 0`
 * gate alone is insufficient (corrupt MP4s can have populated track
 * tables); this overload applies the same discipline as
 * [inspectMediaFile]:
 *   1. extractor opens against the FD,
 *   2. at least one video track present,
 *   3. selecting that track and reading one sample yields > 0 bytes.
 *
 * `extractor.release()` always runs in `finally`. Returns `false` on
 * any throw or rejection.
 */
fun validateMediaFromFd(fd: FileDescriptor): Boolean {
    val extractor = MediaExtractor()
    return try {
        extractor.setDataSource(fd)
        if (extractor.trackCount <= 0) return false

        var videoTrackIndex = -1
        var maxInputSize = 0
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                videoTrackIndex = i
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxInputSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                }
                break
            }
        }
        if (videoTrackIndex < 0) return false

        extractor.selectTrack(videoTrackIndex)
        // RF1: buffer sized to the keyframe (floored at 1 MB) — see
        // [chooseInspectionBufferSize] and the [inspectMediaFile] note.
        val buffer = ByteBuffer.allocate(chooseInspectionBufferSize(maxInputSize))
        extractor.readSampleData(buffer, 0) > 0
    } catch (t: Throwable) {
        RovaLog.w("validateMediaFromFd: extractor failure", t)
        false
    } finally {
        try {
            extractor.release()
        } catch (t: Throwable) {
            RovaLog.w("validateMediaFromFd: extractor.release() failed", t)
        }
    }
}

/**
 * RF1 (frozen spec 2026-07-08) — minimum inspection read-buffer floor. The
 * one-sample validity probe reads the first video sample into a buffer sized
 * to the track's `KEY_MAX_INPUT_SIZE` but never below this floor, so a large
 * FHD/4K IDR keyframe is never truncated → never false-dropped as INVALID.
 * Mirrors `VideoMerger.runMux`'s `KEY_MAX_INPUT_SIZE` sizing (default 1 MB).
 */
private const val MIN_INSPECTION_BUFFER_BYTES = 1024 * 1024

/**
 * Read-buffer size for the one-sample probe: the video track's advertised
 * max input size, floored at [MIN_INSPECTION_BUFFER_BYTES]. Pure — unit
 * tested. A non-positive/absent `maxInputSize` yields the floor.
 */
internal fun chooseInspectionBufferSize(maxInputSize: Int): Int =
    maxOf(maxInputSize, MIN_INSPECTION_BUFFER_BYTES)
