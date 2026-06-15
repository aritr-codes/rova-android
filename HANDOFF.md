# Rova — Session Handoff

> Drop-in orientation for a **fresh session**. Read this + `CLAUDE.md` + the auto-loaded `MEMORY.md`, then [`docs/BACKLOG.md`](docs/BACKLOG.md) for the full task list.
> **As of 2026-06-15 (Enhanced Library & History redesign — Slice 4 Discovery built + installed; PENDING owner device smoke).**

---

## Where things stand

- **Active branch:** `feat/library-history-selection` — **local only, NOT pushed**, **54 commits ahead** of `master`. Holds **all of Slices 1 + 2 + 3 + 4** of the Library/History redesign. The Slice-3 visual polish pass is **committed** (`b12bb5f`); Slice 4 Discovery is committed (12 commits, tip `a9188dc`). Working tree clean (only ephemeral `gradle_*.log` untracked).
- **master:** `dfd3bd9` (#116 DualSight doc-closeout). **Contains zero Library redesign code.**
- **Open PRs (pushed, awaiting the merge train):**
  - **#117 — Slice 1 Foundation** (branch `feat/library-history-foundation`) · OPEN
  - **#118 — Slice 2 Layout** (branch `feat/library-history-layout`, stacked on #117) · OPEN
  - **#115 — DualSight investigation** (DRAFT, archival only — **never merge**, blocked on concurrent-camera hardware; see `memory/project_pr_delta_dualsight_probe.md`).
- **Owner directive:** **NO merge of any Library slice until the FULL Library reskin is done.** Keep stacking slices; merge the whole train at the end. So Slice 3 stays unpushed, and #117/#118 stay open.
- **Working tree:** the polish pass is **uncommitted** (intentional, see "Polish pass" below). Plus the ephemeral untracked root `gradle_*.log` (leave them).
- **Test baseline:** JVM unit tests only (`:app:testDebugUnitTest`), GREEN on this branch. Real-device smoke is mandatory — emulators fail CameraX recording (device = RZCYA1VBQ2H, Android 14).
- **Static gates:** **42** custom `check*` tasks (the 42nd is `checkLibraryNoManifestWrite`, ADR-0030). All green on this branch.

## Library & History redesign — IN PROGRESS (this is the current work)

Liquid-Glass redesign of the recording-browse surfaces (ADR-0028 + new **ADR-0030**). Owner-approved via brainstorming → spec → plan. Full record: `memory/project_library_history_redesign.md`. Docs: spec `docs/superpowers/specs/2026-06-14-enhanced-library-history-design.md`, slice plans under `docs/superpowers/plans/`, `docs/adr/0030-*.md`.

**Slice status (stacked, 1 PR each):**
1. **Foundation** ✅ built — sidecar `LibraryMetadataStore` (favorite/rename, NEVER `SessionManifest`), pure helpers, 42nd gate. **PR #117 OPEN.**
2. **Layout** ✅ built — hero+grid/list, glass cards, badges, day grouping, a11y. **PR #118 OPEN.**
3. **Selection + batch + route-wire** ✅ built — `SelectionReducer`/`PendingDelete` pure helpers, deferred-delete UNDO (codex-hardened), glass selection/batch bars + item sheet, `LibraryScreen` is now the **route orchestrator**, `HistoryScreen` RETIRED (`LibraryRow`/`VideoThumbnail` extracted to `ui/screens/LibraryRow.kt` for VaultScreen), route swapped `"history"`→`LibraryScreen`. Recovery-keep manifest write relocated to `ui/recovery/RecoveryViewModelFactory.kt` (Option A) → gate is exception-free. **Branch `feat/library-history-selection`, local only.**
   - **+ Visual polish pass (P1–P10)** — **committed `b12bb5f`** (owner re-smoke GO 2026-06-15).
4. **Discovery** ✅ built (2026-06-15) — Sort sheet · Filter chips (All·★Favorites·P+L) · inline Search · date fast-scroll Scrubber. New pure `LibraryQuery.heroFor` (filter-aware hero) + `ScrubberIndex`; thin VM `_sort`/`_filter` state; `LibraryScreen` query now sort/filter/search-driven over `visibleRows`; scrubber codex-hardened (separate live-region node, discrete progress, coalesced scroll). Build + 42 gates + JVM suite GREEN; codex + code-reviewer clean (0 critical, 0 ADR-0030). Debug APK installed on RZCYA1VBQ2H. **PENDING owner device smoke** (checklist = `docs/superpowers/plans/2026-06-15-library-history-slice4-discovery.md` Task 12).
5. **A11y close-out** ⬜ NEXT — remediation rows 21/23/32 (player `SegmentedTimeline` progressbar + per-cell labels; History warning-card focus separation + focus restore on player return).

## Polish pass (committed `b12bb5f`) — what it contains

Owner asked for a dedicated visual polish pass on Slice 3 ("feels like a working prototype rather than the approved redesign"). Done, mapped to 10 priorities; **committed `b12bb5f`** after owner re-smoke GO (2026-06-15).

- **New:** `ui/library/components/LibraryDimens.kt` (shared radius/padding/icon tokens, P1/P9), `ui/library/components/LibraryHeroVideo.kt` (hero-only muted autoplay, ONE ExoPlayer, lifecycle-gated, reduce-motion-gated, codex-reviewed — 4 lifecycle/perf fixes folded: app-context, start-paused+seed-from-lifecycle, `onRelease` detach, single derived `playWhenReady`).
- **Modified (10):** `LibraryScreen.kt` (autoplay wire + LibraryDimens paddings), `LibraryHeroCard.kt` (shorter hero 176dp + unified action chip group), `LibraryTopBar.kt`/`LibrarySelectionTopBar.kt` (tighter, statusBarsPadding), `LibraryBatchBar.kt` (labelled actions, not icon-only), `LibraryDayHeader.kt` (lighter section label, not toolbar), `LibraryBadges.kt` (gradient caption scrim), `LibraryGridCard.kt` (radius/scrim, lighter selection ring+check), `LibraryListRow.kt` (padding/shape), `LibraryStates.kt` (ring empty-state).
- **codex residual (acceptable for one hero, noted to owner):** offscreen-prefetch-buffer playback, no `onPlayerError` listener, not hoisted to a VM/controller.

**NEXT for the resuming session:** await owner device-smoke verdict on Slice 4 (Discovery) installed on RZCYA1VBQ2H → then **Slice 5 (A11y close-out, rows 21/23/32)**, stacked on `feat/library-history-selection`. Do NOT push/merge until the full reskin is done.

## Release build (for sharing a tester APK)

- **No merge required.** Gradle builds the **working tree**, not master. The `feat/library-history-selection` tree = Slices 1+2+3 + polish = the full current redesign. Build straight from it.
- **Caveat:** the polish pass is uncommitted — a release build includes it (read from disk) but the bits aren't tracked. For a traceable build, **commit the polish to the branch first** (committing to the stacked branch is NOT merging; honors the owner directive).
- **Command:** `gradlew.bat :app:assembleRelease` → `app/build/outputs/apk/release/app-release.apk`. **Signed** via `keystore.properties` (configured locally).
- **Release ≠ debug behavior:** release has `isMinifyEnabled = true` + `isShrinkResources = true` (R8/ProGuard) and pseudolocales OFF. The new hero ExoPlayer path must be **smoke-tested on the RELEASE build specifically** (media3 ships consumer ProGuard rules, but R8 + a new Compose/Media3 path warrants a real check) before handing it to a tester.

## Build-environment (the "recovery dance" is OBSOLETE)

- **Build WARM. Do NOT wipe caches before every build.** The `kotlin-postedit.ps1` hook that corrupted the incremental cache was **disabled 2026-06-09**. Just `gradlew.bat :app:assembleDebug` (no `--stop`, no `rm`): warm ~1–3 min (UP-TO-DATE ~8s) vs ~17 min cold.
- **Clean only on-demand** on a real kotlinc/MD5 fault: `gradlew.bat --stop` + `rm -rf app/build/kotlin app/build/intermediates/built_in_kotlinc`.
- `gradle.properties` well-tuned. **Config cache is OFF** (the 42 gates capture build-script refs; refactor filed P2 in BACKLOG).
- **Windows / PowerShell.** Use `gradlew.bat`. **adb MCP wrapper broken on Windows — drive adb via PowerShell directly.**
- **`lintDebug` is RED on pre-existing B5 `VaultAndroidOps` NewApi** (unrelated) — gate-build with `:app:assembleDebug`.

## Load-bearing rules (don't violate)

- **Never edit a `check*` task to make it green** — fix the source, or amend the ADR + check with explicit owner sign-off.
- **ADRs are the source of truth.** Touching anything an ADR mentions = amend the ADR clause first, regenerate/extend the matching `check*`, then change code.
- **`checkLibraryNoManifestWrite` (ADR-0030):** Library/History UI (`ui/library/**` + History/Library screens) NEVER writes `SessionManifest`. Library metadata lives in the sidecar `LibraryMetadataStore`. The recovery-keep `markTerminated` write lives in `ui/recovery/` (out of gate scope).
- **codex MCP peer review** (`mcp__codex__codex`) mandatory for code changes >5 lines, architecture/design, algorithmic logic, security-sensitive, migrations, perf claims. Skip for conversational/status/trivial.
- **CodeGraph** is initialized — never call `codegraph_explore`/`codegraph_context` from the main session; spawn an Explore agent. Main session may use `codegraph_search`/`callers`/`callees`/`impact`/`node` for targeted lookups only.
- **JVM unit tests only** (`isReturnDefaultValues = true`); framework-touching code gets a pure-Kotlin sibling (seam/pure-helper pattern). A new feature lands its tests in the same PR.
- **Stacked-PR merge train:** merge base WITHOUT `--delete-branch` → rebase dependent `--onto origin/master <old-base>` → re-target/re-push → THEN delete. See `memory/feedback_stacked_pr_merge_train.md`. (This matters when the whole Library train finally merges.)
- **Commit/push only when the owner asks.** Untracked `gradle_*.log` in root are ephemeral — leave them, don't commit.

## Key references

- `CLAUDE.md` — project instructions. **NOTE:** CLAUDE.md still says "41 gates / 29 ADRs"; on the Library branch it is **42 gates** (`checkLibraryNoManifestWrite`) and **30 ADRs** (ADR-0030). CLAUDE.md updates when the train merges to master.
- `MEMORY.md` (auto-loaded) — cross-session index; `memory/project_library_history_redesign.md` = the redesign record; `memory/project_current_state.md` running state; `memory/project_build_env_perf.md` build-speed note.
- `docs/BACKLOG.md` — full task list. `docs/adr/` — behavioral invariants. `ROADMAP_v6.md` (reliability), `NEW_UI_BACKEND_REPLAN.md` (UI redesign).

---

## Fresh-session kickoff prompt

Paste this to start the next session:

```
Rova Android (com.aritr.rova), repo g:\Books\Python\ACTUAL CODES\PROJECTS\rova-android.

Orient first: read HANDOFF.md, CLAUDE.md, and the auto-loaded MEMORY.md, plus
memory/project_library_history_redesign.md. Don't re-explore what those establish.

State: the Enhanced Library & History redesign (Liquid Glass, ADR-0028 + ADR-0030) is IN PROGRESS.
master is dfd3bd9 (no library code). All of Slices 1+2+3 live on the local-only branch
feat/library-history-selection (40 commits ahead, NOT pushed). Slice 1 = PR #117 OPEN, Slice 2 =
PR #118 OPEN (both pushed, stacked, awaiting the end-of-reskin merge train). On top of Slice 3 sits
an UNCOMMITTED visual polish pass (P1-P10) — build + 42 gates + JVM tests GREEN, APK installed on
RZCYA1VBQ2H, awaiting owner visual re-smoke. Owner directive: NO merge until the full Library reskin
is done; keep stacking.

TASK: Slice 4 — Discovery (sort/filter/search UI + player scrubber), stacked on
feat/library-history-selection. (First confirm whether the owner has (a) approved the polish pass
re-smoke and (b) wants the polish committed before Slice 4 starts.) Then continue Slice 5 (a11y
close-out, rows 21/23/32).

Pipeline: the design + slice plans already exist (docs/superpowers/specs + plans). Follow the
existing Slice plan if one exists for Discovery; otherwise writing-plans -> subagent-driven build ->
device smoke on RZCYA1VBQ2H. New user-facing strings go in resources (ADR-0022, en+es). JVM-only
tests; framework-touching code gets a pure-Kotlin sibling. codex-review code >5 lines / architecture.

Constraints: never edit a check* gate to pass. ADR-0030 = Library/History UI never writes
SessionManifest (sidecar LibraryMetadataStore only); checkLibraryNoManifestWrite enforces it.
WCAG 2.2 AA by default (ADR-0020). CodeGraph exploration via Explore agent only.

Build FAST: WARM — just gradlew.bat :app:assembleDebug (NO --stop, NO cache wipe). Gate-build with
assembleDebug (lintDebug RED on pre-existing VaultAndroidOps NewApi).

Caveman mode + Ultracode if they were on. Commit/push only when the owner asks.
```
