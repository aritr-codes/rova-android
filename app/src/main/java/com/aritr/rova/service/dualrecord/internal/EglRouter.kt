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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * Thread-safety — two-tier locking (revised 2026-05-20 for the recording
 * ANR). [targets] is mutated from UI/main ([addTarget]/[removeTarget],
 * TextureView attach/detach) and iterated by [renderFrame] on the GL/
 * frame-callback thread.
 *  - The `targets` list lock guards only list structure: add, remove,
 *    and [renderFrame]'s one-shot snapshot. It is held *briefly* — never
 *    across a GL draw or `eglSwapBuffers`.
 *  - Each [RenderTarget] is its own monitor (`synchronized(target)`).
 *    [renderFrame] draws a target under its monitor; [removeTarget]/
 *    [release] flip `RenderTarget.alive` false and `eglDestroySurface`
 *    under the same monitor. So a draw never races a destroy (no native
 *    segfault), and a stalled `eglSwapBuffers` (encoder back-pressure)
 *    holds only that one target's monitor — never the shared list lock,
 *    so the main thread cannot be blocked into an ANR.
 * Earlier the whole draw loop ran inside `synchronized(targets)`; a
 * stalled encoder swap then froze the UI the instant it attached or
 * detached a preview TextureView.
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

    // DualShot render threading (2026-05-21) — one dedicated render
    // thread per encoder side. Encoder targets are driven OFF this
    // callback thread, so `targets` now holds only preview targets (and
    // the inert legacy side=null output). A blocking eglSwapBuffers on a
    // MediaCodec input surface therefore can no longer stall the pump.
    // Guarded by its own monitor — never nested with the `targets` lock.
    private val encoderThreads = mutableMapOf<VideoSide, EncoderRenderThread>()
    private val encoderLock = Any()

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

    // Diagnostic — per-frame render timing on the callback thread.
    // Accumulated over PERF_WINDOW frames, then one summary line is
    // logged and the accumulators reset (~1 log / 2 s at 30 fps).
    // Post-threading (2026-05-21): encoder eglSwapBuffers runs off-thread
    // (EncoderRenderThread), so the callback-thread cost is updateTex +
    // preview draw/swap + the encoder barrier wait. `barrier` is the
    // headline number — how long this thread blocked on the encoders'
    // draw+glFinish.
    private var perfFrames = 0
    private var perfLastEntryNs = 0L
    private var perfIntervalSumNs = 0L
    private var perfIntervalMaxNs = 0L
    private var perfTexSumNs = 0L
    private var perfTexMaxNs = 0L
    private var perfTotalSumNs = 0L
    private var perfTotalMaxNs = 0L
    private var perfDrawMaxNs = 0L
    private var perfBarrierSumNs = 0L
    private var perfBarrierMaxNs = 0L
    private var perfPrevSwapMaxNs = 0L

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
    // ConcurrentHashMap (not a plain map): renderFrame writes it under a
    // per-target monitor while removeTarget/debugSnapshot touch it from
    // other paths — the map's own thread-safety avoids a cross-lock race
    // without risking a lock-ordering deadlock against `targets`.
    private val debugInfoBySide =
        java.util.concurrent.ConcurrentHashMap<com.aritr.rova.service.dualrecord.VideoSide, DualShotMatrixDebugInfo>()

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
    ) {
        // Lifecycle guard for the per-target render lock (2026-05-20 ANR
        // fix). removeTarget / release set this false — under
        // synchronized(this) — immediately before eglDestroySurface;
        // renderFrame skips a target whose `alive` is false. @Volatile so
        // the renderFrame-thread read is current; the per-target monitor
        // still orders draw-vs-destroy. A body property, not a
        // constructor one → excluded from data-class equals/hashCode/copy.
        @Volatile
        var alive: Boolean = true
    }

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
        // Build the per-side UV transform + aspect-fit viewport. Used by
        // both encoder render threads and inline preview targets.
        val crop = FloatArray(16)
        val viewport: IntArray
        if (side != null) {
            if (useFirstPrinciplesRender) {
                // V2 first-principles path — canonical UV transform.
                AspectFitMath.buildUvTransformV2(
                    displayRotation, sensorOrientation, side,
                    crop, scratchA, scratchB, scratchC, scratchD,
                )
            } else {
                // Legacy path — buildCropMatrix with empirical sideCorrection.
                @Suppress("DEPRECATION")
                AspectFitMath.buildCropMatrix(displayRotation, sensorOrientation, side, crop)
            }
            val contentAspect = when (side) {
                VideoSide.PORTRAIT -> 9f / 16f
                VideoSide.LANDSCAPE -> 16f / 9f
            }
            viewport = AspectFitMath.computeFitViewport(width, height, contentAspect)
        } else {
            // Legacy CameraEffect Preview output (side=null) — inert.
            Matrix.setIdentityM(crop, 0)
            viewport = intArrayOf(0, 0, width, height)
        }

        // DualShot render threading — an encoder target gets its own
        // render thread, not a slot in `targets`. The thread creates its
        // EGL window surface over `surface` and its shared-group context
        // ON ITS OWN THREAD (an EGLContext is thread-affine once current).
        if (kind == TargetKind.ENCODER && side != null) {
            val thread = EncoderRenderThread(
                side = side,
                encoderSurface = surface,
                eglDisplay = eglDisplay!!,
                eglConfig = eglConfig!!,
                sharedContext = eglContext!!,
                inputTextureId = inputTextureId,
                uvTransform = crop,
                surfaceWidth = width,
                surfaceHeight = height,
                viewportX = viewport[0],
                viewportY = viewport[1],
                viewportW = viewport[2],
                viewportH = viewport[3],
            )
            // Defensive — replace a stale thread for the same side (e.g.
            // a surface reconnect). Remove it from the map under the lock,
            // but shutdown+join it OUTSIDE the lock: a bounded join (up to
            // JOIN_TIMEOUT_MS) must not be held against `encoderLock`,
            // which the camera callback thread also acquires in
            // renderFrame. While the side is briefly absent from the map
            // the callback simply skips that encoder for a frame or two —
            // correct graceful degradation.
            val stale = synchronized(encoderLock) { encoderThreads.remove(side) }
            stale?.shutdown()
            stale?.join(JOIN_TIMEOUT_MS)
            synchronized(encoderLock) { encoderThreads[side] = thread }
            thread.start()
            return
        }

        // Preview target (or the legacy side=null output) — drawn inline
        // on the callback thread, so its EGL window surface is created
        // here and stored in `targets`.
        val winAttribs = intArrayOf(EGL14.EGL_NONE)
        val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay!!, eglConfig, surface, winAttribs, 0)
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
        // DualShot render threading — an encoder target is a thread, not
        // a `targets` entry. Poison it and join (bounded) so its EGL
        // teardown runs on its own thread before this returns.
        if (kind == TargetKind.ENCODER && side != null) {
            val thread = synchronized(encoderLock) { encoderThreads.remove(side) } ?: return
            thread.shutdown()
            thread.join(JOIN_TIMEOUT_MS)
            if (thread.isAlive) {
                RovaLog.w("EglRouter.removeTarget: ${thread.name} did not exit in ${JOIN_TIMEOUT_MS}ms")
            }
            return
        }
        // Drop from the list under the (fast) list lock, THEN destroy the
        // EGL surface under the target's own monitor. renderFrame draws
        // each target under synchronized(target) as well, so the destroy
        // waits out at most one in-flight draw of THIS target — never the
        // whole frame, and never behind a stalled swap on another target.
        // 2026-05-20 ANR fix.
        val target = synchronized(targets) {
            val t = targets.firstOrNull { it.side == side && it.kind == kind } ?: return
            targets.remove(t)
            t
        }
        if (side != null) debugInfoBySide.remove(side)
        synchronized(target) {
            target.alive = false
            try { EGL14.eglDestroySurface(eglDisplay!!, target.eglSurface) }
            catch (e: Throwable) { RovaLog.w("EglRouter.removeTarget eglDestroySurface", e) }
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
        // Diagnostic — frame entry timestamp (see perf* fields).
        val perfEntryNs = System.nanoTime()
        // Snapshot the target list under the list lock, then draw OUTSIDE
        // it. renderFrame must NOT hold `targets` across the blocking
        // eglSwapBuffers calls: a stalled encoder swap (back-pressure)
        // would hold the lock for seconds and ANR the main thread the
        // instant it calls addTarget/removeTarget. Per-target mutual
        // exclusion below (synchronized(target) + RenderTarget.alive)
        // still prevents removeTarget from destroying an EGL surface
        // mid-draw. 2026-05-20 ANR fix — see the EglRouter class KDoc.
        val snapshot = synchronized(targets) { ArrayList(targets) }
        // EGL context MUST be current before SurfaceTexture.updateTexImage
        // (the call samples the OES texture binding on the current
        // context). Use the always-valid 1x1 pbuffer, NOT a target
        // surface — a target may be concurrently destroyed by removeTarget.
        if (pbufferSurface === EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(eglDisplay!!, pbufferSurface, pbufferSurface, eglContext)
        tex.updateTexImage()
        tex.getTransformMatrix(texMatrix)
        val perfTexEndNs = System.nanoTime()

        // DualShot render threading (2026-05-21) — fan this frame out to
        // the encoder threads BEFORE drawing previews, so the two encoder
        // draws run concurrently with the preview draws. Each encoder
        // counts down `barrier` after its glFinish (camera-texture
        // sampling complete) and BEFORE its blocking eglSwapBuffers;
        // awaiting `barrier` below therefore costs only draw+glFinish,
        // never a stalled swap. See the 2026-05-21 render-threading
        // design doc §5.
        val liveEncoders = synchronized(encoderLock) {
            encoderThreads.values.filter { !it.failed }
        }
        val barrier = CountDownLatch(liveEncoders.size)
        if (liveEncoders.isNotEmpty()) {
            // One read-only copy of this frame's transform, shared across
            // encoders — they only sample it, never mutate.
            val frame = EncoderFrame(texMatrix.copyOf(), barrier)
            liveEncoders.forEach { it.submit(frame) }
        }

        // Diagnostic — per-frame maxima across this frame's preview targets.
        var frameDrawMaxNs = 0L
        var framePrevSwapMaxNs = 0L

        for (target in snapshot) {
            // Phase 6.1c — legacy CameraEffect Preview output (side=null,
            // kind=PREVIEW) is inert: covered by DualPreviewZone visually,
            // no draw needed.
            if (target.side == null && target.kind == TargetKind.PREVIEW) continue
            // Per-target monitor: removeTarget/release set `alive=false`
            // and call eglDestroySurface under synchronized(target), so a
            // draw never races a destroy. A blocked swap holds only THIS
            // target's monitor — never the shared `targets` lock.
            synchronized(target) {
                if (!target.alive) return@synchronized

                val perfDrawStartNs = System.nanoTime()
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
                // overhead). debugInfoBySide is a ConcurrentHashMap, so this
                // write is thread-safe outside the `targets` lock.
                // Per-side map cap at 2; writes overwrite.
                if (enableMatrixSnapshots && target.side != null) {
                    val sideAspectCropMatrix = FloatArray(16)
                    AspectFitMath.buildSideAspectCrop(target.side, sensorOrientation, sideAspectCropMatrix)
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

                // Diagnostic — split per-target GL draw time from
                // eglSwapBuffers. A swap on a MediaCodec input surface
                // blocks under encoder back-pressure; a swap on a preview
                // surface blocks on vsync. Splitting them tells us which.
                val perfSwapStartNs = System.nanoTime()
                EGL14.eglSwapBuffers(eglDisplay!!, target.eglSurface)
                val perfTargetEndNs = System.nanoTime()
                val drawNs = perfSwapStartNs - perfDrawStartNs
                val swapNs = perfTargetEndNs - perfSwapStartNs
                if (drawNs > frameDrawMaxNs) frameDrawMaxNs = drawNs
                // `targets` now holds only preview targets — encoder swaps
                // run off-thread in EncoderRenderThread.
                if (swapNs > framePrevSwapMaxNs) framePrevSwapMaxNs = swapNs
            }
        }
        // Strict per-frame barrier (design §5). Do not return — and
        // therefore do not allow the next updateTexImage — until every
        // encoder has finished SAMPLING the shared camera texture, so a
        // frame still in use is never overwritten. The bounded timeout is
        // a wedge detector only; a legitimate draw+glFinish never
        // approaches it. On timeout the pump proceeds degraded rather
        // than ANR (design §7).
        val perfBarrierStartNs = System.nanoTime()
        if (liveEncoders.isNotEmpty()) {
            val ok = barrier.await(BARRIER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!ok) {
                RovaLog.w("EglRouter: render barrier timed out (${BARRIER_TIMEOUT_MS}ms) — encoder wedged")
            }
        }
        val perfBarrierNs = System.nanoTime() - perfBarrierStartNs

        recordRenderPerf(perfEntryNs, perfTexEndNs, frameDrawMaxNs, perfBarrierNs, framePrevSwapMaxNs)
    }

    /**
     * Diagnostic — accumulate one frame's render timings; every
     * [PERF_WINDOW] frames emit a summary line and reset. Pure
     * instrumentation: no behaviour change. Called once per
     * [renderFrame], on the frame-callback thread (single-threaded —
     * the `perf*` fields need no synchronisation).
     */
    private fun recordRenderPerf(
        entryNs: Long,
        texEndNs: Long,
        drawMaxNs: Long,
        barrierNs: Long,
        prevSwapMaxNs: Long,
    ) {
        val nowNs = System.nanoTime()
        val intervalNs = if (perfLastEntryNs != 0L) entryNs - perfLastEntryNs else 0L
        perfLastEntryNs = entryNs
        val texNs = texEndNs - entryNs
        val totalNs = nowNs - entryNs
        if (intervalNs > perfIntervalMaxNs) perfIntervalMaxNs = intervalNs
        perfIntervalSumNs += intervalNs
        if (texNs > perfTexMaxNs) perfTexMaxNs = texNs
        perfTexSumNs += texNs
        if (totalNs > perfTotalMaxNs) perfTotalMaxNs = totalNs
        perfTotalSumNs += totalNs
        if (drawMaxNs > perfDrawMaxNs) perfDrawMaxNs = drawMaxNs
        perfBarrierSumNs += barrierNs
        if (barrierNs > perfBarrierMaxNs) perfBarrierMaxNs = barrierNs
        if (prevSwapMaxNs > perfPrevSwapMaxNs) perfPrevSwapMaxNs = prevSwapMaxNs
        if (++perfFrames < PERF_WINDOW) return
        fun ms(ns: Long): String = String.format(java.util.Locale.US, "%.1f", ns / 1_000_000.0)
        RovaLog.d(
            "EglRouter perf [${perfFrames}f]: " +
                "interval avg=${ms(perfIntervalSumNs / perfFrames)} max=${ms(perfIntervalMaxNs)} | " +
                "updateTex avg=${ms(perfTexSumNs / perfFrames)} max=${ms(perfTexMaxNs)} | " +
                "renderTotal avg=${ms(perfTotalSumNs / perfFrames)} max=${ms(perfTotalMaxNs)} | " +
                "drawMax=${ms(perfDrawMaxNs)} " +
                "barrier avg=${ms(perfBarrierSumNs / perfFrames)} max=${ms(perfBarrierMaxNs)} " +
                "prevSwapMax=${ms(perfPrevSwapMaxNs)} | " +
                "encoders=${encoderThreads.size} targets=${targets.size} (ms)"
        )
        perfFrames = 0
        perfIntervalSumNs = 0L; perfIntervalMaxNs = 0L
        perfTexSumNs = 0L; perfTexMaxNs = 0L
        perfTotalSumNs = 0L; perfTotalMaxNs = 0L
        perfDrawMaxNs = 0L; perfBarrierSumNs = 0L; perfBarrierMaxNs = 0L; perfPrevSwapMaxNs = 0L
    }

    fun release() {
        // DualShot render threading — stop the encoder threads first.
        // Each tears down its own EGL context + window surface ON ITS OWN
        // thread; join (bounded) before destroying the root context they
        // share from.
        val encoders = synchronized(encoderLock) {
            val copy = encoderThreads.values.toList()
            encoderThreads.clear()
            copy
        }
        encoders.forEach { it.shutdown() }
        encoders.forEach { t ->
            t.join(JOIN_TIMEOUT_MS)
            if (t.isAlive) RovaLog.w("EglRouter.release: ${t.name} did not exit in ${JOIN_TIMEOUT_MS}ms")
        }
        // Snapshot + clear the list under the list lock, then destroy each
        // surface under its own monitor — same draw/destroy ordering as
        // removeTarget (2026-05-20 ANR fix).
        val snapshot = synchronized(targets) {
            val copy = ArrayList(targets)
            targets.clear()
            copy
        }
        debugInfoBySide.clear()
        snapshot.forEach { t ->
            synchronized(t) {
                t.alive = false
                try { EGL14.eglDestroySurface(eglDisplay!!, t.eglSurface) }
                catch (e: Throwable) { RovaLog.w("EglRouter eglDestroySurface", e) }
            }
        }
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
        // Diagnostic — render-perf summary cadence (frames per log line).
        private const val PERF_WINDOW = 60
        // DualShot render threading — per-frame barrier wedge-detector
        // timeout. A legitimate encoder draw+glFinish is ~10-14ms; this
        // only fires if an encoder thread is wedged in a driver call.
        private const val BARRIER_TIMEOUT_MS = 100L
        // Bounded join for encoder-thread shutdown (removeTarget/release).
        private const val JOIN_TIMEOUT_MS = 500L
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
