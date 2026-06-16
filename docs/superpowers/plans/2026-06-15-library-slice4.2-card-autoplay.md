# Library — Slice 4.2: Pooled Card Autoplay Implementation Plan

> **STATUS: ✅ DONE — committed `dc1e9ce`, build + 42 gates + JVM tests GREEN, codex clean (2 passes), owner device-smoke GO 2026-06-15.** Codex-reconciled before build: cap counts the hero (`MAX_CONCURRENT=3` total), audio track disabled (not just muted), ≥50% visible-fraction gate, prefix-filtered keys. v2 deferred = pause-while-scrolling + debounced-release pool. Next = Slice 5 (a11y close-out).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Extend muted autoplay from the hero to grid/list **cards** (owner smoke #2), bounded to be decoder-safe: only **visible** cards autoplay, capped to a small concurrency limit, paused while scrolling, and gated by reduce-motion (off → static frames, like the hero today).

**Architecture:** The existing `LibraryHeroVideo(uri, fallback, modifier)` is already a generic, self-contained ONE-ExoPlayer composable (muted, `REPEAT_MODE_ONE`, start-paused→lifecycle-derived `playWhenReady`, `RESIZE_MODE_ZOOM`, `applicationContext`, releases on dispose). Rename it `LibraryAutoplayVideo`, **disable its audio track** (muting output doesn't stop the audio decoder), and reuse it in cards. The Screen computes the set of card keys that should autoplay via a new pure `AutoplayPolicy` (visible card keys in viewport order, ≥50%-on-screen, capped) — gated to `emptySet` while reduce-motion is on OR the list is actively scrolling (no player churn / off-screen playback). Each autoplaying card composes one `LibraryAutoplayVideo`; off-screen / over-cap cards fall back to the static `VideoFrame` and their player is released on dispose. **Total** concurrent players (hero + cards) are bounded by `MAX_CONCURRENT = 3` — the hero counts against that budget (`cardCap = 3 − heroVisible`) — protecting scarce hardware video/audio decoder instances.

**Why not "every card":** owner rejected literal-all (decoder exhaustion ~6-16 concurrent → MediaCodec failures + battery). This is the "visible-only, pooled" option they chose.

**Tech Stack:** Compose, Media3/ExoPlayer **1.4.1** (pinned), JUnit4 JVM tests, `ReducedMotion` seam, `checkA11yAnimationGated` gate.

**Branch:** continue on `feat/library-history-selection` (stacked; do NOT push/merge — owner no-merge directive).

**Build:** WARM `gradlew.bat :app:assembleDebug` (no `--stop`). Tests `:app:testDebugUnitTest`. Device smoke is **mandatory** (decoder/jank/battery behavior is device-specific — RZCYA1VBQ2H).

---

## File structure

**New:**
- `app/src/main/java/com/aritr/rova/ui/library/AutoplayPolicy.kt` — pure visible+cap selection.
- `app/src/test/java/com/aritr/rova/ui/library/AutoplayPolicyTest.kt`.

**Modified:**
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryHeroVideo.kt` → rename file/fun to `LibraryAutoplayVideo`.
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryHeroCard.kt` — update the one call site.
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt` — `previewUri` + `autoplay` params; conditional player vs frame.
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt` — same.
- `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` — compute autoplay set; thread `previewUri`/`autoplay` to cards (controller-authored).

---

## Task 1: `AutoplayPolicy` pure helper + tests

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/AutoplayPolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/AutoplayPolicyTest.kt`

- [ ] **Step 1: Write the failing test** — `AutoplayPolicyTest.kt`:

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayPolicyTest {
    @Test fun emptyInput_emptyOut() =
        assertEquals(emptySet<String>(), AutoplayPolicy.select(emptyList(), 3))

    @Test fun fewerThanCap_allSelected() =
        assertEquals(setOf("a", "b"), AutoplayPolicy.select(listOf("a", "b"), 3))

    @Test fun moreThanCap_firstCapByViewportOrder() =
        assertEquals(setOf("a", "b"), AutoplayPolicy.select(listOf("a", "b", "c", "d"), 2))

    @Test fun capZero_emptyOut() =
        assertEquals(emptySet<String>(), AutoplayPolicy.select(listOf("a", "b"), 0))

    @Test fun capNegative_emptyOut() =
        assertEquals(emptySet<String>(), AutoplayPolicy.select(listOf("a", "b"), -2))

    // hero consumes one decoder of the MAX_CONCURRENT budget.
    @Test fun cardCap_heroVisible_reservesOne() = assertEquals(2, AutoplayPolicy.cardCap(heroVisible = true))
    @Test fun cardCap_heroHidden_fullBudget() = assertEquals(3, AutoplayPolicy.cardCap(heroVisible = false))
    @Test fun maxConcurrentIsDecoderSafe() = assertEquals(3, AutoplayPolicy.MAX_CONCURRENT)

    // visible-fraction gate (don't let a sliver-visible edge card take a decoder).
    @Test fun mostlyVisible_fullyInside() = assertTrue(AutoplayPolicy.isMostlyVisible(top = 100, size = 200, vpStart = 0, vpEnd = 1000))
    @Test fun mostlyVisible_exactlyHalf() = assertTrue(AutoplayPolicy.isMostlyVisible(top = -100, size = 200, vpStart = 0, vpEnd = 1000)) // 100/200 = .5
    @Test fun mostlyVisible_underHalf() = assertFalse(AutoplayPolicy.isMostlyVisible(top = -150, size = 200, vpStart = 0, vpEnd = 1000)) // 50/200 = .25
    @Test fun mostlyVisible_zeroSize() = assertFalse(AutoplayPolicy.isMostlyVisible(top = 0, size = 0, vpStart = 0, vpEnd = 1000))
    @Test fun mostlyVisible_fullyOffscreen() = assertFalse(AutoplayPolicy.isMostlyVisible(top = 2000, size = 200, vpStart = 0, vpEnd = 1000))
}
```

- [ ] **Step 2: Run, verify FAIL**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.AutoplayPolicyTest"`
Expected: FAIL — unresolved `AutoplayPolicy`.

- [ ] **Step 3: Implement** — `AutoplayPolicy.kt`:

```kotlin
package com.aritr.rova.ui.library

/**
 * Slice 4.2 — pure selection of which Library cards may autoplay. Autoplaying every card
 * exhausts hardware video decoders (MediaCodec instance limits + battery), so the Screen feeds
 * the VISIBLE card keys in viewport order and this returns the first [cap]. The hero, when on
 * screen, consumes ONE of the [MAX_CONCURRENT] budget — [cardCap] reserves for it. Framework-free
 * → JVM-tested.
 *
 * codex review (Slice 4.2): cap counts the hero (4 cards + hero = 5 was too aggressive); audio
 * decoders are freed in LibraryAutoplayVideo (track disabled, not just muted); a card must be
 * [MIN_VISIBLE_FRACTION] on-screen to claim a decoder (no sliver-edge thrash).
 */
object AutoplayPolicy {
    /** Max concurrent video players TOTAL (cards + hero). Conservative — mid-range decoders
     *  tolerate only a few instances; getMaxSupportedInstances is an upper hint, not a promise. */
    const val MAX_CONCURRENT = 3

    /** A card must be at least this fraction on-screen to autoplay (don't let a 1px edge steal a decoder). */
    const val MIN_VISIBLE_FRACTION = 0.5f

    /** Card budget after reserving one decoder for the hero when it's visible. */
    fun cardCap(heroVisible: Boolean): Int =
        (MAX_CONCURRENT - if (heroVisible) 1 else 0).coerceAtLeast(0)

    fun select(orderedVisibleKeys: List<String>, cap: Int): Set<String> {
        if (cap <= 0) return emptySet()
        return orderedVisibleKeys.take(cap).toSet()
    }

    /** True if a [size]px item at viewport-relative [top] is ≥[minFraction] inside [vpStart]..[vpEnd]. */
    fun isMostlyVisible(
        top: Int,
        size: Int,
        vpStart: Int,
        vpEnd: Int,
        minFraction: Float = MIN_VISIBLE_FRACTION,
    ): Boolean {
        if (size <= 0) return false
        val visible = (minOf(top + size, vpEnd) - maxOf(top, vpStart)).coerceAtLeast(0)
        return visible.toFloat() / size >= minFraction
    }
}
```

- [ ] **Step 4: Run, verify PASS.** Run the same command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/AutoplayPolicy.kt app/src/test/java/com/aritr/rova/ui/library/AutoplayPolicyTest.kt
git commit -m "feat(library): AutoplayPolicy pure helper (visible+cap selection) (Slice 4.2)"
```

---

## Task 2: Rename `LibraryHeroVideo` → `LibraryAutoplayVideo`

**Files:**
- Rename: `LibraryHeroVideo.kt` → `LibraryAutoplayVideo.kt` (same package `ui.library.components`)
- Modify: `LibraryHeroCard.kt` (the one call site)

The composable is already generic (`uri, fallback, modifier`); only the name is hero-specific.

- [ ] **Step 1: Rename the file + the `fun`.** Create `app/src/main/java/com/aritr/rova/ui/library/components/LibraryAutoplayVideo.kt` with the SAME body as `LibraryHeroVideo.kt`, renaming only `fun LibraryHeroVideo(` → `fun LibraryAutoplayVideo(` and updating the KDoc first line to: `/** Slice 3 polish + 4.2 — ONE muted, looping ExoPlayer over [uri] (hero + visible cards). ... */` (keep the rest of the KDoc + body byte-identical except Step 2's audio-disable). Delete the old `LibraryHeroVideo.kt`.

- [ ] **Step 2: Disable the audio track (free the audio decoder).** codex flag: `volume = 0f` mutes output but the **audio decoder still runs** — and previews are real recordings with audio, so N muted players = N idle audio codecs competing for limited hardware instances. Disable audio track selection on the player. In the player-build block (right after `ExoPlayer.Builder(...).build()` is assigned, and after the existing `volume = 0f` line), add:

```kotlin
        // Slice 4.2: muting != disabling the audio decoder. Drop the audio track so each muted
        // preview holds only a video codec (frees scarce hardware audio-decoder instances).
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, true)
            .build()
```

Keep `volume = 0f` too (belt-and-suspenders; harmless). If the player is built with `.apply { ... }`, put the two lines inside that same `apply` block where `volume`/`repeatMode` are set.

- [ ] **Step 3: Update the call site in `LibraryHeroCard.kt`.** Find `LibraryHeroVideo(` and change it to `LibraryAutoplayVideo(` (args unchanged). Update any import if it was explicitly imported (same package, so likely none).

- [ ] **Step 4: Build to verify compile**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryAutoplayVideo.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryHeroCard.kt
git rm app/src/main/java/com/aritr/rova/ui/library/components/LibraryHeroVideo.kt 2>/dev/null || true
git commit -m "refactor(library): rename LibraryHeroVideo -> LibraryAutoplayVideo (reused by cards) (Slice 4.2)"
```

---

## Task 3: `LibraryGridCard` — autoplay the thumbnail area (controller-authored)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt`

- [ ] **Step 1: Add params** — after `thumbnail: Bitmap?,` (or at the end of the param list before defaults), add:

```kotlin
    previewUri: android.net.Uri? = null,
    autoplay: Boolean = false,
```

- [ ] **Step 2: Swap the thumbnail render.** Replace the line `VideoFrame(thumbnail, Modifier.fillMaxSize())` with:

```kotlin
            if (autoplay && previewUri != null) {
                LibraryAutoplayVideo(previewUri, thumbnail, Modifier.fillMaxSize())
            } else {
                VideoFrame(thumbnail, Modifier.fillMaxSize())
            }
```

(Add `import` for `LibraryAutoplayVideo` only if the file isn't already in `ui.library.components` — it is, same package, so no import.)

- [ ] **Step 3: Build.** `gradlew.bat :app:assembleDebug` → SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt
git commit -m "feat(library): grid card autoplay (previewUri+autoplay -> LibraryAutoplayVideo) (Slice 4.2)"
```

---

## Task 4: `LibraryListRow` — autoplay the thumbnail area (controller-authored)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt`

- [ ] **Step 1: Add params** — after `thumbnail: Bitmap?,`:

```kotlin
    previewUri: android.net.Uri? = null,
    autoplay: Boolean = false,
```

- [ ] **Step 2: Swap the thumbnail render.** Replace the `VideoFrame(thumbnail, Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp)))` block with:

```kotlin
            val thumbMod = Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(8.dp))
            if (autoplay && previewUri != null) {
                LibraryAutoplayVideo(previewUri, thumbnail, thumbMod)
            } else {
                VideoFrame(thumbnail, thumbMod)
            }
```

(Match the file's existing modifier exactly — if it differs from the above, keep the file's actual modifier and just wrap it in the if/else.)

- [ ] **Step 3: Build.** `gradlew.bat :app:assembleDebug` → SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt
git commit -m "feat(library): list row autoplay (Slice 4.2)"
```

---

## Task 5: Wire autoplay selection into `LibraryScreen` (controller-authored)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt`

- [ ] **Step 1: Add imports** (top, with the other compose imports):

```kotlin
import androidx.compose.runtime.derivedStateOf
```
(`com.aritr.rova.ui.library.AutoplayPolicy` is same package — no import.)

- [ ] **Step 2: Compute the autoplay key set.** After `gridState`/`listState` exist, add the block below. **codex fixes baked in:** (a) key the derived state on `reduceMotion` ONLY — `ui.viewMode` + `gridState`/`listState` layout are snapshot-state read *inside* the lambda, so they re-derive without an O(n) `Map.equals` key; (b) filter card keys by **prefix** (`!startsWith("hdr-")`/`!startsWith("hero-")`) instead of `rowByKey::containsKey` — robust to headers/hero (also String keys) and drops the rowByKey dependency; (c) count the hero against the budget via `cardCap(heroVisible)`; (d) require `isMostlyVisible` (≥50%) so a sliver-edge card doesn't claim a decoder.

```kotlin
    // Slice 4.2 — which cards autoplay: visible-only (≥50% on-screen), capped (decoder-safe,
    // hero counts against the budget), paused while scrolling, off under reduce-motion.
    // Keyed on reduceMotion only; viewMode + scroll/layout are snapshot-state read inside.
    val autoplayKeys: Set<String> by remember(reduceMotion) {
        derivedStateOf {
            if (reduceMotion) return@derivedStateOf emptySet()
            val grid = ui.viewMode == LibraryViewMode.GRID
            if (if (grid) gridState.isScrollInProgress else listState.isScrollInProgress) {
                return@derivedStateOf emptySet()
            }
            val orderedKeys: List<String>
            val heroVisible: Boolean
            if (grid) {
                val li = gridState.layoutInfo
                val onScreen = li.visibleItemsInfo.filter {
                    AutoplayPolicy.isMostlyVisible(it.offset.y, it.size.height, li.viewportStartOffset, li.viewportEndOffset)
                }
                heroVisible = onScreen.any { (it.key as? String)?.startsWith("hero-") == true }
                orderedKeys = onScreen.mapNotNull { it.key as? String }
                    .filter { !it.startsWith("hdr-") && !it.startsWith("hero-") }
            } else {
                val li = listState.layoutInfo
                val onScreen = li.visibleItemsInfo.filter {
                    AutoplayPolicy.isMostlyVisible(it.offset, it.size, li.viewportStartOffset, li.viewportEndOffset)
                }
                heroVisible = onScreen.any { (it.key as? String)?.startsWith("hero-") == true }
                orderedKeys = onScreen.mapNotNull { it.key as? String }
                    .filter { !it.startsWith("hdr-") && !it.startsWith("hero-") }
            }
            AutoplayPolicy.select(orderedKeys, AutoplayPolicy.cardCap(heroVisible))
        }
    }

    // Per-row preview URI (same resolution the hero uses).
    fun previewUriFor(stableKey: String): android.net.Uri? =
        byKey[stableKey]?.let { it.shareUri ?: it.file?.let(android.net.Uri::fromFile) }
```

> `getValue` is already imported (used by `by` delegates elsewhere). `byKey` is `remember(items){ items.associateBy { it.stableKey } }` — already defined above. **Verify the actual header/hero key prefixes** in `LibraryScreen.kt` (the `item(key = ...)` / `items(key = ...)` calls) before building — the plan assumes `"hdr-"` for day-headers and `"hero-"` for the hero slot; if they differ, match the real prefixes.

- [ ] **Step 3: Pass to the grid card.** In the `LibraryGridCard(` call, add (e.g. after `thumbnail = ...,`):

```kotlin
                                                previewUri = previewUriFor(row.stableKey),
                                                autoplay = row.stableKey in autoplayKeys,
```

- [ ] **Step 4: Pass to the list row.** In the `LibraryListRow(` call, add (after `thumbnail = ...,`):

```kotlin
                                                previewUri = previewUriFor(row.stableKey),
                                                autoplay = row.stableKey in autoplayKeys,
```

- [ ] **Step 5: Build (full gate run) + tests**

Run: `gradlew.bat :app:assembleDebug`
Expected: SUCCESSFUL — 42 gates pass (`checkA11yAnimationGated`: autoplay is `false` under reduce-motion via `autoplayKeys = emptySet`; no manifest writes → `checkLibraryNoManifestWrite` clean).

Run: `gradlew.bat :app:testDebugUnitTest`
Expected: PASS (incl. `AutoplayPolicyTest`).

- [ ] **Step 6: codex review** the autoplay selection (derivedStateOf churn, player create/release on scroll-settle, decoder cap incl. hero, key filtering). Fold fixes.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt
git commit -m "feat(library): wire visible+capped card autoplay into LibraryScreen (Slice 4.2)"
```

---

## Task 6: Device smoke (RZCYA1VBQ2H) — MANDATORY (decoder behavior is device-specific)

Install: `adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk` (record several clips first; mix durations).

- [ ] Grid: scroll → on settle, the top **2 cards** play muted+looping while the **hero** plays (≤3 total); when the hero scrolls off-screen, **3 cards** play. Lower cards stay static. No black flashes (static frame underneath).
- [ ] Sliver-edge: a card only ~25% on-screen at the viewport edge does **not** autoplay (≥50% gate).
- [ ] While actively scrolling: cards are static (no churn/flicker), playback resumes on settle.
- [ ] **Decoder/perf:** no dropped-video / green frames / "codec"/"audio" logcat errors on a library of 20+ clips; scroll stays smooth; no obvious battery/heat spike. If decoder errors still appear, lower `AutoplayPolicy.MAX_CONCURRENT` (e.g. 2) and re-smoke.
- [ ] **Audio:** confirm no audio plays from any card/hero AND (logcat) no extra audio-decoder allocations — track is disabled, not just volume-muted.
- [ ] List mode: same behavior on the small thumbnails.
- [ ] Reduce-motion ON (Developer options → animation scales 0): ALL cards + hero static (no autoplay). Confirms `checkA11yAnimationGated` intent.
- [ ] Toggle grid↔list: autoplay set recomputes correctly for the active view.
- [ ] Filter/search: autoplay follows the filtered/visible set (no autoplay of hidden rows).

> After owner GO: do NOT push/merge. Update HANDOFF + memory; Slice 5 (a11y close-out) next.

---

## Self-review

- **Owner #2 satisfied:** cards autoplay (visible-only, capped) — the chosen "visible-only, pooled" option; not literal-all (rejected). Note for owner: decoder-safe reality = **≤3 videos play at once** (hero + 2 cards, or 3 cards when hero off-screen), not literally every card; cap is one-line tunable.
- **Decoder safety (codex-reconciled):** total concurrent players ≤ `MAX_CONCURRENT (3)` **including the hero** (`cardCap` reserves for it); audio track disabled (no idle audio codecs); `isMostlyVisible` ≥50% gate; scroll-idle gating prevents churn; reduce-motion → zero.
- **Reuse not duplication:** one `LibraryAutoplayVideo` (renamed hero video, now audio-track-disabled) drives hero + cards; release-on-dispose bounds lifetime.
- **Type consistency:** `previewUri: Uri?` + `autoplay: Boolean` on both cards; `AutoplayPolicy.select`/`cardCap`/`MAX_CONCURRENT`/`isMostlyVisible`; `autoplayKeys` Set<String> keyed on stableKey.
- **derivedStateOf:** keyed on `reduceMotion` only; viewMode + scroll/layout read inside as snapshot state (no O(n) Map key). Card keys filtered by prefix (`hdr-`/`hero-` excluded), not via rowByKey.
- **Risk / deferred to v2:** decoder limits vary by device → smoke is mandatory. Current model **releases** players on scroll-start and re-creates on settle (one batch per gesture, ≤3 players — tolerable). A true **pause-while-scrolling + debounced-release pool** (codex's preferred shape) is the v2 refinement if settle-flicker shows on device.
