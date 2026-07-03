# Library Session-List PR-B (Presentation) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Swap the Library to the single-list session presentation (spec `docs/superpowers/specs/2026-07-02-library-session-list-design.md` §7 PR-B): delete grid/hero/autoplay, wire the merged PR-A pure layer (`LibrarySessionAggregator` / `LatestRowPolicy` / `LibraryDensityDimens`) into `HistoryViewModel`, render session rows + latest-accent row + aggregated DualShot rows with two explicit side actions, and fan file-level batch ops out through `LibraryRow.sides`.

**Architecture:** The VM combine aggregates DualShot per-side rows into session rows (stableKey = `session:<id>`) BEFORE the UI sees them; every file-level operation (play/share/delete) that resolves `byKey[stableKey]` therefore gets a key-expansion step (`LibrarySessionKeys.expand`) back to the original per-side keys carried in `LibraryRow.sides`. Metadata ops (favorite/rename) instead write straight to the canonical session key (`metaKeyForStableKey` fallback). `LibraryListRow` becomes the ONE row composable with a latest-accent variant and DualShot side-action buttons; grid, hero, and all autoplay code are deleted.

**Tech Stack:** Kotlin, Compose (BOM 2025.01.01), JUnit4 (`org.junit.Assert`), Gradle 9.4.1 (`gradlew.bat` on Windows), no new dependencies.

## Global Constraints

- JVM unit tests only; no Robolectric/instrumented tests (project CLAUDE.md).
- Verify via `./gradlew :app:assembleDebug` (fires the 48 `check*` gates on preBuild). Do NOT use `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED).
- Full JVM suite green: `./gradlew :app:testDebugUnitTest` (baseline 2202; net grows this PR).
- `checkLibraryNoManifestWrite` (ADR-0030 §2): zero `SessionManifest`-mutating calls in Library/History UI code — everything here is sidecar/pure only.
- `checkNoHardcodedUiStrings`: every new user-facing string is a resource, added to BOTH `values/strings.xml` and `values-es/strings.xml`.
- ADR-0020 WCAG 2.2 AA: all new tap targets ≥48dp (`heightIn(min = 48.dp)`), clickables carry `Role`, latest-row eyebrow proven ≥4.5:1 across all 12 palettes by a JVM test (spec §3.3).
- NO autoplay anywhere in the Library (ADR-0030 amendment §2 — trust rule). No media surface in any row.
- `LibraryQuery.collection` KEEPS its `heroKey` param; callers pass `null` (codex note in PR-A plan §"PR-B / PR-C").
- Vault: DualShot sessions stay NOT vault-movable (ADR-0025 status quo) — no gating change.
- Branch: create `feat/library-session-list-pr-b` off master. Commit per task; do NOT push (owner-gated).
- Line numbers below are anchors as of master `47a719c5` — verify with a quick read before each edit; content matching wins over line numbers.

**Documented simplifications (owner visibility):**
1. DualShot side-action buttons are text-only ("Portrait · 1:30") — the spec's ▯/▭ glyphs are PR-C polish.
2. The usage line now folds over AGGREGATED rows: `sessionCount` counts a DualShot session once (was twice) and `clipCount` uses the session row's MAX-across-sides value; `totalBytes` is unchanged (session row sums both files). This is the session-centric reading the redesign wants.
3. Pre-aggregation, both side rows of a session read the same merged sidecar metadata (Task 2) — invisible after aggregation, and the correct spec §3.4 semantics.
4. The latest-row eyebrow reuses the existing `library_eyebrow_latest` copy ("Latest recording").

---

### Task 1: Resume-position pass-through (row model + aggregator)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryRow.kt` (data class, ~line 44)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryRowMapper.kt` (`Input` + `map`)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` (`toLibraryRow`, ~line 348)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibrarySessionAggregator.kt` (`collapse`, ~line 51)
- Test: extend `app/src/test/java/com/aritr/rova/ui/library/LibraryRowMapperTest.kt` and `LibrarySessionAggregatorTest.kt`

**Interfaces:**
- Consumes: `LibraryMetadataEntry.positionFor(slot)` (existing), `RecordingIdentity.sideSlot(side)` (existing).
- Produces: `LibraryRow.resumePositionMs: Long?` (default null) — Task 5's Resume pill and Task 6's screen wiring read it. `LibraryRowMapper.Input.resumePositionMs: Long?` (default null, LAST field).

- [ ] **Step 1: Write the failing tests**

Add to `LibraryRowMapperTest.kt` (reuse the file's Input fixture style; the two PR-A tests there show the full named-arg construction — copy one and set):

```kotlin
@Test
fun map_carriesResumePosition() {
    // Build the same Input as map_carriesSessionKeyAndSide but with resumePositionMs = 42_000L.
    // Assert: row.resumePositionMs == 42_000L. Also assert the default (an Input built without
    // the param) yields null.
    val row = LibraryRowMapper.map(
        LibraryRowMapper.Input(
            stableKey = "/path/a_P.mp4",
            startedAtMillis = 1_000L,
            dateMillis = 1_000L,
            dateLabel = "Jul 2",
            sizeBytes = 10L,
            segmentDurationsMs = listOf(30_000L),
            topologyPersisted = "DualShot",
            terminated = null,
            stopReason = StopReason.NONE,
            exportState = ExportState.FINALIZED,
            customTitle = null,
            favorite = false,
            side = VideoSide.PORTRAIT,
            sessionId = "abc123",
            resumePositionMs = 42_000L,
        ),
        Locale.US, TimeZone.getTimeZone("UTC"),
    )
    assertEquals(42_000L, row.resumePositionMs)
}
```

Add to `LibrarySessionAggregatorTest.kt` (reuse its `row(...)` factory — extend the factory with `resumePositionMs: Long? = null` passed through as a named arg):

```kotlin
@Test
fun collapse_resumePosition_portraitWins_elseLandscape() {
    val p = row("/p.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.PORTRAIT, dateMillis = 100L)
        .copy(resumePositionMs = 11_000L)
    val l = row("/l.mp4", CaptureTopology.DualShot, "session:s1", VideoSide.LANDSCAPE, dateMillis = 200L)
        .copy(resumePositionMs = 22_000L)
    // Portrait-first even though landscape is the latest-dated base.
    assertEquals(11_000L, LibrarySessionAggregator.aggregate(listOf(p, l)).single().resumePositionMs)

    val pNull = p.copy(resumePositionMs = null)
    assertEquals(22_000L, LibrarySessionAggregator.aggregate(listOf(pNull, l)).single().resumePositionMs)

    val bothNull = listOf(pNull, l.copy(resumePositionMs = null))
    assertNull(LibrarySessionAggregator.aggregate(bothNull).single().resumePositionMs)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryRowMapperTest" --tests "com.aritr.rova.ui.library.LibrarySessionAggregatorTest"`
Expected: FAIL to compile — no `resumePositionMs` parameter/property.

- [ ] **Step 3: Implement**

`LibraryRow.kt` — append to the data class AFTER `sides` (keeps every positional test constructor compiling):

```kotlin
    /**
     * Saved playback position (ms) for the side this row's tap would play: the row's own side for
     * single/per-side rows, the PORTRAIT-first non-null side on aggregated session rows. Drives the
     * latest-row Resume pill copy only (spec §3.3) — the player re-reads its own resume position.
     */
    val resumePositionMs: Long? = null,
```

`LibraryRowMapper.kt` — `Input` gains `val resumePositionMs: Long? = null` as the LAST field; `map` sets `resumePositionMs = input.resumePositionMs,`.

`HistoryViewModel.toLibraryRow` — add to the `Input(...)` construction (after `sessionId = item.sessionId,`):

```kotlin
                resumePositionMs = meta?.positionFor(RecordingIdentity.sideSlot(item.side)),
```

`LibrarySessionAggregator.collapse` — add to the `base.copy(...)` overrides (after `side = null,`):

```kotlin
            // Resume pill reads the side the row tap plays: PORTRAIT-first non-null (spec §3.3/§3.4).
            resumePositionMs = ordered.firstNotNullOfOrNull { it.resumePositionMs },
```

- [ ] **Step 4: Run the two suites, then the FULL suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.*"` → PASS.
Run: `./gradlew :app:testDebugUnitTest` → PASS (appended defaulted field breaks nothing).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryRow.kt app/src/main/java/com/aritr/rova/ui/library/LibraryRowMapper.kt app/src/main/java/com/aritr/rova/ui/library/LibrarySessionAggregator.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt app/src/test/java/com/aritr/rova/ui/library/LibraryRowMapperTest.kt app/src/test/java/com/aritr/rova/ui/library/LibrarySessionAggregatorTest.kt
git commit -m "feat(library): resume-position pass-through for the latest-row pill (PR-B T1)"
```

---

### Task 2: Session sidecar lazy-merge (spec §3.4)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/SessionSidecarMerge.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` (combine transform, lines ~315–325)
- Test: `app/src/test/java/com/aritr/rova/ui/library/SessionSidecarMergeTest.kt`

**Interfaces:**
- Consumes: `LibraryMetadataEntry.merge(canonical, legacy)` (existing: per-field first-arg-wins, favorite OR, lastPlayedAt max, positions union with first-arg priority).
- Produces: `SessionSidecarMerge.resolve(canonical: LibraryMetadataEntry?, sideLegaciesPortraitFirst: List<LibraryMetadataEntry?>): LibraryMetadataEntry?` — Task 6's VM combine calls it for multi-side DualShot sessions.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSidecarMergeTest {

    @Test
    fun customTitle_firstNonNull_portraitWins() {
        val portrait = LibraryMetadataEntry(customTitle = "from-portrait")
        val landscape = LibraryMetadataEntry(customTitle = "from-landscape")
        val merged = SessionSidecarMerge.resolve(null, listOf(portrait, landscape))
        assertEquals("from-portrait", merged?.customTitle)
    }

    @Test
    fun customTitle_canonicalBeatsEitherSide() {
        val canonical = LibraryMetadataEntry(customTitle = "canonical")
        val merged = SessionSidecarMerge.resolve(
            canonical,
            listOf(LibraryMetadataEntry(customTitle = "p"), LibraryMetadataEntry(customTitle = "l")),
        )
        assertEquals("canonical", merged?.customTitle)
    }

    @Test
    fun favorite_isEitherSide() {
        val merged = SessionSidecarMerge.resolve(
            null,
            listOf(LibraryMetadataEntry(favorite = false), LibraryMetadataEntry(favorite = true)),
        )
        assertTrue(merged!!.favorite)
    }

    @Test
    fun lastPlayedAt_isMaxAcrossAll() {
        val merged = SessionSidecarMerge.resolve(
            LibraryMetadataEntry(lastPlayedAt = 100L),
            listOf(LibraryMetadataEntry(lastPlayedAt = 300L), LibraryMetadataEntry(lastPlayedAt = 200L)),
        )
        assertEquals(300L, merged?.lastPlayedAt)
    }

    @Test
    fun nullSides_andNullCanonical_yieldNull() {
        assertNull(SessionSidecarMerge.resolve(null, listOf(null, null)))
    }

    @Test
    fun positions_union_earlierEntryWinsPerSlot() {
        val canonical = LibraryMetadataEntry(positionsBySide = mapOf("PORTRAIT" to 10L))
        val legacy = LibraryMetadataEntry(positionsBySide = mapOf("PORTRAIT" to 99L, "LANDSCAPE" to 20L))
        val merged = SessionSidecarMerge.resolve(canonical, listOf(legacy))
        assertEquals(10L, merged?.positionsBySide?.get("PORTRAIT"))
        assertEquals(20L, merged?.positionsBySide?.get("LANDSCAPE"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.SessionSidecarMergeTest"`
Expected: FAIL — unresolved reference `SessionSidecarMerge`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.ui.library

/**
 * Session-level sidecar lazy-merge for DualShot rows (spec §3.4, ADR-0030 amendment §3).
 *
 * Pre-#137 sidecar entries were keyed per-side (file path), so a session's two sides can carry
 * divergent legacy metadata. The aggregated session row must read ONE truth. Chained
 * [LibraryMetadataEntry.merge] keeps its proven field semantics with earlier-argument priority
 * (canonical > portrait legacy > landscape legacy): customTitle = first non-null portrait-wins,
 * favorite = either side, lastPlayedAt = max, positions union. READ-path only — writes keep
 * going to the canonical session key ([LibraryMetadataStore.update] merge-on-write).
 */
object SessionSidecarMerge {
    fun resolve(
        canonical: LibraryMetadataEntry?,
        sideLegaciesPortraitFirst: List<LibraryMetadataEntry?>,
    ): LibraryMetadataEntry? =
        sideLegaciesPortraitFirst.fold(canonical) { acc, legacy -> LibraryMetadataEntry.merge(acc, legacy) }
}
```

- [ ] **Step 4: Wire the VM combine pre-pass**

In `HistoryViewModel.kt`, replace the mapping block inside the `libraryUiState` combine (currently lines ~319–325):

```kotlin
            // Spec §3.4 sidecar lazy-merge: a DualShot session's two sides may carry divergent
            // pre-#137 legacy (path-keyed) entries; merge them portrait-first so both side rows —
            // and the aggregated session row built from them — read one metadata truth. Read-path
            // only; writes stay canonical. Single-side / single-mode items keep the pairwise path.
            val legacyBySession: Map<String, List<LibraryMetadataEntry?>> = rows
                .filter { it.sessionId != null && it.side != null }
                .groupBy { it.sessionId!! }
                .filterValues { it.size > 1 }
                .mapValues { (_, group) ->
                    group.sortedBy { if (it.side == VideoSide.PORTRAIT) 0 else 1 }
                        .map { s ->
                            val k = RecordingIdentity.forItem(s.sessionId, s.file?.absolutePath, s.docUri?.toString())
                            k.legacy?.takeIf { it != k.canonical }?.let { snapshot[it] }
                        }
                }
            val mapped = rows.map { item ->
                val key = RecordingIdentity.forItem(item.sessionId, item.file?.absolutePath, item.docUri?.toString())
                val canonical = snapshot[key.canonical]
                val sideLegacies = item.sessionId?.let { legacyBySession[it] }
                val meta = if (sideLegacies != null) {
                    SessionSidecarMerge.resolve(canonical, sideLegacies)
                } else {
                    val legacy = key.legacy?.takeIf { it != key.canonical }?.let { snapshot[it] }
                    LibraryMetadataEntry.merge(canonical, legacy)
                }
                toLibraryRow(item, meta, locale, tz)
            }
```

Add imports if missing: `com.aritr.rova.service.dualrecord.VideoSide`, `com.aritr.rova.ui.library.SessionSidecarMerge`.

- [ ] **Step 5: Run the new test + full suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.SessionSidecarMergeTest"` → PASS (6 tests).
Run: `./gradlew :app:testDebugUnitTest` → PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/SessionSidecarMerge.kt app/src/test/java/com/aritr/rova/ui/library/SessionSidecarMergeTest.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt
git commit -m "feat(library): DualShot session sidecar lazy-merge, read-path (PR-B T2)"
```

---

### Task 3: Key expansion + canonical metadata fallback

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibrarySessionKeys.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/RecordingIdentity.kt` (add `isSessionKey`)
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` (`metaKeyForStableKey`, ~line 384)
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibrarySessionKeysTest.kt`; extend the existing `RecordingIdentity` test file (find via `Glob app/src/test/**/RecordingIdentity*Test.kt`; if none exists, put the `isSessionKey` assertions in `LibrarySessionKeysTest`)

**Interfaces:**
- Consumes: `LibraryRow.sides` (PR-A), `RecordingIdentity.sessionKey` / `MetaKey` (existing).
- Produces: `LibrarySessionKeys.expand(keys: Collection<String>, rowsByKey: Map<String, LibraryRow>): Set<String>` — Task 6's share/delete call sites use it. `RecordingIdentity.isSessionKey(key: String): Boolean`. `metaKeyForStableKey` now resolves aggregated session-row keys (favorite/rename on session rows just work).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.data.CaptureTopology
import com.aritr.rova.service.dualrecord.VideoSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySessionKeysTest {

    private fun sessionRow(key: String, sideKeys: List<String>) = LibraryRow(
        stableKey = key, title = "t", dateLabel = "d", dateMillis = 1L,
        durationMs = 1L, sizeBytes = 1L, clipCount = 1,
        topology = CaptureTopology.DualShot, badge = null, favorite = false,
        sessionKey = key,
        sides = sideKeys.mapIndexed { i, k ->
            LibrarySessionSide(if (i == 0) VideoSide.PORTRAIT else VideoSide.LANDSCAPE, k, 1L, 1)
        },
    )

    private fun plainRow(key: String) = LibraryRow(
        stableKey = key, title = "t", dateLabel = "d", dateMillis = 1L,
        durationMs = 1L, sizeBytes = 1L, clipCount = 1,
        topology = CaptureTopology.Single, badge = null, favorite = false,
    )

    @Test
    fun sessionKey_expandsToBothSideKeys_inOrder() {
        val rows = mapOf("session:s1" to sessionRow("session:s1", listOf("/p.mp4", "/l.mp4")))
        assertEquals(
            setOf("/p.mp4", "/l.mp4"),
            LibrarySessionKeys.expand(listOf("session:s1"), rows),
        )
    }

    @Test
    fun plainKeys_passThrough_unknownKeysKept() {
        val rows = mapOf("a" to plainRow("a"))
        // "ghost" has no row (e.g. deleted mid-flight) — pass through so downstream
        // itemsForKeys can still resolve-or-drop it, exactly like today.
        assertEquals(setOf("a", "ghost"), LibrarySessionKeys.expand(listOf("a", "ghost"), rows))
    }

    @Test
    fun mixedSelection_expandsOnlySessionRows_deduped() {
        val rows = mapOf(
            "session:s1" to sessionRow("session:s1", listOf("/p.mp4", "/l.mp4")),
            "/x.mp4" to plainRow("/x.mp4"),
        )
        val out = LibrarySessionKeys.expand(listOf("/x.mp4", "session:s1", "/p.mp4"), rows)
        assertEquals(setOf("/x.mp4", "/p.mp4", "/l.mp4"), out)
    }

    @Test
    fun recordingIdentity_isSessionKey() {
        assertTrue(RecordingIdentity.isSessionKey("session:abc"))
        assertFalse(RecordingIdentity.isSessionKey("/storage/emulated/0/x.mp4"))
        assertFalse(RecordingIdentity.isSessionKey("content://docs/x"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibrarySessionKeysTest"`
Expected: FAIL — unresolved references `LibrarySessionKeys` / `isSessionKey`.

- [ ] **Step 3: Implement**

`LibrarySessionKeys.kt`:

```kotlin
package com.aritr.rova.ui.library

/**
 * Expands row/selection keys to playable-file keys (spec §3.4). An aggregated DualShot session
 * row keys on the canonical session key, which resolves to NO VideoItem — file-level operations
 * (share/delete/play) must fan out to the ORIGINAL per-side keys carried in [LibraryRow.sides].
 * Non-session keys (and keys with no row) pass through untouched. Order-preserving, de-duplicated.
 */
object LibrarySessionKeys {
    fun expand(keys: Collection<String>, rowsByKey: Map<String, LibraryRow>): Set<String> =
        keys.flatMapTo(LinkedHashSet()) { k ->
            val sides = rowsByKey[k]?.sides
            if (sides.isNullOrEmpty()) listOf(k) else sides.map { it.stableKey }
        }
}
```

`RecordingIdentity.kt` — add next to `sessionKey` (~line 16), matching its `"session:$sessionId"` shape exactly:

```kotlin
    /** True for canonical session keys ("session:<id>") — the stableKey shape of aggregated session rows. */
    fun isSessionKey(key: String): Boolean = key.startsWith("session:")
```

`HistoryViewModel` — REPLACE `metaKeyForStableKey` with a write-keys resolver, and update BOTH writers. Rationale (codex plan-review 2026-07-03, High): a canonical-only write cannot CLEAR state — `LibraryMetadataEntry.merge` ORs `favorite` and `?:`s `customTitle`, so a stale pre-#137 per-side legacy alias would resurrect an unfavorite/cleared-rename on the next read merge (Task 2 merges across BOTH sides, making this visible on every row of the session). Fix: migrate every side's legacy alias into the canonical entry (identity transforms — `LibraryMetadataStore.update(MetaKey)` merges-on-write and DROPS the alias) BEFORE the real transform runs.

```kotlin
    /**
     * Sidecar WRITE keys for one row key. Single-mode rows: the item's own MetaKey (unchanged
     * behavior). DualShot rows — per-side OR aggregated session — return the sides' legacy
     * migration keys FIRST, then the canonical session key as the transform target: a
     * canonical-only write can't clear state while a legacy alias survives, because the read
     * path merge (favorite OR / customTitle ?:) would resurrect it (codex plan-review
     * 2026-07-03). Null = unknown key → callers raise the existing error signal.
     */
    private fun writeKeysForStableKey(stableKey: String): List<RecordingIdentity.MetaKey>? {
        val item = items.value.firstOrNull { it.stableKey == stableKey }
        val sessionId = when {
            item != null && item.sessionId != null && item.side != null -> item.sessionId
            item != null ->
                return listOf(RecordingIdentity.forItem(item.sessionId, item.file?.absolutePath, item.docUri?.toString()))
            RecordingIdentity.isSessionKey(stableKey) -> stableKey.removePrefix("session:")
            else -> return null
        }
        val legacies = items.value
            .filter { it.sessionId == sessionId && it.side != null }
            .map { RecordingIdentity.forItem(it.sessionId, it.file?.absolutePath, it.docUri?.toString()) }
            .filter { it.legacy != null && it.legacy != it.canonical }
        return legacies + RecordingIdentity.MetaKey(canonical = RecordingIdentity.sessionKey(sessionId), legacy = null)
    }
```

`toggleFavorite` — replace the key resolution + store call (same non-optimistic failure model; `renameSession` gets the IDENTICAL restructure with its own transform):

```kotlin
    fun toggleFavorite(stableKey: String) {
        val store = libraryStore ?: return
        val keys = writeKeysForStableKey(stableKey) ?: run {
            RovaLog.e("HistoryViewModel.toggleFavorite: item not in current list for $stableKey")
            _sidecarWriteError.update { it + 1 }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Migrate legacy aliases into canonical first (identity transform = merge + drop
                // alias), THEN apply the real transform to the canonical entry.
                keys.dropLast(1).forEach { store.update(it) { e -> e } }
                store.update(keys.last()) { it.copy(favorite = !it.favorite) }
                _sidecarRevision.update { it + 1 } // success → recompute reads the new snapshot
            } catch (t: Throwable) {
                RovaLog.e("HistoryViewModel.toggleFavorite: sidecar write failed for $stableKey", t)
                _sidecarWriteError.update { it + 1 } // UI unchanged; surface a non-blocking notice
            }
        }
    }
```

(Verify `MetaKey`'s parameter names in `RecordingIdentity.kt` before writing — use positional args if they differ. Delete the old `metaKeyForStableKey`.)

- [ ] **Step 4: Run the new test + full suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibrarySessionKeysTest"` → PASS (4 tests).
Run: `./gradlew :app:testDebugUnitTest` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibrarySessionKeys.kt app/src/test/java/com/aritr/rova/ui/library/LibrarySessionKeysTest.kt app/src/main/java/com/aritr/rova/ui/library/RecordingIdentity.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt
git commit -m "feat(library): session-key expansion + canonical metadata fallback (PR-B T3)"
```

---

### Task 4: Latest-row accent colors (AA across 12 palettes)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryColors.kt` (data class + `rememberLibraryColors` + `LibraryColorSpec`)
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryLatestColorsTest.kt`

**Interfaces:**
- Consumes: `RovaPalette` (`accent`, `accentOnDark`, `textHigh`, `surfaceBase`, `isLight`), `ContrastMath` (`relativeLuminance(r,g,b)`, `contrastRatio(l1,l2)`), `rovaPalettes` map + `ThemeSelection.entries` (test).
- Produces: `LibraryColors.latestContainer/latestEdge/latestEyebrow: Color`; `LibraryColorSpec.latestContainer(p)/latestEdge(p)/latestContainerOver(p)/latestEyebrow(p)/contrastOver(fg,bg)` — Task 5's row composable reads the `LibraryColors` fields.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import com.aritr.rova.ui.theme.ThemeSelection
import com.aritr.rova.ui.theme.rovaPalettes
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec §3.3 contrast clause: the latest-row tinted container + eyebrow must pass AA across all
 * 12 palettes. The eyebrow is labelSmall (small text) → 4.5:1 floor, computed over the WORST-case
 * opaque background (tint composited on the palette's base surface). Same per-palette iteration
 * pattern as RecordAccentContrastTest.
 */
class LibraryLatestColorsTest {

    private val concrete = ThemeSelection.entries.filter { it != ThemeSelection.FOLLOW_SYSTEM }

    @Test
    fun eyebrow_meetsAA_overTintedContainer_everyPalette() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val ratio = LibraryColorSpec.contrastOver(
                LibraryColorSpec.latestEyebrow(p),
                LibraryColorSpec.latestContainerOver(p),
            )
            assertTrue("$sel: latest eyebrow must be >= 4.5:1 (was ${"%.2f".format(ratio)}:1)", ratio >= 4.5)
        }
    }

    @Test
    fun container_isTranslucentIdentityTint_everyPalette() {
        concrete.forEach { sel ->
            val p = rovaPalettes.getValue(sel)
            val c = LibraryColorSpec.latestContainer(p)
            assertTrue("$sel: container must be a restrained tint (alpha <= 0.2)", c.alpha <= 0.2f)
            assertTrue(
                "$sel: container hue must come from the palette accent",
                c.red == p.accent.red && c.green == p.accent.green && c.blue == p.accent.blue,
            )
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryLatestColorsTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Implement**

`LibraryColorSpec` — append a new section (imports: `androidx.compose.ui.graphics.compositeOver`, `com.aritr.rova.ui.theme.RovaPalette` is already imported in the file):

```kotlin
    // ── Latest-row accent (spec §3.3 — identity family, retints with the theme) ──
    /** Tint alpha of the latest-row container laid over the glass card. */
    private const val LATEST_TINT_ALPHA = 0.10f

    /** Translucent identity tint layered over the latest row's glass card. */
    fun latestContainer(p: RovaPalette): Color = p.accent.copy(alpha = LATEST_TINT_ALPHA)

    /** Hairline accent border of the latest row. */
    fun latestEdge(p: RovaPalette): Color = p.accent.copy(alpha = 0.45f)

    /** Worst-case opaque background the eyebrow sits on (tint composited on the base surface). */
    fun latestContainerOver(p: RovaPalette): Color = latestContainer(p).compositeOver(p.surfaceBase)

    /**
     * "Latest" eyebrow colour — accent-family candidate with an AA fallback: when the palette's
     * accent can't reach 4.5:1 over the tinted container (labelSmall = small text), fall back to
     * textHigh so the eyebrow NEVER ships below AA (spec §3.3; LibraryLatestColorsTest ×12).
     */
    fun latestEyebrow(p: RovaPalette): Color {
        val bg = latestContainerOver(p)
        val candidate = if (p.isLight) p.accent else p.accentOnDark
        return if (contrastOver(candidate, bg) >= 4.5) candidate else p.textHigh
    }

    /** WCAG ratio of [fg] composited over the opaque [bg] (pure — ContrastMath substrate). */
    fun contrastOver(fg: Color, bg: Color): Double {
        val c = fg.compositeOver(bg)
        fun lum(x: Color) = ContrastMath.relativeLuminance(
            (x.red * 255).toInt(), (x.green * 255).toInt(), (x.blue * 255).toInt(),
        )
        return ContrastMath.contrastRatio(lum(c), lum(bg))
    }
```

(Check `ContrastMath`'s exact package/import in `TokenContrastTest.kt` usage — it lives with the a11y seams; import accordingly.)

`LibraryColors` data class — add three fields; `rememberLibraryColors` wires them:

```kotlin
    val latestContainer: Color,
    val latestEdge: Color,
    val latestEyebrow: Color,
```

```kotlin
            latestContainer = LibraryColorSpec.latestContainer(palette),
            latestEdge = LibraryColorSpec.latestEdge(palette),
            latestEyebrow = LibraryColorSpec.latestEyebrow(palette),
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryLatestColorsTest"`
Expected: PASS (2 tests). Also run `--tests "com.aritr.rova.ui.library.LibraryColorSpecTest"` → PASS (untouched members).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryColors.kt app/src/test/java/com/aritr/rova/ui/library/LibraryLatestColorsTest.kt
git commit -m "feat(library): latest-row accent colors, AA-proven x12 (PR-B T4)"
```

---

### Task 5: `LibraryListRow` rework — density dims, latest variant, DualShot side actions, autoplay OUT

**Files:**
- Rewrite: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt`
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-es/strings.xml` (new strings)
- Test: none new (composable; the pure inputs are tested in T1/T4; dims in PR-A `LibraryDensityDimensTest`)

**Interfaces:**
- Consumes: `LibraryDensitySpec` (PR-A), `LibraryColors.latestContainer/latestEdge/latestEyebrow` (T4), `LibraryRow.sides/resumePositionMs` (PR-A/T1), existing `OverlayPill`/`OrientationFramePill`/`VideoFrame`/`SessionCaption`/`SmartTitle`/`StorageFormat`/`LibraryIconSpec`/`GlassSurface`/`pressScale`.
- Produces: the new `LibraryListRow` signature below — Task 6's single-list emission calls it. All NEW params are defaulted, so the existing call site (LibraryScreen line ~700) keeps compiling until Task 6 rewires it; the removed `previewUri`/`autoplay` params were passed there, so REMOVE those two arguments from the existing call site in this task (2-line edit, keeps the tree green).

New string resources — `values/strings.xml` (place beside the other `library_*` entries, ~line 828):

```xml
    <string name="library_latest_resume">Resume · %1$s</string>
    <string name="library_a11y_latest_resume">Resume latest recording from %1$s</string>
    <string name="library_a11y_latest_play">Play latest recording</string>
    <string name="library_a11y_play_side">Play %1$s side, %2$s</string>
    <string name="library_side_action_label">%1$s · %2$s</string>
```

`values-es/strings.xml` (same neighborhood):

```xml
    <string name="library_latest_resume">Reanudar · %1$s</string>
    <string name="library_a11y_latest_resume">Reanudar la última grabación desde %1$s</string>
    <string name="library_a11y_latest_play">Reproducir la última grabación</string>
    <string name="library_a11y_play_side">Reproducir el lado %1$s, %2$s</string>
    <string name="library_side_action_label">%1$s · %2$s</string>
```

**Semantics contract for the reworked row (codex plan-review 2026-07-03):** the parent surface stays ONE merged Button-role node carrying [tileDescription] via plain `semantics { }` — NEVER `clearAndSetSemantics` (it would swallow the children). The DualShot side actions and the latest pill are their OWN focusable nodes (Material `TextButton` supplies the Button role) with their own contentDescriptions, matching spec §3.4 "separately-focusable, labeled" — so TalkBack reads: row summary first, then "Play Portrait side …", "Play Landscape side …" / the pill. Device TalkBack pass (Task 8) verifies the traversal.

- [ ] **Step 1: Rewrite the composable**

Replace the file body (keep the package + adjust imports; drop `LibraryAutoplayVideo` import, add `LibraryDensity`/`LibraryDensityDimens`/`LibraryDensitySpec`/`LibrarySessionSide`/`VideoSide`/`rememberLibraryColors`/`TextButton`/`heightIn`/`border`/`background` imports):

```kotlin
/**
 * spec 2026-07-02 §3.2–3.4 — THE session row (single-list presentation). Three faces of one
 * anatomy: normal row / latest-accent row ([latest] — tinted container, hairline accent border,
 * larger thumb, eyebrow + explicit Play/Resume pill) / DualShot session row ([row.sides]
 * non-empty — two explicit ≥48dp side-action buttons; row tap = Portrait, the buttons make the
 * default visible). NO media surface — static thumbnail only (ADR-0030 amendment §2, trust rule).
 * Dimensions come from [dims] (density pref, spec §3.7). One clickable (role Button), merged label;
 * in [isSelectionMode] tap toggles (caller routes [onClick]), long-press [onLongClick] enters.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryListRow(
    row: LibraryRow,
    thumbnail: Bitmap?,
    tileDescription: String,
    durationFallback: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dualShotLabel: String = "",
    dims: LibraryDensitySpec = LibraryDensityDimens.spec(LibraryDensity.COMFORTABLE),
    latest: Boolean = false,
    latestEyebrowText: String = "",
    latestPillText: String = "",
    latestPillDescription: String = "",
    portraitWord: String = "",
    landscapeWord: String = "",
    playSideDescriptionTemplate: String = "",
    sideActionLabelTemplate: String = "",
    onPlaySide: (LibrarySessionSide) -> Unit = {},
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onLongClick: () -> Unit = {},
    selectedLabel: String = "",
    notSelectedLabel: String = "",
) {
    val interactionSource = remember { MutableInteractionSource() }
    val colors = rememberLibraryColors()
    val shape = RoundedCornerShape(if (latest) 16.dp else LibraryDimens.cardRadius)
    GlassSurface(
        role = GlassRole.Card,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (latest) 14.dp else LibraryDimens.screenPadH,
                vertical = LibraryDimens.cardPadV,
            )
            .pressScale(interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics {
                role = Role.Button
                contentDescription = tileDescription
                if (isSelectionMode) stateDescription = if (isSelected) selectedLabel else notSelectedLabel
            },
        shape = shape,
    ) {
        Column(
            Modifier
                .then(
                    if (latest) {
                        Modifier
                            .background(colors.latestContainer, shape)
                            .border(1.dp, colors.latestEdge, shape)
                    } else {
                        Modifier
                    },
                )
                .padding(8.dp),
        ) {
            if (latest && latestEyebrowText.isNotEmpty()) {
                Text(
                    latestEyebrowText,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.latestEyebrow,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }
            Row(
                Modifier.heightIn(min = dims.rowMinHeightDp.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelectionMode) {
                    if (isSelected) {
                        SemanticIcon(
                            glyph = RovaIcons.Select,
                            contentDescription = null,
                            role = IconRole.Accent,
                            modifier = Modifier.padding(end = 8.dp).size(24.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                val thumbW = (if (latest) dims.latestThumbWidthDp else dims.thumbWidthDp).dp
                val thumbH = (if (latest) dims.latestThumbHeightDp else dims.thumbHeightDp).dp
                Box(Modifier.size(width = thumbW, height = thumbH).clip(RoundedCornerShape(LibraryDimens.pillRadius))) {
                    VideoFrame(thumbnail, Modifier.fillMaxSize())
                    // Standard → orientation glyph only; DualShot → "DualShot" label (owner request).
                    Row(
                        Modifier.align(Alignment.TopStart).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (row.topology == CaptureTopology.DualShot) OverlayPill(dualShotLabel)
                        row.orientation?.let { OrientationFramePill(it) }
                    }
                    if (row.durationMs > 0) {
                        OverlayPill(
                            SmartTitle.durationLabel(row.durationMs),
                            Modifier.align(Alignment.BottomEnd).padding(4.dp),
                        )
                    }
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        row.badge?.let { badge ->
                            val icon = LibraryIconSpec.badgeGlyph(badge, row.badgeStopReason)
                            SemanticIcon(
                                imageVector = icon.glyph,
                                contentDescription = null,
                                status = icon.status,
                                modifier = Modifier.padding(end = 5.dp).size(14.dp),
                            )
                        }
                        Text(
                            row.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val clipLabel =
                        if (row.clipCount > 1) pluralStringResource(R.plurals.library_hero_clip_count, row.clipCount, row.clipCount) else ""
                    val durationLabel = if (row.durationMs > 0) SmartTitle.durationLabel(row.durationMs) else durationFallback
                    val meta = SessionCaption.listMeta(
                        clipCountLabel = clipLabel,
                        durationLabel = durationLabel,
                        sizeLabel = StorageFormat.size(row.sizeBytes, Locale.getDefault()),
                    )
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // DualShot session row: two explicit, individually-focusable ≥48dp side actions
            // (spec §3.4 / ADR-0030 amendment §3). Text-only this PR; glyphs are PR-C polish.
            if (row.sides.isNotEmpty()) {
                Row(
                    Modifier.padding(start = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.sides.forEach { s ->
                        val word = if (s.side == VideoSide.PORTRAIT) portraitWord else landscapeWord
                        val dur = if (s.durationMs > 0) SmartTitle.durationLabel(s.durationMs) else durationFallback
                        val cd = String.format(playSideDescriptionTemplate, word, dur)
                        // Label built via the library_side_action_label resource template — an
                        // inline `Text("$word · $dur")` literal would trip checkNoHardcodedUiStrings
                        // (codex plan-review 2026-07-03).
                        val label = String.format(sideActionLabelTemplate, word, dur)
                        TextButton(
                            onClick = { onPlaySide(s) },
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { contentDescription = cd },
                        ) {
                            Text(label)
                        }
                    }
                }
            }
            // Latest anchor: explicit labeled pill; SAME action as the row tap (spec §3.3 —
            // the pill is the visible affordance, not a divergent action).
            if (latest && latestPillText.isNotEmpty()) {
                TextButton(
                    onClick = onClick,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = latestPillDescription },
                ) {
                    Text(latestPillText, color = colors.latestEyebrow)
                }
            }
        }
    }
}
```

- [ ] **Step 2: Fix the existing call site minimally**

In `LibraryScreen.kt` (list branch, ~lines 700–715): delete the two arguments `previewUri = previewUriFor(row.stableKey),` and `autoplay = row.stableKey in autoplayKeys,`. Everything else stays (Task 6 does the full rewire).

- [ ] **Step 3: Build + full suite**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (48 gates green — the two `Text(` calls with variables and the resource-backed strings satisfy `checkNoHardcodedUiStrings`; `TextButton` supplies its own Role).
Run: `./gradlew :app:testDebugUnitTest` → PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt
git commit -m "feat(library): session row rework - density dims, latest variant, side actions (PR-B T5)"
```

---

### Task 6: The swap — VM/UiState wiring + single-list `LibraryScreen` + `LibraryTopBar`

This is the coupled core: `HistoryViewModel`, `LibraryUiState`, `LibraryScreen`, `LibraryTopBar`, and `FocusRestorePolicy` must change together to compile. Deletions of now-dead FILES happen in Task 7.

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` (lines ~251–270, ~314–338, ~371–374)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryUiState.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` (large)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/FocusRestorePolicy.kt`
- Test: update `app/src/test/java/com/aritr/rova/ui/library/FocusRestorePolicyTest.kt`

**Interfaces:**
- Consumes: `LibrarySessionAggregator.aggregate` (PR-A), `LatestRowPolicy.latestKey(visibleRows, sort)` (PR-A), `LibraryDensity`/`LibraryDensityDimens.spec` (PR-A), `LibrarySessionKeys.expand` (T3), `LibraryListRow` new signature (T5), `RovaSettings.libraryDensity` (exists, `RovaSettings.kt:363–365`).
- Produces: `LibraryUiState(rows, hasLoaded, usage, density)`; `HistoryViewModel.refreshDensity()`; `FocusRestorePolicy.targetItemIndex(pendingKey, groupRowKeys)` (heroKey param GONE) — Task 7 relies on nothing else referencing the deleted symbols.

- [ ] **Step 1: `HistoryViewModel` — density in, viewMode/cardPreview out, aggregation wired**

Replace `_viewMode` (lines ~251–258) and `_cardPreview`/`refreshCardPreview` (lines ~260–270) with:

```kotlin
    /**
     * Session-list row density (spec §3.7). Seeded from [RovaSettings.libraryDensity];
     * [refreshDensity] re-reads on resume (the bottom-nav keeps LibraryScreen composed across tab
     * switches — same reseed pattern the retired cardPreview used). Unknown/missing → COMFORTABLE.
     */
    private val _density = MutableStateFlow(readDensity())

    private fun readDensity(): LibraryDensity =
        runCatching { LibraryDensity.valueOf(settings.libraryDensity) }.getOrDefault(LibraryDensity.COMFORTABLE)

    fun refreshDensity() {
        _density.value = readDensity()
    }
```

Replace the combine (keep the Task 2 mapping block inside; only the combine inputs and the `LibraryUiState` build change):

```kotlin
    val libraryUiState: StateFlow<LibraryUiState> =
        combine(items, hasLoaded, _sidecarRevision, _density) { rows, loaded, _, density ->
            val snapshot = libraryStore?.snapshot() ?: emptyMap()
            val locale = Locale.getDefault()
            val tz = TimeZone.getDefault()
            // Spec §3.4 sidecar lazy-merge (Task 2 block, repeated verbatim so this task is
            // self-contained): merge divergent pre-#137 per-side legacy entries portrait-first.
            val legacyBySession: Map<String, List<LibraryMetadataEntry?>> = rows
                .filter { it.sessionId != null && it.side != null }
                .groupBy { it.sessionId!! }
                .filterValues { it.size > 1 }
                .mapValues { (_, group) ->
                    group.sortedBy { if (it.side == VideoSide.PORTRAIT) 0 else 1 }
                        .map { s ->
                            val k = RecordingIdentity.forItem(s.sessionId, s.file?.absolutePath, s.docUri?.toString())
                            k.legacy?.takeIf { it != k.canonical }?.let { snapshot[it] }
                        }
                }
            val mapped = rows.map { item ->
                val key = RecordingIdentity.forItem(item.sessionId, item.file?.absolutePath, item.docUri?.toString())
                val canonical = snapshot[key.canonical]
                val sideLegacies = item.sessionId?.let { legacyBySession[it] }
                val meta = if (sideLegacies != null) {
                    SessionSidecarMerge.resolve(canonical, sideLegacies)
                } else {
                    val legacy = key.legacy?.takeIf { it != key.canonical }?.let { snapshot[it] }
                    LibraryMetadataEntry.merge(canonical, legacy)
                }
                toLibraryRow(item, meta, locale, tz)
            }
            // Spec §3.4: collapse DualShot per-side rows into ONE session row before the UI sees
            // them. Usage folds over the aggregated list: a session counts once, bytes still sum
            // (the session row carries both files' sizes).
            val aggregated = LibrarySessionAggregator.aggregate(mapped)
            LibraryUiState(
                rows = aggregated,
                hasLoaded = loaded,
                usage = UsageAggregator.aggregate(aggregated),
                density = density,
            )
        }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())
```

Delete `setViewMode` (lines ~371–374) and the now-unused `LibraryViewMode` import (line 28).

`LibraryUiState.kt` — full new content:

```kotlin
package com.aritr.rova.ui.library

/**
 * Render state for the session-list Library surface (ADR-0030 amendment 2026-07-02). [rows] are
 * newest-first, DualShot sessions already aggregated to one row (LibrarySessionAggregator); the
 * screen layer filters/sorts/day-groups via LibraryQuery + LibraryDayGrouping. [hasLoaded] mirrors
 * the VM's first-load latch (drives loading placeholder vs empty CTA).
 */
data class LibraryUiState(
    val rows: List<LibraryRow> = emptyList(),
    val hasLoaded: Boolean = false,
    /**
     * Aggregated footprint over [rows] (the FULL library, not the filtered view) — drives the usage
     * summary line (Polish P6). Pure in-memory fold via [UsageAggregator]; no extra disk read.
     */
    val usage: UsageSummary = UsageSummary(0, 0, 0),
    /** Session-list row density (spec §3.7); seeded from RovaSettings.libraryDensity, reseeded on resume. */
    val density: LibraryDensity = LibraryDensity.COMFORTABLE,
)
```

(The `LibraryViewMode` enum at the top of this file is deleted here — Task 6 removes its last references.)

- [ ] **Step 2: `FocusRestorePolicy` — drop the hero slot, fix the flat-bucket header count**

New `targetItemIndex` (keep `shouldScroll` byte-identical — PR #164 fix). The old code counted a day header for EVERY group, but non-chronological sorts (LONGEST/LARGEST) emit ONE `label == ""` bucket whose header the screen does NOT render — a pre-existing off-by-one on the exact return flow the handoff re-verifies (codex plan-review 2026-07-03, Medium). Fixed here via explicit header flags:

```kotlin
    /**
     * Lazy-item index of [pendingKey] in the session list: [0] = recovery/warning header, then per
     * group an OPTIONAL day header (headerless flat bucket under LONGEST/LARGEST — flag false) and
     * its rows. Hero slot removed with the hero (PR-B); flat-bucket flag fixes a pre-existing
     * off-by-one (codex plan-review 2026-07-03). Null = not found (row deleted while away).
     */
    fun targetItemIndex(
        pendingKey: String,
        groupRowKeys: List<List<String>>,
        groupHasHeader: List<Boolean>,
    ): Int? {
        if (pendingKey.isBlank()) return null
        var idx = 1 // [0] = recovery/warning header
        groupRowKeys.forEachIndexed { g, rows ->
            if (groupHasHeader.getOrElse(g) { true }) idx++ // day header
            for (key in rows) {
                if (key == pendingKey) return idx
                idx++
            }
        }
        return null
    }
```

Update `FocusRestorePolicyTest.kt`: remove/adapt hero-key cases (`heroKey` param no longer exists); keep all `shouldScroll` cases untouched; existing group-indexing cases pass `groupHasHeader = List(groups.size) { true }`; ADD one case pinning the flat-bucket fix:

```kotlin
    @Test
    fun flatBucket_noHeader_indexSkipsNoSlot() {
        // LONGEST/LARGEST: one label=="" group, screen renders no day header → rows start at 1.
        val idx = FocusRestorePolicy.targetItemIndex("b", listOf(listOf("a", "b")), listOf(false))
        assertEquals(2, idx) // [0] hdr-recovery-warn, [1] "a", [2] "b"
    }
```

- [ ] **Step 3: `LibraryTopBar` — toggle out**

Remove params `viewMode`, `gridLabel`, `listLabel`, `onToggleView` (lines 33–36), the toggle `IconButton` block (lines 100–115), and the now-unused imports (`LibraryViewMode` line 20, `Icons.AutoMirrored.Filled.ViewList` line 10 — keep `ArrowBack`). Update the KDoc ("grid/list toggle" reference).

- [ ] **Step 4: `LibraryScreen` — the single-list rewrite**

All edits in `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt`:

1. **Delete** `GRID_COLUMNS` (line 112), `gridState` (line 148), the grid imports (lines ~12–16: `lazy.grid.*`, `GridCells`, `GridItemSpan`, `CollectionInfo`, `CollectionItemInfo` — whatever becomes unused), the autoplay block (`autoplayKeys`, lines 189–220), `previewUriFor` (lines 222–224), `renderHero` (lines 460–479), and `reduceMotion` (line 140) IF nothing else in the file uses it after these deletions (grep the file).
2. **Derived block** (replace lines 350–361):

```kotlin
    // ---- derived (pending rows hidden everywhere) ----
    val visibleRows = remember(ui.rows, pending) { pending.visible(ui.rows) }
    // Hero is gone (ADR-0030 amendment §2): the full filtered+sorted collection renders in the
    // list; the latest anchor is an in-timeline accent on the first row under NEWEST only.
    val collection = remember(visibleRows, sort, filter) {
        LibraryQuery.collection(visibleRows, sort, filter, heroKey = null)
    }
    val latestKey = remember(collection, sort) { LatestRowPolicy.latestKey(collection, sort) }
    val groups = remember(collection, sort, nowMillis, locale, tz) { LibraryDayGrouping.groupForSort(collection, sort, nowMillis, locale, tz) }
    // Scrubber segments: leading = the recovery/warnings header only (hero slot gone).
    val leadingItemCount = 1
    val scrubberSegments = remember(groups) {
        ScrubberIndex.segments(groups.map { it.label }, groups.map { it.rows.size }, leadingItemCount)
    }
    val dims = remember(ui.density) { LibraryDensityDimens.spec(ui.density) }
```

3. **Row-resolution helper** — add next to `byKey`/`rowByKey` (after line 184):

```kotlin
    // Aggregated session rows key on session:<id>, which has no VideoItem — resolve via the
    // PORTRAIT-first side key (LibraryRow.sides) for thumbnail/sheet/share (spec §3.4).
    fun itemFor(row: LibraryRow): VideoItem? =
        byKey[row.stableKey] ?: row.sides.firstOrNull()?.let { byKey[it.stableKey] }
```

(`VideoItem` import: `com.aritr.rova.ui.screens.VideoItem` — already referenced in the file.)

4. **`play` gains a side override** (replace lines 292–312; `pendingFocusKey` keeps the ROW key so focus restore targets the list item):

```kotlin
    fun play(rowKey: String, sideKey: String? = null) {
        // Aggregated session row default = Portrait (sides are PORTRAIT-first; ADR-0030 §3 —
        // the side actions make this default visible).
        val resolved = sideKey ?: rowByKey[rowKey]?.sides?.firstOrNull()?.stableKey ?: rowKey
        val item = byKey[resolved] ?: return
        val sid = item.sessionId
        if (sid != null) {
            pendingFocusKey = rowKey // restore focus here on return (row 23)
            // Hand the already-decoded tile thumbnail to the player so it paints over the black
            // shutter until the first video frame renders (no "block" flash on entry).
            com.aritr.rova.ui.screens.player.PlayerPosterHandoff.set(sid, item.thumbnail)
            onOpenPlayer(sid, item.side)
        } else {
            // Legacy file-only row (no manifest): keep the PreviewActivity path.
            item.file?.let { f ->
                pendingFocusKey = rowKey // restore focus here on return (row 23)
                val intent = Intent(context, PreviewActivity::class.java).apply {
                    putExtra("VIDEO_PATH", f.absolutePath)
                    item.shareUri?.let { putExtra("SHARE_URI", it.toString()) }
                }
                context.startActivity(intent)
            }
        }
    }
```

5. **Batch/share/delete fan-out** (T3 expansion at the three `itemsForKeys` call sites):
   - Batch bar `onShare` (line ~547): `shareItems(viewModel.itemsForKeys(LibrarySessionKeys.expand(selection.keys, rowByKey)))`
   - Deferred delete: expand at CONFIRM time, not at snackbar timeout — the commit fires after the undo window, when the captured `rowByKey` may be stale (codex plan-review 2026-07-03, Medium). In `startDeferredDelete` (line ~315), add as the first statement after the empty-check:

```kotlin
        // Fan session rows out to their per-side file keys NOW (confirm time) — the timeout
        // commit runs after the undo window, when the captured rowByKey snapshot may be stale
        // (codex plan-review 2026-07-03). `pending`/selection keep hiding by ROW key.
        val fileKeys = LibrarySessionKeys.expand(keys, rowByKey)
```

   and the commit (line ~335) becomes `val targets = viewModel.itemsForKeys(fileKeys)`.
   - Item-sheet `onShare` (line ~831): `onShare = { sheetTarget = null; shareItems(viewModel.itemsForKeys(LibrarySessionKeys.expand(setOf(row.stableKey), rowByKey))) }`
   - Item-sheet resolution (line ~800): `val item = itemFor(row)` (thumbnail/movable/viewSettings/vault then work for session rows; `movable` stays `row.topology != DualShot && item?.sessionId != null` → session rows correctly not movable).
   - Batch favorite (lines ~549–558) and `batchMoveToVault` (line ~548 → VM) stay UNCHANGED: favorite writes via the T3 canonical fallback; vault self-gates DualShot exactly as before.
6. **New body**: replace the grid/list `if/else` (lines 629–719) with the single `LazyColumn` (list branch shape) and the new row wiring. Labels to hoist beside the other `stringResource` vals (~line 263):

```kotlin
    val playLabel = stringResource(R.string.library_action_play)
    val portraitWord = stringResource(R.string.library_orientation_portrait)
    val landscapeWord = stringResource(R.string.library_orientation_landscape)
    val playSideTemplate = stringResource(R.string.library_a11y_play_side)
    val sideActionTemplate = stringResource(R.string.library_side_action_label)
```

```kotlin
                        Box(Modifier.fillMaxSize().weight(1f)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = 20.dp),
                            ) {
                                item(key = "hdr-recovery-warn") { RecoveryAndWarnings() }
                                groups.forEach { group ->
                                    if (group.label.isNotEmpty()) {
                                        item(key = "hdr-${group.label}") { LibraryDayHeader(group.label, group.sizeTotalLabel) }
                                    }
                                    items(group.rows, key = { it.stableKey }) { row ->
                                        val isLatest = row.stableKey == latestKey
                                        val resumeMs = row.resumePositionMs?.takeIf { it > 0 }
                                        LibraryListRow(
                                            row = row,
                                            thumbnail = itemFor(row)?.thumbnail,
                                            tileDescription = TileSemantics.describe(row, frag),
                                            durationFallback = "—",
                                            dualShotLabel = plLabel,
                                            dims = dims,
                                            latest = isLatest,
                                            latestEyebrowText = eyebrow,
                                            latestPillText = if (isLatest) {
                                                resumeMs?.let { stringResource(R.string.library_latest_resume, SmartTitle.durationLabel(it)) } ?: playLabel
                                            } else {
                                                ""
                                            },
                                            latestPillDescription = if (isLatest) {
                                                resumeMs?.let { stringResource(R.string.library_a11y_latest_resume, SmartTitle.durationLabel(it)) }
                                                    ?: stringResource(R.string.library_a11y_latest_play)
                                            } else {
                                                ""
                                            },
                                            portraitWord = portraitWord,
                                            landscapeWord = landscapeWord,
                                            playSideDescriptionTemplate = playSideTemplate,
                                            sideActionLabelTemplate = sideActionTemplate,
                                            onPlaySide = { s ->
                                                if (selection.active) onTileClick(row.stableKey) else play(row.stableKey, s.stableKey)
                                            },
                                            onClick = { onTileClick(row.stableKey) },
                                            modifier = if (row.stableKey == pendingFocusKey) Modifier.focusRequester(rowFocusRequester) else Modifier,
                                            isSelectionMode = selection.active,
                                            isSelected = row.stableKey in selection.keys,
                                            onLongClick = { onTileLong(row.stableKey) },
                                            selectedLabel = selectedLabel,
                                            notSelectedLabel = notSelectedLabel,
                                        )
                                    }
                                }
                            }
                            // Date fast-scroll rail (self-hides when < 2 day groups).
                            LibraryScrubber(
                                segments = scrubberSegments,
                                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                                railLabel = scrubberRailLabel,
                                onScrollToItemIndex = { idx -> coroutineScope.launch { listState.scrollToItem(idx) } },
                                modifier = Modifier.align(Alignment.CenterEnd),
                            )
                        }
```

   The filtered-empty condition (line 605) becomes `if (collection.isEmpty()) {`.
7. **Selection reconcile source** (lines 233–235): selection keys are now ROW keys (`session:<id>` for aggregated rows), which never appear in `items` keys — the current reconcile would silently clear session-row selections on every items emission (codex plan-review 2026-07-03, High). Replace:

```kotlin
    LaunchedEffect(ui.rows) {
        // Reconcile against ROW keys, not VideoItem keys: aggregated session rows key on
        // session:<id>, which has no VideoItem (codex plan-review 2026-07-03).
        selection = SelectionReducer.reconcile(selection, ui.rows.map { it.stableKey }.toSet())
    }
```

8. **Focus-restore effect** (lines 364–418): `viewModel.refreshCardPreview()` → `viewModel.refreshDensity()` (the density reseed keeps the same resume-pickup contract — PR-C's toggle and adb pref flips land on return); delete `currentHeroKey` (line 367) and `currentViewMode` (line 369); add `val currentGroupHeaders by rememberUpdatedState(groups.map { it.label.isNotEmpty() })` beside `currentGroupKeys`; the call becomes `FocusRestorePolicy.targetItemIndex(key, currentGroupKeys, currentGroupHeaders)`; collapse the grid/list dispatch to the list arm only; visible-keys line (406) loses `.map { it.removePrefix("hero-") }`:

```kotlin
                val visibleKeys = { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
                if (FocusRestorePolicy.shouldScroll(key, visibleKeys())) {
                    listState.scrollToItem(index)
                    snapshotFlow { key in visibleKeys() }.first { it }
                }
```

9. **Top bar call** (lines 517–535): drop `viewMode`/`gridLabel`/`listLabel`/`onToggleView` args.
10. **Sheet header meta** for DualShot session rows is correct as-is (`row.durationMs` = MAX across sides, `row.sizeBytes` = sum — the aggregator contract).

- [ ] **Step 5: Build + full suite + gate check**

Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (48 gates).
Run: `./gradlew :app:testDebugUnitTest` → PASS (FocusRestorePolicyTest updated; everything else untouched).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt app/src/main/java/com/aritr/rova/ui/library/LibraryUiState.kt app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryTopBar.kt app/src/main/java/com/aritr/rova/ui/library/FocusRestorePolicy.kt app/src/test/java/com/aritr/rova/ui/library/FocusRestorePolicyTest.kt
git commit -m "feat(library): single-list presentation - aggregation, latest anchor, density wired (PR-B T6)"
```

---

### Task 7: Deletions — hero/grid/autoplay files, prefs, Settings row, dead strings + tests

Everything below is now unreferenced (Task 6 removed the last call sites). For EACH deletion target, grep first (`Grep pattern:"<SymbolName>" path:app`) — if a hit remains outside the file being deleted, STOP and fix the reference instead of forcing the delete.

**Files:**
- Delete: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryHeroCard.kt`
- Delete: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt`
- Delete: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryAutoplayVideo.kt`
- Delete: `app/src/main/java/com/aritr/rova/ui/library/AutoplayPolicy.kt` + `app/src/test/java/com/aritr/rova/ui/library/AutoplayPolicyTest.kt`
- Delete: `app/src/main/res/layout/library_autoplay_preview.xml`
- Delete (if sole consumer was the autoplay/hero pair — grep `HeroUnderlayPolicy` and `HeroMetaFormatter`): `HeroUnderlayPolicy.kt` (+ its test), `HeroMetaFormatter.kt` (+ its test)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryQuery.kt` — delete `hero` (line 13) and `heroFor` (lines 15–17); update the KDoc (hero language out; `collection`'s `heroKey` param STAYS, documented as "always null since PR-B; retained for API stability per codex").
- Modify: `app/src/main/java/com/aritr/rova/data/RovaSettings.kt` — delete `libraryCardPreview` (lines 350–352) and `libraryViewMode` (lines 357–359); keep `libraryDensity`.
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsViewModel.kt` — delete line 37 (`libraryCardPreview` MutableStateFlow) and line 122 (its collector).
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` — delete line 132 (state read) and the whole Library `SettingsSection` (lines 360–368).
- Modify: `app/src/main/res/values/strings.xml` + ALL translated copies (grep `library_view_grid` across `app/src/main/res` to enumerate) — delete `library_view_grid`, `library_view_list`, `settings_library_card_preview_title`, `settings_library_card_preview_summary`, `settings_section_library`.
- Modify tests: `LibraryQueryTest.kt` — delete the four `heroFor_*` tests (lines ~66–98); `LibraryQueryHeroDedupTest.kt` — delete `hero(...)`-based tests, KEEP the `collection` heroKey-dedup tests (param still exists); `RovaSettingsTest.kt` — remove `libraryViewMode`/`libraryCardPreview` assertions (~lines 404/423), keep/extend `libraryDensity` ones.

**Interfaces:**
- Consumes: Task 6's completed rewire (no remaining references).
- Produces: a tree with zero dead presentation code — the PR's "old grid/hero/autoplay deleted" done-condition.

- [ ] **Step 1: Grep-then-delete each target** (order above; fix any straggler references — expected NONE after Task 6).

- [ ] **Step 2: Full suite + build**

Run: `./gradlew :app:testDebugUnitTest` → PASS, 0-0-0, count ABOVE the 2202 baseline (new: ~15 added vs ~AutoplayPolicyTest/heroFor removals — verify net positive and report the exact number).
Run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (48 gates; `checkNoHardcodedUiStrings` also validates the strings removal left no dangling references — the build would fail on a missing `R.string`).

- [ ] **Step 3: Commit**

```bash
git add -A app/src/main app/src/test
git commit -m "refactor(library): delete grid/hero/autoplay presentation + retired prefs (PR-B T7)"
```

---

### Task 8: Final verification + review-gate handoff

**Files:** none new.

- [ ] **Step 1: Full JVM suite** — `./gradlew :app:testDebugUnitTest` → 0 failures / 0 errors / 0 skipped, report count vs 2202 baseline.
- [ ] **Step 2: Build with gates** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL, all 48 gates (this PR adds no gate; `checkLibraryNoManifestWrite`, `checkNoHardcodedUiStrings`, `checkA11y*` are the ones this PR could plausibly trip).
- [ ] **Step 3: Dead-reference sweep** — grep `LibraryViewMode|LibraryHeroCard|LibraryGridCard|LibraryAutoplayVideo|AutoplayPolicy|heroFor|libraryViewMode|libraryCardPreview|refreshCardPreview` over `app/` → zero hits.
- [ ] **Step 4: Report ready for review-gate** (code-review + codex on the branch diff), then device-verify on RZCYA1VBQ2H per the handoff checklist: single-column day-grouped list; latest accent only under Newest; DualShot ONE row, both side actions play the right file; favorite/rename on a DualShot session reflects on the session row; batch delete removes both side files; density pref persists (flip via adb — `adb shell am force-stop com.aritr.rova` after editing the pref, or reseed on resume); no autoplay; TalkBack sanity on rows + side actions; player→library return does not jump-scroll (PR #164 regression check). Then PR (base master) — NO push without owner GO.

---

## Self-review notes (spec §7 PR-B coverage)

- Single-list `LibraryScreen`, grid/`LibraryViewMode`/toggle deleted → T6 + T7.
- Session row + latest accent + DualShot side actions, density dims → T4 + T5 + T6.
- Aggregator + `LatestRowPolicy` (full collection, `heroKey = null`) + `_cardPreview`→`_density` wiring → T6.
- Hero/`heroFor`/autoplay/`libraryCardPreview` (+ Settings row) deletions → T7.
- Sidecar lazy-merge (read-path) → T2. Batch ops via `LibraryRow.sides` → T3 + T6. Vault status quo → untouched (self-gating verified).
- Focus-restore simplification, `shouldScroll` intact → T6.
- Tests same-PR: T1–T4 pure tests, FocusRestorePolicyTest updated (+ flat-bucket case), hero tests retired → T1–T7.
- NOT in PR-B (PR-C): sticky headers/`LibraryDateLabels` wiring, midnight refresh, scrubber fix + 16dp thumb, density toggle UI, side-action glyphs.

## codex plan-review folds (2026-07-03)

1. **High — legacy-alias resurrection on clear-writes:** session/DualShot sidecar writes migrate ALL side legacy aliases into canonical (identity transforms) before the real transform → `writeKeysForStableKey` (T3/T6). Without it, unfavorite/clear-rename resurrects from a pre-#137 path-keyed entry via the OR/`?:` read merge.
2. **High — selection reconcile:** now reconciles against `ui.rows` keys, not `items` keys — session-row selections survived (T6 §7).
3. **Medium — deferred delete:** side-key expansion happens at confirm time, not at snackbar timeout (stale `rowByKey`) (T6 §5).
4. **Medium — `checkNoHardcodedUiStrings`:** side-action label goes through the `library_side_action_label` resource template, no inline `Text("…")` literal (T5).
5. **Medium — focus-restore flat-bucket off-by-one (pre-existing):** `targetItemIndex` gains `groupHasHeader` flags + pinning test (T6 §2).
6. **Medium — row semantics:** explicit parent-merged / children-separate contract documented; no `clearAndSetSemantics` (T5).
