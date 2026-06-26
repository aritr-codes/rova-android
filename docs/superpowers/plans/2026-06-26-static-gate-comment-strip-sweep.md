# Static-Gate Comment-Strip Sweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Subagents EDIT-ONLY; the controller runs ALL gradle/test/git commands and serializes the single Gradle daemon.

**Goal:** Finish PR #145 — migrate the remaining ~30 comment-handling static gates off the legacy per-line `trimStart().startsWith("//"|"*")` (and `substringBefore("//")` / own DOT_MATCHES_ALL) comment skip onto the shared `CommentStripper` primitive, closing the same F2 false-pass holes #145 closed.

**Architecture:** Build-logic only. Each migrated gate replaces its `startsWith`-guarded per-line **detection input** with `f.strippedLines.getOrElse(i){""}`; opt-out markers, forward/back co-presence windows, and the reported offender line all stay on the RAW `f.lines`. Two whole-text gates consolidate onto `CommentStripper.strip(text)`. No new gates, no `:app` source change, no schema/ADR change.

**Tech Stack:** Kotlin (build-logic included build), JUnit4 golden tests, Gradle 9.4.1 / AGP 9.2.1, config-cache ON.

## Global Constraints

- **Build-logic ONLY.** No `app/src` edits. No new gate. 46 gates total unchanged. `RegistryTest.EXPECTED_IDS` unchanged.
- **THE NON-NEGOTIABLE INVARIANT:** changes only HOW a gate detects comments. A migrated gate may only become STRICTER in the false-pass direction. NEVER newly reject code the original accepted. NEVER change failure-message bytes for any real (non-comment-edge) violation. If closing a hole turns any real `app/` file red under `:app:assembleDebug` → STOP, surface as a latent real violation, escalate (do not edit the gate to hide it).
- **Owner-signed scope (2026-06-26):** minimal #145 recipe (trigger-only — migrate the `startsWith`-guarded per-line detection; co-presence windows STAY RAW). Consolidate the 2 strictly-better C gates (`RovaGlyphHome`, `RecordChromeLockSingleSite`); LEAVE `SingleColorSchemeSource` (it blanks string/char literals — incompatible with CommentStripper which keeps them).
- **DOCUMENTED-LEAVE (do NOT touch):** `ExportPendingVisibilityOnQuery`, `ExportCleanupPredicate` (already-left #145), `WakeLockHeldRefresh`, `WakeLockZeroGapRefresh` (NONE-mechanism, no `startsWith`), `LocaleConfigNoPseudolocale` + `UserCopyVocabulary` (XML, scan-all by design), `SingleColorSchemeSource` (literal-blanking).
- **GOTCHA 1 — KDoc self-nest:** never write a bare `/*` or `*/` in `RovaGateRules` KDoc prose — Kotlin nests block comments → "Unclosed comment" compile error. Reword; markers inside `//` line comments are inert.
- **GOTCHA 3 — raw-string-literal edge (codex review 2026-06-26):** legacy per-line skip ALSO suppressed detection on multi-line raw-string `"""…"""` content lines whose `trimStart` begins with `*` or `//`. CommentStripper KEEPS raw literals verbatim → such a line's token is NEWLY visible. For a FORBID gate this is a *potential* new false-reject if a real forbidden token genuinely sits inside a raw string on such a line. RESOLUTION (preserves invariant): `:app:assembleDebug` (Task 9 Step 3) is the backstop. If a gate newly REDs there AND the offender is a token inside a raw-string literal (not real code) → that gate's migration is REVERTED to legacy + documented-leave (do NOT weaken CommentStripper, do NOT hide a real violation). Expected to not occur (gate-scanned tokens rarely appear in raw strings; #145's 7 literal-keeping migrations shipped clean), but each task SHOULD add a raw-string negative golden test where the forbidden token plausibly appears in user/test data strings.
- **GOTCHA 2 — bare-`*` fixtures flip:** dropping `startsWith("*")` means a test fixture line that is a bare `*`-prefixed line OUTSIDE a `/* */` block (used to fake a KDoc comment line) is NO LONGER treated as a comment by CommentStripper → it flips the `*_skipsCommentLines` test RED. Fix per #145 owner decision: wrap that bare-`*` line in a real `/* ... */` block (preserves intent).
- **Verify (controller, WARM daemon):** `./gradlew -p build-logic test` (golden tests incl. `RegistryTest` 46 ids) + `./gradlew :app:assembleDebug` (fires all 46 gates on real source; NOT `:app:lintDebug` — pre-existing `VaultAndroidOps:267` NewApi RED is unrelated) + one `./gradlew -p build-logic test --configuration-cache --configuration-cache-problems=fail` (0 problems). Confirm one assembleDebug APK installs on `RZCYA1VBQ2H` before GO.

---

## THE MIGRATION RECIPE (applies to every Task 1–7 "A" gate)

For each rule function, locate each detection of this shape:

```kotlin
.filter { (_, line) ->
    val trimmed = line.trimStart()
    if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
    else <PREDICATE>(line)
}
```
(or the `continue` / `return@forEachIndexed` / `return@mapNotNull null` variants, or `indexOfFirst { ... }`).

Replace with — detect on stripped, drop the skip, keep report/opt-out/window on raw:

```kotlin
.filter { (idx, line) ->          // need the INDEX; use withIndex()'s idx
    <PREDICATE>(f.strippedLines.getOrElse(idx) { "" })
}
```

Rules:
1. **Detection input → `f.strippedLines.getOrElse(idx){""}`.** The predicate (regex/`contains`) is otherwise byte-identical.
2. **Drop** the `val trimmed = line.trimStart()` + `startsWith` line entirely.
3. **Report from RAW** — every reported/offender string keeps `line.trim()` / `raw.trim()` on the RAW line. Line numbers `idx + 1` unchanged (CommentStripper preserves length + newlines, so `strippedLines[idx]` aligns with `lines[idx]`).
4. **Opt-out markers** (`i18n-opt-out`, `a11y-opt-out`, `semanticicon-opt-out`, `guard-b-opt-out:`, `terminal-ordering-opt-out:`, `completed-write-opt-out:`, `audio-mode-opt-out:`, `fgs-guard-opt-out:`) STAY read from RAW (they live in `//` comments — must survive).
5. **Co-presence windows STAY RAW** (forward/back `lines.subList(...)`, `body`, `window`, `commitsBefore`, `hasAudioModeBefore`, seam-`lines.any`). Minimal #145 recipe — do NOT strip them.
6. **Where a detection needs both index and a `for ((i,line) in lines.withIndex())` loop**, use `f.strippedLines.getOrElse(i){""}` for the detect, keep `line`/`raw` for window+report.
7. **KDoc:** update the per-rule KDoc "Comment handling:" line to: `detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.` Obey GOTCHA 1.

Per migrated gate, ADD one golden test `<name>_detectsAfterBlockCommentClose` proving the hole is closed:
- a line `*/ <forbidden-token>` (raw `trimStart` begins with `*` → legacy SKIPPED it = false-pass; stripped blanks only `*/` → token detected → gate fails). Assert the gate now returns the failure message.
- where practical, also assert a `/* token */`-in-string-literal case still behaves (literal kept verbatim).

Keep existing `_failsOn*` golden tests byte-identical (proves requirement (a): byte-identical RED on a genuine violation).

---

## Task 1: Scheduler family (1 gate)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Scheduler.kt`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/SchedulerRulesTest.kt`

**Gate:** `ruleSchedulerNoGetService` (`checkSchedulerNoGetService`). FORBID line-scan. Predicate (verbatim, keep):
`line.contains("PendingIntent.getService(") || Regex("""\bgetService\s*\(""").containsMatchIn(line) && line.contains("PendingIntent")`.

- [ ] **Step 1:** Apply the recipe — change the `.filter { (_, line) -> ... }` (lines ~15-24) to `.filter { (idx, line) -> <predicate on f.strippedLines.getOrElse(idx){""}> }`. Drop the `trimmed`/`startsWith` lines. The predicate references the stripped line for BOTH `.contains` checks and the regex. Report block (`line.trim()`) unchanged. Update KDoc comment-skip line (GOTCHA 1).
- [ ] **Step 2:** Add golden test `failsOnGetServiceAfterBlockCommentClose`:

```kotlin
@Test
fun failsOnGetServiceAfterBlockCommentClose() {
    // `*/ <code>` — raw trimStart begins with `*`, legacy skipped it (false-pass).
    val files = listOf(src(
        "app/src/main/java/com/aritr/rova/service/scheduler/AlarmScheduler.kt",
        "    */ val x = PendingIntent.getService(ctx, 0, i, 0)"
    ))
    val msg = RovaGateRules.run("checkSchedulerNoGetService", files)
    assertTrue(msg != null &&
        msg.startsWith("PendingIntent.getService is forbidden in alarm scheduler sources"))
}
```

- [ ] **Step 3 (controller):** `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.SchedulerRulesTest"` → expect PASS (incl. existing `skipsCommentedGetService`, `failsOnGetService`).
- [ ] **Step 4:** Commit `build(gates): migrate ruleSchedulerNoGetService onto CommentStripper`.

---

## Task 2: Recovery family (5 gates)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Recovery.kt`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/RecoveryRulesTest.kt`

**Gates (all FORBID line-scan, apply recipe to each `.filter { (_, line) -> ... }`):**
1. `ruleStopNoGetService` — predicate `pattern.containsMatchIn(line)` (`Regex("""PendingIntent\s*\.\s*getService\b""")`).
2. `ruleScheduleReceiverNoFgsStart` — predicate `pattern.containsMatchIn(line)`.
3. `ruleRecoveryNoDeletion` — predicate `forbidden.any { line.contains(it) }`.
4. `ruleRecoverySegmentRegex` — predicate `line.contains("seg_")`. (Keep the inline `// Comment lines are scanned too` note as-is; it documents intent.)
5. `ruleScanTriggerSingleSite` — predicate `line.contains("runRecoveryScan")` (the RovaApp.kt `.filter` exclusion stays unchanged).

- [ ] **Step 1:** Apply recipe to all 5 (detect on `f.strippedLines.getOrElse(idx){""}`, change `(_, line)` → `(idx, line)`, drop `startsWith`, keep reports raw). Update each KDoc comment-skip line (GOTCHA 1). Update the line-131 KDoc that quotes the old skip code — reword to describe stripped detection in `//` prose.
- [ ] **Step 2:** Audit `RecoveryRulesTest.kt` (5 `comment` hits) for bare-`*` fixtures in `*skipsComment*` tests; wrap any bare-`*`-outside-block line in `/* ... */` (GOTCHA 2). Add one `failsAfterBlockCommentClose` golden test per gate (token forms: `PendingIntent.getService(`, `startService(`, `.delete(`, `seg_`, `runRecoveryScan`), each with a `    */ <token...>` line asserting the gate's existing failure-message prefix.
- [ ] **Step 3 (controller):** `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.RecoveryRulesTest"` → PASS.
- [ ] **Step 4:** Commit `build(gates): migrate 5 recovery/scheduler forbid gates onto CommentStripper`.

---

## Task 3: WakeLock family (1 gate)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_WakeLock.kt`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/WakeLockRulesTest.kt`

**Gate:** `ruleWakeLockBoundedAcquire` ONLY (FORBID; `forEachIndexed` with `startsWith` skip at ~29-30; predicate `unboundedAcquire.containsMatchIn(raw)`). **Do NOT touch `ruleWakeLockHeldRefresh` / `ruleWakeLockZeroGapRefresh`** (NONE-mechanism, documented-leave).

- [ ] **Step 1:** In `ruleWakeLockBoundedAcquire`, change `f.lines.forEachIndexed { idx, raw -> ... }`: drop `val trimmed = raw.trimStart()` + `startsWith` early-return; detect via `if (unboundedAcquire.containsMatchIn(f.strippedLines.getOrElse(idx){""}))`. Report line keeps `${trimmed.take(120)}` — BUT `trimmed` is now gone; replace the reported text with `${raw.trimStart().take(120)}` (byte-identical to the old `trimmed.take(120)`, just inlined from raw). Update KDoc.
- [ ] **Step 2 — FIXTURE FLIP (known):** `boundedAcquire_skipsCommentLines` (test lines ~61-71) has `* .acquire() — do not call bare`. Wrap it in a real block comment so CommentStripper blanks it:

```kotlin
@Test
fun boundedAcquire_skipsCommentLines() {
    // Lines inside // or /* */ comments are ignored (CommentStripper).
    val body = """
        // wakeLock.acquire()
        /* * .acquire() — do not call bare */
    """.trimIndent()
    val files = listOf(
        src("app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt", body)
    )
    assertNull(RovaGateRules.run("checkWakeLockBoundedAcquire", files))
}
```

- [ ] **Step 3:** Add hole-close golden test:

```kotlin
@Test
fun boundedAcquire_detectsAfterBlockCommentClose() {
    val relPath = "app/src/main/java/com/aritr/rova/service/RovaRecordingService.kt"
    val body = "        */ wakeLock.acquire()"  // raw trimStart begins with `*` — legacy false-pass
    val files = listOf(src(relPath, body))
    val msg = RovaGateRules.run("checkWakeLockBoundedAcquire", files)
    assertTrue(msg != null && msg.contains("WakeLock.acquire() must pass a timeout"))
}
```

- [ ] **Step 4 (controller):** `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.WakeLockRulesTest"` → PASS (HeldRefresh/ZeroGap untouched).
- [ ] **Step 5:** Commit `build(gates): migrate ruleWakeLockBoundedAcquire onto CommentStripper`.

---

## Task 4: Service family (5 gates)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Service.kt`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/ServiceRulesTest.kt`

**Gates (apply recipe to the `startsWith`-guarded per-line detection ONLY; windows/`hasAudioModeBefore`/`f.text` pre-filter STAY RAW):**
1. `ruleRecoveryReceiverCounter` — THREE detections: `goAsyncIdx` (`indexOfFirst`), `incIdx` (`indexOfFirst`), `hasDec` (`lines.any`). Migrate all three's detection input to `f.strippedLines.getOrElse(i){""}` (use `lines.indices` / `withIndex` so the index is available). Opt-out `guard-b-opt-out:` + problem messages + `goAsyncIdx+1` line refs unchanged.
2. `ruleAtomicTerminalWriteForbiddenPair` — detect `markTerminated(` opener on stripped (`continue` form at ~97-99); opt-out `terminal-ordering-opt-out:` + the 4-line `window` co-presence STAY RAW.
3. `ruleExternalRootShared` — detect `getExternalFilesDir(null)` on stripped (`continue` at ~147-149). Filename allowlist filter unchanged.
4. `ruleAudioModeFgsTypeMatch` — detect MIC literal `micIdx` on stripped (`indexOfFirst` at ~177-181); `hasAudioModeBefore` (0 until micIdx) STAYS RAW.
5. `ruleFGSStartGuarded` — detect the call-site trigger on stripped (`continue` at ~223-228); the `f.text.contains` pre-filter + the 60-line forward `window` co-presence STAY RAW.

- [ ] **Step 1:** Apply recipe to the 5 detections above. For `indexOfFirst`/`any` forms, switch to an indexed form, e.g. `lines.indices.indexOfFirst { i -> f.strippedLines.getOrElse(i){""}.contains("goAsync()") }`. Update each KDoc (GOTCHA 1).
- [ ] **Step 2:** Audit `ServiceRulesTest.kt` for bare-`*` skip fixtures (wrap in `/* */`). Add hole-close golden tests for each gate's primary trigger token (`goAsync()`, `markTerminated(...USER_STOPPED...NONE`, `getExternalFilesDir(null)`, `FOREGROUND_SERVICE_TYPE_MICROPHONE`, `startForegroundService(`) using a `*/ <token>` line, asserting the existing failure-message substring.
- [ ] **Step 3 (controller):** `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.ServiceRulesTest"` → PASS.
- [ ] **Step 4:** Commit `build(gates): migrate 5 service structural gates onto CommentStripper (trigger detect)`.

---

## Task 5: A11yI18n family (3 gates)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_A11yI18n.kt`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/A11yI18nRulesTest.kt`

**Gates (migrate trigger detect only; windows/seam STAY RAW). Do NOT touch `LocaleConfigNoPseudolocale`, `UserCopyVocabulary`, `A11yTargetSizeToken` (already migrated)):**
1. `ruleNoHardcodedUiStrings` — detect `textLiteral`/`contentDescLiteral` on stripped (`.filter` at ~46-52). `i18n-opt-out` check STAYS on raw (it's `if (line.contains("i18n-opt-out")) return@filter false` BEFORE the startsWith — keep it first, on raw).
2. `ruleA11yAnimationGated` — detect `rawPrimitive` trigger on stripped (`.filter` at ~158-162); `a11y-opt-out` on raw; seam `lines.any { rememberReduceMotion... }` STAYS RAW.
3. `ruleA11yClickableHasRole` — detect `clickable` trigger on stripped (`.filter` at ~214-221); `optOut` on raw; back/forward `roleAssign` window STAYS RAW. **(This is the /code-review-flagged gate.)**

- [ ] **Step 1:** Apply recipe. Note these `.filter` blocks already have the index (`(idx, line)` in ClickableHasRole) or use `(_, line)` — switch to indexed and detect on `f.strippedLines.getOrElse(idx){""}`. Keep opt-out `if (line.contains(...)) return@filter false` lines reading RAW. Update KDocs (GOTCHA 1).
- [ ] **Step 2:** Audit `A11yI18nRulesTest.kt` (7 comment hits) for bare-`*` fixtures; wrap. Add hole-close golden tests: `NoHardcodedUiStrings` (`*/ Text("hi")`), `A11yAnimationGated` (`*/ rememberInfiniteTransition()` with no seam → fails), `A11yClickableHasRole` (`*/ .clickable { }` with no role in window → fails). Assert existing failure-message substrings.
- [ ] **Step 3 (controller):** `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.A11yI18nRulesTest"` → PASS.
- [ ] **Step 4:** Commit `build(gates): migrate 3 a11y/i18n gates onto CommentStripper (incl ruleA11yClickableHasRole — closes #145 review note)`.

---

## Task 6: Export family (5 gates)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Export.kt`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/ExportRulesTest.kt`

**Gates (do NOT touch `ruleExportCleanupPredicate` — documented-leave):**
1. `ruleUserStoppedBeforeMerge` — in the `for ((i, raw) in lines.withIndex())` collection loop (~58-72): detect `markTerminated(` AND the `isMergeCall` predicate on `f.strippedLines.getOrElse(i){""}`; drop the `startsWith` skip. The 3-line `window` USER_STOPPED/COMPLETED co-presence STAYS RAW. opt-out `terminal-ordering-opt-out:` on raw.
2. `ruleExportTierReadTolerant` — detect `pattern.containsMatchIn` on stripped (`.filter` ~108-111).
3. `ruleScanFileBoundedWait` — detect `pattern.containsMatchIn` on stripped (`.filter` ~151-154). MediaScanWaiter.kt exclusion filter unchanged.
4. `rulePendingFdModeIsRW` — detect `line.replace("\"rw\"","").contains("\"w\"")` on stripped (`.filter` ~192-195). **Literal-safe:** CommentStripper keeps `"rw"`/`"w"` string literals verbatim, so detection is preserved. Tier1 filename filter unchanged.
5. `ruleExportNoCopyToPublicMovies` — detect `line.contains("copyToPublicMovies")` on stripped (`.filter` ~226-229).

- [ ] **Step 1:** Apply recipe to the 5. Switch `(_, line)`→`(idx, line)` where needed. Update KDocs (GOTCHA 1).
- [ ] **Step 2:** Audit `ExportRulesTest.kt` (7 comment hits) for bare-`*` fixtures; wrap. Add hole-close golden tests per gate (tokens: `markTerminated(...USER_STOPPED` before a merge, `getString("exportTier")`, `MediaScannerConnection.scanFile`, `"w"` mode in a Tier1 file, `copyToPublicMovies`) each via `*/ <token>`. For `rulePendingFdModeIsRW` add an EXTRA test proving a `"w"` inside a string literal on a `/* */`-prefixed-then-code line is still caught (literal-kept), and that a fully `/* "w" */`-commented `"w"` is NOT caught.
- [ ] **Step 3 (controller):** `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.ExportRulesTest"` → PASS.
- [ ] **Step 4:** Commit `build(gates): migrate 5 export forbid/ordering gates onto CommentStripper`.

---

## Task 7: Pending family (3 gates)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Pending.kt`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/PendingRulesTest.kt`

**Gates (do NOT touch `ruleExportIsPendingGuarded`/`SetIncludePending`/`QueryArgMatch` — already migrated; nor `ruleExportPendingVisibilityOnQuery` — documented-leave):**
1. `ruleExportPipelineSingleEntry` — TWO detections: inv1 `ExportPipeline.export(` (`forEachIndexed` ~266-272) and inv2 `muxPattern` (`.filter` ~295-299). Migrate both detection inputs to stripped; keep file/path filters + problem messages + line refs raw.
2. `ruleSafTargetCommittedBeforeStream` — detect the stream-op `streamIdx` (`indexOfFirst` ~340-344) on stripped (`it.contains("copyFileToDocument(")||it.contains("openOutputStream(")`); `commitsBefore` (lines.take) STAYS RAW. (Verbatim note said old didn't strip `/*` here — migration changing mechanism is allowed; direction stricter.)
3. `ruleCompletedWriteOnlyFromPerformMerge` — detect `markTerminated(` on stripped (`forEachIndexed` ~397-400); the 3-line COMPLETED `window` + performMerge-body boundary logic + `completed-write-opt-out:` STAY RAW.

- [ ] **Step 1:** Apply recipe. For `indexOfFirst` (SafTarget), switch to indexed-stripped detection: `lines.indices.indexOfFirst { i -> val c = f.strippedLines.getOrElse(i){""}; c.contains("copyFileToDocument(") || c.contains("openOutputStream(") }`. Update KDocs (GOTCHA 1) — note the file's existing line-25 KDoc about block-comment chars already uses prose; keep that convention.
- [ ] **Step 2:** Audit `PendingRulesTest.kt` (9 comment hits) for bare-`*` fixtures; wrap. Add hole-close golden tests: `ExportPipelineSingleEntry` (a `*/ VideoMerger.mergeSegments(` outside service/export → flagged), `SafTargetCommittedBeforeStream` (`*/ openOutputStream(` with no prior commit → flagged), `CompletedWriteOnlyFromPerformMerge` (`*/ markTerminated(...Terminated.COMPLETED` outside performMerge → flagged). Assert existing failure substrings.
- [ ] **Step 3 (controller):** `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.PendingRulesTest"` → PASS.
- [ ] **Step 4:** Commit `build(gates): migrate 3 pending pipeline/SAF/terminal gates onto CommentStripper`.

---

## Task 8: IconTheme family — 5 A gates + 2 C consolidations

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_IconTheme.kt`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/IconThemeRulesTest.kt`

**A gates (recipe, trigger-only):**
1. `ruleVaultExporterNoPublicPublish` — detect `forbidden.any { line.contains(it) }` on stripped (`.filter` ~50-53).
2. `ruleRecordSurfaceNoBlur` — detect `blurPattern.containsMatchIn` on stripped (`.filter` ~79-82).
3. `ruleGlassSurfaceRoleUsage` — detect `blurPattern.containsMatchIn` on stripped (`.filter` ~117-120).
4. `ruleSemanticIconNoRawAlpha` — detect the `tint` trigger on stripped (`mapNotNull` ~237-244); `semanticicon-opt-out` on raw; the 3-line `window` STAYS RAW.
5. `ruleStatusColorLocked` — detect `dilutePattern.containsMatchIn` on stripped (`.filter` ~273-276).

**C consolidations (owner-approved):**
6. `ruleRecordChromeLockSingleSite` — REPLACE the custom `blockComment` regex + `substringBefore("//")` (lines ~146, 153-160) with `CommentStripper.strip(f.text)`. Detect `requestedOrientation` per stripped line; report from raw `f.lines`. Use `f.strippedLines.getOrElse(i){""}.contains("requestedOrientation")`, report `${f.lines.getOrElse(i){""}.trim()}`. Drop the local `blockComment` val. allowedSuffix filter unchanged.
7. `ruleRovaGlyphHome` — REPLACE the inline `stripComments` helper (lines ~304-311) with `CommentStripper.strip(f.text)`. Detection `builderPattern.findAll(stripped)` + char-offset → line-number computation unchanged (CommentStripper preserves length+newlines so offsets are byte-stable). Delete the local `stripComments` fun. homeSuffix filter unchanged.

**LEAVE `ruleSingleColorSchemeSource` UNCHANGED** (its `strip` blanks string+char literals; CommentStripper keeps them → migrating would newly-detect literal `darkColorScheme(` = invariant violation). Add a `// NOTE` in its KDoc: not migrated — needs literal-blanking, incompatible with CommentStripper (keeps literals).

- [ ] **Step 1:** Apply A-recipe to gates 1-5; apply C-consolidation to gates 6-7; add the leave-note to `SingleColorSchemeSource`. Update KDocs (GOTCHA 1 — the file has KDoc prose referencing block-comment strip; reword to "CommentStripper").
- [ ] **Step 2:** Audit `IconThemeRulesTest.kt` (17 comment hits) for bare-`*` fixtures; wrap. Add hole-close golden tests for the 5 A gates (tokens: a forbidden `MediaStore` in VaultExporter via `*/ MediaStore`, `.blur(` in RecordScreen.kt, `.blur(` outside allowlist, `tint = Color(` window, `RovaSemantics.x.copy(`). For the 2 C gates add a NESTING-aware test: `RecordChromeLockSingleSite` with a nested `/* /* */ requestedOrientation */` proving the nesting-aware strip blanks it (old non-nesting regex would have left `requestedOrientation` exposed → false-fail); `RovaGlyphHome` similar with `ImageVector.Builder(` inside a nested block comment. Confirm `SingleColorSchemeSource` tests still pass untouched.
- [ ] **Step 3 (controller):** `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.IconThemeRulesTest"` → PASS.
- [ ] **Step 4:** Commit `build(gates): migrate 5 icon/theme forbid gates + consolidate 2 whole-text gates onto CommentStripper`.

---

## Task 9: Full verification + integration gate (controller only)

**Files:** none (verification).

- [ ] **Step 1:** Full build-logic test suite: `./gradlew -p build-logic test` → PASS incl. `RegistryTest` (46 ids unchanged), `CommentStripperTest`, `SourceFileTest`.
- [ ] **Step 2:** Config-cache run: `./gradlew -p build-logic test --configuration-cache --configuration-cache-problems=fail` → 0 problems, BUILD SUCCESSFUL.
- [ ] **Step 3:** Real-source gate run: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (fires all 46 gates on real `app/` source). If ANY gate newly RED, triage the offender: (a) real code a closed hole exposed → STOP, surface as latent real violation, do NOT edit the gate to hide it; (b) a token inside a raw-string `"""…"""` literal that legacy skipped (GOTCHA 3) → that is a migration-induced false-reject: REVERT that one gate to legacy + move it to documented-leave, re-run. Never weaken CommentStripper.
- [ ] **Step 4:** Confirm APK installs: `adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk` → Success. (Device smoke N/A — build-logic only.)
- [ ] **Step 5:** Update `CLAUDE.md` static-gate section: the "2 co-presence REQUIRE gates ... documented conservative-leaves" line now reflects the remaining leaves (`ExportPendingVisibilityOnQuery`, `ExportCleanupPredicate`, `SingleColorSchemeSource`, `WakeLockHeldRefresh`/`ZeroGap` NONE-mechanism). State the whole-text DOT_MATCHES_ALL strips are now consolidated except `SingleColorSchemeSource`.
- [ ] **Step 6:** Final commit `docs: update gate-section comment-strip status after full sweep`. STOP — push/PR/merge ONLY on explicit owner GO.

---

## Self-Review

- **Spec coverage:** All ~30 legacy gates from the grep enumerated into Tasks 1-8; the 4 documented-leaves + 2 already-left + 7 already-migrated explicitly excluded with reasons. Verification + CLAUDE.md update = Task 9. ✓
- **Invariant:** every task migrates detection-input only, windows/opt-out/report raw, adds a byte-identical-RED assertion + a hole-close test. `:app:assembleDebug` is the latent-violation backstop. ✓
- **Gotchas:** GOTCHA 1 (KDoc) called out per task; GOTCHA 2 (bare-`*` fixture flip) called out per task with the WakeLock worked example. ✓
- **No new gates / RegistryTest unchanged:** asserted in Global Constraints + Task 9 Step 1. ✓
- **C decision consistency:** consolidate 2 (`RovaGlyphHome`, `RecordChromeLockSingleSite`); leave `SingleColorSchemeSource` (literal-blanking) — matches owner sign-off. ✓
