# ADR-0039 — Cross-surface design token authority

- **Status:** Accepted (2026-07-20) — independent review returned GO WITH FIXES; all four Required Fixes reconciled
- **Date:** 2026-07-20
- **Deciders:** Rova owner (sign-off recorded 2026-07-20)
- **Supersedes:** nothing. **Amends:** nothing.
- **Constrains:** every future design specification, and every registry entry.
- **Derived from:** `docs/design/rova-design-constitution-reduced.md` (the Constitution), which is the sole governing authority above this ADR. Where this ADR and the Constitution conflict, the Constitution prevails and this ADR is defective (Constitution §3).

Key words **MUST**, **MUST NOT**, **SHALL**, **SHALL NOT**, and **MAY** are to be interpreted as in RFC 2119.

**Provenance.** This ADR carries no per-clause provenance markers. Every clause's single governing authority is recorded in Appendix A, in one of four classes: a Constitution clause, a named peer ADR, a named repository convention, or local origination by this ADR.

**Minimality.** This ADR is cited by every subsequent design specification. It therefore decides registry governance and the ownership of status-precedence computation, and nothing else. It contains no value, no rung, no ladder, no hue, no duration, no scale length, and no visual outcome. Where a reader expects a number, the absence is deliberate.

**Rule 1 discharge.** *Does this document decide what anything looks like?* **No.**

---

## 1. Context

Three independently-authored visual systems exist in this repository, and their token vocabularies do not intersect. Two are frozen (`docs/design/library-bento.html`, `docs/design/warnings-recovery.html`), a third set was frozen subsequently (`docs/design/player-*.html`), and the Recorder and Player packages carry values that intersect none of them. The divergence is recorded as measured fact in `docs/design/2026-07-18-recorder-player-architecture-decision.md` — unladdered radii, near-identical duplicate fills, control heights with no scale, multiple scrim alphas, parallel ink-alpha systems on one screen family, an unreferenced token, a fourth unrelated accent, and position choreographed by unnamed constants. That document is **evidence** for this ADR, not authority for it.

The cause is topological rather than a matter of craft. Each specification established its scale locally and no authority sat above them, so a further specification forks the system by construction, irrespective of its quality. Three consequences follow, and they determine this ADR's shape:

1. **The defect is not repairable by authoring a better specification.** A fourth well-designed specification that mints its own scale produces a fourth fork. The repair sits above specifications.
2. **The defect is not repairable by publishing values.** The divergence accumulated in a repository that already possessed token files. What was absent was a single source, not a vocabulary.
3. **The defect is not repairable by choosing a visual direction.** Nothing in this ADR depends on what any surface will look like.

**On enforcement.** Static gates in this repository do enforce visual and token invariants — colour-scheme topology, locked status hues, icon-tint routing, glyph locality, over-media material, and token size floors among them. What no gate asserts is that two different surfaces resolve the same property from the same scale, and no gate can assert it while no such scale exists. The gap is the absence of a shared source, not the absence of enforcement machinery. This paragraph reflects the correction recorded at Constitution §6, C1.

---

## 2. Scope

### 2.1 Subjects

**2.1.1** This ADR governs exactly two subjects, both assigned to it by Constitution §5.1: the design token registry and its governance, and the ownership of status-precedence computation.

**2.1.2** This ADR governs no other subject. Constitution §5.2 assigns each unenforced visual invariant to a named carrier, and this ADR takes ownership of none of them. The registry may hold canonical values that those mechanisms consume; it does not thereby acquire the invariants themselves.

### 2.2 Property scope

**2.2.1** The registry's property scope is: radius, control height, spacing, ink alpha, scrim alpha, motion duration, motion easing, minimum type size, and over-media colour.

**2.2.2** A value outside that list is not governed by this ADR.

**2.2.3** This ADR decides no value within that list, no number of values, and no relationship between values.

### 2.3 Surface scope

**2.3.1** The registry's surface scope is **all repository surfaces**. The registry is the sole source of canonical token values for every surface, not a repository of package-local values.

**2.3.2** Clause 2.3.1 is a decision originated by this ADR. It is not inherited from any predecessor document, and no predecessor's scope carries into it. Constitution §4 records that this question was left open for deliberate decision; this clause is that decision.

### 2.4 Gate scope

**2.4.1** Surface scope and gate scope are distinct and SHALL NOT be conflated. Clause 2.3.1 states where canonical values live. Section 5 and Appendix B state which packages are presently *enforced*.

**2.4.2** Registry scope takes effect on adoption. Enforcement scope expands only through explicitly declared gate scope, package by package, under section 5.

### 2.5 Non-scope

This ADR does not decide:

| Excluded | Where it is decided |
|---|---|
| Every rung value, ladder length, hue, duration, and inter-rung relationship | A subsequent ADR or specification, through the minting path of section 4 |
| Any design direction, layout, composition grammar, identity claim, register, voice, ornament, or palette treatment argued as an aesthetic | Nowhere in a foundation document (Constitution §2, Rule 1) |
| Whether two surfaces share a chrome grammar; whether a surface's chrome persists or retreats | Outputs of those surfaces' own contracts (Constitution §2, Rule 1) |
| The unenforced visual invariants and their required mechanisms | Constitution §5.2, at the carriers named there |
| The interactive-target conformance gap between a peer ADR's clause and its gate | Constitution §5.3, by amending that ADR or its gate |
| Document precedence between classes | Constitution §3 |
| Gate-at-authoring, freeze-and-supersede, mechanism-versus-enforcement, ADR-amendment-before-code | Repository convention, recorded in `CLAUDE.md` |
| Change control for this ADR itself | Repository ADR-amendment convention |
| Gate identifier naming and renaming | Implementation, under the repository gate convention |
| Registry file format, location, serialization, and package adoption order | Implementation, and Appendix B |
| Presentation of arbitrated status | 6.2.5 leaves it unconstrained |
| Any emergency or expedited path for registry change | Nowhere. Registry change under time pressure follows the ordinary process of this ADR and the repository's ADR-amendment convention |

---

## 3. Decision — registry authority and ownership

### 3.1 The registry

**3.1.1** A single artifact SHALL be the **canonical token registry**. It is the sole source for every property named in 2.2.1, across every surface named in 2.3.1.

**3.1.2** The canonical registry is a machine-readable artifact under version control, from which every consuming form is derived.

**3.1.3** Where more than one form of the registry exists, exactly one form SHALL be canonical and every other form SHALL be generated from it, never hand-authored in parallel. A hand-maintained second form is a second source, which 3.1.1 forbids.

**3.1.4** This ADR does not decide the registry's file format, location, or serialization, provided 3.1.2 and 3.1.3 hold.

### 3.2 Ownership

**3.2.1** The registry has exactly one **Registry Owner** role. The role is a repository responsibility, not a named individual, and survives any change of personnel.

**3.2.2** No specification, no surface package, and no implementation owns any token. Specifications and implementations are consumers only.

**3.2.3** The Registry Owner SHALL NOT use registry ownership to decide a visual outcome. Refusing an entry on a ground stated in 4.4.2 is registry governance; refusing it because of a preferred appearance is a directional decision inside an artifact every specification must cite, which Constitution §2 forbids.

**3.2.4** The Registry Owner SHALL NOT approve a registry change they requested. The roles in 4.4.4 are filled by distinct contexts.

### 3.3 Versioning

**3.3.1** The registry is versioned as a whole, not per token. A version identifies the exact set of canonical tokens, aliases, and values at a point in time.

**3.3.2** Every frozen specification SHALL record the registry version it was frozen against.

**3.3.3** A registry version change SHALL be classified as exactly one of:

| Class | Definition | Effect on frozen specifications |
|---|---|---|
| **Additive** | A canonical token is added; no existing canonical value changes; no alias retires. | None. |
| **Alias** | An alias is added, reclassified, or retired; no canonical value changes. | None, unless a retiring alias is cited by a frozen specification, in which case 3.3.5 applies. |
| **Breaking** | A canonical value changes, or a canonical token is removed. | Every frozen specification citing it is invalidated and MUST be superseded. |

**3.3.4** A **Breaking** change SHALL NOT be made for a specification's convenience. It ships together with the supersession of every specification that cited the changed value.

**3.3.5** A frozen specification SHALL NOT be edited to follow a registry change. Where a registry change invalidates it, the specification is superseded by a versioned successor that restates every carried-forward contract.

---

## 4. Decision — entry and retirement

### 4.1 Seeding

**4.1.1** The registry SHALL be seeded from the union of the existing frozen specifications, unchanged.

**4.1.2** Seeding is not minting. A seeded value enters as canonical without a mint record, because the frozen specification that already carries it is its justification. It SHALL NOT be re-derived, re-measured, or re-expressed on entry.

**4.1.3** A registry entry occupies exactly one lifecycle state at any time: **canonical**, **deprecated**, or **removed**. Seeding and minting produce canonical entries; deprecation under 4.5 produces deprecated entries; removal follows at zero consumers.

### 4.2 Aliases

**4.2.1** An **alias is a name, never a value.** Every alias resolves to exactly one canonical token and carries that token's value unchanged. An alias SHALL NOT introduce, adjust, or approximate a value.

**4.2.2** Alias creation is therefore not a mint. An alias that would require a new value is a mint, and 4.3 and 4.4 govern it entirely.

**4.2.3** An alias resolves directly to a canonical token, never to another alias.

**4.2.4** Every alias records, at creation: its class, its canonical target, its source, and — where its class requires one — its expiry condition.

**4.2.5** Three alias classes exist:

| Class | Purpose | Expires on a schedule | May be cited by a new specification |
|---|---|---|---|
| **Legacy** | A name a frozen specification already uses, imported so that specification's vocabulary remains citable verbatim. | No | No |
| **Migration** | A temporary name mapping an un-migrated local value onto the canonical entry that will replace it. | **Yes, mandatory** | No |
| **Deprecated** | An entry scheduled for removal, retained so existing consumers still resolve. | No | No |

**4.2.6** A legacy alias MAY be permanent. Its owning specification is frozen and may not be edited, so a name that specification uses cannot be withdrawn while it stands. A legacy alias retires only when its owning specification is superseded and the successor cites canonical names.

**4.2.7** A migration alias MUST record an expiry condition at creation, and SHALL NOT be permanent.

**4.2.8** A migration alias whose expiry has passed while it still has consumers SHALL fail the registry gate named in 8.2. Expiry is enforced, not advisory.

**4.2.9** Extending a migration alias's expiry requires the reviewers named in 4.4.4 and a recorded reason. Convenience is not a reason.

### 4.3 When a value may be minted

**4.3.1** A private or proposed value migrates to the nearest existing canonical entry. Migration is the default outcome; minting is the exception.

**4.3.2** A mint is permitted only where migration is not available.

**4.3.3** "No near entry exists" is a *finding*, not a justification. It states that migration failed; it does not state why the proposed value is correct. Both are required.

### 4.4 Minting

**4.4.1** No entry SHALL be minted without written justification recorded at the point of minting. The record MUST contain:

| # | Item |
|---|---|
| a | The property from 2.2.1 and the proposed canonical name |
| b | The consuming surface or surfaces, named |
| c | The nearest-entry analysis: every existing candidate considered, and the specific reason each fails |
| d | Why the proposed value is required, what its absence would cost, and what the mint forecloses |
| e | The migration statement required by 4.4.5 |
| f | The gate-scope statement required by 5.3.1, where the mint reaches a package not presently in scope |

**4.4.2** A mint request MUST be refused where any of the following holds:

| # | Refusal ground |
|---|---|
| a | An existing canonical entry is available and the request states no reason it fails |
| b | The stated reason is a preference for the proposed value's appearance |
| c | The mint would leave two scales coexisting with no stated migration |
| d | The proposed value's property lies outside 2.2.1 |
| e | The mint introduces an invariant whose enforcing gate is unnamed |

**4.4.3** Ground (b) is the specific instance of 3.2.3. Appearance is decided in a specification, never in this artifact.

**4.4.4** A mint requires four distinct roles, no two filled by the same context:

| Role | Obligation |
|---|---|
| **Requester** | Records 4.4.1(a)–(f). Never self-approves. |
| **Registry Owner** | Admits or refuses against 4.3 and 4.4. Never the Requester. |
| **Independent reviewer** | Fresh context, no shared draft state. Attempts to falsify the nearest-entry analysis. Classifies findings Required Fix / Recommended Improvement / Observation and returns exactly one verdict: GO / GO WITH FIXES / NO-GO. |
| **Owner sign-off** | Required for any change classified **Breaking** under 3.3.3. |

**4.4.5** A mint MUST state, for every existing value it renders redundant, either that the value migrates to the new entry with a stated timeline, or that no value is rendered redundant. A mint leaving two scales coexisting without a stated timeline SHALL be refused.

**4.4.6** Where a mint's migration cannot complete inside the requesting change, the un-migrated consumers are carried on migration aliases under 4.2.7. They are never carried silently.

**4.4.7** A gate SHALL NOT be weakened or rescoped to admit a mint. The mint is refused, or the owning clause is amended with recorded sign-off.

### 4.5 Deprecation and removal

**4.5.1** Deprecating a registry entry requires, recorded at deprecation: the reason; the canonical replacement, or an explicit statement that there is none; and the current consumer list.

**4.5.2** A deprecated entry SHALL NOT acquire a new consumer. A new citation of a deprecated name is a gate failure.

**4.5.3** A deprecated entry cited by a frozen specification is not removed until that specification is superseded. Deprecation does not authorize editing a frozen specification.

**4.5.4** Deprecation SHALL NOT be used to effect a value change. Changing a value is a **Breaking** change under 3.3.3 and follows 3.3.4.

**4.5.5** Removal of a deprecated entry whose consumer count is zero is implementation. It requires no amendment and confers no licence to alter any outcome.

---

## 5. Decision — literal prohibition and declared gate scope

### 5.1 The prohibition

**5.1.1** No dimension, alpha, radius, duration, or colour literal for a property named in 2.2.1 SHALL appear in a package named in the declared scope of the gate in 8.2. Every such value MUST resolve to a registry entry.

### 5.2 Declared scope

**5.2.1** The gate declares an explicit list of packages within its scope. Scope is a declared list, never an implicit "everywhere."

**5.2.2** Appendix B is the sole record of that list.

**5.2.3** Until a package appears in the declared list, 5.1.1 states a target for that package, not a present-tense prohibition, and its absence from the list is not a violation.

### 5.3 Expanding scope

**5.3.1** Expanding the declared scope to a further package REQUIRES, in the same change: that package's migration completed under 7.3, or its residue carried on migration aliases with recorded expiry; and the amended list recorded in Appendix B.

**5.3.2** Scope expansion is an amendment to Appendix B with owner sign-off. It is neither a unilateral act of the Registry Owner nor an implementation detail.

**5.3.3** Expanding scope changes only *which packages* a gate reads. It SHALL NOT change *what the gate enforces*. A change that does both is two changes and is admitted as two.

**5.3.4** An adopted package SHALL NOT regress. A gate whose declared scope includes a package is never narrowed to re-admit a literal.

### 5.4 Adoption

**5.4.1** A package is **adopted** when every value in it has resolved to an outcome under 7.3, every carried value holds an unexpired migration alias, the package appears in the declared scope, and the gate passes.

**5.4.2** Adoption of a package SHALL NOT be blocked on the adoption of any other package, on the selection of a design direction, or on the freezing of any specification.

**5.4.3** Package ordering is not decided by this ADR. It is a scheduling determination recorded in Appendix B.

---

## 6. Decision — single status arbiter

### 6.1 The rule

**6.1.1** Exactly one component SHALL compute status precedence.

**6.1.2** The uniqueness in 6.1.1 attaches to the computation, not to the number of queries over it. A component MAY expose more than one query over the precedence order it computes; multiple queries are not multiple arbiters.

**6.1.3** No surface SHALL derive its own precedence.

### 6.2 Scope of the rule

**6.2.1** Clauses 6.1.1 through 6.1.3 govern the **arbiter layer only** — the computation of precedence.

**6.2.2** A transformation applied to a precedence result *after* computation, in the routing layer, is outside the scope of this rule and is not a second arbiter. Constitution §6, C2 records that this repository holds such a transformation as ratified, reviewed practice, and this ADR governs the arbiter layer while leaving routing-layer behaviour to its existing authoritative documents.

**6.2.3** This ADR does not declare any shipped behaviour defective and budgets no migration. A future redesign that changes the arbiter, or eliminates the routing-layer pattern, is introduced by its own architecture decision, not by this ADR.

**6.2.4** The content of precedence — the identifiers, their order, and which of them gate any action — is not decided here. It is governed by `docs/WarningCenterContract.md`.

**6.2.5** Presentation of the arbitrated result is unconstrained by this ADR. Band, cluster, inline, notification, and any other presentation remain available.

---

## 7. Consumption and migration

### 7.1 How a specification consumes the registry

**7.1.1** A specification expresses every property named in 2.2.1 as a token name.

**7.1.2** A specification MAY restate a token's value alongside its name for readability. Where a restated value and the registry disagree, **the registry prevails and the specification is defective**, not authoritative.

**7.1.3** A specification cites canonical names. Legacy, migration, and deprecated aliases are not available to it.

**7.1.4** Where a specification requires a value the registry lacks, it does not proceed. The value enters the registry first, under section 4.

**7.1.5** A specification SHALL NOT: mint an entry; introduce a literal for a property named in 2.2.1; define a private scale, ladder, or set for such a property; adopt a "nearest visual equivalent" of a registry value without a registry entry; re-derive, re-measure, or re-express a seeded value; or introduce an invariant without naming its enforcing gate.

**7.1.6** A specification is not frozen while any part of 7.1.5 is unsatisfied.

### 7.2 How implementation consumes a specification

**7.2.1** Implementation transcribes a frozen specification, resolving each cited token name against the registry version that specification records.

**7.2.2** Implementation SHALL NOT substitute a value, approximate a value, or select a different token. A literal in a package within declared gate scope is a gate failure, not a judgement call.

**7.2.3** Where implementation finds a token citation unresolvable, ambiguous, or defective under 7.1.2, it **stops**. The correction is made in the specification and re-approved; an in-code correction to a visual outcome is a process violation regardless of its merit.

**7.2.4** Implementation MAY resolve only mechanical details the specification does not determine and which no rule fixes.

**7.2.5** A rename, a mechanism change preserving enforcement exactly, or removal of a token whose last consumer is gone is implementation. It requires no amendment and confers no licence to alter any outcome.

### 7.3 How existing local values migrate

**7.3.1** Every value presently held locally in a surface package resolves to exactly one outcome:

| Outcome | Condition |
|---|---|
| **Migrate** | An existing canonical entry serves. Default outcome. |
| **Mint** | No entry serves; section 4 admits a new one. |
| **Carry** | Migration cannot complete in this change; the value is carried on a migration alias with recorded expiry. |

**7.3.2** A value has no fourth outcome. "Left as-is, untracked" is not an outcome; it is the condition this ADR exists to remove.

**7.3.3** A migration SHALL NOT alter a visual outcome. Where a change would alter one, it is a specification matter and routes through the specification, not through the migration.

**7.3.4** Partial migration is permitted, and is the expected shape of this work: seeding from frozen reality under 4.1.1 exists precisely so the work proceeds as migration rather than redesign, without a direction.

**7.3.5** A partial migration is admissible only where every un-migrated value is carried on a migration alias with a recorded expiry. A partial migration with untracked residue is a permanent fork and is prohibited.

**7.3.6** The distinction is exact and testable: **partial** means *tracked and dated*; **fork** means *untracked or undated*.

---

## 8. Enforcement

### 8.1 Obligation

**8.1.1** Every invariant this ADR introduces names its enforcing gate in this section.

**8.1.2** Each gate below is **new**. None is an extension of an existing gate, and no existing gate is claimed to carry any invariant in this ADR. Each must be built through the repository gate path — a rule in `RovaGateRules` with a golden test, a `SourceCheckTask` registration in `app/build.gradle.kts`, a `preBuild` dependency, and its id in `RegistryTest.EXPECTED_IDS` — before it can be relied upon.

**8.1.3** Until a gate below is built, the clauses it enforces state a target rather than an enforced prohibition. This limit is recorded so it is never mistaken for coverage.

### 8.2 Mapping

| Invariant | Clauses | Enforcing gate | What it asserts | Status |
|---|---|---|---|---|
| No literal for an in-scope property in a package within declared scope | 5.1.1, 5.2.1, 5.2.3 | `checkNoRawDimenRecorderPlayer` | Source-level: within the packages listed in Appendix B, no dimension, alpha, radius, duration, or colour literal for a property named in 2.2.1 | **New.** Named by Constitution §4, drafting brief §3. Not yet built |
| Exactly one component computes status precedence, in the arbiter layer | 6.1.1, 6.1.2, 6.1.3, 6.2.1 | single-site gate, of the shape of `checkScanTriggerSingleSite` | Source-level: precedence is computed in exactly one component. It counts computing components, never queries over a computed order (6.1.2). Its declared scope is the arbiter layer only, per 6.2.1 and 6.2.2; it does not read the routing layer | **New.** Named by Constitution §4, drafting brief §4. Not yet built |
| Registry structural integrity | 3.1.1, 3.1.3, 4.1.2, 4.2.1–4.2.4, 4.2.8, 4.4.1, 4.5.2 | `checkTokenRegistryIntegrity` | Registry-artifact level: one canonical form; every alias resolves to a canonical entry of identical value; no alias chain exceeds one hop; every alias records its required fields; no migration alias is past expiry while it holds consumers; every non-seeded canonical entry carries a mint record with items (a)–(f) present; every seeded entry names its frozen-specification source; no deprecated entry has acquired a new consumer | **New.** Originated by this ADR. Not yet built |

### 8.3 Stated limits

**8.3.1** `checkTokenRegistryIntegrity` asserts **presence and structure**, never sufficiency. Whether a mint's justification is *adequate* is a judgement, discharged by the independent reviewer in 4.4.4. A gate that cannot fail on insufficiency is not claimed to test it.

**8.3.2** No gate in 8.2 asserts that a value is visually correct, and none is capable of doing so. Visual correctness is decided in a specification.

**8.3.3** A gate SHALL NOT be weakened, disabled, rescoped, or bypassed to make a check pass. The source is corrected, or the owning clause is amended with recorded sign-off.

---

## 9. Consequences

**Positive.** The topological cause of the divergence recorded in section 1 is removed rather than its symptoms: a fourth specification can no longer fork the system by construction, because there is a source above it. Seeding from frozen reality reframes the work as migration rather than redesign, so it proceeds without a design direction and blocks no other work. Registry scope takes effect on adoption while enforcement expands package by package, so no shipped package is retroactively in violation.

**Negative.** The migration touches surface packages without producing any visible improvement. Some existing values will not map cleanly to a seeded entry and will require judgement calls made without a direction to guide them — accepted, because waiting for a direction is what produced the divergence. Three gates must be built before any clause in section 5, 6, or the registry-integrity set is enforced; until then those clauses state targets, and 8.1.3 records that plainly.

**Neutral.** This ADR does not make the product look better. It makes the next visual decision cheaper and the next divergence traceable.

---

## 10. Appendix A — Authority trace

Every clause states exactly one governing authority. Four classes are used: **Constitution** (a clause of `docs/design/rova-design-constitution-reduced.md`), **peer ADR**, **repository convention**, and **local origination** (originated by this ADR under the competency Constitution §5.1 grants it, and carried by this ADR's own sign-off). No clause takes the Architecture Decision as authority; that document is evidence in section 1 only.

| Clause | Authority |
|---|---|
| §0 header — the Constitution-prevails statement | Constitution §3 |
| 1 (Context) | Evidence only — no normative clause |
| 2.1.1 | Constitution §5.1 |
| 2.1.2 | Constitution §5.2 |
| 2.2.1 | Constitution §4, drafting brief §1 |
| 2.2.2, 2.2.3 | Local origination |
| 2.3.1, 2.3.2 | Local origination |
| 2.4.1, 2.4.2 | Local origination |
| 2.5 (table) | Local origination — each row names where the excluded subject is decided |
| 3.1.1 | Constitution §4, drafting brief §1 |
| 3.1.2, 3.1.3, 3.1.4 | Local origination |
| 3.2.1 | Local origination |
| 3.2.2 | Constitution §4, drafting brief §1 |
| 3.2.3 | Constitution §2, Rule 1 |
| 3.2.4 | Repository convention — Independent Review Workflow |
| 3.3.1, 3.3.2, 3.3.3 | Local origination |
| 3.3.4, 3.3.5 | Repository convention — freeze-and-supersede |
| 4.1.1, 4.1.2 | Constitution §4, drafting brief §2 |
| 4.1.3 | Local origination |
| 4.2.1, 4.2.2, 4.2.3, 4.2.4, 4.2.5 | Local origination |
| 4.2.6 | Repository convention — freeze-and-supersede |
| 4.2.7, 4.2.8, 4.2.9 | Local origination |
| 4.3.1, 4.3.2 | Constitution §4, drafting brief §2 |
| 4.3.3 | Local origination |
| 4.4.1 | Constitution §4, drafting brief §2 |
| 4.4.2 | Local origination |
| 4.4.3 | Constitution §2, Rule 1 |
| 4.4.4 | Repository convention — Independent Review Workflow |
| 4.4.5 | Local origination |
| 4.4.6 | Local origination |
| 4.4.7 | Repository convention — a gate is not edited away to make a check pass |
| 4.5.1, 4.5.2, 4.5.4 | Local origination |
| 4.5.3 | Repository convention — freeze-and-supersede |
| 4.5.5 | Local origination |
| 5.1.1 | Constitution §4, drafting brief §3 |
| 5.2.1, 5.2.2, 5.2.3 | Local origination |
| 5.3.1, 5.3.2 | Local origination |
| 5.3.3 | Repository convention — mechanism-versus-enforcement |
| 5.3.4 | Repository convention — a gate is not edited away to make a check pass |
| 5.4.1, 5.4.2, 5.4.3 | Local origination |
| 6.1.1 | Constitution §4, drafting brief §4 |
| 6.1.2, 6.1.3 | Local origination |
| 6.2.1, 6.2.2, 6.2.3 | Constitution §6, C2 |
| 6.2.4 | Repository convention — `docs/WarningCenterContract.md` is authoritative on precedence |
| 6.2.5 | Local origination |
| 7.1.1 | Constitution §4, drafting brief §1 |
| 7.1.2, 7.1.3 | Local origination |
| 7.1.4 | Constitution §4, drafting brief §2 |
| 7.1.5 | Constitution §4, drafting brief §§1–3 |
| 7.1.6 | Local origination |
| 7.2.1, 7.2.3 | Repository convention — HTML-first design workflow |
| 7.2.2 | Constitution §4, drafting brief §3 |
| 7.2.4 | Repository convention — HTML-first design workflow |
| 7.2.5 | Repository convention — mechanism-versus-enforcement |
| 7.3.1 | Constitution §4, drafting brief §2 |
| 7.3.2 | Local origination |
| 7.3.3 | Repository convention — HTML-first design workflow |
| 7.3.4 | Local origination |
| 7.3.5, 7.3.6 | Local origination |
| 8.1.1 | Repository convention — gate-at-authoring |
| 8.1.2 | Repository convention — the invariant → gate → `preBuild` → `RegistryTest` path |
| 8.1.3, 8.3.1, 8.3.2 | Local origination |
| 8.2 (row 1) | Constitution §4, drafting brief §3 |
| 8.2 (row 2) | Constitution §4, drafting brief §4 |
| 8.2 (row 3) | Local origination |
| 8.3.3 | Repository convention — a gate is not edited away to make a check pass |
| 9 (Consequences) | Non-normative |
| Appendix B | Local origination |
| Appendix C | Local origination |

---

## 11. Appendix B — Declared gate scope

This appendix holds, for each gate named in 8.2, its declared package scope and the expiry for any carried migration alias. It is the sole record of gate scope and is amended under 5.3.2 with owner sign-off.

**At authoring this appendix is empty of package entries.** Populating it is the first migration under 7.3. It is not populated here, for two reasons: doing so would fix a package adoption order that 5.4.3 reserves to scheduling, and it would extend a present-tense prohibition to packages whose migration has not been scheduled, which 5.2.3 forbids. An empty appendix is therefore the correct state on adoption, not an incomplete one.

| Gate | Declared packages | Carried aliases and expiry |
|---|---|---|
| `checkNoRawDimenRecorderPlayer` | *(none declared)* | *(none)* |
| single-site arbiter gate | *(none declared)* | *(none)* |
| `checkTokenRegistryIntegrity` | *(registry artifact; not package-scoped)* | *(none)* |

---

## 12. Appendix C — Explicitly out of scope

Recorded so each absence reads as a decision rather than an oversight.

| Excluded | Why |
|---|---|
| Every rung value, ladder length, hue, duration, and inter-rung relationship | 2.2.3. This ADR decides where values live, never what they are |
| Any design direction, layout grammar, identity claim, register, voice, or ornament | Constitution §2, Rule 1 |
| The eight unenforced visual invariants of Constitution §5.2 and their required mechanisms | 2.1.2. Each already names its carrier |
| The interactive-target conformance gap of Constitution §5.3 | Constitution §5.3 routes it to the peer ADR that owns it |
| Evidence classification, falsifiable success criteria, and rubric-before-candidates | Constitution §5.5 records these as deleted |
| A general amendment process, layer-competency tables, an inheritance diagram, and an authorship checklist | Constitution §5.5 records these as deleted |
| Document precedence between classes | Constitution §3 |
| Change control for this ADR itself | Repository ADR-amendment convention |
| Per-clause provenance markers | Appendix A records authority at clause granularity; the repository has no per-clause marker convention |
| An emergency or expedited path for registry change | The Constitution contains no emergency provision and this ADR originates none. Registry change under time pressure follows the ordinary process of section 4 and the repository's ADR-amendment convention |
| Gate identifier naming and renaming | Implementation, under the repository gate convention. This ADR cites each gate by the identifier in force at authoring |
| Registry file format, location, serialization | 3.1.4 |
| Package adoption order | 5.4.3 |
| Presentation of arbitrated status | 6.2.5 |
| The content and ordering of precedence identifiers | 6.2.4 |
| Any declaration that shipped behaviour is defective | 6.2.3 |

---

*Design ADR. No value, no ladder, no hue, no layout, no direction, no screen. Determines who owns values, how they enter, how they retire, what proves it, and where precedence is computed.*
