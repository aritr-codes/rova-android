# ADR-0029: Capture topology and orientation policy — two orthogonal axes replace the `mode` string

Status: Proposed
Date: 2026-06-08

> **Amendments (2026-06-08, owner-ratified).** Four open questions were
> resolved with cross-model (codex) review and owner sign-off; the clauses below
> incorporate them: (A) default `OrientationPolicy`; (B) PR ordering; (C)
> `FrontBack` composition; (D) lock scope. The ADR stays **Proposed** — it is
> promoted to **Accepted** when PR-γ lands. Changes vs the original draft are
> flagged inline as **[Ratified A/B/C/D]**.

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

- **Candidate new `check*` gates (sketch — owner sign-off before building, in the
  spirit of ADR-0020's not-yet-built `checkA11y*` suite):**
  - a gate forbidding the old orientation-carrying `mode` strings
    (`"Portrait"`/`"Landscape"`/`"PortraitLandscape"`) in live capture paths once
    **γ** lands (legacy read-compat sites allowlisted);
  - a gate asserting rotation is applied **only at segment boundaries** — no
    `setTargetRotation` reachable from an active-recording code path (§3);
  - a gate asserting `FrontBack` construction is **capability-gated** — it cannot
    be instantiated without first consulting the concurrent-combo query (§5).

- **Existing gates preserved**, notably `checkPresetNoOrientation` (ADR-0026):
  presets stay orientation-free; `OrientationPolicy` is a capture axis, never a
  preset field.

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
