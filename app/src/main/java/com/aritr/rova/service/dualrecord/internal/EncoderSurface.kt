package com.aritr.rova.service.dualrecord.internal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.utils.RovaLog
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 6.1a — single-side `MediaCodec` AVC encoder wrapper. Phase 6.1b
 * binds two of these (PORTRAIT + LANDSCAPE) into `DualVideoRecorder`.
 *
 *  - `inputSurface`: provided to `EglRouter` as a render target.
 *  - `start()`: starts the encoder. EglRouter then drives frames into
 *    `inputSurface`; this class drains encoded output via `drainLoop()`.
 *  - `signalEndOfInputStream()`: signals EOS; `drainLoop` exits after
 *    flushing the final samples.
 *  - `release()`: idempotent. Releases the encoder + the input surface.
 *
 * The drain pushes each sample to `onSample` (the muxer write path) and
 * the format-changed event to `onFormatReady` (so the muxer can call
 * `addTrack(format)`).
 *
 * Runtime layer: NO unit tests — `MediaCodec` is not JVM-runnable. On-device
 * smoke at Phase 6.1b verifies behavior.
 */
internal class EncoderSurface(
    val side: VideoSide,
    private val outputSize: Size,
    private val bitrateBps: Long,
    private val fps: Int,
    private val onFormatReady: (MediaFormat) -> Unit,
    private val onSample: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
    private val onSideFailure: (Throwable) -> Unit,
) {

    private val codec: MediaCodec
    val inputSurface: Surface
    private val running = AtomicBoolean(false)
    private val eosSignalled = AtomicBoolean(false)
    @Volatile private var drainThread: Thread? = null

    init {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, outputSize.width, outputSize.height,
        ).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps.toInt().coerceAtLeast(1))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, /* surface */ null, /* crypto */ null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        codec.start()
        drainThread = Thread({ drainLoop() }, "EncoderSurface-${side.name}-drain").also { it.start() }
    }

    fun signalEndOfInputStream() {
        if (running.get() && eosSignalled.compareAndSet(false, true)) {
            codec.signalEndOfInputStream()
        }
    }

    fun release() {
        if (!running.compareAndSet(true, false)) return
        try { codec.stop() } catch (e: Throwable) { RovaLog.w("EncoderSurface ${side} stop", e) }
        try { codec.release() } catch (e: Throwable) { RovaLog.w("EncoderSurface ${side} release", e) }
        try { inputSurface.release() } catch (e: Throwable) { RovaLog.w("EncoderSurface ${side} surface", e) }
        drainThread?.join(500L)
        drainThread = null
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val outIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        onFormatReady(codec.outputFormat)
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0) {
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                onSample(buf, info)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, /* render */ false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }
        } catch (e: Throwable) {
            RovaLog.w("EncoderSurface ${side} drain", e)
            onSideFailure(e)
        }
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
