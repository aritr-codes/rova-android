# Static-gate comment-strip hardening — design

**Date:** 2026-06-25
**Branch:** `feat/static-gate-comment-strip-hardening`
**Base:** master `4f4db3c` (post-#144 config-cache merge)
**Scope:** `build-logic/` only — no `:app` source, no app behavior, no manifest schema, no ADR amend.

## Problem

The 46 static gates live in `build-logic/` as the pure `RovaGateRules` registry behind one
typed `@CacheableTask SourceCheckTask`. The PR #114 review found false-pass holes in how
several gates detect comments. They were verbatim-lifted into `RovaGateRules` during the #144
migration and survive unchanged.

- **F1 (must-fix; gate-disabling).** `ruleA11yTargetSizeToken`
  (`RovaGateRules_A11yI18n.kt`) tracks block comments per-line with
  `line.lastIndexOf("/*")` / `lastIndexOf("*/")` and `inBlock = opens > closes`. A `/*`
  appearing inside a `//` line-comment **or a string literal** sets `inBlock = true` and it
  never clears (no real `*/` follows), so **every subsequent line in that file is skipped** —
  silently disabling the gate for the file tail. This is the one hole that suppresses *other*
  lines, not just its own.
- **F2 (lower-impact; broad).** A per-line `trimmed.startsWith("/*")` prefix-skip recurs at 8
  sites. A line that opens **and** closes a block comment then has real code
  (`/* note */ val camControlSize = 20.dp`) is dropped wholesale, so the real declaration
  escapes detection.
- **F4 (lowest).** `ruleA11yTargetSizeToken`'s value regex matches only `\d+(\.\d+)?\.dp`, so a
  curated size token redefined as an alias/expression (`val primaryActionSize = fabSize`) is
  not range-checked.

**Not a live bug.** None of F1/F2/F4 triggers in today's source — a clean `:app:assembleDebug`
is GREEN at base. This is hardening.

## The non-negotiable invariant (overrides everything)

This changes only **how** a gate detects comments. A gate may only become **stricter in the
false-pass direction** (catch a violation it used to miss). It must:

- **NEVER** start rejecting code the original accepted (no new false-fails).
- **NEVER** change the failure-message bytes for any real (non-comment-edge) violation.
- still **RED-fire byte-identically** on a genuine violation.

If closing a hole turns any real file red, **STOP and surface it** — that is a latent real
violation, escalate before "fixing" it.

## Why a single-pass scanner, not layered regex

The reference strip (`ruleSingleColorSchemeSource.strip`, `RovaGateRules_IconTheme.kt`) uses
independent regex passes in a fixed order (block → `//` → strings → chars). Comments and string
literals are **mutually recursive** — `"` can appear inside `/* */`, and `//` / `/*` can appear
inside `"…"`. No fixed-order regex layering is correct for every interleaving:

- `val u = "http://x"` — the reference strip removes from the first `//`, truncating the
  string (it strips `//` before masking strings).
- `/* note: 3" pipe */ val x = "real"` — masking strings first would consume from the `"` after
  `3` across the `*/`, corrupting the block boundary.

The only correct primitive is a **single left-to-right state-machine scanner** that, at each
char, knows whether it is in code, a line comment, a block comment, a string, a char literal, or
a raw string — so a comment marker inside a literal can never start a comment, and a quote inside
a comment can never start a string.

## Component 1 — `CommentStripper`

New file `build-logic/src/main/kotlin/com/aritr/rova/gradle/CommentStripper.kt`.

```kotlin
internal object CommentStripper {
    /**
     * Replace `//` line comments and `/* */` block comments (nesting-aware) with
     * spaces, preserving every `\n` and `\r` and the total length, so reported
     * line/column offsets stay byte-stable. String ("..."), char ('...') and raw
     * ("""...""") literals are emitted VERBATIM — a `//` or `/*` inside a literal
     * can never start a comment, and a quote inside a comment can never start a
     * literal.
     *
     * OUT OF SCOPE (documented, invariant-safe): string-template `${ ... }` bodies
     * are NOT descended into — template-internal comments are kept verbatim. This
     * matches the pre-migration behavior (gates ran regex on raw lines, so they
     * already saw template-internal content); template-aware stripping would
     * REMOVE detections the original kept, violating the invariant.
     */
    fun strip(text: String): String { /* scanner — see below */ }
}
```

Scanner rules:
- **Normal string** `"`: copy verbatim to the matching `"`. Backslash escapes the next char.
  A bare `\n`/`\r` terminates the string (defensive against malformed/uncompilable input —
  even after a `\`, the newline wins).
- **Char literal** `'`: same escape + newline-terminates rule.
- **Raw string** `"""`: copy verbatim to the matching `"""`.
- **Line comment** `//`: blank to (but not including) the next `\n` **or** `\r` — terminators
  preserved so `readLines()` splits identically.
- **Block comment** `/* */`: nesting-aware via a depth counter; blank every char to spaces
  **except** `\n` and `\r`, which are preserved. Closes when depth returns to 0.
- A stray `*/` outside a block, division `a / b`, `'/'`, `"it's"`, escape-at-EOF: all handled
  by the default-copy path.

### codex-vetted edge resolutions

- **Newline preservation in comment branches** (was the alignment HIGH): both line- and
  block-comment branches keep `\r` and `\n` so the stripped text splits into the same line list
  as the raw text.
- **String templates** kept verbatim — invariant-safe (matches current raw-line behavior),
  documented out-of-scope.
- **Escaped newline** in string/char: newline terminates regardless of preceding `\`.
- Non-ASCII inside comments: replaced by ASCII space per UTF-16 code unit — line/column
  invariants hold (we never report byte offsets).

## Component 2 — `SourceFile` computed props

`SourceFile` is a `data class(relPath, lines, text)`. Add two **lazy body vals** (NOT ctor
params → `equals`/`hashCode`/`copy` and the task's `sortedBy { relPath }` are untouched →
non-breaking; only opted-in rules reference them):

```kotlin
val strippedText: String by lazy { CommentStripper.strip(text) }
val strippedLines: List<String> by lazy { strippedText.reader().readLines() }
```

`strippedText.reader().readLines()` uses identical reader semantics to `file.readLines()` (the
source of `SourceFile.lines`), so `strippedLines[i]` aligns 1:1 with `lines[i]`. Asserted by a
golden test over CRLF, trailing-newline, no-trailing-newline, and multi-line-block inputs:
`strippedLines.size == lines.size`.

## Component 3 — migration recipe (per gate)

For each migrated gate, replace the comment-detection mechanism only:

1. **Detect** the forbid/require regex on `strippedLines[i]` (comments already blanked).
2. **Read the opt-out marker** (`// a11y-opt-out: …`, `i18n-opt-out`, `semanticicon-opt-out`,
   etc.) from the **raw** `lines[i]` — opt-out markers live in comments; reading them from the
   stripped line would erase them and ADD false-fails.
3. **Report** the offender using the **raw** `lines[i]` — message bytes unchanged.
4. **Drop** the `startsWith("//" / "*" / "/*")` prefix-skips — the stripped line handles them.

### codex per-gate audit checklist (apply before migrating each site)

- regex is per-line (or explicitly adapted to `strippedText` for a whole-text gate);
- opt-outs are read from raw source;
- the report never includes stripped `match.value` (always raw line);
- require-style gates are not requiring something that intentionally lives in a comment;
- failure-ordering changes are acceptable only when a newly exposed comment-edge violation
  appears (expected; that is the hole closing).

All 9 in-scope sites are **per-line forbid/require prefix-skips** — no whole-text, multi-line
window, or `match.value`-report cases. (`ruleA11yClickableHasRole` uses a window but is NOT in
scope — it has no `startsWith("/*")`.)

## In-scope sites

| # | Rule | File:line | Type |
|---|------|-----------|------|
| F1 | `ruleA11yTargetSizeToken` | `RovaGateRules_A11yI18n.kt` (inBlock machine + `:297`) | structural token, per-line |
| F2 | `ruleLibraryNoManifestWrite` | `RovaGateRules_IconTheme.kt:207` | per-line forbid |
| F2 | `ruleExportCleanupPredicate` | `RovaGateRules_Export.kt:272` | per-line |
| F2 | `ruleNoLegacyModeStrings` | `RovaGateRules_ModeRotation.kt:89` | per-line |
| F2 | `ruleFrontBackCapabilityGated` | `RovaGateRules_ModeRotation.kt:144` | per-line |
| F2 | `ruleExportIsPendingGuarded` | `RovaGateRules_Pending.kt:54` | per-line |
| F2 | `ruleExportSetIncludePendingGuarded` | `RovaGateRules_Pending.kt:98` | per-line |
| F2 | `ruleExportQueryArgMatchPendingGuarded` | `RovaGateRules_Pending.kt:143` | per-line |
| F2 | `ruleExportPendingVisibilityOnQuery` | `RovaGateRules_Pending.kt:197` | per-line |

(F1's own `startsWith("/*")` is line 297 inside its rule; removed with the `inBlock` machine.)

## Component 4 — F4 decision (ACCEPT + document)

Leave `ruleA11yTargetSizeToken`'s value match numeric-only (`\d+(\.\d+)?\.dp`). Document the
alias gap in the rule KDoc: a token redefined as an alias/expression
(`val primaryActionSize = fabSize`) is not range-checked, by design. **Widening to flag aliases
is rejected**: it could newly reject legitimate indirection = a new false-fail = invariant
violation, and would need separate owner sign-off + its own opt-in gate. codex concurred.

## Proof obligations (every touched gate, every commit)

- **(a) RED-fires byte-identically** — inject a genuine violation → assert RED with the same
  message bytes → revert.
- **(b) hole closed** — new golden test: a stray `/*`-in-`//` line (and a string-literal `/*`
  for F1) followed by a sub-floor token / real offender → asserts RED.
- **(c) nothing flips** — `:app:assembleDebug` GREEN (fires all 46 gates on `preBuild`) and
  `./gradlew -p build-logic test` GREEN. One run with config-cache ON
  (`--configuration-cache --configuration-cache-problems=fail`) to prove the unblock survives.

If (c) turns any real file red → STOP, surface it as a latent real violation, escalate.

## Constraints

- 46 gates + `:app:testDebugUnitTest` GREEN at **every** commit. Verify via
  `:app:assembleDebug` + `./gradlew -p build-logic test` — **not** `:app:lintDebug`
  (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated).
- Never weaken/disable/delete a `check*`. JVM golden tests only;
  `isReturnDefaultValues = true`; no Robolectric/instrumented.
- Build WARM — no prophylactic cache wipes. The only allowed wipe is deleting
  `.gradle/configuration-cache` between store/reuse experiments.
- Subagent-driven: subagents EDIT-ONLY; the controller runs all gradle/tests/git and serializes
  the shared Gradle daemon (one build at a time).
- Confirm one `:app:assembleDebug`-built APK installs on `RZCYA1VBQ2H` before owner GO
  (build-logic-only change; device smoke otherwise N/A).
- Push/PR/merge ONLY on explicit owner GO.

## Out of scope

- Migrating `ruleSingleColorSchemeSource` / `ruleRovaGlyphHome` / `ruleRecordChromeLockSingleSite`
  (whole-text DOT_MATCHES_ALL gates) onto the primitive — they are not `startsWith("/*")` sites
  and have their own (working) strip. A later cycle may consolidate them.
- String-template-aware stripping (documented invariant-safe omission).
- Widening F4.
- Any `:app` source / app behavior change.
