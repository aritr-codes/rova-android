# Warnings + Recovery — Design Foundation (Phase 1)

**Date:** 2026-07-09
**Status:** Phase 1 — Design Foundation. **Awaiting owner approval before HTML is written.**
**Scope:** The Trust System (Warnings + Recovery), designed as one surface family.
**Design direction source:** `docs/superpowers/reviews/2026-07-09-product-design-surface-review.md` (reconciled; treated as settled).
**Binding prior art:** ADR-0007 (sheet/chip/banner), ADR-0013 (warning re-skin v3), ADR-0014 (snooze persistence), ADR-0015/0016 (echoes), ADR-0017/0018 (recovery merge), ADR-0019 (thermal hysteresis), ADR-0020 (AA by default), ADR-0025 (vault), ADR-0028 (Liquid Glass / locked `RovaSemantics`), ADR-0030 (Library method), ADR-0031 (icon system), `docs/WarningCenterContract.md` (NO-GO list).

> **This document contains no HTML and no Kotlin.** It establishes the foundation the frozen HTML specification will be built on. Compose does not exist yet.

> **Reconciled after independent review (verdict: GO WITH FIXES, 2026-07-09).** The reviewer verified the entire §1 inventory as true (21 enum values, 5 dead tokens, the static ring, recency data, the color hexes, the preserved hard invariant) and falsified two proposals, both now reversed: **(RF1)** routing locked severity CTAs through `DialogActionColors.resolve` would have silently darkened 3 of 4 locked severity fills and flipped an AA-proven label — the existing near-black-on-bright-fill is the *correct answer*, not debt (§6.5). **(RF2)** the warning sheet is a `ModalBottomSheet` with its own scrim and opaque container, so it is not over-media; the banner is already 88% opaque, so a structural-scrim primitive is unwarranted (§6.3). Also corrected: the 17-vs-21 count conflation (§1, Q4), the `checkStatusColorLocked` alias hazard (Q2), and one substantive omission — the raw-exception string shown to users on merge failure (Q6).

---

## 0. What this freeze is, and is not

**It is:** bringing the Trust System to the Library's *methodological* standard — one color entry point, tested contrast contracts, pure seams, a single radius/motion ladder, total reduced-motion gating, honest affordances.

**It is not:** making Warnings and Recovery look like the Library. The bento aesthetic (media tiles, ground-wash headers, thumbnail-first density) is wrong for a surface whose job is to interrupt calmly and explain. **We adopt the method, not the appearance.**

**Non-goals, explicitly:**
- No new warning states. `WarningCenterContract.md` NO-GO #1 binds.
- No warnings hub screen. NO-GO #9 binds.
- No `Modifier.blur` on the banner background. NO-GO #3 binds.
- No new `SessionManifest` field. NO-GO #4 binds. *(The recency work below is designed to satisfy this.)*
- No `Modifier.blur` on the record surface (`checkRecordSurfaceNoBlur`).
- No change to precedence, gating, snooze semantics, thermal hysteresis, or the recovery merge state machine.
- No increase in visual density. No novelty. No new features.

---

## 1. Verified ground truth (from source, 2026-07-09)

Design must start from facts, not the last document. Inventory findings that change the design:

| Fact | Evidence | Consequence for this freeze |
|---|---|---|
| `WarningId` has **21** values | `ui/warnings/WarningId.kt:27-50` | **`CLAUDE.md`'s "17 `WarningId` values" is genuinely stale.** `WarningCenterContract.md`'s "17 rows" is *not* — it counts **precedence-resolver rows**, and its own line-16 note explains the axis (18 logical states; `CANT_MERGE` rides a transient `RecoveryCardState` field, and several enum values are contract-"missing" producers with no precedence row). Three different, all-correct counts. The HTML state gallery enumerates all 21 and states which axis each count measures. |
| **5** dead tokens, not 2 | `RovaWarningsV3.kt` L22-24, L31-32 | `sheetTitleSize`, `sheetBodySize`, **plus** `sheetHandleWidth`, `sheetHandleHeight`, `sheetHandleAlpha` — dead because the sheet uses stock `BottomSheetDefaults.DragHandle()` (`WarningSheetV3.kt:116`). All 5 die at freeze. |
| Banner `CountdownRing` is a **static placeholder** | `WarningTopBannerV3.kt:126,184-186` — `totalSeconds=30` hardcoded, `secondsRemaining` never decrements | A ring that depicts a countdown but never counts is a **trust defect**, not a visual one. Must become truthful or be removed. See §12 Q1. |
| `PrimaryCta` uses a **solid** severity fill with a `Color(0xFF1A1A1A)` near-black label | `WarningSheetV3.kt:377-399`; AA rationale documented at `:393-397` (WARN-02: white-on-accent read 1.67–3.76:1 and failed; near-black clears 4.5:1 on **all four** severity fills) | **This is a tested, working AA solution — NOT debt. It stays.** *(Corrected after independent review; see §6.5.)* |
| Both container backgrounds are the same raw hex at two alphas | `0xFF0B0D14`@0.94 (`RecoveryCard.kt:107`), `0xFF0B0D14`@0.88 (`WarningTopBannerV3.kt:71`) | Palette-blind; becomes a named token. The banner at **88% opacity** admits only 12% video bleed — a far easier contrast case than the Library's caption-over-thumbnail. |
| Banner sub-text `White@0.48f` carries **no** AA rationale | `WarningTopBannerV3.kt:117` — unlike sheet body (0.55α, documented ≈5.34:1) and recovery body (0.65α, documented) | The one genuine SC 1.4.3 suspect. Resolved by test, never by eye (§9). |
| Recency data **already exists** | `SessionManifest.startedAt` (non-null), `terminatedAt` (nullable); `RecoveryUiState.recencyKey` already computed for sort then discarded (`RecoveryUiState.kt:179-180`) | Recovery recency needs **no schema change** — NO-GO #4 satisfied. It needs a pure clock seam. |
| Three overlapping definitions of the same red | `RovaWarnings.hard` `#EF4444` · `RovaSemantics.error` · bento `--danger:#EF4444` | One locked semantic home. See §6 and §12 Q2. |
| Severity glyphs deliberately **bypass** `SemanticIcon` | ADR-0031 §4 exception, verbatim: *"Do not 'fix' this into `SemanticIcon`."* | **Preserved.** Severity tint is the warning's primary signal and is finer-grained than `IconStatus`. |
| Warnings/Recovery composables have **no covering tests** | CodeGraph blast-radius; only pure mappers + `RovaWarningsV3Test` | The freeze must define pure seams so the contract is testable (§11). |
| `minHitTarget = 48.dp` exists and is referenced by **zero** warnings/recovery composables | `RovaTokens.kt:31` | Every fixed target here (46/40/28dp) is below the house standard. |

---

## 2. Design principles

Six, in priority order. When two conflict, the earlier wins.

1. **Truthful before beautiful.** No affordance may depict a state the system does not have. A countdown ring must count. A progress strip must measure what it claims. A severity color must mean its severity. *(This principle alone kills the static ring and the artifact-count-as-segment-count proxy.)*
2. **Calm is refusal.** Restraint is achieved by removing, not softening. Reserve red for genuinely destructive or genuinely blocking. Never guilt the user for a condition they did not cause — a device that overheated is not the user's failure.
3. **State the condition, then offer one action.** Every trust surface answers exactly two questions: *what happened* and *what do I do*. Additional actions are subordinate, never co-equal.
4. **Disruptiveness matches severity.** The loudest surface (a non-dismissible modal) is reserved for conditions that genuinely block. Everything else de-escalates to banner → chip → inline row.
5. **One method, two substrates.** Every color, radius, and duration has exactly one source. But the Trust System renders over *two different substrates* (arbitrary video frames, and app surfaces) and must carry a proven contract for each.
6. **Adopt the Library's method, never its appearance.** Single color entry point; identity-vs-locked split as a tested contract; pure JVM-testable seams; frozen-spec provenance with "do not re-derive."

---

## 3. Trust philosophy

Rova is a **hands-free background recorder**. The user is, by definition, not watching. The Trust System is the entire relationship the user has with Rova at the moment something went wrong — it is where the product's core promise is either honored or broken.

Three commitments:

**3.1 — Never overstate, never understate.**
The system reports what it knows, at the precision it knows it. If it recovered artifacts but cannot say how many *segments* they represent, it says what it can prove. Truthful degradation is the same law that governs the merge validity predicate (ADR-0005 §Merge Admission): a frameless artifact is rejected rather than baked in as a false frozen clip. The UI obeys the same law.

**3.2 — The user's data is never at risk from the UI.**
Destructive actions are always last, never primary, never default, never adjacent-by-accident. Discard is the terminal option in every recovery CTA stack — this is already true and is now an invariant, not a habit. Nothing irreversible happens without a distinct, deliberate act.

**3.3 — An interruption is a report, not an alarm.**
"Session interrupted" is a statement of fact with a remedy attached. It is not an error, not a failure, not the user's fault. The visual language must not borrow the vocabulary of alarm (saturated red fields, pulsing chrome, exclamation) for conditions that are merely *recoverable*. Red is earned by blocking or destroying — not by "something happened."

**Corollary — recency is a trust obligation.** A recovery card with no time context asks the user to trust a claim about their past they cannot situate. "Interrupted · 2 hours ago" is not decoration; it is the evidence that makes the claim checkable.

---

## 4. Interaction principles

**4.1 — The severity → disruptiveness ladder** *(preserves ADR-0007; externally validated by Carbon / PatternFly / NN-g)*

| Surface | Reserved for | Dismissible | Host |
|---|---|---|---|
| **Modal sheet** (non-dismissible) | Start-gating hard blocks only: `CAMERA_PERMISSION_DENIED`, `STORAGE_INSUFFICIENT` | No | Record |
| **Modal sheet** (dismissible) | Non-gating hard-block, soft, advisory | Yes → collapses to chip | Record |
| **Top banner** | Mid-session, persistent, non-blocking (thermal, storage-low, echoes) | Per contract | Record (HUD active) |
| **Chip** | Collapsed/snoozed state of a dismissed sheet | Tap → restores sheet | Record |
| **Inline card** | Multi-active, host-owned list | Yes (X) | Library/History |
| **Inline chip** | Multi-active, status mirror | **No** — clears only when the signal clears | Settings |
| **Recovery card** | A terminal artifact of a past session | Yes (explicit action) | Library |

Never a toast for anything critical. Nothing that blocks auto-dismisses.

**4.2 — Cross-host unification: unify the atom, not the behavior.**

The review named "cross-host divergence" as a defect. The *correct* fix is narrow. Today the three hosts diverge in **chrome** (radii 26/18/8/6; fill-border alpha pairs 0.10-0.30 / 0.08-0.25 / 0.88-0.30; icon-glyph on Record vs text-badge on History/Settings) while diverging **legitimately** in behavior (Record = single-active + snooze; History = multi-active + dismissible; Settings = multi-active + non-dismissible).

Therefore:
- **Unify:** one `TrustRow` anatomy — *severity mark → title → [body] → [action] → [dismiss]* — shared by the sheet header, banner, history card, and settings chip. One radius ladder. One fill/border/state-layer scale. One severity mark treatment (dot **+** label **+** glyph; never color alone).
- **Preserve:** per-host containers and per-host dismissibility exactly as `WarningCenterContract.md` §6 specifies. The hard invariant `activeWarning == allActive(...).firstOrNull()` is untouched.

This resolves the visual divergence without collapsing three different, correct behavioral contracts into one wrong one — and without creating the hub screen NO-GO #9 forbids.

**4.3 — Action hierarchy.** Exactly three CTA roles, everywhere:
- **Primary** — filled, accent or severity, AA-resolved label. Exactly one per surface, or zero.
- **Ghost** — hairline border, no fill. Any number.
- **Destructive** — reserved. Always terminal in the stack. Never the primary slot.

**4.4 — Preserved verbatim** (proven, do not redesign): durable snooze (ADR-0014); idle-echo promotion (`effectiveIdleTopBannerId`); the adaptive recovery CTA stack (merge-in-flight disables Merge+Keep, keeps Discard live; merge-fail flips primary to Retry); newest-card-only + `hiddenCount` collapse; per-OEM `VendorGuidanceIntents` with `resolveActivity` gating and graceful fallback; all copy in `@StringRes`/`UiText`.

---

## 5. Spacing system

**Base grid: 4dp.** Every value is a multiple of 4, except the radius ladder and hairlines.

**Radius ladder — one step of radius = one step of importance.** Adopted verbatim from the frozen bento spec (`--r-*`), which already matches two of our four surfaces:

| Token | Value | Trust System use | Change |
|---|---|---|---|
| `r-sm` | 10dp | Settings chip, severity chip, banner icon box | chip 6→10; sev chip 11→10 |
| `r-md` | 14dp | History strip card, sheet CTA | strip 8→14; CTA 14 ✓ |
| `r-lg` | 18dp | Top banner, **Recovery card** | banner 18 ✓; **recovery 20→18** |
| `r-sheet` | 26dp | Modal sheet | ✓ already |
| `pill` | 999dp | Snooze chip | ✓ already |

*The recovery card at 20dp is the single geometric outlier in the app; it snaps to `r-lg`.*

**Spacing scale:** `4 / 8 / 12 / 16 / 20 / 24`.
- Page/host gutter: **16** (matches Library `screenPadH`).
- Sheet side padding: **20**; sheet bottom padding: **24** (both already correct).
- Card content padding: **16** horizontal, **16** vertical *(recovery is 16/18 today → snap to 16/16)*.
- Inter-element gap inside a card: **12** major, **8** minor, **4** tight.
- Hairline stroke: **1dp**. Focus ring: **2dp** (ADR-0020 SC 2.4.7).

**Rule:** no inline dp literal may appear in a component. Every value resolves from the ladder or the scale. This is the discipline that made `LibraryDimens` work.

---

## 6. Color / token strategy

This is the core of the freeze.

**6.1 — One entry point.** Mirror `rememberLibraryColors()` exactly: a single `rememberTrustColors()` reads `LocalGlassEnvironment.current.palette`, `remember(palette)`s, and resolves every slot through a **pure** `TrustColorSpec`. *No Trust System component owns its own color.* A theme swap restyles the whole system for free, and the spec is JVM-testable without a device.

**6.2 — Three families, not one.** The Library ships two (identity, locked-over-media). The Trust System needs three, because severity is a third, orthogonal axis:

| Family | Retints per theme? | Members | Contract |
|---|---|---|---|
| **Identity** | Yes | container fill, edge, hairline, ghost border, state layers, title/body ink | derived from `palette.textHigh` / `textDim` / `edge` / `surfaceBase` |
| **Locked semantic (severity)** | **No** — identical across all 12 palettes | hard / soft / advisory / escalating | ADR-0028 §3 + ADR-0031 §3. Meaning must survive without color (paired with dot + label + glyph, WCAG 1.4.1). |
| **Locked over-media** | **No** | scrim, overlay ink, overlay edge | AA-proven against arbitrary video frames — the `CaptionScrim` contract, generalized |

**6.3 — Substrate: two, and only the banner + chip are over-media.**

*(Scoped down after independent review. The first draft put the sheet in the over-media family and proposed a structural-scrim primitive. Both were wrong.)*

Verified: the warning sheet is a Material 3 `ModalBottomSheet` (`WarningSheetV3.kt:107-117`) — it draws its **own scrim** and renders on its **own opaque container**. It never sits on arbitrary video. Only the **top banner** and the **snooze chip** float over the live viewfinder, and the banner is already an **88%-opaque** capsule (`WarningTopBannerV3.kt:71`) admitting 12% bleed.

| Substrate | Members | Contract |
|---|---|---|
| **Over-app-surface** (deterministic) | Modal sheet (own scrim + container), History strip card, Settings chip, Recovery card | palette-derived identity ink, ratios proven by `TokenContrastTest` |
| **Over-media** (bleed-through) | Top banner, snooze chip | near-opaque capsule + locked overlay ink; ratio proven against the **worst-case composite** (capsule over white frame) |

**Decision (the lazier, correct fix):** the banner's `White@0.48f` sub-text is not a missing architecture — it is an unproven alpha. **Raise the capsule opacity and/or the ink, and prove it with `TokenContrastTest` against the worst-case composite.** Do **not** generalize `CaptionScrim` into a `MediaSubstrate` structural-scrim primitive — that machinery exists because Library captions sit on a 0%-opaque thumbnail; a 88%-opaque capsule is a fundamentally easier case. If, and only if, the readout fails on an opacified capsule does structural scrim machinery earn its place.

**6.4 — Severity as text.** Low-alpha text is banned as a *contrast strategy*. Where severity must be expressed *as ink*, adopt the bento derivation `--danger-text: color-mix(in srgb, var(--danger) 62%, var(--tHigh))` — a hue-carrying, contrast-preserving mix toward the surface's high ink, rather than an alpha that collapses on a light palette.

**6.5 — The severity CTA stays as it is. It is already solved.**

*(Reversed after independent review — this was the highest-risk error in the first draft.)*

`PrimaryCta` is a **solid severity fill** with a near-black `Color(0xFF1A1A1A)` label, documented at `WarningSheetV3.kt:393-397` as a deliberate AA fix (white-on-accent read 1.67–3.76:1; near-black clears 4.5:1 on **all four** severity fills).

`DialogActionColors.resolve(start, end)` is built for the **accent gradient**, and its strategy *prefers a white label, deepening the fill toward black*. Fed the locked severity fills it would return a **darkened fill + white label** for `hard`, `advisory`, and likely `escalating` — flipping both the fill and the label of shipped, AA-proven CTAs, and **darkening the locked severity color that §6.2 exists to protect**. It would not break AA; it would silently redesign a solved contract. The "across all 12 palettes" argument is a non-sequitur: severity fills are *locked* and do not vary by palette.

**Decision: keep the current tested treatment.** The near-black label is not a raw-hex defect; it is the answer. It graduates from a call-site literal to a **named locked token** (`severityCtaInk`), which is a naming change with zero visual delta. `DialogActionColors.resolve` continues to own the *accent* CTA (dialogs, Record FAB) and is not extended here.

**6.6 — Token deaths at freeze.** `sheetTitleSize`, `sheetBodySize`, `sheetHandleWidth`, `sheetHandleHeight`, `sheetHandleAlpha` *(deleting the first two also touches `RovaWarningsV3Test.kt:24-29`, which pins them)*. Raw hex `0xFF0B0D14` (×2 sites) → named token. Ad-hoc `Color.White.copy(alpha=…)` ink → named tokens with proven ratios. **`0xFF1A1A1A` survives, renamed, per §6.5.**

**6.7 — `V3` naming debt.** `RovaWarningsV3` → `RovaTrustTokens`. `WarningSheetV3` → `WarningSheet`. `WarningTopBannerV3` → `WarningTopBanner`. The suffix records an iteration, not a meaning; the freeze is the moment to retire it. *(Rename is mechanical and belongs to the Compose transcription phase, not the HTML.)*

---

## 7. Typography hierarchy

**Decision: do not introduce a fifth type system.** The Trust System keeps Material 3 type slots (as it does today) and binds them to **named semantic roles**, so the HTML and Compose agree on intent rather than on a slot name.

| Role | Slot | Weight | Use |
|---|---|---|---|
| `trustEyebrow` | `labelSmall`, uppercase, tracked | Medium | severity label, "Interrupted", recency |
| `trustTitle` | `titleMedium` | SemiBold | the condition, one line |
| `trustBody` | `bodySmall` | Regular | the consequence + remedy, ≤3 lines |
| `trustCta` | `labelLarge` | SemiBold | action labels |
| `trustNumeric` | `bodySmall` + **`tnum`** | Medium | `N/N` recovery count, any countdown |

**Rules:**
- Hierarchy comes from **weight and opacity**, not size. (Library principle — its whole scale is 10–14sp.)
- All numerals that update in place use **tabular figures** (`tnum`) so they do not jitter. This is why the recovery `N/N` chip and any countdown are a distinct role.
- The dead sp tokens (`sheetTitleSize` 15sp, `sheetBodySize` 11sp) are deleted, not resurrected — they never rendered.
- Body copy caps at 3 lines. If a condition needs more, the "why" expander owns it (already exists).

---

## 8. Motion rules

**Adopted tokens** (verbatim from the frozen bento spec — one motion vocabulary app-wide):
`--ease-std: cubic-bezier(.2,.8,.2,1)` · `--t-micro: 120ms` · `--t-small: 200ms` · `--t-container: 300ms` · `--t-exit: 220ms`

**The bar: an animation must materially improve comprehension, or it does not ship.**

Surviving animations — exactly three, and no more:
1. **Hard-block severity pulse** (snooze-chip dot, recovery severity dot). Justification: it is the sole persistent indicator that a *blocking* condition is unresolved after the sheet collapsed. Already gated. Retained.
2. **`ProcessingGlyph`** during merge. Justification: it distinguishes "working" from "hung" on a long operation with no other feedback. Retained.
3. **Sheet enter/exit and chip collapse/restore.** Justification: the collapse must be *legible as a collapse*, or the chip appears from nowhere and the user loses the object. `--t-container` in, `--t-exit` out, `--ease-std`.

**Removed:** the static `CountdownRing` in its current form (see §12 Q1) — it is animation-shaped chrome that does not animate and does not count.

**Reduced-motion contract (total, ADR-0020 §Decision-3):**
- Every animation degrades to `snap()` / static under `rememberReduceMotion()`. No exceptions.
- **No animation may be the sole carrier of meaning.** The pulsing dot's meaning is carried by the dot + label + glyph; the pulse only adds salience. Under reduced motion, nothing is lost. This is the design rule that makes the gate safe.
- `checkA11yAnimationGated` continues to enforce the seam co-presence.
- `GlassEnvironment.reduceTransparency` is honored: over-media surfaces fall back to an opaque substrate rather than a translucent one.

---

## 9. Accessibility rules

ADR-0020 is binding. Two precisions the review surfaced, stated exactly:

**9.1 — Touch targets: 48dp is the *house* standard, and it is stricter than AA.**
WCAG 2.2 **AA** (SC 2.5.8) requires 24×24dp. Rova's **ADR-0020 §Decision-1 requires 48×48dp** and calls it the Material 3 baseline. Today's targets — sheet CTA **46**, recovery CTAs **40**, sheet overflow **28**, banner overflow **40**, snooze chip padding-only ≈30–38 — **pass WCAG AA and violate ADR-0020.** Both statements are true; the freeze fixes them because the ADR binds, not because AA does.

| Element | Today | Frozen |
|---|---|---|
| Sheet primary/secondary CTA | 46dp | **48dp** min-height |
| Recovery Merge / Keep / Discard / Retry | 40dp | **48dp** min-height |
| Sheet overflow ⋯ | 28dp | 28dp visual inside a **48dp** hit box |
| Banner overflow ⋯ | 40dp | 40dp visual inside a **48dp** hit box |
| Snooze chip | ≈30–38dp | **48dp** min-height *(see §12 Q5 — occlusion trade-off)* |

Heights become `min-height`, never fixed, so 200% text scale (SC 1.4.4) cannot clip a label.
The new size tokens are added to `checkA11yTargetSizeToken`'s **curated set** (the set *is* the invariant — extending it is the documented way to add a target).

**9.2 — Contrast is proven by test, never by eye.**
`TokenContrastTest` is the authoritative SC 1.4.3 enforcement (ADR-0020, 2026-06-14: a static alpha-floor gate was *rejected* because contrast is background-dependent). The freeze extends it to assert every Trust text token across **12 palettes × 4 severities × 2 substrates**. The banner sub-text is resolved there, not by picking a nicer alpha.

**9.3 — Standing rules (preserved and generalized):**
- Severity is **never color-alone**: dot + uppercase label + glyph (WCAG 1.4.1; ADR-0031 §3).
- Non-text contrast ≥3:1 for severity chips, borders, focus rings, dividers (SC 1.4.11). The `sevChipFillAlpha` 0.13→0.20 bump was made for exactly this; it stays.
- `liveRegion` discipline: merge progress = `Polite`; merge failure = `Assertive`. **Recency must not be a live region** — it changes on recomposition and would make TalkBack chatty. It is a static label, spoken as part of the card.
- Every custom clickable carries `role` (enforced by `checkA11yClickableHasRole`) and a `contentDescription`.
- Focus visible ≥2dp, ≥3:1 (`focusHighlight`, already universal here).
- Decorative layers (glow bloom, scrim) are `contentDescription = null` / `clearAndSetSemantics {}`.
- The recovery card's spoken label states condition, recency, and surviving-artifact count in one merged node.

---

## 10. Responsive behavior

- **Rotation.** Record chrome rotates as a group (existing `RecordChrome` seamless-rotation model). The banner and chip rotate with it; the sheet remains bottom-anchored to the *device*, not the chrome.
- **Width.** The sheet caps at a max content width and centers on wide screens/foldables; it never stretches to a 900dp line length. Body copy caps at ~60ch.
- **Text scale.** All trust surfaces must survive 200% font scale (SC 1.4.4): min-heights not fixed heights; CTA stacks reflow vertically; the severity chip wraps below the title rather than truncating it.
- **Safe insets.** The banner respects status-bar + display-cutout insets; the sheet respects navigation-bar insets. Over-media surfaces never occlude the record FAB or the HUD.
- **`reduceTransparency`.** When `GlassEnvironment.reduceTransparency` is true, over-media substrates render opaque. This is already provided by the environment and currently unread by these surfaces.
- **Truncation policy.** Title truncates at 2 lines with ellipsis; body at 3; the "why" expander owns the remainder. A severity label never truncates.

---

## 11. HTML architecture plan

**Deliverable:** `docs/design/warnings-recovery.html` — one self-contained file, versioned, graduating out of `mockups/` into `docs/design/` as the Figma-equivalent source of truth (same status as `library-bento.html`).

**Non-negotiable:** it uses the **real** `RovaPalette` / `RovaTokens` values verbatim (not approximations), so the prototype cannot approve something the app must refuse.

**Structure:**

1. **Header block** — version, freeze status, changelog. (`library-bento.html` convention.)
2. **Token registry (`:root`)** — the three families of §6, declared separately and labeled. Registry-complete: tokens with no consumer on this surface are declared and marked, as bento does for `--glass`.
3. **Theme switcher — all 12 palettes.** Mandatory: the locked-vs-identity split is only *provable* by flipping every palette.
4. **Substrate switcher — over-media vs over-app-surface.** The over-media pane renders trust surfaces above a real video still (and a worst-case white frame). This is where the banner contrast bug is demonstrated and resolved.
5. **Live contrast readout.** Every text-on-substrate pair computes and displays its actual ratio, with a visible PASS/FAIL. A specimen that fails cannot be frozen. This is the HTML analogue of `TokenContrastTest`.
6. **Severity matrix** — 4 severities × each surface, so the locked family is inspected as a system.
7. **Surface specimens** — `HardBlockSheet` (non-dismissible), `SoftSheet`, `AdvisorySheet`, `TopBanner`, `SnoozeChip`, `HistoryStripCard`, `SettingsChip`, `RecoveryCard` (all 6 `RecoveryCardKind`s), `CantMerge` 3-way, `MergeFailed` + Retry **rendering the localized fail-reason set of Q6, never a raw exception**, vendor-guidance slot.
8. **State gallery — all 21 `WarningId` values**, each with tier, `gatesStart`, host, surface, dismissibility. This is the artifact that reconciles the contract's stale 17-row table.
9. **Interaction demos** — sheet → dismiss → chip → restore; recovery merge idle → in-flight (Merge/Keep disabled, Discard live) → failed → Retry; `hiddenCount` collapse; snooze.
10. **Reduced-motion toggle** — every specimen re-renders static. Demonstrates that no meaning is lost.
11. **Accessibility inspector** — a toggle revealing each specimen's spoken label, role, and live-region politeness, so the a11y contract is reviewable as *content*, not inferred from code.
12. **Spec appendix** — the exact dp / sp / easing / duration / ratio table, each row tagged **"do not re-derive"**, for Compose to transcribe.

**Review path:** internal critique → owner feedback → refinement → **DESIGN FREEZE** → then, and only then, Compose transcription with pixel-parity review.

---

## 12. Reusable design-system primitives (the harvest)

Per the reconciled review, the shared contract is **harvested from** this freeze, not designed before it. These are the parts that graduate into `docs/design/rova-design-system.html` afterward. Each is nominated because it will have been *proven on a real surface*, not speculated:

**Tier 1 — certain to generalize** (second instance of an already-proven Library pattern):
1. **`rememberTrustColors()` / `TrustColorSpec`** — the *second* instance of the single-color-entry-point pattern. Two instances establish the pattern; the generalized `ColorSpec` contract is extractable.
2. **The radius ladder** (`r-sm 10 / r-md 14 / r-tile 16 / r-lg 18 / r-sheet 26`) → app-wide `RovaRadii`.
3. **Motion tokens** (`ease-std` + 4 durations) + the total reduced-motion contract → consolidates `RovaMotion`.
4. **State-layer scale** (`fill-1 .05 / fill-2 .08 / press .12 / hairline .06`) — identical to bento's, confirming it is app-wide, not Library-local.
5. **The two-CTA-resolver rule**, made explicit: `DialogActionColors.resolve` owns **accent** fills (which vary per palette); a **locked-ink constant** owns **locked semantic** fills (which do not). This freeze *establishes* that boundary rather than erasing it — see §6.5.
5b. **`SeverityInk.resolve(sev, backing, target)`** *(added during Phase 2; discovered by the HTML's own contrast sweep)* — the general **"resolvers own contrast, tokens own hue"** law. §6.4's fixed `color-mix(sev 62%, textHigh)` derivation was **falsified on Daylight** (label 2.85:1, mark 1.37:1); no fixed derivation can clear AA against both a near-black and a near-white backing. The resolver picks direction from the backing's luminance — lighten on dark (byte-identical to the committed derivation, so **zero delta on all 11 dark palettes**), `deepen` on light (the same primitive `DialogActionColors` uses). This is the **third** instance of deepen-to-AA and therefore the strongest generalization candidate in the harvest. *The locked hue stays the single identity; the resolver is a rendering function over it, never a second token.*

**Tier 2 — likely to generalize** (needs this freeze to prove the shape):
6. **`TrustRow` anatomy** (severity mark → title → body → action → dismiss) + the three CTA roles (primary / ghost / destructive).
7. **Severity-as-text derivation** (`color-mix` toward `textHigh`) — the general replacement for low-alpha colored text.
8. **`RelativeTimeLabels`** — a pure, DST-safe relative-time seam generalized from `LibraryDateLabels` and consumed by recovery recency.

**Explicitly NOT nominated** *(corrected after review — the harvest must describe what this surface proves, not pull work forward)*: a `MediaSubstrate` / structural-scrim primitive (§6.3 shows the banner does not need one), and an app-wide `RovaRadii` / `RovaMotion` consolidation (real, but it is the *third* surface that will prove those, not this one).

**Tier 3 — process primitives** (not code):
10. **The `TokenContrastTest` extension harness** — the *N palettes × M substrates* assertion shape.
11. **The 48dp target contract** + the documented convention for extending `checkA11yTargetSizeToken`'s curated set.
12. **The HTML spec skeleton** (token registry → theme switcher → substrate switcher → contrast readout → state gallery → a11y inspector → do-not-re-derive appendix) as the reusable template for every future surface freeze.

**Deliberately NOT generalized:** the severity color family (Trust-System-specific by design), the vendor-guidance intent map, and the recovery CTA state machine.

---

## 13. Open questions — owner decision required before HTML

These are genuine forks. I have a recommendation on each but will not decide unilaterally.

**Q1 — The static `CountdownRing`.** It renders a countdown ring with `totalSeconds = 30` hardcoded and `secondsRemaining` never decrementing (`WarningTopBannerV3.kt:126,184-186`). It depicts a state the system does not have.
→ *Recommend:* **remove it.** Making it truthful requires a real timer for auto-stop, which is a behavior change and out of scope for a design freeze. Removing an untruthful affordance is subtraction, not redesign. (Alternative: keep and make truthful in a separate behavior PR.)

**Q2 — Two locked semantic families.** **Verified:** `RovaWarnings.hard #EF4444` == `RovaSemantics.error #EF4444`; `RovaWarnings.soft #FBBF24` == `RovaSemantics.warning #FBBF24`; `escalating #F97316` in both. Only `advisory #5B7FFF` is Warnings-unique (`RovaTokens.kt:240-243`, `RovaPalette.kt:15-16`).

**But the separation is deliberate, not redundant.** `RecoveryCard.kt:139-143` explicitly documents keeping the two apart so they are never mixed on one mark. Structurally: `RovaWarnings` is the **alpha-compositable** set (`severityColor.copy(alpha=…)` appears dozens of times across the surface files); `RovaSemantics` is the **never-mutate** set — and `checkStatusColorLocked` forbids `.copy(` on any `RovaSemantics.<member>` (`RovaGateRules_IconTheme.kt:258-259`).

→ *Recommend:* **deduplicate the hex literals only, never the accessor.** `RovaWarnings.hard` may be *defined as* `RovaSemantics.error`'s value, but every call site keeps the `RovaWarnings.` accessor. **Invariant: alias-preservation.** A naive merge that rewrote call sites to `RovaSemantics.error.copy(alpha=…)` would trip `checkStatusColorLocked` at every site. This is the low-risk half of the win; the accessor split stays.

**Q3 — Recovery recency: format and precision.** Data exists (`startedAt`, `terminatedAt`); no schema change needed.
→ *Recommend:* relative label in the eyebrow ("Interrupted · 2 hours ago"), absolute timestamp in the `contentDescription` and the details/"why" expander. Requires a pure clock seam (`RelativeTimeLabels`) injected into the mapper — the mapper today deliberately avoids a `Clock` (`RecoveryCard.kt:135-138`), so this is a real, if small, architectural addition.

**Q4 — Three counts, one of them stale.** `WarningId` declares **21** values. `WarningCenterContract.md`'s "17 rows" counts *precedence-resolver rows* and is **correct on its own axis** (its line-16 note explains: 18 logical states; `CANT_MERGE` rides a transient `RecoveryCardState` field; some enum values are contract-"missing" producers with no precedence row). **`CLAUDE.md`'s "17 `WarningId` values" is the genuinely stale claim.**
→ *Recommend:* the HTML state gallery enumerates all 21 and **labels which axis each count measures** — that is the forcing function. Fix `CLAUDE.md`'s stale line. Amend NO-GO #1 to "no new warning state without an ADR," which is what actually happened in practice (four landed, each with its own ADR). This adds no state; it makes the docs tell the truth.

**Q6 — The merge-failure copy shows the user a raw English exception string.** `RecoveryViewModel.kt:86-91` sets `mergeFailedReason = o.cause.message ?: "merge failed"` (marked `i18n-opt-out: nonlocalized diagnostic sentinel`) and `RecoveryCard.kt:504` renders it to the user behind `recovery_merge_failed_prefix`. So a user in any locale can see *"Couldn't create final video: java.io.IOException: …"*.
→ This directly contradicts §3's trust thesis (calm, truthful, **localized** copy) and the spirit of `checkUserCopyVocabulary` / `checkNoHardcodedUiStrings`. It is the one substantive omission the first draft made. *Recommend:* the freeze settles a **fail-reason copy contract** — a small closed set of localized, human causes (out of space / file unreadable / unknown), with the raw exception demoted to logs only. Decide the set before HTML; the specimen in §11 renders it.

**Q5 — Snooze chip at 48dp.** The chip floats over the live viewfinder. Raising ≈30–38dp → 48dp increases occlusion of the camera preview.
→ *Recommend:* 48dp **hit box** with the current visual size preserved (`minimumInteractiveComponentSize()`), exactly as ADR-0020 §Decision-1 explicitly permits ("visual element may be smaller inside a 48dp hit box"). Zero occlusion cost, full compliance. Same treatment for both overflow ⋯ buttons.

---

## 14. Phase gate

Phase 1 ends here. **No HTML, no Compose, no implementation.**

On approval (and resolution of Q1–Q5), Phase 2 produces `docs/design/warnings-recovery.html` per §11, iterates through critique → owner feedback → refinement, and ends at **DESIGN FREEZE**. Only then does Compose transcription begin, with pixel-parity review against the frozen spec.
