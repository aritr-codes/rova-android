# ADR-0022 — User-facing strings live in resources (i18n Phase A: externalization)

**Status:** Accepted

**Date:** 2026-06-02

**Deciders:** Rova owner

**Supersedes / amends:** none (new cross-cutting policy). Interacts with ADR-0020 (WCAG 2.2 AA by default) because `contentDescription` copy is user-facing for screen readers and is now externalized alongside visible text.

## Context

Phase A of internationalization is **externalization**: move all user-facing English copy out of Kotlin and into `res/values/strings.xml` (and `res/values/plurals.xml` for count-based copy), each referenced by a stable, domain-prefixed resource name. This is Track-B slice **B3**.

Before this slice, `res/values/strings.xml` held only ~64 strings (onboarding + notification copy). The rest of the UI was hardcoded Kotlin literals — ~40 `Text("…")` across 13 Compose files, ~31 `contentDescription = "…"` across 15 files, plus button labels, dialog copy, and warning/recovery prose. Estimated 150–250 user-facing strings inline. The app was effectively un-localizable.

The design splits i18n into two strictly-ordered phases:

- **Phase A — externalization (this slice):** every user-facing string moves into resources with a stable name, gated by a build-time check. **No translation, no locale picker, no behaviour change.** English copy is **byte-identical** after the slice.
- **Phase B — localization (a later, separate slice):** `android:localeConfig`, a per-app language picker (`setApplicationLocales` / API 33 `LocaleManager` + AppCompat backport), and translated `values-xx/` catalogs.

Phase B is pointless before Phase A — only the already-externalized strings would translate. Doing Phase A first means Phase B is a **pure resource add with zero Kotlin churn**: drop in `values-xx/` catalogs and a picker, no call-site edits.

This project already governs behavioral invariants through ADRs paired with `check*` static-gate tasks wired into `preBuild` (25 such checks before this slice). Externalization is now the 26th invariant in that net.

## Decision

### §No Hardcoded UI Strings

This is the invariant clause the gate cites by name.

**All user-facing copy at Compose `Text(` / `contentDescription =` call sites MUST be read from resources** — never written as an inline English `String` literal. Resolution is scope-dependent:

| Scope | Mechanism |
|---|---|
| Compose call site, fixed text | `stringResource(R.string.id)` |
| Compose call site, with args | `stringResource(R.string.id, arg1, …)` |
| Compose call site, count-based | `pluralStringResource(R.plurals.id, n, n)` (count passed **twice** — once selects the rule, once fills `%d`) |
| Service / notification builder (has `Context`) | `context.getString(R.string.id)` / `context.resources.getQuantityString(id, n, n)` |
| Pure helper / VM that **selects** fixed copy | returns a bare `@StringRes Int`; caller resolves at its edge |
| Pure helper / VM that **owns args or plural counts** | returns a `UiText` token; caller resolves at its edge |

**ViewModels and pure helpers never store a *resolved* English `String` in state** — only a `@StringRes Int` or a `UiText` token, resolved at the Compose/Context edge. Storing resolved English would freeze the copy and break Phase-B live locale switching. Pure number formatting (`formatMmSs`-style) stays in Kotlin and flows into the resource as a `%s`/`%d` format argument — it is locale-independent arithmetic, not copy.

#### `UiText` seam

A small pure-Kotlin sealed model (`app/src/main/java/com/aritr/rova/ui/text/UiText.kt`) lets helpers/VMs that own arguments or plural counts stay framework-free and unit-testable under `isReturnDefaultValues = true`, while Compose and the service each resolve the same token at their own edge:

```kotlin
sealed interface UiText {
    data class Str(@StringRes val id: Int) : UiText
    data class StrArgs(@StringRes val id: Int, val args: List<Any>) : UiText
    data class Plural(@PluralsRes val id: Int, val quantity: Int, val args: List<Any>) : UiText
}
```

The two resolution edges (`@Composable fun UiText.resolve()` and `fun UiText.resolve(context: Context)`) are the only framework-touching part. A bare `@StringRes Int` is preferred when a helper only **selects** between fixed messages and feeds both a Compose surface and the notification path; reach for `UiText` only when arguments or plurals enter. This follows the house pure-helper extraction pattern (`AspectFitMath`, `ThermalHysteresis`, `ContrastMath`, …).

### Naming convention

Continue the existing domain-prefix style (`onboarding_*`, `notification_*` predate this slice):

| Prefix | Domain |
|---|---|
| `record_*` | Record screen / chrome / HUD |
| `history_*` | History / Library |
| `settings_*` | Settings screen + sheets |
| `player_*` | Player screen |
| `warning_*` | WarningCenter sheets / banners / chips |
| `recovery_*` | RecoveryCard + vendor guidance |
| `onboarding_*` | (existing) onboarding flow |
| `notification_*` | (existing) notifications + service |
| `dialog_*` | shared dialogs / confirmations |
| `card_*`, `preview_*`, `stepper_*` | sub-component copy within their host domain |

`common_*` is reserved **only** for labels genuinely reused verbatim across ≥2 domains (currently just `common_play`) — it is not a dumping ground. The `_cd` suffix marks a `contentDescription` resource. Plurals use `<plurals>` resources with Android quantity categories (`one`/`other` for English, room for `few`/`many` in Phase B) — **never** a hand-rolled singular/plural `if` or `%d`-into-singular concatenation.

### Enforcement — `checkNoHardcodedUiStrings`

A 26th `check*` task, registered in `app/build.gradle.kts` and wired into `preBuild` via the existing `afterEvaluate { tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(...) } }` block, exactly like the other 25 checks.

- **Scans:** `app/src/main/java/com/aritr/rova/**/*.kt` (the whole app package, walked top-down).
- **Flags:** lines matching `Text("`, `Text(text = "`, or `contentDescription = "` — i.e. a `"` as the next non-space token after the opening paren / `=`, so `Text(stringResource(...))` and `contentDescription = getString(...)` do **not** match.
- **Skips:** comment lines (trimmed start `//` or `*`) and any line containing the `i18n-opt-out` marker.
- **Failure:** throws a `GradleException` listing offenders as `relativePath:line: <trimmed line>` and citing **ADR-0022 §No Hardcoded UI Strings**, matching the existing gates' report format.

**Escape hatch — `// i18n-opt-out: <reason>`:** a line-level marker that skips the line. It is valid **only** for genuinely non-user-facing literals (OEM intent / package constants, internal rotation tokens, diagnostic / log strings) and for `@Preview` / sample-data composables (a Phase-A non-goal). It is **not** a general hatch for un-externalized copy — spelling a reason is the convention so review can audit each use.

## Non-goals (Phase A)

- **No translation, no locale picker, no RTL / pseudolocale work** — all Phase B.
- **Not externalized (must stay literal):** `RovaLog.*` / `Log.*` messages; exception messages; `BuildConfig`; analytics keys; route / navigation destination keys; test tags; MIME types, file extensions, format-template fragments, and protocol constants.
- **`@Preview` / sample-data copy is out of scope** — preview-only literals carry an `i18n-opt-out:` marker.

## Known limitations of the gate

The gate is a **regression tripwire, not a correctness proof** — it matches the pragmatic line-oriented regex nature of the existing 25 checks. Documented honestly so maintainers do not over-trust it:

1. **Line-oriented.** The match is per single line. A literal that lives on a **continuation line** below `Text(` / `text =` (i.e. the opening paren and the string on separate lines) is **not** caught. Accepted, because the goal is catching the common copy-paste regression on a single-line call site, not proving total absence.
2. **Bare-string-mapper blind spot.** A helper that returns user-facing English as a plain `String` **return value** — not at a `Text(` / `contentDescription` call site — is not matched by the regex. Mitigated this slice by externalizing the one live such mapper (`UiCopy`); the remaining bare-string display helpers are now **test-only dead code** (see Consequences).
3. **Substring opt-out.** The `i18n-opt-out` skip is a substring match on the line, not a structured annotation; a line that merely mentions the token in another context would also be skipped. Acceptable given the marker is owner-audited and spells a reason.

If the gate ever needs to be load-bearing beyond a tripwire, migrate it to an Android Lint UAST check or a Detekt PSI rule (a Phase-B-or-later consideration; not built now).

## Consequences

### Positive

- **Phase B becomes a resource-only drop-in** — translated `values-xx/` catalogs + a locale picker, with zero Kotlin call-site churn.
- **TalkBack copy is centralized** in `strings.xml` alongside visible text, reinforcing ADR-0020.
- **Regressions are gated** — the common copy-paste of a hardcoded `Text("…")` / `contentDescription = "…"` now fails `preBuild`.

### Negative / costs

- Every new UI string now needs a resource entry plus the `UiText` / `@StringRes` ceremony for any arg- or plural-owning helper. This is the standing cost of being localizable.
- The gate's line-regex nature leaves the documented blind spots above; correctness still relies partly on review and the device smoke check (copy must be byte-identical to pre-slice).

### Follow-ups (out of scope, deferred)

- **Dead test-only copy helpers** — `UiCopy` and ~8 `RecordHudFormatters` display formatters became dead (test-only) once their live callers moved to resources. Flagged for a later cleanup slice; left in place this slice to keep the diff a pure resource indirection.
- **Phase B (translation + locale picker)** — deferred to a later, separate slice that builds on this externalized baseline.

## Hard invariants preserved

- No existing `check*` gate edited; the 25 existing invariants are untouched (this adds the 26th).
- Zero runtime / logic change — pure resource indirection; English copy byte-identical.
- House conventions honoured: pure-helper extraction (`UiText`), thin framework seam at the resolution edge, ADR-clause → `check*` → `preBuild`.

## Implementation reference

See `docs/superpowers/specs/2026-06-01-b3-i18n-externalization-design.md` for the full design, buckets, resolution rules, runtime-behaviour hazards (apostrophe / `%` / XML-metachar escaping, plural quantity categories, count-twice), and the 12-task implementation slicing.

## Mockup files

None. Externalization is invisible to the UI surface; on-screen copy is unchanged.
