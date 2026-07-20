# The Rova Design Constitution

**Document class:** Constitution. Highest-precedence design document in the repository.
**Status:** Adopted.
**Date:** 2026-07-19.
**Supersedes:** the 590-line `rova-design-constitution.md` (Proposed, 2026-07-18), unadopted.
**Constrains:** the content of any document that later documents are obliged to cite.

Key words **MUST**, **MUST NOT**, **SHALL**, **SHALL NOT** are to be interpreted as in RFC 2119.

---

## 1. Why this document is short

A foundation document is cited rather than re-argued. Everything inside one becomes structure that later documents must inherit and may not question. Regret therefore scales with citation count, not with wrongness — which makes minimality an obligation, not a style.

This document is bound by its own Rule 1 below. It contains one rule, because one rule is what is both (a) true under every candidate design direction and (b) not already enforced elsewhere in this repository.

Everything else proposed for constitutional status was either already gated, already conventional, or an unenforced invariant awaiting a gate. Those dispositions are Section 5, and Section 5 is the operative part of this document.

---

## 2. The Constitutional Rule

**Rule 1 — Separation of system from direction.**

A foundation document SHALL NOT contain a design direction.

Directional content includes: the direction itself; any layout or composition grammar; any claim that a particular property carries identity; any register, voice, or ornament; any emotional-register preference; and any palette treatment argued as an aesthetic rather than as a provability constraint.

Whether two surfaces share a chrome grammar, and whether a surface's chrome persists or retreats, are **outputs** of those surfaces' own contracts. They SHALL NOT be inputs to a foundation document.

**Enforcement.** Rule 1 constrains prose, not code, and is not statically gateable. Its enforcement is one question, asked of every document at DESIGN FREEZE:

> *Does this document decide what anything looks like?*

This question is the retained remainder of the superseded document's Section 6 checklist — reduced from 68 items to one, not deleted. The distinction matters: the superseded Article XI.2 held that *an invariant whose enforcing gate is unnamed is not an invariant*, and that test is correct. Rule 1 survives it by naming a discharge mechanism that a reviewer can actually execute, rather than one that will be pencil-whipped.

---

## 3. Precedence

Constitution → ADR → design specification → implementation.

A lower layer contradicting a higher one is defective, not innovative. Where two documents in the same layer conflict, the conflict is unresolved until an amendment at that layer resolves it; the layer below SHALL NOT resolve it by choosing.

Authority descends. Evidence ascends: a runtime observation may motivate an amendment at any layer, but changes nothing until that layer's own change process has run.

---

## 4. What this document does not contain

The token authority and the status arbiter are **architecture, not constitution.** They are contracts binding named packages, and they belong in an ADR where they can carry gates.

> **PROPOSED ADR TEXT — NOT CONSTITUTIONAL, PENDING AUTHORING.**
> The following is a drafting brief for `docs/adr/0039-cross-surface-token-authority.md`. It is recorded here only so the reduction is legible. It carries no authority until authored as its own file, at which point this block is replaced by a one-line citation.
>
> - **§1** A single token registry is the sole source for radius, control height, spacing, ink alpha, scrim alpha, motion duration/easing, minimum type size, and over-media color.
> - **§2** The registry is seeded from the union of the existing frozen specifications, unchanged. Private values migrate to the nearest existing rung, or are recorded as new rungs with written justification at the point of minting.
> - **§3** No dimension, alpha, radius, duration, or color literal appears in the packages named by the enforcing gate. *Gate: `checkNoRawDimenRecorderPlayer`.*
> - **§4** Exactly one component computes status precedence. *Gate: single-site, of the shape of `checkScanTriggerSingleSite`.*
>
> **Unresolved before authoring — scope.** The superseded Constitution widened token authority from the Architecture Decision's two packages to *all surfaces*, recording the widening as "an act of this document." §3 above narrows back to two packages, following the gate. That reversal must be decided deliberately, not inherited from whichever text was written last.

This Constitution constrains only what an ADR or specification may *be about*. It never decides what one contains.

---

## 5. Dispositions

Recorded so that absence reads as decision rather than oversight. **No row demotes an invariant without naming the mechanism that will carry it** — the superseded document's Articles named their gates, and a disposition that drops the gate name would reproduce the exact decay it exists to repair.

### 5.1 Moved to ADR-0039

Token authority; registry seeding; no-literals; single status arbiter. Contracts with gates, not constitutional rules. See Section 4.

### 5.2 Unenforced invariants — backlog, each with its required gate

These are correct under every candidate direction and need no authority above them. They are **not** one-line fixes: each carries live violations that must be retired before its gate can go green, and each is listed here with the mechanism that will hold it.

| Invariant | Current enforcement | Required mechanism | Live violations |
|---|---|---|---|
| Minimum rendered text 10sp | **None** | `checkMinTextSize` (new) | **9** sub-floor `fontSize` declarations — `RovaTokens.kt` ships 7/7.5/8/8.5/9/9.5sp across 8 sites, plus `RecoveryCard.kt:391` |
| 48dp touch target | **Diverges** — see 5.3 | 24→48 floor **+** declaration→call-site scope **+** literal→alias widening | Unmeasured; requires the call-site sweep the existing gate explicitly defers |
| Non-color recording redundancy | Partial — `RecordFabVisualSpecTest` pins one composable | Spec checklist + a11y test, scoped beyond the FAB | Recording dot, notification, badges uncovered |
| Honest materials (no simulated blur) | **Wrong direction** — `checkRecordSurfaceNoBlur` / `checkGlassSurfaceRoleUsage` forbid *real* blur by regex; a faked blur contains no blur call and passes trivially | **New detector**, not a regex widening | `RecordChromeTokens.kt:18–36` (self-admitted in KDoc) |
| No shadows over media | **None** | `checkNoShadowOverMedia` (new) | 5 `.shadow(` sites across 4 files (`RovaDialogs`, `LibraryScrubber`, `SettingsSheet`, `FloatingGlassSheet`); over-media status unassessed — assess before drafting the gate |
| Tabular-lining figures | **None** | Font-feature check | Consistent practice, zero enforcement |
| Exactly one `recordingAccent` (AD **T5**) | **None** | `checkSingleRecordingAccent` | **The token does not exist** — `grep recordingAccent` returns 0. The fourth red (`RecordChromeTokens.kt:99–100`) is unaddressed; cardinality cannot be enforced before the token is minted |
| Bounded, swept over-media palette (AD **T7**) | Partial — 5 contrast test suites ship; `checkStatusColorLocked` forbids `.copy(` on `RovaSemantics` but bounds no palette | Extend the existing sweep to over-media chrome | Palette not enumerated |

### 5.3 One ADR conformance gap, not a backlog item

**ADR-0020 §Decision-1 normatively requires ≥48×48dp. The gate citing it enforces 24dp** (`RovaGateRules_A11yI18n.kt:293`, whose own failure message states *"Material 3's 48dp is a guideline; 24dp is the WCAG AA bar"*). That is a live clause-versus-enforcement divergence inside an ADR whose Status is still **Proposed**. It is resolved by amending ADR-0020 or its gate — deliberately, with the migration measured — not by asserting 48dp in a new document.

### 5.4 Already shipped or already gated

| Item | Status |
|---|---|
| Reduce-motion gating | `checkA11yAnimationGated` |
| Single colour-scheme source, locked status colour, icon tint, glass role | `checkSingleColorSchemeSource`, `checkStatusColorLocked`, `checkSemanticIconNoRawAlpha`, `checkGlassSurfaceRoleUsage` |
| One motion ladder | **Half.** `RovaMotion.kt` exists; nothing gates *consumption* of it, so T12's stated purpose — "so a new spec cannot mint a second ladder" — is unmet. Ladder authority belongs in ADR-0039 §1 |
| Gate-at-authoring; freeze-and-supersede; mechanism-vs-enforcement; ADR-amendment-before-code; inherited contracts not renegotiated by omission | **Repository convention**, already in `CLAUDE.md`, practised across 38 ADRs |

### 5.5 Deleted

| Item | Grounds |
|---|---|
| Evidence classification; falsifiable success criteria; rubric-before-candidates | Good epistemics, enforced by nothing. An authoring standard for `CLAUDE.md`, not a rule |
| Amendment process; layer-competency tables; inheritance diagram; 68-item checklist; provenance concordance | No measured repository failure motivates them. 38 ADRs have been authored, amended and superseded without them |
| "The product is named Rova" | A fact. Facts do not require Articles |

---

## 6. Corrections carried forward

Three claims in the source documents are falsified by repository state. They are recorded here because downstream documents cite them.

**C1 — The enforcement asymmetry is smaller than stated.** Architecture Decision **F18** — *"48 gates protect behavioral invariants; zero protect cross-surface visual token invariants"* — is false. At least seven gates enforce visual or token invariants, and `checkSingleColorSchemeSource` is precisely the single-source-of-colour topology the Decision declares absent. F18 supports **T1** and **T13**; both citations require restatement. The gap is real but narrower: the mechanism exists and needs extending — **except** for simulated-blur detection and the 48dp scope change, which are genuinely new mechanisms (5.2), not extensions.

**C2 — The single-arbiter rule conflicts with ratified practice.** `WarningCenterViewModel` computes precedence through two entry points (`WarningPrecedence.resolve`, `WarningPrecedence.allActive`) and exposes four warning-id-bearing outputs, though its single *public* `StateFlow<WarningId?>` is one. `effectiveIdleTopBannerId` (`WarningCenter.kt:282`, substituted at `:101`) overrides the arbiter's result at the render path. `CLAUDE.md:145` ratifies this as correct — *"lives in the routing layer, not precedence."* ADR-0039 §4 must scope itself to the arbiter layer explicitly, or declare the reversal and budget the migration, **before** its gate is written.

**C3 — Gate-at-authoring is not new.** Architecture Decision **T13** claims its second clause is new. `CLAUDE.md:41` already states it verbatim: *"Adding a new invariant means: ADR clause → new rule in `RovaGateRules` → …"*. It is existing convention, which strengthens rather than weakens the disposition in 5.4.

---

## 7. Amendment

Rule 1 is amended when it is shown to be false under a candidate direction it was asserted to be direction-independent of, with the falsifying evidence recorded and owner sign-off obtained.

Preference, inconvenience, and the difficulty of the rule are not grounds.

---

*Constitution. One rule. No layout, no composition, no direction, no screen. Everything else is an ADR, a gate, a convention, or a backlog item that names its gate.*
