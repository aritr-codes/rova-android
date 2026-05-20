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
 *  - Portrait encoder's input Surface.
 *  - Landscape encoder's input Surface.
 *
 * Pinned-rotation strategy — FINAL at Phase 6.1c. The GL shader does
 * not rotate by itself; per-target `cropMatrix` (built once at
 * [addTarget] via [AspectFitMath.buildCropMatrix] from the session-
 * pinned `displayRotation`) carries both the device-orientation
 * correction and the per-side aspect crop. Each encoder file contains
 * upright pixels in its native aspect; the muxer's
 * `setOrientationHint(0)` is the sole rotation metadata.
 *
 * Per-target viewport (set at [addTarget] via
 * [AspectFitMath.computeFitViewport]) lets preview targets letterbox
 * the side's content aspect inside their TextureView surface dims.
 * Encoder targets are aspect-matched by design → viewport == full
 * surface.
 *
 * The legacy CameraEffect Preview output (registered with side=null,
 * kind=PREVIEW) is inert: [renderFrame] skips. Covered visually by the
 * UI's `DualPreviewZone`. Kept registered so the CameraEffect bind
 * path is unchanged.
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
 *
 * Phase 6.1c thread-safety: [targets] is mutated from multiple threads
 * — [addTarget]/[removeTarget] called from UI/main (TextureView attach/
 * detach), [renderFrame] called from the GL/frame-callback thread. All
 * three serialize on `synchronized(targets) { ... }`. Holding the lock
 * during [renderFrame]'s draw loop ensures that an EGL surface being
 * destroyed by [removeTarget] is not concurrently used by a GL draw
 * (prevents native segfault). Lock is uncontended in steady state —
 * attach/detach are bind-time/teardown events, not per-frame.
 */
/**
 * Phase 6.1c — render target classification. Lives next to EglRouter
 * because both [DualSurfaceProcessor] and EglRouter consume it.
 *
 *  - [ENCODER]: MediaCodec input surface; recording output.
 *  - [PREVIEW]: visible UI surface (TextureView in P+L mode); or the
 *    legacy CameraEffect Preview output (registered with side=null,
 *    skipped in renderFrame).
 */
internal enum class TargetKind { ENCODER, PREVIEW }

internal class EglRouter(
    private val lensFacing: LensFacing,
    private val displayRotation: Int,
    private val sensorOrientation: Int = 90,
    private val useFirstPrinciplesRender: Boolean = false,
    private val enableMatrixSnapshots: Boolean = false,
) {

    init {
        require(displayRotation in 0..3) {
            "displayRotation must be Surface.ROTATION_0..ROTATION_270 (0..3), was $displayRotation"
        }
        require(sensorOrientation in setOf(0, 90, 180, 270)) {
            "sensorOrientation must be 0/90/180/270 " +
                "(CameraCharacteristics.SENSOR_ORIENTATION), was $sensorOrientation"
        }
    }

    // JVM-stub workaround: EGL14.EGL_NO_DISPLAY/CONTEXT/SURFACE return null
    // under isReturnDefaultValues=true, causing NPE on non-nullable field init.
    // Nullable types match the Java @Nullable annotation on the EGL sentinels;
    // runtime behaviour is unchanged (setup() assigns real values before use).
    // Per 6.1a Size-stub workaround precedent.
    private var eglDisplay: EGLDisplay? = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext? = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbufferSurface: EGLSurface? = EGL14.EGL_NO_SURFACE
    private var inputTextureId: Int = 0
    private var inputSurfaceTexture: SurfaceTexture? = null
    private var program: Int = 0
    private val targets = mutableListOf<RenderTarget>()
    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val finalMatrix = FloatArray(16)
    // Temp buffer for the 2-step matrix multiply:
    //   tmpMatrix   = texMatrix × mirrorMatrix
    //   finalMatrix = uvTransform × tmpMatrix
    // Reused per-target to avoid per-frame allocation.
    private val tmpMatrix = FloatArray(16)

    // Diagnostic — sides whose GL matrices have already been logged. The
    // recorded DualShot files show an aspect deformation that cannot come
    // from buildSideAspectCrop alone (both sides share the same stretch
    // formula), so the per-side texMatrix / uvTransform / finalMatrix must
    // be dumped to diagnose objectively rather than by eyeballing frames.
    // renderFrame logs each side once on its first drawn frame.
    private val diagLoggedSides = mutableSetOf<VideoSide>()

    // Phase: render-architecture audit. Caller-owned scratch buffers for
    // buildUvTransformV2. 4 pairwise-distinct length-16 arrays, allocated
    // once at ctor, reused for every addTarget call. Per spec §2.2 + the
    // multiplyMat4 no-alias contract (spec §2.4).
    private val scratchA = FloatArray(16)
    private val scratchB = FloatArray(16)
    private val scratchC = FloatArray(16)
    private val scratchD = FloatArray(16)

    // Phase: render-architecture audit. Session-pinned component matrices
    // for DualShotMatrixDebugInfo snapshots. Built once in setup() — texture
    // normalization + display rotation depend only on ctor-pinned params, so
    // they're cached. Per-side sideAspectCrop is read from target.uvTransform's
    // composition at snapshot time (or recomputed; cheap).
    private val cachedNormalizationMatrix = FloatArray(16)
    private val cachedDisplayRotationMatrix = FloatArray(16)

    // Phase: render-architecture audit. Per-side debug snapshot map.
    // Written by renderFrame when enableMatrixSnapshots=true (Task 13).
    // Read by debugSnapshot() under synchronized(targets). Size capped
    // at 2 (PORTRAIT + LANDSCAPE) — writes overwrite. Cleared in
    // release() and on removeTarget().
    private val debugInfoBySide =
        mutableMapOf<com.aritr.rova.service.dualrecord.VideoSide, DualShotMatrixDebugInfo>()

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
        val side: VideoSide?,            // null = legacy CameraEffect Preview output (no-draw)
        val kind: TargetKind,
        val surface: Surface,
        val eglSurface: EGLSurface,
        val width: Int,
        val height: Int,
        // RENAMED from cropMatrix per spec §2.5. Carries the full uvTransform
        // composition (legacy: sideAspectCrop × displayRotationCorrection ×
        // sideCorrection; V2: sideAspectCrop × displayRotationCorrection ×
        // textureNormalization). Naming it cropMatrix invited regression.
        val uvTransform: FloatArray,
        val mirrorMatrix: FloatArray,
        // Phase 6.1c — per-target aspect-fit viewport. Encoder targets
        // get viewport == full surface; preview targets letterbox the
        // side's content aspect inside the TextureView surface dims.
        // Mutable `var` because the TextureView SurfaceTextureListener
        // re-registers on size-changed → addTarget recomputes; no
        // per-frame mutation.
        var viewportX: Int,
        var viewportY: Int,
        var viewportW: Int,
        var viewportH: Int,
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
        require(EGL14.eglInitialize(eglDisplay!!, version, 0, version, 1)) { "eglInitialize failed" }

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        require(EGL14.eglChooseConfig(eglDisplay!!, attribs, 0, configs, 0, configs.size, numConfigs, 0)) {
            "eglChooseConfig failed"
        }
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay!!, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        require(eglContext !== EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        // PBuffer surface kept so we can make-current even when no output
        // is attached yet (e.g. updateTexImage before the first target is
        // added — also used as the fallback current-surface in renderFrame
        // if no targets exist yet).
        val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay!!, eglConfig, pbAttribs, 0)
        require(EGL14.eglMakeCurrent(eglDisplay!!, pbufferSurface, pbufferSurface, eglContext)) {
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

        // Phase: render-architecture audit. Populate session-pinned debug
        // snapshot caches. Cheap one-time cost; only meaningful when
        // enableMatrixSnapshots is on.
        AspectFitMath.buildTextureNormalization(sensorOrientation, cachedNormalizationMatrix)
        AspectFitMath.buildDisplayRotationCorrection(displayRotation, cachedDisplayRotationMatrix)
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
     * Phase 6.1c — `cropMatrix` is built once here via
     * [AspectFitMath.buildCropMatrix] from the session-pinned
     * `displayRotation` + per-side aspect. The viewport is computed via
     * [AspectFitMath.computeFitViewport]: encoder targets are
     * aspect-matched by design → full surface; preview TextureView
     * targets letterbox the side's content aspect inside their surface
     * dims. Neither is recomputed per frame (the previous smoke-fix #5
     * per-frame `computeCropMatrix` call has been removed).
     *
     * For the legacy CameraEffect Preview target (`side == null`),
     * `cropMatrix` is identity and the viewport is the full surface —
     * both placeholders, since [renderFrame] skips this target.
     */
    fun addTarget(side: VideoSide?, kind: TargetKind, surface: Surface, width: Int, height: Int) {
        val winAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay!!, eglConfig, surface, winAttribs, 0)
        val crop = FloatArray(16)
        val viewport: IntArray
        if (side != null) {
            // Phase 6.1c — real target. Build cropMatrix once from the
            // session-pinned displayRotation; viewport aspect-fits the
            // side's content aspect inside the surface dims (encoder
            // surfaces are aspect-matched → full viewport; preview
            // TextureView surfaces letterbox).
            if (useFirstPrinciplesRender) {
                // V2 first-principles path — canonical UV transform from
                // sideAspectCrop × displayRotationCorrection × textureNormalization.
                // Active only when SharedPreferences pref.dev.useFirstPrinciplesRender
                // is true AND BuildConfig.DEBUG (release short-circuits to false).
                AspectFitMath.buildUvTransformV2(
                    displayRotation, sensorOrientation, side,
                    crop, scratchA, scratchB, scratchC, scratchD,
                )
            } else {
                // Legacy path — buildCropMatrix with empirical +270° sideCorrection.
                // Default for all callers. Bridge-tested against V2 at
                // sensorOrientation=270 (see AspectFitMathBridgeTest).
                @Suppress("DEPRECATION")
                AspectFitMath.buildCropMatrix(displayRotation, side, crop)
            }
            val contentAspect = when (side) {
                VideoSide.PORTRAIT -> 9f / 16f
                VideoSide.LANDSCAPE -> 16f / 9f
            }
            viewport = AspectFitMath.computeFitViewport(width, height, contentAspect)
        } else {
            // Legacy CameraEffect Preview output (side=null, kind=PREVIEW).
            // Inert in 6.1c — renderFrame skips. cropMatrix + viewport are
            // placeholders.
            Matrix.setIdentityM(crop, 0)
            viewport = intArrayOf(0, 0, width, height)
        }
        val mirror = FloatArray(16).also {
            Matrix.setIdentityM(it, 0)
            if (side == null && lensFacing == LensFacing.FRONT) {
                Matrix.scaleM(it, 0, -1f, 1f, 1f)
            }
        }
        synchronized(targets) {
            targets.add(
                RenderTarget(
                    side, kind, surface, eglSurface, width, height,
                    uvTransform = crop, mirrorMatrix = mirror,
                    viewportX = viewport[0], viewportY = viewport[1],
                    viewportW = viewport[2], viewportH = viewport[3],
                )
            )
        }
    }

    /**
     * Phase 6.1c — un-register a previously-added target by (side, kind).
     * Destroys its EGL window surface and drops it from the targets
     * list. Idempotent — missing target is a no-op. Called when a
     * DualPreviewZone TextureView detaches (e.g. user navigates away
     * from RecordScreen).
     */
    fun removeTarget(side: VideoSide?, kind: TargetKind) {
        synchronized(targets) {
            val target = targets.firstOrNull { it.side == side && it.kind == kind } ?: return
            try { EGL14.eglDestroySurface(eglDisplay!!, target.eglSurface) }
            catch (e: Throwable) { RovaLog.w("EglRouter.removeTarget eglDestroySurface", e) }
            targets.remove(target)
            if (side != null) debugInfoBySide.remove(side)
        }
    }

    /**
     * Phase: render-architecture audit. Returns the most recent
     * DualShotMatrixDebugInfo for [side], or null if none has been written
     * (e.g. enableMatrixSnapshots=false, or removeTarget cleared it, or
     * renderFrame hasn't fired yet).
     *
     * Returns a defensive `.copy()` — caller's mutations don't reach the
     * router's internal map. Read under the same lock as the writer
     * (synchronized(targets) in renderFrame).
     *
     * Consumed by sub-project 2's debug overlay. Inert in this PR (no
     * caller).
     */
    fun debugSnapshot(side: VideoSide): DualShotMatrixDebugInfo? {
        synchronized(targets) {
            return debugInfoBySide[side]?.copy()
        }
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
        EGL14.eglMakeCurrent(eglDisplay!!, anchor, anchor, eglContext)
        tex.updateTexImage()
        tex.getTransformMatrix(texMatrix)

        synchronized(targets) {
            targets.forEach { target ->
                // Phase 6.1c — legacy CameraEffect Preview output (side=null,
                // kind=PREVIEW) is inert: covered by DualPreviewZone visually,
                // no draw needed.
                if (target.side == null && target.kind == TargetKind.PREVIEW) return@forEach

                EGL14.eglMakeCurrent(eglDisplay!!, target.eglSurface, target.eglSurface, eglContext)
                // 1. Clear full surface with black — paints letterbox/pillar bars
                //    for preview targets that letterbox; no visible effect on
                //    encoder targets (full-viewport draw covers it).
                GLES20.glViewport(0, 0, target.width, target.height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                // 2. Restrict viewport to the aspect-fit region and draw.
                GLES20.glViewport(target.viewportX, target.viewportY, target.viewportW, target.viewportH)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
                GLES20.glUniform1i(uTextureLoc, 0)

                // uTexMatrix = uvTransform × texMatrix × mirrorMatrix.
                // uvTransform is pinned at addTarget time (session-start); no
                // per-frame recompute.
                Matrix.multiplyMM(tmpMatrix, 0, texMatrix, 0, target.mirrorMatrix, 0)
                Matrix.multiplyMM(finalMatrix, 0, target.uvTransform, 0, tmpMatrix, 0)
                GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, finalMatrix, 0)

                // Diagnostic — one-time per-side dump of the actual GL
                // matrices. finalMatrix maps encoder UV → OES sampling UV;
                // from it the sampled-region aspect (and therefore the
                // exact stretch) is computed objectively. texMatrix is
                // CameraX's SurfaceTexture transform — the suspected
                // source of the deformation. Logged once per side.
                if (target.side != null && diagLoggedSides.add(target.side)) {
                    RovaLog.d(
                        "EglRouter diag side=${target.side} " +
                            "encoder=${target.width}x${target.height} " +
                            "viewport=[${target.viewportX},${target.viewportY}," +
                            "${target.viewportW},${target.viewportH}] " +
                            "texMatrix=[${texMatrix.joinToString(",")}] " +
                            "uvTransform=[${target.uvTransform.joinToString(",")}] " +
                            "finalMatrix=[${finalMatrix.joinToString(",")}]"
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

                // Phase: render-architecture audit. Per-frame debug snapshot
                // write — gated on enableMatrixSnapshots (default false → zero
                // overhead). Inside the synchronized(targets) block already.
                // Per-side map cap at 2; writes overwrite.
                if (enableMatrixSnapshots && target.side != null) {
                    val sideAspectCropMatrix = FloatArray(16)
                    AspectFitMath.buildSideAspectCrop(target.side, sideAspectCropMatrix)
                    debugInfoBySide[target.side] = DualShotMatrixDebugInfo(
                        side = target.side,
                        sensorOrientation = sensorOrientation,
                        displayRotation = displayRotation,
                        lensFacing = lensFacing,
                        texMatrix = texMatrix.copyOf(),
                        normalizationMatrix = cachedNormalizationMatrix.copyOf(),
                        displayRotationMatrix = cachedDisplayRotationMatrix.copyOf(),
                        sideAspectCropMatrix = sideAspectCropMatrix,
                        uvTransform = target.uvTransform.copyOf(),
                        finalMatrix = finalMatrix.copyOf(),
                        viewport = intArrayOf(
                            target.viewportX, target.viewportY,
                            target.viewportW, target.viewportH,
                        ),
                        encoderSize = target.width to target.height,
                        timestampNs = System.nanoTime(),
                    )
                }

                GLES20.glDisableVertexAttribArray(aPositionLoc)
                GLES20.glDisableVertexAttribArray(aUvLoc)
                EGL14.eglSwapBuffers(eglDisplay!!, target.eglSurface)
            }
        }
    }

    fun release() {
        targets.forEach { t ->
            try { EGL14.eglDestroySurface(eglDisplay!!, t.eglSurface) }
            catch (e: Throwable) { RovaLog.w("EglRouter eglDestroySurface", e) }
        }
        targets.clear()
        debugInfoBySide.clear()
        try { inputSurfaceTexture?.release() } catch (e: Throwable) { RovaLog.w("EglRouter SurfaceTexture", e) }
        try { EGL14.eglMakeCurrent(eglDisplay!!, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        catch (e: Throwable) { RovaLog.w("EglRouter eglMakeCurrent NO", e) }
        if (pbufferSurface !== EGL14.EGL_NO_SURFACE) {
            try { EGL14.eglDestroySurface(eglDisplay!!, pbufferSurface) }
            catch (e: Throwable) { RovaLog.w("EglRouter pbuffer", e) }
            pbufferSurface = EGL14.EGL_NO_SURFACE
        }
        try { EGL14.eglDestroyContext(eglDisplay!!, eglContext) } catch (e: Throwable) { RovaLog.w("EglRouter context", e) }
        try { EGL14.eglTerminate(eglDisplay!!) } catch (e: Throwable) { RovaLog.w("EglRouter terminate", e) }
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
    }
}
