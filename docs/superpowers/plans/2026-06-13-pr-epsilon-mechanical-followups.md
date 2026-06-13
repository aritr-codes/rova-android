# PR-ε mechanical follow-ups — FU-3 (BackHandler disarm) + FU-4 (defer spin read to draw)

> Two MECHANICAL record-chrome follow-ups off the PR-ε arc (master `355069e`).
> Both are Compose-layer refactors — no new pure logic, no ADR change, no gate
> change. FU-1 (warning-sheet visibility hoist) and FU-2 (thermal-tips reading
> frame) are OUT OF SCOPE — they need their own design spec; do NOT plan them here.
>
> **Execution model (house convention):** subagents that run this plan are
> EDIT-ONLY. The controller (main session) runs ALL gradle. No subagent invokes
> the build.

## Scope & priorities

| FU | Priority | What | Files |
|----|----------|------|-------|
| FU-3 | P3 | Keep `BackHandler` consuming during the panel's exit animation so a back-press can't race the dismissal and pop the nav / exit the screen. | `FloatingSettingsPanel.kt` (self-contained) |
| FU-4 | P2 | Defer the per-frame spin-angle read to the DRAW phase by threading `() -> Float` instead of `Float`, so the 180ms chrome spin re-runs only the `graphicsLayer` block — no recomposition of the chrome subtree. | `RecordScreen.kt`, `RecordChrome.kt`, `FloatingSettingsPanel.kt` |

## codex peer review (done — folded in)

Reviewed both approaches (`mcp__codex__codex`, high reasoning). Verdict: FU-4
correct in principle; FU-3 correct with a small gating refinement. Two
refinements folded into the tasks below:

- **FU-4** — pass a REMEMBERED provider `remember(cond, spin) { { … } }` (NOT a
  fresh lambda per recomposition) so unrelated parent recompositions don't churn
  downstream skippability via a new lambda identity. Do NOT key the remember on
  `spin.value`. And: never bind `val d = degrees()` ABOVE the `graphicsLayer` block
  — that reintroduces the composition read and defeats the entire refactor.
- **FU-3** — gate `enabled` on "visible OR entering", and make `onBack` a no-op
  while the exit is running (`targetState == EnterExitState.PostExit`), calling
  `onDismiss()` only when not exiting.

---

## T1 — FU-3: keep BackHandler armed (no-op) through the exit animation

**What.** During `AnimatedVisibility`'s fade-OUT the panel content is still
composed but `enabled = visible` is already `false`, so a back-press is NOT
consumed and propagates (could pop nav / leave the screen mid-dismiss). Keep the
`BackHandler` enabled until the exit transition settles; consume the press as a
no-op while exiting.

**Files.** `app/src/main/java/com/aritr/rova/ui/screens/FloatingSettingsPanel.kt`
(self-contained — touch nothing else).

**Change.**
- Current body (lines ~149-154):
  ```kotlin
  AnimatedVisibility(
      visible = visible,
      enter = if (reduceMotion) EnterTransition.None else fadeIn(tween(160)),
      exit = if (reduceMotion) ExitTransition.None else fadeOut(tween(160)),
  ) {
      BackHandler(enabled = visible, onBack = onDismiss)
      …
  ```
  The trailing lambda of `AnimatedVisibility` is an `AnimatedVisibilityScope`,
  which exposes `transition: Transition<EnterExitState>`. Replace the
  `BackHandler` line (inside the scope, where `this` is the
  `AnimatedVisibilityScope`) with:
  ```kotlin
  // FU-3 — the exit animation runs while this content is STILL composed.
  // Keep the back gesture consumed until the transition settles so a
  // back-press can't race the fade-out and pop the nav (enabled = visible
  // alone went false the instant dismissal started). While exiting we
  // consume as a NO-OP; only a press while actually visible re-fires dismiss.
  val exiting = transition.targetState == EnterExitState.PostExit
  val backArmed = transition.currentState == EnterExitState.Visible ||
      transition.targetState == EnterExitState.Visible
  BackHandler(enabled = backArmed) {
      if (!exiting) onDismiss()
  }
  ```
- Add import: `androidx.compose.animation.EnterExitState` (confirmed package by
  codex). `AnimatedVisibility` / `BackHandler` imports already present
  (lines 3-4). `transition` is a member of the `AnimatedVisibilityScope`
  receiver — no import needed for it.
- Leave the rest of the block (the `namingVisible` / `pendingDelete` /
  `expandedRow` state, `BoxWithConstraints`, the `SpinningBox` card) unchanged
  EXCEPT for the FU-4 param edit in T4.

**Verification.**
- Compiles (controller build, T6). No new symbol other than `EnterExitState`.
- Reasoning check: `enabled` is true whenever the panel is Visible or animating
  toward Visible (enter), AND stays true through the exit because
  `currentState == Visible` holds until the exit transition completes; once
  settled-out, the content leaves composition and the `BackHandler` leaves with
  it (codex Q5 — no stuck handler). Under `reduceMotion` (enter/exit `None`) the
  transition settles synchronously, so there is no lingering no-op window.
- Owner device-smoke (T7): pressing back during the close fade does nothing
  visible-bad (no screen pop); pressing back while open still dismisses.

---

## T2 — FU-4: convert `SpinningBox` + the producer to a `() -> Float` provider

**What.** Make the leaf read the angle at DRAW time and make the producer a
provider lambda, so `spin.value` is no longer read in composition.

**Files.**
- `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt` — `SpinningBox`.
- `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt` — the producer.

**Change — `SpinningBox` (RecordChrome.kt ~lines 110-129).**
- Signature: `degrees: Float` → `degrees: () -> Float`:
  ```kotlin
  internal fun SpinningBox(
      degrees: () -> Float,
      modifier: Modifier = Modifier,
      fadeOutWhenRotated: Boolean = false,
      content: @Composable () -> Unit,
  ) {
  ```
- Body `graphicsLayer` block — invoke INSIDE the lambda (draw phase). Read ONCE
  into a local INSIDE the block to avoid a double snapshot read when
  `fadeOutWhenRotated` is set:
  ```kotlin
  .graphicsLayer {
      val deg = degrees()           // draw-phase read — never hoist above this block
      rotationZ = deg
      if (fadeOutWhenRotated) alpha = uprightFadeAlpha(deg)
  }
  ```
  CRITICAL (codex): do NOT write `val deg = degrees()` ABOVE the `graphicsLayer {`
  — that is a composition read and undoes the whole optimization.

**Change — producer (RecordScreen.kt line ~337).**
- From:
  ```kotlin
  val spinDegrees = if (chromeModeNow == ChromeMode.FixedPhysical && lockChrome) spin.value else 0f
  ```
- To a REMEMBERED provider (codex — avoids churning downstream skippability with
  a fresh lambda identity each recomposition; key on the two booleans + the
  stable `spin`, NOT on `spin.value`):
  ```kotlin
  // FU-4 — defer the per-frame `spin.value` read to draw time. Threaded as a
  // provider so the 180ms spin re-runs only each SpinningBox graphicsLayer
  // block, not the chrome subtree's composition. Remembered so an unrelated
  // RecordScreen recomposition doesn't hand intermediates a new lambda identity.
  val spinDegrees: () -> Float = remember(chromeModeNow, lockChrome, spin) {
      { if (chromeModeNow == ChromeMode.FixedPhysical && lockChrome) spin.value else 0f }
  }
  ```
- `spin`, `chromeModeNow`, `lockChrome` are all in scope at line 337 (verified:
  `spin` = line 285, `lockChrome` = 327, `chromeModeNow` used at 287/330/337).
- The reduced-motion path is UNCHANGED: the `LaunchedEffect` at lines 286-291
  still `spin.snapTo(target)` under `reduceMotion` (instant) vs
  `spin.animateTo(…, tween(elementSpinMs))` otherwise. The provider only changes
  WHEN/WHERE `spin.value` is read, never the animation that drives it. Do NOT
  touch that effect.

**Verification.**
- Controller build (T6).
- Grep audit (T5 covers the cascade): after edits, no `spinDegrees: Float` and
  no `degrees: Float` parameter declarations remain; every `SpinningBox(degrees =
  …)` call passes a lambda.
- Behavioural: spin still animates on orientation change; snaps under
  reduced-motion (the `spin.snapTo` path is untouched).

---

## T3 — FU-4: cascade the provider type through every intermediate composable in RecordChrome.kt

**What.** Every composable that currently carries `spinDegrees: Float` (or, for
`SpinningBox`, `degrees: Float`) and forwards it must change its param type to
`() -> Float` and forward the provider unchanged. Call sites that pass a CONSTANT
become `{ constant }`. This cascade is the bulk of the work — enumerated exactly
below so no re-discovery is needed.

**File.** `app/src/main/java/com/aritr/rova/ui/screens/RecordChrome.kt`.

**Functions whose param changes `spinDegrees: Float = 0f` → `spinDegrees: () -> Float = { 0f }`** (default becomes `{ 0f }`):

| Fn | Decl line | Notes |
|----|-----------|-------|
| `RecordTopOverlay` | 145 | forwards at 153 `SpinningBox(degrees = spinDegrees)` |
| `RecordCameraControls` | 242 | forwards into the `flash`/`flip` local lambdas at 246-247 (passes `spinDegrees` to `CamFlashButton`/`CamFlipButton`) |
| `CamFlashButton` | 260 | private; `spinDegrees: Float = 0f` → `() -> Float = { 0f }`; forwards at 270 |
| `CamFlipButton` | 294 | private; same; forwards at 310 |
| `RecordSettingsCard` | 360 | forwards to many `SettingsCell` / `ModeCycleChip` calls (411-467) and one direct `SpinningBox` at 439 |
| `ModeCycleChip` | 518 | private; forwards at 546 |
| `SettingsCell` | 574 | private; forwards at 582 |
| `RecordBottomNav` | 649 | forwards into `library`/`fab`/`settings` local lambdas (656-660) |
| `NavItem` | 707 | internal; forwards at 728 |
| `RecordFab` | 745 | internal; forwards at 782 |
| `RecordRecoveryChip` | 805 | public; forwards at 820 |
| `LoopPill` | 1059 | private; forwards at 1073 |
| `LoopSegmentBar` | 1106 | private; forwards at 1117 (note `fadeOutWhenRotated = true` — works the same; the lambda is read inside the block per T2) |
| `StatusPill` | 1149 | private; forwards at 1153 |
| `RecordActiveHud` | 1223 | forwards to `LoopPill`/`StatusPill`/`LoopSegmentBar` (landscape branch 1250-1260) AND wraps the portrait group in `SpinningBox(degrees = spinDegrees)` at 1271 |

**Every `SpinningBox(degrees = spinDegrees …)` call site in RecordChrome.kt** —
these need NO edit to the argument (they already pass the param named
`spinDegrees`, which is now a `() -> Float`): lines **153, 270, 310, 439, 546,
582, 728, 782, 820, 1073, 1117, 1153, 1271**. They become correct automatically
once the enclosing fn's param type flips. Confirm each still reads
`degrees = spinDegrees` (an identifier pass-through, not `degrees = spin.value`).

**Forwarding call sites that pass the param to ANOTHER composable** (no
constant — just propagate the identifier, correct once types flip):
246, 247, 411, 413, 415, 417, 424 (`ModeCycleChip`), 433 (`SettingsCell` locked),
459, 460, 461, 462, 463, 467 (landscape cell lambdas), 656, 658, 660, 1250, 1251,
1260. None of these need a value edit — only the receiving fn's param type change
(above) makes them type-check.

**Constant call sites.** Scan for any `SpinningBox(degrees = 0f …)` or
`spinDegrees = 0f` literal pass — convert to `{ 0f }`. From the current grep
there is NO literal-`0f` call site (all call sites pass the named `spinDegrees`
identifier or rely on the `= 0f` DEFAULT). The defaults convert to `{ 0f }` per
the table above; that is the only "constant" change.

**Non-graphicsLayer reads (codex Q3 — flag/verify).** Audit confirms NO consumer
reads `spinDegrees` for layout/branch/label purposes — every consumer either
(a) forwards it, or (b) feeds it straight into `SpinningBox(degrees = …)`. So the
provider conversion fully removes per-frame recomposition; nothing needs a
composition-time `Float`. If T5's grep surfaces any arithmetic/comparison on
`spinDegrees` (e.g. `if (spinDegrees != 0f)`), STOP and flag — that path would
need `spinDegrees()` at composition and would not benefit. (None expected.)

**RecordActiveHud landscape note (line 1244-1251).** The existing comment says
`spinDegrees is always 0f here` in the Adaptive landscape branch. It still
forwards the provider for uniformity — leave the forwarding as-is (provider that
returns 0f). No special-casing.

**Verification.** Controller build (T6); JVM tests (T6) green. Post-edit grep:
zero `: Float` on any `spinDegrees`/`degrees` chrome param; zero `spin.value`
read anywhere except inside the producer provider in RecordScreen.kt.

---

## T4 — FU-4: cascade the provider into FloatingSettingsPanel.kt + update the 8 RecordScreen.kt call sites

**What.** Two remaining consumers of the producer: `FloatingSettingsPanel` (one
threaded `SpinningBox`) and the 8 RecordScreen call sites that pass
`spinDegrees = spinDegrees`.

**Files.**
- `app/src/main/java/com/aritr/rova/ui/screens/FloatingSettingsPanel.kt`
- `app/src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt`

**Change — `FloatingSettingsPanel` (FloatingSettingsPanel.kt).**
- Param at line 121: `spinDegrees: Float,` → `spinDegrees: () -> Float,` (it is a
  required param here, no default — keep it required).
- Forward at line ~179-180: `SpinningBox(degrees = spinDegrees, …)` is already an
  identifier pass-through — correct once the param type flips and once
  `SpinningBox` takes `() -> Float` (T2). No value edit.
- This file also gets the T1 (FU-3) edit; both edits are independent and live in
  the same composable — apply both.

**Change — RecordScreen.kt call sites.** All 8 pass the producer by name; they
need NO value edit (the producer is already `() -> Float` after T2). Confirm each
still reads `spinDegrees = spinDegrees` (identifier, not `spin.value`). Call
sites (from grep): **887** (`RecordRecoveryChip`), **987** (`RecordActiveHud`),
**1093** (`RecordSettingsCard`), **1113** (`RecordTopOverlay`), **1149**
(`RecordCameraControls`), **1169** (`RecordBottomNav`), **1213**
(`FloatingSettingsPanel`). (The grep also lists 1197, a comment line — ignore.)

> NOTE: only 7 live call sites resolve from the grep (887, 987, 1093, 1113, 1149,
> 1169, 1213). If the controller build flags an 8th `spinDegrees =` not in this
> list, it is a forwarding site already covered by T3's table; reconcile against
> the build error, do not invent one.

**Verification.** Controller build (T6). The only RecordScreen.kt edit is the
producer (T2); these call sites are pass-throughs that re-type automatically.

---

## T5 — FU-4: post-edit grep audit (edit-only, no build)

**What.** Before handing back to the controller, a subagent runs a read-only
grep sweep to confirm the cascade is complete and no stray composition read of
`spin.value` survives.

**Checks (Grep tool, both files + RecordScreen.kt):**
1. `spinDegrees:\s*Float` → expect ZERO matches (all converted to `() -> Float`).
2. `degrees:\s*Float` → expect ZERO matches (SpinningBox converted).
3. `spin\.value` → expect the producer provider lambda (RecordScreen.kt ~337) as
   the ONLY NEW read. **Reconciled at execution:** the untouched `LaunchedEffect`
   target calc at RecordScreen.kt:288 already reads `spin.value` twice
   (`spin.value + shortestPathDelta(spin.value, …)`) — that is the animation
   driver (out of scope), NOT a leak. So the raw grep shows 3 total reads (288×2 +
   provider×1); any OTHER match in the chrome composition path = a leaked
   composition read; STOP and flag.
4. `spinDegrees\s*[!=<>]=|spinDegrees\s*[+\-*/]` → expect ZERO (no arithmetic /
   comparison on the provider; would indicate a non-graphicsLayer read needing a
   different fix — codex Q3).
5. `degrees\(\)` → expect exactly the SpinningBox `graphicsLayer` invocation(s);
   confirm none sit ABOVE a `graphicsLayer {` line.

**Verification.** All five expectations met. Findings reported to controller; do
NOT build.

---

## T6 — Controller build + JVM tests (CONTROLLER ONLY)

**What.** The controller (main session) runs the build and tests once T1-T5 land.
No subagent runs gradle.

**Build-env recovery dance (run BEFORE the build — broken kotlin post-edit hook
can corrupt the incremental cache):**
```powershell
.\gradlew.bat --stop
# kill any stray java daemons (PowerShell): Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force
Remove-Item -Recurse -Force app\build\kotlin -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force .gradle\kotlin -ErrorAction SilentlyContinue
```

**Build gate (NOT lintDebug — it is RED on pre-existing `VaultAndroidOps` NewApi,
unrelated to this work):**
```powershell
.\gradlew.bat :app:assembleDebug
```

**JVM tests — must stay green:**
```powershell
.\gradlew.bat :app:testDebugUnitTest
```

**JVM test note.** FU-3 and FU-4 are UI-mechanical Compose-layer refactors with
NO new pure logic — FU-3 is an `enabled`/`onBack` gate read off the Compose
`Transition`; FU-4 is a parameter-type cascade + a draw-phase read. The house
pure-helper-extraction pattern only earns a new JVM test when there is testable
pure logic (a math seam, a state machine, a classifier). There is none here, so
NO new pure helper and NO new JVM test is warranted. The existing
`testDebugUnitTest` baseline must simply stay green (no test touches `SpinningBox`
or the panel directly; the param-type change is source-compatible at the test
boundary because no test constructs these composables).

**Static-gate note.** No gate changes. `checkA11yAnimationGated` still holds (the
`reduceMotion` gating on the panel's enter/exit and on the spin animation is
untouched). `checkRecordChromeLockSingleSite` still holds (the single
`setRequestedOrientation` site is untouched). No ADR amendment — neither FU
changes a behavioral invariant; FU-4 is a pure rendering-performance refactor and
FU-3 hardens an existing dismissal contract.

**Verification.** `assembleDebug` BUILD SUCCESSFUL; `testDebugUnitTest` 0-0-0
(baseline preserved). If the kotlin cache corrupts mid-build, re-run the recovery
dance and rebuild.

---

## T7 — Owner device-smoke (MANDATORY — emulators fail CameraX)

**What.** Real-device smoke on the owner's hardware (RZCYA1VBQ2H / Android 14).
Emulators consistently fail CameraX video recording, so a green build is NOT a
GO on its own.

**Smoke checklist:**
1. **FU-3** — open the floating settings panel; press the system Back during the
   close fade-out. Result: panel dismisses cleanly, the Record screen does NOT
   pop / exit / navigate away. Press Back while the panel is fully open → it
   dismisses (unchanged behaviour).
2. **FU-4 — spin still correct** — rotate the device through portrait →
   landscape (both grips) → reverse-portrait while idle and while recording. The
   chrome (status pill, nav glyphs, FAB, cells, cam controls, recovery chip,
   active HUD) still counter-rotates and reads upright in each grip, exactly as
   before. No visual regression in the spin.
3. **FU-4 — reduced per-frame jank** — with several chrome elements visible
   (idle settings strip + status pill + nav), trigger an orientation change and
   watch the 180ms spin: it should be visibly as smooth or smoother (no
   recomposition churn). Optional: confirm via Layout Inspector / recomposition
   counts that the chrome subtree does NOT recompose per frame during the spin
   (the layer re-runs, composition does not).
4. **FU-4 — reduced-motion** — enable the OS "remove animations" setting; trigger
   an orientation change. The spin SNAPS instantly (no 180ms tween) — the
   `spin.snapTo` path is intact. Re-confirm the panel enter/exit is also instant.

**Verification.** Owner GO on all four items. If FU-4 item 4 (reduced-motion
snap) fails, the producer/`LaunchedEffect` wiring was disturbed — revert T2's
producer edit and re-inspect.

---

## codex review gate (FU-4 only)

FU-4 touches >5 lines across 3 files (a param-type cascade) — per the project's
codex-consult policy this warrants a peer review before finalizing. The design
approach was already reviewed (folded into the tasks above: remembered provider,
no hoisted `degrees()` read, no per-frame recomposition). Re-run
`mcp__codex__codex` on the ACTUAL diff before merge to confirm the mechanical
cascade introduced no stray composition read or missed call site. FU-3 is a
trivial ~6-line gate edit — no separate codex gate needed.

## Out of scope (do not plan / do not touch)

- FU-1 (warning-sheet visibility hoist) and FU-2 (thermal-tips reading frame) —
  separate design spec required.
- `uiCounterRotationDegrees` / `shortestPathDelta` in
  `ui/screens/chrome/ChromeSpin.kt` — the angle math is correct and unrelated;
  leave it untouched.
- The `LaunchedEffect`-driven `spin` Animatable and its reduced-motion snap path
  (RecordScreen.kt 285-291) — FU-4 only changes where `spin.value` is READ, never
  how it is animated.
