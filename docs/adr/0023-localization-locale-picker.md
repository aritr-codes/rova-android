# ADR-0023 — Localization: locale-apply mechanism + gated in-app Language picker (i18n Phase B-i)

**Status:** Accepted

**Date:** 2026-06-02

**Deciders:** Rova owner

**Supersedes / amends:** none. Builds on ADR-0022 (Phase A externalization) — only the already-externalized strings are localizable, so Phase B is a resource-and-picker add with no call-site churn.

## Context

Phase A (ADR-0022) moved all ~470 user-facing strings into `res/values/`. Phase B is localization. This first slice (B-i) builds the locale-switching machinery and a gated in-app Language picker, validated by pseudolocales, with **no real translated catalog and no user-visible change**. The picker stays hidden until a real, reviewed language exists.

End-user UX rationale: a "Language" setting that lists only English is an empty promise; unreviewed machine-translated copy in a privacy-sensitive recording app is worse (wrong permission/deletion/recovery copy erodes trust). So this slice ships no false promise — infra + pseudolocale QA only; each real language lands later as its own reviewed `values-xx/` drop-in.

## Decision

### No AppCompat

The app is deliberately `ComponentActivity` + Compose, edge-to-edge (ADR-0011) with route-aware system bars (B2). Converting to `AppCompatActivity` for `AppCompatDelegate.setApplicationLocales` would risk regressing both for no current benefit. The locale backport is hand-rolled instead.

### Locale-apply mechanism (two tiers)

- **API 33+:** `LocaleManager.setApplicationLocales(LocaleList)`. The framework persists the per-app locale, recreates the Activity, and syncs with the Android 13+ system per-app-language screen.
- **API 24–32:** `MainActivity.attachBaseContext` wraps the base `Context` with a `Configuration.setLocale(...)` built from the persisted tag, so Compose `stringResource` resolves the override; a picker change persists the tag then calls `Activity.recreate()`.

`RovaSettings.localeTag` (BCP-47 tag, null = system) is the single source of truth; the setter is called **before** apply (via `SharedPreferences.apply()`, whose in-memory update to the per-process singleton is immediately visible to the `attachBaseContext` read on the 24–32 recreate path — disk-flush timing is irrelevant for the same-process read). Pure logic (tag coercion, picker options, tag→Locale) lives in `AppLocale`; the framework calls live in the thin `LocaleApplier` seam.

### Picker gating — no empty promise

The Settings Language row renders only when `AppLocale.shouldShowLanguagePicker(...)` is true, i.e. `SUPPORTED_USER_LOCALES` is non-empty. It is `emptyList()` this slice → row hidden. The first real-language slice appends one tag (+ `values-xx/` catalog + one `<locale>` line in `locales_config.xml`) and the row appears.

### locales_config + pseudolocales

`res/xml/locales_config.xml` is **hand-authored** (only `en` now) and referenced via `android:localeConfig`. AGP `generateLocaleConfig` is left **off** so debug pseudolocale folders never leak into the system language list. Pseudolocales (`en_XA`, `ar_XB`) are enabled for the **debug build only** (`isPseudoLocalesEnabled = true`) as the localizability QA harness; they are never in `SUPPORTED_USER_LOCALES` or `locales_config.xml`.

### §No Pseudolocale In LocaleConfig — `checkLocaleConfigNoPseudolocale`

The 27th `check*` gate (wired into `preBuild`). Scans `res/xml/locales_config.xml`; fails with a `GradleException` citing this clause if it finds a pseudolocale tag (`en-XA`/`ar-XB`, incl. the `-rXA` resource form, case-insensitive) inside an `android:name` attribute. A line-oriented tripwire consistent with the existing gates, not a correctness proof.

## Non-goals (Phase B-i)

No real translated catalog; no picker reveal; no full RTL pre-audit (only what pseudolocales surface during this slice's device smoke); no professional-translation pipeline. Each real language is a later, review-gated slice.

## Consequences

- A future language = pure resource add: `values-xx/` + one `SUPPORTED_USER_LOCALES` entry + one `locales_config.xml` line + review. No Kotlin churn.
- The hand-authored localeConfig + the new gate lock the pseudolocale-leak shut.
- Cost: the locale switch (unlike the theme switch) requires an Activity recreate, by nature of resource resolution — documented so it is not "fixed" to mirror theme.

## Hard invariants preserved

- No existing `check*` gate edited; this adds the 27th. Phase A's `checkNoHardcodedUiStrings` (26th) is untouched.
- No AppCompat dependency; `MainActivity` stays `ComponentActivity`.
- ViewModels/helpers store only a `String?` tag, never resolved copy (consistent with ADR-0022 §No Hardcoded UI Strings).
- House conventions: pure-helper extraction (`AppLocale`), thin framework seam (`LocaleApplier`, `attachBaseContext`), ADR-clause → `check*` → `preBuild`.

## Implementation reference

`docs/superpowers/specs/2026-06-02-i18n-phase-b-localization-design.md` and `docs/superpowers/plans/2026-06-02-i18n-phase-b-localization.md`.
