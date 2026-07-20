# ADR-050 — Clause Inventory (Historical Snapshot)

**Document class:** Authoring-support artifact. **Non-normative.** Nothing in this document is enforceable.
**Date captured:** 2026-07-20.
**Source:** `docs/adr/0050-design-token-authority.md` (abandoned draft, Proposed 2026-07-19), read unmodified.
**Purpose:** Satisfies repository state precondition **S5**. Exists solely as the baseline against which Definition-of-Done criteria **#6** and **#7** disposition every concept during the authoring of `docs/adr/0039-cross-surface-token-authority.md`.

**Capture rules applied.** Clause identifier, section, obligation text, and original marker are preserved. Obligation text is verbatim. Markers are recorded **for historical disposition only** and are not corrected, re-pointed, or removed — the authorities several of them cite were abolished by `docs/design/rova-design-constitution-reduced.md`. Nothing here is classified, interpreted, dispositioned, or derived.

**Measured totals.** 115 `**TA-n**` clause definitions. 75 `[CI` markers. 54 `[LOCAL` markers. Sub-item identifiers carried inside clause tables (R-a…R-f, E-1…E-9, M-a…M-f, N-a…N-i, and the version-class, lifecycle-outcome, and role rows) are recorded within their parent clause's obligation text.

---

## Section 2 — Registry ownership

| Clause | § | Marker as authored | Obligation text (verbatim) |
|---|---|---|---|
| TA-1 | 2.1 | `[CI: I.1; S0-1]` | A single artifact SHALL be the **canonical token registry**. It is the sole source for every property within the taxonomy of §3. |
| TA-2 | 2.1 | `[LOCAL]` | The canonical registry is a **machine-readable artifact under version control**, from which every consuming form is derived. |
| TA-3 | 2.1 | `[LOCAL, derived from I.7]` | Where more than one form of the registry exists (a specification-consumable form, an implementation-consumable form, or any other), exactly one form SHALL be canonical and every other form SHALL be **generated from it**, never hand-authored in parallel. A hand-maintained second form is a fork of the registry and is prohibited by the same reasoning as I.7. |
| TA-4 | 2.1 | `[LOCAL]` | This ADR does not decide the registry's file format, location, or serialization. That is an implementation determination, resolvable under Constitution §5.3, provided TA-2 and TA-3 hold. |
| TA-5 | 2.2 | `[LOCAL]` | The registry has exactly one **Registry Owner** role. The role is a repository responsibility, not a named individual, and survives any change of personnel. |
| TA-6 | 2.2 | `[CI: I.1; §4.1 "Design specifications … Must not decide: token values absent from the registry"]` | No specification, no surface package, and no implementation owns any token. Specifications and implementations are **consumers only**. |
| TA-7 | 2.2 | `[CI: X.1]` | The Registry Owner does not decide visual outcome and SHALL NOT use registry ownership to do so. Refusing a mint on the grounds stated in §5 is registry governance; refusing it because of a preferred appearance is a direction decision made in a foundation artifact, prohibited by X.1. |
| TA-8 | 2.3 | `[LOCAL]` | The Registry Owner is responsible for, and only for: *(table rows R-a Admitting, rejecting, and recording mints under §5; R-b Maintaining the alias classes of §4, including their retirement conditions; R-c Maintaining the migration schedule of §7 and its gate-scope declarations; R-d Ensuring every registry entry names its governing Article, ADR clause, or frozen-specification source; R-e Ensuring every registry entry is reachable by the gates of §9; R-f Recording every registry change with its evidence class under Constitution IX.1.)* |
| TA-9 | 2.3 | `[CI: repository Independent Review Workflow, restated as authority by T13/S0-16 and by the Constitution's Known-descendants for Article XI]` | The Registry Owner SHALL NOT self-approve a mint. Every mint requires the reviewers named in §5.4. |
| TA-10 | 2.4 | `[LOCAL]` … `[CI: I.1]` | The registry's **property scope** is the taxonomy of §3, which is bounded by Constitution Article I's Scope and extended only where §3 marks the extension **[LOCAL]**. **[CI: I.1]** |
| TA-11 | 2.4 | `[CI: Constitution, Note on scope; S0-1]` | The registry's **surface scope** is all surfaces. Constitution §Note-on-scope widens the Architecture Decision's Recorder-and-Player scope to all surfaces on the authority of S0-1, "because a token authority that binds only two surfaces reproduces the local-freezing topology it exists to remove." |
| TA-12 | 2.4 | `[CI: I.3a]` | Surface scope and **gate scope** are distinct and SHALL NOT be conflated. TA-11 states where tokens are the source of truth; §7 states which packages are presently *enforced*. Until a package is named by a gate, I.3 states a target for it, not a present-tense prohibition. |
| TA-13 | 2.5 | `[LOCAL]` | The registry is **versioned as a whole**, not per token. A version identifies the exact set of canonical tokens, aliases, and their values at a point in time. |
| TA-14 | 2.5 | `[LOCAL, mechanizing I.2 and XII.2]` | Every frozen specification SHALL record the registry version it was frozen against. |
| TA-15 | 2.5 | `[LOCAL]` | A registry version change SHALL be classified as exactly one of: *(table rows — **Additive**: A canonical token is added; no existing canonical value changes; no alias retires. Effect on frozen specifications: None. Frozen specifications remain valid. **Alias**: An alias is added, reclassified, or retired; no canonical value changes. Effect: None, unless a retiring alias is cited by a frozen specification, in which case TA-17 applies. **Breaking**: A canonical value changes, or a canonical token is removed. Effect: Every frozen specification citing it is invalidated and MUST be superseded under XII.1.)* |
| TA-16 | 2.5 | `[CI: XII.1, XII.5, XII.5a]` | A **Breaking** change SHALL NOT be made to satisfy a specification's convenience. It is admissible only where the value is itself defective under a Constitutional rule (for example, a rung below the II.1 floor), and it ships together with the supersession of every specification that cited it. |
| TA-17 | 2.5 | `[CI: XII.1, XII.2]` | A frozen specification SHALL NOT be edited to follow a registry change. Where a registry change invalidates it, the specification is superseded by a versioned successor that restates every carried-forward contract. |
| TA-18 | 2.6 | `[LOCAL]` | A registry entry occupies exactly one lifecycle state at any time: *(diagram — FROZEN SOURCE →(seed, §4.2)→ CANONICAL; →(mint, §5)→ CANONICAL →(deprecate, §8.4)→ DEPRECATED →(consumers = 0)→ REMOVED)* |
| TA-19 | 2.6 | `[CI: I.2, I.4; S0-13]` | Seeding is not minting. A value imported from a frozen specification under I.4 enters as **CANONICAL** without a mint justification, because its justification is the frozen specification that already carries it. It SHALL NOT be re-derived, re-measured, or re-expressed on entry. |
| TA-20 | 2.6 | `[CI: §5.3]` | Removal of a **DEPRECATED** entry whose consumer count is zero is implementation, requires no amendment, and confers no license to alter outcome. |

---

## Section 3 — Token taxonomy

| Clause | § | Marker as authored | Obligation text (verbatim) |
|---|---|---|---|
| TA-21 | 3 | `[LOCAL]` | The taxonomy is **closed**. A value within the taxonomy MUST resolve to a token in its category. A value outside the taxonomy is not governed by this ADR. |
| TA-22 | 3 | `[LOCAL]` | Adding a category REQUIRES an amendment to this ADR under §8.3. A category SHALL NOT be created implicitly by minting a token that fits none. |
| TA-23 | 3 | `[CI: XI.1, by analogy — a category without an authority is an unenforced rule]` | Every category names the Constitutional clause that places it in scope. A category with no governing clause is defective. |
| TA-24 | 3.1 | `[LOCAL]` | C9 and C10 are ADR-local extensions of Article I's Scope. They are admitted because III.3 and II.2–II.4 each require a **named** value to exist while Article I's Scope list does not name a category to hold it, and a value with no registry category is a value that lives locally — the precise defect Article I removes. They extend structure, not obligation: neither category imposes any requirement the Constitution does not already impose. |
| TA-25 | 3.1 | `[LOCAL]` | A single token SHALL belong to exactly one category. A value serving two categories is two tokens, or one token and one alias under §4. |
| TA-26 | 3.1 | `[CI: Article I, Non-goals]` | This ADR does not decide how many tokens any category contains, nor whether a category's tokens form a ladder, a set, or a single value. |

### §3.1 category table — non-clause rows, markers as authored

| # | Category | Governs | Authority as authored | Marker as authored |
|---|---|---|---|---|
| C1 | **spacing** | Distances between and within elements. | Article I Scope ("spacing"); S0-1 | `[CI]` |
| C2 | **radius** | Corner geometry. | Article I Scope ("radius"); S0-1 | `[CI]` |
| C3 | **size** | Control height and any other named element dimension. | Article I Scope ("control height"); S0-1 | `[CI]` |
| C4 | **ink** | Foreground alpha applied to text and iconography. | Article I Scope ("ink alpha"); S0-1 | `[CI]` |
| C5 | **scrim** | Overlay alpha, and over-media elevation expressed as scrim and opacity. | Article I Scope ("scrim alpha"); IV.3; S0-1, S0-9 | `[CI]` |
| C6 | **color** | Over-media color, including the enumerated semantically-locked hue set. | Article I Scope ("over-media color"); III.1, III.2; S0-1, S0-7 | `[CI]` |
| C7 | **motion** | Duration and easing. | Article I Scope ("motion duration", "motion easing"); VII.1; S0-1, S0-12 | `[CI]` |
| C8 | **typography** | Rendered text size, and font features required for correctness. | Article I Scope ("minimum type size"); II.1; V.1; S0-1, S0-3, S0-10 | `[CI]` |
| C9 | **status** | Tokens whose meaning is semantically locked to a state, including the recording accent and any locked severity hue. | III.3 fixes cardinality and requires a *named* token; the category is the registry structure that holds it | `[LOCAL, mechanizing III.3]` |
| C10 | **interaction** | The interactive-target minimum and the invisible-expansion mechanism's parameters. | II.2, II.3, II.4 fix the floor and the mechanism; the category is the registry structure that holds them | `[LOCAL, mechanizing II.2–II.4]` |

---

## Section 4 — Alias policy

| Clause | § | Marker as authored | Obligation text (verbatim) |
|---|---|---|---|
| TA-27 | 4.1 | `[LOCAL, closing the minting backdoor implied by I.6]` | An **alias is a name, never a value.** Every alias resolves to exactly one canonical token and carries that token's value unchanged. An alias SHALL NOT introduce, adjust, or approximate a value. |
| TA-28 | 4.1 | `[LOCAL]` | Alias creation is therefore **not** a mint and does not require §5 evidence. Alias creation that would require a new value **is** a mint and is governed entirely by §5. |
| TA-29 | 4.1 | `[LOCAL]` | Alias chains SHALL NOT exceed one hop. An alias resolves directly to a canonical token, never to another alias. |
| TA-30 | 4.1 | `[LOCAL]` | Every alias records, at creation: its class, its canonical target, its source, and — for the classes that require one — its retirement condition. An alias with no recorded retirement condition where one is required is defective. |
| TA-31 | 4.3 | `[CI: I.2, XII.1]` | A legacy alias MAY be permanent. Its owning specification is frozen and XII.1 forbids editing it, so a name it uses cannot be withdrawn while that specification stands. |
| TA-32 | 4.3 | `[LOCAL]` | A legacy alias SHALL NOT be cited by a **new** specification. New specifications cite canonical names. |
| TA-33 | 4.4 | `[CI: I.7 "migration with a stated timeline"]` | A migration alias MUST record an **expiry condition** at creation. |
| TA-34 | 4.4 | `[CI: I.7; R6]` | A migration alias SHALL NOT be permanent. A migration alias that does not expire is a sanctioned permanent divergence and is prohibited. |
| TA-35 | 4.4 | `[CI: XI.1, XI.2 — an unenforced deadline is a comment]` | A migration alias whose expiry condition has passed while it still has consumers SHALL fail the registry gate (§9). Expiry is enforced, not advisory. |
| TA-36 | 4.4 | `[LOCAL, by analogy to §5.1 of the Constitution's amendment grounds]` | Extending a migration alias's expiry REQUIRES the same reviewers as a mint (§5.4) and a recorded reason in its Article IX evidence class. Convenience is not a reason. |
| TA-37 | 4.5 | `[LOCAL]` | A deprecated alias SHALL NOT acquire a new consumer. A new citation of a deprecated name is a gate failure. |
| TA-38 | 4.5 | `[LOCAL]` | Deprecation records the canonical replacement, or records explicitly that there is none. |
| TA-39 | 4.5 | `[CI: XII.1]` | A deprecated alias with a live frozen-specification consumer SHALL NOT be removed; it is removed only after that specification is superseded (TA-17). |

**Non-clause definitions carried in §4 (class definitions, "when it exists", "how it retires", and the §4.6 summary table) are recorded here as present in the source and are not reproduced clause-by-clause, having no TA identifier.** Classes defined: Canonical token (§4.2, marker `[CI: I.1, I.4, I.6]`), Legacy alias (§4.3, marker `[CI: I.2, XII.1]`), Migration alias (§4.4, marker `[CI: I.7; S0-13]`), Deprecated alias (§4.5, marker `[LOCAL, mechanizing XII.1 and §5.3]`).

---

## Section 5 — Minting policy

| Clause | § | Marker as authored | Obligation text (verbatim) |
|---|---|---|---|
| TA-40 | 5.1 | `[CI: I.5]` | A mint is permitted **only** where a private or proposed value cannot migrate to an existing canonical rung. Migration to the nearest existing rung is the default outcome; minting is the exception. |
| TA-41 | 5.1 | `[LOCAL, mechanizing I.5–I.7]` | A mint request MUST be refused where any of the following holds: *(table rows — M-a An existing canonical rung is available and the request states no reason it fails. M-b The stated reason is a preference for the proposed value's appearance (X.1 — direction may not enter this artifact). M-c The requesting specification is not frozen and the value has not been demonstrated at the Article II accessibility parameters (II.5, XII.4). M-d The mint would create a second scale coexisting permanently with an existing one (I.7). M-e The mint's category does not exist in §3 and no amendment under §8.3 accompanies it. M-f The mint would place a value below a Constitutional floor (II.1, II.2).)* |
| TA-42 | 5.1 | `[LOCAL]` | "The frozen ladder has no near rung" is a *finding*, not a justification. It states that migration failed; it does not state why the new value is correct. Both are required. |
| TA-43 | 5.2 | `[CI: I.6, IX.1, IX.3; LOCAL as to the item list]` | A mint request MUST record: *(table rows — E-1 The category (§3) and the proposed canonical name. E-2 The consuming surface or surfaces, named. E-3 The nearest-rung analysis: every candidate existing rung considered, and the specific reason each fails. E-4 The evidence class of that reason, recorded under IX.1 as measured fact, expert inspection, assumption, or opinion. E-5 Where the reason is an assumption and the mint depends on it, the assumption labelled load-bearing with its holder named (IX.3). E-6 Demonstration at the accessibility parameters of Article II, where the value affects layout, size, or contrast (II.5, XII.4). E-7 For a C6 or C9 color token: its entry in the contrast-resolution sweep (III.2). E-8 The migration plan required by §5.6. E-9 The gate-scope update required by §5.5.)* |
| TA-44 | 5.2 | `[CI: IX.2]` | An evidence class recorded at E-4 SHALL NOT be inflated. Expert inspection is not recorded as, or reasoned from as, measured fact. |
| TA-45 | 5.2 | `[CI: IX, Non-goals]` | A mint MAY be admitted on expert inspection or on a labelled assumption. Article IX ranks the classes "only by what they may be used to justify" (Article IX, Non-goals), and a rung is not a direction. What IX forbids is the *misrecording*, not the reliance. |
| TA-46 | 5.3 | `[CI: I.6]` | The recorded justification MUST answer, in writing: why the value is required, why no existing rung serves, what the value's absence would cost, and what the mint forecloses. |
| TA-47 | 5.3 | `[CI: IX.4, IX.5, IX.6]` | The justification SHALL NOT consist of, or rest on, a criterion that cannot fail (IX.4), a conformance count in place of an outcome (IX.5), or a self-authored score on a self-authored rubric (IX.6). |
| TA-48 | 5.3 | `[CI: IX.7]` | Where two or more candidate values are compared, the rubric is written before they are scored and applied identically to all of them. |
| TA-49 | 5.4 | `[CI: repository Independent Review Workflow; TA-9]` | A mint REQUIRES: *(role table — **Requester**: Records E-1…E-9. Never self-approves. **Registry Owner**: Admits or refuses against §5.1 and §5.2. Never the Requester. **Independent Review Agent**: Fresh context, no shared draft state; attempts to falsify the nearest-rung analysis; classifies findings Required Fix / Recommended Improvement / Observation; returns exactly one verdict GO / GO WITH FIXES / NO-GO. **Owner sign-off**: Required for any mint classified Breaking (TA-15), and for any mint accompanying a §8.3 amendment.)* |
| TA-50 | 5.4 | `[CI: repository Independent Review Workflow]` | A **NO-GO** verdict blocks the mint. A **GO WITH FIXES** verdict requires every Required Fix reconciled, or the disagreement explicitly surfaced to the owner. Reconciliation is the Requester's obligation, never the Review Agent's. |
| TA-51 | 5.5 | `[CI: XI.1, XI.5; I.3a]` | A mint that introduces a new category (§8.3), a new enforcement obligation, or a value in a package not currently within gate scope MUST update the declared scope of the owning gate in §9 in the same change. |
| TA-52 | 5.5 | `[CI: XI.1, XI.2]` | A mint SHALL NOT be admitted where it introduces an invariant whose enforcing gate is unnamed. |
| TA-53 | 5.5 | `[CI: XI.3]` | A gate SHALL NOT be weakened or rescoped to admit a mint. The mint is refused, or the owning clause is amended with recorded sign-off. |
| TA-54 | 5.6 | `[CI: I.7]` | A mint MUST state, for every existing value it renders redundant, either that the value migrates to the new rung — with a stated timeline — or that no value is rendered redundant. |
| TA-55 | 5.6 | `[CI: I.7; R6]` | A mint that leaves two scales coexisting without a stated timeline is a sanctioned permanent divergence and SHALL be refused. |
| TA-56 | 5.6 | `[CI: I.7; LOCAL as to the mechanism]` | Where a mint's migration cannot complete inside the requesting change, the un-migrated consumers are carried on **migration aliases** (§4.4) with recorded expiry. They are never carried silently. |

---

## Section 6 — Consumption policy

| Clause | § | Marker as authored | Obligation text (verbatim) |
|---|---|---|---|
| TA-57 | 6.1 | `[CI: I.1, I.3; S0-1, S0-2]` | A specification expresses every property within §3's taxonomy as a **token name**. |
| TA-58 | 6.1 | `[LOCAL]` | A specification records the registry version it was frozen against (TA-14). |
| TA-59 | 6.1 | `[LOCAL]` | A specification MAY restate a token's value **alongside** its name for human readability. Where a restated value and the registry disagree, **the registry prevails and the specification is defective**, not authoritative. |
| TA-60 | 6.1 | `[LOCAL]` | A specification cites canonical names. Legacy aliases (TA-32) and deprecated aliases (TA-37) are not available to it. |
| TA-61 | 6.1 | `[CI: I.5, I.6; §5.4 of the Constitution]` | Where a specification requires a value the registry lacks, it does not proceed. The value enters the registry first, under §5. |
| TA-62 | 6.2 | `[CI: I.1, I.3, I.5, I.6, I.7, II.1, II.2, VII.3]` | A specification SHALL NOT: *(table rows with authority — N-a Mint a token. (I.6) N-b Introduce a literal dimension, alpha, radius, duration, or color for a property in §3's taxonomy. (I.3, S0-2) N-c Define a private scale, ladder, or set for any §3 category. (I.1, I.7) N-d Adopt a "nearest visual equivalent" of a registry value without a registry entry. (I.5) N-e Mint a second motion ladder. (VII.3) N-f Re-derive, re-measure, or re-express a frozen value. (I.2) N-g Sanction a permanent divergence between two scales. (I.7) N-h Use a value below a Constitutional floor. (II.1, II.2) N-i Introduce an invariant without naming its enforcing gate. (XI.1, XI.2))* |
| TA-63 | 6.2 | `[CI: XI.5; Constitution §6]` | A specification is not frozen while any of TA-62 is unsatisfied. |
| TA-64 | 6.3 | `[CI: §4.1 "Implementation … transcribes a frozen specification"]` | Implementation **transcribes** a frozen specification. It resolves each cited token name against the registry version the specification records. |
| TA-65 | 6.3 | `[CI: I.3; §4.1]` | Implementation SHALL NOT substitute a value, approximate a value, select a different token, or introduce a literal in a gated package. A literal in a gated package is a **gate failure**, not a judgement call. |
| TA-66 | 6.3 | `[CI: §4.1 Implementation; HTML-first workflow]` | Where implementation finds a specification's token citation unresolvable, ambiguous, or defective under TA-59, it **stops**. The correction is made in the specification and re-approved; an in-code correction to a visual outcome is a process violation regardless of its merit. |
| TA-67 | 6.3 | `[CI: §4.1]` | Implementation MAY resolve only mechanical details the specification does not determine and which no rule fixes. |
| TA-68 | 6.3 | `[CI: §5.3; XI.4]` | A rename, a mechanism change preserving enforcement exactly, or removal of a token whose last consumer is gone is implementation, requires no amendment, and confers no license to alter outcome. |

---

## Section 7 — Migration policy

| Clause | § | Marker as authored | Obligation text (verbatim) |
|---|---|---|---|
| TA-69 | 7.1 | `[CI: I.5]` | Every value presently held locally in a surface package resolves to exactly one outcome: *(table rows — **Migrate**: An existing canonical rung serves. Default outcome. **Mint**: No rung serves; §5 admits a new one. **Retire**: The value is defective under a Constitutional rule (below a floor, an unswept hue, a shadow over media, a simulated blur) and is removed rather than tokenized. **Carry**: Migration cannot complete in this change; the value is carried on a migration alias with recorded expiry (§4.4).)* |
| TA-70 | 7.1 | `[LOCAL]` | A value has no fifth outcome. "Left as-is, untracked" is not an outcome; it is the defect this ADR exists to remove. |
| TA-71 | 7.1 | `[CI: II.1 "retired, not grandfathered"; III.2; IV.1; IV.2; IV.3]` | Retirement under TA-69 is a **Constitutional obligation, not a discretionary cleanup**, where the value violates II.1, II.2, III.2, IV.1, IV.2, or IV.3. |
| TA-72 | 7.1 | `[CI: §4.1 Implementation; §5.4]` | A migration SHALL NOT alter a visual outcome except where the outcome is itself defective under a Constitutional rule. Where a migration does alter an outcome, the change is a specification matter and routes through the specification, not through the migration. |
| TA-73 | 7.1 | `[CI: XII.4]` | A claim that a migration changes zero pixels SHALL NOT be accepted without demonstration at the accessibility parameters required by Article II. |
| TA-74 | 7.2 | `[CI: I.4; S0-13; Architecture Decision, Consequences]` | Partial migration is permitted. It is the expected shape of this work: I.4 seeds the registry from frozen reality precisely so the work "can proceed without a direction" as *migration rather than redesign*. |
| TA-75 | 7.2 | `[CI: I.7; R6]` | A partial migration is admissible **only** where every un-migrated value is carried on a migration alias with a recorded expiry (TA-33). A partial migration with untracked residue is a permanent fork and is prohibited. |
| TA-76 | 7.2 | `[LOCAL]` | The distinction is exact and testable: **partial** means *tracked and dated*; **fork** means *untracked or undated*. |
| TA-77 | 7.3 | `[CI: I.3a; XI.4 — a gate's scope is part of what it enforces]` | Each gate in §9 declares an explicit list of packages within its scope. Scope is a declared list, never an implicit "everywhere." |
| TA-78 | 7.3 | `[CI: I.3a; I.7]` | Expanding a gate's declared scope to a further package REQUIRES, in the same change: the package's migration completed or carried under TA-75; a stated timeline for any carried residue; and the amended scope list recorded in §9 and in Appendix B. |
| TA-79 | 7.3 | `[LOCAL]` | Scope expansion is an **amendment to this ADR's Appendix B**, admitted under §8.3, with owner sign-off. It is not a unilateral act of the Registry Owner and not an implementation detail. |
| TA-80 | 7.3 | `[CI: XI.4]` | Expanding gate scope changes only *which packages* a gate reads. It SHALL NOT change *what the gate enforces*. A scope expansion that also alters the rule is two changes and is admitted as two. |
| TA-81 | 7.3 | `[CI: I.3a]` | Until a package appears in a gate's declared scope, I.3 states a target for it, not a present-tense prohibition, and its absence is not a violation. |
| TA-82 | 7.4 | `[LOCAL, composing I.3a, I.5, I.7]` | Adoption proceeds one package at a time. A package is **adopted** when: every value in it satisfies TA-69; every carried value has an unexpired migration alias; the package appears in the declared scope of every gate in §9 that governs a category it uses; and the gates pass. |
| TA-83 | 7.4 | `[LOCAL — deciding an order here would bind every subsequent specification to a sequence with no constitutional basis, contrary to X.6]` | Package ordering is not decided by this ADR. It is a scheduling determination recorded in Appendix B and amended under §8.3. |
| TA-84 | 7.4 | `[CI: I.4 Rationale; Architecture Decision, Direct answer 4]` | Adoption of a package SHALL NOT be blocked on the adoption of any other package, on the selection of a design direction, or on the freezing of any specification. Article I's rules are direction-independent and the Architecture Decision names holding them inside a design program as "the one active harm." |
| TA-85 | 7.4 | `[CI: XI.3]` | An adopted package SHALL NOT regress. A gate whose scope includes a package is never narrowed to re-admit a literal. |

---

## Section 8 — Governance

| Clause | § | Marker as authored | Obligation text (verbatim) |
|---|---|---|---|
| TA-86 | 8.1 | `[LOCAL, composing TA-5, TA-9, TA-49]` | Roles, and their exclusivity: *(role table — **Registry Owner** owns the registry artifact; admission of mints; alias classes; the migration schedule. Never: decides visual outcome (TA-7); approves own request (TA-9). **Requester** owns the mint's evidence and justification; reconciliation of review findings. Never: self-approves; admits its own mint. **Independent Review Agent** owns falsification; findings; one verdict. Never: fixes; shares context with the Requester. **Owner sign-off** owns Breaking changes; amendments to this ADR; gate-scope expansion.)* |
| TA-87 | 8.1 | `[CI: repository Independent Review Workflow]` | No role is filled by the same context as another within a single mint or amendment. |
| TA-88 | 8.2 | `[LOCAL]` | The following require the full review of §5.4: a mint; a **Breaking** registry change; an amendment to this ADR; a gate-scope expansion; an extension of a migration alias's expiry. |
| TA-89 | 8.2 | `[CI: §5.3; XI.4; TA-20]` | The following are implementation, require no review under this ADR, and confer no license to alter outcome: a rename; a gate mechanism change preserving enforcement byte-identically; removal of a **DEPRECATED** entry whose consumer count is zero. |
| TA-90 | 8.2 | `[CI: repository Independent Review Workflow]` | A registry change SHALL NOT be admitted on the strength of a summary. The reviewing context validates the registry artifact, the gate output, and the diff. |
| TA-91 | 8.3 | `[LOCAL]` | An amendment to this ADR is REQUIRED to: add or remove a taxonomy category (§3); change an alias class definition or retirement condition (§4); change the mint evidence set, refusal grounds, or reviewer set (§5); change the consumption prohibitions (§6); expand or narrow a gate's declared scope (§7.3); or change the gate mapping (§9). |
| TA-92 | 8.3 | `[CI: IX.1; Constitution §5.1's amendment requirements, applied at ADR layer]` | An amendment REQUIRES: the motivating evidence recorded in its Article IX class; independent review under §5.4; owner sign-off; and an updated Appendix A trace. |
| TA-93 | 8.3 | `[CI: X.1, X.3, X.6]` | An amendment SHALL NOT introduce a design direction, a layout, a composition grammar, an identity claim, a register, or an ornament. This ADR is cited by every specification and X.1 applies to it without exception. |
| TA-94 | 8.3 | `[CI: §1.4; §4.2]` | An amendment SHALL NOT weaken a Constitutional rule. Where this ADR and the Constitution conflict, the Constitution prevails and this ADR is defective. |
| TA-95 | 8.3 | `[LOCAL]` | Amending a clause marked **[CI]** in a way that changes its obligation is a **constitutional** amendment and follows Constitution §5.1, not this section. Only **[LOCAL]** clauses are amendable here. |
| TA-96 | 8.3 | `[CI: XII.1, XII.2; §5.2]` | Where the *scope* of this ADR must change — where it must govern a class of decision it does not reach, or cease to govern one it does — a **superseding ADR** is written rather than this one edited. It declares what it replaces and restates every carried-forward rule explicitly. |
| TA-97 | 8.4 | `[LOCAL]` | Deprecating a registry entry REQUIRES, recorded at deprecation: the reason; the canonical replacement or an explicit statement that there is none (TA-38); and the current consumer list. |
| TA-98 | 8.4 | `[LOCAL]` | On deprecation the entry acquires no new consumers (TA-37); existing consumers migrate; and removal follows at consumer count zero (TA-20). |
| TA-99 | 8.4 | `[CI: XII.1]` | A deprecated entry cited by a frozen specification is not removed until that specification is superseded (TA-39, TA-17). Deprecation does not authorize editing a frozen specification. |
| TA-100 | 8.4 | `[LOCAL]` | Deprecation SHALL NOT be used to effect a value change. Changing a value is a **Breaking** change under TA-15 and follows TA-16. |
| TA-101 | 8.5 | `[LOCAL]` | An emergency exception exists for one circumstance only: a defect that blocks a build or a release and whose correct resolution requires a registry change that cannot complete within the available time. |
| TA-102 | 8.5 | `[LOCAL]` | An emergency exception MAY create **only** a migration alias (§4.4). It SHALL NOT create a canonical token, SHALL NOT change a canonical value, SHALL NOT retire an alias, and SHALL NOT expand or narrow gate scope. |
| TA-103 | 8.5 | `[CI: XI.3]` | An emergency exception SHALL NOT weaken, disable, rescope, or bypass a gate. Constitution XI.3 admits no emergency. |
| TA-104 | 8.5 | `[LOCAL]` | A migration alias created under an emergency exception carries an expiry recorded at creation, expiring **no later than the next freeze of any specification consuming it**. |
| TA-105 | 8.5 | `[LOCAL]` | An emergency exception is retroactively reviewed under §5.4 before its expiry. A failed retrospective review does not extend the expiry; it obliges the migration. |
| TA-106 | 8.5 | `[CI: §4.1 Implementation; X.1]` | An emergency exception SHALL NOT resolve a visual or interaction question. Where the blocking defect is a visual outcome, the resolution is a specification supersession, not a registry act. |

---

## Section 9 — Gate mapping

| Clause | § | Marker as authored | Obligation text (verbatim) |
|---|---|---|---|
| TA-107 | 9 | `[CI: X.6; XI.4 — a gate whose mechanism absorbs an adjacent rule without changing what it enforces is preferable to a second gate]` | Gates are **extended in preference to being created**. A new gate is admitted only where no existing gate carries the enforcement point. |
| TA-108 | 9 | `[CI: I.3a]` | Every gate below declares an explicit package scope (TA-77), recorded in Appendix B, expanded only under §7.3. |
| TA-109 | 9.3 | `[LOCAL, under TA-107]` | This ADR admits exactly **one** gate not already named by the Constitution or the Architecture Decision: `checkTokenRegistryIntegrity` (L3). Every other row above is either an existing gate, a declared extension of one, or a gate the Constitution or Architecture Decision already names. |
| TA-110 | 9.3 | `[LOCAL]` | `checkTokenRegistryIntegrity` is admitted as **one** gate rather than several because S0-1's enforcement column ("Registry existence + import-only check on frozen aliases") and S0-13's ("justification required in the gate's allowlist") both describe registry-artifact assertions, and TA-107 prefers consolidation. Its obligations are: resolution completeness, alias-class conformance (§4), migration-alias expiry (TA-35), mint-record presence (TA-43), seed-source presence (TA-19), registry-side floor conformance (II.1), single-ladder conformance (VII.1), and declared-scope integrity (TA-77). |
| TA-111 | 9.3 | `[LOCAL; CI: IX.4 — a gate that cannot fail on insufficiency must not be claimed to test it]` | `checkTokenRegistryIntegrity` asserts **presence and structure**, never sufficiency. Sufficiency of a justification is a §5.4 review obligation (row I.6, second entry). This limit is recorded so it is never mistaken for coverage. |
| TA-112 | 9.4 | `[LOCAL]` | Constitution Article VI (Status Arbitration) is **not** mapped here. VI.1–VI.3 govern computation, not values; VI.4 places presentation outside constitutional constraint; no §3 category is implicated. Its single-site gate is assigned by VI.1 itself and is outside this ADR's competency. Recorded so the absence reads as a boundary, not an oversight. |
| TA-113 | 9.4 | `[CI: XI.6]` | Constitution Article VIII (Inherited Contracts), Article IX (Evidence), and Article X (Separation) impose authorship obligations discharged by the Constitution's own §6 checklist. This ADR adds no gate to them and claims no ownership of them. |

**Non-clause structures carried in §9 and recorded as present in the source:** the §9.1 enforcement-layer table (L1 Static gate, L2 JVM test, L3 Registry gate, L4 Checklist, L5 Review) and the §9.2 mapping table (28 rows keyed to Constitution clauses I.1 through XII.2). Neither carries a TA identifier.

---

## Section 10 — Appendix

| Clause | § | Marker as authored | Obligation text (verbatim) |
|---|---|---|---|
| TA-114 | Appendix B | `[CI: I.3a; LOCAL as to the mechanism]` | This appendix holds, for each gate in §9, its declared package scope and the timeline for any carried migration alias. It is amended under §7.3 with owner sign-off, and is the sole record of gate scope. |
| TA-115 | Appendix B | `[CI: I.3a; X.6]` | At authoring, Appendix B is **empty of package entries**. Populating it is the first migration under §7 and requires the amendment of §7.3. This ADR declines to populate it because doing so would fix an adoption order (TA-83) and would extend a present-tense prohibition to packages whose migration has not been scheduled, contrary to I.3a. |

**Non-clause structures carried in §10 and recorded as present in the source:** Appendix A (trace table, 10 rows, each tracing a section to Constitution clauses, Architecture Decision items, and local originations) and Appendix C (explicitly-out-of-scope table, 7 rows). Neither carries a TA identifier.

---

## Capture verification

| Check | Result |
|---|---|
| `**TA-n**` clause definitions in source | 115 |
| Clause rows in this inventory | 115 |
| Identifier range | TA-1 … TA-115, contiguous, no gaps |
| `[CI` markers in source | 75 |
| `[LOCAL` markers in source | 54 |
| Obligation text | verbatim, unmodified |
| Markers | recorded as authored; none corrected, re-pointed, or removed |
| Classification, interpretation, disposition, derivation | none performed |

*Historical snapshot. Non-normative. Captured to satisfy S5.*
