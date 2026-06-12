# PR-ε — Rotation-first record chrome (fixed window + counter-rotating elements)

**Status:** Owner-ratified design, 2026-06-12. Supersedes the §B′ "rotate, don't redesign" chrome *mechanics* on compact screens; §B′ chrome survives as the large-screen fallback (§9).
**Inputs:** research report `docs/superpowers/research/2026-06-11-rotation-first-chrome-research.md` (25/25 claims verified); on-device observation of One UI Camera segments A/C/D (owner's Samsung, 2026-06-12); owner ratifications: fixed-physical model (Option 1), unlock-while-open settings (Option A), **I-style strip** (upright cells), HUD-spins-mid-REC improvement.
**Scope:** Record-home chrome only. Zero service changes. γ (capture topology / orientation policy / terminology) fully orthogonal and untouched.

---

## 1. Principle

The record-home answers device rotation the way a physical camera does: **the body does not re-arrange; the markings stay level.**

- The Activity window is **orientation-locked to portrait** while record-home chrome is visible on a compact-width display.
- Layout is **frozen**: no component ever moves, resizes, or re-orders on rotation, in any state.
- Device rotation drives exactly one thing: designated elements **counter-rotate in place** so their content stays upright to the user.
- This is the documented photography-app model ("device" model — stock iOS Camera). The social-app "interface" model (output follows UI window) is an explicit non-goal.

Measured native grounding: One UI Camera ignores the system auto-rotate toggle and adapts by sensor; its settings screen rotates normally; its mid-REC layout freezes completely. iOS freezes layout always and spins glyphs. PR-ε = iOS mechanics, with two deliberate divergences recorded in §6/§7.

## 2. Window + orientation mechanics

### 2.1 Lock
- `setRequestedOrientation(portrait)` while: record route is current **∧** no modal surface open **∧** window width class is Compact. Otherwise restore the prior value.
- Implemented as a `DisposableEffect` keyed on those three inputs, restoring on dispose. Precedent: the existing DualShot portrait lock (subsumed by this — DualShot's condition becomes redundant but harmless).
- Pure helper `RecordChromeLockPolicy.shouldLock(isRecordRoute: Boolean, modalOpen: Boolean, isCompactWidth: Boolean): Boolean` — JVM-tested.

### 2.2 Orientation signal
- New `DeviceOrientationSignal` (`ui/signals/`, house seam pattern): wraps `OrientationEventListener`, exposes `StateFlow<Int>` of snapped rotation 0..3.
- Snap math **reuses `OrientationSnap`** (PR-α) — one snap implementation in the codebase; the UI signal and the service seam must not drift. The service's own listener and capture-side `targetRotation` handling are untouched.
- `ORIENTATION_UNKNOWN` (device flat): early-return, hold last value. Never animate while flat. The callback fires once at the transition to flat — no special-case polling.
- Hysteresis: canonical 45° boundaries widened by `ORIENTATION_HYSTERESIS_DEG = 10` (no official value exists; device-tuned constant, one place). Crossing requires exceeding the boundary by the band.
- Enabled when record-home chrome is visible (composition-scoped), disabled otherwise.

### 2.3 Two named mappings (trap)
Physical degrees → UI counter-rotation angle and physical degrees → `Surface.ROTATION_*` are **different numbers** (the canonical CameraX snap maps physical 45–135° to ROTATION_270 — inverse sense). Keep two named pure functions; never pass one where the other is meant:
- `uiCounterRotationDegrees(snappedRotation: Int): Float` → `0, -90, 180(±), +90` for rotations `0,1,2,3` (sign chosen so content appears upright; sense decides −90 vs +90).
- Capture-side mapping: already exists in the service (PR-α) — not touched, asserted by the existing `checkSetTargetRotationBoundaryOnly` gate.

### 2.4 Animation
- One unwrapped-accumulator angle per chrome scope, animated with `Animatable<Float>`; retarget by shortest path: `delta = ((target − current + 540) mod 360) − 180`.
- **Duration 180 ms, standard (Material) easing.** Basis: One UI's full transition measured at ~80–160 ms (snap-like); Material short-motion band 200–300 ms; 180 ms sits between — tune on device, single token `RecordChromeTokens.elementSpinMs`.
- All spinning elements animate **simultaneously** (no stagger — One UI shows none; stagger adds perceived latency).
- First composition: jump to the current angle without animating (no spin-on-entry).
- Mid-animation retarget (user rotates back): `Animatable` retargets along shortest path — spike task in the plan verifies interrupt behavior before wiring all elements.

## 3. Spin-class table (every chrome element, all states)

| Class | Elements | Treatment |
|---|---|---|
| **Cell** | Config-strip cell contents (value+label), SWIPE-TO-EDIT caption, nav icons+labels (Library/Settings), FAB glyph, stop-FAB glyph, flash + flip glyphs | Spin upright in place. Visual child inside `graphicsLayer`; **`clickable`/semantics on the stable outer container** (square, ≥48 dp). Square slots make bounds rotation-invariant. |
| **Info pill** | Status pill ("Ready to record"), active-HUD pills (timer, clip-progress), warning chip | Spin in place with **reserved transpose clearance** — placement guarantees the rotated AABB (diagonal mid-spin, √(w²+h²)) overlaps only viewfinder, never other chrome. |
| **Surface** | Settings sheet, warning sheet, recovery card, any modal | **Never spins.** Opening one releases the window lock (§7); the window rotates normally and the existing portrait/landscape surface presentations apply unchanged. |
| **Neutral** | Framing guide brackets, gradient scrims, viewfinder | Orientation-invariant; nothing to do. |

Mid-recording: identical table. Layout is frozen by construction; pills keep spinning so the **timer stays readable in landscape grip** — deliberate divergence from One UI (which leaves its timer sideways), justified because Follow-Device re-orients capture per clip boundary, so landscape-grip recording is a first-class Rova state.

## 4. Config strip (I-style, owner-ratified)

- One pill, one footprint, both orientations. The pill and its cells **never move**; each cell's content counter-rotates inside its slot.
- Cells normalize to **uniform square slots (48 dp)** in portrait too (today they vary width — `None`, `FHD` wider). 48 dp = verified touch-target floor **and** the rotation-invariant bound; spinning visual content must fit ⌀ ≤ slot/√2 ≈ 33.9 dp (standard value+label block fits).
- No order remapping ever: frozen pixels mean the apparent top-to-bottom order in landscape is whatever physical rotation produces, per sense — exactly iOS behavior. `railOrder`/`clusterEdge` are **not used** on the compact path (retained for §9).
- The γ accent rule is unchanged: quiet `Auto`, accent gradient iff topology ≠ Single; `LOCKED · <orientation>` cell appears per γ spec and spins as a Cell.

## 5. Nav cluster + camera controls

- Nav (Library / FAB / Settings) and flash/flip stay exactly where portrait puts them. Glyphs + their text labels spin as Cells (label is part of the spinning content block; if a spun label would exceed its slot, the label fades out at non-0 rotations — decided per element in the plan with measured widths).
- The β T5/T6 axis-flip branches (`Row↔Column`, `sense` params) are **bypassed on the compact path** — composables keep the parameters (used by §9) but compact always passes the portrait shape.

## 6. Active state (HUD)

- HUD pills (status/timer, clip progress) and stop FAB: frozen positions, spinning contents (Info pill / Cell classes).
- This also resolves the known floating-HUD geometry anomaly by *construction* — the HUD never re-anchors in landscape; the pre-γ screenshot's scattered mid-canvas pills cannot recur.

## 7. Modal surfaces — unlock-while-open (ratified A)

- Opening the settings sheet (or warning sheet / recovery card surfaces if modal) releases the lock; the window rotates normally; the **existing** bottom-sheet (portrait window) and side-panel (landscape window) presentations are reused **verbatim** — zero new sheet code.
- Closing restores the lock; the window snaps back to portrait if held landscape. The visible open/close rotation in landscape grip is an accepted cost (Samsung's settings does the full-screen equivalent) — explicit device-smoke checkpoint.
- The viewfinder live preview stays visible per the existing sheet peek treatment.

## 8. What is deleted, gated, or kept (compact path)

| Item | Fate |
|---|---|
| Force-sensor viewfinder rotation (`ea508a5`), display-driven upright fix (`03be1ff`), seamless viewfinder rotation animation (`09f2db5`) | **Inert on compact** (no window rotation happens); code gated by chrome mode, removed only if §9 proves it unused there too |
| `landscapeSense` | Kept — supplies spin **direction** (sense → ∓90) |
| `clusterEdge`, `railOrder`, slot landscape branches, axis-flip branches | Kept, **fallback-only** (§9) |
| `SettingsSidePanel` | Kept — used whenever the window is actually landscape (§7 unlock, §9) |
| DualShot-specific portrait lock | Subsumed by §2.1 |

## 9. Large screens (mandatory fallback)

- API 36 ignores orientation-lock APIs on sw600dp+ displays; API 37 (Rova's targetSdk) removes the opt-out; **no camera exemption**. iPad Camera also abandons the fixed model — the fallback is correct UX, not just compliance.
- `chromeMode(windowWidthSizeClass)`: `FixedPhysical` (Compact) | `Adaptive` (Medium/Expanded). Pure helper, JVM-tested.
- `Adaptive` = today's β behavior unchanged: window rotates, slot placement + axis-flip chrome, side-panel sheet.

## 10. Out of scope

- Service/capture: no changes (PR-α seam; `checkSetTargetRotationBoundaryOnly` enforces the boundary).
- γ surfaces: mode registry, orientation policy row, LOCKED cell, vocabulary — all unchanged.
- System status-bar visibility on the viewfinder (One UI hides it, Rova shows it — ADR-0011 choice, noted, not changed here).
- Tablet-specific chrome design beyond reusing β. DualSight (PR-δ).

## 11. ADR + gates

- Amend ADR-0029: add **§B″ "Fixed window + counter-rotating elements (compact)"** recording §1–§7 decisions and demoting §B′ chrome to the Adaptive fallback. No existing gate changes.
- New gate candidate (plan decides feasibility): `checkRecordChromeLockSingleSite` — `setRequestedOrientation` on the record route only via the §2.1 helper.

## 12. Acceptance criteria (device smoke, owner's Samsung)

1. Idle portrait: byte-identical behavior to γ build except normalized square strip cells.
2. Rotate idle to both landscape senses: nothing moves; cell/glyph contents spin upright, simultaneous, ~180 ms, no clipping into neighboring slots; status pill spins with clearance.
3. System auto-rotate toggle OFF (owner default) and ON: identical behavior in both.
4. Lay flat: no spin; pick up into landscape: single clean spin to the held sense.
5. Record a session in landscape grip with Follow-Device: HUD timer readable (upright) mid-clip; saved clips correctly oriented (existing PR-α behavior unregressed); zero configuration changes logged on the record route during the session.
6. Open settings sheet in landscape grip: window unlocks and rotates, side panel presents; close: re-lock, snap back; live preview visible throughout.
7. Rapid rotate back-and-forth mid-spin: shortest-path retarget, no long-way 270° spins, no stuck angles.
8. TalkBack: traversal order unchanged in all grips; spun elements announce identically.
9. sw600dp emulator or desktop-windowing check: Adaptive chrome engages (β behavior), no lock attempts.

## 13. Self-review notes

- I-style frozen order means landscape cell order *differs by sense* (no remap) — intentional, matches iOS; called out so it isn't "fixed" later as a bug.
- The only new persistent state is none; the only new tunables are `elementSpinMs` and `ORIENTATION_HYSTERESIS_DEG`.
- Hit-testing verified: Android Compose pointer input follows `graphicsLayer`; modifiers outside the layer keep stable bounds — §3 places interaction on the stable container deliberately.
- Animatable interrupt behavior is the single unverified mechanic → explicit spike task in the plan before mass wiring.
