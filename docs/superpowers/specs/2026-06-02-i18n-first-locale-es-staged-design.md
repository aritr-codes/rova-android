# i18n — First real locale (Spanish, staged) — Design

**Date:** 2026-06-02
**Status:** Approved (brainstorming)
**Owner:** Rova owner
**Builds on:** ADR-0023 (i18n Phase B-i — locale infra + gated picker, PR #82 / master `9fdc0f6`)

## Goal

Ship the first real translated catalog — **Spanish (`es`)** — as a *staged* locale: the full `values-es/` catalog exists and is reachable through Android's **system** per-app-language list (API 33+), while the **in-app** Language picker stays hidden pending a native-review sign-off. One follow-up slice later reveals the in-app row.

## Decisions (from brainstorming)

1. **Locale:** Spanish (`es`). LTR, Latin script, ~20–30% text expansion (already pseudolocale-validated via `en_XA`). No RTL/mirroring this slice.
2. **Production/review model:** *I* (Claude) produce the full catalog; it ships **marked unreviewed/staged**. The in-app picker stays GATED until a native reviewer signs off later. No empty-promise *in-app*.
3. **Staging access:** `es` is added to `locales_config.xml` → appears in **Android Settings ▸ App ▸ Language** (API 33+). Reviewer uses the normal system path. **Accepted trade-off:** release users on 33+ can also select the unreviewed Spanish via system settings.

## Scope

### In

- **`app/src/main/res/values-es/strings.xml`** — all **472** strings from `values/strings.xml`, translated.
- **`app/src/main/res/values-es/plurals.xml`** — all **10** plurals, with Spanish `one`/`other` categories.
- **`locales_config.xml`** — add `<locale android:name="es" />` (after `en`).
- **`AppLocale.SUPPORTED_USER_LOCALES`** — **stays `emptyList()`** (in-app row stays hidden). No Kotlin logic change.
- **`SpanishCatalogParityTest`** — pure-JVM catalog-integrity test (see Testing).
- **ADR-0023 amendment** — new `§Staged Locale Before Reveal` clause + correction to the §Picker-gating "first real-language slice" sentence (decouple catalog/locales_config from `SUPPORTED_USER_LOCALES`).
- **CHANGELOG** — one bullet under `[Unreleased] ▸ Added`.

### Out (deferred)

- Revealing the in-app picker (flip `SUPPORTED_USER_LOCALES` → `["es"]`) — separate follow-up after native sign-off.
- Any 2nd locale, RTL (`ar`), string re-org.
- The native-reviewer polish pass itself.

## Mechanism (how it behaves, given existing infra)

- **API 33+:** the system per-app-language picker (populated from `locales_config`) calls `LocaleManager` directly; the framework applies the config + recreates. `LocaleApplier.wrapContext` is already a no-op on 33+, and `RovaSettings.localeTag` is only written by the *in-app* `setLocale` — which stays hidden — so the staged path touches no app persistence. Works automatically.
- **API 24–32:** no system per-app-language list exists (a 33+ feature), and the in-app picker is hidden, so Spanish is **unreachable** until reveal. Staging review is performed on a 33+ device. The catalog still resolves correctly once the in-app picker is later revealed (which drives the 24–32 `attachBaseContext` path).

No change to `LocaleApplier`, `RovaSettings`, `SettingsViewModel`, `SettingsScreen`, `MainActivity`.

## Translation register & conventions

- **Actions/labels** → neutral infinitive: e.g. *Grabar, Detener, Ajustes, Exportar, Compartir*.
- **Direct address** → informal **tú** (e.g. *"Permite que Rova…"*), not formal *usted* — fits a personal recording app.
- **Untranslated:** brand **Rova**; feature name **DualShot**; established technical terms users expect (*Bitrate*).
- **Preserve verbatim per key:** every format arg (`%1$s`, `%2$d`, `%d`, `%s`) — same count, order, and positional index; every `xliff:g` non-translatable; any embedded markup.
- **Plurals:** Spanish uses `one`/`other` (same two categories as English) — 1:1 mapping, no new categories.
- **Length:** Spanish ~20–30% longer; layouts were pseudolocale-validated at +30% (`en_XA`) in B-i, so they should hold. The implementer flags in the plan any string that is a known tight-fit risk (e.g. fixed-width pills/chips, single-line HUD labels) for device spot-check.

## Integrity guard (correctness gate — distinct from native *polish* review)

Two layers:

1. **Android lint (live preBuild gate).** `lintDebug` runs `MissingTranslation` / `ExtraTranslation` and format-arg consistency checks across `values-es/` automatically. A missing key, extra key, or format-arg-count/type mismatch fails the build (lint `abortOnError` default = true; `lintDebug` is a preBuild dependency). **Confirmed:** the only lint disables in `app/build.gradle.kts` are `PermissionLaunchedDuringComposition` and `Typos` — `MissingTranslation`/`ExtraTranslation` are live. (`Typos` being off also avoids Spanish-word false positives.) No new suppression is added.
2. **`SpanishCatalogParityTest` (pure-JVM, defense-in-depth).** Parses `values/strings.xml` and `values-es/strings.xml`; asserts:
   - identical set of string `name`s (no missing, no extra) — excluding any `translatable="false"` entry from the `values/` side (currently **0**; excluded for future-proofing, since translating a non-translatable is an `ExtraTranslation` error),
   - identical ordered placeholder signature per key (the multiset/sequence of `%n$s` / `%d` tokens matches),
   - plural parity: same set of `<plurals name=…>` and each `es` plural has at least `one` + `other`.
   - Pins the invariant explicitly so it survives any future lint-config drift. House pattern: pure JVM, no Android types (parse XML as text/DOM).

## ADR-0023 amendment

Add clause:

> **§Staged Locale Before Reveal.** A locale catalog MAY ship in `locales_config.xml` (system-reachable on API 33+) while `AppLocale.SUPPORTED_USER_LOCALES` still excludes it (in-app Language row hidden), pending native-review sign-off. This decouples *catalog existence* from *in-app reveal*: the system per-app-language path may expose a staged (unreviewed) catalog; the in-app picker's "no empty-promise" invariant is unchanged. **`es` is the first staged locale.** Reveal = a later slice that appends the tag to `SUPPORTED_USER_LOCALES` after sign-off.

Correct the existing §Picker-gating sentence ("The first real-language slice appends one tag (+ `values-xx/` catalog + one `<locale>` line)…") to reflect that catalog + `locales_config` line land in the *staging* slice and the `SUPPORTED_USER_LOCALES` append lands in the *reveal* slice.

No new `check*` task: `checkLocaleConfigNoPseudolocale` already passes for `es` (not a pseudolocale); catalog integrity is covered by lint + the parity test, not a build-script regex.

## Test plan

- `SpanishCatalogParityTest` green (new).
- Full suite **1390 → 1391**, 0-0-0.
- `lintDebug` green incl. `MissingTranslation`/`ExtraTranslation` over `values-es/` + all 27 existing `check*` gates (incl. `checkLocaleConfigNoPseudolocale`).
- `assembleDebug` green.
- **Device (staging review, 33+):** Settings ▸ App ▸ Language lists *Español*; selecting it renders Spanish across the 12 UI surfaces; no clipped/overflowing labels; format args render correctly (counts, durations, file names). Flagged tight-fit strings spot-checked.

## House-convention compliance

- Pure-helper / thin-seam untouched; no Kotlin churn (resource-and-config add only).
- ADR-clause-first: amend ADR-0023 before/with the `locales_config` edit.
- No existing `check*` gate edited.
- JVM-only test policy; new test is pure (XML-as-text parse, no Android framework calls).
