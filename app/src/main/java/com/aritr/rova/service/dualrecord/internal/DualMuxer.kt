package com.aritr.rova.service.dualrecord.internal

import android.media.MediaFormat
import android.media.MediaMuxer
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.utils.RovaLog
import java.io.File
import java.nio.ByteBuffer
import android.media.MediaCodec

/**
 * Phase 6.1a — pair of `MediaMuxer` instances driven by
 * `DualMuxerStateMachine` (Task 7). Per-side failure is isolated: one
 * muxer throwing during writeSampleData or stop is logged and the side
 * marked failed; the live side continues. `stop()` is tolerant — both
 * sides are attempted regardless of which fails first.
 *
 * Runtime layer: NO unit tests; the state machine seam already has
 * JVM coverage (Task 7).
 */
internal class DualMuxer(
    portraitFile: File,
    landscapeFile: File,
    private val onSideFailure: (VideoSide, Throwable) -> Unit,
) {

    private data class SideState(
        val muxer: MediaMuxer,
        val file: File,
        var videoTrackIndex: Int = -1,
        var audioTrackIndex: Int = -1,
        var failed: Boolean = false,
    )

    private val sm = DualMuxerStateMachine()
    private val sides: Map<VideoSide, SideState> = mapOf(
        VideoSide.PORTRAIT to SideState(
            MediaMuxer(portraitFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
            portraitFile,
        ),
        VideoSide.LANDSCAPE to SideState(
            MediaMuxer(landscapeFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
            landscapeFile,
        ),
    )

    init {
        sm.dispatch(DualMuxerStateMachine.Event.TracksOpened)
    }

    /** Returns the track index in that side's muxer; -1 if the side has failed. */
    fun addVideoTrack(side: VideoSide, format: MediaFormat): Int =
        addTrack(side) { it.muxer.addTrack(format).also { idx -> it.videoTrackIndex = idx } }

    fun addAudioTrack(format: MediaFormat) {
        sides.forEach { (side, _) ->
            addTrack(side) { it.muxer.addTrack(format).also { idx -> it.audioTrackIndex = idx } }
        }
    }

    private inline fun addTrack(side: VideoSide, block: (SideState) -> Int): Int {
        val s = sides[side] ?: return -1
        if (s.failed) return -1
        return try {
            block(s)
        } catch (e: Throwable) {
            failSide(side, e)
            -1
        }
    }

    fun maybeStart() {
        if (sm.state != DualMuxerStateMachine.State.ADDING_TRACKS) return
        if (!sides.values.all { it.failed || (it.videoTrackIndex >= 0 && it.audioTrackIndex >= 0) }) return
        sides.values.forEach { s ->
            if (s.failed) return@forEach
            try {
                s.muxer.start()
            } catch (e: Throwable) {
                failSide(sideOf(s)!!, e)
            }
        }
        sm.dispatch(DualMuxerStateMachine.Event.AllTracksAdded)
    }

    fun writeVideo(side: VideoSide, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val s = sides[side] ?: return
        if (s.failed || sm.state != DualMuxerStateMachine.State.STARTED) return
        try {
            s.muxer.writeSampleData(s.videoTrackIndex, buffer, info)
        } catch (e: Throwable) {
            failSide(side, e)
        }
    }

    fun writeAudio(target: Set<VideoSide>, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (sm.state != DualMuxerStateMachine.State.STARTED) return
        target.forEach { side ->
            val s = sides[side] ?: return@forEach
            if (s.failed) return@forEach
            try {
                s.muxer.writeSampleData(s.audioTrackIndex, buffer, info)
            } catch (e: Throwable) {
                failSide(side, e)
            }
        }
    }

    /** Returns each side's resulting File, or null if that side failed. */
    fun stop(): Map<VideoSide, File?> {
        if (sm.state == DualMuxerStateMachine.State.STARTED) {
            sm.dispatch(DualMuxerStateMachine.Event.StopRequested)
        }
        sides.forEach { (side, s) ->
            if (!s.failed) {
                try { s.muxer.stop() } catch (e: Throwable) { failSide(side, e) }
            }
            try { s.muxer.release() } catch (e: Throwable) { RovaLog.w("DualMuxer $side release", e) }
        }
        if (sm.state == DualMuxerStateMachine.State.STOPPING) {
            sm.dispatch(DualMuxerStateMachine.Event.Stopped)
        }
        return sides.mapValues { (_, s) -> if (s.failed) null else s.file }
    }

    private fun failSide(side: VideoSide, cause: Throwable) {
        val s = sides[side] ?: return
        if (s.failed) return
        s.failed = true
        sm.dispatch(DualMuxerStateMachine.Event.SideFailed(side))
        onSideFailure(side, cause)
    }

    private fun sideOf(state: SideState): VideoSide? =
        sides.entries.firstOrNull { it.value === state }?.key
}
