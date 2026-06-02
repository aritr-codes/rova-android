# i18n Phase B-i — Locale infrastructure + pseudolocale, gated picker

**Date:** 2026-06-02
**Track:** Track-B settings expansion · follows B3 Phase A (externalization, ADR-0022)
**Status:** Approved design — ready for implementation plan

## Goal

Build the runtime locale-switching machinery and a gated in-app **Language** picker, validated by Android pseudolocales, with **zero user-visible change** in this slice. The picker is built but stays hidden until at least one real, reviewed translated catalog exists. No AppCompat dependency is added.

## Why this scoping

Phase A (ADR-0022) externalized all ~470 strings + 10 plurals into `res/values/`, so the app is now *localizable*. Phase B is *localization*. From an end-user UX standpoint the two failure modes are asymmetric:

- A **"Language" setting that only lists English** is an empty promise — reads as placeholder/broken.
- **Unreviewed machine-translated copy** in a privacy-sensitive recording app is worse — wrong wording on permission prompts, "delete is permanent", recording-state, and recovery copy erodes trust exactly where it matters most.

So this slice ships **no false promise**: it builds and proves the switching machinery (incl. RTL and text-expansion) using **pseudolocales** (dev/QA only, never advertised), and keeps the picker hidden until a real reviewed language lands. Each real language then arrives as its own small follow-on slice: a pure `values-xx/` drop-in plus one line in the supported-locales list. This matches Google's recommended workflow (AndroidX picker pattern + pseudolocales as the localizability test harness).

## Decisions locked during brainstorming

1. **Scope:** infra + pseudolocale, gated picker. No real translated catalog this slice.
2. **Locale-apply mechanism:** native `LocaleManager` on API 33+, plus a thin `attachBaseContext` `ContextWrapper` backport for API 24–32. **No AppCompat** — the app is deliberately `ComponentActivity` + Compose, edge-to-edge (ADR-0011) with route-aware system bars (B2); converting to `AppCompatActivity` would risk regressing both for no current benefit.

## Architecture

The app already persists `themeMode` in `RovaSettings` (SharedPreferences `rova_settings`, coerced through the pure `themeModeFromStorage` helper) and collects it *above* `RovaTheme` in `MainActivity` for a recreate-free live theme switch.

**Locale cannot use that recreate-free path.** Theme is a Compose-only concern; a locale change re-resolves Android **resources**, which requires a `Configuration` change and therefore an Activity `recreate()`. This asymmetry is intentional and documented so no one later tries to "fix" the locale switch to match the theme switch.

### Components

All new logic follows the house pure-helper + thin-seam pattern so the framework-free parts are unit-testable under `isReturnDefaultValues = true`.

| Unit | Kind | Responsibility |
|---|---|---|
| `ui/locale/AppLocale.kt` | **pure** (JVM-testable) | Canonical locale model. `SUPPORTED_USER_LOCALES: List<String>` (BCP-47 tags of real, reviewed catalogs — **`emptyList()` this slice**). `localeTagFromStorage(raw: String?): String?` (null/blank/unknown → null = system default). `languagePickerOptions(): List<LocaleOption>` = system-default sentinel + supported tags. `shouldShowLanguagePicker(options): Boolean` = there is ≥1 real language beyond system default. |
| `ui/locale/LocaleApplier.kt` | thin framework seam | `apply(context, tag: String?)`: on API 33+ sets `LocaleManager.applicationLocales = LocaleList.forLanguageTags(tag)` (empty list = follow system). Exposes the pure tag→`Locale` selection (testable) used by the backport `Configuration` wrap. |
| `RovaSettings.localeTag` | persistence | `get`/`set` String under key `locale_tag` in the existing `rova_settings` SharedPreferences; `null` = system default. Read coerces through `AppLocale.localeTagFromStorage`. Single source of truth; on API 33+ the value is mirrored into `LocaleManager` so in-app and system-settings changes converge. |
| `MainActivity.attachBaseContext(base)` | override | On API 24–32, wraps `base` with a `Configuration` whose locale is the persisted tag (via `AppLocale` + `LocaleApplier`), so Compose `stringResource` resolves the override. No-op on API 33+ (LocaleManager already applied the locale to the context). |
| `SettingsViewModel.localeTag: StateFlow<String?>` + `setLocale(tag)` | VM | `setLocale`: persist to `RovaSettings` → `LocaleApplier.apply` → request `activity.recreate()`. Stores only the tag (a `String?`), never resolved copy. |
| Settings **Language** row + picker sheet | UI | Lives in the Appearance section directly under the Theme row, mirroring the existing theme picker (`onPick` → `setLocale`). **Rendered only when `AppLocale.shouldShowLanguagePicker(...)` is true** → absent this slice. |
| `res/xml/locales_config.xml` | resource | Hand-authored `<locale-config>` listing only real shippable locales (just `en` now). Referenced by `android:localeConfig` in the manifest. |

### Apply path detail

- **API 33+:** `getSystemService(LocaleManager).applicationLocales = LocaleList.forLanguageTags(tag)`. An empty/`null` tag sets an empty `LocaleList` = "follow system". The framework recreates the Activity and keeps the app in sync with the Android 13+ system per-app-language screen automatically.
- **API 24–32:** persist the tag, then `attachBaseContext` builds a wrapped `Context` from `base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })`. A change made through the picker calls `recreate()` so the new base context is attached. The pure tag→`Locale` choice is unit-tested; only the `Configuration`/context wrap touches the framework.

### Picker gating — the "no empty promise" rule

`languagePickerOptions()` returns `[SystemDefault] + SUPPORTED_USER_LOCALES`. With `SUPPORTED_USER_LOCALES == emptyList()`, `shouldShowLanguagePicker` is `false` and the Settings row is not composed at all. The first real-language slice appends one BCP-47 tag (e.g. `"es"`) to that list and adds the matching `values-es/` catalog + a `<locale>` line in `locales_config.xml`; the row then renders as `[System default, English, Español]`. **Pseudolocale tags are never members of `SUPPORTED_USER_LOCALES`.**

### localeConfig, pseudolocale, RTL

- **localeConfig is hand-authored**, not generated. AGP's `androidResources.generateLocaleConfig = true` derives the list from resource folders and would publish pseudolocale folders (`values-en-rXA`, `values-ar-rXB`) into the system per-app-language list. We leave it off and maintain the XML by hand.
- **Pseudolocales** are enabled only for the debug build type: `buildTypes.getByName("debug") { isPseudoLocalesEnabled = true }`. This bakes `en_XA` (accent + ~30–40% text expansion + bracket bounds) and `ar_XB` (RTL bidi mirror) into the debug APK for QA. Release is untouched. Pseudolocales are selected for testing via system/developer settings; they are not offered in the in-app picker.
- **RTL:** `android:supportsRtl="true"` is already set. RTL and text-expansion fixes in this slice are **scoped to issues that `ar_XB`/`en_XA` actually surface during the slice's device smoke** (e.g. a hardcoded `start`/`end`, a fixed width that clips expanded text). This is not a full pre-audit of every Compose file — that would be open-ended and is explicitly out of scope.

## Governance

### ADR-0023 — Localization (Phase B)

New ADR recording: the no-AppCompat decision and its rationale (edge-to-edge + route-aware system bars), the API-33+/24–32 split, the picker-gating rule, the hand-authored `locales_config.xml` and the `generateLocaleConfig`-off decision, and that pseudolocales are a dev/QA tool only. References ADR-0022 (Phase A) as its predecessor.

### `checkLocaleConfigNoPseudolocale` — 27th static gate

A new `check*` task registered in `app/build.gradle.kts` and wired into `preBuild` via the existing `afterEvaluate { tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(...) } }` block, matching the existing 26 checks' convention (ADR-clause → `check*` → `preBuild`).

- **Scans:** `app/src/main/res/xml/locales_config.xml`.
- **Fails if:** the file contains a pseudolocale tag (`en-XA`, `ar-XB`, case-insensitive, hyphen or `r`-qualifier forms) — i.e. a pseudolocale leaked into the advertised user-language list.
- **Failure message:** cites **ADR-0023 §No Pseudolocale In LocaleConfig**, in the existing gates' `relativePath:line` report format.

This is a tripwire (line/substring regex) consistent with the pragmatic nature of the existing gates, not a correctness proof.

## Testing (JVM unit tests only)

Pure-helper coverage in `app/src/test/...`:

- `AppLocale.localeTagFromStorage`: `null`/blank/garbage → `null` (system); a known supported tag → itself; an unknown but well-formed tag → `null` (coerce to system, mirroring `themeModeFromStorage`).
- `AppLocale.languagePickerOptions`: with `emptyList()` supported → only the system-default sentinel; with one tag → sentinel + that option.
- `AppLocale.shouldShowLanguagePicker`: empty/system-only → `false`; ≥1 real language → `true`.
- Tag → `Locale` selection used by the backport wrap (the pure part of `LocaleApplier`): correct `Locale` for a tag, system sentinel → null/identity.

No Robolectric; the `Configuration`/context wrap and `LocaleManager` call are thin seams exercised by the device smoke, not unit tests.

## Verification gate (slice exit)

- `:app:testDebugUnitTest` — baseline + new pure-helper tests, 0-0-0.
- `:app:lintDebug` — 27 checks via `preBuild` (incl. the new `checkLocaleConfigNoPseudolocale`, proven RED→GREEN).
- `:app:assembleDebug` — debug APK builds with `isPseudoLocalesEnabled`.
- **Device smoke (owner):** install debug build; confirm (a) no visible change in normal use — Language row absent; (b) `en_XA` via developer settings shows accented/expanded copy app-wide with no clipping regressions surfaced; (c) `ar_XB` mirrors layout RTL with no broken `start`/`end`; fix what surfaces.

## Non-goals (deferred to later slices)

- No real translated catalog; no picker reveal (gated off).
- No full RTL pre-audit beyond what pseudolocales surface this slice.
- No professional/human translation pipeline or string-freeze process.
- Each real language ships as its own follow-on slice: `values-xx/` + one `SUPPORTED_USER_LOCALES` entry + one `locales_config.xml` line + native/professional review.

## Hard invariants preserved

- No existing `check*` gate edited; this adds the 27th. Phase A's `checkNoHardcodedUiStrings` (26th) is untouched.
- No AppCompat dependency; `MainActivity` stays `ComponentActivity`.
- ViewModels/helpers store only a `String?` tag, never resolved English copy (consistent with ADR-0022 §No Hardcoded UI Strings).
- House conventions: pure-helper extraction (`AppLocale`), thin framework seam (`LocaleApplier`, `attachBaseContext`), ADR-clause → `check*` → `preBuild`.
