# DualShot Render Fence-Sync (B3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the DualShot callback thread's post-blit `glFinish()` GPU-drain with a GLES30 `glFenceSync` / `glWaitSync` GPU fence, so the callback thread never blocks on encoder GPU work.

**Architecture:** The callback thread inserts a GL sync object after the OES→FBO blit (`glFenceSync` + `glFlush`, both non-blocking) instead of draining the GPU with `glFinish`. Each encoder thread issues a server-side `glWaitSync` on that fence before it samples the FBO, so the GPU — not a CPU thread — orders blit-before-sample. `FboRing` owns each fence's lifecycle. The two EGL contexts bump ES2→ES3 to make the sync API valid.

**Tech Stack:** Kotlin, Android (minSdk 24, AGP 9.2.1), EGL14, OpenGL ES 3.0 (`android.opengl.GLES30`), CameraX `SurfaceProcessor`.

**Design spec:** `docs/superpowers/specs/2026-05-21-dualshot-fence-sync-design.md` (committed to `master` at `4c06e3d`).

---

## Testing Policy — Read First

`EglRouter`, `EncoderRenderThread`, and `FboRing` are the runtime EGL/GL layer.
**This layer has no unit tests** by established `dualrecord` project policy:
`android.opengl.*` calls are JVM no-ops under
`testOptions { unitTests { isReturnDefaultValues = true } }`, so a JVM test of
this code would assert nothing real.

Therefore the tasks below are **not TDD** — there are no "write a failing test"
steps. Each implementation task's gate is **`:app:assembleDebug` compiles**. The
full JVM test suite + lint run once, in Task 4, only to confirm B3 (which touches
no test files and no lint surface) left the post-B2 `master` baseline unchanged.

Do **not** add unit tests for the GL files. Do **not** modify `FrameMailbox.kt`
or `FrameMailboxTest.kt` — they are byte-identical to master.

## Branch & Baseline

- **Branch base:** off `master` once the B3 spec + this plan are committed there.
  Suggested branch name: `perf/dualshot-fence-sync`.
- **Baseline:** `master` at the post-B2 tip — PR #33 squash-merged
  (`98eeac6`), plus the B3 spec/plan doc commits.
- **Per-task gate:** `.\gradlew.bat :app:assembleDebug` → `BUILD SUCCESSFUL`.
- **Final gate (Task 4):** full JVM test suite + lint, both equal to the
  post-B2 `master` baseline; invariant git-diff allowlist.
- Gradle runs on Windows via `.\gradlew.bat` from the repo root
  `g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android`.

## File Structure

No new files. No new classes. B3 modifies exactly three existing files, all in
`app/src/main/java/com/aritr/rova/service/dualrecord/internal/`:

| File | Responsibility after B3 |
|---|---|
| `FboRing.kt` | The depth-3 FBO ring **and** the per-slot GL fence registry. Gains `fences: LongArray`, `lastAdvancedSlot`, `recordFence(fence)`, and fence cleanup in `release()`. |
| `EglRouter.kt` | Callback-thread render. The blit now ends with `glFenceSync` + `glFlush` + `recordFence` instead of `glFinish`; the root EGL context is ES3. |
| `EncoderRenderThread.kt` | Per-encoder render thread. `EncoderFrame` carries the fence handle; `run()` issues `glWaitSync` before drawing; the encoder EGL context is ES3. |

`FrameMailbox.kt` and `FrameMailboxTest.kt` are **not** touched.
`EncoderFrame` (declared in `EncoderRenderThread.kt`) is constructed in exactly
one place — `EglRouter.renderFrame()` — so its constructor change ripples to
exactly one call site. Task 3 changes both files atomically (the compile gate
cannot pass between them).

---

### Task 1: Bump the DualShot EGL contexts ES2 → ES3

The root context (`EglRouter`) and the per-encoder contexts
(`EncoderRenderThread`) must be OpenGL ES 3.0 contexts before any `glFenceSync` /
`glWaitSync` call is valid. This is a constant change only — ES2 code runs
identically on an ES3 context (ES3 is a strict superset), so there is no
behaviour change yet.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt` (root-context `ctxAttribs`, ~line 305)
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt` (encoder-context `ctxAttribs`, ~line 139)

- [ ] **Step 1: Bump the root context to ES3 in `EglRouter.setup()`**

In `EglRouter.kt`, find this line (inside `setup()`):

```kotlin
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
```

Replace it with:

```kotlin
        // DualShot fence-sync (B3, 2026-05-21) — ES3 context. glFenceSync /
        // glWaitSync (the per-frame blit→encoder fence) are core OpenGL
        // ES 3.0. The EGL config above keeps EGL_OPENGL_ES2_BIT — an ES3
        // context is compatible with an ES2-bit config (ES3 is a strict
        // superset; the bit advertises a minimum). The GLSL ES 1.00
        // shaders compile unchanged on an ES3 context. See fence-sync §5.
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
```

Do **not** change the `attribs` array above it (the `eglChooseConfig`
attributes) — `EGL_RENDERABLE_TYPE` stays `EGL14.EGL_OPENGL_ES2_BIT`.

- [ ] **Step 2: Bump the encoder context to ES3 in `EncoderRenderThread.initEgl()`**

In `EncoderRenderThread.kt`, find this line (inside `initEgl()`'s `try` block):

```kotlin
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
```

Replace it with:

```kotlin
            // DualShot fence-sync (B3, 2026-05-21) — ES3 context, matching
            // the router root context. glWaitSync (the server-side wait on
            // the callback's blit fence) is core OpenGL ES 3.0. The shared
            // eglConfig is unchanged. See fence-sync design §5.
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
```

Leave the `// share_context = ...` comment immediately below it untouched.

- [ ] **Step 3: Gate — compile**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt
git commit -m "fix(dualrecord): bump DualShot EGL contexts to ES3 (B3 fence-sync)" -m "glFenceSync / glWaitSync are core OpenGL ES 3.0; the EGL contexts must be ES3 to call them. The EGL config keeps EGL_OPENGL_ES2_BIT (compatible with an ES3 context) and the GLSL ES 1.00 shaders are unchanged. No behaviour change yet — ES2 code runs identically on an ES3 context." -m "Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: `FboRing` per-slot GL fence tracking

`FboRing` already owns the depth-3 ring of FBO slots on the callback thread. B3
makes it also own one GL sync object per slot — the fence the callback records
after each blit and the encoders wait on. The ring owns the fence's lifecycle:
`recordFence` deletes the stale fence it replaces, `release()` deletes all.

No caller is wired in this task (`recordFence` stays unused until Task 3) — an
`internal` member with no caller compiles clean and is not a lint finding.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/FboRing.kt`

- [ ] **Step 1: Add the `GLES30` import**

In `FboRing.kt`, find:

```kotlin
import android.opengl.GLES20
import com.aritr.rova.utils.RovaLog
```

Replace with:

```kotlin
import android.opengl.GLES20
import android.opengl.GLES30
import com.aritr.rova.utils.RovaLog
```

- [ ] **Step 2: Add the `fences` array + `lastAdvancedSlot` field**

In `FboRing.kt`, find:

```kotlin
    private val slots = ArrayList<Slot>(RING_DEPTH)
    private var counter = 0
```

Replace with:

```kotlin
    private val slots = ArrayList<Slot>(RING_DEPTH)
    private var counter = 0

    // DualShot fence-sync (B3, 2026-05-21) — one GL sync object per slot,
    // index-aligned with `slots`. 0L = no fence recorded yet. The callback
    // thread records a fence after each blit (recordFence); the encoder
    // threads glWaitSync on it. The ring owns the handle's lifecycle — it
    // is deleted when the slot is recycled, or in release(). See the
    // 2026-05-21 fence-sync design doc §6.
    private val fences = LongArray(RING_DEPTH)

    // The slot index advance() most recently returned. recordFence stores
    // the new fence at this index. Set by advance() before it increments
    // `counter`; read by the paired recordFence() call. Safe because both
    // run on the single callback thread, once per frame, advance()-then-
    // recordFence() (the FboRing single-thread contract — see class KDoc).
    private var lastAdvancedSlot = 0
```

- [ ] **Step 3: Record the advanced slot index in `advance()`**

In `FboRing.kt`, find:

```kotlin
    /**
     * Advance to the next slot (round-robin over [RING_DEPTH]) and return
     * it. Only valid after a successful [init].
     */
    fun advance(): Slot {
        val slot = slots[counter]
        counter = (counter + 1) % RING_DEPTH
        return slot
    }
```

Replace with:

```kotlin
    /**
     * Advance to the next slot (round-robin over [RING_DEPTH]) and return
     * it. Only valid after a successful [init]. The caller must record
     * this frame's GL fence via [recordFence] before the next [advance]
     * (B3 fence-sync — design §6).
     */
    fun advance(): Slot {
        val slot = slots[counter]
        lastAdvancedSlot = counter
        counter = (counter + 1) % RING_DEPTH
        return slot
    }
```

- [ ] **Step 4: Add `recordFence(fence: Long)`**

In `FboRing.kt`, immediately **after** the `advance()` function (and before the
`release()` function), add:

```kotlin

    /**
     * Store [fence] — a GL sync object created right after the blit into
     * the slot [advance] just returned — and delete the fence previously
     * held for that slot. The previous fence is [RING_DEPTH] frames
     * (~100 ms at 30 fps) stale; the encoder finished waiting on it long
     * ago, and `glDeleteSync` on a sync with a pending wait is defined to
     * defer the delete — so the delete is always safe.
     *
     * [fence] may be 0 (a failed `glFenceSync`); 0 is stored verbatim and
     * the encoder skips the wait for that frame. MUST be called once,
     * immediately after [advance], on the GL-context (callback) thread.
     */
    fun recordFence(fence: Long) {
        val previous = fences[lastAdvancedSlot]
        if (previous != 0L) GLES30.glDeleteSync(previous)
        fences[lastAdvancedSlot] = fence
    }
```

- [ ] **Step 5: Delete fences in `release()`**

In `FboRing.kt`, find:

```kotlin
    /** Delete every framebuffer + texture. Runs on the GL-context thread. */
    fun release() {
        if (slots.isEmpty()) return
        val fbos = IntArray(slots.size) { slots[it].framebufferId }
        val texs = IntArray(slots.size) { slots[it].textureId }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(fbos.size, fbos, 0)
        GLES20.glDeleteTextures(texs.size, texs, 0)
        slots.clear()
    }
```

Replace with:

```kotlin
    /** Delete every framebuffer, texture, and GL fence. Runs on the GL-context thread. */
    fun release() {
        // DualShot fence-sync (B3) — delete the per-slot GL sync objects
        // first. A 0 entry (no fence recorded for that slot) is skipped.
        for (i in fences.indices) {
            if (fences[i] != 0L) {
                GLES30.glDeleteSync(fences[i])
                fences[i] = 0L
            }
        }
        if (slots.isEmpty()) return
        val fbos = IntArray(slots.size) { slots[it].framebufferId }
        val texs = IntArray(slots.size) { slots[it].textureId }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(fbos.size, fbos, 0)
        GLES20.glDeleteTextures(texs.size, texs, 0)
        slots.clear()
    }
```

- [ ] **Step 6: Gate — compile**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/FboRing.kt
git commit -m "fix(dualrecord): FboRing per-slot GL fence tracking (B3 fence-sync)" -m "FboRing gains a per-slot LongArray of GL sync handles. recordFence(fence) stores this frame's fence and glDeleteSync's the 3-frame-stale one it replaces; release() deletes all. No caller yet — wired in the next commit." -m "Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: Fence the OES→FBO blit, drop the callback `glFinish` (coupled)

This is the B3 fix. The callback thread inserts a GPU fence after the blit and
hands it to the encoders; each encoder issues a server-side `glWaitSync` before
sampling the FBO. The callback thread's `glFinish` is deleted.

**Coupled change — one atomic task.** `EncoderFrame`'s constructor gains a third
parameter (`fenceSync: Long`). `EncoderFrame` is constructed in exactly one
place — `EglRouter.renderFrame()`. The compile gate cannot pass between the
constructor change and the call-site change, so both files are edited before
Step 9's gate.

The encoder's own post-`drawFrame` `glFinish()` is **kept** — it is the
FBO-slot read-safety mechanism (design §8), orthogonal to the callback fence.
Do not touch it.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt` (`GLES30` import, `EncoderFrame`, class KDoc, `run()`)
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt` (`GLES30` import, perf KDoc comment, blit block)

- [ ] **Step 1: Add the `GLES30` import to `EncoderRenderThread.kt`**

Find:

```kotlin
import android.opengl.GLES20
import android.opengl.Matrix
```

Replace with:

```kotlin
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
```

- [ ] **Step 2: Add `fenceSync` to the `EncoderFrame` constructor + KDoc**

In `EncoderRenderThread.kt`, find:

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
 *  - [fenceSync] is the GL sync object the callback thread inserted right
 *    after the blit (B3 fence-sync). The encoder issues `glWaitSync` on
 *    it so the GPU orders the blit before this encoder's FBO sample —
 *    replacing B2's callback-thread `glFinish`. It is a read-only handle
 *    borrowed from the [FboRing], which owns the handle's lifecycle. 0
 *    means no fence (a failed `glFenceSync`) — the encoder then skips the
 *    wait. Both encoders share one handle (fence-sync design §6).
 */
internal class EncoderFrame(
    val fboTextureId: Int,
    val texMatrix: FloatArray,
    val fenceSync: Long,
)
```

- [ ] **Step 3: Update the `EncoderRenderThread` class KDoc per-frame-loop paragraph**

In `EncoderRenderThread.kt`, find this paragraph inside the class KDoc:

```kotlin
 * Per-frame loop: take the latest [EncoderFrame] from the [FrameMailbox]
 * (latest-wins → stale frames dropped for this side only), draw the
 * cropped quad sampling the frame's FBO `GL_TEXTURE_2D` snapshot,
 * `glFinish`, then `eglSwapBuffers`. There is no barrier — the callback
 * thread blitted the camera frame into an FBO slot and waits for no
 * encoder work, so a stalled `eglSwapBuffers` here freezes only this
 * side. See the 2026-05-21 FBO-ring design doc §3 / §6.
```

Replace with:

```kotlin
 * Per-frame loop: take the latest [EncoderFrame] from the [FrameMailbox]
 * (latest-wins → stale frames dropped for this side only), `glWaitSync`
 * on the frame's blit fence (B3 — a server-side GPU wait; the CPU
 * returns at once), draw the cropped quad sampling the frame's FBO
 * `GL_TEXTURE_2D` snapshot, `glFinish`, then `eglSwapBuffers`. There is
 * no barrier and no callback-thread `glFinish` — the callback thread
 * blitted the camera frame, inserted a GPU fence, and waits for no
 * encoder work, so a stalled `eglSwapBuffers` here freezes only this
 * side. See the 2026-05-21 fence-sync design doc §3 / §7.
```

- [ ] **Step 4: Issue `glWaitSync` in `run()` before `drawFrame`**

In `EncoderRenderThread.kt`, find the loop in `run()`:

```kotlin
        while (true) {
            val frame = mailbox.take() ?: break   // null = poisoned → shutdown
            try {
                drawFrame(frame.fboTextureId, frame.texMatrix)
            } catch (t: Throwable) {
                RovaLog.w("EglEncoder[$side] draw failed", t)
                failed = true
                break
            }
```

Replace with:

```kotlin
        while (true) {
            val frame = mailbox.take() ?: break   // null = poisoned → shutdown
            try {
                // DualShot fence-sync (B3) — server-side wait on the
                // callback thread's post-blit fence. glWaitSync queues a
                // GPU-side dependency (blit-before-sample) and returns on
                // the CPU immediately; it does NOT block this thread. A 0
                // fence (failed glFenceSync on the callback side) is
                // skipped — at worst one early/torn frame (design §7/§8).
                if (frame.fenceSync != 0L) {
                    GLES30.glWaitSync(frame.fenceSync, 0, GLES30.GL_TIMEOUT_IGNORED)
                }
                drawFrame(frame.fboTextureId, frame.texMatrix)
            } catch (t: Throwable) {
                RovaLog.w("EglEncoder[$side] draw failed", t)
                failed = true
                break
            }
```

Do not touch `drawFrame` itself — its signature and its trailing `glFinish()`
are unchanged.

- [ ] **Step 5: Add the `GLES30` import to `EglRouter.kt`**

Find:

```kotlin
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
```

Replace with:

```kotlin
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.Matrix
```

- [ ] **Step 6: Update the stale perf KDoc comment in `EglRouter.kt`**

Find (in the `perf*`-fields comment block, near line 173):

```kotlin
    // draw/swap. `blit` is the headline number — how long the OES→FBO
    // snapshot (one quad + glFinish) took.
```

Replace with:

```kotlin
    // draw/swap. `blit` is the headline number — how long the OES→FBO
    // snapshot (one quad + a GPU fence, no glFinish — B3) took.
```

- [ ] **Step 7: Replace the blit-block `glFinish` with the fence in `EglRouter.renderFrame()`**

In `EglRouter.kt`, find this block inside `renderFrame()` (the tail of the
`try` inside the `if (liveEncoders.isNotEmpty() && ring != null)` branch):

```kotlin
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                // glFinish — the blit pixels must be complete AND visible
                // to the encoder share-group contexts before they sample
                // the slot. Drains only the blit (one quad), ~1-2 ms.
                GLES20.glFinish()
                // One read-only texMatrix copy, shared across encoders —
                // they only sample it, never mutate.
                val frame = EncoderFrame(slot.textureId, texMatrix.copyOf())
                liveEncoders.forEach { it.submit(frame) }
```

Replace with:

```kotlin
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                // DualShot fence-sync (B3, 2026-05-21) — insert a GPU
                // fence after the blit instead of draining the GPU with
                // glFinish. glFenceSync + glFlush both return on the CPU
                // immediately; the fence signals on the GPU once the blit
                // completes. Each encoder glWaitSync's it, so the GPU —
                // not this callback thread — orders blit-before-sample.
                // The glFlush is MANDATORY: glWaitSync (unlike
                // glClientWaitSync) cannot self-flush, so without it an
                // encoder could wait on a fence command still stuck in
                // this context's queue. See fence-sync design §3 / §7.
                val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                GLES20.glFlush()
                // Hand the fence to the FboRing — it owns the handle's
                // lifecycle (deleted on slot recycle / in release). A 0
                // fence (glFenceSync failure) is stored verbatim; the
                // encoder skips the wait for that frame (design §6 / §8).
                ring.recordFence(fence)
                // One read-only texMatrix copy + the shared fence handle,
                // broadcast to both encoders — they only sample, never
                // mutate (design §6).
                val frame = EncoderFrame(slot.textureId, texMatrix.copyOf(), fence)
                liveEncoders.forEach { it.submit(frame) }
```

(`ring` is the non-null `FboRing` from `val ring = fboRing` earlier in the
branch; `recordFence` was added in Task 2.)

- [ ] **Step 8: Sanity-check the `EncoderFrame` call sites**

Run: `git grep -n "EncoderFrame"`
Expected: matches only in `EncoderRenderThread.kt` (the `class EncoderFrame`
declaration, the `FrameMailbox<EncoderFrame>` field type, the
`submit(frame: EncoderFrame)` parameter type) and `EglRouter.kt` (the one
`EncoderFrame(slot.textureId, texMatrix.copyOf(), fence)` construction). If any
**other** file constructs `EncoderFrame(...)`, that call site also needs the
third argument — stop and report it.

- [ ] **Step 9: Gate — compile**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt
git commit -m "fix(dualrecord): fence the OES->FBO blit, drop callback glFinish (B3)" -m "The callback thread inserts a GPU fence after the OES->FBO blit (glFenceSync + glFlush, both non-blocking) instead of glFinish, which drained the GPU and waited out encoder work on the shared GPU. Each EncoderRenderThread glWaitSync's the fence — a server-side wait — before sampling the FBO. EncoderFrame carries the fence handle. This is the B3 stutter fix: the callback thread no longer blocks on encoder GPU work." -m "Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: Full-suite gate + invariant verification

B3 is code-complete after Task 3. This task confirms B3 left the post-B2
`master` baseline unchanged and touched only the allowlisted files. It produces
**no commit** — it gates the branch before the final review.

**Files:** none modified.

- [ ] **Step 1: Compile gate**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Full JVM unit-test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. **0 failures, 0 errors.** The test + class counts
must equal the post-B2 `master` baseline — B3 adds and modifies no test files
(`FrameMailbox.kt` / `FrameMailboxTest.kt` are byte-identical). If the count
differs or any test fails, stop and report.

- [ ] **Step 3: Lint gate**

Run: `.\gradlew.bat :app:lintDebug`
Expected: `BUILD SUCCESSFUL`. Lint count equal to the post-B2 `master` baseline
(0 errors; no new warnings). `android.opengl.GLES30` is API 18, below
`minSdk 24`, so it raises **no** `InlinedApi` / `NewApi` finding — there must be
zero new lint hits on `FboRing.kt`, `EglRouter.kt`, or `EncoderRenderThread.kt`.

- [ ] **Step 4: Invariant git-diff allowlist**

Run: `git diff master..HEAD --name-only`
Expected — **exactly** these three paths and nothing else:

```
app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt
app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt
app/src/main/java/com/aritr/rova/service/dualrecord/internal/FboRing.kt
```

(The B3 spec + this plan are committed on `master` before the branch forks, so
they are not branch-exclusive and must not appear here.) If `FrameMailbox.kt`,
`FrameMailboxTest.kt`, `AspectFitMath*`, `RotationCalculator*`,
`DualMuxerStateMachine*`, `DualVideoRecorder*`, `setupSingleCamera`,
`WarningId*`, `WarningPrecedence*`, or any single-mode-pipeline file appears in
the diff, stop and report — that is an invariant breach.

- [ ] **Step 5: Report**

Summarise: `assembleDebug` result, test count + pass/fail, lint count, and the
git-diff file list. No commit. The subagent-driven-development controller then
runs the final whole-branch code review and `finishing-a-development-branch`
(open the PR).

---

## Owner Follow-Up (not an implementation task)

- **PR #34** (`chore/dualshot-blit-timer-split`, the diagnostic `blit` /
  `blitFinish` split-timer — open, unmerged): recommend **closing unmerged**.
  B3 removes the `glFinish` the `blitFinish` sub-timer was built to isolate, so
  it would measure nothing. The plain `blit` perf timer on `master` already
  meters B3's acceptance. Closing a PR is an owner action (`gh pr` is
  owner-gated).
- **On-device smoke (SM-A176B, owner):** the `EglRouter perf` log line.
  Acceptance, recording (`encoders=2`): `renderTotal` avg ≤ ~10 ms; `blit` avg
  ≤ ~3 ms (expect ~1-2 ms — the GPU-drain is gone); `interval` ~33 ms in good
  light; no 80-90 ms cascade; P+L files smooth on playback.
