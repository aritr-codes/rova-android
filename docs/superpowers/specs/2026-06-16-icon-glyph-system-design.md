# Icon &amp; Glyph System — design spec

Status: **Approved (design phase)** — 2026-06-16 (owner GO; visual exploration complete)
Scope: in-app icon/glyph system only. Launcher/mipmap brand mark is **out of scope** (rebranded in M5; ADR-0028 §9 keeps it a separate static asset). No implementation in this phase.
Companion ADR: **ADR-0031**. Refines ADR-0028 §9 ("Icons — separate later PR"). Visual exploration: `.superpowers/brainstorm/…/content/board.html` (v1), `board-v2.html` (language fork), `board-3-semantic.html` (semantic design).

---

## 1 · Goal

Define one coherent, theme-engine-ready in-app icon language **before** the Liquid Glass theme engine is built, so the engine inherits a stable icon color seam instead of forcing icon decisions later. Deliver: a locked visual philosophy, a glyph language, consistency rules, a canonical concept→glyph mapping, and a migration roadmap — without touching Kotlin.

---

## 2 · Research summary (Phase 1)

Competitive principles extracted (not copied) from Apple Photos/Camera/Files/visionOS, Google Photos/Files, Pixel Camera, Arc, Linear, Notion Calendar, Craft, and M3-Expressive Android. Sources: Material Symbols guide, Apple HIG/SF Symbols, WCAG 2.2, Android CDD §3.8.6.

1. **Outlined-default, filled-active** is near-universal for nav (Apple Photos/Files, Google Photos/Files, M3). Linear/Notion/Craft are the exception — constant shape, state via color+background, fill reserved for *status*.
2. **State = multi-signal, never color-alone** (fill swap + tint + container). Satisfies WCAG 1.4.1.
3. **Color = semantics, not decoration** (Linear status, Apple/Google file-type color, Notion calendar dots).
4. **Over-media contrast is bought at the substrate** (Apple/Pixel Camera scrims, visionOS glass), not by thickening stroke.
5. **Bespoke only for brand-unique or icon-poor domains** (camera apps ~50% custom; everything else ~90% system but internally uniform — one stroke vocabulary).
6. Material Symbols supplies this for near-free: **FILL 0→1 (animatable), WEIGHT 100–700, GRADE, opsz 20/24/40/48.** WCAG: meaningful glyphs/state indicators ≥3:1 non-text contrast (SC 1.4.11); ≥24dp touch (SC 2.5.8).

**Principles adopted for a glassmorphic video app:** budget contrast at the substrate (glass chip) for over-media glyphs; lean slightly filled/heavier over media; drive every glyph color from one theme token; state = multi-signal; one uniform stroke vocabulary + a small color-semantic set; optical sizing; verify glyph+backing over worst-case bright/dark frames.

---

## 3 · Current-state audit (Phase 0)

Measured 2026-06-16 across `app/src/main/java/com/aritr/rova/`.

- **Two disjoint icon systems.** Record chrome = **8 bespoke `ImageVector`s** in `ui/screens/RecordChromeIcons.kt` (flashBolt 13×15 fill · flipCamera 16×14 stroke 1.4 · cameraFront/cameraRear 24×24 stroke 1.8 · library/settings 24×24 stroke 1.6 · chevronUp stroke 2.2 · fabPlay fill), neutral-authored + tinted at call-site. Everything else = `androidx.compose.material.icons` (`material-icons-extended`, effectively **pinned**).
- **63 distinct Material glyphs** across 30 files; **Filled dominates**, Outlined sparse (5: ErrorOutline, SearchOff, Splitscreen, StarBorder, VideoLibrary), one AutoMirrored (ViewList). `Icons.Default` (~Filled) sprawls Settings/Preview.
- **10 semantic conflicts** flagged: Star vs StarBorder (favorite); 4 glyphs for error/warning/alert; Delete vs DeleteSweep; Search vs SearchOff; Mic/Videocam/Notifications each split enabled/disabled across screens; two checkmark families (Check / CheckCircle / Checklist); GridView/ViewList both filled.
- **Pinned set has gaps** (CropPortrait/Landscape absent) — already forced a custom-drawn `OrientationFramePill`.
- **7 Canvas-drawn glyphs/illustrations** (CameraGrid, FocusFrame, 3 onboarding illustrations, CountdownRing, RecordingDotPulse) — a separate concern, mostly out of scope here.
- **Sizing is fragmented** — ~17 distinct dp values across `RecordChromeTokens` (navIconGlyphSize 20 / camControlSize 30 / fabSize 56 / stopSquareSize 18), `RovaTokens` (primaryActionSize 64 / stopActionSize 72 / minHitTarget 48), `LibraryDimens` (actionIcon 20 / navIcon 22), `RovaWarningsV3` (sheet 56 / banner 36 / overflow 28), plus inline `Modifier.size(14/18/20/22/30/…)` and `FilterChipDefaults.IconSize`. `statusDotSize`/`camControlSize`/`camControlGap` are **defined twice** (RecordChromeTokens + RovaTokens).
- **Tint is raw at the call-site** — 13 distinct patterns; **20 sites use ad-hoc `Color.White.copy(alpha = …)`** (0.14–0.9) bypassing any seam (RecordChrome ×7, PlayerScreen ×8, WarningSheetV3 ×3, SettingsScreen ×2, + LibraryRow/Onboarding/Vault/Warnings).

### The seam to mirror (already in the codebase)
- `GlassSurface.kt` / `GlassResolver.kt` — `GlassRole` (RecordChrome / BottomSheet / Dialog / Default) → `GlassMaterial`; identity colors only.
- `RovaSemantics` (in `RovaPalette.kt`) — **locked** success/warning/error/escalating/rec, identical across all 12 palettes.
- `LibraryColors` / `LibraryColorSpec` — the **identity-vs-locked split**: `cardEdge/heroEdge/chipEdge` retint per theme; `overlayScrim/overlayText/selectionRing` are locked for contrast over media.

The new icon color contract **mirrors this split exactly**.

---

## 4 · Problems found (summary)

1. Two parallel icon systems with no shared metrics → custom and stock glyphs don't read as one family.
2. Inconsistent fill discipline (Filled-everywhere) → no active-vs-inactive signal, weak hierarchy.
3. 10 semantic conflicts → the same concept shows two glyphs; some concepts (Record, Library) collide across roles.
4. No icon-size registry; duplicated tokens.
5. No semantic tint seam → 20 raw-alpha sites the theme engine cannot drive.
6. Generic Material glyphs misrepresent Rova-unique concepts (DualShot, Vault, Recovery, DualSight) or don't exist (Background recording, Loop/Interval, Segment count, Merging).

---

## 5 · Decision — visual philosophy (System D, locked)

Evaluated four languages (A Material-outlined · B Rounded-premium · C Hybrid · **D Glass-native hybrid**). **D is chosen** (owner GO 2026-06-16).

**System D:**
- **Outlined-default, soft monoline.** 24-grid, **1.9px** stroke, round caps + joins, soft corner radii.
- **Duotone accent layer.** A light outline + a single **accent detail channel** (the `.ac2` parts: play wedge, record core, keyhole, slider knobs, share nodes, the check). Meaning must survive without it — **mono-safe**.
- **Filled-active.** Selected/active or a primary action = filled glyph + accent + a **Liquid-Glass chip** container (FILL 0→1, animatable). Inactive = outlined `onSurfaceVariant`.
- **Theme-retintable accent channel.** The accent layer is the channel the theme engine retints per palette.
- **Locked semantic status colors.** recovered/interrupted/processing/success/warning/rec bound to `RovaSemantics`, never per-call alpha, never retinted.
- **Over-media contrast at the substrate.** Record/over-media glyphs sit on a glass chip; contrast (SC 1.4.11 ≥3:1) is met by the chip, not by thickening the stroke.
- **Bespoke only for brand-unique / icon-poor concepts**, authored to the same grid + stroke so custom + stock read as one family.

---

## 6 · Glyph taxonomy

Four classes, each with a fixed treatment:

| Class | Default | Active/primary | Color | Examples |
|---|---|---|---|---|
| **Navigation** | outlined | filled + glass-chip container | identity (accent on active, `onSurfaceVariant` inactive) | Library, Record(nav), Vault, Search, Settings |
| **Action** | outlined (transport filled by convention) | filled accent on primary/selected | identity; danger=locked error | Sort, View, Favorite, Share, Delete, Select, Play, Pause, Volume, Edit, Flip |
| **Brand/product** | outlined + duotone accent | n/a (identity glyphs) | identity accent layer | DualShot, DualSight, Vault, Recovery, Background-record, Merge |
| **Status** | filled/semantic shape | n/a | **locked** semantic | Recovered, Interrupted, Processing, Success, Warning, Recording |

---

## 7 · Part A — resolved conflicts

| # | Concept | Was | Decision | Why |
|---|---|---|---|---|
| 1 | **Settings** | sliders | **Gear** (soft 8-spoke) | sliders read as filters/equalizer; grid collides with View |
| 2 | **Sort** | lines + arrow | **State-free decreasing bars** | the arrow implied a fixed direction; direction lives in the sort sheet |
| 3 | **Warning vs Notifications** | collision | **Warning = triangle+! (locked amber status)**; **Notifications = bell (setting)** + bell-slash off | a status is never a setting; distinct shapes, distinct roles |
| 4 | **Processing** | spinner only | **Animated spinner arc** + **static three-dots** fallback (reduce-motion); segmented-ring reserve | needs animated + static + fallback per ADR-0020 |
| 5 | **Recovered** | circular arrow | **Clip + check** (green) | circular arrow read as "refresh/undo"; clip+check is domain-specific |

---

## 8 · Part B — brand glyphs (chosen + rationale)

Each explored A–D (see `board-3-semantic.html`); the pick:

- **DualShot** → **P+L twin frames sharing one accent capture core** ("one capture, two framings" — *not* two cameras; that's DualSight). Rejected: dual-lenses (implies 2 sensors), split-diagonal (too abstract), mirror (close 2nd).
- **Privacy Vault** → **locked collection of recordings** (stacked frames + accent lock). Rejected: padlock/shield (generic security, not media), single clip+lock (singular), safe-door (off-domain "bank").
- **Record** → lifecycle set: **solid disc** (FAB action) · **ring+core** (nav resting) · **rounded-square** (recording/stop). Capture-target rejected (busy at 20dp).
- **Library** → **stacked frames** (collection). Rejected: frame+play (collides with Play action), grid (collides with View), film-strip (noisy). Timeline+nodes kept as a strong alternative.
- **Recovery (flow)** → **rejoined frame on an accent seam** (reconstruction). Kept distinct from the **Recovered status** badge (clip+check, Part A-5).

---

## 9 · Part C — new product concepts (first-class)

Camera Flip (rotation arcs around a lens core) · Merge/Stitch (streams → one node) · Loop/Interval (repeat arrows + accent core) · Segment Count (segmented bar, first accented) · Waiting (hourglass) · Merging-state (merge + live accent spinner) · Background Recording (record dot in a backgrounded app card) · **DualSight** (frame + accent PiP inset + 2nd lens — concurrent front+back, **visually distinct** from DualShot).

---

## 10 · Consistency rules

1. Outlined is the default; **filled = selected/active or a primary action** only.
2. **One concept → one glyph, everywhere** (kills the 10 conflicts; un-collides Record/Library/Play).
3. The accent (`.ac2`) layer is emphasis + brand; **meaning must survive without it** (mono-safe).
4. Status color communicates state and is **always paired with shape**, never color-alone (WCAG 1.4.1).
5. Over media, glyphs sit on a glass chip — contrast bought at the substrate.

### Naming
- Bespoke vectors live in **one `RovaGlyphs` object** (`RovaGlyphs.DualShot`, `RovaGlyphs.VaultCollection`, …). `RecordChromeIcons` folds into it.
- Stock glyphs are referenced through a **`RovaIcons` alias map** (`RovaIcons.Settings = Icons.…`) so call-sites are swap-safe and a single edit re-points a concept.

### Sizing scale (one registry)
`18` inline/badge · `20` nav glyph · `24` default action · `30` camera control · `56` FAB. Folds the `RecordChromeTokens` / `LibraryDimens` / duplicated-token overloads into one source.

### Active / inactive
inactive = outlined, `onSurfaceVariant`; active = filled glyph + accent + Liquid-Glass chip (FILL 0→1 animatable).

### Accessibility
- Meaningful glyphs ≥3:1 non-text contrast (SC 1.4.11), incl. over worst-case frames via the chip.
- ≥24dp touch (existing `checkA11yTargetSizeToken`).
- Animated states gated by reduce-motion (existing `checkA11yAnimationGated`) with static fallbacks.
- Every glyph carries a `contentDescription` (or `null` + label pairing if decorative); new strings go to `values/` + `values-es/` (ADR-0022).

### Theme-engine compatibility
The **`SemanticIcon` tint seam** is the single entry-point the theme engine drives: the **accent channel retints** per palette; **status colors are locked**. This mirrors `LibraryColors`/`RovaSemantics` identity-vs-locked exactly. Building icons first means the engine inherits a stable seam.

---

## 11 · Migration strategy

| Tier | Work | When |
|---|---|---|
| **P0** (before theme engine) | Resolve chrome-baked collisions (Settings→gear · Sort→state-free · Library→stack · Warning/Notifications split · Record action/nav/stop). Introduce the **`SemanticIcon` tint seam** replacing the 20 raw-alpha sites. **Lock status colors** to `RovaSemantics`. | next |
| **P1** (with theme engine) | Author bespoke brand glyphs (DualShot, Vault, Recovery, DualSight, Background-record, Merge) into `RovaGlyphs`. Wire the duotone accent channel + glass-chip active container to the palette. | with engine |
| **P2** (future) | Animated states (processing/merging) + reduce-motion fallbacks. Secondary concepts (Loop/Interval, Segment count, Waiting). Onboarding illustration refresh. | later |

**Why P0 is load-bearing:** the theme engine drives icon color through the seam. If the seam and the concept→glyph collisions aren't fixed first, the engine bakes in raw alphas and ambiguous glyphs that are expensive to unwind.

### New invariants → gates (follow invariant → `check*` → preBuild)
- `checkSemanticIconNoRawAlpha` (P0): no `Color.White.copy(alpha=…)` / hardcoded tint on `Icon(...)` outside the `SemanticIcon` seam.
- `checkStatusColorLocked` (P0): status glyphs pull from `RovaSemantics`, never a per-call color.
- `checkRovaGlyphHome` (P1): bespoke `ImageVector`s declared only in `RovaGlyphs` (subsumes the `RecordChromeIcons.kt` allowance in `checkRecordSurfaceNoBlur`).
Exact gate shapes are finalized in the implementation plan, not here.

---

## 12 · Risk assessment

| Risk | Severity | Mitigation |
|---|---|---|
| **High blast radius** (30 files, 63 glyphs, 20 tint sites) | High | P0/P1/P2 tiers; `RovaIcons` alias map makes most call-site swaps one-line; per-concept, not big-bang. |
| **Bespoke glyphs drift from stock** (don't read as one family) | Med | Locked grid/stroke; `checkRovaGlyphHome`; review bespoke vs stock side-by-side at 20dp. |
| **Over-media contrast regressions** | Med | Glass-chip substrate is mandatory for over-media glyphs; verify glyph+chip over worst-case bright/dark frames (reuse `ContrastMath`). |
| **Theme engine started before P0** | High | This spec + ADR-0031 sequence P0 *ahead of* the engine; owner-sequenced. |
| **Pinned material-icons gaps** force more bespoke than planned | Low | Audit already enumerates gaps; bespoke home + budget anticipated. |
| **Animated-state perf / motion a11y** | Low | reduce-motion gate already exists; static fallbacks specified. |
| **String/locale churn** for new labels | Low | ADR-0022 gate; en+es in the same change. |

---

## 13 · Out of scope (this program)
Launcher/mipmap brand icon · the Liquid Glass theme engine itself · onboarding Canvas illustrations (P2 refresh only) · the owner-deferred PORTRAIT square-into-9:16 DualShot capture stretch · DualSight *implementation* (glyph designed; feature remains hardware-blocked per ADR-0029 / PR #115).

---

## 14 · Success criteria
A complete semantic icon system (philosophy + glyph language + rules), product-specific glyph definitions, and a P0/P1/P2 migration roadmap — with zero implementation. Next: `writing-plans` produces the P0 implementation plan after spec sign-off.
