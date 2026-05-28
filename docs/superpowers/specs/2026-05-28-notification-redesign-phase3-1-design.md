# Notification Redesign — Phase 3.1 (Polish: dot tinting, ticking timer, typography) — Design Spec

**Date:** 2026-05-28
**Status:** Approved scope (3-task polish pass)
**Branch:** `feat/notification-redesign-v1` (folds into M5 PR alongside Phase 1 + Phase 2 + Phase 3)
**Last commit (Phase 3 tip):** `039d430 chore(notif): drop unused Phase 2 chip contentDescription strings`
**Sibling specs:** Phase 3 `docs/superpowers/specs/2026-05-28-notification-redesign-phase3-design.md`, mockup `mockups/new_uiux/10-notification-phase3.html`

---

## 1. Context

Phase 3 shipped real-device smoke-passing, but visual review surfaced three polish gaps:

1. **Dots row visually indistinct.** All pills render at roughly the same mid-grey regardless of state. Root cause: `RemoteViews.setInt(viewId, "setColorFilter", ...)` does not tint an `ImageView`'s `android:background` drawable — `setColorFilter` only affects the `src` drawable. The current layout binds the dot pill via `background=`, so the runtime tint is silently no-op'd. Pills show the default white solid shape — alpha-blended by the OS shade — producing the washed-out grey we see.
2. **Pills don't fill the card.** Layout pre-allocates 8 weighted slots with `weightSum="8"`. When only 3 pills are visible (others `GONE`), the 3 visible ones get `3/8` of the row width — the remaining `5/8` is dead whitespace.
3. **Countdown timer is jumpy, not smooth.** Body text updates at our service's ~1Hz throttled `notify()` rate — but Android's notification system may coalesce, producing perceived "stuck-then-jump" behavior. The native fix is an Android `Chronometer` widget with `setCountDown(true)` + `setBase(...)` — the widget free-ticks at 1Hz without service round-trips.
4. **Typography doesn't match the mockup.** Current title + body use `Compat.Notification.Title` / `Compat.Notification` styles — system-default sizes + colors. Mockup specifies Inter 13.5sp/600-weight title with 92% alpha + 12sp/400-weight body with 55% alpha + 1.4 line height.

Phase 3.1 fixes all four without re-architecting Phase 3 — no new state, no new helper file beyond `ChronoSpec` field on the bind plan.

## 2. In scope

1. **Dot tinting fix.** Switch dot `ImageView`s from `android:background="@drawable/notif_dot_pill"` to `android:src="@drawable/notif_dot_pill"` + `android:scaleType="fitXY"`. Service binder switches from `setInt(viewId, "setBackgroundColor", ...)`-style attempts to `setInt(viewId, "setColorFilter", color)` which now correctly tints the `src` drawable.
2. **Dynamic dots row `weightSum`.** Service binder calls `rv.setFloat(R.id.notif_dots_row, "setWeightSum", visiblePillCount.toFloat())` per state. Pills then evenly fill the row regardless of count.
3. **Chronometer for countdown states.** Add `Chronometer` widget to `notif_expanded.xml` next to the body `TextView`. Service binds via `rv.setChronometer(R.id.notif_chrono, base, format, started)` + `rv.setBoolean(R.id.notif_chrono, "setCountDown", true)` for ClipRecording + GapWaiting; for Merging + MergeComplete it hides Chronometer and shows the static body `TextView`.
4. **Bind-plan field `chrono: ChronoSpec?`.** Pure data describing what the service should render:
   ```kotlin
   data class ChronoSpec(val baseElapsedMs: Long)
   ```
   Single field — when `baseElapsedMs > now`, Chronometer counts down to it.
5. **Typography alignment.** Add explicit `android:textSize`, `android:textColor`, `android:lineSpacingExtra` to title + body + Chronometer in both `notif_collapsed.xml` and `notif_expanded.xml`. Mapping in §4.4.

## 2.1 NO-GO (out of scope)

1. **Phase 1 helper changes** — `NotificationCopy.kt`, `NotificationIconRes.kt`, `NotificationChannelConfig.kt`, `NotificationActionSpec.kt` remain untouched.
2. **No new state** — same 4 sealed-interface variants as Phase 1.
3. **No `SessionManifest` schema bump.**
4. **No launcher icon work** — still out of scope, still flagged as separate task.
5. **No suffix text "remaining in this clip" alongside Chronometer.** Chronometer cannot embed surrounding prose — only `mm:ss` / `H:mm:ss`. The body becomes minimalist per mockup style. Owner-approved Option B from the dispatch turn.
6. **No collapsed-view dots row.** Collapsed stays title + tail per Phase 3.
7. **No new `check*` static-check task.** Phase 3.1 is a polish pass, not an invariant addition.

## 3. Architecture

```
NotificationState (Phase 1)
     │
     ├─→ toCopy() ────────────► copy.title (display)
     │                          copy.body  (used for Merging/MergeComplete static body
     │                                       + ChronoSpec-state lockscreen fallback)
     │
     ├─→ toDotsPlan() ────────► DotsPlan (Phase 3 — unchanged)
     │
     ├─→ toChronoSpec() ──────► ChronoSpec? (NEW Phase 3.1)
     │                          ClipRecording.etaSecondsRemaining → SystemClock.elapsedRealtime() + ms
     │                          GapWaiting.nextStartsInSeconds   → SystemClock.elapsedRealtime() + ms
     │                          Merging / MergeComplete          → null
     │
     └─→ toBindPlan() ────────► NotificationBindPlan
                                  ├ existing Phase 3 fields ...
                                  └ chrono: ChronoSpec?   ← NEW
                                       │
                                       ▼
                              service.renderRemoteView(plan, expanded)
                                       │
                                       ├ if chrono != null → bind Chronometer + hide body TextView
                                       └ else              → bind body TextView + hide Chronometer
```

`toChronoSpec()` lives in the existing `NotificationRenderer.kt` (extension on `NotificationState`). No new file. The existing `toBindPlan()` is extended to set the new field.

## 4. Visual contract

### 4.1 Collapsed (48dp budget) — unchanged from Phase 3

Title + right-aligned tail. No dots, no Chronometer. Polish only: typography alignment (§4.4).

### 4.2 Expanded (≤252dp budget) — Chronometer-aware

```
┌───────────────────────────────────────────────────────────┐
│ Recording · Clip 2 of 6                                   │
│ 0:18                                                       │  ← Chronometer (ticks down per second)
│ ▆▆ ▆▆ ░░ ░░ ░░ ░░                                         │  ← dots row (fills full width per §4.3)
│ ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ │  ← progress (if applicable)
└───────────────────────────────────────────────────────────┘
```

For Merging / MergeComplete (no countdown):

```
┌───────────────────────────────────────────────────────────┐
│ Merging clips · 4 of 6                                    │
│ About 15 seconds remaining                                │  ← body TextView (static)
│ ▆▆ ▆▆ ▆▆ ▆▆ ░░ ░░                                         │  ← dots row
│ ████████████████████████████████░░░░░░░░░░░░░░░░░░░░░░░░ │  ← progress
└───────────────────────────────────────────────────────────┘
```

Body and Chronometer occupy the same vertical slot — one is `VISIBLE`, the other `GONE` based on plan.

### 4.3 Dots row — fills full width regardless of count

| visible count | weightSum bound | per-pill weight | per-pill width on 280dp card |
|---|---|---|---|
| 2 | 2.0 | 1 | 137dp (gap 3dp) |
| 3 | 3.0 | 1 | 91dp |
| 6 | 6.0 | 1 | 44dp |
| 8 (cap) | 8.0 | 1 | 32dp |

Layout pre-allocates 8 `ImageView` slots (unchanged from Phase 3). Service binder:
```kotlin
rv.setFloat(R.id.notif_dots_row, "setWeightSum", visibleCount.toFloat())
// then set the GONE pills (index 8..visibleCount-1) to View.GONE
```

`setFloat` is a RemoteViews-safe method back to API 16; `setWeightSum` on `LinearLayout` is part of `LayoutParams` recompute — supported as long as `weightSum` was declared in the XML (it is).

### 4.4 Typography (pixel-match mockup)

Both layouts:

| Element | textSize | textColor (alpha) | weight | lineSpacingExtra |
|---|---|---|---|---|
| Title | 14sp | `#EBFFFFFF` (92%) | bold (600 via fontFamily fallback) | 0 |
| Body | 12sp | `#8CFFFFFF` (55%) | normal | 2dp |
| Chronometer | 12sp | `#C2FFFFFF` (76%) | medium (500 via fontFamily fallback) | 0 |
| Tail (collapsed only) | 11sp | `#A6FFFFFF` (65%) | normal | 0 |
| Count-pill label | 8sp | `#80FFFFFF` (50%) — unchanged from Phase 3 | normal | 0 |

MergeComplete title color override (`#34D399`) takes precedence over the table value — service `setTextColor` runs after the XML-declared color, so the runtime bind wins.

Inter is not a system font; we ship system-default sans-serif. The mockup's 600-weight is approximated via `android:textStyle="bold"`. If owner later wants Inter, that's a separate font-asset task — out of scope.

## 5. Accessibility

- Chronometer auto-announces its time value to TalkBack each tick. We do not override its `contentDescription`.
- Body TextView (for Merging/MergeComplete) carries Phase 1's `copy.body` as text — already a11y-readable.
- Dots row CD unchanged from Phase 3 (combined `"Clip N of M"` / `"Merging, N of M done"` / `"All N clips complete"`).
- Title/tail/count-pill a11y unchanged.

## 6. Helper API contract

`NotificationBindPlan` (Phase 3 type) gains one field:

```kotlin
data class NotificationBindPlan(
    // existing Phase 3 fields ...
    val chrono: ChronoSpec?
)
```

New pure data:

```kotlin
package com.aritr.rova.service.notification

/**
 * M5 Phase 3.1 — countdown spec for Chronometer binding.
 *
 * [baseElapsedMs] is the absolute `SystemClock.elapsedRealtime()` value
 * at which the countdown hits zero. Service binds it via
 * `RemoteViews.setChronometer(viewId, base = baseElapsedMs, format = null, started = true)`
 * + `setBoolean(viewId, "setCountDown", true)`. The widget then free-ticks
 * per second without service round-trips.
 *
 * `null` plan → service hides Chronometer + shows static body TextView.
 */
data class ChronoSpec(val baseElapsedMs: Long)
```

New pure extension (in same file, `NotificationRenderer.kt`):

```kotlin
internal fun NotificationState.toChronoSpec(
    now: () -> Long = { android.os.SystemClock.elapsedRealtime() }
): ChronoSpec? = when (this) {
    is NotificationState.ClipRecording ->
        etaSecondsRemaining?.let { ChronoSpec(now() + it * 1000L) }
    is NotificationState.GapWaiting ->
        nextStartsInSeconds?.let { ChronoSpec(now() + it * 1000L) }
    is NotificationState.Merging,
    is NotificationState.MergeComplete -> null
}
```

The `now` parameter is a test seam — defaults to `SystemClock.elapsedRealtime()` in production. JVM tests inject a fixed clock to verify the offset math without depending on framework calls.

## 7. Testing

### 7.1 Pure JVM (subagent estimate: ~7 new tests)

`NotificationRendererTest` additions:

- `ClipRecording with eta=18 emits ChronoSpec at now+18000ms` — fixed `now=1000L`, eta=18 → `baseElapsedMs=19000L`
- `ClipRecording with eta=null emits chrono=null`
- `GapWaiting with nextStartsInSeconds=300 emits ChronoSpec at now+300000ms`
- `GapWaiting with nextStartsInSeconds=null emits chrono=null`
- `Merging always emits chrono=null` (with and without progress %)
- `MergeComplete always emits chrono=null`
- `toBindPlan delegates chrono field to toChronoSpec`

### 7.2 What we don't unit-test

- RemoteViews `setChronometer` / `setBoolean` calls — no framework under JVM (`isReturnDefaultValues=true`). Verified manually in smoke.
- `setColorFilter` actually tinting the `src` drawable — verified visually in smoke.
- `setFloat(weightSum)` redistributing pills — verified visually.

### 7.3 Real-device smoke

**Polish-specific (additive to Phase 3 smoke):**

- [ ] ClipRecording — dots are DISTINCTLY tinted: solid blue (done) + translucent blue (current) + faint white (todo). All three states are visibly different.
- [ ] GapWaiting — same color distinction.
- [ ] Merging — done pills solid blue, current translucent blue, todo faint white.
- [ ] MergeComplete — all pills solid green.
- [ ] N=3 session — 3 pills fill the full notification card width (no dead space on the right).
- [ ] N=2 session — 2 pills fill the full width.
- [ ] N=8 session — 8 pills evenly distributed (no overflow).
- [ ] N=10 session — 7 state pills + "+3" count pill, all together filling the row.
- [ ] ClipRecording — Chronometer counts DOWN smoothly, second-by-second. "0:18 → 0:17 → 0:16 → ..." visible on-device without service round-trips.
- [ ] GapWaiting — Chronometer counts down smoothly from `nextStartsInSeconds`.
- [ ] Merging — body shows static text "About 15 seconds remaining" or progress %; no Chronometer visible.
- [ ] MergeComplete — body shows "6 clips · 5:00 total · saved to Library"; no Chronometer visible.
- [ ] Typography — title is larger/bolder than body; body alpha is visibly lower than title; matches mockup density.
- [ ] TalkBack — Chronometer announces remaining time each tick (Android default behavior).
- [ ] OS font scale 2× — title + body still fit; Chronometer text doesn't overflow.
- [ ] Lockscreen — Chronometer keeps ticking on lockscreen during recording (VISIBILITY_PUBLIC).
- [ ] Force-stop mid-recording — no orphan Chronometer continues running.

## 8. Lint + static-check gate

No new `check*` task. Existing 25-strong gate must stay green.

Potential lint flags + responses:
- `NewApi` on `setCountDown(true)` — API 24+ method, our minSdk=24, no warning expected.
- `NewApi` on `setBoolean` via RemoteViews — API 16+, no warning.
- `NewApi` on `setFloat` via RemoteViews — API 16+, no warning.

## 9. Risks + mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Chronometer's `setCountDown` not honored on some OEM skins | M | Smoke on real device; if broken on Samsung One UI 6+, fall back to static body TextView with rate-limited service notify (current Phase 3 behavior). Document fallback gate in PR. |
| `setColorFilter` color tint applies wrong blend mode (e.g. SRC_ATOP vs SRC_IN) | L | Test with mid-tone shade BG behind the dot; the white shape + SRC_IN blend produces solid colored pill. Verified before commit. |
| `setFloat("setWeightSum", n)` doesn't trigger LinearLayout relayout | L | RemoteViews reflection invokes the setter; LinearLayout invalidates on weightSum change. Smoke verifies. |
| Chronometer's `setBase` past a large negative offset (clock jump on cold boot) | L | `SystemClock.elapsedRealtime()` is monotonic since boot — immune to wall-clock jumps. Safe. |
| Body TextView and Chronometer briefly both visible during state transition | L | Service rebinds atomically per state — only one of (Chronometer, body) has `VISIBLE` at any time. |

## 10. Acceptance criteria

1. JVM tests pass (≥ 1320 / 0-0-0; Phase 3 baseline ~1313 + ~7 new).
2. `:app:lintDebug` green, no new warnings.
3. `:app:assembleDebug` green.
4. Real-device smoke checklist §7.3 passes on Android 14+.
5. Dots are visibly distinct (color + alpha) in all 4 states.
6. Pills fill the full notification card width at N=2/3/6/8/10.
7. Chronometer ticks smoothly second-by-second in ClipRecording + GapWaiting.

## 11. Follow-on (still out of scope)

1. Rova launcher icon redesign (`mipmap-anydpi-v26` adaptive icon + monochrome).
2. Inter font asset shipping (currently using system sans-serif as visual approximation).
3. Mockup-faithful action button styling — would require fully-custom notifications (Android-12+ retired).

---

## Self-review

**1. Placeholder scan.** No "TBD" / "TODO" / "appropriate" / "implement later". Defaults are concrete (textSize/textColor values from mockup CSS, weightSum binding from a single setFloat call).

**2. Internal consistency.** ChronoSpec field naming (`baseElapsedMs`) matches the `RemoteViews.setChronometer(base, format, started)` signature. The "hide Chronometer / show body TextView" toggle is symmetric across the 2-vs-2 state split. Typography table values appear once and are referenced from §4.4 only.

**3. Scope.** Single subsystem (notification visual layer). Fits in 3 tasks. No cross-cut.

**4. Ambiguity.** One judgment call already locked at dispatch (Option B — no "remaining in this clip" suffix alongside Chronometer; mockup-style minimalism). No other open decisions.
