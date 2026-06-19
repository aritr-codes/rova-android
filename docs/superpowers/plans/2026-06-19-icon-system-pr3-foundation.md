# Icon System PR-3: Icon Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Author 15 board-exact bespoke System-D glyphs + a `IconStatus.Danger` locked-color mapping, and repoint their `RovaIcons` concept→glyph entries off stock Material — additive, no surface rewired.

**Architecture:** Glyphs are `RovaGlyph(outline, accent?)` ImageVectors authored only in `RovaGlyphs.kt` (`checkRovaGlyphHome`), tinted through the `SemanticIcon` seam (`role`/`status`). The seam gains one locked status case (`Danger → RovaSemantics.error`, an existing color). `RovaIcons` is the concept→glyph map. JVM tests assert glyph structure + the status mapping.

**Tech Stack:** Kotlin, Jetpack Compose `ImageVector`/`PathBuilder`, JUnit4 (JVM unit tests, `isReturnDefaultValues = true`), Gradle static `check*` gates.

## Global Constraints

- **Source of truth = `.superpowers/brainstorm/1234-1781611237/content/board-3-semantic.html`** `const G={…}` dict. Transcribe `d=`/element values verbatim — no eyeballing.
- **Glyphs only in `RovaGlyphs.kt`** (`checkRovaGlyphHome`, ADR-0031 §5). 24×24 grid, `SW=1.9f` monoline, round caps/joins via the existing `glyph/strokePath/fillPath/svgStroke/svgFill/circle/roundRect/seg` helpers.
- **Status colors locked** (ADR-0031 §3): never `.copy(`-diluted (`checkStatusColorLocked`), come from `RovaSemantics`.
- **JVM unit tests only**; a feature lands its tests in the same PR. Baseline must stay green.
- **All 46 `check*` gates stay green**; never edit a check to pass.
- **No new user-facing strings** in this PR (no call-sites wired) → `checkNoHardcodedUiStrings` not engaged.
- **Build WARM**: `gradlew.bat :app:assembleDebug` + `:app:testDebugUnitTest`; confirm tasks EXECUTED. No device smoke (additive/invisible).
- **Commits/gradle = controller only.** No commit until owner GO.

---

### Task 1: Add `IconStatus.Danger → RovaSemantics.error` to the seam

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/SemanticIconSpec.kt` (enum `IconStatus` ~line 21; `statusTint` ~line 59-66)
- Test: `app/src/test/java/com/aritr/rova/ui/theme/SemanticIconSpecTest.kt`

**Interfaces:**
- Consumes: existing `RovaSemantics.error = Color(0xFFEF4444)`, `RovaSemantics.rec = Color(0xFFFF4D4D)`.
- Produces: `IconStatus.Danger`; `SemanticIconSpec.statusTint(IconStatus.Danger) == RovaSemantics.error`. PR-4 consumes `status = IconStatus.Danger` at destructive call-sites.

- [ ] **Step 1: Write the failing test** — append to `SemanticIconSpecTest.kt`:

```kotlin
@Test fun danger_status_maps_to_locked_error_red() {
    assertEquals(RovaSemantics.error, SemanticIconSpec.statusTint(IconStatus.Danger))
}

@Test fun danger_is_distinct_from_recording_red() {
    // destructive (0xFFEF4444) must never collapse onto recording (0xFFFF4D4D)
    assertNotEquals(
        SemanticIconSpec.statusTint(IconStatus.Rec),
        SemanticIconSpec.statusTint(IconStatus.Danger),
    )
}
```

(Ensure imports `org.junit.Assert.assertEquals`, `org.junit.Assert.assertNotEquals` exist in the file.)

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.SemanticIconSpecTest"`
Expected: FAIL — `IconStatus.Danger` unresolved (compile error).

- [ ] **Step 3: Add the enum case + tint branch**

In `SemanticIconSpec.kt`, change the enum:

```kotlin
enum class IconStatus { Recovered, Interrupted, Processing, Success, Warning, Rec, Danger }
```

Update the KDoc above it to note `Danger` (destructive/irreversible, locked `error` red, distinct from `Rec`). Add the branch to `statusTint` (keep it next to `Rec`):

```kotlin
    fun statusTint(status: IconStatus): Color = when (status) {
        IconStatus.Recovered -> RovaSemantics.success
        IconStatus.Interrupted -> RovaSemantics.warning
        IconStatus.Processing -> RovaSemantics.escalating
        IconStatus.Success -> RovaSemantics.success
        IconStatus.Warning -> RovaSemantics.warning
        IconStatus.Rec -> RovaSemantics.rec
        IconStatus.Danger -> RovaSemantics.error
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.SemanticIconSpecTest"`
Expected: PASS.

- [ ] **Step 5: (no commit yet — controller batches; owner GO gates commits)**

---

### Task 2: Author the 15 glyphs in `RovaGlyphs.kt`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt` (add a new section before the `// ── authoring helpers ──` block, after the FAB-lifecycle glyphs ~line 194)
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaGlyphsTest.kt`

**Interfaces:**
- Consumes: existing helpers `glyph`, `strokePath`, `fillPath`, `svgStroke`, `svgFill`, `circle`, `roundRect` (all already in the file).
- Produces: `RovaGlyphs.{Search, Share, Delete, Favorite, FavoriteOn, Select, Pause, View, Edit, Theme, WarnTriangle, NotifBell, NotifOff, RecClipCheck, Interrupted}` — each a `RovaGlyph`.

- [ ] **Step 1: Write the failing test** — add per-glyph structural assertions to `RovaGlyphsTest.kt`:

```kotlin
@Test fun pr3_action_glyphs_models() {
    // mono (accent == null)
    assertNull(RovaGlyphs.Search.accent)
    assertNull(RovaGlyphs.Delete.accent)
    assertNull(RovaGlyphs.Favorite.accent)      // single-channel, accent via IconRole.Accent
    assertNull(RovaGlyphs.FavoriteOn.accent)
    assertNull(RovaGlyphs.Pause.accent)
    assertNull(RovaGlyphs.View.accent)
    assertNull(RovaGlyphs.WarnTriangle.accent)  // board warn_tri has zero .ac2
    // duotone (accent != null)
    assertNotNull(RovaGlyphs.Share.accent)
    assertNotNull(RovaGlyphs.Select.accent)
    assertNotNull(RovaGlyphs.Edit.accent)
    assertNotNull(RovaGlyphs.Theme.accent)
    assertNotNull(RovaGlyphs.NotifBell.accent)
    assertNotNull(RovaGlyphs.NotifOff.accent)
    assertNotNull(RovaGlyphs.RecClipCheck.accent)
    assertNotNull(RovaGlyphs.Interrupted.accent)
}
```

Then extend the two existing sweeps. Add all 15 to `all_glyphs_use_the_24_grid`'s `listOf(...)`, and add their layers to `every_glyph_path_has_a_brush`'s `listOf(...)` (outline for all; `accent!!` for the 8 duotone ones).

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaGlyphsTest"`
Expected: FAIL — `RovaGlyphs.Search` etc. unresolved.

- [ ] **Step 3: Author the glyphs.** Insert this block into `RovaGlyphs.kt` after the `ProcDots` val (before `// ── Folded-in record-chrome vectors`):

```kotlin
    // ── PR-3 everyday-action + status glyphs (board-3-semantic.html "shared small" + Part A #3) ──

    // Search — lens ring + handle (mono). board `search`.
    val Search = RovaGlyph(
        outline = glyph {
            strokePath { circle(10.8f, 10.8f, 5.9f) }
            svgStroke("M15.4 15.4 20 20")
        },
    )

    // Share — 2 neutral connectors (outline) + 3 accent nodes (accent). board `share`.
    val Share = RovaGlyph(
        outline = glyph {
            svgStroke("M8.4 10.9 15 7.1")
            svgStroke("M8.4 13.1 15 16.9")
        },
        accent = glyph {
            fillPath { circle(6.2f, 12f, 2.3f) }
            fillPath { circle(17.3f, 6f, 2.3f) }
            fillPath { circle(17.3f, 18f, 2.3f) }
        },
    )

    // Delete — trash can (mono). board `delete`. Destructive tint is a call-site concern
    // (consume with status = IconStatus.Danger in destructive contexts; the glyph is neutral).
    val Delete = RovaGlyph(
        outline = glyph {
            svgStroke("M5 7h14")
            svgStroke("M9 7V5.6A1.6 1.6 0 0 1 10.6 4h2.8A1.6 1.6 0 0 1 15 5.6V7")
            svgStroke("M7.2 7 8.1 19.4A1.6 1.6 0 0 0 9.7 21h4.6a1.6 1.6 0 0 0 1.6-1.6L16.8 7")
        },
    )

    // Favorite (off) — star outline. board `favorite` (single accent channel). Single-layer:
    // consume with IconRole.Accent so the whole star is palette accent (NEVER a status — that
    // would recolor it). PR-4 toggles state by swapping to [FavoriteOn].
    val Favorite = RovaGlyph(
        outline = glyph { svgStroke("M12 4 14.5 9.1l5.5.8-4 3.9.95 5.5L12 17.6 7.05 19.3 8 13.8 4 9.9l5.5-.8z") },
    )

    // Favorite (on) — filled star. board `favorite_on`. Consume with IconRole.Accent.
    val FavoriteOn = RovaGlyph(
        outline = glyph { svgFill("M12 4 14.5 9.1l5.5.8-4 3.9.95 5.5L12 17.6 7.05 19.3 8 13.8 4 9.9l5.5-.8z") },
    )

    // Select — ring (outline) + accent check (accent). board `select`.
    val Select = RovaGlyph(
        outline = glyph { strokePath { circle(12f, 12f, 8f) } },
        accent = glyph { svgStroke("M8.4 12.2 10.8 14.7 15.6 9.6") },
    )

    // Pause — two filled bars (mono). board `pause`.
    val Pause = RovaGlyph(
        outline = glyph {
            fillPath { roundRect(7.4f, 6f, 3.3f, 12f, 1.4f) }
            fillPath { roundRect(13.3f, 6f, 3.3f, 12f, 1.4f) }
        },
    )

    // View — 2×2 grid (mono). board `view` (== rejected `set_grid`: grid is canonically View).
    val View = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(4f, 4f, 6.4f, 6.4f, 1.6f) }
            strokePath { roundRect(13.6f, 4f, 6.4f, 6.4f, 1.6f) }
            strokePath { roundRect(4f, 13.6f, 6.4f, 6.4f, 1.6f) }
            strokePath { roundRect(13.6f, 13.6f, 6.4f, 6.4f, 1.6f) }
        },
    )

    // Edit — pencil body (outline) + accent nib (accent). board `edit`.
    val Edit = RovaGlyph(
        outline = glyph { svgStroke("M5 19h3l9-9-3-3-9 9z") },
        accent = glyph { svgStroke("M14 6 17 9") },
    )

    // Theme — ring (outline) + accent-filled half-disc (accent). board `theme`.
    val Theme = RovaGlyph(
        outline = glyph { strokePath { circle(12f, 12f, 8f) } },
        accent = glyph { svgFill("M12 4a8 8 0 0 1 0 16z") },
    )

    // WarnTriangle — triangle + ! + dot (mono). board `warn_tri` (zero .ac2). A STATUS:
    // consume with status = IconStatus.Warning (locked amber, never a setting).
    val WarnTriangle = RovaGlyph(
        outline = glyph {
            svgStroke("M12 4.4 20.6 19.2H3.4z")
            svgStroke("M12 9.8v4.3")
            fillPath { circle(12f, 16.6f, 0.95f) }
        },
    )

    // NotifBell — bell (outline) + accent clapper (accent). board `notif_bell`. Chrome toggle
    // (a destination/setting), role-tinted — NOT a status.
    val NotifBell = RovaGlyph(
        outline = glyph { svgStroke("M6.5 10.5a5.5 5.5 0 0 1 11 0c0 4.5 2 5.5 2 5.5H4.5s2-1 2-5.5") },
        accent = glyph { svgStroke("M10.2 18.5a2 2 0 0 0 3.6 0") },
    )

    // NotifOff — bell-off (outline) + accent slash & clapper (accent). board `notif_off`.
    val NotifOff = RovaGlyph(
        outline = glyph {
            svgStroke("M6.5 10.5a5.5 5.5 0 0 1 8.4-4.7")
            svgStroke("M17.5 12.5c.2 2.4 1.5 3.5 1.5 3.5H8")
        },
        accent = glyph {
            svgStroke("M4.5 4.5 19.5 19.5")
            svgStroke("M10.2 18.5a2 2 0 0 0 3.6 0")
        },
    )

    // RecClipCheck — clip frame + side bar (outline) + accent check (accent). board `rec_clipcheck`.
    // Authored now; map entry + wiring land in PR-5 (recovered-clip-verified surface).
    val RecClipCheck = RovaGlyph(
        outline = glyph {
            strokePath { roundRect(3.5f, 6f, 12f, 11.5f, 2.3f) }
            svgStroke("M18.5 8.2v7")
        },
        accent = glyph { svgStroke("M12.6 16.8 15.1 19.3 19.6 14.3") },
    )

    // Interrupted — ring + top/bottom ticks (outline) + accent slash (accent). board `interrupted`.
    // A STATUS (locked amber). Authored now; map entry + status-encoding land in PR-5.
    val Interrupted = RovaGlyph(
        outline = glyph {
            strokePath { circle(12f, 12f, 8f) }
            svgStroke("M12 4v3M12 17v3")
        },
        accent = glyph { svgStroke("M8 8 16 16") },
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaGlyphsTest"`
Expected: PASS (all structural + grid + brush sweeps).

- [ ] **Step 5: (no commit — controller batches)**

---

### Task 3: Repoint `RovaIcons` map + import cleanup

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt`

**Interfaces:**
- Consumes: Task 1 (`IconStatus.Warning` already existed; uses it), Task 2 glyphs.
- Produces: `RovaIcons.{Search, Share, Delete, Favorite, FavoriteOn, Select, Pause, Edit, Theme, NotificationsOff}` (bare `RovaGlyph`); flipped `View` (`RovaGlyph`), `WarningStatus` (`RovaIcon` w/ status), `NotificationsSetting` (`RovaGlyph`). `RecClipCheck`/`Interrupted` are deliberately NOT added here (PR-5).

- [ ] **Step 1: Rewrite the map.** Replace the two sections of `RovaIcons.kt`. Remove the unused Material imports (`GridView`, `Notifications`, `WarningAmber`); keep `Outlined.Info`. New body:

```kotlin
package com.aritr.rova.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Canonical concept→glyph map (ADR-0031 §2/§5). One concept → exactly one glyph. Bespoke System-D
 * concepts resolve to a [RovaGlyph] (two-layer); the few still on stock Material stay [RovaIcon]
 * until authored in a later slice. Each entry's KDoc names how it is meant to be tinted at the
 * call-site (role vs locked status) — the map carries the locked status itself only where the
 * concept IS a status (e.g. [WarningStatus]).
 */
data class RovaIcon(val glyph: ImageVector, val status: IconStatus? = null)

object RovaIcons {
    // ── Bespoke System-D (RovaGlyph) ──
    val Library: RovaGlyph = RovaGlyphs.Library
    val Settings: RovaGlyph = RovaGlyphs.Settings
    val Sort: RovaGlyph = RovaGlyphs.Sort
    val Record: RovaGlyph = RovaGlyphs.Record
    val DualShot: RovaGlyph = RovaGlyphs.DualShot
    val Vault: RovaGlyph = RovaGlyphs.Vault
    val Recovery: RovaGlyph = RovaGlyphs.Recovery
    val DualSight: RovaGlyph = RovaGlyphs.DualSight
    val BackgroundRecord: RovaGlyph = RovaGlyphs.BackgroundRecord
    val Merge: RovaGlyph = RovaGlyphs.Merge
    // FAB lifecycle (board-3 FB): rec_disc = [Record] above; the rest:
    val RecordRing: RovaGlyph = RovaGlyphs.RecordRing
    val Waiting: RovaGlyph = RovaGlyphs.Waiting
    val Processing: RovaGlyph = RovaGlyphs.ProcArc
    val ProcessingDots: RovaGlyph = RovaGlyphs.ProcDots
    val OrientationPortrait: RovaGlyph = RovaGlyphs.OrientationPortrait
    val OrientationLandscape: RovaGlyph = RovaGlyphs.OrientationLandscape
    val Single: RovaGlyph = RovaGlyphs.Single
    val FollowDevice: RovaGlyph = RovaGlyphs.FollowDevice

    // ── PR-3 everyday-action glyphs (role-tinted; consume notes in KDoc) ──
    /** Library/global search field. */
    val Search: RovaGlyph = RovaGlyphs.Search
    /** Share sheet trigger. */
    val Share: RovaGlyph = RovaGlyphs.Share
    /** Delete action — neutral glyph; consume with `status = IconStatus.Danger` in destructive UI. */
    val Delete: RovaGlyph = RovaGlyphs.Delete
    /** Favorite (off). Consume with `role = IconRole.Accent`; swap to [FavoriteOn] when set. */
    val Favorite: RovaGlyph = RovaGlyphs.Favorite
    /** Favorite (on). Consume with `role = IconRole.Accent`. */
    val FavoriteOn: RovaGlyph = RovaGlyphs.FavoriteOn
    /** Multi-select / selected check. */
    val Select: RovaGlyph = RovaGlyphs.Select
    /** Pause (player). */
    val Pause: RovaGlyph = RovaGlyphs.Pause
    /** Edit / rename. */
    val Edit: RovaGlyph = RovaGlyphs.Edit
    /** Theme picker. */
    val Theme: RovaGlyph = RovaGlyphs.Theme
    /** View / layout mode (2×2 grid). Was Icons.Default.GridView. */
    val View: RovaGlyph = RovaGlyphs.View
    /** Notifications setting (enabled toggle/destination). */
    val NotificationsSetting: RovaGlyph = RovaGlyphs.NotifBell
    /** Notifications setting (disabled). */
    val NotificationsOff: RovaGlyph = RovaGlyphs.NotifOff

    // ── Status concept — the locked status travels WITH the map entry (codex 2026-06-19) ──
    /**
     * Warning STATUS. WarnTriangle is mono, so its single layer is exposed as the [RovaIcon]
     * image vector with the locked [IconStatus.Warning] baked in — a call-site reads `.glyph`
     * and `.status` and cannot forget the amber lock.
     */
    val WarningStatus = RovaIcon(RovaGlyphs.WarnTriangle.outline, status = IconStatus.Warning)

    // ── Still stock Material (RovaIcon), authored in a later slice ──
    // "View settings" on a library item shows that recording's read-only capture config — per-item
    // details/metadata, not app preferences → Info (ⓘ), not a gear (owner 2026-06-17).
    val Details = RovaIcon(Icons.Outlined.Info)
    val Play = RovaIcon(RovaGlyphs.Play)
}
```

- [ ] **Step 2: Full compile + gate build**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL; all 46 `check*` tasks (incl. `checkRovaGlyphHome`, `checkSemanticIconNoRawAlpha`, `checkStatusColorLocked`) green; no unused-import / unresolved-reference error. Confirm `:app:packageDebug` EXECUTED state is irrelevant (no install).

- [ ] **Step 3: Full JVM test run**

Run: `gradlew.bat :app:testDebugUnitTest`
Expected: PASS, baseline + new tests, 0 failures.

- [ ] **Step 4: (no commit — controller batches; await owner GO)**

---

### Task 4: Ultracode adversarial board-path-match verification

**Files:** none (verification only).

- [ ] **Step 1:** Run the path-match Workflow — one agent per authored glyph verifies the DSL in `RovaGlyphs.kt` reproduces the board `const G={…}` value (spec §5) exactly: same numeric coordinates, same channel (neutral→outline / `.ac2`→accent), same stroke-vs-fill (`solid`). Any mismatch → fix in `RovaGlyphs.kt`, re-run Task 2 Step 4.
- [ ] **Step 2:** codex final pass on the diff (glyph set + seam + map). Fold in or document disagreement.
- [ ] **Step 3:** Re-run `gradlew.bat :app:assembleDebug` + `:app:testDebugUnitTest` if any fix landed.

---

### Task 5: Branch, commit, PR (owner GO only)

- [ ] **Step 1:** `git switch -c feat/icon-foundation-pr3` (off `master` `7d3a996`).
- [ ] **Step 2:** Stage only PR-3 files: `RovaGlyphs.kt`, `SemanticIconSpec.kt`, `RovaIcons.kt`, `RovaGlyphsTest.kt`, `SemanticIconSpecTest.kt`, the spec + plan docs. (Leave ephemeral `gradle_*.log`.)
- [ ] **Step 3:** Commit (`feat(icon): PR-3 icon foundation — 15 board-exact glyphs + IconStatus.Danger`), push, `gh pr create`.
- [ ] **Step 4:** No device smoke (additive, no surface). Update HANDOFF/memory on merge.

## Self-Review

- **Spec coverage:** §4 Danger→Task 1 ✓; §5 15 glyphs→Task 2 ✓; §6 map flips/adds + import cleanup→Task 3 ✓; §7 tests→Tasks 1-2 ✓; §8 verification→Task 4 ✓. `RecClipCheck`/`Interrupted` authored (Task 2) but not mapped (per spec §6) ✓.
- **Placeholder scan:** all code blocks are complete board-verbatim; no TBD/TODO.
- **Type consistency:** `IconStatus.Danger`, `RovaSemantics.error`, `RovaGlyph`, `RovaIcon(ImageVector, IconStatus?)`, glyph val names identical across Tasks 1-3 and the tests. `WarningStatus` uses `.outline` (mono) — valid.
