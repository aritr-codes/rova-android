# DualShot FBO-Ring Redesign (B2) — Design

**Date:** 2026-05-21
**Status:** Approved (brainstorming) — ready for implementation plan
**Scope:** `EglRouter.kt` + `EncoderRenderThread.kt` internals + one new pure-runtime
helper (`FboRing.kt`). No public-API change.
**Driver:** Residual DualShot recording lag after B1 (the render-threading redesign,
PR #32). On-device `EglRouter perf` captures show recording `renderTotal` avg
26-33 ms with `barrier` avg 19-28 ms — the per-frame barrier is still on the
critical path. Symptom: live captured frames drop (the camera HAL drops source
frames when the callback thread overruns the 33 ms budget); the saved file is
mostly intact on playback.

**Relationship to B1:** B2 is the pre-flagged escalation from the B1 design
(`2026-05-21-dualshot-render-threading-design.md` §6 / §9 / §10). Same three-thread
skeleton; B2 inserts a snapshot stage so the callback thread waits for nobody.

**Branch base:** B2 branches off `master` *after* PR #32 (B1) is merged (owner
decision, 2026-05-21). Implementation does not start until the owner confirms the
merge.

---

## 1. Problem

After B1, `EglRouter.renderFrame` runs on the CameraX `SurfaceProcessor` callback
thread and:

1. `updateTexImage()` on the shared camera `SurfaceTexture`.
2. Fans the frame out to two `EncoderRenderThread`s.
3. Draws both previews inline.
4. **Awaits a per-frame `CountDownLatch` barrier** — each encoder counts it down
   after `glFinish` (camera-texture sampling complete) and before its blocking
   `eglSwapBuffers`.

Step 4 is the residual cost. The camera `SurfaceTexture` has **one** OES texture;
`updateTexImage()` for frame N+1 destroys frame N, so every consumer must finish
*sampling* N before N+1. B1's barrier enforces that across threads — and the
`glFinish` it waits on is a full GPU-pipeline drain. The B1 design predicted
~10-14 ms; the device measured **19-28 ms**. Against a 33.3 ms (30 fps) budget,
plus `updateTexImage` and the preview draws, `renderTotal` overruns → the camera
HAL drops source frames → live captured-frame drop.

A separate, non-code factor (Problem A) — camera auto-exposure extends sensor
exposure in dim light, sagging capture to 25 fps — is **out of scope** here. In
good light the camera delivers a steady 30 fps; B2's job is to let recording
actually hold it.

## 2. Goals / Non-Goals

**Goals**
- Recording `renderTotal` average ≤ ~10 ms on the callback thread.
- Callback thread waits for **no** encoder work — zero cross-thread render barrier.
- A slow or stalled encoder degrades only its own output file, never the live
  capture rate, the previews, or the other encoder.
- Recording `interval` holds ~33 ms in good light.

**Non-Goals**
- Camera mode / source resolution change (ADR-0009 untouched; Problem A is a
  separate later ticket — cap the AE target FPS range).
- Any change to `AspectFitMath`, `RotationCalculator`, `DualMuxerStateMachine`,
  `DualVideoRecorder`, the single-mode pipeline, `setupSingleCamera`, or
  `WarningId` / `WarningPrecedence`.
- `pause()` / `resume()` (still deferred, per the Phase 6.1a owner-lock).
- The preview render path — previews stay inline on the callback thread exactly
  as B1 ships them.
- An EGL fence-sync optimisation of the post-blit `glFinish` (rejected for now —
  see §9).

## 3. Strategy — Snapshot, Then Decouple

The B1 barrier exists because the encoders sample the **live OES texture** on
their own threads, so the callback thread cannot call `updateTexImage` for the
next frame until they finish.

B2 removes the reason for the barrier: the callback thread **blits the camera
frame into a private off-screen buffer**, and the encoders sample *that* buffer
instead of the OES texture. The OES single-texture constraint is then satisfied
**entirely within the callback thread** — the blit and the preview draws both
sample OES, both run on the callback thread, both complete before it returns.
`updateTexImage(N+1)` is unconditionally free. The encoders run fully
asynchronously against their own copies.

| Thread | B2 per-frame work |
|---|---|
| **Callback** (existing SurfaceProcessor thread) | `updateTexImage` → blit OES frame into an FBO ring slot → `glFinish` (drains only the blit) → hand the slot's texture id to both encoder mailboxes → draw both previews inline → **return** |
| **Encoder-P thread** | take latest slot texture id → draw the portrait crop sampling that 2D FBO texture → `glFinish` → `eglSwapBuffers` |
| **Encoder-L thread** | take latest slot texture id → draw the landscape crop sampling that 2D FBO texture → `glFinish` → `eglSwapBuffers` |

The price is one blit (~1-2 ms) plus its `glFinish`. The prize is deleting the
~25 ms barrier wait. The callback-thread cost collapses to `updateTexImage` +
blit + preview draws ≈ 6-10 ms.

## 4. The FBO Ring

**`FboRing`** — a new class in `service/dualrecord/internal/`, owned by
`EglRouter`, used only on the callback thread (root EGL context).

- **`RING_DEPTH = 3`** framebuffers. Each framebuffer has one `GL_TEXTURE_2D`
  colour attachment, **2560×1920 RGBA8**.
- The textures live in the **B1 share group** (the encoder contexts were created
  with the root context as `share_context`), so the encoder threads can sample
  them.
- `init(): Boolean` — `glGenFramebuffers` + `glGenTextures` ×3, allocate each
  texture at 2560×1920 with `GL_LINEAR` filtering + `GL_CLAMP_TO_EDGE`, attach as
  `GL_COLOR_ATTACHMENT0`, verify `glCheckFramebufferStatus`. Returns `false` on
  any failure. Called from `EglRouter.setup()`; on `false`, `EglRouter` logs and
  leaves its `fboRing` reference null. `renderFrame` guards the blit branch on
  `fboRing != null` (§6) — a null ring means the encoders simply receive no
  frames, but the callback thread, the previews, and the app never crash.
- `advance(): Slot` — bumps an internal frame counter and returns the slot for
  `counter % RING_DEPTH` as `Slot(framebufferId: Int, textureId: Int)`.
- `release()` — `glDeleteFramebuffers` + `glDeleteTextures`.

### 4.1 FBO resolution — 2560×1920

The buffer resolution sets both the GL-memory cost and the recorded-quality
ceiling. Camera source is 4080×3060 (4:3). The size is derived from the
**smallest-sampling encoder**, not an arbitrary fraction:

- The portrait crop samples **27/64 of the camera width** (the `AspectFitMath`
  PORTRAIT `pivot-scale(27/64,1,1)`). The portrait encoder outputs 1080 wide. For
  the portrait crop to sample at native resolution with no upscale:
  `FBO_width × 27/64 ≥ 1080` → `FBO_width ≥ 2560`.
- At **2560×1920** (4:3 preserved): portrait crop = 2560 × 27/64 = **1080 px
  exact** (native, no upscale); landscape samples the full 2560 width → its 1920
  encoder = 1.33× **downscale** (supersampled, clean).
- **No upscale anywhere.** ~19.7 MB per buffer; ~59 MB for the 3-deep ring.

Versus the alternatives: source-res 4080×3060 is ~47 MB/buffer (~141 MB ring) —
too heavy for the budget SM-A176B (B1 design §6 already ruled it out); any buffer
narrower than 2560 upscales the portrait crop. 2560×1920 is the only sizing that
is both lossless and budget-fitting.

### 4.2 Ring depth = 3, and read-safety

`RING_DEPTH = 3` is the read-safety mechanism — there is no lock and no in-flight
tracking. An encoder is handed slot `N % 3`; the callback thread overwrites that
same slot only at frame `N+3` (~100 ms later at 30 fps). An encoder reading via
the latest-wins mailbox (§5) is at most ~1 frame behind, and its own `glFinish`
completes the read inside that frame — so the slot is free with ~2 frames of
margin. The ring depth, not synchronisation, guarantees the encoder never samples
a slot the callback is mid-write.

**Pre-flagged escalation:** if an on-device smoke shows GL-memory pressure or
thermal throttle, drop `RING_DEPTH` to 2 (~39 MB) — a one-line constant change.
Depth 2 still gives ~1 frame of margin, sufficient for any non-pathological
encoder; depth 3 is the safer default.

## 5. Frame Hand-off — `FrameMailbox` + `EncoderFrame`

`FrameMailbox<T>` (from B1) is **unchanged** — single-slot, latest-wins,
poison-pill shutdown. It now carries an `EncoderFrame` whose payload is just the
ring slot's texture id.

- **`EncoderFrame`** becomes `EncoderFrame(fboTextureId: Int)` — drops B1's
  `texMatrix` (now baked into the FBO by the blit) and `barrier` (deleted — there
  is no barrier).
- The callback thread, after the blit + `glFinish`, calls
  `submit(EncoderFrame(slot.textureId))` on each live encoder. Latest-wins: a
  slow encoder's queued frame is overwritten — that side drops a frame, silently
  and in isolation.
- Poison-pill shutdown is unchanged: `mailbox.take()` returns `null` → the
  encoder thread breaks its loop and tears down its own EGL objects.

## 6. Per-Frame Data Flow

**Callback thread — `renderFrame()`:**

```
1. eglMakeCurrent(pbuffer, rootContext)
2. updateTexImage(); getTransformMatrix(texMatrix)        // OES holds frame N
3. liveEncoders = encoderThreads snapshot, filter !failed
4. if liveEncoders non-empty AND fboRing != null:
     slot = fboRing.advance()                             // slot = N % RING_DEPTH
     glBindFramebuffer(GL_FRAMEBUFFER, slot.framebufferId)
     glViewport(0, 0, 2560, 1920)
     draw full quad: program, OES texture, uTexMatrix = texMatrix   // blit, no mirror
     glBindFramebuffer(GL_FRAMEBUFFER, 0)
     glFinish()                                           // blit complete + cross-context visible
     liveEncoders.forEach { submit(EncoderFrame(slot.textureId)) }
5. draw previews inline (sample OES texture) + swap       // B1 behaviour, unchanged
6. return                                                 // no barrier; updateTexImage(N+1) free
```

The blit reuses the **existing `EglRouter.program`** — that shader already samples
the OES texture through a `uTexMatrix` uniform and draws a full quad. The blit is
exactly that draw, retargeted from a window surface to the FBO, at viewport
2560×1920, with `uTexMatrix = texMatrix` and **no mirror** (encoder output is
never mirrored — the front-camera mirror is preview-only, unchanged).

**Encoder thread loop (per side):**

```
1. texId = mailbox.take()?.fboTextureId   // null = poisoned -> shutdown
2. eglMakeCurrent(its encoder surface, its context)
3. draw quad: glBindTexture(GL_TEXTURE_2D, texId), uniform = uvTransform,
   viewport = this side's aspect-fit viewport
4. glFinish()                              // this side's read of the FBO slot done
5. eglSwapBuffers()                        // blocks on MediaCodec back-pressure — nobody waits
```

Step 3's uniform is `uvTransform` **alone**: B1's encoder computed
`finalMatrix = uvTransform × texMatrix`; the blit has already applied `texMatrix`,
so `texMatrix` is identity from the encoder's view and `finalMatrix = uvTransform`.

**Per-side frame drop.** A slow encoder's mailbox silently overwrites — that side
drops frames; the callback thread, both previews, and the other encoder are
untouched. The camera HAL stops dropping *source* frames because the callback now
returns in ~6-10 ms, well inside the 33 ms budget. That is the
captured-frame-drop fix.

## 7. Shaders & Texture Binding

- **Callback / blit:** unchanged. `EglRouter.program` keeps its
  `samplerExternalOES` fragment shader; the blit binds `GL_TEXTURE_EXTERNAL_OES`
  (the camera OES texture) and draws into the FBO.
- **Encoder:** the `EncoderRenderThread` fragment shader changes from
  `samplerExternalOES` to `sampler2D`, and drops the
  `#extension GL_OES_EGL_image_external : require` line — the encoder now samples
  the FBO's plain `GL_TEXTURE_2D` colour texture. `drawFrame` binds
  `GL_TEXTURE_2D` instead of `GL_TEXTURE_EXTERNAL_OES`.
- The `EncoderRenderThread` constructor drops `inputTextureId` (it no longer
  samples the shared OES texture; the FBO texture id arrives per-frame in
  `EncoderFrame`).

## 8. Error Handling & Teardown

**Blit failure (callback thread).** Wrap the blit in `try/catch`. On throw: log,
skip the `submit` for that frame (encoders retain their last frame via the
mailbox), continue. A persistent blit failure is root-context loss — the whole
router is dead regardless; one logged frame-skip is the correct degrade.

**Encoder failure.** A draw that throws sets the thread's `failed` flag, breaks
the loop, tears down that thread's own EGL objects. The callback's `liveEncoders`
filter drops it on the next frame. There is **no barrier to count down** — B1's
`finally { barrier.countDown() }` is gone. Recording continues one-sided,
mirroring `DualMuxerStateMachine.SideFailed`.

**No wedge detector.** B1's `BARRIER_TIMEOUT_MS` existed only to detect an encoder
wedged behind the barrier. With no barrier there is nothing to time out — the
constant and the `barrier.await(...)` call are deleted. A wedged encoder wedges
only itself; the callback thread never blocks on encoder work.

**Teardown — `EglRouter.release()`:**

```
1. shutdown() + bounded join() every encoder thread   // they stop sampling FBO textures
2. fboRing.release()                                  // delete FBOs + textures (callback thread)
3. destroy preview surfaces, root context, pbuffer, eglTerminate   // B1 order, unchanged
```

`fboRing.release()` MUST follow the encoder join (an encoder must never sample a
deleted texture) and MUST precede root-context destruction. Every GL object is
still created and destroyed on exactly one thread — B1's one-thread-per-EGL-object
rule holds; the FBO ring lives entirely on the callback thread.

**Mid-`glFinish` shutdown** is unchanged from B1: `glFinish` returns when the GPU
drains, the poison-pill is read on the next mailbox poll, an in-flight frame
completes cleanly before teardown.

## 9. Rejected Alternatives

- **B — per-side pre-cropped FBO rings.** Two rings, the callback thread blits
  twice (once cropped+sized per encoder), buffers ~8 MB each. Doubles the
  callback-thread blit work for no gain — the blit cost is the `glFinish` drain,
  not the quad draw; one shared blit + downstream per-encoder crop is strictly
  cheaper on the callback thread. Rejected.
- **C — EGL fence sync instead of post-blit `glFinish`.** Replace the callback's
  `glFinish` with `eglCreateSyncKHR` + the encoder's `eglWaitSyncKHR`. Removes the
  ~1-2 ms blit drain from the callback thread, but adds extension-availability
  handling for a sub-millisecond win. Over-engineered now; kept as a pre-flagged
  future micro-optimisation if a smoke ever shows the blit drain matters.
- **Source-res FBO (4080×3060).** Zero quality loss but ~141 MB for the ring on a
  4 GB-class device — the memory/thermal risk B1 §6 already flagged. Rejected in
  favour of the encoder-matched 2560×1920 sizing (§4.1), which is also lossless.
- **Keeping the barrier as a degraded fallback.** The barrier is the defect. There
  is no scenario where re-coupling the callback thread to encoder timing helps.
  Deleted, not retained.

## 10. Verification

`EglRouter`, `EncoderRenderThread`, and `FboRing` are the runtime EGL/GL layer —
**no unit tests** (the established `dualrecord` policy; `android.opengl.*` are JVM
no-ops under `isReturnDefaultValues=true`).

- **No new JVM tests.** B2 adds no pure logic — the ring-slot selection is
  `counter % RING_DEPTH`, and `FrameMailbox`'s latest-wins + poison behaviour is
  already covered by `FrameMailboxTest`, which carries over **unchanged** (the
  mailbox payload type changed but its contract did not).
- **Gate per task:** `:app:assembleDebug` compiles. The full test + lint gates
  stay at the post-#32 master baseline — B2 adds no tests and changes no lint
  surface.
- **Invariant git-diff allowlist:** `EglRouter.kt`, `EncoderRenderThread.kt`, the
  new `FboRing.kt`, plus this design doc and the implementation plan.
  `FrameMailbox.kt`, `FrameMailboxTest.kt`, `AspectFitMath`, `RotationCalculator`,
  `DualMuxerStateMachine`, `DualVideoRecorder`, the single-mode pipeline,
  `setupSingleCamera`, `WarningId`, and `WarningPrecedence` must be byte-identical
  to master.
- **On-device smoke (SM-A176B, owner):** the existing `EglRouter perf` log line is
  the meter (B1's `barrier` field is renamed `blit`). Acceptance:
  - recording `renderTotal` avg ≤ ~10 ms;
  - `blit` avg ≤ ~3 ms;
  - no 80-90 ms cascade;
  - recording `interval` holds ~33 ms in good light;
  - recorded P+L files smooth on playback.

## 11. Risks

| Risk | Mitigation |
|---|---|
| FBO ring memory pressure / thermal throttle on the budget device | Encoder-matched 2560×1920 sizing keeps the ring at ~59 MB (§4.1); `RING_DEPTH` 3→2 is a pre-flagged one-line drop to ~39 MB (§4.2). |
| Encoder samples a slot the callback is mid-write | `RING_DEPTH = 3` gives ~2 frames of margin over a latest-wins encoder; encoder `glFinish` completes the read inside its frame (§4.2). |
| Cross-context FBO-texture visibility (callback writes, encoder reads) | Callback `glFinish` after the blit, before `submit` — the standard share-group publish point (§6). |
| `glCheckFramebufferStatus` fails at `init()` (driver / size) | `FboRing.init()` returns `false`; `EglRouter` logs, leaves `fboRing` null, and `renderFrame` skips the blit branch (§4, §6) — encoders receive no frames, but the callback thread, previews, and app never crash. |
| Teardown race regression | One-thread-per-EGL-object rule holds; `fboRing.release()` strictly ordered after encoder join, before root-context destroy (§8). |
