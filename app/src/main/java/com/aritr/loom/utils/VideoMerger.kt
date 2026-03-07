package com.aritr.loom.utils

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

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(rotation)
        
        val extractors = mutableListOf<MediaExtractor>()

        try {
            // 1. Setup Extractors
            validSegments.forEach { file ->
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                extractors.add(extractor)
            }

            // 2. Setup Tracks from first segment
            // R3: Key by MIME type so subsequent segments with different track order map correctly
            val mimeToMuxerIndex = mutableMapOf<String, Int>() // MIME type -> muxer track index
            var bufferSize = 0
            val firstExtractor = extractors[0]

            for (i in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    val newIndex = muxer.addTrack(format)
                    mimeToMuxerIndex[mime] = newIndex

                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val size = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (size > bufferSize) bufferSize = size
                    }
                }
            }

            if (bufferSize <= 0) bufferSize = BUFFER_SIZE

            muxer.start()

            // 3. Write Data
            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            val trackOffsets = mutableMapOf<Int, Long>() // Track Index -> Offset Us
            
            // Initialize offsets
            mimeToMuxerIndex.values.forEach { trackOffsets[it] = 0L }

            val totalSegments = extractors.size
            
            extractors.forEachIndexed { index, extractor ->
                // Check for cancellation
                if (!coroutineContext.isActive) {
                    throw kotlinx.coroutines.CancellationException("Merge cancelled")
                }

                val localToMuxerTrackMap = mutableMapOf<Int, Int>()

                // R3: Map tracks by MIME type — safe regardless of per-segment track order
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    val muxerTrack = mimeToMuxerIndex[mime] ?: continue
                    localToMuxerTrackMap[i] = muxerTrack
                    extractor.selectTrack(i)
                }

                var maxTimeStampUs = 0L

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break

                    val trackIndex = extractor.sampleTrackIndex
                    val muxerTrackIndex = localToMuxerTrackMap[trackIndex] ?: continue
                    
                    val presentationTimeUs = extractor.sampleTime
                    val offset = trackOffsets[muxerTrackIndex] ?: 0L
                    
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.flags = extractor.sampleFlags
                    bufferInfo.presentationTimeUs = presentationTimeUs + offset

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)

                    if (presentationTimeUs > maxTimeStampUs) {
                        maxTimeStampUs = presentationTimeUs
                    }

                    extractor.advance()
                }

                // Update offsets for next segment (+1ms buffer to avoid overlapping frames)
                val durationUs = maxTimeStampUs + 1000L
                mimeToMuxerIndex.values.forEach { idx ->
                    trackOffsets[idx] = (trackOffsets[idx] ?: 0L) + durationUs
                }
                
                extractor.release()

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
            try {
                muxer.stop()
            } catch (e: Exception) {
                // Ignore error on stop if it failed before start
                e.printStackTrace()
            }
            muxer.release()
            
            // Ensure all extractors are released
            extractors.forEach { 
                try { it.release() } catch (e: Exception) {} 
            }
        }
    }
}
