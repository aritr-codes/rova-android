# Library/History Slice 5 — A11y Close-out Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Subagents are EDIT-ONLY; the controller runs all gradle + commits. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Close WCAG 2.2 AA remediation-backlog rows 21 / 23 (Library subset) / 32 — player `SegmentedTimeline` progressbar role + per-cell labels, and Library warning-card focus separation + focus visibility + focus restore on return from the player.

**Architecture:** Mirror the Slice-4 `LibraryScrubber` discrete-progress pattern (progress node + SEPARATE polite live-region node) onto `SegmentedTimeline`, but with continuous read-only progress and per-cell traversable labels (no overlay leaf — codex-reviewed). For focus restore, a pure-Kotlin `FocusRestorePolicy` computes the lazy-item index for a launched row's `stableKey`; `LibraryScreen` sets a `rememberSaveable` pending key on play, then on lifecycle `ON_RESUME` scrolls, awaits composition via `snapshotFlow`, and calls `FocusRequester.requestFocus()`.

**Tech Stack:** Kotlin, Jetpack Compose, Compose semantics (`progressBarRangeInfo`, `liveRegion`, `isTraversalGroup`, `FocusRequester`), JUnit JVM tests.

---

## Backlog rows (authoritative wording — verified 2026-06-15)

- **Row 21** | PLR-04, PLR-06 | Moderate | "SegmentedTimeline missing progressbar role + per-cell labels" → Task A.
- **Row 23** | REC-15, RECOV-09, RECOV-10, NAV-04, SHAR-08, SHAR-16, ONB-06, WARN-08 | Moderate | "Focus order / focus restore / focusable() gaps across screens & dialogs" → **Library-relevant subset only** (focus restore on player return; warning-host focusable). **DELTA:** REC-15/RECOV-09/RECOV-10/NAV-04/SHAR-08/SHAR-16/ONB-06 touch Record/Recovery-dialog/Nav/Shared/Onboarding screens — OUT of the Library reskin scope; they stay OPEN in the backlog. Recorded as delta, not closed.
- **Row 32** | HIST-02, HIST-17 | Moderate | "History warning-card overlapping focus + unverified focus visibility" → Task B separation + focus visibility.

## File structure

**Task A (player timeline):**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/SegmentedTimeline.kt` — add semantics (progressbar on Row, per-cell CD on each cell, separate live-region node).
- Modify: `app/src/main/res/values/strings.xml` + `app/src/main/res/values-es/strings.xml` — 4 new keys.
- No new pure helper / JVM test: `SegmentedTimelineMath` already supplies cell kind + clip index; the change is composable-only semantics (not JVM-testable), matching the `LibraryScrubber` precedent.

**Task B (Library focus):**
- Create: `app/src/main/java/com/aritr/rova/ui/library/FocusRestorePolicy.kt` — pure index computation.
- Create: `app/src/test/java/com/aritr/rova/ui/library/FocusRestorePolicyTest.kt` — JVM test.
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` — `isTraversalGroup` wrap on `RecoveryAndWarnings`, `pendingFocusKey` state, set on `play()`, lifecycle-resume restore wiring, attach `FocusRequester` to matching row.
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/HistoryWarningStrip.kt` — `.focusHighlight(...)` on `HistoryWarningCard`.
- Modify (only if needed): `LibraryListRow` signature to accept a `modifier` param (grid card already has one) so the focus modifier can be attached.

---

## Task A: SegmentedTimeline progressbar role + per-cell labels (Row 21)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/player/SegmentedTimeline.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`

- [ ] **Step 1: Add 4 string keys to `values/strings.xml`**

Add near the existing player keys (`player_clip_n_of_m` at ~line 367):

```xml
<string name="player_timeline_cd">Recording timeline</string>
<string name="player_timeline_segment_recorded">Segment %1$d of %2$d, recorded</string>
<string name="player_timeline_segment_playing">Segment %1$d of %2$d, now playing</string>
<string name="player_timeline_segment_upcoming">Segment %1$d of %2$d, not yet played</string>
```

- [ ] **Step 2: Add the same 4 keys to `values-es/strings.xml`**

```xml
<string name="player_timeline_cd">Línea de tiempo de grabación</string>
<string name="player_timeline_segment_recorded">Segmento %1$d de %2$d, grabado</string>
<string name="player_timeline_segment_playing">Segmento %1$d de %2$d, reproduciéndose</string>
<string name="player_timeline_segment_upcoming">Segmento %1$d de %2$d, sin reproducir</string>
```

- [ ] **Step 3: Rewrite `SegmentedTimeline` body to add semantics**

Replace the whole `@Composable internal fun SegmentedTimeline(...)` body. The Row carries the progressbar role + overall CD + `isTraversalGroup` (children stay traversable — codex: do NOT use a same-bounds overlay leaf). Each cell carries a per-cell `contentDescription`. A separate 1.dp `Box` is a sparse polite live region announcing "Clip N of M" only when `currentClipIndex` changes.

```kotlin
@Composable
internal fun SegmentedTimeline(
    segmentDurationsMs: List<Long>,
    positionMs: Long,
    modifier: Modifier = Modifier
) {
    val state = SegmentedTimelineMath.compute(segmentDurationsMs, positionMs)
    val totalDurationMs = segmentDurationsMs.sum().coerceAtLeast(1L)
    val timelineCd = stringResource(R.string.player_timeline_cd)
    val recordedTmpl = stringResource(R.string.player_timeline_segment_recorded)
    val playingTmpl = stringResource(R.string.player_timeline_segment_playing)
    val upcomingTmpl = stringResource(R.string.player_timeline_segment_upcoming)
    val total = state.totalClips
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .semantics {
                    isTraversalGroup = true
                    contentDescription = timelineCd
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        current = positionMs.toFloat().coerceIn(0f, totalDurationMs.toFloat()),
                        range = 0f..totalDurationMs.toFloat(),
                        steps = 0, // continuous, read-only (not seekable)
                    )
                }
        ) {
            state.cells.forEachIndexed { index, cell ->
                val cellLabel = when (cell) {
                    is SegmentedTimelineMath.Cell.Done -> recordedTmpl.format(index + 1, total)
                    is SegmentedTimelineMath.Cell.Current -> playingTmpl.format(index + 1, total)
                    is SegmentedTimelineMath.Cell.Upcoming -> upcomingTmpl.format(index + 1, total)
                }
                val cellModifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .semantics { contentDescription = cellLabel }
                when (cell) {
                    is SegmentedTimelineMath.Cell.Done -> {
                        Box(modifier = cellModifier.background(Color.White.copy(alpha = 0.55f)))
                    }
                    is SegmentedTimelineMath.Cell.Upcoming -> {
                        Box(modifier = cellModifier.background(Color.White.copy(alpha = 0.18f)))
                    }
                    is SegmentedTimelineMath.Cell.Current -> {
                        Box(modifier = cellModifier.background(Color.White.copy(alpha = 0.18f))) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(cell.fillFraction.coerceIn(0f, 1f))
                                    .background(Color.White.copy(alpha = 0.90f))
                            )
                        }
                    }
                }
                if (index != state.cells.lastIndex) {
                    Box(modifier = Modifier.padding(horizontal = 1.5.dp))
                }
            }
        }
        // Sparse polite live region: announces "Clip N of M" ONLY on clip-index change
        // (decoupled from per-frame position updates — mirrors LibraryScrubber).
        val clipAnnounce = stringResource(R.string.player_clip_n_of_m, state.currentClipIndex, state.totalClips)
        Box(
            Modifier.size(1.dp).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = clipAnnounce
            }
        )
    }
}
```

- [ ] **Step 4: Add the new imports to `SegmentedTimeline.kt`**

Ensure these are present (some already are):

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import com.aritr.rova.R
```

- [ ] **Step 5 (controller): build**

Run: `gradlew.bat :app:assembleDebug` (WARM — no `--stop`, no cache wipe).
Expected: BUILD SUCCESSFUL, all 42 `check*` gates pass (incl. `checkNoHardcodedUiStrings` — all new text is in resources).

- [ ] **Step 6 (controller): commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/player/SegmentedTimeline.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(a11y): SegmentedTimeline progressbar role + per-cell labels (Slice 5, row 21)"
```

---

## Task B: Library warning-card focus separation + visibility + restore (Rows 32 + 23 subset)

### Task B1: pure `FocusRestorePolicy`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/FocusRestorePolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/FocusRestorePolicyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusRestorePolicyTest {

    // Lazy-list item order: [0]=recovery/warn header, [1]=hero (if present),
    // then per group: day-header then the group's rows.
    private val groups = listOf(
        listOf("a", "b"),   // group 0
        listOf("c"),        // group 1
    )

    @Test
    fun heroRow_resolvesToIndex1() {
        assertEquals(1, FocusRestorePolicy.targetItemIndex("hero1", "hero1", groups))
    }

    @Test
    fun firstGroupFirstRow_withHero() {
        // 0 hdr, 1 hero, 2 day-hdr(group0), 3 "a", 4 "b", 5 day-hdr(group1), 6 "c"
        assertEquals(3, FocusRestorePolicy.targetItemIndex("a", "hero1", groups))
        assertEquals(4, FocusRestorePolicy.targetItemIndex("b", "hero1", groups))
        assertEquals(6, FocusRestorePolicy.targetItemIndex("c", "hero1", groups))
    }

    @Test
    fun noHero_shiftsIndicesDownByOne() {
        // 0 hdr, 1 day-hdr(group0), 2 "a", 3 "b", 4 day-hdr(group1), 5 "c"
        assertEquals(2, FocusRestorePolicy.targetItemIndex("a", null, groups))
        assertEquals(5, FocusRestorePolicy.targetItemIndex("c", null, groups))
    }

    @Test
    fun missingKey_returnsNull() {
        assertNull(FocusRestorePolicy.targetItemIndex("zzz", "hero1", groups))
    }

    @Test
    fun blankKey_returnsNull() {
        assertNull(FocusRestorePolicy.targetItemIndex("", "hero1", groups))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.FocusRestorePolicyTest"`
Expected: FAIL — `FocusRestorePolicy` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.aritr.rova.ui.library

/**
 * Pure helper for Slice-5 focus restore (remediation-backlog row 23 subset): maps a launched
 * library row's [stableKey][LibraryRow.stableKey] to its flattened lazy-list item index so the
 * caller can `scrollToItem(index)` before requesting focus. Same index sequence for grid and list
 * (every grid cell is its own keyed lazy item).
 *
 * Item order mirrors [LibraryScreen]'s lazy content: index 0 = recovery/warning header,
 * index 1 = hero (when present), then for each day-group a header item followed by its row items.
 */
object FocusRestorePolicy {

    /**
     * @param pendingKey the stableKey of the row that launched playback.
     * @param heroKey the hero row's stableKey, or null when no hero is shown.
     * @param groupRowKeys per-day-group ordered stableKeys (each inner list = one group's rows).
     * @return the lazy item index to scroll to, or null if [pendingKey] is blank or not found.
     */
    fun targetItemIndex(
        pendingKey: String,
        heroKey: String?,
        groupRowKeys: List<List<String>>,
    ): Int? {
        if (pendingKey.isBlank()) return null
        var idx = 1 // [0] = recovery/warning header
        if (heroKey != null) {
            if (pendingKey == heroKey) return idx
            idx++
        }
        for (rows in groupRowKeys) {
            idx++ // day header
            for (key in rows) {
                if (key == pendingKey) return idx
                idx++
            }
        }
        return null
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.FocusRestorePolicyTest"`
Expected: PASS (5 tests).

- [ ] **Step 5 (controller): commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/FocusRestorePolicy.kt app/src/test/java/com/aritr/rova/ui/library/FocusRestorePolicyTest.kt
git commit -m "feat(a11y): FocusRestorePolicy pure helper for Library focus restore (Slice 5, row 23)"
```

### Task B2: warning-card focus visibility

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/HistoryWarningStrip.kt`

- [ ] **Step 1: Add `.focusHighlight(...)` to `HistoryWarningCard`**

In the `HistoryWarningCard` `Row` modifier chain, insert `.focusHighlight(RoundedCornerShape(8.dp))` **after `.clip(...)`/`.border(...)` and before `.clickable(...)`** (house order — see `FocusHighlight.kt`). The card stays a single button-like node (no nested clickable descendants — codex). Resulting chain:

```kotlin
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
            .focusHighlight(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = stringResource(R.string.warning_view_action_label), role = Role.Button, onClick = onClick)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
```

- [ ] **Step 2: Add the import**

```kotlin
import com.aritr.rova.ui.components.focusHighlight
```

- [ ] **Step 3 (controller): commit** (built together with B3 — see B3 Step 8)

### Task B3: focus separation + focus restore in `LibraryScreen`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt`

- [ ] **Step 1: Focus SEPARATION — wrap `RecoveryAndWarnings` content in an `isTraversalGroup` Column (HIST-02)**

Change the `RecoveryAndWarnings` composable so its content is wrapped in a `Column` carrying `Modifier.semantics { isTraversalGroup = true }` — making the recovery/warning host its own traversal group, not merged with sibling library rows:

```kotlin
@Composable
fun RecoveryAndWarnings() {
    Column(modifier = Modifier.semantics { isTraversalGroup = true }) {
        HistoryWarningStrip(
            warningIds = historyWarnings,
            onDismiss = { warningVm?.dismissOnHistoryStrip(it) },
            onOpenSheet = { sheetWarningId = it },
        )
        if (recoveryUiState.cards.isNotEmpty() || recoveryUiState.hiddenCount > 0) {
            RecoveryCardList(
                state = recoveryUiState,
                onDiscard = { recoveryViewModel.dismiss(it) },
                vendorHelpSlotFor = vendorHelpSlotFor,
                onMerge = { recoveryViewModel.merge(it) },
                onKeepRaw = { recoveryViewModel.keepRaw(it) },
            )
        }
    }
}
```

- [ ] **Step 2: Declare focus-restore state near the existing `LibraryScreen` state (next to the `rememberSaveable` search/sort block)**

```kotlin
    var pendingFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    val rowFocusRequester = remember { FocusRequester() }
```

- [ ] **Step 3: Set `pendingFocusKey` when a row actually launches playback**

In `fun play(stableKey: String)`, set the pending key in BOTH launch branches (manifest player + legacy PreviewActivity) — but NOT in the selection-toggle path:

```kotlin
    fun play(stableKey: String) {
        val item = byKey[stableKey] ?: return
        val sid = item.sessionId
        if (sid != null) {
            pendingFocusKey = stableKey
            onOpenPlayer(sid, item.side)
        } else {
            item.file?.let { f ->
                pendingFocusKey = stableKey
                val intent = Intent(context, PreviewActivity::class.java).apply {
                    putExtra("VIDEO_PATH", f.absolutePath)
                    item.shareUri?.let { putExtra("SHARE_URI", it.toString()) }
                }
                context.startActivity(intent)
            }
        }
    }
```

- [ ] **Step 4: Add the lifecycle-resume restore effect**

Place after `hero`/`groups`/`ui` are computed and after `gridState`/`listState` exist. Uses `rememberUpdatedState` so the observer always reads current data; guards on `pendingFocusKey != null` (ON_RESUME also fires on first entry); awaits composition via `snapshotFlow` before `requestFocus()` (codex):

```kotlin
    val currentHeroKey by rememberUpdatedState(hero?.stableKey)
    val currentGroupKeys by rememberUpdatedState(groups.map { g -> g.rows.map { it.stableKey } })
    val currentViewMode by rememberUpdatedState(ui.viewMode)
    val restoreScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val key = pendingFocusKey ?: return@LifecycleEventObserver
            val index = FocusRestorePolicy.targetItemIndex(key, currentHeroKey, currentGroupKeys)
            if (index == null) {
                pendingFocusKey = null
                return@LifecycleEventObserver
            }
            restoreScope.launch {
                if (currentViewMode == LibraryViewMode.GRID) {
                    gridState.scrollToItem(index)
                    snapshotFlow { gridState.layoutInfo.visibleItemsInfo.any { it.key == key } }.first { it }
                } else {
                    listState.scrollToItem(index)
                    snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key == key } }.first { it }
                }
                runCatching { rowFocusRequester.requestFocus() }
                pendingFocusKey = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
```

- [ ] **Step 5: Attach the `FocusRequester` to the matching row in BOTH grid and list**

The card root is already focusable via its internal `.clickable` — attaching `.focusRequester(...)` above it associates the requester. Build a focus modifier and merge it into each card's `modifier`.

Grid (`LibraryGridCard` call) — the call already passes `modifier = Modifier.padding(...)`; chain the focus modifier:

```kotlin
                modifier = Modifier
                    .padding(com.aritr.rova.ui.library.components.LibraryDimens.gridGutter)
                    .then(if (row.stableKey == pendingFocusKey) Modifier.focusRequester(rowFocusRequester) else Modifier),
```

Also apply to the hero, since the hero is a launchable row (so a hero-launched playback can restore):

```kotlin
    // in renderHero(...) root modifier, or the hero card call:
    .then(if (hero.stableKey == pendingFocusKey) Modifier.focusRequester(rowFocusRequester) else Modifier)
```

List (`LibraryListRow` call) — `LibraryListRow` currently takes no `modifier`. Add a `modifier: Modifier = Modifier` param to `LibraryListRow` (applied to its root, before the internal clickable), then pass:

```kotlin
                modifier = if (row.stableKey == pendingFocusKey) Modifier.focusRequester(rowFocusRequester) else Modifier,
```

- [ ] **Step 6: Add imports to `LibraryScreen.kt`**

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
// snapshotFlow is androidx.compose.runtime.snapshotFlow (add if missing)
```

> Note: `LocalLifecycleOwner` moved to `androidx.compose.ui.platform.LocalLifecycleOwner` in recent Compose; if the project still imports `androidx.lifecycle.compose.LocalLifecycleOwner` elsewhere, match the existing project import (grep first).

- [ ] **Step 7: Add `modifier` param to `LibraryListRow` (if not already present)**

In `LibraryListRow.kt`, add `modifier: Modifier = Modifier` to the signature and apply it FIRST in the root composable's modifier chain (before clip/clickable) so the focusRequester attaches above the clickable. If `LibraryListRow` already accepts `modifier`, skip.

- [ ] **Step 8 (controller): build + commit B2 + B3 together**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 42 gates pass (incl. `checkLibraryNoManifestWrite` — NO `SessionManifest` writes added; `checkA11yClickableHasRole` — warning card keeps `role = Role.Button`).
Run: `gradlew.bat :app:testDebugUnitTest`
Expected: full suite GREEN (baseline + `FocusRestorePolicyTest`).

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt app/src/main/java/com/aritr/rova/ui/warnings/HistoryWarningStrip.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt
git commit -m "feat(a11y): Library warning-card focus separation + visibility + restore (Slice 5, rows 32/23)"
```

---

## Final verification (controller)

- [ ] `gradlew.bat :app:assembleDebug` → BUILD SUCCESSFUL, 42 gates green.
- [ ] `gradlew.bat :app:testDebugUnitTest` → full suite green incl. `FocusRestorePolicyTest`.
- [ ] codex peer-review the as-built diff (a11y semantics + focus-restore logic).
- [ ] Install on RZCYA1VBQ2H: `adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk`.
- [ ] Device smoke (TalkBack ON):
  - Player: timeline announces overall progress (progressbar) + each segment cell ("Segment N of M, recorded/playing/upcoming"); "Clip N of M" announced on clip change, NOT every second.
  - Library: warning card reads as its own node / traversal group, distinct from sibling rows; focus ring visible on D-pad focus.
  - Focus returns to the row that launched playback after `popBackStack()` from the player.

## Constraints reminder
- Subagents EDIT-ONLY; controller runs all gradle + commits.
- Never edit a `check*` gate to pass.
- New user-facing strings in `values/` + `values-es/` (ADR-0022).
- JVM tests only; framework code gets a pure sibling (`FocusRestorePolicy`).
- Commit per-slice LOCALLY; no push/merge until owner asks (full reskin not yet done).
