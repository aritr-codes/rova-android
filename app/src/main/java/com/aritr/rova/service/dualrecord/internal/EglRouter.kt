package com.aritr.rova.service.dualrecord.internal

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.aritr.rova.service.dualrecord.LensFacing
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.utils.RovaLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Phase 6.1a — EGL14 context + GLES20 shader + per-frame fan-out to 3
 * targets:
 *  - PreviewView's expected output Surface (CameraEffect.PREVIEW target).
 *  - Portrait encoder's input Surface (stretch-to-fit, no per-side rotation).
 *  - Landscape encoder's input Surface (+90° UV rotation, stretch-to-fit).
 *
 * Pinned-rotation strategy — UPDATED at Phase 6.1b smoke-fix #4: the GL
 * shader still does not rotate by itself, but the per-target `uTexMatrix`
 * now encodes the per-target orientation (landscape gets +90° in UV
 * space) so the landscape encoder's bitstream contains landscape-
 * oriented pixels, not portrait pixels stretched into a landscape-aspect
 * frame. The muxer's `setOrientationHint(displayRotation)` is the sole
 * metadata source (MP4 ignores track-level `KEY_ROTATION` per AOSP
 * `MediaMuxer.addTrack` docs — so it's also dropped from the encoder
 * format). Both sides get the same `setOrientationHint` value because
 * the pixels are already pre-rotated per side. Players still display
 * with the correct orientation; the change is which side does the
 * rotation (now the encoder pre-rotates, not the player post-rotates).
 * Spec §9's "no double-rotation" intent is preserved — the muxer hint
 * and the rendered pixels agree.
 *
 * Front-camera mirror: applied in the vertex transform for the PREVIEW
 * render only (uv.x → 1.0 - uv.x). Encoder renders are NOT mirrored
 * (recorded files play with correct orientation on share).
 *
 * Phase 6.1b smoke-fix — completed the runtime GL pump that 6.1a left
 * stubbed (see KDoc "filled at 6.1b binding time" hand-off in the original
 * `renderFrame()`):
 *  - Full-screen quad vertex/uv interleaved buffer + glDrawArrays.
 *  - Cached attribute + uniform locations after glLinkProgram.
 *  - Per-target glViewport sized from RenderTarget.width/height.
 *  - uTexMatrix bound per target = texMatrix × mirrorMatrix.
 *  - eglMakeCurrent BEFORE SurfaceTexture.updateTexImage (the OES bind
 *    needs the context current).
 *  - SurfaceTexture.setDefaultBufferSize() now wired via
 *    [setInputBufferSize] — caller (DualSurfaceProcessor.onInputSurface)
 *    feeds CameraX's SurfaceRequest.resolution through.
 *
 * Runtime layer — no unit tests.
 */
internal class EglRouter(private val lensFacing: LensFacing) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var inputTextureId: Int = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var program: Int = 0
    private val targets = mutableListOf<RenderTarget>()
    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val finalMatrix = FloatArray(16)
    // Temp buffer for the 2-step matrix multiply:
    //   tmpMatrix   = texMatrix × mirrorMatrix
    //   finalMatrix = cropMatrix × tmpMatrix
    // Reused per-target to avoid per-frame allocation.
    private val tmpMatrix = FloatArray(16)

    private var aPositionLoc: Int = -1
    private var aUvLoc: Int = -1
    private var uTexMatrixLoc: Int = -1
    private var uTextureLoc: Int = -1

    // Full-screen quad in clip space; interleaved (x, y, u, v) per vertex.
    // TRIANGLE_STRIP order: BL, BR, TL, TR.
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_VERTS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply { put(QUAD_VERTS).position(0) }

    private data class RenderTarget(
        val side: VideoSide?,            // null = PREVIEW
        val surface: Surface,
        val eglSurface: EGLSurface,
        val width: Int,
        val height: Int,
        val cropMatrix: FloatArray,
        val mirrorMatrix: FloatArray,
    )

    val inputSurface: Surface
        get() = Surface(inputSurfaceTexture ?: error("EglRouter not initialised"))

    /**
     * Set the input SurfaceTexture's default buffer size so CameraX's
     * producer dequeues correctly-sized frames. MUST be called once,
     * before the first frame arrives, from
     * `DualSurfaceProcessor.onInputSurface` using
     * `SurfaceRequest.resolution`. Without this, the SurfaceTexture's
     * buffer dimensions default to display density — the camera may
     * starve frames or render at the wrong size (Bug 2 of the 6.1b
     * smoke-fix).
     */
    fun setInputBufferSize(width: Int, height: Int) {
        inputSurfaceTexture?.setDefaultBufferSize(width, height)
    }

    // EGL_RECORDABLE_ANDROID = 0x3142 is annotated @RequiresApi(26) in the
    // current stub, but the constant value and the EGL_ANDROID_recordable
    // extension have been stable since AOSP 4.3 (API 18) — well below
    // minSdk=24. The inlined int is safe at runtime on every supported
    // device; this is the canonical workaround.
    @SuppressLint("InlinedApi")
    fun setup() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        require(eglDisplay !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val version = IntArray(2)
        require(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        require(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, configs.size, numConfigs, 0)) {
            "eglChooseConfig failed"
        }
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        require(eglContext !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        // PBuffer surface kept so we can make-current even when no output
        // is attached yet (e.g. updateTexImage before the first target is
        // added — also used as the fallback current-surface in renderFrame
        // if no targets exist yet).
        val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttribs, 0)
        require(EGL14.eglMakeCurrent(eglDisplay, pbufferSurface, pbufferSurface, eglContext)) {
            "eglMakeCurrent failed"
        }

        program = buildProgram()
        // Cache attribute + uniform locations once (after glLinkProgram).
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aUvLoc = GLES20.glGetAttribLocation(program, "aUv")
        uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uTextureLoc = GLES20.glGetUniformLocation(program, "sTex")

        inputTextureId = createOesTexture()
        inputSurfaceTexture = SurfaceTexture(inputTextureId)
        Matrix.setIdentityM(mvpMatrix, 0)
    }

    fun setOnFrameAvailableListener(listener: SurfaceTexture.OnFrameAvailableListener) {
        inputSurfaceTexture?.setOnFrameAvailableListener(listener)
    }

    /**
     * Register an output Surface as a render target. `width`/`height` are
     * the intrinsic pixel dimensions of [surface]:
     *  - PREVIEW target → `SurfaceOutput.size`.
     *  - Encoder target → the configured encoder output `Size`.
     * These drive the per-target `glViewport` so each target receives a
     * full-frame draw at its intrinsic aspect.
     *
     * Phase 6.1b smoke-fix #4 — per-side UV rotation. The single
     * SurfaceTexture's transform matrix (from `getTransformMatrix`) is
     * computed for the consumer's orientation, which CameraX resolves to
     * portrait (PreviewView is portrait). Sampling that transform on the
     * landscape encoder surface stretched portrait-rotated content into
     * a landscape-aspect frame; the player then either rotated it (when
     * the encoder's `KEY_ROTATION` was honored at bitstream level — e.g.
     * Qualcomm encoders) or didn't (when it was honored as
     * metadata-only) — either way the user saw "stretched portrait."
     *
     * Fix: for [VideoSide.LANDSCAPE] targets, prepend an in-UV-space
     * rotation by +90° around the (0.5, 0.5) center, built with the
     * standard Grafika translate→rotate→translate-back pattern. This
     * rotates the sampled OES region 90° in the UV plane, so the
     * landscape encoder reads landscape-oriented pixels from the same
     * OES texture without changing what portrait or preview see.
     *
     * Sign caveat (research): GL's right-hand rule with CCW-positive
     * rotation about +Z combined with CameraX's CCW-into-portrait
     * SurfaceTexture matrix means +90° is the expected "un-rotate to
     * landscape" direction here. If on-device verification shows the
     * landscape video upside-down (i.e., a 180° flip from intent), the
     * fix is a single sign change in [LANDSCAPE_ROTATE_DEG] — try -90f.
     *
     * Aspect-fit crop (sampling the 16:9 sub-region of a 9:16 source
     * instead of stretching) is orthogonal to orientation and remains
     * the parked "crop accuracy" follow-up. Both portrait and landscape
     * sides currently stretch — orientation is the higher-impact half.
     */
    fun addTarget(side: VideoSide?, surface: Surface, width: Int, height: Int) {
        val winAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, winAttribs, 0)
        val crop = FloatArray(16).also {
            Matrix.setIdentityM(it, 0)
            if (side == VideoSide.LANDSCAPE) {
                // Pivot to UV center → rotate about +Z → pivot back. The
                // 3 calls compose right-to-left when applied to a vector,
                // matching the natural reading order of the operation.
                Matrix.translateM(it, 0, 0.5f, 0.5f, 0f)
                Matrix.rotateM(it, 0, LANDSCAPE_ROTATE_DEG, 0f, 0f, 1f)
                Matrix.translateM(it, 0, -0.5f, -0.5f, 0f)
            }
        }
        val mirror = FloatArray(16).also {
            Matrix.setIdentityM(it, 0)
            if (side == null && lensFacing == LensFacing.FRONT) Matrix.scaleM(it, 0, -1f, 1f, 1f)
        }
        targets.add(RenderTarget(side, surface, eglSurface, width, height, crop, mirror))
    }

    fun renderFrame() {
        val tex = inputSurfaceTexture ?: return
        // EGL context MUST be current before SurfaceTexture.updateTexImage:
        // the call samples the OES texture binding, which lives on the
        // current EGL context. Use the first target if available, else the
        // 1x1 PBuffer from setup() so the call still has a valid current
        // surface (Bug 3 of the 6.1b smoke-fix).
        val anchor = if (targets.isNotEmpty()) targets[0].eglSurface else pbufferSurface
        if (anchor === EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(eglDisplay, anchor, anchor, eglContext)
        tex.updateTexImage()
        tex.getTransformMatrix(texMatrix)

        targets.forEach { target ->
            EGL14.eglMakeCurrent(eglDisplay, target.eglSurface, target.eglSurface, eglContext)
            GLES20.glViewport(0, 0, target.width, target.height)
            GLES20.glUseProgram(program)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
            GLES20.glUniform1i(uTextureLoc, 0)

            // uTexMatrix = cropMatrix × texMatrix × mirrorMatrix.
            //   - mirrorMatrix: identity (encoder targets) or H-flip (FRONT
            //     preview only — recorded files are NEVER mirrored so share
            //     plays right-side-up on other apps).
            //   - texMatrix: SurfaceTexture's per-frame consumer-orientation
            //     transform (CameraX-resolved to portrait).
            //   - cropMatrix: identity for PORTRAIT + PREVIEW; +90° UV
            //     rotation around (0.5, 0.5) for LANDSCAPE so the landscape
            //     encoder samples landscape-oriented pixels from the same
            //     OES texture. See [addTarget] KDoc for the rationale.
            //
            // Composition is right-to-left when applied to `aUv`: mirror
            // → tex → crop. Done as two multiplies via `tmpMatrix` because
            // `Matrix.multiplyMM` doesn't support in-place destination.
            Matrix.multiplyMM(tmpMatrix, 0, texMatrix, 0, target.mirrorMatrix, 0)
            Matrix.multiplyMM(finalMatrix, 0, target.cropMatrix, 0, tmpMatrix, 0)
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, finalMatrix, 0)

            // Interleaved (x, y, u, v) → bind aPosition at offset 0,
            // aUv at offset 2 floats, stride = 4 floats = 16 bytes.
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
            EGL14.eglSwapBuffers(eglDisplay, target.eglSurface)
        }
    }

    fun release() {
        targets.forEach { t ->
            try { EGL14.eglDestroySurface(eglDisplay, t.eglSurface) }
            catch (e: Throwable) { RovaLog.w("EglRouter eglDestroySurface", e) }
        }
        targets.clear()
        try { inputSurfaceTexture?.release() } catch (e: Throwable) { RovaLog.w("EglRouter SurfaceTexture", e) }
        try { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        catch (e: Throwable) { RovaLog.w("EglRouter eglMakeCurrent NO", e) }
        if (pbufferSurface !== EGL14.EGL_NO_SURFACE) {
            try { EGL14.eglDestroySurface(eglDisplay, pbufferSurface) }
            catch (e: Throwable) { RovaLog.w("EglRouter pbuffer", e) }
            pbufferSurface = EGL14.EGL_NO_SURFACE
        }
        try { EGL14.eglDestroyContext(eglDisplay, eglContext) } catch (e: Throwable) { RovaLog.w("EglRouter context", e) }
        try { EGL14.eglTerminate(eglDisplay) } catch (e: Throwable) { RovaLog.w("EglRouter terminate", e) }
    }

    private fun createOesTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
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
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        return p
    }

    private fun compileShader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        require(ok[0] == GLES20.GL_TRUE) { "shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    companion object {
        private const val FLOATS_PER_VERT = 4 // x, y, u, v
        // Full-screen quad in clip space + matching UV [0,1] range; the
        // texMatrix from SurfaceTexture remaps these UVs to sample the OES
        // texture correctly. TRIANGLE_STRIP order: BL, BR, TL, TR.
        private val QUAD_VERTS: FloatArray = floatArrayOf(
            -1f, -1f, 0f, 0f, // bottom-left
             1f, -1f, 1f, 0f, // bottom-right
            -1f,  1f, 0f, 1f, // top-left
             1f,  1f, 1f, 1f, // top-right
        )
        // Phase 6.1b smoke-fix #4 — degree value for the per-side UV
        // rotation that turns the landscape encoder's sample area from
        // portrait-rotated content back to landscape-oriented content.
        // GL right-hand rule + CameraX CCW-into-portrait combine to make
        // +90° the expected "un-rotate" direction; flip to -90f if
        // on-device verification shows landscape inverted. See
        // [addTarget] KDoc for the full derivation.
        private const val LANDSCAPE_ROTATE_DEG = 90f
    }
}
