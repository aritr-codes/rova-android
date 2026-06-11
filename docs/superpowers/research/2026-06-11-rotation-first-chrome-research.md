# Rotation-first chrome — research report (pre-spec)

**Status:** Research complete 2026-06-11. Feeds the PR-ε "fixed physical layout + counter-rotating elements" spec.
**Method:** 5-angle web sweep, 21 sources fetched, 99 claims extracted, 25 adversarially verified (3-vote panels) — 25 confirmed, 0 refuted. Plus first-party derivation of the geometry/interpolation math and a current-docs check of Compose semantics.
**Companion decisions already ratified by owner:** fixed-physical chrome on phones (Option 1), settings sheet unlocks the window while open (Option A), β axis-flip chrome retained as the large-screen fallback.

---

## 1. The architecture is the documented industry standard

Camera apps split into two documented orientation models (VisionCamera orientation guide, corroborated by OSXDaily + AirServer observations of iOS Camera):

| Model | Behavior | Who uses it |
|---|---|---|
| **Device** | UI window locked; capture output + individual controls track physical rotation (icons counter-rotate in place) | **Stock iOS Camera, most photography apps** |
| **Interface** | Output orientation follows the UI window; if UI doesn't rotate, output stays portrait | Snapchat, Instagram (social cameras) |

Rova's ratified model **is** the photography-app standard. Spec should name the social-app model as the deliberate non-goal.

Scope caveat that independently confirms our large-screen plan: **iPad Camera fully rotates its UI** — Apple itself abandons the fixed model on large screens. The β adaptive chrome on sw600dp is therefore the *correct UX*, not merely Android-policy compliance.

## 2. Orientation pipeline — verified mechanics

- `OrientationEventListener.onOrientationChanged` delivers **raw continuous 0–359°** (0 = natural, 90 = left side up, 180 = upside down, 270 = right side up). Both landscape senses directly distinguishable. All snapping is app code. (API reference, unchanged since API 3.)
- **Canonical snap** (official CameraX orientation guide, verbatim):
  `[45,135)→ROTATION_270 · [135,225)→ROTATION_180 · [225,315)→ROTATION_90 · else ROTATION_0`
  ⚠️ **Inverse-mapping trap:** physical 45–135° (left side up) maps to ROTATION_**270**, not 90 — display rotation is counter-clockwise vs clockwise physical rotation. **UI counter-rotation angle and CameraX Surface rotation are NOT the same number.** The spec must define both mappings explicitly and the implementation must keep them as two named functions.
- **No hysteresis exists in any official sample** (full-page verified: zero occurrences of hysteresis/buffer/debounce in the CameraX guide; CameraXBasic has no alternative precedent). A buffer band is Rova's own addition, tuned on device. Precedent in-repo: asymmetric `ThermalHysteresis`.
- **Flat device:** listener reports `ORIENTATION_UNKNOWN (-1)` once at the transition to flat (AOSP guard `magnitude*4 >= Z*Z`, sign-agnostic — face-up and face-down both). Official handling = silent early return → **hold last known rotation, never spin while flat.**
- **Locked window:** `Display.getRotation()` never changes under lock — the sensor listener is the *documented* mechanism for keeping `targetRotation` correct in a locked camera activity (official guidelines table, "Locked orientation" row; enable in onStart, disable in onStop). UI counter-rotation and capture rotation should derive from one listener/state source. Rova note: the PR-α service seam (`OrientationSnap`) already implements the capture side; the UI signal must converge with (not duplicate-drift from) its snap math.

## 3. Compose mechanics — two corrections to working assumptions

1. **Hit-testing FOLLOWS graphicsLayer transforms on Android.** The earlier "draw-only, touch bounds unrotated" caution (codex review) is true **only for layout** (measure/placement unchanged — verified verbatim in the official doc). For pointer input, androidx-main `NodeCoordinator.hitTestChild` maps the pointer through the full inverse layer matrix including rotationZ; JetBrains/compose-multiplatform#3230 confirms Android fired clicks at the *transformed* location and maintainers treated that as correct (the ignore-transform behavior was a Desktop/web bug, fixed 2023-06). Consequence: a rotated 48 dp control stays tappable where it *appears*, no hit-rect compensation.
   **Implementation rule:** modifiers chained *after* `graphicsLayer` are inside the transform (rotated targets); modifiers *before* it are not. **Rova choice: put `clickable`/semantics on the stable outer square container** — for a square the at-rest bounds are identical either way, and the target doesn't sweep mid-animation.
2. **`rotationZ` is an unbounded plain Float** ("0–360" in docs is convention, not enforcement), default pivot `TransformOrigin.Center` — bare `graphicsLayer { rotationZ = angle }` already rotates in place. Shortest-path is entirely the caller's job; accumulating past 360 is legal and is the simplest representation.

**Geometry (derived, consistent with the verified draw-only layout semantics):**
- w×h content at angle θ has AABB `(w·|cosθ| + h·|sinθ|) × (w·|sinθ| + h·|cosθ|)`.
- The max over a 0→90° sweep is the **diagonal √(w²+h²)** (at θ = atan(h/w)), *not* the 90° endpoint — clearance must cover mid-spin.
- **Square rule:** a square slot is rotation-invariant at the endpoints, and content with visual diameter ≤ slot/√2 never clips *mid-spin* either. A 48 dp slot holds spinning visuals up to ~33.9 dp — standard 24 dp icons fit with margin.
- Non-square content (timer pill, labels): square-slot it, reserve a max(w,h) slot and accept transient AABB overflow into empty viewfinder, or **crossfade instead of rotate**.

**Shortest-path delta:** `Δ = ((target − current + 540) mod 360) − 180` → Δ ∈ [−180, 180); animate an unwrapped accumulator `current + Δ`. (Worked check: 0→270 gives −90, not +270.)

**Animation API:** `Animatable` vs `animateFloatAsState` for retarget-mid-spin behavior — *no authoritative claim survived verification*; small spike planned in the implementation (T-spike), expectation: `Animatable` for explicit shortest-path retargeting.

## 4. Touch targets

Google accessibility guidance (verified verbatim): **≥48×48 dp, ≥8 dp separation**; 48 dp ≈ 9 mm physically, inside the recommended 7–10 mm range; Compose Material auto-pads via `minimumInteractiveComponentSize`. 48 dp squares are doubly convenient: they satisfy the floor *and* are the rotation-invariant bounds the architecture needs. Exceeds the ADR-0020 WCAG 2.2 AA floor (SC 2.5.8 = 24×24 px).

## 5. Sheet taxonomy (M3) — partially open

M3 defines exactly two variants (M2's three were reduced):
- **Standard** — supplementary content, primary content stays accessible, **no scrim** (viewfinder stays interactive). Compose: `BottomSheetScaffold`.
- **Modal** — above a scrim, disables everything else until dismissed. Compose: `ModalBottomSheet` (Rova's current portrait sheet).

How stock cameras *actually* structure quick-settings over a live viewfinder (strip vs sheet, peek states, scrim vs blur, rotation behavior of the surface) **did not survive web verification** → on-device observation (§8).

## 6. Gesture grammar (for future viewfinder gestures; record-screen layering)

- **Region-conditioned disambiguation** (Apple patent US9716825B1, granted 2017, active): the camera UI names seven input areas; the *same gesture* means different things by region (tap in viewfinder = focus; tap elsewhere = that control's action). Condition on hit region first, gesture type second.
- **Exclusivity rule** (medium confidence, 2-1 vote — patent embodiment, not shipping iOS): a recognized mode-swipe suppresses zoom for that gesture. Shipping iOS allows mode-swipes anywhere on the viewfinder — don't generalize the patent's area confinement.
- **Reachability** (Halide Mark II, first-party): all controls within one-handed thumb reach on every model, achieved partly via edge-gesture equivalents rather than literal bottom placement; viewfinder swipes reserved for parameter dials, mode switch on edge-swipe/top-corner tap.

## 7. Platform constraint — corrected timeline, hard requirement

(Earlier framing conflated releases: **API 36 = Android 16, API 37 = Android 17**.)

- **API 36 (Android 16):** orientation/resizability/aspect-ratio restrictions **ignored on sw600dp+ displays** — app fills the window, no pillarboxing. Ignored APIs include `screenOrientation`, `setRequestedOrientation()`, `resizableActivity`, min/maxAspectRatio, incl. `portrait`/`sensorPortrait`/`sensorLandscape` (`nosensor`/`locked` not on the published list — do not rely on that loophole). Temporary opt-out exists (`PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY`).
- **API 37 (Android 17): opt-out REMOVED** (page verified 2026-06-02). **Rova targets SDK 37** (`app/build.gradle.kts`) → no opt-out available.
- **No camera-app exemption.** Exceptions are exactly: games (`appCategory`), explicit per-app user opt-in, screens < sw600dp. Google's docs name camera apps as the canonical *affected* category and prescribe fixing previews for free-running orientation, not exemption.
- **Consequence (hard spec requirement):** fixed-physical chrome is safe on phones (incl. the owner's Samsung, sub-sw600dp); on sw600dp+ the window *will* rotate regardless → the **WindowSizeClass-driven fallback to the β adaptive chrome is mandatory, not optional**. Play targeting deadlines: Aug 2026 (API 36) / Aug 2027 (API 37).

## 8. Open questions → on-device observation protocol (owner's Samsung, One UI)

None of the polish-level animation/sheet facts survived web verification (no design-teardown or APK-teardown source held up). The remaining values come from observing One UI Camera directly:

| # | Observe | Drives |
|---|---|---|
| O1 | Which elements counter-rotate vs stay fixed (icons, timer, zoom chip, mode labels, thumbnail) | Spin-class table |
| O2 | Rotation animation: duration, easing, simultaneous vs staggered (frame-count from `adb shell screenrecord` while rotating) | Motion tokens |
| O3 | Mid-video-recording rotation behavior (does the timer spin? do controls spin while REC?) | Active-state spin policy |
| O4 | Flat / face-up behavior (hold vs reset) | Confirms hold-last policy |
| O5 | Both landscape senses (90 vs 270) — mirror correctness | Sense→angle sign mapping |
| O6 | Quick-settings surface anatomy: strip vs sheet, states, scrim/blur, rotation handling while open | Sheet spec + M3 divergence notes |

Fallback if observation is skipped: Material short-duration motion tokens (~200–300 ms, standard easing), tuned on device.

## 9. Recommended values for the spec (as verified or derived)

| Item | Value | Basis |
|---|---|---|
| Touch container | 48×48 dp square, ≥8 dp gaps | Verified Google guidance §4 |
| Spinning visual budget | ≤ 33.9 dp inside a 48 dp slot (slot/√2) | Derived §3 |
| Snap boundaries | Canonical 45° half-open bands + Rova hysteresis band (device-tuned, no official value exists) | Verified §2 |
| Flat policy | Hold last known; `ORIENTATION_UNKNOWN` early-return | Verified §2 |
| Angle interpolation | Unwrapped accumulator + `((Δ+540) mod 360) − 180` | Derived §3 |
| Counter-rotation vs Surface rotation | Two named mappings, never one number | Verified §2 trap |
| Click target placement | Stable outer square container (outside the layer) | Verified §3 + design choice |
| Duration/easing | Pending O2; fallback ~200–300 ms standard easing | Research gap §8 |
| Large-screen fallback | β adaptive chrome at sw600dp+ (mandatory) | Verified §7 |

## Sources (primary unless noted)

developer.android.com: OrientationEventListener reference · CameraX orientation-rotation guide · Compose graphics modifiers · Android 16 behavior-changes · Android 17 ff-restrictions-ignored · adaptive orientation guide — android-developers.googleblog.com (2025-01, 2026-02) — m3.material.io bottom-sheets · designing/structure — support.google.com Android accessibility 7101858 — react-native-vision-camera.com orientation guide — JetBrains/compose-multiplatform#3230 — USPTO US9716825B1 — lux.camera Halide Mark II announcement (first-party launch copy) + Halide support docs — material-components-android BottomSheet docs — secondary: AndroidPolice Pixel 8 camera, AndroidAuthority One UI 7 camera, OSXDaily, AirServer.
