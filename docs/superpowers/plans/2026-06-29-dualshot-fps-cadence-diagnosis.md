# DualShot fps-cadence Diagnosis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a debug-gated, observer-effect-safe instrumentation harness that localizes the DualShot ~18–20 fps plateau to a specific pipeline stage (capture/AE vs callback-thread overrun vs encoder backpressure), and a runbook + findings template to turn one on-device session into a verdict.

**Architecture:** Two pure-JVM helpers — `CadenceProbe` (per-thread preallocated `LongArray` ring; hot path is a single masked store) and `CadenceStats` (median/p95/min/max + reset-aware delta math) — are wired into the three hot threads via DEBUG-gated taps that reuse the existing `perfNow()` pattern. The GL/encoder/binding edits are timestamp captures and a stop-time dump only; no behavioral change to the render pipeline. The decisive signal is `SurfaceTexture.getTimestamp()` (camera HW cadence) vs `System.nanoTime()` wall-clock arrival. No fps fix is implemented — the deliverable is evidence + a verdict.

**Tech Stack:** Kotlin, CameraX 1.4.2 (`Camera2Interop`), EGL14/GLES30, JUnit4 (`org.junit.Assert`), Android `SurfaceTexture`/`TotalCaptureResult`.

## Global Constraints

- Diagnosis-only slice: **no fps fix** lands (no AE pin, no preview-swap move, no encoder/topology change). Out-of-scope list in spec §9 is binding.
- Probe is **purely additive** and **DEBUG-gated** (`if (BuildConfig.DEBUG)`), zero release-build cost, no behavioral change to the render pipeline.
- Observer-effect-safe: per-thread single writer, preallocated `LongArray`, power-of-two capacity + bitmask index; hot path does **raw `long` stores only** — no boxing, no locks, no `Atomic*`, no I/O, no `Log` call. Aggregation/format happen once at segment/record stop.
- Stability stack untouched: FBO depth-3 ring, fence-sync chain (`glFenceSync`+`glFlush`+`glWaitSync`), two-tier per-target locking, mailbox latest-wins frame-drop. ADR-0009 crop geometry untouched (no output-dim change).
- `getTimestamp()` vs `nanoTime()`: compare **same-clock deltas only**, never cross-clock absolutes. Guards: skip `ts <= 0`; on `cur <= prev` drop that delta (reset/dup/wrap); discard the first ~30 warm-up frames after bind.
- Steady-state window ≥ 300 frames over ≥ 2 segments; report **median/p95**, not mean. Ring sized to a power of two ≥ window (512). Probe reset per segment (no intra-segment wrap).
- Camera2 AE metadata (spec §5.3) is **best-effort enrichment, never a slice dependency**; wrap in try/catch, never perturb the binding. The slice still concludes on `getTimestamp` + bright/dim if interop is unreachable.
- All 46 static-check gates pass byte-identically; none added or edited. Verify via `:app:assembleDebug` (fires gates on preBuild) + `:app:testDebugUnitTest`, **not** `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated). Build WARM (no prophylactic cache wipe).
- Test baseline 1241/0-0-0; new pure-helper tests land in the same PR. GL/encoder/binding wiring follows the dualrecord "runtime EGL/GL layer — no unit tests" policy and is verified by `assembleDebug` + on-device.
- Branch: `perf/dualshot-fps-diagnosis` (already created; spec committed at `2119d1d`). No push/PR/merge without explicit owner GO; never push master directly.

---

### Task 1: `CadenceStats` pure helper

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/CadenceStats.kt`
- Test: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/CadenceStatsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `CadenceStats.Summary(count: Int, medianNs: Long, p95Ns: Long, minNs: Long, maxNs: Long)`
  - `CadenceStats.deltas(raw: LongArray, from: Int, count: Int): LongArray` — successive positive deltas, skipping non-increasing pairs.
  - `CadenceStats.summarize(values: LongArray): Summary` — median/p95/min/max over already-delta/duration values; empty → all-zero Summary.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CadenceStatsTest {

    @Test fun summarize_oddCount_medianIsMiddle() {
        val s = CadenceStats.summarize(longArrayOf(30, 10, 50, 20, 40))
        assertEquals(5, s.count)
        assertEquals(30, s.medianNs) // sorted 10,20,30,40,50 → index 2
        assertEquals(10, s.minNs)
        assertEquals(50, s.maxNs)
    }

    @Test fun summarize_evenCount_medianIsUpperMiddle() {
        // sorted 10,20,30,40 → size/2 = 2 → 30 (upper-middle, documented convention)
        val s = CadenceStats.summarize(longArrayOf(40, 10, 30, 20))
        assertEquals(30, s.medianNs)
    }

    @Test fun summarize_p95_nearestRank() {
        // 1..100 → p95 nearest-rank index = ceil(0.95*100)-1 = 94 → value 95
        val vals = LongArray(100) { (it + 1).toLong() }
        val s = CadenceStats.summarize(vals)
        assertEquals(95, s.p95Ns)
    }

    @Test fun summarize_empty_allZero() {
        val s = CadenceStats.summarize(LongArray(0))
        assertEquals(0, s.count)
        assertEquals(0, s.medianNs); assertEquals(0, s.p95Ns)
        assertEquals(0, s.minNs); assertEquals(0, s.maxNs)
    }

    @Test fun summarize_single() {
        val s = CadenceStats.summarize(longArrayOf(42))
        assertEquals(1, s.count); assertEquals(42, s.medianNs)
        assertEquals(42, s.p95Ns); assertEquals(42, s.minNs); assertEquals(42, s.maxNs)
    }

    @Test fun deltas_successivePositive() {
        val raw = longArrayOf(100, 133, 166, 216) // deltas 33,33,50
        assertArrayEquals(longArrayOf(33, 33, 50), CadenceStats.deltas(raw, 0, 4))
    }

    @Test fun deltas_skipsNonIncreasing_resetOrDup() {
        // 100→133 (33), 133→133 dup (skip), 133→90 reset (skip), 90→120 (30)
        val raw = longArrayOf(100, 133, 133, 90, 120)
        assertArrayEquals(longArrayOf(33, 30), CadenceStats.deltas(raw, 0, 5))
    }

    @Test fun deltas_honorsFromAndCount_warmupSkip() {
        // skip first 2 warm-up samples; window = [166,216,250] → deltas 50,34
        val raw = longArrayOf(0, 99, 166, 216, 250)
        assertArrayEquals(longArrayOf(50, 34), CadenceStats.deltas(raw, 2, 3))
    }

    @Test fun deltas_tooShort_empty() {
        assertArrayEquals(LongArray(0), CadenceStats.deltas(longArrayOf(5), 0, 1))
        assertArrayEquals(LongArray(0), CadenceStats.deltas(LongArray(0), 0, 0))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.CadenceStatsTest"`
Expected: FAIL — `CadenceStats` unresolved reference.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.service.dualrecord.internal

/**
 * DualShot fps-cadence diagnosis (2026-06-29) — pure statistics over raw
 * timestamp / duration samples gathered by [CadenceProbe]. Framework-free
 * so it is unit-tested directly (CadenceStatsTest), unlike the GL/encoder
 * threads that feed it. See the 2026-06-29 fps-cadence diagnosis spec §5.4.
 */
internal object CadenceStats {

    data class Summary(
        val count: Int,
        val medianNs: Long,
        val p95Ns: Long,
        val minNs: Long,
        val maxNs: Long,
    )

    private val EMPTY = Summary(0, 0L, 0L, 0L, 0L)

    /**
     * Successive positive deltas of [raw] over [count] entries starting at
     * [from]. A pair where `cur <= prev` (clock reset, duplicate, or ring
     * wrap) is skipped — the delta chain continues from `cur`. Fewer than
     * two entries → empty. Used for the camera HW-timestamp and wall-clock
     * arrival series (cadence). Service/duration series are already deltas
     * and go straight to [summarize].
     */
    fun deltas(raw: LongArray, from: Int, count: Int): LongArray {
        if (count <= 1) return LongArray(0)
        val out = ArrayList<Long>(count - 1)
        var prev = raw[from]
        for (i in (from + 1) until (from + count)) {
            val cur = raw[i]
            if (cur > prev) out.add(cur - prev)
            prev = cur
        }
        return out.toLongArray()
    }

    /**
     * Median / p95 / min / max over [values] (already deltas or durations).
     * Median = upper-middle element (`size/2` after sort). p95 = nearest-rank
     * (`ceil(0.95 * size) - 1`). Empty → all-zero [Summary]. Allocates (sorts
     * a copy) — call OFF the hot path.
     */
    fun summarize(values: LongArray): Summary {
        if (values.isEmpty()) return EMPTY
        val s = values.copyOf()
        s.sort()
        val median = s[s.size / 2]
        val p95Idx = (((95 * s.size + 99) / 100) - 1).coerceIn(0, s.size - 1)
        return Summary(s.size, median, s[p95Idx], s.first(), s.last())
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.CadenceStatsTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/CadenceStats.kt app/src/test/java/com/aritr/rova/service/dualrecord/internal/CadenceStatsTest.kt
git commit -m "feat(dualshot-diag): CadenceStats pure helper + tests"
```

---

### Task 2: `CadenceProbe` pure helper

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/CadenceProbe.kt`
- Test: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/CadenceProbeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `CadenceProbe(capacityPow2: Int = 512)` — throws `IllegalArgumentException` if capacity is not a positive power of two.
  - `record(value: Long)` — hot path: single masked store + index bump. No alloc, no lock.
  - `recorded(): Int` — total samples recorded (may exceed capacity if wrapped).
  - `snapshot(): LongArray` — valid samples oldest→newest, capped at capacity. Allocates; call off the hot path.
  - `reset()` — clear the write index (samples logically discarded).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.aritr.rova.service.dualrecord.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CadenceProbeTest {

    @Test fun rejectsNonPowerOfTwoCapacity() {
        assertThrows(IllegalArgumentException::class.java) { CadenceProbe(3) }
        assertThrows(IllegalArgumentException::class.java) { CadenceProbe(0) }
    }

    @Test fun recordThenSnapshot_inOrder_noWrap() {
        val p = CadenceProbe(4)
        p.record(10); p.record(20); p.record(30)
        assertEquals(3, p.recorded())
        assertArrayEquals(longArrayOf(10, 20, 30), p.snapshot())
    }

    @Test fun snapshot_afterWrap_returnsLastCapacityInOrder() {
        val p = CadenceProbe(4)
        for (v in longArrayOf(1, 2, 3, 4, 5, 6)) p.record(v)
        // capacity 4, wrote 6 → newest 4 are 3,4,5,6 oldest→newest
        assertEquals(6, p.recorded())
        assertArrayEquals(longArrayOf(3, 4, 5, 6), p.snapshot())
    }

    @Test fun snapshot_exactlyFull() {
        val p = CadenceProbe(4)
        for (v in longArrayOf(7, 8, 9, 10)) p.record(v)
        assertArrayEquals(longArrayOf(7, 8, 9, 10), p.snapshot())
    }

    @Test fun reset_clearsSamples() {
        val p = CadenceProbe(4)
        p.record(1); p.record(2)
        p.reset()
        assertEquals(0, p.recorded())
        assertArrayEquals(LongArray(0), p.snapshot())
        p.record(99)
        assertArrayEquals(longArrayOf(99), p.snapshot())
    }

    @Test fun emptySnapshot() {
        assertArrayEquals(LongArray(0), CadenceProbe(8).snapshot())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.CadenceProbeTest"`
Expected: FAIL — `CadenceProbe` unresolved reference.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.service.dualrecord.internal

/**
 * DualShot fps-cadence diagnosis (2026-06-29) — an observer-effect-safe,
 * single-writer sample ring. One instance per hot thread (the [EglRouter]
 * callback thread and each [EncoderRenderThread]); the per-frame [record]
 * is a single masked array store — no allocation, no lock, no I/O — so the
 * probe does not perturb the cadence it measures. Aggregation ([snapshot]
 * → [CadenceStats]) and formatting happen once at segment/record stop, off
 * the hot path. Pure JVM (no Android types) → unit-tested (CadenceProbeTest).
 * See the 2026-06-29 fps-cadence diagnosis spec §5.1. NOT thread-safe by
 * design: each probe has exactly one writer thread.
 */
internal class CadenceProbe(capacityPow2: Int = 512) {

    init {
        require(capacityPow2 > 0 && (capacityPow2 and (capacityPow2 - 1)) == 0) {
            "capacity must be a positive power of two, was $capacityPow2"
        }
    }

    private val mask = capacityPow2 - 1
    private val ring = LongArray(capacityPow2)
    private var writeIdx = 0

    /** Hot path — record one sample. Single masked store + index bump. */
    fun record(value: Long) {
        ring[writeIdx++ and mask] = value
    }

    /** Total samples recorded (may exceed capacity once the ring wrapped). */
    fun recorded(): Int = writeIdx

    /**
     * Valid samples in write order (oldest→newest), capped at capacity.
     * Allocates a fresh array — call OFF the hot path (segment stop).
     */
    fun snapshot(): LongArray {
        val cap = ring.size
        val n = if (writeIdx < cap) writeIdx else cap
        val out = LongArray(n)
        if (writeIdx <= cap) {
            System.arraycopy(ring, 0, out, 0, n)
        } else {
            val start = writeIdx and mask
            for (i in 0 until n) out[i] = ring[(start + i) and mask]
        }
        return out
    }

    /** Discard recorded samples (reset the write index). Call off the hot path. */
    fun reset() {
        writeIdx = 0
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.CadenceProbeTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/CadenceProbe.kt app/src/test/java/com/aritr/rova/service/dualrecord/internal/CadenceProbeTest.kt
git commit -m "feat(dualshot-diag): CadenceProbe observer-safe sample ring + tests"
```

---

### Task 3: `FrameMailbox` per-side overwrite (drop) counter

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/FrameMailbox.kt`
- Test: `app/src/test/java/com/aritr/rova/service/dualrecord/internal/FrameMailboxTest.kt` (existing — add cases)

**Interfaces:**
- Consumes: nothing.
- Produces: `FrameMailbox.overwriteCount(): Long` — number of unread frames discarded by `offer()` over the mailbox's life. Read at encoder shutdown.

- [ ] **Step 1: Write the failing tests** (append to existing `FrameMailboxTest`)

```kotlin
    @Test fun overwriteCount_countsDiscardedUnreadFrames() {
        val mb = FrameMailbox<String>()
        assertEquals(0L, mb.overwriteCount())
        mb.offer("a")
        mb.offer("b") // "a" unread → discarded
        mb.offer("c") // "b" unread → discarded
        assertEquals(2L, mb.overwriteCount())
    }

    @Test fun overwriteCount_consumedFrameIsNotAnOverwrite() {
        val mb = FrameMailbox<String>()
        mb.offer("a")
        assertEquals("a", mb.take()) // consumed, slot empty
        mb.offer("b")                // slot was empty → not an overwrite
        assertEquals(0L, mb.overwriteCount())
    }

    @Test fun overwriteCount_noIncrementAfterPoison() {
        val mb = FrameMailbox<String>()
        mb.poison()
        mb.offer("a") // no-op once poisoned
        assertEquals(0L, mb.overwriteCount())
    }
```

If `FrameMailboxTest` lacks the import, ensure `import org.junit.Assert.assertEquals` is present.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.FrameMailboxTest"`
Expected: FAIL — `overwriteCount` unresolved reference.

- [ ] **Step 3: Edit `FrameMailbox`**

Add the field after `private var poisoned = false` (line 23):

```kotlin
    private var overwrites = 0L
```

Replace the body of `offer` (lines 26–32) with:

```kotlin
    fun offer(item: T) {
        synchronized(lock) {
            if (poisoned) return
            if (slot != null) overwrites++ // an unread frame is discarded — per-side drop
            slot = item
            lock.notifyAll()
        }
    }
```

Add the accessor after `poison()`:

```kotlin
    /**
     * Diagnostic (2026-06-29 fps-cadence) — count of unread frames
     * discarded by [offer] (the per-side frame-drop). Read off the hot
     * path, e.g. at encoder shutdown.
     */
    fun overwriteCount(): Long = synchronized(lock) { overwrites }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.service.dualrecord.internal.FrameMailboxTest"`
Expected: PASS (existing cases + 3 new).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/FrameMailbox.kt app/src/test/java/com/aritr/rova/service/dualrecord/internal/FrameMailboxTest.kt
git commit -m "feat(dualshot-diag): FrameMailbox overwrite (drop) counter + tests"
```

---

### Task 4: Wire HW-timestamp + wall-arrival probes into the `EglRouter` callback thread

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt` (fields ~176–187; `renderFrame` 556–577; summary path 803–819)

GL-thread runtime layer — no unit test (dualrecord policy). Verified by `assembleDebug` + on-device. `BuildConfig` is already imported (line 18); `inputSurfaceTexture` is the camera `SurfaceTexture` (line 126); the callback thread is single-threaded so the probe needs no synchronization.

**Interfaces:**
- Consumes: `CadenceProbe` (Task 2), `CadenceStats` (Task 1).
- Produces: a DEBUG-only log line `EglRouter cadence [...]` with HW-timestamp and wall-arrival median/p95, emitted every `PERF_WINDOW` frames.

- [ ] **Step 1: Add the two probes as fields** (after line 187, alongside the existing `perf*` fields)

```kotlin
    // DualShot fps-cadence diagnosis (2026-06-29) — DEBUG-only cadence
    // rings. Single-writer (this callback thread), so no synchronization.
    //  - hwTsProbe: SurfaceTexture.getTimestamp() (camera HW cadence)
    //  - wallArrivalProbe: renderFrame() entry nanoTime (wall arrival)
    // Compared as same-clock deltas only (spec §4). 512 ≥ the ≥300-frame
    // steady-state window; reset every PERF_WINDOW emit (no intra-window wrap).
    private val hwTsProbe = CadenceProbe(512)
    private val wallArrivalProbe = CadenceProbe(512)
```

- [ ] **Step 2: Record both samples in `renderFrame`**

After line 577 (`val perfTexEndNs = perfNow()`), add:

```kotlin
        // fps-cadence diagnosis — record camera HW timestamp + wall arrival.
        // getTimestamp() is the camera frame's hardware clock (possibly a
        // different timebase than nanoTime — only same-clock deltas are
        // compared downstream). 0 is skipped by CadenceStats.deltas.
        if (BuildConfig.DEBUG) {
            hwTsProbe.record(tex.getTimestamp())
            wallArrivalProbe.record(perfEntryNs)
        }
```

- [ ] **Step 3: Emit cadence summary at the window boundary**

Inside `recordRenderPerf`, immediately before `perfFrames = 0` (line 815), add:

```kotlin
        if (BuildConfig.DEBUG) {
            val hw = CadenceStats.summarize(hwTsProbe.snapshot().let { CadenceStats.deltas(it, 0, it.size) })
            val wall = CadenceStats.summarize(wallArrivalProbe.snapshot().let { CadenceStats.deltas(it, 0, it.size) })
            fun fps(medNs: Long) = if (medNs > 0) String.format(java.util.Locale.US, "%.1f", 1_000_000_000.0 / medNs) else "n/a"
            RovaLog.d {
                "EglRouter cadence [${hw.count + 1}f]: " +
                    "cameraHW median=${ms(hw.medianNs)} p95=${ms(hw.p95Ns)} (~${fps(hw.medianNs)}fps) | " +
                    "wallArrival median=${ms(wall.medianNs)} p95=${ms(wall.p95Ns)} (~${fps(wall.medianNs)}fps) (ms)"
            }
            hwTsProbe.reset(); wallArrivalProbe.reset()
        }
```

Note: `ms(...)` is the local helper already defined at line 804 in `recordRenderPerf`; these lines must be placed after that `fun ms` declaration and before the field-reset block.

- [ ] **Step 4: Build to verify it compiles + gates pass**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; 46 `check*` gate tasks run on preBuild and pass; no behavior change.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt
git commit -m "feat(dualshot-diag): EglRouter camera-HW vs wall-arrival cadence probe"
```

---

### Task 5: Wire per-side service probes into `EncoderRenderThread`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt` (imports 1–16; fields ~99; `run` loop 121–155)

GL/encoder runtime layer — no unit test (dualrecord policy). Verified by `assembleDebug` + on-device. This thread is single-writer for its own probes.

**Interfaces:**
- Consumes: `CadenceProbe` (Task 2), `CadenceStats` (Task 1), `FrameMailbox.overwriteCount()` (Task 3), `BuildConfig`.
- Produces: a DEBUG-only `EglEncoder[$side] cadence` summary logged once at thread shutdown (consume cadence, GPU service `take→glFinish`, swap `glFinish→swap`, full `take→swap`, mailbox drops).

- [ ] **Step 1: Add the `BuildConfig` import**

After line 13 (`import com.aritr.rova.utils.RovaLog`), add:

```kotlin
import com.aritr.rova.BuildConfig
```

- [ ] **Step 2: Add per-side probe fields** (after line 99, `private var loggedOnce = false`)

```kotlin
    // DualShot fps-cadence diagnosis (2026-06-29) — DEBUG-only per-side
    // rings. Single-writer (this encoder thread). Dumped once at shutdown.
    private val consumeProbe = CadenceProbe(512)   // post-take timestamps → consume cadence
    private val serviceProbe = CadenceProbe(512)   // take→glFinish durations (GPU sample service)
    private val swapProbe = CadenceProbe(512)      // glFinish→swap durations (MediaCodec back-pressure)
    private val takeToSwapProbe = CadenceProbe(512) // full take→swap durations
```

- [ ] **Step 3: Tap the run loop**

Replace the loop body (lines 121–153) with the instrumented version — the only additions are DEBUG-gated `nanoTime()` reads and `record` calls; the draw/swap logic is byte-identical:

```kotlin
        while (true) {
            val frame = mailbox.take() ?: break   // null = poisoned → shutdown
            val tTake = if (BuildConfig.DEBUG) System.nanoTime() else 0L
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
            val tFinish = if (BuildConfig.DEBUG) System.nanoTime() else 0L
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
            if (BuildConfig.DEBUG) {
                val tSwap = System.nanoTime()
                consumeProbe.record(tTake)               // cadence = successive take deltas
                serviceProbe.record(tFinish - tTake)     // GPU sample service (incl glWaitSync+draw+glFinish)
                swapProbe.record(tSwap - tFinish)        // submit / MediaCodec back-pressure
                takeToSwapProbe.record(tSwap - tTake)    // full per-frame encoder service
            }
        }
        if (BuildConfig.DEBUG) dumpCadence()
```

- [ ] **Step 4: Add the shutdown dump method** (after the `run()` method, before `initEgl`)

```kotlin
    /**
     * DualShot fps-cadence diagnosis (2026-06-29) — emit this side's
     * cadence summary once, at thread shutdown (off the hot path). DEBUG
     * only. consume = successive post-take deltas; service/swap/total are
     * durations (already deltas). See spec §5.2.
     */
    private fun dumpCadence() {
        val consume = CadenceStats.summarize(
            consumeProbe.snapshot().let { CadenceStats.deltas(it, 0, it.size) }
        )
        val service = CadenceStats.summarize(serviceProbe.snapshot())
        val swap = CadenceStats.summarize(swapProbe.snapshot())
        val total = CadenceStats.summarize(takeToSwapProbe.snapshot())
        fun ms(ns: Long) = String.format(java.util.Locale.US, "%.1f", ns / 1_000_000.0)
        fun fps(medNs: Long) = if (medNs > 0) String.format(java.util.Locale.US, "%.1f", 1_000_000_000.0 / medNs) else "n/a"
        RovaLog.d {
            "EglEncoder[$side] cadence [${consume.count + 1}f]: " +
                "consume median=${ms(consume.medianNs)} p95=${ms(consume.p95Ns)} (~${fps(consume.medianNs)}fps) | " +
                "service(take→finish) median=${ms(service.medianNs)} p95=${ms(service.p95Ns)} | " +
                "swap(finish→swap) median=${ms(swap.medianNs)} p95=${ms(swap.p95Ns)} | " +
                "total(take→swap) median=${ms(total.medianNs)} p95=${ms(total.p95Ns)} | " +
                "mailboxDrops=${mailbox.overwriteCount()}"
        }
    }
```

- [ ] **Step 5: Build to verify it compiles + gates pass**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; 46 gates pass; draw/swap path byte-identical (only DEBUG-gated taps added).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/dualrecord/internal/EncoderRenderThread.kt
git commit -m "feat(dualshot-diag): EncoderRenderThread per-side service/swap/drop cadence probe"
```

---

### Task 6: Best-effort AE capture-metadata via `Camera2Interop`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` (preview build at 2190–2193)

Best-effort enrichment, **not** a slice dependency (Global Constraints). Framework layer — no unit test; verified by `assembleDebug` + on-device. Must never perturb the binding: the interop attach is wrapped in try/catch and the `Preview` still builds if it throws.

**Interfaces:**
- Consumes: `Camera2Interop.Extender`, `TotalCaptureResult`, `BuildConfig`.
- Produces: DEBUG-only periodic log `DualShot AE` with `SENSOR_EXPOSURE_TIME`, `SENSOR_FRAME_DURATION`, `CONTROL_AE_TARGET_FPS_RANGE`, `CONTROL_AE_STATE`.

- [ ] **Step 1: Replace the preview builder block (2190–2193)**

```kotlin
            val previewBuilder = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(android.view.Surface.ROTATION_0)
            // fps-cadence diagnosis (2026-06-29) — best-effort AE metadata.
            // DEBUG only; wrapped so a failure never blocks the binding.
            if (BuildConfig.DEBUG) {
                try {
                    attachAeMetadataProbe(previewBuilder)
                } catch (t: Throwable) {
                    RovaLog.w("DualShot AE probe attach failed (non-fatal)", t)
                }
            }
            preview = previewBuilder.build()
```

- [ ] **Step 2: Add the probe method** (private method on the service, near `setupDualCamera`)

```kotlin
    /**
     * DualShot fps-cadence diagnosis (2026-06-29) — best-effort AE capture
     * metadata. Attaches a Camera2 session capture callback to the dual
     * Preview and logs exposure / frame-duration / AE fps-range / AE-state
     * every ~60 frames. DEBUG only, best-effort (spec §5.3): the direct
     * AE verdict when reachable, never a dependency — the slice still
     * concludes on getTimestamp cadence + bright/dim if interop is absent.
     */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun attachAeMetadataProbe(builder: androidx.camera.core.Preview.Builder) {
        var frame = 0L
        androidx.camera.camera2.interop.Camera2Interop.Extender(builder).setSessionCaptureCallback(
            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: android.hardware.camera2.CaptureRequest,
                    result: android.hardware.camera2.TotalCaptureResult,
                ) {
                    if (frame++ % 60L != 0L) return
                    val expNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                    val durNs = result.get(android.hardware.camera2.CaptureResult.SENSOR_FRAME_DURATION)
                    val aeRange = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                    val aeState = result.get(android.hardware.camera2.CaptureResult.CONTROL_AE_STATE)
                    val expFps = if (expNs != null && expNs > 0) String.format(java.util.Locale.US, "%.1f", 1_000_000_000.0 / expNs) else "n/a"
                    val durFps = if (durNs != null && durNs > 0) String.format(java.util.Locale.US, "%.1f", 1_000_000_000.0 / durNs) else "n/a"
                    RovaLog.d {
                        "DualShot AE: exposure=${expNs}ns (~${expFps}fps ceiling) " +
                            "frameDuration=${durNs}ns (~${durFps}fps) " +
                            "aeTargetFpsRange=$aeRange aeState=$aeState"
                    }
                }
            }
        )
    }
```

- [ ] **Step 3: Build to verify it compiles + gates pass**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; 46 gates pass (notably `checkSetTargetRotationBoundaryOnly`, `checkPresetNoOrientation` — no `setTargetRotation` added, only the interop attach). If `androidx.camera.camera2.interop` does not resolve, STOP and report (the `camera-camera2` artifact should be present transitively via CameraX; do not add a dependency without owner sign-off — fall back to skipping Task 6, the slice does not depend on it).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt
git commit -m "feat(dualshot-diag): best-effort Camera2Interop AE metadata probe"
```

---

### Task 7: Measurement runbook + findings template

**Files:**
- Create: `docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-findings.md`

Documentation deliverable — the slice's verdict lives here. No code; no test. This task makes the harness usable and gives the verdict a home.

- [ ] **Step 1: Write the runbook + findings skeleton**

Create the file with this content:

````markdown
# DualShot fps-cadence — Findings & Runbook

**Harness:** `perf/dualshot-fps-diagnosis` (CadenceProbe/CadenceStats + DEBUG taps).
**Spec:** `docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-diagnosis-design.md`
**Device:** RZCYA1VBQ2H. Build: `./gradlew :app:assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Log filters (PowerShell)

```powershell
adb logcat -c
adb logcat -s RovaLog:* | Select-String "EglRouter cadence","EglEncoder","DualShot AE"
```

`EglRouter cadence` → cameraHW vs wallArrival median/p95 (the §4 discriminator).
`EglEncoder[PORTRAIT|LANDSCAPE] cadence` → consume / service / swap / total + mailboxDrops (logged at record stop).
`DualShot AE` → exposure ceiling / frameDuration / aeTargetFpsRange / aeState (best-effort).

## Test matrix (run each ≥2 segments, discard first ~30 warm-up frames)

| # | Light | Preview | AE pin | Purpose |
|---|-------|---------|--------|---------|
| 1 | Bright | Foreground | none | baseline + does cadence rise when bright? |
| 2 | Dim | Foreground | none | AE light-dependence |
| 3 | Bright | Headless | none | isolate callback-thread preview-swap overrun |
| 4 | Dim | Foreground | [30,30] one-off | direct AE test (probe-only, reverted) |

Capture merged-MP4 `stts` fps each run as the external cross-check (same method as #152).

## Discriminator (spec §4)

- cameraHW median ≈ 50ms AND wallArrival ≈ 50ms → **capture/AE-side**.
- cameraHW ≈ 33ms BUT wallArrival ≈ 50ms → **consumer-side** (callback overrun or encoder backpressure).
  - Then: encoder `total(take→swap)` ≈ 50ms with high mailboxDrops → **encoder backpressure**; else callback service time dominates → **callback overrun**.
- Report per-stage distributions; call out the dominant limiter AND any near-tie (compound cause), do not force one bucket.

## Findings (fill after measurement)

### Raw data per matrix cell
_(paste the cadence log lines)_

### Per-stage breakdown
_(camera HW · wall arrival · callback service · encoder consume/service/swap/total · mailbox drops)_

### Verdict
_(capture/AE-side · callback overrun · encoder backpressure · compound — with the evidence)_

### Candidate fix → follow-up spec
_(capture→AE_TARGET_FPS_RANGE floor w/ dim-light tradeoff; callback→move preview swap off hot path / headless-during-record; encoder→service-time reduction. Fix = separate brainstorm→spec→plan, gated on this verdict.)_
````

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-06-29-dualshot-fps-cadence-findings.md
git commit -m "docs(dualshot-diag): fps-cadence measurement runbook + findings template"
```

---

## Post-plan: gather evidence (controller, after Task 7)

Not a code task — the slice's actual output. After the harness builds clean:
1. `:app:assembleDebug` + install on RZCYA1VBQ2H.
2. Run the Task-7 matrix; collect the three log streams + `stts` fps per run.
3. Fill the findings doc; derive the verdict per the discriminator.
4. Present the verdict + candidate fix to the owner. The fix is a **separate** brainstorm→spec→plan cycle gated on this verdict (spec §7). Owner then decides whether to keep the probe (DEBUG-gated) on master or revert it (spec §5: removed before the fix PR).

---

## Self-review notes

- **Spec coverage:** §4 discriminator → Task 4 (HW vs wall) + Task 7 runbook. §5.1 observer-safe → Task 2. §5.2 five stages → Task 4 (camera HW, wall arrival, callback service via existing `renderTotal`) + Task 5 (consume, service, swap, total) + Task 3 (mailbox drops). §5.3 AE metadata → Task 6 (best-effort). §5.4 CadenceStats → Task 1. §6 matrix/protocol → Task 7. §7 verdict → Task 7 + post-plan. §8 constraints → Global Constraints. §9 out-of-scope → no fix task exists (correct).
- **Callback service time** (spec §5.2 stage 3) is already emitted by the existing `renderTotal avg/max` in `recordRenderPerf` — not re-implemented (DRY); the runbook reads it from the existing `EglRouter perf` line.
- **Type consistency:** `CadenceProbe.record/recorded/snapshot/reset` and `CadenceStats.deltas(raw,from,count)/summarize(values)/Summary` used identically in Tasks 4–5. `FrameMailbox.overwriteCount(): Long` defined in Task 3, consumed in Task 5.
- **No placeholders:** every code/edit step shows full code; every run step has an exact command + expected result.
