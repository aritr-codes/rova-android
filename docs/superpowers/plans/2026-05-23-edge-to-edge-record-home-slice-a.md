# Edge-to-Edge Immersive Record-Home (Slice A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the RecordScreen preview fill the physical screen edge-to-edge across all 3 modes (Portrait / Landscape / DualShot P+L) by removing the inset double-pad, transparentising system bars, and dropping the opaque preview-zone background.

**Architecture:** This is a removal patch. `targetSdk 37` already triggers Android-15 edge-to-edge enforcement and `MainActivity.kt:23` calls `enableEdgeToEdge()`. Today's RovaTheme `SideEffect` and the NavHost `padding(innerPadding)` actively defeat that. Slice A reverses the over-eager inset consumption: bars become `Color.Transparent`, NavHost stops consuming insets, RecordScreen Scaffold stops consuming insets, DualPreviewZone background is dropped. The 6 per-element `windowInsetsPadding` calls already in RecordScreen.kt become the sole inset layer.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3 (Theme.Material3.DayNight.NoActionBar), Compose Foundation `WindowInsets`, AndroidX Activity `enableEdgeToEdge()`.

**Source spec:** `docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-a-design.md`

**Test policy (project precedent):** Compose UI / window / insets work is not unit-tested in this project — Phase 4.1, R1, R2, Phase 6 mode-picker, ADR-0010 all owner-deferred to on-device smoke. Each task therefore uses **compile-gate + lint + existing unit-suite green** as the per-task verification, and a single end-of-plan on-device smoke as the final gate. No new unit tests are added.

**Hard invariants (do not violate at any step):**
- No `service/dualrecord/**`, `EglRouter`, `AspectFitMath`, `DualVideoRecorder`, muxer, recording-pipeline changes — recorded files byte-identical
- ADR-0009 + ADR-0010 outputs unchanged; `buildCropMatrix` / `buildSideAspectCrop` / `buildPreviewCropMatrix` untouched
- `WarningId` / `WarningPrecedence` / Start-gate / recovery flow untouched
- `DualPreviewZone` `TextureView` / `SurfaceTexture` / `registerPreviewSurface` lifecycle untouched
- 352f / 225f P+L zone weights + `cam-split-divider` untouched
- `MainActivity.kt:23` `enableEdgeToEdge()` untouched

**Baseline at branch point (master @ `e07d914`):**
- `gradlew assembleDebug` — OK
- `gradlew testDebugUnitTest` — 1054 tests / 83 classes / 0 failures / 0 ignored
- `gradlew lintDebug` — 51 findings, no lint-baseline.xml

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/res/values/themes.xml` | Modify | Material 3 parent, disable nav-bar contrast auto-scrim |
| `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt` | Modify | `SideEffect` writes `Color.Transparent` to both bars |
| `app/src/main/java/com/aritr/rova/ui/MainScreen.kt` | Modify | `Scaffold(contentWindowInsets=WindowInsets(0))` + drop `NavHost.padding(innerPadding)` |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` | Modify | `Scaffold(contentWindowInsets=WindowInsets(0))` + drop inner-Box `.padding(innerPadding)` |
| `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt` | Modify | Drop `.background(camZoneBackground)` on PreviewZone Box |
| `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt` | Modify | Delete `camZoneBackground` declaration |
| `docs/UI_DESIGN_TOKENS.md` | Modify | Remove `camZoneBackground` row from §2.13.1 Camera-zone framing table |
| `docs/adr/0011-edge-to-edge-record-home.md` | Create | ADR for window/inset ownership shift |

**Audit-only (no edit expected):** `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`, `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` — both already use `Scaffold` + `TopAppBar` (verified during planning), so Material 3 handles their insets automatically once the NavHost double-pad is removed. Task 8 verifies on-device.

---

## Branching

Create a feature branch off master before Task 1:

```bash
git checkout master
git pull --ff-only
git checkout -b feat/edge-to-edge-record-home-slice-a
```

All commits land on this branch; PR opens against `master` after Task 8.

---

## Task 1: Theme bar colors → transparent

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt:97-98`

The current `SideEffect` paints both system bars with the M3 colorScheme's `background` / `surface` colors, which actively undoes `MainActivity.enableEdgeToEdge()`. Switch both writes to `Color.Transparent`. The light/dark icon-appearance writes (lines 99-100) are retained so status-bar icons stay readable on light themes.

- [ ] **Step 1: Make the edit**

Replace lines 97-98 of `app/src/main/java/com/aritr/rova/ui/theme/Theme.kt`.

Before:
```kotlin
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
```

After:
```kotlin
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
```

The `Color` import already exists at line 10; the `toArgb` import already exists at line 11. No new imports needed.

- [ ] **Step 2: Compile-gate**

Run: `./gradlew.bat :app:assembleDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL. (On non-Windows hosts use `./gradlew :app:assembleDebug --no-daemon -q`.)

- [ ] **Step 3: Lint-gate**

Run: `./gradlew.bat :app:lintDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL. Open `app/build/reports/lint-results-debug.html` and confirm finding count is still 51 (no new InlinedApi/NewApi).

- [ ] **Step 4: Unit-suite gate**

Run: `./gradlew.bat :app:testDebugUnitTest --no-daemon -q`
Expected: BUILD SUCCESSFUL. `app/build/test-results/testDebugUnitTest/*.xml` aggregate stays at **1054 tests / 0 failures / 0 ignored / 83 classes** (no test changes; this confirms no regressions ripple back from the theme edit).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/Theme.kt
git commit -m "feat(ui): transparentize system bars in RovaTheme SideEffect

Theme.kt was actively repainting statusBarColor=background and
navigationBarColor=surface every recomposition, undoing
MainActivity.enableEdgeToEdge(). Slice A flips both writes to
Color.Transparent so the system bars become true overlays. Light/dark
icon-appearance writes are retained.

Refs: docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-a-design.md §5.1"
```

---

## Task 2: Material 3 theme parent + disable nav-bar contrast auto-scrim

**Files:**
- Modify: `app/src/main/res/values/themes.xml`

The current parent `android:Theme.Material.Light.NoActionBar` is the old Material framework theme — Android 10+ defaults `enforceNavigationBarContrast=true`, so the OS draws its own translucent scrim behind the gesture-nav region on top of ours. Switch to `Theme.Material3.DayNight.NoActionBar` and explicitly disable the contrast attribute. `DayNight` parent picks light/dark automatically; Compose uses its own colorScheme regardless, so the XML parent only affects pre-Compose window defaults + the contrast attribute.

`values-night/themes.xml` does not exist (verified during planning); `DayNight` parent + Compose's `isSystemInDarkTheme()` handle both modes from this single file. **Do not create a values-night file** — it would be empty and confusing.

- [ ] **Step 1: Replace the file contents**

Overwrite `app/src/main/res/values/themes.xml` with:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>

    <style name="Theme.Rova" parent="Theme.Material3.DayNight.NoActionBar">
        <item name="android:enforceNavigationBarContrast">false</item>
    </style>
</resources>
```

`android:enforceNavigationBarContrast` is API 29+. Project minSdk is well above (Compose ships modern). Lint may emit a soft `UnusedAttribute` warning for older qualifiers — handled in the lint-gate step.

- [ ] **Step 2: Compile-gate**

Run: `./gradlew.bat :app:assembleDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL.

If the build fails with `error: resource style/Theme.Material3.DayNight.NoActionBar not found`: the project is missing the Material AndroidX dependency. Check `app/build.gradle.kts` for `com.google.android.material:material:*`; the project ships with this dep per Compose Material 3 setup. If it's missing, the planning context was wrong — stop and report.

- [ ] **Step 3: Lint-gate**

Run: `./gradlew.bat :app:lintDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL. Open `app/build/reports/lint-results-debug.html` and confirm finding count is still **51**. If a new `UnusedAttribute` warning fires for `enforceNavigationBarContrast` (because minSdk < 29 on some build variant), suppress it explicitly:

```xml
<style name="Theme.Rova" parent="Theme.Material3.DayNight.NoActionBar">
    <item name="android:enforceNavigationBarContrast" tools:targetApi="29">false</item>
</style>
```

…and add `xmlns:tools="http://schemas.android.com/tools"` to the `<resources>` element. Re-run lint and confirm 51 again.

- [ ] **Step 4: Unit-suite gate**

Run: `./gradlew.bat :app:testDebugUnitTest --no-daemon -q`
Expected: BUILD SUCCESSFUL. 1054 / 0 / 0 / 83.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/themes.xml
git commit -m "feat(ui): Material 3 theme parent + disable nav-bar contrast auto-scrim

Switch Theme.Rova parent from android:Theme.Material.Light.NoActionBar
to Theme.Material3.DayNight.NoActionBar, and set
android:enforceNavigationBarContrast=false to prevent the OS from
adding its own translucent scrim behind the gesture-nav region on top
of ours.

Refs: docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-a-design.md §5.2"
```

---

## Task 3: MainScreen Scaffold stops consuming insets

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/MainScreen.kt:1-10, 48-55`

The MainScreen `Scaffold` consumes window insets via its default `contentWindowInsets`, then the NavHost wrapper pads its content by `innerPadding`. This pushes every route inward by status + nav-bar heights — the root cause of letterboxed previews. Switch Scaffold's `contentWindowInsets` to `WindowInsets(0, 0, 0, 0)` and drop the NavHost padding. Each route owns its own insets (RecordScreen via per-element `windowInsetsPadding` calls; History/Settings via Material 3 Scaffold+TopAppBar defaults). Container color flips to `Color.Black` so the fallback surface across routes is unified.

- [ ] **Step 1: Update imports**

In `app/src/main/java/com/aritr/rova/ui/MainScreen.kt`, replace the import on line 3:

Before:
```kotlin
import androidx.compose.foundation.layout.padding
```

After:
```kotlin
import androidx.compose.foundation.layout.WindowInsets
```

Then add this import alongside the existing `Modifier` import (line 8):

```kotlin
import androidx.compose.ui.graphics.Color
```

`Modifier.padding(innerPadding)` is the only `.padding` usage in this file (verified during planning) and the Scaffold lambda now drops it — so the `layout.padding` import becomes unused after the edit. Removing the import keeps the file clean. If a future task re-introduces `Modifier.padding` here, the import can be re-added then.

- [ ] **Step 2: Edit the Scaffold + NavHost block**

Replace lines 48-55 of `app/src/main/java/com/aritr/rova/ui/MainScreen.kt`.

Before:
```kotlin
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding)
        ) {
```

After:
```kotlin
    Scaffold(
        containerColor = Color.Black,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
        ) {
```

The lambda parameter is renamed `_` to silence the unused-parameter warning (Scaffold's `content` slot requires a lambda that takes `PaddingValues`, but we no longer consume it). `MaterialTheme` import becomes unused after this edit; the `import androidx.compose.material3.MaterialTheme` line at line 4 can be deleted. (If you keep it, Kotlin lint will warn about an unused import — clean it up.)

- [ ] **Step 3: Compile-gate**

Run: `./gradlew.bat :app:assembleDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL.

If the build complains about `WindowInsets` constructor — Compose 1.6+ exposes `WindowInsets(left: Int, top: Int, right: Int, bottom: Int)` as a top-level function in `androidx.compose.foundation.layout`. The project uses modern Compose; the constructor should resolve. If it doesn't, fall back to `WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)` (Dp overload) — both are valid.

- [ ] **Step 4: Lint-gate**

Run: `./gradlew.bat :app:lintDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL. Open `app/build/reports/lint-results-debug.html` and confirm finding count is still **51**.

- [ ] **Step 5: Unit-suite gate**

Run: `./gradlew.bat :app:testDebugUnitTest --no-daemon -q`
Expected: BUILD SUCCESSFUL. 1054 / 0 / 0 / 83.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/MainScreen.kt
git commit -m "feat(ui): MainScreen Scaffold stops consuming insets

contentWindowInsets=WindowInsets(0) on the root Scaffold, and the
NavHost wrapper drops Modifier.padding(innerPadding). Each route now
owns its own insets (RecordScreen via per-element windowInsetsPadding
calls; History/Settings via M3 Scaffold+TopAppBar defaults).

Container color flips to Color.Black for a unified fallback surface.

Refs: docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-a-design.md §5.3"
```

---

## Task 4: RecordScreen Scaffold stops consuming insets

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt:429-437`

RecordScreen's own Scaffold also consumes insets and its inner Box re-applies them via `.padding(innerPadding)` — the second of the two double-pad layers. Apply the same `contentWindowInsets=WindowInsets(0)` treatment and drop the inner Box's `.padding(innerPadding)`. The 6 per-element `windowInsetsPadding(WindowInsets.statusBars/navigationBars)` calls already in this file (lines 525, 539, 578, 615, 638, 658) and the one in `RecordChrome.kt:369` continue to handle insets correctly — verified during planning.

- [ ] **Step 1: Edit the Scaffold + inner Box**

Replace lines 429-438 of `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`.

Before:
```kotlin
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black
    ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Color.Black)
            ) {
```

After:
```kotlin
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
```

The import for `WindowInsets` is already covered: `import androidx.compose.foundation.layout.*` on line 8 brings in `WindowInsets` (verified during planning). No import changes needed in this file.

`.padding(innerPadding)` was the only usage of the lambda parameter — renaming to `_` silences the warning.

- [ ] **Step 2: Compile-gate**

Run: `./gradlew.bat :app:assembleDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Lint-gate**

Run: `./gradlew.bat :app:lintDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL. Finding count still **51**.

- [ ] **Step 4: Unit-suite gate**

Run: `./gradlew.bat :app:testDebugUnitTest --no-daemon -q`
Expected: BUILD SUCCESSFUL. 1054 / 0 / 0 / 83.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt
git commit -m "feat(ui): RecordScreen Scaffold stops consuming insets

contentWindowInsets=WindowInsets(0) on the RecordScreen Scaffold; the
inner Box drops its .padding(innerPadding). The 6 per-element
windowInsetsPadding calls already in RecordScreen.kt (recovery chip,
idle WarningCenter, active HUD, settings card, top overlay, camera
controls) become the sole inset layer. RecordBottomNav.kt:369 keeps its
own windowInsetsPadding(WindowInsets.navigationBars).

Refs: docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-a-design.md §5.4"
```

---

## Task 5: DualPreviewZone drops opaque zone background

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt:95`

`PreviewZone` paints each TextureView container with `RecordChromeTokens.camZoneBackground` (`#060D18`) before the TextureView attaches. When the preview's aspect doesn't match the zone's, this fill shows as a hard rectangle around the preview — the visible "letterbox slab" in the current screenshots. Drop the background. During the brief TextureView-attach window (~50ms), the underlying Scaffold containerColor (`Color.Black`) shows through — visually identical to the current behaviour (both black).

- [ ] **Step 1: Edit line 95**

Replace line 95 of `app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt`.

Before:
```kotlin
    Box(modifier = modifier.background(RecordChromeTokens.camZoneBackground)) {
```

After:
```kotlin
    Box(modifier = modifier) {
```

The `import androidx.compose.foundation.background` line may become unused IF this is the only consumer of `Modifier.background` in this file. Check the file with:

```bash
grep -n "\.background(" app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt
```

If there are zero remaining `.background(` calls, also remove the `import androidx.compose.foundation.background` line. The `RecordChromeTokens` import may also become unused if `camZoneBackground` was its only reference in this file — re-grep:

```bash
grep -n "RecordChromeTokens\." app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt
```

If zero remaining hits, remove the `import com.aritr.rova.ui.theme.RecordChromeTokens` line too. Leave both imports in place if other references exist.

- [ ] **Step 2: Compile-gate**

Run: `./gradlew.bat :app:assembleDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Lint-gate**

Run: `./gradlew.bat :app:lintDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL. Finding count still **51**.

- [ ] **Step 4: Unit-suite gate**

Run: `./gradlew.bat :app:testDebugUnitTest --no-daemon -q`
Expected: BUILD SUCCESSFUL. 1054 / 0 / 0 / 83.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/DualPreviewZone.kt
git commit -m "feat(ui): drop opaque camZoneBackground on PreviewZone Box

Each zone's Box no longer paints RecordChromeTokens.camZoneBackground
behind the TextureView. The Scaffold containerColor (Color.Black) is
visible during the TextureView attach window — identical to the
current pre-attach visual. After attach, the TextureView fills the
zone with the live preview.

Refs: docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-a-design.md §5.5"
```

---

## Task 6: Delete `camZoneBackground` token

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt:72-73`
- Modify: `docs/UI_DESIGN_TOKENS.md:319-326`

After Task 5, `camZoneBackground` has zero consumers (verified by `grep` during planning — only references were `RecordChromeTokens.kt:73` self-decl and `DualPreviewZone.kt:95` consumer). Delete the declaration and remove the documentation row.

- [ ] **Step 1: Delete the token declaration**

In `app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt`, delete lines 72-73:

Before:
```kotlin
    /** `.cam-zone` background — `#060d18`. */
    val camZoneBackground = Color(0xFF060D18)
```

After: (lines simply removed; the file's surrounding tokens remain unchanged)

- [ ] **Step 2: Verify zero remaining references**

Run:

```bash
grep -rn "camZoneBackground" app/ docs/UI_DESIGN_TOKENS.md
```

Expected output: only `docs/UI_DESIGN_TOKENS.md:324` (the row to be removed in Step 3). No `app/` hits. Historical refs in `docs/superpowers/plans/` and `docs/superpowers/specs/` are immutable artifacts — leave them.

- [ ] **Step 3: Remove docs row**

In `docs/UI_DESIGN_TOKENS.md`, locate the §2.13.1 Camera-zone framing table (around lines 319-326). Remove the `camZoneBackground` row only.

Before:
```markdown
| Token | Value | Source CSS |
|---|---|---|
| `splitDivider` | `Color.White.copy(alpha = 0.14f)` | `.cam-split-divider` |
| `camZoneBackground` | `Color(0xFF060D18)` | `.cam-zone` background |
| `cameraGridLine` | `Color.White.copy(alpha = 0.018f)` | `.camera-grid` line |
| `focusFrameStroke` | `Color.White.copy(alpha = 0.20f)` | `.focus-frame` bracket |
```

After:
```markdown
| Token | Value | Source CSS |
|---|---|---|
| `splitDivider` | `Color.White.copy(alpha = 0.14f)` | `.cam-split-divider` |
| `cameraGridLine` | `Color.White.copy(alpha = 0.018f)` | `.camera-grid` line |
| `focusFrameStroke` | `Color.White.copy(alpha = 0.20f)` | `.focus-frame` bracket |
```

- [ ] **Step 4: Compile-gate**

Run: `./gradlew.bat :app:assembleDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL. (If you missed a reference the deletion would fail compilation.)

- [ ] **Step 5: Lint-gate**

Run: `./gradlew.bat :app:lintDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL. Finding count still **51**.

- [ ] **Step 6: Unit-suite gate**

Run: `./gradlew.bat :app:testDebugUnitTest --no-daemon -q`
Expected: BUILD SUCCESSFUL. 1054 / 0 / 0 / 83.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt docs/UI_DESIGN_TOKENS.md
git commit -m "chore(ui): delete unused camZoneBackground token

After Slice A's DualPreviewZone.kt:95 dropped its consumer, the
RecordChromeTokens.camZoneBackground declaration has zero references
across app/. Remove the declaration and the documentation row.

Refs: docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-a-design.md §5.6"
```

---

## Task 7: History / Settings audit

**Files:**
- Audit only: `app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt`
- Audit only: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt`

Both screens already use `Scaffold` + `TopAppBar` (verified during planning: `HistoryScreen.kt:256, 261, 332` Scaffold/TopAppBar lines; `SettingsScreen.kt:124, 127` Scaffold/TopAppBar lines). Material 3 `Scaffold` defaults `contentWindowInsets = ScaffoldDefaults.contentWindowInsets`, which respects `WindowInsets.systemBars` — so each screen handles its own insets automatically once the MainScreen NavHost double-pad is removed. **This task expects no source changes** but verifies the assumption by re-grepping. If either screen turns out to have a bare-`Column` root somewhere we missed, add a one-line fix.

- [ ] **Step 1: Confirm HistoryScreen root is a Scaffold**

Run:

```bash
grep -nE "^@Composable$|^fun HistoryScreen\(|    Scaffold\(" app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt | head -10
```

Expected output (line numbers may drift; structure matters):

```
98:fun HistoryScreen(
256:        Scaffold(
332:                    TopAppBar(
```

If the `Scaffold(` line is missing or appears AFTER any earlier `Column(modifier = ...)` that styles the root, the root is bare — apply the fallback in Step 3.

- [ ] **Step 2: Confirm SettingsScreen root is a Scaffold**

Run:

```bash
grep -nE "^@Composable$|^fun SettingsScreen\(|    Scaffold\(" app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt | head -10
```

Expected output:

```
94:fun SettingsScreen(settingsViewModel: SettingsViewModel, onBack: () -> Unit = {}) {
124:    Scaffold(
```

If absent, fallback in Step 3.

- [ ] **Step 3: Fallback (only if Step 1 or Step 2 found a bare-Column root)**

Locate the screen's outermost composable (the very first `Box`, `Column`, etc. inside the `fun ScreenName(...)` body). Add `.windowInsetsPadding(WindowInsets.systemBars)` to its `Modifier` chain. Example:

Before:
```kotlin
@Composable
fun HistoryScreen(...) {
    Column(modifier = Modifier.fillMaxSize()) { ... }
}
```

After:
```kotlin
@Composable
fun HistoryScreen(...) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
    ) { ... }
}
```

Add imports if absent:

```kotlin
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
```

(Per planning verification both screens use Scaffold + TopAppBar; expectation is this step is a no-op.)

- [ ] **Step 4: Compile-gate**

Run: `./gradlew.bat :app:assembleDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Unit-suite gate**

Run: `./gradlew.bat :app:testDebugUnitTest --no-daemon -q`
Expected: BUILD SUCCESSFUL. 1054 / 0 / 0 / 83.

- [ ] **Step 6: Commit (or skip if Step 3 was a no-op)**

If Step 3 made changes:

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/HistoryScreen.kt app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt
git commit -m "fix(ui): History/Settings root windowInsetsPadding fallback

After MainScreen's NavHost stopped consuming insets in Slice A Task 3,
the bare-Column root of [HistoryScreen|SettingsScreen] needed an
explicit windowInsetsPadding(WindowInsets.systemBars) to keep
content out from under the status bar.

Refs: docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-a-design.md §5.7"
```

If Step 3 was a no-op, no commit. Move directly to Task 8.

---

## Task 8: ADR-0011 + final gates + smoke handoff

**Files:**
- Create: `docs/adr/0011-edge-to-edge-record-home.md`

The slice introduces a small but consequential architectural shift: inset ownership moves from layout containers (NavHost / outer Scaffolds) to per-element overlays. That choice deserves a recorded rationale next to ADR-0009 + ADR-0010.

- [ ] **Step 1: Create the ADR file**

Create `docs/adr/0011-edge-to-edge-record-home.md` with the content below.

```markdown
# ADR-0011 — Edge-to-edge record-home: per-element inset ownership

**Status:** Accepted

**Date:** 2026-05-23

## Context

After ADR-0010 (DualShot preview crop-to-fill, merged 2026-05-22 @ `e07d914`), the record screen's GL-rendered preview surface inside each DualShot zone fills its container with no internal letterbox. Yet on-device screenshots (Samsung SM-A176B, 2026-05-23) showed the screen still reading as three discrete horizontal slabs: an opaque status-bar band on top, the preview itself, and an opaque settings + dock + system-gesture-nav region at the bottom. The seams sat outside the GL-rendered region — at the window level.

Four root causes (all citing `master @ e07d914`):

1. `MainScreen.kt:54` wraps `NavHost` in `Modifier.padding(innerPadding)`, consuming every screen's insets at the root layout.
2. `RecordScreen.kt:436` re-applies the same padding via the inner Box — a second insets layer.
3. `Theme.kt:97-98` writes `colorScheme.background.toArgb()` / `colorScheme.surface.toArgb()` to the system bars every recomposition, undoing `MainActivity.kt:23` `enableEdgeToEdge()`.
4. `themes.xml:4` parent `android:Theme.Material.Light.NoActionBar` doesn't set `enforceNavigationBarContrast=false`, so Android 10+ draws its own translucent scrim behind the gesture-nav region on top of ours.

The result: preview never reached the physical screen edges; status bar and gesture-nav region painted in colors that didn't match RecordScreen's `Color.Black` containerColor. Native Samsung camera renders edge-to-edge with no such seams — that is the target.

## Decision

**Insets are owned by overlay elements, not by layout containers. System bars are transparent. The preview surface gets the full window.**

Four concrete reversals:

- **`Theme.kt` `SideEffect`** writes `Color.Transparent.toArgb()` to both `window.statusBarColor` and `window.navigationBarColor`. Icon-appearance writes (`isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars`) are retained for readability across light/dark themes.
- **`themes.xml`** parent → `Theme.Material3.DayNight.NoActionBar`; explicit `android:enforceNavigationBarContrast=false` suppresses the OS auto-scrim.
- **`MainScreen.kt` `Scaffold`** gains `contentWindowInsets = WindowInsets(0, 0, 0, 0)`; `NavHost` modifier drops `.padding(innerPadding)`. Container color flips to `Color.Black` for a unified fallback surface.
- **`RecordScreen.kt` `Scaffold`** mirrors the same: `contentWindowInsets = WindowInsets(0)`, inner Box drops `.padding(innerPadding)`. The 6 per-element `windowInsetsPadding(WindowInsets.statusBars/navigationBars)` calls already at lines 525 / 539 / 578 / 615 / 638 / 658, plus the one at `RecordChrome.kt:369`, become the sole inset layer.
- **`DualPreviewZone.kt:95`** drops `.background(RecordChromeTokens.camZoneBackground)`. The unused token is deleted from `RecordChromeTokens.kt` and `docs/UI_DESIGN_TOKENS.md`.

History and Settings screens already use `Scaffold` + `TopAppBar`, so Material 3's default `ScaffoldDefaults.contentWindowInsets` handles their insets automatically once the NavHost double-pad is gone — no per-screen edits needed.

## Consequences

**Accepted:**
- Inset-padding ownership is no longer co-located in one place (NavHost wrapping). Each screen — and within RecordScreen, each overlay element — is responsible for its own inset clearance. Debugging an inset bug requires looking at the element, not the container.
- Compose Material 3 components that depend on `LocalScaffoldDefaults.contentWindowInsets` (e.g., `BottomAppBar`) on the RecordScreen now see `WindowInsets(0)` and must opt into insets explicitly if mounted there. Not currently a problem — `RecordChrome.kt:369` already calls `windowInsetsPadding(WindowInsets.navigationBars)` directly.
- `RecordChromeTokens.camZoneBackground` is gone. Any future feature that wants a non-black fallback behind a TextureView must re-introduce a token or use a different color.

**Rejected:**
- *Camera-screen-only via WindowInsetsController side effect on enter/exit.* Brittle — `SideEffect` timing can flash a colored bar on navigation transitions; entangles theme + screen lifecycle.
- *Keep NavHost padding, override per-screen.* Doesn't fix the root cause (status-bar repaints in Theme.kt) and leaves the double-pad in place for any future screen that forgets the override.
- *Slice B chrome restructure folded in.* Floating glass dock + pill controls + mode-picker placement = larger blast radius, needs HTML mockup iteration. Split for clean review.

**Hard invariants (preserved by this slice):**
- No `service/dualrecord/**`, `EglRouter`, `AspectFitMath`, `DualVideoRecorder`, muxer, or recording-pipeline behaviour changes — recorded files byte-identical.
- ADR-0009 + ADR-0010 outputs unchanged. `buildCropMatrix` / `buildSideAspectCrop` / `buildPreviewCropMatrix` and all `AspectFitMathTest` assertions untouched.
- `WarningId` / `WarningPrecedence` / `WarningCenterViewModel` / Start-gate / recovery flow untouched.
- `DualPreviewZone` `TextureView` / `SurfaceTexture` / `registerPreviewSurface` lifecycle untouched. P+L `352f` / `225f` zone weights + `cam-split-divider` untouched.
- `MainActivity.kt:23` `enableEdgeToEdge()` untouched.

## Future work

**Slice B** — floating chrome restructure: dock fill becomes a translucent scrim/gradient instead of `bottomNavFill`; settings row + tray restyled as glass pills; mode-picker placement reconsidered (native camera puts it just above the gesture nav). Own brainstorm → spec → plan → PR. Requires HTML mockup iteration; not folded into Slice A to keep the blast radius small.

**OEM rollback path** — some Samsung One UI versions may ignore `android:enforceNavigationBarContrast=false` and continue drawing the auto-scrim. If on-device smoke surfaces a leftover scrim under gesture nav, the rollback is a single-attribute revert in `themes.xml`; the rest of the slice still delivers the bar-transparency + double-pad fix.
```

- [ ] **Step 2: Final assembleDebug gate (whole branch)**

Run: `./gradlew.bat :app:assembleDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Final lintDebug gate**

Run: `./gradlew.bat :app:lintDebug --no-daemon -q`
Expected: BUILD SUCCESSFUL. Finding count at **51**.

Compare against the baseline:

```bash
grep -oE "<issue[^>]*severity" app/build/reports/lint-results-debug.xml | sort | uniq -c
```

Should show the same severity breakdown as `master @ e07d914` (3 Error / 48 Warning / 0 Information — the project ships with these as known carries).

- [ ] **Step 4: Final unit-suite gate**

Run: `./gradlew.bat :app:testDebugUnitTest --no-daemon -q`
Expected: BUILD SUCCESSFUL. **1054 tests / 0 failures / 0 ignored / 83 classes** — identical to baseline.

Aggregate check:

```bash
grep -h -oE 'tests="[0-9]+" skipped="[0-9]+" failures="[0-9]+" errors="[0-9]+"' app/build/test-results/testDebugUnitTest/*.xml | \
  awk -F'"' '{t+=$2; s+=$4; f+=$6; e+=$8} END {print "tests="t" skipped="s" failures="f" errors="e}'
```

Expected: `tests=1054 skipped=0 failures=0 errors=0`

- [ ] **Step 5: Commit the ADR**

```bash
git add docs/adr/0011-edge-to-edge-record-home.md
git commit -m "docs(adr): ADR-0011 edge-to-edge record-home — per-element inset ownership

Records the architectural decision behind Slice A: inset-padding
ownership shifts from NavHost / outer Scaffolds to per-element
overlays. System bars become transparent. DualPreviewZone background
deleted. References ADR-0010 (DualShot preview crop) as the most
recent neighbor; explicitly defers floating chrome restructure to a
future Slice B brainstorm.

Refs: docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-a-design.md §5.8"
```

- [ ] **Step 6: Push branch**

```bash
git push -u origin feat/edge-to-edge-record-home-slice-a
```

- [ ] **Step 7: Open PR**

```bash
gh pr create --title "feat(ui): edge-to-edge immersive record-home — Slice A" --body "$(cat <<'EOF'
## Summary
- Slice A removal patch: system bars become transparent, NavHost stops consuming insets, RecordScreen Scaffold stops consuming insets, DualPreviewZone background dropped, Material 3 theme parent + enforceNavigationBarContrast=false.
- Hard invariants preserved byte-for-byte: no service/dualrecord/**, EglRouter, AspectFitMath, encoder, muxer, recording-pipeline changes. ADR-0009 + ADR-0010 outputs unchanged. Start-gate / WarningPrecedence / recovery flow untouched.
- New master baseline predicted: **1054 tests / 83 classes / 0-0-0; lint 51 (3 E + 48 W + 0 I); assembleDebug OK** — identical to branch-point baseline (slice adds zero tests, removes a deprecated token, no lint surface area change).

## Spec & ADR
- Spec: `docs/superpowers/specs/2026-05-23-edge-to-edge-record-home-slice-a-design.md`
- Plan: `docs/superpowers/plans/2026-05-23-edge-to-edge-record-home-slice-a.md`
- ADR: `docs/adr/0011-edge-to-edge-record-home.md`
- Mockup (local-only, gitignored): `mockups/new_uiux/01c-record-home-edge-to-edge.html`

## Test plan (owner — Samsung SM-A176B on-device)
- [ ] Portrait mode: status bar transparent over preview; preview fills full height including under status icons; bottom dock + gesture-nav region share one continuous tone; no visible seam.
- [ ] Landscape mode: same checks rotated.
- [ ] DualShot P+L: both zones full-bleed; cam-split-divider visible as 2dp line; status bar transparent over top zone; gesture-nav region merges with dock.
- [ ] HistoryScreen: title still readable (not under status bar); back button reachable.
- [ ] SettingsScreen: same.
- [ ] Light theme: status-bar icons dark, readable.
- [ ] Dark theme: status-bar icons light, readable.
- [ ] Recording start: active HUD + status pills sit correctly above gesture nav.
- [ ] Recovery banner / Snackbar: sits above gesture nav, readable.
- [ ] OEM rollback: if One UI shows a leftover gesture-nav scrim, single-attribute revert in `themes.xml` (line `enforceNavigationBarContrast`) — slice still ships the bar-transparency + double-pad win.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review (planner)

**1. Spec coverage:**
- §5.1 Theme.kt → Task 1 ✓
- §5.2 themes.xml → Task 2 ✓
- §5.3 MainScreen.kt → Task 3 ✓
- §5.4 RecordScreen.kt → Task 4 ✓
- §5.5 DualPreviewZone.kt → Task 5 ✓
- §5.6 RecordChromeTokens.kt + UI_DESIGN_TOKENS.md → Task 6 ✓
- §5.7 History/Settings audit → Task 7 ✓
- §5.8 ADR-0011 → Task 8 Step 1 ✓
- §6 data flow → no code (already correct per the existing per-element padding calls) ✓
- §7 edge cases → addressed in ADR Consequences + smoke checklist in Task 8 Step 7 ✓
- §8 testing → compile/lint/unit gates per task; on-device smoke in PR checklist ✓
- §9 file table → covered by Tasks 1-7 ✓
- §10 hard invariants → quoted in plan header + ADR ✓
- §11 owner follow-up → Task 8 PR test plan ✓

**2. Placeholder scan:** Every step shows exact diff or exact command. No "TODO", "TBD", "implement later", or "similar to Task N". Task 7 Step 3 includes the full fallback code even though it is expected to be a no-op (defensive). Task 8 ADR content is given in full.

**3. Type consistency:**
- `WindowInsets(0, 0, 0, 0)` used identically in Tasks 3 and 4 ✓
- `Color.Transparent.toArgb()` used identically in Task 1 for both bars ✓
- File paths cited identically across spec, plan, and ADR ✓
- `camZoneBackground` deletion order: Task 5 drops consumer first, Task 6 deletes the declaration second (prevents an intermediate broken-compilation state) ✓
- ADR text in Task 8 Step 1 references the exact line numbers cited in earlier tasks (lines 54, 97-98, 436, 525/539/578/615/638/658, 95) ✓

Plan validated. No gaps.
