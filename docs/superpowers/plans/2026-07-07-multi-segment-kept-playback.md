# MULTI_SEGMENT_KEPT Playback (ADR-0037) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make kept-raw (`MULTI_SEGMENT_KEPT`) Library rows playable by implementing the ADR-0037 playback identity contract: `(sessionId, side?, segmentIndex?)` with mutually exclusive coordinates, resolver-owned validity, injective resume slots.

**Architecture:** Extend the existing identity tuple end-to-end — Library mints `segmentIndex` (it already has it on `VideoItem`), nav route transports it, `PlayerUriResolver` resolves it (new kept-raw branch, fail-closed validity matrix V1–V5), `RecordingIdentity.slotFor` derives per-segment resume slots with exact-match lookup. No new abstractions; merged-artifact path (V1) stays byte-identical.

**Tech Stack:** Kotlin, Compose Navigation, ExoPlayer (Media3 1.4.1), JVM unit tests (JUnit4, `isReturnDefaultValues=true`), FileProvider.

**Spec:** `docs/adr/0037-playback-identity-contract.md` (Accepted 2026-07-07). Read it before starting.

## Global Constraints

- Branch: `fix/multi-segment-kept-playback` (already created; ADR committed `a383e916`).
- JVM unit tests only; no Robolectric. Resolver/identity helpers stay pure (no `android.net.Uri`, no `File` I/O in resolver).
- V1 (merged FINALIZED) behavior must be **byte-identical** — the three existing `PlayerUriResolver*Test` suites must pass unchanged.
- No new user-facing strings (reuse `player_unavailable_*`; `checkNoHardcodedUiStrings` gate).
- Read-only resolution: no file deletion, no terminal/ExportState writes (`checkRecoveryNoDeletion`, `checkCompletedWriteOnlyFromPerformMerge` postures).
- No sidecar migration: existing resume slots (`""`, `"PORTRAIT"`, `"LANDSCAPE"`) keep working unchanged.
- Suite baseline on master: 2241 tests / 0 failures. Full verify: `./gradlew :app:testDebugUnitTest` then `./gradlew :app:assembleDebug` (fires all 48 gates on preBuild). `lintDebug` is RED on a pre-existing `VaultAndroidOps:267 NewApi` — use `assembleDebug` for gate verification.
- Commit per task, message style `feat(player): …` / `test(player): …`; end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: `RecordingIdentity.slotFor` — resume-slot derivation

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/RecordingIdentity.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/RecordingIdentityTest.kt` (exists — append)

**Interfaces:**
- Consumes: existing `RecordingIdentity.sideSlot(side: VideoSide?): String`.
- Produces: `RecordingIdentity.slotFor(side: VideoSide?, segmentIndex: Int?): String` — Tasks 2, 4, 5 call this. `segmentIndex == null` → `sideSlot(side)`; else `"#seg$segmentIndex"` (side ignored per ADR-0037 §1 exclusivity).

- [ ] **Step 1: Write the failing tests** (append to `RecordingIdentityTest.kt`; note: backtick test names must not contain `.`):

```kotlin
    // ADR-0037 §4 — slotFor is the ONLY resume-slot composer.
    @Test
    fun `slotFor without segmentIndex equals legacy sideSlot`() {
        assertEquals("", RecordingIdentity.slotFor(null, null))
        assertEquals("PORTRAIT", RecordingIdentity.slotFor(VideoSide.PORTRAIT, null))
        assertEquals("LANDSCAPE", RecordingIdentity.slotFor(VideoSide.LANDSCAPE, null))
    }

    @Test
    fun `slotFor with segmentIndex ignores side and is injective per index`() {
        assertEquals("#seg0", RecordingIdentity.slotFor(null, 0))
        assertEquals("#seg3", RecordingIdentity.slotFor(null, 3))
        // ADR-0037 §1 — coordinates mutually exclusive; a side passed alongside an
        // index must not change the slot (the identity is malformed upstream, V4b,
        // but the slot function stays total and side-blind).
        assertEquals("#seg3", RecordingIdentity.slotFor(VideoSide.PORTRAIT, 3))
        // Distinct indices → distinct slots; no collision with legacy values.
        assertNotEquals(RecordingIdentity.slotFor(null, 1), RecordingIdentity.slotFor(null, 2))
        assertNotEquals("", RecordingIdentity.slotFor(null, 0))
        assertNotEquals("PORTRAIT", RecordingIdentity.slotFor(VideoSide.PORTRAIT, 0))
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.RecordingIdentityTest"`
Expected: FAIL — `Unresolved reference: slotFor` (compile error counts as the failing state).

- [ ] **Step 3: Implement** (add to `RecordingIdentity` object, below `sideSlot`):

```kotlin
    /**
     * ADR-0037 §4 — resume slot for a playback identity. The single slot
     * composer: no caller builds slot strings by hand. Merged artifacts
     * (segmentIndex == null) keep the legacy [sideSlot] byte-identically.
     * Kept-raw segment artifacts get "#seg<index>" — side-blind, because
     * segmentIndex indexes the FULL interleaved segments array and is
     * already unique across DualShot sides (ADR-0037 §1, coordinates
     * mutually exclusive). '#' cannot occur in a VideoSide name, so no
     * legacy slot can collide with a segment slot.
     */
    fun slotFor(side: VideoSide?, segmentIndex: Int?): String =
        if (segmentIndex == null) sideSlot(side) else "#seg$segmentIndex"
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.RecordingIdentityTest"`
Expected: PASS (all, including pre-existing).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/RecordingIdentity.kt app/src/test/java/com/aritr/rova/ui/library/RecordingIdentityTest.kt
git commit -m "feat(library): RecordingIdentity.slotFor — ADR-0037 resume-slot derivation"
```

---

### Task 2: `LibraryMetadataEntry.positionFor` — exact-match for segment slots

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataEntry.kt:17-19`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataEntryTest.kt` (exists — append)

**Interfaces:**
- Consumes: slot strings produced by `RecordingIdentity.slotFor` (Task 1): `""`, side names, `"#seg<N>"`.
- Produces: `positionFor(slot)` where segment slots (`startsWith("#seg")`) resolve by exact match only — **no** `""`-fallback. Legacy non-empty slots (P+L sides) keep the fallback.

- [ ] **Step 1: Write the failing test** (append to `LibraryMetadataEntryTest.kt`):

```kotlin
    // ADR-0037 §4 (codex BLOCKING finding 2026-07-07) — a kept-raw segment slot
    // must NEVER inherit the session-level "" position: that is exactly the
    // cross-artifact resume bleed the contract forbids.
    @Test
    fun `positionFor segment slot is exact match with no empty-slot fallback`() {
        val entry = LibraryMetadataEntry(positionsBySide = mapOf("" to 5_000L, "#seg2" to 9_000L))
        assertEquals(9_000L, entry.positionFor("#seg2"))
        assertNull(entry.positionFor("#seg0"))          // absent means absent
        // Legacy P+L grace fallback is preserved for side slots:
        assertEquals(5_000L, entry.positionFor("PORTRAIT"))
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryMetadataEntryTest"`
Expected: FAIL — `entry.positionFor("#seg0")` returns 5000 (fallback fires), assertNull fails.

- [ ] **Step 3: Implement** — replace `positionFor` in `LibraryMetadataEntry.kt`:

```kotlin
    /**
     * Saved position for a slot. A P+L side with no own value falls back to the
     * single "" slot (pre-seam grace). Segment slots ("#seg…", ADR-0037 §4) are
     * EXACT MATCH ONLY — falling a kept-raw clip back to the session position
     * would bleed resume state across distinct playable artifacts.
     */
    fun positionFor(slot: String): Long? = when {
        slot.startsWith("#seg") -> positionsBySide[slot]
        slot.isNotEmpty() -> positionsBySide[slot] ?: positionsBySide[""]
        else -> positionsBySide[slot]
    }
```

- [ ] **Step 4: Run to verify pass** — same command, expected PASS (all).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryMetadataEntry.kt app/src/test/java/com/aritr/rova/ui/library/LibraryMetadataEntryTest.kt
git commit -m "feat(library): exact-match positionFor on segment slots (ADR-0037 no-bleed)"
```

---

### Task 3: `PlayerUriResolver` — validity matrix + kept-raw resolution

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUriResolver.kt`
- Test: Create `app/src/test/java/com/aritr/rova/ui/screens/player/PlayerUriResolverKeptRawTest.kt`

**Interfaces:**
- Consumes: `SessionManifest` (fields: `terminated: Terminated?`, `exportState`, `segments: List<SegmentRecord>` where `SegmentRecord(filename, durationMs, sizeBytes, sha1, side, …, startedAtWallClock: Long?)`, `startedAt`, `config.durationSeconds`, `sessionId`).
- Produces:
  - `resolve(manifest: SessionManifest?, side: VideoSide? = null, segmentIndex: Int? = null): PlayerUiState` — Task 4 calls with the third arg.
  - `const val KEPT_SEGMENT_SCHEME = "keptsegment://"` — sentinel URI `keptsegment://<filename>` (bare segment filename; the Android wrapper joins it with `sessionStore.sessionDir(sessionId)` and round-trips through FileProvider, same pattern as `VAULT_FILE_SCHEME`).

**Design (ADR-0037 §5):** `segmentIndex != null` dispatches to the kept-raw branch BEFORE the FINALIZED gate; `segmentIndex == null` follows today's path untouched. Strings: V4/V4b → `player_unavailable_not_available`; V5 (out of range) → `player_unavailable_file_not_found`. No new strings.

- [ ] **Step 1: Write the failing tests.** Create `PlayerUriResolverKeptRawTest.kt`. Copy the manifest-builder helper pattern from the top of the existing `PlayerUriResolverTest.kt` (read it first; reuse its builder if it is top-level, otherwise replicate minimally). Cover:

```kotlin
package com.aritr.rova.ui.screens.player

// imports mirroring PlayerUriResolverTest.kt

/**
 * ADR-0037 §5 validity matrix — kept-raw (V2) + rejections (V4/V4b/V5).
 * V1/V3 regression is the three pre-existing PlayerUriResolver*Test suites,
 * which must pass unchanged.
 */
class PlayerUriResolverKeptRawTest {

    // V2: user-keep writer path (exportState = NOT_STARTED)
    @Test fun `kept-raw segment resolves Ready with sentinel scheme and single-clip timeline`() {
        val m = keptRawManifest(exportState = ExportState.NOT_STARTED,
            segments = listOf(seg("segment_0000.mp4", 30_000L), seg("segment_0001.mp4", 31_000L)))
        val r = PlayerUriResolver.resolve(m, side = null, segmentIndex = 1) as PlayerUiState.Ready
        assertEquals(PlayerUriResolver.KEPT_SEGMENT_SCHEME + "segment_0001.mp4", r.mediaUri)
        assertEquals(1, r.totalClips)
        assertEquals(listOf(31_000L), r.segmentDurationsMs)
        assertEquals(31_000L, r.totalDurationFromSegmentsMs)
    }

    // V2: classifier writer path (exportState = FAILED) must also resolve
    @Test fun `kept-raw resolves Ready when exportState is FAILED`() { /* same shape, FAILED */ }

    // V2 wall-clock: exact stamp when present; synthesized (approx) when null
    @Test fun `kept-raw wall start is the segment's own stamp exact`() {
        // seg with startedAtWallClock = 1_700_000_000_000L →
        // r.segmentWallStartsMs == listOf(1_700_000_000_000L); wallStartIsApproxMask == listOf(false)
    }
    @Test fun `kept-raw wall start synthesizes from manifest startedAt when stamp missing`() {
        // ADR-0032 convention: index i with no stamp → manifest.startedAt + sum(durations of segments BEFORE i
        // in the same array order); approx mask true.
    }

    // V2: DualShot kept-raw — index into the FULL interleaved array; record's own side irrelevant to input
    @Test fun `kept-raw DualShot segment resolves by full-array index with side null`() {
        val m = keptRawManifest(topology = "DualShot", segments = listOf(
            seg("segment_0000_P.mp4", 30_000L, side = VideoSide.PORTRAIT),
            seg("segment_0000_L.mp4", 30_000L, side = VideoSide.LANDSCAPE)))
        val r = PlayerUriResolver.resolve(m, side = null, segmentIndex = 1) as PlayerUiState.Ready
        assertEquals(PlayerUriResolver.KEPT_SEGMENT_SCHEME + "segment_0000_L.mp4", r.mediaUri)
    }

    // V4: segmentIndex against a non-KEPT manifest (incl. FINALIZED) → invalid identity
    @Test fun `segmentIndex on FINALIZED session is rejected`() {
        // finalized single-mode manifest + segmentIndex = 0 →
        // Unavailable with UiText.Str(R.string.player_unavailable_not_available)
    }

    // V4b: both coordinates → malformed
    @Test fun `side plus segmentIndex is rejected as malformed`() {
        // kept-raw manifest, side = PORTRAIT, segmentIndex = 0 → Unavailable(not_available)
    }

    // V5: out-of-range index → fail closed
    @Test fun `out-of-range segmentIndex is unavailable`() {
        // kept-raw manifest with 2 segments, segmentIndex = 5 → Unavailable(file_not_found)
        // and segmentIndex = -1 → Unavailable(file_not_found)
    }

    // V3 unchanged: KEPT session WITHOUT segmentIndex still refuses (merged-identity shape)
    @Test fun `kept-raw manifest without segmentIndex stays not-finished`() {
        // resolve(m, null, null) → Unavailable(player_unavailable_not_finished)
    }
}
```

Write every body fully (the comments above give expected values); use the same assertion helpers as the existing suites (`PlayerUiState.Unavailable` carries `UiText.Str(resId)` — assert on the resId as the existing tests do).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.PlayerUriResolverKeptRawTest"`
Expected: FAIL — no `segmentIndex` parameter / no `KEPT_SEGMENT_SCHEME`.

- [ ] **Step 3: Implement.** In `PlayerUriResolver.kt`:

Add the constant next to `VAULT_FILE_SCHEME`:

```kotlin
    /**
     * ADR-0037 §5 V2 — sentinel scheme for a kept-raw segment artifact:
     * `keptsegment://<bare segment filename>`. The resolver stays pure (it
     * cannot know the app-private session directory); the Android wrapper
     * ([PlayerViewModel.resolvePlaybackUri]) joins the filename with
     * `sessionStore.sessionDir(sessionId)` and round-trips through
     * FileProvider — same mechanism and reason as [VAULT_FILE_SCHEME]
     * (app-private path ⇒ FileUriExposedException on raw file://).
     */
    const val KEPT_SEGMENT_SCHEME = "keptsegment://"
```

Change the signature and insert the dispatch immediately after the null-manifest guard (BEFORE the `exportState` gate at current line 95):

```kotlin
    fun resolve(
        manifest: SessionManifest?,
        side: VideoSide? = null,
        segmentIndex: Int? = null
    ): PlayerUiState {
        if (manifest == null) {
            return PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_not_available))
        }
        // ADR-0037 §5 — kept-raw segment identity dispatch. segmentIndex != null
        // names a kept-raw segment artifact and never consults exportState (which
        // describes only the merged artifact kind). Fail closed on every
        // malformed / mismatched shape: V4 (non-KEPT manifest — a FINALIZED
        // session's segment files are merge inputs the cleanup path may delete),
        // V4b (side + segmentIndex — coordinates are mutually exclusive, §1),
        // V5 (index out of range).
        if (segmentIndex != null) {
            if (side != null) {
                return PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_not_available)) // V4b
            }
            if (manifest.terminated != Terminated.MULTI_SEGMENT_KEPT) {
                return PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_not_available)) // V4
            }
            val seg = manifest.segments.getOrNull(segmentIndex)
                ?: return PlayerUiState.Unavailable(UiText.Str(R.string.player_unavailable_file_not_found)) // V5
            // Single-clip timeline (ADR-0037 §5): the artifact IS one clip.
            val stamp = seg.startedAtWallClock
            val wallStart = stamp
                ?: (manifest.startedAt +
                    manifest.segments.take(segmentIndex).sumOf { it.durationMs })
            return PlayerUiState.Ready(
                mediaUri = KEPT_SEGMENT_SCHEME + seg.filename,
                sessionId = manifest.sessionId,
                startedAt = manifest.startedAt,
                segmentDurationsMs = listOf(seg.durationMs),
                perClipDurationMs = manifest.config.durationSeconds * 1000L,
                totalClips = 1,
                totalDurationFromSegmentsMs = seg.durationMs,
                segmentWallStartsMs = listOf(wallStart),
                wallStartIsApproxMask = listOf(stamp == null)
            )
        }
        if (manifest.exportState != ExportState.FINALIZED) {
            // …everything from here down UNCHANGED (V1/V3) …
```

Add `import com.aritr.rova.data.Terminated`. Update the class KDoc paragraph that currently reads "The screen never plays a non-finalized session … The resolver re-asserts that gate" — replace with a pointer to ADR-0037: the resolver owns the full validity matrix; the FINALIZED gate applies to the merged artifact kind only.

- [ ] **Step 4: Run new + regression suites**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.*"`
Expected: PASS — new suite green, `PlayerUriResolverTest` / `PlayerUriResolverVaultTest` / `PlayerUriResolverWallClockTest` untouched and green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/PlayerUriResolver.kt app/src/test/java/com/aritr/rova/ui/screens/player/PlayerUriResolverKeptRawTest.kt
git commit -m "feat(player): ADR-0037 validity matrix — kept-raw segment resolution (V2), fail-closed V4/V4b/V5"
```

---

### Task 4: Identity plumbing — route → screen → ViewModel → resume seam

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/RovaApp.kt:153-183` (`readResumePosition` / `writeResumePosition`)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerViewModel.kt` (ctor `:58-59`, init `:162,169`, `resolvePlaybackUri:237`, `persistPosition:249`, `factory:477-500`)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/PlayerScreen.kt:101-120`
- Modify: `app/src/main/java/com/aritr/rova/ui/MainScreen.kt:196-268` (Library `onOpenPlayer` lambda + player route)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt:134,300-311` (`onOpenPlayer` signature + `play()`)
- Test: none new (framework plumbing; behavior covered by Task 3 resolver tests + Task 6 journey test + device verification). `VaultScreen` stays 2-arg — vault never lists kept-raw rows.

**Interfaces:**
- Consumes: `RecordingIdentity.slotFor` (Task 1), `PlayerUriResolver.resolve(m, side, segmentIndex)` + `KEPT_SEGMENT_SCHEME` (Task 3), `VideoItem.segmentIndex` (exists, `HistoryViewModel.kt:134`).
- Produces: end-to-end transport of `segmentIndex` — the Library is the only minting site (ADR-0037 §3); every hop passes it verbatim.

- [ ] **Step 1: `RovaApp` resume seam** — add `segmentIndex: Int? = null` as the LAST parameter of both functions; replace both `sideSlot(side)` calls with `RecordingIdentity.slotFor(side, segmentIndex)` (fully-qualified as the file already does). KDoc: "slot derivation delegated to RecordingIdentity.slotFor (ADR-0037 §3 — no hand-built slots)."

- [ ] **Step 2: `PlayerViewModel`** —
  - ctor: add `private val segmentIndex: Int? = null` after `side`; widen the lambda types to `readResume: suspend (String, VideoSide?, Int?) -> Long?` and `writeResume: (String, VideoSide?, Int?, Long) -> Unit`.
  - `init`: `PlayerUriResolver.resolve(manifest, side, segmentIndex)`; `readResume(sessionId, side, segmentIndex)`.
  - `persistPosition()`: `writeResume(sessionId, side, segmentIndex, pos)`.
  - `resolvePlaybackUri`: add the kept-raw branch before the vault branch:

```kotlin
        if (uri.startsWith(PlayerUriResolver.KEPT_SEGMENT_SCHEME)) {
            // ADR-0037 §5 V2 — kept-raw segment file lives in the app-private
            // session dir; FileProvider round-trip for the same reason as vault.
            val filename = uri.removePrefix(PlayerUriResolver.KEPT_SEGMENT_SCHEME)
            val dir = (app as RovaApp).sessionStore.sessionDir(sessionId)
            return androidx.core.content.FileProvider.getUriForFile(
                app, "${app.packageName}.provider", java.io.File(dir, filename)
            )
        }
```

  - `factory(app, sessionId, side = null, segmentIndex = null)`: pass through; lambdas become `{ sid, s, seg -> app.readResumePosition(sid, s, seg) }` / `{ sid, s, seg, pos -> app.writeResumePosition(sid, s, seg, pos) }`.

- [ ] **Step 3: `PlayerScreen`** — add `segmentIndex: Int? = null` parameter; pass to `PlayerViewModel.factory(app, sessionId, side, segmentIndex)`; extend the VM key so each segment gets its own VM/ExoPlayer (same reason as the per-side key):

```kotlin
        key = "player-$sessionId-${side?.name ?: "single"}-${segmentIndex ?: "merged"}"
```

- [ ] **Step 4: `MainScreen`** — route gains an optional `seg` arg (StringType nullable — NavType.IntType cannot be nullable; parse fail-closed to null, mirroring the `side` runCatching comment):
  - route string: `"player/{sessionId}?side={side}&secure={secure}&seg={seg}"`; add `navArgument("seg") { type = NavType.StringType; nullable = true; defaultValue = null }`.
  - parse in the composable: `val segmentIndex = backStackEntry.arguments?.getString("seg")?.toIntOrNull()` and pass `segmentIndex = segmentIndex` to `PlayerScreen`.
  - Library `onOpenPlayer` lambda (line 196) becomes `{ sessionId, side, segmentIndex -> … }` building the route: base as today, then `if (segmentIndex != null) route += (if ('?' in route) "&" else "?") + "seg=$segmentIndex"`. (Kept-raw rows always have `side == null` — ADR-0037 V4b — so in practice the URL is `player/<id>?seg=N`.) Vault lambda (line 295) unchanged (2-arg `VaultScreen` type).

- [ ] **Step 5: `LibraryScreen`** — signature `onOpenPlayer: (sessionId: String, side: VideoSide?, segmentIndex: Int?) -> Unit = { _, _, _ -> }`; call site becomes `onOpenPlayer(sid, item.side, item.segmentIndex)`.

- [ ] **Step 6: Compile + full player/library test sweep**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.player.*" --tests "com.aritr.rova.ui.library.*"`
Expected: PASS. Then `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL, 48 gates green.

- [ ] **Step 7: Commit**

```bash
git add -A app/src/main/java
git commit -m "feat(player): transport playback identity end-to-end (ADR-0037 §3 — Library mints, route transports)"
```

---

### Task 5: Hairline truthfulness — per-segment resume slot in the Library read

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt:386`
- Test: locate the existing suite covering `LibraryRow.resumePositionMs` mapping (grep `resumePositionMs` under `app/src/test/`; PR #173 added a journey/regression test — append there).

**Interfaces:**
- Consumes: `RecordingIdentity.slotFor` (Task 1); `item.segmentIndex` (`VideoItem`, already populated for kept-raw rows via `buildItem:717`).
- Produces: kept-raw tiles read their own `#seg<N>` slot — PR #173 hairline becomes truthful per clip.

- [ ] **Step 1: Write the failing test** — in the located suite, build a kept-raw row whose sidecar entry has `positionsBySide = mapOf("" to 5_000L, "#seg1" to 9_000L)` and assert the row for segment 1 gets `resumePositionMs == 9_000L` and the row for segment 0 gets `null` (NOT 5000 — no bleed). Follow the suite's existing row-construction helpers exactly.

- [ ] **Step 2: Run to verify failure** (segment rows currently read `sideSlot(item.side)` = `""` → both rows get 5000).

- [ ] **Step 3: Implement** — line 386, keep the EXACT-read shape (the v3.3 comment stays; extend it):

```kotlin
                // EXACT slot read, not positionFor: the hairline (spec v3.3) must never paint a
                // legacy ""-slot position on a named DualShot side. The player keeps positionFor.
                // ADR-0037 §4 — kept-raw rows read their own "#seg<N>" slot (slotFor), never the
                // session-level slot: no resume bleed between distinct playable artifacts.
                resumePositionMs = meta?.positionsBySide?.get(
                    RecordingIdentity.slotFor(item.side, item.segmentIndex)
                ),
```

- [ ] **Step 4: Run to verify pass**, then the full history/library suites:
`./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.*" --tests "com.aritr.rova.ui.library.*"` — PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt app/src/test/java/com/aritr/rova
git commit -m "fix(library): per-segment resume slot in hairline read (ADR-0037 §4 truthfulness)"
```

---

### Task 6: Contract pin tests — index stability + recovery-path journey

**Files:**
- Test: append to the existing `HistoryDeleter` test suite (grep `HistoryDeleterTest` under `app/src/test/`) — segments-array no-rewrite pin.
- Test: create `app/src/test/java/com/aritr/rova/ui/screens/KeptRawPlaybackJourneyTest.kt` — fan-out → resolve journey.

**Interfaces:**
- Consumes: `HistoryDeleter` batch delete (ADR-0036), `HistoryArtifactMapper.resolveArtifactsPerSegment`, `PlayerUriResolver.resolve` (Task 3).
- Produces: pinned ADR-0037 §1 stability invariant + end-to-end pure-JVM proof that every fanned-out row resolves Ready.

- [ ] **Step 1: No-rewrite pin test.** In the `HistoryDeleter` suite, using its existing fixtures: subset-delete ONE segment row of a 3-segment `MULTI_SEGMENT_KEPT` session, then reload the manifest and assert `manifest.segments` is **element-for-element identical** to before (same order, same filenames — the array is never reordered/compacted by deletion; ADR-0037 §1). Name it `` `subset delete never reorders or compacts the segments array` ``.

- [ ] **Step 2: Journey test.** Build a kept-raw manifest per writer path (`NOT_STARTED` user-keep; `FAILED` classifier), run `HistoryArtifactMapper.resolveArtifactsPerSegment(manifest)`, and for EVERY emitted `PerSegmentArtifact` assert `PlayerUriResolver.resolve(manifest, side = null, segmentIndex = it.segmentIndex)` is `PlayerUiState.Ready` with `mediaUri == KEPT_SEGMENT_SCHEME + it.filename`. Include a DualShot kept-raw manifest (interleaved P/L segments) — asserts full-array index alignment between mapper and resolver forever.

- [ ] **Step 3: Run both new suites + verify failure-first** (journey test should pass immediately if Tasks 3 is correct — that is fine; the no-rewrite pin must be run once against a deliberately-broken assertion to prove it can fail, then restored).

- [ ] **Step 4: Full suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: baseline 2241 + new tests, 0 failures.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/aritr/rova
git commit -m "test(library): ADR-0037 pins — segments-array no-rewrite + kept-raw fanout-to-resolve journey"
```

---

### Task 7: Final verification + review gate

**Files:** none (verification only).

- [ ] **Step 1:** `./gradlew :app:testDebugUnitTest` — full suite green; `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL (48 gates).
- [ ] **Step 2:** Install on RZCYA1VBQ2H (`adb install -r app/build/outputs/apk/debug/app-debug.apk`) and run the device checklist:
  1. Create a kept-raw session via the recovery "Keep as raw clips" CTA (kill the app mid-session, relaunch, choose keep). Every segment row: tap → **plays**.
  2. Scrub into clip 2, back out, reopen clip 2 → resumes; open clip 1 → does NOT inherit clip 2's position; hairline renders per tile at distinct widths.
  3. DualShot kept-raw session: both P and L segment rows play the correct file.
  4. FINALIZED session regression: plays, resumes, hairline unchanged; vault + SAF playback unchanged.
  5. Delete one kept-raw row → only that file gone, siblings intact and still playable (ADR-0036 + index stability live).
  6. Manual re-merge (`CANT_MERGE` retry) after playing a clip → merged row appears and plays; segment rows gone.
- [ ] **Step 3:** codex final review (gpt-5.4-mini) of the full branch diff — independent adversarial pass per the restored workflow — then `superpowers:requesting-code-review` / open PR per `superpowers:finishing-a-development-branch`.
