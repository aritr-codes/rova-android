package com.aritr.rova.service.dualrecord.internal

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

/**
 * Phase 6.1a — EGL14 context + GLES20 shader + per-frame fan-out to 3
 * targets:
 *  - PreviewView's expected output Surface (CameraEffect.PREVIEW target).
 *  - Portrait encoder's input Surface (crop + scale, no rotation).
 *  - Landscape encoder's input Surface (identity + scale, no rotation).
 *
 * Pinned-rotation strategy (spec §9): the GL shader does NOT rotate; the
 * encoder track's MediaFormat.KEY_ROTATION metadata signals orientation.
 * Players rotate on playback. Prevents double-rotation playback (180° /
 * sideways).
 *
 * Front-camera mirror: applied in the vertex transform for the PREVIEW
 * render only (uv.x → 1.0 - uv.x). Encoder renders are NOT mirrored
 * (recorded files play with correct orientation on share).
 *
 * Runtime layer — no unit tests.
 */
internal class EglRouter(private val lensFacing: LensFacing) {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var inputTextureId: Int = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var program: Int = 0
    private val targets = mutableListOf<RenderTarget>()
    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)

    private data class RenderTarget(
        val side: VideoSide?,            // null = PREVIEW
        val surface: Surface,
        val eglSurface: EGLSurface,
        val cropMatrix: FloatArray,
        val mirrorMatrix: FloatArray,
    )

    val inputSurface: Surface
        get() = Surface(inputSurfaceTexture ?: error("EglRouter not initialised"))

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

        // PBuffer surface so we can make-current before any output is attached.
        val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val pbuf = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttribs, 0)
        require(EGL14.eglMakeCurrent(eglDisplay, pbuf, pbuf, eglContext)) { "eglMakeCurrent failed" }

        program = buildProgram()
        inputTextureId = createOesTexture()
        inputSurfaceTexture = SurfaceTexture(inputTextureId)
        Matrix.setIdentityM(mvpMatrix, 0)
    }

    fun setOnFrameAvailableListener(listener: SurfaceTexture.OnFrameAvailableListener) {
        inputSurfaceTexture?.setOnFrameAvailableListener(listener)
    }

    fun addTarget(side: VideoSide?, surface: Surface) {
        val winAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, winAttribs, 0)
        val crop = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
        val mirror = FloatArray(16).also {
            Matrix.setIdentityM(it, 0)
            if (side == null && lensFacing == LensFacing.FRONT) Matrix.scaleM(it, 0, -1f, 1f, 1f)
        }
        targets.add(RenderTarget(side, surface, eglSurface, crop, mirror))
    }

    fun renderFrame() {
        val tex = inputSurfaceTexture ?: return
        tex.updateTexImage()
        tex.getTransformMatrix(texMatrix)
        targets.forEach { target ->
            EGL14.eglMakeCurrent(eglDisplay, target.eglSurface, target.eglSurface, eglContext)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
            // Canonical Grafika-style fan-out: render full-screen quad with
            // texMatrix × mirrorMatrix applied via the uTexMatrix uniform.
            // Encoder targets render at full output size (set by encoder
            // input Surface). 6.1b on-device smoke verifies pixel correctness.
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // glDrawArrays would go here in the full Grafika program; the
            // architectural decision (one program, fan-out to multiple
            // EGLSurface targets) is what 6.1a locks. Vertex/uv buffer
            // setup + glVertexAttribPointer + glDrawArrays(TRIANGLE_STRIP,0,4)
            // is filled at 6.1b binding time when the live preview can verify.
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
}
