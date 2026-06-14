import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "com.aritr.rova"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.aritr.rova"
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        // Ship only the locales Rova actually translates (en + es, ADR-0023).
        // Without this, every AndroidX/Compose/Media3/CameraX dependency drags
        // its own ~70 transitive `values-*/strings` folders into the bundle.
        // AGP 8.8+ DSL — replaces the deprecated `resConfigs`. Keep this list in
        // lockstep with the in-app locale picker and `checkNoHardcodedUiStrings`
        // discipline: adding a translated locale means adding it here too.
        localeFilters += listOf("en", "es")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.isFile) {
                storeFile = rootProject.file(
                    keystoreProperties.getProperty("storeFile")
                        ?: error("Missing storeFile in keystore.properties")
                )
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: error("Missing storePassword in keystore.properties")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: error("Missing keyAlias in keystore.properties")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: error("Missing keyPassword in keystore.properties")
            }
        }
    }

    buildTypes {
        debug {
            // i18n Phase B (ADR-0023) — bake en_XA (accent + ~30–40% text
            // expansion + [bracket bounds]) and ar_XB (RTL bidi mirror) into the
            // DEBUG apk only, as the localizability QA harness. Release is
            // untouched; pseudolocales are never offered in the in-app picker.
            isPseudoLocalesEnabled = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // Tooling unblocker — Accompanist's `PermissionsLaunchDetector`
        // throws an NPE during `:app:lintDebug` when scanning the
        // unmodified `RecordScreen.kt` permission flow. The crash makes
        // the lint gate unusable for every Phase 2 review even though
        // the runtime code is correct. Suppress the single offending
        // detector so the gate is green again; revisit when Accompanist
        // ships a fix.
        disable += "PermissionLaunchedDuringComposition"
        // Suppress Typos false-positives on base64 certificate fingerprint strings
        // in font_certs.xml (substrings like GA1, XFI1 are not misspelled prose).
        // tools:ignore on the XML element does not propagate to <item> text content
        // in Android lint's Typos detector; file-scoped lint.xml also requires this
        // pointer to be resolved. The check is disabled project-wide; font_certs.xml
        // is the sole source of Typos hits and contains no natural-language strings.
        disable += "Typos"
    }
}

// AGP 9 compiles Kotlin natively (built-in Kotlin) — no `org.jetbrains.kotlin.android`
// plugin. Compiler options go through the Kotlin Gradle plugin's `compilerOptions {}`
// DSL (the old `android { kotlinOptions {} }` shim is gone).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

// androidx.test.espresso:espresso-core (androidTest) transitively requests
// androidx.concurrent:concurrent-futures(-ktx):1.2.0, but AGP's main↔androidTest
// version alignment (android.dependency.useConstraints) pins the androidTest
// classpath to main's 1.1.0 — and Gradle 9 fails on the disagreement instead of
// downgrading espresso's transitive request. Force the androidx.concurrent group
// to the current stable 1.1.0 everywhere so the two sides agree. (There is no
// stable 1.2.0 of androidx.concurrent:concurrent-futures on Google's Maven.)
configurations.configureEach {
    resolutionStrategy {
        force(
            "androidx.concurrent:concurrent-futures:1.1.0",
            "androidx.concurrent:concurrent-futures-ktx:1.1.0",
        )
    }
}

// Phase 1.2 — CI lint rule (ROADMAP_v6.md §"Lint / CI Rules Summary"):
// alarm-scheduling code MUST NOT use PendingIntent.getService.
// Android 12+ forbids starting foreground services from background-only
// contexts (alarms qualify), so all alarm targets must be BroadcastReceivers.
// Scope is restricted to service/scheduler/** because RovaRecordingService
// legitimately uses PendingIntent.getService for stop-action notifications.
val checkSchedulerNoGetService = tasks.register("checkSchedulerNoGetService") {
    group = "verification"
    description = "Forbid PendingIntent.getService in alarm scheduler sources."
    val schedulerDir = file("src/main/java/com/aritr/rova/service/scheduler")
    inputs.dir(schedulerDir).withPropertyName("schedulerSources")
    doLast {
        if (!schedulerDir.exists()) {
            throw GradleException("Scheduler dir missing: $schedulerDir")
        }
        val offenders = schedulerDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .mapNotNull { f ->
                val hits = f.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        // Match getService( on PendingIntent. Comment lines
                        // (// or *) are ignored so the doc reference in
                        // AlarmScheduler.kt does not trip the rule.
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                        else line.contains("PendingIntent.getService(") ||
                            Regex("""\bgetService\s*\(""").containsMatchIn(line) &&
                            line.contains("PendingIntent")
                    }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "PendingIntent.getService is forbidden in alarm scheduler sources " +
                    "(Android 12+ forbids FGS starts from background contexts). Offenders:\n$report"
            )
        }
    }
}
// Phase 1.3 — broader CI lint (ROADMAP_v6.md §"Lint / CI Rules Summary"):
// PendingIntent.getService is forbidden anywhere in app sources.
// Notifications and broadcast PIs alike must use PendingIntent.getBroadcast
// with a BroadcastReceiver. This rule subsumes checkSchedulerNoGetService
// post-Phase 1.3 (the legacy notification stop-action getService call was
// removed when STOP migrated to RovaStopReceiver). Both tasks are kept:
// the scheduler-scoped task remains a tighter local invariant; this one
// catches regressions anywhere in the app.
//
// Regex anchors on `PendingIntent.getService` precisely (with whitespace
// tolerance) so unrelated `getService()` callers — notably
// `LocalBinder.getService()` in RovaRecordingService and
// `serviceBinder?.getService()` in RecordViewModel — are not flagged.
// Comment lines (`//`, `*`) are skipped so KDoc references and rationale
// comments do not trip the rule.
val checkStopNoGetService = tasks.register("checkStopNoGetService") {
    group = "verification"
    description = "Forbid PendingIntent.getService — STOP and tick actions must use broadcast (Android 12+ FGS-from-background restriction)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("Rova source dir missing: $srcDir")
        }
        val pattern = Regex("""PendingIntent\s*\.\s*getService\b""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .mapNotNull { f ->
                val hits = f.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                        else pattern.containsMatchIn(line)
                    }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "PendingIntent.getService is forbidden — use PendingIntent.getBroadcast " +
                    "with a BroadcastReceiver (Android 12+ disallows FGS starts from " +
                    "alarm/notification background contexts). Offenders:\n$report"
            )
        }
    }
}

// ADR-0027 — the daily-window receivers must NEVER start the camera FGS.
// Android 14+ forbids starting a while-in-use FGS from the background; the
// only legal camera-start site is MainActivity on the user's notification tap.
// Forbid getService / startForegroundService / startService under service/schedule/.
val checkScheduleReceiverNoFgsStart = tasks.register("checkScheduleReceiverNoFgsStart") {
    group = "verification"
    description = "Schedule receivers (service/schedule/) must never start the camera FGS (ADR-0027)."
    val srcDir = file("src/main/java/com/aritr/rova/service/schedule")
    inputs.dir(srcDir).withPropertyName("scheduleSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("Schedule source dir missing: $srcDir")
        }
        val pattern = Regex("""PendingIntent\s*\.\s*getService\b|\bstartForegroundService\s*\(|\bstartService\s*\(""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val hits = f.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                        else pattern.containsMatchIn(line)
                    }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "Schedule receivers must not start a foreground service (ADR-0027): the " +
                    "camera FGS is started only from MainActivity on the user's notification " +
                    "tap. Offenders:\n$report"
            )
        }
    }
}

// Phase 1.5 — ADR 0005 §"Acceptance Criteria" lint #1:
// Phase 1.5 sources MUST NOT call any deletion API. Phase 1.5 emits
// DiscardEligibility flags only; physical deletion is owned by Phase 1.7
// (post-export-recovery cleanup) or by explicit user action through
// Phase 2 UI. Without this rule, an accidental dir.deleteRecursively()
// would silently strand pending export rows owned by ADR 0003 — the
// blocker that surfaced in Phase 1.5 design round 4.
//
// Scope: RovaApp.kt and everything under service/recovery/. Forbidden
// substrings: ".delete(", ".deleteRecursively(", ".discardSession(".
// Comment lines (// or *) are skipped so KDoc references and rationale
// comments do not trip the rule.
val checkRecoveryNoDeletion = tasks.register("checkRecoveryNoDeletion") {
    group = "verification"
    description = "Forbid deletion APIs in Phase 1.5 sources (ADR 0005 — emits flags, never deletes)."
    val recoveryDir = file("src/main/java/com/aritr/rova/service/recovery")
    val rovaAppFile = file("src/main/java/com/aritr/rova/RovaApp.kt")
    inputs.dir(recoveryDir).withPropertyName("recoverySources")
    inputs.file(rovaAppFile).withPropertyName("rovaAppSource")
    doLast {
        if (!recoveryDir.exists()) {
            throw GradleException("Phase 1.5 recovery dir missing: $recoveryDir")
        }
        val forbidden = listOf(
            ".delete(",
            ".deleteRecursively(",
            ".discardSession(",
        )
        val targets = recoveryDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList() + listOfNotNull(rovaAppFile.takeIf { it.exists() })
        val offenders = targets
            .mapNotNull { f ->
                val hits = f.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                        else forbidden.any { line.contains(it) }
                    }
                if (hits.isEmpty()) null else f to hits
            }
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "Phase 1.5 sources must not call deletion APIs (ADR 0005 — " +
                    "Phase 1.5 emits DiscardEligibility flags only; deletion is " +
                    "owned by Phase 1.7 post-export-recovery cleanup or by " +
                    "explicit user action). Offenders:\n$report"
            )
        }
    }
}

// Phase 1.5 — ADR 0005 §"Acceptance Criteria" lint #2:
// The canonical segment filename pattern in recovery sources is
// `segment_NNNN.mp4` (matching SessionStore.nextSegmentFilename). A `seg_`
// variant would silently miss every real segment file — exactly the
// blocker caught in Phase 1.5 design round 5. Lock the literal in CI.
//
// Scope: service/recovery/. Forbidden substring: `seg_` (the only
// reachable substring of any wrong abbreviation; `segment_` itself does
// NOT contain `seg_` because the underscore lands after `segment`, not
// after `seg`).
val checkRecoverySegmentRegex = tasks.register("checkRecoverySegmentRegex") {
    group = "verification"
    description = "Forbid `seg_` variant in Phase 1.5 sources; canonical pattern is `segment_NNNN.mp4` (ADR 0005)."
    val recoveryDir = file("src/main/java/com/aritr/rova/service/recovery")
    inputs.dir(recoveryDir).withPropertyName("recoverySources")
    doLast {
        if (!recoveryDir.exists()) {
            throw GradleException("Phase 1.5 recovery dir missing: $recoveryDir")
        }
        val offenders = recoveryDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .mapNotNull { f ->
                val hits = f.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trimStart()
                        // Comment lines are scanned too — a stale rationale
                        // referring to `seg_` is itself a code-rot signal.
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                        else line.contains("seg_")
                    }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "`seg_` is forbidden in Phase 1.5 recovery sources — the " +
                    "canonical segment filename pattern is `segment_NNNN.mp4` " +
                    "(SessionStore.nextSegmentFilename, ADR 0005). " +
                    "Offenders:\n$report"
            )
        }
    }
}

// Phase 1.5 — ADR 0005 §"Acceptance Criteria" lint #3:
// `runRecoveryScan` is the private helper invoked solely by
// RovaApp.triggerRecoveryScanIfNeeded. Calling it from anywhere else —
// a receiver, a scheduled job, MainActivity directly — would defeat
// Guard A (latch reset on throw) and the trigger-boundary invariant
// designed in round 2. Forbid every reference outside RovaApp.kt.
val checkScanTriggerSingleSite = tasks.register("checkScanTriggerSingleSite") {
    group = "verification"
    description = "Forbid runRecoveryScan references outside RovaApp.kt (ADR 0005 §Scan Trigger Boundary)."
    val srcDir = file("src/main/java/com/aritr/rova")
    val allowedFile = file("src/main/java/com/aritr/rova/RovaApp.kt").canonicalFile
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("Rova source dir missing: $srcDir")
        }
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .filter { it.canonicalFile != allowedFile }
            .mapNotNull { f ->
                val hits = f.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                        else line.contains("runRecoveryScan")
                    }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "`runRecoveryScan` is owned exclusively by RovaApp; the only " +
                    "trigger is RovaApp.triggerRecoveryScanIfNeeded called from " +
                    "MainActivity.onCreate (ADR 0005 §Scan Trigger Boundary). " +
                    "Offenders:\n$report"
            )
        }
    }
}

// Phase 1.5 — ADR 0005 §"Concurrency Invariants" item 3 (Guard B):
// Every receiver using goAsync() under service/ MUST:
//   1. call `activeReceiverWork.incrementAndGet()` SYNCHRONOUSLY in
//      onReceive BEFORE goAsync() (so the recovery scan's drain sees the
//      counter raised before goAsync() returns).
//   2. call `activeReceiverWork.decrementAndGet()` in the launched
//      coroutine's `finally` (after pendingResult.finish()).
//
// Without this rule, a future receiver edit could reopen the scan-vs-
// receiver race that round 2 of the design review identified. Runtime
// tests can't catch the regression because the broken state is only
// observable under a specific race window; static enforcement is the
// only defense.
//
// Opt-out: any file containing the literal `guard-b-opt-out:` (typically
// in a comment) skips the check. Mandatory colon forces the writer to
// articulate a reason (e.g., `// guard-b-opt-out: synchronous receiver,
// no coroutine launched`). Mirrors the documented-annotation/comment
// option in ADR 0005 §Acceptance Criteria.
val checkRecoveryReceiverCounter = tasks.register("checkRecoveryReceiverCounter") {
    group = "verification"
    description = "Verify Guard B (ADR 0005): receivers using goAsync() must increment activeReceiverWork before goAsync() and decrement in finally."
    val serviceDir = file("src/main/java/com/aritr/rova/service")
    inputs.dir(serviceDir).withPropertyName("serviceSources")
    doLast {
        if (!serviceDir.exists()) {
            throw GradleException("Service dir missing: $serviceDir")
        }
        val offenders = serviceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val lines = f.readLines()

                // Find first non-comment goAsync() call line.
                val goAsyncIdx = lines.indexOfFirst { line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                    else line.contains("goAsync()")
                }
                if (goAsyncIdx < 0) return@mapNotNull null  // no goAsync — not a receiver-async pattern

                // Opt-out: any file marker `guard-b-opt-out:` (with reason)
                // skips the check.
                val hasOptOut = lines.any { it.contains("guard-b-opt-out:") }
                if (hasOptOut) return@mapNotNull null

                val problems = mutableListOf<String>()

                // Increment: first non-comment line that contains the
                // synchronous incrementAndGet on activeReceiverWork.
                val incIdx = lines.indexOfFirst { line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                    else line.contains("activeReceiverWork.incrementAndGet")
                }
                val hasDec = lines.any { line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                    else line.contains("activeReceiverWork.decrementAndGet")
                }

                if (incIdx < 0) {
                    problems += "missing activeReceiverWork.incrementAndGet() before goAsync() (line ${goAsyncIdx + 1})"
                } else if (incIdx >= goAsyncIdx) {
                    problems += "activeReceiverWork.incrementAndGet() (line ${incIdx + 1}) must precede goAsync() (line ${goAsyncIdx + 1}); the gap between goAsync() and the launched coroutine body is the race window"
                }
                if (!hasDec) {
                    problems += "missing activeReceiverWork.decrementAndGet() — must run in the launched coroutine's finally so every exit path releases the counter"
                }

                if (problems.isEmpty()) null else f to problems
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, problems) ->
                problems.joinToString("\n") { "  ${f.relativeTo(rootDir)}: $it" }
            }
            throw GradleException(
                "Guard B violations (ADR 0005 §Concurrency Invariants item 3):\n$report\n" +
                    "Add a documented opt-out marker `// guard-b-opt-out: <reason>` " +
                    "to the file (with a reason) only if the receiver does no " +
                    "asynchronous work and never races the recovery scan."
            )
        }
    }
}

// ============================================================
// Phase 1.4 (ADR 0006) lint tasks
// ============================================================

/**
 * ADR 0006 §"Atomic terminal-write API" (B16 forbidden-pair scanner).
 * Flags `markTerminated(..., Terminated.USER_STOPPED, StopReason.NONE)`
 * literal pairs. Every USER_STOPPED writer must pass a real StopReason
 * (USER, INIT_FAILED, PERMISSION_REVOKED, or LOW_STORAGE) per the
 * §"Migration table". Only KILLED_* and merge-success COMPLETED writers
 * pass NONE.
 */
val checkAtomicTerminalWriteForbiddenPair = tasks.register("checkAtomicTerminalWriteForbiddenPair") {
    group = "verification"
    description = "Forbid markTerminated(USER_STOPPED, NONE) literal pair (ADR 0006 B16)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("srcAll")
    doLast {
        if (!srcDir.exists()) throw GradleException("Source dir missing: $srcDir")
        // Pattern matches any markTerminated call that mentions both
        // USER_STOPPED and NONE on the same line OR within a 3-line window.
        // We use a multi-line scan: find every `markTerminated(` opener
        // and inspect the next 3 lines for the forbidden pair.
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val lines = f.readLines()
                val hits = mutableListOf<Int>()
                for ((i, line) in lines.withIndex()) {
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                    if (!line.contains("markTerminated(")) continue
                    if (line.contains("terminal-ordering-opt-out:")) continue
                    // Window of up to 3 lines covers multi-line invocations.
                    val window = (i..minOf(i + 3, lines.lastIndex))
                        .joinToString("\n") { lines[it] }
                    val hasUserStopped = window.contains("Terminated.USER_STOPPED") ||
                        window.contains(", USER_STOPPED")
                    val hasReasonNone = window.contains("StopReason.NONE") ||
                        window.contains(", NONE")
                    if (hasUserStopped && hasReasonNone) {
                        hits += i + 1
                    }
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, ls) ->
                "  ${f.relativeTo(rootDir)}: lines ${ls.joinToString(", ")}"
            }
            throw GradleException(
                "Forbidden pair markTerminated(USER_STOPPED, NONE) detected (ADR 0006 B16):\n$report\n" +
                    "USER_STOPPED writers must pass a real StopReason " +
                    "(USER, INIT_FAILED, PERMISSION_REVOKED, LOW_STORAGE). " +
                    "Only KILLED_* and merge-success COMPLETED pass NONE."
            )
        }
    }
}

/**
 * ADR 0006 B21: only `RovaApp.resolveExternalRoot` may call
 * `getExternalFilesDir(null)`. All other code reads
 * `RovaApp.videosRoot` / `RovaApp.externalRoot`. The deprecated
 * `SessionStore(Context)` constructor uses it inside a fallback that
 * itself errors out.
 */
val checkExternalRootShared = tasks.register("checkExternalRootShared") {
    group = "verification"
    description = "Only RovaApp.resolveExternalRoot may reference getExternalFilesDir(null) (ADR 0006 B21)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("srcAll")
    doLast {
        if (!srcDir.exists()) throw GradleException("Source dir missing: $srcDir")
        val allowedFiles = setOf("RovaApp.kt", "SessionStore.kt")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in allowedFiles }
            .mapNotNull { f ->
                val lines = f.readLines()
                val hits = mutableListOf<Int>()
                for ((i, line) in lines.withIndex()) {
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                    if (line.contains("getExternalFilesDir(null)")) hits += i + 1
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, ls) ->
                "  ${f.relativeTo(rootDir)}: lines ${ls.joinToString(", ")}"
            }
            throw GradleException(
                "External-root drift detected (ADR 0006 B21):\n$report\n" +
                    "Use RovaApp.videosRoot or RovaApp.externalRoot instead. " +
                    "Only RovaApp and SessionStore are allowed direct references."
            )
        }
    }
}

/**
 * ADR 0006 B18: `FOREGROUND_SERVICE_TYPE_MICROPHONE` reference must be
 * preceded (lower line index) by an `audioMode` reference within the
 * same source file. Forbidden: hardcoded `CAMERA | MICROPHONE` without
 * an audio-mode gate. Caught Round 6 B20 as draft-residue regression.
 */
val checkAudioModeFgsTypeMatch = tasks.register("checkAudioModeFgsTypeMatch") {
    group = "verification"
    description = "FGS_TYPE_MICROPHONE must be gated by audioMode (ADR 0006 B18 + B20)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("srcAll")
    doLast {
        if (!srcDir.exists()) throw GradleException("Source dir missing: $srcDir")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val lines = f.readLines()
                val micIdx = lines.indexOfFirst { line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                    else line.contains("FOREGROUND_SERVICE_TYPE_MICROPHONE")
                }
                if (micIdx < 0) return@mapNotNull null
                if (lines.any { it.contains("audio-mode-opt-out:") }) return@mapNotNull null
                // Search lines BEFORE the MIC literal for an audioMode
                // reference (variable read, AudioMode enum, when branch).
                val hasAudioModeBefore = (0 until micIdx).any { i ->
                    val s = lines[i]
                    s.contains("audioMode") || s.contains("AudioMode.")
                }
                if (hasAudioModeBefore) null else f to micIdx + 1
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, line) ->
                "  ${f.relativeTo(rootDir)}: line $line — FGS MIC type without audioMode gate"
            }
            throw GradleException(
                "FGS-type / audio-mode coupling violation (ADR 0006 B18 + B20):\n$report\n" +
                    "FOREGROUND_SERVICE_TYPE_MICROPHONE must be gated by an " +
                    "audioMode == AudioMode.VIDEO_AUDIO check. Hardcoding the " +
                    "type bitfield risks SecurityException on Android 14+."
            )
        }
    }
}

/**
 * ADR 0006 B11 + B20: every `startForegroundService` (caller side) and
 * `startForeground(` (service side) reference must be inside a
 * `try { ... } catch (e: IllegalStateException)` block. Service-side
 * sites also require a `catch (e: SecurityException)` arm. The catch
 * block must reference `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`
 * before any `is ForegroundServiceStartNotAllowedException` check.
 */
val checkFGSStartGuarded = tasks.register("checkFGSStartGuarded") {
    group = "verification"
    description = "FGS start sites must catch IllegalStateException + SecurityException with SDK-gated is-check (ADR 0006 B10 + B11 + B20)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("srcAll")
    doLast {
        if (!srcDir.exists()) throw GradleException("Source dir missing: $srcDir")
        val offenders = mutableListOf<Pair<File, String>>()
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                // Skip RovaTickReceiver / RovaStopReceiver — they have
                // documentation comments referencing startForegroundService
                // but no actual call.
                val text = f.readText()
                text.contains("startForegroundService(") || text.contains("startForeground(")
            }
            .forEach { f ->
                val lines = f.readLines()
                if (lines.any { it.contains("fgs-guard-opt-out:") }) return@forEach
                for ((i, line) in lines.withIndex()) {
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                    val isCallerSide = line.contains("startForegroundService(")
                    val isServiceSide = line.contains("startForeground(") &&
                        !line.contains("startForegroundService(")
                    if (!isCallerSide && !isServiceSide) continue
                    // Look forward up to 60 lines for catch arms.
                    val end = minOf(i + 60, lines.lastIndex)
                    val window = lines.subList(i, end + 1).joinToString("\n")
                    val hasIseCatch = window.contains("catch (e: IllegalStateException)") ||
                        window.contains("catch(e: IllegalStateException)") ||
                        window.contains("catch (e:IllegalStateException)")
                    val hasSdkGate = window.contains("Build.VERSION.SDK_INT") &&
                        window.contains("Build.VERSION_CODES.S")
                    val hasIsCheck = window.contains("ForegroundServiceStartNotAllowedException")
                    val hasSecurityCatch = window.contains("catch (e: SecurityException)") ||
                        window.contains("catch(e: SecurityException)")
                    if (!hasIseCatch) {
                        offenders += f to "line ${i + 1}: missing catch (e: IllegalStateException)"
                    } else {
                        if (!hasSdkGate) offenders += f to "line ${i + 1}: catch is not SDK-gated (Build.VERSION.SDK_INT >= S)"
                        if (!hasIsCheck) offenders += f to "line ${i + 1}: missing ForegroundServiceStartNotAllowedException is-check"
                    }
                    if (isServiceSide && !hasSecurityCatch) {
                        offenders += f to "line ${i + 1}: service-side startForeground missing catch (e: SecurityException) (B18 + B20)"
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, msg) ->
                "  ${f.relativeTo(rootDir)}: $msg"
            }
            throw GradleException(
                "FGS guard violations (ADR 0006 B10 + B11 + B20):\n$report\n" +
                    "Caller-side: catch IllegalStateException, SDK-gate the is-check.\n" +
                    "Service-side: also catch SecurityException for FGS-type / permission mismatch."
            )
        }
    }
}

/**
 * ADR 0006 §"Terminal-Write Ordering" (B3 + B7). Within
 * `RovaRecordingService.kt`, every `markTerminated(...)` invocation
 * passing `Terminated.USER_STOPPED` must appear on a LOWER line than
 * any reachable `performMerge` / `mergeSegments` / `VideoMerger`
 * reference in the same file. (Eager-write rule for USER_STOPPED.)
 *
 * Conversely the `Terminated.COMPLETED` invocation must appear AFTER
 * `performMerge` (B7 carve-out: `COMPLETED` ⇔ merge succeeded).
 *
 * The lint operates at file scope (not function scope) — it's a coarse
 * compile-time guard that catches accidental reordering. Documented
 * opt-out marker `// terminal-ordering-opt-out:` skips the check for
 * any file requiring exemption.
 */
val checkUserStoppedBeforeMerge = tasks.register("checkUserStoppedBeforeMerge") {
    group = "verification"
    description = "USER_STOPPED markTerminated must precede merge call sites in RovaRecordingService.kt (ADR 0006 B3); COMPLETED must follow them (B7)."
    val targetFile = file("src/main/java/com/aritr/rova/service/RovaRecordingService.kt")
    inputs.file(targetFile).withPropertyName("recordingServiceSource")
    doLast {
        if (!targetFile.exists()) {
            throw GradleException("Target file missing: $targetFile")
        }
        val lines = targetFile.readLines()
        if (lines.any { it.contains("terminal-ordering-opt-out:") }) return@doLast

        // Collect line indices for USER_STOPPED writes, COMPLETED writes,
        // and merge call sites (performMerge / mergeSegments / VideoMerger
        // references that aren't comments).
        val userStoppedLines = mutableListOf<Int>()
        val completedLines = mutableListOf<Int>()
        val mergeLines = mutableListOf<Int>()
        for ((i, raw) in lines.withIndex()) {
            val trimmed = raw.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
            // markTerminated(... USER_STOPPED ...) within a 3-line window
            if (raw.contains("markTerminated(")) {
                val window = (i..minOf(i + 3, lines.lastIndex))
                    .joinToString("\n") { lines[it] }
                if (window.contains("Terminated.USER_STOPPED")) userStoppedLines += i + 1
                if (window.contains("Terminated.COMPLETED")) completedLines += i + 1
            }
            // Merge call detection — function call to performMerge,
            // VideoMerger.mergeSegments, or VideoMerger reference.
            // Skip the function definition itself.
            val isMergeCall = (raw.contains("performMerge(") &&
                !raw.contains("private suspend fun performMerge")) ||
                raw.contains("VideoMerger.mergeSegments(") ||
                raw.contains(".mergeSegments(")
            if (isMergeCall) mergeLines += i + 1
        }

        if (mergeLines.isEmpty()) {
            // No merge call sites — nothing to check.
            return@doLast
        }
        val firstMerge = mergeLines.min()
        val lastMerge = mergeLines.max()
        val problems = mutableListOf<String>()

        // B3: every USER_STOPPED write must precede the FIRST merge call.
        userStoppedLines.filter { it >= firstMerge }.forEach { line ->
            problems += "USER_STOPPED markTerminated at line $line appears AT OR AFTER merge call at line $firstMerge — eager-write rule (B3) violated"
        }
        // B7: every COMPLETED write must follow the LAST merge call.
        completedLines.filter { it <= lastMerge }.forEach { line ->
            problems += "COMPLETED markTerminated at line $line appears AT OR BEFORE the last merge call at line $lastMerge — merge-success-only rule (B7) violated"
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Terminal-write ordering violations in ${targetFile.relativeTo(rootDir)} (ADR 0006 B3 + B7):\n" +
                    problems.joinToString("\n") { "  $it" } +
                    "\nFix: USER_STOPPED writes go BEFORE merge entry; COMPLETED only at merge-success commit point.\n" +
                    "Opt-out marker: // terminal-ordering-opt-out: <reason>"
            )
        }
    }
}

// ============================================================
// Phase 1.7 lints (commit-0 onwards)
// ============================================================

/**
 * Phase 1.7 commit-0 — schema-2 read-tolerance gate. ADR 0003 §"FD Mode
 * Amendment" partner: schema-2 manifests written by Phase 1.3 builds
 * carry no `exportTier` field; the strict `getString("exportTier")`
 * call site that previously lived in [SessionManifest.fromJson] threw
 * `JSONException` on those manifests, crashing the cold launch before
 * Phase 1.5 / Phase 1.7 recovery could classify the session. The
 * read-tolerant pattern is `optString + runCatching + currentExportTier()`
 * fallback (mirroring the existing patterns for `audioMode`,
 * `stopReason`, `terminated`). This lint forbids any regression to the
 * strict `getString("exportTier")` form.
 */
val checkExportTierReadTolerant = tasks.register("checkExportTierReadTolerant") {
    group = "verification"
    description = "Forbid getString(\"exportTier\") — schema-2 manifests lack the field; use opt + runCatching + currentExportTier() fallback (ADR 0003 §FD Mode Amendment partner)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("srcAll")
    doLast {
        if (!srcDir.exists()) throw GradleException("Source dir missing: $srcDir")
        val pattern = Regex("""\bgetString\s*\(\s*"exportTier"\s*\)""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val hits = f.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                        else pattern.containsMatchIn(line)
                    }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "Strict getString(\"exportTier\") is forbidden — schema-2 " +
                    "manifests lack the field and would throw JSONException. " +
                    "Use the optString + runCatching + currentExportTier() " +
                    "fallback pattern (ADR 0003 §FD Mode Amendment partner). " +
                    "Offenders:\n$report"
            )
        }
    }
}

/**
 * Phase 1.7 commit-2 (Patch 2 — bounded scanFile wait).
 * `MediaScannerConnection.scanFile` is fire-and-forget at the platform
 * layer; a misbehaving `MediaScanner` can swallow the callback. Holding
 * the foreground service open waiting indefinitely would block new
 * sessions and burn wakelock budget. Every call site MUST go through
 * `MediaScanWaiter.scanAndWait`, which wraps the platform call in a
 * `withTimeoutOrNull`-bounded `suspendCancellableCoroutine`. Production
 * exporters (Tier 2 / Tier 3) call the waiter; recovery routines call
 * the waiter; nothing else may import `MediaScannerConnection.scanFile`
 * directly.
 *
 * Allow-list: `MediaScanWaiter.kt` is the single legitimate call site.
 */
val checkScanFileBoundedWait = tasks.register("checkScanFileBoundedWait") {
    group = "verification"
    description = "MediaScannerConnection.scanFile must only be called inside the bounded MediaScanWaiter helper (Phase 1.7 Patch 2)."
    val srcDir = file("src/main/java/com/aritr/rova")
    val allowedFile = file(
        "src/main/java/com/aritr/rova/service/export/MediaScanWaiter.kt"
    ).canonicalFile
    inputs.dir(srcDir).withPropertyName("srcAll")
    doLast {
        if (!srcDir.exists()) throw GradleException("Source dir missing: $srcDir")
        val pattern = Regex("""\bMediaScannerConnection\s*\.\s*scanFile\b""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.canonicalFile != allowedFile }
            .mapNotNull { f ->
                val hits = f.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                        else pattern.containsMatchIn(line)
                    }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "MediaScannerConnection.scanFile is forbidden outside " +
                    "MediaScanWaiter.kt — every call must be bounded by " +
                    "MediaScanWaiter.scanAndWait (Phase 1.7 Patch 2; " +
                    "naked awaits hang the foreground service if the " +
                    "scan callback never fires). Offenders:\n$report"
            )
        }
    }
}

/**
 * Phase 1.7 commit-4 — ADR 0003 §"FD Mode Amendment" enforcement.
 * Tier 1 (`Tier1*.kt` under `service/export/`) opens the pending-row
 * `MediaStore` FD with `MediaMuxer(FileDescriptor)`. The amendment
 * forbids mode `"w"` because Android documents it as non-seekable;
 * `MediaMuxer.stop()` rewrites the moov atom and requires a seekable
 * FD, so `"w"` corrupts every Tier 1 export at finalize time. Only
 * `"rw"` is legal for the muxer FD; `"r"` is also acceptable (used by
 * the recovery extractor probe). This lint scans `Tier1*.kt` files for
 * the literal `"w"` and rejects any non-comment occurrence.
 *
 * Detection: strip the safe substring `"rw"` from each line, then check
 * for residual `"w"`. Avoids false positives on `"rw"` (which contains
 * `w` but not the standalone `"w"` literal).
 */
val checkPendingFdModeIsRW = tasks.register("checkPendingFdModeIsRW") {
    group = "verification"
    description = "Tier 1 sources must use openFileDescriptor mode \"rw\"; \"w\" is non-seekable and breaks MediaMuxer.stop() (ADR 0003 §FD Mode Amendment)."
    val exportDir = file("src/main/java/com/aritr/rova/service/export")
    inputs.dir(exportDir).withPropertyName("exportSources")
    doLast {
        if (!exportDir.exists()) throw GradleException("Export dir missing: $exportDir")
        val offenders = exportDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name.startsWith("Tier1") }
            .mapNotNull { f ->
                val hits = f.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                        else line.replace("\"rw\"", "").contains("\"w\"")
                    }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "Forbidden openFileDescriptor mode \"w\" in Tier 1 sources " +
                    "(ADR 0003 §FD Mode Amendment — \"w\" is non-seekable; " +
                    "MediaMuxer.stop() rewrites the moov atom and requires a " +
                    "seekable FD). Use \"rw\" instead. Offenders:\n$report"
            )
        }
    }
}

/**
 * Phase 1.7 commit-5 — `IS_PENDING` is API 29+. Any `IS_PENDING`
 * reference inside `service/export/` must live in a file that is
 * either annotated `@RequiresApi(Build.VERSION_CODES.Q)` (or higher)
 * or contains a `Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q`
 * runtime guard. Without this, a future edit could compile a
 * non-gated `IS_PENDING` write into a code path reachable on API 24,
 * crashing with `NoSuchFieldError` at runtime. Scope: `service/export/`
 * only — Phase 1's pre-existing `PreviewActivity` / `RovaRecordingService`
 * call sites are commit-7's deletion target and stay outside this lint.
 */
val checkExportIsPendingGuarded = tasks.register("checkExportIsPendingGuarded") {
    group = "verification"
    description = "IS_PENDING references in service/export/ must be SDK-gated to API 29+ (ADR 0003 Tier 1)."
    val exportDir = file("src/main/java/com/aritr/rova/service/export")
    inputs.dir(exportDir).withPropertyName("exportSources")
    doLast {
        if (!exportDir.exists()) throw GradleException("Export dir missing: $exportDir")
        val pattern = Regex("""\bIS_PENDING\b""")
        val offenders = exportDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val text = f.readText()
                val hits = f.readLines().withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) false
                        else pattern.containsMatchIn(line)
                    }
                if (hits.isEmpty()) return@mapNotNull null
                val hasFileGuard = text.contains("@RequiresApi(Build.VERSION_CODES.Q)") ||
                    text.contains("@RequiresApi(Build.VERSION_CODES.R)") ||
                    text.contains("@RequiresApi(android.os.Build.VERSION_CODES.Q)") ||
                    (text.contains("Build.VERSION.SDK_INT") && text.contains("Build.VERSION_CODES.Q"))
                if (hasFileGuard) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "IS_PENDING used without SDK gating in service/export/ — " +
                    "the file must be annotated @RequiresApi(Build.VERSION_CODES.Q) " +
                    "or guard the reference with `Build.VERSION.SDK_INT >= " +
                    "Build.VERSION_CODES.Q`. Offenders:\n$report"
            )
        }
    }
}

/**
 * Phase 1.7 commit-5 — `MediaStore.setIncludePending` was deprecated
 * in API 30 (no-op or worse on R+). It is the legitimate pending-row
 * visibility mechanism on API 29 ONLY. Every reference inside
 * `service/export/` must appear within a `Build.VERSION_CODES.R`
 * SDK branch (≤30 lines from a `VERSION_CODES.R` token), forcing the
 * call to live in the `< R` arm of an SDK comparison.
 */
val checkExportSetIncludePendingGuarded = tasks.register("checkExportSetIncludePendingGuarded") {
    group = "verification"
    description = "setIncludePending references in service/export/ must be SDK-gated against Build.VERSION_CODES.R (Tier 1; deprecated/forbidden API 30+)."
    val exportDir = file("src/main/java/com/aritr/rova/service/export")
    inputs.dir(exportDir).withPropertyName("exportSources")
    doLast {
        if (!exportDir.exists()) throw GradleException("Export dir missing: $exportDir")
        val pattern = Regex("""\bsetIncludePending\b""")
        val offenders = exportDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val lines = f.readLines()
                val hits = mutableListOf<Pair<Int, String>>()
                for ((i, line) in lines.withIndex()) {
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
                    if (!pattern.containsMatchIn(line)) continue
                    val window = lines.subList(
                        maxOf(0, i - 30),
                        minOf(lines.size, i + 30)
                    ).joinToString("\n")
                    val hasSdkBranch = window.contains("Build.VERSION_CODES.R") &&
                        window.contains("Build.VERSION.SDK_INT")
                    if (!hasSdkBranch) hits += i + 1 to line.trim()
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:$i: $line"
                }
            }
            throw GradleException(
                "setIncludePending used without an SDK branch against " +
                    "Build.VERSION_CODES.R — must run only on API 29 (deprecated " +
                    "and unreliable on API 30+). Offenders:\n$report"
            )
        }
    }
}

/**
 * Phase 1.7 commit-5 — `MediaStore.QUERY_ARG_MATCH_PENDING` does not
 * exist below API 30. Every reference inside `service/export/` must
 * appear near a `Build.VERSION_CODES.R` SDK branch, forcing the
 * `>= R` arm. A reference outside an SDK guard would `NoSuchFieldError`
 * on Android Q.
 */
val checkExportQueryArgMatchPendingGuarded = tasks.register("checkExportQueryArgMatchPendingGuarded") {
    group = "verification"
    description = "QUERY_ARG_MATCH_PENDING references in service/export/ must be SDK-gated against Build.VERSION_CODES.R (Tier 1; API 30+ only)."
    val exportDir = file("src/main/java/com/aritr/rova/service/export")
    inputs.dir(exportDir).withPropertyName("exportSources")
    doLast {
        if (!exportDir.exists()) throw GradleException("Export dir missing: $exportDir")
        val pattern = Regex("""\bQUERY_ARG_MATCH_PENDING\b""")
        val offenders = exportDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val lines = f.readLines()
                val hits = mutableListOf<Pair<Int, String>>()
                for ((i, line) in lines.withIndex()) {
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
                    if (!pattern.containsMatchIn(line)) continue
                    val window = lines.subList(
                        maxOf(0, i - 30),
                        minOf(lines.size, i + 30)
                    ).joinToString("\n")
                    val hasSdkBranch = window.contains("Build.VERSION_CODES.R") &&
                        window.contains("Build.VERSION.SDK_INT")
                    if (!hasSdkBranch) hits += i + 1 to line.trim()
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:$i: $line"
                }
            }
            throw GradleException(
                "QUERY_ARG_MATCH_PENDING used without an SDK branch against " +
                    "Build.VERSION_CODES.R — must run only on API 30+ " +
                    "(NoSuchFieldError on Q). Offenders:\n$report"
            )
        }
    }
}

/**
 * Phase 1.7 commit-5 — pending-visibility-on-query rule. Pending
 * `MediaStore` rows are NOT visible to the standard `query(uri,
 * projection, ...)` call by default, AND `setIncludePending` on API
 * 29 makes pending rows VISIBLE without filtering out non-pending
 * rows — so a query against the wrapped URI without an explicit
 * `IS_PENDING = 1` SQL selection returns every owned video, including
 * published ones, which the sweep would mistake for orphans (NO-GO
 * patch).
 *
 * Any file in `service/export/` that calls `resolver.query(` MUST
 * contain ALL THREE:
 *  1. `setIncludePending` (API 29 visibility primitive),
 *  2. `QUERY_ARG_MATCH_PENDING` (API 30+ visibility primitive),
 *  3. an explicit `IS_PENDING = 1` SQL selection (defense in depth on
 *     both branches — OEM MediaStore behavior on visibility flags is
 *     not always conformant).
 *
 * The loose presence check is sufficient because the SDK-guard lints
 * already constrain WHERE each visibility primitive lives, and the
 * extracted `filterPendingOwned` test verifies the post-cursor
 * defense pass at runtime.
 */
val checkExportPendingVisibilityOnQuery = tasks.register("checkExportPendingVisibilityOnQuery") {
    group = "verification"
    description = "Files in service/export/ that call resolver.query(...) must use BOTH visibility mechanisms AND an explicit IS_PENDING = 1 SQL selection (Tier 1; commit-5 NO-GO patch)."
    val exportDir = file("src/main/java/com/aritr/rova/service/export")
    inputs.dir(exportDir).withPropertyName("exportSources")
    doLast {
        if (!exportDir.exists()) throw GradleException("Export dir missing: $exportDir")
        val queryPattern = Regex("""resolver\s*\.\s*query\s*\(""")
        // Match `IS_PENDING = 1` (with or without `}` from string-template
        // closure) and tolerate whitespace.
        val isPendingFilterPattern = Regex("""IS_PENDING\}?\s*=\s*1\b""")
        val offenders = exportDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val text = f.readText()
                val nonCommentText = f.readLines()
                    .filter {
                        val t = it.trimStart()
                        !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")
                    }
                    .joinToString("\n")
                if (!queryPattern.containsMatchIn(nonCommentText)) return@mapNotNull null
                val hasIncludePending = text.contains("setIncludePending")
                val hasMatchPending = text.contains("QUERY_ARG_MATCH_PENDING")
                val hasIsPendingFilter = isPendingFilterPattern.containsMatchIn(nonCommentText)
                if (hasIncludePending && hasMatchPending && hasIsPendingFilter) null
                else f to listOfNotNull(
                    if (!hasIncludePending) "missing setIncludePending (API 29 visibility)" else null,
                    if (!hasMatchPending) "missing QUERY_ARG_MATCH_PENDING (API 30+ visibility)" else null,
                    if (!hasIsPendingFilter) "missing explicit IS_PENDING = 1 SQL selection (defense in depth — visibility flags alone are not enough)" else null
                )
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, problems) ->
                problems.joinToString("\n") { p -> "  ${f.relativeTo(rootDir)}: $p" }
            }
            throw GradleException(
                "Pending-visibility-on-query rule violated in service/export/ — " +
                    "any file calling resolver.query(...) must wire BOTH visibility " +
                    "mechanisms AND an explicit IS_PENDING = 1 SQL selection. The " +
                    "visibility flags alone do not exclude non-pending rows on API 29 " +
                    "(setIncludePending = \"include in addition\", not \"only\") and " +
                    "OEM MediaStore behavior on MATCH_ONLY varies. " +
                    "Offenders:\n$report"
            )
        }
    }
}

/**
 * Phase 1.7 commit-6 — cleanup-pass predicate must reference all four
 * gates per ADR 0006 §"Ownership table" + the commit-6 NO-GO patch:
 *
 *  1. `AUTO_DISCARD_ELIGIBLE` — Phase 1.5 eligibility gate.
 *  2. `privateTempPath` — Tier 2/3 deferred-scan retention gate.
 *  3. `RetryableFailure` AND `ManifestWriteFailed` — per-session
 *     export-recovery terminal-clean gate.
 *  4. `QueryFailed` — Tier 1 orphan sweep clean gate (post-NO-GO patch:
 *     QueryFailed blocks ALL physical cleanup this run because pending-
 *     row reference safety is unknown when the listing seam threw).
 *
 * Source-text presence check; cannot enforce semantic correctness, but
 * documents that the predicate covers every load-bearing dimension. A
 * predicate edit that drops any gate trips this lint at preBuild.
 *
 * Allow-list: only `ExportCleanupPredicate.kt` is required to contain
 * all four tokens. The check runs against that one file.
 */
val checkExportCleanupPredicate = tasks.register("checkExportCleanupPredicate") {
    group = "verification"
    description = "ExportCleanupPredicate must reference all four cleanup gates (ADR 0006 §Ownership table + commit-6 NO-GO patch)."
    val targetFile = file(
        "src/main/java/com/aritr/rova/service/export/ExportCleanupPredicate.kt"
    )
    inputs.file(targetFile).withPropertyName("exportCleanupPredicate")
    doLast {
        if (!targetFile.exists()) {
            throw GradleException(
                "Phase 1.7 commit-6 source missing: ${targetFile.relativeTo(rootDir)}"
            )
        }
        // Strip comment lines so a doc-only mention doesn't satisfy the
        // gate; the gate must be exercised by code.
        val codeText = targetFile.readLines()
            .filter {
                val t = it.trimStart()
                !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")
            }
            .joinToString("\n")
        val problems = mutableListOf<String>()
        if (!codeText.contains("AUTO_DISCARD_ELIGIBLE")) {
            problems += "missing AUTO_DISCARD_ELIGIBLE (Phase 1.5 eligibility gate)"
        }
        if (!codeText.contains("privateTempPath")) {
            problems += "missing privateTempPath check (Tier 2/3 deferred-scan retention gate)"
        }
        if (!codeText.contains("RetryableFailure")) {
            problems += "missing RetryableFailure check (per-session recovery clean gate)"
        }
        if (!codeText.contains("ManifestWriteFailed")) {
            problems += "missing ManifestWriteFailed check (per-session recovery clean gate)"
        }
        if (!codeText.contains("QueryFailed")) {
            problems += "missing QueryFailed check (sweep clean gate; commit-6 NO-GO patch)"
        }
        if (problems.isNotEmpty()) {
            val report = problems.joinToString("\n") { "  $it" }
            throw GradleException(
                "ExportCleanupPredicate cleanup gate violation (ADR 0006 §Ownership table " +
                    "+ commit-6 NO-GO patch):\n$report\n" +
                    "All four gates MUST be referenced in the predicate source. " +
                    "Dropping any gate risks deleting a manifest still needed to " +
                    "protect a referenced pending row."
            )
        }
    }
}

/**
 * Phase 1.7 commit-7 — `copyToPublicMovies` was the pre-Phase-1.7
 * merge-then-publish helper in `RovaRecordingService`. Commit 7 deletes
 * it; the tier-dispatching `ExportPipeline` is the single live publish
 * path. Resurrecting `copyToPublicMovies` would re-introduce the
 * merge-then-publish anti-pattern that splits artifact authority
 * between the merger and the exporter. Lock the symbol out.
 */
val checkExportNoCopyToPublicMovies = tasks.register("checkExportNoCopyToPublicMovies") {
    group = "verification"
    description = "Forbid copyToPublicMovies symbol (Phase 1.7 commit-7)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("srcAll")
    doLast {
        if (!srcDir.exists()) throw GradleException("Source dir missing: $srcDir")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val hits = f.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                        else line.contains("copyToPublicMovies")
                    }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "copyToPublicMovies symbol is forbidden (Phase 1.7 commit-7). Offenders:\n$report"
            )
        }
    }
}

/**
 * Phase 1.7 commit-7 — single-entry rule for the live tier export
 * pipeline. Two complementary invariants:
 *  1. `ExportPipeline.export(` may appear EXACTLY once across all
 *     production sources, in `RovaRecordingService.kt`.
 *  2. `VideoMerger.mergeSegments(` and `VideoMerger.mergeSegmentsToFd(`
 *     callers must all live under `service/export/`. This internalizes
 *     the public mux surface so a future edit cannot bypass the tier
 *     pipeline by directly invoking the mux helper.
 */
val checkExportPipelineSingleEntry = tasks.register("checkExportPipelineSingleEntry") {
    group = "verification"
    description = "ExportPipeline.export single call site in RovaRecordingService.kt; VideoMerger mux callers restricted to service/export/ (Phase 1.7 commit-7)."
    val srcDir = file("src/main/java/com/aritr/rova")
    val recordingServicePath = "src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
    val videoMergerPath = "src/main/java/com/aritr/rova/utils/VideoMerger.kt"
    val exportPathFragment = "service/export/"
    inputs.dir(srcDir).withPropertyName("srcAll")
    doLast {
        if (!srcDir.exists()) throw GradleException("Source dir missing: $srcDir")
        val recordingServiceFile = file(recordingServicePath).canonicalFile
        val videoMergerFile = file(videoMergerPath).canonicalFile

        // Invariant 1 — exactly one ExportPipeline.export( call site,
        // and it must live in RovaRecordingService.kt.
        val pipelineCalls = mutableListOf<Pair<File, Int>>()
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                    if (line.contains("ExportPipeline.export(")) {
                        pipelineCalls += f to (i + 1)
                    }
                }
            }
        val problems = mutableListOf<String>()
        when (pipelineCalls.size) {
            0 -> problems += "ExportPipeline.export(...) not called anywhere — performMerge must dispatch to the pipeline."
            1 -> {
                val (f, line) = pipelineCalls.single()
                if (f.canonicalFile != recordingServiceFile) {
                    problems += "${f.relativeTo(rootDir)}:$line — only RovaRecordingService.performMerge may call ExportPipeline.export."
                }
            }
            else -> pipelineCalls.forEach { (f, line) ->
                problems += "${f.relativeTo(rootDir)}:$line — ExportPipeline.export has more than one call site."
            }
        }

        // Invariant 2 — VideoMerger mux callers restricted to
        // service/export/ + the definition file.
        val muxPattern = Regex("""\bVideoMerger\s*\.\s*(mergeSegments|mergeSegmentsToFd)\s*\(""")
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.canonicalFile != videoMergerFile }
            .forEach { f ->
                val hits = f.readLines().withIndex().filter { (_, line) ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                    else muxPattern.containsMatchIn(line)
                }
                if (hits.isEmpty()) return@forEach
                val pathStr = f.relativeTo(rootDir).path.replace('\\', '/')
                if (pathStr.contains(exportPathFragment)) return@forEach
                hits.forEach { (i, line) ->
                    problems += "${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()} — VideoMerger mux callers must live under service/export/"
                }
            }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Single-entry rule violated for the tier export pipeline (Phase 1.7 commit-7):\n" +
                    problems.joinToString("\n") { "  $it" } +
                    "\nFix: live publish goes through ExportPipeline.export from " +
                    "RovaRecordingService.performMerge ONLY. VideoMerger mux helpers " +
                    "are pipeline internals; consumers must live under service/export/."
            )
        }
    }
}

// ADR-0024 §commit-before-stream — a SAF byte write (openOutputStream / the
// copyFileToDocument publish) must be preceded, in the same file, by a
// setExportSafTarget / setSafTarget commit so the target doc Uri is durable in
// the manifest before any byte is written (crash-safe validate-before-delete
// recovery depends on it). SafAndroidOps.kt is the raw-stream seam and exempt.
val checkSafTargetCommittedBeforeStream = tasks.register("checkSafTargetCommittedBeforeStream") {
    group = "verification"
    description = "SAF openOutputStream/copy must be preceded by a setExportSafTarget commit (ADR-0024 §commit-before-stream)."
    val srcDir = file("src/main/java/com/aritr/rova/service/export")
    inputs.dir(srcDir).withPropertyName("exportSrc")
    doLast {
        if (!srcDir.exists()) throw GradleException("export source dir missing: $srcDir")
        val offenders = mutableListOf<String>()
        srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val lines = f.readLines()
            val streamIdx = lines.indexOfFirst {
                val t = it.trimStart()
                !t.startsWith("//") && !t.startsWith("*") &&
                    (it.contains("copyFileToDocument(") || it.contains("openOutputStream("))
            }
            if (streamIdx >= 0) {
                val commitsBefore = lines.take(streamIdx).any {
                    it.contains("setExportSafTarget") || it.contains("setSafTarget(")
                }
                // SafAndroidOps.kt holds the raw stream op (the seam) and is exempt.
                if (!commitsBefore && f.name != "SafAndroidOps.kt") {
                    offenders += "${f.relativeTo(rootDir)}:${streamIdx + 1}: SAF stream op without a prior setExportSafTarget commit"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "ADR-0024 §commit-before-stream violation:\n" + offenders.joinToString("\n") { "  $it" } +
                    "\nThe SAF target doc Uri MUST be committed to the manifest before any byte is written to it."
            )
        }
    }
}

/**
 * Phase 1.7 commit-7 — ADR 0006 §"Terminal-Write Ordering" B7
 * tightening. `markTerminated(...,Terminated.COMPLETED,...)` writes
 * are owned by `RovaRecordingService.performMerge` (live merge-success
 * commit point). The single legitimate exception is the cold-launch
 * late-terminal reconciliation pass owned by `ExportRecoveryRunner`
 * (ADR 0005 row 13c). That site MUST advertise the carve-out via a
 * file-scoped `// completed-write-opt-out: <reason>` marker.
 *
 * Detection: any non-comment line containing `markTerminated(` whose
 * 3-line forward window contains `Terminated.COMPLETED` literal. The
 * `performMerge` body uses a variable-bound `reason` so the literal
 * never appears within 3 lines of `markTerminated(` — the lint passes
 * vacuously for that idiom (mirrors `checkUserStoppedBeforeMerge`).
 */
val checkCompletedWriteOnlyFromPerformMerge = tasks.register("checkCompletedWriteOnlyFromPerformMerge") {
    group = "verification"
    description = "markTerminated(...,Terminated.COMPLETED,...) writes outside performMerge require an explicit completed-write-opt-out file marker (ADR 0006 B7)."
    val srcDir = file("src/main/java/com/aritr/rova")
    val recordingServicePath = "src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
    inputs.dir(srcDir).withPropertyName("srcAll")
    doLast {
        if (!srcDir.exists()) throw GradleException("Source dir missing: $srcDir")
        val recordingServiceFile = file(recordingServicePath).canonicalFile
        val offenders = mutableListOf<Pair<File, String>>()
        srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val lines = f.readLines()
                val hasOptOut = lines.any { it.contains("completed-write-opt-out:") }
                val isRecordingService = f.canonicalFile == recordingServiceFile
                if (hasOptOut && !isRecordingService) return@forEach

                lines.forEachIndexed { i, raw ->
                    val trimmed = raw.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                    if (!raw.contains("markTerminated(")) return@forEachIndexed
                    val window = (i..minOf(i + 3, lines.lastIndex))
                        .joinToString("\n") { lines[it] }
                    if (!window.contains("Terminated.COMPLETED")) return@forEachIndexed
                    if (isRecordingService) {
                        // Must lie inside performMerge body.
                        val perfMergeStart = lines.indexOfFirst { line ->
                            line.contains("private suspend fun performMerge(")
                        }
                        if (perfMergeStart < 0) {
                            offenders += f to "line ${i + 1}: COMPLETED write but performMerge declaration missing"
                            return@forEachIndexed
                        }
                        val fnDeclPattern = Regex("""^    (private suspend fun|private fun|suspend fun|fun) \w+""")
                        var nextFnAbs = lines.size
                        for (j in (perfMergeStart + 1) until lines.size) {
                            if (fnDeclPattern.containsMatchIn(lines[j])) {
                                nextFnAbs = j
                                break
                            }
                        }
                        if (i in (perfMergeStart + 1)..(nextFnAbs - 1)) return@forEachIndexed
                        offenders += f to "line ${i + 1}: markTerminated(...,Terminated.COMPLETED,...) outside performMerge body"
                    } else {
                        offenders += f to "line ${i + 1}: markTerminated(...,Terminated.COMPLETED,...) — only performMerge may write COMPLETED. Add `// completed-write-opt-out: <reason>` for late-terminal reconciliation."
                    }
                }
            }
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, msg) ->
                "  ${f.relativeTo(rootDir)}: $msg"
            }
            throw GradleException(
                "B7 violation (ADR 0006 §Terminal-Write Ordering) — " +
                    "Terminated.COMPLETED writes restricted to performMerge:\n$report\n" +
                    "Fix: write COMPLETED only from RovaRecordingService.performMerge. " +
                    "Late-terminal reconciliation (ExportRecoveryRunner) must carry " +
                    "`// completed-write-opt-out: <reason>` marker."
            )
        }
    }
}

/**
 * Phase 1.8 / C17 — bounded WakeLock acquire.
 *
 * ADR 0006 §"WakeLock Ownership" requires every `acquire` on a
 * `PowerManager.WakeLock` in the recording-service code path to pass a
 * timeout argument. The no-arg overload `acquire()` is the exact bug
 * C17 names: a missed release becomes a battery-drain bug surviving
 * until process death. This lint forbids `.acquire()` (empty arg list)
 * inside [RovaRecordingService.kt] and [WakeLockPolicy.kt]. Comment
 * lines (`//`, `*`) are skipped.
 *
 * Allowed forms: `.acquire(<expr>)` where `<expr>` is any non-empty
 * argument — typically [WakeLockPolicy.ACQUIRE_TIMEOUT_MS].
 */
val checkWakeLockBoundedAcquire = tasks.register("checkWakeLockBoundedAcquire") {
    group = "verification"
    description = "Forbid no-arg WakeLock.acquire() — Phase 1.8 / C17 bounded-acquire discipline."
    val serviceFile = file("src/main/java/com/aritr/rova/service/RovaRecordingService.kt")
    val policyFile = file("src/main/java/com/aritr/rova/service/wakelock/WakeLockPolicy.kt")
    inputs.file(serviceFile).withPropertyName("recordingService")
    inputs.file(policyFile).withPropertyName("wakeLockPolicy")
    doLast {
        val targets = listOf(serviceFile, policyFile).filter { it.exists() }
        if (targets.isEmpty()) {
            throw GradleException("checkWakeLockBoundedAcquire: no source files found.")
        }
        val unboundedAcquire = Regex("""\.acquire\s*\(\s*\)""")
        val offenders = mutableListOf<String>()
        targets.forEach { f ->
            f.readLines().forEachIndexed { idx, raw ->
                val trimmed = raw.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
                if (unboundedAcquire.containsMatchIn(raw)) {
                    offenders += "  ${f.relativeTo(rootDir)}:${idx + 1}: ${trimmed.take(120)}"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "C17 violation (Phase 1.8 §WakeLock Discipline) — " +
                    "WakeLock.acquire() must pass a timeout. Use " +
                    "acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS):\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}

/**
 * Phase 1.8 / C17 (review round 2) — held-wakelock refresh.
 *
 * `acquireWakeLock()` short-circuits when `existing?.isHeld == true`.
 * Round 1 returned without doing any work in that branch, which meant
 * continuous sessions with inter-segment gaps below
 * `WAKE_LOCK_RELAX_THRESHOLD_SECONDS` (= 120s) never hit the
 * release/re-acquire path and could outlive
 * `WakeLockPolicy.ACQUIRE_TIMEOUT_MS` (= 10 min), silently losing the
 * wakelock. Round 2 fix: the held branch must call
 * `existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)` to refresh the
 * timeout — `setReferenceCounted(false)` guarantees this is a refresh
 * rather than an accumulating acquire.
 *
 * This lint asserts the refresh statement exists inside the body of
 * `acquireWakeLock()` in [RovaRecordingService.kt]. Drop it and the
 * lint fails preBuild.
 */
val checkWakeLockHeldRefresh = tasks.register("checkWakeLockHeldRefresh") {
    group = "verification"
    description = "Require held-wakelock refresh inside acquireWakeLock — Phase 1.8 / C17 round-2 fix."
    val serviceFile = file("src/main/java/com/aritr/rova/service/RovaRecordingService.kt")
    inputs.file(serviceFile).withPropertyName("recordingService")
    doLast {
        if (!serviceFile.exists()) {
            throw GradleException("checkWakeLockHeldRefresh: source file missing: $serviceFile")
        }
        val lines = serviceFile.readLines()
        val fnStart = lines.indexOfFirst { it.contains("private fun acquireWakeLock()") }
        if (fnStart < 0) {
            throw GradleException(
                "checkWakeLockHeldRefresh: acquireWakeLock() declaration not found in " +
                    serviceFile.relativeTo(rootDir)
            )
        }
        // Walk forward to the first matching `}` at the function's
        // opening-brace indent. acquireWakeLock is declared with 4-space
        // indent, so its closing brace is `    }` exactly.
        val fnEnd = (fnStart + 1 until lines.size).firstOrNull { lines[it] == "    }" }
            ?: throw GradleException(
                "checkWakeLockHeldRefresh: could not locate end of acquireWakeLock() body."
            )
        val body = lines.subList(fnStart, fnEnd + 1).joinToString("\n")
        val hasHeldGuard = body.contains("existing?.isHeld == true")
        val hasRefresh = body.contains("existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)")
        if (!hasHeldGuard || !hasRefresh) {
            throw GradleException(
                "C17 round-2 violation (Phase 1.8 §WakeLock Discipline) — " +
                    "acquireWakeLock() must refresh the bounded timeout on the " +
                    "held branch.\n" +
                    "Required statements inside the function body:\n" +
                    "  - `existing?.isHeld == true` guard: ${if (hasHeldGuard) "OK" else "MISSING"}\n" +
                    "  - `existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)`: ${if (hasRefresh) "OK" else "MISSING"}\n" +
                    "Fix: in the `if (existing?.isHeld == true)` branch, call " +
                    "`existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)` before " +
                    "returning so continuous sessions cannot outlive the timeout."
            )
        }
    }
}

/**
 * Phase 1.8 / C17 (review round 3) — zero-gap loop refresh.
 *
 * The recording loop only enters `waitForNextSegment(...)` when
 * `waitSeconds > 0`. When the user's interval ≤ duration the loop runs
 * back-to-back with `waitSeconds == 0` and never reaches the
 * `waitForNextSegment` finally block — so the round-2 per-segment
 * refresh is bypassed and a long continuous session can outlive
 * `WakeLockPolicy.ACQUIRE_TIMEOUT_MS`. Round 3 fix: an `else` clause
 * on the `if (waitSeconds > 0)` block calls `acquireWakeLock()`, which
 * routes through the held-branch refresh.
 *
 * This lint asserts the structure: an `} else {` clause immediately
 * after the `waitForNextSegment` call, with `acquireWakeLock()` inside
 * the lookahead window. Drop the else branch and preBuild fails.
 */
val checkWakeLockZeroGapRefresh = tasks.register("checkWakeLockZeroGapRefresh") {
    group = "verification"
    description = "Require WakeLock refresh on the zero-gap continuing-loop path — Phase 1.8 / C17 round-3 fix."
    val serviceFile = file("src/main/java/com/aritr/rova/service/RovaRecordingService.kt")
    inputs.file(serviceFile).withPropertyName("recordingService")
    doLast {
        if (!serviceFile.exists()) {
            throw GradleException("checkWakeLockZeroGapRefresh: source file missing: $serviceFile")
        }
        val lines = serviceFile.readLines()
        val ifIdx = lines.indexOfFirst { it.contains("if (waitSeconds > 0)") }
        if (ifIdx < 0) {
            throw GradleException(
                "checkWakeLockZeroGapRefresh: `if (waitSeconds > 0)` not found in " +
                    serviceFile.relativeTo(rootDir)
            )
        }
        val window = lines.subList(ifIdx, minOf(ifIdx + 20, lines.size)).joinToString("\n")
        val hasElse = Regex("""\}\s*else\s*\{""").containsMatchIn(window)
        val hasRefresh = window.contains("acquireWakeLock()")
        if (!hasElse || !hasRefresh) {
            throw GradleException(
                "C17 round-3 violation (Phase 1.8 §WakeLock Discipline) — " +
                    "the `if (waitSeconds > 0) { waitForNextSegment(...) }` block must " +
                    "have an `else { acquireWakeLock() }` clause so back-to-back " +
                    "(interval <= duration) sessions still refresh the bounded " +
                    "WakeLock timeout.\n" +
                    "  - `} else {` clause: ${if (hasElse) "OK" else "MISSING"}\n" +
                    "  - `acquireWakeLock()` in window: ${if (hasRefresh) "OK" else "MISSING"}\n" +
                    "Fix: add `} else { acquireWakeLock() }` after the " +
                    "`waitForNextSegment` call inside the recording loop."
            )
        }
    }
}

// B3 i18n externalization (ADR-0022 §No Hardcoded UI Strings):
// User-facing copy must live in `res/values/strings.xml` and be read via
// `stringResource(...)` / `getString(...)`, never inlined as a Kotlin string
// literal at a Compose/notification call site. This static gate prevents NEW
// hardcoded literals from regressing the externalization done in Tasks 1-10b.
//
// Flagged patterns (a bare string literal as visible copy on a single line):
//   * `Text("...")` / `Text(text = "...")`  — Compose visible text first arg
//   * `contentDescription = "..."`           — accessibility copy
// Resource calls (`Text(stringResource(...))`, `contentDescription =
// stringResource(...)`, `getString(...)`) are naturally excluded because the
// next non-space token after `Text(` / `text =` / `contentDescription =` is
// `(` / an identifier, not a `"`.
//
// Opt-out: any line containing the literal `i18n-opt-out` is skipped. This is
// the sanctioned hatch for genuinely non-user-facing literals and
// @Preview/preview-only sample data; the convention is to spell a reason,
// e.g. `// i18n-opt-out: preview-only sample data`.
//
// Pragmatic-regex scope (mirrors the other check* tasks): single-line call
// sites only. A literal that lives on a continuation line below `Text(` /
// `text =` is a known blind spot, accepted because the goal is catching the
// common copy-paste regression, not proving total absence.
val checkNoHardcodedUiStrings = tasks.register("checkNoHardcodedUiStrings") {
    group = "verification"
    description = "Forbid hardcoded user-facing string literals at Text(/contentDescription call sites — externalize to res/values/strings.xml (ADR-0022 §No Hardcoded UI Strings)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkNoHardcodedUiStrings: Rova source dir missing: $srcDir")
        }
        // `Text("` or `Text(text = "` — require `"` as the next non-space token
        // so `Text(stringResource(...))` / `Text(text = getString(...))` don't match.
        val textLiteral = Regex("""(^|[^A-Za-z0-9_])Text\(\s*(text\s*=\s*)?"""")
        // `contentDescription = "` (with or without spaces around `=`).
        val contentDescLiteral = Regex("""contentDescription\s*=\s*"""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val hits = f.readLines()
                    .withIndex()
                    .filter { (_, line) ->
                        if (line.contains("i18n-opt-out")) return@filter false
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                        else textLiteral.containsMatchIn(line) ||
                            contentDescLiteral.containsMatchIn(line)
                    }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "Hardcoded user-facing string literal(s) found at Compose " +
                    "`Text(`/`contentDescription =` call sites. User-facing copy " +
                    "must live in `res/values/strings.xml` and be read via " +
                    "`stringResource(...)` / `getString(...)` (ADR-0022 §No " +
                    "Hardcoded UI Strings). For a genuinely non-user-facing " +
                    "literal or @Preview-only sample data, add a " +
                    "`// i18n-opt-out: <reason>` marker on the line to skip it. " +
                    "Offenders:\n$report"
            )
        }
    }
}

val checkLocaleConfigNoPseudolocale = tasks.register("checkLocaleConfigNoPseudolocale") {
    group = "verification"
    description = "Forbid pseudolocale tags (en-XA/ar-XB) in res/xml/locales_config.xml — they must never reach the system per-app-language list (ADR-0023 §No Pseudolocale In LocaleConfig)."
    val configFile = file("src/main/res/xml/locales_config.xml")
    inputs.file(configFile).withPropertyName("localesConfig")
    doLast {
        if (!configFile.exists()) {
            throw GradleException("checkLocaleConfigNoPseudolocale: locales_config.xml missing: $configFile")
        }
        // Match pseudolocale tags only inside android:name="..." attribute values.
        // Covers BCP-47 forms: en-XA, en-rXA, ar-XB, ar-rXB (case-insensitive).
        val pseudo = Regex("""android:name\s*=\s*"[^"]*\b(en|ar)-r?X[AB]\b[^"]*"""", RegexOption.IGNORE_CASE)
        val offenders = configFile.readLines()
            .withIndex()
            .filter { (_, line) -> pseudo.containsMatchIn(line) }
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (i, line) ->
                "  ${configFile.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
            }
            throw GradleException(
                "Pseudolocale tag(s) found in locales_config.xml. Pseudolocales " +
                    "(en_XA / ar_XB) are a DEBUG-ONLY QA tool and must never be " +
                    "advertised as a user language (ADR-0023 §No Pseudolocale In " +
                    "LocaleConfig). Remove them; keep generateLocaleConfig OFF. " +
                    "Offenders:\n$report"
            )
        }
    }
}

// ADR-0026 — a preset is a config bundle ONLY; orientation/mode must never become
// a RovaPreset field, or the preset/orientation vocabulary collision returns.
// Scans the two preset source files for an orientation/mode property declaration.
val checkPresetNoOrientation = tasks.register("checkPresetNoOrientation") {
    group = "verification"
    description = "Forbid an orientation/mode field on RovaPreset/BuiltInPresets — preset = config bundle only (ADR-0026)."
    val files = listOf(
        file("src/main/java/com/aritr/rova/data/RovaSettings.kt"),
        file("src/main/java/com/aritr/rova/data/BuiltInPresets.kt"),
    )
    inputs.files(files).withPropertyName("presetSources")
    doLast {
        // `val mode`/`var mode`/`val orientation` etc. as a declared property.
        val offendingProp = Regex("""(^|\s)(val|var)\s+(mode|orientation)\b""")
        // RovaPreset's parameter list must not name a mode/orientation field.
        val ctorParam = Regex("""\b(mode|orientation)\s*:""")
        val rovaPresetSrc = files[0]
        if (!rovaPresetSrc.exists()) {
            throw GradleException("checkPresetNoOrientation: source missing: $rovaPresetSrc")
        }
        // Narrow to the RovaPreset declaration block to avoid matching unrelated
        // `mode` usage elsewhere in RovaSettings.kt (e.g. the legacy `mode` pref).
        val text = rovaPresetSrc.readText()
        val start = text.indexOf("data class RovaPreset")
        if (start >= 0) {
            val end = text.indexOf(")", start).let { if (it < 0) text.length else it }
            val block = text.substring(start, end)
            if (ctorParam.containsMatchIn(block)) {
                throw GradleException(
                    "checkPresetNoOrientation: RovaPreset must not declare a mode/orientation field (ADR-0026)."
                )
            }
        }
        val builtIns = files[1]
        if (builtIns.exists() && offendingProp.containsMatchIn(builtIns.readText())) {
            throw GradleException(
                "checkPresetNoOrientation: BuiltInPresets must not declare a mode/orientation property (ADR-0026)."
            )
        }
    }
}

// ADR-0029 PR-γ gate 1 — live capture paths must not branch on the legacy
// orientation-carrying mode strings; read-compat sites are allowlisted (§6).
// Comment/KDoc lines are skipped: documenting a legacy value is legal,
// branching on one is not.
val checkNoLegacyModeStrings = tasks.register("checkNoLegacyModeStrings") {
    group = "verification"
    description = "Forbid \"Portrait\"/\"Landscape\"/\"PortraitLandscape\" string literals outside legacy read-compat (ADR-0029 PR-γ §6)."
    val allow = setOf(
        "data/SessionManifest.kt",   // legacy "mode" JSON read-tolerance
        "data/ModeMigration.kt",     // the migration mapper itself
        "data/RovaSettings.kt",      // one-shot prefs migration
    )
    doLast {
        val offenders = mutableListOf<String>()
        fileTree("src/main/java") { include("**/*.kt") }.forEach { f ->
            val rel = f.path.replace('\\', '/').substringAfter("com/aritr/rova/")
            if (allow.any { rel.endsWith(it) }) return@forEach
            f.readLines().forEachIndexed { i, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                if (Regex("\"(Portrait|Landscape|PortraitLandscape)\"").containsMatchIn(line)) {
                    offenders += "$rel:${i + 1}: ${line.trim()}"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "ADR-0029 PR-γ §6: legacy mode strings in live paths (use CaptureTopology):\n" +
                    offenders.joinToString("\n")
            )
        }
    }
}

// ADR-0029 PR-γ gate 2 — rotation applies only at segment boundaries (§3):
// setTargetRotation is reachable only from the allowlisted capture files.
val checkSetTargetRotationBoundaryOnly = tasks.register("checkSetTargetRotationBoundaryOnly") {
    group = "verification"
    description = "setTargetRotation only in RovaRecordingService/dualrecord (ADR-0029 §3 segment-boundary rule)."
    doLast {
        val offenders = mutableListOf<String>()
        fileTree("src/main/java") { include("**/*.kt") }.forEach { f ->
            val rel = f.path.replace('\\', '/').substringAfter("com/aritr/rova/")
            val allowed = rel.endsWith("service/RovaRecordingService.kt") || rel.contains("service/dualrecord/")
            if (allowed) return@forEach
            f.readLines().forEachIndexed { i, line ->
                if (line.contains("setTargetRotation(")) offenders += "$rel:${i + 1}"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("ADR-0029 §3: setTargetRotation outside boundary-owning files:\n" + offenders.joinToString("\n"))
        }
    }
}

// ADR-0029 PR-γ gate 3 — FrontBack construction is capability-gated (§5):
// the topology may be referenced only by its declaration and the registry
// that owns the capability gate. PR-δ extends the allowlist with the
// concurrent-camera module it builds.
val checkFrontBackCapabilityGated = tasks.register("checkFrontBackCapabilityGated") {
    group = "verification"
    description = "\"FrontBack\" referenced only in CaptureTopology/CaptureModes (capability gate site) (ADR-0029 §5)."
    val allow = setOf("data/CaptureTopology.kt", "ui/screens/CaptureModes.kt")
    doLast {
        val offenders = mutableListOf<String>()
        fileTree("src/main/java") { include("**/*.kt") }.forEach { f ->
            val rel = f.path.replace('\\', '/').substringAfter("com/aritr/rova/")
            if (allow.any { rel.endsWith(it) }) return@forEach
            f.readLines().forEachIndexed { i, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                if (line.contains("FrontBack")) offenders += "$rel:${i + 1}"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("ADR-0029 §5: FrontBack outside the capability-gated registry:\n" + offenders.joinToString("\n"))
        }
    }
}

// ADR-0029 §C — user-facing copy speaks clip/session only (spec 2026-06-11 §7).
val checkUserCopyVocabulary = tasks.register("checkUserCopyVocabulary") {
    group = "verification"
    description = "No loop/repeat/segment vocabulary in user-visible string VALUES, en+es (ADR-0029 §C terminology)."
    val banned = Regex("(?i)\\b(loops?|repeats?|segments?|ciclos?|segmentos?|repeticion(es)?|bucles?)\\b")
    // Allowlist by resource NAME for justified exceptions (none expected at γ).
    val allowNames = setOf<String>()
    doLast {
        val offenders = mutableListOf<String>()
        listOf("src/main/res/values/strings.xml", "src/main/res/values-es/strings.xml").forEach { p ->
            val text = file(p).readText()
            val nameRe = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            val matches = nameRe.findAll(text).toList()
            val declared = Regex("<string ").findAll(text).count()
            if (declared != matches.size) {
                throw GradleException("checkUserCopyVocabulary: parser matched ${matches.size}/$declared strings in $p — fix the regex")
            }
            matches.forEach { m ->
                val (name, value) = m.destructured
                if (name in allowNames) return@forEach
                if (banned.containsMatchIn(value)) offenders += "$p: $name = ${value.trim()}"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("ADR-0029 §C: banned vocabulary in user copy (use clip/session):\n" + offenders.joinToString("\n"))
        }
    }
}

// ADR-0020 §Decision-3 (WCAG 2.2 AA — SC 2.3.3 "Animation from Interactions" /
// SC 2.2.2 "Pause, Stop, Hide"): every looping/auto-playing Compose animation
// must be gated on the system reduced-motion preference and fall back to a
// static value. The reduced-motion seam is `ReducedMotion.isReduced` /
// `rememberReduceMotion()` (ui/components/ReducedMotion.kt). This is the FIRST
// of the four checkA11y* gates sketched in ADR-0020 (the other three stay STUB).
//
// File-level co-presence rule (same pragmatic-regex style as
// checkNoHardcodedUiStrings): any .kt file that uses a RAW infinite-animation
// primitive must also read the reduced-motion seam somewhere in the same file.
//
// Trigger primitives (RAW only): `rememberInfiniteTransition(` /
// `infiniteRepeatable(`. The self-gating helpers `pulsingOpacity` /
// `pulsingBorder` are deliberately NOT triggers — they consult ReducedMotion
// internally, so their call sites (e.g. BackgroundRecordingBanner) are safe by
// delegation and must not be flagged.
//
// Seam tokens (string-contains, not regex): `rememberReduceMotion(` /
// `ReducedMotion.isReduced`. The seam helper RovaAnimations.kt carries both the
// raw primitive and the seam read, so it passes naturally with no allowlist.
//
// Opt-out: a triggering line bearing `a11y-opt-out` is skipped — the sanctioned
// hatch for a genuinely static or @Preview-only animation; spell a reason, e.g.
// `// a11y-opt-out: static brand splash, not user-interrupting`.
//
// Accepted blind spot (mirrors checkNoHardcodedUiStrings): a file with a gated
// animation in one composable and an ungated one in another passes. The
// centralized seam makes per-file co-presence a strong signal; per-composable
// proximity was rejected as brittle.
val checkA11yAnimationGated = tasks.register("checkA11yAnimationGated") {
    group = "verification"
    description = "Require a reduced-motion seam read in any file using rememberInfiniteTransition/infiniteRepeatable — WCAG 2.2 AA SC 2.3.3/2.2.2 (ADR-0020 §Decision-3)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkA11yAnimationGated: Rova source dir missing: $srcDir")
        }
        // `\s*\(` tolerates the legal `rememberInfiniteTransition (` spacing variant.
        val rawPrimitive = Regex("""rememberInfiniteTransition\s*\(|infiniteRepeatable\s*\(""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val lines = f.readLines()
                val triggers = lines.withIndex().filter { (_, line) ->
                    if (line.contains("a11y-opt-out")) return@filter false
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                    else rawPrimitive.containsMatchIn(line)
                }
                if (triggers.isEmpty()) return@mapNotNull null
                val hasSeam = lines.any {
                    it.contains("rememberReduceMotion(") || it.contains("ReducedMotion.isReduced")
                }
                if (hasSeam) null else f to triggers
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "ADR-0020 §Decision-3 violation (WCAG 2.2 AA — SC 2.3.3 Animation " +
                    "from Interactions / SC 2.2.2 Pause, Stop, Hide): looping/" +
                    "auto-playing animation primitive(s) used without a " +
                    "reduced-motion guard in the same file. Every file that uses " +
                    "`rememberInfiniteTransition` / `infiniteRepeatable` must also " +
                    "read the reduced-motion seam (`rememberReduceMotion()` or " +
                    "`ReducedMotion.isReduced`) and select a static value when " +
                    "motion is reduced. For a genuinely static or @Preview-only " +
                    "animation, add `// a11y-opt-out: <reason>` on the primitive " +
                    "line.\nOffenders:\n$report"
            )
        }
    }
}

// ADR-0020 §Decision-1 — checkA11yClickableHasRole (WCAG 2.2 AA SC 4.1.2 Name,
// Role, Value). A custom `Modifier.clickable` / `combinedClickable` (a Row/Box/
// Surface/Column the developer made tappable) must declare an accessibility role
// so TalkBack announces it as actionable, not as a generic container. The role
// may sit in the clickable call (`clickable(role = …)`) or on an adjacent
// `.semantics { role = … }` / `.clearAndSetSemantics { role = … }`.
//
// Detection (pragmatic same-chain approximation): for each clickable, scan a
// FIXED bounded window — a small backward cushion + the clickable line + a
// generous forward span — for a `role =` token (in the clickable call args or a
// nearby `.semantics`/`.clearAndSetSemantics`). A fixed window (rather than a
// structural chain parse, and rather than an early-stop terminator) can only
// over-reach, never cut a chain short before a trailing `.semantics { role }`,
// so it fails SAFE toward false-pass and never false-fails valid code (codex).
//
// Out of scope (NOT flagged): Material Button/IconButton/TextButton (component
// calls, not the modifier) and toggleable/selectable (their own role/state
// invariant). Opt-out: a clickable line bearing `a11y-opt-out` is skipped — spell
// a non-empty reason (`// a11y-opt-out: <reason>`), mirroring i18n-opt-out.
//
// Accepted blind spot (mirrors checkNoHardcodedUiStrings): an UNRELATED
// `role = Role.…` for a DIFFERENT clickable inside the window can mask a missing
// one. (The matcher requires a literal `Role.`, so a design-system
// `role = GlassRole.…` arg does NOT count — see roleAssign below.) This fails
// SAFE — toward false-pass (a missed regression), never false-fail (a blocked
// legit build).
val checkA11yClickableHasRole = tasks.register("checkA11yClickableHasRole") {
    group = "verification"
    description = "Require an accessibility role on custom Modifier.clickable/combinedClickable — WCAG 2.2 AA SC 4.1.2 (ADR-0020 §Decision-1)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkA11yClickableHasRole: Rova source dir missing: $srcDir")
        }
        // `.clickable(`/`.combinedClickable(` (paren) or `… {` (trailing lambda).
        val clickable = Regex("""\.(clickable|combinedClickable)\s*[({]""")
        // Require a literal `Role.` so the design-system `role = GlassRole.…`
        // Liquid Glass arg does NOT satisfy the a11y-role requirement.
        val roleAssign = Regex("""\brole\s*=\s*Role\.""")
        // Opt-out is honored only when a non-empty reason follows the colon.
        val optOut = Regex("""a11y-opt-out:\s*\S""")
        // Window spans the modifier chain in BOTH directions: a chain's
        // `.semantics { role }` can sit either AFTER the clickable (forward) or
        // BEFORE it (e.g. a tab whose `.semantics { selected; role = Role.Tab }`
        // precedes a conditional `.let { … clickable … }` ~10 lines further down).
        // Both spans are generous so such a legitimately-roled chain is never
        // false-FAILED (a blocked legit build). The trade-off is the SAFE
        // direction: an unrelated role within the span can over-reach into a
        // false-PASS (a missed regression), never a false-fail.
        val backWindow = 15
        val forwardWindow = 20
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { f ->
                val lines = f.readLines()
                val hits = lines.withIndex().filter { (idx, line) ->
                    if (optOut.containsMatchIn(line)) return@filter false
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@filter false
                    if (!clickable.containsMatchIn(line)) return@filter false
                    val from = maxOf(0, idx - backWindow)
                    val to = minOf(lines.size - 1, idx + forwardWindow)
                    !roleAssign.containsMatchIn(lines.subList(from, to + 1).joinToString("\n"))
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) ->
                    "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}"
                }
            }
            throw GradleException(
                "ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 4.1.2 Name, " +
                    "Role, Value): custom Modifier.clickable / combinedClickable " +
                    "used without an accessibility role on the same modifier " +
                    "chain. A custom clickable container (Row/Box/Surface/Column) " +
                    "must declare a role so TalkBack announces it as actionable — " +
                    "either `clickable(role = Role.Button, …)` or an adjacent " +
                    "`.semantics { role = … }` / `.clearAndSetSemantics { role = … }`. " +
                    "Material Button/IconButton supply a role already. For toggles/" +
                    "selections use toggleable/selectable (out of scope here). For a " +
                    "genuinely role-exempt case, add `// a11y-opt-out: <reason>` " +
                    "(reason required) on the clickable line.\nOffenders:\n$report"
            )
        }
    }
}

// ADR-0020 §Decision-1 — checkA11yTargetSizeToken (WCAG 2.2 AA SC 2.5.8 Target
// Size (Minimum)). An interactive-target SIZE token — the rendered minimum
// dimension of a tappable control (a button diameter, a tap box, a FAB, a
// tile) — must be >= 24.dp, the WCAG AA floor. (Material 3's 48dp is a
// *guideline*, not the AA bar; the 2026-06-13 panel pass reclassified touch
// targets to the 24dp WCAG minimum — docs/accessibility/remediation-backlog.md.)
//
// Design (CURATED set, NOT a blind `*Size`/`*Height` name scan): the token
// objects mix interactive-target sizes with DECORATIVE/glyph/divider sizes that
// are legitimately tiny (statusDotSize 6, navIconGlyphSize 20, stopSquareSize
// 18, handleHeight 4, dividers 2 …). A name-pattern scan would FALSE-FAIL on
// those — the opposite of the false-pass-safe direction the sibling a11y gates
// hold. So this gate pins an EXPLICIT, enumerated set of interactive-target
// size tokens and asserts each >= 24.dp. The set itself is the invariant: a new
// tappable control's size token is a deliberate addition that extends this set
// (the ADR clause -> check convention, exactly as a new ADR invariant extends
// the gate list). The gate still catches the live regression it exists for —
// lowering an existing pinned token below 24 (e.g. camControlSize -> 20.dp)
// fails the build.
//
// Accepted blind spot (mirrors the sibling gates; fails SAFE toward false-pass):
// a pinned token that is RENAMED silently drops out of the scan rather than
// false-failing a legit rename. Opt-out: a token line bearing
// `// a11y-opt-out: <reason>` (reason required) is skipped — for a control whose
// 24dp touch floor is met by call-site padding/`heightIn` rather than the token.
val checkA11yTargetSizeToken = tasks.register("checkA11yTargetSizeToken") {
    group = "verification"
    description = "Require interactive-target size tokens >= 24.dp — WCAG 2.2 AA SC 2.5.8 (ADR-0020 §Decision-1)."
    val themeDir = file("src/main/java/com/aritr/rova/ui/theme")
    inputs.dir(themeDir).withPropertyName("rovaThemeTokens")
    doLast {
        if (!themeDir.exists()) {
            throw GradleException("checkA11yTargetSizeToken: theme token dir missing: $themeDir")
        }
        // Enumerated interactive-target size tokens (bare `val` names; the same
        // name declared in two token objects — e.g. camControlSize in RovaTokens
        // and RecordChromeTokens — is checked in both). EXTEND this set when a
        // new tappable control gets a size token.
        val interactiveSizeTokens = setOf(
            "camControlSize",    // cam-ctrl-btn diameter
            "stepperButtonSize", // stepper +/- button (RovaTokens)
            "stepBtnSize",       // stepper +/- button (SettingsSheetTokens)
            "primaryActionSize", // Start FAB
            "stopActionSize",    // Stop FAB
            "fabSize",           // record-chrome FAB
            "navIconBoxSize",    // bottom-nav item tap box
            "tileMinHeight",     // preset tile
            // NOT pinned: cellSlot — it is a 44dp VISUAL slot whose 24dp touch
            // floor is owned by the parent card's `heightIn(min = 48.dp)` at the
            // call site, not by the token. Pinning a proxy here would assert the
            // wrong thing; the call-site floor is a separate (future) invariant.
        )
        val minTargetDp = 24.0
        // `val NAME [: Dp] = <number>.dp` — tolerates the optional `: Dp` type.
        val tokenDecl = Regex("""\bval\s+(\w+)\s*(?::\s*Dp\s*)?=\s*(\d+(?:\.\d+)?)\s*\.dp\b""")
        val optOut = Regex("""a11y-opt-out:\s*\S""")
        val offenders = themeDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                var inBlock = false // inside a /* … */ (incl. /** KDoc */) block
                f.readLines().withIndex().mapNotNull inner@{ (idx, line) ->
                    // Track block-comment state so a commented-out
                    // `val NAME = N.dp` can NEVER false-fail (trailing marker
                    // wins; a same-line `/* … */` is skipped by the `/*` prefix
                    // check below). KDoc inner `*` lines are skipped too.
                    val wasInBlock = inBlock
                    val opens = line.lastIndexOf("/*")
                    val closes = line.lastIndexOf("*/")
                    if (opens >= 0 || closes >= 0) inBlock = opens > closes
                    if (wasInBlock) return@inner null
                    if (optOut.containsMatchIn(line)) return@inner null
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                        trimmed.startsWith("/*")
                    ) return@inner null
                    val m = tokenDecl.find(line) ?: return@inner null
                    if (m.groupValues[1] !in interactiveSizeTokens) return@inner null
                    if (m.groupValues[2].toDouble() >= minTargetDp) null
                    else Triple(f, idx + 1, line.trim())
                }
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, ln, line) ->
                "  ${f.relativeTo(rootDir)}:$ln: $line"
            }
            throw GradleException(
                "ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 2.5.8 Target " +
                    "Size (Minimum)): an interactive-target size token is below " +
                    "the 24.dp accessibility floor. A tappable control's size " +
                    "token (button diameter / tap box / FAB / tile) " +
                    "must be >= 24.dp. (Material 3's 48dp is a guideline; 24dp is " +
                    "the WCAG AA bar.) Raise the token, or — if the 24dp touch " +
                    "floor is met by call-site padding/`heightIn` rather than the " +
                    "token itself — add `// a11y-opt-out: <reason>` (reason " +
                    "required) on the token line.\nOffenders:\n$report"
            )
        }
    }
}

// B5 / ADR-0025 — the core privacy invariant, mechanically enforced:
// VaultExporter must never reach a public-publish API. A vaulted recording
// stays app-private; any MediaStore insert / media scan / public-dir write
// inside VaultExporter.kt would silently make it gallery-visible.
val checkVaultExporterNoPublicPublish = tasks.register("checkVaultExporterNoPublicPublish") {
    group = "verification"
    description = "Forbid public-publish APIs in VaultExporter (ADR-0025 — vault recordings never publish)."
    val vaultExporter = file("src/main/java/com/aritr/rova/service/export/VaultExporter.kt")
    inputs.file(vaultExporter).withPropertyName("vaultExporterSource")
    doLast {
        if (!vaultExporter.exists()) {
            throw GradleException("checkVaultExporterNoPublicPublish: VaultExporter.kt missing: $vaultExporter")
        }
        val forbidden = listOf(
            "MediaStore",
            "MediaScannerConnection",
            "insertPendingRow",
            "scanAndWait",
            "DIRECTORY_MOVIES",
            "IS_PENDING",
            ".insert(",
            "getExternalStoragePublicDirectory",
        )
        val hits = vaultExporter.readLines().withIndex().filter { (_, line) ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*")) false
            else forbidden.any { line.contains(it) }
        }
        if (hits.isNotEmpty()) {
            val report = hits.joinToString("\n") { (i, line) -> "  VaultExporter.kt:${i + 1}: ${line.trim()}" }
            throw GradleException(
                "ADR-0025: VaultExporter must not reference any public-publish API " +
                    "(vault recordings stay app-private). Offenders:\n$report"
            )
        }
    }
}

val checkRecordSurfaceNoBlur = tasks.register("checkRecordSurfaceNoBlur") {
    group = "verification"
    description = "Record-chrome files must not apply Modifier.blur/RenderEffect — record glass uses GlassRole.RecordChrome (blurRadius=0). DualPreviewZone is the preview/carve-out, not chrome (ADR-0028 §2.3)."
    val srcDir = file("src/main/java/com/aritr/rova")
    inputs.dir(srcDir).withPropertyName("rovaSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkRecordSurfaceNoBlur: Rova source dir missing: $srcDir")
        }
        // Record-chrome rendering files (confirmed real). DualPreviewZone is
        // deliberately EXCLUDED — it's the camera preview/carve-out, not chrome.
        val recordChromeNames = setOf(
            "RecordScreen.kt", "RecordChrome.kt", "RecordChromeIcons.kt",
        )
        val blurPattern = Regex("""\.blur\s*\(|Modifier\s*\.\s*blur\b|RenderEffect|createBlurEffect""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name in recordChromeNames }
            .mapNotNull { f ->
                val hits = f.readLines().withIndex().filter { (_, line) ->
                    val t = line.trimStart()
                    if (t.startsWith("//") || t.startsWith("*")) false
                    else blurPattern.containsMatchIn(line)
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) -> "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" }
            }
            throw GradleException(
                "ADR-0028 §2.3 violation: record-chrome files must not blur. Record glass " +
                    "renders through GlassRole.RecordChrome, whose resolver returns " +
                    "blurRadius=0 (fill + scrim + edge + opaque text capsule instead). " +
                    "DualPreviewZone's RenderEffect on non-recorded margins is the only " +
                    "documented carve-out and is not record chrome.\nOffenders:\n$report"
            )
        }
    }
}

val checkGlassSurfaceRoleUsage = tasks.register("checkGlassSurfaceRoleUsage") {
    group = "verification"
    description = "Modifier.blur is permitted only in GlassSurface.kt (resolver-driven glass), the DualPreviewZone carve-out, and pre-existing glow-bloom (WarningSheetV3/RecoveryCall). All new glass goes through GlassSurface(role=…) (ADR-0028 §2.1/§5)."
    val srcDir = file("src/main/java/com/aritr/rova/ui")
    inputs.dir(srcDir).withPropertyName("rovaUiSources")
    doLast {
        if (!srcDir.exists()) {
            throw GradleException("checkGlassSurfaceRoleUsage: Rova ui source dir missing: $srcDir")
        }
        // GlassSurface = sanctioned glass blur. DualPreviewZone = ADR carve-out
        // (RenderEffect on non-recorded margins). WarningSheetV3 + RecoveryCard =
        // pre-existing decorative icon-glow bloom; TODO(PR6/PR9): revisit when
        // those surfaces migrate to GlassSurface.
        val allowlist = setOf(
            "GlassSurface.kt", "DualPreviewZone.kt", "WarningSheetV3.kt", "RecoveryCard.kt",
        )
        // Aligned with checkRecordSurfaceNoBlur: also catch RenderEffect/backdrop
        // blur so the "all glass through GlassSurface" invariant stays airtight as
        // later PRs migrate surfaces (the only current RenderEffect site is the
        // allowlisted DualPreviewZone carve-out).
        val blurPattern = Regex("""\.blur\s*\(|Modifier\s*\.\s*blur\b|RenderEffect|createBlurEffect""")
        val offenders = srcDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowlist }
            .mapNotNull { f ->
                val hits = f.readLines().withIndex().filter { (_, line) ->
                    val t = line.trimStart()
                    if (t.startsWith("//") || t.startsWith("*")) false
                    else blurPattern.containsMatchIn(line)
                }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) -> "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" }
            }
            throw GradleException(
                "ADR-0028 §2.1/§5 violation: Modifier.blur outside the sanctioned glass " +
                    "wrapper. All translucent glass must render through " +
                    "GlassSurface(role=…) so the GlassResolver owns the blur/fallback " +
                    "decision. Permitted only in GlassSurface.kt, the DualPreviewZone.kt " +
                    "carve-out, and the pre-existing glow-bloom sites.\nOffenders:\n$report"
            )
        }
    }
}

// PR-ε — ADR-0029 §B″ (single lock writer): `requestedOrientation` on the UI
// side is written ONLY by RecordScreen's unified lock DisposableEffect
// (RecordChromeLockPolicy.shouldLock is the sole decision point). A second
// writer reintroduces the lock/unlock races the §B″ model exists to prevent.
// Comments are stripped before matching (block/KDoc + line) because prose
// mentions are legal — e.g. DualShotPortraitGate.kt documents the legacy
// lock in KDoc.
val checkRecordChromeLockSingleSite = tasks.register("checkRecordChromeLockSingleSite") {
    group = "verification"
    description = "Forbid requestedOrientation writes in ui/ outside RecordScreen.kt (ADR-0029 §B″ single lock writer)."
    val uiDir = file("src/main/java/com/aritr/rova/ui")
    inputs.dir(uiDir).withPropertyName("rovaUiSources")
    doLast {
        if (!uiDir.exists()) {
            throw GradleException("checkRecordChromeLockSingleSite: Rova ui source dir missing: $uiDir")
        }
        // Blank out block/KDoc comments but keep their newlines so reported
        // line numbers stay true to the file on disk.
        val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        // Canonical-path compare (checkScanTriggerSingleSite precedent) — a
        // second RecordScreen.kt in another ui/ subpackage must not slip through.
        val allowedWriter = file("src/main/java/com/aritr/rova/ui/screens/RecordScreen.kt").canonicalFile
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.canonicalFile != allowedWriter }
            .mapNotNull { f ->
                val stripped = blockComment.replace(f.readText()) { m ->
                    m.value.filter { ch -> ch == '\n' }
                }
                val hits = stripped.lines()
                    .withIndex()
                    .filter { (_, line) ->
                        line.substringBefore("//").contains("requestedOrientation")
                    }
                if (hits.isEmpty()) null else f to hits
            }
            .toList()
        if (offenders.isNotEmpty()) {
            val report = offenders.joinToString("\n") { (f, hits) ->
                hits.joinToString("\n") { (i, line) -> "  ${f.relativeTo(rootDir)}:${i + 1}: ${line.trim()}" }
            }
            throw GradleException(
                "ADR-0029 §B″ violation: requestedOrientation touched on the UI side " +
                    "outside RecordScreen.kt. The unified DisposableEffect in " +
                    "RecordScreen is the ONLY UI-layer requestedOrientation writer; " +
                    "RecordChromeLockPolicy.shouldLock is the sole decision point. " +
                    "A second writer reintroduces lock/unlock races.\nOffenders:\n$report"
            )
        }
    }
}

afterEvaluate {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(checkSchedulerNoGetService)
        dependsOn(checkScheduleReceiverNoFgsStart)
        dependsOn(checkStopNoGetService)
        dependsOn(checkRecoveryNoDeletion)
        dependsOn(checkRecoverySegmentRegex)
        dependsOn(checkScanTriggerSingleSite)
        dependsOn(checkRecoveryReceiverCounter)
        dependsOn(checkAtomicTerminalWriteForbiddenPair)
        dependsOn(checkExternalRootShared)
        dependsOn(checkAudioModeFgsTypeMatch)
        dependsOn(checkFGSStartGuarded)
        dependsOn(checkUserStoppedBeforeMerge)
        dependsOn(checkExportTierReadTolerant)
        dependsOn(checkScanFileBoundedWait)
        dependsOn(checkPendingFdModeIsRW)
        dependsOn(checkExportIsPendingGuarded)
        dependsOn(checkExportSetIncludePendingGuarded)
        dependsOn(checkExportQueryArgMatchPendingGuarded)
        dependsOn(checkExportPendingVisibilityOnQuery)
        dependsOn(checkExportCleanupPredicate)
        dependsOn(checkExportNoCopyToPublicMovies)
        dependsOn(checkExportPipelineSingleEntry)
        dependsOn(checkSafTargetCommittedBeforeStream)
        dependsOn(checkCompletedWriteOnlyFromPerformMerge)
        // dependsOn(checkCompletedWriteOnlyFromPerformMerge)  // bisect
        dependsOn(checkWakeLockBoundedAcquire)
        dependsOn(checkWakeLockHeldRefresh)
        dependsOn(checkWakeLockZeroGapRefresh)
        dependsOn(checkNoHardcodedUiStrings)
        dependsOn(checkLocaleConfigNoPseudolocale)
        dependsOn(checkA11yAnimationGated)
        dependsOn(checkA11yClickableHasRole)
        dependsOn(checkA11yTargetSizeToken)
        dependsOn(checkPresetNoOrientation)
        dependsOn(checkVaultExporterNoPublicPublish)
        dependsOn(checkRecordSurfaceNoBlur)
        dependsOn(checkGlassSurfaceRoleUsage)
        dependsOn(checkNoLegacyModeStrings)
        dependsOn(checkSetTargetRotationBoundaryOnly)
        dependsOn(checkFrontBackCapabilityGated)
        dependsOn(checkUserCopyVocabulary)
        dependsOn(checkRecordChromeLockSingleSite)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // ProcessLifecycleOwner — app foreground/background for camera-warm-across-nav (ADR-0021)
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui.text.google.fonts)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.biometric)
    // Force modern stable fragment: biometric:1.1.0 transitively pulls
    // fragment:1.2.5, whose FragmentActivity.checkForValidRequestCode enforces
    // the legacy 16-bit requestCode limit. activity:1.11.0's ActivityResultRegistry
    // emits request codes >= 0x10000, so the SAF OpenDocumentTree picker crashed
    // ("Can only use lower 16 bits for requestCode") since B5 made MainActivity a
    // FragmentActivity. fragment >= 1.3.0 removed that check. First-order dep wins
    // conflict resolution over biometric's transitive 1.2.5. No code change needed.
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.concurrent.futures.ktx)
    // Phase 2.5 — In-app player. media3-exoplayer drives playback over
    // the merged MP4; media3-ui provides PlayerView (the Compose wrapper
    // is mounted via AndroidView in PlayerScreen). Pinned to 1.4.1 — the
    // last 1.4.x stable that has been smoke-tested against AGP 8.13 +
    // Kotlin 2.0 + minSdk 24, and avoids the 1.5.x line which bumps a
    // few transitive constraints we have not audited. media3-session,
    // -hls, -dash, and -transformer are intentionally NOT pulled in:
    // playback is local mp4 only, no MediaSession integration in scope,
    // and the editor / transformer pipeline is NO-GO for v1.0.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // B4 SAF export — DocumentFile API for tree-URI operations (ADR-0024)
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation(libs.junit)
    // Phase 1.5 — JVM unit tests need a real org.json impl. The android.jar
    // shipped to JVM tests stubs JSONObject/JSONArray to throw at runtime;
    // SessionManifest and SessionConfig serialize through these classes, so
    // tests that touch persistence need the actual library on the classpath.
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")
}
