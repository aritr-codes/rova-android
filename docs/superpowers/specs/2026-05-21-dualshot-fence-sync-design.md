# DualShot Render Fence-Sync (B3) — Design

**Date:** 2026-05-21
**Status:** Approved (brainstorming) — ready for implementation plan
**Scope:** `EglRouter.kt` + `EncoderRenderThread.kt` + `FboRing.kt` internals. No
public-API change.
**Driver:** Residual DualShot recording lag after B2 (the FBO-ring redesign,
PR #33). The callback thread no longer waits on a render barrier, but the
post-blit `glFinish()` still drains the GPU pipeline on the callback thread —
on a GPU shared with the two encoders, that drain waits out encoder work.
**Relationship to B2:** B3 is the realisation of B2 design §9 rejected
alternative **C** ("EGL fence sync instead of post-blit `glFinish`"). B2 deferred
it as a micro-optimisation "if a smoke ever shows the blit drain matters." The
PR #34 split-timer smoke showed it matters (§1). B3 picks the GL-core
(`glFenceSync`) path rather than the EGL-extension (`eglCreateSyncKHR`) path
§9-C sketched (§9).
**Branch base:** B3 branches off `master` at the post-B2 tip — `98eeac6`
(PR #33 squash-merged 2026-05-21).

---

## 1. Problem

After B2, `EglRouter.renderFrame` runs on the CameraX `SurfaceProcessor` callback
thread and:

1. `updateTexImage()` on the shared camera `SurfaceTexture` (OES holds frame N).
2. Blits the OES frame into an FBO ring slot (one quad, identity `uTexMatrix`).
3. **`glFinish()`** — so the blit pixels are complete and visible to the encoder
   share-group contexts before they sample the slot.
4. Hands the slot's texture id to both `EncoderRenderThread` mailboxes.
5. Draws both previews inline, returns.

Step 3 is the residual cost. `glFinish()` blocks the **callback CPU thread**
until the GPU has drained *every* pending command — and the GPU is shared with
the two encoder contexts. So the callback thread's `glFinish` waits out not just
its own ~0.7 ms blit but whatever encoder draw work is in flight.

B2's on-device recording captures (SM-A176B) showed `renderTotal` avg
**20-32 ms** and `blit` avg **15-27 ms** — against the 33.3 ms (30 fps) budget,
the callback thread still overran intermittently. The PR #34 diagnostic split the
`blit` timer into `blitDraw` (the quad) and `blitFinish` (the `glFinish`):
steady-state **`blitDraw` ≈ 0.7 ms, `blitFinish` ≈ 18-26 ms**. The `glFinish`
GPU-drain is **≥96 % of the blit cost** and the whole residual overrun. The FBO
size is not the cost — `blitDraw` at 0.7 ms confirms 2560×1920 is fine.

When the callback thread overruns 33.3 ms the camera HAL drops *source* frames →
the live captured-frame drop the owner reports.

A separate, non-code factor (Problem A) — camera auto-exposure sagging capture to
~25 fps in dim light — remains **out of scope** (a later `setTargetFpsRange` /
AE-priority ticket on `RovaRecordingService`).

## 2. Goals / Non-Goals

**Goals**
- The callback thread issues the blit and **returns without draining the GPU** —
  no `glFinish` on the callback thread.
- Blit-before-encoder-sample ordering is still guaranteed — enforced as a
  GPU-side dependency, not a CPU stall.
- Recording `renderTotal` average ≤ ~10 ms; `blit` average ≤ ~3 ms.
- A slow or stalled encoder still degrades only its own output file.
- Recording `interval` holds ~33 ms in good light.

**Non-Goals**
- The B2 FBO ring itself — `RING_DEPTH = 3`, `FBO_WIDTH = 2560`,
  `FBO_HEIGHT = 1920`, the identity blit, the latest-wins mailbox — all unchanged.
- The encoder's own post-`drawFrame` `glFinish()` — **kept** (§8). It is the
  FBO-slot read-safety, orthogonal to the callback fence. "Fence swap only"
  means swapping the *callback's* `glFinish`.
- Any change to `AspectFitMath`, `RotationCalculator`, `DualMuxerStateMachine`,
  `DualVideoRecorder`, the single-mode pipeline, `setupSingleCamera`, `WarningId`,
  `WarningPrecedence`, or `FrameMailbox` / `FrameMailboxTest`.
- Camera mode / source resolution (ADR-0009 untouched; Problem A is separate).
- `pause()` / `resume()` (still deferred, per the Phase 6.1a owner-lock).
- The preview render path — previews stay inline on the callback thread.

## 3. Strategy — A GPU Fence Replaces the CPU Drain

`glFinish()` enforces "blit done before encoder samples" by **blocking the
callback CPU thread** until the GPU drains. The ordering is correct; the cost is
that a CPU thread is parked on GPU work.

B3 expresses the *same* ordering as a **GPU sync object**:

- The callback context, right after the blit, inserts a fence into its GL
  command stream — `glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)` — then
  `glFlush()`. Both calls return on the CPU **immediately**. The fence will
  signal on the GPU once every command issued before it (the blit) completes.
- Each encoder context, before its draw, issues `glWaitSync(fence, 0,
  GL_TIMEOUT_IGNORED)`. `glWaitSync` is a **server-side** wait: the encoder CPU
  thread returns at once; the GPU is told to hold that context's subsequent
  commands (the FBO sample) until the fence signals.

The cross-thread ordering `glFinish` used to buy with a ~20 ms CPU stall is now a
GPU-scheduler dependency that costs **neither** CPU thread anything. The callback
thread's per-frame cost collapses to `updateTexImage` + the ~0.7 ms blit draw +
the preview draws ≈ 6-10 ms.

| Thread | B3 per-frame work |
|---|---|
| **Callback** (SurfaceProcessor thread) | `updateTexImage` → blit OES into an FBO slot → `glFenceSync` + `glFlush` (both return at once) → `recordFence` → hand slot id + fence to both encoder mailboxes → draw both previews inline → **return** |
| **Encoder-P / Encoder-L** | take latest `EncoderFrame` → `glWaitSync(frame.fenceSync)` (GPU-side, CPU returns at once) → draw the crop sampling the FBO `GL_TEXTURE_2D` → `glFinish` (FBO-slot read-safety, kept) → `eglSwapBuffers` |

GL sync objects are core OpenGL ES **3.0** and are shared across an EGL share
group — the callback root context creates the fence, the encoder contexts (B1's
share group) wait on it. Using them requires the EGL contexts to be ES3 (§5).

## 4. Files & Components

| File | Change |
|---|---|
| `FboRing.kt` | Add `fences: LongArray(RING_DEPTH)` + `lastAdvancedSlot`; `advance()` records the slot index; new `recordFence(fence: Long)`; `release()` deletes all non-zero fences. New `GLES30` import. |
| `EglRouter.kt` | Root context `EGL_CONTEXT_CLIENT_VERSION` 2 → 3 (`setup()`). Blit block: `glFinish()` → `glFenceSync` + `glFlush` + `recordFence`; `EncoderFrame(...)` gains the fence argument. New `GLES30` import. Comment/KDoc updates. |
| `EncoderRenderThread.kt` | `EncoderFrame` gains `fenceSync: Long`. Encoder context `EGL_CONTEXT_CLIENT_VERSION` 2 → 3 (`initEgl()`). `run()` issues `glWaitSync` before `drawFrame`. Encoder's own `glFinish()` **unchanged**. New `GLES30` import. KDoc updates. |
| `FrameMailbox.kt` / `FrameMailboxTest.kt` | **Unchanged** — byte-identical to master. `FrameMailbox<T>` is payload-agnostic; `EncoderFrame` gaining a field does not touch its contract. |

No new files. No new classes. `EncoderFrame` becomes
`EncoderFrame(fboTextureId: Int, texMatrix: FloatArray, fenceSync: Long)`.

## 5. ES2 → ES3 Context Bump

`glFenceSync` / `glWaitSync` / `glDeleteSync` are core OpenGL ES 3.0 entry
points. To call them validly the EGL context must be an ES3 context. Two
context-creation sites change:

- `EglRouter.setup()` — the root context `ctxAttribs`:
  `EGL_CONTEXT_CLIENT_VERSION, 2` → `3`.
- `EncoderRenderThread.initEgl()` — the per-encoder context `ctxAttribs`:
  `EGL_CONTEXT_CLIENT_VERSION, 2` → `3`.

**The EGL config does not change.** `EglRouter`'s config keeps
`EGL_RENDERABLE_TYPE = EGL_OPENGL_ES2_BIT`. An ES3 context is fully compatible
with an `EGL_OPENGL_ES2_BIT` config — ES3 is a strict superset of ES2 and the bit
advertises a *minimum* capability. This is the universal Android pattern (Google's
Grafika creates ES3 contexts against ES2-bit configs). `EGL_OPENGL_ES3_BIT_KHR`
(0x0040) is a KHR-extension constant absent from base `android.opengl.EGL14` and
is unnecessary. The single shared `eglConfig` also backs both encoder window
surfaces — leaving it untouched keeps surface creation byte-identical.

**The shaders do not change.** The blit, preview, and encoder shaders are GLSL
ES 1.00 (`attribute` / `varying`, no `#version` directive). An ES3 context
compiles and runs GLSL ES 1.00 unchanged (ES3 supports `#version 100`). No
`#version 300 es` migration; the shader strings stay verbatim.

**Availability.** `android.opengl.GLES30` is API 18 — below `minSdk = 24`
(Android 7.0) — so no `@RequiresApi`, no `InlinedApi` / `NewApi` lint, no
`@SuppressLint`. OpenGL ES 3.0 is guaranteed on every Android 7+ GPU (ES3 has
had ~100 % device coverage since Android 5). The test device, SM-A176B, is
ES 3.x. The existing `eglChooseConfig` / `eglCreateContext` `require()` guards in
`setup()` and the `EGL_NO_CONTEXT` check in `initEgl()` already surface a
context-creation failure exactly as today — no new failure path.

## 6. Fence Lifecycle — `FboRing`

A GL sync object is a `long` handle that must be explicitly deleted
(`glDeleteSync`). `FboRing` — already the single-thread, callback-thread-only
owner of the ring slots — also owns the fences.

- **`private val fences = LongArray(RING_DEPTH)`** — index-aligned with `slots`;
  `0L` means "no fence recorded for this slot".
- **`private var lastAdvancedSlot = 0`** — the slot index `advance()` most
  recently returned.
- **`advance()`** — set `lastAdvancedSlot = counter` *before* the existing
  `counter = (counter + 1) % RING_DEPTH` increment, then return the slot. No
  other behaviour change.
- **`recordFence(fence: Long)`** — for `fences[lastAdvancedSlot]`: if the current
  value is non-zero, `GLES30.glDeleteSync` it, then store `fence`. The value
  being overwritten is the fence from when this slot was last used —
  `RING_DEPTH` frames (~100 ms at 30 fps) ago. Must be called once, immediately
  after `advance()`, on the callback thread (the existing `FboRing`
  single-thread contract — documented in its class KDoc).
- **`release()`** — before deleting the FBOs and textures, `GLES30.glDeleteSync`
  every non-zero entry in `fences` and zero them.

**Why deleting a fence is always safe.** The depth-3 ring means a slot's fence is
~100 ms old before `recordFence` overwrites it; the encoder, at most ~1 frame
behind via the latest-wins mailbox, finished `glWaitSync`-ing it long ago. And
`glDeleteSync` on a sync object that still has a wait pending is *defined* to
defer the actual delete until the wait resolves — never a crash. Same depth-3
margin argument that already protects FBO-slot reuse (B2 design §4.2).

**Fence ownership is the ring's, not the `EncoderFrame`'s.** `EncoderFrame.fenceSync`
is a **read-only copy** of the handle, for the encoder to wait on.
The handle's lifecycle — create-tracked-by-`recordFence`, deleted on slot recycle
or at `release()` — belongs entirely to `FboRing.fences[]`. Consequences:

- An encoder that fails, or drops a frame via latest-wins, leaks **nothing** —
  the dropped `EncoderFrame` is GC'd, but the ring still deletes the fence.
- Both encoders receive the **same** `EncoderFrame` instance, so both
  `glWaitSync` the **same** fence handle. `glWaitSync` neither consumes nor
  deletes a sync — concurrent waits from two contexts are well-defined.

## 7. Per-Frame Data Flow

**Callback thread — `renderFrame()` blit block:**

```
1. eglMakeCurrent(pbuffer, rootContext)
2. updateTexImage(); getTransformMatrix(texMatrix)            // OES holds frame N
3. liveEncoders = encoderThreads snapshot, filter !failed
4. if liveEncoders non-empty AND fboRing != null:
     slot = fboRing.advance()                                 // slot = N % RING_DEPTH
     glBindFramebuffer(GL_FRAMEBUFFER, slot.framebufferId)
     glViewport(0, 0, 2560, 1920)
     draw full quad: program, OES texture, uTexMatrix = IDENTITY   // identity blit
     glBindFramebuffer(GL_FRAMEBUFFER, 0)
     fence = GLES30.glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)   // was: glFinish()
     GLES20.glFlush()                                         // MANDATORY — see note below
     fboRing.recordFence(fence)
     liveEncoders.forEach { submit(EncoderFrame(slot.textureId, texMatrix.copyOf(), fence)) }
5. draw previews inline (sample OES) + swap                   // B2 behaviour, unchanged
6. return                                                      // no GPU drain on this thread
```

> **The `glFlush()` after `glFenceSync()` is load-bearing, not optional.** A
> fence created in one context is observable by `glWaitSync` in another context
> of the same share group **only after the creating context flushes** the fence
> command to the GPU. `glClientWaitSync` can self-flush via
> `GL_SYNC_FLUSH_COMMANDS_BIT`; `glWaitSync` **cannot** (its `flags` must be 0).
> Without the explicit `glFlush()`, the encoder's `glWaitSync` can wait on a
> fence command still sitting in the callback context's unflushed queue —
> indefinitely. `glFlush` (unlike `glFinish`) only *submits* the queue; it does
> not block the CPU.

**Encoder thread loop (per side) — `run()`:**

```
1. frame = mailbox.take()                       // null = poisoned -> shutdown
2. if frame.fenceSync != 0L:
     GLES30.glWaitSync(frame.fenceSync, 0, GLES30.GL_TIMEOUT_IGNORED)
                                                 // GPU-side wait; CPU returns at once
3. drawFrame(frame.fboTextureId, frame.texMatrix)               // B2 math, unchanged
4. glFinish()                                    // KEPT — FBO-slot read-safety (§8)
5. eglSwapBuffers()                              // blocks on MediaCodec back-pressure
```

`glWaitSync` is issued in `run()`, inside the existing `try` block, immediately
after `mailbox.take()` and before `drawFrame` — the encoder context is current
for the thread's whole life, so no extra `eglMakeCurrent` is needed. The
`drawFrame` signature and its `finalMatrix = uvTransform × texMatrix` math are
**unchanged** from B2 (the FBO is still an identity copy of the OES `[0,1]`
space).

## 8. Error Handling & Teardown

**`glFenceSync` returns 0.** `glFenceSync` returns 0 only on error — invalid
arguments (ruled out by the fixed `GL_SYNC_GPU_COMMANDS_COMPLETE` / `0`
constants) or `GL_OUT_OF_MEMORY`. `recordFence(0L)` stores 0; the `EncoderFrame`
carries `fenceSync = 0`; the encoder's `if (frame.fenceSync != 0L)` guard skips
`glWaitSync`. Degradation: that one frame on that encoder may sample the FBO
slot before the blit's GPU writes land — at worst one torn frame; the next frame
carries a valid fence and self-heals. No crash. (A GL out-of-memory is a
whole-app failure regardless.)

**Blit `try / catch`.** The blit block stays wrapped in the existing
`catch (t: Throwable)` — the new `glFenceSync` / `glFlush` / `recordFence` calls
sit inside it. On throw: log, skip the `submit` for that frame, continue —
unchanged from B2.

**`glWaitSync` on a stale fence.** The depth-3 ring gives a ≥2-frame margin
before a fence the encoder is waiting on could be `glDeleteSync`'d (same argument
as FBO-slot reuse). And `glWaitSync` on an invalid/deleted sync is a benign
`GL_INVALID_VALUE` no-op — it does not wait, does not crash. Double-safe.

**The encoder's own `glFinish()` stays.** `EncoderRenderThread.drawFrame` ends
with `glFinish()` so the encoder's *sampling* of the FBO slot completes inside
its own frame, before the depth-3 ring wraps back to that slot. That is the
FBO-slot read-safety mechanism — orthogonal to the callback's blit fence, which
only orders blit-before-encoder-sample. B3 does not touch it. Replacing it would
re-introduce a callback↔encoder coupling (§9).

**Teardown — `EglRouter.release()`** — order unchanged from B2:

```
1. shutdown() + bounded join() every encoder thread   // they stop issuing glWaitSync
2. fboRing.release()                                  // glDeleteSync fences, then delete FBOs/textures
3. destroy preview surfaces, root context, pbuffer, eglTerminate
```

`glDeleteSync` is a GL call and needs the context current. `fboRing.release()`
already runs under B2's `contextCurrent` guard (the pbuffer is made current
first; `ring.release()` is skipped if that fails). The fence deletes ride inside
`fboRing.release()`, so they are covered by the same guard. If the guard skips
`release()`, `eglDestroyContext` reclaims the fences with every other object the
share group owns. The one-thread-per-GL-object rule holds — the fences, like the
FBOs, live entirely on the callback thread.

**Mid-frame shutdown.** A poisoned encoder reads `null` from the mailbox, breaks
its loop, tears down its own EGL objects. Any `glWaitSync` it already issued just
completes on the GPU — the callback `glFlush`'d the fence, so it *will* signal.
Harmless, same reasoning as B2's mid-`glFinish` shutdown.

## 9. Rejected Alternatives

- **`glClientWaitSync` instead of `glWaitSync`.** `glClientWaitSync` blocks the
  *calling CPU thread* until the fence signals. On the encoder thread that merely
  relocates the ~20 ms stall from the callback thread to the encoder thread — the
  encoder's fence wait and its `eglSwapBuffers` back-pressure would serialise, and
  a slow encoder could still fall behind. Only `glWaitSync` (a GPU-side wait)
  actually frees a CPU thread. Rejected.
- **EGL fence extension (`eglCreateSyncKHR` / `eglWaitSyncKHR`).** This is what
  B2 design §9-C sketched. It needs runtime extension-availability detection
  (`eglQueryString(EGL_EXTENSIONS)` for `EGL_KHR_fence_sync`) and KHR constants
  absent from base `EGL14`. The GL-core path — `glFenceSync` from ES 3.0 — is
  guaranteed by the ES3 context with **no** extension check, and is strictly
  simpler. B3 supersedes §9-C by taking the GL-core path. Rejected.
- **Encoder → callback read-fence.** Have the encoder signal a fence when it
  finishes sampling, and the callback `glWaitSync` it before reusing a slot. This
  re-introduces a callback↔encoder dependency — exactly what B2 removed. The
  depth-3 ring already guarantees slot-reuse safety without the callback waiting
  on anything. Rejected.
- **Keep `glFinish` behind a feature flag.** The callback `glFinish` *is* the
  defect; a toggle to restore it is a dead branch. Deleted, not retained.
- **Shrink the FBO or drop `RING_DEPTH`.** The PR #34 split showed `blitDraw`
  ≈ 0.7 ms — the FBO size is not the cost. Resizing would be cargo-culting. The
  ring geometry is untouched.

## 10. Verification

`EglRouter`, `EncoderRenderThread`, and `FboRing` are the runtime EGL/GL layer —
**no unit tests** (the established `dualrecord` policy; `android.opengl.*` are
JVM no-ops under `isReturnDefaultValues = true`).

- **No new JVM tests.** B3 adds no pure logic. `FrameMailbox` / `FrameMailboxTest`
  carry over **byte-identical** — `FrameMailbox<T>` is payload-agnostic, and
  `EncoderFrame` gaining a `fenceSync` field does not change the mailbox contract.
- **Gate per task:** `:app:assembleDebug` compiles. The full test + lint gates
  stay at the post-B2 `master` baseline — B3 adds no tests and no lint surface
  (`GLES30` is API 18, below `minSdk = 24`; no `InlinedApi` / `NewApi`).
- **Invariant git-diff allowlist:** `FboRing.kt`, `EglRouter.kt`,
  `EncoderRenderThread.kt`, this design doc, and the implementation plan.
  `FrameMailbox.kt`, `FrameMailboxTest.kt`, `AspectFitMath`, `RotationCalculator`,
  `DualMuxerStateMachine`, `DualVideoRecorder`, the single-mode pipeline,
  `setupSingleCamera`, `WarningId`, and `WarningPrecedence` must be byte-identical
  to master.
- **On-device smoke (SM-A176B, owner):** the existing `EglRouter perf` log line
  is the meter. Acceptance, recording (`encoders=2`):
  - `renderTotal` avg ≤ ~10 ms;
  - `blit` avg ≤ ~3 ms (the GPU-drain is gone — expect ~1-2 ms);
  - `interval` holds ~33 ms in good light;
  - no 80-90 ms cascade;
  - recorded P+L files smooth on playback.
- **PR #34** (the diagnostic `blit` / `blitFinish` split-timer, open, unmerged):
  recommend closing **unmerged**. B3 removes the `glFinish` the `blitFinish`
  sub-timer was built to isolate, so it would measure nothing. The plain `blit`
  timer on `master` already suffices for B3's acceptance check above.

## 11. Risks

| Risk | Mitigation |
|---|---|
| ES3 context unsupported on a device | `minSdk = 24`; ES 3.0 is universal on Android 7+. `eglChooseConfig` / `eglCreateContext` are already `require()`-guarded — a failure surfaces at `setup()` exactly as today, no new path. |
| `glWaitSync` never signals (callback did not flush) | The spec mandates `glFlush()` after `glFenceSync()`; called out in §7 as the load-bearing line; the plan makes it an explicit step. |
| Fence handle leak | `FboRing.fences[]` owns every handle — deleted on slot recycle (`recordFence`) and at `release()`. `EncoderFrame` only borrows the handle; a dropped/failed encoder leaks nothing (§6). |
| Encoder samples FBO before the blit completes (`glFenceSync` returned 0) | One torn frame on one encoder; self-heals on the next frame's valid fence (§8). A GL OOM is a whole-app failure regardless. |
| Teardown race regression | Order unchanged from B2; fence `glDeleteSync` calls ride inside `fboRing.release()` under B2's `contextCurrent` guard (§8). |
