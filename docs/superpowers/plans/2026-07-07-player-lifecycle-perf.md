# Player lifecycle ownership — implementation plan (perf/player-lifecycle)

Date: 2026-07-07 · Branch: `perf/player-lifecycle` · Base: master `cc496041`
Status: owner-approved architecture (candidate B + Review Agent Required Fixes 1 & 2 + owner refinements)

## OUTCOME (post-implementation, 2026-07-07)

Landed as three commits: `b79aed77` (engine + ledger + VM rewire), `3b274a6b` (review round 2:
isOwner lease-guards), `ec7721ca` (device-driven pivot, below). Suite 2271/0-0-0, 48 gates GREEN.

**Device verification falsified instance reuse.** Rapid Library→Player→Library→Player wedged the
REUSED codec into black output on RZCYA1VBQ2H (3/3 repro, pixel-verified; `MediaCodec.setOutputSurface`
unreliability on this Exynos OMX decoder; wedge sticky across leases). Final shape — same states,
tokens, guards, ownership boundary — is **fresh ExoPlayer per lease on the warm shared playback
thread**, park() = cheap hygiene (~2 ms) + **release deferred 400 ms** onto the main handler (past
the pop transition; release must stay on the application/main thread). 3/3 rapid-renav renders
after the pivot. PARKED now holds only the warm thread — no codec, no surface, by construction.

**Measured (identical probe methodology, same tile, 10× loop, RZCYA1VBQ2H):**

| main-thread span | baseline (master) | branch (final) |
|---|---|---|
| back-nav clear/detach | 31 ms median / 61 ms max | **2.1 ms median / 2.5 ms max** |
| warm attach | 10.5 ms median | 8.8 ms median |
| cold first-entry attach | 146 ms | 117 ms |
| gfxinfo janky % / p90/p95/p99 | 13.38% / 53/81/350 ms | 12.96% / 53/77/350 ms (loop dominated by playback frames) |

Note: the briefed 210/450 ms did not reproduce at those magnitudes on this content (10 s clips);
the release stall measured 27–61 ms baseline. The mechanism is the same; the win is the nav-path
teardown dropping to ~2 ms and the codec teardown moving to an idle frame.

Re-verified on device: warm reuse + resume position, poster handoff on re-entry
(onRenderedFirstFrame refires), rapid-renav rendering, ON_STOP background pause with no
auto-resume (play icon on return), cold rebuild after process start. FLAG_SECURE/vault and
ADR-0037 paths untouched by design (review observation).

## Problem

ExoPlayer lifetime is coupled 1:1 to the player NavBackStackEntry. Every Library→Player pays a
full `ExoPlayer.Builder().build()` (~210 ms main-thread) and every Player→Library pays a
synchronous `release()` with playback-thread join (~450 ms main-thread, right under Media3's
500 ms release timeout). Root cause = ownership boundary, not ExoPlayer/Compose/nav.

## Non-goals (owner-locked)

- NO behavior change: play-on-open, resume seek, ON_STOP pause (no auto-resume), NO autoplay,
  FLAG_SECURE ref-count, poster handoff, ADR-0037 identity transport, `PlayerUriResolver` — all untouched.
- NO TextureView/PlayerView-inflation work this branch (separate, later, if measurement justifies).
- NO playback logic in the holder — ever. See ownership contract.
- NO Media3 version change (pinned 1.4.1).

## Ownership contract (owner-mandated, verbatim boundary)

**`PlayerEngine` (holder) owns lifecycle ONLY:**
- ExoPlayer lifetime (build once, destroy at end-of-reusability)
- shared playback `HandlerThread` (`setPlaybackLooper`)
- application-looper pin to main (`Builder.setLooper(Looper.getMainLooper())` — Review Fix 2)
- acquisition + ownership tokens + detach (Review Fix 1)
- parking hygiene at detach: pause → position snapshot → `clearVideoSurface()` → `stop()` → `clearMediaItems()`
- destruction

**`PlayerViewModel` keeps owning playback (everything else):**
- media item (`setMediaItem` with resolved URI), `prepare()`, `playWhenReady`
- listener registration/removal (its own `playerListener`)
- seeking, scrub session, speed, segment jumps
- resume read/seek + resume persist (via snapshot returned by detach)
- all feature logic and UiState

Rule: if a future change wants playback behavior in the engine, it is wrong — route it through the VM.

## Lifecycle states (owner refinement 2)

```
DESTROYED --acquire()--> ACTIVE --detach(ownerToken)--> PARKED --acquire()--> ACTIVE
                                                        PARKED --destroy()--> DESTROYED
                          ACTIVE --acquire() by newer token--> ACTIVE (takeover; old token stale)
                          ACTIVE --destroy()--> DESTROYED (park hygiene first)
```

- **ACTIVE** — one current owner token; player configured/driven by that owner's VM.
- **PARKED** — player alive but neutral (stopped, no media, no surface, no owner). Reusable.
- **DESTROYED** — player released, playback thread quit. Next `acquire()` rebuilds from scratch
  (correctness never depends on the cache being warm).

**Conditions under which the player ceases to be reusable** (the contract; framework-agnostic):
1. The app's UI is going away for real (host activity finishing).
2. The platform signals memory pressure while no owner is active.
3. The engine's player hits an unrecoverable internal error / its playback thread dies.

Implementation maps these onto Android callbacks (`MainActivity.onDestroy` + `isFinishing`,
`onTrimMemory` while PARKED) — but the callbacks are mapping details, not the contract.
`onTrimMemory` is NOT part of the architectural contract.

## Design

### New: `ui/screens/player/PlayerEngine.kt`

- App-scoped singleton reachable via `RovaApp.playerEngine` (lazy; safe because the builder pins
  both loopers explicitly — Fix 2 — but acquire() also `check(Looper.myLooper() == mainLooper)`).
- `acquire(): PlayerLease` — main-thread only. If DESTROYED → build player
  (`setPlaybackLooper(sharedThread.looper)`, `setLooper(main)`). If ACTIVE (stale owner still
  attached — Compose Nav does not order outgoing `ViewModelStore.clear()` vs incoming init;
  predictive back worsens it) → perform takeover: snapshot old owner's position, park, then hand
  the player to the new lease. Returns `PlayerLease(token, player)`.
- `detach(token): Long?` — main-thread only. If `token` is the current owner: snapshot
  `currentPosition`, park (hygiene above), state → PARKED, return snapshot. If `token` is stale:
  **no player mutation at all** (Fix 1); return the snapshot captured at takeover (once), so the
  stale VM can still persist an accurate resume position.
- `destroy()` — park if needed, `player.release()` (PARKED release is cheap-ish and off the
  interaction path by definition), quit playback thread, state → DESTROYED.
- Pure state machine extracted as **`PlayerEngineLedger`** (pure Kotlin, no android/media3 types):
  token generation counter, current owner, state enum, pending takeover-snapshot per stale token.
  The engine is a thin main-thread wrapper executing the ledger's decisions on the real player.
  (House pure-helper pattern; JVM-testable under `isReturnDefaultValues`.)

### Changed: `PlayerViewModel.kt`

- Constructor gains an engine seam (default = real engine from `RovaApp`; tests pass a fake) —
  same seam style as `loadManifest`/`readResume`.
- `attachExoPlayer(uri, startMs)` → `val lease = engine.acquire()`; then exactly today's config
  on `lease.player`: `setMediaItem`, `addListener`, seek, `playWhenReady = true`, `prepare()`.
  `_firstFrameRendered.value = false` reset unchanged.
- `onCleared()` → `removeListener` (VM owns its listener), `val snapshot = engine.detach(token)`,
  persist `snapshot` (falls back to nothing if null — same as today's null-player path), stop polling.
  **No `release()`.** `persistPosition()` on pause/background paths unchanged (player still owned then).
- Cancellation edge: VM cleared before the init coroutine reaches `attachExoPlayer` → no lease was
  taken, `detach` never called with a live token, engine state untouched. If cleared between
  acquire and the `_uiState` write — onCleared runs on main after the init coroutine's main-thread
  segment completes or is cancelled; detach(token) handles both (owner or stale → safe).

### Changed: `MainActivity.kt`

- `onDestroy()`: `if (isFinishing) app.playerEngine.destroy()`.
- `onTrimMemory` mapping: RovaApp `onTrimMemory` → `playerEngine.destroyIfParked()` (implementation
  mapping of contract condition 2).

### Unchanged

`PlayerScreen.kt` (`getOrCreatePlayer()` stays a cached-reference read — recomposition-cheap),
`MainScreen.kt` routes, `PlayerUriResolver`, `RecordingIdentity`/`ResumePolicy`, resume write
pipeline (`sidecarWriteScope`), FLAG_SECURE, ON_STOP observer.

## TDD plan (tests first, RED→GREEN per unit)

1. **`PlayerEngineLedgerTest`** (new, pure):
   - acquire from DESTROYED → build decision + ACTIVE
   - detach(owner) → PARKED + snapshot request
   - detach(stale) → NO player-mutation decision; returns takeover snapshot exactly once, null after
   - acquire during ACTIVE → takeover: park-old + snapshot-stashed-for-old-token + new owner
   - destroy from each state; acquire after destroy → rebuild
   - rapid A→B→A token sequences (predictive-back shape) never let a stale token mutate
2. **`PlayerViewModelResumeTest`** extension + new `PlayerViewModelLifecycleTest`:
   - onCleared calls `detach(ownToken)` and persists the returned snapshot via `writeResume`
   - onCleared never calls release on the leased player (fake engine asserts)
   - VM cleared pre-attach → no acquire leak / no detach with unminted token
   - two VMs overlapping (old cleared after new init) → old persists takeover snapshot, new playback untouched
3. **Regression:** full suite `--max-workers=2` (baseline 2258/0-0-0) + 48 gates via `assembleDebug`/`lintDebug`.

## Measurement (owner refinement 4 — identical methodology before/after)

Probe patch (debug-only `RovaLog` + `android.os.Trace` sections around: engine/VM attach span,
onCleared span) applied identically to (a) master baseline build and (b) branch build. Not merged;
lives as a single commit reverted before PR, or guarded local patch — decision at measure time,
whichever keeps the PR clean.

Protocol on RZCYA1VBQ2H, per build:
1. Cold start, open Library, let it settle.
2. Scripted loop ×10: tap same tile → wait first frame → back. Same session, same media.
3. Collect: (i) attach span ms and clear/detach span ms from logcat probes (median + max of 10);
   (ii) `adb shell dumpsys gfxinfo com.aritr.rova framestats` reset before loop, read after —
   janky-frame %, p90/p95/p99 frame times; (iii) subjective responsiveness note (predictive back).
4. Also measure the *first* entry after process start on branch (cold engine — expected ≈ baseline)
   vs subsequent entries (warm — expected ≈ 0 build cost) — report both, no silent best-case-only.

Acceptance: warm attach ≪ 210 ms baseline; back-nav main-thread block ≪ 450 ms baseline;
jank % during loop strictly improved; NO regression in cold first-entry.

## Device verification checklist (RZCYA1VBQ2H)

1. Single session play → back → replay (warm reuse; resume position correct).
2. DualShot P then L rapid switch (VM identity change; takeover path).
3. Kept-raw segment (ADR-0037 V2) play + per-clip resume slot intact.
4. Vault playback: FLAG_SECURE on, no frame of previous session's video ever visible after takeover.
5. Predictive back gesture Player→Library, repeated fast (Review recommendation 5).
6. Background (Home) during playback → pause, no auto-resume on return.
7. Poster handoff still masks shutter on entry — incl. 2nd+ entry (reused player must re-fire
   `onRenderedFirstFrame` for the new media; explicit check, known risk).
8. Park + browse Library several minutes → `dumpsys media.codec` / meminfo: parked player holds no
   codec (Review recommendation 6).
9. Process-death entry (deep-link shape): engine DESTROYED → rebuild path works.
10. Playback error path (corrupt file if reproducible) → Unavailable UI, engine not wedged.

## Delivery sequence

1. Ledger (test-first) → 2. Engine wrapper → 3. VM seam + rewire (test-first) → 4. Activity mapping
→ 5. Full suite + gates → 6. Baseline measurement on master, branch measurement → 7. Independent
Review Agent (falsify) → 8. Reconcile → 9. Device verification → 10. Docs/stewardship (this plan
updated with measured numbers; memory entry; no ADR change needed — no behavioral contract touched;
add a "player engine" note to CLAUDE.md architecture section at merge).
