# DualShot Render Threading Redesign — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `EglRouter`'s single-threaded serial render pump with a 3-thread model so DualShot recording stops stuttering — callback thread + one dedicated render thread per encoder, joined by a per-frame barrier that decouples the pump from blocking `eglSwapBuffers`.

**Architecture:** Each encoder target gets an `EncoderRenderThread` owning its own `EGLContext` (in the router's share group) and window `EGLSurface`. The callback thread `updateTexImage`s, fans the frame out to both encoder threads, draws previews inline, then awaits a `CountDownLatch` barrier. Each encoder counts the barrier down right after `glFinish` (camera-texture sampling done) and *before* its blocking `eglSwapBuffers` — so the pump waits only for the predictable draw+glFinish, never for a stalled swap. Strategy B1 from the design doc (direct `SurfaceTexture` sampling, zero extra memory).

**Tech Stack:** Kotlin, Android `EGL14`/`GLES20`, `java.util.concurrent` (`CountDownLatch`, `Thread`), JUnit4 (JVM unit tests for the pure `FrameMailbox` helper only).

**Design doc:** `docs/superpowers/specs/2026-05-21-dualshot-render-threading-design.md`

**Branch:** create `feat/dualshot-render-threading` off `master` before Task 1. The design doc + this plan ride along (cherry-pick or move from the current `diag/` branch).

**Invariants — must stay byte-identical to master:** `AspectFitMath.kt`, `RotationCalculator.kt`, `DualMuxerStateMachine.kt`, `DualVideoRecorder.kt`, the single-mode camera pipeline, `setupSingleCamera`, `WarningId`/`WarningPrecedence`. The `git diff` allowlist for this PR is exactly: `FrameMailbox.kt`, `FrameMailboxTest.kt`, `EncoderRenderThread.kt`, `EglRouter.kt`, plus the spec + this plan.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/aritr/rova/service/dualrecord/internal/FrameMailbox.kt` | **New.** Pure-JVM single-slot latest-wins rendezvous + poison-pill. The one unit-testable piece of the threading core. |
| `app/src/test/java/com/aritr/rova/service/dualrecord/internal/FrameMailboxTest.kt` | **New.** JVM tests for `FrameMailbox` — latest-wins, poison, blocking `take`. |
| `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt` | **New.** Per-encoder render thread + the `EncoderFrame` payload. Owns a shared-group `EGLContext`, window `EGLSurface`, GL program. Runtime layer — no unit tests. |
| `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt` | **Modify.** Encoder targets become threads, not `targets` entries. `renderFrame` fans out + awaits the barrier. `release`/`removeTarget` gain the thread shutdown handshake. |

---

## Task 1: `FrameMailbox` — pure-JVM rendezvous (TDD)

**Files:**
- Create: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/FrameMailboxTest.kt`
- Create: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/FrameMailbox.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/aritr/rova/service/dualrecord/internal/FrameMailboxTest.kt`:

```kotlin
package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JVM unit tests for [FrameMailbox]. This is the only unit-tested piece
 * of the DualShot render-threading core — the EGL/GL threads around it
 * are the runtime layer (no unit tests). Covers latest-wins overwrite,
 * the poison-pill shutdown signal, and the blocking [FrameMailbox.take].
 */
class FrameMailboxTest {

    @Test
    fun offer_thenTake_returnsTheItem() {
        val mb = FrameMailbox<String>()
        mb.offer("a")
        assertEquals("a", mb.take())
    }

    @Test
    fun offerTwice_take_returnsLatestOnly() {
        val mb = FrameMailbox<String>()
        mb.offer("stale")
        mb.offer("fresh")
        assertEquals("fresh", mb.take())
    }

    @Test
    fun poison_thenTake_returnsNull() {
        val mb = FrameMailbox<String>()
        mb.poison()
        assertNull(mb.take())
    }

    @Test
    fun poison_winsOverAPendingSlot() {
        val mb = FrameMailbox<String>()
        mb.offer("pending")
        mb.poison()
        assertNull(mb.take())
    }

    @Test
    fun offer_afterPoison_isNoOp() {
        val mb = FrameMailbox<String>()
        mb.poison()
        mb.offer("ignored")
        assertNull(mb.take())
    }

    @Test(timeout = 2000)
    fun take_blocksUntilOffer() {
        val mb = FrameMailbox<String>()
        val started = CountDownLatch(1)
        val result = arrayOfNulls<String>(1)
        val consumer = Thread {
            started.countDown()
            result[0] = mb.take()
        }
        consumer.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        Thread.sleep(50)            // consumer is now parked inside take()
        mb.offer("delivered")
        consumer.join(1000)
        assertEquals("delivered", result[0])
    }

    @Test(timeout = 2000)
    fun take_unblocksOnPoison() {
        val mb = FrameMailbox<String>()
        val result = arrayOfNulls<String>(1)
        val done = CountDownLatch(1)
        val consumer = Thread {
            result[0] = mb.take()
            done.countDown()
        }
        consumer.start()
        Thread.sleep(50)
        mb.poison()
        assertTrue(done.await(1, TimeUnit.SECONDS))
        assertNull(result[0])
    }
}
```

- [ ] **Step 2: Run the test, verify it fails**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.FrameMailboxTest"`
Expected: FAIL — compilation error, `FrameMailbox` is unresolved.

- [ ] **Step 3: Write `FrameMailbox`**

Create `app/src/main/java/com/aritr/rova/service/dualrecord/internal/FrameMailbox.kt`:

```kotlin
package com.aritr.rova.service.dualrecord.internal

/**
 * DualShot render threading (2026-05-21) — a single-slot, latest-wins
 * rendezvous between the [EglRouter] callback thread (producer) and one
 * [EncoderRenderThread] (consumer).
 *
 *  - [offer] overwrites the slot. An unread frame is discarded so the
 *    consumer always draws the newest camera frame — this is the
 *    per-side frame-drop, achieved with no explicit drop logic.
 *  - [poison] is the shutdown signal. Once poisoned, [take] returns null
 *    forever and [offer] is a no-op. Poison wins over a pending slot.
 *  - [take] blocks until an item is offered or the mailbox is poisoned.
 *
 * Pure JVM — no Android types — so it is unit-tested directly
 * (FrameMailboxTest), unlike the EGL/GL threads that use it. See the
 * 2026-05-21 render-threading design doc §6.
 */
internal class FrameMailbox<T : Any> {

    private val lock = java.lang.Object()
    private var slot: T? = null
    private var poisoned = false

    /** Overwrite the slot with [item] and wake a waiting [take]. No-op once poisoned. */
    fun offer(item: T) {
        synchronized(lock) {
            if (poisoned) return
            slot = item
            lock.notifyAll()
        }
    }

    /** Shutdown signal — wakes a waiting [take], which then returns null. */
    fun poison() {
        synchronized(lock) {
            poisoned = true
            lock.notifyAll()
        }
    }

    /**
     * Block until an item is offered or the mailbox is poisoned. Returns
     * the latest item, or null iff poisoned (poison wins over a pending
     * slot, so a poisoned mailbox never delivers a stale frame).
     */
    fun take(): T? {
        synchronized(lock) {
            while (slot == null && !poisoned) lock.wait()
            if (poisoned) return null
            val item = slot
            slot = null
            return item
        }
    }
}
```

- [ ] **Step 4: Run the test, verify it passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.FrameMailboxTest"`
Expected: PASS — 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/FrameMailbox.kt app/src/test/java/com/aritr/rova/service/dualrecord/internal/FrameMailboxTest.kt
git commit -m "feat(dualrecord): FrameMailbox — latest-wins frame rendezvous (#2)"
```

---

## Task 2: `EncoderRenderThread` — per-encoder render thread

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt`

No unit test — runtime EGL/GL layer (`android.opengl.*` are JVM no-ops). Verified by the on-device smoke in Task 5.

- [ ] **Step 1: Write `EncoderRenderThread.kt` (with the `EncoderFrame` payload)**

Create `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt`:

```kotlin
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
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            } catch (t: Throwable) {
                RovaLog.w("EglEncoder[$side] eglSwapBuffers failed", t)
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
```

- [ ] **Step 2: Compile-gate**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`EglRouter` does not yet reference these classes — it still compiles unchanged.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt
git commit -m "feat(dualrecord): EncoderRenderThread — per-encoder GL render thread (#2)"
```

---

## Task 3: `EglRouter` — encoder targets become threads

Encoder targets stop being `targets`-list entries; `addTarget`/`removeTarget` route them to `EncoderRenderThread`s.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt`

- [ ] **Step 1: Add imports**

Find:

```kotlin
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
```

Replace with:

```kotlin
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
```

- [ ] **Step 2: Add the `encoderThreads` field**

Find:

```kotlin
    private val targets = mutableListOf<RenderTarget>()
    private val mvpMatrix = FloatArray(16)
```

Replace with:

```kotlin
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
```

- [ ] **Step 3: Replace `addTarget`**

Find the entire `fun addTarget(...)` function body (from `fun addTarget(side: VideoSide?, kind: TargetKind, surface: Surface, width: Int, height: Int) {` through its closing brace) and replace it with:

```kotlin
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
            synchronized(encoderLock) {
                // Defensive — replace a stale thread for the same side.
                encoderThreads.remove(side)?.let { old ->
                    old.shutdown()
                    old.join(JOIN_TIMEOUT_MS)
                }
                encoderThreads[side] = thread
            }
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
```

- [ ] **Step 4: Add the encoder branch to `removeTarget`**

Find:

```kotlin
    fun removeTarget(side: VideoSide?, kind: TargetKind) {
        // Drop from the list under the (fast) list lock, THEN destroy the
```

Replace with:

```kotlin
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
```

- [ ] **Step 5: Add the companion constants**

Find:

```kotlin
        private const val FLOATS_PER_VERT = 4 // x, y, u, v
        // Diagnostic — render-perf summary cadence (frames per log line).
        private const val PERF_WINDOW = 60
```

Replace with:

```kotlin
        private const val FLOATS_PER_VERT = 4 // x, y, u, v
        // Diagnostic — render-perf summary cadence (frames per log line).
        private const val PERF_WINDOW = 60
        // DualShot render threading — per-frame barrier wedge-detector
        // timeout. A legitimate encoder draw+glFinish is ~10-14ms; this
        // only fires if an encoder thread is wedged in a driver call.
        private const val BARRIER_TIMEOUT_MS = 100L
        // Bounded join for encoder-thread shutdown (removeTarget/release).
        private const val JOIN_TIMEOUT_MS = 500L
```

- [ ] **Step 6: Compile-gate**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. `renderFrame` still references `frameEncSwapMaxNs` — that is fixed in Task 4; at this point `renderFrame` is unchanged and still compiles.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt
git commit -m "feat(dualrecord): EglRouter routes encoder targets to render threads (#2)"
```

---

## Task 4: `EglRouter` — per-frame barrier, perf, teardown

`renderFrame` fans the frame out and awaits the barrier; `recordRenderPerf` reports barrier wait instead of encoder swap; `release` joins the encoder threads first.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt`

- [ ] **Step 1: Update the perf-fields comment + fields**

Find:

```kotlin
    // Diagnostic — per-frame render timing, to locate the on-device
    // DualShot recording stutter (2026-05-20). Accumulated over
    // PERF_WINDOW frames, then one summary line is logged and the
    // accumulators reset (~1 log / 2 s at 30 fps → negligible overhead).
    // The per-target swap times are split ENCODER vs PREVIEW because the
    // prime suspect is encoder-input-surface back-pressure: eglSwapBuffers
    // on a MediaCodec input surface blocks when the encoder is not
    // draining fast enough, stalling the whole single-threaded pump.
```

Replace with:

```kotlin
    // Diagnostic — per-frame render timing on the callback thread.
    // Accumulated over PERF_WINDOW frames, then one summary line is
    // logged and the accumulators reset (~1 log / 2 s at 30 fps).
    // Post-threading (2026-05-21): encoder eglSwapBuffers runs off-thread
    // (EncoderRenderThread), so the callback-thread cost is updateTex +
    // preview draw/swap + the encoder barrier wait. `barrier` is the
    // headline number — how long this thread blocked on the encoders'
    // draw+glFinish.
```

Find:

```kotlin
    private var perfDrawMaxNs = 0L
    private var perfEncSwapMaxNs = 0L
    private var perfPrevSwapMaxNs = 0L
```

Replace with:

```kotlin
    private var perfDrawMaxNs = 0L
    private var perfBarrierSumNs = 0L
    private var perfBarrierMaxNs = 0L
    private var perfPrevSwapMaxNs = 0L
```

- [ ] **Step 2: Fan out to the encoder threads in `renderFrame`**

Find:

```kotlin
        tex.updateTexImage()
        tex.getTransformMatrix(texMatrix)
        val perfTexEndNs = System.nanoTime()

        // Diagnostic — per-frame maxima across this frame's targets.
        var frameDrawMaxNs = 0L
        var frameEncSwapMaxNs = 0L
        var framePrevSwapMaxNs = 0L
```

Replace with:

```kotlin
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
```

- [ ] **Step 3: Drop the encoder/preview swap split**

Find:

```kotlin
                val drawNs = perfSwapStartNs - perfDrawStartNs
                val swapNs = perfTargetEndNs - perfSwapStartNs
                if (drawNs > frameDrawMaxNs) frameDrawMaxNs = drawNs
                if (target.kind == TargetKind.ENCODER) {
                    if (swapNs > frameEncSwapMaxNs) frameEncSwapMaxNs = swapNs
                } else {
                    if (swapNs > framePrevSwapMaxNs) framePrevSwapMaxNs = swapNs
                }
```

Replace with:

```kotlin
                val drawNs = perfSwapStartNs - perfDrawStartNs
                val swapNs = perfTargetEndNs - perfSwapStartNs
                if (drawNs > frameDrawMaxNs) frameDrawMaxNs = drawNs
                // `targets` now holds only preview targets — encoder swaps
                // run off-thread in EncoderRenderThread.
                if (swapNs > framePrevSwapMaxNs) framePrevSwapMaxNs = swapNs
```

- [ ] **Step 4: Await the barrier, update the `recordRenderPerf` call**

Find:

```kotlin
        recordRenderPerf(perfEntryNs, perfTexEndNs, frameDrawMaxNs, frameEncSwapMaxNs, framePrevSwapMaxNs)
    }
```

Replace with:

```kotlin
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
```

- [ ] **Step 5: Update `recordRenderPerf`**

Find:

```kotlin
    private fun recordRenderPerf(
        entryNs: Long,
        texEndNs: Long,
        drawMaxNs: Long,
        encSwapMaxNs: Long,
        prevSwapMaxNs: Long,
    ) {
```

Replace with:

```kotlin
    private fun recordRenderPerf(
        entryNs: Long,
        texEndNs: Long,
        drawMaxNs: Long,
        barrierNs: Long,
        prevSwapMaxNs: Long,
    ) {
```

Find:

```kotlin
        if (drawMaxNs > perfDrawMaxNs) perfDrawMaxNs = drawMaxNs
        if (encSwapMaxNs > perfEncSwapMaxNs) perfEncSwapMaxNs = encSwapMaxNs
        if (prevSwapMaxNs > perfPrevSwapMaxNs) perfPrevSwapMaxNs = prevSwapMaxNs
```

Replace with:

```kotlin
        if (drawMaxNs > perfDrawMaxNs) perfDrawMaxNs = drawMaxNs
        perfBarrierSumNs += barrierNs
        if (barrierNs > perfBarrierMaxNs) perfBarrierMaxNs = barrierNs
        if (prevSwapMaxNs > perfPrevSwapMaxNs) perfPrevSwapMaxNs = prevSwapMaxNs
```

Find:

```kotlin
                "drawMax=${ms(perfDrawMaxNs)} encSwapMax=${ms(perfEncSwapMaxNs)} " +
                "prevSwapMax=${ms(perfPrevSwapMaxNs)} | targets=${targets.size} (ms)"
```

Replace with:

```kotlin
                "drawMax=${ms(perfDrawMaxNs)} " +
                "barrier avg=${ms(perfBarrierSumNs / perfFrames)} max=${ms(perfBarrierMaxNs)} " +
                "prevSwapMax=${ms(perfPrevSwapMaxNs)} | " +
                "encoders=${encoderThreads.size} targets=${targets.size} (ms)"
```

Find:

```kotlin
        perfDrawMaxNs = 0L; perfEncSwapMaxNs = 0L; perfPrevSwapMaxNs = 0L
```

Replace with:

```kotlin
        perfDrawMaxNs = 0L; perfBarrierSumNs = 0L; perfBarrierMaxNs = 0L; perfPrevSwapMaxNs = 0L
```

- [ ] **Step 6: Join the encoder threads first in `release`**

Find:

```kotlin
    fun release() {
        // Snapshot + clear the list under the list lock, then destroy each
```

Replace with:

```kotlin
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
```

- [ ] **Step 7: Compile-gate**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL — no unresolved `frameEncSwapMaxNs` / `perfEncSwapMaxNs`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt
git commit -m "feat(dualrecord): EglRouter per-frame render barrier — #2 stutter fix"
```

---

## Task 5: Gate + on-device smoke + PR

**Files:** none — verification only.

- [ ] **Step 1: Run the full gate**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
Expected:
- `testDebugUnitTest`: PASS. New baseline = master baseline + 7 (`FrameMailboxTest`), +1 test class. No other suite changes.
- `lintDebug`: 53 findings unchanged (50 W + 3 H + 0 E). `FrameMailbox` / `EncoderRenderThread` introduce no SDK-gated constants; `EGL_RECORDABLE_ANDROID` is not used in the new code (encoder window surfaces inherit the router's already-`@SuppressLint`-ed config).
- `assembleDebug`: BUILD SUCCESSFUL.

If the test count or lint differs from the prediction, stop and investigate before the PR.

- [ ] **Step 2: Verify the invariant allowlist**

Run: `git diff master --name-only`
Expected — exactly these files:
```
app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt
app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt
app/src/main/java/com/aritr/rova/service/dualrecord/internal/FrameMailbox.kt
app/src/test/java/com/aritr/rova/service/dualrecord/internal/FrameMailboxTest.kt
docs/superpowers/specs/2026-05-21-dualshot-render-threading-design.md
docs/superpowers/plans/2026-05-21-dualshot-render-threading.md
```
Any other file → an invariant was disturbed; stop and revert it.

- [ ] **Step 3: Build the smoke APK**

Run: `.\gradlew.bat :app:assembleDebug`
The APK is at `app/build/outputs/apk/debug/app-debug.apk`. Hand it to the owner for SM-A176B smoke.

- [ ] **Step 4: On-device smoke checklist (owner, SM-A176B)**

Runtime EGL/GL layer — these cannot be unit-tested; this checklist is the verification gate.
1. Cold-launch the app in **P+L** mode → both preview panes show live camera (no black pane, no stretch).
2. Record a DualShot clip ≥ 30 s → playback of both the portrait and landscape files is smooth, no stutter, no aspect deformation.
3. While recording, capture logcat tag `Rova` → `EglRouter perf` lines show `renderTotal avg` ≤ ~16 ms and no sustained `barrier max` near 100 ms; `EglEncoder[PORTRAIT]` / `EglEncoder[LANDSCAPE]` first-frame lines appear once each.
4. Stop recording, navigate away from RecordScreen and back, record again → no crash, no ANR, encoder threads re-created cleanly.
5. Switch P+L → Portrait → P+L a few times → no leaked threads (logcat shows matching shutdown), no black preview.

- [ ] **Step 5: Open the PR**

```bash
git push -u origin feat/dualshot-render-threading
gh pr create --base master --title "fix(dualrecord): DualShot render threading — #2 recording stutter" --body "<summary + the design-doc link + the on-device smoke result>"
```
PR merge is owner-only (`gh pr merge` not run by the implementer).

---

## Self-Review

**Spec coverage** — design doc → task:
- §3 thread model → Task 2 (`EncoderRenderThread`) + Task 3 (`EglRouter` routing).
- §4 shared EGL context group → Task 2 `initEgl` (`eglCreateContext` with `sharedContext`, per-thread program, same `eglConfig`).
- §5 per-frame barrier + countDown-before-swap → Task 2 `run` (countDown in `finally`, swap after) + Task 4 Steps 2/4 (`CountDownLatch`, fan-out, `await`).
- §6 mailbox / frame-drop / B1 → Task 1 (`FrameMailbox`). B2 is explicitly out of scope (escalation only).
- §7 error handling + teardown → Task 2 `failed` flag + `finally` countDown + `teardownEgl`; Task 3/4 `JOIN_TIMEOUT_MS` joins; Task 4 Step 6 `release` ordering; `BARRIER_TIMEOUT_MS` wedge timeout.
- §8 verification → Task 1 (`FrameMailboxTest`) + Task 5 (gate, allowlist, on-device smoke).

**Placeholder scan:** none — every step has complete code or an exact command.

**Type consistency:** `EncoderFrame(texMatrix, barrier)`, `EncoderRenderThread.submit`/`shutdown`/`failed`, `FrameMailbox.offer`/`poison`/`take`, `encoderThreads`/`encoderLock`, `BARRIER_TIMEOUT_MS`/`JOIN_TIMEOUT_MS`, `recordRenderPerf(..., barrierNs, ...)` — all defined and used identically across tasks. The front-camera mirror stays `side == null`-gated (unchanged from master). Encoder targets are intentionally never mirrored — `EncoderRenderThread.drawFrame` omits `mirrorMatrix`, matching master where encoder `RenderTarget`s always got an identity mirror.

**Deliberate scope note:** the inert `enableMatrixSnapshots` debug-snapshot block in `renderFrame` is left verbatim — it now writes only for preview targets (encoder draws moved off-thread). The feature has no caller (`debugSnapshot` is consumed by an unbuilt sub-project), so this is acceptable; restoring encoder-side snapshots, if ever needed, is a trivial follow-up.
