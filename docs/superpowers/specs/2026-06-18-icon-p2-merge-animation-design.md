# Icon P2 — Branded Merge Animation (Track A) — Design Spec

**Date:** 2026-06-18
**Status:** Approved (owner-ratified in brainstorming 2026-06-18)
**ADR:** Refines ADR-0031 §6/§8 (P2 — "animated states"). Reuses ADR-0020 (WCAG 2.2 AA reduced-motion), ADR-0028 theme engine (palette-driven `LocalGlassEnvironment`), the `SemanticIcon` seam (ADR-0031 §4), and the locked `RovaSemantics` status channel (ADR-0031 §3).
**Branch:** `feat/icon-p2-merge-animation` (off `master 8a75849`).

---

## 1. Context

ADR-0031 §8 defers "animated states" to P2. §6 names the active/processing states that should animate. The icon system (P0 + P1a slices 1–3) and the theme engine (PR #120, merged `8a75849`) are now both live, so the seam this rides on exists.

P2 as written bundles four independent chunks — animated states, glass-chip active-nav containers, secondary-concept glyphs, and an onboarding-illustration refresh. A file-overlap audit (2026-06-18) showed only **one conflict-free parallel split**:

- **Track A (this spec) — icon seam:** animated merge states. Touches the shared icon-seam files (`SemanticIcon.kt`, `RovaGlyphs`/`RovaIcons`, `RecordChrome.kt`, `RecoveryCard.kt`).
- **Track B (separate spec/cycle) — onboarding:** `OnboardingIllustrations.kt` is Canvas-based, self-contained, imports zero icon-seam files → fully disjoint, own branch/PR, runs concurrently.

**Glass-chip active-nav (chunk #3) is rejected** (see §8): the app has no persistent selected-tab `NavigationBar` (each screen renders its own button nav), and the mode/topology pickers already accent-fill on selection. Building a selected-tab chip would be a pattern with no consumer (YAGNI).

**Owner scope decisions (brainstorming):**
- Animate **merge surfaces only** — the two places a merge actually runs.
- Approach: **indeterminate spin via one shared primitive** on the `SemanticIcon` seam (not a determinate arc, not per-surface inline).
- Secondary glyphs: **consumer-driven** — author one only if a call-site needs it. `RovaIcons.Merge` already exists, so this slice authors **zero** new glyphs.

This is presentation-only. No manifest, no schema, no behavior/reliability change.

## 2. The two merge surfaces (current state)

1. **Record-home active HUD — `StatusPill`** (`RecordChrome.kt`). The `Merging` HUD case renders `StatusDot(StatusDotColor.MERGING)` — a **static** dot colored by the orphan `MergingDotColor = Color(0xFF60A5FA)` (a hardcoded blue with "no mockup token") — beside the "Merging" status text and a progress %. (By contrast the `RECORDING` dot animates with a pulse + halo.)
2. **Recovery card — `ProgressStrip`** (`RecoveryCard.kt`). During a recovery merge (`mergeInProgress = 0f..1f`, surfaced as `progress != null`) it renders a determinate **cell-fill strip** under a "Merging" header (`recovery_progress_header_merging`) with a polite live region. No glyph today.

## 3. Decision

Introduce **one reusable animated-glyph primitive** and drop it into both surfaces.

### 3.1 `MergeMotion` — pure phase helper

`ui/theme/MergeMotion.kt`, pure-Kotlin, JVM-unit-tested under `isReturnDefaultValues = true` (house pure-helper pattern):

```kotlin
object MergeMotion {
    /** One full revolution per cycle. */
    const val SPIN_PERIOD_MS = 1400

    /**
     * Rotation in degrees for an animation [fraction] in [0f,1f).
     * Reduced-motion holds the glyph static (0°) so meaning survives without motion
     * (WCAG 2.2 AA SC 2.3.3). Fraction is wrapped into [0,1) before mapping to [0,360).
     */
    fun angle(fraction: Float, reduceMotion: Boolean): Float {
        if (reduceMotion) return 0f
        val wrapped = fraction - kotlin.math.floor(fraction)
        return wrapped * 360f
    }
}
```

The helper owns all phase/clamp/gate-decision math; the composable stays a thin framework seam.

### 3.2 `ProcessingGlyph` — animated wrapper composable

`ui/components/ProcessingGlyph.kt`. A single composable that:

- reads `rememberReduceMotion()` (satisfies `checkA11yAnimationGated`, which requires every file using `rememberInfiniteTransition`/`infiniteRepeatable` to read the reduced-motion seam in the same file),
- **branches on reduced-motion (codex):** when reduced, render a static `SemanticIcon` and **do not create the infinite transition at all** (no wasted work / no recomposition churn). Only the non-reduced path builds the `rememberInfiniteTransition` float `0f→1f` over `MergeMotion.SPIN_PERIOD_MS` (`RepeatMode.Restart`, linear), computes `MergeMotion.angle(fraction, reduceMotion = false)`, and applies `Modifier.rotate(angle)`. Both paths render the same `SemanticIcon` — keep the two branches as two clean top-level composable calls, not `animateFloat` buried in conditional logic.
- renders `SemanticIcon(glyph = RovaIcons.Merge, contentDescription = null, status = IconStatus.Processing, modifier = …size…)`.

`MergeMotion.angle` keeps its `reduceMotion` parameter for the pure-test contract even though the composable now short-circuits the reduced path before calling it. `kotlin.math.floor` has a `Float` overload (`floor(Float): Float`), so `fraction - floor(fraction)` stays `Float` — no `Double` round-trip.

Default size **18.dp** (sizing scale 18/20/24/30/56). The tint flows through `SemanticIcon` (the only file allowlisted by `checkSemanticIconNoRawAlpha`), so the wrapper applies **no** raw color. `contentDescription = null` because both host surfaces already announce "Merging"/progress via live regions — the glyph is decorative and must not double-announce. **Planning must verify** the glyph is not the sole accessible indication on either surface (it is not — Record-home pill carries the status label, recovery card carries the live-region header), per codex.

### 3.3 Wiring

- **`RecordChrome.kt`** — the `StatusPill` `MERGING` branch renders `ProcessingGlyph` in place of the static `StatusDot(MERGING)`. **Retire `MergingDotColor`** and the `StatusDotColor.MERGING` color path (the dot is replaced, not recolored). Pill layout, padding, and overall height held stable so the active HUD does not reflow.
  - **Precondition (codex, High):** before deleting `MergingDotColor` or the `StatusDotColor.MERGING` enum value, grep-audit every reference (previews, tests, `hudStatusPillContent`, other branches). Remove the enum value only if its sole consumers are the two static indicators this slice replaces; otherwise retire just the color binding and leave the enum value. A blind delete risks a compile break or a silent semantic regression.
- **`RecoveryCard.kt`** — `ProgressStrip`'s header `Row` renders `ProcessingGlyph` before the "Merging" header label, gated on `progress != null` (merge active). The determinate cell-fill strip is unchanged — it still carries the real progress; the glyph signals "actively processing."

## 4. Color & contrast

The glyph uses the locked status channel: `status = IconStatus.Processing → RovaSemantics.escalating = Color(0xFFF97316)` (orange), identical across all 12 palettes, never theme-retinted, never per-call alpha-diluted (ADR-0031 §3).

**Visual change to verify on device:** the Record-home merge indicator shifts **blue (#60A5FA) → orange (#F97316)** to honor the P0-established `Processing → escalating` mapping. This is the icon-system contract, not a new choice. If the owner finds orange reads as "warning" for a benign merge, that is a `SemanticIconSpec` mapping change handled separately — out of scope here.

**Contrast requirement (SC 1.4.11, non-text ≥3:1):** `escalating` (#F97316) must clear 3:1 over both substrates — the `GlassRole.RecordChrome` StatusPill fill and the recovery card surface. A `ContrastMath` assertion covers this. Because the color is locked, a failure is surfaced to the owner (cannot be silently retinted); flagged as the one risk to confirm during planning.

## 5. Accessibility

- Glyph is **decorative** (`contentDescription = null`); the surfaces' existing polite live regions ("Merging", progress) are the sole announcement (no double-speak).
- Reduced-motion (`rememberReduceMotion()`) holds the glyph **static but visible** — the mark still communicates "merging," motion is the only thing dropped (SC 2.3.3 / 2.2.2, ADR-0020).
- Status is paired with shape + text, never color-alone (SC 1.4.1).
- **Stall/error is not this glyph's job (codex):** an indefinitely spinning glyph could imply progress on a stuck merge. Merge stall/failure is already owned elsewhere — the Record-home `LaunchedEffect(isMerging, mergeError)` path and the recovery card's determinate cell-fill strip. This slice does not change that; it only restyles the active-merge indicator.

## 6. Gates

**No new gate.** Coverage is already in place:
- `checkA11yAnimationGated` — `ProcessingGlyph` reads `rememberReduceMotion()` in the same file as its `rememberInfiniteTransition`.
- `checkSemanticIconNoRawAlpha` — the wrapper tints only through `SemanticIcon`; no raw `Color` on a `tint =`.

Retiring `MergingDotColor` removes an orphan hardcoded color. **Gate count stays 46.**

## 7. Tests (JVM)

- **`MergeMotionTest`** — `angle(0f, false) == 0f`; `angle(0.25f, false) == 90f`; `angle(0.5f, false) == 180f`; `angle(0.999f, false) ≈ 359.6f`; boundary `angle(1f, false) == 0f`; wrap `angle(1.25f, false) == 90f`; reduced-motion `angle(anything, true) == 0f` (codex test set).
- **Contrast assertion** (extend `ThemeContrastTest` or a focused `ContrastMath` test) — `escalating` ≥3:1 over the StatusPill glass substrate and the recovery card substrate (worst case).

No instrumented/Compose-render tests (project policy: JVM unit tests only). Visual placement and the blue→orange shift are confirmed by owner device smoke.

## 8. Rejected / out of scope

- **Glass-chip active-nav (ADR-0031 §6, P2 chunk #3)** — rejected: no persistent selected-tab `NavigationBar` exists; mode/topology pickers already accent-fill. A selected-tab chip would be a consumerless pattern (YAGNI). Revisit only if a persistent nav bar is introduced.
- **Determinate Processing arc** — rejected: duplicates progress already shown (% on Record-home, cell-fill on Recovery), needs a new Canvas arc primitive + likely a new gate; over-builds for a ~few-second merge.
- **Per-surface inline animation (no shared seam)** — rejected: duplicates motion logic across two files, no reuse for future P2 animated states, against the one-seam ADR ethos.
- **Secondary-concept glyph authoring** — none needed this slice (`RovaIcons.Merge` exists); consumer-driven.
- **Onboarding-illustration refresh (Track B)** — separate spec + branch; disjoint files, runs in parallel.

## 9. File summary

| File | Change |
|--|--|
| `ui/theme/MergeMotion.kt` | **new** — pure phase helper |
| `ui/components/ProcessingGlyph.kt` | **new** — animated wrapper composable |
| `ui/screens/RecordChrome.kt` | modify — `MERGING` → `ProcessingGlyph`; remove `MergingDotColor` + `StatusDotColor.MERGING` path |
| `ui/recovery/RecoveryCard.kt` | modify — `ProgressStrip` header glyph when merging |
| `test/.../MergeMotionTest.kt` | **new** — phase math |
| `test/.../ThemeContrastTest.kt` (or focused test) | modify — escalating contrast over both substrates |

2 new units (+2 test changes), 2 modified surfaces. No schema/manifest/behavior change.
