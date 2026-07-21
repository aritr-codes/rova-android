# ADR-0039 — Authoring Audit

**Document class:** Audit artifact. **Non-normative.** Nothing in this document is enforceable, and nothing in it governs anything.
**Date:** 2026-07-20.
**Subject:** the disposition of every clause recorded in `docs/adr/0050-design-token-authority-clause-inventory.md` against `docs/adr/0039-cross-surface-token-authority.md`.
**Purpose:** discharges Definition-of-Done items **#6** and **#7** of the frozen authoring specification.

## Standing statements

**ADR-0039 is authoritative.** Where this audit and ADR-0039 differ, ADR-0039 governs and this audit is wrong. This document describes what happened during authoring; it does not interpret, qualify, extend, or narrow any clause of ADR-0039.

**The clause inventory is historical evidence only.** It is a snapshot of an abandoned draft that was never adopted, whose derivation was anchored to a superseded document. No clause in it carries authority, and no disposition below confers any.

**No disposition introduces governance.** Every disposition is a factual record of where a concept went or why it did not travel. A "Dropped" row removes nothing that was in force, because nothing in the inventory was ever in force. A "Carried" or "Re-derived" row creates no obligation; the obligation, where one exists, is created by the ADR-0039 clause named as its destination and by that clause alone.

**Every inventory clause receives exactly one disposition.** Six categories are used, defined as follows:

| Disposition | Meaning |
|---|---|
| **Carried unchanged** | Substance and authority class both survive; only internal cross-references were renumbered. |
| **Re-derived** | Substance survives, but the governing authority changed or the text was rebuilt to remove a citation to an abolished clause. |
| **Merged** | Several inventory clauses became one ADR-0039 clause. Every contributing clause is recorded. |
| **Split** | One inventory clause became several ADR-0039 clauses. Every destination is recorded. |
| **Relocated** | Substance and authority survive, but the clause's home section changed. |
| **Dropped** | The concept does not appear in ADR-0039. A reason is recorded for every instance. |

---

## 1. Disposition register — inventory §2, Registry ownership

| ID | Original § | Disposition | Destination(s) | Rationale |
|---|---|---|---|---|
| TA-1 | 2.1 | Re-derived | 3.1.1 | Sole-source rule survives. Authority moved from an abolished Article to Constitution §4, drafting brief §1; surface scope widened to all repository surfaces by owner decision D2. |
| TA-2 | 2.1 | Carried unchanged | 3.1.2 | Machine-readable, version-controlled registry. Local origination in both documents. |
| TA-3 | 2.1 | Re-derived | 3.1.3 | One canonical form, others generated. Its stated basis was an abolished clause; ADR-0039 derives the prohibition from 3.1.1 directly — a hand-maintained second form is a second source. |
| TA-4 | 2.1 | Re-derived | 3.1.4 | Format, location, serialization not decided. The citation to a mis-resolving Constitution section was removed; the clause now stands on local origination. |
| TA-5 | 2.2 | Carried unchanged | 3.2.1 | Registry Owner role. Local origination in both documents. |
| TA-6 | 2.2 | Re-derived | 3.2.2 | Consumers-only rule. Both cited authorities were abolished; now Constitution §4, drafting brief §1. |
| TA-7 | 2.2 | Re-derived | 3.2.3 | The anti-leak rule survives, re-derived onto Constitution §2, Rule 1 — the one surviving constitutional rule and the direct source of the prohibition. |
| TA-8 | 2.3 | Split | 4.4.4, 4.2, 5.3.2, 8.2 | The enumerated responsibility list ceased to exist as a standalone clause; its items were placed with the clauses that own them — R-a in the role table, R-b in alias policy, R-c in scope amendment, R-d and R-e in the registry gate's assertions. **R-f is not carried:** it required recording a change's evidence class, and Constitution §5.5 records evidence classification as deleted. |
| TA-9 | 2.3 | Re-derived | 3.2.4 | No self-approval. The compound attribution to abolished clauses was replaced by a single authority: repository convention, Independent Review Workflow. |
| TA-10 | 2.4 | Re-derived | 2.2.1 | Property scope. Re-derived as an explicit list from Constitution §4, drafting brief §1, rather than by reference to a taxonomy construct that ADR-0039 does not carry. |
| TA-11 | 2.4 | Re-derived | 2.3.1, 2.3.2 | All-surfaces scope survives, but on an entirely different basis. The inventory clause presented it as inherited from a superseded document's Note on scope; ADR-0039 states it as a decision it originates (owner decision D2) and records it as originated, not inherited. |
| TA-12 | 2.4 | Re-derived | 2.4.1, 2.4.2 | Surface scope versus gate scope. Authority moved from an abolished clause to local origination under owner decision D2, which makes the independence of the two scopes an explicit decision. |
| TA-13 | 2.5 | Carried unchanged | 3.3.1 | Registry versioned as a whole. Local origination in both documents. |
| TA-14 | 2.5 | Re-derived | 3.3.2 | Specifications record the registry version. The "mechanizing" attribution to abolished clauses was removed; local origination. |
| TA-15 | 2.5 | Re-derived | 3.3.3 | The three change classes survive. The Breaking row's citation to an abolished clause was removed. |
| TA-16 | 2.5 | Re-derived | 3.3.4 | The Breaking-change bar survives on repository convention (freeze-and-supersede). Its parenthetical example referenced a type-size floor, which owner decision D6 places outside this ADR; the example was not carried. |
| TA-17 | 2.5 | Re-derived | 3.3.5 | Frozen specifications are superseded, not edited. Authority moved from abolished clauses to repository convention. |
| TA-18 | 2.6 | Re-derived | 4.1.3 | The lifecycle survives as three states in prose rather than a four-node diagram; the "frozen source" node is expressed by 4.1.1 and 4.1.2 instead. |
| TA-19 | 2.6 | Re-derived | 4.1.2 | Seeding is not minting. Authority moved from abolished clauses to Constitution §4, drafting brief §2. |
| TA-20 | 2.6 | Re-derived | 4.5.5 | Removal at zero consumers is implementation. Its Constitution-section citation mis-resolved under the adopted text; now repository convention. |

---

## 2. Disposition register — inventory §3, Token taxonomy

| ID | Original § | Disposition | Destination(s) | Rationale |
|---|---|---|---|---|
| TA-21 | 3 | Re-derived | 2.2.2 | The closure rule survives as "a value outside 2.2.1 is not governed by this ADR", stated against an explicit property list rather than a taxonomy object. |
| TA-22 | 3 | Dropped | — | Adding a category required an amendment to the taxonomy. ADR-0039 carries no category construct: property scope is the Constitution's own list at §4, drafting brief §1. Change control for ADR-0039 itself is out of scope and follows repository ADR-amendment convention. |
| TA-23 | 3 | Dropped | — | Every category naming its governing clause presupposes the category construct, which is not carried. |
| TA-24 | 3.1 | Dropped | — | The clause existed to justify two locally-originated categories holding values that abolished Articles required to be named. Both those Articles and the category construct are gone. |
| TA-25 | 3.1 | Dropped | — | One-token-one-category presupposes the category construct, which is not carried. |
| TA-26 | 3.1 | Re-derived | 2.2.3 | The disclaimer survives — this ADR decides no value, no number of values, and no relationship between values — re-stated without the category framing. |
| **C1–C10** | 3.1 | Dropped | — | *(Non-clause table rows, inventoried with markers.)* The ten-category taxonomy is not carried. Eight rows restated the Constitution's own property list, which ADR-0039 states directly at 2.2.1. C9 and C10 held values that owner decision D6 places with their Constitution §5.2 carriers. |

---

## 3. Disposition register — inventory §4, Alias policy

| ID | Original § | Disposition | Destination(s) | Rationale |
|---|---|---|---|---|
| TA-27 | 4.1 | Carried unchanged | 4.2.1 | An alias is a name, never a value. Local origination in both documents; the minting-backdoor closure survives verbatim in substance. |
| TA-28 | 4.1 | Carried unchanged | 4.2.2 | Alias creation is not a mint. Only internal cross-references were renumbered. |
| TA-29 | 4.1 | Carried unchanged | 4.2.3 | One-hop resolution. |
| TA-30 | 4.1 | Re-derived | 4.2.4 | Recorded fields survive. The trailing "is defective" declaration was not restated in the clause; the equivalent assertion is made by the registry gate at 8.2, so the obligation is enforced rather than declared twice. |
| TA-31 | 4.3 | Re-derived | 4.2.6 | Legacy aliases may be permanent. Authority moved from abolished clauses to repository convention (freeze-and-supersede). |
| TA-32 | 4.3 | Merged | 7.1.3, 4.2.5 | Merged with TA-60 into a single consumption rule covering all three alias classes, and reflected in the alias-class table's citability column. |
| TA-33 | 4.4 | Re-derived | 4.2.7 | Mandatory expiry survives. Its authority — an abolished clause requiring a stated timeline — no longer exists, so ADR-0039 originates it locally. |
| TA-34 | 4.4 | Merged | 4.2.7 | Merged with TA-33 into one clause: a migration alias records an expiry and shall not be permanent. |
| TA-35 | 4.4 | Re-derived | 4.2.8 | Expiry is enforced by the registry gate. Authority moved from abolished clauses to local origination; the enforcement point is named at 8.2. |
| TA-36 | 4.4 | Re-derived | 4.2.9 | Extending an expiry requires the mint reviewers and a recorded reason. The requirement to record it in an evidence class was not carried — Constitution §5.5 records evidence classification as deleted. |
| TA-37 | 4.5 | Carried unchanged | 4.5.2 | A deprecated entry acquires no new consumer; a new citation is a gate failure. |
| TA-38 | 4.5 | Merged | 4.5.1 | Merged with TA-97, which already required the replacement or an explicit statement that there is none. |
| TA-39 | 4.5 | Merged | 4.5.3 | Merged with TA-99. Both stated that an entry with a live frozen-specification consumer is not removed until that specification is superseded. |
| **Class definitions** | 4.2–4.5 | Merged | 4.2.5 | *(Non-clause definitions, inventoried with markers.)* The four prose class definitions became one table. The canonical-token class is not an alias class in ADR-0039 and is expressed by 4.1.3's lifecycle instead. |

---

## 4. Disposition register — inventory §5, Minting policy

| ID | Original § | Disposition | Destination(s) | Rationale |
|---|---|---|---|---|
| TA-40 | 5.1 | Split | 4.3.1, 4.3.2 | Separated into the default rule (migration) and the exception (minting), so each states one obligation. Authority now Constitution §4, drafting brief §2. |
| TA-41 | 5.1 | Re-derived | 4.4.2 | Refusal grounds survive, re-derived from six to five. Grounds requiring demonstration at accessibility parameters and forbidding a value below a constitutional floor were not carried — owner decision D6 places both invariants with their Constitution §5.2 carriers. The category-existence ground became a property-scope ground. |
| TA-42 | 5.1 | Re-derived | 4.3.3 | A finding is not a justification. Reworded to drop the "frozen ladder" framing, which presupposed a ladder construct ADR-0039 does not carry. |
| TA-43 | 5.2 | Re-derived | 4.4.1 | The mint record survives, re-derived from nine items to six. **Four items were not carried:** the evidence class of the reason and the load-bearing-assumption label, because Constitution §5.5 records evidence classification as deleted; and the accessibility demonstration and contrast-sweep entry, because owner decision D6 places both invariants with their Constitution §5.2 carriers. TA-46 merged in as item (d). |
| TA-44 | 5.2 | Dropped | — | A rule about not inflating an evidence class. Constitution §5.5 records evidence classification as deleted, so there are no classes to inflate. |
| TA-45 | 5.2 | Dropped | — | A rule permitting reliance on a labelled evidence class. Same reason as TA-44. |
| TA-46 | 5.3 | Merged | 4.4.1 | The four questions a justification must answer became item (d) of the mint record. |
| TA-47 | 5.3 | Dropped | — | Forbade a justification resting on an unfalsifiable criterion, a conformance count, or a self-authored rubric. Constitution §5.5 records falsifiable success criteria and rubric-before-candidates as deleted. |
| TA-48 | 5.3 | Dropped | — | Required a rubric written before candidates are scored. Same reason as TA-47. |
| TA-49 | 5.4 | Re-derived | 4.4.4 | The four-role table survives. Authority consolidated onto repository convention (Independent Review Workflow). The owner sign-off row's reference to an internal amendment section was **not** carried: change control for ADR-0039 is out of scope and follows repository ADR-amendment convention, so the row is scoped to Breaking changes alone. |
| TA-50 | 5.4 | Dropped | — | Verdict semantics — a NO-GO blocks, a GO WITH FIXES requires reconciliation, reconciliation is the requester's obligation. These are repository convention, stated in `CLAUDE.md`'s Independent Review Workflow. 4.4.4 invokes that convention; restating it here would duplicate governance the repository already holds. |
| TA-51 | 5.5 | Split | 4.4.1, 5.3.1 | Separated into the mint record's gate-scope item and the scope-expansion requirement, so the obligation sits with each act rather than being stated once for both. |
| TA-52 | 5.5 | Merged | 4.4.2 | Became refusal ground (e): a mint introducing an invariant whose enforcing gate is unnamed is refused. |
| TA-53 | 5.5 | Re-derived | 4.4.7 | A gate is not weakened to admit a mint. Authority moved from an abolished clause to repository convention. |
| TA-54 | 5.6 | Re-derived | 4.4.5 | Migration statement for redundant values. Authority moved from an abolished clause to Constitution §4, drafting brief §2. |
| TA-55 | 5.6 | Merged | 4.4.5, 4.4.2 | Merged with TA-54 as the refusal half of one clause, and reflected as refusal ground (c). |
| TA-56 | 5.6 | Re-derived | 4.4.6 | Un-migrated consumers carried on migration aliases, never silently. Authority moved from an abolished clause to local origination. |

---

## 5. Disposition register — inventory §6, Consumption policy

| ID | Original § | Disposition | Destination(s) | Rationale |
|---|---|---|---|---|
| TA-57 | 6.1 | Re-derived | 7.1.1 | Specifications express in-scope properties as token names. Authority moved from abolished clauses to Constitution §4, drafting brief §1. |
| TA-58 | 6.1 | Merged | 3.3.2 | Merged with TA-14. Both required a specification to record the registry version it froze against; ADR-0039 states it once, in the versioning section. |
| TA-59 | 6.1 | Carried unchanged | 7.1.2 | The registry prevails over a restated value; the specification is defective, not authoritative. |
| TA-60 | 6.1 | Carried unchanged | 7.1.3 | Specifications cite canonical names. TA-32 merged in to cover all three alias classes. |
| TA-61 | 6.1 | Re-derived | 7.1.4 | A specification requiring an absent value does not proceed. Authority moved from abolished clauses and a mis-resolving section reference to Constitution §4, drafting brief §2. |
| TA-62 | 6.2 | Re-derived | 7.1.5 | The specification prohibitions survive, re-derived from nine to six. The second-motion-ladder prohibition folded into the private-scale prohibition, since motion duration and easing are properties in 2.2.1. The permanent-divergence prohibition is stated at 7.3.5 with the migration rules it belongs to. **The below-a-floor prohibition was not carried** — owner decision D6 places type-size and target-size floors with their Constitution §5.2 carriers. |
| TA-63 | 6.2 | Re-derived | 7.1.6 | A specification is not frozen while the prohibitions are unsatisfied. Its authority included the Constitution's authorship checklist, which Constitution §5.5 records as deleted; now local origination. |
| TA-64 | 6.3 | Re-derived | 7.2.1 | Implementation transcribes. Its mis-resolving section citation was replaced by repository convention (HTML-first design workflow). |
| TA-65 | 6.3 | Re-derived | 7.2.2 | No substitution, approximation, or different token. Authority moved to Constitution §4, drafting brief §3. |
| TA-66 | 6.3 | Re-derived | 7.2.3 | Implementation stops on an unresolvable citation. Authority consolidated onto repository convention (HTML-first design workflow). |
| TA-67 | 6.3 | Re-derived | 7.2.4 | Mechanical details only. Mis-resolving citation replaced by repository convention. |
| TA-68 | 6.3 | Re-derived | 7.2.5 | Rename, enforcement-preserving mechanism change, and dead-token removal are implementation. Authority moved from an abolished clause and a mis-resolving section reference to repository convention. |

---

## 6. Disposition register — inventory §7, Migration policy

| ID | Original § | Disposition | Destination(s) | Rationale |
|---|---|---|---|---|
| TA-69 | 7.1 | Re-derived | 7.3.1 | The outcome set survives, re-derived from four to three. **The "Retire" outcome was not carried:** every condition it enumerated — a value below a floor, an unswept hue, a shadow over media, a simulated blur — is a Constitution §5.2 invariant that owner decision D6 leaves with its named carrier. |
| TA-70 | 7.1 | Re-derived | 7.3.2 | No further outcome; "left as-is, untracked" is not one. Restated against the three-outcome set. |
| TA-71 | 7.1 | Dropped | — | Made retirement a constitutional obligation by enumerating six abolished Articles. Owner decision D6 places every invariant it named with its Constitution §5.2 carrier, and none of those Articles exists in the adopted text. |
| TA-72 | 7.1 | Re-derived | 7.3.3 | A migration alters no visual outcome. Authority moved to repository convention (HTML-first). The exception for outcomes "defective under a Constitutional rule" was not carried, since the rules it depended on are Constitution §5.2 invariants owned elsewhere; ADR-0039 states the rule without exception. |
| TA-73 | 7.1 | Dropped | — | Required a zero-pixel claim to be demonstrated at accessibility parameters. Those parameters are a Constitution §5.2 invariant that owner decision D6 leaves with its named carrier. |
| TA-74 | 7.2 | Re-derived | 7.3.4 | Partial migration is permitted and is the expected shape. Authority moved from an abolished clause to Constitution §4, drafting brief §2. |
| TA-75 | 7.2 | Re-derived | 7.3.5 | Tracked residue required; untracked residue is a fork. Authority moved from an abolished clause to local origination. Also carries the substance of the inventory's permanent-divergence prohibition. |
| TA-76 | 7.2 | Carried unchanged | 7.3.6 | The partial-versus-fork test: tracked and dated, versus untracked or undated. |
| TA-77 | 7.3 | Split | 5.2.1, 5.2.2 | Separated into the rule (scope is a declared list, never implicit) and the record (Appendix B is its sole home). Authority moved from abolished clauses to local origination. |
| TA-78 | 7.3 | Re-derived | 5.3.1 | Scope-expansion requirements survive. Authority moved from abolished clauses to local origination; the requirement to record the list in two places was reduced to Appendix B alone. |
| TA-79 | 7.3 | Carried unchanged | 5.3.2 | Scope expansion is an Appendix B amendment with owner sign-off, not a unilateral act and not an implementation detail. |
| TA-80 | 7.3 | Re-derived | 5.3.3 | Expansion changes only which packages a gate reads. Authority moved from an abolished clause to repository convention (mechanism-versus-enforcement). |
| TA-81 | 7.3 | Re-derived | 5.2.3 | Until a package is declared, the prohibition states a target. Authority moved from an abolished clause to local origination under owner decision D2. |
| TA-82 | 7.4 | Re-derived | 5.4.1 | The adoption test survives. Its composition from three abolished clauses was replaced by local origination. |
| TA-83 | 7.4 | Carried unchanged | 5.4.3 | Package ordering is not decided here; it is scheduling, recorded in Appendix B. |
| TA-84 | 7.4 | Re-derived | 5.4.2 | Adoption is not blocked on another package, on a direction, or on a freeze. Authority moved from an abolished clause and an Architecture Decision citation to local origination — the Architecture Decision is evidence, never authority. |
| TA-85 | 7.4 | Re-derived | 5.3.4 | An adopted package does not regress. Authority moved from an abolished clause to repository convention. |

---

## 7. Disposition register — inventory §8, Governance

| ID | Original § | Disposition | Destination(s) | Rationale |
|---|---|---|---|---|
| TA-86 | 8.1 | Merged | 4.4.4 | Merged with TA-49. The inventory stated the role table twice, in minting and again in governance; ADR-0039 states it once. |
| TA-87 | 8.1 | Merged | 4.4.4 | Role exclusivity became the lead sentence of the same table: four distinct roles, no two filled by the same context. |
| TA-88 | 8.2 | Split | 4.4.4, 4.2.9, 5.3.2 | The list of acts requiring full review was distributed to the acts themselves — minting and Breaking changes at 4.4.4, expiry extension at 4.2.9, scope expansion at 5.3.2. **Amendment of this ADR is not among them:** change control for ADR-0039 is out of scope and follows repository ADR-amendment convention. |
| TA-89 | 8.2 | Split | 4.5.5, 7.2.5 | The list of acts that are implementation was distributed to the sections that own them — deprecated-entry removal in the registry lifecycle, rename and enforcement-preserving mechanism change in consumption. |
| TA-90 | 8.2 | Dropped | — | Forbade admitting a change on the strength of a summary. This is repository convention — the Independent Review Workflow already requires a reviewer to validate repository state rather than trust a summary. Restating it would duplicate governance the repository holds. |
| TA-91 | 8.3 | Dropped | — | Enumerated what requires an amendment to the ADR. Change control for ADR-0039 itself is out of scope; the repository's ADR-amendment convention governs it. |
| TA-92 | 8.3 | Dropped | — | Stated amendment requirements. Same scope reason as TA-91, and it additionally routed to a Constitution amendment process that Constitution §5.5 records as deleted and required an evidence class that the same clause deletes. |
| TA-93 | 8.3 | Dropped | — | Forbade an amendment introducing a design direction. Constitution §2, Rule 1 binds ADR-0039 and any amendment to it directly; restating the prohibition would duplicate the one surviving constitutional rule. |
| TA-94 | 8.3 | Relocated | §0 header | The Constitution-prevails rule survives verbatim in substance, moved from a governance clause to the header's derivation statement, where it governs the whole document rather than only its amendment. Authority is now Constitution §3. |
| TA-95 | 8.3 | Dropped | — | Split amendment weight by clause-marker class. Owner decision D4 carries no clause-marker system into ADR-0039, so there is no class distinction to amend against. |
| TA-96 | 8.3 | Dropped | — | Required a superseding ADR for a scope change. Change control for ADR-0039 is out of scope, and freeze-and-supersede is repository convention. |
| TA-97 | 8.4 | Carried unchanged | 4.5.1 | The deprecation record: reason, replacement or an explicit statement of none, and the current consumer list. TA-38 merged in. |
| TA-98 | 8.4 | Split | 4.5.2, 4.5.5 | A summary clause restating three obligations. Its content sits with the clauses that state them: no new consumers, and removal at zero consumers. |
| TA-99 | 8.4 | Re-derived | 4.5.3 | A deprecated entry with a live frozen-specification consumer is not removed, and deprecation does not authorize editing a frozen specification. Authority moved from an abolished clause to repository convention. TA-39 merged in. |
| TA-100 | 8.4 | Carried unchanged | 4.5.4 | Deprecation is not used to effect a value change; that is a Breaking change. |
| TA-101 | 8.5 | Dropped | — | **Owner decision D7.** The emergency exception's triggering circumstance. The adopted Constitution contains no emergency provision and ADR-0039 originates none; registry change under time pressure follows the ordinary process and the repository's ADR-amendment convention. |
| TA-102 | 8.5 | Dropped | — | **Owner decision D7.** The envelope's permitted act (migration alias only). |
| TA-103 | 8.5 | Dropped | — | **Owner decision D7.** The gate-inviolability clause within the envelope. The underlying obligation is not lost: ADR-0039 states it unconditionally at 8.3.3, with no emergency to except. |
| TA-104 | 8.5 | Dropped | — | **Owner decision D7.** The envelope's mandatory expiry. |
| TA-105 | 8.5 | Dropped | — | **Owner decision D7.** Retroactive review of an exercised exception. |
| TA-106 | 8.5 | Dropped | — | **Owner decision D7.** The prohibition on an exception resolving a visual question. The underlying separation is preserved by 3.2.3 and 4.4.2(b), which admit no exception. |

---

## 8. Disposition register — inventory §9, Gate mapping

| ID | Original § | Disposition | Destination(s) | Rationale |
|---|---|---|---|---|
| TA-107 | 9 | Dropped | — | Preferred extending a gate over creating one. Both cited authorities are abolished, and no gate in ADR-0039 is an extension — 8.1.2 states that all three are new. Retained, it would state a gate-authoring policy beyond registry governance. |
| TA-108 | 9 | Merged | 5.2.1, 5.2.2 | Merged with TA-77 into the declared-scope rule and its Appendix B record. |
| TA-109 | 9.3 | Re-derived | 8.1.2, 8.2 | The count of originated gates survives as a per-row status. ADR-0039 records three gates, two named by Constitution §4 (drafting briefs §3 and §4) and one originated, and states that none is an extension of an existing gate. |
| TA-110 | 9.3 | Re-derived | 8.2 | The registry gate's consolidated obligation list survives, re-derived. Registry-side floor conformance and single-ladder conformance were not carried — owner decision D6 places both with their Constitution §5.2 carriers. A deprecated-consumer assertion was derived from 4.5.2. |
| TA-111 | 9.3 | Carried unchanged | 8.3.1 | The gate asserts presence and structure, never sufficiency; sufficiency is a review obligation. |
| TA-112 | 9.4 | Dropped | — | Declined to map status arbitration, on the authority of a clause the adopted Constitution does not contain. **Owner decision D3 assigns the single status arbiter to this ADR**, so the boundary this clause drew has no application. ADR-0039 §6 owns the subject. |
| TA-113 | 9.4 | Dropped | — | Disclaimed ownership of three Articles whose obligations were discharged by the Constitution's authorship checklist. Constitution §5.5 records that checklist as deleted, and the Articles do not exist in the adopted text. |
| **§9.1 layer table** | 9.1 | Dropped | — | *(Non-clause table, inventoried as present.)* The five-layer enforcement model is not carried. Its L4 layer was defined as the Constitution's authorship checklist, which Constitution §5.5 records as deleted. ADR-0039 §8 names gates directly. |
| **§9.2 mapping table** | 9.2 | Re-derived | 8.2 | *(Non-clause table, inventoried as present.)* Twenty-eight rows keyed to abolished Articles became three rows keyed to the invariants ADR-0039 introduces. Owner decision D6 removes the rows for Constitution §5.2 invariants; owner decision D3 adds the arbiter row. |

---

## 9. Disposition register — inventory §10, Appendix

| ID | Original § | Disposition | Destination(s) | Rationale |
|---|---|---|---|---|
| TA-114 | Appendix B | Re-derived | Appendix B (lead) | Appendix B holds each gate's declared scope and is its sole record. Authority moved from an abolished clause to local origination; the amendment reference was retargeted to 5.3.2. |
| TA-115 | Appendix B | Re-derived | Appendix B (second paragraph) | Appendix B is empty of package entries at authoring. The two reasons survive, re-derived from ADR-0039's own clauses — 5.4.3 reserves adoption order to scheduling, and 5.2.3 forbids extending a present-tense prohibition to an unscheduled package — rather than from the abolished clauses the inventory cited. |
| **Appendix A** | Appendix A | Re-derived | Appendix A | *(Non-clause structure, inventoried as present.)* The trace survives but is rebuilt: from ten section-granularity rows tracing to abolished Articles, to a clause-granularity table covering all 91 clauses against four authority classes. Owner decision D4 makes it the sole provenance mechanism. |
| **Appendix C** | Appendix C | Re-derived | Appendix C | *(Non-clause structure, inventoried as present.)* The out-of-scope register survives, re-derived against the adopted Constitution's dispositions and the resolved owner decisions. |

---

## 10. ADR-0039 content with no inventory antecedent

Recorded for completeness. These clauses are not dispositions of anything in the inventory; they were derived directly and are listed so the register is not mistaken for a complete account of ADR-0039.

| ADR-0039 clause(s) | Source |
|---|---|
| 2.1.1, 2.1.2 | Constitution §5.1 and §5.2 — the subject assignment and the exclusion of the backlog invariants |
| 2.5 (non-scope table) | The resolved owner decisions and the Constitution's dispositions |
| 6.1.1 | Constitution §4, drafting brief §4. The inventory declined this subject at TA-112, so no clause of §6 descends from the inventory |
| 6.1.2, 6.1.3 | Local origination. 6.1.2 records that the uniqueness in 6.1.1 attaches to the computation, not to the number of queries over it |
| 6.2.1 – 6.2.5 | Constitution §6, C2, and owner decision D3a — the arbiter-layer scoping, the routing-layer boundary, the no-migration statement, the precedence-content referral, and the presentation disclaimer |
| 8.1.1 – 8.1.3 | Repository gate convention, and the stated limit that an unbuilt gate enforces nothing |
| 8.2 (arbiter row) | Constitution §4, drafting brief §4, scoped by owner decision D3a |
| 8.3.2, 8.3.3 | Local origination, and repository convention on gate integrity |
| 9 (Consequences) | Non-normative |

---

## 11. Verification

| # | Check | Method | Result |
|---|---|---|---|
| 1 | Every inventory identifier appears exactly once | TA-1 … TA-115 enumerated across sections 1–9; each ID appears in exactly one register row | **115 / 115** — contiguous, no gaps, no duplicates |
| 2 | Every disposition category is valid | Each row's disposition drawn from the six defined categories | **Pass** — no other value used |
| 3 | Every ADR-0039 destination exists | Each destination checked against ADR-0039's 91 numbered clauses, its §0 header block, its §8.2 table, and Appendices A–C | **Pass** — no destination is unresolvable |
| 4 | No inventory clause is left without disposition | Every TA row carries exactly one disposition; every "Dropped" carries a reason | **Pass** |
| 5 | Non-clause inventoried structures dispositioned | C1–C10, the four alias-class definitions, the §9.1 layer table, the §9.2 mapping table, Appendix A, Appendix C | **6 / 6** dispositioned |

### Disposition totals

| Disposition | Count | Identifiers |
|---|---|---|
| **Carried unchanged** | 15 | TA-2, 5, 13, 27, 28, 29, 37, 59, 60, 76, 79, 83, 97, 100, 111 |
| **Re-derived** | 55 | TA-1, 3, 4, 6, 7, 9, 10, 11, 12, 14, 15, 16, 17, 18, 19, 20, 21, 26, 30, 31, 33, 35, 36, 41, 42, 43, 49, 53, 54, 56, 57, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 72, 74, 75, 78, 80, 81, 82, 84, 85, 99, 109, 110, 114, 115 |
| **Merged** | 11 | TA-32, 34, 38, 39, 46, 52, 55, 58, 86, 87, 108 |
| **Split** | 7 | TA-8, 40, 51, 77, 88, 89, 98 |
| **Relocated** | 1 | TA-94 |
| **Dropped** | 26 | TA-22, 23, 24, 25, 44, 45, 47, 48, 50, 71, 73, 90, 91, 92, 93, 95, 96, 101, 102, 103, 104, 105, 106, 107, 112, 113 |
| **Total** | **115** | |

15 + 55 + 11 + 7 + 1 + 26 = **115.**

### Grounds for the 26 drops

| Ground | Count | Identifiers |
|---|---|---|
| Owner decision **D7** — no emergency exception | 6 | TA-101 – TA-106 |
| Owner decision **D6** — invariant remains with its Constitution §5.2 carrier | 2 | TA-71, TA-73 |
| Owner decision **D4** — no clause-marker system | 1 | TA-95 |
| Owner decision **D3** — this ADR owns the arbiter, so the declining boundary has no application | 1 | TA-112 |
| Constitution §5.5 — evidence classification, falsifiable criteria, rubric-before-candidates deleted | 4 | TA-44, 45, 47, 48 |
| Constitution §5.5 — authorship checklist deleted, and the Articles it discharged do not exist | 1 | TA-113 |
| Out of scope — change control for this ADR follows repository ADR-amendment convention | 4 | TA-91, 92, 96, and TA-93 (Rule 1 binds directly) |
| Duplication — the obligation is repository convention and is invoked rather than restated | 2 | TA-50, TA-90 |
| Construct not carried — the category taxonomy | 4 | TA-22, 23, 24, 25 |
| No application — no gate in ADR-0039 is an extension | 1 | TA-107 |

### Standing confirmations

- **Every inventory clause has exactly one recorded disposition.** Verified at check 1 and by the totals above.
- **No disposition introduces new governance.** Every row records where a concept went or why it did not travel. Obligations exist only in ADR-0039, created by the clause named as a destination.
- **ADR-0039 is treated as authoritative.** This audit describes authoring; it does not interpret, qualify, or extend any ADR-0039 clause. Where the two differ, ADR-0039 governs.
- **The inventory is treated as historical evidence only.** No clause in it carried authority at any point, and no disposition confers any.

---

*Audit artifact. Non-normative. Records disposition only. Discharges Definition-of-Done items #6 and #7.*
