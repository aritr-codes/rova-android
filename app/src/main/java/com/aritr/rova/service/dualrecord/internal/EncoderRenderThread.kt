package com.aritr.rova.service.dualrecord.internal

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.utils.RovaLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * One camera frame handed from the [EglRouter] callback thread to an
 * [EncoderRenderThread].
 *
 *  - [texMatrix] is the `SurfaceTexture` transform for this frame — a
 *    defensive copy the callback thread will not mutate. It is shared
 *    read-only across both encoders.
 *  - [barrier] is the per-frame [CountDownLatch] the callback thread
 *    awaits. The encoder counts it down after `glFinish` (camera-texture
 *    sampling complete) and BEFORE its blocking `eglSwapBuffers`.
 */
internal class EncoderFrame(
    val texMatrix: FloatArray,
    val barrier: CountDownLatch,
)

/**
 * DualShot render threading (2026-05-21) — one dedicated render thread
 * per encoder target.
 *
 * Each instance owns its own [EGLContext], created in the [EglRouter]
 * root context's share group so it samples the shared camera OES
 * texture, plus its own window [EGLSurface] over the MediaCodec input
 * [Surface] and its own GL program + vertex buffer. The context never
 * leaves this thread — that is what removes the cross-thread EGL-surface
 * races the pre-threading [EglRouter] had to patch with two-tier
 * locking.
 *
 * Per-frame loop: take the latest [EncoderFrame] from the [FrameMailbox]
 * (latest-wins → stale frames dropped for this side only), draw the
 * cropped quad, `glFinish`, count the frame barrier down, then
 * `eglSwapBuffers`. The swap blocks on MediaCodec back-pressure but runs
 * AFTER the barrier countdown, so a stalled encoder never freezes the
 * callback thread. See the 2026-05-21 render-threading design doc §5.
 *
 * Runtime EGL/GL layer — no unit tests (the dualrecord policy). The
 * latest-wins / poison-pill logic is unit-tested via [FrameMailbox].
 */
internal class EncoderRenderThread(
    private val side: VideoSide,
    private val encoderSurface: Surface,
    private val eglDisplay: EGLDisplay,
    private val eglConfig: EGLConfig,
    private val sharedContext: EGLContext,
    private val inputTextureId: Int,
    private val uvTransform: FloatArray,
    private val surfaceWidth: Int,
    private val surfaceHeight: Int,
    private val viewportX: Int,
    private val viewportY: Int,
    private val viewportW: Int,
    private val viewportH: Int,
) : Thread("EglEncoder-$side") {

    private val mailbox = FrameMailbox<EncoderFrame>()

    /** True once an unrecoverable EGL/GL error took this side down. */
    @Volatile
    var failed: Boolean = false
        private set

    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var program: Int = 0
    private var aPositionLoc: Int = -1
    private var aUvLoc: Int = -1
    private var uTexMatrixLoc: Int = -1
    private var uTextureLoc: Int = -1
    private var loggedOnce = false

    private val finalMatrix = FloatArray(16)

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_VERTS).position(0) }

    /** Hand the newest frame to this encoder. Called on the callback thread. */
    fun submit(frame: EncoderFrame) = mailbox.offer(frame)

    /** Signal shutdown — the run loop drains, tears down EGL on this thread, exits. */
    fun shutdown() = mailbox.poison()

    override fun run() {
        if (!initEgl()) {
            failed = true
            teardownEgl()
            return
        }
        while (true) {
            val frame = mailbox.take() ?: break   // null = poisoned → shutdown
            var drawOk = true
            try {
                drawFrame(frame.texMatrix)
            } catch (t: Throwable) {
                RovaLog.w("EglEncoder[$side] draw failed", t)
                failed = true
                drawOk = false
            } finally {
                // ALWAYS release the callback thread, even on draw failure,
                // so a broken encoder side never wedges the frame barrier.
                frame.barrier.countDown()
            }
            if (!drawOk) break
            try {
                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                    val err = EGL14.eglGetError()
                    RovaLog.w("EglEncoder[$side] eglSwapBuffers failed err=0x${err.toString(16)}")
                    failed = true
                    break
                }
            } catch (t: Throwable) {
                // Defensive — eglSwapBuffers normally returns false rather
                // than throwing, but a JNI-layer wrapper could still throw.
                RovaLog.w("EglEncoder[$side] eglSwapBuffers threw", t)
                failed = true
                break
            }
        }
        teardownEgl()
    }

    private fun initEgl(): Boolean {
        try {
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            // share_context = the router's root context → this context
            // joins the share group and can sample the camera OES texture.
            context = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedContext, ctxAttribs, 0)
            if (context === EGL14.EGL_NO_CONTEXT) {
                RovaLog.w("EglEncoder[$side] eglCreateContext failed")
                return false
            }
            val winAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, encoderSurface, winAttribs, 0)
            if (eglSurface === EGL14.EGL_NO_SURFACE) {
                RovaLog.w("EglEncoder[$side] eglCreateWindowSurface failed")
                return false
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, context)) {
                RovaLog.w("EglEncoder[$side] eglMakeCurrent failed")
                return false
            }
            program = buildProgram()
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
            aUvLoc = GLES20.glGetAttribLocation(program, "aUv")
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
            uTextureLoc = GLES20.glGetUniformLocation(program, "sTex")
            return true
        } catch (t: Throwable) {
            RovaLog.w("EglEncoder[$side] initEgl failed", t)
            return false
        }
    }

    private fun drawFrame(texMatrix: FloatArray) {
        // Clear the full surface black, then draw into the aspect-fit
        // viewport (encoder surfaces are aspect-matched → full viewport,
        // so the clear has no visible effect; kept for parity/safety).
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(viewportX, viewportY, viewportW, viewportH)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        // uTexMatrix = uvTransform x texMatrix. Encoder targets are never
        // mirrored — the front-camera mirror applies only to the
        // side=null PREVIEW path in EglRouter.
        Matrix.multiplyMM(finalMatrix, 0, uvTransform, 0, texMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, finalMatrix, 0)

        if (!loggedOnce) {
            loggedOnce = true
            RovaLog.d(
                "EglEncoder[$side] first frame: encoder=${surfaceWidth}x$surfaceHeight " +
                    "viewport=[$viewportX,$viewportY,$viewportW,$viewportH]"
            )
        }

        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(
            aPositionLoc, 2, GLES20.GL_FLOAT, false, FLOATS_PER_VERT * 4, vertexBuffer,
        )
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(aUvLoc)
        GLES20.glVertexAttribPointer(
            aUvLoc, 2, GLES20.GL_FLOAT, false, FLOATS_PER_VERT * 4, vertexBuffer,
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aUvLoc)

        // Block until the GPU has finished SAMPLING the shared camera
        // texture. Only after this is it safe for the callback thread to
        // call updateTexImage for the next frame — run() counts the frame
        // barrier down immediately after drawFrame() returns. Design §5.
        GLES20.glFinish()
    }

    private fun teardownEgl() {
        try {
            EGL14.eglMakeCurrent(
                eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
        } catch (t: Throwable) {
            RovaLog.w("EglEncoder[$side] eglMakeCurrent NO", t)
        }
        if (eglSurface !== EGL14.EGL_NO_SURFACE) {
            try { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            catch (t: Throwable) { RovaLog.w("EglEncoder[$side] eglDestroySurface", t) }
            eglSurface = EGL14.EGL_NO_SURFACE
        }
        if (context !== EGL14.EGL_NO_CONTEXT) {
            try { EGL14.eglDestroyContext(eglDisplay, context) }
            catch (t: Throwable) { RovaLog.w("EglEncoder[$side] eglDestroyContext", t) }
            context = EGL14.EGL_NO_CONTEXT
        }
    }

    private fun buildProgram(): Int {
        val vs = "attribute vec4 aPosition; attribute vec4 aUv; " +
            "uniform mat4 uTexMatrix; varying vec2 vUv; " +
            "void main(){ gl_Position = aPosition; vUv = (uTexMatrix * aUv).xy; }"
        val fs = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float; varying vec2 vUv; " +
            "uniform samplerExternalOES sTex; " +
            "void main(){ gl_FragColor = texture2D(sTex, vUv); }"
        val v = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val f = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val linkOk = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linkOk, 0)
        require(linkOk[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        // Shaders are reference-counted by the program once linked; flag
        // them for deletion now so they are freed with the program.
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        require(ok[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    companion object {
        private const val FLOATS_PER_VERT = 4 // x, y, u, v
        // Full-screen quad in clip space; interleaved (x, y, u, v).
        // TRIANGLE_STRIP order: BL, BR, TL, TR. Mirrors EglRouter.QUAD_VERTS
        // — duplicated rather than shared so EncoderRenderThread is fully
        // self-contained (the two will never meaningfully diverge).
        private val QUAD_VERTS: FloatArray = floatArrayOf(
            -1f, -1f, 0f, 0f, // bottom-left
             1f, -1f, 1f, 0f, // bottom-right
            -1f,  1f, 0f, 1f, // top-left
             1f,  1f, 1f, 1f, // top-right
        )
    }
}
