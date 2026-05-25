# Phase 4.3 — Recovery Merge + C2.4 "Can't Merge Yet" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire a service-hosted recovery merge path (`RovaRecordingService.startRecoveryMerge` → `ExportPipeline.exportRecovered` → `VideoMerger`) with three CTAs on the Library recovery card (Merge / Keep as raw / Discard), plus the C2.4 "Can't merge yet" sheet for the insufficient-storage branch, reusing shipped v3 chrome end-to-end.

**Architecture:** Eager pre-flight in a new `ExportPipeline.exportRecovered` entry; failure routes via a new `RecoveryMergeOutcomeSignal` to `WarningId.CANT_MERGE` (ADVISORY, AdvisorySheet); success writes `Terminated.COMPLETED`; user-keeps-raw writes new `Terminated.MULTI_SEGMENT_KEPT`. RecoveryCard signature extends with optional `onMerge` / `onKeepRaw` (back-compat null). All chrome reuses `WarningSheetV3`, `WarningTopBannerV3`, `RovaWarningsV3` tokens, and the shipped `RecoveryCard` body.

**Tech Stack:** Kotlin 2.2.10, Compose, Coroutines + StateFlow, AndroidX Lifecycle, AGP 9.2.1, Gradle 9.4.1, JUnit 4 + kotlinx-coroutines-test JVM tests (no Espresso).

**Spec:** [docs/superpowers/specs/2026-05-25-phase-4-3-recovery-merge-design.md](../specs/2026-05-25-phase-4-3-recovery-merge-design.md)

---

## File Structure

**Modified:**
- `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` — `Terminated.MULTI_SEGMENT_KEPT` enum value
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt` — `CANT_MERGE` row #14
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt` — `WarningActionStyle` enum, `tertiary` field, new `ActionTarget`s, CANT_MERGE arms
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt` — accept new `cantMergeActive` input
- `app/src/main/java/com/aritr/rova/service/export/ExportPipeline.kt` — `exportRecovered` entry
- `app/src/main/java/com/aritr/rova/service/export/ExportTypes.kt` — `ExportResult.InsufficientStorage`
- `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt` — `ACTION_RECOVER_MERGE` branch + companion factory
- `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryUiState.kt` — `RecoveryCardState` fields + mapper rules
- `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt` — `onMerge`/`onKeepRaw`/`mergeInProgress` params + `CtaRow`
- `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryViewModel.kt` — `merge`/`keepRaw` methods + constructor seams
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt` — `pendingCantMergeSessionId`
- `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt` — KEEP_SEGMENTS_ONLY / DISCARD_RECOVERY_SESSION dispatch
- `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt` — wire new VM seams + onMerge/onKeepRaw on `RecoveryCardList`
- `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` — wire `onKeepRawFromSheet` / `onDiscardFromSheet` callbacks (or whichever host owns WarningCenter)
- `app/src/main/java/com/aritr/rova/RovaApp.kt` — `recoveryMergeOutcomeSignal` lazy prop + WarningCenterViewModel wiring
- `docs/WarningCenterContract.md` — §C2.4 row correction

**Created:**
- `app/src/main/java/com/aritr/rova/ui/signals/RecoveryMergeOutcomeSignal.kt`
- `app/src/main/java/com/aritr/rova/service/recovery/RecoveryMerger.kt`
- `docs/adr/0017-recovery-merge-architecture.md`

**Test files created/modified:**
- `app/src/test/java/com/aritr/rova/data/TerminatedMultiSegmentKeptTest.kt` (NEW)
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningIdOrderTest.kt` (MOD — pin row #14)
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentTest.kt` (MOD — CANT_MERGE sheet)
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentV3Test.kt` (MOD — `WarningActionStyle` defaults)
- `app/src/test/java/com/aritr/rova/ui/warnings/MidRecBannerContentTest.kt` (MOD — CANT_MERGE defensive throw)
- `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt` (MOD — CANT_MERGE precedence)
- `app/src/test/java/com/aritr/rova/service/export/ExportPipelineRecoveredPreflightTest.kt` (NEW)
- `app/src/test/java/com/aritr/rova/ui/signals/RecoveryMergeOutcomeSignalTest.kt` (NEW)
- `app/src/test/java/com/aritr/rova/service/recovery/RecoveryMergerTest.kt` (NEW)
- `app/src/test/java/com/aritr/rova/ui/recovery/RecoveryUiStateMapperTest.kt` (MOD)
- `app/src/test/java/com/aritr/rova/ui/recovery/RecoveryViewModelTest.kt` (MOD)

---

## Conventions

- **Tests are pure JVM** under `app/src/test/java/...` using JUnit 4 (`org.junit.Test`) + `kotlinx.coroutines.test.runTest`. No Robolectric, no Espresso. Service-side tests use pure-helper extraction (precedent: `SegmentGateThermal`).
- **Subagent for Gradle.** Long `./gradlew.bat` runs (assembleDebug, lint, full test) must be dispatched to a subagent per standing constraint. Short single-test runs (`./gradlew.bat :app:testDebugUnitTest --tests "<class>"`) may run in-session if budget allows; if blocked, dispatch.
- **Commit message style.** Follow recent slice format (`feat(ui):`, `feat(service):`, `feat(data):`, `chore(docs):` prefixes). One-line subject. Co-author trailer per project default.
- **No `git push` / `gh pr create`** within plan execution — owner consent per slice. The plan ends with a final commit; push/PR is owner-driven afterward.

---

### Task 1: Add `Terminated.MULTI_SEGMENT_KEPT` enum value + mapper hide rule

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/data/SessionManifest.kt:313-318` (Terminated enum)
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryUiState.kt` (isEligible + toCard)
- Test (new): `app/src/test/java/com/aritr/rova/data/TerminatedMultiSegmentKeptTest.kt`
- Test (mod): `app/src/test/java/com/aritr/rova/ui/recovery/RecoveryUiStateMapperTest.kt`

- [ ] **Step 1: Write the failing test — enum value exists**

`app/src/test/java/com/aritr/rova/data/TerminatedMultiSegmentKeptTest.kt`:

```kotlin
package com.aritr.rova.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TerminatedMultiSegmentKeptTest {

    @Test
    fun `MULTI_SEGMENT_KEPT exists and is the fifth value`() {
        val values = Terminated.values()
        assertEquals(5, values.size, "Terminated must have 5 values after Phase 4.3")
        assertNotNull(Terminated.valueOf("MULTI_SEGMENT_KEPT"))
        assertEquals(4, Terminated.MULTI_SEGMENT_KEPT.ordinal, "MULTI_SEGMENT_KEPT must be last")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.TerminatedMultiSegmentKeptTest"`
Expected: compile error or `MULTI_SEGMENT_KEPT exists ...` FAIL — enum value absent.

- [ ] **Step 3: Add the enum value**

Edit `app/src/main/java/com/aritr/rova/data/SessionManifest.kt` — replace the `Terminated` enum (currently lines 313-318) with:

```kotlin
enum class Terminated {
    USER_STOPPED,
    COMPLETED,
    KILLED_BY_SYSTEM,
    KILLED_FORCE_STOP,
    /**
     * Phase 4.3 — user chose to keep recovered segments as N separate
     * files instead of running the merge. Atomically written by the
     * `Keep as raw clips` / `Save segments only` CTAs via
     * `SessionStore.markTerminated(MULTI_SEGMENT_KEPT, StopReason.NONE)`.
     * Recovery card mapper hides it (no card emitted); Library row
     * enumeration is out-of-scope for this slice (see spec §4.11).
     */
    MULTI_SEGMENT_KEPT,
}
```

- [ ] **Step 4: Add the mapper hide rule**

Edit `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryUiState.kt`:

In `RecoveryUiStateMapper.isEligible` (around line 134):

```kotlin
private fun isEligible(view: RecoverySessionView): Boolean {
    val terminated = view.manifest.terminated ?: return false
    if (terminated == Terminated.COMPLETED) return false
    if (terminated == Terminated.MULTI_SEGMENT_KEPT) return false   // Phase 4.3 — kept raw, no recovery card
    if (view.classification.eligibility != DiscardEligibility.OFFER_DISCARD) return false
    if (view.manifest.exportState == ExportState.FINALIZED) return false
    return true
}
```

In `RecoveryUiStateMapper.toCard` exhaustive `when (terminated)` around line 161:

```kotlin
val kind = when (terminated) {
    Terminated.USER_STOPPED -> RecoveryCardKind.USER_STOPPED
    Terminated.KILLED_BY_SYSTEM -> RecoveryCardKind.KILLED_BY_SYSTEM
    Terminated.KILLED_FORCE_STOP -> RecoveryCardKind.KILLED_FORCE_STOP
    Terminated.COMPLETED -> return null
    Terminated.MULTI_SEGMENT_KEPT -> return null   // Phase 4.3 — defensive (isEligible already filters)
}
```

- [ ] **Step 5: Add the mapper test**

Append to `RecoveryUiStateMapperTest.kt`:

```kotlin
@Test
fun `MULTI_SEGMENT_KEPT terminated hides the card`() {
    val manifest = SessionManifest(
        sessionId = "sess-1",
        startedAt = 1_000L,
        terminated = Terminated.MULTI_SEGMENT_KEPT,
        terminatedAt = 2_000L,
        // other required fields per existing test factory ...
    )
    val view = RecoverySessionView(
        manifest = manifest,
        classification = SessionClassification(
            eligibility = DiscardEligibility.OFFER_DISCARD,
            appendedSegmentFilenames = listOf("seg_0.mp4"),
            anomalies = emptyList(),
        ),
    )
    val state = RecoveryUiStateMapper.map(listOf(view))
    assertEquals(RecoveryUiState.Empty, state, "MULTI_SEGMENT_KEPT must not surface a card")
}
```

(If the existing test file has a `manifestOf(...)` test helper, prefer it; verify by reading `RecoveryUiStateMapperTest.kt` at the start of the task and pattern-match its existing fixtures.)

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.data.TerminatedMultiSegmentKeptTest" --tests "com.aritr.rova.ui.recovery.RecoveryUiStateMapperTest"`
Expected: PASS.

- [ ] **Step 7: Run the broader test suite to catch exhaustive-when failures elsewhere**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: PASS. If any new `Terminated` exhaustive `when` site breaks, add a `MULTI_SEGMENT_KEPT ->` arm that mirrors `COMPLETED` or `USER_STOPPED` as appropriate. Re-run.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/data/SessionManifest.kt \
        app/src/main/java/com/aritr/rova/ui/recovery/RecoveryUiState.kt \
        app/src/test/java/com/aritr/rova/data/TerminatedMultiSegmentKeptTest.kt \
        app/src/test/java/com/aritr/rova/ui/recovery/RecoveryUiStateMapperTest.kt
git commit -m "feat(data): add Terminated.MULTI_SEGMENT_KEPT + hide rule in recovery mapper"
```

---

### Task 2: Add `WarningId.CANT_MERGE` at row #14

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt`
- Test (mod): `app/src/test/java/com/aritr/rova/ui/warnings/WarningIdOrderTest.kt`

- [ ] **Step 1: Update the ordinal pin test first**

Open `WarningIdOrderTest.kt`, find the assertion list (it pins each `WarningId.<name>.ordinal == N`). Insert a new line after `THERMAL_AUTOSTOPPED` (current row #13) and renumber the rows below:

```kotlin
@Test
fun `warning id ordinals are pinned`() {
    assertEquals(0, WarningId.CAMERA_PERMISSION_DENIED.ordinal)
    assertEquals(1, WarningId.EXACT_ALARM_DENIED.ordinal)
    assertEquals(2, WarningId.STORAGE_INSUFFICIENT.ordinal)
    assertEquals(3, WarningId.THERMAL_SHUTDOWN.ordinal)
    assertEquals(4, WarningId.THERMAL_EMERGENCY.ordinal)
    assertEquals(5, WarningId.THERMAL_CRITICAL.ordinal)
    assertEquals(6, WarningId.BATTERY_CRITICAL.ordinal)
    assertEquals(7, WarningId.CAMERA_IN_USE.ordinal)
    assertEquals(8, WarningId.CAMERA_DISABLED.ordinal)
    assertEquals(9, WarningId.BATTERY_LOW.ordinal)
    assertEquals(10, WarningId.STORAGE_LOW_MID_REC.ordinal)
    assertEquals(11, WarningId.STORAGE_FULL_AUTOSTOPPED.ordinal)
    assertEquals(12, WarningId.THERMAL_AUTOSTOPPED.ordinal)
    assertEquals(13, WarningId.CANT_MERGE.ordinal)                  // Phase 4.3
    assertEquals(14, WarningId.THERMAL_SEVERE.ordinal)
    assertEquals(15, WarningId.MICROPHONE_DENIED.ordinal)
    assertEquals(16, WarningId.BATTERY_OPTIMIZATION_ON.ordinal)
    assertEquals(17, WarningId.POWER_SAVE_MODE.ordinal)
    assertEquals(18, WarningId.THERMAL_MODERATE.ordinal)
    assertEquals(19, WarningId.NOTIFICATIONS_DENIED.ordinal)
    assertEquals(20, WarningId.values().size, "WarningId must have exactly 20 entries after Phase 4.3")
}
```

(Adapt to the existing test name + structure — read `WarningIdOrderTest.kt` first and match its current style.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningIdOrderTest"`
Expected: FAIL with `WarningId.CANT_MERGE` unresolved reference.

- [ ] **Step 3: Insert `CANT_MERGE` at row #14**

Edit `app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt` — replace the enum body so the order matches the spec:

```kotlin
enum class WarningId(val tier: WarningTier, val gatesStart: Boolean = false) {
    // Hard block — recording can't start / must abort
    CAMERA_PERMISSION_DENIED(WarningTier.HARD_BLOCK, gatesStart = true),   // #1
    EXACT_ALARM_DENIED(WarningTier.HARD_BLOCK),                            // #2
    STORAGE_INSUFFICIENT(WarningTier.HARD_BLOCK, gatesStart = true),       // #3
    // Critical — active session at risk
    THERMAL_SHUTDOWN(WarningTier.CRITICAL),             // #4
    THERMAL_EMERGENCY(WarningTier.CRITICAL),            // #5
    THERMAL_CRITICAL(WarningTier.CRITICAL),             // #6
    BATTERY_CRITICAL(WarningTier.CRITICAL),             // #7
    CAMERA_IN_USE(WarningTier.CRITICAL),                // #8
    CAMERA_DISABLED(WarningTier.CRITICAL),              // #9
    // Advisory — degraded but functional
    BATTERY_LOW(WarningTier.ADVISORY),                  // #10
    STORAGE_LOW_MID_REC(WarningTier.ADVISORY),          // #11
    STORAGE_FULL_AUTOSTOPPED(WarningTier.ADVISORY),     // #12
    THERMAL_AUTOSTOPPED(WarningTier.ADVISORY),          // #13
    CANT_MERGE(WarningTier.ADVISORY),                   // #14  ← NEW (Phase 4.3 — recovery merge pre-flight failed)
    THERMAL_SEVERE(WarningTier.ADVISORY),               // #15
    MICROPHONE_DENIED(WarningTier.ADVISORY),            // #16
    BATTERY_OPTIMIZATION_ON(WarningTier.ADVISORY),      // #17
    POWER_SAVE_MODE(WarningTier.ADVISORY),              // #18
    THERMAL_MODERATE(WarningTier.ADVISORY),             // #19
    NOTIFICATIONS_DENIED(WarningTier.ADVISORY)          // #20
}
```

- [ ] **Step 4: Run ordinal test — expect PASS, then run broader suite — expect exhaustive `when` failures elsewhere**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningIdOrderTest"`
Expected: PASS.

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: compile errors in `WarningSheetContent.kt` (`warningSurfaceFor`, `warningSheetContent`, `midRecBannerContent`) — Tasks 3 will fix these. Note them; do not write the CANT_MERGE arms here yet. Instead, add **placeholder arms** so the rest of the suite compiles:

In `WarningSheetContent.kt::warningSurfaceFor`, temporarily add (Task 3 finalizes):
```kotlin
WarningId.CANT_MERGE -> WarningSurface.AdvisorySheet
```
In `warningSheetContent`, add a defensive placeholder arm (will be replaced in Task 3):
```kotlin
WarningId.CANT_MERGE -> WarningSheetContent(
    Icons.Default.Storage, "Can't merge yet", "",
    WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS), null,
)
```
In `midRecBannerContent`, add `WarningId.CANT_MERGE` to the existing defensive list:
```kotlin
WarningId.CAMERA_PERMISSION_DENIED,
WarningId.EXACT_ALARM_DENIED,
WarningId.STORAGE_INSUFFICIENT,
WarningId.MICROPHONE_DENIED,
WarningId.BATTERY_OPTIMIZATION_ON,
WarningId.POWER_SAVE_MODE,
WarningId.NOTIFICATIONS_DENIED,
WarningId.CANT_MERGE ->
    error("midRecBannerContent called for non-mid-rec id $id — caller bug; gate on warningSurfaceFor(id) == TopBanner")
```

Also update any `WarningPrecedence.resolve` exhaustive `when` over `WarningId` — Task 9 finalizes the precedence logic, but a temporary arm may be required to compile (likely a `WarningId.CANT_MERGE -> null` fallthrough — read `WarningPrecedence.kt` first to confirm shape).

Re-run `./gradlew.bat :app:testDebugUnitTest` — expected PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningId.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningIdOrderTest.kt
git commit -m "feat(ui): add WarningId.CANT_MERGE row #14 with placeholder arms"
```

---

### Task 3: Finalize `WarningSheetContent` for CANT_MERGE — sheet body, `tertiary`, `WarningActionStyle`, new ActionTargets

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt`
- Test (mod): `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentTest.kt`
- Test (mod): `app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentV3Test.kt` (style defaults)
- Test (mod): `app/src/test/java/com/aritr/rova/ui/warnings/MidRecBannerContentTest.kt`

- [ ] **Step 1: Write the failing sheet-content test**

Append to `WarningSheetContentTest.kt`:

```kotlin
@Test
fun `CANT_MERGE sheet renders 3 CTAs with link-styled tertiary`() {
    val content = warningSheetContent(WarningId.CANT_MERGE)
    assertEquals("Can't merge yet", content.title)
    assertEquals("Free space and retry, or keep the raw segments for later.", content.body)
    assertEquals("Free space & retry", content.primary.label)
    assertEquals(ActionTarget.STORAGE_SETTINGS, content.primary.target)
    assertNotNull(content.secondary)
    assertEquals("Save segments only", content.secondary!!.label)
    assertEquals(ActionTarget.KEEP_SEGMENTS_ONLY, content.secondary!!.target)
    assertNotNull(content.tertiary)
    assertEquals("Discard session", content.tertiary!!.label)
    assertEquals(ActionTarget.DISCARD_RECOVERY_SESSION, content.tertiary!!.target)
    assertEquals(WarningActionStyle.Link, content.tertiary!!.style)
}

@Test
fun `CANT_MERGE surface is AdvisorySheet`() {
    assertEquals(WarningSurface.AdvisorySheet, warningSurfaceFor(WarningId.CANT_MERGE))
}
```

Append to `WarningSheetContentV3Test.kt` (or its closest peer — verify which file currently tests `WarningAction` defaults):

```kotlin
@Test
fun `WarningAction defaults to Primary style`() {
    val a = WarningAction("OK", ActionTarget.APP_DETAILS_SETTINGS)
    assertEquals(WarningActionStyle.Primary, a.style)
}
```

Append to `MidRecBannerContentTest.kt`:

```kotlin
@Test(expected = IllegalStateException::class)
fun `CANT_MERGE throws — never renders mid-rec`() {
    midRecBannerContent(WarningId.CANT_MERGE)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningSheetContentTest" --tests "com.aritr.rova.ui.warnings.WarningSheetContentV3Test" --tests "com.aritr.rova.ui.warnings.MidRecBannerContentTest"`
Expected: FAIL (unresolved references `WarningActionStyle`, `KEEP_SEGMENTS_ONLY`, `DISCARD_RECOVERY_SESSION`, `tertiary`).

- [ ] **Step 3: Add `WarningActionStyle` enum + `style` field on `WarningAction` + `tertiary` field on `WarningSheetContent` + two new `ActionTarget`s**

Edit `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt`:

After the `WarningSurface` enum (around line 34), insert:

```kotlin
/**
 * Phase 4.3 — visual style hint for a [WarningAction]. Defaults to
 * [Primary] so all existing sheets compile without change. [Link]
 * renders as the destructive-text-only treatment (red, transparent
 * background, underlined) used by C2.4 §2.4 "Discard session".
 */
internal enum class WarningActionStyle { Primary, Secondary, Link }
```

Update `WarningAction`:

```kotlin
internal data class WarningAction(
    val label: String,
    val target: ActionTarget,
    val style: WarningActionStyle = WarningActionStyle.Primary,
)
```

Update `ActionTarget` — append:

```kotlin
/** Phase 4.3 — VM-only target: routes to RecoveryViewModel.keepRaw(sessionId). NOT an Intent. */
KEEP_SEGMENTS_ONLY,
/** Phase 4.3 — VM-only target: routes to RecoveryViewModel.dismiss(sessionId). NOT an Intent. */
DISCARD_RECOVERY_SESSION,
```

Update `WarningSheetContent` — add `tertiary` field:

```kotlin
internal data class WarningSheetContent(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val primary: WarningAction,
    val secondary: WarningAction?,
    /**
     * Phase 4.3 — optional third CTA, stacked under [secondary]. Used by
     * C2.4 ("Can't merge yet") for the "Discard session" destructive-link.
     * Null = no third row rendered (back-compat for all existing sheets).
     */
    val tertiary: WarningAction? = null,
    val overflow: List<WarningAction> = emptyList(),
    val whyThisMatters: String? = null,
)
```

Replace the placeholder `WarningId.CANT_MERGE ->` arm in `warningSheetContent` (added in Task 2) with the final body:

```kotlin
WarningId.CANT_MERGE -> WarningSheetContent(
    Icons.Default.Storage, "Can't merge yet",
    "Free space and retry, or keep the raw segments for later.",
    primary = WarningAction("Free space & retry", ActionTarget.STORAGE_SETTINGS),
    secondary = WarningAction("Save segments only", ActionTarget.KEEP_SEGMENTS_ONLY, WarningActionStyle.Secondary),
    tertiary = WarningAction("Discard session", ActionTarget.DISCARD_RECOVERY_SESSION, WarningActionStyle.Link),
)
```

(The `Icons.Default.Storage` choice matches the spec; spec §4.2 mentions `MergeType` as preferred but Storage is the safe fallback that's already imported in this file.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningSheetContentTest" --tests "com.aritr.rova.ui.warnings.WarningSheetContentV3Test" --tests "com.aritr.rova.ui.warnings.MidRecBannerContentTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetContent.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentTest.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningSheetContentV3Test.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/MidRecBannerContentTest.kt
git commit -m "feat(ui): finalize CANT_MERGE sheet content + WarningActionStyle + tertiary CTA slot"
```

---

### Task 4: Add `ExportResult.InsufficientStorage` variant

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/export/ExportTypes.kt`

- [ ] **Step 1: Edit `ExportTypes.kt` — append to the `ExportResult` sealed hierarchy**

Inside `sealed class ExportResult { ... }`, before the closing brace, add:

```kotlin
/**
 * Phase 4.3 — recovery merge pre-flight refused the run because
 * available storage at the destination is below the required size +
 * overhead. No `MediaMuxer` was opened; nothing was written. Caller
 * routes this to [RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage]
 * which surfaces `WarningId.CANT_MERGE` on the next Idle.
 */
data class InsufficientStorage(
    val requiredBytes: Long,
    val availableBytes: Long,
) : ExportResult()
```

- [ ] **Step 2: Compile check — exhaustive `when` over `ExportResult` may have broken**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: PASS (no exhaustive `when` over `ExportResult` exists today — caller code uses `when (val r = ...) { is Success -> ...; else -> ... }` pattern). If a site breaks, add an `is InsufficientStorage ->` arm matching the surrounding error-handling pattern.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/export/ExportTypes.kt
git commit -m "feat(service): add ExportResult.InsufficientStorage variant for recovery pre-flight"
```

---

### Task 5: Add `ExportPipeline.exportRecovered` with eager pre-flight

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/export/ExportPipeline.kt`
- Test (new): `app/src/test/java/com/aritr/rova/service/export/ExportPipelineRecoveredPreflightTest.kt`

- [ ] **Step 1: Write the failing pre-flight test**

`ExportPipelineRecoveredPreflightTest.kt`:

```kotlin
package com.aritr.rova.service.export

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ExportPipelineRecoveredPreflightTest {

    @Test
    fun `pre-flight returns InsufficientStorage when available bytes below required`() = runTest {
        // 100 KB segment × 1.05 = 105 KB required; only 50 KB available.
        val seg = File.createTempFile("seg", ".mp4").apply {
            writeBytes(ByteArray(100_000))
            deleteOnExit()
        }
        var muxCalled = false
        val result = ExportPipeline.exportRecoveredForTest(
            segments = listOf(seg),
            availableBytesProvider = { 50_000L },
            performMerge = { _, _ ->
                muxCalled = true
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
            },
            onProgress = {},
        )
        assertIs<ExportResult.InsufficientStorage>(result)
        assertEquals(105_000L, (result as ExportResult.InsufficientStorage).requiredBytes)
        assertEquals(50_000L, result.availableBytes)
        assertEquals(false, muxCalled, "pre-flight must short-circuit before mux")
    }

    @Test
    fun `pre-flight passes through to merge when storage sufficient`() = runTest {
        val seg = File.createTempFile("seg", ".mp4").apply {
            writeBytes(ByteArray(100_000))
            deleteOnExit()
        }
        var muxCalled = false
        val result = ExportPipeline.exportRecoveredForTest(
            segments = listOf(seg),
            availableBytesProvider = { 10_000_000L },
            performMerge = { _, _ ->
                muxCalled = true
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
            },
            onProgress = {},
        )
        assertIs<ExportResult.Success>(result)
        assertEquals(true, muxCalled)
    }
}
```

This test exercises a **test seam** `exportRecoveredForTest` that you'll add in Step 3 — same shape as the production `exportRecovered`, but with the storage-probe and tier-dispatch injected as lambdas (mirrors how the rest of the codebase tests `ExportPipeline` — see `Tier1ExporterTest.kt` for the precedent).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.export.ExportPipelineRecoveredPreflightTest"`
Expected: FAIL (unresolved reference `exportRecoveredForTest`, `InsufficientStorage` already exists from Task 4).

- [ ] **Step 3: Add `exportRecovered` + `exportRecoveredForTest` seam**

Edit `app/src/main/java/com/aritr/rova/service/export/ExportPipeline.kt` — inside `internal object ExportPipeline`, after the existing `export(...)` function, add:

```kotlin
/**
 * Phase 4.3 — recovery merge entry. Same tier dispatch as [export] but
 * runs an eager storage pre-flight before opening any muxer; returns
 * [ExportResult.InsufficientStorage] if the destination cannot hold
 * the merged file. Caller maps this to `WarningId.CANT_MERGE` via
 * [RecoveryMergeOutcomeSignal].
 *
 * The 5% headroom over `sum(segment.length())` accounts for muxer
 * overhead (track tables, moov atom, padding).
 */
suspend fun exportRecovered(
    context: Context,
    sessionStore: SessionStore,
    sessionId: String,
    sessionDir: File,
    segments: List<File>,
    mediaScanWaiter: MediaScanWaiter = AndroidMediaScanWaiter(context),
    onProgress: (Float) -> Unit,
): ExportResult {
    val required = (segments.sumOf { it.length() } * 1.05).toLong()
    val available = sessionDir.usableSpace
    if (available < required) {
        return ExportResult.InsufficientStorage(requiredBytes = required, availableBytes = available)
    }
    // Reuse the live tier dispatch verbatim — recovery merge is identical to
    // live merge once the pre-flight clears. `side = null` because recovery
    // merge is never a P+L per-side resume (P+L per-side artifacts are
    // already mid-export when the kill happens; that path uses the existing
    // ExportRecoveryRunner cold-launch flow, not this entry).
    return export(
        context = context,
        sessionStore = sessionStore,
        sessionId = sessionId,
        sessionDir = sessionDir,
        segments = segments,
        mediaScanWaiter = mediaScanWaiter,
        side = null,
        onProgress = onProgress,
    )
}

/**
 * Phase 4.3 — pure test seam. Same shape as [exportRecovered] but with
 * the storage probe and merge step injected as lambdas so the pre-flight
 * branch can be verified without `Context` / `SessionStore` / `MediaMuxer`
 * dependencies. Production calls [exportRecovered]; tests call this.
 */
internal suspend fun exportRecoveredForTest(
    segments: List<File>,
    availableBytesProvider: () -> Long,
    performMerge: suspend (List<File>, (Float) -> Unit) -> ExportResult,
    onProgress: (Float) -> Unit,
): ExportResult {
    val required = (segments.sumOf { it.length() } * 1.05).toLong()
    val available = availableBytesProvider()
    if (available < required) {
        return ExportResult.InsufficientStorage(requiredBytes = required, availableBytes = available)
    }
    return performMerge(segments, onProgress)
}
```

Wait — the production `exportRecovered` and the test seam compute `required` identically; the test seam exists so we can fake `usableSpace` (which is a `File` property, hard to stub without Robolectric). The risk of divergence is low because the formula is one line. If you want extra safety, factor `requiredBytesFor(segments)` into a `private fun` and reuse — optional.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.export.ExportPipelineRecoveredPreflightTest"`
Expected: PASS.

- [ ] **Step 5: Verify the `checkExportPipelineSingleEntry` Gradle check still passes**

The check matches the literal `ExportPipeline.export(` (with parenthesis after `export`). `ExportPipeline.exportRecovered(` does NOT contain that substring (the period is followed by `exportRecovered`, not `export(`). No check update needed.

Run: `./gradlew.bat :app:checkExportPipelineSingleEntry`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/export/ExportPipeline.kt \
        app/src/test/java/com/aritr/rova/service/export/ExportPipelineRecoveredPreflightTest.kt
git commit -m "feat(service): add ExportPipeline.exportRecovered with eager storage pre-flight"
```

---

### Task 6: Create `RecoveryMergeOutcomeSignal`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/signals/RecoveryMergeOutcomeSignal.kt`
- Test (new): `app/src/test/java/com/aritr/rova/ui/signals/RecoveryMergeOutcomeSignalTest.kt`

- [ ] **Step 1: Write the failing signal test**

`RecoveryMergeOutcomeSignalTest.kt`:

```kotlin
package com.aritr.rova.ui.signals

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RecoveryMergeOutcomeSignalTest {

    @Test
    fun `initial state is Idle`() {
        val signal = RecoveryMergeOutcomeSignal()
        assertEquals(RecoveryMergeOutcomeSignal.State.Idle, signal.state.value)
    }

    @Test
    fun `emitInProgress carries sessionId and progress`() = runTest {
        val signal = RecoveryMergeOutcomeSignal()
        signal.emitInProgress("sess-1", 0.25f)
        val s = signal.state.first()
        assertIs<RecoveryMergeOutcomeSignal.State.InProgress>(s)
        assertEquals("sess-1", s.sessionId)
        assertEquals(0.25f, s.progress)
    }

    @Test
    fun `emitOutcome InsufficientStorage carries required and available bytes`() = runTest {
        val signal = RecoveryMergeOutcomeSignal()
        signal.emitOutcome(
            sessionId = "sess-2",
            outcome = RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage(
                requiredBytes = 105_000,
                availableBytes = 50_000,
            ),
        )
        val s = signal.state.value
        assertIs<RecoveryMergeOutcomeSignal.State.Outcome>(s)
        assertEquals("sess-2", s.sessionId)
        val o = s.outcome
        assertIs<RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage>(o)
        assertEquals(105_000L, o.requiredBytes)
    }

    @Test
    fun `acknowledge resets state to Idle`() = runTest {
        val signal = RecoveryMergeOutcomeSignal()
        signal.emitOutcome("sess-3", RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.Succeeded)
        signal.acknowledge("sess-3")
        assertEquals(RecoveryMergeOutcomeSignal.State.Idle, signal.state.value)
    }

    @Test
    fun `acknowledge with wrong sessionId is a no-op`() = runTest {
        val signal = RecoveryMergeOutcomeSignal()
        signal.emitOutcome("sess-4", RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.Succeeded)
        signal.acknowledge("sess-different")
        val s = signal.state.value
        assertIs<RecoveryMergeOutcomeSignal.State.Outcome>(s)
        assertEquals("sess-4", s.sessionId)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignalTest"`
Expected: FAIL — file does not exist.

- [ ] **Step 3: Create the signal**

`app/src/main/java/com/aritr/rova/ui/signals/RecoveryMergeOutcomeSignal.kt`:

```kotlin
package com.aritr.rova.ui.signals

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 4.3 — push signal for the recovery merge lifecycle. Lives on
 * [com.aritr.rova.RovaApp] as a lazy singleton; producer is
 * [com.aritr.rova.service.recovery.RecoveryMerger] (running inside the
 * FGS), consumers are:
 *  - `RecoveryViewModel`: combines [State.InProgress] into
 *    `RecoveryCardState.mergeInProgress` so the clip-progress strip fills.
 *  - `WarningCenterViewModel`: lifts [State.Outcome.InsufficientStorage]
 *    into `WarningId.CANT_MERGE` on the next Idle precedence pick.
 *
 * `acknowledge(sessionId)` is called when the consumer has rendered the
 * outcome (e.g., user dismissed the CANT_MERGE sheet, or the merge
 * succeeded and the card disappeared via terminal write). Idempotent
 * and sessionId-scoped — acknowledging a different session does nothing,
 * so a late acknowledgement for a stale outcome cannot wipe a fresher one.
 */
class RecoveryMergeOutcomeSignal {

    sealed class State {
        object Idle : State()
        data class InProgress(val sessionId: String, val progress: Float) : State()
        data class Outcome(val sessionId: String, val outcome: RecoveryMergeOutcome) : State()
    }

    sealed class RecoveryMergeOutcome {
        object Succeeded : RecoveryMergeOutcome()
        data class InsufficientStorage(
            val requiredBytes: Long,
            val availableBytes: Long,
        ) : RecoveryMergeOutcome()
        data class MuxFailed(val cause: Throwable) : RecoveryMergeOutcome()
        object ServiceBusy : RecoveryMergeOutcome()
        object UnknownSession : RecoveryMergeOutcome()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun emitInProgress(sessionId: String, progress: Float) {
        _state.value = State.InProgress(sessionId, progress.coerceIn(0f, 1f))
    }

    fun emitOutcome(sessionId: String, outcome: RecoveryMergeOutcome) {
        _state.value = State.Outcome(sessionId, outcome)
    }

    fun acknowledge(sessionId: String) {
        val current = _state.value
        if (current is State.Outcome && current.sessionId == sessionId) {
            _state.value = State.Idle
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignalTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/signals/RecoveryMergeOutcomeSignal.kt \
        app/src/test/java/com/aritr/rova/ui/signals/RecoveryMergeOutcomeSignalTest.kt
git commit -m "feat(ui): add RecoveryMergeOutcomeSignal for recovery merge lifecycle"
```

---

### Task 7: Create `RecoveryMerger`

**Files:**
- Create: `app/src/main/java/com/aritr/rova/service/recovery/RecoveryMerger.kt`
- Test (new): `app/src/test/java/com/aritr/rova/service/recovery/RecoveryMergerTest.kt`

- [ ] **Step 1: Write the failing test**

`RecoveryMergerTest.kt`:

```kotlin
package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RecoveryMergerTest {

    private val tempDir = File.createTempFile("recovery-merger-", "").apply {
        delete(); mkdirs(); deleteOnExit()
    }

    private fun mergerOf(
        segments: List<File> = listOf(File(tempDir, "seg_0.mp4").apply { writeBytes(ByteArray(100)) }),
        sessionDir: File = tempDir,
        export: suspend (List<File>, (Float) -> Unit) -> ExportResult,
        progressCollector: (String, Float) -> Unit = { _, _ -> },
    ): Pair<RecoveryMergeOutcomeSignal, RecoveryMerger> {
        val signal = RecoveryMergeOutcomeSignal()
        val merger = RecoveryMerger(
            loadSegments = { _ -> segments },
            sessionDirOf = { _ -> sessionDir },
            exportRecovered = { _, segs, onProgress -> export(segs, onProgress) },
            signal = signal,
            onProgress = { sid, p ->
                progressCollector(sid, p)
                signal.emitInProgress(sid, p)
            },
        )
        return signal to merger
    }

    @Test
    fun `Success maps to Succeeded outcome`() = runTest {
        val (signal, merger) = mergerOf(export = { _, _ -> ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false) })
        val outcome = merger.run("sess-1")
        assertEquals(RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.Succeeded, outcome)
        val s = signal.state.value
        assertIs<RecoveryMergeOutcomeSignal.State.Outcome>(s)
        assertEquals("sess-1", s.sessionId)
    }

    @Test
    fun `InsufficientStorage carries bytes`() = runTest {
        val (_, merger) = mergerOf(export = { _, _ -> ExportResult.InsufficientStorage(requiredBytes = 200, availableBytes = 50) })
        val outcome = merger.run("sess-2")
        assertIs<RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage>(outcome)
        assertEquals(200L, outcome.requiredBytes)
        assertEquals(50L, outcome.availableBytes)
    }

    @Test
    fun `MuxFailed wraps cause`() = runTest {
        val cause = RuntimeException("boom")
        val (_, merger) = mergerOf(export = { _, _ -> ExportResult.MuxFailed(cause) })
        val outcome = merger.run("sess-3")
        assertIs<RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.MuxFailed>(outcome)
        assertEquals(cause, outcome.cause)
    }

    @Test
    fun `UnknownSession from ExportPipeline maps to UnknownSession outcome`() = runTest {
        val (_, merger) = mergerOf(export = { _, _ -> ExportResult.UnknownSession("sess-4") })
        val outcome = merger.run("sess-4")
        assertEquals(RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.UnknownSession, outcome)
    }

    @Test
    fun `empty segments shortcircuits to UnknownSession`() = runTest {
        val (_, merger) = mergerOf(
            segments = emptyList(),
            export = { _, _ -> error("should not be called") },
        )
        val outcome = merger.run("sess-empty")
        assertEquals(RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.UnknownSession, outcome)
    }

    @Test
    fun `progress callback fires for each emission with sessionId`() = runTest {
        val captures = mutableListOf<Pair<String, Float>>()
        val (_, merger) = mergerOf(
            export = { _, onProgress ->
                onProgress(0.25f); onProgress(0.5f); onProgress(1f)
                ExportResult.Success(mediaScanCompleted = true, privateTempRetained = false)
            },
            progressCollector = { sid, p -> captures += sid to p },
        )
        merger.run("sess-5")
        assertEquals(
            listOf("sess-5" to 0.25f, "sess-5" to 0.5f, "sess-5" to 1f),
            captures,
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.RecoveryMergerTest"`
Expected: FAIL — class does not exist.

- [ ] **Step 3: Create `RecoveryMerger`**

`app/src/main/java/com/aritr/rova/service/recovery/RecoveryMerger.kt`:

```kotlin
package com.aritr.rova.service.recovery

import com.aritr.rova.service.export.ExportResult
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal.RecoveryMergeOutcome
import java.io.File

/**
 * Phase 4.3 — wraps the `ExportPipeline.exportRecovered` call for a
 * single recovery merge session. Owns:
 *  - Segment list lookup (via [loadSegments]) from `SessionStore`.
 *  - Session-directory resolution (via [sessionDirOf]).
 *  - Translation from `ExportResult` to `RecoveryMergeOutcome`.
 *  - Push to [RecoveryMergeOutcomeSignal] for both progress + final.
 *
 * Constructor seams keep this JVM-testable: production wires
 * `ExportPipeline.exportRecovered` + `app.sessionStore` lambdas;
 * tests inject pure-function fakes. `onProgress` is split out so tests
 * can capture progress without subclassing the final `RecoveryMergeOutcomeSignal`.
 *
 * Hosted by `RovaRecordingService.handleRecoveryMergeStart`.
 */
class RecoveryMerger(
    private val loadSegments: (sessionId: String) -> List<File>,
    private val sessionDirOf: (sessionId: String) -> File,
    private val exportRecovered: suspend (sessionDir: File, segments: List<File>, onProgress: (Float) -> Unit) -> ExportResult,
    private val signal: RecoveryMergeOutcomeSignal,
    private val onProgress: (String, Float) -> Unit = signal::emitInProgress,
) {

    suspend fun run(sessionId: String): RecoveryMergeOutcome {
        val segments = loadSegments(sessionId)
        if (segments.isEmpty()) {
            val outcome = RecoveryMergeOutcome.UnknownSession
            signal.emitOutcome(sessionId, outcome)
            return outcome
        }
        val sessionDir = sessionDirOf(sessionId)
        val result = exportRecovered(sessionDir, segments) { progress ->
            onProgress(sessionId, progress)
        }
        val outcome = result.toRecoveryOutcome()
        signal.emitOutcome(sessionId, outcome)
        return outcome
    }

    private fun ExportResult.toRecoveryOutcome(): RecoveryMergeOutcome = when (this) {
        is ExportResult.Success -> RecoveryMergeOutcome.Succeeded
        is ExportResult.InsufficientStorage ->
            RecoveryMergeOutcome.InsufficientStorage(requiredBytes, availableBytes)
        is ExportResult.MuxFailed -> RecoveryMergeOutcome.MuxFailed(cause)
        is ExportResult.UnknownSession -> RecoveryMergeOutcome.UnknownSession
        // All remaining ExportResult variants (CopyFailed, RenameFailed,
        // PendingInsertFailed, FinalizeFailed, ManifestWriteFailed) collapse
        // to MuxFailed for the user-facing recovery surface — the UI cannot
        // distinguish them and the user action is identical (retry or keep
        // raw). The underlying ExportResult is still recorded by the
        // pipeline's failure path; only the user-surface compresses.
        is ExportResult.CopyFailed -> RecoveryMergeOutcome.MuxFailed(cause)
        is ExportResult.RenameFailed -> RecoveryMergeOutcome.MuxFailed(IllegalStateException("rename failed"))
        is ExportResult.PendingInsertFailed -> RecoveryMergeOutcome.MuxFailed(cause ?: IllegalStateException("pending insert failed"))
        is ExportResult.FinalizeFailed -> RecoveryMergeOutcome.MuxFailed(cause ?: IllegalStateException("finalize failed"))
        is ExportResult.ManifestWriteFailed -> RecoveryMergeOutcome.MuxFailed(cause)
    }
}
```

`signal::emitInProgress` works as a default because `emitInProgress(String, Float)` is `public final fun` on `RecoveryMergeOutcomeSignal` (Task 6) — Kotlin can synthesize a `(String, Float) -> Unit` reference from it without `open`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.recovery.RecoveryMergerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/recovery/RecoveryMerger.kt \
        app/src/test/java/com/aritr/rova/service/recovery/RecoveryMergerTest.kt
git commit -m "feat(service): add RecoveryMerger wrapping exportRecovered + outcome translation"
```

---

### Task 8: Add `RovaRecordingService.ACTION_RECOVER_MERGE` branch + companion factory + active-recording guard

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`
- Modify: `app/src/main/java/com/aritr/rova/service/notification/NotificationCopy.kt` (or equivalent — recovery merge notification text)
- Test (new): `app/src/test/java/com/aritr/rova/service/RovaRecordingServiceRecoveryGateTest.kt`

The service guard is **pure logic** — extract it into a top-level helper so it's JVM-testable without spinning up a real `Service`. Precedent: `SegmentGateThermal` (extracted from the service for the thermal slice).

- [ ] **Step 1: Write the failing guard-helper test**

`RovaRecordingServiceRecoveryGateTest.kt`:

```kotlin
package com.aritr.rova.service

import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal
import org.junit.Test
import kotlin.test.assertEquals

class RovaRecordingServiceRecoveryGateTest {

    @Test
    fun `gate refuses when recording active`() {
        val outcome = recoveryMergeStartGate(isRecordingActive = true, sessionId = "sess-1")
        assertEquals(
            RecoveryMergeStartDecision.Reject(
                outcome = RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.ServiceBusy,
            ),
            outcome,
        )
    }

    @Test
    fun `gate accepts when idle`() {
        val outcome = recoveryMergeStartGate(isRecordingActive = false, sessionId = "sess-2")
        assertEquals(RecoveryMergeStartDecision.Accept(sessionId = "sess-2"), outcome)
    }

    @Test
    fun `gate rejects when sessionId blank`() {
        val outcome = recoveryMergeStartGate(isRecordingActive = false, sessionId = "")
        assertEquals(
            RecoveryMergeStartDecision.Reject(
                outcome = RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.UnknownSession,
            ),
            outcome,
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.RovaRecordingServiceRecoveryGateTest"`
Expected: FAIL — `recoveryMergeStartGate`, `RecoveryMergeStartDecision` unresolved.

- [ ] **Step 3: Add the pure gate helper**

Add to a NEW file (or to `RovaRecordingService.kt` near the existing top-level helpers like `sanitizeSensorOrientation`):

```kotlin
// app/src/main/java/com/aritr/rova/service/RecoveryMergeStartGate.kt
package com.aritr.rova.service

import com.aritr.rova.ui.signals.RecoveryMergeOutcomeSignal

/**
 * Phase 4.3 — pure decision helper for `RovaRecordingService`'s
 * `handleRecoveryMergeStart` branch. Extracted so the routing logic
 * is JVM-testable without a live `Service` instance. The service
 * branch reads its `isRecordingActive` boolean from existing
 * recording-state and dispatches per [RecoveryMergeStartDecision].
 */
internal sealed class RecoveryMergeStartDecision {
    data class Accept(val sessionId: String) : RecoveryMergeStartDecision()
    data class Reject(val outcome: RecoveryMergeOutcomeSignal.RecoveryMergeOutcome) : RecoveryMergeStartDecision()
}

internal fun recoveryMergeStartGate(
    isRecordingActive: Boolean,
    sessionId: String,
): RecoveryMergeStartDecision = when {
    sessionId.isBlank() -> RecoveryMergeStartDecision.Reject(
        RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.UnknownSession,
    )
    isRecordingActive -> RecoveryMergeStartDecision.Reject(
        RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.ServiceBusy,
    )
    else -> RecoveryMergeStartDecision.Accept(sessionId)
}
```

- [ ] **Step 4: Run helper test — expect PASS**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.service.RovaRecordingServiceRecoveryGateTest"`
Expected: PASS.

- [ ] **Step 5: Wire the gate + companion factory + intent branch into `RovaRecordingService.kt`**

Edit `app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt`:

Inside the `RovaRecordingService` class, add a `companion object` (or extend the existing one if present — grep first):

```kotlin
companion object {
    const val ACTION_RECOVER_MERGE = "com.aritr.rova.service.ACTION_RECOVER_MERGE"
    const val EXTRA_RECOVERY_SESSION_ID = "recovery_session_id"

    /**
     * Phase 4.3 — starts the FGS in recovery-merge mode for [sessionId].
     * The service's `onStartCommand` branches on `ACTION_RECOVER_MERGE`
     * and dispatches to [recoveryMergeStartGate]; the decision drives
     * either a merge launch or an immediate signal emission + stopSelf.
     */
    fun startRecoveryMerge(context: Context, sessionId: String) {
        val intent = Intent(context, RovaRecordingService::class.java).apply {
            action = ACTION_RECOVER_MERGE
            putExtra(EXTRA_RECOVERY_SESSION_ID, sessionId)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
```

In `onStartCommand`, branch BEFORE the existing recording logic:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_RECOVER_MERGE) {
        val sessionId = intent.getStringExtra(EXTRA_RECOVERY_SESSION_ID).orEmpty()
        return handleRecoveryMergeStart(sessionId, startId)
    }
    // ... existing recording branch unchanged ...
}

private fun handleRecoveryMergeStart(sessionId: String, startId: Int): Int {
    val app = application as RovaApp
    val signal = app.recoveryMergeOutcomeSignal       // wired in Task 9
    val decision = recoveryMergeStartGate(
        isRecordingActive = currentRecording != null,
        sessionId = sessionId,
    )
    return when (decision) {
        is RecoveryMergeStartDecision.Reject -> {
            signal.emitOutcome(sessionId, decision.outcome)
            stopSelf(startId)
            START_NOT_STICKY
        }
        is RecoveryMergeStartDecision.Accept -> {
            startForegroundForRecoveryMerge(decision.sessionId)
            serviceScope.launch {
                val merger = RecoveryMerger(
                    loadSegments = { sid -> app.sessionStore.loadSegments(sid) },
                    sessionDirOf = { sid -> app.sessionStore.sessionDirFor(sid) },
                    exportRecovered = { dir, segs, onProgress ->
                        com.aritr.rova.service.export.ExportPipeline.exportRecovered(
                            context = this@RovaRecordingService,
                            sessionStore = app.sessionStore,
                            sessionId = decision.sessionId,
                            sessionDir = dir,
                            segments = segs,
                            onProgress = onProgress,
                        )
                    },
                    signal = signal,
                )
                try {
                    merger.run(decision.sessionId)
                } finally {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            START_NOT_STICKY
        }
    }
}

private fun startForegroundForRecoveryMerge(sessionId: String) {
    val channelId = ensureNotificationChannel()   // reuse existing helper
    val notif = NotificationCompat.Builder(this, channelId)
        .setContentTitle("Merging recovered clips")
        .setContentText("Session ${sessionId.take(8)}…")
        .setSmallIcon(R.drawable.ic_notification)   // reuse existing icon
        .setOngoing(true)
        .build()
    startForeground(NOTIFICATION_ID_RECOVERY_MERGE, notif)
}

private companion object {
    private const val NOTIFICATION_ID_RECOVERY_MERGE = 4096   // distinct from recording id
}
```

(Adapt notification IDs / icon resource names / channel helper names to match existing code — grep first. `loadSegments` and `sessionDirFor` are seams you'll need to confirm exist on `SessionStore`; if not, expose them as small helpers there.)

- [ ] **Step 6: Verify exhaustive `when` on `RecoveryMergeStartDecision`**

Run: `./gradlew.bat :app:compileDebugKotlin`
Expected: PASS. Fix any missing `RovaApp.recoveryMergeOutcomeSignal` reference temporarily with a `TODO("wired in Task 9")` stub — but you cannot compile through this until Task 9. Reorder: do **Task 9** before this step, OR add the `recoveryMergeOutcomeSignal` lazy prop to `RovaApp` first (just the prop, not the consumers — those land in Task 9).

**Recommendation:** Add the `recoveryMergeOutcomeSignal` lazy prop on `RovaApp` here as a one-liner so this task compiles. Task 9 wires consumers.

`RovaApp.kt`:
```kotlin
val recoveryMergeOutcomeSignal: RecoveryMergeOutcomeSignal by lazy {
    RecoveryMergeOutcomeSignal()
}
```

- [ ] **Step 7: Full test run**

Run: `./gradlew.bat :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/service/RecoveryMergeStartGate.kt \
        app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt \
        app/src/main/java/com/aritr/rova/RovaApp.kt \
        app/src/test/java/com/aritr/rova/service/RovaRecordingServiceRecoveryGateTest.kt
git commit -m "feat(service): wire RovaRecordingService.startRecoveryMerge action branch + gate"
```

---

### Task 9: Wire `RecoveryMergeOutcomeSignal` → `WarningPrecedence` → `WarningCenterViewModel.CANT_MERGE`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt`
- Modify: `app/src/main/java/com/aritr/rova/RovaApp.kt` (factory wiring for WarningCenterViewModel)
- Test (mod): `app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt`
- Test (mod): `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt`

- [ ] **Step 1: Write the failing precedence test**

Append to `WarningPrecedenceTest.kt`:

```kotlin
@Test
fun `CANT_MERGE resolves when cantMergeActive=true and no higher-priority warning`() {
    val resolved = WarningPrecedence.resolve(
        // existing args defaulted to "no signal", plus the new arg:
        cantMergeActive = true,
        // ... whatever default-args helper the existing tests use ...
    )
    assertEquals(WarningId.CANT_MERGE, resolved)
}

@Test
fun `CANT_MERGE loses to STORAGE_FULL_AUTOSTOPPED (lower ordinal wins)`() {
    val resolved = WarningPrecedence.resolve(
        cantMergeActive = true,
        storageFullAutoStoppedActive = true,
        // ...
    )
    assertEquals(WarningId.STORAGE_FULL_AUTOSTOPPED, resolved)
}
```

(Read `WarningPrecedenceTest.kt` first to understand the existing `resolve` test signature. The two existing echo-style precedence tests for `STORAGE_FULL_AUTOSTOPPED` and `THERMAL_AUTOSTOPPED` are direct templates.)

- [ ] **Step 2: Add `cantMergeActive: Boolean` input to `WarningPrecedence.resolve` + new precedence arm**

Edit `WarningPrecedence.kt`. The function signature gains a new parameter; insert the `CANT_MERGE` precedence row between `THERMAL_AUTOSTOPPED` and `THERMAL_SEVERE` per the spec:

```kotlin
fun resolve(
    // ... existing parameters ...
    storageFullAutoStoppedActive: Boolean = false,
    thermalAutoStoppedActive: Boolean = false,
    cantMergeActive: Boolean = false,   // NEW — Phase 4.3
    // ... rest of existing parameters ...
): WarningId? {
    // ... existing rows ...
    // After THERMAL_AUTOSTOPPED row:
    if (cantMergeActive) return WarningId.CANT_MERGE
    // ... existing THERMAL_SEVERE row onward ...
}
```

(Adapt to the actual signature — read the file first. If `resolve` uses a `WarningInputs` data class rather than named-arg params, extend the data class.)

- [ ] **Step 3: Run precedence test — expect PASS**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.WarningPrecedenceTest"`
Expected: PASS.

- [ ] **Step 4: Wire the signal into `WarningCenterViewModel`**

Edit `WarningCenterViewModel.kt`:

- Constructor: add `recoveryMergeOutcomeSignal: StateFlow<RecoveryMergeOutcomeSignal.State>` parameter.
- Combine the new flow into the existing `combine(...)` that builds the resolved warning.
- Compute `cantMergeActive = (recoveryMergeOutcome is State.Outcome && outcome.outcome is RecoveryMergeOutcome.InsufficientStorage)` and pass to `WarningPrecedence.resolve(...)`.
- Expose `pendingCantMergeSessionId: StateFlow<String?>` that extracts the sessionId when the outcome is `InsufficientStorage`, null otherwise.

```kotlin
// Inside WarningCenterViewModel:
val pendingCantMergeSessionId: StateFlow<String?> = recoveryMergeOutcomeSignal
    .map { state ->
        when (state) {
            is RecoveryMergeOutcomeSignal.State.Outcome ->
                (state.outcome as? RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage)
                    ?.let { state.sessionId }
            else -> null
        }
    }
    .stateIn(scope, SharingStarted.Eagerly, null)
```

- [ ] **Step 5: Wire the signal source in `RovaApp.buildWarningCenterViewModel` (or equivalent factory)**

Grep for the existing factory:

```bash
grep -rn "WarningCenterViewModel(" app/src/main/java/com/aritr/rova/
```

At the factory call site, pass `recoveryMergeOutcomeSignal.state`.

- [ ] **Step 6: Aggregate-test verification**

Update `WarningCenterAggregateTest.kt` to add a CANT_MERGE case if the test factory expects all WarningIds. Run:

```
./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.warnings.*"
```
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningPrecedence.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningCenterViewModel.kt \
        app/src/main/java/com/aritr/rova/RovaApp.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningPrecedenceTest.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt
git commit -m "feat(ui): wire RecoveryMergeOutcomeSignal -> WarningPrecedence -> CANT_MERGE"
```

---

### Task 10: Extend `RecoveryCardState` + `RecoveryUiStateMapper`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryUiState.kt`
- Test (mod): `app/src/test/java/com/aritr/rova/ui/recovery/RecoveryUiStateMapperTest.kt`

- [ ] **Step 1: Write the failing mapper tests**

Append to `RecoveryUiStateMapperTest.kt`:

```kotlin
@Test
fun `mapper populates mergeLabel and keepRawLabel when survivingArtifacts non-empty`() {
    val view = RecoverySessionView(
        manifest = manifestOf(sessionId = "sess-1", terminated = Terminated.USER_STOPPED),
        classification = SessionClassification(
            eligibility = DiscardEligibility.OFFER_DISCARD,
            appendedSegmentFilenames = listOf("seg_0.mp4", "seg_1.mp4"),
            anomalies = emptyList(),
        ),
    )
    val card = RecoveryUiStateMapper.map(listOf(view)).cards.single()
    assertEquals("Merge segments", card.mergeLabel)
    assertEquals("Keep as raw clips", card.keepRawLabel)
    assertNull(card.mergeInProgress)
    assertNull(card.mergeFailedReason)
}

@Test
fun `mapper leaves mergeLabel null when no surviving artifacts`() {
    val view = RecoverySessionView(
        manifest = manifestOf(sessionId = "sess-2", terminated = Terminated.USER_STOPPED),
        classification = SessionClassification(
            eligibility = DiscardEligibility.OFFER_DISCARD,
            appendedSegmentFilenames = emptyList(),
            anomalies = emptyList(),
        ),
    )
    val card = RecoveryUiStateMapper.map(listOf(view)).cards.single()
    assertNull(card.mergeLabel)
    assertNull(card.keepRawLabel)
}
```

(Use the test's existing `manifestOf(...)` helper. If absent, build a full `SessionManifest` per the existing fixtures.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.recovery.RecoveryUiStateMapperTest"`
Expected: FAIL — unresolved properties on `RecoveryCardState`.

- [ ] **Step 3: Extend `RecoveryCardState` + mapper**

Edit `RecoveryUiState.kt`:

```kotlin
data class RecoveryCardState(
    val sessionId: String,
    val kind: RecoveryCardKind,
    val title: String,
    val body: String,
    val discardLabel: String,
    val showVendorHelpSlot: Boolean,
    val survivingArtifacts: List<String>,
    /** Phase 4.3 — null when there's nothing to merge (no surviving artifacts). */
    val mergeLabel: String? = null,
    /** Phase 4.3 — null when there's nothing to keep. Always co-set with [mergeLabel]. */
    val keepRawLabel: String? = null,
    /** Phase 4.3 — null=idle; 0..1=merge in progress (strip fills, buttons disabled). */
    val mergeInProgress: Float? = null,
    /** Phase 4.3 — non-null=last merge failed; body+button flip to retry copy. */
    val mergeFailedReason: String? = null,
)
```

Update `RecoveryUiStateMapper.toCard` to populate the new fields:

```kotlin
return RecoveryCardState(
    sessionId = view.manifest.sessionId,
    kind = kind,
    title = titleFor(kind),
    body = bodyFor(kind),
    discardLabel = DISCARD_LABEL,
    showVendorHelpSlot = (kind == RecoveryCardKind.KILLED_BY_SYSTEM),
    survivingArtifacts = summarize(view.classification),
    mergeLabel = if (view.classification.appendedSegmentFilenames.isNotEmpty()) MERGE_LABEL else null,
    keepRawLabel = if (view.classification.appendedSegmentFilenames.isNotEmpty()) KEEP_RAW_LABEL else null,
    // mergeInProgress + mergeFailedReason filled in by the VM after combining with the signal — not the mapper's job.
)
```

Add constants near `DISCARD_LABEL`:

```kotlin
private const val MERGE_LABEL = "Merge segments"
private const val KEEP_RAW_LABEL = "Keep as raw clips"
```

- [ ] **Step 4: Run tests — expect PASS**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.recovery.RecoveryUiStateMapperTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/recovery/RecoveryUiState.kt \
        app/src/test/java/com/aritr/rova/ui/recovery/RecoveryUiStateMapperTest.kt
git commit -m "feat(ui): extend RecoveryCardState with merge/keepRaw labels + progress"
```

---

### Task 11: Extend `RecoveryCard` composable — 3-CTA stack + progress-aware ProgressStrip

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt`

No new unit tests — `RecoveryCard` is render-only; the data layer's `RecoveryCardState` tests cover the inputs. Compose preview validation is manual.

- [ ] **Step 1: Edit the `RecoveryCard` composable signature**

```kotlin
@Composable
fun RecoveryCard(
    state: RecoveryCardState,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
    vendorHelpSlot: (@Composable () -> Unit)? = null,
    /** Phase 4.3 — null = button hidden (back-compat). When non-null with [onKeepRaw] non-null, the CTA row becomes a 3-button stack. */
    onMerge: (() -> Unit)? = null,
    onKeepRaw: (() -> Unit)? = null,
)
```

(`mergeInProgress` flows in via `state.mergeInProgress` — no separate param needed.)

- [ ] **Step 2: Replace the single-CTA `DestructiveCta` block with `CtaRow`**

Inside `RecoveryCard`'s `Column`, replace the existing `DestructiveCta(...)` call with:

```kotlin
CtaRow(
    state = state,
    onMerge = onMerge,
    onKeepRaw = onKeepRaw,
    onDiscard = onDiscard,
)
```

And add the new private composable at the bottom of the file:

```kotlin
@Composable
private fun CtaRow(
    state: RecoveryCardState,
    onMerge: (() -> Unit)?,
    onKeepRaw: (() -> Unit)?,
    onDiscard: () -> Unit,
) {
    val showThreeCtaStack = onMerge != null && onKeepRaw != null && state.mergeLabel != null && state.keepRawLabel != null
    val inFlight = state.mergeInProgress != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showThreeCtaStack) {
            // 1. Merge segments (primary; ink fill) — labels flip when retry is active.
            val mergeLabel = if (state.mergeFailedReason != null) "Retry merge" else state.mergeLabel!!
            PrimaryMergeCta(
                label = mergeLabel,
                enabled = !inFlight,
                onClick = { if (!inFlight) onMerge!!.invoke() },
            )
            // 2. Keep as raw clips (ghost; hairline border).
            GhostCta(
                label = state.keepRawLabel!!,
                enabled = !inFlight,
                onClick = { if (!inFlight) onKeepRaw!!.invoke() },
            )
        }
        // 3. Discard recording — always present, last in stack.
        DestructiveCta(
            label = state.discardLabel,
            onClick = onDiscard,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription =
                        "${state.discardLabel}. This action is permanent."
                },
        )
    }
}

@Composable
private fun PrimaryMergeCta(label: String, enabled: Boolean, onClick: () -> Unit) {
    val accent = Color(0xFF5B7FFF)   // existing v3 primary ink — verify token name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = if (enabled) 1f else 0.40f))
            .clickable(enabled = enabled) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = if (enabled) 1f else 0.55f),
        )
    }
}

@Composable
private fun GhostCta(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (enabled) 0.20f else 0.10f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = enabled) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = if (enabled) 0.80f else 0.40f),
        )
    }
}
```

- [ ] **Step 3: Update `ProgressStrip` to accept an optional `progress: Float?`**

Replace the existing `ProgressStrip` signature/body:

```kotlin
@Composable
private fun ProgressStrip(artifactCount: Int, accent: Color, progress: Float? = null) {
    val cellCount = artifactCount.coerceAtLeast(1)
    val filledCells = progress?.let { (it.coerceIn(0f, 1f) * cellCount).toInt() } ?: cellCount
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (progress != null) "MERGING" else "CLIPS RECOVERED",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.36f),
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$filledCells / $artifactCount",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.defaultMinSize(
                        minWidth = RovaWarningsV3.recoveryNumericChipMinWidth,
                    ),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RovaWarningsV3.recoveryProgressCellGap),
        ) {
            repeat(cellCount) { i ->
                val filled = i < filledCells
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(RovaWarningsV3.recoveryProgressCellHeight)
                        .clip(RoundedCornerShape(RovaWarningsV3.recoveryProgressCellRadius))
                        .background(accent.copy(alpha = if (filled) 0.55f else 0.15f)),
                )
            }
        }
    }
}
```

And update the call site inside `RecoveryCard`:

```kotlin
if (state.survivingArtifacts.isNotEmpty()) {
    Spacer(Modifier.height(14.dp))
    ProgressStrip(
        artifactCount = state.survivingArtifacts.size,
        accent = severityColor,
        progress = state.mergeInProgress,
    )
}
```

- [ ] **Step 4: Update `RecoveryCardList` to pass the new callbacks through**

```kotlin
@Composable
fun RecoveryCardList(
    state: RecoveryUiState,
    onDiscard: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier,
    vendorHelpSlotFor: (sessionId: String) -> (@Composable () -> Unit)? = { null },
    onMerge: ((sessionId: String) -> Unit)? = null,
    onKeepRaw: ((sessionId: String) -> Unit)? = null,
) {
    if (state.cards.isEmpty() && state.hiddenCount == 0) return
    Column(modifier = modifier.fillMaxWidth()) {
        state.cards.forEachIndexed { index, card ->
            if (index > 0) Spacer(Modifier.height(12.dp))
            RecoveryCard(
                state = card,
                onDiscard = { onDiscard(card.sessionId) },
                vendorHelpSlot = vendorHelpSlotFor(card.sessionId),
                onMerge = onMerge?.let { fn -> { fn(card.sessionId) } },
                onKeepRaw = onKeepRaw?.let { fn -> { fn(card.sessionId) } },
            )
        }
        if (state.hiddenCount > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "+${state.hiddenCount} older interrupted sessions",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}
```

- [ ] **Step 5: Compile + test**

Run: `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt
git commit -m "feat(ui): RecoveryCard 3-CTA stack + progress-aware ProgressStrip"
```

---

### Task 12: Extend `RecoveryViewModel` — `merge`, `keepRaw`, signal combine

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryViewModel.kt`
- Test (mod): `app/src/test/java/com/aritr/rova/ui/recovery/RecoveryViewModelTest.kt`

- [ ] **Step 1: Write failing tests**

Append to `RecoveryViewModelTest.kt`:

```kotlin
@Test
fun `keepRaw invokes markKeptRaw seam with sessionId`() = runTest {
    val seen = mutableListOf<String>()
    val vm = RecoveryViewModel(
        recoveryReport = MutableStateFlow(null),
        loadManifest = { null },
        markKeptRaw = { id -> seen += id },
        startRecoveryMergeFn = { },
        mergeOutcome = MutableStateFlow(RecoveryMergeOutcomeSignal.State.Idle),
        ioDispatcher = Dispatchers.Unconfined,
    )
    vm.keepRaw("sess-1")
    assertEquals(listOf("sess-1"), seen)
}

@Test
fun `merge invokes startRecoveryMergeFn with sessionId`() = runTest {
    val seen = mutableListOf<String>()
    val vm = RecoveryViewModel(
        recoveryReport = MutableStateFlow(null),
        loadManifest = { null },
        markKeptRaw = { },
        startRecoveryMergeFn = { id -> seen += id },
        mergeOutcome = MutableStateFlow(RecoveryMergeOutcomeSignal.State.Idle),
        ioDispatcher = Dispatchers.Unconfined,
    )
    vm.merge("sess-2")
    assertEquals(listOf("sess-2"), seen)
}

@Test
fun `InProgress outcome flows into mergeInProgress on the matching card`() = runTest {
    val report = buildReportWithOneCard(sessionId = "sess-3", artifacts = listOf("seg_0.mp4"))   // existing test helper
    val signal = MutableStateFlow<RecoveryMergeOutcomeSignal.State>(
        RecoveryMergeOutcomeSignal.State.InProgress("sess-3", 0.42f)
    )
    val vm = RecoveryViewModel(
        recoveryReport = MutableStateFlow(report),
        loadManifest = { null },
        markKeptRaw = { },
        startRecoveryMergeFn = { },
        mergeOutcome = signal,
        ioDispatcher = Dispatchers.Unconfined,
    )
    val card = vm.uiState.value.cards.single()
    assertEquals(0.42f, card.mergeInProgress)
}

@Test
fun `InProgress for a different session does not affect this card`() = runTest {
    val report = buildReportWithOneCard(sessionId = "sess-A", artifacts = listOf("seg_0.mp4"))
    val signal = MutableStateFlow<RecoveryMergeOutcomeSignal.State>(
        RecoveryMergeOutcomeSignal.State.InProgress("sess-OTHER", 0.7f)
    )
    val vm = RecoveryViewModel(
        recoveryReport = MutableStateFlow(report),
        loadManifest = { null },
        markKeptRaw = { },
        startRecoveryMergeFn = { },
        mergeOutcome = signal,
        ioDispatcher = Dispatchers.Unconfined,
    )
    assertNull(vm.uiState.value.cards.single().mergeInProgress)
}

@Test
fun `MuxFailed outcome surfaces mergeFailedReason on the matching card`() = runTest {
    val report = buildReportWithOneCard(sessionId = "sess-fail", artifacts = listOf("seg_0.mp4"))
    val signal = MutableStateFlow<RecoveryMergeOutcomeSignal.State>(
        RecoveryMergeOutcomeSignal.State.Outcome(
            sessionId = "sess-fail",
            outcome = RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.MuxFailed(RuntimeException("encoder")),
        )
    )
    val vm = RecoveryViewModel(
        recoveryReport = MutableStateFlow(report),
        loadManifest = { null },
        markKeptRaw = { },
        startRecoveryMergeFn = { },
        mergeOutcome = signal,
        ioDispatcher = Dispatchers.Unconfined,
    )
    val card = vm.uiState.value.cards.single()
    assertNotNull(card.mergeFailedReason)
}
```

(If `buildReportWithOneCard` doesn't exist as a helper, build a `RecoveryReport` in-line via the existing fixtures — read the test file first.)

- [ ] **Step 2: Run tests — verify they fail**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.recovery.RecoveryViewModelTest"`
Expected: FAIL (constructor param mismatch).

- [ ] **Step 3: Edit `RecoveryViewModel`**

```kotlin
class RecoveryViewModel(
    recoveryReport: StateFlow<RecoveryReport?>,
    loadManifest: (String) -> SessionManifest?,
    private val discardSession: suspend (String) -> Unit = {},
    /** Phase 4.3 — writes Terminated.MULTI_SEGMENT_KEPT for the Keep-as-raw + Save-segments-only flows. */
    private val markKeptRaw: suspend (String) -> Unit = {},
    /** Phase 4.3 — fires `RovaRecordingService.startRecoveryMerge(context, sid)`. NOT suspended; the service is the host. */
    private val startRecoveryMergeFn: (String) -> Unit = {},
    /** Phase 4.3 — push signal from the recovery merge lifecycle. */
    private val mergeOutcome: StateFlow<RecoveryMergeOutcomeSignal.State> =
        MutableStateFlow(RecoveryMergeOutcomeSignal.State.Idle),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val dismissedIds = MutableStateFlow<Set<String>>(emptySet())

    val uiState: StateFlow<RecoveryUiState> =
        combine(recoveryReport, dismissedIds, mergeOutcome) { report, dismissed, merge ->
            val base = RecoveryViewSource.buildUiState(report, dismissed, loadManifest)
            applyMergeOutcome(base, merge)
        }.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = RecoveryUiState.Empty,
        )

    private fun applyMergeOutcome(
        base: RecoveryUiState,
        merge: RecoveryMergeOutcomeSignal.State,
    ): RecoveryUiState = when (merge) {
        RecoveryMergeOutcomeSignal.State.Idle -> base
        is RecoveryMergeOutcomeSignal.State.InProgress -> base.copy(
            cards = base.cards.map { c ->
                if (c.sessionId == merge.sessionId) c.copy(mergeInProgress = merge.progress) else c
            },
        )
        is RecoveryMergeOutcomeSignal.State.Outcome -> base.copy(
            cards = base.cards.map { c ->
                if (c.sessionId != merge.sessionId) c
                else when (val o = merge.outcome) {
                    is RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.MuxFailed ->
                        c.copy(mergeInProgress = null, mergeFailedReason = o.cause.message ?: "merge failed")
                    is RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage ->
                        c.copy(mergeInProgress = null)   // CANT_MERGE sheet handles the user surface
                    RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.Succeeded,
                    RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.ServiceBusy,
                    RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.UnknownSession ->
                        c.copy(mergeInProgress = null, mergeFailedReason = null)
                }
            },
        )
    }

    fun dismiss(sessionId: String) {
        dismissedIds.value = dismissedIds.value + sessionId
        scope.launch {
            try { discardSession(sessionId) }
            catch (t: Throwable) { RovaLog.w("RecoveryViewModel.dismiss: failed for $sessionId", t) }
        }
    }

    fun merge(sessionId: String) {
        startRecoveryMergeFn(sessionId)
    }

    fun keepRaw(sessionId: String) {
        // Best-effort, same pattern as dismiss; failure leaves the session in OFFER_DISCARD
        // and the user can retry from the card.
        scope.launch {
            try { markKeptRaw(sessionId) }
            catch (t: Throwable) { RovaLog.w("RecoveryViewModel.keepRaw: failed for $sessionId", t) }
        }
    }

    override fun onCleared() { scope.cancel() }
}
```

- [ ] **Step 4: Run tests — expect PASS**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.recovery.RecoveryViewModelTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/recovery/RecoveryViewModel.kt \
        app/src/test/java/com/aritr/rova/ui/recovery/RecoveryViewModelTest.kt
git commit -m "feat(ui): extend RecoveryViewModel with merge/keepRaw seams + signal combine"
```

---

### Task 13: Wire `WarningCenter` C2.4 sheet dispatch — `KEEP_SEGMENTS_ONLY`, `DISCARD_RECOVERY_SESSION`, tertiary render

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetV3.kt` (tertiary rendering)
- Test (mod or new): `app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt`

- [ ] **Step 1: Render the `tertiary` CTA in `WarningSheetV3`**

Grep for the existing sheet body that renders `primary` + `secondary`. Add a third `if (content.tertiary != null) { ... }` block after `secondary`, styled per `content.tertiary.style`. The `Link` style is destructive-red text on a transparent background:

```kotlin
content.tertiary?.let { tertiary ->
    Spacer(Modifier.height(8.dp))
    Text(
        text = tertiary.label,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium,
        color = RovaWarnings.hard,
        textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable { onAction(tertiary.target) },
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
```

(Read the existing sheet structure first to slot this in correctly — the `onAction(target)` callback is the existing dispatch pattern.)

- [ ] **Step 2: Wire `KEEP_SEGMENTS_ONLY` + `DISCARD_RECOVERY_SESSION` dispatch in `WarningCenter.kt`**

Grep for the existing `ActionTarget.DISMISS_AUTOSTOP_ECHO` dispatch in `WarningCenter.kt`. Add new callback params to `WarningCenter`:

```kotlin
@Composable
fun WarningCenter(
    // ... existing params ...
    onKeepRawFromSheet: ((sessionId: String) -> Unit)? = null,
    onDiscardFromSheet: ((sessionId: String) -> Unit)? = null,
    pendingCantMergeSessionId: String? = null,
)
```

In the action dispatch `when (target)` block:

```kotlin
ActionTarget.KEEP_SEGMENTS_ONLY -> {
    pendingCantMergeSessionId?.let { onKeepRawFromSheet?.invoke(it) }
    viewModel.dismissActiveSheet()   // close the sheet — use the existing close-sheet method
}
ActionTarget.DISCARD_RECOVERY_SESSION -> {
    pendingCantMergeSessionId?.let { onDiscardFromSheet?.invoke(it) }
    viewModel.dismissActiveSheet()
}
```

- [ ] **Step 3: Compile + test**

Run: `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: PASS. Add a `WarningCenterAggregateTest` case if missing — at minimum verify the two new `ActionTarget` enum values exist (they do from Task 3) and that the precedence test from Task 9 still passes.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/warnings/WarningCenter.kt \
        app/src/main/java/com/aritr/rova/ui/warnings/WarningSheetV3.kt \
        app/src/test/java/com/aritr/rova/ui/warnings/WarningCenterAggregateTest.kt
git commit -m "feat(ui): wire C2.4 sheet dispatch — tertiary link + keep-raw/discard targets"
```

---

### Task 14: Wire production — `HistoryScreen` (RecoveryViewModel factory + RecoveryCardList CTAs) + `RecordScreen` (WarningCenter callbacks)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` (or whichever screen hosts `WarningCenter`)
- Modify: `app/src/main/java/com/aritr/rova/RovaApp.kt` (if a `buildRecoveryViewModel` helper exists; otherwise inline at the HistoryScreen factory)

- [ ] **Step 1: Extend the `RecoveryViewModel` factory in `HistoryScreen.kt`**

Edit `HistoryScreen.kt` around lines 125-140 (the existing factory block):

```kotlin
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
```

- [ ] **Step 2: Wire `RecoveryCardList` callbacks**

Find the `RecoveryCardList(...)` call in `HistoryScreen.kt` and add the new callbacks:

```kotlin
val onMerge: (String) -> Unit = { sessionId -> recoveryViewModel.merge(sessionId) }
val onKeepRaw: (String) -> Unit = { sessionId -> recoveryViewModel.keepRaw(sessionId) }

RecoveryCardList(
    state = recoveryUiState,
    onDiscard = onRecoveryDiscard,
    vendorHelpSlotFor = vendorHelpSlotFor,
    onMerge = onMerge,
    onKeepRaw = onKeepRaw,
)
```

- [ ] **Step 3: Wire C2.4 callbacks on `WarningCenter` in `RecordScreen.kt`**

Grep for the `WarningCenter(` call in `RecordScreen.kt`. Add the new callbacks. The host needs access to the same `RecoveryViewModel` instance — but `RecoveryViewModel` is currently scoped to `HistoryScreen`. **Resolution:** pull the recovery merge actions into `WarningCenterViewModel` OR host `RecoveryViewModel` at a higher level (e.g., on `MainActivity` or a `ViewModelStoreOwner` that both screens share).

**Simplest path** — host the C2.4 dispatch directly via `app.sessionStore` + service factory in `RecordScreen.kt`, without going through `RecoveryViewModel`:

```kotlin
val pendingSid by warningCenterViewModel.pendingCantMergeSessionId.collectAsStateWithLifecycle()

WarningCenter(
    // ... existing params ...
    onKeepRawFromSheet = { sessionId ->
        coroutineScope.launch {
            try {
                app.sessionStore.markTerminated(
                    sessionId = sessionId,
                    terminated = Terminated.MULTI_SEGMENT_KEPT,
                    stopReason = StopReason.NONE,
                )
                app.recoveryMergeOutcomeSignal.acknowledge(sessionId)
            } catch (t: Throwable) {
                RovaLog.w("C2.4 keepRaw failed for $sessionId", t)
            }
        }
    },
    onDiscardFromSheet = { sessionId ->
        coroutineScope.launch {
            try {
                app.sessionStore.discardSession(sessionId)
                app.recoveryMergeOutcomeSignal.acknowledge(sessionId)
            } catch (t: Throwable) {
                RovaLog.w("C2.4 discard failed for $sessionId", t)
            }
        }
    },
    pendingCantMergeSessionId = pendingSid,
)
```

(`coroutineScope` should already exist in `RecordScreen` from prior slices — verify by reading the file.)

- [ ] **Step 4: Full compile + test**

Run: `./gradlew.bat :app:compileDebugKotlin :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt \
        app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(ui): wire HistoryScreen recovery merge CTAs + RecordScreen C2.4 dispatch"
```

---

### Task 15: Correct `WarningCenterContract.md` §C2.4 and §C4.4

**Files:**
- Modify: `docs/WarningCenterContract.md`

- [ ] **Step 1: Read the current §C2.4 row**

Open the file and locate §4.2 C2.4 — note the current "Producer source" text that names `RovaController.recoverAndMerge`.

- [ ] **Step 2: Edit §C2.4**

Replace the row content per spec §5. The corrected text:

> **C2.4 "Can't merge yet" (3-way sheet)**
>
> Surface: `WarningSheetV3` with severity ADVISORY; tertiary destructive-link CTA. Producer: `RovaRecordingService.startRecoveryMerge(context, sessionId)` → `ExportPipeline.exportRecovered(...)` → eager pre-flight; on `ExportResult.InsufficientStorage`, `RecoveryMerger` emits `RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.InsufficientStorage` carrying `(requiredBytes, availableBytes)`. `WarningCenterViewModel` lifts the signal into `WarningId.CANT_MERGE` and exposes `pendingCantMergeSessionId`. The sheet auto-presents on next Idle; CTAs dispatch via `STORAGE_SETTINGS` (intent), `KEEP_SEGMENTS_ONLY` (VM-only), and `DISCARD_RECOVERY_SESSION` (VM-only). Phase 4.3 (this slice, ADR-0017).

(Use the exact column/row format the contract already uses — match the style of nearby rows.)

- [ ] **Step 3: Add or amend §C4.4 if relevant — Merge-failed RecoveryCard variant**

Note in §4.4 C4.4 that the Merge-failed variant is implemented via the transient `RecoveryCardState.mergeFailedReason: String?` field — no new `RecoveryCardKind` — and that the underlying signal is `RecoveryMergeOutcomeSignal.RecoveryMergeOutcome.MuxFailed`. Phase 4.3.

- [ ] **Step 4: Commit**

```bash
git add docs/WarningCenterContract.md
git commit -m "chore(docs): correct WarningCenterContract.md §C2.4 + §C4.4 for Phase 4.3"
```

---

### Task 16: Add ADR-0017 — Recovery merge architecture

**Files:**
- Create: `docs/adr/0017-recovery-merge-architecture.md`

- [ ] **Step 1: Write the ADR**

Use the existing ADR template (read `docs/adr/0016-thermal-autostop.md` for format reference). Content:

```markdown
# ADR-0017: Recovery merge architecture — service-hosted, ExportPipeline-routed, signal-driven outcomes

Date: 2026-05-25
Status: Accepted
Supersedes: none
Related: ADR-0003 (Tier1 FD mode), ADR-0006 (atomic terminal-write), ADR-0007 (record warning sheets), ADR-0013 (warning re-skin v3 chrome canon), ADR-0015 (storage-full echo), ADR-0016 (thermal autostop)

## Context

Recovery sessions (`Terminated ∈ {USER_STOPPED, KILLED_BY_SYSTEM, KILLED_FORCE_STOP}` AND `ExportState != FINALIZED`) accumulate unmerged segments in their session directory. Beta shipped a Discard-only path; users with valid segments had no way to recover them into a watchable artifact. Phase 4.3 adds a Merge action.

Three coupled architectural questions:

1. **Where does the recovery merge run?** Options: `WorkManager` (durability), a new `RecoveryMergeController` class with its own scope (separation), or `RovaRecordingService` via a new action (lifecycle reuse).
2. **How does the merge call the mux primitives?** `VideoMerger` is single-entry-locked behind `ExportPipeline` (Gradle check `checkExportPipelineSingleEntry`).
3. **How does pre-flight failure surface to the user?** Must compose with the existing WarningCenter precedence + the v3 sheet chrome.

## Decision

1. **Host = `RovaRecordingService.startRecoveryMerge(context, sessionId)` via `ACTION_RECOVER_MERGE` intent.** Reuses the FGS lifecycle, the notification channel, the battery-optimization wiring, the wakelock policy, and the live-recording mutex (the gate refuses a recovery merge while a recording is active, emitting `RecoveryMergeOutcome.ServiceBusy`).
2. **Merge call = new `ExportPipeline.exportRecovered(...)` entry alongside the existing `export()`.** Runs an eager storage pre-flight (`sum(segment.length()) * 1.05` vs `sessionDir.usableSpace`); on insufficient storage, returns `ExportResult.InsufficientStorage(requiredBytes, availableBytes)` WITHOUT opening a `MediaMuxer`. Otherwise delegates verbatim to `export()` (same tier dispatch, same `VideoMerger` call). Single-entry invariant preserved — recovery still goes through `service/export/`.
3. **Outcome surface = `RecoveryMergeOutcomeSignal` (state flow on `RovaApp`).** States: `Idle | InProgress(sessionId, progress) | Outcome(sessionId, outcome)`. `WarningCenterViewModel` lifts `Outcome.InsufficientStorage` into `WarningId.CANT_MERGE` (ADVISORY, AdvisorySheet); `RecoveryViewModel` lifts `InProgress` into the matching card's `mergeInProgress` and `Outcome.MuxFailed` into the card's `mergeFailedReason`.
4. **New terminal `Terminated.MULTI_SEGMENT_KEPT`** for the user choice "keep recovered segments as N separate files instead of one merged file" (`Keep as raw clips` CTA on the card + `Save segments only` CTA on the C2.4 sheet). Recovery mapper hides cards in this state; Library row enumeration (one row per segment) is a future slice.

## Rejected alternatives

- **`WorkManager`** — durable, but: (a) the FGS notification + wakelock policy + battery-opt prompt are already on the service, (b) `WorkManager`'s constraint system adds latency the user can see, (c) duplicating "are we already recording?" mutex into WorkManager is more code than the start-with-action path.
- **`RecoveryMergeController` class with its own coroutine scope** — works, but the service already owns the long-running compute lifecycle on this device. Adding a second one duplicates the notification / wakelock / battery-opt wiring without gain.
- **Caller-scope coroutine (off `RecoveryViewModel`)** — fails the FGS requirement: a long mux call from a backgrounded `ViewModel` scope can be process-killed mid-merge with no notification. Service-hosted gets the FGS for free.
- **`ExportState` flag instead of a `Terminated` value** — `ExportState` is owned by the export-pipeline contract (NOT_STARTED / MUXING / COPYING / FINALIZED / FAILED). "User explicitly chose to keep raw" is a session terminal, not an export step. Belongs on `Terminated`.

## Consequences

- `RovaRecordingService` gains a second responsibility (recovery merge). Mitigation: extract the start-decision into a pure helper (`recoveryMergeStartGate`), JVM-testable; the service-side wiring is a thin shell.
- `ExportPipeline` gains a second entry. The Gradle single-entry check matches `ExportPipeline.export(` literally and is unaffected by `exportRecovered(`.
- `Terminated` enum grows to 5 values; every exhaustive `when` adds one arm (one-line `MULTI_SEGMENT_KEPT -> ...`).
- `WarningId` grows to 20 entries; `WarningIdOrderTest` pins the new ordinal.
- Out-of-scope (parked): Library row enumeration for `MULTI_SEGMENT_KEPT`; retry backoff / auto-retry on transient failures; mid-recording "Can't merge" prevention; merge progress in the FGS notification.
```

- [ ] **Step 2: Commit**

```bash
git add docs/adr/0017-recovery-merge-architecture.md
git commit -m "docs(adr): add ADR-0017 recovery merge architecture"
```

---

### Task 17: Full-suite verification (subagent-driven gradle)

**Files:** none changed

- [ ] **Step 1: Dispatch a verification subagent**

Per standing constraint — long `./gradlew.bat` runs route through a subagent. Dispatch:

> **Description:** "Phase 4.3 full-suite verification"
> **Prompt:** "Run the full Gradle verification chain for the Rova Android project: `./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. Report: (a) pass/fail per task, (b) any new warnings vs the baseline 51W+0H+0E lint state, (c) any new test failures vs the 1140-pass baseline. If anything fails, include the first ~50 lines of the failure log. Do not push or commit. Do not modify any source files."

- [ ] **Step 2: Read subagent report**

If anything regressed, return to the relevant Task and fix; rerun this task.

- [ ] **Step 3: Final summary commit (optional)**

If lint warnings need suppressing or trivial follow-ups surfaced, fold them into one cleanup commit. Otherwise, skip — the per-task commits are the deliverable.

- [ ] **Step 4: Hand back to owner for `git push` + `gh pr create` consent**

Per standing constraint — push + PR are owner-driven. Stop here. Report to owner:
- Number of commits made
- Verification result (pass / fail per task)
- ADR-0017 added
- Memory update recommended (`project_current_state.md` parked-backlog "Phase 4.3" entry — owner-only edit)

---

## Self-Review

**Spec coverage:**

| Spec section | Task(s) | Coverage |
|---|---|---|
| §4.1 `Terminated.MULTI_SEGMENT_KEPT` | Task 1 | ✓ enum + mapper hide rule + when-arms |
| §4.2 `WarningId.CANT_MERGE` | Tasks 2, 3 | ✓ enum row #14 + ordinal pin + sheet content + AdvisorySheet routing + `tertiary` field + `WarningActionStyle` |
| §4.3 New `ActionTarget`s | Task 3 | ✓ `KEEP_SEGMENTS_ONLY` + `DISCARD_RECOVERY_SESSION` enum values added |
| §4.4 `ExportPipeline.exportRecovered` + `ExportResult.InsufficientStorage` | Tasks 4, 5 | ✓ entry function with pre-flight + new sealed variant + pure test seam |
| §4.5 `RovaRecordingService.startRecoveryMerge` | Task 8 | ✓ companion factory + ACTION_RECOVER_MERGE branch + start-gate helper |
| §4.6 `RecoveryMergeOutcomeSignal` | Task 6 | ✓ new file + state hierarchy + `acknowledge` |
| §4.6 `RecoveryMerger` | Task 7 | ✓ wraps exportRecovered + outcome translation + progress emit |
| §4.7 `RecoveryCard` signature extension | Task 11 | ✓ `onMerge` / `onKeepRaw` / progress-aware ProgressStrip + `CtaRow` |
| §4.8 `RecoveryCardState` extension | Task 10 | ✓ four new fields + mapper populates label fields |
| §4.9 `RecoveryViewModel` extension | Task 12 | ✓ renamed seams `markKeptRaw` + `startRecoveryMergeFn` + signal combine + `merge`/`keepRaw` methods |
| §4.10 WarningCenter C2.4 sheet wiring + sessionId plumbing | Tasks 9, 13, 14 | ✓ `pendingCantMergeSessionId` flow + sheet dispatch + tertiary render + production callbacks |
| §4.11 Library row enumeration — out of scope | — | explicitly parked in plan body |
| §5 Contract correction | Task 15 | ✓ §C2.4 + §C4.4 rewrites |
| §6 Failure matrix | Tasks 7, 11, 12 | ✓ MuxFailed → mergeFailedReason; InsufficientStorage → CANT_MERGE; ServiceBusy → service-side reject; kept-raw → terminal write; process-kill → existing recovery cold-launch path (no change needed) |
| §7 UI fidelity callouts — destructive-link style, ProgressStrip reuse | Tasks 11, 13 | ✓ `WarningActionStyle.Link` rendering + `ProgressStrip(progress)` overload |
| §8 Data shape changes — full list | Tasks 1–13 | ✓ every file in the table is touched by at least one task |
| §9 ADR-0017 | Task 16 | ✓ created |
| §10 Test policy ~15-20 JVM tests | Tasks 1, 2, 3, 5, 6, 7, 8, 9, 10, 12 | ✓ ~20 new/modified test cases across the cited files |
| §11 Open items / future | — | parked in plan body |
| §12 Memory update | Task 17 final report | ✓ flagged as owner-only follow-up |

**Placeholder scan:** no "TBD", no "implement later", no "similar to Task N" without code — every step has the actual code.

**Type consistency check:**

- `markKeptRaw` and `startRecoveryMergeFn` (the renamed constructor seams) — same names used in Task 12 (definition), Task 14 (HistoryScreen wiring). ✓
- `RecoveryMergeOutcomeSignal.State` and `RecoveryMergeOutcomeSignal.RecoveryMergeOutcome` — fully-qualified names used consistently across Tasks 6, 7, 8, 9, 12. ✓
- `pendingCantMergeSessionId` — defined Task 9, consumed Task 13/14. ✓
- `WarningActionStyle.Link` — defined Task 3, rendered Task 13. ✓
- `Terminated.MULTI_SEGMENT_KEPT` — defined Task 1, written Task 14 (HistoryScreen `markKeptRaw` lambda) + Task 14 (RecordScreen C2.4 dispatch). ✓
- `ExportResult.InsufficientStorage(requiredBytes, availableBytes)` — defined Task 4, mapped Task 7. ✓
- `tertiary: WarningAction?` field — defined Task 3, populated for CANT_MERGE Task 3, rendered Task 13. ✓
- `onMerge` / `onKeepRaw` callback signatures — `(() -> Unit)?` on `RecoveryCard` (Task 11), `((sessionId: String) -> Unit)?` on `RecoveryCardList` (Task 11), wired Task 14. ✓
- `acknowledge(sessionId)` on `RecoveryMergeOutcomeSignal` — defined Task 6, called Task 14 (RecordScreen C2.4 dispatch after a kept-raw or discard succeeds, to reset state to Idle). ✓

**Decomposition decisions locked in:**
- Each task = one commit = one focused responsibility (matches Slice 1-3 PR pattern).
- Compile-gate splits: Task 2 introduces placeholder `CANT_MERGE` arms so the project keeps compiling after the enum row insert; Task 3 swaps the placeholders for the final sheet body. Same RED→GREEN split as ADR-0011 Slice B.
- Pure-helper extraction: `recoveryMergeStartGate` (Task 8) is JVM-testable; matches the `SegmentGateThermal` precedent from Slice 3.

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-25-phase-4-3-recovery-merge.md`. Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Matches the established Phase 4 Slice 1-3 execution pattern.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints.

**Which approach?**
