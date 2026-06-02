# B3 Phase A — String Externalization + Enforcement Gate — Design Spec

**Date:** 2026-06-01
**Branch:** `feat/settings-expansion-b3` (off master `d5200a0`)
**Track:** Settings expansion Track B, slice B3 (i18n)
**Touches:** new **ADR-0022**; adds a 26th `check*` static gate.

---

## Scope decision

"i18n" decomposes into two strictly-ordered phases:

- **Phase A — Externalization (this slice):** move every hardcoded user-facing
  string into `res/values/strings.xml` with stable resource names; add a static
  gate so the sweep cannot rot. **No translation, no locale picker, no behaviour
  change.** English copy is byte-identical after the slice.
- **Phase B — Localization (a later, separate slice):** `android:localeConfig`,
  a per-app language picker (`setApplicationLocales` / API 33 `LocaleManager` +
  AppCompat backport), and translated `values-xx/` catalogs.

B is pointless before A (only the ~64 already-externalized onboarding/notification
strings would translate). **B3 is Phase A only.** This spec covers Phase A.

## Problem / current state

`res/values/strings.xml` holds ~64 strings — onboarding + notification copy only.
The rest of the UI is hardcoded Kotlin string literals:

- ~40 `Text("…")` literals across 13 Compose files.
- ~31 `contentDescription = "…"` literals across 15 files.
- Plus button labels, dialog copy (`RovaDialogs`), warning/recovery prose
  (`WarningCenter`, `RecoveryCard`, vendor guidance), and notification/service
  copy not yet fully externalized.

Estimated **150–250 user-facing strings** still inline. No `android:localeConfig`,
no `resConfigs`. The app is effectively un-localizable today.

## Goal

Every **user-facing** string lives in `strings.xml`, referenced by a stable name.
The externalized state is enforced by a build-time gate. Runtime behaviour and
on-screen English copy are unchanged.

## Buckets externalized (all four)

1. **Visible `Text` / button / chip labels** — Record, History, Settings, Player,
   onboarding, dialogs, warning sheets.
2. **`contentDescription` (a11y / TalkBack)** — the ~31 hardcoded literals. Ties
   into the WCAG-AA track; user-facing for screen readers.
3. **Notification + service copy** — `NotificationCopy`, `RovaRecordingService`,
   channel names/actions (finish what `notification_*` started).
4. **Warning / recovery copy** — `WarningCenter` banner/sheet text, `RecoveryCard`,
   `VendorGuidanceIntents` user-visible prose.

## Non-goals (stay literal — must NOT be externalized)

- `RovaLog.*` / `Log.*` messages.
- Exception messages (`throw …Exception("…")`).
- `BuildConfig`, analytics keys, route/navigation destination keys.
- MIME types, file extensions, format-template fragments, protocol constants.
- `@Preview` sample data and test sources.

These are the **only** acceptable `i18n-opt-out:` reasons (see gate).

## Naming convention

Continue the existing domain-prefix style (`onboarding_*`, `notification_*`):

| Prefix | Domain |
|---|---|
| `record_*` | Record screen / chrome / HUD |
| `history_*` | History / Library |
| `settings_*` | Settings screen + sheets |
| `player_*` | Player screen |
| `warning_*` | WarningCenter sheets/banners/chips |
| `recovery_*` | RecoveryCard + vendor guidance |
| `onboarding_*` | (existing) onboarding flow |
| `notification_*` | (existing) notifications + service |
| `common_*` | shared across ≥2 domains (Cancel, OK, Close, …) |

Plurals use `<plurals>` resources, **never** `%d`-into-singular concatenation.

## Resolution rules

`stringResource(...)` is `@Composable`-only; user-facing copy also lives in
ViewModels, pure helpers, and the foreground service. Resolution by scope:

| Scope | Mechanism |
|---|---|
| Compose call site, fixed text | `stringResource(R.string.id)` |
| Compose call site, with args | `stringResource(R.string.id, arg1, …)` |
| Compose call site, count-based | `pluralStringResource(R.plurals.id, n, n)` (count passed **twice** — once for rule selection, once for `%d`) |
| Service / notification builder (has `Context`) | `context.getString(R.string.id)` / `getQuantityString` |
| Pure helper / VM that **selects** fixed copy | return `@StringRes Int`; caller resolves at its edge |
| Pure helper / VM that **owns args or plurals** | return a `UiText` token; caller resolves at its edge |

**No global `StringProvider` / injected resolver** — over-engineered for Phase A
and it would make helper tests assert a mocked resolver instead of the real
decision the helper owns (which copy key was selected).

**ViewModels never store *resolved* strings in state** — only `@StringRes Int` or
`UiText`. Storing resolved English would break Phase-B live locale switching.

### `UiText` seam

A small pure-Kotlin sealed model so helpers/VMs that own arguments or plural
counts stay framework-free and unit-testable, with Compose and the service each
resolving the same token at their own edge:

```kotlin
sealed interface UiText {
    data class Str(@StringRes val id: Int) : UiText
    data class StrArgs(@StringRes val id: Int, val args: List<Any>) : UiText
    data class Plural(@PluralsRes val id: Int, val quantity: Int, val args: List<Any>) : UiText
}
```

Resolution edges (thin wrappers, the only framework-touching part):

```kotlin
@Composable fun UiText.resolve(): String = when (this) {
    is UiText.Str     -> stringResource(id)
    is UiText.StrArgs -> stringResource(id, *args.toTypedArray())
    is UiText.Plural  -> pluralStringResource(id, quantity, *args.toTypedArray())
}

fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Str     -> context.getString(id)
    is UiText.StrArgs -> context.getString(id, *args.toTypedArray())
    is UiText.Plural  -> context.resources.getQuantityString(id, quantity, *args.toTypedArray())
}
```

A bare `@StringRes Int` is sufficient (and preferred) for helpers that only choose
between fixed messages and feed both a Compose surface and the notification path —
`getString(id)` on the service side, `stringResource(id)` on the Compose side.
Reach for `UiText` only when arguments or plurals enter.

## Static gate — `checkNoHardcodedUiStrings`

A 26th `check*` task, registered in `app/build.gradle.kts` and wired into
`preBuild` via the existing `tasks.matching { it.name == "preBuild" }` block.

**Scans:** `src/main/java/com/aritr/rova/ui/` and
`src/main/java/com/aritr/rova/service/notification/` Kotlin files.

**Flags:** lines matching `Text("…")`, `contentDescription = "…"`, `label = "…"`
with a non-empty string literal.

**Allowlist (not flagged):**
- comment lines (`//`, `*`),
- `RovaLog.` / `Log.` calls,
- `throw …Exception(`,
- `BuildConfig`,
- files whose path contains `Preview` or under a test source set, and any
  function annotated `@Preview` (preview-only literals),
- empty / blank string literals,
- format-argument-only literals (`"%1$s"`, `"%d"`, `"%%"`).

**Opt-out:** a file/line marker `i18n-opt-out: <reason>` (mandatory reason, mirrors
the existing `guard-b-opt-out:` pattern) skips the line. **Valid reasons are
non-user-facing literals only** (protocol/key/debug/route) — the marker is not a
general escape hatch for un-externalized copy.

**Failure** cites **ADR-0022 §Invariant** and lists offenders as
`relativePath:line: <trimmed line>`, matching the existing gates' report format.

**Honest limitation (documented in ADR-0022):** regex is a *regression tripwire*,
not a correctness proof. Known false-negative holes it does **not** catch — to be
swept manually this slice and listed in the ADR:
- string assigned to a `val` then passed (`val t = "Record"; Text(t)`),
- custom wrapper composables (`PrimaryButton("Start")`, chip/row helpers),
- `Toast` / `Snackbar` / `AlertDialog` / `DropdownMenuItem` text,
- `Modifier.semantics { contentDescription = "…" }`,
- `buildAnnotatedString { append("…") }`,
- string templates (`"$count videos"`).

If the gate later needs to be load-bearing, migrate to Android Lint UAST or a
Detekt PSI rule (a Phase-B-or-later consideration, noted in the ADR, not built now).

## New ADR-0022 — User-facing strings live in resources

`docs/adr/0022-user-facing-strings-in-resources.md`, **Status: Accepted**.
Documents: the invariant (all user-facing copy in `strings.xml`); the resolution
rules (Compose `stringResource`, service `getString`, helpers return
`@StringRes Int`/`UiText`, no resolved strings in VM state); the
`checkNoHardcodedUiStrings` gate, its allowlist, the `i18n-opt-out:` contract, and
its honest false-negative limitations; the buckets and explicit non-goals; and a
forward note that Phase B (localeConfig + picker + translations) builds on this.

## Runtime-behaviour hazards (must be neutralised during the sweep)

Externalization is "zero behaviour change" **only if** these are handled — each is
a way moving a literal into XML can silently alter output:

1. **Apostrophes / backslashes:** `'` and `\` must be escaped (`\'`, `\\`) or
   wrapped in `"…"` in XML, else the resource truncates or fails to compile.
2. **Percent:** any `%` in a string used with format args must be `%%`, or the
   resource marked `formatted="false"`. Existing `%1$d`-style specifiers must be
   preserved exactly.
3. **XML metacharacters:** `&` → `&amp;`, and `<`/`>` escaped where they appear.
4. **Plurals:** use Android quantity categories (`one`/`other` for English, with
   room for `few`/`many`/etc. in Phase B) — not a hand-rolled singular/plural `if`.
5. **count-twice:** `pluralStringResource(id, n, n)` / `getQuantityString(id, n, n)`
   — once selects the rule, once fills `%d`.
6. **Concatenation seams:** when copy that was built by `+` concatenation moves to
   a single resource, preserve exact spacing/punctuation.
7. **Notification collapsed layout:** verify converted notification text still fits
   the collapsed layout (length can shift after the round-trip).
8. **Tests assert IDs/tokens, not localized English** — so they survive Phase B.

## Testing

- **Zero-behaviour-change is the contract:** the existing **1377 JVM tests** stay
  green (no logic touched). This is the primary regression signal.
- **New JVM tests:** `UiText.resolve` token mapping (correct `id`/quantity
  selected per variant); any pure plural/format helper introduced. Pure-Kotlin,
  no instrumentation (`isReturnDefaultValues = true`).
- **The gate self-verifies:** `checkNoHardcodedUiStrings` runs in `preBuild`; a
  deliberate temporary hardcoded literal must make it fail (smoke-checked once
  during the gate task, then reverted).
- **Manual device smoke (mandatory before done):** open every screen
  (Record / History / Settings / Player / onboarding / warnings / recovery /
  notifications) and confirm copy is **identical** to pre-slice — this is the only
  way to catch an escaping/format regression that compiles but renders wrong.

## Implementation slicing (subagent-driven, ~12 tasks)

Ordered so each task is self-contained and leaves the build green:

1. `UiText` seam + resolution edges + its JVM tests.
2. Record screen/chrome/HUD literals → resources.
3. History / Library literals → resources.
4. Settings screen + sheets literals → resources.
5. Player screen literals → resources.
6. Onboarding — finish any remaining inline literals.
7. WarningCenter (sheets / banners / chips) copy → resources.
8. Recovery (`RecoveryCard`, vendor guidance) copy → resources.
9. Notification + service copy → resources (`getString`/`getQuantityString`).
10. Shared dialogs / `common_*` (`RovaDialogs`, confirmations) → resources.
11. `checkNoHardcodedUiStrings` gate + wire into `preBuild` (RED→GREEN: prove it
    fails on a planted literal, then prove the swept tree passes).
12. ADR-0022 + CHANGELOG entry.

## File touch list (indicative)

- `app/src/main/res/values/strings.xml` — all new resources (grows substantially).
- New: `app/src/main/res/values/plurals.xml` (if any count-based copy exists).
- `app/src/main/java/com/aritr/rova/ui/text/UiText.kt` (new seam).
- `app/src/test/java/com/aritr/rova/ui/text/UiTextTest.kt` (new).
- Sweep edits across `ui/screens/**`, `ui/components/**`, `ui/warnings/**`,
  `ui/recovery/**`, `service/notification/**`, `service/RovaRecordingService.kt`.
- `app/build.gradle.kts` — `checkNoHardcodedUiStrings` task + `preBuild` wiring.
- `docs/adr/0022-user-facing-strings-in-resources.md` (new).
- `CHANGELOG.md` — Unreleased entry.

## Invariants preserved

- No existing `check*` gate edited; the 25 existing invariants untouched.
- Zero runtime/logic change — pure resource indirection.
- House conventions honoured: pure-helper extraction (`UiText`), thin framework
  seam at the resolution edge, ADR-clause → `check*` → `preBuild`.
