# checkA11yClickableHasRole — Custom-Clickable Role Static Gate Design

- **Date:** 2026-06-13
- **Status:** Approved (owner delegated the scope decision to controller + codex; reconciled decision recorded below)
- **ADR:** amends ADR-0020 (WCAG 2.2 AA by Default) — promotes the `checkA11yClickableHasRole` STUB to a built gate
- **Scope:** the SECOND `checkA11y*` static-check gate (after `checkA11yAnimationGated`, 2026-06-03). The other two sketched in ADR-0020 (`checkA11yNoLowAlphaTextToken`, `checkA11yTargetSizeToken`) stay deferred — see §Out of scope for the rationale.
- **Peer review:** codex consulted on the scope decision and gate semantics (per repo convention for design decisions); its refinements are folded in and attributed inline.

## Goal

A Gradle static-check task that fails the build when a custom `Modifier.clickable` / `Modifier.combinedClickable` is applied without an accessibility **role** on the same modifier chain, enforcing ADR-0020 §Decision-1 (WCAG 2.2 AA SC 4.1.2 "Name, Role, Value") by construction for all future UI. The gate freezes the invariant: a custom clickable container (a `Row`/`Box`/`Surface`/`Column` that a developer made tappable) must announce a role to TalkBack, instead of being read as a generic container.

This is the single most common accessibility regression in this codebase's idiom: a developer adds `Modifier.clickable { … }` to a custom layout and forgets the role. A static gate catches it at build time, the same way `checkNoHardcodedUiStrings` catches a raw string literal.

## Context

ADR-0020 established "WCAG 2.2 AA by default" and sketched four `checkA11y*` gates as design-only, explicitly deferring their construction until each gate's source was remediated GREEN. **Landing a gate against red source wires `preBuild` to a permanently-failing task** — this is the load-bearing rule that governs which gate can be built when. The first gate (`checkA11yAnimationGated`) shipped 2026-06-03 only because the reduced-motion seam had already landed and every looping animation was already gated.

A measured current-source scan (2026-06-13) produced this buildability matrix for the three remaining candidates:

| Candidate gate | Verdict | Detail |
|---|---|---|
| `checkA11yNoLowAlphaTextToken` | **RED** | 5 `*Text`/`*Label` tokens at 0.40–0.50 alpha (backlog rows 4/5, *Serious*, unshipped). |
| `checkA11yTargetSizeToken` | needs-allowlist | 8 interactive-size tokens 27–42dp; all ≥24dp so all already pass AA SC 2.5.8; owner reclassified them Advisory 2026-05-30. |
| `checkA11yClickableHasRole` | 5 fixable offenders | 15/20 clickable sites already carry a role; the 5 gaps are single-line additions (= backlog row-8 partial). |

`checkA11yClickableHasRole` is the gate whose source can be made GREEN cheaply (five single-line edits) and whose invariant has the highest forward value. It is the subject of this spec.

### Why the other two are deferred (not in this cycle)

- **`checkA11yNoLowAlphaTextToken` — deferred.** It is RED, and the *sketch itself is flawed*. Alpha is not contrast: 0.50-alpha white over a near-black surface computes ≈5:1 and **passes** the 4.5:1 AA floor, so an `alpha < 0.55` heuristic false-fails passing tokens. It is also mis-scoped: `modeTabDisabledText` (0.40) is a **disabled** control, which WCAG 1.4.3 explicitly **exempts**; `navIcon` (0.50) is an icon, governed by 1.4.11 non-text contrast (3:1), not 1.4.3. A correct gate would compute real contrast ratios through the existing `ContrastMath` helper from structured foreground/background token pairs — a substantially harder Gradle scan. Deferring this gate means the genuine *Serious* contrast debt (backlog rows 4/5) stays ungated this cycle; that remediation (raise token alphas, re-baseline the `mockups/`-derived token contract with owner sign-off, then build a `ContrastMath`-based gate) is its own future slice.
- **`checkA11yTargetSizeToken` — skipped.** codex: token size is not the same thing as touch-target size (a small glyph commonly sits inside a 48dp padded hitbox), so a token-size scan misclassifies. All 8 flagged tokens already pass AA's real 24×24dp floor (SC 2.5.8), and flag-`<48` would contradict the owner's standing 2026-05-30 reclassification of these as M3-polish/Advisory. Low signal for the cost. If ever revisited, it should be renamed `checkA11yNoSub24InteractiveTargetToken` and enforce the true 24dp AA line, keeping 48dp as advisory M3 polish only.

## Decision — source remediation first (land GREEN)

The five offending sites are fixed **before** the gate is wired, so the gate lands GREEN. Each fix is a single-line addition of a role to the existing `clickable` call (`role = Role.Button`, or the correct role where the control is not a plain button). codex confirmed: **fix, do not allowlist** — allowlisting real 4.1.2 violations turns the gate into paperwork and forces future reviewers to defend old debt. The only legitimate allowlist reason is a site that is *already compliant* through an outer merged-semantics wrapper, `clearAndSetSemantics`, or a deliberately non-button role; each of the five is verified to be a genuine missing-role site, not such a case.

The five sites (to be pinned exactly in the implementation plan):

| Site | Current | Fix |
|---|---|---|
| `LargeValueStepper.kt:122` | `.clickable(enabled = enabled, onClick = onClick)` | add `role = Role.Button` |
| `RecordChrome.kt:768` | conditional `Modifier.clickable { onClick() }` | add `role = Role.Button` |
| `SettingsScreen.kt:837` | `.clickable(onClick = onClick)` | add `role = Role.Button` |
| `SettingsSheet.kt:920` | `.clickable(onClickLabel = label, onClick = …)` (label but no role) | add `role = Role.Button` |
| `SettingsStepperSheet.kt:128` | `.clickable(onClick = onClick)` | add `role = Role.Button` |

The plan must verify each is a true positive (the clickable node is the semantics node a user activates) at edit time, and pick `Role.Button` unless the control is semantically a toggle/selection (none of the five appear to be).

## Decision — gate semantics

**Granularity: per-clickable, modifier-chain-scoped.** Stronger than the file-level co-presence of `checkA11yAnimationGated`, because codex flagged that a same-file/same-window check is gameable (an unrelated `role` elsewhere in the file would mask a missing one). The rule binds the role to the *same modifier chain* as the clickable.

Algorithm, per `*.kt` file under `src/main/java/com/aritr/rova`:

1. **Locate clickables.** Find each occurrence of `.clickable(` or `.combinedClickable(` (also bare `clickable(`/`combinedClickable(` when used as `Modifier.clickable(`). A line whose trimmed start is `//` or `*` (comment/KDoc) is skipped. A clickable occurrence carrying `// a11y-opt-out:` with a non-empty reason on its line is skipped (see Opt-out).
2. **Capture the call arguments.** From the opening `(`, capture the argument text up to the balanced closing `)` (paren-counting, so the capture spans multiline calls correctly — this closes codex's "multiline call beyond the scan window" false-positive trap).
3. **Role-in-call check.** If the captured arguments contain `role =` (e.g. `role = Role.Button`), the site is compliant. Done.
4. **Role-on-chain check.** Otherwise, scan the contiguous modifier chain this clickable belongs to — the run of adjacent lines that are part of the same `Modifier`/modifier expression (lines whose trimmed content begins with `.`, plus the line bearing the `Modifier`/parameter the chain hangs off). If any of those chain lines contains `.semantics {` or `.clearAndSetSemantics {` whose block mentions `role`, the site is compliant. This admits the second compliant idiom in the codebase (`…clickable{…}.semantics { role = Role.Button }`) while keeping the check chain-local, not file-wide.
5. **Offense.** A clickable that passes neither check (3) nor (4), and is not opted out, is an offender. Report it as `file:line`.
6. If any offenders exist, throw `GradleException` citing ADR-0020 §Decision-1 (SC 4.1.2), listing offenders, and spelling the `// a11y-opt-out: <reason>` hatch.

### Compliant idioms (must pass)

- `Modifier.clickable(role = Role.Button, onClick = …)` — role in call (the dominant idiom, e.g. `FloatingSettingsPanel.kt` close button).
- `Modifier.clickable { … }.semantics { role = Role.Button }` — role on an adjacent chain modifier.
- `Modifier.clickable { … }.clearAndSetSemantics { role = Role.Button; … }` — role on a replacing-semantics modifier.

### Scope boundary (must NOT flag)

- **Material components** — `Button`, `IconButton`, `TextButton`, `Card(onClick = …)`, etc. supply a role internally and are *component calls*, not `Modifier.clickable`. The scan targets the modifier, so these are naturally out of scope.
- **`toggleable` / `selectable`** — explicitly out of scope. They carry their own role/state semantics (`Role.Switch`/`Role.Checkbox`/`Role.RadioButton` + state) and are a separate invariant; this gate is about custom *clickable* role only. Documented here so the boundary is intentional, not an oversight (codex).
- The ADR clause and error message must say this gate enforces **custom-clickable role semantics**, not "all Name/Role/Value obligations" (codex) — `contentDescription`/name coverage is a different, still-deferred concern (backlog rows 26/29/30).

### Opt-out hatch

A clickable occurrence bearing `// a11y-opt-out: <reason>` on its line is skipped. The reason string is **required and must be non-empty** (codex: empty opt-outs become invisible debt) — the gate treats `// a11y-opt-out:` with no following non-whitespace as itself malformed and does **not** honor it (the site still counts as an offender, surfacing the bad hatch). This mirrors the `// i18n-opt-out: <reason>` convention in `checkNoHardcodedUiStrings`. No site needs an opt-out at landing.

### Accepted blind spots (documented, per house precedent)

Consistent with the bounded blind spots every existing gate accepts (`checkA11yAnimationGated` file-level; `checkNoHardcodedUiStrings` single-line):

- A clickable whose modifier chain is built in a detached `val customModifier = Modifier.clickable{…}` and consumed elsewhere — the role, if added on the consuming chain, is not seen as "same chain." Rare in this codebase; if it occurs the fix is to put the role in the `clickable` call itself.
- Tap handling via `pointerInput { detectTapGestures { … } }` is not a `clickable` and is not scanned (it is also frequently a non-activation gesture). Out of scope.
- A stale `role = null` literal would satisfy the textual `role =` check though it sets no role; treated as an accepted blind spot (it is a deliberate "no role" marker and vanishingly rare).

## Architecture / files

| File | Change |
|---|---|
| 5 source files (above) | Add `role = Role.Button` to each offending `clickable` call; import `androidx.compose.ui.semantics.Role` where not already imported. Source goes GREEN before the gate is wired. |
| `app/build.gradle.kts` | Register `val checkA11yClickableHasRole = tasks.register("checkA11yClickableHasRole") { … }` with a KDoc block citing ADR-0020 §Decision-1 (SC 4.1.2), following the `checkA11yAnimationGated` / `checkNoHardcodedUiStrings` structure; add one `dependsOn(checkA11yClickableHasRole)` line inside the existing `afterEvaluate { tasks.matching { it.name == "preBuild" } … }` block. |
| `docs/adr/0020-wcag-2.2-aa-by-default.md` | Promote `checkA11yClickableHasRole` from the STUB list to a built gate: stamp owner sign-off 2026-06-13, state it is wired into `preBuild`, and scope its clause to "custom clickable role semantics only." The other two `checkA11y*` remain STUB with their deferral rationale. |
| `CLAUDE.md` | Add `checkA11yClickableHasRole` to the check* registry list and bump the count 39 → 40. |
| `CHANGELOG.md` | One line under `Added` (Keep a Changelog 1.1.0): the new custom-clickable role static gate. |

No JVM unit test (build logic; consistent with all existing gates — none are unit-tested, and extracting the scan to `buildSrc` for one gate is unjustified over-engineering).

## Error message (exact shape)

```
ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 4.1.2 Name, Role, Value):
custom Modifier.clickable / combinedClickable used without an accessibility
role on the same modifier chain. A custom clickable container (Row/Box/Surface/
Column) must declare a role so TalkBack announces it as actionable — either
`clickable(role = Role.Button, …)` or an adjacent `.semantics { role = … }` /
`.clearAndSetSemantics { role = … }`. Material Button/IconButton supply a role
already. For toggles/selections use toggleable/selectable (out of scope here).
For a genuinely role-exempt case, add `// a11y-opt-out: <reason>` (reason
required) on the clickable line.
Offenders:
  <file>:<line>: <trimmed line>
```

## Testing / verification

Gradle build logic, verified by running the task (the project standard for all existing `check*` gates — none carry JVM unit tests):

1. **GREEN proof:** after the five fixes, `./gradlew :app:checkA11yClickableHasRole` passes on the remediated source.
2. **RED proof (must do before wiring):** transiently add `Box(Modifier.clickable { })` (no role) to a scratch composable; run the task; confirm it throws and names that `file:line`; revert. A gate that cannot be shown to fail is silently toothless (codex).
3. **Idiom proof:** confirm the task stays GREEN against the two compliant idioms (`clickable(role = …)` and `clickable{}.semantics { role }`) and against a Material `IconButton` — i.e. no false positives on existing compliant sites.
4. **Full gate:** `./gradlew :app:assembleDebug` (runs the new gate via `preBuild`) and `./gradlew :app:testDebugUnitTest` (baseline green) both succeed. Use `assembleDebug` not `lintDebug` to gate-build (lint is RED on pre-existing `VaultAndroidOps` NewApi, unrelated).

## Out of scope

- **`checkA11yNoLowAlphaTextToken`** — deferred; RED + flawed sketch; needs a `ContrastMath`-based real-contrast gate plus the Serious contrast remediation (rows 4/5) and mockup-token re-baseline. Its own future cycle.
- **`checkA11yTargetSizeToken`** — skipped; token-size ≠ touch-target, all current tokens pass the real 24dp AA floor, contradicts the owner's reclassification. If revived: rename to the 24dp rule.
- **`toggleable` / `selectable` role/state** — a distinct invariant, not this gate.
- **Name / `contentDescription` coverage** (backlog rows 26/29/30) — this gate enforces *role*, not *name*.
- **Per-composable AST analysis** — rejected in favor of the chain-scoped textual scan, consistent with every existing gate's regex approach and its documented bounded blind spots.
