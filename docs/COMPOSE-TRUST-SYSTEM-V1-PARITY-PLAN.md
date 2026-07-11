# Compose Trust System V1 — Parity Plan

> **Status:** Implementation contract. **Canonical design source:** `docs/design/warnings-recovery.html` (v1.0 FROZEN 2026-07-09, commit `09a0cba7`). This plan tells engineers *how to transcribe* that document into Compose; it never overrides it. Where this plan and the HTML disagree, the HTML wins and this plan has a bug.
>
> **For agentic workers:** execute milestone-by-milestone with independent review between milestones (house Independent Review Workflow). Each milestone is one PR.

**Goal:** Bring every Warnings + Recovery surface (WarningSheet, TopBanner, SnoozeChip, HistoryStrip, SettingsChip, RecoveryCard, merge-failure card) to pixel- and behavior-parity with the frozen HTML spec.

**Architecture:** Token-first. New locked/derived tokens and the pure `ResolveInk` resolver land before any surface changes; each surface then transcribes independently against the already-tested color contract. Recovery recency is the single architectural addition (a Clock seam into the recovery mapper). Everything else is transcription of values the HTML fixes.

**Tech stack:** Kotlin, Compose (BOM 2025.01.01), M3 `ModalBottomSheet`, existing `RovaPalette`/`RovaTokens`/`SemanticIcon` seams, JVM-only unit tests.

---

## 1. Implementation philosophy

1. **Transcribe, never design.** The HTML froze on 2026-07-09. Every dp, sp, alpha, hex, duration and easing in this plan is copied from the HTML's token registry and §12 appendix. If Compose work reveals an ambiguity or problem, the fix routes through the HTML (update → re-approve → transcribe) — never an in-code decision. This is the same discipline as the Library bento freeze (PR #172).
2. **Contract before chrome.** The HTML's on-load sweep asserts 78 contrast pairs × 36 cells (12 palettes × 3 media frames) = 2,808 assertions. The JVM equivalent of that sweep must exist and pass *before* any surface adopts resolved inks, so a milestone that breaks contrast fails in CI, not on a device.
3. **Smallest shippable slices.** One surface per milestone. Master stays green after every merge; a half-migrated app (new snooze chip, old banner) is acceptable because every surface is self-consistent and the token families are additive until cleanup.
4. **Existing seams over new machinery.** `quietTextColor`, `DialogActionColors.resolve` (its `deepen` primitive), `SemanticIcon`, `rememberReduceMotion`, the glass-environment seam, `ContrastMath` — all already exist and are reused. The only new abstractions are the three the HTML itself names as net-new: `ResolveInk`, `RelativeTimeLabels`, the recovery Clock seam.
5. **Gates are the floor, the HTML is the ceiling.** No milestone may weaken any of the 48 `check*` gates. New size tokens join `checkA11yTargetSizeToken`'s curated set as they are introduced (ADR clause → rule + golden test → registration, per the standing process).

## 2. Parity rules

- **P1 — Token identity.** Every color a Trust surface paints comes from one of the three HTML token families: Identity (themed, `RovaPalette`-derived), Locked severity (`RovaWarnings`/`RovaSemantics`, never themed, never alpha-mutated beyond the documented decorative fills), Locked pinned/over-media (`pinSurface` #0B0D14, `pinContainerAlpha` 0.94, `mediaInk` 0.94 / `mediaInkDim` 0.48 / `mediaInkBody` 0.55). No raw hex at a call site survives cleanup except the six shape primitives (APPX-A exception list).
- **P2 — One resolver.** Any hue used *as ink* (severity as label, severity as mark, accent as text) is resolved per-backing by `ResolveInk` — never a fixed `copy(alpha=…)` mix, never a second resolver, never a third fixed mix. Accent *fills* stay on `DialogActionColors.resolve`; severity *fills* never route through it (APPX-C boundary).
- **P3 — Sweep or it didn't happen.** A new painted pair (ink over backing) is added to the JVM sweep in the same PR that paints it. A site absent from the sweep's site list is not resolved and not proven — the HTML's INK_SITES discipline, ported.
- **P4 — Ladders only.** Radius from {10, 14, 18, 26, 999}; spacing from the 4dp ladder {4, 8, 12, 16, 20, 24}; motion from {120, 200, 300, 220 ms} with `cubic-bezier(.2,.8,.2,1)`. No inline dp literal for padding, gap or radius (the six APPX-A shape primitives — dots 5/7dp, progress cell 7×r3.5, glyphs 11–12dp, 6dp mark↔label gap — are the complete exception list).
- **P5 — Hit ≥48dp, visuals preserved.** Touch targets reach 48dp by *invisible hit-area expansion, never visual enlargement* (owner Q5, ADR-0020 §Decision-1). Heights are min-heights so 200% text scale cannot clip.
- **P6 — Motion carries state or dies.** Exactly three animations exist (severity pulse ×2 sites, ProcessingGlyph, sheet enter/exit + chip collapse) plus the CTA press micro. All gated on reduced motion. Nothing else animates; nothing conveys meaning by motion alone.
- **P7 — Copy via resources.** All user copy through `@StringRes` (`checkNoHardcodedUiStrings`); the merge-failure raw-diagnostic pass-through is *closed* by this plan (Q6), removing the documented opt-out from the render path (raw string stays for logs/diagnostics only).
- **P8 — Behavior untouched.** Precedence, gating, snooze, hysteresis, dismissal axes, merge state machine, echo promotion, allowlists, the 21-id enum and its order: all frozen inputs. The only behavioral deltas are the four the HTML itself mandates: progress-chip numerator fix, CountdownRing removal, merge-failure closed reason set, hard-block sheet loses its drag handle.

## 3. Transcription rules

- **T1** — Values come from the HTML's `:root` registry and §12/APPX-A tables, not from this plan's prose. Before coding a surface, open the HTML section for that surface and its specimen; screenshots of the specimen are the pixel reference.
- **T2** — 1 CSS px in the spec = 1 dp in Compose; font sizes in px = sp (the HTML states the type scale 9.5–15 in dp/sp-equivalent terms).
- **T3** — The HTML's `resolveInk` JS (lines ~1252–1264) is the reference algorithm for `ResolveInk`: constants `STEP=0.12`, `MAX_T=0.6`, `DARK_BACKING=0.18`, mixes `MIX_LABEL=.62`, `MIX_ACCENT=.72`, mark `mix=0`; dark backing → `mix(hue, mix, top)` lighten (does **not** consult target — the sweep proves clearance); light backing → minimum `t ∈ {0,.12,…,.6}` clearing target via the same `deepen` primitive `DialogActionColors` uses. Targets: 4.5 label/accent-text, 3.0 mark.
- **T4** — Do not re-derive: severity-per-kind (`severityColorFor`, `RecoveryCard.kt:246–253`), the 21-id surface mapping (`warningSurfaceFor`, `WarningSheetContent.kt:35–46`), the History/Settings allowlists, the merge-failure reason copy (§08 verbatim), the recovery card copy (§07 verbatim), the recency ladder (APPX-G verbatim).
- **T5** — The Version-history **Transcription notes** are mandatory reading; each is mapped to a milestone in §10's parity checklist (progress-chip numerator; ⋯ overflow only for the two echo ids; snooze pulse gate; two dismissal axes; `--surface-hi` DERIVED; accent CTA ink is house `#0E1116`, never accent-derived).
- **T6** — Decorative blooms (sheet icon glow 22dp blur, recovery top-edge glow 28dp blur) are radial/linear gradients, **not** `Modifier.blur` on the record surface; they are gate-legal and must not be "fixed" away (APPX-D states this explicitly).
- **T7** — SC 1.4.11 exemption list (severity chip 0.20α fill; ghost/destructive CTA borders; banner/chip severity borders; progress cells) is closed. Everything not on it is asserted by the sweep.

## 4. Reusable abstractions

| Abstraction | Why it exists | Where it lives | Owner milestone | Existing vs new |
|---|---|---|---|---|
| `RovaTrustTokens` (rename of `RovaWarningsV3`) | One home for Trust geometry/alpha canon; three families explicit | `ui/theme/RovaTrustTokens.kt` | M1 adds tokens into `RovaWarningsV3`; M11 renames | **Existing file, extended then renamed.** New members: `pinSurface`, `pinContainerAlpha`, `mediaInk`, `mediaInkDim`, `mediaInkBody`, `mediaEdgeTop`, `severityCtaInk`, `surfaceHi(palette)` derivation (mix(white 8%, surfaceBase); Daylight uses its explicit member). Deleted (M5/M6/M11): 3 CountdownRing tokens, 3 drag-handle tokens, `sheetTitleSize`/`sheetBodySize` |
| `ResolveInk` | One per-backing hue-as-ink resolver (APPX-D); kills fixed mixes that fail on Daylight | `ui/theme/ResolveInk.kt` (pure) | M2 | **New.** Reuses `DialogActionColors`' `deepen` primitive and `ContrastMath` |
| `DialogActionColors.resolve` | Accent-fill CTA contract (AA=4.5, STEP=.12, MAX_DEEPEN=.6, WHITE_DEEPEN_LIMIT=.4) | `ui/theme/DialogActionColors.kt` | — | **Existing, unchanged.** Severity fills must never route through it (APPX-C) |
| `quietTextColor` | Themed dim/body ink that drops alpha on light schemes | `ui/theme/QuietText.kt:17` | — | **Existing, unchanged.** M7/M8 route all themed `--ink-dim`/`--ink-body` through it |
| `RelativeTimeLabels` | APPX-G recency ladder ("just now" … absolute date), DST-safe, generalized from `LibraryDateLabels`' method | `ui/recovery/RelativeTimeLabels.kt` (pure) | M3 | **New.** `LibraryDateLabels` stays where it is — method harvested, not code moved |
| Recovery Clock seam | Mapper needs "now" to render recency; deliberately avoids a Clock today. The one architectural addition of the freeze | `RecoveryUiStateMapper` ctor/param | M3 | **New parameter** on existing pure mapper |
| `SemanticIcon` / `RovaGlyph` / `WarningIconSpec` | Glyph identity + tint seam (ADR-0031, §4 severity-tint exception) | `ui/theme/`, `ui/warnings/WarningIconSpec.kt` | — | **Existing, unchanged.** Manual severity tints keep their §4 exception comments |
| Severity chip atom | Shared eyebrow chip (sheet, strip, settings, recovery tag) — r-sm 10, 0.20 fill, `lbl-ink`/`dot-ink` resolved | today duplicated per surface | M2 defines inks; each surface milestone transcribes; **no forced shared composable** | The HTML unifies the *ordering and values*, not markup. Extract a shared `TrustSeverityChip` composable only if M6–M8 turn out byte-identical — YAGNI otherwise |
| TrustRow | §01 ordering law (mark → what/meaning/action/else) | ordering discipline, **not** a composable | all surface milestones | **Not extracted.** The HTML is explicit: A1–A4 is an ordering enforced by source order, "deliberately no .a1…a4 rules". Do not build a TrustRow layout composable |
| `rememberReduceTransparency()` | Over-media substrates go opaque under the a11y signal (§11) | `ui/components/ReducedTransparency.kt`, via `GlassEnvironment` | M10 | **Existing, unchanged** — the two pinned surfaces start *reading* it |
| `RovaMotion` | One motion ladder (ADR-0028 §3.2); HTML APPX-A adopts bento's values | `ui/theme/RovaMotion.kt` | M1 extends (300/220ms + easing) | **Existing, extended** |
| Hit-expansion helper | 48dp invisible expansion (banner Stop pill, chip, overflow) | Compose `minimumInteractiveComponentSize()` / existing `.hit` pattern | per surface | **Existing platform mechanism.** New size tokens join `checkA11yTargetSizeToken`'s curated set |
| Contrast sweep test | JVM analogue of the HTML's 2,808-assertion on-load sweep | `app/src/test/java/com/aritr/rova/ui/theme/TrustContrastSweepTest.kt` | M2 | **New**, alongside (not replacing) `TokenContrastTest`; reuses `ContrastMath` |
| `MergeFailureReason` | Q6 closed reason set (STORAGE / UNREADABLE / INCOMPLETE / UNKNOWN) → `@StringRes`; never a pass-through | `ui/recovery/MergeFailureReason.kt` (pure) | M9 | **New** pure classifier over the existing raw diagnostic string |

## 5. File-level migration map

**Leave unchanged** (behavior frozen; P8): `WarningId.kt` · `WarningPrecedence.kt` · `WarningScreen.kt` · `WarningCenterViewModel.kt` (aggregation, snooze, rate limits, dismissal) · `WarningCenter.kt` routing logic (echo promotion, `launchActionTarget`, factory) · `WarningIconSpec.kt` · `TerminalEcho.kt` · `BatteryCardRateLimit.kt` · `ThermalTipsSheet.kt` (visuals may inherit token updates only) · all `ui/signals/*` · `RecoveryViewModel.kt` (except M9's reason plumb-through) · `RecoveryIconSpec.kt` · `VendorGuidanceIntents.kt` · `QuietText.kt` · `DialogActionColors.kt` · `RovaPalette.kt` · `PaletteColorScheme.kt` · `SemanticIconSpec.kt` · `RovaIcons.kt`/`RovaGlyphs.kt` · `ContrastMath.kt`.

**Modify:**
- `ui/theme/RovaMotion.kt` — M1: add `container 300ms` / `exit 220ms` / standard easing (ladder home per ADR-0028 §3.2).
- `ui/theme/RovaWarningsV3.kt` — M1 ✅ added Family-2/3 tokens + `surfaceHi` derivation. The **ladder migrations** (whyRow radius 11→10, banner vertical padding 11→12, recovery radius 20→18, settings chip radius 6→10, strip radius 8→14, sev chip radius 11→10) were deliberately NOT added in M1 — each lands as a new named token *with its first consumer* (M4–M8), which is also when the old value's last consumer disappears and it can be deleted. M5/M6 delete ring/handle/title-size tokens; M11 rename → `RovaTrustTokens.kt`.
- `ui/warnings/WarningSnoozeChip.kt` — M4: container `Color.Black@.55` → `pinSurface@.94` (the genuine AA repair — 3.61:1 today); label `.78` → `mediaInk .94`; glyph → `mediaInkDim`; dot → resolved `dot-ink`; pulse gate and severity border α0.25 survive verbatim.
- `ui/warnings/WarningTopBannerV3.kt` — M5: **delete `CountdownRing`** (Q1) and the `autoAction` render arm; trailing slot = Stop pill always; container `.88` → `pinContainerAlpha .94`; title `.88` → `mediaInk .94`; sub stays `.48` (= `mediaInkDim`); Stop-pill label `severityColor.copy(.95)` → resolved `lbl-ink` (fails SC 1.4.3 today at 3.44–4.38:1); icon-box glyph → `dot-ink`; vertical padding 11→12; Stop pill + ⋯ get 48dp invisible hit expansion; ⋯ gating (`overflow.isNotEmpty()`) unchanged. M11 rename drop V3.
- `ui/warnings/WarningSheetV3.kt` — M6: hard-block sheet renders **no drag handle** (dismissible sheets use stock `BottomSheetDefaults.DragHandle()`; custom handle tokens die); primary severity CTA = solid `--sev` fill + `severityCtaInk` #1A1A1A (today accent fill — see M6 note); ghost CTA to spec (ink-high 7% fill, ink-body label, 1px ink-high 12% border); title/body drop `sheetTitleSize`/`sheetBodySize` for M3 type roles per spec scale; why-row radius 11→10; CTAs min-height 48; overflow ⋯ hit 48; icon glow kept (22dp bloom). M11 rename drop V3.
- `ui/warnings/HistoryWarningStrip.kt` — M7: card radius 8→14, fill `color-mix(sev 8%, surface)`, border sev 25%; sevchip inks resolved (`stripchip` site); body via `quietTextColor`; X dismiss keeps 48dp hit.
- `ui/warnings/SettingsPermissionsSection.kt` — M7: chip radius 6→10, min-height 48, fill sev 8% over surface, border sev 25%, leading 7dp resolved dot, trailing "Fix" in resolved `acc-ink` (mix .72); not dismissible (unchanged).
- `ui/recovery/RecoveryCard.kt` — M8: container `0xFF0B0D14@.94` → themed `surfaceHi` + 1px `edge` border, radius 20→18, padding 16/18→16/16; severity tag fill `0.14` → `0.20` (`sevChipFillAlpha`), label/dot → resolved inks (`recov` site); eyebrow gains relative recency (" · 2 hours ago", tabular-nums); body/artifacts via `quietTextColor`; CTAs re-roled — primary Merge/Retry = `cta-accent` (`accent-fill`/`accent-ink` #0E1116 via `DialogActionColors`), Keep = ghost, **Discard = `cta-dest`** (transparent, label mix(sev-hard 62%, ink-high), 1px sev-hard 30% border, own row, never full-width — today it's a solid red-filled CTA); vendor link in resolved `acc-ink` + external glyph, min-height 48; progress strip: cell track `fill-1` 5%, filled `accent-fill`, **numerator fix at :408** (`filled/total`, not N/N); merged a11y node + live-region matrix per §07; pulse gate (`:101`) unchanged.
- `ui/recovery/RecoveryUiState.kt` — M3: mapper takes a Clock, `RecoveryCardState` carries a recency label input (no schema change — reads existing `startedAt`/`terminatedAt`); M9: carries `mergeFailedReasonRes` alongside the raw diagnostic.
- `app/src/main/res/values/strings.xml` (+ every locale file) — M3 recency plurals; M9 four merge-failure reason strings; M8 any copy deltas §07 fixes (audit against `KINDS` verbatim copy).
- `docs/adr/0013-*.md` — M0 amendment (see below).
- `CLAUDE.md` — M0: fix stale "17 `WarningId` values" → 21. APPX-H calls this "the only false claim"; the M0 review found a **second** one, `docs/architecture.md:79` ("17-entry enum"), also fixed in M0. The frozen HTML is not edited for this — a spec in Design Freeze is amended only through a new design iteration, and APPX-H's miscount is a documentation observation, not a visual clause.
- `docs/architecture.md` — M0: same stale count, line 79.
- `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_*.kt` — M4–M8 as new size tokens join `checkA11yTargetSizeToken`'s curated set (+ golden tests). Extension only; no gate weakened.

**New files:** `ui/theme/ResolveInk.kt` (M2) · `ui/recovery/RelativeTimeLabels.kt` (M3) · `ui/recovery/MergeFailureReason.kt` (M9) · tests listed in §8.

**Delete:** `CountdownRing` composable + `bannerCountdownRingSize`/`Stroke`/`TrackAlpha` (M5) · `sheetHandleWidth`/`Height`/`Alpha` (M6) · `sheetTitleSize`/`sheetBodySize` (M6, unpins part of `RovaWarningsV3Test`) · `AutoAction` in `WarningSheetContent.kt` and its static-30s placeholder (M5 — it was never wired to a real ticker; Q1's rationale) · the rejected accent×0.16 ink experiments (none shipped; nothing to delete in code).

**Rename (M11, mechanical):** `RovaWarningsV3.kt` → `RovaTrustTokens.kt`; `WarningSheetV3` → `WarningSheet`; `WarningTopBannerV3` → `WarningTopBanner`; `RovaWarningsV3Test` → `RovaTrustTokensTest`.

## 6. Implementation order & dependency graph

Order is architectural, not by screen: docs → tokens → resolver+sweep → pure helpers → surfaces (pinned first — they carry the AA failures — then themed) → behavior fixes → cleanup.

```
M0 (ADR + doc staleness)
 └─ M1 (token foundation)
     ├─ M2 (ResolveInk + contrast sweep)
     │    ├─ M4 (SnoozeChip)        [pinned, smallest, genuine AA fix]
     │    ├─ M5 (TopBanner)         [pinned; CountdownRing deletion]
     │    ├─ M6 (WarningSheet)      [pinned]
     │    ├─ M7 (HistoryStrip + SettingsChip)  [themed]
     │    └─ M8 (RecoveryCard)      [themed; also needs M3]
     ├─ M3 (RelativeTimeLabels + Clock seam) ──→ M8
     └─ M10 (reduce-transparency wiring)  [needs M1's pinSurface tokens; independent of M2]
M8 ──→ M9 (merge-failure closed reason set)
M4–M10 ──→ M11 (renames, dead-token deletion, final parity audit)
```

M4–M7 are mutually independent after M2 and may land in any order; the listed order goes worst-contrast-first.

## 7. Milestones

Every milestone additionally inherits: suite green (baseline + new tests, `--max-workers=2` on this box), all 48 gates green, `:app:assembleDebug` green, independent review (Implementer ≠ Reviewer) with reconciliation before merge, `scripts/preflight.ps1` clean before branching.

**Progress.** M0 ✅ MERGED (PR #178 → `9220e342`, 2026-07-10) · M1 ✅ MERGED (PR #179 → `c46b5f8b`, 2026-07-10, suite 2302/0-0-0) · M2 ✅ MERGED (PR #180 → `3ca08918`, 2026-07-10, suite 2320/0-0-0) · M3 ✅ MERGED (PR #181 → `8083b675`, 2026-07-10, suite 2348/0-0-0) · **M4 ✅ MERGED** (PR #182 → `d92bc800`, 2026-07-11, suite 2353/0-0-0 — first `ResolveInk` + M1 pinned-token UI consumer; the snooze pulse-motion debt it carried is **CLOSED in M5**). · **M5 ✅ MERGED** (PR #183 → `7a444ce1`, 2026-07-11, suite 2353/0-0-0 — TopBanner parity: first `ResolveInk` consumer on the banner INK_SITE; CountdownRing + `AutoAction` deleted; invisible 48dp Stop hit-target (Investigation-2 fix, device-verified); snooze pulse-motion debt closed). · **M6 ✅ MERGED** (PR #184 → `1a77f045`, 2026-07-11, suite 2354/0-0-0 — WarningSheet parity: pinSurface sheet, hard-block no-drag-handle, severity-filled primary CTA + `severityCtaInk`, sevchip r-sm 10 with resolved dot/label, neutral media-ink why-row, CANT_MERGE `cta-dest`, 48dp overflow. **Reconciliation:** both pinned LABEL sites now feed the opaque `mediaInk`-over-`pinSurface` composite as ResolveInk `top` — sheet sevchip label **and** the M5 banner Stop-pill label fixed together; raw `mediaInk` had drifted the resolved label ~15/255 lighter than the frozen `--lbl-ink` since ResolveInk reads `top`'s RGB only. MARK sites untouched). **M7 is next** (Themed hosts: HistoryStrip + SettingsChip). M7–M11 not started. M8 (RecoveryCard) additionally needs M3 and is the first consumer of `RelativeTimeLabel` (the M3 recency `strings.xml` plurals land there, per the M1 "token lands with its first consumer" precedent — deliberately not added in M3).

---

### M0 — ADR amendment + documentation staleness — ✅ MERGED (PR #178)
**Purpose:** House rule: ADR clause before code. ADR-0013 is the current chrome canon; the frozen HTML supersedes its visual layer.
**Files:** `docs/adr/0013-*.md` (amendment: visual/token layer superseded by `docs/design/warnings-recovery.html` v1.0; precedence/surfacing clauses unchanged), `CLAUDE.md` (17→21 fix), optionally a one-line pointer in `docs/WarningCenterContract.md` to APPX-H for the count axes.
**Visual outcome:** none. **Behavioral outcome:** none.
**Risks:** scope creep into rewriting the contract — don't; the contract's behavior clauses are all still true. **Regression risks:** none (docs only).
**Tests:** none. **Device verification:** none.
**DoD:** ADR amendment merged; CLAUDE.md line fixed; owner sign-off on the amendment wording.

### M1 — Trust token foundation (additive) — ✅ MERGED (PR #179 → `c46b5f8b`)
**As shipped:** all seven tokens + `surfaceHi(palette)` + the three `RovaMotion` ladder values, 17 new pins, suite 2302/0-0-0, 48 gates green, zero production consumers. Two deviations from the text below, both deliberate and reviewed: (a) the **"ladder-migration values as new named tokens"** were NOT added — the radius/spacing rungs have no named home yet and no M1 consumer, so adding them would have been speculative; they land with their first consumer (M4+), which is when the old-value-deletion rule actually has something to delete. (b) `mediaEdge` 0.08 was omitted — the HTML marks it "registry-complete, no consumer". Neither is stale planned work; both are YAGNI deferrals recorded here.
**Purpose:** All Family-2/3 tokens and ladder values exist and are value-pinned before any consumer changes.
**Files:** `ui/theme/RovaWarningsV3.kt` (extend), `app/src/test/java/com/aritr/rova/ui/theme/RovaWarningsV3Test.kt` (extend pins).
**Contents:** `pinSurface` 0xFF0B0D14 · `pinContainerAlpha` 0.94f · `mediaInk` 0.94f / `mediaInkDim` 0.48f / `mediaInkBody` 0.55f (whites) · `mediaEdgeTop` 0.12f · `severityCtaInk` 0xFF1A1A1A (named token for the existing literal — zero delta) · `surfaceHi(palette)` = mix(white 8%, `surfaceBase`), Daylight returns its explicit member · ladder-migration values as *new* named tokens beside the old ones (old values deleted only when their last consumer migrates). **Motion ladder home is `RovaMotion`** (ADR-0028 §3.2: "all animated surfaces consume these") — it already has 120/200ms; extend it with `container 300ms`, `exit 220ms`, and the `cubic-bezier(.2,.8,.2,1)` easing so M6's sheet/chip motion never lands as inline durations. Also modify `ui/theme/RovaMotion.kt` + its test.
**Visual outcome:** none (additive). **Behavioral outcome:** none.
**Risks:** `surfaceHi` derivation mismatch vs the HTML's `color-mix` — pin the exact Aurora result **0xFF272934** in a test (see the bootstrap-literal note below; this line said `0xFF242737` until M1 execution corrected it). **Regression risks:** none if purely additive.
**Tests:** value pins for every new token; `surfaceHi` pinned per-palette against the value the HTML *derives at runtime*.
**Device verification:** none.
**DoD:** tokens exist, pinned, unused-by-production yet; no visual diff anywhere.

> **Bootstrap-literal note — `surfaceHi` (added 2026-07-10, during M1 execution).**
>
> 1. **The frozen HTML contains a bootstrap CSS literal for `surfaceHi`.** `:root` declares `--surface-hi:#242737` (`warnings-recovery.html:35`). It is a parse-time seed value, and it is wrong: it contradicts the "DERIVED — mix(white 8%, surfaceBase)" comment attached to its own declaration.
> 2. **Runtime behavior derives the value.** The document's single synchronous `<script>` (`:1155–:1816`) calls `applyTheme('Aurora')` (`:1814`) before any specimen exists. `applyTheme` (`:1340`) writes `--surface-hi` as an **inline style on `documentElement`** — `p.surfaceHi ?? rgbToHex(mixc([255,255,255],.08,bgB))` — which outranks the `:root` rule in the cascade and is never removed (no `removeProperty` call exists in the document). It then calls `render()`, which builds every generated specimen. The sole CSS consumer, `.recov { background: var(--surface-hi) }` (`:356`), therefore resolves to the derived value in every settled state, and re-resolves on every palette switch, since swatch clicks route through `applyTheme`. The contrast inspector never consults CSS at all: `contrastRows()` computes `surfHi = surfaceHiOf(p)` (`:1439`, `:1272`) straight from the `PALETTES` data, and the one helper that *could* read computed style, `cs()` (`:1425`), has **zero call sites**. For Aurora, `mix(white 8%, #141622)` = `#272934`, reproduced independently for all 11 dark palettes. Daylight short-circuits to its explicit `#FFFFFF` member (`:1190`).
>
>    *The one path that looks like an exception isn't.* `applyFrame('white')` (`:1813`) runs **before** `applyTheme` and itself calls `render()`, so generated specimens are first built while `--surface-hi` still holds the literal. No paint can intervene: `:1813`–`:1815` are consecutive synchronous top-level statements in one task, `applyTheme` rebuilds the specimens before that task ends, and CSS `var()` resolves at *paint* time — by which point the inline override is set. The only theoretical abort, an unknown-theme throw in `applyTheme` before the `:1340` write, is unreachable: every swatch's `dataset.theme` is minted from `PALETTES` keys.
>
>    *Precisely:* the literal is not unpaintable — one static specimen exists in the parsed markup (`:730`, distinct from the empty `#recovStage` that `renderRecovery` fills), so a browser that paints mid-parse could flash it for the interval before the end-of-body script runs. It is unreachable in every **settled, rendered, inspected, or interacted** state, and no contrast cell in the sweep ever observes it. Its entire lifetime is the prototype's own bootstrap.
> 3. **Compose intentionally transcribes the runtime behavior, not the dead bootstrap literal.** `RovaWarningsV3.surfaceHi(palette)` implements the derivation and `RovaWarningsV3Test` pins Aurora at `0xFF272934`. A pre-script paint frame has no analogue in Compose; transcribing `#242737` would transcribe an artifact of the prototype's initialization, not a design decision. **The frozen HTML is NOT edited for this** — a documentation defect in a frozen spec is recorded and superseded, never silently patched (same precedent as APPX-H's "only false claim" miscount, corrected in the ADR during M0). If the owner wants `:35` fixed, it goes through a new HTML iteration and re-approval.

### M2 — `ResolveInk` + JVM contrast sweep — ✅ MERGED (PR #180 → `3ca08918`)
**As shipped:** `ResolveInk` + `TrustContrastSweepTest` (2808 assertions) + `ResolveInkTest`, suite 2320/0-0-0, 48 gates green, zero production consumers. Two substrate decisions the plan text did not anticipate, both reviewed and both load-bearing for every later milestone:
>
> (a) **Compositing runs in float, not 8-bit.** The tightest frozen pair (Daylight, `Mark · stripchip · hard`) clears its 3.0 bar by **0.0055**; quantizing each intermediate composite to 8 bits erodes that to 0.0011 and breaks the spec's own published fixtures (snooze pre-freeze `3.61:1` → `3.59:1`). `ContrastMath` therefore gained an **additive** exact `Double`-channel tier (`relativeLuminance(Double…)`, `compositeAlphaOverExact`); the `Int` API delegates to it and is byte-identical. `compositeAlphaOverExact` is also the one *mix* primitive — CSS `color-mix` reduces to the same expression as src-over, so the spec's `over()` and `mixc()` are one function.
>
> (b) **Compose quantizes sRGB `Color` to 8 bits** (`(c * 255f + 0.5f).toInt()`), so `RovaWarningsV3.mediaInk.alpha` reads back `240/255 = 0.941176`, not `0.94`. The sweep is therefore **substrate-accurate, not spec-literal**: its Jade/Eclipse destructive ratios pin at 5.72 / 7.41 where the HTML *displays* 5.70 / 7.37. This is the direct consequence of "read from `RovaPalette`, never from CSS" — asserting the spec's decimals would prove the spec self-consistent and leave the shipped tokens unverified. Asserting the *painted* 8-bit ink (rather than the HTML inspector's unrounded rgb) flips **zero** verdicts across all 1728 resolved-ink cells.
>
> `DialogActionColors.deepen` widened `private` → `internal` (visibility only; `resolve`'s contract byte-identical). An independent architecture review weighed all five alternatives and returned GO: `resolve()` takes a gradient pair with no `backing`/`target` and prefers a white label, so it cannot express the deepen-until-clear contract, and keeping `private` would force the duplicate colour math P2 forbids. APPX-C's two-resolver boundary is intact — `ResolveInk` shares only the policy-free leaf primitive and never calls `resolve()`.
>
> **Documentation observation, deliberately not fixed here:** §5's "Leave unchanged" list names both `ContrastMath.kt` and `DialogActionColors.kt`, yet M2 modified both — additively and behavior-preservingly, exactly as §4's `ResolveInk` row mandates ("Reuses `DialogActionColors`' `deepen` primitive and `ContrastMath`"). §5 contradicts §4. Reopening a completed architectural milestone to reword it was declined; fold the correction into the planned documentation cleanup or M11.

**Purpose:** The color contract. Port the HTML resolver and its 2,808-assertion sweep so every later milestone is CI-proven.
**Files:** new `ui/theme/ResolveInk.kt`; new `app/src/test/java/com/aritr/rova/ui/theme/ResolveInkTest.kt`, `TrustContrastSweepTest.kt`.
**Contents:** `ResolveInk.of(hue, backing, target, top, mix)` per T3. Sweep test enumerates the HTML's 8 INK_SITES × 4 severities × 12 palettes × 3 media frames (dark/bright/white) plus the ~20 non-site pairs (pinned inks over the .94 capsule, themed inks over `surfaceHi`, severity-CTA ink over the four solid fills, destructive fixed mix, accent CTA from the palette not from Aurora) — the full 78-pair list transcribed from the HTML's `contrastRows()` — **excluding rows tagged `rejected`/`PRE-FREEZE`** (the HTML filters those out of the 78 count; do not assert or count them). Targets 4.5 text / 3.0 mark. SC 1.4.11 exemption list (T7) excluded, as in the HTML.
**Visual outcome:** none. **Behavioral outcome:** none.
**Risks:** compositing math divergence from the HTML's `over()`/`mixc()` — reuse `ContrastMath`, add fixture assertions against numbers the HTML itself displays (e.g. snooze-today 3.61:1 FAIL, destructive worst 5.70:1 Jade / best 7.37:1 Eclipse) to prove the port computes the same world. **Regression risks:** none.
**Tests:** `ResolveInkTest` — dark-branch lighten (does not consult target), `DARK_BACKING` 0.18 boundary, light-branch minimum-t selection, `MAX_T` fallback, mark mix=0 passthrough, Daylight-is-the-only-deepen-palette property. `TrustContrastSweepTest` — the matrix; a deliberately-broken token must fail it (RED-prove once, then revert).
**Device verification:** none.
**DoD:** sweep green over all 12 palettes; RED-proof recorded in PR.

### M3 — `RelativeTimeLabels` + recovery Clock seam
**Purpose:** APPX-G recency: relative time in primary UI, absolute only in a11y/why/diagnostics. The freeze's one architectural addition.
**Files:** new `ui/recovery/RelativeTimeLabels.kt`; `ui/recovery/RecoveryUiState.kt` (mapper gains a Clock parameter; state gains the recency inputs); `strings.xml` (plurals: minutes/hours/days ago, "just now", "yesterday"); new `RelativeTimeLabelsTest.kt`; extend `RecoveryViewSourceTest`/mapper tests.
**Contents:** ladder verbatim — <60s "just now" · <60m "N minutes ago" · <24h "N hours ago" · <48h "yesterday" · <7d "N days ago" · else absolute date. DST-safe (method from `LibraryDateLabels`, code not shared). No schema change: reads existing `startedAt`/`terminatedAt` (NO-GO #4 satisfied — APPX-G confirms `recencyKey` is already computed and discarded).
**Visual outcome:** none yet (rendered by M8). **Behavioral outcome:** none.
**Risks:** timezone/DST edge cases — copy `LibraryDateLabels`' proven epoch-day technique. Recency staleness while a card sits on screen: the label is minted at map time; APPX-G freezes it as a static label (never a live region) — do not add a ticker. **Regression risks:** mapper signature change ripples to `RecoveryViewSource`/factory — mechanical.
**Tests:** every ladder boundary (59s/60s, 59m/60m, 23:59/24:00, 47:59/48:00, 6d23h/7d), DST fold/gap days, clock-injection determinism.
**Device verification:** none (pure).
**DoD:** pure layer merged, unconsumed by UI.
**As shipped (PR #181 → `8083b675`, 2026-07-10, suite 2348/0-0-0, independent review GO).** Two judgment calls, both review-upheld:
- **The ladder is elapsed-duration arithmetic, with NO calendar-day math.** APPX-G's rungs are stated as elapsed windows and its "yesterday" *is* the `24h..<48h` bucket by the boundary text (`47:59/48:00`), so elapsed arithmetic is timezone/DST-invariant by construction and there is no `startOfDay`/`dayDiff` to harvest. Copying `LibraryDateLabels`' calendar rounding would make the ladder *diverge* from APPX-G (an event 90 min before local midnight would flip to "yesterday"). What is harvested is its **method** — return a KIND not user copy, inject `now`, `coerceAtLeast(0)` future-clamp — not its code. The Files line above that says "method from `LibraryDateLabels`" is honored precisely by NOT sharing the calendar code.
- **No `strings.xml` and no absolute-date format in M3.** The Files line lists recency plurals, but they were deferred to their first consumer (M8): the frozen HTML specifies no date-format string (inventing one is a redesign), unconsumed strings would be dead resources, and M1 set the "token lands with its first consumer" precedent. `RelativeTimeLabel` carries `atMillis` so M8 can format the DATE rung and resolve every plural.
- `RecoveryClock` is a pure `fun interface` (not `java.time.Clock` — minSdk 24, no core-library desugaring), read once and only when a card exists; required on the pure `RecoveryUiStateMapper.map(views, clock)`, defaulted to `RecoveryClock.System` at the wiring-adjacent `RecoveryViewSource`/`RecoveryViewModel`. The DST tests are vacuous today (the ladder never touches a `TimeZone`) and are kept as documented **regression pins** against a future reintroduction of calendar math. `memory/project_trust_system_relative_time.md`.

### M4 — SnoozeChip parity — ✅ MERGED (PR #182 → `d92bc800`)
**Purpose:** Smallest surface; carries the spec's one outright AA failure (container `Color.Black@.55` + label `.78` = 3.61:1).
**Files:** `ui/warnings/WarningSnoozeChip.kt`; sweep already covers the `snooze` site (M2).
**Visual outcome:** pill fill `pinSurface@.94`; label `mediaInk` (.78→.94); glyph 12dp `mediaInkDim`; dot 7dp resolved `dot-ink`; severity border α0.25 unchanged; visual pill stays 34dp — the 48dp target comes from invisible hit-area expansion (`minimumInteractiveComponentSize()`), never a taller pill (Q5, same rule as M5's Stop pill).
**Behavioral outcome:** none — pulse gate `isHardBlock && !reduceMotion` (`:63`) survives verbatim; label "N warning(s)" unchanged.
**Risks:** dot color shifts subtly on Daylight (resolved vs raw) — expected, spec-mandated. **Regression risks:** pulse gate accidentally widened/narrowed — `checkA11yAnimationGated` + existing `WarningSurfaceTest` protect it.
**Tests:** none new beyond the sweep (visuals only); token pins from M1 already lock values.
**Device verification:** chip render over live viewfinder (bright + dark scene), pulse only for a hard-block id, reduce-motion static, TalkBack "1 warning. Double-tap to review."
**DoD:** pixel-parity vs HTML §05 snooze specimen; device checklist passed.

**As shipped (PR #182 → `d92bc800`, 2026-07-11, suite 2353/0-0-0, independent review GO).** First `ResolveInk` + M1 pinned-token UI consumer. The static transcription matched §05 exactly and the dot is provably byte-identical to the pre-freeze `severityColor` dot (`ResolveInk.of` takes the LIGHTEN branch on the near-black capsule and, with `MIX_MARK = 0`, returns the raw locked hue). Two deviations from the plan text above, both deliberate and review-upheld:
- **Tests were added, not "none new."** M4 introduced three *named geometry tokens* (`snoozeChipPillHeight` 34dp, `snoozeChipPaddingH` 16dp, `snoozeChipGap` 8dp) so the pill/padding/gap no longer read raw `.dp` literals (P4). New tokens land with their first consumer's pins (M1 precedent), so `RovaWarningsV3Test` gained 3 geometry assertions and a new `WarningSnoozeChipInkTest` (2 tests: LIGHTEN passthrough + mark-bar clearance over worst-case white media, ×4 severities). The plan's "none new" was written before P4 tokenization was scoped in.
- **The dead `snoozeChipFillAlpha` (`Color.Black@.55`) is retained, not deleted.** Its only consumer moved to `pinSurface@pinContainerAlpha`; deleting it is opportunistic cleanup outside M4's transcription surface, so it is documented in-source and deferred to M11's dead-token audit.

> **✅ RESOLVED IN M5 (PR #183) — snooze dot pulse motion now matches §05.** M5 retranscribed the pulse: `keyframes { 1600ms; 1f at 0; .45f at 800 (period/2); easeStandard }`, `RepeatMode.Restart`, hard-block-only + reduce-motion gate unchanged (`snoozeChipDotPulseAlpha` 0.45f, `snoozeChipPulsePeriodMs` 1600). The debt described below is retained for history. — M4 preserved the *pre-existing* pulse **verbatim** ("Behavioral outcome: none — pulse gate survives verbatim"), which is correct for this milestone: the parity plan froze pulse *behavior* for M4, so retranscribing its motion here would be out-of-scope design work. But the motion itself does not yet match §05. Frozen HTML (`warnings-recovery.html` :334–:337): `pulse 1.6s var(--ease-std)` with `50%{opacity:.45}` — i.e. **min opacity .45, 1.6s period, `cubic-bezier(.2,.8,.2,1)`**. Current Compose (`WarningSnoozeChip.kt` :85–:97): min alpha **.60** (`snoozeChipDotPulseAlpha`), **1.5s** `tween`/`RepeatMode.Reverse` (≈3.0s full cycle), **default `FastOutSlowIn`** easing. Depth, period, and easing all differ; `RovaMotion.easeStandard` (shipped in M1) is the correct easing and is currently unused here. **No milestone M5–M11 currently claims this retranscription** — it must be assigned to a milestone (M5 already touches banner pulse ×2 sites and is the natural home) or the divergence formally accepted, so full §05 parity is not silently lost. Independent review flagged this as the sole open observation on the M4 GO.

### M5 — TopBanner parity + CountdownRing removal — ✅ MERGED (PR #183 → `7a444ce1`)
**As shipped:** all planned token/ink migrations landed; CountdownRing + its 3 tokens and `AutoAction` + its static-30s placeholder deleted; trailing slot = always-present Stop pill. Suite 2353/0-0-0, 48 gates green, no new gate. Two things beyond the plan text below, both deliberate: (a) the **snooze pulse-motion retranscription** (the M4 tracked debt) was folded into this milestone — `keyframes` min `.45` at period/2, `1600ms`, `RovaMotion.easeStandard`, gate unchanged (`WarningSnoozeChip.kt`; +2 token pins). (b) The Stop pill's 48dp hit expansion is a **new pure helper** — `TouchTargetExpansion` + `Modifier.invisibleTouchTarget` (not `minimumInteractiveComponentSize`, which reserves layout and would grow the banner Row; the snooze chip can use the stock helper only because it is a standalone control). This resolved the **Investigation-2** finding (the falsification pass proved every banner sub is single-line, so the stock helper systematically inflated the banner). Device-verified RZCYA1VBQ2H (atomic session): banner 60dp not inflated; a tap 6.5dp above the visible pill fires Stop → pointer input not clipped to reported bounds. `+TouchTargetExpansionTest` (6). Orphan `R.string.warning_banner_auto_stop_protect` retained (cleanup deferred to M11 dead-token audit — deleting it now is opportunistic). Non-blocking, recorded not expanded: TalkBack reasoned from unchanged semantics (not on-device), rotation not exercised in the atomic pass, verification test recordings left on device.

### M5 — TopBanner parity + CountdownRing removal (original plan)
**Purpose:** Pinned banner to spec; delete the ring that "depicted a countdown the system never ran" (owner Q1).
**Files:** `ui/warnings/WarningTopBannerV3.kt` (ring composable deleted; container/inks/padding); `ui/warnings/WarningSheetContent.kt` (delete `AutoAction` + the static-30s placeholder; `TopBannerContent` loses the field); `RovaWarningsV3.kt` (delete 3 ring tokens); update `WarningSheetContentV3Test`.
**Visual outcome:** container `pinSurface@pinContainerAlpha` (.88→.94); title `mediaInk` (.88→.94); sub `mediaInkDim` .48 (unchanged value, new token); icon-box glyph `dot-ink`; Stop-pill label resolved `lbl-ink` (today fails SC 1.4.3); vertical padding 12; trailing slot is always the Stop pill; severity border α0.30 survives.
**Behavioral outcome:** ring gone (it was static chrome; no timer behavior existed to lose). ⋯ still only for the two echo ids (`:151` logic untouched). Stop pill + ⋯ hit 48dp via invisible expansion.
**Risks:** deleting `AutoAction` touches the content layer's public shape — mechanical, but sweep all 12 `midRecBannerContent` arms. Banner height must not grow from the 48dp hit expansion (HTML: invisible `::after`-style expansion; Compose: hit-area modifier, not min-height on the pill). **Regression risks:** live-region politeness regression; `checkA11yTargetSizeToken` curated-set additions.
**Tests:** content-layer test updates (no `autoAction` arm); sweep covers both banner backings (icon box, Stop pill).
**Device verification:** banner over live recording (escalating severity path `WarningCenter.kt:199`), idle echo path, Stop tap works, TalkBack order title→sub→"Stop, button", rotation with chrome group, cutout insets.
**DoD:** ring gone from source; pixel-parity vs §05 banner specimen; device checklist passed.

### M6 — WarningSheet parity — ✅ MERGED (PR #184 → `1a77f045`)
**As-shipped note:** transcribed to spec — opaque `pinSurface` container, hard-block sheets render **no** drag handle (dismissible keep stock `BottomSheetDefaults.DragHandle()`), title `mediaInk` 15sp/600 + body `mediaInkBody` 12.5sp, sevchip r-sm 10 @ .20 fill with resolved dot (MARK, byte-identical) + label (LABEL), why-row migrated to neutral media-ink (frozen `--ink-high` in pinned host, not severity), primary CTA severity-fill + `severityCtaInk`, ghost "Not now" preserved on all three hard-block ids, CANT_MERGE tertiary `cta-dest` (wrap-content, `hard@.30` border, mix .62 ink), overflow 28dp visual / 48dp hit via `minimumInteractiveComponentSize`, CTA `heightIn(min=48)`. Retired dead `sheetHandle*` + `sheetTitle/BodySize` tokens; `sheetCtaHeight` 46→48, `sevChipRadius` 10, `whyRowCornerRadius` 11→10, `secondaryCtaStrokeAlpha` .05→.12 (dead `sevChipForegroundAlpha`/`secondaryCtaTextAlpha` deferred to M11 audit, M4 precedent). **Reconciliation (falsification pass):** both pinned LABEL `ResolveInk.of` sites now feed the opaque `mediaInk.compositeOver(pinSurface)` as `top` (matches `TrustInkSites` `over(WHITE,.94,pin)`); raw `mediaInk` fed pure white (ResolveInk ignores `top`'s alpha) → resolved label drifted ~15/255 lighter than the frozen authority; fixed in `WarningSheetV3` (sevchip label) **and** `WarningTopBannerV3` (M5 Stop-pill label) together; MARK sites untouched. No new gate (protection = `TrustContrastSweepTest` + token/ink JVM pins). Suite 2354/0-0-0, 48 gates, `assembleDebug` green, two independent GO reviews. **Device verification deferred to the M6 device pass.** Non-blocking observations recorded (not M6 defects): pre-existing sheet vertical rhythm, sheet border-top hairline, CTA content padding, `sevChipPaddingH` 10-vs-8, ghost premultiply.

### M6 — WarningSheet parity (original plan)
**Purpose:** Modal sheet to spec: honest drag handle, severity-filled primary CTA, ghost secondary, spec type scale.
**Files:** `ui/warnings/WarningSheetV3.kt`; `RovaWarningsV3.kt` (delete handle + title/body-size tokens); `RovaWarningsV3Test` (unpin deleted tokens).
**Visual outcome:** opaque `pinSurface` sheet, r-sheet 26 top corners, 420dp max content width, 60ch body cap; **hard-block sheets render no drag handle**, dismissible sheets use stock `BottomSheetDefaults.DragHandle()`; icon 56dp r-lg tile, sev 16% fill + 22% border, glyph `dot-ink`, 22dp bloom kept; eyebrow severity chip r-sm 10 @ 0.20 fill with resolved inks; title 15sp/600, body 12.5sp `mediaInkBody`; why-row r-sm 10, min-height 36; CTAs min-height 48 r-md 14.
**Behavioral outcome:** primary CTA on hard-block/severity sheets becomes **solid severity fill + `severityCtaInk`** (today: accent fill). "Not now" ghost preserved on all three hard-block ids (`WarningSheetContent.kt:113,119,125` — Transcription note: none may lose it). Scrim/back blocking for HardBlockSheet only (`WarningCenter.kt:191`) untouched. CANT_MERGE tertiary destructive (declared `WarningSheetContent.kt:201`, rendered `WarningSheetV3.kt:197`) restyles to `cta-dest`.
**Risks:** the biggest visual delta of the plan (primary CTA accent→severity). §01's fill decision table is the law: resolves-the-severity-condition → `--sev`; constructive choice → accent. Transcribe per-id from the HTML specimens; when in doubt the HTML gallery (§10 secondary column) is normative. `confirmValueChange` hard-block non-dismissal must survive the handle change. **Regression risks:** `WarningSheetContentV3Test` copy pins; TalkBack focus order; `checkNoHardcodedUiStrings`.
**Tests:** content tests updated; sweep covers `sheeticon`/`pinchip` sites + severity-CTA ink over all four fills.
**Device verification:** all three hard-block sheets (no handle, scrim/back blocked, "Not now" collapses to chip, Start stays gated), one advisory sheet (handle, scrim dismiss), MICROPHONE_DENIED "Continue without audio", CANT_MERGE 3-way, 200% font scale (no clipping, CTAs stack), TalkBack.
**DoD:** pixel-parity vs §05 hard-block + advisory specimens; device checklist passed.

### M7 — Themed hosts: HistoryStrip + SettingsChip — ✅ MERGED (PR #185 → `8440c7b7`)
**As-shipped note:** the first `ResolveInk` consumers whose backings come from the live `MaterialTheme.colorScheme` (M4–M6 used the pinned tokens). New pure `ThemedHostInk` (house pure-helper seam) computes `tint` + the resolved strip/set inks + the themed body, feeding `ResolveInk` the **exact** `stripchip`/`set` backings `TrustInkSites`/`TrustContrastSweepTest` model, so shipped == swept: strip sevchip dot/label over `sev@.20` on the tint (MARK/LABEL), settings dot + "Fix" over the tint (MARK + accent `MIX_ACCENT`). Strip: r-md 14, fill `color-mix(sev 8%, surface)`, border sev .25 (was .30), bordered badge → filled `.sevchip.on-tint` (sev@.20, r-sm 10, resolved 5dp dot + label), **body** added via `quietTextColor`, 48dp X unchanged. Settings chip: r-sm 10, min-height 48, same tint fill + border, **leading 7dp resolved dot** + **trailing "Fix"** in resolved accent-as-text (hue = **raw** palette accent via `cs.inversePrimary` == `RovaPalette.accent`, never the deepened CTA `primary`), non-dismissible unchanged. **Root-cause finding:** `rovaQuietText` does NOT strip `onSurfaceVariant`'s alpha, so on a light scheme it paints `textDim@.6` → **~4.25:1 on Daylight (fails SC 1.4.3)** — the very island §06 removes; `ThemedHostInk.themedBody` strips the alpha and re-supplies it as `dimAlpha` (mirrors `TrustInkSites.quietOver`): solid on light (~14:1), dimmed on dark. `+ThemedHostInkTest` (6: Daylight deepen · dark MARK passthrough · the quiet-body trap asserting naive-dimmed `< 4.5` AND solid-clears · tint identity) `+warning_settings_fix_action` (`checkNoHardcodedUiStrings`). No new gate (protection = pure helper JVM tests + the M2 sweep, ADR-0036/0038 precedent). Suite **2360/0-0-0**, 48 gates, `assembleDebug` green, two independent GO reviews (fresh-context falsification, pre- and post-commit). **Behavioral outcome: none** — WarningCenter routing/precedence/dismissal/recovery untouched, no shared `TrustRow`, no merged semantic API. **As-shipped notes (recorded, not defects):** the frozen `.setchip` carries severity by dot **colour** alone — the severity WORD survives only in the folded a11y `contentDescription` (faithful §06; any concern routes through the HTML first); "Fix" `labelLarge` + sevchip label without `.13em` tracking (typographic approximations consistent with M4–M6); strip trailing `end=4.dp` compensated by the `IconButton` inset (both are items for the post-M7 pixel-parity checkpoint). **Device verification deferred to the pixel-parity checkpoint** (M7 is presentation-only, no behavior-touching device gate).

### M7 — Themed hosts: HistoryStrip + SettingsChip (original plan)
**Purpose:** The two palette-following surfaces; kills the near-black island pattern on Daylight via resolved inks + `quietTextColor`.
**Files:** `ui/warnings/HistoryWarningStrip.kt`, `ui/warnings/SettingsPermissionsSection.kt`.
**Visual outcome:** strip card r-md 14, fill sev 8% over surface, border sev 25%, sevchip resolved (`stripchip` site), body `quietTextColor`; settings chip r-sm 10, min-height 48, 7dp resolved dot, trailing "Fix" in resolved `acc-ink`.
**Behavioral outcome:** none — strip dismissible per-session with down-edge re-surface, settings chips not dismissible, allowlists untouched.
**Risks:** these surfaces currently tint from `accentFor(surface)` with their own alphas — the migration is mechanical but audit every `copy(alpha=…)` against the spec value. **Regression risks:** SET-14 merged TalkBack description; `WarningScreenAllowlistTest` untouched.
**Tests:** sweep covers `stripchip` + `set` sites ×4 severities ×12 palettes.
**Device verification:** History strip with 2 concurrent warnings on Daylight + Aurora; Settings chips ×3 on Daylight (the palette that exposes bad fixed mixes); "Fix" opens the sheet.
**DoD:** pixel-parity vs §06 specimens on Aurora and Daylight; device checklist passed.

### M8 — RecoveryCard parity + recency + progress-chip fix

> **✅ MERGED (PR #186 → `4683d695`, 2026-07-11). As-shipped:**
> - **Themed, not pinned:** container `#0B0D14@.94` → elevated **`surfaceHi`** token + themed `--edge` border (`colorScheme.outlineVariant`), follows the palette (§06). r-lg 20→18, padding 16/18→16/16, glow kept. A `surfaceHi(surfaceBase, isLight)` overload lets the composable derive the backing from `colorScheme.surface` byte-identically (no palette CompositionLocal).
> - **TrustRow:** single leading 7dp resolved dot; the ADR-0031 SemanticIcon emblem **and** the sev-chip are removed (frozen `.recov` has only a dot, `:362`/`:1625`). **The parity-plan prose below ("eyebrow = severity chip") is STALE vs the frozen HTML — the code correctly followed the HTML authority.** Pulse (KILLED_BY_SYSTEM only) retranscribed to the shared `@keyframes pulse`.
> - **Recency eyebrow** `TAG · recency` (one AnnotatedString, tabular-nums recency span); first RENDER consumer of the M3 `RelativeTimeLabel`; `recencyText()` resolves all 6 rungs incl. DATE.
> - **CTAs:** accent primary (`colorScheme.primary`/`onPrimary`), ghost Keep, **`cta-dest` outline** Discard (wrap-content, never full-width, terminal — replacing the pre-freeze solid-red fill). Progress strip drawn **only while merging**, header removed, chip = **filled/total** via pure `recoveryProgressFilledCells` (fixes `:408` N/N); ProcessingGlyph retained (§11 `:864`) leading the strip. Artifacts list now rendered.
> - **Ink:** new `ThemedHostInk.forRecovery` (`recov` INK_SITE — dot MARK on `surfaceHi`, `top=onSurface∘surfaceHi`) == the swept backings; **shipped == swept**, no sweep edit.
> - **Copy:** 3 EN eyebrow tags → frozen `KINDS` verbatim; new `recency_*` strings + plurals in `values/` **and** `values-es/` (SpanishCatalogParityTest + plural parity green).
> - `RecoveryUiState`/mapper/ViewModel/schema **untouched**. `+5` fill tests (RED-proven). Gate count **48**, suite **2365/0-0-0**. Two independent GO reviews; reconciled one Recommended (dropped a computed-but-dead `RecoveryInk.vendor`).
> - **Deferred (tracked):** frozen `.vendor` borderless `acc-ink` link styling (LibraryScreen + a glyph are outside M8's file set — only its POSITION transcribed) → BACKLOG; §07 merged a11y node → device pass; ProcessingGlyph placement → pixel-parity. **Device verification is the separate mandatory pre-release gate.**

**Purpose:** The largest transcription: themed elevated card, TrustRow ordering, CTA re-roling, recency eyebrow, and the `:408` N/N fix.
**Files:** `ui/recovery/RecoveryCard.kt`; `ui/recovery/RecoveryUiState.kt` (consume M3 recency); `strings.xml` (copy audit vs §07 `KINDS` verbatim); extend `RecoveryProgressA11yTest` + mapper tests.
**Visual outcome:** container `surfaceHi` + 1px `edge` border, r-lg 18, padding 16/16, 28dp top-edge glow kept; eyebrow = severity chip (0.14→**0.20** fill, resolved inks) + " · " + relative recency (tabular-nums); title 15sp, body `quietTextColor`; artifacts line 11.5sp; primary Merge/Retry = `cta-accent` (accent-fill / house #0E1116 ink via `DialogActionColors`), Keep = ghost, Discard = `cta-dest` outline **replacing today's solid red fill**, own row, never full-width; vendor link `acc-ink` + external glyph, min-height 48; progress strip cells track 5%/filled accent-fill, numchip.
**Behavioral outcome:** progress chip reads **filled/total** (`progress × survivingArtifacts.size` / `survivingArtifacts.size`), fixing `RecoveryCard.kt:408`; merge-in-flight disables Merge and Keep, Discard stays live (unchanged); pulse only KILLED_BY_SYSTEM (unchanged); severity-per-kind mapping untouched (T4).
**Risks:** Discard restyling (solid red → destructive outline) is the second-biggest visual delta — it is §01's law ("destructive… never full-width, always terminal"), owner-frozen; do not soften it back. Recency label wording must come from resources with plurals. **Regression risks:** `recoveryProgressContentDescription` pins; live-region matrix (polite merging / assertive failure / none idle); merged a11y node wording per §07.
**Tests:** mapper test — numerator/denominator across progress values (0, 0.5, 1.0, rounding); recency label injection; a11y description updates; sweep covers `recov` site.
**Device verification:** all 6 kinds staged (or the reachable subset + JVM coverage for the rest, per ADR-0036 precedent), merge start→progress→complete, KILLED_BY_SYSTEM vendor CTA + pulse, reduce-motion static, Daylight + Aurora, TalkBack merged node.
**DoD:** pixel-parity vs §07 specimens; N/N proven fixed on device; device checklist passed.

### M9 — Merge-failure closed reason set (Q6)
**Purpose:** Replace the raw-diagnostic pass-through with the owner-locked 4-cause set; "never a pass-through".
**Files:** new `ui/recovery/MergeFailureReason.kt` (pure classifier → {STORAGE, UNREADABLE, INCOMPLETE, UNKNOWN}); `RecoveryUiState.kt` (`mergeFailedReasonRes` beside the raw string, raw kept for logs only); `RecoveryCard.kt` (failbox renders the `@StringRes`; body keeps bolded "Your clips are safe."); `strings.xml` ×4; new `MergeFailureReasonTest.kt`.
**Visual outcome:** failbox r-sm 10, sev-hard 8% fill + 22% border; Retry primary `cta-accent`, Keep ghost, Discard dest.
**Behavioral outcome:** user-visible failure copy is always one of four localized strings; unmapped causes → UNKNOWN. No state-machine change (ADR-0017's transient `mergeFailedReason` mechanics untouched).
**Risks — classifier input is the TYPED chain, not the message string.** The only producer reaching the failbox today is `RecoveryViewModel.kt:85–91` (`RecoveryMergeOutcome.MuxFailed(cause) → cause.message ?: "merge failed"`); typed `InsufficientStorage` routes to the CANT_MERGE sheet instead, and `RecoveryMerger.kt:134–148` collapses `CopyFailed`/`RenameFailed`/`PendingInsertFailed`/`FinalizeFailed`/`ManifestWriteFailed`/`SafFolderUnavailable` into `MuxFailed` with synthesized/dynamic messages (e.g. `RovaApp.kt:752`). Message strings are therefore an *open* set — classify **at or before `RecoveryMerger.toRecoveryOutcome`** (over the pre-collapse `ExportResult` variants and/or `MuxFailed.cause`'s Throwable type chain); message inspection is a last-resort heuristic only; UNKNOWN is the fallback. `@StringRes` on the state is Context-free, preserving the VM's JVM-testability. This also closes the documented `checkNoHardcodedUiStrings` opt-out for the render path — verify the opt-out marker can be removed without gate edits. **Regression risks:** Retry flow, assertive live region, CANT_MERGE routing (InsufficientStorage must keep going to the sheet, not the failbox).
**Tests:** classifier — every typed `ExportResult`/outcome variant → its cause; unmapped Throwable/garbage → UNKNOWN; stability (same input, same cause).
**Device verification:** forced merge failure (storage-revoke path) shows the STORAGE string + Retry; retry succeeds after restore.
**DoD:** no raw exception text reachable in UI; all four strings localized.

### M10 — Reduce-transparency wiring
**Purpose:** §11: "over-media substrates fully opaque — currently unread by these surfaces; the freeze wires it."
**Files:** `WarningTopBannerV3.kt`, `WarningSnoozeChip.kt` (the two over-media translucent containers); a small pure policy if a seam doesn't already exist.
**Contents:** when the reduce-transparency signal is active, `pinContainerAlpha` renders as 1.0. **The seam already exists — use it:** `ui/components/ReducedTransparency.kt` (pure `reduceTransparency(highContrastText, reduceMotion)` + `rememberReduceTransparency()`, tested by `ReducedTransparencyTest`, consumed app-wide via `GlassEnvironment.reduceTransparency`, `Theme.kt:66`). The HTML (§11) names exactly this signal. Do not invent a new signal class.
**Visual outcome:** none by default; opaque capsules under the a11y setting. **Behavioral outcome:** none.
**Risks:** the seam's semantics are `highContrastText || reduceMotion` — reduce-motion users also get opaque capsules. That is the existing app-wide seam behavior; if it is unacceptable for these two surfaces, route the question through the HTML (new iteration), never fork the seam locally. **Regression risks:** none when signal off (alpha path byte-identical).
**Tests:** pure policy test (signal on → 1.0, off → 0.94).
**Device verification:** toggle high-contrast text (the seam's actual trigger — Android has no literal reduce-transparency switch), observe opaque banner + chip.
**DoD:** wired behind the seam; default rendering byte-identical.

### M11 — Cleanup: renames, dead tokens, final parity audit
**Purpose:** Retire superseded names and prove whole-system parity.
**Files:** rename `RovaWarningsV3.kt`→`RovaTrustTokens.kt`, `WarningSheetV3`→`WarningSheet`, `WarningTopBannerV3`→`WarningTopBanner`, `RovaWarningsV3Test`→`RovaTrustTokensTest`; delete any token whose last consumer migrated; final `checkA11yTargetSizeToken` curated-set audit; `memory/` topic file; CLAUDE.md status sentence.
**Visual outcome:** none. **Behavioral outcome:** none.
**Risks:** rename churn across call sites — mechanical, single PR, no logic edits mixed in. **Regression risks:** gate rules that match on names (`checkA11y*` scan patterns) — verify gates still RED-fire on a planted violation post-rename.
**Tests:** suite green; one planted-violation RED-proof per renamed-scope gate.
**Device verification:** full Trust System pass: 3 hard-block sheets, 1 advisory sheet, banner (active + idle echo), chip collapse/restore round-trip, History strip, Settings chips, all reachable recovery kinds, merge success + failure, Daylight + Aurora + one more palette, 200% font, TalkBack, reduce-motion, reduce-transparency.
**DoD:** pixel-parity review (device screenshots vs frozen HTML, per house pipeline) signed off; docs/memory stewardship done.

## 8. Testing strategy

**Pure JVM (the project's only automated tier — new tests, all named):**
- `ResolveInkTest` — algorithm branches, boundary at `DARK_BACKING=0.18`, minimum-t selection, `MAX_T` fallback, mark passthrough, only-Daylight-deepens property (M2)
- `TrustContrastSweepTest` — the 78-pair × 12-palette × 3-frame matrix; fixture pins against ratios the HTML displays (M2)
- `RelativeTimeLabelsTest` — ladder boundaries + DST (M3)
- mapper extensions in `RecoveryViewSourceTest`/`RecoveryViewModelTest` — Clock injection, recency inputs (M3), progress numerator/denominator (M8), `mergeFailedReasonRes` (M9)
- `MergeFailureReasonTest` — closed-set classifier (M9)
- `RovaWarningsV3Test` extensions — new token pins (M1), deletions unpinned (M5/M6), rename (M11)
- reduce-transparency policy test (M10)
- `RecoveryProgressA11yTest` extensions — chip text + content description (M8)
- build-logic golden tests for every `checkA11yTargetSizeToken` curated-set addition (M4–M8)

**Compose UI tests:** none. Project test policy is JVM-only (no Robolectric, no new instrumented tests); interaction/visual verification is carried by the device checklists above.

**Accessibility verification:** per-milestone TalkBack items in each device checklist (spoken order, merged nodes, live regions per §07's matrix, decorative `contentDescription=null` on dots/glyphs/pulses/blooms); role assertions ride `checkA11yClickableHasRole`.

**Contrast verification:** `TrustContrastSweepTest` in CI (every milestone); existing `TokenContrastTest` untouched and still green.

**Reduced motion:** `checkA11yAnimationGated` (static gate) + device toggle in M4 (pulse), M6 (sheet enter/exit → instant swap), M8 (recovery pulse, ProcessingGlyph).

**Device verification (RZCYA1VBQ2H, mandatory for behavior/visual milestones):** listed per milestone; M11 runs the consolidated full pass.

**Manual review checklist (every visual milestone's PR):** side-by-side device screenshot vs frozen HTML specimen; ladder audit (no new inline dp for padding/gap/radius); token-family audit (no new raw hex, no severity alpha-mutation outside the documented decorative fills); §01 ordering audit (mark → answers 1–4; ghost/destructive never precede primary; destructive terminal, never full-width).

## 9. Review checkpoints & rollout

- **Per milestone:** independent Review Agent (fresh context) → Required Fixes reconciled → merge. Behavior-touching milestones (M5, M6, M8, M9) additionally get device verification before merge.
- **Pixel-parity checkpoint** after M7 (all warning surfaces) and after M9 (recovery complete): screenshot set vs HTML, mismatches are findings routed to the HTML if the spec is ambiguous, to code otherwise.
- **Rollout:** one PR per milestone onto master, no feature flag — each surface is self-consistent the moment its milestone lands, and tokens are additive until M11. No release is cut mid-stream unless all merged milestones' device checklists have passed; version bump at M11.

## 10. Explicit parity checklist (HTML section → work)

| HTML section | Invariant(s) | Milestone |
|---|---|---|
| §01 Four Answers + TrustRow | ordering law; fill decision table; ghost/dest never precede primary; dest terminal never full-width; hierarchy by weight/opacity | M4–M9 (audited in every surface PR checklist) |
| §02 Token registry | three families; `surfaceHi` DERIVED; `tQuiet` routing; accent-fill/ink | M1 (tokens), M7/M8 (`quietTextColor` routing) |
| §03 Contrast inspector | "a specimen that fails cannot be frozen" → CI sweep | M2 |
| §04 Severity matrix | 4 locked colors; meaning survives without color (dot+label+glyph) | M2 (inks), M4–M8 (three channels present per surface) |
| §05 Record host | sheet (no handle on hard-block, opaque pin, sev CTA), banner (.94, Stop pill, no ring), snooze chip (.94 fix) | M6, M5, M4 |
| §06 Themed hosts | strip r-14 sev-8% fill; settings chip r-10 min-48 "Fix" acc-ink | M7 |
| §07 Recovery cards | severity-per-kind DO-NOT-RE-DERIVE; TrustRow copy verbatim; CTA roles; live-region matrix; recency eyebrow | M8 (+M3) |
| §08 Merge failure | Q6 closed 4-cause set; "Your clips are safe."; Retry primary accent | M9 |
| §09 Interactions | sheet→chip→restore motion; merge in-flight disables Merge+Keep, Discard live; progress strip only while merging; **filled/total chip** | M6 (motion), M8 (merge states + chip fix) |
| §10 State gallery | 21 ids, surface mapping frozen; two dismissal axes; "Not now" on all 3 hard blocks; EXACT_ALARM_DENIED transcribed as-is | P8 (no change) + M6 preserves "Not now"; divergence = non-goal |
| §11 Responsive & motion | 3-animation inventory; reduced-motion total gate; 420dp/60ch; 200% scale; truncation rules; reduce-transparency | M4/M5/M6/M8 (motion+layout), M10 (transparency), device checklists (scale/truncation) |
| §12 + APPX-A | ladders; "transcribes, does not recompute"; 6-item shape-primitive exception | M1 + P4 audit every PR |
| APPX-B | pinned .94 unification; contrast by inspector never by hand | M1/M2/M4/M5 |
| APPX-C | two-resolver boundary (severity fills never through `DialogActionColors`) | M2/M6 (+ P2 audit) |
| APPX-D | `ResolveInk` contract; INK_SITES; SC 1.4.11 exemption list; blooms are not `Modifier.blur` | M2, T6/T7 |
| APPX-E | Q5 invisible hit expansion; min-heights; curated-set additions | M4–M8 |
| APPX-F | Q2 alias invariant (`RovaWarnings` defined-as `RovaSemantics`, never collapsed); token fate table; disclosed ink bumps | M1 (aliases untouched), M5/M6 (deaths), M11 (renames) |
| APPX-G | Q3 recency ladder; no schema change; Clock seam; never a live region | M3, M8 |
| APPX-H | count axes; CLAUDE.md 17→21 | M0 |
| Version-history transcription notes | :408 numerator; ⋯ two-id gate; pulse gate; two axes; surfaceHi DERIVED; #0E1116 accent ink | M8, M5, M4, M6, M1, M8 |

## 11. Acceptance criteria (whole plan)

1. Every §10 checklist row done; pixel-parity sign-off at both checkpoints and at M11.
2. Suite green (baseline 2285 + all §8 tests), 48 gates green, `TrustContrastSweepTest` green across 12 palettes × 3 frames.
3. The four mandated behavior deltas shipped and device-proven: N/N fixed, ring gone, closed reason set live, hard-block handle gone.
4. No behavioral drift anywhere else: `WarningIdOrderTest`, `WarningScreenAllowlistTest`, `WarningCenterAggregateTest`, precedence/surface tests all untouched-and-green.
5. ADR-0013 amendment merged before the first code milestone; docs/memory stewardship done at M11.

## 12. Explicit non-goals

- **EXACT_ALARM_DENIED container change** — the doc-vs-code divergence is transcribed as-is (HardBlockSheet); changing it needs its own ADR + HTML iteration.
- **No new warning states**, no precedence/gating/snooze/hysteresis/merge-state-machine/allowlist changes, no `SessionManifest` schema change (NO-GO #1/#4/#5), no warnings hub screen (NO-GO #9), no mid-session full-card overlay (NO-GO #10).
- **No bento visual restyle** of Trust surfaces — the parity is methodological (tokens/contrast/ladders), not stylistic.
- **No TrustRow composable, no CaptionScrim primitive, no shared severity-chip composable up front** — the HTML unifies orderings and values, not markup (extract only on proven byte-identical duplication).
- **No CountdownRing return** ("may return only when backed by a real countdown"), no real auto-stop ticker.
- **No collapse of `RovaWarnings` into `RovaSemantics`** (Q2 alias invariant; would trip `checkStatusColorLocked` at dozens of sites).
- **No `WarningCenterContract.md` rewrite** — behavior clauses are current; only the count-axes pointer (M0) if the owner wants it.
- **No Compose UI/instrumented tests** — against project test policy.
- **No gate weakening, ever** — curated-set additions and opt-out-marker removals only.
