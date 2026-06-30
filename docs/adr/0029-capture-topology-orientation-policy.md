# ADR-0029: Capture topology and orientation policy — two orthogonal axes replace the `mode` string

Status: Accepted (2026-06-11 — promoted on PR-γ landing per the amendment note below)
Date: 2026-06-08

> **Amendments (2026-06-08, owner-ratified).** Four open questions were
> resolved with cross-model (codex) review and owner sign-off; the clauses below
> incorporate them: (A) default `OrientationPolicy`; (B) PR ordering; (C)
> `FrontBack` composition; (D) lock scope. The ADR stays **Proposed** — it is
> promoted to **Accepted** when PR-γ lands. Changes vs the original draft are
> flagged inline as **[Ratified A/B/C/D]**.

> **Implementation status (2026-06-14): `FrontBack` (DualSight) BUILD phase is DEFERRED / BLOCKED.**
> PR-α/β/γ/ε (the two-axis model + rotation-first chrome) are merged and Accepted.
> The final `FrontBack` (DualSight) capture phase — §5's single-file PiP via CameraX
> `CompositionSettings` — was investigated via a hardware probe and is **blocked pending
> a device with verified concurrent front+back support** (Pixel 7+/Samsung Director's
> View class). On the available device (Galaxy A-class, single-ISP) concurrent capture is
> unsupported at the silicon level (`FEATURE_CAMERA_CONCURRENT=false`,
> `getConcurrentCameraIds()` empty, `bindToLifecycle` threw `UnsupportedOperationException`),
> and no non-concurrent path delivers simultaneous front+back *video*. The full investigation —
> evidence (`…-dualsight-probe-results.md`), the deferred feature plan
> (`…-dualsight-delta-feature-DEFERRED.md`), the δ0 bump plan, and the preserved capability helper
> `service/dualsight/ConcurrentCameraCapability.kt` (+ tests) — is **archived in PR #115 (branch
> `feat/pr-delta-dualsight`)**, not on master. §5's "PR-δ extends the allowlist" clause therefore
> remains **pending** — `checkFrontBackCapabilityGated` keeps fencing `FrontBack` to its declaration
> sites; the allowlist extension happens only when the feature is unblocked and built.

## Context

Rova's recording "MODE" is a single string enum
`"Portrait" | "Landscape" | "PortraitLandscape"` that conflates **two orthogonal
concerns**: camera **topology** (how many sensors, how they are framed) and
output **orientation**.

The verified root cause of owner-reported disorientation is in
`RovaRecordingService.computeTargetRotation(displayRotation, mode)`: it returns
`base` for Portrait, `(base + 1) % 4` for Landscape (a hidden +90° offset), and
`base` for P+L. `base` is the device `displayRotation` read **once at bind time**.
There is **no `OrientationEventListener`**; rotation is locked at bind and never
re-sampled. So "Landscape mode" means the output is rotated 90° from a one-time
snapshot that never tracks how the phone is physically held. Rova is a
hands-free, often phone-**mounted** periodic recorder, where a static snapshot is
exactly the wrong model: a mount can be angled or inverted, and the recorder
keeps firing segments for hours against a rotation read at second zero.

ADR-0026 already established preset⊥orientation (a preset never carries a
mode/orientation field, enforced by `checkPresetNoOrientation`). This ADR extends
that orthogonality to the mode axis itself: topology and orientation become two
independent axes, and orientation becomes device-driven (with a first-class lock)
instead of bind-frozen.

This is a multi-PR program. The candidate sequence is:
**α** device-driven `Auto` (modes untouched — the quick disorientation win) →
**β** landscape chrome re-layout (Configuration-driven) →
**γ** collapse Portrait/Landscape→`Single` + `OrientationPolicy` + migration →
**δ** `FrontBack` concurrent dual-camera.

## Decision

1. **Two axes replace the conflated `mode` string.**
   `CaptureTopology { Single, DualShot, FrontBack }` — `Single` replaces **both**
   "Portrait" and "Landscape" (they differed only in orientation, never in
   topology); `DualShot` is today's P+L single-sensor two-crop pipeline;
   `FrontBack` is a future two-sensor concurrent capture. The second axis is
   `OrientationPolicy { Auto, PortraitLock, LandscapeLock }`, with room to extend
   to reverse / advanced 0/90/180/270 locks. **Topology is
   orientation-orthogonal** — every topology composes with every policy (subject
   to §4 for DualShot's own contract).

   **[Ratified D]** The *user-facing* picker exposes exactly **three** options
   (`Auto`, "Lock portrait", "Lock landscape"), but the *internal* model is the
   four fixed device rotations, **not** a closed two-lock abstraction. A lock does
   **not** pin a symbolic Portrait/Landscape token to a hardcoded constant —
   `Surface.ROTATION_0` means device-**natural** orientation (portrait on phones,
   landscape on tablets/foldables/ChromeOS) and "landscape" is intrinsically
   two-valued (`ROTATION_90` *or* `ROTATION_270`). Instead, **a lock captures and
   persists the explicit currently-snapped `Surface.ROTATION_*` at lock time**
   ("lock whatever I'm aimed at now"). This makes future reverse / 180° / advanced
   locks a **UI-only** addition with zero model or persisted-prefs migration —
   shipping a symbolic two-lock abstraction now would instead force a behavioral
   prefs migration later (the very churn this ADR avoids).

2. **Orientation is device-driven by default (`Auto`) but lock is first-class,
   not optional.** codex sharpened this: a purely device-driven model is "too
   optimistic for a mounted recorder" — a mount may be upside-down or angled, and
   what users actually want is *how the final video plays back*, not *how the
   phone happens to be held*; `Auto` cannot correct a non-90° tilted mount. Locks
   pin `effectiveTargetRotation` regardless of device tilt. `Auto` drives an
   `OrientationEventListener` snapped to `Surface.ROTATION_0/90/180/270` (**not**
   raw display rotation — the display can be user-locked or skip reverse
   orientations), ignoring `ORIENTATION_UNKNOWN`, with **asymmetric hysteresis**
   near the 45° thresholds to stop flutter (reuse the existing `ThermalHysteresis`
   asymmetric-hysteresis pattern, ADR-0019).

   **[Ratified A]** The **new-user / new-session default is `Auto`**, *conditional*
   on the orientation control being visible on the pre-record / session-setup
   surface (so `Auto` never becomes a trap the user can't escape); if that control
   cannot be surfaced, the default falls back to `PortraitLock` until it can.
   A `PortraitLock` default is rejected for new users because it silently produces
   90°-sideways video for every landscape-mounted hands-free user — a worse,
   harder-to-self-diagnose failure than `Auto`'s occasional, correctable
   mid-session flip. **`Auto` MUST define a deterministic first-sample fallback**
   for a segment boundary with no stable sample (`ORIENTATION_UNKNOWN`,
   face-up/down, listener not yet delivering, motion at start): order =
   *last effective rotation for this session → current snapped display rotation →
   default portrait*, persisting which source fired (debuggability). This fallback
   lives in the pure `OrientationSnap` / `OrientationPolicyResolver` helpers and is
   a PR-α deliverable, not a later add.

3. **Rotation changes apply at SEGMENT BOUNDARIES, never mid-clip.** CameraX
   `VideoCapture.setTargetRotation` may be updated live on a bound use case
   **without** a rebind, but `Recorder` ignores target-rotation changes on an
   in-progress recording and applies them only to the **next** recording. Rova's
   segment-loop model makes this clean: orientation is sampled at each segment
   **start**. A phone rotated mid-clip leaves the current clip in its old
   orientation; the next clip adopts the new one. The HUD must distinguish the
   **current-segment** orientation from a **pending-next** orientation (so the user
   sees that a rotation will take effect at the next boundary, not silently). Because
   `setTargetRotation` can shift `ResolutionInfo`, crop/resolution consumers refresh
   after a change.

   **[Ratified B]** Because an `Auto` session may contain mixed-orientation
   segments, the **merge/concat contract** must be crisp: the merged output adopts
   the **first segment's** `effectiveTargetRotation` (the `MediaMuxer` concat path
   cannot re-rotate per-segment). This is a PR-γ acceptance criterion with explicit
   tests; per-clip orientation truth still lives in the manifest (§6) so export of
   individual clips stays correct.

4. **DualShot keeps its own rotation contract.** P+L owns rotation inside the EGL
   transform / crop / muxer-metadata pipeline (`VideoCapture.targetRotation`
   hardcoded `ROTATION_0`, ADR-0009). `OrientationPolicy` for a `DualShot` topology
   therefore feeds the **EGL / metadata path**, NOT `VideoCapture.targetRotation`.
   The two axes still compose; only the application surface differs.

5. **`FrontBack` is two concurrent sensors, hardware-capability-gated.** A
   single-sensor "front + back" is meaningless — that is already `DualShot`. True
   concurrent front+rear capture needs API 30+
   `CameraManager.getConcurrentCameraIds()` **and** per-device support for the
   specific concurrent combination; entry devices (e.g. Samsung A17) cannot do it,
   which is exactly why `DualShot` uses one sensor + two crops. `FrontBack` is
   **hidden/disabled when the concurrent combo is unavailable** — gate on the
   actually-reported combinations, not on API level alone. Prefer the CameraX
   concurrent-camera API (`LifecycleCameraProvider`, two-camera limit) over raw
   Camera2. Expect reduced max resolution / fps / stabilization. The architecture
   parallels `service/dualrecord/` (EGL + dual-muxer) but with **two CameraX
   bindings** instead of one sensor + two crops. `FrontBack` is a later, separate
   delivery (δ).

   **[Ratified C]** The composition model is **single-file Picture-in-Picture**
   (rear full-frame + front inset), opinionated default, rendered via the
   **CameraX-native concurrent-camera composition** (`CompositionSettings` /
   composition mode on `ProcessCameraProvider`) — **not** a hand-rolled EGL blend,
   and **not** two unjoined files handed to the user as the primary artifact. The
   dominant feasibility risk is **two concurrent hardware video encoders**, not GL
   complexity: `getMaxSupportedInstances()` is only an upper bound and a second
   encoder can be denied under long hands-free + thermal load, so a single composed
   encoder is strongly preferred and any raw two-file path must additionally gate on
   proven encoder-instance availability. The chosen layout is **persisted as session
   metadata**, so split / side-by-side become later **export/playback-time**
   re-renders rather than capture-time toggles. Corner *position* may be
   user-selectable; PiP-vs-split is not a capture-time choice.

6. **Migration preserves historical meaning.** New persisted / manifest fields
   `captureTopology`, `orientationPolicy`, and `effectiveTargetRotation` are added
   (SessionManifest schema bump); the legacy `mode` field stays readable for one
   release. Old `mode="Landscape"` sessions **MUST NOT** be silently reinterpreted
   as the new `Single + LandscapeLock` capture semantics — the legacy label is
   preserved as its own historical capture meaning. Runtime-prefs migration is a
   pure mapper: **[Ratified A]** `Portrait`→`Single` + **`PortraitLock`** (flagged
   **legacy-migrated** — preserve the common observed output of bind-frozen
   Portrait rather than retroactively enabling `Auto`, whose mid-session flips
   would surprise a "set-and-forget" user); `Landscape`→`Single` + `LandscapeLock`
   (flagged **legacy-migrated**); `PortraitLandscape`→`DualShot`. Note the honest
   framing: legacy `Portrait` never meant "portrait lock," only
   "rotation-at-bind"; `PortraitLock` preserves the **typical-case** output, and
   because no per-clip rotation history was ever persisted, this label-based
   mapping is the safest available default (document the lossy assumption). This is
   distinct from the **new-user** default (`Auto`, §2): migrated users keep
   least-surprise, new users get the new device-driven default — the settings UI
   must show the migrated policy clearly. Every new segment persists its `effectiveTargetRotation` so recovery
   and export treat orientation as **per-clip** metadata (under `Auto`, rotation
   may legitimately differ clip-to-clip).

## §B — PR-β amendment (2026-06-10): landscape Record-home chrome — **WITHDRAWN, superseded by §B′**

> **WITHDRAWN 2026-06-11.** Shipped as Phase β-interim, then owner-rejected: it reads as a
> *separate landscape layout*, not the portrait UI rotated. §B′ below replaces it. The §B code
> (grouped `RecordNavRail`, docked `RecordConfigCardLandscape`, `CompactSteppers` 3-up,
> `sideSheetRailInset`-band side panel) is deleted. B1–B6 are kept verbatim below for history
> only — do not implement against them.

PR-β ("landscape chrome re-layout") is specified here after a UX-research pass
(camera-app + Material 3 + WCAG 2.2) and owner-approved mocks
(`landscape_chrome_combined_mockup.html`). The shipped β-interim split (nav rail
left / Record FAB right, `ChromeSlotPlacement` `NAV_RAIL=CENTER_START` /
`RECORD_ACTION=CENTER_END`) is **revised**. Codex-reviewed
(thread 019eb14d). Portrait Record-home is **unchanged** and its
`ChromeSlotPlacement` values stay byte-identical (regression-pinned by
`ChromeSlotPlacementTest`).

**B1. Everything hugs the system-navigation-bar edge.** In landscape the grouped
nav rail, the settings side sheet, and the config-summary card all anchor to the
edge the system nav bar occupies — right for `ROTATION_90`, left for
`ROTATION_270` — so controls never get thrown to the far end on rotation. The
edge is derived by a **pure `systemNavEdge(navBarInsets)` helper** whose *primary*
signal is the `WindowInsets.navigationBars` left-vs-right inset (the layout
truth); display rotation is a fallback only. Gesture nav (no side inset) →
default **Trailing**. New pure type `NavEdge { Leading, Trailing }`.

**B2. Grouped nav rail.** Library · Record FAB · Settings become **one** vertical
rail (portrait bottom-bar adjacency preserved) hugging the system-nav edge, inset
past the system buttons. The split `RECORD_ACTION` standalone placement is folded
into the rail; the `RECORD_ACTION` slot value is kept **advisory** (no longer
rendered independently in landscape) to avoid churning the pinned test.

**B3. Settings surface is orientation-adaptive — standard (non-modal) side sheet
in landscape.** `SettingsSheet` splits into a dispatcher → `SettingsBottomSheet`
(portrait, today's modal sheet + camera peek, moved ~verbatim) + `SettingsSidePanel`
(landscape), sharing a `SettingsContent` (mode tabs / presets / steppers / quality
/ reset rows). The landscape panel is a **standard, non-modal side sheet** (M3):
**no scrim**, occupying a band **inboard of the rail** (never `fillMaxSize`), so
the rail — including the Record FAB and Library — **stays visible and tappable
while settings are open**. Camera preview stays `fillMaxSize` underneath (overlay-
inboard-band; no flex-row reflow — avoids CameraX resize churn on a tuned camera
path). The panel drops the peek (the visible preview *is* the context), demotes
the oversized Save to a compact **Done**/collapse (settings already apply on
change), scrolls its body, and enters/exits **horizontally** from the nav edge.
The rail's Settings item **toggles** the pane; system-back **collapses** it (does
not navigate); no vertical drag-dismiss. Because DualShot/P+L is portrait-locked
(see the DualShot rotation contract §4 + `DualShotPortraitGate`), the landscape
side sheet only ever applies to `Single`.

**B4. Config-summary card placement (WCAG 2.2 + Android-researched).** The idle
config card (duration / repeats / quality / mode; tap → open settings, mode chip
cycles) **docks to the rail's inboard edge beside the Record FAB**, vertically
centered — *not* bottom-center, which is the worst landscape thumb-reach zone. It
**hides whenever the side sheet is open** (the sheet occupies that inboard band —
clean mutual exclusion). Standing constraints, enforced for this card and the
panel: semi-opaque **scrim behind text** so contrast is measured against the
scrim, not the live frame (≥ 4.5:1 text / 3:1 chip border — SC 1.4.3 / 1.4.11);
card-tap **and** mode chip each ≥ **24 dp** target (48 dp preferred) with 24 dp
spacing (SC 2.5.8); honor `WindowInsets.safeDrawing` for the landscape cutout +
gesture bar; no clip at 200 % font / reflow (SC 1.4.4 / 1.4.10); both orientations
operable (SC 1.3.4) — consistent with the standing ADR-0020 "WCAG 2.2 AA by
default" requirement.

**B5. Slot model extension.** `placementFor` gains an `edge: NavEdge` parameter
(`placementFor(slot, orientation, navEdge = Trailing)`, with a delegating two-arg
overload so portrait call sites + pinned tests are untouched). Edge-aware logic is
**landscape-only**; portrait ignores `navEdge` and asserts identical outputs. New
slots `SETTINGS_SHEET` and `CONFIG_SUMMARY` (and the grouped rail) map to the
correct edge + inboard offsets.

**B6. The single biggest implementation risk (codex) is hit-test / state
coexistence drift:** leaving a `fillMaxSize` settings overlay, or the existing
`if (!combinedSettingsOpen)` chrome-suppression, in place in landscape — it would
*look* right while making the rail/FAB dead. The landscape chrome-suppression
becomes **portrait-only**; the side panel must occupy only its band. This is a
named acceptance criterion: with the sheet open in landscape, tapping the Record
FAB and Library must work.

**Slice order (codex-revised — chrome first, sheet second):**
β1 `NavEdge` + pure `systemNavEdge()` + JVM tests · β2 edge-aware `placementFor`
(+ new slots), portrait pins preserved · β3 grouped rail on the correct edge
(replaces the split) · β4 config card docked to the rail inboard edge, hidden when
sheet open · β5 split `SettingsSheet` → bottom sheet + side panel sharing
`SettingsContent` · β6 flip landscape chrome-suppression to portrait-only · β7
device smoke matrix (ROTATION_90/270 × 3-button/gesture nav × sheet open × FAB-tap-
while-open × Back-collapse × P+L gate). Reverts the interim landscape
centered-cap; keeps the portrait scroll/sticky baseline.

## §B′ — "Rotate, don't redesign" (2026-06-11, owner-ratified; supersedes §B)

> **Post-ε note (2026-06-12):** §B″ below demotes this chrome to the **Adaptive
> fallback only** (sw600dp+). Not withdrawn — the fallback is mandatory (API 36+
> ignores orientation-lock APIs on sw600dp+; see B″6). Compact devices run §B″
> `FixedPhysical` instead.

Authoritative spec: `docs/superpowers/specs/2026-06-10-landscape-rotate-not-redesign-design.md`
(+ its §11 weight amendment). Approved mock: `landscape_record_mockup.html`. Native reference:
the stock camera app (RZCYA1VBQ2H).

**B′1. Principle.** Portrait is the source of truth. Landscape is the identical widget set —
same hierarchy, grouping, order, and interaction model — re-anchored by the device rotation,
every widget kept upright (rotation-aware placement; `graphicsLayer` rotation is forbidden on
interactive chrome). There is no landscape information architecture.

**B′2. Anchor classes.** The bottom cluster (config strip · Library/FAB/Settings nav · settings
sheet) is **device-anchored**: glued to the physical portrait-bottom edge — right for
`ROTATION_90` (sense A, rail order reversed), left for `ROTATION_270` (sense B, identity order),
per the pure `LandscapeRotation` mapping (`landscapeSense` / `clusterEdge` / `railOrder`,
JVM-pinned). The status chip and flash/lens controls are **screen-anchored**: top-start /
top-end in BOTH senses, exactly like the native camera's indicators. Nav stays edge-most,
config inboard — portrait's depth order, rotated.

**B′3. Density, not relocation (the §11 weight amendment).** The landscape config column renders
the same 5 cells (Clip · Repeats · Wait · Quality · Mode) at **compact density**
(`cellValueCompact`/`cellKeyCompact`, `landscapeCellGap`); the settings side panel is the
portrait sheet's **silhouette** (`sideSheetWidth` 380dp cap, full height, slimmed landscape Save
via `ctaPaddingVCompact`, body scroll) sliding from the cluster edge with portrait scrim parity.
A top-center config dock / three-zone landscape composition was explicitly rejected.

**B′4. Correctness invariant.** `VideoCapture.targetRotation` keeps being driven by the PR-α
`effectiveTargetRotation` + `OrientationEventListener` seam — chrome placement must never
acquire responsibility for recorded-MP4 orientation. Device acceptance: rotate both senses;
nav/cluster handedness matches the stock camera; recorded MP4 orientation correct in both.

## §B″ — Fixed window + counter-rotating elements (compact, 2026-06-12)

Authoritative spec: `docs/superpowers/specs/2026-06-12-rotation-first-chrome-fixed-window-design.md`
(research basis committed `953a2c4`). Native references: stock camera + One UI camera (RZCYA1VBQ2H);
iPad Camera for the sw600dp+ branch.

**B″1. Principle.** Compact (<sw600dp) record-home runs `ChromeMode.FixedPhysical`: the
window is orientation-locked portrait and the layout is frozen; device rotation only
counter-rotates designated element **contents** in place (`ChromeSpin.kt` math:
`uiCounterRotationDegrees` + `shortestPathDelta` on one unwrapped `Animatable`, 180 ms
tween; `ReducedMotion` snaps).

**B″2. Lock policy — single writer.** `RecordChromeLockPolicy.shouldLock(route ∧ ¬modal ∧
FixedPhysical)` is the ONLY lock decision point; the single `requestedOrientation` writer
on the UI side is RecordScreen's unified `DisposableEffect` (lock when the predicate
holds; Adaptive/modal → pre-ε per-state behavior; restore on dispose). Gate:
`checkRecordChromeLockSingleSite` (36th).

**B″3. I-style strip (owner-ratified).** One pill, one footprint; uniform square
cell slots; frozen order — `landscapeSense`/`railOrder` are NOT used on compact (sense
only picks spin direction). Cell/nav/FAB/camera glyph contents spin as the Cell class;
over-slot labels (SWIPE caption, nav labels, LoopSegmentBar) fade out when spun
(`uprightFadeAlpha`).

> **Amendment 2026-06-13 (owner refinement — slimmer + responsive strip/panel).**
> The cell slot baseline is **44dp** (was 48dp) and the card vertical padding **6dp**
> (was 7dp), for a slimmer strip / more unobstructed preview. The 48dp **touch-target
> floor** is unaffected — it is held separately by the card's `heightIn(min = 48.dp)`,
> not by the slot — and the rotation-invariant bound (content ≤ slot/√2) holds at any
> square size (≈31.1dp at 44dp). Both the slot and the `FloatingSettingsPanel` side cap
> (`PanelMaxSide`, baseline 320dp) are now multiplied by `ChromeScale.factor(...)`, a
> device-anchored scale keyed on `smallestScreenWidthDp` (== 1.0 on the 411dp reference
> device, so geometry is byte-identical there; clamped 0.88–1.15 so narrower phones
> shrink and tablets/foldables grow proportionally). `smallestScreenWidthDp` is
> orientation-invariant, so the chrome does NOT resize mid-spin.

**B″4. Info pills spin.** Status chip, HUD timer/clip, and recovery chip spin WITH the
device — the timer stays readable mid-REC. Deliberate divergence from One UI's frozen
sideways timer, justified by Follow-Device per-clip re-orientation.

**B″5. Floating settings panel — window NEVER unlocks on compact (owner-ratified
2026-06-12; supersedes the original unlock-while-open clause A).** On FixedPhysical the
settings surface is `FloatingSettingsPanel`: a floating near-square centered card (V1
content × V3 geometry, mockup `floating_panel_mockup.html`, ratified 2026-06-12) — V1
full-label stepper rows, no scrim, "Presets" collapsed; V3 near-square rotation-invariant
footprint. The panel is **chrome-class**: it counter-rotates as ONE unit via `SpinningBox`
like every other chrome element, so no modal feeds the lock anymore — the lock predicate
on compact is `route ∧ FixedPhysical`, and the window stays locked portrait, always.
Tap-outside / ✕ / back dismiss with the sheet's save-on-dismiss semantics; the same
ViewModel plumbing and row composables are shared with the sheet. The bottom-sheet /
side-panel presentations survive on the **Adaptive branch only** (sw600dp+, where the
lock never engages). Known deviation, follow-up: the thermal-tips ModalBottomSheet (and
the warning sheet, whose visibility is not hoisted out of WarningCenter) renders portrait
under the permanent lock.

**B″6. §B′ demoted to the Adaptive fallback (sw600dp+), which is MANDATORY.** API 36
ignores orientation-lock APIs on sw600dp+; API 37 (Rova targetSdk) removes the opt-out,
with no camera exemption. iPad Camera makes the same choice. Slot placement, axis-flip,
`railOrder`, and the side panel live on in that branch only.

**B″7. DualShot lock subsumed.** The DualShot-only portrait lock is subsumed by the
unified effect; the Adaptive branch preserves it.

**B″8. Capture side untouched.** The PR-α `effectiveTargetRotation` seam and the existing
service gates are unaffected — chrome counter-rotation never acquires responsibility for
recorded-MP4 orientation (B′4 carries over verbatim).

## Enforcement

Following the standing invariant→`check*`→`preBuild` convention. Logic lives in
pure, JVM-testable helpers (house pure-helper pattern); the framework-touching
wrappers stay thin seams.

- **Pure helpers (built with the feature):**
  - `OrientationSnap` — sensor degrees → `ROTATION_0/90/180/270` with asymmetric
    hysteresis and `ORIENTATION_UNKNOWN` ignore (reuses the `ThermalHysteresis`
    pattern, ADR-0019). **[Ratified A]** Also owns the deterministic **first-sample
    fallback** (last-effective → snapped-display → portrait) for boundaries with no
    stable sample, recording which source fired.
  - `OrientationPolicyResolver` — `(policy, snapped device rotation)` →
    `effectiveTargetRotation`. **[Ratified D]** `Auto` passes the snapped rotation
    through; a lock resolves to the **explicit `Surface.ROTATION_*` captured at
    lock time** (four-rotation model), NOT a symbolic Portrait/Landscape token
    mapped to a hardcoded constant — so adding reverse/180/270 later is additive
    with no persisted-prefs migration.
  - a pure **mode-migration mapper** — legacy `mode` string → `(captureTopology,
    orientationPolicy, legacyMigrated)` per §6.

- **Built (PR-γ, 2026-06-11)** — the three candidate gates sketched here shipped,
  plus a fourth for the §C vocabulary clause:
  - `checkNoLegacyModeStrings` — forbids the old orientation-carrying `mode`
    strings (`"Portrait"`/`"Landscape"`/`"PortraitLandscape"`) in live capture
    paths (legacy read-compat allowlist: `SessionManifest.kt`, `ModeMigration.kt`,
    `RovaSettings.kt`; comment/KDoc lines skipped — documenting a legacy value is
    legal, branching on one is not);
  - `checkSetTargetRotationBoundaryOnly` — `setTargetRotation` reachable only
    from `RovaRecordingService.kt` / `service/dualrecord/` / `service/singlerecord/` (§3);
    `service/singlerecord/SingleVideoRecorder` owns the single-mode `VideoCapture` use case
    and its build-time `setTargetRotation`, symmetric with `service/dualrecord/` for the dual
    pipeline (2026-06-30).
  - `checkFrontBackCapabilityGated` — `FrontBack` referenced only by
    `CaptureTopology.kt` / `CaptureModes.kt` (the capability-gate site, §5);
    PR-δ extends the allowlist with the concurrent-camera module it builds;
  - `checkUserCopyVocabulary` — enforces §C below.

- **Built (PR-ε, 2026-06-12)** — one gate for the §B″ single-writer clause:
  - `checkRecordChromeLockSingleSite` — `requestedOrientation` touched on the UI
    side only by `RecordScreen.kt` (the unified lock `DisposableEffect`, §B″2);
    block/KDoc and line comments stripped before matching so prose mentions
    (e.g. `DualShotPortraitGate.kt`) stay legal. 36th `check*` gate.

- **Existing gates preserved**, notably `checkPresetNoOrientation` (ADR-0026):
  presets stay orientation-free; `OrientationPolicy` is a capture axis, never a
  preset field.

## §C — User-copy vocabulary (2026-06-11, owner-ratified; spec 2026-06-11-capture-mode-orientation-ux-design.md)

User-facing copy speaks exactly two nouns: **session** (one Start→Stop run) and
**clip** (one video within it). "Loop", "repeat", "segment" are banned from
string VALUES in `values*/strings.xml` (internal code vocabulary unaffected —
`loopCount`, `Segment*` classes, segment file naming, and the gates that pin
them are exempt by construction). Mode names are capture strategies
(Auto / DualShot / DualSight), never orientations; the orientation policy
displays "Follow Device", never "Auto". Enforced by `checkUserCopyVocabulary`.

## Consequences

- **Quick immediate win:** **α** ships device-driven `Auto` and fixes the
  disorientation without touching the `mode` enum at all — orientation simply
  stops being frozen at bind time.
- **Multi-PR rollout** (α→β→γ→δ): each PR is independently shippable; the enum
  collapse and manifest bump are deferred to **γ** so the early win carries no
  migration risk.
- **`FrontBack` unlocks a premium concurrent-dual-camera mode** on capable
  devices while degrading gracefully (hidden) on entry hardware — no false
  promise on devices that cannot do it.
- The **manifest carries richer per-clip orientation truth**: recovery and export
  read `effectiveTargetRotation` per segment, so an `Auto` session whose rotation
  changed across a boundary still merges and exports each clip in its own correct
  orientation.
- Two clean axes make future locks (reverse-portrait, 180°, advanced
  0/90/180/270) additive on the `OrientationPolicy` axis, with no further churn on
  topology.
