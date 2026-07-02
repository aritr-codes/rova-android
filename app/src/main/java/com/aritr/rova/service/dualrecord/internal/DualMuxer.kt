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
 * ## Thread-safety (fix 2026-07-02, device-diagnosed)
 * `addVideoTrack`/`addAudioTrack`/`maybeStart`/`writeVideo` are invoked
 * from THREE threads — the PORTRAIT and LANDSCAPE `EncoderSurface` drain
 * threads (via `onFormatReady`/`onSample`) and the `AudioFanOut` thread.
 * `MediaMuxer` is not thread-safe, and the old `maybeStart` did an
 * unsynchronised check-then-act on `sm.state`: when the last video track
 * and the audio track were added within the same instant, two threads both
 * passed the `ADDING_TRACKS` gate and both ran the start loop → one started
 * a muxer, the other re-`start()`ed it (`IllegalStateException: wrong
 * state(STARTED)`) or raced `nativeStart` (`Failed to start the muxer`),
 * failing BOTH sides → the whole segment was dropped (the "missing clip"
 * on the no-wait cadence; a 30s gap staggered the events so it didn't
 * fire). Every mutating method now holds [lock], making the start gate
 * atomic. [failSide] is only ever called while holding [lock]; the
 * `onSideFailure` callback is drained and fired OUTSIDE the lock so a
 * future non-trivial callback cannot deadlock.
 *
 * ## First-segment keyframe (same fix)
 * A single muxer holds both this side's video track AND the shared audio
 * track, so it cannot `start()` until the audio format has also arrived.
 * Video output (with `KEY_I_FRAME_INTERVAL=1`, so the very first sample is
 * the leading IDR) frequently precedes the audio format by a few frames —
 * those pre-START samples used to be DROPPED, yielding a keyframe-less,
 * frozen first clip. They are now buffered per side (a byte copy, since the
 * codec reclaims the `ByteBuffer` on `releaseOutputBuffer`) and flushed in
 * order the instant the muxer starts, before the state flips to STARTED.
 *
 * Runtime layer: NO unit tests; the state machine seam already has
 * JVM coverage (Task 7).
 */
internal class DualMuxer(
    portraitFile: File,
    landscapeFile: File,
    private val onSideFailure: (VideoSide, Throwable) -> Unit,
) {

    private class PendingSample(val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private data class SideState(
        val muxer: MediaMuxer,
        val file: File,
        var videoTrackIndex: Int = -1,
        var audioTrackIndex: Int = -1,
        var failed: Boolean = false,
        // Video samples emitted before the muxer could start (waiting on the
        // audio track). Flushed in order by maybeStart; the leading entry is
        // the IDR keyframe, so dropping it froze the first clip.
        val pendingVideo: ArrayDeque<PendingSample> = ArrayDeque(),
    )

    /** Serialises all MediaMuxer access + the start gate. */
    private val lock = Any()

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

    /**
     * Phase 6.1b smoke-fix #4 — set the MP4 composition-matrix rotation
     * hint for [side]'s muxer. Must be called BEFORE the muxer starts
     * (i.e. between `MediaMuxer` construction in this class's `init` and
     * [maybeStart]). Per AOSP `MediaMuxer.setOrientationHint` docs the
     * value MUST be one of 0/90/180/270; a non-conforming value throws.
     *
     * This replaces the previous reliance on `MediaFormat.KEY_ROTATION`
     * in the encoder output format (which MP4 ignores at `addTrack` per
     * the same docs, and which some Qualcomm encoders honor at the
     * bitstream level — producing a double-rotation now that pixels are
     * pre-oriented per side). The hint here is the SOLE rotation
     * metadata; the encoder format no longer carries `KEY_ROTATION`.
     *
     * Side already-failed → no-op (matches the addTrack/writeSample
     * tolerance pattern: the live side continues even if the other
     * muxer raised on construction).
     */
    fun setOrientationHint(side: VideoSide, degrees: Int) {
        val failure = synchronized(lock) {
            val s = sides[side] ?: return
            if (s.failed) return
            try {
                s.muxer.setOrientationHint(degrees)
                null
            } catch (e: Throwable) {
                failSide(side, e)
            }
        }
        fireFailure(failure)
    }

    /** Returns the track index in that side's muxer; -1 if the side has failed. */
    fun addVideoTrack(side: VideoSide, format: MediaFormat): Int {
        val (idx, failure) = synchronized(lock) {
            addTrack(side) { it.muxer.addTrack(format).also { i -> it.videoTrackIndex = i } }
        }
        fireFailure(failure)
        return idx
    }

    fun addAudioTrack(format: MediaFormat) {
        val failures = synchronized(lock) {
            sides.keys.mapNotNull { side ->
                addTrack(side) { it.muxer.addTrack(format).also { i -> it.audioTrackIndex = i } }.second
            }
        }
        failures.forEach { fireFailure(it) }
    }

    /** Runs [block] under [lock]; returns (index, deferredFailure-or-null). */
    private inline fun addTrack(side: VideoSide, block: (SideState) -> Int): Pair<Int, DeferredFailure?> {
        val s = sides[side] ?: return -1 to null
        if (s.failed) return -1 to null
        return try {
            block(s) to null
        } catch (e: Throwable) {
            -1 to failSide(side, e)
        }
    }

    fun maybeStart() {
        val failures = mutableListOf<DeferredFailure>()
        synchronized(lock) {
            if (sm.state != DualMuxerStateMachine.State.ADDING_TRACKS) return
            if (!sides.values.all { it.failed || (it.videoTrackIndex >= 0 && it.audioTrackIndex >= 0) }) return
            sides.values.forEach { s ->
                if (s.failed) return@forEach
                try {
                    s.muxer.start()
                    // Flush buffered pre-START video (leading IDR first) BEFORE
                    // the state flips to STARTED, so keyframe ordering holds.
                    while (s.pendingVideo.isNotEmpty()) {
                        val p = s.pendingVideo.removeFirst()
                        s.muxer.writeSampleData(s.videoTrackIndex, p.data, p.info)
                    }
                } catch (e: Throwable) {
                    failSide(sideOf(s)!!, e)?.let { failures += it }
                }
            }
            sm.dispatch(DualMuxerStateMachine.Event.AllTracksAdded)
        }
        failures.forEach { fireFailure(it) }
    }

    fun writeVideo(side: VideoSide, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val failure = synchronized(lock) {
            val s = sides[side] ?: return
            if (s.failed) return
            if (sm.state != DualMuxerStateMachine.State.STARTED) {
                // Muxer not started yet (waiting on the audio track): buffer a
                // copy — the codec reclaims `buffer` on releaseOutputBuffer.
                if (s.pendingVideo.size < MAX_PENDING_VIDEO) {
                    s.pendingVideo.addLast(copyOf(buffer, info))
                } else {
                    // ponytail: bounded so a never-arriving audio track can't OOM.
                    // The cap (~MAX_PENDING_VIDEO frames) far exceeds the real
                    // few-frame window; overflow means the muxer is never
                    // starting — keep the buffered leading keyframe, drop newest.
                    RovaLog.w("DualMuxer $side pre-start buffer full (${s.pendingVideo.size})", null)
                }
                return
            }
            try {
                s.muxer.writeSampleData(s.videoTrackIndex, buffer, info)
                null
            } catch (e: Throwable) {
                failSide(side, e)
            }
        }
        fireFailure(failure)
    }

    fun writeAudio(target: Set<VideoSide>, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        val failures = synchronized(lock) {
            if (sm.state != DualMuxerStateMachine.State.STARTED) return
            target.mapNotNull { side ->
                val s = sides[side] ?: return@mapNotNull null
                if (s.failed) return@mapNotNull null
                try {
                    s.muxer.writeSampleData(s.audioTrackIndex, buffer, info)
                    null
                } catch (e: Throwable) {
                    failSide(side, e)
                }
            }
        }
        failures.forEach { fireFailure(it) }
    }

    /** Returns each side's resulting File, or null if that side failed. */
    fun stop(): Map<VideoSide, File?> {
        val (result, failures) = synchronized(lock) {
            val fails = mutableListOf<DeferredFailure>()
            if (sm.state == DualMuxerStateMachine.State.STARTED) {
                sm.dispatch(DualMuxerStateMachine.Event.StopRequested)
            }
            sides.forEach { (side, s) ->
                s.pendingVideo.clear()
                if (!s.failed) {
                    try { s.muxer.stop() } catch (e: Throwable) { failSide(side, e)?.let { fails += it } }
                }
                try { s.muxer.release() } catch (e: Throwable) { RovaLog.w("DualMuxer $side release", e) }
            }
            if (sm.state == DualMuxerStateMachine.State.STOPPING) {
                sm.dispatch(DualMuxerStateMachine.Event.Stopped)
            }
            sides.mapValues { (_, s) -> if (s.failed) null else s.file } to fails
        }
        failures.forEach { fireFailure(it) }
        return result
    }

    /** A side failure captured under [lock], to fire its callback after release. */
    private class DeferredFailure(val side: VideoSide, val cause: Throwable)

    /**
     * Marks [side] failed under [lock] and returns a [DeferredFailure] the
     * caller must pass to [fireFailure] AFTER releasing the lock (or null if
     * already-failed). Never invokes the callback itself — holding [lock]
     * across an arbitrary callback is a deadlock hazard.
     */
    private fun failSide(side: VideoSide, cause: Throwable): DeferredFailure? {
        val s = sides[side] ?: return null
        if (s.failed) return null
        s.failed = true
        s.pendingVideo.clear()
        sm.dispatch(DualMuxerStateMachine.Event.SideFailed(side))
        return DeferredFailure(side, cause)
    }

    private fun fireFailure(failure: DeferredFailure?) {
        if (failure == null) return
        RovaLog.w("DualMuxer side ${failure.side} failed", failure.cause)
        onSideFailure(failure.side, failure.cause)
    }

    private fun sideOf(state: SideState): VideoSide? =
        sides.entries.firstOrNull { it.value === state }?.key

    private companion object {
        /** ~5s at 24fps — far beyond the real pre-start window; OOM guard only. */
        private const val MAX_PENDING_VIDEO = 120

        fun copyOf(buffer: ByteBuffer, info: MediaCodec.BufferInfo): PendingSample {
            val dup = buffer.duplicate()
            dup.position(info.offset).limit(info.offset + info.size)
            val data = ByteBuffer.allocate(info.size).put(dup).apply { flip() }
            val copy = MediaCodec.BufferInfo().apply {
                set(0, info.size, info.presentationTimeUs, info.flags)
            }
            return PendingSample(data, copy)
        }
    }
}
