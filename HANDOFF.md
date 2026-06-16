# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-16 — Enhanced Library & History redesign is COMPLETE: Slices 1–5 + polish P1–P8 + Theme-Foundation M1–M3 + M1/M2 UX refine + stabilization (sort-crash fix) ALL built, device/TalkBack GO, local-only. Library is reference-ready for the Liquid Glass theme engine. Stabilization closeout done (Issue A sort crash FIXED `e0f359a`; Issue B blue-dot identified = scrubber thumb, keep). NEXT SESSION = Icon & Glyph System Redesign (NOT the theme engine). Merge train: owner-gated, see below.**

---

## Where things stand

- **Active branch:** `feat/library-history-selection` — **local only, NOT pushed**, **~91 commits ahead** of `master`, tip = sort-crash fix `e0f359a`. Holds Slices 1+2+3 (+polish P1–P10) +4 (+4.1/4.2) +5 (a11y) + polish pass **P1–P8** + **Theme-Foundation M1–M3** + **M1/M2 UX refine** + **stabilization fix**. Working tree clean (only ephemeral `gradle_*.log` + an untracked polish plan doc).
- **master:** `dfd3bd9` (#116 DualSight doc-closeout). **Contains zero Library redesign code.**
- **Open PRs (pushed, awaiting the end-of-reskin merge train):**
  - **#117 — Slice 1 Foundation** (branch `feat/library-history-foundation`) · OPEN — covers Slice 1 only.
  - **#118 — Slice 2 Layout** (branch `feat/library-history-layout`, stacked on #117) · OPEN — covers Slice 2 only.
  - **#115 — DualSight investigation** (DRAFT, archival only — **never merge**, blocked on concurrent-camera hardware; see `memory/project_pr_delta_dualsight_probe.md`).
  - **NOTE:** #117/#118 cover only Slices 1–2 (~early commits). The other ~88 commits (Slices 3–5 + polish + theme + refine + stabilization) are on **no pushed branch** — the merge train must account for them (see "Merge train" below).
- **Owner directive (now satisfiable):** NO merge until the FULL reskin is done. **The full reskin — incl. polish, theme foundation, and stabilization — is now DONE.** The end-of-reskin merge train is unblocked pending owner confirmation of strategy + device smoke (see below).
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), GREEN on this branch. Real-device smoke is mandatory — emulators fail CameraX recording (device = RZCYA1VBQ2H, Android 14). **The sort-crash fix `e0f359a` is build+gate+JVM verified but NOT yet device-smoked.**
- **Static gates:** **42** custom `check*` tasks (the 42nd is `checkLibraryNoManifestWrite`, ADR-0030). All green on this branch.

## Library & History redesign — IN PROGRESS (this is the current work)

Liquid-Glass redesign of the recording-browse surfaces (ADR-0028 + new **ADR-0030**). Owner-approved via brainstorming → spec → plan. Full record: `memory/project_library_history_redesign.md`. Docs: spec `docs/superpowers/specs/2026-06-14-enhanced-library-history-design.md`, slice plans under `docs/superpowers/plans/`, `docs/adr/0030-*.md`.

**Slice status (stacked, 1 PR each at the end):**
1. **Foundation** ✅ built — sidecar `LibraryMetadataStore` (favorite/rename, NEVER `SessionManifest`), pure helpers, 42nd gate. **PR #117 OPEN.**
2. **Layout** ✅ built — hero+grid/list, glass cards, badges, day grouping, a11y. **PR #118 OPEN.**
3. **Selection + batch + route-wire** ✅ built — `SelectionReducer`/`PendingDelete` pure helpers, deferred-delete UNDO (codex-hardened), glass selection/batch bars + item sheet, `LibraryScreen` is the **route orchestrator**, `HistoryScreen` RETIRED (`LibraryRow`/`VideoThumbnail` → `ui/screens/LibraryRow.kt` for VaultScreen), route `"history"`→`LibraryScreen`. Recovery-keep manifest write relocated to `ui/recovery/RecoveryViewModelFactory.kt` (Option A) → gate exception-free. **committed.**
   - **+ Visual polish pass (P1–P10)** — **committed `b12bb5f`**, owner re-smoke **GO** 2026-06-15.
4. **Discovery** ✅ built + owner device-smoke **GO** (2026-06-15) — Sort sheet · Filter chips (All·★Favorites·DualShot) · inline Search · date fast-scroll Scrubber. Pure `LibraryQuery.heroFor` (filter-aware hero) + `ScrubberIndex`; thin VM `_sort`/`_filter` state; `LibraryScreen` query sort/filter/search-driven over `visibleRows`; scrubber codex-hardened (separate live-region node, discrete progress, coalesced scroll). Plan: `docs/superpowers/plans/2026-06-15-library-history-slice4-discovery.md`.
   - **4.1 smoke fixes** ✅ **committed `09c3e5c`** — (#1) **view-mode persists across launches** (`RovaSettings.libraryViewMode`, seeded into `HistoryViewModel._viewMode`); (#3) filter chip **P+L → "DualShot"** (en+es). (#4) confirmed **hero = newest** is intended (no code change — `heroFor` keeps newest regardless of sort; collection is what re-sorts).
   - **4.2 pooled card autoplay** ✅ **committed `dc1e9ce`**, owner device-smoke **GO** (2026-06-15) — owner #2 "all recordings auto-play". Plan: `docs/superpowers/plans/2026-06-15-library-slice4.2-card-autoplay.md`. See "Slice 4.2" below.
5. **A11y close-out** ✅ built + owner TalkBack device-smoke **GO** (2026-06-15) — remediation **rows 21 / 23 / 32**. Player `SegmentedTimeline`: progressbar role (`progressBarRangeInfo`, continuous position/duration) + per-cell `contentDescription` ("Clip N of M, recorded/playing/upcoming") + separate sparse polite live-region. Library: `RecoveryAndWarnings` is its own `isTraversalGroup` (focus separation, HIST-02), `HistoryWarningCard` gains `focusHighlight` (HIST-17), focus restore on player return via `pendingFocusKey` + ON_RESUME observer + new pure `FocusRestorePolicy` (JVM-tested) + `FocusRequester` on each target's own modifier chain (codex fix). 3 commits `32487fd`/`345e74e`/`23bee99`. **Row-23 DELTA:** only the Library-relevant subset closed (focus restore + warning focusable); REC-15/RECOV-09/RECOV-10/NAV-04/SHAR-08/SHAR-16/ONB-06 (other screens) stay OPEN.

**Library reskin is COMPLETE** — functional (Slices 1–5) + visual polish (P1–P8) + theme-foundation seam (M1–M3) + UX refine (M1/M2) + stabilization. Reference-ready for the theme engine. See the polish/theme/stabilization records in `memory/project_library_history_redesign.md`.

## DualShot hero thumbnail artifact — ROOT-CAUSED (2026-06-15), fix folds into polish P2

Owner reported a grey horizontal strip on the **hero** card for DualShot recordings (grid tile clean). Full RCA + device disambiguation: `docs/superpowers/specs/2026-06-15-library-dualshot-rca-and-polish-brief.md`.
- **Result: Library-side render bug (H1), NOT capture (H2 refuted).** Pulled both DualShot sides of the hero recording from RZCYA1VBQ2H: `ffprobe` → exact 1080×1920 / 1920×1080, SAR 1:1, no rotation (so `computeFitViewport` bakes no letterbox); `ffmpeg` raw frames → **clean, no strip in pixels**. The strip is introduced at render.
- **Mechanism:** the hero autoplay stacks a static `VideoFrame` (`ContentScale.Crop`) under a transparent ExoPlayer (`RESIZE_MODE_ZOOM`); the two fill identically only at 16:9, so the grid (16:9) is clean but the off-16:9 hero box (~2.27:1) leaks a band. **Fix = Slice P2** (make both layers fill identically / drop static under-layer after first frame / lock hero `aspectRatio`).
- The separate **owner-deferred** PORTRAIT square-into-9:16 capture stretch (`AspectFitMath.kt:359–366`) is unrelated and stays capture-side (ADR-0009/0010).

## Library UX/UI polish pass — P1–P8 ✅ DONE (2026-06-15/16, device-smoked)

Audit + design brief: `docs/superpowers/specs/2026-06-15-library-dualshot-rca-and-polish-brief.md`. All slices built + 42-gates + JVM green + device-smoked on RZCYA1VBQ2H, committed local-only. Summary (full detail in `memory/project_library_history_redesign.md`):
- **P1** tokens (`LibraryDimens` Record-scale) · **P2** hero showcase overlay + **DualShot grey-strip FIXED via TextureView** (`surface_type=texture_view`, owner "completely gone") · **P3** grid/list glass + session identity (`clipCount`+`SessionCaption`+orientation badges on all tiles; DualShot N×2 clip/duration bug fixed via `SessionDurations.forRow`) · **P4** states (pure `LibraryStatePolicy`, skeleton shimmer, quiet empties, flat glass chips) · **P5** motion (pure `PressFeedback` + `RovaAnimations.pressScale`) · **P6** usage line (`UsageAggregator`/`StorageSummaryFormatter`) · **P7** card-autoplay demoted to opt-in `RovaSettings.libraryCardPreview` default OFF (hero autoplay untouched; reseed ON_RESUME) · **P8** floating Brave-style item sheet (transparent ModalBottomSheet + inner elevated Surface, nav-bar insets).

## Theme Foundation M1–M3 ✅ DONE + M1/M2 UX refine ✅ DONE + Stabilization ✅ DONE (2026-06-16)

- **Theme Foundation (M1–M3, commits `91b598e`→`d0cb386`, device GO):** `LibraryColors`/`LibraryColorSpec` seam — identity edges + `palette.background` retint per theme; overlay-over-media (scrim/pill/ring + status) LOCKED via CaptionScrim/RovaSemantics; `FloatingGlassSheet` (shadow-wrap + GlassResolver BottomSheet near-opaque branch) theme-enables the item sheet; UX-B sheet grouping (Primary/Secondary/Danger red Delete). UX-A visible-Select-entry DEFERRED (needs a SelectionReducer arch change). **Library = the reference screen for the glass tint engine.**
- **M1/M2 UX refine (commit `fec1f8a`):** filter multi-select cue (leading ✓ on additive Favorites/DualShot toggles; `All` gap-isolated) + educational empty states (pure `FilteredEmptyPolicy` → Favorites/DualShot/Generic/Search copy) + usage-line demote (labelMedium→Small, AA-safe). **M3a (drop orientation glyph on DualShot tiles) was evaluated and REJECTED** — DualShot rows are per-SIDE, so the glyph is authoritative side-identity (`OrientationResolver`); codex + code proved the premise wrong.
- **Stabilization (commit `e0f359a`):** **Issue A sort crash (Largest/Longest) FIXED** — `LibraryDayGrouping.groupForSort` gates day-grouping by `LibrarySort.isChronological`; size/duration sorts render flat (one header-less bucket) so day labels can't recur → no duplicate LazyList keys. +5 regression tests incl the no-duplicate-labels invariant. **Issue B "blue dot" = `LibraryScrubber` thumb** (24dp primary CircleShape, the date-rail drag handle; intentional, a11y-wired) — KEEP; rest-state refinement is a deferred B-item.

## Slice 4.2 — pooled muted card autoplay (committed `dc1e9ce`)

Extends the hero's muted autoplay to **visible** grid/list cards, bounded decoder-safe per **two codex passes**:
- **`ui/library/AutoplayPolicy.kt`** (pure, JVM-tested `AutoplayPolicyTest`): `MAX_CONCURRENT = 3` total players **including the hero** (`cardCap(heroVisible) = 3 − heroVisible`); `select()` = first-N in viewport order; `isMostlyVisible(top,size,vpStart,vpEnd, ≥0.5)` so an edge-sliver card can't claim a decoder.
- **Renamed** `LibraryHeroVideo.kt` → **`LibraryAutoplayVideo.kt`** (now reused by hero + cards) and **disabled the audio track** — `trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)`. codex: `volume = 0f` mutes output but leaves the audio **decoder** allocated; disabling the track frees scarce hardware audio-codec instances.
- **`LibraryGridCard` / `LibraryListRow`** gained `previewUri: Uri?` + `autoplay: Boolean`; autoplay → the shared player, else the static `VideoFrame` (= the reduce-motion path too).
- **`LibraryScreen`** computes `autoplayKeys` via `derivedStateOf` (keyed on `reduceMotion`; `ui.viewMode` + `gridState/listState.layoutInfo` read **inside** as snapshot state → recomputes on scroll/settle): visible-only, ≥50% on-screen, hero counted, `emptySet` while scrolling or reduce-motion. Card keys **prefix-filtered** (`!startsWith("hdr-")/"hero-"`), not via a Map.
- **Decoder-safe reality (owner-acknowledged):** "all recordings auto-play" resolves to **≤3 videos at once** (hero + 2 cards, or 3 cards when hero off-screen), not literally every card. `MAX_CONCURRENT` is one-line tunable (drop to 2 if any device shows codec errors).
- **v2 note (deferred):** current shape releases players on scroll-start, re-creates on settle (one batch/gesture, ≤3 players — tolerable). codex's preferred **pause-while-scrolling + debounced-release pool** is the refinement if settle-churn shows on device. Black-flash is already covered (static `VideoFrame` sits under the transparent-shutter `AndroidView`).

## Build-environment (the "recovery dance" is OBSOLETE)

- **Build WARM. Do NOT wipe caches before every build.** The `kotlin-postedit.ps1` hook that corrupted the incremental cache was **disabled 2026-06-09**. Just `gradlew.bat :app:assembleDebug` (no `--stop`, no `rm`): warm ~1–3 min (UP-TO-DATE ~8s) vs ~17 min cold. Last full Slice-4.2 gate build = 11m25s.
- **Clean only on-demand** on a real kotlinc/MD5 fault: `gradlew.bat --stop` + `rm -rf app/build/kotlin app/build/intermediates/built_in_kotlinc`.
- `gradle.properties` well-tuned. **Config cache is OFF** (the 42 gates capture build-script refs; refactor filed P2 in BACKLOG).
- **Windows / PowerShell.** Use `gradlew.bat`. **adb MCP wrapper broken on Windows — drive adb via PowerShell directly** (`adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk`).
- **`lintDebug` is RED on pre-existing B5 `VaultAndroidOps` NewApi** (unrelated) — gate-build with `:app:assembleDebug`.

## Release build (for sharing a tester APK)

- **No merge required.** Gradle builds the **working tree**, not master. The `feat/library-history-selection` tree = Slices 1+2+3+4(+4.x) = the full current redesign. Build straight from it.
- **Command:** `gradlew.bat :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`. **Signed** via `keystore.properties` (configured locally).
- **Release ≠ debug behavior:** release has `isMinifyEnabled = true` + `isShrinkResources = true` (R8/ProGuard) and pseudolocales OFF. The hero **and now card** ExoPlayer autoplay path must be **smoke-tested on the RELEASE build specifically** (media3 ships consumer ProGuard rules, but R8 + new Compose/Media3 paths warrant a real check) before handing it to a tester. (Slice-3 hero release path was reported smoked clean; the Slice-4.2 multi-player path has only been debug-smoked.)

## Load-bearing rules (don't violate)

- **Never edit a `check*` task to make it green** — fix the source, or amend the ADR + check with explicit owner sign-off.
- **ADRs are the source of truth.** Touching anything an ADR mentions = amend the ADR clause first, regenerate/extend the matching `check*`, then change code.
- **`checkLibraryNoManifestWrite` (ADR-0030):** Library/History UI (`ui/library/**` + History/Library screens) NEVER writes `SessionManifest`. Library metadata lives in the sidecar `LibraryMetadataStore`. The recovery-keep `markTerminated` write lives in `ui/recovery/` (out of gate scope).
- **codex MCP peer review** (`mcp__codex__codex`) mandatory for code changes >5 lines, architecture/design, algorithmic logic, security-sensitive, migrations, perf claims. Skip for conversational/status/trivial.
- **CodeGraph** is initialized — never call `codegraph_explore`/`codegraph_context` from the main session; spawn an Explore agent. Main session may use `codegraph_search`/`callers`/`callees`/`impact`/`node` for targeted lookups only.
- **JVM unit tests only** (`isReturnDefaultValues = true`); framework-touching code gets a pure-Kotlin sibling (seam/pure-helper pattern). A new feature lands its tests in the same PR.
- **New user-facing strings go in resources** (ADR-0022) in **both** `values/strings.xml` and `values-es/strings.xml`. `checkNoHardcodedUiStrings` enforces it.
- **WCAG 2.2 AA by default** (ADR-0020). Three a11y gates live: `checkA11yAnimationGated`, `checkA11yClickableHasRole`, `checkA11yTargetSizeToken` (SC 2.5.8 = ≥24dp, not Material 48dp). Token contrast is covered by `TokenContrastTest` (JVM), NOT a static gate.
- **Stacked-PR merge train:** merge base WITHOUT `--delete-branch` → rebase dependent `--onto origin/master <old-base>` → re-target/re-push → THEN delete. See `memory/feedback_stacked_pr_merge_train.md`. (This matters when the whole Library train finally merges.)
- **Commit/push only when the owner asks.** (On this branch the working agreement is: commit per-slice **locally**; never push/merge until the full reskin is done.) Untracked `gradle_*.log` in root are ephemeral — leave them, don't commit.
- **Subagents EDIT-ONLY; the controller runs all gradle + commits** (post-edit daemon pileup pins CPU otherwise).

## Key references

- `CLAUDE.md` — project instructions. **NOTE:** CLAUDE.md still says "41 gates / 29 ADRs"; on the Library branch it is **42 gates** (`checkLibraryNoManifestWrite`) and **30 ADRs** (ADR-0030). CLAUDE.md updates when the train merges to master.
- `MEMORY.md` (auto-loaded) — cross-session index; `memory/project_library_history_redesign.md` = the redesign record; `memory/project_current_state.md` running state; `memory/project_build_env_perf.md` build-speed note.
- `docs/accessibility/` — WCAG 2.2 AA audit + **`remediation-backlog.md`** (the source for Slice 5 rows 21/23/32). `docs/adr/` — behavioral invariants. `docs/BACKLOG.md` — full task list. `ROADMAP_v6.md` (reliability), `NEW_UI_BACKEND_REPLAN.md` (UI redesign).

---

## Fresh-session kickoff prompt — Library UX/UI polish pass (P1–P5)

Paste this to start the next session:

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.

Orient first: read HANDOFF.md, CLAUDE.md, the auto-loaded MEMORY.md,
memory/project_library_history_redesign.md, and the design brief
docs/superpowers/specs/2026-06-15-library-dualshot-rca-and-polish-brief.md (PART B audit + PART C
roadmap + PART A DualShot RCA). Don't re-explore what those establish.

State: the Enhanced Library & History redesign (Liquid Glass, ADR-0028 + ADR-0030) is FUNCTIONALLY
COMPLETE — Slices 1–5 (foundation, layout, selection/batch, discovery + 4.1/4.2 autoplay, a11y
close-out) all built + device/TalkBack GO, on the local-only branch feat/library-history-selection
(~62 commits ahead of master dfd3bd9, NOT pushed; Slice-1 PR #117 + Slice-2 PR #118 OPEN/stacked).
Owner directive: NO merge until the WHOLE reskin (including this polish pass) is done; keep stacking
locally. All device smoke on RZCYA1VBQ2H Android 14.

TASK: Library UX/UI POLISH PASS, P1–P5, stacked on feat/library-history-selection. The Library is
functionally done but reads as a prototype next to the Record screen. Raise it to Record's visual
quality. The audit + design brief + per-slice direction is PART B/C of the brief doc above — follow it.
Run writing-plans to turn P1–P5 into a per-slice plan BEFORE coding (brainstorm only if the brief
leaves a real fork). Slices:
  P1 — token foundation: extend ui/library/components/LibraryDimens.kt to a Record-aligned scale
       (screenPadH 16, cardRadius 18, heroRadius 20, pillRadius 11, + scrim/divider/selection/empty
       tokens) and route every Library component through it. Safe refactor; review visual deltas.
  P2 — hero treatment: glass+scrim depth on LibraryHeroCard, RovaTokens.eyebrow, ONE primary CTA
       (Play; Favorite/Share subordinate), stronger caption scrim. **ALSO fix the DualShot hero
       artifact here** — it is a CONFIRMED Library render-layer bug (PART A: source frames are clean,
       H2 capture-bake refuted). The static VideoFrame (ContentScale.Crop) under the transparent
       RESIZE_MODE_ZOOM ExoPlayer in LibraryAutoplayVideo diverge in the off-16:9 hero box → grey band.
       Make both layers fill identically (or drop the static under-layer after the player's first
       frame, or lock the hero aspectRatio). Confirm with reduce-motion-off on device.
  P3 — grid/list polish: glass-aware LibraryGridCard/LibraryListRow, thumbnail prominence, soft glass
       selection ring (replace the hard 2dp primary border), tokenized CaptionBar scrim.
  P4 — discovery bar + states: quieter glass-consistent chips, skeleton/shimmer loading,
       on-brand empty + search-empty, error/missing-file messaging.
  P5 — interaction/motion consistency with Record (press feedback, durations); verify GlassResolver
       output alphas equal Record's (0.40 fill / 0.07 stroke) and align if not.

Reference = Record screen tokens: ui/theme/RecordChromeTokens.kt, ui/theme/RovaTokens.kt (Inter scale),
ui/theme/GlassSurface.kt (3-layer glass). Quality bar = the Record home screen.

Pipeline: writing-plans -> subagent-driven build (subagents EDIT-ONLY; controller runs all gradle +
commits) -> device smoke on RZCYA1VBQ2H per slice (incl. reduce-motion path for P2). New user-facing
strings in resources (ADR-0022, en+es). JVM-only tests; framework-touching code gets a pure-Kotlin
sibling. codex-review code >5 lines / architecture / visual-system decisions.

Constraints: never edit a check* gate to pass (fix source, or amend ADR + check with owner sign-off).
ADR-0030 = Library/History UI never writes SessionManifest (sidecar LibraryMetadataStore only);
checkLibraryNoManifestWrite enforces it. WCAG 2.2 AA by default (ADR-0020); a11y gates live =
checkA11yAnimationGated / checkA11yClickableHasRole / checkA11yTargetSizeToken; reduce-motion gated
(rememberReduceMotion). CodeGraph exploration via Explore agent only (never codegraph_explore /
codegraph_context from the main session).

Build FAST: WARM — just gradlew.bat :app:assembleDebug (NO --stop, NO cache wipe). Gate-build with
assembleDebug (lintDebug RED on pre-existing VaultAndroidOps NewApi). adb via PowerShell direct
(adb -s RZCYA1VBQ2H ...; MCP adb wrapper broken on Windows).

Caveman mode + Ultracode are ON. Commit per-slice LOCALLY; push/merge only when the owner asks
(no merge until the full reskin — incl. this polish pass — is done). After P5 the reskin is complete:
ask the owner whether to run the end-of-reskin stacked merge train
(see memory/feedback_stacked_pr_merge_train.md).

NOT in scope: the owner-deferred PORTRAIT square-into-9:16 DualShot CAPTURE stretch
(AspectFitMath.kt:359-366, ADR-0009/0010) — capture-side, separate task, do not touch in the polish pass.
```
