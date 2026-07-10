# Rova — Product & Design Surface Review

**Date:** 2026-07-09
**Type:** Design + product review only. No implementation.
**Benchmark:** Library "Adaptive Bento Timeline" (ADR-0030) as the gold standard.
**Method:** Grounded read of every primary surface (not from memory) + external reference research.

---

## 0. The one-sentence finding

Rova is **architecturally mature with an un-generalized design methodology**: every surface is individually competent and accessibility-first, and the shared *atoms* already exist (`RovaPalette`, `RovaTokens`, `SemanticIcon`, `DialogActionColors`, the `StatusColorLocked` gate span multiple surfaces). What is *not* shared is the Library's **method** — its single color-entry-point (`rememberLibraryColors`) and its tested identity/locked-contrast contracts. Two of the apparent "other canons" are correct *by design* (Player's cinematic black; Warnings' severity-locked palette, ADR-0031 §4), so this is a **rigor gap, not four competing design languages.** The gap between the Library and everything else is not a "prettiness" gap — it is a **methodology and trust** gap.

> **Reconciled after independent review (GO WITH FIXES, 2026-07-09):** an adversarial pass corrected three overstatements in the first draft — (1) sub-48dp touch targets are *house-standard* misses, **not** WCAG AA regressions (SC 2.5.8 minimum is 24dp; only the α0.48 banner text is a genuine AA *contrast* item); (2) Storage Management is **not** greenfield — keep-latest retention, batch deletion (ADR-0036), and the storage warning ladder already ship, so it is thin UI over existing machinery; (3) a "freeze the whole design system first" gate is itself the speculative-capacity spend this doc rejects for Storage — the shared contract should be *harvested from* the first surface freeze, not designed up front. All three corrections are folded into the sections below. The constraint the roadmap review named ("product signal, not engineering capacity") shows up here as **cohesion and trust debt, not missing features.**

---

## 1. Current product maturity assessment

**Engineering maturity: very high.** 38 ADRs, 48 load-bearing static gates, 2258 tests, config-cache-clean, device-verified merge/recovery/player pipelines. The reliability backbone is done and defended.

**Design maturity: uneven — one exemplary room in an otherwise solid house.**

The Library is not just the best-looking surface; it is the only surface built on a **disciplined design *method***:

- **Single color entry point** — `rememberLibraryColors()` reads the active palette once; a theme swap restyles the whole surface for free. Every other surface reads color from a different place.
- **Identity-vs-locked color split as a *tested contract*** — overlay-over-media tokens (scrims, progress hairline) carry a proven white-text AA / ≥3:1 contract (`TokenContrastTest`, `CaptionScrimTest`); `mediaProgress` is a *separately named semantic role* from `accentFill` even though the value is identical today.
- **Pure, JVM-testable seams** — `BentoRowPlanner`, `BentoWashPolicy`, `LibraryColorSpec`, `ScrubberIndex` — layout/color/contrast verified without a device.
- **Frozen-spec provenance** — values port from `docs/design/library-bento.html` with explicit "do not re-derive" contracts, so implementation cannot drift from design.
- **Trust-preserving motion** — entrance plays *exactly once* (`rememberSaveable`), no autoplay, no auto-scroll, no auto-reposition; total reduced-motion gating (every animation degrades to `snap()`).
- **Touch targets defended against parent clamps** (`requiredSizeIn(48,48)` where a plain `sizeIn` got shrunk).

**This is the real bar.** It is a *process* bar (HTML-first → freeze → pure seams → tested contracts → trust rules) as much as a visual one. The other surfaces predate this pipeline and were built to a lower — still good — standard.

**The three (four) canons, concretely:**

| Canon | Where it lives | Color source | Type | Notable |
|---|---|---|---|---|
| **Library** (gold) | Library | `rememberLibraryColors()` + `colorScheme` | M3 + `tnum` | single entry point, tested contracts, frozen HTML |
| **Glass / `RovaTokens`** | Record chrome, dialogs, bottom-nav | `RovaPalette` + `GlassSurface` | custom `RovaTokens` styles | modern, coherent, `DialogActionColors` AA resolver |
| **Warnings** | Warning sheets, Recovery | `RovaWarnings` (4 fixed hexes) + `RovaWarningsV3` | `MaterialTheme.typography` | severity-locked palette; `V3` naming debt |
| **Bespoke** | Player, some tile hexes | raw `Color.Black` / `Color.White.copy(alpha)` | raw M3 slots | cinematic-black island; off-token by design |

No surface outside the Library uses the Library's color/type/spacing path. **The gold standard is currently un-shared infrastructure.**

---

## 2. Surface-by-surface design review

Each surface is scored on distance from the Library bar, with findings separated by class: **[V]** visual · **[I]** interaction · **[IA]** information architecture · **[P]** product · **[Impl]** implementation detail.

### 2.1 Recording — *closest to gold; one real UX friction*

The glass-over-camera chrome is a legitimately distinct, current language (not outdated), with sophisticated reduced-motion-gated rotation and strong semantics (FAB 56dp, `liveRegion`, HUD boundary-only announcement). Research validates the direction: Halide/Kino/Linear all preach "one accent as a flashlight" — which maps directly onto Rova's existing `StatusColorLocked` gate.

- **[I][IA] Mode picker is not tabs — it is a tap-to-cycle `ModeCycleChip`** (Portrait→Landscape→P+L by repeated tapping; the `↻` affordance glyph was deliberately removed). Tap-to-cycle through three modes is low-discoverability and gives no preview of the alternatives. This is the single most concrete UX friction on the surface. *(Research: Halide/Blackmagic keep mode selection explicit and legible.)*
- **[V] "Recording in progress" is a badge, not a perimeter.** Kino's tally-light ring following the screen curvature is the calm, unmistakable idiom for a capture-in-progress truth. For a *background* recorder this truth is the single most important thing the chrome communicates. Worth exploring a quiet edge affordance. *(Inspiration only.)*
- **[Impl] Inline magic numbers** interleaved with tokens (`top = 14.dp`, mode chip `vertical = 4.dp`, HUD spacers `8.dp`) undercut the otherwise data-driven `ChromeSlotPlacement`.
- **[V] Icon-only bottom nav (no visible labels).** Defensible (a11y via contentDescription), but worth a deliberate decision vs. the Library's editorial legibility.

### 2.2 Player — *biggest token outlier, but the divergence is mostly correct*

The Player is the most off-token surface (raw `Color.Black`, `Color.White.copy(alpha)`, raw M3 type). **Challenge the reflex to "fix" this:** a fullscreen video player *should* be cinematic black — a bento/glass treatment over live video would be wrong. Its accessibility is the **best in the app** (full `progressBarRangeInfo`, `setProgress`, segment prev/next custom actions, polite live-region, 48dp hit-box over a 3dp bar). The wall-clock playhead (ADR-0032) is a genuine, distinctive strength.

- **[Impl] It has *no* token discipline of its own** — every alpha is a magic literal, `formatMmSs` is duplicated across two files, chrome fades are untokened (no `RovaMotion` duration). The fix is **an internal "player token" set**, *not* library-fication.
- **[I] Single-tap incurs ~300ms double-tap-resolution latency** (owner-accepted). Acceptable; note it.
- **[I][a11y] Double-tap edge-seek is TalkBack-invisible** (mitigated by explicit Replay10/Forward10 buttons). Fine.
- **[V] The media is not yet the scrubber.** Research (Voice Memos waveform, Mux/img.ly) argues for thumbnail-at-scrub + haptic snap at clip boundaries. Clip ticks already exist; this is an *enhancement* opportunity, not a defect. *(Inspiration.)*

**Verdict: do not redesign. Tokenize internally and move on.**

### 2.3 Recovery — *strong; the trust surface that already mostly works*

Best-in-class IA and copy discipline: only the newest interrupted card shows (older collapse to a `hiddenCount` footer — avoids a wall of red), all copy is `@StringRes`/`UiText`, real per-OEM auto-start guidance (MIUI/Samsung/OnePlus/Vivo/Oppo) with `resolveActivity` gating and graceful fallback, adaptive CTA stack with Discard always last, in-flight merge safety. Research (error-UX literature, Figma offline banner) strongly endorses this "state the condition + one action, recover fast" framing.

- **[V] It lives on the *warnings* canon** (`RovaWarnings` severity hexes + `RovaWarningsV3`, card radius 20dp vs bento 16dp, bespoke `0xFF0B0D14`). Internally coherent, but a second canon.
- **[a11y] CTAs are 40dp tall — below the house 48dp `minHitTarget`** (meets WCAG 2.5.8's 24dp floor, but under Rova's own comfortable bar).
- **[IA][P] No recency signal / timestamp** ("moments ago"); the progress strip uses artifact-count as a proxy for "recovered N segments" (acknowledged limitations). For a trust surface, "when did this happen" is a real gap.
- **[V][P] Two hues on one card** — advisory-blue primary CTA beside red destructive. Intentional, but the research ("use red sparingly," Things' no-guilt principle) suggests pressure-testing whether the destructive red needs to be that loud.

### 2.4 Warning sheets — *good model, house-standard target misses + one AA contrast item*

The sheet-collapses-to-chip + top-banner model is modern and **externally validated**: Carbon/PatternFly/UX-Movement all say match disruptiveness to severity — modal only for progress-blocking decisions, banners for persistent-non-urgent, nothing critical auto-dismisses. Rova's "only `CAMERA_PERMISSION_DENIED` / `STORAGE_INSUFFICIENT` gate Start; `EXACT_ALARM_DENIED` is a flat banner" is textbook-correct.

- **[a11y] Multiple sub-48dp touch targets:** overflow `⋯` 28dp, banner `⋯` 40dp (`WarningTopBannerV3.kt:156`), snooze chip ≈26dp tall. These **pass WCAG AA** (SC 2.5.8 = 24dp) — they are misses against Rova's *house* 48dp comfortable standard, not AA violations. Still worth fixing on the surface whose whole job is to be tapped, but label them honestly.
- **[a11y — the one real AA item] Banner sub-text at white α0.48** (`WarningTopBannerV3.kt:117`, composited over a α0.88 `0xFF0B0D14`) is likely sub-4.5:1 — a genuine **SC 1.4.3 AA contrast** concern, and *inconsistent* with the sheet body, which was deliberately bumped to α0.55 with a documented AA rationale. This one is a real regression hiding next to a fix. (Run an actual contrast calc before asserting it in a freeze.)
- **[Impl] Dead tokens:** `sheetTitleSize` (15sp) and `sheetBodySize` (11sp) are defined-but-unused — the sheet actually renders `titleMedium`/`bodySmall`. Token discipline theater.
- **[Impl] `V3` naming on every token and composable** signals iterative-reskin lineage — naming debt.
- **[IA] Cross-host surfacing divergence:** the *same* warnings render as sheet/chip/banner on Record but as an inline **strip+chip** on History/Settings. The surfacing model is not unified across hosts.
- **[Impl] Raw hex** (`0xFF0B0D14`) inline in the banner.

### 2.5 Settings — *furthest structurally; a half-migrated screen*

A competent, accessibility-first **M3 inset-list** (nine+ flat label→row sections, hairline dividers). A11y is its best dimension (48dp rows, `Role.Switch` single-node toggles, `heading()` sections, focus rings, polite stepper announcements).

- **[V] Only ONE section (Appearance) is a glass card** — an explicit "PR1 proof surface." The other ~9 sections render flat. The screen visibly looks half-redesigned.
- **[V] Two color systems side by side** — M3 alpha tokens for the body, `RovaPalette` glass for the one card + theme grid.
- **[V][I] Stock `android.app.TimePickerDialog`** for schedule times — a raw framework dialog that matches nothing in the Liquid-Glass identity. Stock `FilterChip`s for weekdays/keep-latest.
- **[I] Numeric settings are stepper-only** (−/value/+) — crossing a large interval range is many taps; no slider, no type-entry. Main interaction friction.
- **[I] The premium `RovaAlertDialog` is the most polished component in the app — and Settings' own body does not match it.**
- **[Impl] Inline magic numbers** (`11.dp` gap, `9.dp` chip radius, `5.dp` badge) and a one-off `RovaWarnings.soft` inline on the battery badge.
- **[Impl] Motion: essentially none** — conditional Schedule rows pop in with a bare `if`, no expand animation, no reduced-motion seam (nothing to gate, but also nothing considered).

### 2.6 Bottom-nav / app shell — *note the actual structure*

There is **no app-wide bottom navigation.** `MainScreen` is a `NavHost`; Settings is a full-screen pushed route, not a tab. The only nav bar is `RecordBottomNav` (Library · center FAB · Settings) living inside the Record chrome — glass circles, `DialogActionColors` FAB, landscape rail, icon-only. It matches the glass polish but is a Record-screen component. This is fine, but it means "Settings" and "Library" are peers reached only from Record — worth a conscious IA decision, not an accident.

---

## 3. Prioritized redesign opportunities

Ranked by **leverage** (trust + cohesion impact ÷ effort), not by how broken each surface is.

**P0 — Warnings + Recovery trust-language freeze, and *harvest* the shared contract from it.**
The highest-leverage move is **not** a speculative "design system" refactor up front — that would be the same spend-capacity-on-abstraction pattern this review rejects for Storage, and it risks freezing the wrong tokens before any surface validates them. Instead, freeze **one** real trust surface first — Warnings + Recovery — *to the Library's method* (single color entry point, tested identity/locked-contrast contract, reduced-motion gating, house-48dp targets), and let the reusable token/motion/contrast contract **crystallize out of that concrete freeze** into a shared layer other surfaces later adopt. This is trust-critical (a hands-free background recorder lives or dies on how it behaves when something went wrong), the two surfaces already share a canon, and they carry the app's only genuine AA *contrast* item (α0.48 banner text) plus the house-standard target misses. Fold in the calm severity-ladder + non-alarmist recovery copy the research endorses, unify cross-host surfacing, kill the `V3` naming and dead tokens, add a recency signal to Recovery. The generalized `rova-design-system.html` is an *output* of this cycle, not a prerequisite gate.

**P2 — Settings structural pass.**
Adopt glass-card section grouping, unify onto the shared token layer, replace the stock `TimePickerDialog` and stock `FilterChip`s, add sliders/type-entry for numeric settings. High visible-inconsistency, medium stakes.

**P3 — Record mode-picker discoverability + accent discipline.**
Targeted: make mode selection legible/previewable (not tap-to-cycle), tidy inline magic numbers, explore the quiet-perimeter recording affordance. **Not** a full redesign — Record is close.

**P4 — Player internal tokenization (not a redesign).**
Give the cinematic-black surface its own token set, dedupe `formatMmSs`, tokenize fades. Optionally thumbnail-at-scrub + boundary haptics as a *later* enhancement.

---

## 4. Which surfaces deserve a Design Freeze next

Design Freeze = an approved, versioned HTML spec under `docs/design/` that Compose transcribes.

1. **Warnings + Recovery**, frozen as a pair *first* — to the Library's method — and used to harvest the reusable token/motion/contrast contract. Trust-critical, shared canon, the α0.48 AA contrast fix + house-target fixes to fold in. (Not a "system-first" gate: the shared `rova-design-system.html` is written *from* this freeze, then refined per subsequent surface. This keeps the process iterative-HTML-first-per-surface rather than big-upfront-waterfall.)
2. **Settings**, frozen next — largely a transcription of the harvested contract onto the inset-list, plus the picker replacements.
4. **Record** — a *targeted* freeze amendment (mode picker + accent), not a whole-surface freeze.
5. **Player** — **no freeze / no redesign.** Internal token cleanup only; its divergence is correct.

---

## 5. Should Storage Management still be the next implementation branch?

**Recommendation: Defer the Storage Management *screen* — but with an honest correction to the cost math, because half of it already ships.**

The first draft called Storage Management "speculative greenfield." That is wrong, and the correction matters for the decision. Already **live and wired:** `RecordingRetentionCleaner.surplus()` (keep-latest-N surplus selection) routed through the ADR-0036 `HistoryDeleter` batch transaction; a Settings "Auto-delete old recordings" toggle + `KeepLatestChips([5,10,25,50])`; a dedicated Storage settings section; and a full storage **warning ladder** (`STORAGE_INSUFFICIENT` gates Start, `STORAGE_LOW_MID_REC`, `STORAGE_FULL_AUTOSTOPPED`). There is even a recorded owner signal (BACKLOG, 2026-06-14: "storage usage + management view"). So Storage Management is **mostly a UI surface over shipped retention + deletion + warning plumbing** — cheaper and less speculative than "new capability" implies. The genuinely-unbuilt part is only the *usage-summary/visualization* view.

That correction still leaves a defensible defer, on a narrower and more honest basis:

1. **The distinction that survives is manage-vs-warn.** The plumbing that serves the foreseeable pain (recorder fills storage → warn + auto-retain) is *already there*. What's unbuilt is a surface for users to *actively manage/visualize* storage — and there's no signal yet that users want to manage rather than be warned + auto-retained. Building the management screen now optimizes a step users may not be reaching.

2. **Trust debt should not compound under new surface area.** The app has one real AA *contrast* item and an un-generalized design method. A new visible surface built before the trust surfaces are brought to the Library bar either mints another off-method surface or has to be redone. Freeze Warnings + Recovery first; then Storage's UI inherits the harvested contract for free and ships in one clean pass.

3. **The cheapest honest response to storage pain is already the P0 work.** Refining the existing storage warnings into the calm severity-ladder language *is* the Warnings/Recovery freeze — it directly serves the foreseeable pain without a new screen.

**Storage Management earns the branch when:** (a) Warnings + Recovery are frozen so the harvested contract exists and its UI ships to the bar in one pass, and (b) there is a real signal that users want to *manage/visualize* storage, not merely be warned + auto-retained. Because the underlying machinery already ships, this is a *near*-term "next after P0/P1," not a distant deferral — the sequencing is about coherence and signal, not capability.

---

## 6. If not Storage Management — the recommended next design initiative

**"Bring the house to the Library bar — one trust surface at a time."** HTML-first, per-surface, no big-upfront system gate:

1. **Warnings + Recovery trust freeze (first).** Trust is Rova's core value proposition and these surfaces carry the only real AA *contrast* item in the app. Freeze them *to the Library's method*; fold in the research-backed calm severity-ladder and non-alarmist recovery copy; unify cross-host surfacing; retire `V3` naming and dead tokens; add Recovery recency; fix the α0.48 contrast and lift house-target sizes.

2. **Harvest the shared contract** (`docs/design/rova-design-system.html`) *out of* that freeze — the single-entry-point color, tested identity/locked-contrast contract, spacing/radius/motion/touch-target tokens, reduced-motion + no-autoplay trust rules — as an *output*, then refine it per subsequent surface. This avoids freezing an abstraction before a real surface validates it.

3. **Settings**, then the **Record mode-picker** follow as subsequent cycles, transcribing the harvested contract. **Player** gets an internal token cleanup whenever convenient (no redesign). **Storage Management's UI** slots in after P1 as thin, on-method UI over its already-shipped machinery — once manage-vs-warn signal justifies it.

**This sequence turns "we built faster than we used" into "we make what we built cohere and earn trust" — refinement over expansion, which is what the roadmap review actually asked for.**

---

## Appendix — external references (inspiration only, not to copy)

- Calm camera/recording chrome: Halide (halide.cam; developer.apple.com/news/?id=x6bv1a36), Kino/Lux (lux.camera/kino-a-pro-video-camera-in-four-months), Blackmagic Camera, Just Press Record (openplanetsoftware.com/just-press-record).
- Editorial players/scrubbing: Infuse 8 (firecore.com/infuse), Voice Memos waveform (support.apple.com), Mux hover previews (mux.com/docs/guides/create-timeline-hover-previews), img.ly mobile timeline.
- Premium settings / restraint: Things 3 (culturedcode.com/things), Linear redesign (linear.app/now/how-we-redesigned-the-linear-ui), NN/g progressive disclosure & permission priming (nngroup.com/articles/permission-requests).
- Editorial hierarchy/type: iA Writer, Bear (bear.app), Smashing/Toptal typographic-hierarchy guides.
- Trust/error/recovery: Smashing/LogRocket/Pencil&Paper error-UX, Figma offline-banner pattern.
- Calm warning surfacing: Carbon (carbondesignsystem.com/patterns/notification-pattern), PatternFly alert guidelines, UX Movement "3 types of alerts."

Cross-cutting principles distilled: (1) one accent used as a flashlight; (2) the media is the control; (3) calm = refusal (no red guilt, no "failed", reassurance + one action); (4) reveal complexity on demand; (5) prime in context, degrade gracefully.
