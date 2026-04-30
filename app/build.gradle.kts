plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aritr.rova"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.aritr.rova"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
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

afterEvaluate {
    tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(checkSchedulerNoGetService)
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.concurrent.futures.ktx)
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