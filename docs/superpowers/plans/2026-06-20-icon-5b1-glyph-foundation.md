# Icon 5b-1 — Glyph Foundation + Board SSOT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.
> First sub-PR of the icon-system-completion track (spec: `docs/superpowers/specs/2026-06-20-icon-system-completion-design.md`). Surfaces 5b-2…5b-5 follow in their own plans.

**Goal:** Author the ~25 new System-D glyphs (+ re-author `FlashBolt`/`FlipCam`), round-trip every new glyph into the design board, extend `RovaIcons`, and land the two exact small fixes (`Play` board d-string; `Interrupted → escalating`) — with JVM coverage. No surface call-site churn in this sub-PR.

**Architecture:** Bespoke glyphs are `ImageVector`s authored ONLY in `ui/theme/RovaGlyphs.kt` (gate `checkRovaGlyphHome`) via the in-file helpers; concepts are exposed through the `RovaIcons` map and rendered by the `SemanticIcon` seam. The board HTML (`G={}` + E1 table) is the single source of truth and is updated in lock-step.

**Tech Stack:** Kotlin, Jetpack Compose `ImageVector`, JUnit (JVM), the System-D authoring helpers in `RovaGlyphs.kt`.

## Global Constraints

- **Bespoke glyphs live ONLY in `RovaGlyphs.kt`** (`checkRovaGlyphHome`). Use the helpers `glyph`/`strokePath`/`fillPath`/`svgStroke`/`svgFill`/`circle`/`roundRect`/`seg`.
- **System-D geometry:** 24×24 grid, 1.9px stroke, round caps+joins, soft radii; duotone `.ac2` accent layer only where it adds meaning; **mono-safe** (meaning survives with the accent removed).
- **Board is SSOT:** every new/changed glyph is mirrored into `.superpowers/brainstorm/1234-1781611237/content/board-3-semantic.html` (`G={}` dictionary entry + E1 `MAP` row). The board is gitignored; keep `G={}`/`MAP` parseable.
- **Status colours are locked** (`checkStatusColorLocked`); the seam tints (`checkSemanticIconNoRawAlpha`). Don't introduce raw alpha on a SemanticIcon.
- **All 46 gates + JVM green at every commit.** Never edit a `check*` to pass.
- **No new user-facing strings in this sub-PR** (call-site migrations carry their own content-descriptions in 5b-2…5b-5).
- **Subagents edit-only; controller runs gradle/commits.** Build WARM.
- Locked decisions: `Interrupted → RovaSemantics.escalating`; `Play` → board `M8 6.3 17.4 12 8 17.7z`; Camera-flip → board `flip_cam`.

---

### Task 1: Two exact fixes — `Play` d-string + `Interrupted` hue

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt` (the `Play` val, ~line 408)
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/SemanticIconSpec.kt` (status→colour map, ~line 65)
- Test: `app/src/test/java/com/aritr/rova/ui/theme/SemanticIconSpecTest.kt`

**Interfaces:**
- Produces: `RovaGlyphs.Play` drawn from the board `play` path; `IconStatus.Interrupted → RovaSemantics.escalating`.

- [ ] **Step 1: Write/extend the failing test**

```kotlin
@Test fun interrupted_isEscalatingOrange() {
    val palette = rovaPalettes.first()
    assertEquals(RovaSemantics.escalating, SemanticIconSpec.statusColor(IconStatus.Interrupted, palette))
    // Recovered stays green; Warning stays amber (guard against an accidental swap)
    assertEquals(RovaSemantics.success, SemanticIconSpec.statusColor(IconStatus.Recovered, palette))
    assertEquals(RovaSemantics.warning, SemanticIconSpec.statusColor(IconStatus.Warning, palette))
}
```
(Adapt `statusColor(...)` to the real accessor name in `SemanticIconSpec`.)

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.SemanticIconSpecTest"` → FAIL.

- [ ] **Step 3: Apply both fixes**

`RovaGlyphs.Play` — replace the triangle body with the board fill path:
```kotlin
val Play: ImageVector = glyph { svgFill("M8 6.3 17.4 12 8 17.7z") }
```
`SemanticIconSpec` status map — change the `Interrupted` arm:
```kotlin
IconStatus.Interrupted -> RovaSemantics.escalating
```

- [ ] **Step 4: Run to verify pass** — same command → PASS. Then `./gradlew :app:checkStatusColorLocked` → PASS.

- [ ] **Step 5: Mirror to the board**

In `board-3-semantic.html`: the `play` `G` entry already matches; the E1 `MAP` already says Interrupted = "escalating orange" (the code now agrees with the board — no board edit needed, just confirm).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt app/src/main/java/com/aritr/rova/ui/theme/SemanticIconSpec.kt app/src/test/java/com/aritr/rova/ui/theme/SemanticIconSpecTest.kt
git commit -m "fix(icon): Play board d-string + Interrupted -> escalating orange"
```

---

### Task 2: Re-author `FlashBolt` + author `FlipCam` (board-defined d-strings)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt`
- Modify (board): `.superpowers/brainstorm/1234-1781611237/content/board-3-semantic.html`

**Interfaces:**
- Produces: `RovaGlyphs.FlipCam` (board `flip_cam`); `RovaGlyphs.FlashBolt` re-authored to System-D.

- [ ] **Step 1: Author `FlipCam` from the board `flip_cam` d-string**

Extract the exact board path (do NOT eyeball) — from `G.flip_cam`:
```
<path d="M5.5 9.5A7 6 0 0 1 18 7.5"/><path class="ac2" d="M18.3 4.5v3.2h-3.2"/>
<path d="M18.5 14.5A7 6 0 0 1 6 16.5"/><path class="ac2" d="M5.7 19.5v-3.2h3.2"/>
<circle class="ac2 solid" cx="12" cy="12" r="1.7"/>
```
Author as a duotone glyph: outline arcs (`svgStroke`) + accent arrowheads/core (the `.ac2` layer → accent channel), e.g.
```kotlin
val FlipCam: RovaGlyph = RovaGlyph(
    outline = glyph { svgStroke("M5.5 9.5A7 6 0 0 1 18 7.5"); svgStroke("M18.5 14.5A7 6 0 0 1 6 16.5") },
    accent = glyph { svgStroke("M18.3 4.5v3.2h-3.2"); svgStroke("M5.7 19.5v-3.2h3.2"); circle(12f,12f,1.7f) },
)
```
(Match the existing `RovaGlyph(outline, accent)` two-layer shape used by other duotone glyphs.)

- [ ] **Step 2: Re-author `FlashBolt`** to clean System-D (24-grid, 1.9px, round joins). Bolt outline + optional accent core; mono-safe. Replace the legacy-folded body.

- [ ] **Step 3: Mirror to board** — `flip_cam` already exists in `G`; ADD a `flash:` entry to `G={}` and an E1 `MAP` row (`['Flash','flash','Torch toggle; bolt — hardware-state coloured when on']`). Keep parseable.

- [ ] **Step 4: Build** — `./gradlew :app:assembleDebug` → packageDebug EXECUTED; `./gradlew :app:checkRovaGlyphHome` → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt
git commit -m "feat(icon): FlipCam (board flip_cam) + System-D FlashBolt re-author"
```

---

### Task 3: Author the ~25 new System-D glyphs (net-new concepts)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt`
- Modify (board): `board-3-semantic.html` (`G={}` + E1 `MAP` rows for each)

> These concepts are NOT on the board — design them in the System-D family, then write them back so the board stays SSOT. This is a CRAFT task: the deliverable per glyph is a 24-grid `ImageVector` that (a) reads at 18dp and 24dp, (b) is mono-safe, (c) matches stroke weight/cap family, (d) is duotone only where it adds meaning. Verify each by rendering in the app + the board preview.

**Concept briefs** (group A — warnings/permissions; group B — settings; group C — onboarding affirmative):

| Concept | Glyph name | System-D metaphor |
|---|---|---|
| Thermal | `Thermal` | thermometer stem + bulb; accent rising level |
| Storage | `Storage` | drive/disk stack; accent fill bar |
| BatteryLow | `BatteryLow` | battery body + terminal; accent low-level + warn notch |
| BatterySaver | `BatterySaver` | battery + accent leaf/eco mark |
| PowerMode | `PowerMode` | power glyph (circle + break) |
| AlarmOff | `AlarmOff` | clock + bells + accent slash |
| CameraOff | `CameraOff` | video frame + lens + accent slash |
| CameraPermission | `CameraPermission` | video frame + lens (no slash; "needs access") |
| MicOff | `MicOff` | capsule + stem + accent slash |
| DarkMode | `DarkMode` | crescent (reuse `theme` half-disc family, but a moon) |
| Language | `Language` | globe + accent meridian / glyph "文A" abstraction |
| Quality | `Quality` | frame + "HD" abstraction / stacked bars |
| Timer | `Timer` | clock + accent hand sweep |
| Schedule | `Schedule` | calendar grid + accent day mark |
| Lock | `Lock` | shackle + body (reuse vault padlock geometry, simplified) |
| Vibration | `Vibration` | phone + accent motion waves |
| Device | `Device` | phone body + screen (reuse orientation phone family) |
| GridLayout | `GridLayout` | 2×2 (reuse `view`/`set_grid`) |
| Video | `Video` | film frame + play notch |
| Folder | `Folder` | folder + accent tab |
| Cleanup | `Cleanup` | broom/sweep + accent particles |
| DeleteAll | `DeleteAll` | trash + accent stack/sweep lines |
| Privacy | `Privacy` | shield + accent eye-off / tick |
| Info | `Info` | circle + accent "i" |
| CameraAccess | `CameraAccess` | affirmative camera (no slash) — onboarding |
| MicAccess | `MicAccess` | affirmative mic (no slash) — onboarding |

(Some reuse existing geometry families — `theme`/`view`/`vault_pad`/orientation-phone — keep them visually consistent, do not duplicate identical shapes; where a concept can reuse an existing board glyph, prefer the reuse and skip authoring.)

- [ ] **Step 1: Author the glyphs** in `RovaGlyphs.kt` using the helpers, one `val` per concept, grouped with a section comment. Duotone via `RovaGlyph(outline, accent)` where the brief calls for an accent layer; single-layer `glyph{}` otherwise.

- [ ] **Step 2: Mirror each to the board** — add a `G={}` entry (the same path data) + an E1 `MAP` row per concept. Confirm the board file still parses (`G={...}` object closes; `MAP=[...]` array closes).

- [ ] **Step 3: Build** — `./gradlew :app:assembleDebug` → packageDebug EXECUTED; `./gradlew :app:checkRovaGlyphHome` → PASS.

- [ ] **Step 4: Visual check** — render a temporary debug grid (or the existing glyph preview screen if present) at 18/24dp; owner eyeballs family consistency. Do not ship a debug screen.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaGlyphs.kt
git commit -m "feat(icon): author 25 System-D glyphs for warnings/settings/onboarding gaps"
```

---

### Task 4: Extend the `RovaIcons` concept→glyph map

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt`
- Test: `app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt` (create)

**Interfaces:**
- Produces: `RovaIcons.<Concept>` entries for every Task-2/Task-3 glyph + the status-bearing `RovaIcons.Recovered` (`rec_clipcheck` + `IconStatus.Recovered`) and `RovaIcons.Interrupted` (`interrupted` + `IconStatus.Interrupted`).

- [ ] **Step 1: Write the failing test** — assert every newly-mapped concept resolves to a non-null glyph and the two status entries carry the right `IconStatus`:

```kotlin
@Test fun newConcepts_areMapped() {
    listOf(RovaIcons.Thermal, RovaIcons.Storage, RovaIcons.Timer, RovaIcons.Folder,
           RovaIcons.Language, RovaIcons.FlipCam /* ...spot-check */)
        .forEach { /* RovaIcon/RovaGlyph */ requireNotNull(it) }
    assertEquals(IconStatus.Recovered, RovaIcons.Recovered.status)
    assertEquals(IconStatus.Interrupted, RovaIcons.Interrupted.status)
}
```

- [ ] **Step 2: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests "com.aritr.rova.ui.theme.RovaIconsTest"` → FAIL.

- [ ] **Step 3: Add the map entries** in `RovaIcons.kt` (one line per concept; status entries via the `RovaIcon(glyph, status)` form, mirroring the existing `WarningStatus` entry).

- [ ] **Step 4: Run to verify pass** — same command → PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/aritr/rova/ui/theme/RovaIcons.kt app/src/test/java/com/aritr/rova/ui/theme/RovaIconsTest.kt
git commit -m "feat(icon): map new concepts + Recovered/Interrupted status glyphs in RovaIcons"
```

---

### Task 5: Foundation verification

- [ ] **Step 1: Full JVM + build** — `./gradlew :app:testDebugUnitTest :app:assembleDebug` → all green; packageDebug EXECUTED.
- [ ] **Step 2: All 46 gates** (esp. `checkRovaGlyphHome`, `checkSemanticIconNoRawAlpha`, `checkStatusColorLocked`) → PASS. Fix source if any fail.
- [ ] **Step 3: codex review** the glyph set + the two seam fixes (`mcp__codex__codex`): board-fidelity, mono-safety, duotone correctness, the Interrupted-hue change.
- [ ] **Step 4: Do NOT push/PR** until owner GO. The foundation is consumed by 5b-2…5b-5.

---

## Self-Review

**Spec coverage:** Play d-string ✓ (T1) · Interrupted→escalating ✓ (T1) · FlipCam board `flip_cam` ✓ (T2) · FlashBolt re-author + board-add ✓ (T2) · 25 new glyphs + board round-trip ✓ (T3) · RovaIcons map incl. status glyphs ✓ (T4) · no surface churn (deferred to 5b-2…5b-5) ✓.
**Placeholders:** the 25-glyph task is a craft deliverable with per-glyph briefs + acceptance (mono-safe, 18/24dp, family-consistent) rather than pre-baked paths — the path IS the task output, board-verified by round-trip, not a "TODO". Deterministic fixes (Play, Interrupted, FlipCam from the board path) carry exact code.
**Type consistency:** `RovaGlyph(outline, accent)` two-layer + single-layer `glyph{}`, `RovaIcon(glyph, status)`, `IconStatus.Interrupted/Recovered`, `RovaSemantics.escalating` used consistently with the existing file shapes (confirm exact `RovaGlyph`/`RovaIcon` constructor names against the live files at T2/T4).
**Executor note:** confirm the real accessor name in `SemanticIconSpec` (the test uses `statusColor`) and the `RovaGlyph` two-layer constructor signature before T1/T2.
