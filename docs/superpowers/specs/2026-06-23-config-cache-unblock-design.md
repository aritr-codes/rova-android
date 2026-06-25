# Config-Cache Unblock — Gate Refactor Design Spec

**Date:** 2026-06-23
**Status:** Proposed (design only — zero source touched by this doc)
**Owner item:** `docs/BACKLOG.md` line 192 — "Build-speed: unblock the Gradle configuration cache (gate refactor)" — **P2**
**Scope:** Make the 46 `check*` gates config-cache-serializable **and** UP-TO-DATE/build-cacheable, so `org.gradle.configuration-cache=true` can be set without breaking any gate, and behaviour stays byte-identical (each gate still fails on exactly the violation it catches today).
**Peer-reviewed:** codex (gpt-5.2) — design confirmed; refinements folded in (see §7).

> **Non-negotiable invariant (CLAUDE.md "Static-check gate (load-bearing)"):** this spec changes *how* a gate is implemented, never *what* it enforces. Each gate's ADR-clause enforcement, its regex/AST rule, its comment-skipping, and its failure message stay semantically identical. "Do not edit a check away to make it green" applies here too — a refactor that lets a real violation slip through is a regression, not a speed-up.

---

## 1. Problem — precisely why each gate breaks config-cache

### 1.1 The current gate shape

All 46 gates are registered inline in `app/build.gradle.kts` with `tasks.register("checkX") { ... }` and do their scan inside a `doLast {}` action. The canonical example (the template the rest follow):

```kotlin
// app/build.gradle.kts
val checkSchedulerNoGetService = tasks.register("checkSchedulerNoGetService") {
    group = "verification"
    description = "Forbid PendingIntent.getService in alarm scheduler sources."
    val schedulerDir = file("src/main/java/com/aritr/rova/service/scheduler")   // (A) Project.file(...)
    inputs.dir(schedulerDir).withPropertyName("schedulerSources")
    doLast {
        if (!schedulerDir.exists()) {
            throw GradleException("Scheduler dir missing: $schedulerDir")
        }
        val offenders = schedulerDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .mapNotNull { f ->
                val hits = f.readLines().withIndex().filter { (_, line) -> /* regex + comment-skip */ }
                if (hits.isEmpty()) null else f to hits
            }.toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) -> "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" }  // (B) Project.rootDir
            }
            throw GradleException("PendingIntent.getService is forbidden ... Offenders:\n$report")
        }
    }
}
```

Measured across all 46 gates (parsed from `app/build.gradle.kts`):

| Trait | Count / 46 |
|---|---|
| Throw `GradleException` on violation | 46 |
| Use `file(...)` (i.e. `Project.file`) | 42 |
| Reference `rootDir` inside `doLast` | 39 |
| Declare `inputs.dir(...)` | 33 |
| Declare `inputs.files(...)` | 1 |
| **Declare any `outputs.*`** | **0** |

### 1.2 Why it fails config-cache serialization (not just warns)

The Gradle configuration cache serializes the *task graph and each task's state* at the end of configuration, then re-runs execution from the deserialized graph — **without** a live `Project` object. Gradle's config-cache rules explicitly forbid touching `Project` (and `project.rootDir`, `project.file(...)`) at execution time.

In every gate above, the `doLast {}` lambda is a closure compiled against the build script's synthetic class (`CompiledKotlinBuildScript`). The closure captures the script receiver (the `Project`) because:

- **(A)** `val schedulerDir = file(...)` resolves at *configuration* time so the `File` itself is fine — but several gates also call `file(...)` / reference `rootDir` *inside* `doLast`, and even where the `File` is hoisted, the failure-report path `f.relativeTo(rootDir)` **(B)** reads `rootDir` off the script receiver at *execution* time.
- The captured outer reference (`this$0`) is the build-script instance, which the config cache cannot serialize.

At execution against a reused config-cache entry, that captured `this$0` is `null`, producing the exact runtime failure documented in `gradle.properties` and `docs/BACKLOG.md`:

```
CompiledKotlinBuildScript ... this$0 is null
```

This is a **hard execution failure**, not a warning — which is why config-cache is currently OFF.

### 1.3 The orthogonal UP-TO-DATE problem

Independently of serialization: **zero of the 46 gates declare an `output`.** A Gradle task with inputs but no outputs is **never UP-TO-DATE and never build-cacheable** — it re-executes on every build. Today the gates are cheap (file walks + regex), so this is tolerable, but:

- It is a prerequisite cleanup for config-cache value: even with config-cache on, an output-less task still re-runs every build (config-cache caches the *graph*, not the gate result).
- Adding an `outputs.file(...)` sentinel is what makes a gate go UP-TO-DATE when its inputs are unchanged, and `@CacheableTask` is what lets the result be pulled from the build cache across branches/CI.

**Summary of the two defects:** (1) script-receiver capture in `doLast` ⇒ config-cache serialization failure; (2) no declared outputs ⇒ no UP-TO-DATE/cacheability. The target design fixes both at once by moving each gate into a typed `@CacheableTask`.

---

## 2. Target design

### 2.1 Principle

Replace each inline `tasks.register("checkX") { doLast { ... } }` with a **typed task class** whose:

- **inputs** are declared as managed properties (`ConfigurableFileCollection` for the scanned sources, `@Input` for every pass/fail-affecting rule parameter), and
- **output** is a sentinel marker file (`@OutputFile`), written **only on success**, so the task goes UP-TO-DATE when inputs are unchanged and re-runs (and re-fails) whenever a watched source changes.

The `@TaskAction` body never references `Project`, `rootDir`, or `file(...)`. The only directory it needs for *relative-path failure messages* is injected as an `@Internal DirectoryProperty` (a checkout path must not affect the cache key — see §7).

### 2.2 The base task — `RegexCheckTask`

~40 of the 46 gates are the same shape: "scan a source subtree, flag lines matching (or failing to match) a regex, skipping `//` and `*` comment lines, throw with a relative-path report." These collapse to **one** parameterized base class. The ~6 structural/AST gates (e.g. `checkWakeLockZeroGapRefresh`, `checkExportPipelineSingleEntry`, lookahead-window structural checks) become **bespoke subclasses** that override only the rule body.

```kotlin
// build-logic/src/main/kotlin/com/aritr/rova/gradle/RegexCheckTask.kt
package com.aritr.rova.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

/**
 * Cacheable, config-cache-safe base for the comment-skipping regex gates.
 * Subclass for structural/AST gates by overriding [findViolations].
 *
 * Invariant (CLAUDE.md "Static-check gate"): this changes only the *mechanism*.
 * The rule, comment-skip, and failure message must remain behaviour-identical
 * to the in-script gate it replaces.
 */
@CacheableTask
abstract class RegexCheckTask : DefaultTask() {

    /** The exact file set whose fingerprint gates UP-TO-DATE — only what we scan. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:SkipWhenEmpty            // tolerate an absent/empty subtree the way the rule expects
    abstract val sources: ConfigurableFileCollection

    /** Pattern that must NOT match (forbid mode) — every pass/fail input is @Input. */
    @get:Input
    abstract val forbiddenPattern: Property<String>

    /** Human-readable message; relative reporting only — does not affect cache key. */
    @get:Input
    abstract val violationMessage: Property<String>

    /** Lines beginning with any of these (after trim) are skipped. */
    @get:Input
    abstract val commentPrefixes: ListProperty<String>

    /** Base dir for relative-path messages ONLY. @Internal so checkout path != cache key. */
    @get:Internal
    abstract val reportBaseDir: DirectoryProperty

    /** Success sentinel — presence + input-fingerprint match = UP-TO-DATE. */
    @get:OutputFile
    abstract val sentinel: RegularFileProperty

    @TaskAction
    fun run() {
        val rx = Regex(forbiddenPattern.get())
        val prefixes = commentPrefixes.get()
        val base = reportBaseDir.get().asFile

        val offenders = sources.files
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .mapNotNull { f ->
                val hits = f.readLines().withIndex().filter { (_, line) ->
                    val t = line.trimStart()
                    if (prefixes.any { t.startsWith(it) }) false else rx.containsMatchIn(line)
                }
                if (hits.isEmpty()) null else f to hits
            }

        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(base)}:${i + 1}: ${line.trim()}"
                }
            }
            // THROW BEFORE writing the sentinel — a failed gate must never be UP-TO-DATE.
            throw GradleException("${violationMessage.get()}\nOffenders:\n$report")
        }

        // success only: write the marker so unchanged inputs go UP-TO-DATE next build.
        sentinel.get().asFile.apply { parentFile.mkdirs(); writeText("ok\n") }
    }
}
```

### 2.3 Before / after for ONE gate — the migration template

**BEFORE** (`app/build.gradle.kts`, inline — captures `rootDir`, no output):

```kotlin
val checkSchedulerNoGetService = tasks.register("checkSchedulerNoGetService") {
    group = "verification"
    description = "Forbid PendingIntent.getService in alarm scheduler sources."
    val schedulerDir = file("src/main/java/com/aritr/rova/service/scheduler")
    inputs.dir(schedulerDir).withPropertyName("schedulerSources")
    doLast {
        if (!schedulerDir.exists()) throw GradleException("Scheduler dir missing: $schedulerDir")
        val offenders = schedulerDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .mapNotNull { f ->
                val hits = f.readLines().withIndex().filter { (_, line) ->
                    val t = line.trimStart()
                    if (t.startsWith("//") || t.startsWith("*")) false
                    else Regex("""PendingIntent\s*\.\s*getService\b""").containsMatchIn(line)
                }
                if (hits.isEmpty()) null else f to hits
            }.toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) -> "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" }
            }
            throw GradleException("PendingIntent.getService is forbidden ... Offenders:\n$report")
        }
    }
}
```

**AFTER** (`app/build.gradle.kts`, typed registration — no Project capture, has output):

```kotlin
val checkSchedulerNoGetService = tasks.register<RegexCheckTask>("checkSchedulerNoGetService") {
    group = "verification"
    description = "Forbid PendingIntent.getService in alarm scheduler sources."
    // Declare the scanned set as the fingerprinted input (only .kt/.java under the subtree).
    sources.from(layout.projectDirectory.dir("src/main/java/com/aritr/rova/service/scheduler")
        .asFileTree.matching { include("**/*.kt", "**/*.java") })
    forbiddenPattern.set("""PendingIntent\s*\.\s*getService\b""")
    commentPrefixes.set(listOf("//", "*"))
    violationMessage.set("PendingIntent.getService is forbidden in alarm scheduler sources " +
        "(Android 12+ forbids FGS starts from background contexts).")
    reportBaseDir.set(rootProject.layout.projectDirectory)   // resolved as Provider at config time
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkSchedulerNoGetService.ok"))
}
```

Notes on the after-form:
- `layout.projectDirectory` / `layout.buildDirectory` / `rootProject.layout.projectDirectory` are `Provider`-based and config-cache-safe; they are evaluated at configuration time and stored as serializable file/provider state — **no `Project` reference survives into execution.**
- The old `if (!dir.exists()) throw "dir missing"` guard is replaced by `@SkipWhenEmpty` semantics: an empty/absent set is "no offenders," which is the same pass result as today (the dirs always exist on master, so behaviour is unchanged; if a future move deletes the dir the gate becomes a no-op rather than a hard error — acceptable, and documented).
- The relative-path report uses the injected `reportBaseDir` so messages are byte-identical to today's `f.relativeTo(rootDir)`.

### 2.4 Bespoke structural gates

For the ~6 lookahead/structural gates (e.g. `checkWakeLockHeldRefresh`, `checkWakeLockZeroGapRefresh`, `checkExportPipelineSingleEntry`, `checkUserStoppedBeforeMerge`), subclass:

```kotlin
@CacheableTask                       // NOT inherited — must re-annotate each concrete subclass (§7)
abstract class WakeLockZeroGapRefreshCheckTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection
    @get:Internal abstract val reportBaseDir: DirectoryProperty
    @get:OutputFile abstract val sentinel: RegularFileProperty
    @TaskAction fun run() { /* exact structural logic lifted verbatim from the doLast */ }
}
```

The logic is *lifted verbatim* from the existing `doLast` — only the `File`/`rootDir` access is swapped for `sources` + `reportBaseDir`.

### 2.5 Wiring into `preBuild` (config-cache-safe)

Keep the existing convention but drop the `afterEvaluate` wrapper (codex: `afterEvaluate` is unnecessary here and makes ordering fragile). Use `pluginManager.withPlugin` so it fires after AGP registers `preBuild`:

```kotlin
val rovaChecks = listOf(checkSchedulerNoGetService, checkStopNoGetService, /* ...all 46... */)
pluginManager.withPlugin("com.android.application") {
    tasks.matching { it.name == "preBuild" }.configureEach {
        rovaChecks.forEach { dependsOn(it) }
    }
}
```

`tasks.matching{}.configureEach{}` is itself config-cache-compatible during normal configuration; only the `afterEvaluate` wrapper is removed.

---

## 3. build-logic included build vs in-place — recommendation

| Option | Pros | Cons |
|---|---|---|
| **A. `buildSrc/`** | Zero `settings.gradle.kts` change; auto on build-script classpath; lowest first-move friction. | Any edit in `buildSrc` invalidates the *whole* build's configuration cache broadly; conventionally discouraged for large shared logic on modern Gradle. |
| **B. `build-logic/` included build** (recommended) | Clean module boundary; convention-plugin pattern scales; edits invalidate only the included build, not the whole app config; idiomatic on AGP 9 / Gradle 9.4. | One-time `settings.gradle.kts` `includeBuild("build-logic")` + a tiny `build-logic/build.gradle.kts` (`kotlin-dsl` plugin) + `build-logic/settings.gradle.kts`. |
| **C. in-script, drop Project capture only** | Smallest diff; can flip config-cache ON fast (see §6 intermediate win). | Still output-less unless you also add `outputs.file(...)`; `doLast` task actions remain a poorer fit for `@CacheableTask`; logic stays in the 2900-line build script. |

**Recommendation: B (`build-logic/` included build) for the final state, with C as a validated intermediate checkpoint.**

Reasoning:
- We are already touching all 46 gates; the marginal cost of a proper module is small and the payoff (testable, cacheable, isolated task classes out of the giant build script) is large.
- `build-logic` scopes config-cache invalidation to the plugin module — `buildSrc` would re-invalidate app configuration on every gate tweak, which is exactly the churn we want to avoid for a load-bearing 46-gate suite.
- AGP 9 / Gradle 9.4 fully support `kotlin-dsl` precompiled/programmatic plugins in an included build; no version blocker.

Minimal `build-logic` skeleton:

```
build-logic/
  settings.gradle.kts            # rootProject.name = "build-logic"; dependencyResolutionManagement
  build.gradle.kts               # plugins { `kotlin-dsl` }
  src/main/kotlin/com/aritr/rova/gradle/
      RegexCheckTask.kt
      <bespoke>CheckTask.kt ...
      RovaChecksPlugin.kt        # optional: registers + wires all 46 + the preBuild dependsOn
```

`settings.gradle.kts` (app, one line added):
```kotlin
pluginManagement { includeBuild("build-logic") }   // before the existing plugins {} block
```

Optionally collapse the per-gate `tasks.register<...>` blocks into a `RovaChecksPlugin` applied via `plugins { id("com.aritr.rova.checks") }`, so `app/build.gradle.kts` shrinks dramatically. This is optional polish; the task-class extraction alone delivers the cache win.

---

## 4. Migration plan — keep all 46 green & behaviour-identical at every step

The cardinal rule (this repo NEVER weakens a gate to make builds pass): **a gate must still fail on exactly the violation it catches today.** Each step below is verified by a *fail test* (introduce the violation, confirm the gate throws) plus a *pass test* (clean tree, gate passes) before moving on.

**Step 0 — Baseline capture (no code change).**
- Record current `:app:preBuild` wall time (warm + cold) for the payoff measurement (§6).
- For each of the 46 gates, capture today's exact failure message + line-number format (these are the golden output the refactor must reproduce). A throwaway local probe: temporarily inject one violation per rule, capture the thrown message, revert.

**Step 1 — Scaffold `build-logic` (additive, no gate moved yet).**
- Create `build-logic/` (settings + `kotlin-dsl` build) and add `includeBuild("build-logic")` to app `settings.gradle.kts`.
- Add `RegexCheckTask` (empty rule wiring, unused).
- Verify: `:app:assembleDebug` still GREEN, all 46 still run from the in-script definitions (nothing rewired yet).

**Step 2 — Convert ONE pilot gate (`checkSchedulerNoGetService`).**
- Rewrite it as `tasks.register<RegexCheckTask>(...)` per §2.3. Leave the other 45 inline.
- Verify behaviour parity:
  - **pass:** clean tree → gate passes; `:app:checkSchedulerNoGetService` succeeds.
  - **fail:** add `PendingIntent.getService(...)` in `service/scheduler/` → gate throws with the same message + `path:line:` format as the golden capture.
  - **UP-TO-DATE:** run the task twice with no source change → second run reports `UP-TO-DATE`.
  - **invalidation:** touch a scanned `.kt` → task re-runs.

**Step 3 — Convert the regex-shaped cluster (~40 gates) in small batches.**
- Migrate ~8–10 per batch, each batch verified pass+fail+UP-TO-DATE before the next.
- Gates that scan the same subtree can share `sources` wiring; each keeps its own `forbiddenPattern` / `violationMessage` / unique `sentinel`.
- A few gates assert a token *must be present* (e.g. allow-list "this file must contain all four tokens") rather than *must be absent* — for those add a `requiredTokens: ListProperty<String>` mode to the base (or a `RequirePresenceCheckTask` sibling). Same `@CacheableTask` skeleton.

**Step 4 — Convert the ~6 bespoke structural/AST gates.**
- One subclass each, logic lifted verbatim, re-annotated `@CacheableTask` (not inherited — §7).
- Same pass/fail/UP-TO-DATE verification.

**Step 5 — Rewire `preBuild` without `afterEvaluate` (§2.5).**
- Verify the full suite still gates `preBuild`: a violation anywhere fails `:app:assembleDebug` at the preBuild phase, identical to today.

**Step 6 — Flip config-cache ON.**
- Set `org.gradle.configuration-cache=true` in `gradle.properties` (and update the explanatory NOTE block to past tense / "now enabled").
- Verify: run `:app:assembleDebug` **twice**; second run prints "Reusing configuration cache." with no `this$0 is null` and no serialization problems report.
- Run `./gradlew :app:assembleDebug --configuration-cache-problems=fail` to assert zero problems.
- Full `:app:testDebugUnitTest` GREEN (1241 baseline / 0-0-0).

**Step 7 — Update docs + memory.**
- `gradle.properties` NOTE, `docs/BACKLOG.md` line 192 → done, `CLAUDE.md` "Static-check gate" section (note gates now live in `build-logic` as `@CacheableTask`s — count unchanged at 46), `memory/project_build_env_perf.md`.

**Rollback at any step:** because gates are migrated one batch at a time and `build-logic` is additive, any failing batch can be reverted to its inline form without affecting the already-migrated gates. Config-cache stays OFF until Step 6, so no intermediate step can break a normal build.

---

## 5. Behaviour-parity guarantees (how we prove no gate weakened)

For **every** gate, the refactor must preserve:
1. **The rule** — same regex / structural predicate, same comment-skip prefixes.
2. **The trigger** — same violation still throws (fail test, per gate).
3. **The non-trigger** — clean tree still passes (pass test).
4. **The message** — same human-readable text + `relative/path:line: content` format (golden-output diff).
5. **The scope** — same source subtree scanned (the `sources` FileCollection must cover exactly the files `walkTopDown()` covered: `**/*.kt` + `**/*.java` under the same root).

A subtle scope trap to avoid: today's `walkTopDown()` walks the directory live; the new `sources` is a declared `FileTree`. They must select the **same** files. Use `layout.projectDirectory.dir(<same path>).asFileTree.matching { include("**/*.kt","**/*.java") }` so the fingerprinted set == the scanned set (codex: "scan the same declared input file set you fingerprint").

---

## 6. Risk / effort + the zero-code quick win

### 6.1 Independent zero-code quick win (do this regardless)
The old per-build cache-wipe **"recovery dance" is obsolete** and should simply stop being run. Earlier sessions ran `./gradlew --stop` + kill java + `rm -rf app/build/kotlin .gradle/kotlin app/build/intermediates/built_in_kotlinc` before *every* build, which forced a cold full Kotlin recompile (~17–20 min) each time. The corruption source — the global `~/.claude/hooks/kotlin-postedit.ps1` hook that spawned `:app:detekt` + `:app:compileDebugKotlin` concurrently on every `.kt` save — was neutered 2026-06-09 (it is `exit 0` at line 11, a no-op). So:
- **Build warm:** just `./gradlew :app:assembleDebug` (no `--stop`, no `rm`). A small-change incremental build ≈ **1–3 min** vs ~17 cold.
- Clean **only on demand**, when a build actually fails with a kotlinc/MD5/incremental error.

This recovers the bulk of day-to-day build time with **zero code change** and is fully independent of the config-cache work. (Source: `memory/project_build_env_perf.md`.)

### 6.2 Intermediate config-cache win (smaller diff than full build-logic)
Per codex, config-cache can be unblocked *without* `build-logic` by keeping the tasks in-script but ensuring the `doLast` lambda captures **no** `Project`: hoist `rootDir`/`file(...)`/regex/sentinel into local vals at configuration time and reference only `File`/`String`/`Provider`/`FileCollection` in `doLast`, and add an `outputs.file(...)` per gate. This can flip config-cache ON faster. **Trade-off:** output-less-no-more, but ad-hoc `doLast` actions remain a worse fit for `@CacheableTask` and the logic stays in the 2900-line script. Treat C as a possible fast checkpoint; B (`build-logic` typed tasks) is the recommended end state.

### 6.3 Effort
| Phase | Effort |
|---|---|
| Zero-code quick win (§6.1) | ~0 (stop running the dance) |
| `build-logic` scaffold + pilot gate + verify | ~0.5 day |
| Migrate ~40 regex gates in batches (with per-gate fail tests) | ~1.5–2 days |
| ~6 bespoke structural gates | ~0.5–1 day |
| Flip config-cache ON + verify + docs | ~0.5 day |
| **Total** | **~3–4 days** |

### 6.4 Risk
- **Low–medium.** The gates are load-bearing, so the risk is *silently weakening* a gate, not breaking the build. Mitigation: the per-gate fail test in §4/§5 is mandatory — a migrated gate that doesn't throw on its golden violation blocks the batch.
- **Scope-mismatch risk** (FileTree vs walkTopDown) — mitigated by §5 item 5.
- **`@SkipWhenEmpty` vs "dir missing → hard error"** — behaviour changes from "throw on missing dir" to "pass (no offenders)" on missing dir. The dirs exist on master; acceptable and documented. If any gate's "dir missing is itself a violation" semantics matters, model `sourceRoot` separately and validate explicitly (codex note).
- **`@CacheableTask` not inherited** — each bespoke subclass must re-annotate (§7).

---

## 7. codex review — refinements folded in

codex (gpt-5.2) reviewed the design and confirmed it directionally correct. Refinements incorporated above:

1. **Sentinel-on-success is correct.** Gradle snapshots outputs only after a *successful* action, so a thrown gate writes no marker and re-runs next build; a later violating source edit changes the input fingerprint, so even a previously-passing gate re-executes and re-fails. → §2.2 throws before `writeText`.
2. **Use `@Internal DirectoryProperty` for the relative-report base, not `@Input String`.** A checkout path must not affect pass/fail or the cache key (would make the task non-relocatable for no benefit). → §2.2 `reportBaseDir` is `@Internal`.
3. **`build-logic` over `buildSrc` for lower long-term risk** (buildSrc edits invalidate config broadly); buildSrc only if we wanted the absolute-lowest first move. → §3 recommends B.
4. **Drop `afterEvaluate`; use `pluginManager.withPlugin("com.android.application")`** then `tasks.matching{}.configureEach{}` (or `tasks.named("preBuild")`). → §2.5.
5. **Simpler intermediate win exists** — hoist Project access out of `doLast`, keep in-script — flips config-cache without `build-logic`, but still wants explicit outputs and is a worse `@CacheableTask` fit. → §6.2.
6. **`@CacheableTask` is NOT inherited** — annotate each concrete subclass. → §2.4, §6.4.
7. **Scan the same file set you fingerprint** — declare a `FileTree` matching `**/*.kt` and iterate *that*, not a broader `walkTopDown()`. → §5 item 5.
8. **Every pass/fail rule parameter is `@Input`** (regex, globs, allowlists, mode flags); store *strings*, compile the `Regex` inside `@TaskAction` — never store compiled `Regex`/parser state. → §2.2.
9. **`@InputDirectory` may fail validation before your action if the dir is missing** — use `@Optional`/`@SkipWhenEmpty` + explicit validation. → §2.3, §6.4.

---

## 8. Expected payoff & how to measure

- **Config-cache reuse** skips the entire configuration phase on warm builds — for a Compose/CameraX/Media3 app this is typically the multi-second-to-tens-of-seconds configuration cost saved on *every* incremental build and IDE sync. The 46 gates also stop re-executing once UP-TO-DATE, removing 46 file-walks per build.
- **Build-cache (`@CacheableTask`)** lets the gate results be pulled from the local/remote build cache across branches and CI — a clean checkout that hasn't changed the scanned sources gets the gates for free.
- **The §6.1 quick win** is the largest single day-to-day saving (avoiding the ~17 min cold-recompile dance), independent of config-cache.

**Measurement protocol:**
1. `./gradlew --stop` then a clean cold `:app:assembleDebug` — record wall time (baseline cold).
2. Warm incremental (touch one trivial `.kt`) `:app:assembleDebug` ×2 — record (baseline warm), config-cache OFF.
3. After Step 6: same two runs with config-cache ON; second run must print "Reusing configuration cache." Compare warm-incremental wall time and the configuration-phase time from `--profile` HTML reports (`build/reports/profile/`).
4. Assert correctness gate, not just speed: `./gradlew :app:assembleDebug --configuration-cache-problems=fail` → 0 problems; introduce one violation per a sampled set of gates → each still fails. Build-cache hit rate visible via `--build-cache` + Build Scan / `--profile`.

Success criteria: config-cache ON with **0** serialization problems, **all 46 gates still fail on their golden violation**, warm-incremental configuration time materially reduced, and gates reporting UP-TO-DATE on unchanged sources.
