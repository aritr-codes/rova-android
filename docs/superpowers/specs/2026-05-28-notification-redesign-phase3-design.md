# Notification Redesign — Phase 3 (Mockup Alignment) — Design Spec

**Date:** 2026-05-28
**Status:** Approved post-mockup review (mockup `mockups/new_uiux/10-notification-phase3.html`)
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
6. **Translucent surface drawable** (`notif_surface.xml`) — rounded shape with ~70% opaque dark fill + thin hairline border, applied as the root background of both `notif_collapsed.xml` and `notif_expanded.xml`. Contributes the "modern glass" feel inside the system-owned card (see §4.4 + §10 risks).
7. **MergeComplete styling switch** — drop `setColorized(true)` from the builder for complete state; bind title text color to green via `RemoteViews.setTextColor`. Green dots row + green title + green hairline border replace the full-green card fill. See §3.2.

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

### 3.2 MergeComplete styling — LOCKED to mockup style

**Decision (owner-approved at mockup review 2026-05-28):** drop `setColorized(true)`, apply mockup-style **dark translucent card + green title text + green dots row + green hairline border**.

Implementation:
- Builder no longer calls `.setColorized(true)` for MergeComplete (or for any state — leave unset across the board).
- Title text color is bound at runtime per state via `rv.setTextColor(R.id.notif_title, plan.titleColor)`.
  - `MergeComplete` → green (`NotificationChannelConfig.ACCENT_COMPLETE`, `#34D399`)
  - Other states → default neutral (use the Compat.Notification.Title style's color; pass `null`/`Color.TRANSPARENT` sentinel to skip the explicit `setTextColor` call).
- The translucent surface drawable (§2 item 6) gains a `@color/notif_border_complete` border variant for MergeComplete only — implementation either swaps drawables (`notif_surface_complete.xml`) OR binds the border color at runtime if practical. Simpler default: two drawables, swap via `RemoteViews.setInt(rootId, "setBackgroundResource", R.drawable.notif_surface_complete)` when `plan.isComplete`.

**Tradeoff accepted:** loses the punchy "full green card" celebratory signal, gains mockup-fidelity + consistent surface treatment across all 4 states.

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

### 4.4 Translucent surface treatment ("modern glass" feel)

**What we own vs what we don't:**

- ❌ **The rounded notification card chrome** (outer edge, fill, drop shadow, rounded corners that contain everything including the system header). Android 12+ `DecoratedCustomViewStyle` mandate: the OS launcher owns this entirely. There is **no API** to apply `backdrop-filter`, blur, or override the card's fill from the app side.
- ✅ **The custom content area's own background.** Our `notif_collapsed.xml` and `notif_expanded.xml` root `LinearLayout`s can carry a `ShapeDrawable` background. This sits INSIDE the system card but is still visible as a translucent inner surface.

**The "glass" look on real Android comes from three stacked layers, two of which are free:**

1. **Wallpaper / app content** (the bottom layer — whatever's behind the shade).
2. **The OS shade** — already blurred at the system level on Android 12+ (`SystemUI` applies a backdrop blur to the entire shade pull).
3. **The OS notification card** — system-owned, sits on top of the shade.
4. **Our custom content surface** — a translucent ShapeDrawable inside the card.

Layers 1–3 give us blur-for-free. Our contribution (layer 4) is to use a translucent dark fill instead of an opaque one so the card chrome above us reads as airy rather than blocky.

**Drawable contract:**

```xml
<!-- res/drawable/notif_surface.xml -->
<shape android:shape="rectangle">
    <solid android:color="#B81C1E26" />     <!-- ~72% opaque dark surface -->
    <stroke android:width="1dp"
            android:color="#11FFFFFF" />     <!-- hairline border -->
    <corners android:radius="14dp" />
</shape>
```

```xml
<!-- res/drawable/notif_surface_complete.xml — MergeComplete only -->
<shape android:shape="rectangle">
    <solid android:color="#B81C1E26" />     <!-- same fill -->
    <stroke android:width="1dp"
            android:color="#3334D399" />     <!-- green hairline -->
    <corners android:radius="14dp" />
</shape>
```

Both layouts gain `android:background="@drawable/notif_surface"` on the root `LinearLayout` + a small inner padding bump (`paddingHorizontal=14dp`, `paddingVertical=10dp`) so content doesn't touch the surface edge.

**Risks we accept (see §10):**

- Some OEMs (Xiaomi MIUI, Samsung One UI on darker themes) render the system notification card with a fully-opaque dark fill — our 72%-opaque inner surface against an opaque dark card may look indistinguishable from the chrome. We accept this: on those skins the result is still clean (just less "glass"), and on AOSP-aligned skins (Pixel, OnePlus, Motorola) the effect lands.

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

`NotificationBindPlan` (Phase 2 type) gains two fields:

```kotlin
data class NotificationBindPlan(
    // existing fields ...
    val dots: DotsPlan,
    @ColorInt val titleColor: Int?,        // null = use Compat.Notification.Title default
    @DrawableRes val surfaceRes: Int       // notif_surface or notif_surface_complete
)
```

And LOSES one field (chip CD is dead):

```kotlin
// REMOVE:
val chipContentDescriptionRes: Int
```

Renderer logic:
- `surfaceRes = if (isComplete) R.drawable.notif_surface_complete else R.drawable.notif_surface`
- `titleColor = if (isComplete) NotificationChannelConfig.ACCENT_COMPLETE else null`

Service binder:
- `rv.setInt(R.id.notif_root, "setBackgroundResource", plan.surfaceRes)`
- `plan.titleColor?.let { rv.setTextColor(R.id.notif_title, it) }` — skip when null to keep the Compat text appearance default.
- Builder: do **not** call `.setColorized(...)` for any state (decision §3.2).

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
- Add: `plan.titleColor == ACCENT_COMPLETE` for MergeComplete; `null` for ClipRecording/GapWaiting/Merging (4 tests)
- Add: `plan.surfaceRes == R.drawable.notif_surface_complete` for MergeComplete; `R.drawable.notif_surface` for the other 3 states (4 tests)

### 8.2 What we don't unit-test

- RemoteViews inflation + layout rendering (no Robolectric, per project policy).
- Pill tinting at runtime — verified manually in smoke checklist.

### 8.3 Real-device smoke (additive to Phase 2 checklist)

- [ ] ClipRecording at N=6 — dots show [▆ ▆ ░ ░ ░ ░] in blue.
- [ ] GapWaiting at N=6 — dots show [▆ ▆ ░ ░ ░ ░] in blue (current advances to "next" position).
- [ ] Merging at N=6, done=4 — dots show [▆ ▆ ▆ ▆ ▆-translucent ░] in blue + progress fills below.
- [ ] MergeComplete at N=6 — dots show [▆ ▆ ▆ ▆ ▆ ▆] in green; **title text is green** (not white-on-green); card is dark glass, not fully-green-bg.
- [ ] MergeComplete shows green hairline border (subtle, not loud).
- [ ] N=2 session — dots scale up; no thread-thin visuals.
- [ ] N=10 session (synthetic) — 7 state pills + "+3" count pill; tail count readable.
- [ ] No rail visible on any state. No secondary chip visible on any state.
- [ ] Translucent surface visible on Pixel-class devices (card chrome reads as airy, not blocky). Acceptable to look "flat dark" on Xiaomi/Samsung skins with opaque card chrome (decision §4.4 risk).
- [ ] Collapsed view still shows title + tail correctly; no orphan view.
- [ ] System header still shows Rova + time + (stock app icon — flagged out-of-scope).
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
| Translucent surface looks identical to opaque card on OEM skins with non-AOSP card chrome (Xiaomi MIUI, Samsung One UI dark) | M | Accept — the result is still clean on those skins (just less "glass"). Documented in §4.4. |
| `setTextColor` via RemoteViews on `R.id.notif_title` overrides the Compat.Notification.Title style's color-state-list, losing dark/light theme adaptation | M | Only bind `titleColor` when non-null (MergeComplete only). Other 3 states keep the default Compat text appearance and adapt to theme. |
| `setBackgroundResource` via `RemoteViews.setInt` requires the target to be a view that supports `setBackgroundResource(int)` — verified for `LinearLayout` back to API 1; safe at minSdk=24 | L | Tag the root `LinearLayout` with `@id/notif_root` in both layouts; ensure shape drawables are pre-API-29-compatible (no `<gradient>` modes that fail on older versions). |
| Dropping `setColorized(true)` removes the OS-injected "important notification" highlight on the lockscreen for MergeComplete | M | Accept — the title-color + dots row are sufficient celebratory signal. The post-merge notif also has `setVisibility(VISIBILITY_PRIVATE)` so lockscreen hides the body anyway. |

## 11. Acceptance criteria

1. JVM tests pass (≥ 1318 / 0-0-0; Phase 2 baseline 1300 + ~14-18 new for Renderer delegation + DotsRow + title-color/surface-res assertions).
2. `:app:lintDebug` green; no new `UnusedResources` warnings.
3. `:app:assembleDebug` green.
4. Real-device smoke checklist §8.3 passes on Android 14+ (Pixel-class skin for glass effect verification).
5. No file in `app/src/main/java/com/aritr/rova/service/notification/` exceeds 300 LoC.

## 12. Follow-on (out of scope)

1. Rova launcher icon redesign (`mipmap-anydpi-v26` adaptive icon + monochrome icon for Android 13+ themed icons).
2. Mockup-faithful action button styling — would require fully-custom (Android-12+ retired path); revisit if Material 3 Expressive notifications ship a styled-action API.
3. Tune translucent surface alpha per Android OEM skin (if multi-device smoke surfaces visible regressions on Samsung/Xiaomi).

---

## Self-review (per spec author)

**1. Placeholder scan.** No "TBD" / "TODO" / "appropriate" / "implement later". All defaults and recommendations are concrete.

**2. Internal consistency.** Dot count tables in §4.3 match the helper API in §7 (Kind enum: DONE/CURRENT/TODO/COUNT_PILL). MergeComplete styling decision (§3.2 keep colorized) is consistent with §4.3 green dots accent and §8.3 smoke item.

**3. Scope.** Single subsystem. No cross-cut. Fits in 4 implementation tasks; small enough for one plan.

**4. Ambiguity.** Three judgment calls remain (one locked at mockup review):
- §3.1 dot cap policy → **cap at 8 visible** (owner-approved at mockup review).
- §3.2 MergeComplete styling → **LOCKED: dark glass card + green title** (owner-approved at mockup review).
- §4.4 translucent surface → **LOCKED: 72%-opaque dark fill + hairline border** (owner-approved at mockup review).
- §6 dot-row CD strings → default: in-code, not in `strings.xml`.
- §9 unused chip CD strings → default: delete.

Three above are locked; remaining two have a "**Recommended:** …" line and a sensible default. None block the plan.
