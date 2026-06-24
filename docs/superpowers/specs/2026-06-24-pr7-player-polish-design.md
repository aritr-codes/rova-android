# PR-7 — Player Polish (speed · double-tap edge-seek · auto-hide chrome) — Design Spec

> **Status:** DRAFT / DESIGN-ONLY — no source touched by this document.
> **Date:** 2026-06-24
> **Track:** Video / Player / Editing (`docs/BACKLOG.md`). The deferred PR-7 slice; lands after PR-6 (#127), resume (#137), and PR-6b wall-clock (#139) — all MERGED.
> **Scope owner sign-off required before any code.** Open questions at the end.

---

## 0. Framing — this is a REVIEW player, not a streaming player

Authoritative reframe (`memory/project_player_roadmap.md`, codex-sharpened): Rova's player reviews the user's **own** periodic/segmented recordings — a personal surveillance/dashcam **review** tool. Value ranking inverts vs Netflix/YouTube: **find-the-moment > watch-fast**. Navigation (timeline scrub, clip-jump, wall-clock) is already shipped and is the headline. PR-7 is the *comfort/skim* tail — explicitly the last functional player slice, and deliberately small.

### Explicitly OUT OF SCOPE (evaluated + rejected 2026-06-20, re-affirmed here)
These are streaming cruft for a local self-recording review tool. **Do not add, do not "while we're here":**

- **Picture-in-Picture (PiP)** — FLAG_SECURE blanks the vault surface; no MediaSession; wrong use case.
- **Gesture overload** — pinch-zoom, swipe-for-volume, swipe-for-brightness. Portrait by design; audio is secondary; brightness is an OS concern.
- **Fullscreen / orientation / landscape mode** — player is portrait by design.
- **Closed captions / subtitles** — self-recordings have no caption track.
- **Casting / Chromecast / remote playback** — local-only review.
- **Quality / audio-track switching, A-B loop, frame-step, mute, lock-controls** — P3 "later/nice" at most; NOT PR-7.

The three PR-7 features below are the **entire** scope. If a feature here starts to pull in any rejected item, stop and escalate.

---

## 1. Current player baseline (what PR-7 builds on)

Files under `app/src/main/java/com/aritr/rova/ui/screens/player/`:

- **`PlayerViewModel.kt`** — owns the single `ExoPlayer`; exposes `uiState: StateFlow<PlayerUiState>` and `progress: StateFlow<PlaybackProgress>`. Transport primitives already present: `togglePlayPause()`, `seekRelative(deltaMs)`, `seekTo(positionMs)`, `jumpNextSegment()`, `jumpPrevSegment()`, scrub state-machine (`beginScrub`/`updateScrub`/`endScrub` with `scrubActive` guard, CLOSEST_SYNC→EXACT, ~180 ms throttle), `pauseForBackground()`, position polling @250 ms, resume-at-attach (`PlayerResumeMath`/`ResumePolicy`), wall-clock readout (PR-6b).
- **`PlayerScreen.kt`** (547 lines) — `useController = false`, fully custom Compose chrome. Layout: full-bleed `AndroidView(PlayerView)` + a **top gradient bar** (back + title/clip-count) + a **bottom panel** `Column` (`InfoRow` → `SegmentedTimeline` → `ControlsRow`) + `WallClockReadout`. `ControlsRow` = Replay10 `IconButton` · 48dp circular play/pause `Surface(onClick)` · Forward10 `IconButton`. `SEEK_DELTA_MS = 10_000L`.
- **`PlaybackProgress`** — `positionMs`, `durationMs`, `isPlaying`, `isScrubbing` (snapshot rebuilt by `pushProgress`; `isScrubbing` owned exclusively by begin/endScrub, preserved by everyone else).
- **Pure helpers** — `SegmentedTimelineMath`, `PlayerUriResolver`, `RecordHudFormatters`, `WallClockTimeline`, `SegmentOrdering`, `PlayerResumeMath`, `ResumePolicy`, `PlayerStateEmitter`. House pattern: framework-touching wrapper stays a thin seam, logic lives in a JVM-testable pure object.
- **Reduced-motion seam** — `com.aritr.rova.ui.components.rememberReduceMotion()` (`ReducedMotion.kt`). Already used for the timeline playhead glide.
- **a11y conventions in this file** — `Modifier.semantics { contentDescription = … }`, `Role` imported, 48dp play/pause target, `stringResource`/`pluralStringResource` everywhere (zero hardcoded UI strings), text alpha ≥0.72 over the dark scrim for AA 4.5:1 (PLR-01).

### Static gates that bind this work (all in `app/build.gradle.kts`, wired to `preBuild`)
- **`checkNoHardcodedUiStrings`** — `Text("…")` / `contentDescription = "…"` literals forbidden; everything via `stringResource`. **Every new string lands in BOTH `values/strings.xml` and `values-es/strings.xml`** (locale filter is `en, es`).
- **`checkUserCopyVocabulary`** — banned `(?i)\b(loops?|repeats?|segments?|ciclos?|segmentos?|…)\b` in en+es string **values**. Use **"clip"** and **"session"**. (Affects any new CD copy — e.g. NOT "segment", say "clip".)
- **`checkA11yAnimationGated`** — any `.kt` file using a RAW infinite-animation primitive (`rememberInfiniteTransition(`, etc.) must also read the reduced-motion seam in the same file. **Auto-hide fade and any speed-chip transition must gate on `rememberReduceMotion()`.**
- **`checkA11yClickableHasRole`** — clickable composables must declare a semantics `Role`. **Double-tap zones and speed control must carry a Role + contentDescription.**
- **`checkA11yTargetSizeToken`** — curated touch-target set must be ≥24dp (SC 2.5.8); house bar is ≥48dp for primary controls. **Speed control hit target ≥48dp.**

---

## 2. Feature 1 — Playback speed control

### 2.1 User-facing behavior
A reviewer skimming a long merged session wants to speed through quiet stretches. Provide a small set of discrete speeds: **0.5× · 1× · 1.5× · 2×** (0.5× is a *review* affordance — slow-mo to confirm a detail — and fits "find-the-moment" better than a 4× firehose). **4× is DEFERRED** to device-test (codex: only ship 4× if audio/comprehension holds; this is a review tool, not a skim-for-hours tool). Speed applies immediately and **persists for the current player session only** (resets to 1× on next open — do NOT add a new persisted setting; out of scope).

Default = **1×**. The control shows the current speed (e.g. "1×") and cycles or opens a tiny chooser on tap.

### 2.2 UI treatment
Two candidate placements (owner picks — see open Q1):
- **(A) A compact text chip** in `ControlsRow` (or a new row above it) reading "1×", glass-styled (`Surface(color = Color.White.copy(alpha = 0.10f), shape = …)`) consistent with the existing 48dp play/pause `Surface`. **Tap cycles** 1× → 1.5× → 2× → 0.5× → 1×. Cheapest; one tap target.
- **(B) A speed chip that opens a small popup** (`DropdownMenu` or a row of 4 chips) for direct selection. More discoverable, more surface.

Recommendation: **(A) cycling chip** for v1 — minimal surface, matches the "small tail" framing; revisit (B) only if device-smoke shows cycling is confusing. Either way:
- Hit target ≥48dp (`checkA11yTargetSizeToken`).
- Label via `stringResource` (`player_speed_label`, formatted with the multiplier). Decimal formatting is **locale-aware** — "1.5×" vs "1,5×" (es). Do this in the pure helper / via `String.format(locale, …)`, never a raw literal.
- `contentDescription` announces current speed + that tapping changes it; **Role.Button** (`checkA11yClickableHasRole`).
- No animation needed; if a selection highlight animates, gate on `rememberReduceMotion()`.

### 2.3 ViewModel seam
Add to `PlayerViewModel`:
- `setPlaybackSpeed(speed: Float)` → `exoPlayer?.setPlaybackSpeed(speed)` (Media3 1.4.1 supports `Player.setPlaybackSpeed`; **no MediaSession needed**). Persist `speed` into `PlaybackProgress` so the UI reflects it (add `speed: Float = 1f` field — see §5 conflict note on snapshot preservation, identical discipline to `isScrubbing`).
- A `cycleSpeed()` convenience OR keep cycling logic in the pure helper and call `setPlaybackSpeed` with the result.

### 2.4 PURE HELPER + tests
New pure object **`PlaybackSpeedPolicy`** (`…/player/PlaybackSpeedPolicy.kt`):
```
object PlaybackSpeedPolicy {
    val SPEEDS: List<Float> = listOf(0.5f, 1f, 1.5f, 2f)   // 4f gated behind device-test
    val DEFAULT: Float = 1f
    fun next(current: Float): Float            // cycle order, wraps; tolerant of off-list current (snap to nearest then advance)
    fun isValid(speed: Float): Boolean
    fun clampToSupported(speed: Float): Float  // defensive: coerce arbitrary input onto SPEEDS
    fun label(speed: Float, locale: Locale): String  // "1×" / "1.5×" / "0.5×" — locale decimal
}
```
JVM unit tests (`PlaybackSpeedPolicyTest`):
- `next(1f) == 1.5f`, `next(2f) == 0.5f` (wrap), `next(0.5f) == 1f`.
- `next` of an off-list value (e.g. 1.25f, 3f) snaps sensibly (define: snap-to-nearest-then-advance, or snap-to-default — owner Q2).
- `clampToSupported(4f) == 2f`, `clampToSupported(0.1f) == 0.5f`.
- `label` decimal formatting for `Locale.US` ("1.5×") and `Locale("es") ("1,5×") — pins the locale-aware contract.
- `isValid` matrix.

---

## 3. Feature 2 — Double-tap edge-seek

### 3.1 User-facing behavior
Double-tap the **left third** of the video surface → jump **back** `SEEK_DELTA_MS` (10 s); double-tap the **right third** → jump **forward** 10 s. Double-tap the **center third** → toggle play/pause (familiar YouTube-style affordance, and gives the center a meaningful action so it isn't a dead zone). This is a **discoverable, faster** alternative to the explicit Replay10/Forward10 buttons. Per the roadmap, the explicit seek buttons *may* then retire (owner Q3) — but **default to KEEPING them** for discoverability/a11y (double-tap is not TalkBack-friendly; see §3.4).

Single-tap anywhere = toggle chrome visibility (Feature 3). Disambiguation is the central interaction risk — see §5.

### 3.2 UI treatment
- Optional **brief ripple/flash** at the tap point indicating "−10s" / "+10s" (text via `stringResource`). If animated, **gate on `rememberReduceMotion()`** → snap/no-fade when reduced (`checkA11yAnimationGated`). v1 may ship with **no visual feedback** (the playhead jump is itself feedback) to keep surface minimal; owner Q4.
- The gesture surface is the full-bleed video `Box`. It uses `pointerInput { detectTapGestures(onTap = …, onDoubleTap = { offset → … }) }`. Because this is a clickable/gesture region, it needs a semantics `Role` + `contentDescription` for the single-tap toggle (`checkA11yClickableHasRole`) — see §3.4.
- Edge thirds computed from layout width; the **center third is wide enough** that an accidental edge double-tap is unlikely. The L/R/C split is a pure function (testable).

### 3.3 ViewModel seam
Reuses existing primitives — **no new VM method strictly required**:
- back/forward → `seekRelative(-SEEK_DELTA_MS)` / `seekRelative(+SEEK_DELTA_MS)` (already clamps to `[0, duration]`).
- center → `togglePlayPause()`.

### 3.4 a11y note (important)
`detectTapGestures` double-tap is **invisible to TalkBack** (no semantic action). Therefore:
- **Keep the explicit Replay10/Forward10 IconButtons** (they carry `player_rewind_cd`/`player_forward_cd` and are the accessible path). Default recommendation: do NOT retire them (overrides the roadmap's "lets the explicit seek buttons retire" — flag as Q3).
- The single-tap chrome-toggle region must still expose a Role + CD so the surface isn't an unlabeled clickable. Consider `Role.Button` with a CD like "Video, tap to show controls".
- Do NOT put the ±10s seek **only** behind double-tap.

### 3.5 PURE HELPER + tests
New pure object **`EdgeSeekZones`** (`…/player/EdgeSeekZones.kt`):
```
object EdgeSeekZones {
    enum class Zone { SEEK_BACK, TOGGLE, SEEK_FORWARD }
    fun zoneFor(tapX: Float, width: Float, leftFraction: Float = 1f/3f, rightFraction: Float = 1f/3f): Zone
    // returns SEEK_BACK for left band, SEEK_FORWARD for right band, TOGGLE for center; clamps out-of-range x
}
```
JVM unit tests (`EdgeSeekZonesTest`):
- `zoneFor(10f, 300f) == SEEK_BACK`, `zoneFor(150f, 300f) == TOGGLE`, `zoneFor(290f, 300f) == SEEK_FORWARD`.
- Boundary exactness (x at exactly leftFraction*width and rightFraction*width — define inclusive/exclusive).
- `width == 0f` / negative x → safe default (TOGGLE, no crash — divide-by-zero guard).
- Custom fractions honored.

> The ±10s math is already covered by `seekRelative`'s clamp; no new math there. Optionally a thin `EdgeSeekIntent` mapping Zone→action delta if the owner wants the delta itself tested (low value; the delta is a constant).

---

## 4. Feature 3 — Auto-hide chrome

### 4.1 User-facing behavior
After **~3 s of inactivity while playing**, the top bar + bottom panel (info/timeline/controls/wall-clock) **fade out**, leaving the bare video. Any tap **reappears** them and restarts the timer. The chrome is **pinned visible** (timer suspended) when:
- playback is **paused** (a reviewer studying a frame must keep the controls),
- a **scrub is in progress** (`progress.isScrubbing == true`),
- (optional) a **speed popup is open**, if Feature 1 ships variant (B).

This is explicitly **NOT the headline** and lands last — it depends on Feature 2's single-tap-to-toggle gesture existing.

### 4.2 UI treatment
- Wrap the top bar and bottom panel in `AnimatedVisibility` (fade). **The fade MUST gate on `rememberReduceMotion()`** — when reduced, swap to an instant show/hide (no fade), via `snap()`/`tween(0)` or a plain `if (visible)`. `checkA11yAnimationGated` requires the seam to be read in `PlayerScreen.kt` (it already is for the glide — keep that satisfied).
- Controls hidden ⇒ also **not focusable by TalkBack** (use the standard `AnimatedVisibility` which removes from composition, so hidden controls don't trap focus). When chrome is hidden, the single-tap region remains active to bring it back.
- The `view.keepScreenOn = progress.isPlaying` behavior is unaffected (screen stays on during playback regardless of chrome visibility).

### 4.3 State ownership
Auto-hide visibility is **transient view state** — it does NOT belong in `PlaybackProgress` / the VM (it's not playback state, survives no config-change semantics worth persisting, and putting it in the VM invites a poll/tick coupling). Keep it in `PlayerReady` composable state:
- `var chromeVisible by remember { mutableStateOf(true) }`
- A `LaunchedEffect` keyed on `(chromeVisible, progress.isPlaying, progress.isScrubbing, lastInteractionTick)` that, **only when `isPlaying && !isScrubbing && chromeVisible`**, `delay(AUTO_HIDE_MS)` then sets `chromeVisible = false`. Pausing / scrubbing / a fresh tap cancels-and-restarts the effect (key change re-launches it).
- The single-tap handler sets `chromeVisible = true` and bumps `lastInteractionTick`.

### 4.4 PURE HELPER + tests
The decision "should chrome be visible / should the timer run" is pure and worth extracting so the gating rules are testable without Compose. New pure object **`AutoHideChromePolicy`** (`…/player/AutoHideChromePolicy.kt`):
```
object AutoHideChromePolicy {
    const val DEFAULT_TIMEOUT_MS = 3_000L
    // The timer should only count down when ALL hold:
    fun shouldRunHideTimer(isPlaying: Boolean, isScrubbing: Boolean, chromeVisible: Boolean, speedMenuOpen: Boolean = false): Boolean
    // Given an event, what's the next visibility:
    fun onUserTap(currentlyVisible: Boolean): Boolean   // tap toggles? or always-show? — define (see Q5)
    fun onPlaybackPaused(): Boolean                     // -> true (pin visible)
}
```
JVM unit tests (`AutoHideChromePolicyTest`):
- `shouldRunHideTimer(playing=true, scrubbing=false, visible=true) == true`.
- Suppressed when paused / scrubbing / already hidden / speed-menu-open (each a row).
- `onPlaybackPaused()` forces visible.
- Tap semantics matrix (depends on Q5: does a tap *toggle* chrome, or always *show*?).

> Note: the `delay()` itself stays in the `LaunchedEffect` (Compose/coroutine side); the helper only decides *whether* the timer is allowed to run and what the next visibility is. This keeps the helper pure and the wrapper thin (house seam pattern).

---

## 5. Interaction conflicts between the three features (the hard part)

### C1 — Single-tap (toggle chrome) vs double-tap (edge-seek) — THE central conflict
`detectTapGestures` resolves this for free: `onTap` fires only after the double-tap timeout elapses with no second tap; `onDoubleTap` fires on the second tap. So:
- 1 tap → `onTap` → toggle/show chrome (Feature 3).
- 2 taps → `onDoubleTap(offset)` → `EdgeSeekZones.zoneFor(offset.x, width)` → seek or toggle-play (Feature 2).
**Caveat:** a deliberate single tap has a perceptible (~300 ms) delay before chrome reacts, because the gesture detector waits to rule out a double tap. This is standard and acceptable for a player, but **owner should be aware** (Q6). Do NOT hand-roll tap disambiguation — use `detectTapGestures`'s built-in arbitration.

### C2 — Auto-hide timer vs scrub
While `isScrubbing`, the hide timer must be **suspended** (`AutoHideChromePolicy.shouldRunHideTimer` returns false). The scrub state-machine already publishes `isScrubbing` in `PlaybackProgress`; the `LaunchedEffect` keys on it, so a scrub both prevents hiding and (on end) restarts the timer. No new plumbing.

### C3 — Auto-hide vs speed control
If chrome auto-hides, the speed chip hides with it — fine (tap brings it back). **But** if Feature 1 ships variant (B) with a popup, an **open speed popup must pin chrome** (`speedMenuOpen` arg to `shouldRunHideTimer`). With variant (A) cycling chip there's no popup, so no pin needed — another reason to prefer (A).

### C4 — Double-tap center (toggle play) vs auto-hide (pause pins chrome)
Double-tap center → pause → `AutoHideChromePolicy.onPlaybackPaused()` forces chrome visible. Consistent: pausing always reveals controls. No conflict, but the ordering must be: apply play/pause first, then recompute visibility (the `LaunchedEffect` key on `isPlaying` handles this reactively).

### C5 — Edge double-tap while chrome visible
If the user double-taps an edge while the bottom panel is showing, the double-tap is on the **video surface above the panel**, not on the panel's buttons. Ensure the gesture `pointerInput` is on the full-bleed video `Box` (z-below the chrome), so taps on actual control buttons are consumed by those buttons, not the seek gesture. Verify the bottom panel intercepts its own touches (it does — `Surface(onClick)` / `IconButton` consume).

---

## 6. Test & gate summary

**New JVM unit tests** (pure helpers, run under `isReturnDefaultValues = true`):
- `PlaybackSpeedPolicyTest` — cycle/wrap, off-list snap, clamp, locale label, validity.
- `EdgeSeekZonesTest` — zone boundaries, degenerate width, custom fractions.
- `AutoHideChromePolicyTest` — timer-run gating matrix, pause-forces-visible, tap semantics.

**Compose-side** (verified by device smoke, per house policy — no Robolectric): chrome fade gates on reduced-motion; double-tap zones map correctly on a real surface; speed audibly/visibly changes; TalkBack still reaches play/pause/seek buttons; chrome pins while paused/scrubbing.

**Gates to keep green:**
- `checkNoHardcodedUiStrings` — all new copy via `stringResource`, EN **and** ES (new keys: `player_speed_label`, `player_speed_cd`, possibly `player_seek_back_flash`/`player_seek_forward_flash`, `player_show_controls_cd`).
- `checkUserCopyVocabulary` — use "clip"/"session", never "segment", in any new value.
- `checkA11yAnimationGated` — auto-hide fade + any chip/flash animation read `rememberReduceMotion()` in `PlayerScreen.kt`.
- `checkA11yClickableHasRole` — speed chip + the single-tap video region carry a `Role`.
- `checkA11yTargetSizeToken` — speed chip ≥48dp.
- Baseline unchanged otherwise; no new `check*` task expected (PR-7 adds no new invariant).

**Test baseline:** new tests land in the same PR (house policy). Each new pure helper ships its test file.

---

## 7. Suggested PR shape
Single PR (the slice is small), but commit-ordered so review is incremental and each feature is independently revertable:
1. `PlaybackSpeedPolicy` + tests + speed chip wired into `ControlsRow` + `setPlaybackSpeed` VM seam + EN/ES strings.
2. `EdgeSeekZones` + tests + `detectTapGestures` on the video `Box` (back/center/forward) + (optional) flash feedback.
3. `AutoHideChromePolicy` + tests + `AnimatedVisibility` wrap on top/bottom chrome + interaction wiring (depends on #2's single-tap handler).

Feature 3 **must** come after Feature 2 (single-tap-to-show is the entry point for re-showing hidden chrome).

---

## 8. OPEN DESIGN QUESTIONS (need owner input before code)

- **Q1 — Speed control shape:** cycling chip (variant A, minimal) vs popup chooser (variant B, more discoverable). Recommendation: A. Confirm?
- **Q2 — Speed set:** ship `0.5× / 1× / 1.5× / 2×`? Include `0.5×` slow-mo (review affordance) or drop it for fewer cycle stops? Is `4×` permanently out, or gated behind a device-comprehension smoke test?
- **Q3 — Retire the explicit Replay10/Forward10 buttons** once double-tap exists? Recommendation: **KEEP** (double-tap is TalkBack-invisible; they're the accessible seek path). Confirm we keep them.
- **Q4 — Double-tap visual feedback:** ship a "−10s/+10s" flash (reduced-motion-gated) or rely on the playhead jump alone for v1?
- **Q5 — Single-tap chrome semantics:** does a tap **toggle** chrome (show if hidden, hide if shown) or **always show** (hide only via the auto-timer)? "Always show on tap" is simpler and avoids a tap accidentally hiding controls the user wanted; recommendation = always-show-on-tap.
- **Q6 — Single-tap latency:** the ~300 ms double-tap arbitration delay before single-tap chrome reaction — acceptable? (Standard for players; alternative is no double-tap, which kills Feature 2.)
- **Q7 — Auto-hide timeout:** 3 s default — right for a review tool, or longer (reviewers study frames)? Should it auto-hide at all when the user is actively *reviewing* (vs just letting it play)?
- **Q8 — Speed persistence:** confirm speed is **session-only** (resets to 1× on reopen), i.e. no new persisted `RovaSettings`/sidecar field. (Recommendation: session-only; persisting is scope creep.)
