# ADR 0013 — Phase 4 warning re-skin v3 (chrome canon)

- **Status:** Accepted (owner sign-off pending PR A merge)
- **Date:** 2026-05-23
- **Phase:** Phase 4 warning surface v3 (`docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md`)
- **Supersedes (visually):** the original `WarningSheet` / `WarningTopBanner` chrome shipped in PR #12 (Phase 4.1) / PR #13 (Phase 4.1b) and refined in R1 / R2 (PRs #17 / #18 / #19)
- **Amends:** ADR-0007 (record warning sheets) — the v3 canon (glow bloom, severity chip, overflow ⋯, countdown ring, snooze-chip) supersedes the §1 stripe-and-icon stack described there
- **Does NOT supersede:** ADR-0007 §3 (Start-gate from leaf signals), `WarningPrecedence.resolve(...)`, `WarningId` (17 rows, ordinals, tier, `gatesStart`), `WarningSurface` routing, `RecoveryViewModel` / `RecoveryUiState`
- **Superseded (visually) by:** Amendment 2026-07-10 below — `docs/design/warnings-recovery.html` v1.0 (DESIGN FROZEN 2026-07-09, commit `09a0cba7`)
- **Related:** mockup `mockups/new_uiux/07c-warnings.html` (authoritative until the 2026-07-10 amendment)

---

## Amendment — 2026-07-10: visual layer superseded by the Trust System V1 HTML freeze

**Status:** Accepted. **Supersedes:** this ADR's *visual and token layer only*.

`docs/design/warnings-recovery.html` (v1.0, DESIGN FROZEN 2026-07-09, commit `09a0cba7`) is now the **canonical design source** for every Warnings + Recovery surface — `WarningSheet`, `WarningTopBanner`, `WarningSnoozeChip`, the History warning strip, the Settings permission chips, `RecoveryCard`, and the merge-failure card. Per the HTML-first workflow, **Compose transcribes that document; behavioral changes require a new HTML design iteration before implementation.** `mockups/new_uiux/07c-warnings.html` is retired as an authority — an adopted spec lives in `docs/design/`, and gitignored mockups cannot be a source of truth.

**What the freeze supersedes (this ADR's Decision 1 and 4):** the chrome canon and its token values. Concretely: the countdown ring is removed (it depicted a countdown the system never ran; Decision 5's deferred "real thermal-hysteresis seconds-source" is therefore closed *unbuilt* — per the freeze, the ring "may return only when backed by a real countdown"); the hard-block sheet loses its drag handle; pinned containers unify on one surface + alpha; every hue used as ink is resolved per-backing rather than mixed at a fixed alpha; radius / spacing / motion move onto shared ladders. Token geometry and alpha move from ad-hoc call sites into the Trust token registry the HTML defines. `RovaWarnings` (the four severity colors) remains untouched, exactly as Decision 4 states: `RovaWarnings` and `RovaSemantics` stay **separate semantic APIs and are never collapsed into one namespace** — three severities are defined-as their `RovaSemantics` counterpart (one hex literal each), `advisory` is Warnings-unique with no `RovaSemantics` twin, and future divergence must remain possible.

**What the freeze does NOT supersede — unchanged and still binding:** Decision 2 (the file split; `WarningCenter.kt` stays the routing entrypoint), Decision 3 (additive VM state; `activeWarning` filters snoozed ids; **the Start-gate reads leaf signals directly, so snoozing or dismissing a hard block never opens the gate** — pinned by `WarningCenterAggregateTest`), ADR-0007 §3, `WarningPrecedence.resolve(...)`, the `WarningId` declaration order and its `tier` / `gatesStart` columns, `WarningSurface` routing, the History / Settings allowlists, snooze persistence (ADR-0014), thermal hysteresis (ADR-0019), and the recovery merge state machine (ADR-0017 / ADR-0018). The `docs/WarningCenterContract.md` NO-GO list stands in full.

**Count correction.** This ADR's front-matter and Decision 1 cite "17 rows" / "17 idle-reachable warning sheets" — accurate on 2026-05-23, stale today. `WarningId` now declares **21** values (`WarningId.kt:25–51`); four landed after this ADR, each under its own ADR (`STORAGE_FULL_AUTOSTOPPED` → 0015, `THERMAL_AUTOSTOPPED` → 0016, `CANT_MERGE` → 0017, `SAVE_FOLDER_UNAVAILABLE` → 0024). The historical lines above are left as written; **21 is the live count.** See the frozen HTML's APPX-H for the four distinct count axes (21 enum values · 18 logical states · 17 precedence-resolver rows) and why three of them are simultaneously correct.

**Known divergence, deliberately out of scope.** `EXACT_ALARM_DENIED` is described in `docs/WarningCenterContract.md` and `CLAUDE.md` as "a flat non-gating banner", while `warningSurfaceFor` maps it to `HardBlockSheet` (`WarningSheetContent.kt:36`). It is genuinely non-gating either way. The frozen HTML transcribes the code, and Compose must do the same. Whether the modal container is the *right* one is a behavior question requiring its own ADR and its own HTML iteration; it does not block this amendment, and no implementation decision depends on it.

**Implementation contract:** `docs/COMPOSE-TRUST-SYSTEM-V1-PARITY-PLAN.md` (milestones, dependency graph, parity checklist). The roadmap explains implementation order; it never overrides the HTML.

---

## Context

The R2 record-home re-skin (Slices A/B, PRs #41 / #42) shipped a new canon for the live record chrome — edge-to-edge gradient scrim dock, glass pills with backdrop blur, Inter SemiBold, 22dp pill-radius settings card, severity-typed micro-affordances. The Phase 4.1 / 4.1b warning surface predates that canon: severity stripe at the sheet's top edge, 40dp CTAs, single-pass action stack, white-α0.55 secondary-CTA contrast. Against the new chrome it looks dated.

The mockup `07c-warnings.html` defines a v3 canon that aligns with R2:
- **Icon glow bloom** replaces the stripe (radial-gradient blur behind the icon, severity-tinted, 22dp blur, 0.70 alpha).
- **Inline severity chip** ("Hard · Required", "Soft · Degraded mode", "Advisory · Optional") sits above the title — explicit, glanceable.
- **Overflow ⋯ menu** carries tertiary actions (e.g. "Don't show again") that don't deserve a dedicated CTA slot.
- **"Why this matters"** dashed-border expander row reveals advisory rationale on demand (progressive disclosure).
- **Auto-action countdown ring** replaces the CTA pill on banners whose underlying state will auto-stop the session (THERMAL_EMERGENCY / THERMAL_SHUTDOWN).
- **CTAs** grow to 46dp (a11y), secondary text α0.55 → α0.68.
- **Snooze-chip** post-dismiss state — same composable canon, severity-tinted border, hard-block ids pulse the dot.
- **Recovery cards** in Library re-skin under the same canon (top glow bloom replaces stripe; numeric clip-count chip in the progress label row).

## Decision

1. **Adopt the v3 chrome canon** for all 17 idle-reachable warning sheets, all 10 mid-rec top banners, the post-dismiss snooze-chip, and the three Library recovery-card variants (`KILLED_BY_SYSTEM`, `KILLED_FORCE_STOP`, `MERGE_FAILED`).
2. **Split `WarningCenter.kt`** into `WarningSheetContent.kt` (pure content + helpers), `WarningSheetV3.kt`, `WarningTopBannerV3.kt`, `WarningSnoozeChip.kt`. `WarningCenter.kt` becomes the routing entrypoint (~135L).
3. **Add additive VM state** — `expandedWhy: StateFlow<Set<WarningId>>` for the "Why this matters" toggle, `snoozedForever: StateFlow<Set<WarningId>>` for the "Don't show again" overflow action. `activeWarning` filters out snoozed ids. **The Start-gate in `RecordScreen` continues to read leaf signals directly** — snoozing a hard-block does NOT open the gate. This invariant is pinned by `WarningCenterAggregateTest`.
4. **Add tokens** in `RovaWarningsV3.kt` (geometry, alpha, brush helpers). `RovaWarnings` (the four severity colors) is untouched. `iconGlow(severityColor, radiusPx)` takes an explicit pixel radius — the default `Float.POSITIVE_INFINITY` collapses the radial gradient to a flat fill, so callers compute `radiusPx` from `sheetIconSize` via `LocalDensity.current`.
5. **Defer to Phase 4.4:** real thermal-hysteresis seconds-source for the countdown ring (static 30s placeholder), snooze persistence across process death (in-memory only here), `SD_CARD_EJECTED` WarningId, `STORAGE_FULL_AUTOSTOPPED` sheet variant, stop-recording confirmation sheet (vacant C4.3), inline export-failed history tile (C5.3).

## Consequences

- **Positive:** the warning surface visually unifies with the R2 record-home canon. The split file structure unblocks Phase 4.4 work (countdown ticker, SD-eject, recovery-card variants) without re-crossing the 500L threshold. The Why-expander surfaces context that today is buried in the body copy — frees the body to be concise. The countdown ring sets the foundation for the real auto-stop UX in 4.4.
- **Negative / cost:** in-memory snooze means "Don't show again" forgets across process restarts. Acceptable in this slice — matches the original `WarningCenterContract.md` §5.2 design ("the user gets the banner back on cold start, by design"). 4.1c persists it durably. The countdown ring renders a placeholder 30 in 4.1 — owners must understand the ring is a UI shell, not a live signal.
- **VM scope injection:** `WarningCenterViewModel` gained an optional `scope: CoroutineScope?` constructor parameter so the in-memory snooze/expand StateFlows can be tested without the `Dispatchers.Main` requirement of `viewModelScope`. Mirrors the `RecoveryViewModel` pattern; no `kotlinx-coroutines-test` dependency added.
- **Testing:** new pure-helper tests (`WarningSheetContentV3Test` + `RovaWarningsV3Test`) + edits to `WarningCenterAggregateTest`. No new Compose-UI tests — sheet / banner / chip / recovery-card chrome is owner-verified on device, matching the ADR-0007 precedent.

## Status / sign-off

Drafted alongside PR A (Record-screen v3). Owner-signed on PR A merge.
