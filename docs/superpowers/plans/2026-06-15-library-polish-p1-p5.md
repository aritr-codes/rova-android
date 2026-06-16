# Library UX/UI Polish Pass (P1–P8) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Subagents EDIT-ONLY; the controller runs all gradle + commits** (house rule — post-edit daemon pileup pins CPU). Build WARM: `gradlew.bat :app:assembleDebug` (NO `--stop`, NO cache wipe). Gate-build with `assembleDebug` (`lintDebug` is RED on pre-existing `VaultAndroidOps` NewApi — unrelated). JVM tests: `gradlew.bat :app:testDebugUnitTest`.

**Goal:** Raise the Enhanced Library/History surfaces to the Record screen's visual quality — route every Library component through a Record-aligned token scale, give the hero a glass showcase treatment (and fix the DualShot hero grey-strip render bug), make grid/list cards glass-consistent, and replace plain states with on-brand skeleton/empty/error.

**Architecture:** Five stacked slices (P1→P5) on `feat/library-history-selection`, one local commit each, no push/merge until the owner runs the end-of-reskin merge train. P1 is a pure token refactor every later slice depends on. Framework-touching changes get a pure-Kotlin sibling + JVM test (house seam pattern). New user-facing strings land in `values/strings.xml` + `values-es/strings.xml` (ADR-0022). All animation is reduce-motion gated (ADR-0020). Library/History UI never writes `SessionManifest` (ADR-0030, `checkLibraryNoManifestWrite`).

**Tech Stack:** Kotlin, Jetpack Compose, Media3 1.4.1 (ExoPlayer/PlayerView), the in-source token contracts `RovaTokens` (Inter scale) / `RecordChromeTokens` / `GlassSurface`+`GlassRole`+`GlassResolver` (3-layer glass), `LibraryDimens` (Library tokens).

---

## Reconciliation notes (read before starting — corrects two brief assumptions)

1. **Glass alphas (affects P5).** Record's glass-system fill **is** `0.40` (`GlassResolver.RECORD_ALPHA` via `NeutralDarkRecordPalette.glassTint = Black@0.40`). But the glass-system **stroke/edge is `White@0.09`** (`NeutralDarkRecordPalette.edge`), **not** `0.07`. The `0.07` in the brief is the *legacy literal* `RecordChromeTokens.glassStroke`, used only for hand-built pills/cells — it is NOT the glass-system edge. Library's glass chrome (`LibraryTopBar`/`LibraryBatchBar`/`LibrarySelectionTopBar`/`LibraryListRow`) already routes through `GlassSurface(role = …)`, so it already inherits the correct `0.40 / 0.09`. **P5 target = `0.40` fill / `0.09` edge** (verify the roles, don't chase `0.07`).
2. **`GlassRole` enum** (`ui/theme/GlassEnvironment.kt:24`): `RecordChrome, BottomSheet, Dialog, Card, NavBar, Banner`. The opaque outliers needing glass = **`LibraryHeroCard`** (plain `Column`) and **`LibraryGridCard`** (raw `Box`). Thumbnails stay opaque `surfaceVariant` — spec §9 "never glass-on-thumbnail" (the glass goes on the card *frame/edge*, not over the video).

**Decision (owner, 2026-06-15):** P2 hero = **Showcase overlay** model — media fills the hero box; eyebrow+title+meta overlaid bottom-left over a tokenized scrim; one primary Play overlaid; Favorite/Share subordinate ghost icons top-right.

**Decisions (owner, 2026-06-15, after UX research + codex review) — folded into this plan:**
- **Card autoplay DEMOTED to static posters by default.** Scroll-triggered card autoplay is the strongest UX anti-pattern in the research (battery/distraction/false-intent). The hero keeps muted autoplay (single focal element). Card preview becomes an **opt-in Setting, default OFF, reduce-motion gated** → **Slice P7**. (Reverts the Slice 4.2 "all cards autoplay" default; the pooled-autoplay machinery is preserved, just gated off by default.)
- **Storage/usage summary line** at the Library top (`128 sessions · 1,842 clips · 18.6 GB` + fill bar) — must-have for a storage-hungry background recorder → **Slice P6**.
- **Session identity is the priority signal.** Every card/row must read as a *recording session*: time (range) · clip count · total duration · size · status — not a generic thumbnail (codex: "make every item read as a recording session"). Requires adding **`clipCount`** to `LibraryRow` → folded into **Slice P3**.
- **Empty state** = plain tagline + quiet `Go to Record →` text link, **no filled CTA** (M3 empty-state guidance) → adjusts **Slice P4**.
- **Scope held flat:** one Library tab + filter chips (incl. Favorites). **No** albums/collections/extra nav layers (YAGNI). **No** session-detail screen — grouped row → player-with-segment-timeline is sufficient.
- **Item sheet → floating "Brave-style" elevated surface** (respects system nav-bar insets, gap from screen edge, rounded all corners, shadow/elevation, breathing room) → **Slice P8**.

---

## File Structure

**P1 — tokens**
- Modify: `ui/library/components/LibraryDimens.kt` (extend scale)
- Modify (route through tokens): `LibraryHeroCard.kt`, `LibraryGridCard.kt`, `LibraryListRow.kt`, `LibraryBadges.kt`, `LibraryStates.kt`, `LibraryScreen.kt`

**P2 — hero showcase + DualShot fix**
- Create: `ui/library/HeroMetaFormatter.kt` (pure) + `test/.../HeroMetaFormatterTest.kt`
- Create: `ui/library/HeroUnderlayPolicy.kt` (pure) + `test/.../HeroUnderlayPolicyTest.kt`
- Modify: `ui/library/components/LibraryAutoplayVideo.kt` (drop static under-layer after first frame)
- Modify: `ui/library/components/LibraryHeroCard.kt` (overlay restructure, eyebrow token, one CTA, scrim)
- Modify: `LibraryScreen.kt` (hero box height if hero footprint changes), `strings.xml` (+ `-es`)

**P3 — grid/list polish**
- Modify: `LibraryGridCard.kt` (glass-aware frame, soft selection ring, tokenized scrim/pads)
- Modify: `LibraryListRow.kt` (tokenized spacing/type, larger thumbnail)
- Modify: `LibraryBadges.kt` (tokenized pads, tighter weight)

**P4 — states**
- Create: `ui/library/LibraryStatePolicy.kt` (pure) + `test/.../LibraryStatePolicyTest.kt`
- Modify: `LibraryStates.kt` (skeleton/shimmer loading, search-empty + error/missing-file states)
- Modify: `LibraryFilterChips.kt` (quieter glass-consistent chips)
- Modify: `LibraryScreen.kt` (route the new state kinds), `strings.xml` (+ `-es`)

**P5 — interaction/motion**
- Modify: `ui/components/RovaAnimations.kt` (add `Modifier.pressScale`, reduce-motion gated)
- Create: `ui/library/PressFeedback.kt` (pure) + `test/.../PressFeedbackTest.kt`
- Modify: hero/grid/list press feedback; verify glass alphas (doc step, no change expected)

**P6 — storage/usage summary**
- Create: `ui/library/StorageSummaryFormatter.kt` (pure) + `test/.../StorageSummaryFormatterTest.kt`
- Create: `ui/library/components/LibraryUsageLine.kt` (composable)
- Modify: `LibraryUiState.kt` (carry usage aggregate), `HistoryViewModel.kt` (derive from rows), `LibraryScreen.kt` (render under top bar), `strings.xml` (+ `-es`)

**P7 — card-preview opt-in setting (autoplay demotion)**
- Modify: `data/RovaSettings.kt` (add `libraryCardPreview` pref, default false)
- Modify: `ui/screens/SettingsScreen.kt` (+ its ViewModel) (toggle row)
- Modify: `LibraryScreen.kt` (gate `autoplayKeys` card-selection on the setting; hero unaffected)
- Modify: `strings.xml` (+ `-es`)

**P8 — floating "Brave-style" item sheet**
- Modify: `ui/library/components/LibraryItemSheet.kt` (floating elevated surface, nav-bar insets, gap, rounded, shadow) + a session-identity header row
- Modify: `ui/library/components/LibraryDimens.kt` (sheet floating tokens — added in P1.1)

Test root: `app/src/test/java/com/aritr/rova/ui/library/`.

---

# Slice P1 — Token foundation

**Goal:** Extend `LibraryDimens` to a Record-aligned scale and route every Library component literal through it. Pure refactor; no behavior change. Visual deltas (slightly larger radii/edges) are reviewed on device at the end of the slice.

### Task P1.1: Extend the LibraryDimens token scale

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryDimens.kt`

- [ ] **Step 1: Replace the token values and add the new tokens**

Current values: `screenPadH = 12.dp`, `gridGutter = 6.dp`, `cardRadius = 14.dp`, `heroRadius = 18.dp`, `pillRadius = 8.dp`, `heroHeight = 176.dp`, plus icon/padding tokens. Edit the body to:

```kotlin
object LibraryDimens {
    /** Outer horizontal page padding (grid/list/hero share this gutter). Record edge = 16dp. */
    val screenPadH = 16.dp
    /** Gap between grid tiles. */
    val gridGutter = 6.dp

    /** Card / tile corner radius (grid + list). Matches Record's card family (18–22dp). */
    val cardRadius = 18.dp
    /** Hero media corner radius (focal element — one step above cards). */
    val heroRadius = 20.dp
    /** Overlay pill / chip-ish corner radius. Matches Record loopPill = 11dp. */
    val pillRadius = 11.dp

    /** Hero media height — showcase footprint (overlay caption lives inside this box). */
    val heroHeight = 200.dp

    /** Quick-action / batch icon glyph size. */
    val actionIcon = 20.dp
    /** Top-bar nav/toggle glyph size. */
    val navIcon = 22.dp

    /** Compact vertical padding inside the glass top bars (excludes the system-bar inset). */
    val topBarPadV = 6.dp
    /** Day section label vertical padding. */
    val sectionPadV = 6.dp

    // ── Polish P1: card rhythm + semantic alphas ─────────────────────────
    /** Vertical padding inside a grid/list card (Record rhythm 6–8dp). */
    val cardPadV = 6.dp
    /** Badge / overlay-pill content padding (was inline 6.dp/2.dp). */
    val badgePadH = 8.dp
    val badgePadV = 3.dp
    /** Caption-bar inner padding (was inline 8/8/18/6). */
    val captionPadH = 10.dp
    val captionPadTop = 18.dp
    val captionPadBottom = 8.dp

    /** Hairline divider / glass-edge alpha on cards (matches glass-system edge ~0.09; use 0.07 for hairlines). */
    val dividerAlpha = 0.07f
    /** Soft selection-ring alpha (replaces the hard 2dp primary border). */
    val selectionEdgeAlpha = 0.30f
    /** Selection-ring stroke width. */
    val selectionEdgeWidth = 1.5.dp
    /** Card frame edge stroke width (glass-consistent 1dp). */
    val cardEdgeWidth = 1.dp
    /** Hero bottom-scrim peak alpha (over bright footage, AA on white worst-case backed by CaptionScrim/ContrastMath). */
    val heroScrimAlpha = 0.62f
    /** Empty-state icon ring alpha (was hardcoded 0.4f). */
    val emptyIconAlpha = 0.40f

    // ── Polish P6/P3: usage line + session-identity ──────────────────────
    /** Status dot diameter on list rows / cards (recovered/interrupted). */
    val statusDotSize = 6.dp
    /** Usage fill-bar height. */
    val usageBarHeight = 4.dp

    // ── Polish P8: floating "Brave-style" item sheet ─────────────────────
    /** Gap between the floating sheet and the screen edges (left/right/bottom, ABOVE the nav-bar inset). */
    val sheetEdgeGap = 12.dp
    /** Floating sheet corner radius (all four corners — it floats, so not just the top). */
    val sheetCornerRadius = 28.dp
    /** Floating sheet shadow/elevation. */
    val sheetElevation = 12.dp
    /** Max width so the sheet reads as a card on wide screens (tablets/landscape). */
    val sheetMaxWidth = 560.dp
}
```

- [ ] **Step 2: Build to verify it compiles (no callers changed yet)**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL (42 gates green). New tokens are unused until P1.2 — fine.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryDimens.kt
git commit -m "polish(library): extend LibraryDimens to Record-aligned scale (P1)"
```

### Task P1.2: Route grid/list/badges/states/screen literals through the tokens

**Files (modify):** `LibraryGridCard.kt`, `LibraryListRow.kt`, `LibraryBadges.kt`, `LibraryStates.kt`, `LibraryScreen.kt`

> Read each file first. Replace the inline literals identified below with the matching `LibraryDimens` token. This is mechanical — NO visual-model change yet (that's P2/P3). Selection-border replacement and glass frames are P3, not here; in P1 just swap literals 1:1 where a token now exists.

- [ ] **Step 1: `LibraryBadges.kt` — tokenize the caption + pill paddings**

`OverlayPill` content padding `6.dp`/`2.dp` → `LibraryDimens.badgePadH`/`badgePadV`. `CaptionBar` padding `start/end = 8.dp`, `top = 18.dp`, `bottom = 6.dp` → `captionPadH`, `captionPadTop`, `captionPadBottom`. Leave `pillRadius` usage (already tokenized; value now 11dp).

- [ ] **Step 2: `LibraryStates.kt` — tokenize the empty-icon alpha**

`LibraryEmpty`: replace the hardcoded `primaryContainer.copy(alpha = 0.4f)` (≈line 49) with `…copy(alpha = LibraryDimens.emptyIconAlpha)`. (Skeleton/error states are P4 — leave the spinner/empty shape here.)

- [ ] **Step 3: `LibraryListRow.kt` — tokenize the card vertical padding**

Replace the outer vertical `4.dp` with `LibraryDimens.cardPadV`. Leave thumbnail size (96×54) and inner pads for P3.

- [ ] **Step 4: `LibraryGridCard.kt` — tokenize badge/check pads only**

Replace the badge padding `6.dp` literals with `LibraryDimens.badgePadH`/`badgePadV` where they wrap the badge cluster; replace the check-chip pad `6.dp` with `cardPadV`. **Do NOT touch** the `border(2.dp, primary)` selection line or the opaque `Box` — that is P3.

- [ ] **Step 5: `LibraryScreen.kt` — no literal swap needed**

The background gradient `0.24f` and `20.dp` content padding are addressed in P5/P4 respectively; confirm `screenPadH`/`gridGutter` already come from `LibraryDimens` (they do). No edit unless a stray literal is found.

- [ ] **Step 6: Build + full JVM suite**

Run: `gradlew.bat :app:assembleDebug` then `gradlew.bat :app:testDebugUnitTest`
Expected: both GREEN, 42 gates pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/
git commit -m "polish(library): route grid/list/badges/states through LibraryDimens tokens (P1)"
```

### Task P1.3: Device smoke (visual delta review)

- [ ] **Step 1: Install + eyeball**

```
gradlew.bat :app:assembleDebug
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk
```
Open Library. Confirm: 16dp edges read as more breathing room, 18/20dp radii look intentional (not buggy), nothing clipped/overlapping. **Owner GO before P2.** (No reduce-motion check needed — P1 has no motion.)

---

# Slice P2 — Hero showcase + DualShot grey-strip fix

**Goal:** Make the hero the app's showcase (media-dominant, overlaid caption over a tokenized scrim, Inter eyebrow, one primary Play CTA, subordinate Favorite/Share), and fix the confirmed Library render-layer DualShot grey strip by dropping the static under-layer once the player's first frame is ready.

**Architecture:** Two pure helpers (meta-line formatting + under-layer gate) carry the logic and get JVM tests; the Compose changes are thin seams. The DualShot fix is autoplay-only — the reduce-motion (static-`VideoFrame`-only) path is already clean per the device RCA (raw frames clean), so the leak is purely the `Crop`-under-`ZOOM` layer divergence in the off-16:9 hero box.

### Task P2.1: HeroMetaFormatter (pure) — the meta line

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/HeroMetaFormatter.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/HeroMetaFormatterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class HeroMetaFormatterTest {
    @Test fun joinsAllParts_withMiddot() {
        val out = HeroMetaFormatter.format(
            dayLabel = "Mon", timeLabel = "12:34", clipCountLabel = "12 clips", durationLabel = "1m",
        )
        assertEquals("Mon · 12:34 · 12 clips · 1m", out)
    }

    @Test fun dropsBlankParts() {
        val out = HeroMetaFormatter.format(
            dayLabel = "Mon", timeLabel = "12:34", clipCountLabel = "", durationLabel = "1m",
        )
        assertEquals("Mon · 12:34 · 1m", out)
    }

    @Test fun singlePart_noSeparator() {
        assertEquals("Mon", HeroMetaFormatter.format("Mon", "", "", ""))
    }
}
```

- [ ] **Step 2: Run it — expect FAIL (unresolved reference HeroMetaFormatter)**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.HeroMetaFormatterTest"`
Expected: compile failure / FAIL.

- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.library

/**
 * Pure formatter for the hero meta line ("Mon · 12:34 · 12 clips · 1m"). All parts arrive already
 * localized (caller supplies stringResource / SmartTitle output); blanks are dropped so legacy rows
 * without a clip count or duration collapse cleanly. Separator is the middot the rest of the app uses.
 * Framework-free → JVM-tested (house seam pattern).
 */
object HeroMetaFormatter {
    private const val SEP = " · "
    fun format(dayLabel: String, timeLabel: String, clipCountLabel: String, durationLabel: String): String =
        listOf(dayLabel, timeLabel, clipCountLabel, durationLabel)
            .filter { it.isNotBlank() }
            .joinToString(SEP)
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.HeroMetaFormatterTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/HeroMetaFormatter.kt app/src/test/java/com/aritr/rova/ui/library/HeroMetaFormatterTest.kt
git commit -m "polish(library): HeroMetaFormatter pure helper for hero meta line (P2)"
```

### Task P2.2: Clip-count plural string (en + es)

**Files:** `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`

- [ ] **Step 1: Add the plural to `values/strings.xml`**

```xml
<plurals name="library_hero_clip_count">
    <item quantity="one">%d clip</item>
    <item quantity="other">%d clips</item>
</plurals>
```

- [ ] **Step 2: Add the Spanish plural to `values-es/strings.xml`**

```xml
<plurals name="library_hero_clip_count">
    <item quantity="one">%d clip</item>
    <item quantity="other">%d clips</item>
</plurals>
```

- [ ] **Step 3: Build (verifies resource merge + `checkNoHardcodedUiStrings`)**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "polish(library): library_hero_clip_count plural (en+es) (P2)"
```

### Task P2.3: HeroUnderlayPolicy (pure) + drop static under-layer after first frame

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/HeroUnderlayPolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/HeroUnderlayPolicyTest.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryAutoplayVideo.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroUnderlayPolicyTest {
    @Test fun showsUnderlay_beforeFirstFrame() {
        assertTrue(HeroUnderlayPolicy.showStaticUnderlay(firstFrameRendered = false))
    }

    @Test fun hidesUnderlay_afterFirstFrame() {
        assertFalse(HeroUnderlayPolicy.showStaticUnderlay(firstFrameRendered = true))
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.HeroUnderlayPolicyTest"`
Expected: FAIL.

- [ ] **Step 3: Implement the policy**

```kotlin
package com.aritr.rova.ui.library

/**
 * Decides whether the static [VideoFrame] under-layer renders beneath the autoplay PlayerView.
 *
 * RCA (2026-06-15, device RZCYA1VBQ2H): the static `VideoFrame` (`ContentScale.Crop`) and the
 * PlayerView (`RESIZE_MODE_ZOOM`) fill identically only when the box is ~16:9. In the off-16:9 hero
 * box (~2.27:1) the two layers diverge and leak a grey band on DualShot recordings. The under-layer
 * exists only to mask the black shutter before the first decoded frame — once a frame is rendered it
 * has no job, so dropping it removes the leak source entirely. Framework-free → JVM-tested.
 */
object HeroUnderlayPolicy {
    fun showStaticUnderlay(firstFrameRendered: Boolean): Boolean = !firstFrameRendered
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.HeroUnderlayPolicyTest"`
Expected: PASS.

- [ ] **Step 5: Wire it into `LibraryAutoplayVideo.kt`**

Read the file. The two-layer `Box` (≈80–101) renders `VideoFrame(fallback, Modifier.fillMaxSize())` then the `AndroidView` PlayerView. Add a first-frame flag and gate the under-layer:

1. Add state near the top of the composable:
```kotlin
var firstFrameRendered by remember(uri) { mutableStateOf(false) }
```
2. Attach a listener on the ExoPlayer (where the player is built / in the `DisposableEffect`):
```kotlin
val frameListener = remember(uri) {
    object : Player.Listener {
        override fun onRenderedFirstFrame() { firstFrameRendered = true }
    }
}
// in the build/effect: player.addListener(frameListener)   ; on dispose: player.removeListener(frameListener)
```
   (If a `Player.Listener` is already attached for playback state, add `onRenderedFirstFrame` to it instead of a second listener.)
3. Gate the under-layer:
```kotlin
if (HeroUnderlayPolicy.showStaticUnderlay(firstFrameRendered)) {
    VideoFrame(thumbnail = thumbnail, modifier = Modifier.fillMaxSize())  // exact current call signature
}
```
   Keep `setShutterBackgroundColor(AndroidColor.TRANSPARENT)` so there is no black flash while the under-layer is still present (before first frame).

> **codex-flagged lifecycle rules (do these or the fix breaks on reuse):**
> - **State MUST be keyed on `uri`** — `remember(uri) { mutableStateOf(false) }` (as written). `LibraryAutoplayVideo` is reused by pooled grid/list cards (Slice 4.2), so a recycled instance must NOT inherit a stale `firstFrameRendered=true` (would suppress the static under-layer and show a blank/transparent `PlayerView` for the next video). Keying on `uri` resets it; the `ExoPlayer` is also built `remember(uri)` (fresh per item) so no cross-item bleed.
> - **`onRenderedFirstFrame` re-fires harmlessly** under `REPEAT_MODE_ONE` (state is monotonic-true per item — idempotent set).
> - **Do NOT use `setKeepContentOnPlayerReset(true)`** here: with a fresh per-`uri` player there is no reset to bridge, and keeping a stale last frame across any reuse is worse than the static thumbnail. Leave it default (false); the static under-layer already covers the pre-first-frame gap.

Required imports: `androidx.compose.runtime.{getValue, setValue, mutableStateOf, remember}`, `androidx.media3.common.Player`.

- [ ] **Step 6: Build + JVM suite**

Run: `gradlew.bat :app:assembleDebug` then `gradlew.bat :app:testDebugUnitTest`
Expected: GREEN, 42 gates pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/HeroUnderlayPolicy.kt app/src/test/java/com/aritr/rova/ui/library/HeroUnderlayPolicyTest.kt app/src/main/java/com/aritr/rova/ui/library/components/LibraryAutoplayVideo.kt
git commit -m "fix(library): drop static hero under-layer after first frame — DualShot grey-strip (P2)"
```

### Task P2.4: LibraryHeroCard — showcase overlay restructure

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryHeroCard.kt`
- Modify (if hero footprint grows): `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt`

> Read `LibraryHeroCard.kt` first. Current: outer `Column` (horizontal `screenPadH`, vertical `4.dp`); eyebrow `Text(typography.labelMedium, primary)`; media (`heroHeight`/`heroRadius`, autoplay-vs-static branch, clickable role=Button, `mediaFocusRequester`); then a `Row` with `titleSmall` title `weight(1f)` + Play `FilledIconButton` + Favorite/Share `FilledTonalIconButton` (spacedBy 8.dp). Restructure to a media-dominant overlay. **Preserve:** all injected strings, the `mediaFocusRequester` on the media's own modifier chain (a11y row-23 focus restore), `role = Role.Button` + `contentDescription = playDescription` on the media click target, the favorite icon toggle (`Filled.Star` ↔ `Outlined.StarBorder`), and the `checkA11y*` semantics.

- [ ] **Step 1: Rewrite the composable body to the overlay model**

Target structure (adapt names to the file's actual params):

```kotlin
Box(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = LibraryDimens.screenPadH, vertical = LibraryDimens.cardPadV)
        .height(LibraryDimens.heroHeight)
        .clip(RoundedCornerShape(LibraryDimens.heroRadius))
        .border(
            width = LibraryDimens.cardEdgeWidth,
            color = Color.White.copy(alpha = LibraryDimens.dividerAlpha),
            shape = RoundedCornerShape(LibraryDimens.heroRadius),
        ),
) {
    // 1) Media fills the whole box (autoplay or static), carries the play-click target + focus requester.
    val mediaModifier = Modifier
        .matchParentSize()
        .then(if (mediaFocusRequester != null) Modifier.focusRequester(mediaFocusRequester) else Modifier)
        .clickable(role = Role.Button, onClickLabel = playDescription) { onPlay() }
    if (autoplay) {
        LibraryAutoplayVideo(uri = previewUri, thumbnail = row.thumbnail, modifier = mediaModifier)
    } else {
        VideoFrame(thumbnail = row.thumbnail, modifier = mediaModifier)
    }

    // 2) Bottom caption scrim (tokenized) — fades footage so the caption is legible.
    Box(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .fillMaxHeight(0.55f)
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = LibraryDimens.heroScrimAlpha),
                ),
            ),
    )

    // 3) Subordinate actions — ghost icons, top-right (over a soft local scrim for legibility).
    //    These ARE real accessible controls (Favorite/Share have no other surface on the hero).
    Row(
        Modifier.align(Alignment.TopEnd).padding(LibraryDimens.cardPadV),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (row.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = if (row.favorite) unfavoriteLabel else favoriteLabel,
                tint = Color.White, modifier = Modifier.size(LibraryDimens.actionIcon),
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = shareLabel,
                tint = Color.White, modifier = Modifier.size(LibraryDimens.actionIcon))
        }
    }

    // 4) Caption block — eyebrow (Inter, tracked, caps) + title + meta, overlaid bottom-left.
    Column(Modifier.align(Alignment.BottomStart).padding(LibraryDimens.captionPadH)) {
        Text(text = eyebrow.uppercase(), style = RovaTokens.eyebrow, color = Color.White)
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = Color.White,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (metaLine.isNotBlank()) {
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFeatureSettings = "tnum",       // tabular figures — no width jitter
                ),
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // 5) Primary CTA — single filled Play, bottom-right. VISUAL AFFORDANCE ONLY: the accessible
    //    Play action lives ONCE on the full-media click target (Step 1). codex: a clickable media
    //    target + a labeled Play button = duplicate "Play" a11y actions. So this button is
    //    decorative (clearAndSetSemantics{}) — it still calls onPlay on touch, but TalkBack sees
    //    exactly one Play (the media), which is also the focus-restore target (mediaFocusRequester).
    FilledIconButton(
        onClick = onPlay,
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .padding(LibraryDimens.captionPadH)
            .clearAndSetSemantics {},
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null,
            modifier = Modifier.size(LibraryDimens.actionIcon))
    }
}
```

> **codex a11y/z-order notes (honor exactly):**
> - The scrim Box (2) and caption Column (4) are **draw/text only** — NO `clickable`, `pointerInput`, or `semantics`. They must not steal hits from the media beneath or add traversal nodes. (Caption `Text`s carry their own text semantics — fine, they're informational, not actions.)
> - Hit-testing: media is composed first (bottom), buttons last (top) → the Play/Favorite/Share buttons win touches in their bounds by z-order; the rest of the media area falls through to the media Play target. Correct as written.
> - `eyebrow.uppercase()` is Kotlin's **locale-independent (root)** `uppercase()` — deterministic, no Turkish-i hazard; matches the Record-screen eyebrow call-site convention. Do NOT switch to `java.lang.String.toUpperCase(Locale)`.

Compute `metaLine` from the caller-supplied parts via the P2.1 helper. If the hero already receives `dayLabel`/`timeLabel`/`clipCount`/`durationMs`, build it inside the composable:
```kotlin
val clipCountLabel = if (row.clipCount > 0)
    pluralStringResource(R.plurals.library_hero_clip_count, row.clipCount, row.clipCount) else ""
val metaLine = HeroMetaFormatter.format(dayLabel, timeLabel, clipCountLabel,
    if (row.durationMs > 0) SmartTitle.durationLabel(row.durationMs) else "")
```
(If the hero's current params don't expose day/time/clipCount, thread them from `LibraryScreen.renderHero` — they already exist on the `LibraryRow`/`VideoItem`. Use the same fields the list-row meta already reads.)

Required imports to add: `Box`, `Brush`, `Alignment`, `IconButton`, `FilledIconButton`, `Icons.Filled.{PlayArrow, Share, Star}`, `Icons.Outlined.StarBorder`, `RoundedCornerShape`, `clip`, `border`, `background`, `clearAndSetSemantics`, `pluralStringResource`, `RovaTokens`, `HeroMetaFormatter`, `SmartTitle`, `TextOverflow`, `Color`.

- [ ] **Step 2: If `heroHeight` grew (176→200), confirm `LibraryScreen` hero insertion still spans full width**

`LibraryScreen.renderHero` (≈423–442) and the grid/list insertions (≈599/643) set no height wrapper — height lives in the card. No change needed unless the grid hero item needs `maxLineSpan`; it already uses a full-span key. Verify visually in smoke.

- [ ] **Step 3: Build + JVM suite**

Run: `gradlew.bat :app:assembleDebug` then `gradlew.bat :app:testDebugUnitTest`
Expected: GREEN, 42 gates pass (esp. `checkA11yClickableHasRole`, `checkNoHardcodedUiStrings`, `checkRecordSurfaceNoBlur`, `checkLibraryNoManifestWrite`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryHeroCard.kt app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt
git commit -m "polish(library): hero showcase overlay — glass edge, eyebrow token, one Play CTA, scrim (P2)"
```

### Task P2.5: Device smoke — hero + DualShot fix (incl. reduce-motion)

- [ ] **Step 1: Install**

```
gradlew.bat :app:assembleDebug
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] **Step 2: Verify (reduce-motion OFF — autoplay path)**

Open Library with the DualShot recording as the hero. Confirm: (a) **no grey horizontal strip** on the hero (the H1 fix); (b) eyebrow reads as tracked Inter caps; (c) one prominent Play, Favorite/Share read as subordinate; (d) caption legible over bright footage; (e) tabular meta line doesn't jitter.

- [ ] **Step 3: Verify (reduce-motion ON — static path)**

Settings → enable "Remove animations". Re-open Library. Confirm the hero is the static `VideoFrame` (no autoplay) and **still has no strip** (RCA: static frame is clean). Overlay caption/CTA still correct.

- [ ] **Step 4: Owner GO before P3.**

---

# Slice P3 — Grid/list card polish + session identity

**Goal:** Make the grid card glass-consistent (frame edge, not glass-on-thumbnail), replace the hard `2dp primary` selection border with a soft glass ring + check chip, tokenize the remaining scrim/padding literals, bump the list-row thumbnail for prominence, **and make every card/row read as a recording session** (time · clip count · duration · size · status). Adds `clipCount` to the row model.

> **Card rendering = STATIC posters in this slice.** Do NOT wire card autoplay here — the autoplay demotion + opt-in setting is Slice P7. Cards render the static `VideoFrame` (the reduce-motion path) by default; P7 re-enables preview behind a setting. The hero (P2) keeps autoplay regardless.

### Task P3.0: Add `clipCount` to the row model + mapper

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryRow.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/library/LibraryRowMapper.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryRowMapperTest.kt` (extend if present)

- [ ] **Step 1: Add the field**

In `LibraryRow` add `val clipCount: Int` (after `durationMs`/`sizeBytes`). KDoc: "number of clips/segments in the session (manifest segment count; 0 for legacy single-file rows)."

- [ ] **Step 2: Populate it in `LibraryRowMapper`**

Read the mapper. It builds the row from `VideoItem` + `SessionManifest` + sidecar. Set `clipCount` from the manifest's segment count (the same source the player's `SegmentedTimeline` uses — find the existing segment-count field; do NOT invent one). For legacy file-scan rows with no manifest, `clipCount = 0` (the caption then omits the clip-count part — see P3.A formatter).

> **codex gotcha:** count **persisted/playable** segments, not raw attempted/temp/failed ones. Use whatever count the `SegmentedTimeline` renders (the clips the user can actually play), so the chip matches the player. If the manifest distinguishes attempted vs completed segments, take the completed/persisted figure.

- [ ] **Step 3: Update any `LibraryRow(...)` construction sites + tests**

Grep for `LibraryRow(` — fix every constructor call (tests + mapper) to pass `clipCount`. Run the suite.

- [ ] **Step 4: Build + JVM suite + commit**

```bash
gradlew.bat :app:assembleDebug && gradlew.bat :app:testDebugUnitTest
git add app/src/main/java/com/aritr/rova/ui/library/LibraryRow.kt app/src/main/java/com/aritr/rova/ui/library/LibraryRowMapper.kt app/src/test/java/com/aritr/rova/ui/library/
git commit -m "polish(library): add clipCount to LibraryRow for session identity (P3)"
```

### Task P3.A: Session-caption formatter (pure) + test

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/SessionCaption.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/SessionCaptionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCaptionTest {
    @Test fun listMeta_joinsAllParts() {
        assertEquals(
            "12:34 · 12 clips · 1m 04s · 84 MB",
            SessionCaption.listMeta(time = "12:34", clipCountLabel = "12 clips", durationLabel = "1m 04s", sizeLabel = "84 MB"),
        )
    }
    @Test fun listMeta_dropsBlankClipCount_forLegacyRow() {
        assertEquals(
            "12:34 · 0m 22s · 19 MB",
            SessionCaption.listMeta(time = "12:34", clipCountLabel = "", durationLabel = "0m 22s", sizeLabel = "19 MB"),
        )
    }
    @Test fun gridCaption_isTimeOnly() {
        assertEquals("10:02", SessionCaption.gridCaption(time = "10:02"))
    }
}
```

- [ ] **Step 2: Run — expect FAIL.** `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.SessionCaptionTest"`

- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.library

/**
 * Pure caption builders so a card/row reads as a recording SESSION, not a random thumbnail.
 * List rows show the dense meta (time · clips · duration · size); grid tiles stay terse (time only —
 * the clip-count chip, duration badge, and status badge are separate overlays). Blank parts drop so
 * legacy single-file rows (clipCount 0) collapse cleanly. Framework-free → JVM-tested.
 */
object SessionCaption {
    private const val SEP = " · "
    fun listMeta(time: String, clipCountLabel: String, durationLabel: String, sizeLabel: String): String =
        listOf(time, clipCountLabel, durationLabel, sizeLabel).filter { it.isNotBlank() }.joinToString(SEP)
    fun gridCaption(time: String): String = time
}
```

- [ ] **Step 4: Run — expect PASS.** Commit.

```bash
git add app/src/main/java/com/aritr/rova/ui/library/SessionCaption.kt app/src/test/java/com/aritr/rova/ui/library/SessionCaptionTest.kt
git commit -m "polish(library): SessionCaption pure formatter (P3)"
```

> The clip-count plural reuses `R.plurals.library_hero_clip_count` (P2.2). The size label reuses the existing `StorageFormat`/`SmartTitle` size helper (grep — do NOT add a second byte formatter). The status comes from the existing `LibraryBadge` (recovered/interrupted).

### Task P3.1: LibraryGridCard — glass-aware frame + soft selection ring

**Files:** `app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt`

> Read first. Current card surface (≈65–81): opaque `Box` with `aspectRatio(16f/9f)`, `clip(cardRadius)`, `combinedClickable`, and a selection `border(2.dp, MaterialTheme.colorScheme.primary)`. Check overlay (≈100–119): `Black@0.32` circle chip with `CheckCircle`/`RadioButtonUnchecked`.

- [ ] **Step 1: Replace the selection border with a soft glass ring**

Swap the hardcoded selection border:
```kotlin
// before:
.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(LibraryDimens.cardRadius))
// after — soft, glass-consistent, only when selected:
.then(
    if (selected) Modifier.border(
        width = LibraryDimens.selectionEdgeWidth,
        color = Color.White.copy(alpha = LibraryDimens.selectionEdgeAlpha),
        shape = RoundedCornerShape(LibraryDimens.cardRadius),
    ) else Modifier
)
```

- [ ] **Step 2: Add a permanent glass-consistent frame edge (all cards, not just selected)**

On the card `Box` modifier (after `clip`):
```kotlin
.border(
    width = LibraryDimens.cardEdgeWidth,
    color = Color.White.copy(alpha = LibraryDimens.dividerAlpha),
    shape = RoundedCornerShape(LibraryDimens.cardRadius),
)
```
This gives the opaque tile the same 1dp-white hairline the glass surfaces have (the "glass-aware" treatment) without putting blur/glass over the video. Selection ring (Step 1) stacks on top at a higher alpha.

> **codex note:** the **check chip is the authoritative selected-state carrier** (the soft `white@0.30` ring can read faint over bright thumbnails). The ring is reinforcement, not the sole signal — a11y already uses `stateDescription` (never color-only). At P3.3 smoke, if the ring is too subtle, bump `selectionEdgeAlpha` (0.30→0.40) or tint it `primary@0.50` rather than white; the check chip stays regardless.

- [ ] **Step 3: Tokenize the check-chip scrim alpha**

The check chip's `Black.copy(alpha = 0.32f)` background → keep as a local readable constant or reuse `heroScrimAlpha`? Use a dedicated readable value; leave `0.32f` if no token fits (it's a chip backing, not a card scrim). Tokenize the chip **padding** (`6.dp` → `LibraryDimens.cardPadV`) only.

- [ ] **Step 3b: Session-identity overlays on the tile (NOT in selection mode)**

The grid tile must read as a session. Add/confirm these overlays (use `LibraryBadges` styling already in the file + the new `LibraryDimens`):
- **Clip-count chip** top-left: `pluralStringResource(R.plurals.library_hero_clip_count, row.clipCount, row.clipCount)` — shown only when `row.clipCount > 1` (a 1-clip session needs no chip). Tokenized scrim background + `pillRadius`.
- **Status badge** top-right (only when `row.badge != null`): Recovered/Interrupted, existing `statusBadgeLabel`.
- **Duration badge** bottom-right: `SmartTitle.durationLabel(row.durationMs)` over a small dark backing.
- **Caption** bottom-left: `SessionCaption.gridCaption(time)` (terse — time only; the chips carry the rest). Keep the existing `CaptionBar` scrim.
Hide all of these in selection mode (the check chip + ring own the tile then) — matches the current "badges only outside selection mode" rule.

- [ ] **Step 4: Build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 42 gates green (esp. `checkA11yTargetSizeToken`, `checkGlassSurfaceRoleUsage`, `checkRecordSurfaceNoBlur`, `checkNoHardcodedUiStrings`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryGridCard.kt
git commit -m "polish(library): grid card glass edge + soft selection ring + session identity (P3)"
```

### Task P3.2: LibraryListRow — tokenized spacing + larger thumbnail

**Files:** `app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt`

> Already glass (`GlassSurface(role = GlassRole.Card)`). Just tighten the scale.

- [ ] **Step 1: Bump the thumbnail + tokenize inner pads**

Thumbnail `96×54` → `112×63` (still exact 16:9, more prominent). Thumbnail corner `8.dp` → `LibraryDimens.pillRadius` (11dp) for consistency. Inner row pad `8.dp` stays (glass content pad); column start pad `12.dp` → keep. Confirm outer vertical already `LibraryDimens.cardPadV` (from P1.2 Step 3). Overlay the **duration badge** (`SmartTitle.durationLabel`) bottom-right on the thumbnail (matches grid).

- [ ] **Step 2: Rich session meta line**

Replace the current `bodySmall` meta with `SessionCaption.listMeta(...)`:
```kotlin
val meta = SessionCaption.listMeta(
    time = row.dateLabel,                                  // or the time-of-day slice the row already exposes
    clipCountLabel = if (row.clipCount > 1)
        pluralStringResource(R.plurals.library_hero_clip_count, row.clipCount, row.clipCount) else "",
    durationLabel = if (row.durationMs > 0) SmartTitle.durationLabel(row.durationMs) else "",
    sizeLabel = StorageFormat.format(row.sizeBytes),       // reuse existing size helper — grep, don't add one
)
```
Prefix the title with a small status dot (`LibraryDimens.statusDotSize`) when `row.badge != null` (green = recovered, amber = interrupted) so state is visible without opening — a11y already carries the badge label.

- [ ] **Step 3: Build + smoke-compile**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL, `checkNoHardcodedUiStrings` green.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryListRow.kt
git commit -m "polish(library): list-row larger thumbnail + session meta line (P3)"
```

### Task P3.3: Device smoke (grid + list, selection)

- [ ] **Step 1: Install + verify**

Install. Confirm: grid tiles have a subtle frame edge; **selecting a tile shows a soft ring + check chip (no hard blue border)**; list rows read denser/more prominent thumbnails. Toggle grid/list. **Owner GO before P4.**

---

# Slice P4 — Discovery bar + states

**Goal:** A pure state resolver drives four distinct states (Loading / Empty / Search-empty / Content); loading becomes a reduce-motion-gated skeleton; empty/search-empty/error are on-brand with clear copy; filter chips read quieter and glass-consistent.

### Task P4.1: LibraryStatePolicy (pure) + test

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/LibraryStatePolicy.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/LibraryStatePolicyTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryStatePolicyTest {
    @Test fun loading_beforeFirstLoad() {
        assertEquals(LibraryStateKind.Loading,
            LibraryStatePolicy.resolve(hasLoaded = false, isEmpty = true, hasActiveQuery = false))
    }
    @Test fun content_whenRowsPresent() {
        assertEquals(LibraryStateKind.Content,
            LibraryStatePolicy.resolve(hasLoaded = true, isEmpty = false, hasActiveQuery = true))
    }
    @Test fun emptyLibrary_whenLoadedAndNoQuery() {
        assertEquals(LibraryStateKind.Empty,
            LibraryStatePolicy.resolve(hasLoaded = true, isEmpty = true, hasActiveQuery = false))
    }
    @Test fun searchEmpty_whenLoadedEmptyWithQuery() {
        assertEquals(LibraryStateKind.SearchEmpty,
            LibraryStatePolicy.resolve(hasLoaded = true, isEmpty = true, hasActiveQuery = true))
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryStatePolicyTest"`

- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.library

/** Which Library body state to render. (Error is intentionally NOT a member — there is no VM error
 *  signal yet; `LibraryError` is built in P4.3 but stays unrouted until a signal exists, so this pure
 *  resolver remains total over its three boolean inputs. codex: don't let Error drift into ad-hoc
 *  branching outside the policy — when an error signal lands, add it here as a 4th input + member.) */
enum class LibraryStateKind { Loading, Empty, SearchEmpty, Content }

/**
 * Pure resolver for the Library body state. Separates "never loaded yet" (spinner/skeleton) from
 * "loaded but no rows" (empty), and within empty, distinguishes a filter/search miss (SearchEmpty,
 * keeps the discovery bar + offers clear-filters) from a genuinely empty library (Empty, record CTA).
 * Framework-free → JVM-tested.
 */
object LibraryStatePolicy {
    fun resolve(hasLoaded: Boolean, isEmpty: Boolean, hasActiveQuery: Boolean): LibraryStateKind = when {
        !hasLoaded -> LibraryStateKind.Loading
        !isEmpty -> LibraryStateKind.Content
        hasActiveQuery -> LibraryStateKind.SearchEmpty
        else -> LibraryStateKind.Empty
    }
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.LibraryStatePolicyTest"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryStatePolicy.kt app/src/test/java/com/aritr/rova/ui/library/LibraryStatePolicyTest.kt
git commit -m "polish(library): LibraryStatePolicy pure state resolver (P4)"
```

### Task P4.2: State copy strings (en + es)

**Files:** `values/strings.xml`, `values-es/strings.xml`

- [ ] **Step 1: Add to `values/strings.xml`** (verify exact existing empty-state keys first; add only the missing ones)

```xml
<string name="library_search_empty_title">No matches</string>
<string name="library_search_empty_body">No recordings match your search or filters.</string>
<string name="library_search_empty_cta">Clear filters</string>
<string name="library_error_title">Couldn\'t load your library</string>
<string name="library_error_body">Something went wrong reading your recordings. Pull to retry.</string>
```

- [ ] **Step 2: Add Spanish to `values-es/strings.xml`**

```xml
<string name="library_search_empty_title">Sin coincidencias</string>
<string name="library_search_empty_body">Ninguna grabación coincide con tu búsqueda o filtros.</string>
<string name="library_search_empty_cta">Borrar filtros</string>
<string name="library_error_title">No se pudo cargar tu biblioteca</string>
<string name="library_error_body">Ocurrió un error al leer tus grabaciones. Desliza para reintentar.</string>
```

- [ ] **Step 3: Build + commit**

```bash
gradlew.bat :app:assembleDebug
git add app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "polish(library): search-empty + error state strings (en+es) (P4)"
```

### Task P4.3: LibraryStates — skeleton loading + search-empty + error

**Files:** `app/src/main/java/com/aritr/rova/ui/library/components/LibraryStates.kt`

- [ ] **Step 1: Add a reduce-motion-gated skeleton loading composable**

Replace `LibraryLoading`'s bare `CircularProgressIndicator` with a skeleton grid of placeholder cards. Shimmer ONLY when motion is allowed (`checkA11yAnimationGated` requires the gate):
```kotlin
@Composable
fun LibraryLoading(modifier: Modifier = Modifier) {
    val reduce = rememberReduceMotion()
    val shimmerAlpha = if (reduce) 0.12f else RovaAnimations.pulsingOpacity(
        durationMillis = 900, minAlpha = 0.06f, maxAlpha = 0.16f,
    )
    Column(modifier.padding(horizontal = LibraryDimens.screenPadH)) {
        repeat(4) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = LibraryDimens.cardPadV)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(LibraryDimens.cardRadius))
                    .background(Color.White.copy(alpha = shimmerAlpha)),
            )
        }
    }
}
```
(`pulsingOpacity` already self-gates reduce-motion internally; the explicit `reduce` branch keeps a static visible placeholder regardless — both paths are safe for the gate.)

- [ ] **Step 2: Add `LibrarySearchEmpty` and `LibraryError` composables**

```kotlin
@Composable
fun LibrarySearchEmpty(onClearFilters: () -> Unit, modifier: Modifier = Modifier) =
    LibraryEmptyScaffold(
        icon = Icons.Outlined.SearchOff,
        title = stringResource(R.string.library_search_empty_title),
        body = stringResource(R.string.library_search_empty_body),
        ctaLabel = stringResource(R.string.library_search_empty_cta),
        onCta = onClearFilters, modifier = modifier,
    )

@Composable
fun LibraryError(modifier: Modifier = Modifier) =
    LibraryEmptyScaffold(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(R.string.library_error_title),
        body = stringResource(R.string.library_error_body),
        ctaLabel = null, onCta = {}, modifier = modifier,
    )
```
Refactor the existing `LibraryEmpty` body into a private `LibraryEmptyScaffold(icon, title, body, action)` where `action` is an optional slot. **Empty library uses a QUIET text link, NOT a filled CTA** (M3 empty-state guidance — the tagline must not read as a tappable onboarding button):
```kotlin
@Composable
fun LibraryEmpty(onGoToRecord: () -> Unit, modifier: Modifier = Modifier) =
    LibraryEmptyScaffold(
        icon = Icons.Outlined.VideoLibrary,
        title = stringResource(R.string.library_empty_title),       // "No recordings yet"
        body = stringResource(R.string.library_empty_body),         // "Your recordings will appear here once you capture one."
        action = {
            TextButton(onClick = onGoToRecord) {                    // quiet link, NOT FilledButton
                Text(stringResource(R.string.library_empty_go_to_record))   // "Go to Record"
            }
        },
        modifier = modifier,
    )
```
`LibrarySearchEmpty` keeps a `TextButton` "Clear filters" (an action on a *populated* library, fine to be a touch stronger but still tonal/text, not filled). `LibraryError` has no action. Verify the existing `library_empty_*` string copy is non-shouty (no "!", not phrased as a command); adjust copy + add `library_empty_go_to_record` (en+es) if missing.

- [ ] **Step 3: Build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL, 42 gates green (esp. `checkA11yAnimationGated`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryStates.kt
git commit -m "polish(library): skeleton loading + search-empty + error states (P4)"
```

### Task P4.4: LibraryScreen — route the four state kinds

**Files:** `app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt`

> Read the current body-state branch (it currently does `if (loading) LibraryLoading else if (hero==null && collection.isEmpty()) LibraryEmpty(cta=null) …`).

- [ ] **Step 1: Replace the ad-hoc branch with the policy**

```kotlin
val stateKind = LibraryStatePolicy.resolve(
    hasLoaded = ui.hasLoaded,                                  // existing latch from HistoryViewModel
    isEmpty = hero == null && collection.isEmpty(),
    hasActiveQuery = filter.search.isNotBlank() || filter.favoritesOnly || filter.topology != null,
)
when (stateKind) {
    LibraryStateKind.Loading -> LibraryLoading()
    LibraryStateKind.Empty -> LibraryEmpty(onGoToRecord = onNavigateToRecord)   // QUIET text link, not a filled CTA
    LibraryStateKind.SearchEmpty -> LibrarySearchEmpty(onClearFilters = viewModel::clearFilters)
    LibraryStateKind.Content -> { /* existing grid/list lazy content */ }
}
```
Keep the discovery bar (search + chips) visible above the body in BOTH `Content` and `SearchEmpty` (so the user can clear/adjust). `Error` wiring is optional this slice — if `HistoryViewModel` has no error signal, leave `LibraryError` defined for a later wire and do not fabricate one.

- [ ] **Step 2: Build + full JVM suite**

Run: `gradlew.bat :app:assembleDebug` then `gradlew.bat :app:testDebugUnitTest`
Expected: GREEN, 42 gates.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt
git commit -m "polish(library): route Loading/Empty/SearchEmpty via LibraryStatePolicy (P4)"
```

### Task P4.5: LibraryFilterChips — quieter, glass-consistent

**Files:** `app/src/main/java/com/aritr/rova/ui/library/components/LibraryFilterChips.kt`

- [ ] **Step 1: Soften the chip styling**

Current `FilterChip` only overrides `selectedContainerColor`/`selectedLabelColor`. Quiet it: unselected chips get a transparent container + the glass hairline border (`Color.White.copy(alpha = LibraryDimens.dividerAlpha)`); selected stays the tonal `primaryContainer`/`onPrimaryContainer` but at a lower visual weight (no elevation). Use `FilterChipDefaults.filterChipColors(containerColor = Color.Transparent, …)` + `FilterChipDefaults.filterChipBorder(...)`. **Preserve** the `clearAndSetSemantics` (cd + role + selected) — selection must never be color-only.

- [ ] **Step 2: Build + commit**

```bash
gradlew.bat :app:assembleDebug
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryFilterChips.kt
git commit -m "polish(library): quieter glass-consistent filter chips (P4)"
```

### Task P4.6: Device smoke (states, incl. reduce-motion)

- [ ] **Step 1: Install + verify**

Install. Verify: (a) cold open shows skeleton cards (reduce-motion OFF = subtle shimmer; ON = static placeholders); (b) search with no match → "No matches" + Clear filters (discovery bar still visible); (c) filter to `★Favorites` with none → search-empty; (d) chips read quieter, don't compete with hero; (e) genuinely empty library → record CTA. **Owner GO before P5.**

---

# Slice P5 — Interaction & motion consistency

**Goal:** Add Record-consistent press feedback (reduce-motion gated) to hero/grid/list tap targets, and verify the Library glass surfaces resolve to the Record glass alphas (`0.40` fill / `0.09` edge). No glass-value change is expected (Library glass already routes through `GlassRole`); this slice locks it.

### Task P5.1: PressFeedback (pure) + test

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/PressFeedback.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/PressFeedbackTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PressFeedbackTest {
    @Test fun pressed_scalesDown() {
        assertEquals(0.97f, PressFeedback.targetScale(pressed = true, reduceMotion = false), 0.0001f)
    }
    @Test fun released_isUnity() {
        assertEquals(1f, PressFeedback.targetScale(pressed = false, reduceMotion = false), 0.0001f)
    }
    @Test fun reduceMotion_neverScales() {
        assertEquals(1f, PressFeedback.targetScale(pressed = true, reduceMotion = true), 0.0001f)
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.PressFeedbackTest"`

- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.library

/**
 * Pure press-feedback scale. Record-consistent subtle scale-down on press; held at unity under
 * reduce-motion (WCAG 2.2 AA SC 2.3.3, ADR-0020). Framework-free → JVM-tested; the Compose
 * Modifier that animates toward this target lives in RovaAnimations and is reduce-motion gated.
 */
object PressFeedback {
    const val PRESSED_SCALE = 0.97f
    fun targetScale(pressed: Boolean, reduceMotion: Boolean): Float =
        if (pressed && !reduceMotion) PRESSED_SCALE else 1f
}
```

- [ ] **Step 4: Run — expect PASS**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.PressFeedbackTest"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/PressFeedback.kt app/src/test/java/com/aritr/rova/ui/library/PressFeedbackTest.kt
git commit -m "polish(library): PressFeedback pure helper (reduce-motion gated) (P5)"
```

### Task P5.2: `Modifier.pressScale` in RovaAnimations + apply to cards/hero

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/components/RovaAnimations.kt`
- Modify: `LibraryGridCard.kt`, `LibraryListRow.kt`, `LibraryHeroCard.kt`

- [ ] **Step 1: Add the gated Modifier**

```kotlin
fun Modifier.pressScale(interactionSource: InteractionSource): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduce = rememberReduceMotion()
    val target = PressFeedback.targetScale(pressed, reduce)
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = RecordChromeTokens.elementSpinMs),
        label = "pressScale",
    )
    this.graphicsLayer { scaleX = scale; scaleY = scale }
}
```
Imports: `collectIsPressedAsState`, `InteractionSource`, `animateFloatAsState`, `graphicsLayer`, `PressFeedback`, `RecordChromeTokens`, `rememberReduceMotion`.

- [ ] **Step 2: Apply to the tap targets**

On `LibraryGridCard`/`LibraryListRow`/hero media: hoist the `combinedClickable`/`clickable` `interactionSource` (create `remember { MutableInteractionSource() }`, pass it to the click modifier AND `.pressScale(it)`). Reuse the existing interaction source the clickable already creates if present.

> **codex note:** apply `.pressScale(...)` to the card's **inner content/surface layer, NOT the Lazy item root**. Scaling the lazy item root can clip against item bounds and jitter neighboring items during layout. The scale goes on the same node that carries the visual card (the `Box`/`GlassSurface` content), inside any outer padding. Keep ripple/indication on the clickable unchanged.

- [ ] **Step 3: Build + JVM suite**

Run: `gradlew.bat :app:assembleDebug` then `gradlew.bat :app:testDebugUnitTest`
Expected: GREEN, 42 gates (esp. `checkA11yAnimationGated` — `pressScale` is gated via `rememberReduceMotion`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/components/RovaAnimations.kt app/src/main/java/com/aritr/rova/ui/library/
git commit -m "polish(library): Record-consistent press feedback on cards + hero (P5)"
```

### Task P5.3: Glass-alpha verification (doc step)

- [ ] **Step 1: Confirm the Library glass surfaces resolve to Record alphas**

Read `LibraryTopBar`/`LibraryBatchBar`/`LibrarySelectionTopBar`/`LibraryListRow` — each wraps content in `GlassSurface(role = GlassRole.NavBar | Card)`. Confirm none passes a custom fill/edge override. The resolved values on Library routes come from the active palette's `glassTint`/`edge`. Library is NOT a pinned-dark route, so it uses the themed palette (`DarkGlass`/`DarkEdge ~0.10`), not `NeutralDarkRecordPalette`. **Decision point for the owner:** if the Library should match Record's *pinned* `0.40 / 0.09` exactly, the Library routes would need pinning (out of polish scope — flag, don't change). Otherwise the themed glass is correct-by-design and consistent within the app's theming. **Record the finding in the commit message / handoff; make no code change unless the owner asks to pin.**

- [ ] **Step 2: Sort/search sheets glass (optional, low-risk)**

`LibrarySortSheet` KDoc says "glass" but is a default `ModalBottomSheet`; `LibrarySearchField` is a plain `OutlinedTextField`. If time permits within P5, give the sort sheet a `GlassSurface(role = GlassRole.BottomSheet)` container and the search field a glass-consistent container. If it risks scope creep, defer to a follow-up and note it. Keep the existing a11y (`stateDescription`, `Role.Button`, IME Search).

- [ ] **Step 3: Commit any doc/notes**

```bash
git add -A
git commit -m "polish(library): verify glass alphas (0.40 fill / 0.09 edge) — Library routes themed-glass by design (P5)"
```

### Task P5.4: Final device smoke (whole polish pass)

- [ ] **Step 1: Install + full pass**

Install. Walk the whole Library: hero (DualShot clean, showcase), grid/list cards (glass edge, press feedback, soft selection), discovery (quiet chips), states (skeleton/empty/search-empty), reduce-motion ON for the full surface. Confirm no regressions vs the functional Slices 1–5. **Owner GO = polish pass complete.**

- [ ] **Step 2: Release-build smoke (autoplay path under R8)**

```
gradlew.bat :app:assembleRelease
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/release/app-release.apk
```
Smoke the hero + card ExoPlayer autoplay on the **release** APK specifically (R8 + Media3 ProGuard) before any merge-train.

---

# Slice P6 — Storage/usage summary line

**Goal:** A compact usage line under the top bar — `128 sessions · 1,842 clips · 18.6 GB` + a fill bar — so the user can judge footprint and prune. Derived from the already-built row list (no new disk read).

### Task P6.1: UsageAggregator + join (pure) + test

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/library/StorageSummary.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/library/StorageSummaryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSummaryTest {
    @Test fun aggregate_countsSessionsClipsBytes() {
        val rows = listOf(
            row(clipCount = 12, sizeBytes = 84_000_000),
            row(clipCount = 1, sizeBytes = 6_000_000),
            row(clipCount = 0, sizeBytes = 19_000_000),   // legacy row: counts as 1 clip
        )
        val s = UsageAggregator.aggregate(rows)
        assertEquals(3, s.sessionCount)
        assertEquals(14, s.clipCount)                       // 12 + 1 + max(1,0)
        assertEquals(109_000_000L, s.totalBytes)
    }
    @Test fun join_dropsBlanks() {
        assertEquals("3 sessions · 14 clips · 104 MB",
            StorageSummaryFormatter.join("3 sessions", "14 clips", "104 MB"))
    }
    private fun row(clipCount: Int, sizeBytes: Long) = LibraryRow(
        stableKey = "k$sizeBytes", title = "t", dateLabel = "d", dateMillis = 0L,
        durationMs = 0L, sizeBytes = sizeBytes, clipCount = clipCount,
        topology = com.aritr.rova.data.CaptureTopology.SINGLE, badge = null, favorite = false,
    )
}
```
(Match the real `LibraryRow` constructor + `CaptureTopology` value — adjust if names differ.)

- [ ] **Step 2: Run — expect FAIL.** `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.library.StorageSummaryTest"`

- [ ] **Step 3: Implement**

```kotlin
package com.aritr.rova.ui.library

/** Aggregated footprint over the visible Library rows. */
data class UsageSummary(val sessionCount: Int, val clipCount: Int, val totalBytes: Long)

/** Pure: fold the row list into a footprint. A legacy row (clipCount 0) counts as 1 clip. */
object UsageAggregator {
    fun aggregate(rows: List<LibraryRow>): UsageSummary = UsageSummary(
        sessionCount = rows.size,
        clipCount = rows.sumOf { it.clipCount.coerceAtLeast(1) },
        totalBytes = rows.sumOf { it.sizeBytes },
    )
}

/** Pure join for the usage line — parts arrive already localized (plurals + StorageFormat). */
object StorageSummaryFormatter {
    fun join(sessionsLabel: String, clipsLabel: String, sizeLabel: String): String =
        listOf(sessionsLabel, clipsLabel, sizeLabel).filter { it.isNotBlank() }.joinToString(" · ")
}
```

- [ ] **Step 4: Run — expect PASS.** Commit.

```bash
git add app/src/main/java/com/aritr/rova/ui/library/StorageSummary.kt app/src/test/java/com/aritr/rova/ui/library/StorageSummaryTest.kt
git commit -m "polish(library): UsageAggregator + StorageSummaryFormatter pure helpers (P6)"
```

### Task P6.2: Usage strings (en + es)

**Files:** `values/strings.xml`, `values-es/strings.xml`

- [ ] **Step 1: Add plurals + size to `values/strings.xml`**

```xml
<plurals name="library_usage_sessions">
    <item quantity="one">%d session</item>
    <item quantity="other">%d sessions</item>
</plurals>
<plurals name="library_usage_clips">
    <item quantity="one">%d clip</item>
    <item quantity="other">%d clips</item>
</plurals>
<string name="library_usage_cd">Library footprint: %1$s</string>
```

- [ ] **Step 2: Spanish in `values-es/strings.xml`**

```xml
<plurals name="library_usage_sessions">
    <item quantity="one">%d sesión</item>
    <item quantity="other">%d sesiones</item>
</plurals>
<plurals name="library_usage_clips">
    <item quantity="one">%d clip</item>
    <item quantity="other">%d clips</item>
</plurals>
<string name="library_usage_cd">Espacio de la biblioteca: %1$s</string>
```

- [ ] **Step 3: Build + commit.** `gradlew.bat :app:assembleDebug`

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "polish(library): usage-line strings (en+es) (P6)"
```

### Task P6.3: Wire usage into the VM state + render the line

**Files:** `LibraryUiState.kt`, `HistoryViewModel.kt`, `ui/library/components/LibraryUsageLine.kt` (create), `LibraryScreen.kt`

- [ ] **Step 1: Carry the summary in `LibraryUiState`**

Add `val usage: UsageSummary` to `LibraryUiState`. In `HistoryViewModel.libraryUiState` combine, compute `UsageAggregator.aggregate(rows)` over the SAME full row list it already builds (before the visible/pending-delete filter, or after — choose "all rows" so the footprint reflects the whole library, not the filtered view; document the choice). This is a pure in-memory fold inside the existing combine — no new flow, no extra disk read.

- [ ] **Step 2: Create `LibraryUsageLine`**

```kotlin
@Composable
fun LibraryUsageLine(usage: UsageSummary, modifier: Modifier = Modifier) {
    val sessions = pluralStringResource(R.plurals.library_usage_sessions, usage.sessionCount, usage.sessionCount)
    val clips = pluralStringResource(R.plurals.library_usage_clips, usage.clipCount, usage.clipCount)
    val size = StorageFormat.format(usage.totalBytes)              // reuse existing size helper
    val text = StorageSummaryFormatter.join(sessions, clips, size)
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(horizontal = LibraryDimens.screenPadH, vertical = 4.dp)
            .semantics { contentDescription = /* library_usage_cd, text */ },
    )
}
```
(Add a thin fill-bar Row beneath if a storage cap is known; if not, ship text-only — don't fabricate a % with no denominator. The mockup's bar is decorative; omit it unless a real total-capacity figure is available.)

- [ ] **Step 3: Render it in `LibraryScreen`** directly under the top bar, above the discovery bar, in `Content` and `SearchEmpty` states (hide on `Loading`/`Empty`). Build + suite.

- [ ] **Step 4: Commit**

```bash
gradlew.bat :app:assembleDebug && gradlew.bat :app:testDebugUnitTest
git add app/src/main/java/com/aritr/rova/ui/library/ app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt
git commit -m "polish(library): storage/usage summary line (P6)"
```

### Task P6.4: Device smoke

- [ ] Install; confirm the usage line reads correctly (counts match the library, size formats sanely, tabular figures don't jitter), TalkBack reads the `library_usage_cd` description. **Owner GO before P7.**

---

# Slice P7 — Card-preview opt-in setting (autoplay demotion)

**Goal:** Library grid/list cards render **static posters by default**; muted card preview becomes an **opt-in Setting, default OFF**, reduce-motion still gated. The hero keeps autoplay regardless.

### Task P7.1: `libraryCardPreview` pref

**Files:** `app/src/main/java/com/aritr/rova/data/RovaSettings.kt`

- [ ] **Step 1: Add the pref** (mirror `preferFrontCamera`):

```kotlin
/** Polish P7 — opt-in muted autoplay PREVIEW for Library grid/list cards. Default OFF: scroll-triggered
 *  card autoplay is a known anti-pattern (battery/distraction); the hero showcase autoplays regardless.
 *  Reduce-motion still suppresses all card preview when this is on. */
var libraryCardPreview: Boolean
    get() = prefs.getBoolean("library_card_preview", false)
    set(value) = prefs.edit { putBoolean("library_card_preview", value) }
```

- [ ] **Step 2: Build + commit.**

```bash
gradlew.bat :app:assembleDebug
git add app/src/main/java/com/aritr/rova/data/RovaSettings.kt
git commit -m "polish(library): libraryCardPreview pref, default off (P7)"
```

### Task P7.2: Settings toggle (en + es)

**Files:** `ui/screens/SettingsScreen.kt` (+ its ViewModel), `values/strings.xml`, `values-es/strings.xml`

- [ ] **Step 1: Strings**

`values/strings.xml`:
```xml
<string name="settings_library_card_preview_title">Preview recordings while browsing</string>
<string name="settings_library_card_preview_summary">Autoplay muted previews on library cards. Off by default to save battery.</string>
```
`values-es/strings.xml`:
```xml
<string name="settings_library_card_preview_title">Previsualizar grabaciones al explorar</string>
<string name="settings_library_card_preview_summary">Reproduce vistas previas silenciadas en las tarjetas. Desactivado por defecto para ahorrar batería.</string>
```

- [ ] **Step 2: Add the toggle row**

Read `SettingsScreen.kt` + its VM. Follow the EXISTING `RovaSettings`-backed switch-row pattern (the same one B1 used for recording defaults). Add a switch row under a "Library" group: title/summary from the strings above, bound to `settingsViewModel.libraryCardPreview` (StateFlow seeded from `RovaSettings.libraryCardPreview`; setter writes the pref). If `SettingsViewModel` resumes/reseeds (dual-owner pattern), reseed this like the others.

- [ ] **Step 3: Build + commit.**

```bash
gradlew.bat :app:assembleDebug
git add app/src/main/java/com/aritr/rova/ui/screens/ app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "polish(library): Settings toggle for card preview (P7)"
```

### Task P7.3: Gate the card autoplay on the setting

**Files:** `LibraryUiState.kt`, `HistoryViewModel.kt`, `LibraryScreen.kt`

- [ ] **Step 1: Surface the setting as state — RESEED ON RESUME (codex)**

Add `val cardPreview: Boolean` to `LibraryUiState`, seeded from `RovaSettings.libraryCardPreview`. **Do NOT rely on seed-at-composition alone:** the bottom-nav `saveState`/`restoreState` keeps `LibraryScreen` composed across tab switches, so toggling the Setting and returning to Library would NOT pick up the change (the switch would look broken until process recreation). Reseed on `Lifecycle.Event.ON_RESUME` — add a `LifecycleEventObserver` in `LibraryScreen` (the redesign already uses an ON_RESUME observer for focus restore — mirror it) that calls a thin `HistoryViewModel.refreshCardPreview()` re-reading the pref into the state. (Note: the existing `viewMode` seed has the same latent staleness but is out of scope here; only wire `cardPreview` this way.)

- [ ] **Step 2: Gate `autoplayKeys`**

In `LibraryScreen`, the `autoplayKeys` `derivedStateOf` currently returns `emptySet` under reduce-motion or scrolling. Add the setting: when `!ui.cardPreview`, return `emptySet` too (so NO card autoplays). Key the `derivedStateOf` on `(reduceMotion, ui.cardPreview)`. **Hero is unaffected** — `renderHero` keeps `autoplay = !reduceMotion` (the card setting does NOT gate the hero).

- [ ] **Step 3: Build + suite + commit.**

```bash
gradlew.bat :app:assembleDebug && gradlew.bat :app:testDebugUnitTest
git add app/src/main/java/com/aritr/rova/ui/library/ app/src/main/java/com/aritr/rova/ui/screens/HistoryViewModel.kt
git commit -m "polish(library): cards static by default; preview gated on setting (P7)"
```

### Task P7.4: Device smoke

- [ ] Install. Default state: cards are **static posters**, only the hero autoplays. Toggle the Setting ON → visible cards preview (≤ decoder cap, muted). Enable reduce-motion → all card preview stops, hero static too. **Owner GO before P8.**

---

# Slice P8 — Floating "Brave-style" item sheet

**Goal:** Re-skin `LibraryItemSheet` from a hard-attached `ModalBottomSheet` into a floating, elevated card that respects the system navigation-bar insets, sits off the screen edges with a gap, is fully rounded, and casts a shadow — premium and intentional. Add a session-identity header.

### Task P8.1: Float the sheet

**Files:** `app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt`

- [ ] **Step 1: Rewrite the container to float**

The default `ModalBottomSheet` paints a full-width container hard against the bottom (under the nav bar). Make it float: transparent sheet container + zero content insets + an inner elevated `Surface` card with margins that include the nav-bar inset.

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryItemSheet(
    /* existing params … */
    headerTitle: String,
    headerMeta: String,
    headerThumbnail: android.graphics.Bitmap?,
    /* existing labels + callbacks … */
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,                 // we paint our own floating card
        contentWindowInsets = { WindowInsets(0) },          // we handle insets ourselves
        dragHandle = null,                                  // floating card → no attached handle
        shape = RectangleShape,                             // the visible rounding is on the inner Surface
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth(Alignment.CenterHorizontally)   // center the capped card (codex: fillMaxWidth+widthIn may not cap under constraints)
                .widthIn(max = LibraryDimens.sheetMaxWidth)
                .padding(horizontal = LibraryDimens.sheetEdgeGap) // outer padding gives the drop shadow room (codex: shadow clips otherwise)
                .navigationBarsPadding()                    // never collide with the system nav bar (gesture AND 3-button)
                .padding(bottom = LibraryDimens.sheetEdgeGap),
            shape = RoundedCornerShape(LibraryDimens.sheetCornerRadius),   // all four corners
            shadowElevation = LibraryDimens.sheetElevation,                // floating shadow
            tonalElevation = 2.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,        // crisp elevated surface (not glass — needs a real shadow)
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                // small grab handle centered inside the card (optional, premium touch)
                Box(Modifier.padding(top = 4.dp, bottom = 8.dp).align(Alignment.CenterHorizontally)
                    .size(width = 34.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)))
                // session-identity header (P8.2)
                SheetHeader(headerThumbnail, headerTitle, headerMeta)
                HorizontalDivider(color = Color.White.copy(alpha = LibraryDimens.dividerAlpha))
                // existing rows …
                SheetRow(Icons.Filled.PlayArrow, playLabel) { onPlay() }
                /* … unchanged rows … */
                SheetRow(Icons.Filled.Delete, deleteLabel) { onDelete() }
            }
        }
    }
}
```

Imports to add: `Color`, `RectangleShape`, `RoundedCornerShape`, `Surface`, `WindowInsets`, `navigationBarsPadding`, `widthIn`, `wrapContentWidth`, `fillMaxWidth`, `clip`, `background`, `size`, `Box`, `HorizontalDivider`, `MaterialTheme`, `Alignment`.

> **codex notes (verified behavior):** (a) `containerColor=Transparent` keeps the floating look but the **scrim/dim behind still renders** (it's `scrimColor`, separate) — good, leave it. (b) Swipe-to-dismiss, the up-animation, and drag physics still work — they live on `ModalBottomSheet`, not the visible container or drag handle. (c) The transparent full-width sheet area still participates in hit-testing, so at P8.2 smoke **tap just outside the card** to confirm it dismisses (not dead zone). (d) If the card can ever host text input later, add `imePadding()`; not needed now (no input).

> **Why a `Surface`, not `GlassSurface`:** a floating card needs a real drop **shadow** (`shadowElevation`) for the elevated/Brave feel; `GlassSurface` is a blur+fill+edge with no cast shadow. `checkGlassSurfaceRoleUsage` only governs `Modifier.blur` sites, so a plain elevated `Surface` here is compliant. This is a deliberate role choice — note it in the KDoc.

- [ ] **Step 2: Add the session-identity header**

```kotlin
@Composable
private fun SheetHeader(thumbnail: android.graphics.Bitmap?, title: String, meta: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoFrame(thumbnail = thumbnail, modifier = Modifier.size(width = 64.dp, height = 40.dp)
            .clip(RoundedCornerShape(9.dp)))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
```
Caller (`LibraryScreen` item-sheet host) passes `headerTitle = row.title`, `headerMeta = SessionCaption.listMeta(time, clips, duration, size)` (reuse P3.A), `headerThumbnail` = the row's decoded bitmap. No new strings (meta is already-localized parts).

- [ ] **Step 3: Build (verify `checkA11yTargetSizeToken`, `checkA11yClickableHasRole`, `checkNoHardcodedUiStrings`, `checkRecordSurfaceNoBlur`, `checkLibraryNoManifestWrite` all green).**

Run: `gradlew.bat :app:assembleDebug`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/library/components/LibraryItemSheet.kt app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt
git commit -m "polish(library): floating elevated item sheet w/ nav-bar insets + session header (P8)"
```

### Task P8.2: Device smoke (incl. gesture-nav + 3-button nav)

- [ ] Install. Long-press a session → the item sheet floats: gap on left/right/bottom, fully rounded, visible shadow, **clear gap above the system nav bar on BOTH gesture-nav and 3-button nav** (test both — switch in system settings). Header shows the session identity. Rows still ≥48dp, TalkBack reads them. **Owner GO — full polish pass (P1–P8) complete.**

---

## Post-P8 — merge train (owner-gated, NOT part of the build)

After owner GO on the whole polish pass, the full Library reskin (Slices 1–5 + P1–P8) is done. **Ask the owner** whether to run the end-of-reskin stacked merge train (`memory/feedback_stacked_pr_merge_train.md`): merge base WITHOUT `--delete-branch` → rebase each dependent `--onto origin/master <old-base>` → re-target/re-push → delete. Do NOT push/merge before the owner asks.

---

## Self-Review (against PART B/C of the brief)

- **PART B gap #1 (hero/grid opaque, skip glass):** hero → P2.4 (glass edge + scrim overlay); grid → P3.1 (glass edge). ✓
- **#2 (token-sparse, off-scale):** P1.1 extends to Record scale; P1.2/P2/P3 route literals. ✓
- **#3 (generic M3 type, no Inter/tnum):** P2.4 eyebrow→`RovaTokens.eyebrow`, meta line `fontFeatureSettings="tnum"`. ✓ (Caption/badge type left M3 — acceptable; brief marks #4 MEDIUM and badges already SemiBold.)
- **#4 (visual noise/weak hierarchy):** P2 one-CTA + overlay; P4.5 quiet chips. ✓
- **#5 (ad-hoc scrims/borders):** P1 scrim/divider/selection tokens; P2 hero scrim token; P3 soft ring replaces `2dp primary`. ✓
- **#6 (plain states):** P4 skeleton + search-empty + error. ✓
- **#7 (glass alpha verify):** P5.3 — corrected target `0.40/0.09`; finding documented. ✓
- **#8 (background gradient):** left as-is (`0.24f`) — LOW severity; not worth churn this pass (note for a follow-up if owner wants Record depth language).
- **PART A DualShot H1:** P2.3 drops static under-layer after first frame; smoke at P2.5 reduce-motion OFF/ON. ✓

### Owner-added scope (research + codex, 2026-06-15) — coverage

- **Card autoplay demotion:** P3 renders static cards; P7 adds the opt-in `libraryCardPreview` Setting (default OFF) and gates `autoplayKeys`; hero autoplay untouched. ✓
- **Storage/usage summary:** P6 — `UsageAggregator`/`StorageSummaryFormatter` (pure, tested), `LibraryUsageLine`, wired in the VM combine. ✓
- **Session identity:** P3.0 adds `clipCount` to `LibraryRow`+mapper; P3.A `SessionCaption` (pure, tested); grid chips/badges + list meta line + status dot. ✓
- **Empty = quiet link, no filled CTA:** P4.3/P4.4 — `TextButton` "Go to Record", non-shouty copy. ✓
- **Scope held flat:** no albums/collections/extra tabs, no session-detail screen — confirmed, nothing added. ✓
- **Floating Brave-style item sheet:** P8 — transparent `ModalBottomSheet` + inner elevated `Surface` (rounded, shadow), `navigationBarsPadding`, `sheetEdgeGap`, max-width, session-identity header; smoked on gesture-nav AND 3-button nav. ✓

- **Type consistency:** `HeroMetaFormatter.format(...)`, `HeroUnderlayPolicy.showStaticUnderlay(...)`, `LibraryStatePolicy.resolve(...)`/`LibraryStateKind`, `PressFeedback.targetScale(...)`, `Modifier.pressScale(...)`, `SessionCaption.listMeta(...)`/`gridCaption(...)`, `UsageAggregator.aggregate(...)`→`UsageSummary`, `StorageSummaryFormatter.join(...)`, `RovaSettings.libraryCardPreview`, `LibraryRow.clipCount` — names used identically across creation + call sites. ✓
- **Out of scope (held):** PORTRAIT square-into-9:16 DualShot CAPTURE stretch (`AspectFitMath.kt:359-366`) — capture-side, untouched. ✓
```

