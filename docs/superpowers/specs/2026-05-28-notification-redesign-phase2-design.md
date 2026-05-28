# Notification Redesign — Phase 2 (Visual Skin) — Design Spec

**Created:** 2026-05-28
**Owner:** slunia@releasemyad.com
**Folds into:** M5 PR (same branch `feat/notification-redesign-v1`, squash-merge with Phase 1)
**Supersedes-visual:** [2026-05-27-notification-redesign-v1-design.md](./2026-05-27-notification-redesign-v1-design.md) §5.* "stock NotificationCompat" appearance only — semantic/a11y/channel design from Phase 1 stays load-bearing and is referenced, not reopened.

---

## 1. Context

Phase 1 (commits `38f2212..4967f9c`) shipped the semantic + behavioral layer of the redesigned recording notification: dual channels, per-state copy/actions/dismissibility, ETA/countdown/merge-% wiring, ~1Hz throttle, 49 pure-JVM tests, all 25 static-check gates green. Real-device smoke surfaced one residual gap: the **visual** still reads as a generic OS foreground-service notification because we stayed inside the stock `NotificationCompat.Builder` template.

Observed gap (from device screenshots `screenshots/Screenshot_20260528_103103.png` / `_103133.png` / `_103425.png`):
- `setColor(#5B7FFF)` accent is compressed to invisibility on modern Android FGS notifications (Samsung OneUI 7.x).
- Small icon renders as a system-tinted bubble — our 4 vectors are present but engulfed by OS chrome.
- No surface tint, no left accent rail, no chip styling — nothing visually identifies the notification as Rova versus any other backgrounded utility app.

Phase 2 closes that gap by moving to `NotificationCompat.DecoratedCustomViewStyle` with a custom RemoteViews **content area only**. The system continues to own the header (app name, expand/collapse affordance) and the action row (Stop / Open / View / Share buttons). This is the only path Android 12+ permits — fully-custom notifications were retired in API 31.

**Inspiration:** [`mockups/new_uiux/09-notification-export.html`](../../../mockups/new_uiux/09-notification-export.html). **Not a pixel-perfect reproduction** — Android 12+'s 48dp collapsed / 252dp expanded budget plus the imposed system header forbid that. Phase 2 inhabits the customizable area gracefully and aligns with the in-app HUD chrome already shipped via [`RecordChromeTokens.kt`](../../../app/src/main/java/com/aritr/rova/ui/theme/RecordChromeTokens.kt) — so the user sees one Rova design language across the in-app pill banner and the panel notification.

## 2. Scope

### In scope

- New pure helper `NotificationRenderer.kt` producing a `NotificationBindPlan` per state.
- Two shared XML RemoteViews layouts: `notif_collapsed.xml` (48dp) + `notif_expanded.xml` (≤252dp).
- Two new shape drawables: `notif_accent_rail.xml` (4dp full-height bar, runtime-tinted) and `notif_chip_bg.xml` (32dp rounded-square chip, runtime-tinted).
- Service refactor: `createNotification(state, sessionId)` now applies `DecoratedCustomViewStyle` + inflates RemoteViews + binds bind-plan fields. Action row stays on the system's `.addAction()` path (Phase 1 wiring untouched).
- Pure-JVM tests for `NotificationRenderer` (no RemoteViews mocking — bind plan is plain data).
- Real-device smoke pass folded into M5 final gate.

### Out of scope (NO-GO)

- Replacing the system header (app-name row, timestamp, collapse caret) — Android 12+ rejects this.
- Material You / dynamic-color participation — fixed brand colors only. Rationale: notification recognizability across user wallpapers.
- Replacing notification action row with custom buttons inside RemoteViews. The system row keeps TalkBack auto-wiring + per-OEM treatment.
- A 5th NotificationState. (Phase 1 NO-GO preserved.)
- Manifest schema bump. (Phase 1 NO-GO preserved.)
- Notification animations beyond the system-provided expand/collapse transition.
- Per-state structural divergence in the XML — both layouts are shared across all 4 states; the renderer toggles visibility/text/tint at bind time.

## 3. Architecture deltas (vs Phase 1)

```
app/src/main/java/com/aritr/rova/service/notification/
├── NotificationCopy.kt              [UNCHANGED]
├── NotificationIconRes.kt           [UNCHANGED — chip ImageView consumes toIconRes()]
├── NotificationChannelConfig.kt     [UNCHANGED — accent constants reused for rail + chip tint]
├── NotificationActionSpec.kt        [UNCHANGED — system action row]
└── NotificationRenderer.kt          [NEW — pure data: state → NotificationBindPlan]

app/src/main/java/com/aritr/rova/service/
└── RovaRecordingService.kt          [createNotification refactored — DecoratedCustomViewStyle path]

app/src/main/res/layout/
├── notif_collapsed.xml              [NEW — 48dp shared template]
└── notif_expanded.xml               [NEW — ≤252dp shared template]

app/src/main/res/drawable/
├── notif_accent_rail.xml            [NEW — 4dp solid shape, color tinted at runtime]
└── notif_chip_bg.xml                [NEW — 32dp rounded-square shape, color tinted at runtime]

app/src/test/java/com/aritr/rova/service/notification/
└── NotificationRendererTest.kt      [NEW — pure-JVM tests on bind plan fields]
```

### Data flow

```
NotificationState (Phase 1 sealed interface)
        │
        ▼
NotificationRenderer.toBindPlan(state)        (pure — testable)
        │
        ▼
NotificationBindPlan { layoutCollapsedRes, layoutExpandedRes,
                       title, body, accentColorInt, iconRes,
                       progress: NotificationProgress?, isComplete }
        │
        ▼
RovaRecordingService.createNotification(state, sessionId)
        │  inflates RemoteViews from layoutRes
        │  binds title/body via setTextViewText
        │  tints rail + chip via setInt R.id.* setColorFilter (or setColorStateList)
        │  sets icon via setImageViewResource
        │  shows/hides + binds progress via setProgressBar
        │  applies DecoratedCustomViewStyle + setCustomContentView/setCustomBigContentView
        │  retains Phase 1 channel/action/visibility wiring unchanged
        ▼
NotificationCompat.Builder → Notification
```

The renderer never touches Android `RemoteViews` or `Context` — that boundary is the service's responsibility (mirrors Phase 1's pure-helper / service-as-seam pattern).

## 4. Visual contract

### Layout grid (both templates)

```
┌─────────────────────────────────────────────────────────────┐
│ ▌  [icon]   Title text                                      │
│ ▌           Body line                                       │
│ ▌           ▰▰▰▰▰▰▰▱▱▱  (progress, optional)               │
└─────────────────────────────────────────────────────────────┘
 │   │        │
 │   │        └── content column: title + body + optional progress
 │   └── icon chip (32dp rounded-square, accent-tinted bg, white icon)
 └── accent rail (4dp wide, full notification height, accent solid)
```

**Spacings** (matches in-app HUD via RecordChromeTokens parity):
- Outer padding: 12dp top / 12dp bottom / 12dp end. Rail consumes the start edge with no padding.
- Rail → chip gap: 12dp.
- Chip → content gap: 12dp.
- Chip size: 32dp × 32dp, 8dp corner radius.
- Icon inside chip: 18dp, centered, white.
- Title → body vertical gap: 2dp.
- Body → progress vertical gap: 6dp.
- Progress bar height: 4dp.

### Per-state bind plan

| State | Accent | Icon | Body line | Progress | Action row |
|---|---|---|---|---|---|
| ClipRecording | blue `#5B7FFF` | `ic_notif_recording` | "X:XX remaining in this clip" (or fallback "Recording in progress") | hidden (Phase 1 `toProgress()` returns null for this state) | [Stop] [Open] |
| GapWaiting | blue `#5B7FFF` | `ic_notif_waiting` | "Next clip starts in M:SS" | determinate `(gapTotal − nextStartsIn) / gapTotal`, hidden if either is null | [Stop Early] [Open] |
| Merging | blue `#5B7FFF` | `ic_notif_merging` | "Processing — please wait" | determinate `mergeProgressPercent / 100`, indeterminate if percent null | (none — empty system row) |
| MergeComplete | green `#34D399` | `ic_notif_complete` | "N clips · M:SS total · saved to Library" (or fallback "N clips saved to Library") | hidden | [View in Library] [Share] |

All per-state copy + progress data already comes from Phase 1's `toCopy()` + `toProgress()` — Phase 2 only changes the *render*, never the data.

### Typography

Use `TextAppearance.Compat.Notification.Title` for the title TextView and `TextAppearance.Compat.Notification` for the body. These adapt to OS dark/light theme automatically; we do not hard-code text color. (Inter is NOT shipped into RemoteViews — custom fonts are unreliable in remote views; system body face stays.)

### Collapsed view (48dp)

Single line: `▌ [icon] Title • Body-fragment-or-meta`

Concrete examples:
- ClipRecording: `▌ [●] Recording · Clip 2 of 6     0:18 remaining`
- GapWaiting: `▌ [⌛] Waiting · Clip 3 of 6 next     4:42`
- Merging: `▌ [⇆] Merging clips · 4 of 6     67%`
- MergeComplete: `▌ [✓] Merge complete     6 clips`

Tail meta is right-aligned, dimmed (uses `Notification` body style with `alpha` via theme — RemoteViews has no direct alpha setter, so we use a styled TextView with reduced opacity in the layout XML). Tail meta is OPTIONAL; if there is no concise tail metric for a state, omit and let the title take the full row.

### Expanded view (≤252dp; targeting ~120–140dp typical)

Two-row template:
- Row 1: title (single line, ellipsize end if needed)
- Row 2: body (single line, ellipsize end)
- Row 3 (optional): progress bar
- (System renders action row below, owned by the platform)

Total custom-area height per state stays well under the 252dp ceiling.

## 5. Accessibility

### TalkBack contract

- System header (app name) + system action row keep automatic TalkBack support — unchanged from Phase 1.
- Custom body area gains explicit `setContentDescription` calls on RemoteViews elements:
  - Icon chip → state-keyed CD (e.g. "Recording in progress", "Waiting for next clip", "Merging clips", "Recording complete") — defined as string-res entries.
  - Progress bar → "Progress, NN percent" when determinate; "Indeterminate progress" when indeterminate; not announced when hidden. The percent string is computed at bind time (not in the renderer — context-needed).
  - Accent rail → `importantForAccessibility="no"` in the XML (purely decorative).
  - Title + body TextViews → no explicit CD; TalkBack reads their text directly.

### Color contrast

- `#5B7FFF` on dark notification surface ≥ 5.2:1 (WCAG AA ✓, verified in Phase 1 §8).
- `#34D399` on dark notification surface ≥ 7.8:1 (WCAG AA ✓).
- Title/body text colors come from `TextAppearance.Compat.Notification.*` — system-guaranteed contrast.
- Progress bar uses `progressTint = accent`, `progressBackgroundTint = accent at 24% alpha` — contrast against surface guaranteed by accent meeting body-text contrast.

### Reduce-motion / large-text

- No animation introduced (Android 12+ owns expand/collapse transition).
- Large-text via OS font scale: layouts use `wrap_content` heights + `ellipsize="end"` on title/body so 1.5×–2× scale truncates gracefully rather than overflowing.

## 6. New strings (res/values/strings.xml)

```xml
<!-- M5 Phase 2: chip contentDescription per state -->
<string name="notification_chip_cd_recording">Recording in progress</string>
<string name="notification_chip_cd_waiting">Waiting for next clip</string>
<string name="notification_chip_cd_merging">Merging clips</string>
<string name="notification_chip_cd_complete">Recording complete</string>

<!-- M5 Phase 2: progress bar contentDescription template -->
<string name="notification_progress_cd_determinate">Progress, %1$d percent</string>
<string name="notification_progress_cd_indeterminate">In progress</string>
```

## 7. Helper API contract — `NotificationRenderer.kt`

```kotlin
package com.aritr.rova.service.notification

import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.aritr.rova.R

/**
 * M5 Phase 2 — bind-plan emitted by [toBindPlan]. Pure data; the
 * service consumes it to inflate + bind RemoteViews. No Android
 * RemoteViews / Context calls in this file.
 */
data class NotificationBindPlan(
    @LayoutRes val layoutCollapsedRes: Int,
    @LayoutRes val layoutExpandedRes: Int,
    val title: String,
    val body: String,
    val collapsedTail: String?,         // right-aligned meta for collapsed view, or null
    @ColorInt val accent: Int,
    @DrawableRes val iconRes: Int,
    @StringRes val chipContentDescriptionRes: Int,
    val progress: NotificationProgress?, // reuse Phase 1 NotificationProgress
    val isComplete: Boolean              // drives colorized + slight layout tweaks if needed
)

fun NotificationState.toBindPlan(): NotificationBindPlan
```

`toBindPlan` is exhaustive over the 4 sealed-interface variants, with no `else` branch. It reuses `toCopy`, `toIconRes`, `toChannelId`, `toAccent`, `toProgress` from Phase 1 — no duplication.

The `collapsedTail` field is computed per-state:
- ClipRecording → "X:XX remaining" if eta present, else null
- GapWaiting → `nextInLabel` directly
- Merging → "NN%" if `mergeProgressPercent` present, else "N of M"
- MergeComplete → "N clip(s)"

## 8. Testing

### Pure-JVM tests — `NotificationRendererTest.kt`

For each of the 4 sealed-interface variants:
- Bind plan's `layoutCollapsedRes` / `layoutExpandedRes` equal the shared layouts (R.layout.notif_collapsed / notif_expanded).
- `accent` matches the channel-config constant for that state.
- `iconRes` matches the existing `toIconRes()` result for that state.
- `chipContentDescriptionRes` is the correct string-res id.
- `progress` is the same value Phase 1's `toProgress()` returns.
- `isComplete` is true iff the variant is `MergeComplete`.
- `collapsedTail` matches the per-state formula above (3 sub-cases for variants with optional inputs).

Approx **~16 new tests** total. Existing Phase 1 tests (49 / 0) stay green untouched — the renderer is additive.

### What we do NOT unit-test

- RemoteViews binding inside the service. RemoteViews is framework code with no reasonable JVM stub; the only meaningful coverage is real-device visual.
- XML layout structural correctness. AAPT2 catches malformed XML at compile time; a unit test against XML would just re-parse what AAPT2 already validated.

### Real-device smoke (folded into M5 final gate, supersedes Phase 1 visual items)

- [ ] **ClipRecording**: 48dp collapsed shows blue rail + blue chip with white dot icon + "Recording · Clip 1 of N" + "0:XX remaining" tail. Pull down → expanded shows the indeterminate pulse-style progress strip.
- [ ] **GapWaiting**: blue rail + blue chip with hourglass icon + "Waiting · Clip N of M next" + tail "M:SS". Expanded shows determinate countdown bar shrinking each second.
- [ ] **Merging**: blue rail + blue chip with merge-arrows icon. Expanded shows determinate fill bar advancing as merge runs. No system action buttons (empty row).
- [ ] **MergeComplete**: **green** rail + green chip with check icon + "Merge complete" + tail "N clips". Expanded shows full body + system actions [View in Library] [Share]. Swipe-dismissible.
- [ ] Lockscreen during recording: title row visible, body row visible (VISIBILITY_PUBLIC). Lockscreen post-MergeComplete: body hidden (VISIBILITY_PRIVATE).
- [ ] TalkBack: each state announces chip CD + title + body + (progress percent if shown) + action labels.
- [ ] Large-text OS setting at 1.5× and 2×: title + body truncate with ellipsis instead of overflowing the 252dp budget.
- [ ] Light theme (OS): text remains legible (Compat text appearance adapts).
- [ ] All Phase 1 smoke items (channel topology in Settings → Notifications, action wiring, etc.) still pass — re-verify the per-channel disable case still suppresses MergeComplete independently.

## 9. Lint + static-check gate impact

No new static-check task introduced — the existing 25 checks all watch the service file and the export pipeline, none of which Phase 2 changes structurally beyond the `createNotification` body. We MUST verify all 25 still pass after the renderer + RemoteViews wiring lands.

If `lintDebug` flags a `NewApi` warning for `RemoteViews.setColorStateList` (API 31+) or similar, gate the call with `Build.VERSION.SDK_INT` and fall back to `setInt(..., "setColorFilter", color)` for API 24–30. The fallback is well-established and works back to API 1.

## 10. Risks + mitigations

| Risk | Mitigation |
|---|---|
| OEM (Samsung OneUI, Xiaomi MIUI, OPPO) re-themes our RemoteViews and clips the accent rail | Test on Samsung (already in user's device pool); add Pixel test if rail clipping appears |
| `TextAppearance.Compat.Notification.*` text colors look wrong on some OEM dark themes | Acceptable trade vs hard-coding; if user reports a real broken case, switch the affected element to a system color directly |
| Custom layout drops below the 48dp / 252dp limits on small screens | Use `wrap_content` heights + `ellipsize="end"` on text. Re-verify in smoke at 1× and 2× font scale |
| Accent compression like Phase 1 `setColor()` returns | Rail + chip are explicit RemoteViews fills, NOT system-honored `setColor` — accent visibility is now controlled by us, not the OS |
| TalkBack reads tail-meta + body redundantly | Tail-meta uses TextView text directly (no CD override); if redundancy is jarring at smoke time, give the tail-meta TextView `importantForAccessibility="no"` since the same info appears in the expanded body |

## 11. Acceptance criteria

- All 4 states render with the visual contract above on real Android 14+ device.
- `NotificationRendererTest` 16/0 + Phase 1 tests 49/0 + project baseline = total ≥ 1299 / 0.
- All 25 static check* tasks green.
- Real-device smoke checklist §8 fully passes.
- Phase 1 a11y rules (channel routing, dismissibility, lockscreen visibility, alert-once) preserved.
- M5 PR (Phase 1 + Phase 2 squashed) is mergeable.

## 12. Follow-on (post-M5)

- Cleanup slice (one release after M5 ships): delete the legacy `RovaRecordingChannel` channel registration; existing user-importance overrides decay naturally.
- Animation polish (e.g. ProgressBar drawable with subtle gradient): deferred; requires custom drawable XML and is out of scope.
- "Pause" affordance on ClipRecording: blocked by the upstream pause-resume work that is itself deferred (see ADR-0009 follow-on).
