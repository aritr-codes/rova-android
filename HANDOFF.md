# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-15 (Enhanced Library & History redesign — Slices 1–4 + Discovery follow-ups (4.1/4.2) all built, device-smoke GO. NEXT = Slice 5 a11y close-out.)**

---

## Where things stand

- **Active branch:** `feat/library-history-selection` — **local only, NOT pushed**, **57 commits ahead** of `master`, tip `dc1e9ce`. Holds **all of Slices 1 + 2 + 3 + 4 (+ Discovery follow-ups 4.1/4.2)** of the Library/History redesign. Working tree clean (only ephemeral `gradle_*.log` untracked).
- **master:** `dfd3bd9` (#116 DualSight doc-closeout). **Contains zero Library redesign code.**
- **Open PRs (pushed, awaiting the end-of-reskin merge train):**
  - **#117 — Slice 1 Foundation** (branch `feat/library-history-foundation`) · OPEN
  - **#118 — Slice 2 Layout** (branch `feat/library-history-layout`, stacked on #117) · OPEN
  - **#115 — DualSight investigation** (DRAFT, archival only — **never merge**, blocked on concurrent-camera hardware; see `memory/project_pr_delta_dualsight_probe.md`).
- **Owner directive:** **NO merge of any Library slice until the FULL Library reskin is done.** Keep stacking slices; merge the whole train at the end. Slices 3/4/4.x stay unpushed; #117/#118 stay open.
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), GREEN on this branch. Real-device smoke is mandatory — emulators fail CameraX recording (device = RZCYA1VBQ2H, Android 14).
- **Static gates:** **42** custom `check*` tasks (the 42nd is `checkLibraryNoManifestWrite`, ADR-0030). All green on this branch. Slice 4/4.x added **no** new gates (autoplay is a runtime policy, not a static invariant).

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
5. **A11y close-out** ⬜ **NEXT** — remediation **rows 21 / 23 / 32** (player `SegmentedTimeline` progressbar + per-cell labels; Library warning-card focus separation + focus restore on player return). Stacked on `feat/library-history-selection`. **See the kickoff prompt at the bottom.**

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

## Fresh-session kickoff prompt — Slice 5 (A11y close-out)

Paste this to start the next session:

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.

Orient first: read HANDOFF.md, CLAUDE.md, and the auto-loaded MEMORY.md, plus
memory/project_library_history_redesign.md. Don't re-explore what those establish.

State: the Enhanced Library & History redesign (Liquid Glass, ADR-0028 + ADR-0030) is IN PROGRESS.
master is dfd3bd9 (no library code). Slices 1+2+3+4 (+ Discovery follow-ups 4.1 view-mode-persist /
DualShot-rename and 4.2 pooled card autoplay) all live on the local-only branch
feat/library-history-selection (57 commits ahead, tip dc1e9ce, NOT pushed). Slice 1 = PR #117 OPEN,
Slice 2 = PR #118 OPEN (pushed, stacked, awaiting the end-of-reskin merge train). Slices 3/4/4.x are
committed locally only. Owner directive: NO merge until the full Library reskin is done; keep stacking.
All of Slices 1–4 are owner device-smoke GO on RZCYA1VBQ2H.

TASK: Slice 5 — A11y close-out, stacked on feat/library-history-selection. Close the WCAG 2.2 AA
remediation-backlog rows that the redesign deferred. Read docs/accessibility/remediation-backlog.md
and confirm the exact wording of rows 21, 23, 32 before planning — then:
  - Player SegmentedTimeline (ui/screens/player/**): add progressBarRangeInfo semantics to the
    timeline (current position / duration as a range) + a per-segment-cell contentDescription/label
    so TalkBack can announce each segment. (This mirrors the discrete-progress pattern already used
    by LibraryScrubber in Slice 4 — reuse that approach.)
  - Library warning/recovery card (RecoveryAndWarnings in ui/library/LibraryScreen.kt + the
    RecoveryCard/warning host): focus SEPARATION (the warning card must not merge its focus/semantics
    with sibling rows) + focus RESTORE when returning from the player (restore focus to the row that
    launched playback). Use a FocusRequester/saved-key seam; extract any non-trivial restore logic as
    a pure-Kotlin helper so it's JVM-testable.
  - Verify against rows 21/23/32 exactly; if any row's scope differs from the above, follow the
    backlog wording (it is authoritative) and note the delta.

Pipeline: writing-plans -> subagent-driven build (subagents EDIT-ONLY; controller runs all gradle +
commits) -> device smoke on RZCYA1VBQ2H (TalkBack ON: confirm timeline announces position+segments,
warning card reads as its own node, focus returns correctly from the player). New user-facing strings
go in resources (ADR-0022, en+es). JVM-only tests; framework-touching code gets a pure-Kotlin sibling.
codex-review code >5 lines / architecture / a11y-semantics logic.

Constraints: never edit a check* gate to pass (fix source, or amend ADR + check with owner sign-off).
ADR-0030 = Library/History UI never writes SessionManifest (sidecar LibraryMetadataStore only);
checkLibraryNoManifestWrite enforces it. WCAG 2.2 AA by default (ADR-0020); a11y gates live =
checkA11yAnimationGated / checkA11yClickableHasRole / checkA11yTargetSizeToken. CodeGraph exploration
via Explore agent only (never codegraph_explore/codegraph_context from the main session).

Build FAST: WARM — just gradlew.bat :app:assembleDebug (NO --stop, NO cache wipe). Gate-build with
assembleDebug (lintDebug RED on pre-existing VaultAndroidOps NewApi).

Caveman mode + Ultracode are ON. Commit per-slice LOCALLY; push/merge only when the owner asks
(no merge until the full reskin is done). After Slice 5: the full reskin may be complete — ask the
owner whether to run the end-of-reskin stacked merge train (see memory/feedback_stacked_pr_merge_train.md).
```
