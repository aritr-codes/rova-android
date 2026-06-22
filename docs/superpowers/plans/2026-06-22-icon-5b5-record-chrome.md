# 5b-5 Record-chrome Icon Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the record-screen flip control read as "switch camera," give the record-nav Library/Settings icons the glass-circle background the cam-controls already have, and size up the History/Library card overlay glyphs.

**Architecture:** Pure UI-chrome edits in two files (`RecordChrome.kt`, `RovaGlyphs.kt`) plus Library card components. No new logic seam — `RovaIcons.FlipCam` and the `GlassSurface(RecordChrome, CircleShape)` pattern already exist; this slice wires them in and removes the two dead camera-body glyphs. #3 is a token/size bump confirmed by on-device A/B.

**Tech Stack:** Kotlin, Jetpack Compose, ADR-0031 icon system (`SemanticIcon` glyph overload, `RovaIcons`/`RovaGlyphs`), `GlassSurface`/`GlassRole`, `RecordChromeTokens`, `LibraryDimens`.

## Global Constraints

- Branch `feat/icon-5b5-record-chrome` off **latest master** (after PR #130 / #131 merge). Create via a git worktree.
- **46 static gates + full `:app:testDebugUnitTest` green at EVERY commit.** Never edit a `check*` to pass — fix the source.
- **No new user-facing strings** (all changes decorative / sizing). `record_switch_to_rear_cd` / `record_switch_to_front_cd` already exist and are reused unchanged.
- `board-3-semantic.html` (gitignored) is the glyph **SSOT** — round-trip any glyph d-string change.
- **EDIT-only subagents**; the controller runs ALL gradle/tests/commits/smoke. Build **WARM** (no cache wipe). On a journal-lock failure: `gradlew --stop` then rebuild with `--no-build-cache`. Copy the gitignored `local.properties` (`sdk.dir=…`) into the worktree before the first build.
- **codex** peer review for the Task 1 + Task 2 edits (chrome behavior / >5 lines).
- Device smoke on **RZCYA1VBQ2H** (Android 14), record screen both orientations + history. **Push/PR/merge only on explicit owner GO.**
- `SemanticIcon` glyph overload signature: `SemanticIcon(glyph: RovaGlyph, contentDescription: String?, role: IconRole, modifier: Modifier)`.
- `GlassSurface` signature (per `GlassCircleButton`): `GlassSurface(role: GlassRole, modifier: Modifier, shape: Shape) { content }`.

---

### Task 1: Flip glyph → FlipCam (remove dead camera-body glyphs)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (`CamFlipButton`, ~297–309)
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt` (remove `CameraFront`/`CameraRear`, ~355–401; update comments at ~328 and ~346)
- Test (existing, unchanged): `app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt` already asserts `RovaIcons.FlipCam == RovaGlyphs.FlipCam`.

**Interfaces:**
- Consumes: `RovaIcons.FlipCam` (`RovaGlyph`), `SemanticIcon` glyph overload, `R.string.record_switch_to_rear_cd` / `record_switch_to_front_cd` (existing).
- Produces: nothing new (no later task depends on Task 1).

- [ ] **Step 1: Repoint the flip glyph in `CamFlipButton`**

In `RecordChrome.kt`, replace the current body (~297–309):

```kotlin
        val flipIcon = if (isFrontCamera) RovaGlyphs.CameraRear else RovaGlyphs.CameraFront
        val flipCd = stringResource(
            if (isFrontCamera) R.string.record_switch_to_rear_cd
            else R.string.record_switch_to_front_cd,
        )
        SpinningBox(degrees = spinDegrees) {
            SemanticIcon(
                flipIcon,
                contentDescription = flipCd,
                role = role,
                modifier = Modifier.size(16.dp),
            )
        }
```

with:

```kotlin
        // 5b-5: the flip affordance is the rotation-arrows FlipCam glyph (duotone), state-independent
        // — the active lens is conveyed by [flipCd], not by the glyph. (ADR-0031; board `flip_cam`.)
        val flipCd = stringResource(
            if (isFrontCamera) R.string.record_switch_to_rear_cd
            else R.string.record_switch_to_front_cd,
        )
        SpinningBox(degrees = spinDegrees) {
            SemanticIcon(
                glyph = RovaIcons.FlipCam,
                contentDescription = flipCd,
                role = role,
                modifier = Modifier.size(16.dp),
            )
        }
```

(`RovaIcons` is already imported — used by `RecordBottomNav`. `RovaGlyphs` stays imported — `FlashBolt` still uses it.)

- [ ] **Step 2: Remove the dead `CameraFront` + `CameraRear` glyph defs**

In `RovaGlyphs.kt`, delete both `val CameraFront: ImageVector = …` and `val CameraRear: ImageVector = …` blocks (the two `ImageVector.Builder(...).build()` definitions, ~355–401, including their `/** … */` KDoc lines). They have no remaining references (not in `RovaIcons`, not in any test). Leave the surrounding glyphs (`FlashBolt` above, `ChevronUp` below) intact.

- [ ] **Step 3: Fix the now-stale comments**

In `RovaGlyphs.kt`, update the `FlipCam` KDoc (~326–328): change the line
`// channel. Bespoke duotone sibling of CameraFront/CameraRear; the call-site flip migrates in 5b-2…5.`
to
`// channel. The record-screen flip control consumes this glyph (migrated in 5b-5).`

And in the folded-in-vectors block comment (~344–346), change
`// flip switched to CameraFront/CameraRear in B6).`
to
`// flip now uses the duotone RovaGlyphs.FlipCam — 5b-5).`

- [ ] **Step 4: Build + gates + JVM (controller)**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all 46 `check*` gates pass; `testDebugUnitTest` green (incl. `RovaIconsTest`). No reference-to-`CameraFront`/`CameraRear` compile error anywhere.

- [ ] **Step 5: Board SSOT round-trip + device smoke (controller + owner)**

Confirm `FlipCam` renders legibly at 16dp: install the debug APK on RZCYA1VBQ2H, open the record screen, screencap + crop the flip control. The glyph must read as a camera-flip (rotation arrows), not a static camera. If illegible at 16dp, adjust the `FlipCam` d-strings in `RovaGlyphs.kt` (round-trip through `board-3-semantic.html`) and rebuild — keep edits inside `RovaGlyphs.kt` (`checkRovaGlyphHome`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt
git commit -m "feat(icon): 5b-5 flip control uses FlipCam rotation-arrows glyph"
```

---

### Task 2: Record-nav glass-circle background

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` (`NavItem`, ~722–757)

**Interfaces:**
- Consumes: `GlassSurface`, `GlassRole.RecordChrome`, `CircleShape`, `RecordChromeTokens.navIconBoxSize` / `navIconGlyphSize`, `rememberChromeScale()` (all already used in this file).
- Produces: nothing new.

- [ ] **Step 1: Wrap the `NavItem` glyph in a glass circle**

In `RecordChrome.kt` `NavItem`, replace the inner glyph `Box` (~735–751):

```kotlin
        Box(
            modifier = Modifier
                .size(RecordChromeTokens.navIconBoxSize)
                .clip(RoundedCornerShape(RecordChromeTokens.navIconCornerRadius)),
            contentAlignment = Alignment.Center,
        ) {
            // PR-ε (spec §5): glyph spins inside the stable square icon box
            // (square = rotation-invariant; clickable stays on the Column).
            SpinningBox(degrees = spinDegrees) {
                SemanticIcon(
                    glyph = glyph,
                    contentDescription = label,
                    role = if (enabled) IconRole.Default else IconRole.Disabled,
                    modifier = Modifier.size(RecordChromeTokens.navIconGlyphSize * rememberChromeScale()),
                )
            }
        }
```

with a `GlassSurface` circle mirroring `GlassCircleButton`:

```kotlin
        // 5b-5: Library/Settings sit in the SAME glass circle as the cam-controls (GlassCircleButton)
        // so the nav reads as chrome buttons, not bare glyphs, beside the accent FAB. CircleShape is
        // rotation-invariant — the glyph still spins inside via SpinningBox.
        GlassSurface(
            role = GlassRole.RecordChrome,
            modifier = Modifier.size(RecordChromeTokens.navIconBoxSize),
            shape = CircleShape,
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                SpinningBox(degrees = spinDegrees) {
                    SemanticIcon(
                        glyph = glyph,
                        contentDescription = label,
                        role = if (enabled) IconRole.Default else IconRole.Disabled,
                        modifier = Modifier.size(RecordChromeTokens.navIconGlyphSize * rememberChromeScale()),
                    )
                }
            }
        }
```

- [ ] **Step 2: Track the focus ring to the circle**

In the same `NavItem`, the `enabled` branch sets a focus highlight (~729):

```kotlin
                .focusHighlight(RoundedCornerShape(RecordChromeTokens.navIconCornerRadius))
```

Change it to:

```kotlin
                .focusHighlight(CircleShape)
```

(`CircleShape` is already imported — line 29. `fillMaxSize` is already used in this file — `GlassCircleButton`. `RecordChromeTokens.navIconCornerRadius` may now be unused; leave the token in place — removing it is out of scope.)

- [ ] **Step 3: Build + gates + JVM (controller)**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; 46 gates pass — specifically `checkGlassSurfaceRoleUsage` (RecordChrome role; precedent = `GlassCircleButton`), `checkRecordSurfaceNoBlur` (no `Modifier.blur`), `checkRecordChromeLockSingleSite` (no second chrome-lock site added). JVM green.

- [ ] **Step 4: Device smoke (controller + owner)**

Install + open the record screen on RZCYA1VBQ2H in BOTH orientations. Confirm Library + Settings now sit in a glass circle matching the flash/flip controls, the FAB still dominates as the accent disc between them, and the glyphs stay centered. Owner visual confirm.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt
git commit -m "feat(icon): 5b-5 record-nav Library/Settings get glass-circle background"
```

---

### Task 3: History/Library card overlay-glyph size bump (#3, device A/B)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryBadges.kt` (`OrientationFramePill`, ~63–80)
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryDimens.kt` (`statusDotSize`, line 63)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: nothing.

> The exact final dp is a **perceptual, owner-determined** value (spec §Change 3). This task lands a concrete first bump, then the controller captures before/after on device and the owner picks the final size. If the owner points at a different card element (e.g. the grid Select chip or the overlay text), redirect the bump there in the review loop.

- [ ] **Step 1: Enlarge the orientation frame glyph**

In `LibraryBadges.kt` `OrientationFramePill`, change the frame dimensions (~64–67) and border:

```kotlin
    val (w, h) = when (orientation) {
        com.aritr.rova.ui.library.LibraryOrientation.PORTRAIT -> 10.dp to 14.dp
        com.aritr.rova.ui.library.LibraryOrientation.LANDSCAPE -> 14.dp to 10.dp
    }
```

to:

```kotlin
    val (w, h) = when (orientation) {
        com.aritr.rova.ui.library.LibraryOrientation.PORTRAIT -> 13.dp to 18.dp
        com.aritr.rova.ui.library.LibraryOrientation.LANDSCAPE -> 18.dp to 13.dp
    }
```

and the border stroke (~77) `1.5.dp` → `2.dp`:

```kotlin
                .border(2.dp, captionTextColor, RoundedCornerShape(2.dp)),
```

- [ ] **Step 2: Enlarge the card status dot**

In `LibraryDimens.kt`, change line 63:

```kotlin
    val statusDotSize = 6.dp
```

to:

```kotlin
    val statusDotSize = 8.dp
```

- [ ] **Step 3: Build + gates + JVM (controller)**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; 46 gates pass (`checkA11yTargetSizeToken` unaffected — these are decorative overlay sizes, not touch targets); JVM green.

- [ ] **Step 4: Device A/B (controller + owner)**

Install on RZCYA1VBQ2H, open History/Library (grid + list). Screencap before/after the card overlays. Owner confirms the orientation frame + status dot now read at the right size, or redirects to a different element / different dp. Iterate the literals in Step 1–2 until owner-approved.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryBadges.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryDimens.kt
git commit -m "feat(icon): 5b-5 bump History card orientation-frame + status-dot size"
```

---

## Execution notes

- Tasks are independent (different concerns; Tasks 1 & 2 share `RecordChrome.kt` but disjoint functions — sequence them to avoid edit churn). No cross-task interface dependencies.
- This is a visual-heavy chrome slice with no new pure-logic seam, so there are no new JVM unit tests; verification per task = build + 46 gates + full `testDebugUnitTest` (existing `RovaIconsTest` covers `FlipCam` identity) + device smoke. This is intentional and matches prior record-chrome slices.
