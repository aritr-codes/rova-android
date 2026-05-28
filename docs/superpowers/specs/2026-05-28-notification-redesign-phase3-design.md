# Notification Redesign — Phase 3 (Mockup Alignment) — Design Spec

**Date:** 2026-05-28
**Status:** Draft (awaiting owner review)
**Branch:** `feat/notification-redesign-v1` (folds into M5 PR alongside Phase 1 + Phase 2)
**Last commit (Phase 2 tip):** `5b876c8 fix(notif): bind chip contentDescription to icon view, not bg`
**Inspiration:** `mockups/new_uiux/09-notification-export.html`
**Sibling specs:** Phase 1 `docs/superpowers/specs/2026-05-27-notification-redesign-v1-design.md`, Phase 2 `docs/superpowers/specs/2026-05-28-notification-redesign-phase2-design.md`

---

## 1. Context

Phase 2 shipped `DecoratedCustomViewStyle` + custom RemoteViews (accent rail + 32dp icon chip + tail meta + progress). Real-device smoke passed but the result is visually weaker than the mockup:

1. The stock Android-bot launcher icon dominates the system header on every state. That's the first pixel users see; it has nothing to do with Rova.
2. The custom-area chip duplicates the system header's app-icon chip → redundant.
3. The 4dp accent rail is too thin to register against system padding → invisible signal.
4. Missing the mockup's signature: a **clip dots row** that surfaces N-segment progress at a glance.

Phase 3 narrows the visual gap without re-architecting Phase 2: trim the custom area down to what the mockup actually shows (title + body + dots + progress), and use the colors more sparingly so they read as accents instead of competing chrome.

## 2. In scope

1. **Drop accent rail + chip column** from `notif_collapsed.xml` and `notif_expanded.xml`. The system header already carries app identity; our second chip was redundant.
2. **Add a clip-dots row** (RemoteViews `LinearLayout` of N pill `ImageView`s) below body, above progress bar in `notif_expanded.xml`. Tinted per state via runtime `setColorFilter`.
3. **Pure helper `NotificationDotsRow`** — emits a `DotsPlan` (state list per pill, accent color) from a `NotificationState`. JVM-testable.
4. **Drop `chipContentDescriptionRes` from `NotificationBindPlan`** — dead field once chip is gone. Strings stay (no churn).
5. Service binder cleanup: remove rail + chip `setInt`/`setContentDescription`/`setImageViewResource` calls; add `bindDotsRow(rv, plan)`.

## 2.1 NO-GO (out of scope)

1. **Launcher icon redesign** — the right brand signal is a proper `mipmap/ic_launcher` + `drawable/ic_notif_brand` for `setLargeIcon`. Separate task: design + adaptive icon set + monochrome icon. Flag in PR description.
2. **No 5th NotificationState** — same as Phase 2 NO-GO.
3. **No `SessionManifest` schema bump.**
4. **Pill-shaped colored action buttons** (red Stop, blue Open) as in mockup — unachievable on `DecoratedCustomViewStyle`; the action row is system-owned. Fully-custom is Android-12+ retired. We accept the system action row.
5. **No collapsed-view dots** — 48dp budget doesn't accommodate them; collapsed stays as title + tail.
6. **No new Phase 1 helper changes** — `NotificationCopy.kt`, `NotificationIconRes.kt`, `NotificationChannelConfig.kt`, `NotificationActionSpec.kt` untouched.

## 3. Two design judgment calls baked in

These are flagged at the top of the spec so owner can redirect before plan-writing.

### 3.1 Dot count strategy for large N

Sessions can have any `total`: typical 2-6, theoretical up to 50+. Three options considered:

| Strategy | Visual at N=6 | Visual at N=50 | Cost |
|---|---|---|---|
| **A. Cap at 8 visible** (last pill = "+N more") | 6 pills + 0 spacer | 7 pills + "+43" pill | Need a count pill widget |
| **B. Scale pills to fit** | 6 wide pills | 50 thread-thin pills | Pills become invisible at high N |
| **C. Always cap at 6, show "X/Y" text** | 6 pills | "1/50" text only | Loses progress visualization |

**Recommended: A (cap at 8 visible).** Matches mockup at N=6, degrades gracefully at high N, keeps individual pill width readable. Implementation: layout pre-allocates 8 `ImageView` slots; helper emits up to 8 states or 7 + a count tag.

### 3.2 MergeComplete styling

Current Phase 2 behavior uses `setColorized(true)` which paints the **whole notification background green** when system permits. That's visually punchier than the mockup's dark-card-with-green-accent treatment.

| Approach | Visual | Tradeoff |
|---|---|---|
| **Keep colorized green bg** (current Phase 2 behavior) | Strong celebratory signal — whole notification is green | Diverges from mockup |
| **Switch to mockup style** (drop colorized, tint title text green via `setTextColor`) | Subtler — green card border + green title only | OEM-fragile; not every launcher respects card border tints |

**Recommended: keep colorized green bg.** The current MergeComplete screenshot looks great on-device; switching would be a regression in visual impact. The dots row will already carry the mockup's "all-green pills" signal. Surfacing two green treatments is louder than the mockup intends.

If owner wants mockup-faithful, this is a one-task swap — flag at spec review.

## 4. Visual contract

### 4.1 Collapsed (48dp budget)

```
┌───────────────────────────────────────────────────────────┐
│ Recording · Clip 2 of 6                  0:18 remaining   │
└───────────────────────────────────────────────────────────┘
```

Single horizontal row. Title weight=1 (truncates with ellipsis), tail right-aligned. No rail, no chip. The system header above (Rova + app icon + time) provides identity.

### 4.2 Expanded (≤252dp budget)

```
┌───────────────────────────────────────────────────────────┐
│ Recording · Clip 2 of 6                                   │
│ 0:18 remaining in this clip                               │
│ ▆▆ ▆▆ ░░ ░░ ░░ ░░                                         │  ← dots row (6 pills)
│ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ │  ← progress (if applicable)
└───────────────────────────────────────────────────────────┘
```

Vertical stack:
- Title (Compat.Notification.Title)
- Body (Compat.Notification, marginTop 2dp)
- Dots row (LinearLayout horizontal, gap 3dp, marginTop 10dp, height 4dp)
- Progress bar (only if `plan.progress != null`, marginTop 8dp)

No rail. No chip. The dots row inherits the accent color from the plan.

### 4.3 Per-state dot pattern

For a session with `total = 6`, `done = N`, `current = N+1` (1-based):

| State | Dot 1 | Dot 2 | Dot 3 | Dot 4 | Dot 5 | Dot 6 | Accent |
|---|---|---|---|---|---|---|---|
| ClipRecording (current=2) | ▆ done | ▆ current | ░ todo | ░ todo | ░ todo | ░ todo | blue |
| GapWaiting (next=3) | ▆ done | ▆ done | ░ todo | ░ todo | ░ todo | ░ todo | blue |
| Merging (done=4 of 6) | ▆ done | ▆ done | ▆ done | ▆ done | ▆ current | ░ todo | blue |
| MergeComplete (count=6) | ▆ done | ▆ done | ▆ done | ▆ done | ▆ done | ▆ done | green |

Tint mapping:
- `done` → `plan.accent` at full alpha (0xFF)
- `current` → `plan.accent` at 40% alpha (0x66)
- `todo` → white at 12% alpha (0x1F FFFFFF)

For N > 8: show 7 state pills + 1 trailing count pill displaying "+N-7" text (e.g. "+43"). Count pill background = white 8% alpha, text = white 50% alpha.

For sessions where `total` is unknown (e.g. ClipRecording with no `total` field set): hide the entire dots row (`setViewVisibility(GONE)`).

## 5. Accessibility

- The dots row carries one combined contentDescription: e.g. `"Clip 2 of 6"` for ClipRecording, `"Merging, 4 of 6 done"` for Merging, `"All 6 clips complete"` for MergeComplete. Individual pills have `importantForAccessibility="no"`.
- Existing chip CD strings (`notification_chip_cd_*`) are no longer bound (chip is gone). Leave the strings in `strings.xml` for now — a follow-up cleanup can remove them; no behavior cost in keeping them.
- All other a11y semantics from Phase 1 + 2 carry over unchanged (lockscreen visibility, title/body for screen readers, action labels).

## 6. New strings

None. Combined dots-row CDs are formatted in code from existing Phase 1 `NotificationCopy` numbers, or use a single new template if owner prefers — open question:

- Option X: hard-code the CD format strings in `NotificationDotsRow` (no new resources).
- Option Y: extract to 4 new templates in `strings.xml`.

**Recommended: X.** Single small surface, easy to grep. We're talking 4 short format strings; adding them to `strings.xml` adds churn without payoff.

## 7. Helper API contract

```kotlin
package com.aritr.rova.service.notification

data class DotState(val kind: Kind, val countLabel: String?) {
    enum class Kind { DONE, CURRENT, TODO, COUNT_PILL }
}

data class DotsPlan(
    val pills: List<DotState>,        // 0..8 entries; empty = hide row
    val accent: Int,                  // re-uses NotificationBindPlan.accent
    val contentDescription: String,   // combined CD for the row
    val visible: Boolean              // false when total is unknown / 0
)

internal fun NotificationState.toDotsPlan(): DotsPlan
```

`NotificationBindPlan` (Phase 2 type) gains one field:

```kotlin
data class NotificationBindPlan(
    // existing fields ...
    val dots: DotsPlan
)
```

And LOSES one field (chip CD is dead):

```kotlin
// REMOVE:
val chipContentDescriptionRes: Int
```

## 8. Testing

### 8.1 Pure JVM (subagent estimate: ~14 new tests)

`NotificationDotsRowTest`:
- ClipRecording with total=6, current=2 → 6 pills [DONE, CURRENT, TODO, TODO, TODO, TODO], accent=blue
- GapWaiting with total=6, nextNumber=3 → 6 pills [DONE, DONE, TODO, TODO, TODO, TODO], accent=blue
- Merging with total=6, done=4 → 6 pills [DONE, DONE, DONE, DONE, CURRENT, TODO], accent=blue
- MergeComplete with clipCount=6 → 6 pills [DONE × 6], accent=green
- Large N: ClipRecording total=10, current=3 → 7 state pills + 1 COUNT_PILL("+2") — or matching cap policy
- Unknown total (ClipRecording with no total) → visible=false, pills=[]
- N=1 (single-clip session, MergeComplete clipCount=1) → 1 pill [DONE]
- Combined CD strings per state
- Accent matches Phase 2 channel-config constants (ACCENT_RECORDING / ACCENT_COMPLETE)

`NotificationRendererTest` (updated):
- `chipContentDescriptionRes` field removed from `NotificationBindPlan` — drop the 4 tests that asserted on it
- Add: `plan.dots` matches `state.toDotsPlan()` for each of the 4 states (delegation test, 4 tests)

### 8.2 What we don't unit-test

- RemoteViews inflation + layout rendering (no Robolectric, per project policy).
- Pill tinting at runtime — verified manually in smoke checklist.

### 8.3 Real-device smoke (additive to Phase 2 checklist)

- [ ] ClipRecording at N=6 — dots show [▆ ▆ ░ ░ ░ ░] in blue.
- [ ] GapWaiting at N=6 — dots show [▆ ▆ ░ ░ ░ ░] in blue (current advances to "next" position).
- [ ] Merging at N=6, done=4 — dots show [▆ ▆ ▆ ▆ ▆-translucent ░] in blue + progress fills below.
- [ ] MergeComplete at N=6 — dots show [▆ ▆ ▆ ▆ ▆ ▆] in green.
- [ ] N=2 session — dots scale up; no thread-thin visuals.
- [ ] N=10 session (synthetic) — 7 state pills + "+3" count pill; tail count readable.
- [ ] No rail visible on any state. No secondary chip visible on any state.
- [ ] Collapsed view still shows title + tail correctly; no orphan view.
- [ ] System header still shows Rova + time + (stock app icon — flagged out-of-scope).
- [ ] MergeComplete still uses colorized green bg (decision §3.2).
- [ ] TalkBack — dots row announces "Clip N of M" / "Merging, N of M done" / "All N clips complete".
- [ ] Font scale 2× — dots row height unchanged; title + body truncate.

## 9. Lint + static-check gate

No new `check*` tasks. The 4 `notification_chip_cd_*` strings become unreferenced — Android lint may flag `UnusedResources`. Two options:
- **Keep strings, suppress lint** with a `<resources xmlns:tools="..." tools:ignore="UnusedResources">` wrap — noisy.
- **Delete the 4 chip CD strings.** Lower noise; consistent with "dead field" cleanup pattern.

**Recommended: delete.** Phase 2 added them 4 commits ago; nothing else references them. Re-adding takes 30 seconds if we ever bring the chip back.

## 10. Risks + mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| `setColorFilter` on `ImageView.background` not supported via RemoteViews pre-API-31 | M | Use `setInt(viewId, "setColorFilter", color)` — supported back to API 16. Fallback to white pill + accent tint via `setBackgroundColor`. |
| Dots row inflates layout beyond 252dp expanded budget on huge N | L | Capped at 8 pills, 4dp pill height — fits in <20dp. |
| User has a session with `total = 0` (cold-launch edge) | L | Helper emits `visible=false`, layout hides row entirely. |
| Removing rail + chip from the layout files breaks Phase 2 tests | M | Phase 2 `NotificationRendererTest` already drops the `chipContentDescriptionRes` assertions in Task 4 of the plan; renderer test rewrites land in same compile-gate split. |

## 11. Acceptance criteria

1. JVM tests pass (≥ 1310 / 0-0-0; Phase 2 baseline 1300 + ~10-14 new).
2. `:app:lintDebug` green; no new `UnusedResources` warnings.
3. `:app:assembleDebug` green.
4. Real-device smoke checklist §8.3 passes on Android 14+.
5. No file in `app/src/main/java/com/aritr/rova/service/notification/` exceeds 300 LoC.

## 12. Follow-on (out of scope)

1. Rova launcher icon redesign (`mipmap-anydpi-v26` adaptive icon + monochrome).
2. Mockup-faithful MergeComplete (drop colorized, dark card with green title).
3. Mockup-faithful action button styling — would require fully-custom (Android-12+ retired path); revisit if Material 3 Expressive notifications ship a styled-action API.

---

## Self-review (per spec author)

**1. Placeholder scan.** No "TBD" / "TODO" / "appropriate" / "implement later". All defaults and recommendations are concrete.

**2. Internal consistency.** Dot count tables in §4.3 match the helper API in §7 (Kind enum: DONE/CURRENT/TODO/COUNT_PILL). MergeComplete styling decision (§3.2 keep colorized) is consistent with §4.3 green dots accent and §8.3 smoke item.

**3. Scope.** Single subsystem. No cross-cut. Fits in 4 implementation tasks; small enough for one plan.

**4. Ambiguity.** Three judgment calls remain explicit and flagged for owner redirect:
- §3.1 dot cap policy (default: cap at 8 visible).
- §3.2 MergeComplete styling (default: keep colorized).
- §6 dot-row CD strings (default: in-code, not in `strings.xml`).
- §9 unused chip CD strings (default: delete).

Each has a "**Recommended:** …" line. None block the plan.
