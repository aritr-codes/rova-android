# Icon 5b-4 — Onboarding + Recovered/Interrupted status glyphs (close ADR-0031)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) tracking.

**Goal:** The LAST icon-system slice (ADR-0031). Consume the already-authored glyphs that nothing renders yet: migrate Onboarding permission-slide icons to `RovaGlyph`s, and wire the unused `RovaIcons.Recovered` (green) / `RovaIcons.Interrupted` (orange) **status** glyphs into the RecoveryCard and Library status badges. After this, every authored glyph has a consumer and ADR-0031 is closed.

**Architecture:** The icon seam is LIVE — `ui/components/SemanticIcon.kt` (tint seam), `ui/theme/RovaIcons.kt` (concept→glyph bridge; `Recovered`/`Interrupted` carry a LOCKED `IconStatus` so the status color travels with the concept), `ui/theme/RovaGlyphs.kt` (glyphs). Prior slices used pure `*IconSpec` helpers (`WarningIconSpec`, `LibraryIconSpec`) for any state→glyph/status decision, JVM-tested. This slice mirrors that pattern.

**Tech stack:** Kotlin, Jetpack Compose, JVM unit tests (`isReturnDefaultValues=true`). Status glyphs already exist — this is adoption, not authoring.

## Global Constraints

- Branch `feat/icon-5b4-onboarding-status` off master `aae66e7` (already created, in the MAIN repo — warm builds).
- **46 static gates + full `:app:testDebugUnitTest` green at EVERY commit.** Never edit a `check*` to pass — fix the source. Relevant gates: `checkSemanticIconNoRawAlpha` (no raw alpha tints — route ALL tint through `SemanticIcon`/`IconRole`/`IconStatus`), `checkStatusColorLocked` (status colors locked), `checkRovaGlyphHome` (glyphs authored only in `RovaGlyphs.kt`), `checkNoHardcodedUiStrings` (no hardcoded UI text).
- **All 5b-4 icons are DECORATIVE** (`contentDescription = null`) — the adjacent card title/body/badge-label already carries the meaning, and a decorative icon avoids double-announce (a11y-correct, WCAG). **Zero new strings** (matches 5b-2/5b-3). Do NOT add `strings.xml` entries.
- **No status-color authoring at call-sites.** The status glyphs already lock their color via `RovaIcons.Recovered`/`.Interrupted`. A call-site passes the `RovaIcon` (glyph + locked status) to `SemanticIcon`; it must NOT pass a raw tint (`checkSemanticIconNoRawAlpha`).
- **EDIT-only subagents**; the controller runs ALL gradle/tests/commits/smoke. Build WARM (no cache wipe). `local.properties` already present (main repo).
- **codex** peer review for any non-trivial logic in the pure `*IconSpec` helpers (the state→status mapping).
- Device smoke on **RZCYA1VBQ2H** (Android 14) — onboarding slides render the new glyphs; a recovered/interrupted session shows the status glyph in RecoveryCard + Library card. Capture path not touched, but the surfaces are visual → owner eyeball. **Push/PR/merge only on explicit owner GO.**
- Verified facts (do not re-derive — from the 5b-4 surface audit):
  - Onboarding: `OnboardingSlide.kt` — `PermissionIconTile` (~312–329) renders `Icon(imageVector, tint = accent.copy(alpha=0.90f))`. Three slides: PERM_CAMERA `Icons.Filled.Videocam` (~211), PERM_MIC `Icons.Filled.Mic` (~222), PERM_NOTIFS `Icons.Filled.Notifications` (~233). Target concepts: `RovaIcons.CameraAccess`, `RovaIcons.MicAccess`, `RovaIcons.NotificationsSetting` (all exist).
  - `RovaIcons.Recovered = RovaIcon(RovaGlyphs.RecClipCheck.outline, status = IconStatus.Recovered)`; `RovaIcons.Interrupted = RovaIcon(RovaGlyphs.Interrupted.outline, status = IconStatus.Interrupted)`. `IconStatus` = {Recovered, Interrupted, Processing, Success, Warning, Rec, Danger}. `IconRole` = {Default, Secondary, Disabled, Accent, OnAccent}.
  - RecoveryCard: `ui/recovery/RecoveryCard.kt` (~139–153) leads the header with `SemanticIcon(glyph = RovaIcons.Recovery, contentDescription = null, role = IconRole.Secondary)`. The card's interruption nature is carried by a `kind` enum (values incl. `KILLED_BY_SYSTEM`, `KILLED_FORCE_STOP`, `USER_STOPPED`) → severity color. Read the actual enum from source for exact case names.
  - Library badges: `ui/library/LibraryBadge.kt` `enum class LibraryBadge { RECOVERED, INTERRUPTED }`; `StatusBadgePolicy.kt` derives it; `LibraryBadges.kt` (~84–88) `statusBadgeLabel(...)` returns TEXT only, rendered by `CaptionBar`/`OverlayPill` into `LibraryGridCard.kt`/`LibraryListRow.kt`/`LibraryHeroCard.kt`.
  - Pattern to mirror: `ui/warnings/WarningIconSpec.kt` (pure `object` + `glyphFor(WarningId): RovaGlyph`, no Compose) + test `src/test/.../ui/warnings/WarningIconSpecTest.kt`; `ui/library/components/LibraryIconSpec.kt` + its test.

---

### Task 1: Onboarding permission-slide glyphs

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/screens/onboarding/OnboardingSlide.kt`

**Interfaces:**
- Consumes: `RovaIcons.CameraAccess`, `RovaIcons.MicAccess`, `RovaIcons.NotificationsSetting`, `SemanticIcon`, `IconRole`.

- [ ] **Step 1: Route the three permission-slide icons through `SemanticIcon`.** Replace the per-slide `Icons.Filled.Videocam`/`Mic`/`Notifications` with the corresponding `RovaIcons.CameraAccess`/`MicAccess`/`NotificationsSetting`. Adapt `PermissionIconTile` so it renders a `RovaGlyph`/`RovaIcon` via `SemanticIcon` instead of `Icon(imageVector, tint=accent.copy(alpha=…))`. **Remove the raw `accent.copy(alpha=0.90f)` tint** — tint must flow through `SemanticIcon`'s `IconRole` (use `IconRole.Accent` to keep the accent look, which `checkSemanticIconNoRawAlpha` permits; the raw `.copy(alpha)` would fail the gate). Keep the tile's size/container/layout identical. `contentDescription = null` (decorative — the slide title + body carry the meaning).
- [ ] **Step 2: Build + gates + JVM (controller).** `gradlew.bat :app:assembleDebug :app:testDebugUnitTest` → BUILD SUCCESSFUL, 46 gates pass (esp. `checkSemanticIconNoRawAlpha`, `checkRovaGlyphHome`), JVM green.
- [ ] **Step 3: Commit** — `feat(icon): 5b-4 onboarding permission slides → SemanticIcon glyphs`.

---

### Task 2: `RecoveryIconSpec` + RecoveryCard status glyph

**Files:**
- Create: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryIconSpec.kt`
- Create: `app/src/test/java/com/aritr/rova/ui/recovery/RecoveryIconSpecTest.kt`
- Modify: `app/src/main/java/com/aritr/rova/ui/recovery/RecoveryCard.kt`

**Interfaces:**
- Produces: `RecoveryIconSpec.statusGlyphFor(kind): RovaIcon?` (pure, no Compose).
- Consumes: `RovaIcons.Recovered`, `RovaIcons.Interrupted`, `SemanticIcon`.

- [ ] **Step 1: Pure helper (mirror `WarningIconSpec`).** Read the actual RecoveryCard `kind` enum from source. Create `internal object RecoveryIconSpec` with `fun statusGlyphFor(kind: <KindEnum>): RovaIcon?` mapping: interruption-class kinds (`KILLED_BY_SYSTEM`, `KILLED_FORCE_STOP`) → `RovaIcons.Interrupted`; a recovered/kept-class kind (if present, e.g. user-stopped-but-recoverable / merged-kept) → `RovaIcons.Recovered`; anything with no distinct status → `null` (card keeps the generic `RovaIcons.Recovery` emblem). The exact case→status mapping is a judgment call — base it on each kind's semantics (was the session interrupted/killed vs cleanly recovered). NO Compose imports; pure so it's JVM-testable.
- [ ] **Step 2: JVM test (mirror `WarningIconSpecTest`).** Assert every enum case maps to the intended `RovaIcon?` (incl. the `null` cases), and that the returned icons carry the LOCKED status (`Recovered.status == IconStatus.Recovered`, `Interrupted.status == IconStatus.Interrupted`). Exhaustive `when`-coverage so a new kind forces a test update.
- [ ] **Step 3: Wire into `RecoveryCard.kt`.** Where the header emblem renders (~139–153): if `RecoveryIconSpec.statusGlyphFor(kind)` is non-null, render THAT `RovaIcon` via `SemanticIcon` (the locked status drives the color — green Recovered / orange Interrupted) with `contentDescription = null` (decorative; the card title/severity text carries meaning); else keep the existing `RovaIcons.Recovery` + `IconRole.Secondary`. Do not pass a raw tint.
- [ ] **Step 4: Build + gates + JVM (controller).** Full `:app:assembleDebug :app:testDebugUnitTest` green; `checkSemanticIconNoRawAlpha`/`checkStatusColorLocked` pass.
- [ ] **Step 5: Commit** — `feat(icon): 5b-4 RecoveryIconSpec — wire Recovered/Interrupted status glyphs into RecoveryCard`.

---

### Task 3: Library status-badge glyph (icon + text)

**Files:**
- Modify: `app/src/main/java/com/aritr/rova/ui/library/components/LibraryBadges.kt` (or the badge home — read source)
- Create: `app/src/test/java/com/aritr/rova/ui/library/.../LibraryBadgeGlyphTest.kt` (co-locate with where the helper lands)
- Modify: the badge render path so the glyph appears alongside the existing label (`CaptionBar`/`OverlayPill`, consumed by `LibraryGridCard.kt`/`LibraryListRow.kt`/`LibraryHeroCard.kt` — change the shared badge composable, not each card, if they funnel through one place)

**Interfaces:**
- Produces: a pure `fun badgeGlyph(badge: LibraryBadge): RovaIcon` (RECOVERED → `RovaIcons.Recovered`, INTERRUPTED → `RovaIcons.Interrupted`).
- Consumes: `SemanticIcon`, `RovaIcons.Recovered`/`.Interrupted`.

- [ ] **Step 1: Pure mapper + test.** Add `badgeGlyph(badge: LibraryBadge): RovaIcon` (exhaustive `when`) next to the badge code; JVM-test both cases map to the right locked-status icon.
- [ ] **Step 2: Render the glyph alongside the label (icon + text).** In the shared badge composable that draws the label text, prepend the `SemanticIcon(badgeGlyph(badge), contentDescription = null, …)` — decorative (the text label is adjacent and announced). Size it to the badge text rhythm; keep the existing pill/caption background + the text. Status color comes from the locked status (green/orange) via `SemanticIcon` — no raw tint. Verify it renders in all three card variants (grid/list/hero) if they share the composable; if a variant renders its own badge, update each.
- [ ] **Step 3: Build + gates + JVM (controller).** Full build + 46 gates + JVM green.
- [ ] **Step 4: Commit** — `feat(icon): 5b-4 Library status badges — glyph + label (Recovered/Interrupted)`.

---

### Task 4: Device smoke (controller + owner)

**No code.** Build the debug APK, confirm `:app:packageDebug` EXECUTED + fresh APK mtime, `adb install -r` (PowerShell), verify on RZCYA1VBQ2H:

- [ ] Onboarding: the 3 permission slides render the new accent glyphs (camera / mic / notifications) at the right size/tint; no regression to the tile layout.
- [ ] Recovery: a session that was killed/interrupted shows the **orange Interrupted** glyph in the RecoveryCard header; a recovered/kept one shows the **green Recovered** glyph (or the generic emblem where there's no distinct status).
- [ ] Library: recovered/interrupted clips show the status glyph **next to** their existing RECOVERED/INTERRUPTED label on the card (grid + list + hero).
- [ ] Owner confirms the glyphs read clearly and the status colors are correct (green vs orange).

---

## Out of scope / future

- **Notification status glyph** — the FGS/notification surface needs a drawable resource, not an `ImageVector` (`RovaGlyph`); documented exclusion (same reason `BackgroundRecord` glyph was deferred). Not in 5b-4.
- **Bottom-nav glass** — already shipped in 5b-5 (Library/Settings nav glass-circles via `NavItem`, FlipCam/FlashBolt). The audit's "nav glass beyond NavItem" has no real remaining gap; owner confirmed DROP.

## Execution notes

- Tasks are disjoint files (Onboarding / Recovery / Library) → sequence them; no shared-file conflict. All decorative → no `strings.xml` contention.
- This closes ADR-0031 (every authored glyph now has a consumer). Update `memory/project_icon_glyph_system.md` + the ADR status note after device-GO.
