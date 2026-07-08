package com.aritr.rova.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Phase 1.7 commit-7 — segment-to-MP4 mux primitives consumed by
 * [com.aritr.rova.service.export.ExportPipeline]. Tier 2/3 use the
 * file-path entry [mergeSegments]; Tier 1 uses the FD entry
 * [mergeSegmentsToFd] so `MediaMuxer.stop()` can rewrite the moov atom
 * against the pending-row PFD opened by
 * [com.aritr.rova.service.export.Tier1AndroidOps.withPendingFd] (mode
 * `"rw"` per ADR 0003 §FD Mode Amendment).
 *
 * Single-entry rule: callers other than `service/export/` are forbidden
 * by `checkExportPipelineSingleEntry` — the tier pipeline cannot be
 * bypassed by a direct mux call.
 */
object VideoMerger {

    private const val TAG = "VideoMerger"
    private const val BUFFER_SIZE = 1024 * 1024 // 1MB buffer

    private enum class TrackKind { VIDEO, AUDIO }

    private data class TrackDescriptor(
        val kind: TrackKind,
        val mime: String,
        val width: Int? = null,
        val height: Int? = null,
        val sampleRate: Int? = null,
        val channelCount: Int? = null
    )

    /**
     * Merges multiple video files into a single output file via
     * `MediaMuxer(String, ...)`. Used by Tier 2/3's private-temp mux
     * step.
     */
    suspend fun mergeSegments(
        segments: List<File>,
        outputFile: File,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val (validSegments, rotation, totalBytes) = preflight(segments)
        Log.d(TAG, "Starting merge of ${validSegments.size} segments to ${outputFile.name}")
        onProgress(0f)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            muxer.setOrientationHint(rotation)
            runMux(muxer, validSegments, totalBytes, onProgress)
        } catch (e: Exception) {
            Log.e(TAG, "Merge failed", e)
            outputFile.delete()
            throw e
        } finally {
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    /**
     * Tier 1 entry. Caller (`Tier1AndroidOps.withPendingFd`) owns the
     * underlying `ParcelFileDescriptor` and closes it on block exit;
     * `MediaMuxer(FileDescriptor)` does NOT take ownership.
     * `MediaMuxer.stop()` rewrites the moov atom which requires a
     * seekable FD — the caller's `"rw"` mode satisfies that
     * (ADR 0003 §FD Mode Amendment).
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun mergeSegmentsToFd(
        segments: List<File>,
        fd: FileDescriptor,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val (validSegments, rotation, totalBytes) = preflight(segments)
        Log.d(TAG, "Starting Tier 1 merge of ${validSegments.size} segments to FD")
        onProgress(0f)

        val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            muxer.setOrientationHint(rotation)
            runMux(muxer, validSegments, totalBytes, onProgress)
        } finally {
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    private data class Preflight(val segments: List<File>, val rotation: Int, val totalBytes: Long)

    private fun preflight(segments: List<File>): Preflight {
        if (segments.isEmpty()) {
            throw IllegalArgumentException("No segments to merge")
        }
        // Frozen invariant (spec 2026-07-08, ADR-0005): the universal merge
        // chokepoint admits a segment ONLY via the single validity predicate
        // (video track + ≥1 readable video sample) — not the old size-only
        // `length() > 0`, which let a frameless audio-only stub through. Drops
        // are logged (previously silent). Runs on the callers' Dispatchers.IO.
        val filtered = MergeSegmentFilter.partition(segments, ::validateMediaFile)
        filtered.dropped.forEach { drop ->
            Log.w(TAG, "preflight dropped invalid segment ${drop.file.name}: ${drop.reason}")
        }
        val validSegments = filtered.kept
        if (validSegments.isEmpty()) {
            throw IllegalArgumentException("No valid segments found")
        }
        val totalBytes = validSegments.sumOf { it.length() }
        var rotation = 0
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(validSegments[0].absolutePath)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            rotation = rotationStr?.toInt() ?: 0
            retriever.release()
            Log.d(TAG, "Detected rotation: $rotation")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract rotation", e)
        }
        return Preflight(validSegments, rotation, totalBytes)
    }

    private suspend fun runMux(
        muxer: MediaMuxer,
        validSegments: List<File>,
        totalBytes: Long,
        onProgress: (Float) -> Unit
    ) {
        val extractors = mutableListOf<MediaExtractor>()
        var muxerStarted = false
        // Phase 1.7 commit-7 (NO-GO patch round 1, blocker 2):
        // muxer.stop() is the moov-atom rewrite step. A failure there
        // means the on-disk artifact is corrupt — the caller MUST see
        // it as a mux failure, not as a Success that finalizes a bad
        // pending row / .part file. We hoist stop() into the try-block
        // success path so its exception propagates; the finally only
        // performs best-effort idempotent cleanup if stop() has not
        // already run (i.e., we're already on the throwing path).
        var muxerStopped = false
        var bytesProcessed = 0L

        try {
            // 1. Setup Extractors
            validSegments.forEach { file ->
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                extractors.add(extractor)
            }

            // 2. Validate that every segment matches the app's expected CameraX output:
            // one video track plus an optional single audio track with compatible formats.
            val expectedTracks = mutableMapOf<TrackKind, TrackDescriptor>()
            val kindToMuxerIndex = mutableMapOf<TrackKind, Int>()
            var bufferSize = 0

            extractors.forEach { extractor ->
                val tracksByKind = linkedMapOf<TrackKind, Pair<MediaFormat, TrackDescriptor>>()

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val descriptor = buildTrackDescriptor(format) ?: continue

                    if (tracksByKind.put(descriptor.kind, format to descriptor) != null) {
                        throw IllegalArgumentException(
                            "Unsupported segment layout: multiple ${descriptor.kind.name.lowercase()} tracks detected"
                        )
                    }
                }

                tracksByKind.forEach { (kind, trackInfo) ->
                    val format = trackInfo.first
                    val descriptor = trackInfo.second
                    val expected = expectedTracks[kind]

                    if (expected == null) {
                        expectedTracks[kind] = descriptor
                        if (kind !in kindToMuxerIndex) {
                            val newIndex = muxer.addTrack(format)
                            kindToMuxerIndex[kind] = newIndex
                        }
                    } else if (expected != descriptor) {
                        throw IllegalArgumentException(
                            "Incompatible ${kind.name.lowercase()} track format across segments: expected $expected but found $descriptor"
                        )
                    }

                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (size > bufferSize) bufferSize = size
                    }
                }
            }

            if (kindToMuxerIndex.isEmpty()) {
                throw IllegalArgumentException("No audio/video tracks found in source segments")
            }

            if (bufferSize <= 0) bufferSize = BUFFER_SIZE

            muxer.start()
            muxerStarted = true

            // 3. Write Data
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var timelineOffsetUs = 0L
            val totalSegments = extractors.size

            extractors.forEachIndexed { index, extractor ->
                if (!coroutineContext.isActive) {
                    throw kotlinx.coroutines.CancellationException("Merge cancelled")
                }

                val localToMuxerTrackMap = mutableMapOf<Int, Int>()
                for (i in 0 until extractor.trackCount) {
                    val descriptor = buildTrackDescriptor(extractor.getTrackFormat(i)) ?: continue
                    val muxerTrack = kindToMuxerIndex[descriptor.kind] ?: continue
                    localToMuxerTrackMap[i] = muxerTrack
                    extractor.selectTrack(i)
                }

                val firstSampleTimeUs = mutableMapOf<Int, Long>()
                val lastWrittenTimeUs = mutableMapOf<Int, Long>()
                var segmentEndUs = timelineOffsetUs
                var wroteSamples = false

                while (true) {
                    val trackIndex = extractor.sampleTrackIndex
                    if (trackIndex < 0) break

                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val muxerTrackIndex = localToMuxerTrackMap[trackIndex]
                    if (muxerTrackIndex == null) {
                        extractor.advance()
                        continue
                    }

                    val presentationTimeUs = extractor.sampleTime
                    if (presentationTimeUs < 0) {
                        extractor.advance()
                        continue
                    }

                    val trackStartUs = firstSampleTimeUs.getOrPut(muxerTrackIndex) { presentationTimeUs }
                    val normalizedTimeUs = presentationTimeUs - trackStartUs
                    val lastTimeUs = lastWrittenTimeUs[muxerTrackIndex]
                    val outputTimeUs = if (lastTimeUs != null && timelineOffsetUs + normalizedTimeUs <= lastTimeUs) {
                        lastTimeUs + 1L
                    } else {
                        timelineOffsetUs + normalizedTimeUs
                    }

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    // C1: MediaExtractor.SAMPLE_FLAG_* and MediaCodec.BUFFER_FLAG_* are
                    // separate namespaces with overlapping values (notably
                    // SAMPLE_FLAG_PARTIAL_FRAME == BUFFER_FLAG_END_OF_STREAM == 4).
                    // Forward only the sync-frame bit, mapped explicitly.
                    bufferInfo.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    else 0
                    bufferInfo.presentationTimeUs = outputTimeUs

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    wroteSamples = true
                    lastWrittenTimeUs[muxerTrackIndex] = outputTimeUs

                    if (outputTimeUs > segmentEndUs) {
                        segmentEndUs = outputTimeUs
                    }

                    extractor.advance()
                }

                if (wroteSamples) {
                    timelineOffsetUs = segmentEndUs + 1000L
                }

                bytesProcessed += validSegments[index].length()
                onProgress(if (totalBytes > 0) bytesProcessed.toFloat() / totalBytes else (index + 1).toFloat() / totalSegments)
            }

            // Phase 1.7 commit-7 (NO-GO patch): stop() runs in the
            // success path so a moov-atom rewrite failure surfaces to
            // the caller as an exception. Caller maps it to MuxFailed
            // (Tier 2/3 path-mux deletes the partial output via the
            // outer catch in mergeSegments; Tier 1 FD-mux leaves the
            // pending row to Tier1Exporter.cleanupAndMap +
            // safeDeleteRow).
            if (muxerStarted) {
                muxer.stop()
                muxerStopped = true
            }
            Log.d(TAG, "Merge completed successfully")
        } finally {
            if (muxerStarted && !muxerStopped) {
                // Throwing path — do best-effort stop so the muxer
                // releases its native resources cleanly. The original
                // exception is what the caller will see; swallowing
                // here keeps the finally idempotent.
                try {
                    muxer.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "muxer.stop() failed (best-effort on throwing path)", e)
                }
            }
            extractors.forEach {
                try { it.release() } catch (_: Exception) {}
            }
        }
    }

    private fun buildTrackDescriptor(format: MediaFormat): TrackDescriptor? {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
        return when {
            mime.startsWith("video/") -> TrackDescriptor(
                kind = TrackKind.VIDEO,
                mime = mime,
                width = format.takeIf { it.containsKey(MediaFormat.KEY_WIDTH) }?.getInteger(MediaFormat.KEY_WIDTH),
                height = format.takeIf { it.containsKey(MediaFormat.KEY_HEIGHT) }?.getInteger(MediaFormat.KEY_HEIGHT)
            )

            mime.startsWith("audio/") -> TrackDescriptor(
                kind = TrackKind.AUDIO,
                mime = mime,
                sampleRate = format.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }?.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channelCount = format.takeIf { it.containsKey(MediaFormat.KEY_CHANNEL_COUNT) }?.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            )

            else -> null
        }
    }
}
