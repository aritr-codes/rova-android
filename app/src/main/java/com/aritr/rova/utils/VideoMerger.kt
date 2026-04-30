package com.aritr.rova.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

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
     * Merges multiple video files into a single output file using MediaMuxer.
     * 
     * @param segments List of video files to merge
     * @param outputFile Destination file
     * @param onProgress Callback for progress (0.0 to 1.0)
     */
    suspend fun mergeSegments(
        segments: List<File>,
        outputFile: File,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (segments.isEmpty()) {
            throw IllegalArgumentException("No segments to merge")
        }

        val validSegments = segments.filter { it.exists() && it.length() > 0 }
        if (validSegments.isEmpty()) {
            throw IllegalArgumentException("No valid segments found")
        }

        Log.d(TAG, "Starting merge of ${validSegments.size} segments to ${outputFile.name}")
        onProgress(0f)

        // P3: Pre-compute total bytes for byte-weighted progress
        val totalBytes = validSegments.sumOf { it.length() }
        var bytesProcessed = 0L

        // 0. Extract Rotation from first segment
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

        val extractors = mutableListOf<MediaExtractor>()
        var muxerStarted = false
        var muxer: MediaMuxer? = null

        try {
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
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
            val mux = muxer // smart-cast to non-null

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
                            val newIndex = mux.addTrack(format)
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

            mux.start()
            muxerStarted = true

            // 3. Write Data
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var timelineOffsetUs = 0L
            val totalSegments = extractors.size
            
            extractors.forEachIndexed { index, extractor ->
                // Check for cancellation
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

                    mux.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                    wroteSamples = true
                    lastWrittenTimeUs[muxerTrackIndex] = outputTimeUs

                    if (outputTimeUs > segmentEndUs) {
                        segmentEndUs = outputTimeUs
                    }

                    extractor.advance()
                }

                if (wroteSamples) {
                    // Advance the whole muxer timeline together so missing audio/video
                    // tracks still leave the right gap instead of collapsing sync.
                    timelineOffsetUs = segmentEndUs + 1000L
                }
                
                // P3: Byte-weighted progress — large segments take proportionally more time
                bytesProcessed += validSegments[index].length()
                onProgress(if (totalBytes > 0) bytesProcessed.toFloat() / totalBytes else (index + 1).toFloat() / totalSegments)
            }
            
            Log.d(TAG, "Merge completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Merge failed", e)
            outputFile.delete() // Cleanup partial file
            throw e
        } finally {
            if (muxerStarted) {
                try { muxer?.stop() } catch (e: Exception) { Log.w(TAG, "muxer.stop() failed", e) }
            }
            muxer?.release()

            // Ensure all extractors are released
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
