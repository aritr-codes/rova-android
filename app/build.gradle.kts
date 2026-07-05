import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    id("com.aritr.rova.checks")
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
        versionCode = 5
        versionName = "0.10.0"

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
val checkStopNoGetService = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkStopNoGetService") {
    group = "verification"
    description = "Forbid PendingIntent.getService — STOP and tick actions must use broadcast (Android 12+ FGS-from-background restriction)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkStopNoGetService")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkStopNoGetService.ok"))
}

// ADR-0027 — the daily-window receivers must NEVER start the camera FGS.
// Android 14+ forbids starting a while-in-use FGS from the background; the
// only legal camera-start site is MainActivity on the user's notification tap.
// Forbid getService / startForegroundService / startService under service/schedule/.
val checkScheduleReceiverNoFgsStart = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkScheduleReceiverNoFgsStart") {
    group = "verification"
    description = "Schedule receivers (service/schedule/) must never start the camera FGS (ADR-0027)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/service/schedule")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkScheduleReceiverNoFgsStart")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkScheduleReceiverNoFgsStart.ok"))
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
val checkRecoveryNoDeletion = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkRecoveryNoDeletion") {
    group = "verification"
    description = "Forbid deletion APIs in Phase 1.5 sources (ADR 0005 — emits flags, never deletes)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/service/recovery")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    sources.from(
        layout.projectDirectory.file("src/main/java/com/aritr/rova/RovaApp.kt")
    )
    checkId.set("checkRecoveryNoDeletion")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkRecoveryNoDeletion.ok"))
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
val checkRecoverySegmentRegex = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkRecoverySegmentRegex") {
    group = "verification"
    description = "Forbid `seg_` variant in Phase 1.5 sources; canonical pattern is `segment_NNNN.mp4` (ADR 0005)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/service/recovery")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkRecoverySegmentRegex")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkRecoverySegmentRegex.ok"))
}

// Phase 1.5 — ADR 0005 §"Acceptance Criteria" lint #3:
// `runRecoveryScan` is the private helper invoked solely by
// RovaApp.triggerRecoveryScanIfNeeded. Calling it from anywhere else —
// a receiver, a scheduled job, MainActivity directly — would defeat
// Guard A (latch reset on throw) and the trigger-boundary invariant
// designed in round 2. Forbid every reference outside RovaApp.kt.
val checkScanTriggerSingleSite = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkScanTriggerSingleSite") {
    group = "verification"
    description = "Forbid runRecoveryScan references outside RovaApp.kt (ADR 0005 §Scan Trigger Boundary)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkScanTriggerSingleSite")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkScanTriggerSingleSite.ok"))
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
val checkRecoveryReceiverCounter = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkRecoveryReceiverCounter") {
    group = "verification"
    description = "Verify Guard B (ADR 0005): receivers using goAsync() must increment activeReceiverWork before goAsync() and decrement in finally."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/service")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkRecoveryReceiverCounter")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkRecoveryReceiverCounter.ok"))
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
val checkAtomicTerminalWriteForbiddenPair = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkAtomicTerminalWriteForbiddenPair") {
    group = "verification"
    description = "Forbid markTerminated(USER_STOPPED, NONE) literal pair (ADR 0006 B16)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkAtomicTerminalWriteForbiddenPair")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkAtomicTerminalWriteForbiddenPair.ok"))
}

/**
 * ADR 0006 B21: only `RovaApp.resolveExternalRoot` may call
 * `getExternalFilesDir(null)`. All other code reads
 * `RovaApp.videosRoot` / `RovaApp.externalRoot`. The deprecated
 * `SessionStore(Context)` constructor uses it inside a fallback that
 * itself errors out.
 */
val checkExternalRootShared = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkExternalRootShared") {
    group = "verification"
    description = "Only RovaApp.resolveExternalRoot may reference getExternalFilesDir(null) (ADR 0006 B21)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkExternalRootShared")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkExternalRootShared.ok"))
}

/**
 * ADR 0006 B18: `FOREGROUND_SERVICE_TYPE_MICROPHONE` reference must be
 * preceded (lower line index) by an `audioMode` reference within the
 * same source file. Forbidden: hardcoded `CAMERA | MICROPHONE` without
 * an audio-mode gate. Caught Round 6 B20 as draft-residue regression.
 */
val checkAudioModeFgsTypeMatch = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkAudioModeFgsTypeMatch") {
    group = "verification"
    description = "FGS_TYPE_MICROPHONE must be gated by audioMode (ADR 0006 B18 + B20)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkAudioModeFgsTypeMatch")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkAudioModeFgsTypeMatch.ok"))
}

/**
 * ADR 0006 B11 + B20: every `startForegroundService` (caller side) and
 * `startForeground(` (service side) reference must be inside a
 * `try { ... } catch (e: IllegalStateException)` block. Service-side
 * sites also require a `catch (e: SecurityException)` arm. The catch
 * block must reference `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S`
 * before any `is ForegroundServiceStartNotAllowedException` check.
 */
val checkFGSStartGuarded = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkFGSStartGuarded") {
    group = "verification"
    description = "FGS start sites must catch IllegalStateException + SecurityException with SDK-gated is-check (ADR 0006 B10 + B11 + B20)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkFGSStartGuarded")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkFGSStartGuarded.ok"))
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
val checkUserStoppedBeforeMerge = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkUserStoppedBeforeMerge") {
    group = "verification"
    description = "USER_STOPPED markTerminated must precede merge call sites in RovaRecordingService.kt (ADR 0006 B3); COMPLETED must follow them (B7)."
    sources.from(
        layout.projectDirectory.file("src/main/java/com/aritr/rova/service/RovaRecordingService.kt")
    )
    checkId.set("checkUserStoppedBeforeMerge")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkUserStoppedBeforeMerge.ok"))
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
val checkExportTierReadTolerant = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkExportTierReadTolerant") {
    group = "verification"
    description = "Forbid getString(\"exportTier\") — schema-2 manifests lack the field; use opt + runCatching + currentExportTier() fallback (ADR 0003 §FD Mode Amendment partner)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkExportTierReadTolerant")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkExportTierReadTolerant.ok"))
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
val checkScanFileBoundedWait = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkScanFileBoundedWait") {
    group = "verification"
    description = "MediaScannerConnection.scanFile must only be called inside the bounded MediaScanWaiter helper (Phase 1.7 Patch 2)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkScanFileBoundedWait")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkScanFileBoundedWait.ok"))
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
val checkPendingFdModeIsRW = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkPendingFdModeIsRW") {
    group = "verification"
    description = "Tier 1 sources must use openFileDescriptor mode \"rw\"; \"w\" is non-seekable and breaks MediaMuxer.stop() (ADR 0003 §FD Mode Amendment)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/service/export")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkPendingFdModeIsRW")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkPendingFdModeIsRW.ok"))
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
val checkExportIsPendingGuarded = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkExportIsPendingGuarded") {
    group = "verification"
    description = "IS_PENDING references in service/export/ must be SDK-gated to API 29+ (ADR 0003 Tier 1)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/service/export")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkExportIsPendingGuarded")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkExportIsPendingGuarded.ok"))
}

/**
 * Phase 1.7 commit-5 — `MediaStore.setIncludePending` was deprecated
 * in API 30 (no-op or worse on R+). It is the legitimate pending-row
 * visibility mechanism on API 29 ONLY. Every reference inside
 * `service/export/` must appear within a `Build.VERSION_CODES.R`
 * SDK branch (≤30 lines from a `VERSION_CODES.R` token), forcing the
 * call to live in the `< R` arm of an SDK comparison.
 */
val checkExportSetIncludePendingGuarded = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkExportSetIncludePendingGuarded") {
    group = "verification"
    description = "setIncludePending references in service/export/ must be SDK-gated against Build.VERSION_CODES.R (Tier 1; deprecated/forbidden API 30+)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/service/export")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkExportSetIncludePendingGuarded")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkExportSetIncludePendingGuarded.ok"))
}

/**
 * Phase 1.7 commit-5 — `MediaStore.QUERY_ARG_MATCH_PENDING` does not
 * exist below API 30. Every reference inside `service/export/` must
 * appear near a `Build.VERSION_CODES.R` SDK branch, forcing the
 * `>= R` arm. A reference outside an SDK guard would `NoSuchFieldError`
 * on Android Q.
 */
val checkExportQueryArgMatchPendingGuarded = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkExportQueryArgMatchPendingGuarded") {
    group = "verification"
    description = "QUERY_ARG_MATCH_PENDING references in service/export/ must be SDK-gated against Build.VERSION_CODES.R (Tier 1; API 30+ only)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/service/export")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkExportQueryArgMatchPendingGuarded")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkExportQueryArgMatchPendingGuarded.ok"))
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
val checkExportPendingVisibilityOnQuery = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkExportPendingVisibilityOnQuery") {
    group = "verification"
    description = "Files in service/export/ that call resolver.query(...) must use BOTH visibility mechanisms AND an explicit IS_PENDING = 1 SQL selection (Tier 1; commit-5 NO-GO patch)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/service/export")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkExportPendingVisibilityOnQuery")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkExportPendingVisibilityOnQuery.ok"))
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
val checkExportCleanupPredicate = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkExportCleanupPredicate") {
    group = "verification"
    description = "ExportCleanupPredicate must reference all four cleanup gates (ADR 0006 §Ownership table + commit-6 NO-GO patch)."
    sources.from(
        layout.projectDirectory.file("src/main/java/com/aritr/rova/service/export/ExportCleanupPredicate.kt")
    )
    checkId.set("checkExportCleanupPredicate")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkExportCleanupPredicate.ok"))
}

/**
 * Phase 1.7 commit-7 — `copyToPublicMovies` was the pre-Phase-1.7
 * merge-then-publish helper in `RovaRecordingService`. Commit 7 deletes
 * it; the tier-dispatching `ExportPipeline` is the single live publish
 * path. Resurrecting `copyToPublicMovies` would re-introduce the
 * merge-then-publish anti-pattern that splits artifact authority
 * between the merger and the exporter. Lock the symbol out.
 */
val checkExportNoCopyToPublicMovies = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkExportNoCopyToPublicMovies") {
    group = "verification"
    description = "Forbid copyToPublicMovies symbol (Phase 1.7 commit-7)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkExportNoCopyToPublicMovies")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkExportNoCopyToPublicMovies.ok"))
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
val checkExportPipelineSingleEntry = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkExportPipelineSingleEntry") {
    group = "verification"
    description = "ExportPipeline.export single call site in RovaRecordingService.kt; VideoMerger mux callers restricted to service/export/ (Phase 1.7 commit-7)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkExportPipelineSingleEntry")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkExportPipelineSingleEntry.ok"))
}

// ADR-0024 §commit-before-stream — a SAF byte write (openOutputStream / the
// copyFileToDocument publish) must be preceded, in the same file, by a
// setExportSafTarget / setSafTarget commit so the target doc Uri is durable in
// the manifest before any byte is written (crash-safe validate-before-delete
// recovery depends on it). SafAndroidOps.kt is the raw-stream seam and exempt.
val checkSafTargetCommittedBeforeStream = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkSafTargetCommittedBeforeStream") {
    group = "verification"
    description = "SAF openOutputStream/copy must be preceded by a setExportSafTarget commit (ADR-0024 §commit-before-stream)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/service/export")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkSafTargetCommittedBeforeStream")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkSafTargetCommittedBeforeStream.ok"))
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
val checkCompletedWriteOnlyFromPerformMerge = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkCompletedWriteOnlyFromPerformMerge") {
    group = "verification"
    description = "markTerminated(...,Terminated.COMPLETED,...) writes outside performMerge require an explicit completed-write-opt-out file marker (ADR 0006 B7)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkCompletedWriteOnlyFromPerformMerge")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkCompletedWriteOnlyFromPerformMerge.ok"))
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
val checkWakeLockBoundedAcquire = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkWakeLockBoundedAcquire") {
    group = "verification"
    description = "Forbid no-arg WakeLock.acquire() — Phase 1.8 / C17 bounded-acquire discipline."
    sources.from(layout.projectDirectory.file("src/main/java/com/aritr/rova/service/RovaRecordingService.kt"))
    sources.from(layout.projectDirectory.file("src/main/java/com/aritr/rova/service/wakelock/WakeLockPolicy.kt"))
    checkId.set("checkWakeLockBoundedAcquire")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkWakeLockBoundedAcquire.ok"))
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
val checkWakeLockHeldRefresh = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkWakeLockHeldRefresh") {
    group = "verification"
    description = "Require held-wakelock refresh inside acquireWakeLock — Phase 1.8 / C17 round-2 fix."
    sources.from(layout.projectDirectory.file("src/main/java/com/aritr/rova/service/RovaRecordingService.kt"))
    checkId.set("checkWakeLockHeldRefresh")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkWakeLockHeldRefresh.ok"))
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
val checkWakeLockZeroGapRefresh = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkWakeLockZeroGapRefresh") {
    group = "verification"
    description = "Require WakeLock refresh on the zero-gap continuing-loop path — Phase 1.8 / C17 round-3 fix."
    sources.from(layout.projectDirectory.file("src/main/java/com/aritr/rova/service/RovaRecordingService.kt"))
    checkId.set("checkWakeLockZeroGapRefresh")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkWakeLockZeroGapRefresh.ok"))
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
val checkNoHardcodedUiStrings = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkNoHardcodedUiStrings") {
    group = "verification"
    description = "Forbid hardcoded user-facing string literals at Text(/contentDescription call sites — externalize to res/values/strings.xml (ADR-0022 §No Hardcoded UI Strings)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkNoHardcodedUiStrings")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkNoHardcodedUiStrings.ok"))
}

val checkLocaleConfigNoPseudolocale = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkLocaleConfigNoPseudolocale") {
    group = "verification"
    description = "Forbid pseudolocale tags (en-XA/ar-XB) in res/xml/locales_config.xml — they must never reach the system per-app-language list (ADR-0023 §No Pseudolocale In LocaleConfig)."
    sources.from(
        layout.projectDirectory.file("src/main/res/xml/locales_config.xml")
    )
    checkId.set("checkLocaleConfigNoPseudolocale")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkLocaleConfigNoPseudolocale.ok"))
}

// ADR-0026 — a preset is a config bundle ONLY; orientation/mode must never become
// a RovaPreset field, or the preset/orientation vocabulary collision returns.
// Scans the two preset source files for an orientation/mode property declaration.
val checkPresetNoOrientation = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkPresetNoOrientation") {
    group = "verification"
    description = "Forbid an orientation/mode field on RovaPreset/BuiltInPresets — preset = config bundle only (ADR-0026)."
    sources.from(layout.projectDirectory.file("src/main/java/com/aritr/rova/data/RovaSettings.kt"))
    sources.from(layout.projectDirectory.file("src/main/java/com/aritr/rova/data/BuiltInPresets.kt"))
    checkId.set("checkPresetNoOrientation")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkPresetNoOrientation.ok"))
}

// ADR-0030 gate (42nd) — Library/History UI must never mutate a SessionManifest.
// Favorite/rename/lastPlayedAt go only through LibraryMetadataStore (sidecar),
// so the terminal-state write race is impossible. Forbid the manifest-mutating
// SessionStore setters in any ui/library or ui/screens History source. Read-only
// APIs (loadManifest/listSessionIds) and discardSession (file delete, not a
// manifest write) are allowed. Comment/KDoc lines are skipped. A line carrying the
// exact `ADR-0030-allow: recovery-keep-raw` marker is exempt — reserved for the ONE
// recovery-subsystem terminal write (recovery-keep MULTI_SEGMENT_KEPT) co-located in
// HistoryScreen but owned by the recovery flow (ADR-0005), not Library metadata. The
// gate asserts that marker appears exactly once, in HistoryScreen.kt, on a
// markTerminated call — a stray marker anywhere else is itself a failure. Matching is
// regex (`\bname\s*\(`) so a forbidden call survives reformatting (e.g. `name (`).
val checkLibraryNoManifestWrite = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkLibraryNoManifestWrite") {
    group = "verification"
    description = "Library/History UI must not call SessionManifest-mutating SessionStore APIs (ADR-0030 §2)."
    sources.from(
        layout.projectDirectory.dir("src/main/java").asFileTree.matching { include("**/*.kt") }
    )
    checkId.set("checkLibraryNoManifestWrite")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkLibraryNoManifestWrite.ok"))
}

// ADR-0031 §4 (P0) — every in-app glyph tint flows through the SemanticIcon seam. A raw Color literal
// bound to a `tint =` argument bypasses the theme engine's single icon-color contract. Keyed on the
// `tint =` argument (offenders are multi-line Icon(...) calls), scanned over a 3-line window so a
// wrapped/conditional Color value is still caught. The seam file is allowlisted by canonical path.
val checkSemanticIconNoRawAlpha = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkSemanticIconNoRawAlpha") {
    group = "verification"
    description = "Forbid a raw Color literal as an Icon tint outside the SemanticIcon seam — all glyph " +
        "color must flow through SemanticIcon/SemanticIconSpec (ADR-0031 §4)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova").asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkSemanticIconNoRawAlpha")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkSemanticIconNoRawAlpha.ok"))
}

// ADR-0031 §3 (P0) — locked semantic status colors. A RovaSemantics color is exact and used at full
// opacity; mutating it at a call-site (.copy of alpha or any channel) breaks the lock. The status→
// RovaSemantics mapping itself is covered by SemanticIconSpecTest; this gate enforces no-mutation everywhere.
val checkStatusColorLocked = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkStatusColorLocked") {
    group = "verification"
    description = "Forbid per-call mutation (.copy of alpha or any channel) of a locked RovaSemantics " +
        "status color — status colors are exact, used at full locked opacity, always paired with shape " +
        "(ADR-0031 §3, WCAG 1.4.1)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova").asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkStatusColorLocked")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkStatusColorLocked.ok"))
}

// ADR-0029 PR-γ gate 1 — live capture paths must not branch on the legacy
// orientation-carrying mode strings; read-compat sites are allowlisted (§6).
// Comment/KDoc lines are skipped: documenting a legacy value is legal,
// branching on one is not.
val checkNoLegacyModeStrings = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkNoLegacyModeStrings") {
    group = "verification"
    description = "Forbid \"Portrait\"/\"Landscape\"/\"PortraitLandscape\" string literals outside legacy read-compat (ADR-0029 PR-γ §6)."
    sources.from(
        layout.projectDirectory.dir("src/main/java").asFileTree.matching { include("**/*.kt") }
    )
    checkId.set("checkNoLegacyModeStrings")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkNoLegacyModeStrings.ok"))
}

// ADR-0029 PR-γ gate 2 — rotation applies only at segment boundaries (§3):
// setTargetRotation is reachable only from the allowlisted capture files.
val checkSetTargetRotationBoundaryOnly = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkSetTargetRotationBoundaryOnly") {
    group = "verification"
    description = "setTargetRotation only in RovaRecordingService/dualrecord (ADR-0029 §3 segment-boundary rule)."
    sources.from(
        layout.projectDirectory.dir("src/main/java").asFileTree.matching { include("**/*.kt") }
    )
    checkId.set("checkSetTargetRotationBoundaryOnly")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkSetTargetRotationBoundaryOnly.ok"))
}

// ADR-0029 PR-γ gate 3 — FrontBack construction is capability-gated (§5):
// the topology may be referenced only by its declaration and the registry
// that owns the capability gate. PR-δ extends the allowlist with the
// concurrent-camera module it builds.
val checkFrontBackCapabilityGated = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkFrontBackCapabilityGated") {
    group = "verification"
    description = "\"FrontBack\" referenced only in CaptureTopology/CaptureModes (capability gate site) (ADR-0029 §5)."
    sources.from(
        layout.projectDirectory.dir("src/main/java").asFileTree.matching { include("**/*.kt") }
    )
    checkId.set("checkFrontBackCapabilityGated")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkFrontBackCapabilityGated.ok"))
}

// ADR-0034 — DualShot AE target fps range must be capability-gated (never a hard-coded literal).
val checkAeFpsRangeCapabilityGated = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkAeFpsRangeCapabilityGated") {
    group = "verification"
    description = "DualShot AE target fps range must be capability-gated via AeFpsRangePolicy, never a hard-coded Range literal (ADR-0034)."
    sources.from(
        layout.projectDirectory.file("src/main/java/com/aritr/rova/service/RovaRecordingService.kt")
    )
    checkId.set("checkAeFpsRangeCapabilityGated")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkAeFpsRangeCapabilityGated.ok"))
}

// ADR-0035 — thermal encode decimation must gate only the encoder-feed path, never preview render.
val checkDecimationEncoderOnly = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkDecimationEncoderOnly") {
    group = "verification"
    description = "Thermal encode decimation (encodeDecimationFactor/shouldSubmit) must not appear in EglRouter's preview render loop (ADR-0035)."
    sources.from(
        layout.projectDirectory.file("src/main/java/com/aritr/rova/service/dualrecord/internal/EglRouter.kt")
    )
    checkId.set("checkDecimationEncoderOnly")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkDecimationEncoderOnly.ok"))
}

// ADR-0029 §C — user-facing copy speaks clip/session only (spec 2026-06-11 §7).
val checkUserCopyVocabulary = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkUserCopyVocabulary") {
    group = "verification"
    description = "No loop/repeat/segment vocabulary in user-visible string VALUES, en+es (ADR-0029 §C terminology)."
    sources.from(layout.projectDirectory.file("src/main/res/values/strings.xml"))
    sources.from(layout.projectDirectory.file("src/main/res/values-es/strings.xml"))
    checkId.set("checkUserCopyVocabulary")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkUserCopyVocabulary.ok"))
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
val checkA11yAnimationGated = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkA11yAnimationGated") {
    group = "verification"
    description = "Require a reduced-motion seam read in any file using rememberInfiniteTransition/infiniteRepeatable — WCAG 2.2 AA SC 2.3.3/2.2.2 (ADR-0020 §Decision-3)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkA11yAnimationGated")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkA11yAnimationGated.ok"))
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
val checkA11yClickableHasRole = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkA11yClickableHasRole") {
    group = "verification"
    description = "Require an accessibility role on custom Modifier.clickable/combinedClickable — WCAG 2.2 AA SC 4.1.2 (ADR-0020 §Decision-1)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkA11yClickableHasRole")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkA11yClickableHasRole.ok"))
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
val checkA11yTargetSizeToken = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkA11yTargetSizeToken") {
    group = "verification"
    description = "Require interactive-target size tokens >= 24.dp — WCAG 2.2 AA SC 2.5.8 (ADR-0020 §Decision-1)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/ui/theme")
            .asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkA11yTargetSizeToken")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkA11yTargetSizeToken.ok"))
}

// B5 / ADR-0025 — the core privacy invariant, mechanically enforced:
// VaultExporter must never reach a public-publish API. A vaulted recording
// stays app-private; any MediaStore insert / media scan / public-dir write
// inside VaultExporter.kt would silently make it gallery-visible.
val checkVaultExporterNoPublicPublish = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkVaultExporterNoPublicPublish") {
    group = "verification"
    description = "Forbid public-publish APIs in VaultExporter (ADR-0025 — vault recordings never publish)."
    sources.from(
        layout.projectDirectory.file("src/main/java/com/aritr/rova/service/export/VaultExporter.kt")
    )
    checkId.set("checkVaultExporterNoPublicPublish")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkVaultExporterNoPublicPublish.ok"))
}

// ADR-0031 §5 (P1) — bespoke ImageVectors have one home. `ImageVector.Builder(` may appear only in
// RovaGlyphs.kt; folding RecordChromeIcons in here removed the second declaration site. This subsumes
// the old RecordChromeIcons.kt allowance in checkRecordSurfaceNoBlur.
val checkRovaGlyphHome = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkRovaGlyphHome") {
    group = "verification"
    description = "Bespoke ImageVectors must be declared only in RovaGlyphs.kt — one home for " +
        "hand-authored glyphs so the icon family + theme engine resolve from a single source (ADR-0031 §5)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova").asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkRovaGlyphHome")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkRovaGlyphHome.ok"))
}

val checkSingleColorSchemeSource = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkSingleColorSchemeSource") {
    group = "verification"
    description = "The Material ColorScheme is constructed in exactly one place — the engine " +
        "(Theme.kt builds it from PaletteColorScheme; PaletteColorScheme.kt holds the factory base). " +
        "No other file may call darkColorScheme(/lightColorScheme( or pass MaterialTheme(colorScheme=…) " +
        "(ADR-0028 amendment 2026-06-18). // colorscheme-source-opt-out to waive a line."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova").asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkSingleColorSchemeSource")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkSingleColorSchemeSource.ok"))
}

val checkRecordSurfaceNoBlur = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkRecordSurfaceNoBlur") {
    group = "verification"
    description = "Record-chrome files must not apply Modifier.blur/RenderEffect — record glass uses GlassRole.RecordChrome (blurRadius=0). DualPreviewZone is the preview/carve-out, not chrome (ADR-0028 §2.3)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova").asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkRecordSurfaceNoBlur")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkRecordSurfaceNoBlur.ok"))
}

val checkGlassSurfaceRoleUsage = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkGlassSurfaceRoleUsage") {
    group = "verification"
    description = "Modifier.blur is permitted only in GlassSurface.kt (resolver-driven glass), the DualPreviewZone carve-out, and pre-existing glow-bloom (WarningSheetV3/RecoveryCall). All new glass goes through GlassSurface(role=…) (ADR-0028 §2.1/§5)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/ui").asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkGlassSurfaceRoleUsage")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkGlassSurfaceRoleUsage.ok"))
}

// PR-ε — ADR-0029 §B″ (single lock writer): `requestedOrientation` on the UI
// side is written ONLY by RecordScreen's unified lock DisposableEffect
// (RecordChromeLockPolicy.shouldLock is the sole decision point). A second
// writer reintroduces the lock/unlock races the §B″ model exists to prevent.
// Comments are stripped before matching (block/KDoc + line) because prose
// mentions are legal — e.g. DualShotPortraitGate.kt documents the legacy
// lock in KDoc.
val checkRecordChromeLockSingleSite = tasks.register<com.aritr.rova.gradle.SourceCheckTask>("checkRecordChromeLockSingleSite") {
    group = "verification"
    description = "Forbid requestedOrientation writes in ui/ outside RecordScreen.kt (ADR-0029 §B″ single lock writer)."
    sources.from(
        layout.projectDirectory.dir("src/main/java/com/aritr/rova/ui").asFileTree.matching { include("**/*.kt", "**/*.java") }
    )
    checkId.set("checkRecordChromeLockSingleSite")
    reportBaseDir.set(rootProject.layout.projectDirectory)
    sentinel.set(layout.buildDirectory.file("reports/rova-checks/checkRecordChromeLockSingleSite.ok"))
}

// preBuild gate wiring — pluginManager.withPlugin (not afterEvaluate) so it fires
// once AGP has registered preBuild, without the fragile afterEvaluate ordering
// (config-cache-safe; the 46 gates are typed SourceCheckTasks in build-logic).
pluginManager.withPlugin("com.android.application") {
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
        dependsOn(checkLibraryNoManifestWrite)
        dependsOn(checkVaultExporterNoPublicPublish)
        dependsOn(checkRecordSurfaceNoBlur)
        dependsOn(checkGlassSurfaceRoleUsage)
        dependsOn(checkNoLegacyModeStrings)
        dependsOn(checkSetTargetRotationBoundaryOnly)
        dependsOn(checkFrontBackCapabilityGated)
        dependsOn(checkAeFpsRangeCapabilityGated)
        dependsOn(checkDecimationEncoderOnly)
        dependsOn(checkUserCopyVocabulary)
        dependsOn(checkRecordChromeLockSingleSite)
        dependsOn(checkSemanticIconNoRawAlpha)
        dependsOn(checkStatusColorLocked)
        dependsOn(checkRovaGlyphHome)
        dependsOn(checkSingleColorSchemeSource)
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
