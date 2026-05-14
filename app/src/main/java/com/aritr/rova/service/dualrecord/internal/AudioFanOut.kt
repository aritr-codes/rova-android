package com.aritr.rova.service.dualrecord.internal

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.utils.RovaLog
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 6.1a — single AudioRecord + single AAC MediaCodec encoder.
 * Encoded AAC samples are broadcast to both muxers via the
 * `AudioFanOutPlanner` (Task 8) — `byteBuffer.duplicate()` per write
 * ensures each muxer's writeSampleData sees an independent
 * position/limit.
 *
 * Audio PTS: `(cumulativePcmFrames / sampleRate) * 1_000_000` µs
 * (spec §4). Independent of wall-clock; immune to scheduling jitter.
 *
 * Runtime layer — no unit tests; the planner seam already has 8 JVM
 * tests in AudioFanOutPlannerTest (Task 8).
 */
internal class AudioFanOut(
    private val sampleRate: Int,
    private val bitrateBps: Int,
    private val onFormatReady: (MediaFormat) -> Unit,
    private val onSample: (target: Set<VideoSide>, buffer: ByteBuffer, info: MediaCodec.BufferInfo) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {

    private val planner = AudioFanOutPlanner()
    private val running = AtomicBoolean(false)
    private val audioRecord: AudioRecord
    private val encoder: MediaCodec
    @Volatile private var captureThread: Thread? = null
    @Volatile private var drainThread: Thread? = null

    init {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(2048)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.CAMCORDER, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf,
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord init failed (state=${audioRecord.state})")
        }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, /* surface */ null, /* crypto */ null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            encoder.start()
            audioRecord.startRecording()
            captureThread = Thread({ captureLoop() }, "AudioFanOut-capture").also { it.start() }
            drainThread = Thread({ drainLoop() }, "AudioFanOut-drain").also { it.start() }
        } catch (e: Throwable) {
            running.set(false); onFailure(e)
        }
    }

    fun stopSide(side: VideoSide) {
        planner.stopSide(side)
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { audioRecord.stop() } catch (e: Throwable) { RovaLog.w("AudioFanOut record stop", e) }
        try { encoder.signalEndOfInputStream() } catch (e: Throwable) { RovaLog.w("AudioFanOut EOS", e) }
        captureThread?.join(500L); captureThread = null
        drainThread?.join(500L); drainThread = null
        try { audioRecord.release() } catch (e: Throwable) { RovaLog.w("AudioFanOut record release", e) }
        try { encoder.stop() } catch (e: Throwable) { RovaLog.w("AudioFanOut encoder stop", e) }
        try { encoder.release() } catch (e: Throwable) { RovaLog.w("AudioFanOut encoder release", e) }
    }

    private fun captureLoop() {
        val bufSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(2048)
        val pcm = ByteArray(bufSize)
        var pcmFramesSeen = 0L
        try {
            while (running.get()) {
                val read = audioRecord.read(pcm, 0, pcm.size)
                if (read <= 0) continue
                val inputIdx = encoder.dequeueInputBuffer(10_000L)
                if (inputIdx >= 0) {
                    val inputBuf = encoder.getInputBuffer(inputIdx) ?: continue
                    inputBuf.clear(); inputBuf.put(pcm, 0, read)
                    val ptsUs = pcmFramesSeen * 1_000_000L / sampleRate
                    encoder.queueInputBuffer(inputIdx, 0, read, ptsUs, 0)
                    pcmFramesSeen += read / 2L  // 16-bit mono → 2 bytes per frame
                }
            }
            val inputIdx = encoder.dequeueInputBuffer(10_000L)
            if (inputIdx >= 0) {
                val ptsUs = pcmFramesSeen * 1_000_000L / sampleRate
                encoder.queueInputBuffer(inputIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                planner.markEosSent()
            }
        } catch (e: Throwable) {
            RovaLog.w("AudioFanOut capture", e); onFailure(e)
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                val outIdx = encoder.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!running.get()) break else continue
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormatReady(encoder.outputFormat)
                    outIdx >= 0 -> {
                        val buf = encoder.getOutputBuffer(outIdx)
                        if (buf != null && info.size > 0 &&
                            (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                        ) {
                            val decision = planner.planWrite()
                            if (decision is AudioFanOutPlanner.Decision.Route) {
                                onSample(decision.sides, buf.duplicate(), info)
                            }
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } catch (e: Throwable) {
            RovaLog.w("AudioFanOut drain", e); onFailure(e)
        }
    }
}
