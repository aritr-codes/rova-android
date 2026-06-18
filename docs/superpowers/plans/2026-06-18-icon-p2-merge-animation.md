# Icon P2 Track A — Branded Merge Animation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the two static merge indicators (Record-home `StatusPill` dot + Recovery card header) with one reusable, reduced-motion-gated, branded animated Merge glyph on the `SemanticIcon` seam.

**Architecture:** A pure `MergeMotion` phase helper (JVM-tested) owns the rotation math; a thin `ProcessingGlyph` composable drives an infinite transition through it and renders `RovaIcons.Merge` via the existing `SemanticIcon` seam locked to `IconStatus.Processing`. Both surfaces call `ProcessingGlyph`. The orphan hardcoded `MergingDotColor` is retired; the `StatusDotColor.MERGING` enum value stays (consumed by the pure `hudStatusPillContent` helper + its test).

**Tech Stack:** Kotlin, Jetpack Compose (animation-core), JUnit4 JVM unit tests, Gradle Kotlin DSL static-check gates.

**Spec:** `docs/superpowers/specs/2026-06-18-icon-p2-merge-animation-design.md`
**Branch:** `feat/icon-p2-merge-animation` (already created off `master 8a75849`).

## Global Constraints

- **JVM unit tests only** (`isReturnDefaultValues = true`); no Robolectric/instrumented. Pure logic goes in a framework-free helper so it is testable.
- **Locked status color:** the glyph tints via `status = IconStatus.Processing → RovaSemantics.escalating` (`#F97316`). Never apply a raw `Color` to the glyph — tint flows only through `SemanticIcon` (`checkSemanticIconNoRawAlpha`).
- **Reduced-motion:** any file using `rememberInfiniteTransition`/`infiniteRepeatable` MUST read `rememberReduceMotion()` in the same file (`checkA11yAnimationGated`), and select a static value when motion is reduced (ADR-0020, SC 2.3.3/2.2.2).
- **No new gate** — coverage is existing `checkA11yAnimationGated` + `checkSemanticIconNoRawAlpha`. Gate count stays **46**.
- **No new user-facing strings** — reuse `R.string.record_hud_status_merging` and `R.string.recovery_progress_header_merging` (already en+es). So `checkNoHardcodedUiStrings` is untouched.
- **Decorative glyph:** `contentDescription = null` on the glyph — both surfaces already carry polite live-region "Merging" text; do not double-announce.
- **`StatusDotColor.MERGING` enum value must NOT be deleted** — `hudStatusPillContent` (RecordChrome.kt:961) sets it and `RecordActiveHudFormattersTest.kt:150` asserts it. Retire only the *color binding* (`MergingDotColor`).
- **Build discipline:** controller runs all gradle; build WARM (no cache wipe). Before any device install, confirm `packageDebug` actually EXECUTED (a timed-out `assembleDebug` can leave a stale APK).
- **Baseline:** master is green (full `testDebugUnitTest` + 46 gates). A new feature lands its tests in the same PR.

## File Structure

| File | Responsibility |
|--|--|
| `app/src/main/java/com/aritr/rova/ui/theme/MergeMotion.kt` | **new** — pure rotation/phase math |
| `app/src/test/java/com/aritr/rova/ui/theme/MergeMotionTest.kt` | **new** — phase-math tests |
| `app/src/main/java/com/aritr/rova/ui/components/ProcessingGlyph.kt` | **new** — animated wrapper composable |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` | modify — StatusPill MERGING → `ProcessingGlyph`; retire `MergingDotColor`; MERGING dot-arm → invariant guard |
| `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt` | modify — `ProgressStrip` header glyph when merging |
| `app/src/test/java/com/aritr/rova/ui/theme/ThemeContrastTest.kt` | modify — escalating ≥3:1 over pinned-dark record substrate |

**Parallelism note:** Task 3 (RecordChrome.kt) and Task 4 (RecoveryCard.kt) touch disjoint files and both depend only on Task 2 — they can be dispatched concurrently. Task 5 (test file) is independent of both.

---

### Task 1: `MergeMotion` pure phase helper

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/theme/MergeMotion.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/MergeMotionTest.kt`

**Interfaces:**
- Produces: `object MergeMotion { const val SPIN_PERIOD_MS: Int = 1400; fun angle(fraction: Float, reduceMotion: Boolean): Float }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/aritr/rova/ui/theme/MergeMotionTest.kt`:

```kotlin
package com.aritr.rova.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeMotionTest {

    @Test
    fun `zero fraction maps to zero degrees`() {
        assertEquals(0f, MergeMotion.angle(0f, reduceMotion = false), 0.001f)
    }

    @Test
    fun `quarter fraction maps to ninety degrees`() {
        assertEquals(90f, MergeMotion.angle(0.25f, reduceMotion = false), 0.001f)
    }

    @Test
    fun `half fraction maps to one hundred eighty degrees`() {
        assertEquals(180f, MergeMotion.angle(0.5f, reduceMotion = false), 0.001f)
    }

    @Test
    fun `near-one fraction approaches three sixty`() {
        assertEquals(359.64f, MergeMotion.angle(0.999f, reduceMotion = false), 0.01f)
    }

    @Test
    fun `fraction of exactly one wraps to zero`() {
        assertEquals(0f, MergeMotion.angle(1f, reduceMotion = false), 0.001f)
    }

    @Test
    fun `fraction above one wraps into range`() {
        assertEquals(90f, MergeMotion.angle(1.25f, reduceMotion = false), 0.001f)
    }

    @Test
    fun `reduced motion holds at zero regardless of fraction`() {
        assertEquals(0f, MergeMotion.angle(0.5f, reduceMotion = true), 0.001f)
        assertEquals(0f, MergeMotion.angle(0.999f, reduceMotion = true), 0.001f)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.MergeMotionTest"`
Expected: FAIL — `MergeMotion` unresolved (compile error).

- [ ] **Step 3: Write the minimal implementation**

`app/src/main/java/com/aritr/rova/ui/theme/MergeMotion.kt`:

```kotlin
package com.aritr.rova.ui.theme

import kotlin.math.floor

/**
 * Pure rotation math for the branded "merge in progress" glyph (ADR-0031 §6/§8,
 * Icon P2 Track A). Framework-free so it is JVM-unit-testable under
 * `isReturnDefaultValues = true` — the house pure-helper seam pattern. The
 * framework-touching part is the thin [com.aritr.rova.ui.components.ProcessingGlyph].
 */
object MergeMotion {

    /** Milliseconds for one full 360° revolution of the merge glyph. */
    const val SPIN_PERIOD_MS: Int = 1400

    /**
     * Rotation in degrees for an animation [fraction] (driven 0f→1f by an infinite
     * transition). [fraction] is wrapped into [0f,1f) before mapping to [0f,360f),
     * so the `1f→0f` restart is seamless (360° ≡ 0°). When [reduceMotion] is true
     * the glyph is held static at 0° (WCAG 2.2 AA SC 2.3.3 / 2.2.2): meaning
     * survives without motion because both host surfaces also show "Merging" text.
     */
    fun angle(fraction: Float, reduceMotion: Boolean): Float {
        if (reduceMotion) return 0f
        val wrapped = fraction - floor(fraction)
        return wrapped * 360f
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.MergeMotionTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/MergeMotion.kt app/src/test/java/com/aritr/rova/ui/theme/MergeMotionTest.kt
git commit -m "feat(icon): MergeMotion pure phase helper for the branded merge glyph"
```

---

### Task 2: `ProcessingGlyph` animated wrapper composable

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/components/ProcessingGlyph.kt`

**Interfaces:**
- Consumes: `MergeMotion.SPIN_PERIOD_MS`, `MergeMotion.angle(...)` (Task 1); existing `SemanticIcon(glyph: RovaGlyph, contentDescription: String?, modifier: Modifier, role: IconRole, status: IconStatus?)` two-layer overload; `RovaIcons.Merge` (a `RovaGlyph`); `IconStatus.Processing`; `rememberReduceMotion()` (in `com.aritr.rova.ui.components.ReducedMotion.kt`).
- Produces: `@Composable fun ProcessingGlyph(modifier: Modifier = Modifier, size: Dp = 18.dp)`

This task has no JVM unit test (it is pure Compose wiring; the only logic — the angle — is tested in Task 1). It is verified by the two static-check gates and a compile. Both reduced-motion and animated paths render the SAME `SemanticIcon`; the `size` param applies `.size(size)` internally so a caller can never produce a zero-size (invisible) glyph.

- [ ] **Step 1: Write the implementation**

`app/src/main/java/com/aritr/rova/ui/components/ProcessingGlyph.kt`:

```kotlin
package com.aritr.rova.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aritr.rova.ui.theme.IconStatus
import com.aritr.rova.ui.theme.MergeMotion
import com.aritr.rova.ui.theme.RovaIcons

/**
 * Branded "merge in progress" indicator (ADR-0031 §6/§8, Icon P2 Track A).
 * Renders [RovaIcons.Merge] through the [SemanticIcon] seam, locked to
 * [IconStatus.Processing] (`RovaSemantics.escalating`), spinning one revolution
 * per [MergeMotion.SPIN_PERIOD_MS]. Drop-in replacement for the two static merge
 * indicators (Record-home StatusPill, recovery card header).
 *
 * Decorative: `contentDescription = null` — both host surfaces already announce
 * "Merging" through their own polite live regions, so a spoken glyph would
 * double-announce.
 *
 * Reduced motion (WCAG 2.2 AA SC 2.3.3 / 2.2.2, ADR-0020): when the OS toggle is
 * on we render the glyph STATIC and never build the infinite transition (no
 * wasted recomposition). The mark still communicates "merging"; only the spin
 * is dropped. The same-file `rememberReduceMotion()` read satisfies
 * `checkA11yAnimationGated`.
 */
@Composable
fun ProcessingGlyph(modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val reduceMotion = rememberReduceMotion()
    val sized = modifier.size(size)
    if (reduceMotion) {
        SemanticIcon(
            glyph = RovaIcons.Merge,
            contentDescription = null,
            status = IconStatus.Processing,
            modifier = sized,
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "mergeSpin")
    val fraction by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = MergeMotion.SPIN_PERIOD_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mergeSpinFraction",
    )
    SemanticIcon(
        glyph = RovaIcons.Merge,
        contentDescription = null,
        status = IconStatus.Processing,
        // Read `fraction` INSIDE the graphicsLayer lambda so rotation updates in the
        // draw phase only — no recomposition at animation-frame cadence (codex).
        modifier = sized.graphicsLayer { rotationZ = MergeMotion.angle(fraction, reduceMotion = false) },
    )
}
```

- [ ] **Step 2: Verify the gates + compile pass**

Run: `./gradlew :app:checkA11yAnimationGated :app:checkSemanticIconNoRawAlpha :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`checkA11yAnimationGated` is satisfied because the file reads `rememberReduceMotion()`; `checkSemanticIconNoRawAlpha` is satisfied because the file applies no raw `Color` tint — tint flows through `SemanticIcon`.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/components/ProcessingGlyph.kt
git commit -m "feat(icon): ProcessingGlyph animated merge indicator on the SemanticIcon seam"
```

---

### Task 3: Wire Record-home `StatusPill` + retire `MergingDotColor`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`
  - Line 99: delete `private val MergingDotColor = …`
  - `StatusPill` body (~line 1203): branch MERGING → `ProcessingGlyph`
  - `StatusDot` color `when` (~line 1042): MERGING arm → invariant guard
- Test (regression, unchanged): `app/src/test/java/com/aritr/rova/ui/screens/RecordActiveHudFormattersTest.kt`

**Interfaces:**
- Consumes: `ProcessingGlyph(modifier, size)` (Task 2); existing `StatusDotColor.MERGING`, `StatusPillContent.dot`.

**Pre-edit audit (already done — codex High precondition):** `MergingDotColor` is referenced ONLY at RecordChrome.kt:1042. `StatusDotColor.MERGING` is referenced at :918 (decl), :962 (`hudStatusPillContent`), :1042 (`StatusDot`), and `RecordActiveHudFormattersTest.kt:150` (assert). Therefore: delete `MergingDotColor`; KEEP the enum value; the `StatusDot` MERGING arm becomes unreachable (StatusPill routes MERGING to `ProcessingGlyph`) and is replaced by an `error(...)` invariant guard.

- [ ] **Step 1: Add the import**

In RecordChrome.kt, beside the existing `import com.aritr.rova.ui.components.SemanticIcon` (line ~82), add:

```kotlin
import com.aritr.rova.ui.components.ProcessingGlyph
```

- [ ] **Step 2: Delete the orphan color**

Remove RecordChrome.kt line 99 entirely:

```kotlin
private val MergingDotColor = Color(0xFF60A5FA)   // blue — no mockup token (mockup has idle/recording/break only)
```

- [ ] **Step 3: Replace the StatusDot MERGING color arm with an invariant guard**

In `StatusDot` (the `val color = when (dot) { … }`, ~line 1039-1043), change the MERGING arm from:

```kotlin
        StatusDotColor.MERGING   -> MergingDotColor
```

to:

```kotlin
        // MERGING never reaches StatusDot — StatusPill routes it to ProcessingGlyph
        // (Icon P2 Track A). The arm stays for exhaustiveness; tripping it is a caller bug.
        StatusDotColor.MERGING   -> error("StatusDot is not rendered for MERGING; StatusPill uses ProcessingGlyph")
```

- [ ] **Step 4: Branch the StatusPill indicator**

In `StatusPill` (~line 1203), change:

```kotlin
                StatusDot(content.dot)
```

to:

```kotlin
                if (content.dot == StatusDotColor.MERGING) {
                    // Icon P2 Track A — branded animated merge glyph replaces the
                    // static dot for the Merging HUD state (ADR-0031 §6/§8).
                    ProcessingGlyph(size = 18.dp)
                } else {
                    StatusDot(content.dot)
                }
```

- [ ] **Step 5: Verify build + gates + the existing HUD-formatter test still pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.screens.RecordActiveHudFormattersTest" :app:checkA11yAnimationGated :app:checkSemanticIconNoRawAlpha :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL; `RecordActiveHudFormattersTest` PASS (the pure `hudStatusPillContent` still emits `StatusDotColor.MERGING`, unchanged). No reference to `MergingDotColor` remains (compile proves it).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(icon): Record-home Merging HUD uses ProcessingGlyph; retire MergingDotColor"
```

---

### Task 4: Wire Recovery card `ProgressStrip` header

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt`
  - `ProgressStrip` header `Row` (~line 352-364): show `ProcessingGlyph` before the "Merging" label when `progress != null`.

**Interfaces:**
- Consumes: `ProcessingGlyph(modifier, size)` (Task 2). `ProgressStrip` already has `progress: Float?` in scope (`progress != null` ⇔ merge active).

- [ ] **Step 1: Add the imports**

In RecoveryCard.kt's import block add (keep alphabetical with neighbors if the file is ordered):

```kotlin
import com.aritr.rova.ui.components.ProcessingGlyph
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
```

(If `Row` / `Arrangement` are already imported, do not duplicate — a duplicate import is a compile error.)

- [ ] **Step 2: Wrap the header label with the glyph**

In `ProgressStrip`, the header row currently renders the label `Text(...)` directly as the first child of the `SpaceBetween` `Row` (~line 357). Replace that first `Text(text = headerLabel, …)` child with a left-aligned inner `Row` that prepends the glyph when merging:

```kotlin
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (progress != null) {
                    // Icon P2 Track A — branded animated merge glyph beside the
                    // "Merging" header while a recovery merge runs (ADR-0031 §6/§8).
                    // Decorative; the strip's polite live region announces progress.
                    ProcessingGlyph(size = 16.dp)
                }
                Text(
                    text = headerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    // WCAG 2.2 AA (ADR-0020, audit RECOV-06): 0.36α was ~3:1 over the
                    // elevated card — below the 4.5:1 SC 1.4.3 bar. 0.70α ≈ 6.83:1.
                    // See ContrastMathTest.
                    color = Color.White.copy(alpha = 0.70f),
                )
            }
```

(The outer `SpaceBetween` `Row` and the trailing count-chip `Box` are unchanged — the inner `Row` simply replaces the single label `Text` as the left child.)

- [ ] **Step 3: Verify build + gates pass**

Run: `./gradlew :app:checkA11yAnimationGated :app:checkSemanticIconNoRawAlpha :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt
git commit -m "feat(icon): recovery card Merging header shows ProcessingGlyph"
```

---

### Task 5: Contrast assertion — escalating ≥3:1 over the pinned-dark record substrate

**Files:**
- Modify: `app/src/test/java/com/aritr/rova/ui/theme/ThemeContrastTest.kt`

**Interfaces:**
- Consumes: `RovaSemantics.escalating`, `NeutralDarkRecordPalette.surfaceBase`, `RecordChromeTokens.glassFill`, `ContrastMath.compositeAlphaOver`, the test's own `rgb(...)` / `ratioOver(...)` helpers.

**Rationale:** Both merge surfaces render on the pinned-dark record route (neutral-dark regardless of the selected theme — pinned routes ignore the palette), so the substrate is palette-independent. The glyph uses the locked `IconStatus.Processing → RovaSemantics.escalating`. Assert it clears the SC 1.4.11 non-text 3:1 bar over the glass chip composited on the neutral-dark record background. Arbitrary brighter live-video frames are the inherited over-media caveat the glass chip addresses (ADR-0031 §6) — not introduced or regressed by this slice.

- [ ] **Step 1: Add the test method**

Add this `@Test` inside `class ThemeContrastTest` (it reuses the existing `rgb` and `ratioOver` private helpers in that file):

```kotlin
    @Test
    fun `processing status color clears 3 to 1 over the pinned-dark record glass`() {
        // Both merge surfaces (Record-home StatusPill, recovery card) render on the
        // pinned-dark record route — neutral-dark regardless of theme. The branded
        // merge glyph uses the locked IconStatus.Processing -> RovaSemantics.escalating.
        // Substrate = the StatusPill glass fill (Black @ 40%) composited over the
        // neutral-dark record background. (Brighter video frames are the inherited
        // over-media caveat the glass chip handles per ADR-0031 §6 — not this slice.)
        val recordBg = NeutralDarkRecordPalette.surfaceBase
        val glassAlpha = RecordChromeTokens.glassFill.alpha.toDouble()  // Black @ 0.40
        val (br, bg, bb) = rgb(recordBg)
        val substrate = ContrastMath.compositeAlphaOver(0, 0, 0, glassAlpha, br, bg, bb)
        val ratio = ratioOver(RovaSemantics.escalating, substrate)
        assertTrue(
            "escalating ${"%.2f".format(ratio)}:1 < 3.0 over pinned-dark record glass",
            ratio >= 3.0,
        )
    }
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.ThemeContrastTest"`
Expected: PASS — escalating (#F97316) over the near-black glass substrate is ≈7:1, well above 3:1. (If this ever FAILS, do NOT retint — escalating is locked; surface it to the owner per the spec.)

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/aritr/rova/ui/theme/ThemeContrastTest.kt
git commit -m "test(icon): assert Processing status color clears 3:1 over pinned-dark record glass"
```

---

### Task 6: Full verification sweep

**Files:** none (verification only).

- [ ] **Step 1: Full JVM suite + all gates + APK**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL; full unit suite green (baseline + 7 new `MergeMotionTest` + 1 new `ThemeContrastTest` method); all 46 `check*` gates green via `preBuild`. **Confirm `packageDebug` EXECUTED** in the task list (not just `assembleDebug` up-to-date) before treating the APK as installable — a timed-out build can leave a stale APK.

- [ ] **Step 2: Owner device smoke (manual, RZCYA1VBQ2H)**

Confirm on device:
1. Record a periodic session; on the merge boundary the Record-home HUD status pill shows the **spinning orange Merge glyph** (not the old blue dot) beside "Merging NN%".
2. The blue→orange shift reads as intended (not "warning"). If not, that's a `SemanticIconSpec` mapping discussion, out of this slice.
3. With OS "Remove animations" ON, the glyph is **static but visible** (no spin), still beside "Merging".
4. Trigger a recovery merge (interrupted session): the recovery card "Merging" header shows the same glyph; the determinate cell-fill strip still advances.

---

## Self-Review

**1. Spec coverage:**
- §3.1 `MergeMotion` → Task 1. ✅
- §3.2 `ProcessingGlyph` (reduced-motion branch, no transition when reduced, `floor` Float, decorative) → Task 2. ✅
- §3.3 RecordChrome wiring + `MergingDotColor` retire + usage-audit precondition → Task 3. ✅
- §3.3 RecoveryCard wiring → Task 4. ✅
- §4 locked escalating color + §contrast assertion → Task 3/4 (status=Processing) + Task 5. ✅
- §5 a11y (decorative, reduced-motion static, stall/error owned elsewhere) → Tasks 2/3/4 (cd=null, branch) — stall/error path untouched by design. ✅
- §6 no new gate → confirmed (Tasks 2/3/4 verify the two existing gates; nothing added). ✅
- §7 tests (`MergeMotionTest` set + contrast) → Tasks 1, 5. ✅
- §8 rejected (glass-chip, arc, inline, secondary glyphs) → not built. ✅

**2. Placeholder scan:** none — every step has exact paths, full code, exact commands and expected output.

**3. Type consistency:** `MergeMotion.angle(fraction: Float, reduceMotion: Boolean): Float` and `SPIN_PERIOD_MS: Int` are used identically in Task 1 (def), Task 2 (`tween(durationMillis = MergeMotion.SPIN_PERIOD_MS …)`, `MergeMotion.angle(fraction, reduceMotion = false)`), and the tests. `ProcessingGlyph(modifier: Modifier, size: Dp)` is defined in Task 2 and called as `ProcessingGlyph(size = 18.dp)` / `ProcessingGlyph(size = 16.dp)` in Tasks 3/4. `StatusDotColor.MERGING` stays an enum value throughout. ✅
