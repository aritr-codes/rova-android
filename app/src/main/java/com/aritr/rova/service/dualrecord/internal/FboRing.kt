package com.aritr.rova.service.dualrecord.internal

import android.opengl.GLES20
import com.aritr.rova.utils.RovaLog

/**
 * DualShot FBO ring (B2, 2026-05-21) — a depth-[RING_DEPTH] ring of
 * off-screen framebuffers used by [EglRouter] to snapshot each camera
 * frame.
 *
 * The callback thread blits the live camera OES frame into a ring slot,
 * then hands the slot's [Slot.textureId] to the encoder threads. The
 * encoders sample that 2D copy on their own clocks — so the callback
 * thread never waits on encoder GPU work, and the camera
 * `SurfaceTexture`'s single OES texture is consumed entirely on the
 * callback thread. See the 2026-05-21 FBO-ring design doc §3 / §4.
 *
 * Each slot is one framebuffer with one [FBO_WIDTH]x[FBO_HEIGHT] RGBA8
 * `GL_TEXTURE_2D` colour attachment (~19.7 MB; ~59 MB for the 3-deep
 * ring). The textures live in the [EglRouter] root context's share
 * group, so the encoder contexts can sample them.
 *
 * Lifecycle — created, advanced, and released on the [EglRouter]
 * callback thread only (the thread that owns the root GL context). Not
 * thread-safe; it does not need to be.
 *
 * Runtime GL layer — no unit tests (the dualrecord policy; `GLES20.*`
 * are JVM no-ops under `isReturnDefaultValues=true`).
 */
internal class FboRing {

    /** One ring slot: a framebuffer object and its colour texture. */
    internal data class Slot(val framebufferId: Int, val textureId: Int)

    private val slots = ArrayList<Slot>(RING_DEPTH)
    private var counter = 0

    /**
     * Allocate [RING_DEPTH] framebuffers, each with one
     * [FBO_WIDTH]x[FBO_HEIGHT] RGBA8 `GL_TEXTURE_2D` colour attachment.
     * MUST run on the thread owning the GL context. Returns `false` on
     * any GL failure (incomplete framebuffer) — the caller then leaves
     * the ring unused. Not idempotent on success — calling [init] twice on a live ring
     * leaks the first set of GL objects; call [release] first. On a
     * `false` return everything this call allocated is deleted before
     * returning.
     */
    fun init(): Boolean {
        val fbos = IntArray(RING_DEPTH)
        val texs = IntArray(RING_DEPTH)
        GLES20.glGenFramebuffers(RING_DEPTH, fbos, 0)
        GLES20.glGenTextures(RING_DEPTH, texs, 0)
        for (i in 0 until RING_DEPTH) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texs[i])
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                FBO_WIDTH, FBO_HEIGHT, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbos[i])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, texs[i], 0,
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                RovaLog.w(
                    "FboRing.init: framebuffer $i incomplete " +
                        "status=0x${status.toString(16)}",
                )
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                GLES20.glDeleteFramebuffers(RING_DEPTH, fbos, 0)
                GLES20.glDeleteTextures(RING_DEPTH, texs, 0)
                slots.clear()
                return false
            }
            slots.add(Slot(fbos[i], texs[i]))
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return true
    }

    /**
     * Advance to the next slot (round-robin over [RING_DEPTH]) and return
     * it. Only valid after a successful [init].
     */
    fun advance(): Slot {
        val slot = slots[counter]
        counter = (counter + 1) % RING_DEPTH
        return slot
    }

    /** Delete every framebuffer + texture. Runs on the GL-context thread. */
    fun release() {
        if (slots.isEmpty()) return
        val fbos = IntArray(slots.size) { slots[it].framebufferId }
        val texs = IntArray(slots.size) { slots[it].textureId }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(fbos.size, fbos, 0)
        GLES20.glDeleteTextures(texs.size, texs, 0)
        slots.clear()
    }

    companion object {
        /**
         * Ring depth. A slot the callback thread writes is not reused for
         * [RING_DEPTH] frames (~100 ms at 30 fps) — far longer than an
         * encoder, picking the latest frame, ever holds it (design §4.2).
         * Pre-flagged escalation: drop to 2 (~39 MB) under memory pressure.
         */
        const val RING_DEPTH = 3

        /**
         * FBO width. The portrait crop samples 27/64 of the frame width;
         * 2560 x 27/64 = 1080 lands exactly on the 1080-wide portrait
         * encoder with no upscale (design §4.1).
         */
        const val FBO_WIDTH = 2560

        /** FBO height — 4:3 with [FBO_WIDTH], matching the camera frame. */
        const val FBO_HEIGHT = 1920
    }
}
