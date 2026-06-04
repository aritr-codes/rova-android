# checkA11yAnimationGated — Reduced-Motion Static Gate Design

- **Date:** 2026-06-03
- **Status:** Approved (owner sign-off 2026-06-03)
- **ADR:** amends ADR-0020 (WCAG 2.2 AA by Default) — promotes the `checkA11yAnimationGated` STUB to a built gate
- **Scope:** the FIRST `checkA11y*` static-check gate. The other three sketched in ADR-0020 (`checkA11yNoLowAlphaTextToken`, `checkA11yClickableHasRole`, `checkA11yTargetSizeToken`) stay STUB — out of scope here.

## Goal

A Gradle static-check task that fails the build when a Compose looping/auto-playing animation is used on a UI surface without a reduced-motion guard in the same file, enforcing ADR-0020 §Decision-3 (WCAG 2.2 AA SC 2.3.3 "Animation from Interactions" / SC 2.2.2 "Pause, Stop, Hide") by construction for all future UI.

## Context

ADR-0020 established "WCAG 2.2 AA by default" and sketched four `checkA11y*` gates as design-only, explicitly deferring their construction until the source was remediated (landing a gate against red source would wire `preBuild` to a permanently-failing task). The accessibility remediation stack landed on master (`892d551`), introducing the reduced-motion seam `ReducedMotion` / `rememberReduceMotion()` and routing every looping animation through it. The source is now GREEN, so the first gate can be built without poisoning `preBuild`.

The reduced-motion seam lives at `app/src/main/java/com/aritr/rova/ui/components/ReducedMotion.kt`:

```kotlin
object ReducedMotion {
    fun isReduced(transitionScale: Float, animatorScale: Float): Boolean =
        transitionScale == 0f || animatorScale == 0f
    fun isReduced(context: Context): Boolean { /* reads Settings.Global scales */ }
}
@Composable fun rememberReduceMotion(): Boolean { /* thin Compose seam */ }
```

### Current animation inventory (master) — gate must land GREEN

Five files use looping-animation primitives; **all are already gated**:

| File | Raw primitive in-file? | Seam read in-file? | Gating idiom |
|------|------------------------|--------------------|--------------|
| `ui/components/RovaAnimations.kt` | yes (`rememberInfiniteTransition`, `infiniteRepeatable`) | yes (`rememberReduceMotion()` early-return) | helper self-gates |
| `ui/recovery/RecoveryCard.kt` | yes | yes (`rememberReduceMotion()`) | call-site conditional value |
| `ui/screens/RecordChrome.kt` | yes | yes (`rememberReduceMotion()`) | call-site conditional value |
| `ui/warnings/WarningSnoozeChip.kt` | yes | yes (`rememberReduceMotion()`) | call-site conditional create |
| `ui/components/BackgroundRecordingBanner.kt` | **no** (calls `RovaAnimations.pulsingOpacity()`) | n/a | delegates to self-gated helper |

`BackgroundRecordingBanner` has no raw primitive, so it is never triggered — correctly, because its gating is delegated. `RovaAnimations.kt` carries both the raw primitive and the seam read, so it passes the co-presence rule with no special allowlist.

## Decision — gate semantics

**Granularity: file-level co-presence** (matches the nearest sibling gate `checkNoHardcodedUiStrings`; the dominant precedent across the existing gates is single-file/line regex scans that accept a bounded blind spot).

Algorithm, per `*.kt` file under `src/main/java/com/aritr/rova`:

1. **Trigger detection.** Collect lines matching the raw-primitive regex:
   - `rememberInfiniteTransition(`
   - `infiniteRepeatable(`

   Excluded from triggering: lines whose trimmed start is `//` or `*` (comments/KDoc), and any line containing the literal `a11y-opt-out`.
2. **Seam detection.** The file "has a seam read" if any line contains `rememberReduceMotion(` or `ReducedMotion.isReduced`.
3. **Offense.** A file with ≥1 triggering line and NO seam read is an offender. Report every triggering line as `file:line`.
4. If any offenders exist, throw `GradleException` citing ADR-0020 §Decision-3 (SC 2.3.3 / 2.2.2), listing offenders, and spelling the `// a11y-opt-out: <reason>` hatch.

### Trigger set rationale

Only **raw** primitives (`rememberInfiniteTransition`, `infiniteRepeatable`) trigger. The seam helpers `pulsingOpacity` / `pulsingBorder` are deliberately NOT triggers: they self-gate internally, so flagging their call sites would false-positive on correctly-delegated callers like `BackgroundRecordingBanner`. `infiniteRepeatable` is included alongside `rememberInfiniteTransition` as belt-and-suspenders (they normally co-occur; either alone still triggers).

### Opt-out hatch

A triggering line bearing `// a11y-opt-out: <reason>` is skipped (does not trigger the seam requirement). This is the sanctioned escape for a genuinely static or `@Preview`-only animation; the convention is to spell the reason, mirroring the established `// i18n-opt-out: <reason>` hatch in `checkNoHardcodedUiStrings`. No `@Preview` composables currently use these primitives, so no opt-out is needed at landing.

### Accepted blind spot

File-level co-presence passes a file that has a *gated* animation in composable A and an *ungated* animation in composable B. This is the same class of bounded blind spot the project already accepts in `checkNoHardcodedUiStrings` (single-line scope, continuation-line blindness). The centralized seam helper makes per-file co-presence a strong signal in practice. Tightening to per-composable proximity was considered and rejected: brittle window-tuning, and it misfires on helper-extracted gating (`BackgroundRecordingBanner`).

## Architecture / files

| File | Change |
|------|--------|
| `app/build.gradle.kts` | Register `val checkA11yAnimationGated = tasks.register("checkA11yAnimationGated") { ... }` with a KDoc block citing ADR-0020 §Decision-3, following the `checkNoHardcodedUiStrings` structure; add one `dependsOn(checkA11yAnimationGated)` line inside the existing `afterEvaluate { tasks.matching { it.name == "preBuild" } ... }` block. |
| `docs/adr/0020-wcag-2.2-aa-by-default.md` | Promote `checkA11yAnimationGated` from the "STUB — future" section to a built gate: add a Decision/Consequences note stamping owner sign-off 2026-06-03 and stating it is now wired into `preBuild`. The other three `checkA11y*` remain in the STUB section. |
| `CLAUDE.md` | Add `checkA11yAnimationGated` to the check* registry list and correct the count (the doc says "25"; `preBuild` already wires the two i18n gates `checkNoHardcodedUiStrings` + `checkLocaleConfigNoPseudolocale`; the new gate makes the accurate total **28**). |
| `CHANGELOG.md` | One line under `Added` (Keep a Changelog 1.1.0 format): the new reduced-motion static gate. |

No production Kotlin changes. No JVM unit test (build logic; consistent with all existing gates — none are unit-tested, and extracting the scan to `buildSrc` for a single gate is unjustified over-engineering).

## Error message (exact shape)

```
ADR-0020 §Decision-3 violation (WCAG 2.2 AA — SC 2.3.3 Animation from
Interactions / SC 2.2.2 Pause, Stop, Hide): looping/auto-playing animation
primitive(s) used without a reduced-motion guard in the same file. Every file
that uses `rememberInfiniteTransition` / `infiniteRepeatable` must also read
the reduced-motion seam (`rememberReduceMotion()` or `ReducedMotion.isReduced`)
and select a static value when motion is reduced. For a genuinely static or
@Preview-only animation, add `// a11y-opt-out: <reason>` on the primitive line.
Offenders:
  <file>:<line>: <trimmed line>
```

## Testing / verification

This is Gradle build logic, verified by running the task (the project's standard for all 27 existing `check*` gates — none carry JVM unit tests):

1. **GREEN proof:** `./gradlew :app:checkA11yAnimationGated` passes on unmodified master source.
2. **RED proof:** transiently add an ungated `val t = rememberInfiniteTransition(label = "scratch")` to a scratch composable in a file lacking a seam read; run the task; confirm it throws and names that file:line; revert.
3. **Full gate:** `./gradlew :app:lintDebug` (runs the new gate via `preBuild`) and `./gradlew :app:testDebugUnitTest` (baseline 1394 / 0-0-0) both succeed.

## Out of scope

- The other three `checkA11y*` gates (low-alpha token, clickable-role, target-size) — remain ADR-0020 STUB.
- Any source remediation — the source is already GREEN; this gate only freezes the invariant.
- Per-composable / AST-level proximity analysis — rejected above in favor of file-level co-presence.
