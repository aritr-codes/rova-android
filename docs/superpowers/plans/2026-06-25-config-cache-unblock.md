# Config-Cache Unblock (Gate Refactor) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make all 46 custom `check*` Gradle gates config-cache-serializable AND UP-TO-DATE/build-cacheable so `org.gradle.configuration-cache=true` can be enabled, with EVERY gate's enforced rule, comment-skipping, scanned scope, and failure message byte-identical to today.

**Architecture:** Move every gate out of `app/build.gradle.kts`'s `doLast{}` closures (which capture `Project`/`rootDir`/`file()`/`fileTree()` at execution time → the config-cache `this$0 is null` failure) into ONE typed `@CacheableTask SourceCheckTask` in a `build-logic/` included build. The task captures no `Project`: it receives a declared `ConfigurableFileCollection`, reads each file into a pure `SourceFile(relPath, lines, text)`, and dispatches to a pure-Kotlin `RovaGateRules` registry keyed by an `@Input` `checkId` string. Each rule function is the existing gate logic **lifted verbatim**, returning the exact thrown message (or `null` for pass). A success-only `@OutputFile` sentinel gives UP-TO-DATE + build-cache. Golden pass/fail/empty JVM tests in `build-logic`'s own test source set pin every gate's behaviour.

**Tech Stack:** Gradle 9.4.1 included build (`build-logic`, `kotlin-dsl` plugin), Kotlin 2.2.10, AGP 9.2.1, JUnit (build-logic test source set). No app source / no `app/` Kotlin behaviour change.

## Global Constraints

- **46 static gates + `:app:testDebugUnitTest` GREEN at EVERY commit.** Verify app builds via `./gradlew :app:assembleDebug` (fires the 46 `check*` on `preBuild`), NOT `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated).
- **THE NON-NEGOTIABLE INVARIANT:** this refactor changes only HOW a gate runs, never WHAT it enforces. Each gate keeps its exact ADR-clause rule, regex/AST logic, comment-skipping, scanned scope, and failure message. "Do not edit a check away to make it green." A refactor that lets a real violation slip through is a REGRESSION = instant fail.
- **No `Project`/script reference at any gate's execution phase:** no `Project.file`/`rootDir`/`fileTree`/`logger`/`project` inside `@TaskAction`. Inject everything as serializable inputs/providers at configuration time.
- **Per-gate proof obligation (heart of the task):** for EVERY one of the 46 gates, prove it STILL FAILS on a real violation after the refactor (inject a representative violation, run, confirm RED with the same message class, revert) AND that a watched-source edit RE-TRIGGERS it (not stuck UP-TO-DATE on stale cache). Systematic, not sampled. These proofs are codified as build-logic JVM tests + a controller-run RED/GREEN matrix.
- **Config-cache store/reuse proof:** first run STORES, second run prints "Reusing configuration cache.", both with all 46 gates wired to `preBuild` and GREEN on clean source; `--configuration-cache-problems=fail` → 0 problems. Run at least once with config-cache OFF too, confirming no regression either way. Deleting `.gradle/configuration-cache` between store/reuse experiments is task-intrinsic, NOT a prophylactic wipe.
- **Pure-helper + JVM tests:** rule logic lives in pure `RovaGateRules` functions over `List<SourceFile>`; tests use no Robolectric/instrumented. `:app` test policy unchanged (`isReturnDefaultValues = true`); build-logic has its own JUnit test source set.
- **No app behavioural change → no manifest schema bump, no ADR amend.** Gate count stays **46**. If the refactor genuinely needs an ADR touch, STOP and escalate to owner.
- **Subagent-driven:** subagents are EDIT-ONLY; the controller runs ALL gradle/tests/commits/smoke and serializes the shared Gradle daemon (one build at a time). Build WARM — no prophylactic cache wipes (except the task-intrinsic config-cache-dir deletes above).
- **Daemon serialization:** never run two gradle invocations concurrently against this repo's daemon.
- Push/PR/merge ONLY on explicit owner GO. PowerShell for git on Windows.

---

## Reference: the 46 gates (verbatim source of truth)

The existing gate definitions in `app/build.gradle.kts` (lines ~138–2653) are the **golden source** for each rule's logic and message. Every "lift verbatim" step below means: copy the existing `doLast{}` body into a `RovaGateRules` function with exactly two mechanical swaps and nothing else changed:

1. The file iteration source `dir.walkTopDown().filter{ it.isFile && (ext=="kt"||ext=="java") }` (or `fileTree(...)`) becomes iteration over the `files: List<SourceFile>` parameter. The `.kt/.java`/filename/path filtering that the old code did is **preserved verbatim inside the rule** (operating on `f.relPath` instead of `File`), so the declared `sources` set can stay the same broad subtree and the rule still narrows identically.
2. Every `f.readLines()` becomes `f.lines`; every `f.readText()` becomes `f.text`; every `f.relativeTo(rootDir)` (or `targetFile.relativeTo(rootDir)`) becomes `f.relPath`; every hardcoded reported filename stays as-is.

Nothing else changes — same regex strings, same comment-skip prefixes, same windows/counters/balanced-paren logic, same `throw GradleException(<message>)` text. The rule returns that message string instead of throwing; the task throws it.

**Gate shape buckets** (drives batch order; full per-gate regex/message/scope is in the inventory the controller holds and in the existing source):

| Bucket | Gates |
|---|---|
| REGEX_FORBID (single-line, `//`+`*` skip) | checkSchedulerNoGetService, checkStopNoGetService, checkScheduleReceiverNoFgsStart, checkRecoveryNoDeletion, checkRecoverySegmentRegex, checkScanTriggerSingleSite, checkExternalRootShared, checkExportTierReadTolerant, checkScanFileBoundedWait, checkPendingFdModeIsRW, checkExportNoCopyToPublicMovies, checkWakeLockBoundedAcquire, checkNoHardcodedUiStrings, checkSemanticIconNoRawAlpha, checkStatusColorLocked, checkRovaGlyphHome, checkRecordSurfaceNoBlur, checkGlassSurfaceRoleUsage, checkRecordChromeLockSingleSite, checkLibraryNoManifestWrite, checkVaultExporterNoPublicPublish, checkNoLegacyModeStrings, checkSetTargetRotationBoundaryOnly, checkFrontBackCapabilityGated, checkLocaleConfigNoPseudolocale |
| REGEX_REQUIRE (token must be present) | checkExportPendingVisibilityOnQuery, checkExportCleanupPredicate, checkWakeLockHeldRefresh, checkA11yAnimationGated, checkA11yClickableHasRole |
| STRUCTURAL (windows / boundaries / balanced-paren / XML+count) | checkRecoveryReceiverCounter, checkAtomicTerminalWriteForbiddenPair, checkAudioModeFgsTypeMatch, checkFGSStartGuarded, checkUserStoppedBeforeMerge, checkExportIsPendingGuarded, checkExportSetIncludePendingGuarded, checkExportQueryArgMatchPendingGuarded, checkExportPipelineSingleEntry, checkSafTargetCommittedBeforeStream, checkCompletedWriteOnlyFromPerformMerge, checkWakeLockZeroGapRefresh, checkA11yTargetSizeToken, checkPresetNoOrientation, checkSingleColorSchemeSource, checkUserCopyVocabulary |

> **`@SkipWhenEmpty` is deliberately ABSENT** from `SourceCheckTask` (codex-required). REGEX_REQUIRE / count-sanity / single-file gates must be able to FAIL on empty input — `@SkipWhenEmpty` would skip the action and let those pass silently. Forbid gates pass on empty input because their rule finds no offenders, exactly as today.

---

## Task 1: Scaffold `build-logic` included build + `SourceCheckTask` + empty registry + tests

**Files:**
- Create: `build-logic/settings.gradle.kts`
- Create: `build-logic/build.gradle.kts`
- Create: `build-logic/src/main/kotlin/com/aritr/rova/gradle/SourceFile.kt`
- Create: `build-logic/src/main/kotlin/com/aritr/rova/gradle/SourceCheckTask.kt`
- Create: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules.kt`
- Create: `build-logic/src/test/kotlin/com/aritr/rova/gradle/RegistryTest.kt`
- Modify: `settings.gradle.kts` (app root) — add `includeBuild("build-logic")` in `pluginManagement {}`

**Interfaces:**
- Produces: `com.aritr.rova.gradle.SourceFile(relPath: String, lines: List<String>, text: String)`; `abstract class SourceCheckTask : DefaultTask` with managed props `sources: ConfigurableFileCollection`, `checkId: Property<String>`, `reportBaseDir: DirectoryProperty`, `sentinel: RegularFileProperty`; `object RovaGateRules { val registry: Map<String, (List<SourceFile>) -> String?>; fun run(id: String, files: List<SourceFile>): String? }`.

- [ ] **Step 1: Create `build-logic/settings.gradle.kts`**

```kotlin
rootProject.name = "build-logic"
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

- [ ] **Step 2: Create `build-logic/build.gradle.kts`**

```kotlin
plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 3: Create `SourceFile.kt`**

```kotlin
package com.aritr.rova.gradle

/**
 * Pure, Gradle-free representation of one scanned source file.
 *
 * Invariant (CLAUDE.md "Static-check gate"): rules operate ONLY on this — no
 * java.io.File / Project / rootDir — so the same logic is unit-testable and the
 * @TaskAction never captures the build-script receiver.
 *
 * @param relPath path relative to the report base dir (rootProject), forward/OS
 *   separators identical to the old `file.relativeTo(rootDir).path`.
 * @param lines `file.readLines(UTF_8)` — for the per-line regex/window gates.
 * @param text  `file.readText(UTF_8)` — for content-regex gates (DOT_MATCHES_ALL).
 */
data class SourceFile(
    val relPath: String,
    val lines: List<String>,
    val text: String,
)
```

- [ ] **Step 4: Create `SourceCheckTask.kt`**

```kotlin
package com.aritr.rova.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Config-cache-safe, cacheable host for every Rova static gate.
 *
 * The @TaskAction NEVER references Project/rootDir/file(...). It reads the
 * declared [sources] into pure [SourceFile]s and dispatches to the verbatim-
 * lifted rule in [RovaGateRules] by [checkId]. Behaviour (rule, comment-skip,
 * scope, message) is identical to the in-script gate it replaces.
 *
 * NOTE: no @SkipWhenEmpty — REQUIRE/single-file/count gates must be able to fail
 * on empty input (codex review). Forbid gates pass on empty input via their rule.
 */
@CacheableTask
abstract class SourceCheckTask : DefaultTask() {

    /** Exactly the file set the old gate scanned; fingerprinted for UP-TO-DATE. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Selects the verbatim rule; part of the cache key so two gates with the
     *  same sources never share a result. */
    @get:Input
    abstract val checkId: Property<String>

    /** Base for relPath ONLY (reproduces old `relativeTo(rootDir)`).
     *  @Internal so a checkout path never affects pass/fail or the cache key. */
    @get:Internal
    abstract val reportBaseDir: DirectoryProperty

    /** Success-only sentinel: written ONLY when the rule passes, so a failed gate
     *  is never UP-TO-DATE and a later violating edit re-runs (and re-fails). */
    @get:OutputFile
    abstract val sentinel: RegularFileProperty

    @TaskAction
    fun run() {
        val base = reportBaseDir.get().asFile.toPath()
        // Deterministic order (codex): sort by relPath so multi-offender reports
        // are stable across OS/FS instead of inheriting walkTopDown() order.
        val files = sources.files
            .filter { it.isFile }
            .map { f ->
                val text = f.readText(Charsets.UTF_8)
                SourceFile(
                    relPath = base.relativize(f.toPath()).toString().replace('\\', '/'),
                    lines = f.readLines(Charsets.UTF_8),
                    text = text,
                )
            }
            .sortedBy { it.relPath }

        val message = RovaGateRules.run(checkId.get(), files)
        if (message != null) {
            // Throw BEFORE writing the sentinel: a failed gate must never cache success.
            throw GradleException(message)
        }
        sentinel.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") }
    }
}
```

> **relPath note:** the old code reported `f.relativeTo(rootDir)` whose `.toString()` uses the OS separator. To keep the comparison reportable across families the task normalizes to `/`. If a controller golden-capture shows the old report used `\` on Windows, change `.replace('\\','/')` to match — verify against the Step-0 capture in Task 2. (Most reports are single-offender in the fail-tests, where the path is one token and easy to pin.)

- [ ] **Step 5: Create `RovaGateRules.kt` (empty registry to start)**

```kotlin
package com.aritr.rova.gradle

/**
 * The single home for all 46 gate rules, each LIFTED VERBATIM from the former
 * app/build.gradle.kts doLast bodies. A rule returns the exact message the old
 * gate threw, or null to pass. Split into family files (RovaGateRules_*.kt) as
 * it grows; this object only assembles the immutable registry.
 */
object RovaGateRules {

    /** id -> pure rule. Assembled from family maps; immutable, no mutable state. */
    val registry: Map<String, (List<SourceFile>) -> String?> = buildMap {
        // populated batch-by-batch in later tasks
    }

    fun run(id: String, files: List<SourceFile>): String? {
        val rule = registry[id]
            ?: throw IllegalArgumentException("Unknown gate id: $id")
        return rule(files)
    }
}
```

- [ ] **Step 6: Create `RegistryTest.kt` (registry guardrails — grow per batch)**

```kotlin
package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RegistryTest {

    /** Every registered id resolves; unknown ids throw (never silently pass). */
    @Test
    fun unknownIdThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            RovaGateRules.run("checkDoesNotExist", emptyList())
        }
    }

    /**
     * The registry must hold EXACTLY the 46 gate ids — no missing/extra/typo.
     * Update EXPECTED_IDS as each batch lands; final value is all 46.
     */
    @Test
    fun registryHoldsExactlyTheExpectedIds() {
        assertEquals(EXPECTED_IDS, RovaGateRules.registry.keys.toSortedSet())
    }

    companion object {
        // Grows per batch; Task N (final wiring) asserts all 46.
        val EXPECTED_IDS = sortedSetOf<String>()
    }
}
```

- [ ] **Step 7: Add `includeBuild("build-logic")` to app `settings.gradle.kts`**

In the app-root `settings.gradle.kts`, inside the existing `pluginManagement {}` block (before its `repositories`/`plugins`), add as the first line:

```kotlin
pluginManagement {
    includeBuild("build-logic")
    // ...existing repositories { ... } / plugins { ... } unchanged...
}
```

- [ ] **Step 8: Controller — compile build-logic + run its tests**

Run: `./gradlew -p build-logic build`
Expected: BUILD SUCCESSFUL; `RegistryTest` passes (empty expected-set, unknownIdThrows green).

- [ ] **Step 9: Controller — confirm app still builds, all 46 gates still inline & firing**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Nothing rewired yet — all 46 gates run from their existing inline definitions. `build-logic` is additive.

- [ ] **Step 10: Commit**

```bash
git add build-logic settings.gradle.kts
git commit -m "build(gates): scaffold build-logic with SourceCheckTask + empty rule registry"
```

---

## Task 2: Pilot — migrate `checkSchedulerNoGetService` (full template)

This task establishes the exact per-gate recipe every later batch repeats. The pilot is the canonical REGEX_FORBID gate.

**Files:**
- Modify: `app/build.gradle.kts` (replace the inline `checkSchedulerNoGetService` registration with a typed one)
- Modify: `build-logic/.../RovaGateRules.kt` (add the verbatim rule + register it)
- Modify: `build-logic/.../RegistryTest.kt` (add `"checkSchedulerNoGetService"` to `EXPECTED_IDS`)
- Create: `build-logic/src/test/kotlin/com/aritr/rova/gradle/SchedulerRulesTest.kt`

**Interfaces:**
- Consumes: `SourceCheckTask`, `SourceFile`, `RovaGateRules` from Task 1.
- Produces: `RovaGateRules.registry["checkSchedulerNoGetService"]`.

- [ ] **Step 1: Controller — capture the golden failure message (Step-0 probe)**

Before any edit: in `app/src/main/java/com/aritr/rova/service/scheduler/`, temporarily add `PendingIntent.getService(context, 0, intent, 0)` to a real `.kt` file. Run `./gradlew :app:checkSchedulerNoGetService`. Record the verbatim thrown message + the `path:line: content` offender line. Revert the injection. This is the golden output the rule + fail-test must reproduce.

- [ ] **Step 2: Write the failing rule test (golden pass + fail)**

Create `SchedulerRulesTest.kt`. Use the message captured in Step 1 (the literal below is the inventory text — RECONCILE with the Step-1 capture; the capture wins on any byte difference):

```kotlin
package com.aritr.rova.gradle

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerRulesTest {

    private fun src(relPath: String, body: String) =
        SourceFile(relPath, body.split("\n"), body)

    @Test
    fun passesOnCleanScheduler() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/scheduler/AlarmScheduler.kt",
            "fun schedule() { PendingIntent.getBroadcast(ctx, 0, i, 0) }"
        ))
        assertNull(RovaGateRules.run("checkSchedulerNoGetService", files))
    }

    @Test
    fun failsOnGetService() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/scheduler/AlarmScheduler.kt",
            "fun schedule() { PendingIntent.getService(ctx, 0, i, 0) }"
        ))
        val msg = RovaGateRules.run("checkSchedulerNoGetService", files)
        assertTrue(msg != null && msg.startsWith("PendingIntent.getService is forbidden in alarm scheduler sources"))
    }

    @Test
    fun skipsCommentedGetService() {
        val files = listOf(src(
            "app/src/main/java/com/aritr/rova/service/scheduler/AlarmScheduler.kt",
            "// PendingIntent.getService(ctx, 0, i, 0)"
        ))
        assertNull(RovaGateRules.run("checkSchedulerNoGetService", files))
    }
}
```

- [ ] **Step 3: Run the test — verify it FAILS (rule not registered yet)**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.SchedulerRulesTest"`
Expected: FAIL — `IllegalArgumentException: Unknown gate id: checkSchedulerNoGetService`.

- [ ] **Step 4: Add the verbatim rule to `RovaGateRules`**

Lift the existing `checkSchedulerNoGetService` `doLast` body from `app/build.gradle.kts` into a function, applying ONLY the two mechanical swaps (iterate `files`, use `f.relPath`/`f.lines`). Add to a new family file `RovaGateRules_Scheduler.kt` (or inline for now) and wire into the registry `buildMap`:

```kotlin
// build-logic/.../RovaGateRules_Scheduler.kt
package com.aritr.rova.gradle

internal fun ruleSchedulerNoGetService(files: List<SourceFile>): String? {
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .mapNotNull { f ->
            val hits = f.lines.withIndex().filter { (_, line) ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*")) false
                else /* EXACT predicate lifted from the old gate, verbatim */
                    line.contains("PendingIntent.getService(") ||
                    (Regex("""\bgetService\s*\(""").containsMatchIn(line) && line.contains("PendingIntent"))
            }
            if (hits.isEmpty()) null else f to hits
        }
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, hits) ->
        hits.joinToString("\n") { (i, line) -> "  ${f.relPath}:${i + 1}: ${line.trim()}" }
    }
    return "PendingIntent.getService is forbidden in alarm scheduler sources " +
        "(Android 12+ forbids FGS starts from background contexts). Offenders:\n$report"
}
```

> The predicate and message above are the inventory's transcription. The implementer MUST open the existing gate in `app/build.gradle.kts` and copy the **actual** predicate + message byte-for-byte (the inventory may abbreviate). The Step-1 golden capture is the tiebreaker.

Register it in `RovaGateRules.kt`:

```kotlin
val registry: Map<String, (List<SourceFile>) -> String?> = buildMap {
    put("checkSchedulerNoGetService", ::ruleSchedulerNoGetService)
}
```

- [ ] **Step 5: Add the id to `RegistryTest.EXPECTED_IDS`**

```kotlin
val EXPECTED_IDS = sortedSetOf("checkSchedulerNoGetService")
```

- [ ] **Step 6: Run build-logic tests — verify GREEN**

Run: `./gradlew -p build-logic test`
Expected: `SchedulerRulesTest` + `RegistryTest` all PASS.

- [ ] **Step 7: Replace the inline gate with a typed registration in `app/build.gradle.kts`**

Delete the entire inline `checkSchedulerNoGetService` `tasks.register("...") { ... doLast { ... } }` block and replace with:

```kotlin
val checkSchedulerNoGetService = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkSchedulerNoGetService") {
    group = "verification"
    description = "Forbid PendingIntent.getService in alarm scheduler sources."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/service/scheduler")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkSchedulerNoGetService")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkSchedulerNoGetService.ok"))
}
```

Add the import `import com.aritr.rova.gradle.SourceCheckTask` at the top of `app/build.gradle.kts` if you prefer the unqualified `tasks.register<SourceCheckTask>` form. The existing `afterEvaluate { ... dependsOn(checkSchedulerNoGetService) }` wiring stays untouched (rewired in Task 11).

- [ ] **Step 8: Controller — pass test (clean tree)**

Run: `./gradlew :app:checkSchedulerNoGetService`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Controller — fail test (inject real violation)**

Add `PendingIntent.getService(ctx, 0, i, 0)` to a real scheduler `.kt`. Run `./gradlew :app:checkSchedulerNoGetService`.
Expected: FAIL with the Step-1 golden message + `path:line:` offender. Revert the injection.

- [ ] **Step 10: Controller — UP-TO-DATE + invalidation proof**

Run `./gradlew :app:checkSchedulerNoGetService` twice with no change → second run reports `UP-TO-DATE`. Then `touch` (rewrite) a scanned `.kt` → run again → task RE-RUNS (not UP-TO-DATE). Revert the touch.

- [ ] **Step 11: Controller — full app build still green**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (45 inline gates + 1 typed gate all fire).

- [ ] **Step 12: Commit**

```bash
git add app/build.gradle.kts build-logic
git commit -m "build(gates): migrate checkSchedulerNoGetService to typed SourceCheckTask (pilot)"
```

---

## Tasks 3–10: Migrate the remaining 45 gates in family batches

**Each batch task repeats the Task 2 recipe for its gates.** For every gate in the batch, the implementer/controller performs this exact cycle (do NOT skip any sub-step — the per-gate fail proof is the non-negotiable invariant):

1. **Golden capture** (controller): inject one representative violation in real source, run `./gradlew :app:<gateName>`, record the verbatim message + offender format, revert.
2. **Failing rule test** (subagent, edit-only): add `<Family>RulesTest.kt` cases — `passesOnClean`, `failsOnViolation` asserting the golden message prefix, plus any comment-skip / empty-input case the gate's bucket requires (REGEX_REQUIRE / single-file / count gates MUST include an `failsOnEmptyOrMissingToken` case).
3. **Run test → FAILS** (controller): `./gradlew -p build-logic test --tests "...<Family>RulesTest"` → unknown-id failure.
4. **Lift the verbatim rule** (subagent): copy the existing `doLast` body from `app/build.gradle.kts` into `RovaGateRules_<Family>.kt` with ONLY the two mechanical swaps; register it; add the id to `EXPECTED_IDS`.
5. **Run test → GREEN** (controller): `./gradlew -p build-logic test`.
6. **Typed registration** (subagent): replace the inline gate in `app/build.gradle.kts` with `tasks.register<SourceCheckTask>("<gateName>") { sources.from(<exact same scanned set>); checkId.set("<gateName>"); reportBaseDir.set(rootProject.layout.projectDirectory); sentinel.set(layout.buildDirectory.file("reports/rova-checks/<gateName>.ok")) }`. The `sources.from(...)` must declare **exactly** the set the old `inputs.dir`/`inputs.file`/`inputs.files`/`fileTree` covered (see per-gate scope below).
7. **RED/GREEN matrix per gate** (controller): pass (clean) → green; fail (inject golden violation) → RED with golden message → revert; UP-TO-DATE twice → UP-TO-DATE; edit scanned source → re-runs.
8. **Full build** (controller): `./gradlew :app:assembleDebug` green.
9. **Commit** the batch.

**Per-gate `sources.from(...)` wiring** (reproduce the exact old scope — broad subtree + verbatim in-rule filename/path filtering, OR specific files):

| Gate | `sources.from(...)` declares |
|---|---|
| Single-subtree `.kt`+`.java` (scheduler/stop/recovery/export/etc.) | `layout.projectDirectory.dir("<old dir>").asFileTree.matching { include("**/*.kt","**/*.java") }` |
| Whole-app `.kt`+`.java` (`src/main/java/com/aritr/rova`) | `layout.projectDirectory.dir("src/main/java/com/aritr/rova").asFileTree.matching { include("**/*.kt","**/*.java") }` |
| `.kt`-only `fileTree("src/main/java")` gates (checkLibraryNoManifestWrite, checkNoLegacyModeStrings, checkSetTargetRotationBoundaryOnly, checkFrontBackCapabilityGated) | `layout.projectDirectory.dir("src/main/java").asFileTree.matching { include("**/*.kt") }` — path-prefix narrowing stays **inside the rule** verbatim |
| Single-file gates (checkUserStoppedBeforeMerge, checkExportCleanupPredicate, checkWakeLockHeldRefresh, checkWakeLockZeroGapRefresh, checkVaultExporterNoPublicPublish, checkLocaleConfigNoPseudolocale) | `layout.projectDirectory.file("<exact path>")` |
| Two-file gates (checkRecoveryNoDeletion = recovery dir + RovaApp.kt; checkWakeLockBoundedAcquire = service + WakeLockPolicy.kt; checkPresetNoOrientation = RovaSettings.kt + BuiltInPresets.kt) | `.from(<file/dir A>); sources.from(<file/dir B>)` |
| XML gates (checkUserCopyVocabulary = strings.xml + values-es/strings.xml; checkLocaleConfigNoPseudolocale = locales_config.xml) | `.from(layout.projectDirectory.file("src/main/res/.../*.xml"))` — rule uses `f.text` |

> Where the old gate used a broad `inputs.dir` then filtered by `file.name`/canonical path inside `doLast` (e.g. checkScanTriggerSingleSite excludes RovaApp.kt, checkPendingFdModeIsRW keeps only `Tier1*.kt`, checkRecordSurfaceNoBlur keeps `RecordScreen.kt`/`RecordChrome.kt`), keep that exact filter **in the rule** operating on `f.relPath`, and declare the same broad subtree in `sources`. This guarantees the fingerprinted set == the scanned set and the filtering is byte-identical.

### Task 3: Batch A — scheduler/stop/recovery forbid (5 gates)
checkStopNoGetService, checkScheduleReceiverNoFgsStart, checkRecoveryNoDeletion, checkRecoverySegmentRegex, checkScanTriggerSingleSite. Family file `RovaGateRules_Recovery.kt`. Commit: `build(gates): migrate scheduler/recovery forbid batch to SourceCheckTask`.

### Task 4: Batch B — recovery/terminal structural (5 gates)
checkRecoveryReceiverCounter, checkAtomicTerminalWriteForbiddenPair, checkExternalRootShared, checkAudioModeFgsTypeMatch, checkFGSStartGuarded. Structural: lift the multi-line windows / counters / opt-out markers VERBATIM. Tests must cover the window edge (e.g. FGS guard 60-line lookahead present vs absent) and the file-level opt-out marker path. Commit: `build(gates): migrate recovery/terminal structural batch`.

### Task 5: Batch C — terminal-ordering + export forbid (6 gates)
checkUserStoppedBeforeMerge, checkExportTierReadTolerant, checkScanFileBoundedWait, checkPendingFdModeIsRW, checkExportNoCopyToPublicMovies, checkExportCleanupPredicate. (checkUserStoppedBeforeMerge + checkExportCleanupPredicate are single-file; checkExportCleanupPredicate is REGEX_REQUIRE — include empty-input-fails test.) Commit: `build(gates): migrate terminal-ordering + export-forbid batch`.

### Task 6: Batch D — export pending-visibility structural (6 gates)
checkExportIsPendingGuarded, checkExportSetIncludePendingGuarded, checkExportQueryArgMatchPendingGuarded, checkExportPendingVisibilityOnQuery, checkExportPipelineSingleEntry, checkSafTargetCommittedBeforeStream, checkCompletedWriteOnlyFromPerformMerge. (7 gates — large; split into D1/D2 if a reviewer prefers.) These carry ±30-line SDK proximity, exactly-one-call-site, function-body-boundary inference, and `/*` block-comment skipping — lift verbatim, test the proximity window both sides. Commit: `build(gates): migrate export pending-visibility structural batch`.

### Task 7: Batch E — wakelock (3 gates)
checkWakeLockBoundedAcquire (2-file forbid), checkWakeLockHeldRefresh (single-file REQUIRE — empty/missing-token-fails test), checkWakeLockZeroGapRefresh (single-file 20-line window structural). Family `RovaGateRules_WakeLock.kt`. Commit: `build(gates): migrate wakelock batch`.

### Task 8: Batch F — i18n / locale / a11y (6 gates)
checkNoHardcodedUiStrings, checkLocaleConfigNoPseudolocale (single XML file — uses `f.text`/lines, scans comments too: NO comment-skip), checkUserCopyVocabulary (XML, `f.text` DOT_MATCHES_ALL + the count-sanity throw — test the count-mismatch path AND that it receives the xml file, since the task does NOT filter by extension), checkA11yAnimationGated (REQUIRE), checkA11yClickableHasRole (REQUIRE window), checkA11yTargetSizeToken (structural token set + block-comment state). REQUIRE gates get empty-input-fails tests. Commit: `build(gates): migrate i18n/locale/a11y batch`.

### Task 9: Batch G — mode / rotation / preset (4 gates)
checkPresetNoOrientation (2-file structural), checkNoLegacyModeStrings, checkSetTargetRotationBoundaryOnly (NO comment-skip — scans comments), checkFrontBackCapabilityGated. The three `fileTree` gates declare `src/main/java` `**/*.kt` and keep path-suffix exclusion in-rule. Commit: `build(gates): migrate mode/rotation/preset batch`.

### Task 10: Batch H — icon / theme / glass / record-chrome / library / vault (9 gates)
checkVaultExporterNoPublicPublish (single-file, hardcoded report filename), checkRecordSurfaceNoBlur, checkGlassSurfaceRoleUsage, checkRecordChromeLockSingleSite (block-comment strip), checkLibraryNoManifestWrite, checkSemanticIconNoRawAlpha (3-line wrapped-call window + canonical allowlist), checkStatusColorLocked, checkRovaGlyphHome (stripComments then whole-file ImageVector.Builder), checkSingleColorSchemeSource (balanced-paren + comment/string stripping). Family files `RovaGateRules_Icon.kt` / `RovaGateRules_Glass.kt`. Commit: `build(gates): migrate icon/theme/glass/library/vault batch`.

> After Task 10: all 46 gates are typed `SourceCheckTask` registrations; `RegistryTest.EXPECTED_IDS` should now equal all 46 ids. The `registryHoldsExactlyTheExpectedIds` test is the completeness backstop.

---

## Task 11: Rewire `preBuild` without `afterEvaluate` (config-cache-safe)

**Files:**
- Modify: `app/build.gradle.kts` (replace the `afterEvaluate { tasks.matching { it.name == "preBuild" } ... }` block)

- [ ] **Step 1: Replace the wiring block**

Replace the entire `afterEvaluate { ... }` preBuild-wiring block with:

```kotlin
val rovaChecks = listOf(
    checkSchedulerNoGetService, checkScheduleReceiverNoFgsStart, checkStopNoGetService,
    checkRecoveryNoDeletion, checkRecoverySegmentRegex, checkScanTriggerSingleSite,
    checkRecoveryReceiverCounter, checkAtomicTerminalWriteForbiddenPair, checkExternalRootShared,
    checkAudioModeFgsTypeMatch, checkFGSStartGuarded, checkUserStoppedBeforeMerge,
    checkExportTierReadTolerant, checkScanFileBoundedWait, checkPendingFdModeIsRW,
    checkExportIsPendingGuarded, checkExportSetIncludePendingGuarded, checkExportQueryArgMatchPendingGuarded,
    checkExportPendingVisibilityOnQuery, checkExportCleanupPredicate, checkExportNoCopyToPublicMovies,
    checkExportPipelineSingleEntry, checkSafTargetCommittedBeforeStream, checkCompletedWriteOnlyFromPerformMerge,
    checkWakeLockBoundedAcquire, checkWakeLockHeldRefresh, checkWakeLockZeroGapRefresh,
    checkNoHardcodedUiStrings, checkLocaleConfigNoPseudolocale, checkA11yAnimationGated,
    checkA11yClickableHasRole, checkA11yTargetSizeToken, checkPresetNoOrientation,
    checkLibraryNoManifestWrite, checkVaultExporterNoPublicPublish, checkRecordSurfaceNoBlur,
    checkGlassSurfaceRoleUsage, checkNoLegacyModeStrings, checkSetTargetRotationBoundaryOnly,
    checkFrontBackCapabilityGated, checkUserCopyVocabulary, checkRecordChromeLockSingleSite,
    checkSemanticIconNoRawAlpha, checkStatusColorLocked, checkRovaGlyphHome,
    checkSingleColorSchemeSource,
)
tasks.matching { it.name == "preBuild" }.configureEach {
    rovaChecks.forEach { dependsOn(it) }
}
```

(If `preBuild` is only registered by AGP after plugin application, wrap in `pluginManager.withPlugin("com.android.application") { ... }` per spec §2.5. Verify which is needed by the Step-2 result.)

- [ ] **Step 2: Controller — full suite still gates preBuild**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all 46 gates run on preBuild. Then inject one violation (any gate) → `./gradlew :app:assembleDebug` FAILS at the preBuild phase → revert.

- [ ] **Step 3: Controller — `:app:testDebugUnitTest` green**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 1241 baseline / 0-0-0 (config-cache still OFF at this point).

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build(gates): wire 46 typed gates into preBuild without afterEvaluate"
```

---

## Task 12: Flip config-cache ON + store/reuse + no-regression proof

**Files:**
- Modify: `gradle.properties` (set `org.gradle.configuration-cache=true`, update the explanatory NOTE to "now enabled")

- [ ] **Step 1: Controller — baseline OFF run (regression guard)**

With config-cache still OFF: `./gradlew :app:assembleDebug` → green. Record warm wall time.

- [ ] **Step 2: Enable config-cache**

In `gradle.properties`, set `org.gradle.configuration-cache=true` and rewrite the NOTE block from "OFF because gates capture script refs" to past tense / "enabled — gates are config-cache-safe typed SourceCheckTasks in build-logic".

- [ ] **Step 3: Controller — STORE then REUSE proof**

Delete the config-cache dir (task-intrinsic, allowed): `rm -rf .gradle/configuration-cache`. Run `./gradlew :app:assembleDebug` → first run STORES ("Configuration cache entry stored."). Run it AGAIN with no change → second run prints **"Reusing configuration cache."**, no `this$0 is null`, BUILD SUCCESSFUL, all 46 gates wired.

- [ ] **Step 4: Controller — zero serialization problems**

Run: `./gradlew :app:assembleDebug --configuration-cache-problems=fail`
Expected: BUILD SUCCESSFUL, 0 problems. (If any gate still captures Project, this fails here — fix that gate's registration to use only `layout.*` / String / FileCollection.)

- [ ] **Step 5: Controller — gates STILL fire under config-cache (spot the silent-skip)**

With config-cache ON and a warm cache: inject one violation per a SAMPLED set spanning each bucket (≥1 forbid, ≥1 require, ≥1 structural, ≥1 XML) → `./gradlew :app:assembleDebug` must FAIL each time → revert. (The exhaustive 46-gate RED/GREEN proof already ran per-gate in Tasks 2–10 with config-cache OFF; this confirms config-cache + UP-TO-DATE doesn't suppress firing.)

- [ ] **Step 6: Controller — invalidation under config-cache**

Edit a real scanned source that a gate watches → confirm that gate re-runs (not UP-TO-DATE from stale cache) and a real violation in it still fails the build. Revert.

- [ ] **Step 7: Controller — tests green under config-cache**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 1241 / 0-0-0, "Reusing configuration cache." on a warm second run.

- [ ] **Step 8: Commit**

```bash
git add gradle.properties
git commit -m "build: enable org.gradle.configuration-cache (46 gates now config-cache-safe)"
```

---

## Task 13: Docs + memory + APK install sanity + branch finish

**Files:**
- Modify: `docs/BACKLOG.md` (line ~192 → mark "unblock the Gradle configuration cache" DONE)
- Modify: `CLAUDE.md` ("Static-check gate" section: note gates now live in `build-logic` as `@CacheableTask SourceCheckTask` driven by `RovaGateRules`; count unchanged at 46; config-cache now ON)
- Modify: memory `project_build_env_perf.md` (config-cache ON; gates are typed/cacheable; remove the "config-cache OFF — gates capture script refs" note)

- [ ] **Step 1: Update BACKLOG.md** — flip line 192 to done with PR ref placeholder.

- [ ] **Step 2: Update CLAUDE.md** — amend the "Static-check gate (load-bearing)" section: gates are now `SourceCheckTask` registrations in `app/build.gradle.kts` backed by verbatim pure rules in `build-logic/.../RovaGateRules*.kt`; adding a new invariant is now: ADR clause → new `RovaGateRules` function + golden test → `tasks.register<SourceCheckTask>` + `rovaChecks` list + preBuild. Gate count 46.

- [ ] **Step 3: Update memory** `project_build_env_perf.md` + add MEMORY.md pointer line.

- [ ] **Step 4: Controller — final clean full proof**

Run: `./gradlew :app:assembleDebug` (warm, config-cache ON) → green, "Reusing configuration cache." on 2nd run. `./gradlew :app:testDebugUnitTest` → 1241/0-0-0. `./gradlew -p build-logic test` → all golden gate tests green.

- [ ] **Step 5: Controller — APK install sanity (build-infra has no app behaviour, but confirm the APK still installs)**

Install the assembleDebug APK on RZCYA1VBQ2H: `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Expected: `Success`. (No device smoke beyond install — no app behaviour changed.)

- [ ] **Step 6: Commit docs**

```bash
git add docs/BACKLOG.md CLAUDE.md
git commit -m "docs: config-cache unblocked — gates are cacheable SourceCheckTasks; backlog line 192 done"
```

- [ ] **Step 7:** STOP. Report the full RED/GREEN matrix + config-cache store/reuse evidence to the owner. Push/PR/merge ONLY on explicit owner GO (PowerShell git; finishing-a-development-branch skill).

---

## Self-Review

**Spec coverage:**
- §1 problem (Project capture + no outputs) → Task 1 `SourceCheckTask` removes both. ✔
- §2.1–2.2 typed cacheable task, sentinel-on-success, `@Internal reportBaseDir`, `@Input` rule selector → Task 1. ✔ (RegexCheckTask base generalized to one `SourceCheckTask` + pure registry — codex-approved refinement; rationale: ~18 structural gates make per-subclass boilerplate the bigger risk.)
- §2.3 before/after migration template → Task 2 pilot (full). ✔
- §2.4 structural gates → Batches B/D/E/F/G/H lift verbatim (no `@CacheableTask` inheritance issue: single concrete task). ✔
- §2.5 preBuild without afterEvaluate → Task 11. ✔
- §3 build-logic included build → Task 1. ✔
- §4 step-by-step with per-gate fail+pass+UP-TO-DATE → Tasks 2–10 recipe. ✔
- §5 behaviour-parity (rule/trigger/non-trigger/message/scope; FileTree==walkTopDown set) → per-gate `sources.from` table + in-rule verbatim filter + golden tests. ✔
- §6.1 zero-code quick win (stop the recovery dance) → already obsolete per memory; not re-introduced. ✔
- §6.4 risks (scope-mismatch, @SkipWhenEmpty, @CacheableTask-not-inherited) → addressed: NO @SkipWhenEmpty (codex), single task class, declared==scanned. ✔
- §7 codex refinements → folded + extended by the new codex review (no SkipWhenEmpty, no in-task ext filter, deterministic order, UTF-8, registry-completeness test). ✔
- §8 payoff/measurement → Task 12 store/reuse + `--configuration-cache-problems=fail`. ✔

**Deviations from spec (both STRENGTHEN the invariant, neither changes WHAT a gate enforces — no ADR amend):**
1. One `SourceCheckTask` + pure `RovaGateRules` registry instead of `RegexCheckTask` base + ~18 bespoke subclasses. Codex-approved; better satisfies pure-helper+JVM-test requirement; lower boilerplate-bug surface.
2. No `@SkipWhenEmpty` (codex-required): REQUIRE/single-file/count gates must be able to fail on empty input.
3. Task does NOT filter by `.kt/.java` extension (codex-required): XML gates would be silently disabled; per-gate `sources` declaration does the filtering.

**Placeholder scan:** the only "fill from existing source" instruction is "lift the verbatim `doLast` body" — this is intentional and safer than re-transcribing 46 rule bodies into the plan (transcription drift would itself be a regression). The pilot (Task 2) shows the complete coded transform; every later gate is the same mechanical transform against the cited existing source, pinned by a golden test. Scope wiring, sentinel paths, message-assertion prefixes, and the verification matrix are all concrete.

**Type consistency:** `SourceFile(relPath, lines, text)`, `SourceCheckTask{sources,checkId,reportBaseDir,sentinel}`, `RovaGateRules.run(id, files): String?`, `registry: Map<String,(List<SourceFile>)->String?>` — names consistent across Tasks 1, 2, 3–10, 11.

---

## Execution Handoff

Subagent-driven (controller runs all gradle/tests/commits; subagents edit-only), per the task's standing constraints.
