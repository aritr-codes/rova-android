# DualShot Render Threading Redesign — Design

**Date:** 2026-05-21
**Status:** Approved (brainstorming) — ready for implementation plan
**Scope:** `EglRouter.kt` internals + one new pure-JVM helper. No public-API change.
**Driver:** Defect #2 — DualShot recording stutter/lag. `EglRouter.renderFrame` is a
single-threaded serial pump; with 4 targets and blocking `eglSwapBuffers`, recording
`renderTotal` averages 20-28ms and spikes 80-90ms against a 33.3ms (30fps) budget.

---

## 1. Problem

`EglRouter.renderFrame` runs on the CameraX `SurfaceProcessor` callback thread and
draws all targets serially:

1. `updateTexImage()` on the shared camera `SurfaceTexture`.
2. For each of 4 targets (preview-P, preview-L, encoder-P, encoder-L):
   `eglMakeCurrent` → draw textured quad → `eglSwapBuffers`.

`eglSwapBuffers` on an encoder surface **blocks on MediaCodec input-surface
back-pressure**. When an encoder stalls, the whole serial pump freezes — every
target waits behind it. Measured: avg 20-28ms, spikes 80-90ms. Preview-only
(no encoders) is ~5ms, confirming encoders are the entire load.

**FPS target:** 30fps. The camera-mode diagnostic (2026-05-21) confirmed
`4080x3060@30.0fps` is a real 30fps source mode and steady-state preview-only
interval is `avg=33.3ms`. No source-mode change is needed; ADR-0009 stands.
Scope is purely the `EglRouter` threading model.

## 2. Goals / Non-Goals

**Goals**
- Recording `renderTotal` average ≤ ~16ms.
- Eliminate cascading 80-90ms freezes; any residual stall is isolated and self-heals.
- Run the two encoder swaps concurrently rather than serially.
- Remove the teardown races that PR #28 and PR #30 patched defensively — structurally.

**Non-Goals**
- Source resolution / camera mode change (ADR-0009 untouched).
- Any change to `AspectFitMath`, `RotationCalculator`, `DualMuxerStateMachine`,
  `DualVideoRecorder`, the single-mode pipeline, or `setupSingleCamera`.
- `pause()`/`resume()` (still deferred, per Phase 6.1a owner-lock).
- A full per-target thread fan-out (rejected — see §9).

## 3. Thread Model

Three threads replace the single serial pump.

| Thread | Owns | Per-frame work |
|---|---|---|
| **Callback thread** (existing SurfaceProcessor thread) | root `EGLContext`, anchor pbuffer surface, both preview `EGLSurface`s | `updateTexImage`, fan out frame to encoder mailboxes, draw both previews inline, host the per-frame barrier |
| **Encoder-P thread** (new) | shared `EGLContext`, portrait encoder `EGLSurface` | draw portrait quad → `glFinish` → `countDown` → `eglSwapBuffers` |
| **Encoder-L thread** (new) | shared `EGLContext`, landscape encoder `EGLSurface` | draw landscape quad → `glFinish` → `countDown` → `eglSwapBuffers` |

**Why previews stay inline:** preview swaps are cheap (~5-10ms) and `PreviewView`'s
Surface drops frames itself — no hard back-pressure. They draw on the callback
thread *while* the encoder threads run, so their cost overlaps and is effectively
free. Two extra preview threads (the rejected approach C) buy nothing but lock
surface area. Encoders are the load — they get the threads.

**Concurrency win:** the two encoder swaps that ran serially (~25ms summed) now
run concurrently → the encoder cost the callback thread observes collapses toward
`max(encP, encL)`. The spike fix itself is structural (§5), not just parallelism.

## 4. Shared EGL Context Group

The camera frame is one `SurfaceTexture` → one `GL_TEXTURE_EXTERNAL_OES` texture.
All three threads must sample that same texture id, which EGL permits only inside
a **share group**.

**Setup:**
1. Callback thread creates the root `EGLContext` + anchor pbuffer (exists today).
2. Each encoder thread, on first run, creates its own context:
   `eglCreateContext(display, config, ROOT_CONTEXT, attribs)` — passing the root
   as `share_context` joins the share group. The external camera texture and
   shaders become visible across all three contexts.
3. Each encoder thread **compiles its own shader program** (one-time, cheap) —
   avoids cross-thread "which context owns this program" foot-guns.
4. Each encoder thread creates its window `EGLSurface` from the encoder input
   `Surface` **on its own thread** (EGLSurface creation must run on the thread
   that will `makeCurrent` it).

**Hard rule — one context, one thread, forever.** An `EGLContext` is current on at
most one thread at a time. The root context never leaves the callback thread; each
encoder context never leaves its encoder thread. No `makeCurrent` ping-ponging.
This is what removes the two-tier-lock complexity from the PR #30 ANR fix —
threads no longer share surfaces.

**All three contexts use the same `EGLConfig`** (the recordable-Android config
already chosen in `setup()`) — some drivers reject sharing across differing
configs.

## 5. Per-Frame Barrier + Decoupling Trick (the spike fix)

Two facts in tension:

- **Fact A:** `SurfaceTexture` has one texture. `updateTexImage()` for frame N+1
  destroys frame N. Every consumer must finish *sampling* N before N+1's
  `updateTexImage`.
- **Fact B:** `eglSwapBuffers` on an encoder surface blocks on back-pressure
  (80-90ms when stalled).

A naive barrier (wait for encoders to fully finish before the next frame)
re-couples the callback thread to Fact B. The fix: **split each encoder's work at
`glFinish` and count down the barrier before the swap.**

**`renderFrame` (callback thread), per camera frame:**
1. `eglMakeCurrent(anchor)` → `updateTexImage()` + `getTransformMatrix()`.
2. Publish the transform matrix to each encoder mailbox; arm a `CountDownLatch(2)`.
3. Draw both previews inline, swap them.
4. `latch.await(timeout)` — block until both encoders have counted down.
5. Return → the next `updateTexImage` is now allowed.

**Encoder thread loop:**
1. Take the latest frame from the mailbox (block until one arrives).
2. `eglMakeCurrent(its surface)` → draw the quad with the matrix.
3. `glFinish()` — the GPU has finished *sampling* the camera texture.
4. `latch.countDown()` — **the callback thread is released here.**
5. `eglSwapBuffers()` — blocks on back-pressure, but the callback thread has
   already moved on.

**The decoupling:** step 4 precedes step 5. The callback thread waits only for
`draw + glFinish` — GPU-bound, predictable, ~10-14ms — **never** for
`eglSwapBuffers`. A stalled encoder swap no longer freezes the pump.

**One-frame pipeline.** An encoder cannot start frame N+1's draw until N's swap
returns (same `EGLSurface`). So the callback thread on frame N effectively waits
for *swap N-1* (embedded in the encoder being ready), not swap N. In steady state
swap N-1 already completed during the 33ms inter-frame gap, so the callback thread
observes only `draw + glFinish` ≈ 14ms.

**What a stall costs now:** a single 90ms encoder swap delays that encoder's
readiness for exactly one frame → the callback thread blocks once for ~90ms, then
the encoder catches up because the mailbox is latest-wins (it skips the backlog).
A cascading multi-target freeze becomes **one isolated, self-healing hiccup**. No
ANR.

**`latch.await` timeout** is a wedge detector only (§7).

## 6. Mailbox & Frame-Drop Semantics

**`FrameMailbox<T>`** — one per encoder thread. Single slot, **latest-wins**:
- Callback thread writes the frame payload (transform matrix + per-side state).
- Writing again before the encoder reads simply overwrites — stale frame discarded.
- Encoder thread reads the newest payload; intermediate frames are dropped
  implicitly. No explicit drop logic.
- A poison-pill value signals shutdown (§7).

This is the per-side frame-drop: a slow encoder drops its own frames without
affecting the other side or the previews.

**Chosen strategy: B1 — strict barrier on direct `SurfaceTexture` sampling.**
Zero extra memory. Drops the average from ~24ms to ~14ms, makes encoders
concurrent, converts cascading freezes into isolated self-healing hiccups.

**Pre-flagged escalation: B2 — half-res FBO ring.** If on-device smoke still
shows visible hiccups, snapshot each camera frame into a ping-pong FBO ring so
`updateTexImage` fully decouples from the encoders, killing even the isolated
hiccup. Cost: ~2-3ms blit + FBO memory. A source-res buffer is ~50MB
(4080×3060 RGBA) — too heavy for the budget SM-A176B — so B2 would snapshot at
half res (~12MB/buffer), a slight source-quality concession. Same threading
skeleton; B2 only inserts the snapshot stage. **Not in this design's scope —
escalation only.**

## 7. Error Handling & Teardown

**Per-encoder isolation.** An encoder thread that throws (EGL context loss, codec
death) sets its own `failed` flag, counts down its latch (so the callback thread
is never stuck), and exits. The other side keeps recording. Mirrors the existing
`DualMuxerStateMachine.SideFailed` tolerance.

**`latch.await` timeout = wedge detector.** Steady-state `draw + glFinish` is
~14ms; a strict barrier never legitimately exceeds ~30ms. The timeout is set
generously (~100ms). If it fires, an encoder thread is wedged in a driver call:
log, mark that side failed, proceed. Recording degrades to one side; the app never
ANRs. (WarningCenterContract NO-GO posture: a render failure must not itself
become an ANR.)

**Shutdown handshake (`EglRouter.release()`):**
1. Callback thread sets `shuttingDown`, pushes a poison-pill into each mailbox.
2. Each encoder thread, on the poison-pill:
   `eglMakeCurrent(NO_SURFACE, NO_CONTEXT)` → `eglDestroySurface` →
   `eglDestroyContext` **on its own thread** → exit.
3. Callback thread `join()`s both encoder threads (bounded timeout).
4. Callback thread destroys the root context + anchor pbuffer last.

Every EGL object is now created and destroyed on exactly one thread — there is
nothing left to race. This is the structural cure for the teardown bugs that
PR #28 (`EncoderSurface` join-before-stop) and PR #30 (two-tier locking) patched
defensively.

**Mid-`glFinish` shutdown:** `glFinish` returns when the GPU drains; the
poison-pill is read on the *next* mailbox poll, so an in-flight frame completes
cleanly before teardown. GL calls are never interrupted.

## 8. Verification

`EglRouter` is the runtime EGL/GL layer — **no unit tests** (the established
`dualrecord` policy; `android.opengl.*` are JVM no-ops under
`isReturnDefaultValues=true`).

- **JVM-testable carve-out:** `FrameMailbox<T>` is a pure class — JVM tests cover
  latest-wins overwrite, poison-pill delivery, and concurrent write/read. Keeps
  the threading core honest without an emulator.
- **On-device smoke (SM-A176B, owner):** the existing `recordRenderPerf`
  instrumentation already logs `interval / renderTotal / drawMax / encSwapMax`.
  Acceptance = recording `renderTotal` avg ≤ ~16ms, no 80-90ms cascade, recorded
  P+L files smooth on playback.
- **Invariant check:** `git diff` allowlist = `EglRouter.kt` + new
  `FrameMailbox.kt` + `FrameMailboxTest.kt`. `AspectFitMath`, `RotationCalculator`,
  `DualMuxerStateMachine`, `DualVideoRecorder`, the single-mode pipeline, and
  `setupSingleCamera` must be byte-identical to master.

## 9. Rejected Alternatives

- **A — single encoder thread.** Both encoders on one off-callback thread,
  serialized. Removes the encoder load from the callback thread but the two swaps
  still run serially (~25ms). Partial fix; the concurrency win is left on the
  table for one fewer thread. Rejected — the third thread is cheap.
- **C — full per-target threads (5 threads).** A thread per preview as well.
  Previews have no back-pressure problem and cost ~5ms; threading them adds lock
  surface and shutdown complexity for no measurable gain. Over-engineered.
- **B2 now (FBO ring up front).** Strictly better latency but costs memory on a
  budget device and adds the snapshot stage before it is proven necessary. Kept
  as the pre-flagged escalation, not the initial ship (§6).

## 10. Risks

| Risk | Mitigation |
|---|---|
| Driver rejects shared context across configs | All three contexts use the same `EGLConfig` (§4). |
| Encoder thread wedges in a driver call | `latch.await` timeout → mark side failed, proceed (§7). |
| Residual isolated hiccup still visible | B2 half-res FBO ring is the pre-flagged escalation (§6). |
| Teardown race regression | One-thread-per-EGL-object rule + poison-pill handshake (§7). |
