# Library/History Slice 3 — Selection + Batch + Route-Wire Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **House rule: subagents EDIT-ONLY; the controller runs every gradle/git command** (avoids Gradle daemon pileup — see `memory/project_b5_private_vault.md`).

**Goal:** Wire the redesigned `LibraryScreen` into the `"history"` route with long-press multi-select, a glass contextual top bar + bottom batch bar (Share / Vault / Favorite / Delete), Snackbar-UNDO delete, per-item overflow (incl. Rename), porting the recovery-card header + warning strip — retiring `HistoryScreen.kt`.

**Architecture (owner-ratified + codex-concurred, 2026-06-14 — Option A):** The sanctioned recovery-keep `markTerminated(MULTI_SEGMENT_KEPT)` write relocates from `HistoryScreen.kt` into the recovery subsystem (`ui/recovery/`), its principled home (ADR-0005). That empties the `checkLibraryNoManifestWrite` scope of all manifest writes, so the gate's allow-marker machinery is **deleted** → the gate becomes a stricter, exception-free "zero manifest writes in Library/History UI." Selection + deferred-delete logic live in pure JVM-tested helpers (`SelectionReducer`, `PendingDelete`); the route surface composes them. No merge until the full Library reskin is done (owner).

**Tech Stack:** Kotlin, Jetpack Compose, `GlassSurface`/`GlassRole` (ADR-0028), `LibraryMetadataStore` sidecar, `ContrastMath`/`rememberReduceMotion` (ADR-0020), JVM unit tests under `isReturnDefaultValues = true`.

---

## Owner + codex decisions folded in

1. **Option A** route-wire: relocate recovery write to `ui/recovery/`, amend ADR-0030 §2, delete gate's marker exception. (Owner leaned A; codex concurred — "principled, cleaner than B.")
2. **Delete-UNDO = deferred-delete, abandon-on-dispose** (codex flip): commit the real delete only on the explicit snackbar owner — timeout or swipe = commit; UNDO = cancel; **screen dispose / route-leave = abandon (files untouched, rows reappear next load).** Never commit an irreversible op outside the active snackbar owner.
3. **One pending-delete batch at a time.** Confirming a new delete commits the in-flight one first (its snackbar is replaced → resolves Dismissed → commits).
4. **Partial-delete failure** restores the failed rows to visibility + shows an aggregate failure snackbar; the overlay never keeps hiding a row whose delete failed.
5. **Rows entering pending-delete clear selection immediately** and exit select mode if the selection empties.
6. ADR amendment clause (codex): *only recovery-owned terminal-repair writes live outside ADR-0030's gate; favorite / rename / lastPlayedAt stay sidecar-only regardless of caller location.* `ui/recovery/` is not a manifest-write dumping ground.
7. Backlog note: a narrow `checkRecoveryManifestWriteAllowlist` gate is a **future** candidate (recovery writes are now invisible to `checkLibraryNoManifestWrite`); not built in Slice 3.

---

## File Structure

**Amend:**
- `docs/adr/0030-library-history-information-architecture.md` — §2 relocation clause (T1).
- `docs/BACKLOG.md` — future recovery-write gate candidate (T1).
- `app/build.gradle.kts` — `checkLibraryNoManifestWrite` loses the allow-marker machinery (T3).
- `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt` — batch vault, rename, share helper (T6).
- `app/src/main/java/com/aritr/rova/ui/MainScreen.kt` — `"history"` route → `LibraryScreen` (T9).
- `app/src/main/res/values/strings.xml` + `values-es/strings.xml` — new selection/batch/menu strings (T9).

**Create:**
- `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryViewModelFactory.kt` — relocated factory incl. the `markTerminated` lambda (T2).
- `app/src/main/java/com/aritr/rova/ui/library/SelectionReducer.kt` + test (T4).
- `app/src/main/java/com/aritr/rova/ui/library/PendingDelete.kt` + test (T5).
- `app/src/main/java/com/aritr/rova/ui/library/components/LibrarySelectionTopBar.kt` (T7).
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryBatchBar.kt` (T7).
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt` (T7).
- `app/src/main/java/com/aritr/rova/ui/library/components/LibraryRenameDialog.kt` (T7).

**Delete (end of T8):**
- `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt` — after all concerns ported and `MainScreen` swapped.

**Heavily modify:**
- `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt` — becomes the route orchestrator (T8).

---

### Task 1: ADR-0030 §2 amendment + backlog note

**Files:**
- Modify: `docs/adr/0030-library-history-information-architecture.md`
- Modify: `docs/BACKLOG.md`

- [ ] **Step 1: Amend ADR-0030 §2.** Replace the "**One sanctioned exception**" paragraph (the one describing the `HistoryScreen.kt`-co-located `markTerminated` + `ADR-0030-allow: recovery-keep-raw` marker) with:

```markdown
   **Recovery-keep terminal write lives in the recovery subsystem.** The recovery-keep action
   (`markTerminated(MULTI_SEGMENT_KEPT)`, wired through `RecoveryViewModel.markKeptRaw`) is constructed in
   `ui/recovery/RecoveryViewModelFactory.kt` — its principled home (recovery subsystem, ADR-0005), outside the
   `ui/library/` + History/Library-screen scope that `checkLibraryNoManifestWrite` guards. No in-scope exception
   marker is needed; the gate now asserts **zero** manifest-mutating `SessionStore` calls in Library/History UI
   code, full stop. Only recovery-owned terminal-repair writes are permitted outside this gate — favorite,
   rename (`customTitle`), and `lastPlayedAt` go **only** through `LibraryMetadataStore` regardless of which file
   calls them. `ui/recovery/` is not a general manifest-write location.
```

- [ ] **Step 2: Note the date + decision.** Under Status add a line: `Amended 2026-06-14 (Slice 3): recovery-keep write relocated to ui/recovery/; gate exception removed (Option A, owner + codex).`

- [ ] **Step 3: Backlog candidate.** Append to `docs/BACKLOG.md` (in the gates/static-analysis section, or a new P3 row):

```markdown
- **P3 — narrow recovery-write allowlist gate.** After ADR-0030's Slice-3 amendment, recovery manifest writes
  in `ui/recovery/` are invisible to `checkLibraryNoManifestWrite` (by design — ADR-0005 owns them). If recovery
  manifest writes proliferate, add a `checkRecoveryManifestWriteAllowlist` gate scoping `ui/recovery/` to a known
  set of terminal-repair calls. Not needed while the only such write is the recovery-keep `markTerminated`.
```

- [ ] **Step 4: Commit.**

```bash
git add docs/adr/0030-library-history-information-architecture.md docs/BACKLOG.md
git commit -m "docs(adr): ADR-0030 §2 — relocate recovery-keep write to ui/recovery/, drop gate exception (Slice 3, Option A)"
```

---

### Task 2: Relocate the RecoveryViewModel factory to `ui/recovery/`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryViewModelFactory.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt` (remove inline factory — temporary until T8 deletes the file)

**Context:** `HistoryScreen.kt:138-184` builds `RecoveryViewModel` via an inline `viewModelFactory { initializer { … } }` that defines 5 lambdas, including `markKeptRaw` (the only `markTerminated` call). `RecoveryViewModel` already accepts these as constructor params — only the **definition site** moves. The factory needs `RovaApp` + `Context` (for `RovaRecordingService.startRecoveryMerge`). The `videosRoot == null` storage-unavailable guards must be preserved verbatim.

- [ ] **Step 1: Create the relocated factory.** It is recovery-owned (KDoc says so); the `markTerminated` line carries **no** allow-marker (it is now out of gate scope).

```kotlin
package com.aritr.rova.ui.recovery

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aritr.rova.RovaApp
import com.aritr.rova.data.SessionManifest
import com.aritr.rova.data.StopReason
import com.aritr.rova.data.Terminated
import com.aritr.rova.service.RovaRecordingService

/**
 * Recovery subsystem (ADR-0005) factory for [RecoveryViewModel]. The recovery-keep terminal write
 * (`markTerminated(MULTI_SEGMENT_KEPT)`) is constructed here — its principled home — and is therefore
 * OUTSIDE the `ui/library/` + History/Library-screen scope of `checkLibraryNoManifestWrite` (ADR-0030 §2,
 * amended Slice 3). This is the only sanctioned manifest write reachable from the Library/History surface,
 * and it belongs to recovery, not Library metadata. Do not add favorite/rename/lastPlayedAt writes here —
 * those go through `LibraryMetadataStore`.
 *
 * `videosRoot == null` (storage unavailable at boot) disables load AND the two writes: the scan never ran,
 * there is nothing to load, and discard/markTerminated on a missing dir would be a no-op anyway — guarding
 * the lambdas avoids a hot-path NullPointer if the lazy `sessionStore` initializer throws.
 */
fun recoveryViewModelFactory(app: RovaApp, context: Context): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            val sessionStoreAvailable = app.videosRoot != null
            val loadManifest: (String) -> SessionManifest? = if (sessionStoreAvailable) {
                { id -> app.sessionStore.loadManifest(id) }
            } else {
                { _ -> null }
            }
            val discardSession: suspend (String) -> Unit = if (sessionStoreAvailable) {
                { id -> app.sessionStore.discardSession(id) }
            } else {
                { _ -> }
            }
            val markKeptRaw: suspend (String) -> Unit = if (sessionStoreAvailable) {
                { id ->
                    app.sessionStore.markTerminated(
                        sessionId = id,
                        terminated = Terminated.MULTI_SEGMENT_KEPT,
                        stopReason = StopReason.NONE,
                    )
                }
            } else {
                { _ -> }
            }
            val startRecoveryMergeFn: (String) -> Unit = { id ->
                RovaRecordingService.startRecoveryMerge(context, id)
            }
            RecoveryViewModel(
                recoveryReport = app.recoveryReport,
                loadManifest = loadManifest,
                discardSession = discardSession,
                markKeptRaw = markKeptRaw,
                startRecoveryMergeFn = startRecoveryMergeFn,
                mergeOutcome = app.recoveryMergeOutcomeSignal.state,
            )
        }
    }
```

- [ ] **Step 2: Point HistoryScreen at the relocated factory** (interim — keeps the file compiling until T8). Replace the inline `factory = viewModelFactory { … }` (lines ~139-183) with:

```kotlin
    val recoveryViewModel: RecoveryViewModel = viewModel(
        factory = recoveryViewModelFactory(app, context),
    )
```

Add `import com.aritr.rova.ui.recovery.recoveryViewModelFactory`. Remove now-unused imports if the build flags them (`viewModelFactory`, `initializer`, `Terminated`, `StopReason`, `SessionManifest` if unreferenced elsewhere in the file).

- [ ] **Step 3: Controller builds.** `gradlew.bat :app:assembleDebug`. Expected: BUILD SUCCESSFUL. **Note:** `checkLibraryNoManifestWrite` is still the OLD gate here — it will now FAIL because the `markTerminated` marker left `HistoryScreen.kt`. **That failure is expected and resolved by T3** (the gate amendment lands next). If running the full gate suite, expect this one red until T3; gate with `assembleDebug` minus that task, or sequence T3 immediately.

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/recovery/RecoveryViewModelFactory.kt app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt
git commit -m "refactor(recovery): relocate RecoveryViewModel factory + markTerminated to ui/recovery/ (ADR-0030 §2)"
```

---

### Task 3: Amend `checkLibraryNoManifestWrite` — drop the allow-marker machinery

**Files:**
- Modify: `app/build.gradle.kts:1831-1886`

**Context:** With the recovery write out of scope, the gate has **zero** sanctioned in-scope writes. Remove `allowMarker`, the `marked`/`allowMarks` tracking, and the stray-marker check. The gate becomes: any forbidden call in scope → fail.

- [ ] **Step 1: Replace the task body.** New version:

```kotlin
val checkLibraryNoManifestWrite = tasks.register("checkLibraryNoManifestWrite") {
    group = "verification"
    description = "Library/History UI must not call SessionManifest-mutating SessionStore APIs (ADR-0030 §2). " +
        "Recovery-owned terminal writes live in ui/recovery/ (out of scope) — see ADR-0030."
    val forbidden = listOf(
        "markTerminated", "appendSegment", "submitPersistFinalizedSegment",
        "setExportPending", "setExportPrivateTarget", "setExportCopying",
        "setExportSafPrivateTemp", "setExportSafTarget", "setExportFinalized",
        "setExportFailed", "setMediaScanCompleted", "incrementSafTransientRetry",
        "setExportPendingForSide", "setExportPrivateTargetForSide",
        "setExportSafPrivateTempForSide", "setExportSafTargetForSide",
        "setExportFinalizedForSide", "setMediaScanCompletedForSide",
        "setVaultFinalized", "setVaultFinalizedForSide", "setVaultState",
        "setVaultMovedOut", "setVaultStateVaultedAndClearPublic",
        "setPendingMoveOutTier1", "setPendingMoveOutPreQ", "setStopRequested",
        "writeManifestAtomic",
    )
    val callRegex = Regex("\\b(${forbidden.joinToString("|")})\\s*\\(")
    doLast {
        val offenders = mutableListOf<String>()
        fileTree("src/main/java") { include("**/*.kt") }.forEach { f ->
            val rel = f.path.replace('\\', '/').substringAfter("com/aritr/rova/")
            val inScope = rel.startsWith("ui/library/") ||
                (rel.startsWith("ui/screens/") && (rel.contains("History") || rel.contains("Library")))
            if (!inScope) return@forEach
            f.readLines().forEachIndexed { i, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                val code = line.substringBefore("//")
                if (callRegex.containsMatchIn(code)) offenders += "$rel:${i + 1}: ${line.trim()}"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "ADR-0030 §2: Library/History UI must not mutate SessionManifest — use LibraryMetadataStore " +
                    "(recovery-owned writes belong in ui/recovery/):\n" + offenders.joinToString("\n")
            )
        }
    }
}
```

- [ ] **Step 2: Controller verifies the gate alone is green.** `gradlew.bat :app:checkLibraryNoManifestWrite`. Expected: `BUILD SUCCESSFUL` (no offenders — the relocated write is out of scope; LibraryScreen has no manifest writes yet).

- [ ] **Step 3: Negative check (manual reasoning, no code).** Confirm: if a future edit adds e.g. `setVaultState(` inside `LibraryScreen.kt`, the regex matches in-scope → fail. The grep marker exception is gone, so there is no longer a loophole. (Do NOT add a real offending line — just confirm the logic.)

- [ ] **Step 4: Commit.**

```bash
git add app/build.gradle.kts
git commit -m "build(gate): checkLibraryNoManifestWrite now exception-free — zero manifest writes in Library/History UI (ADR-0030 §2)"
```

---

### Task 4: `SelectionReducer` pure helper

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/SelectionReducer.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/SelectionReducerTest.kt`

**Context:** Pure immutable selection state over `stableKey` strings (spec §5.6). Drives long-press select, per-tile toggle, per-day select-all, count, clear. No Compose/Android types.

- [ ] **Step 1: Write the failing test.**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionReducerTest {
    private val empty = SelectionState()

    @Test fun enterOnLongPress_activatesAndSelects() {
        val s = SelectionReducer.enter(empty, "a")
        assertTrue(s.active)
        assertEquals(setOf("a"), s.keys)
        assertEquals(1, s.count)
    }

    @Test fun toggle_addsThenRemoves_andDeactivatesWhenEmpty() {
        var s = SelectionReducer.enter(empty, "a")
        s = SelectionReducer.toggle(s, "b")
        assertEquals(setOf("a", "b"), s.keys)
        s = SelectionReducer.toggle(s, "a")
        assertEquals(setOf("b"), s.keys)
        s = SelectionReducer.toggle(s, "b")
        assertFalse(s.active)          // emptying selection exits select mode
        assertEquals(emptySet<String>(), s.keys)
    }

    @Test fun selectAllInGroup_unionsGroupKeys() {
        var s = SelectionReducer.enter(empty, "a")
        s = SelectionReducer.selectAll(s, listOf("a", "b", "c"))
        assertEquals(setOf("a", "b", "c"), s.keys)
    }

    @Test fun selectAllInGroup_whenAllPresent_deselectsGroup() {
        var s = SelectionState(active = true, keys = setOf("a", "b", "c"))
        s = SelectionReducer.selectAll(s, listOf("a", "b", "c"))
        assertFalse(s.active)
        assertTrue(s.keys.isEmpty())
    }

    @Test fun reconcile_dropsMissingKeys_andExitsWhenEmpty() {
        val s = SelectionState(active = true, keys = setOf("a", "x"))
        val r = SelectionReducer.reconcile(s, setOf("a", "b"))
        assertEquals(setOf("a"), r.keys)
        assertTrue(r.active)
        val gone = SelectionReducer.reconcile(s, setOf("b"))
        assertFalse(gone.active)
        assertTrue(gone.keys.isEmpty())
    }

    @Test fun clear_resetsToInactive() {
        val s = SelectionState(active = true, keys = setOf("a"))
        assertEquals(SelectionState(), SelectionReducer.clear(s))
    }

    @Test fun removeAll_dropsKeys_keepsModeIfNonEmpty() {
        val s = SelectionState(active = true, keys = setOf("a", "b", "c"))
        val r = SelectionReducer.removeAll(s, setOf("a", "b"))
        assertEquals(setOf("c"), r.keys)
        assertTrue(r.active)
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (unresolved `SelectionState`/`SelectionReducer`).

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.SelectionReducerTest"`

- [ ] **Step 3: Implement.**

```kotlin
package com.aritr.rova.ui.library

/**
 * Immutable multi-select state over row [keys] (stableKey strings). [active] is the select-mode flag;
 * it auto-clears whenever the selection empties so the UI drops back to the normal top bar. Pure — no
 * Android/Compose types (spec §5.6, JVM-tested).
 */
data class SelectionState(
    val active: Boolean = false,
    val keys: Set<String> = emptySet(),
) {
    val count: Int get() = keys.size
}

/** Pure transitions for [SelectionState]. Every function returns a new state. */
object SelectionReducer {

    /** Long-press entry: activate select mode with [key] selected. */
    fun enter(state: SelectionState, key: String): SelectionState =
        SelectionState(active = true, keys = state.keys + key)

    /** Tap-toggle a key; emptying the selection exits select mode. */
    fun toggle(state: SelectionState, key: String): SelectionState =
        finalize(if (key in state.keys) state.keys - key else state.keys + key)

    /**
     * Per-day select-all: if every [groupKeys] is already selected, deselect them (toggle-off);
     * otherwise union them in.
     */
    fun selectAll(state: SelectionState, groupKeys: List<String>): SelectionState =
        if (groupKeys.isNotEmpty() && state.keys.containsAll(groupKeys)) {
            finalize(state.keys - groupKeys.toSet())
        } else {
            finalize(state.keys + groupKeys)
        }

    /** Drop keys no longer present in [liveKeys] (items list changed). */
    fun reconcile(state: SelectionState, liveKeys: Set<String>): SelectionState =
        if (!state.active) state else finalize(state.keys intersect liveKeys)

    /** Remove a specific set (e.g. rows entering pending-delete). */
    fun removeAll(state: SelectionState, drop: Set<String>): SelectionState =
        finalize(state.keys - drop)

    /** Hard reset to inactive/empty. */
    fun clear(@Suppress("UNUSED_PARAMETER") state: SelectionState): SelectionState = SelectionState()

    private fun finalize(keys: Set<String>): SelectionState =
        if (keys.isEmpty()) SelectionState() else SelectionState(active = true, keys = keys)
}
```

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/SelectionReducer.kt app/src/test/java/com/aritr/rova/ui/library/SelectionReducerTest.kt
git commit -m "feat(library): SelectionReducer pure multi-select state (spec §5.6)"
```

---

### Task 5: `PendingDelete` pure helper (deferred-delete overlay)

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/PendingDelete.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/PendingDeleteTest.kt`

**Context:** Pure model for the deferred-delete overlay (decision 2-5 above). Holds the batch currently hidden behind a Snackbar window. The screen filters rows whose `stableKey ∈ pending`; commit/abandon/undo are owner-driven. Includes the row-filter so it is unit-testable.

- [ ] **Step 1: Write the failing test.**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingDeleteTest {
    private fun row(k: String) = LibraryRow(
        stableKey = k, title = k, dateLabel = "", dateMillis = 0, durationMs = 0,
        sizeBytes = 0, topology = com.aritr.rova.data.CaptureTopology.SINGLE, badge = null, favorite = false,
    )

    @Test fun visibleRows_hidePendingKeys() {
        val pending = PendingDelete(setOf("b"))
        val rows = listOf(row("a"), row("b"), row("c"))
        assertEquals(listOf("a", "c"), pending.visible(rows).map { it.stableKey })
    }

    @Test fun none_hidesNothing() {
        val rows = listOf(row("a"), row("b"))
        assertEquals(rows, PendingDelete.NONE.visible(rows))
    }

    @Test fun isPending_reflectsMembership() {
        val p = PendingDelete(setOf("a"))
        assertTrue(p.isPending("a"))
        assertFalse(p.isPending("b"))
    }

    @Test fun restoreFailed_unhidesOnlyFailedKeys() {
        // Commit returned failures for "b"; "a","c" really deleted.
        val p = PendingDelete(setOf("a", "b", "c"))
        val after = p.restore(setOf("b"))
        // "b" must become visible again; "a"/"c" stay hidden (they were deleted, VM refresh removes them).
        assertEquals(emptySet<String>(), after.keys.intersect(setOf("b")))
        assertEquals(setOf("a", "c"), after.keys)
    }

    @Test fun isEmpty_whenNoKeys() {
        assertTrue(PendingDelete.NONE.isEmpty)
        assertFalse(PendingDelete(setOf("a")).isEmpty)
    }
}
```

- [ ] **Step 2: Run — expect FAIL.**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.PendingDeleteTest"`

- [ ] **Step 3: Implement.**

```kotlin
package com.aritr.rova.ui.library

/**
 * Deferred-delete overlay (ADR-0030 Slice 3, owner + codex). Holds the row keys hidden behind an active
 * Snackbar-UNDO window. The real delete is NOT yet performed — the screen filters these rows out of the
 * rendered list via [visible]; the owning coroutine commits `deleteItems` only on snackbar timeout/swipe,
 * cancels on UNDO, and abandons (clears) on screen dispose. Pure — JVM-tested.
 */
data class PendingDelete(val keys: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = keys.isEmpty()

    fun isPending(key: String): Boolean = key in keys

    /** Rows the UI should render (pending-delete rows hidden). */
    fun visible(rows: List<LibraryRow>): List<LibraryRow> =
        if (keys.isEmpty()) rows else rows.filter { it.stableKey !in keys }

    /** Un-hide rows whose delete failed (they stay in the library); successfully-deleted keys stay hidden
     *  until the VM refresh drops them. */
    fun restore(failedKeys: Set<String>): PendingDelete = PendingDelete(keys - failedKeys)

    companion object { val NONE = PendingDelete() }
}
```

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/PendingDelete.kt app/src/test/java/com/aritr/rova/ui/library/PendingDeleteTest.kt
git commit -m "feat(library): PendingDelete overlay for deferred Snackbar-UNDO delete (spec §5.2)"
```

---

### Task 6: HistoryViewModel batch ops — vault gating, rename, share targets

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt`

**Context (from the structural map):** the VM already exposes `deleteItems(items): DeleteResult`, `moveToVault(sessionId): Boolean`, `toggleFavorite(stableKey)`, `setViewMode`, `loadSessionConfig`, `items`, `libraryUiState`, `sidecarWriteError`. Slice 3 adds: batch vault, rename (sidecar `customTitle`), and a pure helper to resolve `VideoItem`s for a selection (share + delete need the concrete items). **No manifest writes** — vault still routes through the existing `moveToVault` (gate-safe), rename through `libraryStore.update`.

- [ ] **Step 1: Add `itemsForKeys` + `movableVaultKeys` + `renameSession` + `batchMoveToVault`.** Insert near `toggleFavorite` / `deleteItems`:

```kotlin
    /** Resolve selected stableKeys to concrete [VideoItem]s (share/delete need the artifacts). */
    fun itemsForKeys(keys: Set<String>): List<VideoItem> {
        val byKey = items.value.associateBy { it.stableKey }
        return keys.mapNotNull { byKey[it] }
    }

    /**
     * Subset of [keys] whose session is vault-movable (single-mode only; P+L not movable — ADR-0009/0025).
     * Used to gate/partition the batch Vault action. Mirrors per-row `isSingleModeMovable`.
     */
    fun movableVaultKeys(keys: Set<String>): Set<String> {
        val byKey = items.value.associateBy { it.stableKey }
        return keys.filter { k ->
            val sid = byKey[k]?.sessionId ?: return@filter false
            VaultMoverBuilder.isSingleModeMovable(app.sessionStore.loadManifest(sid))
        }.toSet()
    }

    /** Rename = sidecar customTitle write (never the manifest — ADR-0030). Blank clears the custom title. */
    fun renameSession(stableKey: String, newTitle: String) {
        val store = libraryStore ?: return
        val trimmed = newTitle.trim()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.update(stableKey) { it.copy(customTitle = trimmed.ifBlank { null }) }
                _sidecarRevision.update { it + 1 }
            } catch (t: Throwable) {
                RovaLog.e("HistoryViewModel.renameSession: sidecar write failed for $stableKey", t)
                _sidecarWriteError.update { it + 1 }
            }
        }
    }

    /** Batch Vault: move every movable selected session; returns moved/skipped counts. */
    suspend fun batchMoveToVault(keys: Set<String>): VaultBatchResult {
        val byKey = items.value.associateBy { it.stableKey }
        val movable = movableVaultKeys(keys)
        var moved = 0
        for (k in movable) {
            val sid = byKey[k]?.sessionId ?: continue
            if (moveToVault(sid)) moved++
        }
        return VaultBatchResult(moved = moved, skipped = keys.size - movable.size)
    }
```

Add the result type near `DeleteResult`:

```kotlin
    data class VaultBatchResult(val moved: Int, val skipped: Int)
```

Add imports if missing: `com.aritr.rova.service.vault.VaultMoverBuilder` (confirm the exact package of `isSingleModeMovable` — it is the same symbol the per-row menu uses in `HistoryScreen.kt`; reuse that import path). Confirm `app` is reachable in the VM (it extends `AndroidViewModel` — use `getApplication()` if there is no existing `app` alias; match the pattern already used by `libraryStore`).

- [ ] **Step 2: Verify against the existing per-row vault path.** Read how `HistoryScreen.kt` resolves `isSingleModeMovable` (around the overflow-menu / `onMenuMoveToVault` wiring) and **mirror the exact symbol + manifest source** so batch and per-row agree. If the per-row path passes a manifest already in hand, prefer that to a fresh `loadManifest` to avoid a disk read per key (acceptable either way for v1 — note it).

- [ ] **Step 3: Controller builds + runs existing VM tests.** `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.*"` then `:app:checkLibraryNoManifestWrite`. Expected: green (no new manifest write — `moveToVault` already gate-safe; rename is sidecar).

- [ ] **Step 4: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt
git commit -m "feat(library): VM batch vault + rename(sidecar) + selection item resolution (spec §5.2/§5.3)"
```

---

### Task 7: Selection/batch composables + rename dialog

**Files:**
- Create: `LibrarySelectionTopBar.kt`, `LibraryBatchBar.kt`, `LibraryItemSheet.kt`, `LibraryRenameDialog.kt` under `ui/library/components/`

**Context:** All glass chrome via `GlassSurface(role = …)`. a11y per spec §8: roles on every clickable (`checkA11yClickableHasRole`), ≥24dp targets (`checkA11yTargetSizeToken`), polite live-region for the count, `stateDescription` on toggleable tiles (the tile toggle wiring lands in T8 on the existing `LibraryGridCard`/`LibraryListRow` — these components are the bars/sheets). Strings come in as params (caller does `stringResource`), matching the established component style (see `LibraryTopBar`).

- [ ] **Step 1: `LibrarySelectionTopBar`.** Glass `NavBar` role. Close (✕) + live "N selected" + select-all. The count node is a polite live region announced on change (anti-chant — §8).

```kotlin
package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.GlassRole
import com.aritr.rova.ui.components.GlassSurface

@Composable
fun LibrarySelectionTopBar(
    countLabel: String,        // already-formatted "3 selected"
    closeLabel: String,
    selectAllLabel: String,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(role = GlassRole.NavBar, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = closeLabel) }
            Text(
                text = countLabel,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = selectAllLabel)
            }
        }
    }
}
```

- [ ] **Step 2: `LibraryBatchBar`.** Glass `BottomSheet`/`NavBar` role. Share / Vault / Favorite / Delete. Vault disabled (with reason via `contentDescription`) when `vaultEnabled == false`. Each `IconButton` has a `contentDescription` (role satisfied — IconButton is a Button role).

```kotlin
package com.aritr.rova.ui.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.components.GlassRole
import com.aritr.rova.ui.components.GlassSurface

@Composable
fun LibraryBatchBar(
    shareLabel: String,
    vaultLabel: String,
    vaultDisabledLabel: String,
    favoriteLabel: String,
    deleteLabel: String,
    vaultEnabled: Boolean,
    onShare: () -> Unit,
    onVault: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassSurface(role = GlassRole.NavBar, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = shareLabel) }
            IconButton(onClick = onVault, enabled = vaultEnabled) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = if (vaultEnabled) vaultLabel else vaultDisabledLabel,
                )
            }
            IconButton(onClick = onFavorite) { Icon(Icons.Filled.Star, contentDescription = favoriteLabel) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = deleteLabel) }
        }
    }
}
```

- [ ] **Step 3: `LibraryItemSheet`.** Per-item glass context sheet (overflow / long-press in non-select): Play · Share · Favorite · Rename · Move-to-Vault · View settings · Delete (NO Export — ADR-0030). Use `ModalBottomSheet` + `GlassSurface` content, each row a `Row` with `Modifier.clickable(onClickLabel=…, role = Role.Button)` ≥24dp (satisfies `checkA11yClickableHasRole` + `checkA11yTargetSizeToken`). Vault row hidden when `!movable`. Provide all labels as params. (Full row list mechanical — follow `LibraryListRow` clickable+role idiom already in the package.)

- [ ] **Step 4: `LibraryRenameDialog`.** `AlertDialog` with an `OutlinedTextField` seeded with the current title; confirm calls `onRename(newTitle)`; blank → clears custom title (VM handles). Confirm/Dismiss `TextButton`s (Button role). Labels as params.

- [ ] **Step 5: Controller builds.** `gradlew.bat :app:assembleDebug` (composables compile; no unit tests — device-smoked). Expected: SUCCESSFUL. Confirm `checkA11yClickableHasRole` + `checkA11yTargetSizeToken` green for the new files.

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibrarySelectionTopBar.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryBatchBar.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryRenameDialog.kt
git commit -m "feat(library): glass selection top bar + batch bar + item sheet + rename dialog (spec §5.2/§5.3, a11y §8)"
```

---

### Task 8: LibraryScreen route orchestrator — port recovery/warnings/selection/batch/dialogs/UNDO; retire HistoryScreen

**Files:**
- Modify (heavily): `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt`
- Delete (end): `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`

**Context:** This is the integration task. `LibraryScreen` gains the full route surface. **Controller authors this task** (integration-critical, like Slice 2's Task 6/12). It must:

1. Take the route params `HistoryScreen` had: `viewModel`, `onNavigateToRecord`, `onOpenPlayer`, `onOpenVault`, `onBack` — plus keep `onShare`/grid rendering from current LibraryScreen.
2. Build `recoveryViewModel` via `recoveryViewModelFactory(app, context)` (T2) + render the recovery card header (`RecoveryCardList` + `vendorHelpSlotFor`) above the hero/grid — **full-span items at the top of the grid/list**, and in the empty-state branch.
3. Build the warning VM via `buildWarningCenterViewModel(app)` + render `HistoryWarningStrip` (same placement), with the `sheetWarningId` sheet host.
4. Hold `var selection by remember { mutableStateOf(SelectionState()) }` and `var pending by remember { mutableStateOf(PendingDelete.NONE) }`. Reconcile selection on `items` change via `SelectionReducer.reconcile`.
5. **Tiles become toggleable in select mode:** pass `isSelectionMode`, `isSelected`, `onToggle`, `onLongPress` into `LibraryGridCard`/`LibraryListRow`. Long-press → `SelectionReducer.enter`. In select mode, tap → `SelectionReducer.toggle`; otherwise tap → play. Add `Modifier.toggleable(value, role = Role.Checkbox/...).semantics { stateDescription = selected/notSelected }` on the tile (per §8). **This requires extending `LibraryGridCard`/`LibraryListRow` signatures** — add the select params with defaults so existing call sites/tests stay valid.
6. Render `LibrarySelectionTopBar` instead of `LibraryTopBar` when `selection.active`; render `LibraryBatchBar` (bottom) in select mode. Wire:
   - **Share:** `viewModel.itemsForKeys(selection.keys)` → existing `safeShareUri` + `ACTION_SEND_MULTIPLE` (lift the helper from HistoryScreen).
   - **Favorite (batch):** loop `viewModel.toggleFavorite` over keys (or add a batch setter — simplest: loop).
   - **Vault (batch):** `vaultEnabled = viewModel.movableVaultKeys(selection.keys).isNotEmpty()`; confirm dialog → `coroutineScope.launch { viewModel.batchMoveToVault(selection.keys) }` → snackbar moved/skipped; then `selection = SelectionReducer.clear(selection)`.
   - **Delete (batch):** confirm dialog → enter deferred-delete (Step 7).
7. **Deferred-delete owner coroutine** (decisions 2-5):

```kotlin
// after the user confirms the delete dialog for `selection.keys`:
val toDelete = selection.keys
// 1. commit any in-flight pending batch first (one at a time)
pendingJob?.let { /* its snackbar will resolve Dismissed → its own block commits */ }
// 2. hide rows + clear them from selection
pending = PendingDelete(pending.keys + toDelete)
selection = SelectionReducer.removeAll(selection, toDelete)
pendingJob = coroutineScope.launch {
    val result = snackbarHostState.showSnackbar(
        message = deletedCountMessage(toDelete.size),
        actionLabel = undoLabel,
        duration = SnackbarDuration.Short,
    )
    when (result) {
        SnackbarResult.ActionPerformed -> {           // UNDO → abandon
            pending = pending.restore(toDelete)
        }
        SnackbarResult.Dismissed -> {                 // timeout/swipe → commit
            val items = viewModel.itemsForKeys(toDelete)
            val res = viewModel.deleteItems(items)     // suspend; refreshes on success
            // restore rows whose delete failed (aggregate surface)
            val deletedKeys = items.take(res.deleted).map { it.stableKey }.toSet() // see note
            pending = pending.restore(toDelete - deletedKeys)
            if (res.failed > 0) snackbarHostState.showSnackbar(deleteFailedMessage(res.failed))
        }
    }
}
```

> **Note on failure attribution:** `DeleteResult(deleted, failed)` returns counts, not which keys failed. For accurate `restore`, prefer to **iterate `deleteItems` per-item** in the screen (or add a VM method returning the failed `Set<String>`). Cleanest: add `suspend fun deleteItemsKeyed(items): Set<String> /* failed keys */` to the VM in T6-style and `restore(failedKeys)`. If kept count-based, on any `failed > 0` conservatively `pending = pending.restore(toDelete)` (un-hide all, let the VM refresh drop the truly-deleted) and show the aggregate message — safe, slightly less precise. **Pick the keyed variant** for correctness; add it to T6 if not present.

8. **Abandon on dispose** (codex): `DisposableEffect(Unit) { onDispose { pendingJob?.cancel(); pending = PendingDelete.NONE } }` — cancelling the coroutine means neither branch runs → no commit, rows reappear next load. (The `pending` reset is cosmetic since the screen is leaving.)
9. Apply `pending.visible(...)` when building `hero`/`collection`: feed `LibraryQuery.hero(pending.visible(ui.rows))` and `collection(pending.visible(ui.rows), …)` so hidden rows drop from both hero and groups consistently.
10. Port the view-settings dialog (`viewSettingsConfig` + `loadSessionConfig`), vault move-in confirm dialog, retention-notice + snackbar effects, and `onEditPlaceholder` verbatim.
11. Keep all a11y wired: grid `collectionInfo`/`collectionItemInfo` (already present), merged tile description, reduce-motion seam, live-region count (in the top bar component).

- [ ] **Step 1: Extend `LibraryGridCard` + `LibraryListRow`** with `isSelectionMode: Boolean = false`, `isSelected: Boolean = false`, `onToggle: () -> Unit = {}`, `onLongPress: () -> Unit = {}`, plus `selectedLabel`/`notSelectedLabel` for `stateDescription`. Use `combinedClickable` (onClick routes to toggle-or-play decided by caller via the passed `onClick`; onLongClick → `onLongPress`) and, in select mode, overlay a selection check + `Modifier.semantics { stateDescription = if (isSelected) selectedLabel else notSelectedLabel }`. Defaults preserve current call sites.

- [ ] **Step 2: Rewrite `LibraryScreen`** per the context above. Lift `safeShareUri` + the share-intent builder from `HistoryScreen.kt` into the screen (or a small `LibraryShare.kt` helper). Render recovery header + warning strip as leading full-span grid items / column items and in the empty branch.

- [ ] **Step 3: Controller builds.** `gradlew.bat :app:assembleDebug`. Iterate on compile errors. Expected eventually: SUCCESSFUL.

- [ ] **Step 4: Run full unit suite.** `gradlew.bat :app:testDebugUnitTest`. Expected: all green (new pure-helper tests + existing).

- [ ] **Step 5: Commit (LibraryScreen complete, HistoryScreen still present).**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/
git commit -m "feat(library): LibraryScreen route orchestrator — recovery+warnings+selection+batch+UNDO (spec §5.2)"
```

---

### Task 9: MainScreen route swap + strings + retire HistoryScreen

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/MainScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`
- Delete: `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`

- [ ] **Step 1: Add strings (en).** In `values/strings.xml`:

```xml
<string name="library_select_count">%1$d selected</string>
<string name="library_action_close_selection">Exit selection</string>
<string name="library_action_select_all">Select all</string>
<string name="library_action_delete">Delete</string>
<string name="library_action_vault">Move to vault</string>
<string name="library_action_vault_disabled">Vault unavailable for the current selection</string>
<string name="library_action_rename">Rename</string>
<string name="library_action_view_settings">View settings</string>
<string name="library_action_play">Play</string>
<string name="library_delete_confirm_title">Delete recordings?</string>
<string name="library_delete_confirm_body">%1$d recording(s) will be deleted.</string>
<string name="library_delete_undo">Undo</string>
<string name="library_deleted_count">Deleted %1$d recording(s)</string>
<string name="library_delete_failed">Couldn\'t delete %1$d recording(s)</string>
<string name="library_vault_batch_result">Moved %1$d, skipped %2$d</string>
<string name="library_rename_title">Rename recording</string>
<string name="library_rename_hint">Title</string>
<string name="library_rename_confirm">Save</string>
<string name="common_cancel">Cancel</string>
```

(Reuse existing strings where present — e.g. `history_title`, `library_action_favorite/unfavorite/share`, `common_cancel` if it already exists; drop dupes.)

- [ ] **Step 2: Add the Spanish equivalents** in `values-es/strings.xml` (translate each new key).

- [ ] **Step 3: Swap the route.** In `MainScreen.kt`, change the `composable("history") { HistoryScreen(...) }` body to call `LibraryScreen(...)` with the matching params (`viewModel`, `onNavigateToRecord`, `onOpenPlayer`, `onOpenVault`, `onBack`, `onShare`). Update the import from `ui.screens.HistoryScreen` to `ui.library.LibraryScreen`.

- [ ] **Step 4: Delete `HistoryScreen.kt`.** `git rm app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`. Grep for any remaining references (`HistoryScreen(`, `import ...HistoryScreen`) and fix.

- [ ] **Step 5: Controller full build + gates.** `gradlew.bat :app:assembleDebug` → runs all 42 gates via preBuild. Expected: BUILD SUCCESSFUL, `checkLibraryNoManifestWrite` green (no manifest write anywhere in scope), `checkNoHardcodedUiStrings` green (all new strings in resources).

- [ ] **Step 6: Commit.**

```bash
git add app/src/main/java/com/aritr/rova/ui/MainScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git rm app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt
git commit -m "feat(library): route history→LibraryScreen, strings en+es, retire HistoryScreen (Slice 3)"
```

---

### Task 10: Full verification

- [ ] **Step 1: Full unit suite.** `gradlew.bat :app:testDebugUnitTest`. Expected: 0-0-0, baseline + new tests.
- [ ] **Step 2: Full assemble (42 gates).** `gradlew.bat :app:assembleDebug`. Expected: SUCCESSFUL.
- [ ] **Step 3: Spot-check the relocated gate is exception-free** and that `ADR-0030-allow` appears in **zero** source files: `grep -rn "ADR-0030-allow" app/src/main` → no matches.
- [ ] **Step 4: codex review** of the LibraryScreen deferred-delete owner coroutine + tile toggle wiring (the integration-critical part) before device smoke; fold findings.

---

### Task 11: Device smoke (RZCYA1VBQ2H) — deferred Slice-2 checklist + Slice-3 actions

Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Verify (real device — emulator can't do CameraX, but Library is render-only here):

- [ ] Library opens in grid; hero = newest, no duplicate in groups; day headers show size totals.
- [ ] Grid/List toggle persists view; both render thumbnails (disk cache hit on 2nd open).
- [ ] Long-press a tile → select mode; glass contextual top bar shows "N selected" (TalkBack announces count on change); per-day select-all works; ✕ exits.
- [ ] Batch **Favorite** stars the selection (★ persists across app restart — sidecar).
- [ ] Batch **Share** opens the system chooser with the selected clips.
- [ ] Batch **Vault** disabled when a P+L session is in the selection; moves single-mode ones; "Moved N, skipped M" snackbar.
- [ ] Batch **Delete** → confirm → rows vanish + Snackbar UNDO; **UNDO restores them**; letting it time out actually deletes (gone after refresh); leaving the screen mid-window abandons (rows still present on return).
- [ ] Per-item overflow: Rename persists (custom title across restart); View settings opens; Move-to-Vault works; Delete (single) works.
- [ ] **Recovery card** still renders above the list and **keep-raw still works** (the relocated write) — induce/observe a recoverable session; confirm classification unaffected (the ADR-0030 safety check).
- [ ] Warning strip still surfaces + dismisses.
- [ ] TalkBack: tile = single focus stop (title+duration+status merged); selection toggles announce state; batch bar reachable.
- [ ] Theme-native (not pinned dark) across an Aurora-dark + a Daylight-light theme.

---

## Self-Review (controller, before execution)

- **Spec coverage:** §5.2 selection+batch ✓ (T4/T7/T8), §5.3 per-item incl. Rename ✓ (T7/T8), §6 sidecar rename ✓ (T6), §8 a11y selection/live-region/targets ✓ (T7/T8), §10 ADR/gate ✓ (T1/T3), §11 slice-3 scope ✓. Out of scope kept out: sort/filter/search UI + scrubber (Slice 4), row-21 player a11y (Slice 5).
- **Type consistency:** `SelectionState`/`SelectionReducer`, `PendingDelete`, `VaultBatchResult`, `DeleteResult` names consistent across tasks. `LibraryRow` fields match the real model (read 2026-06-14).
- **No placeholders:** pure helpers fully coded + tested; integration tasks give exact call sites + the deferred-delete coroutine in full. The one open mechanics choice (keyed-failure delete) is called out with a decision ("pick the keyed variant").
- **Gate safety:** no `check*` is edited to pass broken code — T3 amends the gate to reflect a *better* architecture (recovery write relocated), with owner sign-off; it gets stricter, not weaker.
```
