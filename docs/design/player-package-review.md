# Player Redesign — Package Reconciliation & Design Stewardship Report

**Scope:** the nine frozen/candidate Player HTML specifications under `docs/design/player-*.html`.
**Type:** architectural reconciliation — NOT a design specification. It adds no design, moves no
pixel, and reopens no frozen decision. It answers one question: *is the Player design one coherent
system, ready for Compose transcription, and in what order?*
**Method:** every claim below is grounded in the nine specs (section/line) and, where a spec cites
source, in the repository. Two independent full-read audits of all nine specs fed this reconciliation;
findings were then cross-checked against `player-core.html` directly. No spec was modified.
**Authored:** 2026-07-13. Read-only apart from this file.

---

> **Stewardship addendum — as-shipped (2026-07-14, PR #190 → merge `c2e14f94`).** The Player redesign was
> transcribed to Compose and merged to master. **Phases 1–4 + the accessibility contract shipped in full.**
> Two intentional deferrals hold exactly as §6 requires: **player-editing** produced **no** implementation
> (dedicated editing ADR still gates it) and the **in-player DualShot angle switch** was **not** built (the
> passive indicator shipped; RK3/ADR-0037 amendment still gates the switch).
>
> **Reconciled contradiction (surfaced per HTML-first rule, HTML unchanged):** the §1 status table and §6
> inventory mark **sharing, info, swipe-down-dismiss, and the boundary haptic** as *PROPOSED / owner-sign-off-
> gated / "not present today."* All four **shipped** — the owner's completion directive is that sign-off, so
> gating them off would reopen a completed milestone. Their design-time labels above are left **as authored**
> (this report and the frozen `player-*.html` specs are historical/canonical design records); the authoritative
> **as-shipped** status lives in the living docs (`CLAUDE.md` → "Player Redesign V1", `docs/BACKLOG.md`
> 2026-07-14 update, `memory/project_player_redesign_v1.md`). §6's contrast pin (built), title grouping (built),
> state announcer (built), sheet focus trap (built via `ModalBottomSheet`), and reduce-transparency wiring
> (built) are all delivered; the top-band 96dp→min-height floor remains the one owed refinement, correctly
> routed through a future player-core HTML re-approval (C5). One Required Fix from independent review was
> reconciled at merge — the dead `PlayerActionFramework` (its "no Share, no Info … declined this milestone"
> docstring was falsified by the shipped Info/Share; zero consumers) was deleted, not fixed in place.

---

## 1. Package overview

The Player redesign is a **nine-document design system** built HTML-first, foundation-first. One shell
spec establishes the token model, layout, and interaction philosophy; every other spec *layers on it*
and explicitly disclaims what it does not own. The set is complete: there is no tenth Player concern
left unassigned (fit/fill is the one named-but-unwritten future spec, deliberately deferred, see §6).

| # | Spec | Owns | Status (per §4 C6 — cross-declared, not self-declared) | Needs new ADR? |
|---|------|------|------------------------|----------------|
| 1 | `player-core` | Token model · 3-zone layout · chrome/immersive policy · control+timeline placement · motion vocabulary · interaction philosophy | **FROZEN** | No |
| 2 | `player-states` | Loading · first-frame handoff · resume · every Unavailable cause + Retry · runtime Ready→Unavailable flip · state announcements | **FROZEN CANDIDATE** | No |
| 3 | `player-gestures` | Every touch→action map · conflict-priority matrix · auto-hide interaction contract · gesture a11y equivalents | **FROZEN** | No |
| 4 | `player-actions` | Action-slot framework · primary rule · secondary ceiling (Info+Share) · overflow philosophy · destructive-action policy | **FROZEN** | No |
| 5 | `player-sharing` | The share flow *after* Share is pressed · artifact resolution · DualShot side sheet · failure/feedback · grant security | **PROPOSED** (gates with the Share slot) | No |
| 6 | `player-info` | Info-sheet contents · field inventory + provenance · DualShot "describe both" · unavailable-field rule | **PROPOSED** (gates with the Info slot) | No |
| 7 | `player-editing` | Trim-to-copy experience · non-destructive artifact model · execution classification · DualShot single-side rule | **PROPOSED / Future** | **Yes — dedicated editing ADR** |
| 8 | `player-dualshot` | Angle identity display · angle-switch interaction · RK3 minter resolution · per-side continuity | **PROPOSED** (indicator ships now; switch gated) | **Yes — RK3/ADR-0037 amendment** (switch only) |
| 9 | `player-accessibility` | The a11y *contract* unifying all eight: tree · focus order · live-region ownership · large-text · motion · contrast · captions | **FINAL** (contract, not a build) | No |

**Backbone preserved by every spec:** ADR-0037 (identity: Library sole minter, transported not
reconstructed, resolver fails closed), ADR-0038 (lifecycle: fresh player per lease, `ownedPlayer()`
lease-gate), ADR-0032 (wall-clock playhead), ADR-0025 (vault: app-private, FLAG_SECURE), ADR-0020
(WCAG 2.2 AA by default), ADR-0036 (delete-then-discard — cited by actions/editing to *reject*
in-player deletion). Media3 is pinned 1.4.1 with **no MediaSession** — stated as a hard envelope in
core, gestures, actions, sharing, editing, and accessibility.

---

## 2. Spec dependency graph

Layering is a strict, acyclic topological order. Each spec names the exact set it "LAYERS ON":

```
                        player-core  (FROZEN — the shell)
                             │
          ┌──────────────────┼──────────────────┐
          ▼                  ▼                  ▼
   player-states      player-gestures     player-actions
   (FROZEN CAND.)        (FROZEN)            (FROZEN)
          │                  │                  │
          │                  │      ┌───────────┼───────────┬───────────┐
          │                  │      ▼           ▼           ▼           ▼
          │                  │  player-      player-     player-     player-
          │                  │  sharing       info       editing     dualshot
          │                  │  (PROP.)      (PROP.)     (PROP./Fut) (PROP.)
          │                  │      │           │           │           │
          └──────────────────┴──────┴───────────┴───────────┴───────────┘
                             │
                             ▼
              player-accessibility  (layers on ALL EIGHT — the contract)
```

- **core** is the root; it defers everything downstream via its §10 frozen-boundary table.
- **states / gestures / actions** each layer on core only. They are siblings — no ordering
  dependency *between* them beyond core.
- **sharing / info / editing / dualshot** each layer on core + states + gestures + actions
  (+ each other cumulatively in the order sharing→info→editing→dualshot for the "same sheet idiom"
  reuse). That reuse is not a *runtime* coupling, but it **is** a transcription-order constraint — the
  spec that defines the sheet idiom transcribes before the one that inherits it (info §00/§09 LAYERS ON
  sharing), which §7 honors. Each plugs into the **action slot framework** that `player-actions` froze.
- **accessibility** is terminal: it reads all eight and asserts the invariant they instantiate. It
  builds nothing on its own; its owed items ride the owning surface's transcription.

No cycles. No spec depends on one that depends on it.

---

## 3. Responsibility matrix

Each concern has exactly one owner. Cross-checked: no concern is owned twice, and every spec
explicitly disclaims its neighbors' facets.

| Concern | Owner | Everyone else |
|---------|-------|---------------|
| Token families (F1 ink / F2 scrim+geo / F3 motion+spacing) | **core** §02 | inherit verbatim; "NO NEW … tokens" declared in states*, gestures*, actions, sharing, info, editing, dualshot, accessibility (*add scoped tokens, see §4) |
| 3-zone layout, immersive/auto-hide *policy*, control+timeline *placement* | **core** §03–§05 | states/gestures/actions "reuse verbatim; never redraw" |
| Loading · resume · Unavailable causes · Retry · runtime flip | **states** | core defers (§10); accessibility defers announcements to states §08 |
| Touch→action mapping · conflict matrix · auto-hide *interaction* | **gestures** | core froze placement + the 3 s timer *value*; gestures owns the interaction over it |
| Action slot framework · primary rule · secondary ceiling · overflow · destructive policy | **actions** | sharing/info/editing/dualshot each "fill a slot", never move it |
| Share flow after Share pressed · side sheet · grant model | **sharing** | actions owns the Share *slot*; sharing owns the *flow* |
| Info sheet contents · provenance · unavailable-field rule | **info** | actions owns the Info *slot*; info owns the *contents* |
| Trim-to-copy · artifact model · execution routes | **editing** | actions did NOT make edit a permanent action; editing owns the future experience |
| Angle identity + switch · RK3 resolution · per-side continuity | **dualshot** | states owns the stream states a switched-to side enters; sharing/info/editing own their own DualShot facet |
| DualShot facet split (no overlap) | which file to **share** → sharing · **describe** both → info · **trim** one side → editing · **switch** angle → dualshot | each disclaims the other three explicitly |
| A11y tree · focus order · live-region ownership · large-text · contrast · captions | **accessibility** | each surface authors its *own* announcements; accessibility sets the invariant |

**Verdict: clean.** The DualShot four-way facet split — the most likely place to find duplicated
ownership — is explicitly partitioned in all four owning specs. "Both sides / composite / PiP" is
consistently **Rejected** (not deferred) in sharing §04, info §06, editing §03/§07, dualshot §02/§04.

---

## 4. Cross-spec consistency audit

I actively attempted to falsify token consistency, ownership, gesture/action/state/lifecycle
guidance, and ADR handling across all nine. Findings, all grounded:

### C1 — Token set is byte-identical; scoped additions are additive, not conflicting ✅
FAMILY 1/2/3 values are verbatim-identical in all nine specs (e.g. `--media-ink:rgba(255,255,255,.94)`,
`--pin-surface:#0B0D14`, `--ease-std:cubic-bezier(.2,.8,.2,1)`, `--target:48px`). Two specs add
**scoped** tokens, and both are explicitly new-and-owned, so there is no collision:
- `player-states` adds state tokens: `--loading-grace:400ms`, `--spinner-period:900ms`,
  `--resume-cue-dwell:4000ms`, `--err-card-w:280px`, `--err-card-pad:22px`, `--err-glyph:44px`.
- `player-gestures` adds interaction-timing tokens: `--dbl-tap-window:300ms`, `--touch-slop:~8dp`
  (framework-owned), `--dismiss-commit:120dp` (PROPOSED), `--dismiss-velocity:800dp/s` (PROPOSED),
  `--haptic-tick:CLOCK_TICK` (PROPOSED).

These are legitimate per-spec extensions of the model core owns; core §02 anticipates this ("An action
that needs a NEW token needs a design change in player-core first" — actions restates the rule). **No
contradiction.**

### C2 — `--auto-hide:3000ms` registry drift (cosmetic) ⚠️ Observation
`--auto-hide:3000ms` is declared in core, states, gestures, actions, and sharing `:root`, but **omitted**
from info, editing, dualshot, and accessibility `:root` (four specs). None of the four consume auto-hide, so it's harmless — a
registry-completeness nit, not a behavioral divergence. The auto-hide *value* (3 s) and its status
(frozen, "MUST NOT redesign" per gestures §09) are consistent everywhere it appears. **No fix required;
recorded for the transcription so no one reads the omission as a different timer.**

### C3 — Contrast proof: one obligation described by three specs ⚠️ needs single owner named
core §07 **requires** a *new scrim-composite worst-case contrast pin* (dimmed inks `--media-ink-dim` .48
and `--media-ink-body` .55 over the band-composite over bright footage), modelled on
`TokenContrastTest.mediaProgress`, explicitly **not** the opaque-surface Trust sweep. `player-states`
notes and `player-accessibility` §09 both restate the same gap and the same "don't reuse the Trust
sweep" rule. Grounded against source: `OverlayContrastTest.kt` already asserts the player subtitle
(0.72) and play-fill (0.35) but only over a **fixed** dark ref `#0E1216` — it does not model the
worst-case bright-footage composite. So three specs correctly point at **one** real, still-open
obligation. This is **description duplication, not conflict** — but the package must name a single
owner so it's built once. **Reconciled position:** `player-core` §07 is the normative owner of the
scrim-composite pin (it is the shell's contrast guarantee); states and accessibility *reference* it.
It is delivered during the core transcription.

### C4 — `±10s` vs `5s` seek increment: unresolved owner question, correctly parked ✅
gestures §03 flags that Google Photos uses a 5 s hop / length-gated disable, explicitly marks it an
**owner question**, and does **not** change `SEEK_DELTA_MS = 10_000L`. core §05 states the increment
reconciliation is "deferred to player-gestures"; gestures owns it and left it frozen at ±10s. This is a
correctly-deferred open question, not an inconsistency. Both the ±10s buttons and the double-tap seek
use the same constant — no split.

### C5 — Top band fixed 96dp vs large-text min-height: a real tension, routed correctly ⚠️
core §08 freezes the top scrim band at a **fixed** 96dp (`--scrim-top-h:96px`, source
`PlayerScreen.kt:408` is `.height(96.dp)`). accessibility §07 flags that a *fixed* height is a
large-text clipping risk and marks converting it to a **min-height floor** as OWED work. accessibility
cannot redesign core (its own boundary), so it correctly records this as owed rather than overriding.
**Reconciled position:** this is a genuine future refinement that, if pursued, **routes back through
`player-core.html` first** (HTML-first re-approval), then transcribes. It is not a blocker and not a
contradiction — it is the process working as designed. Logged in the deferred inventory (§6).

### C6 — Status is *cross-declared*, not self-declared; the freeze labels drift ⚠️ Observation
Grounded correction (surfaced by the review): **no spec declares its own freeze status.** Where a spec
speaks to its *own* state it is explicitly **pre-freeze** — core §09 and states §09 both read
"Non-binding until freeze; captures the transcription intent," and core §10 reads "Next in the pipeline
— critique → freeze → … pending owner go-ahead." The FROZEN / FROZEN-CANDIDATE labels come entirely
from *downstream* specs' "LAYERS ON" citations (sharing calls its predecessors "four frozen specs", info
"five", editing "six", dualshot "seven", accessibility "eight" — cumulative and internally consistent).
So the statuses are **cross-declared by later specs**, and the labels drift: `player-states` is
"FROZEN CANDIDATE" downstream, while sharing/info are cited "FROZEN CANDIDATE" by later specs though
their own headers say **PROPOSED**. This is documentation hygiene, not a design conflict. **Reconciled
canonical status of record is the §1 table** (core/gestures/actions treated FROZEN; states FROZEN
CANDIDATE; sharing/info/editing/dualshot PROPOSED; accessibility FINAL-contract) — but note the specs
themselves are, by their own text, **pre-freeze pending owner go-ahead** (see §10: freezing them is the
first outstanding item). No spec edit required.

### C7 — Gesture / action / state / lifecycle guidance: no conflicts ✅
- **Gestures**: only gestures defines touch→action; every other spec inherits and none re-specifies a
  conflicting mapping. The **transport-cluster focus order** is consistent where it appears —
  `Back → timeline → −10 → Play/Pause → +10 → speed` in actions §10 and accessibility §03. Two grounded
  nuances (not conflicts): the trailing `Info → Share` tail is **unconditional** in actions §10 but
  **conditional** ("(Info → Share *when present*)") in accessibility §03 — correct, since actions owns
  the slots and accessibility respects their PROPOSED/context-visible status; and `player-gestures`
  carries **per-gesture a11y equivalents** (§10–§11), *not* a full ordered traversal string — it layers
  on core + states only, so the actions-owned Info/Share slots are outside its vocabulary by design. The
  cluster order itself never conflicts.
- **Actions**: the "primary = transport cluster, no hero CTA" rule holds across core §05 and actions
  §02; no later spec elevates Share/Save/Export to a hero (sharing/editing both confirm).
- **States**: the sealed `PlayerUiState { Loading, Ready, Unavailable(UiText) }` and the six string-keyed
  Unavailable causes are owned solely by states; accessibility's "state announcer" node defers to
  states §08 for the actual strings. Consistent.
- **Lifecycle**: every spec that touches lifecycle (gestures dismiss, sharing background, editing pause,
  dualshot re-lease) asserts ADR-0038 is **unchanged** — "same leased player, no new teardown path."
  No spec introduces a competing lifecycle owner.

### C8 — DualShot "both angles" semantics: consistent across three specs ✅
sharing sends two *separate* files (`ACTION_SEND_MULTIPLE`, "Both angles"), editing trims one side at a
time "never combining", dualshot shows "one on screen at a time." All three agree: Rova never produces
a composited/stitched artifact. No conflict.

**Audit conclusion:** zero design contradictions. Two items need a named single-owner decision (C3
contrast pin → core; C6 status → this report's §1 table). Two are correctly-parked open questions (C4
seek increment, C5 min-height — both route through core HTML if pursued). One cosmetic registry nit
(C2). Nothing blocks transcription.

---

## 5. Architectural integrity review

| Invariant | Held across the package? | Evidence |
|-----------|--------------------------|----------|
| **ADR-0037** identity — Library sole minter, transported not reconstructed, fails closed | ✅ | Every spec cites it read-only. dualshot §03 *settles RK3* by making the angle switch a **selection over a Library-minted transported pair**, explicitly rejecting in-player synthesis as "the exact thing ADR-0037:143-158 forbids." The one architectural change the package proposes (in-player switch) is framed as a *transport widening*, gated behind an ADR amendment. |
| **ADR-0038** lifecycle — fresh player per lease, `ownedPlayer()` gate | ✅ | gestures/sharing/editing/dualshot all assert "ADR-0038 unchanged." dualshot's switch uses "a fresh side-keyed lease" (lease key already varies by side). No new lifecycle owner anywhere. |
| **ADR-0032** wall-clock playhead | ✅ | Read-only in all; dualshot continuity uses `segmentWallStartsMs`/`startedAtWallClock`. |
| **ADR-0025** vault, FLAG_SECURE | ✅ | sharing never shares vaulted; info renders under FLAG_SECURE; accessibility honors it. |
| **No new backend / schema / MediaSession / Media3 change** | ✅ *except editing (gated)* | sharing/info/dualshot are pure transport/read reuse. **editing** is the sole exception: Route A needs a new session-free publish write path; Route B needs the deliberately-excluded `media3-transformer` — both classified **Future**, gated behind a dedicated editing ADR, "not implementable from this document alone." Everything else is transcription. |
| **NO autoplay** trust rule | ✅ | Stated in core, restated in states/actions. |
| **Token model single source** | ✅ | core owns F1/F2/F3; scoped additions (states, gestures) are additive and self-owned (§4 C1). |

**Two — and only two — specs require an architecture decision before their code can land:**
1. **`player-editing`** — a dedicated *editing engine + affordance* ADR (and possibly the transformer
   dependency). It is Future by its own declaration.
2. **`player-dualshot` in-player switch** — an RK3 / ADR-0037 amendment for Library pair-transport.
   Note the **angle indicator + Library-bounce interim ships today** without any ADR; only the
   one-tap in-player switch is gated.

Everything else in the package is a **pure Compose transcription** of a frozen HTML spec with no
backend, schema, or ADR change — exactly the low-risk profile the HTML-first pipeline is designed to
produce.

---

## 6. Deferred work inventory

Intentional deferrals, each with its gate. None is an accidental gap.

| Deferred item | Owner spec | Gate before it can land |
|---------------|-----------|-------------------------|
| Scrim-composite bright-footage contrast pin | core §07 | Built during core transcription (new pure JVM test, no gate) — the one *required* new test. |
| Top band 96dp → min-height floor (large-text) | core §08 / accessibility §07 | HTML-first re-approval of core, then transcribe. Refinement, not blocker. |
| State announcer live region (Loading/Ready polite, Unavailable assertive) | states §08 (semantics), accessibility §05 (contract) | Rides states transcription. |
| Title + sub-title grouped into one semantic node | core §07 / accessibility §02 | Rides core transcription (`mergeDescendants`). |
| Sheet focus trap-and-return | accessibility §03 | Rides each sheet's transcription (info/sharing/editing). |
| Player reduce-transparency wiring (`pinContainerAlphaFor`) | core §04 / accessibility §09 | Rides core transcription (seam already exists, unused by Player today). |
| Swipe-down-to-dismiss + boundary haptic | gestures §06/§07 (PROPOSED) | Owner sign-off; both marked PROPOSED, "not present today." |
| Share flow | sharing (PROPOSED) | Gates **with** the actions Share slot (one owner sign-off). |
| Info sheet | info (PROPOSED) | Gates **with** the actions Info slot (same sign-off). |
| Trim-to-copy | editing (PROPOSED/Future) | Dedicated editing ADR (+ possible transformer dep). |
| In-player angle switch | dualshot (PROPOSED) | RK3/ADR-0037 amendment. (Indicator ships now.) |
| Fit/fill toggle | *unwritten* `player-fit-fill.html` | Future spec; named in core §08 and actions §13, not yet authored. |
| Landscape / fullscreen | — | Rejected in V1 (core §08, backlog §5.4). |
| MediaSession / media-key / lock-screen transport | — | Out of scope everywhere (Media3 1.4.1). Future backend decision. |
| ASR captions / transcript | accessibility §10 | Future ADR; no caption machinery exists — the package correctly exposes **no dead CC control**. |

---

## 7. Compose transcription order

Recommended order, strictly risk-minimizing. The rule: **a spec transcribes only after every spec it
layers on is transcribed, pure-transcription work before ADR-gated work, and the highest-risk
capability last.**

**Phase 1 — Foundation (unblocks everything).**
1. **`player-core`.** The token migration (11 `Color.White.copy(alpha)` literals + `Color.Black`
   gradients → F1/F2 tokens, same pixels) + the scrim-composite contrast pin + wiring the shell to
   read the pinned `LocalGlassEnvironment` + deleting the dead 5-button docstrings. No ADR, no
   backend. **Must be first** — every other spec inherits its tokens/layout.

**Phase 2 — Core-only layers (any order; recommended states → gestures → actions).**
2. **`player-states`.** Adds 5 scoped tokens + 9 strings; the string-keyed classifier
   (`PlayerUnavailableAction.of`) and `PlayerUiState` already exist. Delivers the state announcer that
   accessibility depends on. Low risk.
3. **`player-gestures`.** Largely **already shipped** (double-tap PR #143, drag-scrub, auto-hide,
   speed-cycle all in source). Remaining work is small and mostly PROPOSED (swipe-down-dismiss,
   boundary haptic) — land the already-built mappings' token/semantic cleanup; gate the PROPOSED
   extras on sign-off. Low risk.
4. **`player-actions`.** The empty slot framework (primary cluster + leading/trailing secondary +
   empty overflow) can land as pure layout scaffolding. The two secondary actions (Info, Share) stay
   **PROPOSED** — the framework ships; the actions gate on owner sign-off. Low risk.

**Phase 3 — Accessibility verification (woven through, not standalone).**
5. **`player-accessibility`** is not a build step — it is the contract Phases 1–2 must satisfy. Its
   owed items (title grouping, state announcer, focus trap, reduce-transparency wiring, min-height)
   land inside the owning surface's transcription above. Treat it as the **acceptance checklist** for
   every Player PR and the final pixel/AA verification pass. No independent code.

**Phase 4 — Slot-fillers (gated on the Phase-2 actions sign-off; order sharing → info → dualshot-indicator).**
6. **`player-sharing`.** Reuses the existing `ShareUriResolver.safeShareUri` + `Intent.createChooser`;
   no backend. Adds the DualShot side sheet, and **defines the sheet idiom `player-info` reuses**.
   Depends on the actions Share slot. **Ordered before info** because `player-info` LAYERS ON
   `player-sharing` (info §00; "identical to the sharing side sheet", info §09) — the topological rule
   applies even though the two gate on the same sign-off and info is otherwise the lower-risk of the pair.
7. **`player-info`.** Lowest-risk *content* (a read-only manifest re-read `sessionStore.loadManifest`,
   no new schema, no writes), but it inherits sharing's sheet idiom, so it transcribes after sharing.
8. **`player-dualshot` (indicator only).** The angle indicator + Library-bounce interim needs **no
   ADR** — ship it here.

**Phase 5 — Architecture-gated (each behind its own ADR; separate tracks, last).**
9. **`player-dualshot` (in-player switch).** After the RK3 / ADR-0037 amendment lands.
10. **`player-editing`.** After the dedicated editing ADR (and, for frame-accurate trim, the
    transformer dependency decision). Highest risk — it is the only spec that introduces a new
    capability boundary. Strictly last, on its own track.

**Why this order minimizes risk:** it is the dependency graph's topological order (no spec transcribes
before its foundation), it front-loads the pure-transcription/no-ADR work (Phases 1–4) so the bulk of
the Player ships without any architecture decision, and it isolates the two ADR-gated capabilities
(editing engine, in-player switch) at the end where a delayed ADR blocks nothing already shipped.
Accessibility runs as a continuous gate rather than a deferred cleanup, so AA is proven per-PR, not
retrofitted.

---

## 8. Testing strategy

The package's own precedent (ADR-0036/0038: "protection = pure JVM tests, no new gate") governs. No
spec asks for a new static-check gate; all protection is pure JVM + device verification.

- **Token migration (core):** the migration is "same rendered pixels" — assert the F1/F2 token values
  equal the retired literals (a pure token-equality test), plus device screenshot parity against the
  frozen HTML.
- **Contrast (core §07):** the one **required new test** — a pure JVM scrim-composite pin asserting
  `--media-ink-dim` (.48) and `--media-ink-body` (.55) clear AA over the worst-case band-over-bright
  composite, modelled on `TokenContrastTest.mediaProgress`. Extends `OverlayContrastTest.kt`'s
  fixed-dark-ref model to the bright-footage worst case. **Not** the opaque Trust sweep.
- **States:** the classifier (`PlayerUnavailableAction.of`, unknown→DISMISS) and near-end reset
  (`resolveOpenPosition`) are already pure and testable; assert each of the six causes maps to the
  right {RETRY, DISMISS} + string. Device-verify the runtime Ready→Unavailable flip.
- **Gestures:** the pure seams exist and are tested (`EdgeSeekZones`, `SegmentedTimelineMath.snapIfNear`,
  `AutoHideChromePolicy.shouldRunHideTimer`, `PlaybackSpeedPolicy.next`). New PROPOSED gestures
  (swipe-dismiss commit distance/velocity) get pure threshold tests when they land.
- **Accessibility:** semantics are asserted via Compose UI tests already scaffolded; the contract's
  invariants (single live-region owner, assertive-only-for-failure, focus order, 48dp targets) are the
  per-PR acceptance checklist. Device TalkBack + fontScale 2.0 pass is the mandatory pre-merge gate for
  every Player surface.
- **Sharing/info/dualshot:** resolver + URI-map are pure; assert artifact-selection per recording kind
  and fail-closed on malformed identity. Device-verify the chooser, the side sheet, and per-side
  continuity.
- **Editing:** out until its ADR; when it lands, the artifact model (new file, manifest never touched)
  gets a pure "manifest unchanged after edit" assertion + Route-A remux tests.

Baseline discipline stays: a feature lands its tests in the same PR; the full suite needs
`--max-workers=2` on this box.

---

## 9. Risks

| # | Risk | Severity | Mitigation |
|---|------|----------|------------|
| R1 | **editing** pulls in `media3-transformer` — the deliberately-excluded dependency — if frame-accurate trim is chosen over keyframe remux. | High (scope/binary-size/maintenance) | Editing is gated behind its own ADR and classified Future; Route A (keyframe remux, no new dep) is the low-risk default. Do not start editing until the ADR picks a route. Isolated as Phase-5, last. |
| R2 | **in-player angle switch** widens ADR-0037's transport contract; a sloppy amendment could crack the sole-minter invariant. | Med-High | dualshot §03 already frames the switch as *selection over a Library-minted pair*, never synthesis. The amendment must keep the Library the sole minter; the switch only transports a pre-minted sibling. Indicator ships without it. |
| R3 | **Contrast pin never built**, and the token migration ships assuming AA it hasn't proven over bright footage. | Med | Named the single owner (core §07) and made the pin the one *required* new test in Phase 1. It gates the core PR. |
| R4 | **Accessibility owed items** (title grouping, state announcer, focus trap, reduce-transparency, min-height) are diffuse across surfaces and could each be quietly skipped. | Med | accessibility is the per-PR acceptance checklist, not a deferred cleanup phase. Each owed item is tied to its owning surface's transcription in §6, so it can't ship "done" without them. |
| R5 | **PROPOSED status** on sharing/info/editing/dualshot means owner sign-off is a hard prerequisite; starting them early wastes work if a slot is cut. | Low-Med | Phase-4/5 ordering gates every filler behind the actions slot sign-off (info/sharing) or an ADR (editing/switch). The framework (Phase 2) ships regardless. |
| R6 | **Status-label drift** (C6) or the `--auto-hide` registry omission (C2) misleads a future transcriber about what's frozen. | Low | This report's §1 table is the canonical status of record; C2 flagged so the omission isn't read as a different timer. No spec edit needed. |
| R7 | **fit/fill** is referenced (core §08, actions §13) but unwritten; a transcriber might assume Fit-only is permanent. | Low | core explicitly "assumes Fit"; fit/fill is a named future spec. Documented as deferred (§6), not a gap in the current nine. |

No risk in this table blocks the Phase 1–4 transcription of the frozen/candidate specs.

---

## 10. Frozen package contract

- **The Player design is complete and internally coherent.** Nine specs, one acyclic dependency graph,
  every concern owned exactly once, zero design contradictions. The two items needing a single-owner
  call (contrast pin, status labels) are resolved in this report (§4 C3 → core; §4 C6 → §1 table).
- **This document is the reconciliation of record.** It is not a design spec and reopens nothing. The
  nine HTML specs remain the canonical visual/interaction source of truth; Compose transcribes them and
  never diverges. Any post-freeze visual or interaction change routes back through the owning HTML spec
  first (HTML-first, non-negotiable).
- **Canonical status** is the §1 table: core/gestures/actions **FROZEN**; states **FROZEN CANDIDATE**;
  sharing/info/editing/dualshot **PROPOSED**; accessibility **FINAL contract**.
- **Two ADRs are owed before their code lands** — a dedicated editing ADR and an RK3/ADR-0037 amendment
  for the in-player angle switch. Nothing else in the package requires a backend, schema, MediaSession,
  or Media3 change.
- **Transcription order is §7.** Foundation (core) first; core-only layers next; accessibility woven
  through as the per-PR gate; slot-fillers after owner sign-off; the two ADR-gated capabilities last on
  their own tracks.
- **What remains before Compose transcription begins:** (1) owner sign-off to freeze the FROZEN-CANDIDATE
  and PROPOSED specs (or an explicit decision to transcribe core alone first); (2) the editing ADR and
  the RK3 amendment authored for Phase 5 (not needed for Phases 1–4); (3) nothing else — the package is
  transcription-ready from `player-core` down.

*Grounded in the nine `docs/design/player-*.html` specs (read in full) and, where they cite source, the
repository. Repository unchanged apart from this file.*
