# Icon 5b-3 — Settings glyph wiring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route every `SettingsScreen` row's leading icon through a System-D `RovaGlyph` via `SemanticIcon`, authoring the two last missing board glyphs (`LoopInterval`, `Volume`).

**Architecture:** `SettingsRow` is the single shared render site for all row leading icons. Change its `icon: ImageVector` param to `glyph: RovaGlyph` and render via `SemanticIcon`; each of ~24 call sites swaps `icon = Icons.X` → `glyph = RovaIcons.Y`. The two glyphs with no existing source are round-tripped from `board-3-semantic.html` d-strings into `RovaGlyphs.kt` (`checkRovaGlyphHome`). State rows stay neutral (the existing `Switch`/badge conveys state — identity-only fence). Zero new strings.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit (JVM unit tests under `isReturnDefaultValues = true`).

## Global Constraints

- Branch/worktree off `master`, rebased on the merged 5b-2 branch. Subagents EDIT-ONLY; controller runs all gradle/tests/commits.
- 46 gates + full `:app:testDebugUnitTest` green at every commit. Never edit a `check*` to pass.
- Build WARM (`gradlew.bat :app:assembleDebug`, no cache wipe). Gate-build with `:app:assembleDebug`.
- New glyphs live ONLY in `RovaGlyphs.kt` (`checkRovaGlyphHome`); round-trip board d-strings verbatim via `svgStroke`/`svgFill`, do not eyeball.
- No new user-facing strings (settings leading icons are decorative beside their label → `contentDescription = null`).
- Glyph color flows through `SemanticIcon` (the seam) — no raw `Color` tint at the call site.
- codex MCP peer review on the `SettingsRow` signature/render change before the final commit.

---

### Task 1: Author `LoopInterval` + `Volume` glyphs and map them

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/SettingsGlyphWiringTest.kt`

**Interfaces:**
- Produces: `RovaGlyphs.LoopInterval`, `RovaGlyphs.Volume`; `RovaIcons.LoopInterval`, `RovaIcons.Volume`, `RovaIcons.Interval` (consumed by Task 3).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.aritr.rova.ui.theme

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsGlyphWiringTest {

    // `outline` is a non-null type, so its presence is compile-guaranteed; the meaningful authoring
    // assertion is that each glyph carries the duotone *accent* layer (nullable) round-tripped from
    // the board's `ac2` paths.
    @Test fun loop_interval_glyph_has_a_duotone_accent_layer() {
        assertNotNull("LoopInterval has a duotone accent layer", RovaGlyphs.LoopInterval.accent)
    }

    @Test fun volume_glyph_has_a_duotone_accent_layer() {
        assertNotNull("Volume has a duotone accent layer", RovaGlyphs.Volume.accent)
    }

    @Test fun map_entries_point_at_the_new_glyphs() {
        assertSame(RovaGlyphs.LoopInterval, RovaIcons.LoopInterval)
        assertSame(RovaGlyphs.Volume, RovaIcons.Volume)
    }

    @Test fun interval_alias_reuses_the_existing_waiting_hourglass() {
        assertSame(RovaGlyphs.Waiting, RovaIcons.Interval)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.SettingsGlyphWiringTest"`
Expected: FAIL — `RovaGlyphs.LoopInterval` / `RovaGlyphs.Volume` / `RovaIcons.*` unresolved.

- [ ] **Step 3: Author the two glyphs in `RovaGlyphs.kt`**

Add, in the glyph-val region (before the private DSL helpers, after the existing brand glyphs), round-tripped verbatim from `board-3-semantic.html` (`loop_interval` line ~147, `volume` line ~161):

```kotlin
// Loop / Interval — repeat arrows (outline) + accent core. board `loop_interval`.
val LoopInterval = RovaGlyph(
    outline = glyph {
        svgStroke("M8 6h8a4 4 0 0 1 0 8h-1")
        svgStroke("M16 18H8a4 4 0 0 1 0-8h1")
    },
    accent = glyph {
        svgStroke("M10.5 3.5 8 6l2.5 2.5")
        svgStroke("M13.5 20.5 16 18l-2.5-2.5")
        fillPath { circle(12f, 12f, 1.5f) }
    },
)

// Volume — filled speaker body (outline) + accent waves. board `volume`.
val Volume = RovaGlyph(
    outline = glyph { svgFill("M4 9.4h3.2L11 6v12L7.2 14.6H4z") },
    accent = glyph {
        svgStroke("M14.4 9.2a4 4 0 0 1 0 5.6")
        svgStroke("M17 7a7.4 7.4 0 0 1 0 10")
    },
)
```

- [ ] **Step 4: Map them in `RovaIcons.kt`**

In the "5b-1 new concepts" block of `RovaIcons`, add:

```kotlin
/** Loop count / repeats — periodic record cycle. */
val LoopInterval: RovaGlyph = RovaGlyphs.LoopInterval
/** Sound cues / volume. */
val Volume: RovaGlyph = RovaGlyphs.Volume
/** Interval between clips — reuses the Waiting hourglass (board `waiting`). */
val Interval: RovaGlyph = RovaGlyphs.Waiting
```

- [ ] **Step 5: Run test to verify it passes**

Run: `gradlew.bat :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.SettingsGlyphWiringTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Build to confirm the glyphs compile + `checkRovaGlyphHome` passes**

Run: `gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `checkRovaGlyphHome` green (glyphs are in `RovaGlyphs.kt`).

- [ ] **Step 7: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt app/src/test/java/com/aritr/rova/ui/theme/SettingsGlyphWiringTest.kt
git commit -m "feat(icon): 5b-3 author LoopInterval + Volume glyphs (board round-trip) + map"
```

---

### Task 2: Switch `SettingsRow` to render a `RovaGlyph` via `SemanticIcon`

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` (`SettingsRow` signature + leading-icon render)

**Interfaces:**
- Consumes: `com.aritr.rova.ui.theme.RovaGlyph`, `com.aritr.rova.ui.components.SemanticIcon`, `com.aritr.rova.ui.theme.IconRole`.
- Produces: `SettingsRow(glyph: RovaGlyph, …)` (consumed by Task 3).

- [ ] **Step 1: Add imports**

Add to `SettingsScreen.kt`:
```kotlin
import com.aritr.rova.ui.components.SemanticIcon
import com.aritr.rova.ui.theme.RovaGlyph
import com.aritr.rova.ui.theme.IconRole
```

- [ ] **Step 2: Change the `SettingsRow` param**

Line ~827: `icon: ImageVector,` → `glyph: RovaGlyph,`.

- [ ] **Step 3: Replace the leading-icon render**

Replace the `Icon(...)` block (lines ~876–881) with:

```kotlin
SemanticIcon(
    glyph = glyph,
    contentDescription = null,
    role = IconRole.Secondary,
    modifier = Modifier.size(15.dp),
)
```

(`IconRole.Secondary` → `palette.textDim`, the closest match to the prior dim 0.7-alpha leading icon. The trailing chevron `Icon` at line ~926 and the dialog `Icon`s stay stock — utility/dialog, identity-only fence — so keep the `Icon` and `ImageVector` imports.)

- [ ] **Step 4: Compile (call sites still pass `icon =` — expected to fail)**

Run: `gradlew.bat :app:compileDebugKotlin`
Expected: FAIL — every `SettingsRow(icon = …)` call site (`no parameter 'icon'`). Fixed in Task 3.

- [ ] **Step 5: (no commit yet — Task 3 completes the compile)**

---

### Task 3: Repoint every row call site to a `RovaIcons` glyph

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` (the ~24 `SettingsRow(...)` calls)

**Interfaces:**
- Consumes: `SettingsRow(glyph = …)` (Task 2), `RovaIcons.*` (Task 1 + existing).

- [ ] **Step 1: At each call site, replace `icon = Icons.<…>` with `glyph = RovaIcons.<…>`**

Apply this table (line numbers approximate — match by the row's `label`/`stringResource`). Every entry is a leading-icon arg swap; nothing else on the row changes:

| Row (≈line) | Old `icon =` | New `glyph =` |
|---|---|---|
| Theme / dark mode (300) | `Icons.Default.DarkMode` | `RovaIcons.DarkMode` |
| Language (310) | `Icons.Default.Language` | `RovaIcons.Language` |
| Resolution / quality (322) | `Icons.Default.HighQuality` | `RovaIcons.Quality` |
| Clip duration (331) | `Icons.Default.Timer` | `RovaIcons.Timer` |
| Interval (340) | `Icons.Default.HourglassEmpty` | `RovaIcons.Interval` |
| Loop count (349) | `Icons.Default.Repeat` | `RovaIcons.LoopInterval` |
| Keep screen on (360) | `Icons.Default.Smartphone` | `RovaIcons.Device` |
| Camera guides (368) | `Icons.Default.GridOn` | `RovaIcons.GridLayout` |
| Library preview (378) | `Icons.Default.Movie` | `RovaIcons.Video` |
| Sound cues (388) | `Icons.AutoMirrored.Filled.VolumeUp` | `RovaIcons.Volume` |
| Vibrate alerts (396) | `Icons.Default.Vibration` | `RovaIcons.Vibration` |
| Hide in vault (410) | `Icons.Default.Lock` | `RovaIcons.Vault` |
| Schedule master (445) | `Icons.Default.Schedule` | `RovaIcons.Schedule` |
| Start time (463) | `Icons.Default.Schedule` | `RovaIcons.Schedule` |
| Stop time (475) | `Icons.Default.Schedule` | `RovaIcons.Schedule` |
| Exact alarm (505) | `Icons.Default.Schedule` | `RovaIcons.AlarmOff` |
| System notifications (527) | `Icons.Default.Notifications` | `RovaIcons.NotificationsSetting` |
| Auto-delete (547) | `Icons.Default.DeleteSweep` | `RovaIcons.DeleteAll` |
| Save location (570) | `Icons.Default.Folder` | `RovaIcons.Folder` |
| Use internal (579) | `Icons.Default.Folder` | `RovaIcons.Folder` |
| Clear cache (589) | `Icons.Default.CleaningServices` | `RovaIcons.Cleanup` |
| Battery optimization (623) | `Icons.Default.BatteryAlert` | `RovaIcons.PowerMode` |
| Version / about (633) | `Icons.Default.Info` | `RovaIcons.Info` |
| Privacy policy (642) | `Icons.Default.PrivacyTip` | `RovaIcons.Privacy` |

Do NOT change: the trailing chevron (`KeyboardArrowRight`, ~926), the dialog `Icon`s (~265, ~1083). If any `SettingsRow` call beyond this table exists, repoint it to its closest `RovaIcons` concept (every concept used by Settings exists in `RovaIcons`).

- [ ] **Step 2: Remove the now-unused `Icons.*` settings-row imports**

Delete imports only used by the migrated rows (e.g. `DarkMode`, `Language`, `HighQuality`, `Timer`, `HourglassEmpty`, `Repeat`, `Smartphone`, `GridOn`, `Movie`, `VolumeUp`, `Vibration`, `Lock`, `Schedule`, `Notifications`, `DeleteSweep`, `CleaningServices`, `PrivacyTip`). KEEP any `Icons.*` still referenced (chevron `KeyboardArrowRight`, `BatteryAlert` if the dialog at ~1083 still uses it, plus any in `FloatingSettingsPanel`/dialogs). Let the compiler's "unused import" + unresolved-reference output drive the exact set.

- [ ] **Step 3: Build + full gate/test run**

Run: `gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; 46 gates green (esp. `checkSemanticIconNoRawAlpha`, `checkRovaGlyphHome`); all unit tests green incl. `SettingsGlyphWiringTest`.

- [ ] **Step 4: codex review**

`mcp__codex__codex`: "review the 5b-3 SettingsRow glyph migration — signature change + 24 call-site swaps + 2 round-tripped glyphs; check gate-safety and that no row lost its icon." Fold in blocking findings.

- [ ] **Step 5: Commit**

```
git add app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt
git commit -m "feat(icon): 5b-3 route Settings rows through SemanticIcon/RovaIcons"
```

---

## Verification (end of slice)

- `gradlew.bat :app:assembleDebug :app:testDebugUnitTest` — BUILD SUCCESSFUL, 46 gates + all tests green.
- Confirm `:app:packageDebug` EXECUTED + fresh APK mtime, then `adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk`.
- Device smoke (Settings NOT FLAG_SECURE → `screencap` works): open Settings, scroll all sections — confirm every row shows a System-D glyph (esp. the new LoopInterval on Loop count, Volume on Sound cues, Waiting-hourglass on Interval, Vault on Hide-in-Vault). Owner visual confirm of the @18dp board glyphs (Cleanup/Info/Quality + the 2 new) is final.

## Self-review notes

- Spec coverage: 2 glyphs authored + mapped (Task 1), shared render via SemanticIcon (Task 2), all rows repointed incl. state rows neutral (Task 3). No new strings (decorative). Utility/dialog icons untouched (identity-only fence).
- Type consistency: `glyph: RovaGlyph` param name + `SemanticIcon(glyph = …)` overload match Task 2↔3; `RovaIcons.LoopInterval/Volume/Interval` names match Task 1 test + Task 3 table.
- Open risk flagged for smoke: `IconRole.Secondary` chosen to preserve the prior dim weight; if owner finds it too faint, bump to `IconRole.Default` (one-line, single render site).
