# DualShot FBO-Ring (B2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Insert an off-screen FBO snapshot stage between the camera callback thread and the two encoder threads so the callback thread waits for no encoder GPU work — eliminating the residual recording lag the B1 render barrier still imposes.

**Architecture:** The callback thread blits each camera frame into a 3-deep ring of off-screen framebuffers (an *identity* blit — the FBO mirrors the OES texture's `[0,1]` UV space). It hands the slot's texture id + `texMatrix` to both encoder threads via the existing `FrameMailbox`, then returns immediately. Encoders sample their private FBO copies asynchronously; B1's per-frame `CountDownLatch` barrier is deleted entirely. See `docs/superpowers/specs/2026-05-21-dualshot-fbo-ring-design.md`.

**Tech Stack:** Kotlin, Android, EGL14 / GLES20, CameraX 1.4.2 `SurfaceProcessor`. The `service/dualrecord/internal` package.

**Branch:** B2 branches off `master` **after** PR #32 (B1) is merged (owner decision). The spec + this plan are two docs-only commits on the current branch — cherry-pick them onto the B2 branch (same pattern as B1). Do **not** start Task 1 until the owner confirms #32 is merged.

**Test policy:** `EglRouter`, `EncoderRenderThread`, and `FboRing` are the runtime EGL/GL layer — **no unit tests** (`android.opengl.*` are JVM no-ops under `isReturnDefaultValues=true`; the established `dualrecord` policy). B2 adds **no new pure logic**, so it adds no JVM tests. Per-task verification is `:app:assembleDebug` + the invariant git-diff allowlist. `FrameMailbox.kt` / `FrameMailboxTest.kt` are **unchanged** — the mailbox payload type changes but its contract does not.

**Gradle note:** Gradle invocations route through `ctx_execute` subagents (the main controller is blocked from long gradle calls). An implementer subagent runs `:app:assembleDebug` etc. directly.

**Invariant allowlist (every task):** the only files that may change across the whole branch are `EglRouter.kt`, `EncoderRenderThread.kt`, the new `FboRing.kt`, plus the spec + this plan. `FrameMailbox.kt`, `FrameMailboxTest.kt`, `AspectFitMath`, `RotationCalculator`, `DualMuxerStateMachine`, `DualVideoRecorder`, the single-mode camera pipeline, `setupSingleCamera`, `WarningId`, `WarningPrecedence` MUST stay byte-identical to master.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/com/aritr/rova/service/dualrecord/internal/FboRing.kt` | Owns the depth-3 ring of off-screen framebuffers + colour textures; allocates, hands out slots round-robin, releases. | **Create** |
| `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt` | Callback-thread router. Gains: builds/releases the `FboRing`; `renderFrame` blits OES→slot and submits the slot to encoders; barrier deleted. | **Modify** |
| `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt` | Per-encoder render thread + `EncoderFrame`. Samples the FBO `GL_TEXTURE_2D` instead of the OES texture; no barrier countdown. | **Modify** |

Task order: **Task 1** creates `FboRing.kt` standalone. **Task 2** wires the ring's lifecycle (build in `setup()`, release in `release()`) — compiles with the ring still unused by `renderFrame`. **Task 3** is the coupled core — `EncoderRenderThread`'s `EncoderFrame` signature change and `EglRouter`'s `renderFrame` blit must land in **one commit** (neither file compiles against the other half-changed).

---

## Task 1: Create `FboRing.kt`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/FboRing.kt`

No unit test — runtime GL layer (`GLES20.*` are JVM no-ops). Verification is compile-only.

- [ ] **Step 1: Create the file**

Create `app/src/main/java/com/aritr/rova/service/dualrecord/internal/FboRing.kt` with exactly this content:

```kotlin
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
    internal class Slot(val framebufferId: Int, val textureId: Int)

    private val slots = ArrayList<Slot>(RING_DEPTH)
    private var counter = 0

    /**
     * Allocate [RING_DEPTH] framebuffers, each with one
     * [FBO_WIDTH]x[FBO_HEIGHT] RGBA8 `GL_TEXTURE_2D` colour attachment.
     * MUST run on the thread owning the GL context. Returns `false` on
     * any GL failure (incomplete framebuffer) — the caller then leaves
     * the ring unused. Idempotent only in the failure sense: on `false`
     * everything allocated is deleted before returning.
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
        val slot = slots[counter % RING_DEPTH]
        counter++
        return slot
    }

    /** Delete every framebuffer + texture. Runs on the GL-context thread. */
    fun release() {
        if (slots.isEmpty()) return
        val fbos = IntArray(slots.size) { slots[it].framebufferId }
        val texs = IntArray(slots.size) { slots[it].textureId }
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
```

- [ ] **Step 2: Compile**

Run: `:app:assembleDebug` (via a `ctx_execute` subagent).
Expected: BUILD SUCCESSFUL. `FboRing` is not yet referenced — an unused-symbol warning on `advance()` is acceptable (it is wired in Task 3).

- [ ] **Step 3: Verify the invariant allowlist**

Run: `git status --porcelain`
Expected: exactly one new file — `app/src/main/java/com/aritr/rova/service/dualrecord/internal/FboRing.kt`. Nothing else modified.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/FboRing.kt
git commit -m "feat(dualrecord): add FboRing — depth-3 off-screen framebuffer ring (B2)"
```

---

## Task 2: Wire the `FboRing` lifecycle into `EglRouter`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt`

`EglRouter` builds the ring in `setup()` and releases it in `release()`. `renderFrame` does not yet use it — that is Task 3. After this task the ring is allocated but unused by the per-frame path; this compiles cleanly and is correct (the ring just sits idle).

No unit test — runtime GL layer. Verification is compile-only.

- [ ] **Step 1: Add the `fboRing` field**

In `EglRouter.kt`, find the encoder-threads field block:

```kotlin
    private val encoderThreads = mutableMapOf<VideoSide, EncoderRenderThread>()
    private val encoderLock = Any()
```

Add directly below it:

```kotlin

    // DualShot FBO ring (B2, 2026-05-21) — off-screen snapshot buffers the
    // encoder threads sample, so the callback thread never blocks on
    // encoder GPU work. Built in setup(); null if FboRing.init() fails, in
    // which case renderFrame skips the encoder fan-out. Touched only on the
    // callback thread (setup/renderFrame/release all run there).
    private var fboRing: FboRing? = null
```

- [ ] **Step 2: Build the ring at the end of `setup()`**

In `setup()`, find the last two lines of the method:

```kotlin
        AspectFitMath.buildTextureNormalization(sensorOrientation, cachedNormalizationMatrix)
        AspectFitMath.buildDisplayRotationCorrection(displayRotation, cachedDisplayRotationMatrix)
    }
```

Replace with:

```kotlin
        AspectFitMath.buildTextureNormalization(sensorOrientation, cachedNormalizationMatrix)
        AspectFitMath.buildDisplayRotationCorrection(displayRotation, cachedDisplayRotationMatrix)

        // DualShot FBO ring (B2) — allocate the off-screen snapshot
        // buffers. init() needs the GL context current; the pbuffer was
        // made current above. A false return leaves fboRing null and
        // renderFrame simply skips the encoder fan-out (design §4).
        val ring = FboRing()
        if (ring.init()) {
            fboRing = ring
        } else {
            RovaLog.w(
                "EglRouter.setup: FboRing.init failed — " +
                    "dual-record encoders will receive no frames",
            )
        }
    }
```

- [ ] **Step 3: Release the ring in `release()`**

In `release()`, find the encoder-thread shutdown block followed by the targets snapshot:

```kotlin
        encoders.forEach { it.shutdown() }
        encoders.forEach { t ->
            t.join(JOIN_TIMEOUT_MS)
            if (t.isAlive) RovaLog.w("EglRouter.release: ${t.name} did not exit in ${JOIN_TIMEOUT_MS}ms")
        }
        // Snapshot + clear the list under the list lock, then destroy each
```

Insert the FBO-ring teardown between the encoder join and the targets snapshot comment:

```kotlin
        encoders.forEach { it.shutdown() }
        encoders.forEach { t ->
            t.join(JOIN_TIMEOUT_MS)
            if (t.isAlive) RovaLog.w("EglRouter.release: ${t.name} did not exit in ${JOIN_TIMEOUT_MS}ms")
        }
        // DualShot FBO ring (B2) — delete the ring AFTER the encoder
        // threads have joined (an encoder must never sample a deleted
        // texture) and BEFORE the root context is destroyed.
        // glDeleteFramebuffers is a GL call — the root context must be
        // current, so make the pbuffer current first (design §8).
        fboRing?.let { ring ->
            if (pbufferSurface !== EGL14.EGL_NO_SURFACE) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay!!, pbufferSurface, pbufferSurface, eglContext)
                } catch (e: Throwable) {
                    RovaLog.w("EglRouter.release: eglMakeCurrent for FBO teardown", e)
                }
            }
            ring.release()
        }
        fboRing = null
        // Snapshot + clear the list under the list lock, then destroy each
```

- [ ] **Step 4: Compile**

Run: `:app:assembleDebug` (via a `ctx_execute` subagent).
Expected: BUILD SUCCESSFUL. `fboRing` is written (setup) and read (release) — no unused warning.

- [ ] **Step 5: Verify the invariant allowlist**

Run: `git status --porcelain`
Expected: only `EglRouter.kt` modified (plus `FboRing.kt` already committed in Task 1, not listed). Nothing else.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt
git commit -m "feat(dualrecord): build + release the FboRing in EglRouter (B2)"
```

---

## Task 3: FBO sampling in `EncoderRenderThread` + the blit in `EglRouter.renderFrame`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt`
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt`

**COUPLED TASK — one commit.** Changing `EncoderFrame`'s constructor and `EncoderRenderThread`'s constructor breaks `EglRouter` until `EglRouter` is updated to match, and vice versa. Both files must change together; neither compiles in isolation. This is expected — implement all steps, then compile once.

No unit test — runtime GL layer. Verification is compile + full gate + invariant diff.

### Part A — `EncoderRenderThread.kt`

- [ ] **Step 1: Drop the `GLES11Ext` and `CountDownLatch` imports**

In `EncoderRenderThread.kt`, find the import block:

```kotlin
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
```

Replace with (remove the `GLES11Ext` and `CountDownLatch` lines — the encoder now binds `GL_TEXTURE_2D` and there is no barrier):

```kotlin
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.view.Surface
import com.aritr.rova.service.dualrecord.VideoSide
import com.aritr.rova.utils.RovaLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
```

- [ ] **Step 2: Rewrite `EncoderFrame`**

Find the `EncoderFrame` class:

```kotlin
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
```

Replace with:

```kotlin
/**
 * One camera frame handed from the [EglRouter] callback thread to an
 * [EncoderRenderThread].
 *
 *  - [fboTextureId] is the `GL_TEXTURE_2D` id of the FBO ring slot
 *    holding this frame's snapshot. The encoder samples this 2D copy
 *    instead of the shared camera OES texture, so it never races the
 *    callback thread's `updateTexImage`. The ring depth keeps the slot
 *    valid long enough (FBO-ring design §4.2).
 *  - [texMatrix] is the `SurfaceTexture` transform for this frame — a
 *    defensive copy the callback thread will not mutate, shared
 *    read-only across both encoders. The FBO blit is an *identity* copy,
 *    so the encoder still applies the full `uvTransform x texMatrix`
 *    composition (design §6 — matrix multiply does not commute).
 */
internal class EncoderFrame(
    val fboTextureId: Int,
    val texMatrix: FloatArray,
)
```

- [ ] **Step 3: Update the `EncoderRenderThread` class KDoc + drop the `inputTextureId` constructor parameter**

Find the class KDoc + constructor:

```kotlin
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
```

Replace with (KDoc rewritten for B2; `inputTextureId` parameter removed — the encoder now samples the per-frame FBO texture, not the shared OES texture):

```kotlin
/**
 * DualShot FBO-ring rendering (B2, 2026-05-21) — one dedicated render
 * thread per encoder target.
 *
 * Each instance owns its own [EGLContext], created in the [EglRouter]
 * root context's share group so it can sample the FBO ring textures,
 * plus its own window [EGLSurface] over the MediaCodec input [Surface]
 * and its own GL program + vertex buffer. The context never leaves this
 * thread — that is what removes the cross-thread EGL-surface races the
 * pre-threading [EglRouter] had to patch with two-tier locking.
 *
 * Per-frame loop: take the latest [EncoderFrame] from the [FrameMailbox]
 * (latest-wins → stale frames dropped for this side only), draw the
 * cropped quad sampling the frame's FBO `GL_TEXTURE_2D` snapshot,
 * `glFinish`, then `eglSwapBuffers`. There is no barrier — the callback
 * thread blitted the camera frame into an FBO slot and waits for no
 * encoder work, so a stalled `eglSwapBuffers` here freezes only this
 * side. See the 2026-05-21 FBO-ring design doc §3 / §6.
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
    private val uvTransform: FloatArray,
    private val surfaceWidth: Int,
    private val surfaceHeight: Int,
    private val viewportX: Int,
    private val viewportY: Int,
    private val viewportW: Int,
    private val viewportH: Int,
) : Thread("EglEncoder-$side") {
```

- [ ] **Step 4: Rewrite `run()` — drop the barrier countdown**

Find the `run()` method:

```kotlin
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
```

Replace with (no barrier → no `finally`, no `drawOk` flag — a draw failure simply breaks the loop):

```kotlin
    override fun run() {
        if (!initEgl()) {
            failed = true
            teardownEgl()
            return
        }
        while (true) {
            val frame = mailbox.take() ?: break   // null = poisoned → shutdown
            try {
                drawFrame(frame.fboTextureId, frame.texMatrix)
            } catch (t: Throwable) {
                RovaLog.w("EglEncoder[$side] draw failed", t)
                failed = true
                break
            }
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
```

- [ ] **Step 5: Rewrite `drawFrame` — sample the FBO `GL_TEXTURE_2D`**

Find the `drawFrame` method:

```kotlin
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
```

Replace that opening of `drawFrame` with (new signature; bind `GL_TEXTURE_2D`; matrix math is byte-for-byte the same composition):

```kotlin
    private fun drawFrame(fboTextureId: Int, texMatrix: FloatArray) {
        // Clear the full surface black, then draw into the aspect-fit
        // viewport (encoder surfaces are aspect-matched → full viewport,
        // so the clear has no visible effect; kept for parity/safety).
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glViewport(viewportX, viewportY, viewportW, viewportH)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        // Sample the FBO ring slot's 2D snapshot, NOT the camera OES
        // texture. The EglRouter blit is an identity copy, so this is the
        // SAME composition B1 applied to the OES texture (design §6).
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glUniform1i(uTextureLoc, 0)

        // uTexMatrix = uvTransform x texMatrix. Encoder targets are never
        // mirrored — the front-camera mirror applies only to the
        // side=null PREVIEW path in EglRouter.
        Matrix.multiplyMM(finalMatrix, 0, uvTransform, 0, texMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, finalMatrix, 0)
```

The rest of `drawFrame` (the `loggedOnce` log, the vertex-attribute setup, `glDrawArrays`, the `glDisableVertexAttribArray` calls, and the trailing `glFinish()`) is **unchanged**.

- [ ] **Step 6: Change the encoder fragment shader to `sampler2D`**

In `buildProgram()`, find:

```kotlin
        val fs = "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float; varying vec2 vUv; " +
            "uniform samplerExternalOES sTex; " +
            "void main(){ gl_FragColor = texture2D(sTex, vUv); }"
```

Replace with (the encoder now samples a plain 2D texture — drop the OES extension + `samplerExternalOES`):

```kotlin
        val fs = "precision mediump float; varying vec2 vUv; " +
            "uniform sampler2D sTex; " +
            "void main(){ gl_FragColor = texture2D(sTex, vUv); }"
```

### Part B — `EglRouter.kt`

- [ ] **Step 7: Drop the `CountDownLatch` and `TimeUnit` imports**

In `EglRouter.kt`, find:

```kotlin
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
```

Replace with (the barrier is deleted — neither import is used any more):

```kotlin
import java.nio.FloatBuffer
```

- [ ] **Step 8: Add the `identityMatrix` field**

Find the matrix-scratch field block:

```kotlin
    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val finalMatrix = FloatArray(16)
```

Add an identity matrix for the blit (the blit draws the OES frame into the FBO with no transform — see Step 11):

```kotlin
    private val mvpMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val finalMatrix = FloatArray(16)
    // DualShot FBO ring (B2) — the identity uTexMatrix for the OES→FBO
    // blit. The blit must NOT bake texMatrix in: the FBO is an identity
    // copy of the OES [0,1] space and the encoder re-applies the full
    // uvTransform × texMatrix composition (design §6).
    private val identityMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
```

- [ ] **Step 9: Update the perf-field block — `barrier` → `blit`**

Find the perf-accumulator comment + fields:

```kotlin
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
```

Replace with (`barrier` accumulators renamed to `blit`; comment updated for B2):

```kotlin
    // Diagnostic — per-frame render timing on the callback thread.
    // Accumulated over PERF_WINDOW frames, then one summary line is
    // logged and the accumulators reset (~1 log / 2 s at 30 fps).
    // FBO ring (B2, 2026-05-21): the callback thread blits the camera
    // frame into an FBO slot and waits for NO encoder work. The
    // callback-thread cost is updateTex + the OES→FBO blit + preview
    // draw/swap. `blit` is the headline number — how long the OES→FBO
    // snapshot (one quad + glFinish) took.
    private var perfFrames = 0
    private var perfLastEntryNs = 0L
    private var perfIntervalSumNs = 0L
    private var perfIntervalMaxNs = 0L
    private var perfTexSumNs = 0L
    private var perfTexMaxNs = 0L
    private var perfTotalSumNs = 0L
    private var perfTotalMaxNs = 0L
    private var perfDrawMaxNs = 0L
    private var perfBlitSumNs = 0L
    private var perfBlitMaxNs = 0L
    private var perfPrevSwapMaxNs = 0L
```

- [ ] **Step 10: Drop `inputTextureId` from the `EncoderRenderThread` construction in `addTarget`**

In `addTarget`, find the `EncoderRenderThread(...)` construction:

```kotlin
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
```

Replace with (drop the `inputTextureId` argument — the encoder no longer samples the shared OES texture):

```kotlin
            val thread = EncoderRenderThread(
                side = side,
                encoderSurface = surface,
                eglDisplay = eglDisplay!!,
                eglConfig = eglConfig!!,
                sharedContext = eglContext!!,
                uvTransform = crop,
                surfaceWidth = width,
                surfaceHeight = height,
                viewportX = viewport[0],
                viewportY = viewport[1],
                viewportW = viewport[2],
                viewportH = viewport[3],
            )
```

- [ ] **Step 11: Replace the barrier fan-out block in `renderFrame` with the FBO blit**

In `renderFrame`, find the B1 fan-out block:

```kotlin
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
```

Replace with (blit the camera frame into an FBO slot, then submit the slot — timed into `perfBlitNs`):

```kotlin
        // DualShot FBO ring (B2, 2026-05-21) — snapshot this camera frame
        // into an off-screen FBO slot, then hand the slot's texture id to
        // the encoder threads. They sample the FBO copy on their own
        // clocks; the callback thread waits for NO encoder work. See the
        // 2026-05-21 FBO-ring design doc §3 / §6.
        val perfBlitStartNs = System.nanoTime()
        val liveEncoders = synchronized(encoderLock) {
            encoderThreads.values.filter { !it.failed }
        }
        val ring = fboRing
        if (liveEncoders.isNotEmpty() && ring != null) {
            try {
                val slot = ring.advance()
                // Identity blit: full quad, OES source, uTexMatrix =
                // identity. The FBO becomes a faithful 2D mirror of the
                // OES [0,1] UV space; the encoder re-applies texMatrix via
                // its uvTransform composition. The blit must NOT bake
                // texMatrix in — matrix multiply does not commute (§6).
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, slot.framebufferId)
                GLES20.glViewport(0, 0, FboRing.FBO_WIDTH, FboRing.FBO_HEIGHT)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTextureId)
                GLES20.glUniform1i(uTextureLoc, 0)
                GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, identityMatrix, 0)
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
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                // glFinish — the blit pixels must be complete AND visible
                // to the encoder share-group contexts before they sample
                // the slot. Drains only the blit (one quad), ~1-2 ms.
                GLES20.glFinish()
                // One read-only texMatrix copy, shared across encoders —
                // they only sample it, never mutate.
                val frame = EncoderFrame(slot.textureId, texMatrix.copyOf())
                liveEncoders.forEach { it.submit(frame) }
            } catch (t: Throwable) {
                // A blit failure is root-context loss — the router is dead
                // regardless. Log, skip this frame's submit, continue
                // (design §8).
                RovaLog.w("EglRouter: FBO blit failed — encoders skip this frame", t)
            }
        }
        val perfBlitNs = System.nanoTime() - perfBlitStartNs
```

- [ ] **Step 12: Delete the barrier-await block at the end of `renderFrame`**

Find the barrier-await block + the `recordRenderPerf` call at the end of `renderFrame`:

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

Replace with (barrier deleted — `renderFrame` returns straight after the previews; `perfBlitNs` from Step 11 is passed to `recordRenderPerf`):

```kotlin
        // FBO ring (B2) — there is no barrier. The OES→FBO blit (above)
        // already consumed the camera frame on this thread, so the next
        // updateTexImage is free; the encoders run fully async against
        // their FBO copies. renderFrame returns straight after the
        // previews (design §3 / §6).
        recordRenderPerf(perfEntryNs, perfTexEndNs, frameDrawMaxNs, perfBlitNs, framePrevSwapMaxNs)
    }
```

- [ ] **Step 13: Update `recordRenderPerf` — `barrierNs` → `blitNs`**

Find the `recordRenderPerf` signature + KDoc:

```kotlin
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
```

Replace with:

```kotlin
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
        blitNs: Long,
        prevSwapMaxNs: Long,
    ) {
```

- [ ] **Step 14: Update the `recordRenderPerf` body — `barrier` accumulators → `blit`**

In `recordRenderPerf`, find:

```kotlin
        if (drawMaxNs > perfDrawMaxNs) perfDrawMaxNs = drawMaxNs
        perfBarrierSumNs += barrierNs
        if (barrierNs > perfBarrierMaxNs) perfBarrierMaxNs = barrierNs
        if (prevSwapMaxNs > perfPrevSwapMaxNs) perfPrevSwapMaxNs = prevSwapMaxNs
```

Replace with:

```kotlin
        if (drawMaxNs > perfDrawMaxNs) perfDrawMaxNs = drawMaxNs
        perfBlitSumNs += blitNs
        if (blitNs > perfBlitMaxNs) perfBlitMaxNs = blitNs
        if (prevSwapMaxNs > perfPrevSwapMaxNs) perfPrevSwapMaxNs = prevSwapMaxNs
```

- [ ] **Step 15: Update the perf log line + the accumulator reset**

In `recordRenderPerf`, find the log line + reset block:

```kotlin
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
```

Replace with (`barrier` → `blit` in both the log string and the reset):

```kotlin
        RovaLog.d(
            "EglRouter perf [${perfFrames}f]: " +
                "interval avg=${ms(perfIntervalSumNs / perfFrames)} max=${ms(perfIntervalMaxNs)} | " +
                "updateTex avg=${ms(perfTexSumNs / perfFrames)} max=${ms(perfTexMaxNs)} | " +
                "renderTotal avg=${ms(perfTotalSumNs / perfFrames)} max=${ms(perfTotalMaxNs)} | " +
                "drawMax=${ms(perfDrawMaxNs)} " +
                "blit avg=${ms(perfBlitSumNs / perfFrames)} max=${ms(perfBlitMaxNs)} " +
                "prevSwapMax=${ms(perfPrevSwapMaxNs)} | " +
                "encoders=${encoderThreads.size} targets=${targets.size} (ms)"
        )
        perfFrames = 0
        perfIntervalSumNs = 0L; perfIntervalMaxNs = 0L
        perfTexSumNs = 0L; perfTexMaxNs = 0L
        perfTotalSumNs = 0L; perfTotalMaxNs = 0L
        perfDrawMaxNs = 0L; perfBlitSumNs = 0L; perfBlitMaxNs = 0L; perfPrevSwapMaxNs = 0L
```

- [ ] **Step 16: Delete the `BARRIER_TIMEOUT_MS` companion constant**

In the `companion object`, find:

```kotlin
        // Diagnostic — render-perf summary cadence (frames per log line).
        private const val PERF_WINDOW = 60
        // DualShot render threading — per-frame barrier wedge-detector
        // timeout. A legitimate encoder draw+glFinish is ~10-14ms; this
        // only fires if an encoder thread is wedged in a driver call.
        private const val BARRIER_TIMEOUT_MS = 100L
        // Bounded join for encoder-thread shutdown (removeTarget/release).
        private const val JOIN_TIMEOUT_MS = 500L
```

Replace with (drop `BARRIER_TIMEOUT_MS` — there is no barrier; keep `JOIN_TIMEOUT_MS`, still used by `removeTarget`/`release`):

```kotlin
        // Diagnostic — render-perf summary cadence (frames per log line).
        private const val PERF_WINDOW = 60
        // Bounded join for encoder-thread shutdown (removeTarget/release).
        private const val JOIN_TIMEOUT_MS = 500L
```

### Part C — verify

- [ ] **Step 17: Compile**

Run: `:app:assembleDebug` (via a `ctx_execute` subagent).
Expected: BUILD SUCCESSFUL. If it fails, the most likely cause is a missed reference to `barrier`, `BARRIER_TIMEOUT_MS`, `inputTextureId`, `CountDownLatch`, or `TimeUnit` — search both files for each and confirm none remain.

- [ ] **Step 18: Run the full gate**

Run (via a `ctx_execute` subagent): `:app:testDebugUnitTest` and `:app:lintDebug`.
Expected: tests + classes + lint identical to the post-#32 master baseline — B2 adds **no tests** and changes **no lint surface** (no new SDK-gated constants, no new suppressions). `FrameMailboxTest` still passes unchanged.

- [ ] **Step 19: Verify the invariant allowlist**

Run: `git diff --name-only master`
Expected: exactly — `EglRouter.kt`, `EncoderRenderThread.kt`, `FboRing.kt`, `docs/superpowers/specs/2026-05-21-dualshot-fbo-ring-design.md`, `docs/superpowers/plans/2026-05-21-dualshot-fbo-ring.md`. Nothing else. In particular `FrameMailbox.kt`, `FrameMailboxTest.kt`, `AspectFitMath*`, `RotationCalculator*`, `DualMuxerStateMachine*`, `DualVideoRecorder*`, `setupSingleCamera`, `WarningId`, `WarningPrecedence` must NOT appear.

- [ ] **Step 20: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt \
        app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt
git commit -m "feat(dualrecord): FBO-ring encoder sampling — delete the render barrier (B2)

renderFrame blits the camera frame into an FboRing slot and submits the
slot's GL_TEXTURE_2D id to the encoder threads, which sample their FBO
copies async. B1's per-frame CountDownLatch barrier (and its glFinish
drain, measured 19-28ms on SM-A176B) is deleted — the callback thread
waits for no encoder work. The blit is an identity copy so the encoder
keeps B1's exact uvTransform x texMatrix composition."
```

---

## Post-Implementation

After all three tasks: dispatch a final whole-branch code review (the `subagent-driven-development` final-reviewer step). Then **on-device smoke on the SM-A176B (owner)** — the acceptance bar from design §10:

- recording `renderTotal` avg ≤ ~10 ms;
- `blit` avg ≤ ~3 ms (the perf line's renamed field);
- no 80-90 ms cascade;
- recording `interval` holds ~33 ms in good light;
- recorded P+L files smooth on playback.

If a smoke shows GL-memory pressure or thermal throttle, the pre-flagged escalation is `FboRing.RING_DEPTH` 3 → 2 (~59 MB → ~39 MB) — a one-line change (design §4.2).
