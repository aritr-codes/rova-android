# B2 — Theme switcher (Light / Dark / System)

**Date:** 2026-05-31
**Track:** B (Settings expansion), slice 2. Follows [B1](2026-05-30-b1-settings-expansion-design.md).
**Status:** Design — approved reach + mechanism, pending spec review.

## 1. Problem

`RovaTheme(darkTheme = true, …)` is **hard-pinned to dark** ([Theme.kt:86](../../../app/src/main/java/com/aritr/rova/ui/theme/Theme.kt)). The pin was added in M4 (2026-05-27) because fresh-install light-mode users landed on an unfinished light scheme. A `lightColorScheme` already exists and is structurally complete — every M3 slot is populated. There is no user-facing theme control and no `RovaSettings.themeMode`.

Goal: a **Light / Dark / System** switcher in App Settings, applied live (no Activity recreate), defaulting to **System**, meeting **WCAG 2.2 AA (ADR-0020)**.

## 2. Audit finding (why "unfinished")

The light scheme is not a palette gap — it is ~60 UI sites that bake a dark assumption (hardcoded `Color(0x…)`, `Color.White`/`Color.Black`, dark palette constants) instead of reading `MaterialTheme.colorScheme.*`. Those sites cluster precisely where the design intends to stay dark:

| Stays dark (out of scope) | Why |
|---|---|
| Record viewfinder chrome (`RecordChromeTokens`, `RecordChrome`, `RecordChromeIcons`) — ~15 sites | Camera HUD; intentionally dark; only a dark mockup exists |
| `PlayerScreen` — 19 sites | Video over black |
| `SegmentedTimeline`, `DualPreviewZone` — ~6 sites | Scene/preview visualization over dark media |

| Themes to light (in scope) | State |
|---|---|
| `SettingsScreen`, `SettingsSheet`, `OnboardingScreen`, `OnboardingSlide` | Already GREEN — flip for free |
| `HistoryScreen` (28 `colorScheme` reads) | Mostly GREEN; 2 media-overlay sites stay dark-on-media |
| `MainScreen` Scaffold `containerColor = Color.Black` | 1 fix → `colorScheme.background` |
| `MergeCompleteCard`, `RovaCardComponents` thumbnail overlays | Overlays **on media** stay dark; non-media chrome flips |
| `OnboardingIllustrations` (canvas white strokes, 7 sites) | Onboarding flips light → strokes must use `onBackground`/`onSurface` |

`ContrastMath.kt` (WCAG luminance/contrast helpers, from the a11y stack) and `TokenContrastTest` already exist and are reused to verify light contrast.

## 3. Reach decision (approved)

**App chrome themes to Light; the camera viewfinder, video player, and timeline stay dark always.** This matches camera-app convention, avoids inventing a light record-home design (no light mockup), and keeps the RED-heavy surfaces out of scope.

## 4. Mechanism

### 4.1 Persisted preference
- `enum class ThemeMode { SYSTEM, DARK, LIGHT }`.
- `RovaSettings.themeMode: ThemeMode` in the **backed-up** `prefs` file (a genuine user preference — correct to survive reinstall, unlike `mode`). Stored as the enum `name` string.
- Coercion is a **pure helper** `fun themeModeFromStorage(raw: String?): ThemeMode` (unknown/missing → `SYSTEM`) so it is JVM-testable without touching SharedPreferences; the getter is a thin `themeModeFromStorage(prefs.getString(...))` wrapper. Mirrors the existing `mode` coercion intent but extracted per the project's pure-helper pattern.

### 4.2 Pure resolver (test seam)
```kotlin
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.DARK   -> true
    ThemeMode.LIGHT  -> false
}
```
JVM unit tests: 3 cases (SYSTEM→passthrough both values, DARK→true, LIGHT→false).

### 4.3 ViewModel
`SettingsViewModel.themeMode: MutableStateFlow<ThemeMode>` seeded from `settings.themeMode`; an `init` write-back collector mirrors changes to `RovaSettings` (the established B1 collector pattern: `collect { settings.themeMode = it }`). **Single writer** — only `SettingsViewModel` writes the theme, so no dual-owner resume-reseed is needed (contrast B1's recording defaults).

### 4.4 Theme root (live apply, explicit owner)
Per codex review, the `SettingsViewModel` is **hoisted to `MainActivity`** rather than relying on `viewModel()` resolving to the same activity-scoped instance twice:

```kotlin
setContent {
    val settingsViewModel: SettingsViewModel = viewModel()   // activity-scoped at setContent root
    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val dark = resolveDarkTheme(themeMode, isSystemInDarkTheme())
    RovaTheme(darkTheme = dark) {
        MainScreen(initialTab = initialTab, settingsViewModel = settingsViewModel)
    }
}
```
- `MainScreen` gains a `settingsViewModel` parameter (defaulting to `viewModel()` for previews) instead of creating its own. Removes the `LocalViewModelStoreOwner` ambiguity.
- Picker mutates `themeMode.value` → `collectAsStateWithLifecycle` recomposes `setContent` → `RovaTheme` recomputes `darkTheme` → whole tree re-themes. **No Activity recreate.**
- Status/nav-bar icon polarity already updates: `RovaTheme`'s `SideEffect` keys on `darkTheme` and sets `isAppearanceLightStatusBars/NavigationBars = !darkTheme` ([Theme.kt:97-103](../../../app/src/main/java/com/aritr/rova/ui/theme/Theme.kt)). Live switch flips them correctly.

### 4.5 Mixed-theme islands (codex #7 — the real correctness risk)
When the app is in Light but a surface stays dark, any descendant reading `colorScheme.surface`/`onSurface`/`background` would get **light** values painted over a hardcoded **dark** background → low contrast / inconsistent controls. Fix with a dark `colorScheme` provider that does **not** touch window bars:

```kotlin
@Composable
fun RovaDarkSurface(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = DarkColorScheme, typography = Typography, content = content)
```
Wrap the dark-pinned subtrees at their roots: `RecordScreen` (3 `colorScheme` reads), `PlayerScreen`. `SegmentedTimeline`/`DualPreviewZone` are reached from those subtrees or from media contexts; wrap or convert per the plan's per-surface pass. Net effect: those islands always see the dark scheme regardless of app theme — consistent, no window side-effect, no nested status-bar flip.

### 4.6 Picker UI
Reuse B1's `SettingsOptionSheet<T>` (radio `selectableGroup`, WCAG-clean, no double-announce). New **"Appearance"** `SettingsSection` with one row ("Theme", value = current mode label) opening the sheet with `ThemeMode.entries`. On pick: set `themeMode.value`, close sheet, theme applies live.

## 5. WCAG (ADR-0020)
- Light-scheme text/icon contrast verified by extending `TokenContrastTest` with light-variant assertions over `LightColorScheme` (using `ContrastMath`). Cover: body text, disabled/secondary text, dividers, selected nav item, dialog scrim, error color, and the Appearance radio rows.
- Picker rows inherit B1's accessible `SettingsOptionSheet` semantics. The Appearance row label is a heading-free `SettingsRow`; the sheet title carries `heading()`.

## 6. Out of scope / deferred
- Dynamic color (Material You) — `dynamicColor` stays `false`.
- A light record-home / viewfinder design (no mockup; viewfinder stays dark by decision §3).
- Per-surface light redesign of player/timeline.
- A `checkA11y*` static gate (ADR-0020 still Proposed/unbuilt) — not part of B2.

## 7. Existing-user migration
Fresh installs and existing updaters both default to **System** (no migration flag). Existing users who were on forced-Dark will follow their OS setting after update; they can pick Dark explicitly. Accepted per owner decision.

## 8. Testing
- `resolveDarkThemeTest` — 3 cases.
- `themeModeFromStorage` — null, unknown, and each valid name (pure, JVM).
- `TokenContrastTest` light-variant additions.
- Full gate: `:app:testDebugUnitTest :app:lintDebug` (all 25 `check*` tasks). Real-device manual check of live switching + each in-scope surface in Light (mandatory per CLAUDE.md — emulator OK here since no CameraX recording is exercised by the theme paths, but verify record screen stays dark).
