# checkA11yClickableHasRole (Gate-3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the second `checkA11y*` static gate — `checkA11yClickableHasRole` — which fails the build when a custom `Modifier.clickable`/`combinedClickable` lacks an accessibility role on its modifier chain (WCAG 2.2 AA SC 4.1.2), after first remediating the real offenders so the gate lands GREEN.

**Architecture:** A Gradle `verification`-group task (regex scan over `src/main/java/com/aritr/rova`, same idiom as `checkA11yAnimationGated`/`checkNoHardcodedUiStrings`) wired into `preBuild`. The gate is written first and run as its own oracle to produce the *true* offender list (the recon's candidate list contained false positives), the offenders are fixed, then the gate is wired in. Detection is a bounded per-clickable window (clickable call args + a forward window to the modifier-block terminator + a small backward window) checked for a `role =` assignment — a pragmatic same-chain approximation that fails safe toward false-pass.

**Tech Stack:** Kotlin Gradle DSL (`app/build.gradle.kts`), Jetpack Compose semantics (`androidx.compose.ui.semantics.Role`), the repo's `check*` → `preBuild` static-gate convention (ADR-0020).

**Spec:** `docs/superpowers/specs/2026-06-13-checka11y-clickable-role-gate-design.md`

**Controller/subagent split (house rule):** Subagents are EDIT-ONLY. The controller (main session) runs ALL gradle. Build-env recovery dance before any gradle: `gradlew.bat --stop` → kill stray `java` → delete `app/build/kotlin` + `.gradle/kotlin`. Gate-build with `:app:assembleDebug` (NOT `lintDebug` — RED on pre-existing `VaultAndroidOps` NewApi).

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/build.gradle.kts` | Static-gate registry + `preBuild` wiring | Add the `checkA11yClickableHasRole` task (after `checkA11yAnimationGated`, ~line 2005) + one `dependsOn` line (after line 2216). |
| `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` | Record-home chrome (Start/Stop FAB) | Add `role = Role.Button` to the FAB's `.semantics {}` (~line 772). |
| `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt` | Settings rows | Add `role = Role.Button` to the clickable settings row (~line 837). |
| `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt` | Record settings sheet (orientation tabs) | Add `role = Role.Tab` to the tab's `.semantics {}` (~line 929). |
| `docs/adr/0020-wcag-2.2-aa-by-default.md` | ADR | Promote the `checkA11yClickableHasRole` bullet STUB → built (owner sign-off 2026-06-13, scoped). |
| `CLAUDE.md` | Project instructions | Gate count 39 → 40; append `checkA11yClickableHasRole` to the inline gate list. |
| `CHANGELOG.md` | Changelog | One `Added` line. |

---

## Task 1: Write the gate task (not yet wired)

**Files:**
- Modify: `app/build.gradle.kts` (insert after the `checkA11yAnimationGated` block, which currently ends at line 2005)

- [ ] **Step 1: Insert the new task registration**

Insert the following immediately after the closing `}` of `checkA11yAnimationGated` (after line 2005, before the `// B5 / ADR-0025 …` comment at line 2007). It mirrors the `checkA11yAnimationGated` / `checkNoHardcodedUiStrings` structure exactly (same `group`, `inputs.dir`, `walkTopDown`, `GradleException` shape).

```kotlin
// ADR-0020 §Decision-1 — checkA11yClickableHasRole (WCAG 2.2 AA SC 4.1.2
// Name, Role, Value). A custom `Modifier.clickable` / `combinedClickable`
// (a Row/Box/Surface/Column the developer made tappable) must declare an
// accessibility role so TalkBack announces it as actionable, not as a generic
// container. The role may sit in the clickable call (`clickable(role = …)`) or
// on an adjacent `.semantics { role = … }` / `.clearAndSetSemantics { role = … }`
// in the same modifier block.
//
// Detection (pragmatic same-chain approximation): for each clickable, scan a
// bounded window = the clickable's own call args + a small backward window +
// a forward window that stops at the modifier-block terminator (the line where
// the composable's `contentAlignment`/`verticalAlignment`/`horizontalArrangement`/
// `content =` args begin, or the chain's closing `)`), capped at 20 lines.
// A `role =` token anywhere in that window makes the site compliant.
//
// Out of scope (NOT flagged): Material `Button`/`IconButton`/`TextButton` etc.
// (component calls, not the modifier), and `toggleable`/`selectable` (their own
// role/state invariant). Material components are excluded automatically because
// the scan keys on the `.clickable(`/`.combinedClickable(`/`{` modifier token.
//
// Opt-out: a clickable line bearing `a11y-opt-out` is skipped — the reason must
// be non-empty (`// a11y-opt-out: <reason>`), mirroring `i18n-opt-out`.
//
// Accepted blind spots (mirrors the other gates): an UNRELATED `role =` inside
// the bounded window can mask a missing one (fails safe toward false-pass, never
// false-fail); a clickable whose modifier is built in a detached `val` and whose
// role is added on the consuming chain is not seen (put the role in the
// clickable call). `pointerInput { detectTapGestures }` taps are not `clickable`
// and are out of scope.
val checkA11yClickableHasRole = tasks.register("checkA11yClickableHasRole") {
    group = "verification"
    description = "Require an accessibility role on custom Modifier.clickable/combinedClickable — WCAG 2.2 AA SC 4.1.2 (ADR-0020 §Decision-1)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkA11yClickableHasRole: Rova source dir missing: $srcDir")
        }
        // `.clickable(` / `.combinedClickable(` (paren form) OR `.clickable {` /
        // `.combinedClickable {` (trailing-lambda form). The leading `.` keeps it
        // to Modifier extensions, not unrelated identifiers.
        val clickable = Regex("""\.(clickable|combinedClickable)\s*[({]""")
        val roleAssign = Regex("""\brole\s*=""")
        // Lines that mark the end of a modifier block (content/alignment args of
        // the composable follow the modifier, or the chain's args close).
        val terminator = Regex("""^\s*(contentAlignment|verticalAlignment|horizontalArrangement|content\s*=|\)\s*\{)""")
        val backWindow = 4
        val maxForward = 20
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val lines = f.readLines()
                val hits = lines.indices.filter { idx ->
                    val line = lines[idx]
                    if (line.contains("a11y-opt-out")) return@filter false
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@filter false
                    if (!clickable.containsMatchIn(line)) return@filter false
                    // Build the bounded window around this clickable.
                    val from = maxOf(0, idx - backWindow)
                    var to = idx
                    var n = 0
                    while (to + 1 < lines.size && n < maxForward) {
                        val next = lines[to + 1]
                        if (terminator.containsMatchIn(next)) break
                        to++; n++
                    }
                    val window = lines.subList(from, to + 1).joinToString("\n")
                    // Compliant iff a role assignment appears in the window.
                    !roleAssign.containsMatchIn(window)
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { i ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${lines_at(f, i)}"
                }
            }
            throw GradleException(
                "ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 4.1.2 Name, " +
                    "Role, Value): custom Modifier.clickable / combinedClickable " +
                    "used without an accessibility role on the same modifier " +
                    "chain. A custom clickable container (Row/Box/Surface/Column) " +
                    "must declare a role so TalkBack announces it as actionable — " +
                    "either `clickable(role = Role.Button, …)` or an adjacent " +
                    "`.semantics { role = … }` / `.clearAndSetSemantics { role = … }`. " +
                    "Material Button/IconButton supply a role already. For " +
                    "toggles/selections use toggleable/selectable (out of scope " +
                    "here). For a genuinely role-exempt case, add " +
                    "`// a11y-opt-out: <reason>` (reason required) on the " +
                    "clickable line.\nOffenders:\n$report"
            )
        }
    }
}
```

Note: `lines_at(f, i)` in the report is a placeholder for re-reading the offending line text. Replace it with the same inline pattern the sibling gates use — capture the line during the filter instead of re-reading. Concretely, change the `hits` collection to keep `IndexedValue`-style pairs so the report has the text. Use this exact compliant variant instead of the `lines_at` shorthand:

```kotlin
                val hits = lines.withIndex().filter { (idx, line) ->
                    if (line.contains("a11y-opt-out")) return@filter false
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@filter false
                    if (!clickable.containsMatchIn(line)) return@filter false
                    val from = maxOf(0, idx - backWindow)
                    var to = idx
                    var n = 0
                    while (to + 1 < lines.size && n < maxForward) {
                        if (terminator.containsMatchIn(lines[to + 1])) break
                        to++; n++
                    }
                    !roleAssign.containsMatchIn(lines.subList(from, to + 1).joinToString("\n"))
                }
                if (hits.isEmpty()) null else f to hits
```

and the report:

```kotlin
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
```

This matches `checkNoHardcodedUiStrings` (line 1712-1731) byte-for-byte in shape. Do NOT keep the `lines_at` shorthand — it is illustrative only.

- [ ] **Step 2: Do NOT wire it into preBuild yet**

Leave the `preBuild` `dependsOn(...)` block (lines 2185-2225) untouched in this task. Wiring happens in Task 4, only after the source is GREEN.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: add checkA11yClickableHasRole gate task (unwired)"
```

---

## Task 2: Calibrate the gate — RED-proof + true offender list (CONTROLLER runs gradle)

**Files:** none (gradle run only)

- [ ] **Step 1: Run the gate in isolation**

Controller runs (after the build-env dance):

```
./gradlew :app:checkA11yClickableHasRole
```

Expected: **FAILS** (RED) with `ADR-0020 §Decision-1 violation …` listing offenders. This is the red-proof (the gate demonstrably fails on real violations) AND the authoritative offender list.

- [ ] **Step 2: Verify the calibration set**

Confirm the offender list **includes**:
- `RecordChrome.kt:768` (FAB — `.semantics{}` has no role)
- `SettingsScreen.kt:837` (settings row — `.clickable(onClick = onClick)` no role)
- `SettingsSheet.kt:920` (orientation tab — `.semantics{ selected }` no role)

Confirm it **does NOT** include (these are already compliant; if they appear, the window/terminator is too tight — widen `maxForward` and re-run):
- `LargeValueStepper.kt:122` (`.clearAndSetSemantics { role = Role.Button }` 1-4 lines below)
- `SettingsStepperSheet.kt:128` (`.semantics { role = Role.Button }` ~6-9 lines below)

- [ ] **Step 3: Record the actual list**

The gate may report MORE offenders than the three above (the recon only spot-checked). Capture the full reported list — every reported `file:line` gets fixed in Task 3. Do not assume exactly three.

If the gate misclassifies a known-compliant site as an offender (false-fail), tune `maxForward` / `terminator` and re-run until the calibration set in Step 2 holds. (No source edits in this task.)

---

## Task 3: Fix the true offenders → GREEN

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt:772-775`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt:837`
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt:929-932`
- Plus any additional offenders Task 2 reported (same one-line role-addition pattern; pick `Role.Button` unless the control is a tab/toggle/selection).

- [ ] **Step 1: RecordChrome.kt — add `role = Role.Button` to the FAB semantics**

The Start/Stop FAB (a custom clickable `Box`). Current (lines ~768-775):

```kotlin
                .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
                // SC 4.1.3 (NAV-07): the label flips Start↔Stop when recording
                // toggles; a polite live region announces that transition even
                // when focus is elsewhere on the HUD.
                .semantics {
                    contentDescription = semanticsLabel
                    liveRegion = LiveRegionMode.Polite
                },
```

Change to (add the role line inside `.semantics {}`):

```kotlin
                .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
                // SC 4.1.3 (NAV-07): the label flips Start↔Stop when recording
                // toggles; a polite live region announces that transition even
                // when focus is elsewhere on the HUD.
                // SC 4.1.2 (checkA11yClickableHasRole): the FAB is a custom
                // clickable Box, so it must declare its button role explicitly.
                .semantics {
                    contentDescription = semanticsLabel
                    role = Role.Button
                    liveRegion = LiveRegionMode.Polite
                },
```

- [ ] **Step 2: Verify the `Role` import in RecordChrome.kt**

Confirm `import androidx.compose.ui.semantics.Role` is present (the file already uses `role =` in `SpinningBox`/nav semantics — likely imported). If absent, add it with the other `androidx.compose.ui.semantics.*` imports.

- [ ] **Step 3: SettingsScreen.kt — add the role to the clickable param**

Current (line ~835-837):

```kotlin
                    onClick != null -> base
                        .focusHighlight(RectangleShape)
                        .clickable(onClick = onClick)
```

Change to:

```kotlin
                    onClick != null -> base
                        .focusHighlight(RectangleShape)
                        .clickable(role = Role.Button, onClick = onClick)
```

`Role` is already imported here (line ~832 uses `role = Role.Switch`). No import change.

- [ ] **Step 4: SettingsSheet.kt — add `role = Role.Tab` to the orientation tab semantics**

This is a **tab** (it has `selected = isActive`), so the correct role is `Role.Tab`, NOT `Role.Button`. Current (lines ~929-932):

```kotlin
                .semantics {
                    contentDescription = label
                    selected = isActive
                    if (!enabled) disabled()
```

Change to:

```kotlin
                .semantics {
                    contentDescription = label
                    role = Role.Tab
                    selected = isActive
                    if (!enabled) disabled()
```

- [ ] **Step 5: Verify the `Role` import in SettingsSheet.kt**

Confirm `import androidx.compose.ui.semantics.Role` is present; add it if not (with the other semantics imports). `Role.Tab` is part of the same `Role` object.

- [ ] **Step 6: Fix any additional offenders from Task 2**

For each extra `file:line` the gate reported, add a role using the same pattern: `role` in the `.clickable(...)` args if it takes parens, else `role = Role.Button` in an adjacent `.semantics {}` (use `Role.Tab`/`Role.Switch`/`Role.Checkbox`/`Role.RadioButton` if the control is a tab/toggle/selection). Verify the `Role` import per file.

- [ ] **Step 7: Controller re-runs the gate → GREEN**

```
./gradlew :app:checkA11yClickableHasRole
```

Expected: **PASS** (no offenders). If still RED, a reported site was missed or a fix didn't land in the window — inspect and repeat Step 6.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt app/src/main/java/com/aritr/rova/ui/screens/SettingsScreen.kt app/src/main/java/com/aritr/rova/ui/screens/SettingsSheet.kt
git commit -m "a11y: add role to custom clickables (FAB, settings row, orientation tab) — SC 4.1.2"
```

---

## Task 4: Wire the gate into preBuild

**Files:**
- Modify: `app/build.gradle.kts:2216` (insert after the `dependsOn(checkA11yAnimationGated)` line)

- [ ] **Step 1: Add the dependsOn line**

In the `tasks.matching { it.name == "preBuild" }.configureEach { … }` block, after line 2216 (`dependsOn(checkA11yAnimationGated)`), add:

```kotlin
        dependsOn(checkA11yClickableHasRole)
```

(Grouped with the other a11y gate.)

- [ ] **Step 2: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: wire checkA11yClickableHasRole into preBuild"
```

---

## Task 5: ADR-0020 amendment

**Files:**
- Modify: `docs/adr/0020-wcag-2.2-aa-by-default.md` (the `checkA11y*` static-gate suite section, lines ~54-64)

- [ ] **Step 1: Update the suite header count**

Change the section header `## \`checkA11y*\` static-gate suite (1 of 4 built; rest STUB)` to `(2 of 4 built; rest STUB)`.

- [ ] **Step 2: Promote the `checkA11yClickableHasRole` bullet**

Replace the existing STUB bullet (line ~61):

```markdown
- **`checkA11yClickableHasRole`** — scan composables: any `Modifier.clickable`/`combinedClickable` on a `Row`/`Box`/`Surface` that is not a Material `Button`/`IconButton` must be accompanied by `semantics { role = Role.Button }` (or an allowlist annotation). Cites §Decision-1 (4.1.2).
```

with:

```markdown
- **`checkA11yClickableHasRole`** — **BUILT 2026-06-13 (owner sign-off); wired into `preBuild`.** For each `Modifier.clickable` / `combinedClickable` under `src/main/java/com/aritr/rova`, a bounded modifier-chain window (the clickable call args + a forward window to the modifier-block terminator + a small backward window) must contain a `role =` assignment — set in the `clickable(role = …)` call or on an adjacent `.semantics { role = … }` / `.clearAndSetSemantics { role = … }`. Material `Button`/`IconButton` (component calls, not the modifier) and `toggleable`/`selectable` (their own role/state invariant) are out of scope. Opt-out hatch `// a11y-opt-out: <reason>` (reason required). Enforces **custom-clickable role semantics only**, not all of SC 4.1.2 (name/`contentDescription` coverage remains a separate concern). Cites §Decision-1 (4.1.2). Design: `docs/superpowers/specs/2026-06-13-checka11y-clickable-role-gate-design.md`.
```

- [ ] **Step 3: Update the 2026-06-03 update note**

Change the `**Update 2026-06-03:**` paragraph (line ~56) opener `\`checkA11yAnimationGated\` (reduced-motion, §Decision-3) is now **built and wired into \`preBuild\`**` to also note the second gate, e.g. append a sentence: `**Update 2026-06-13:** \`checkA11yClickableHasRole\` (§Decision-1, SC 4.1.2) is now built and wired into \`preBuild\` (owner sign-off) — see its bullet below; the remaining two (\`checkA11yNoLowAlphaTextToken\`, \`checkA11yTargetSizeToken\`) stay design-only, deferred per the 2026-06-13 spec.`

- [ ] **Step 4: Commit**

```bash
git add docs/adr/0020-wcag-2.2-aa-by-default.md
git commit -m "docs(adr): promote checkA11yClickableHasRole to built (ADR-0020, 2 of 4)"
```

---

## Task 6: CLAUDE.md + CHANGELOG

**Files:**
- Modify: `CLAUDE.md` (Static-check-gate section)
- Modify: `CHANGELOG.md` (`[Unreleased]` → `Added`)

- [ ] **Step 1: CLAUDE.md — bump the count and extend the list**

In the Static-check-gate section, change `registers **39 custom \`check*\` tasks**` to `registers **40 custom \`check*\` tasks**`. In the inline gate-name list (the sentence ending `…, checkRecordChromeLockSingleSite.`), append `, checkA11yClickableHasRole` before the closing period.

- [ ] **Step 2: CHANGELOG.md — add one Added line**

Under `## [Unreleased]` → `### Added` (after line 16, the `checkA11yAnimationGated` entry), insert:

```markdown
- **Second `checkA11y*` static gate** — `checkA11yClickableHasRole` (the 40th `check*` task, ADR-0020 §Decision-1, WCAG 2.2 AA SC 4.1.2 Name/Role/Value). Bounded modifier-chain scan: any custom `Modifier.clickable` / `combinedClickable` under `src/main/java/com/aritr/rova` must declare an accessibility role — in the `clickable(role = …)` call or on an adjacent `.semantics { role = … }` / `.clearAndSetSemantics { role = … }` — so TalkBack announces custom clickable containers (Rows/Boxes/Surfaces) as actionable. Material `Button`/`IconButton` and `toggleable`/`selectable` are out of scope; opt-out hatch `// a11y-opt-out: <reason>`. Landed GREEN after adding the role to three stragglers (Start/Stop FAB, a settings row, the orientation tab). Promotes the ADR-0020 design stub to built (2 of 4). Spec: `docs/superpowers/specs/2026-06-13-checka11y-clickable-role-gate-design.md`.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md CHANGELOG.md
git commit -m "docs: record checkA11yClickableHasRole (count 39->40, CHANGELOG)"
```

---

## Task 7: codex review of the gate task (CONTROLLER)

**Files:** none

- [ ] **Step 1: Peer-review the gate code**

Per CLAUDE.md (build-logic / >5-line change), the controller passes the final `checkA11yClickableHasRole` task body to `mcp__codex__codex` for review: false-positive/negative traps in the bounded-window scan, the terminator regex, the opt-out reason handling, the report shape. Reconcile or note disagreement. Apply any fixes as a follow-up commit and re-run Task 2/3 GREEN proof if the scan logic changed.

---

## Task 8: Full build + tests GREEN (CONTROLLER)

**Files:** none

- [ ] **Step 1: Build-env dance + full gate build**

```
gradlew.bat --stop
# kill stray java; delete app/build/kotlin + .gradle/kotlin
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. `preBuild` now runs `checkA11yClickableHasRole` (among the 40) and passes.

- [ ] **Step 2: JVM unit tests**

```
./gradlew :app:testDebugUnitTest
```

Expected: green (no new tests — this is build logic + Compose-semantics one-liners; the house pattern adds no JVM test for gates, and the role additions have no pure logic to test).

---

## Task 9: Finish the branch

**Files:** none

- [ ] **Step 1: Optional TalkBack spot-check (nice-to-have, not blocking)**

The three role fixes are runtime a11y behavior. If a device is handy: enable TalkBack, confirm the Start/Stop FAB announces "Button", a settings row announces "Button", and an orientation tab announces "Tab, selected/not selected". Not a CameraX flow, so no full device-camera smoke is required.

- [ ] **Step 2: finishing-a-development-branch**

Use `superpowers:finishing-a-development-branch` to open the PR: "feat: checkA11yClickableHasRole static gate (ADR-0020 §Decision-1, Gate-3)". Reference the spec + ADR amendment.

---

## Self-Review

**Spec coverage:**
- Build Gate-3 only → Tasks 1-8. ✓
- Defer Gate-1 / skip Gate-2 → recorded in spec + ADR §suite (untouched by this plan beyond the count). ✓
- Remediate-then-land GREEN → Task 2 (RED-proof) → Task 3 (fix) → Task 4 (wire). ✓
- Chain-scoped scan + opt-out reason-required + scope boundary → Task 1 code + comments. ✓
- RED-proof before wiring → Task 2. ✓
- ADR amendment / CLAUDE.md 39→40 / CHANGELOG → Tasks 5-6. ✓
- codex review of gate code → Task 7. ✓

**Placeholder scan:** The `lines_at(f, i)` shorthand in Task 1 Step 1 is explicitly flagged as illustrative with the exact compliant replacement provided immediately after — not a residual placeholder. No `TBD`/`TODO`.

**Type/name consistency:** `checkA11yClickableHasRole` (task val + dependsOn + ADR + CLAUDE.md + CHANGELOG) consistent. `role`/`roleAssign`/`clickable`/`terminator`/`backWindow`/`maxForward` consistent within Task 1. `Role.Button` (FAB, settings row) vs `Role.Tab` (orientation tab) deliberately distinguished per Task 3.

**Known risk (documented):** the recon's candidate offender list had 2 false positives; the plan does NOT trust it — Task 2 runs the gate as the oracle and Task 3 fixes whatever it actually reports (the 3 named are the expected minimum, calibrated in Task 2 Step 2).
