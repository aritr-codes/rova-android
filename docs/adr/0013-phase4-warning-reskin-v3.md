# ADR 0013 — Phase 4 warning re-skin v3 (chrome canon)

- **Status:** Accepted (owner sign-off pending PR A merge)
- **Date:** 2026-05-23
- **Phase:** Phase 4 warning surface v3 (`docs/superpowers/specs/2026-05-23-phase-4-warning-reskin-v3-design.md`)
- **Supersedes (visually):** the original `WarningSheet` / `WarningTopBanner` chrome shipped in PR #12 (Phase 4.1) / PR #13 (Phase 4.1b) and refined in R1 / R2 (PRs #17 / #18 / #19)
- **Amends:** ADR-0007 (record warning sheets) — the v3 canon (glow bloom, severity chip, overflow ⋯, countdown ring, snooze-chip) supersedes the §1 stripe-and-icon stack described there
- **Does NOT supersede:** ADR-0007 §3 (Start-gate from leaf signals), `WarningPrecedence.resolve(...)`, `WarningId` (17 rows, ordinals, tier, `gatesStart`), `WarningSurface` routing, `RecoveryViewModel` / `RecoveryUiState`
- **Related:** mockup `mockups/new_uiux/07c-warnings.html` (authoritative)

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
