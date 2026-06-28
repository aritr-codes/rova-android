package com.aritr.rova.gradle

/**
 * Verbatim lifts of seven former app/build.gradle.kts inline gate bodies
 * (export pending-visibility / pipeline / SAF / terminal-write gates).
 * Mechanical swaps only vs the inline body:
 *   - iterate [files] parameter instead of dir.walkTopDown() / fileTree(...)
 *   - f.lines / f.text / f.relPath instead of readLines() / readText() / relativeTo(rootDir)
 *   - return message instead of throw GradleException(message); return null to pass
 *   - No "dir.exists()" guard — an empty file set means no offenders → null
 *     (exception: checkExportPipelineSingleEntry is a REQUIRE/count gate — count==0
 *      means ExportPipeline.export is never called, which the old gate fails; we
 *      replicate the old `when (pipelineCalls.size) { 0 -> problems += … }` branch)
 *   - Canonical-path comparison (checkExportPipelineSingleEntry, checkSafTargetCommittedBeforeStream,
 *     checkCompletedWriteOnlyFromPerformMerge): old code used f.canonicalFile == allowedFile.canonicalFile.
 *     We only have relPath here. We replace with a relPath suffix match after normalising
 *     backslashes to forward slashes. The suffix strings are unique in the rova source tree.
 *   - Filename exemption (checkSafTargetCommittedBeforeStream): old code used `f.name != "SafAndroidOps.kt"`.
 *     We replicate by extracting the trailing filename component of relPath after normalising separators.
 *   - Separator note: a LOCAL normalised var is used only for relPath-suffix / filename
 *     checks. The reported f.relPath is NEVER normalised — it preserves the platform
 *     separator exactly as SourceCheckTask builds it (Windows '\', Linux '/'), byte-identical
 *     to the old `f.relativeTo(rootDir)` output.
 *
 * NOTE on block-comment characters in KDoc: literal slash-star sequences are written
 * as prose ("block-comment opener") throughout this file to avoid Kotlin nested-comment
 * parse errors.
 */

// ─── checkExportIsPendingGuarded ──────────────────────────────────────────────

/**
 * Verbatim lift of checkExportIsPendingGuarded.
 *
 * Regex: \bIS_PENDING\b
 * Comment handling: detection on f.strippedLines (CommentStripper); file-level guard via f.strippedText (round-3: was raw f.text).
 * Guard: the FILE (via readText) must contain at least one of:
 *   - @RequiresApi(Build.VERSION_CODES.Q)
 *   - @RequiresApi(Build.VERSION_CODES.R)
 *   - @RequiresApi(android.os.Build.VERSION_CODES.Q)
 *   - Build.VERSION.SDK_INT + Build.VERSION_CODES.Q (both substrings co-present)
 * If any hit file lacks a file-level guard, it is an offender.
 * Scope: .kt files in [files] (export dir).
 */
internal fun ruleExportIsPendingGuarded(files: List<SourceFile>): String? {
    val pattern = Regex("""\bIS_PENDING\b""")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .mapNotNull { f ->
            val hits = f.lines.withIndex()
                .filter { (idx, _) ->
                    // Detect IS_PENDING on the comment-stripped line so a
                    // `/* … */`-then-code line or a string-literal marker can't
                    // hide it. The file-level guard check below stays on raw text.
                    pattern.containsMatchIn(f.strippedLine(idx))
                }
            if (hits.isEmpty()) return@mapNotNull null
            // Guard check on f.strippedText (round-3 false-pass close): a commented
            // @RequiresApi / SDK_INT guard must NOT satisfy the require.
            val hasFileGuard = f.strippedText.contains("@RequiresApi(Build.VERSION_CODES.Q)") ||
                f.strippedText.contains("@RequiresApi(Build.VERSION_CODES.R)") ||
                f.strippedText.contains("@RequiresApi(android.os.Build.VERSION_CODES.Q)") ||
                (f.strippedText.contains("Build.VERSION.SDK_INT") && f.strippedText.contains("Build.VERSION_CODES.Q"))
            if (hasFileGuard) null else f to hits
        }
        .toList()
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, hits) ->
        hits.joinToString("\n") { (i, line) ->
            "  ${f.relPath}:${i + 1}: ${line.trim()}"
        }
    }
    return "IS_PENDING used without SDK gating in service/export/ — " +
        "the file must be annotated @RequiresApi(Build.VERSION_CODES.Q) " +
        "or guard the reference with `Build.VERSION.SDK_INT >= " +
        "Build.VERSION_CODES.Q`. Offenders:\n$report"
}

// ─── checkExportSetIncludePendingGuarded ──────────────────────────────────────

/**
 * Verbatim lift of checkExportSetIncludePendingGuarded.
 *
 * Regex: \bsetIncludePending\b
 * Comment handling: hit detection on f.strippedLines (CommentStripper); ±30 SDK-branch window on f.strippedLines.
 * Window: ±30 lines around each hit. Both Build.VERSION_CODES.R AND
 * Build.VERSION.SDK_INT must be present in the window.
 *   Round-4 (2026-06-28): the ±30 SDK-branch REQUIRE window reads f.strippedLines
 *   (was raw lines) — a commented `Build.VERSION_CODES.R` / SDK_INT must NOT satisfy
 *   the require (was a false-PASS hole). Both checks are literal `.contains`, so the
 *   migration is stricter-only and keeps the hit + window substrate consistent.
 * Scope: .kt files in [files] (export dir).
 */
internal fun ruleExportSetIncludePendingGuarded(files: List<SourceFile>): String? {
    val pattern = Regex("""\bsetIncludePending\b""")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .mapNotNull { f ->
            val lines = f.lines
            val hits = mutableListOf<Pair<Int, String>>()
            for ((i, line) in lines.withIndex()) {
                // Detect the hit on the comment-stripped line; the ±30 SDK-branch
                // REQUIRE window reads stripped too (round-4: a commented guard must
                // not satisfy the require).
                if (!pattern.containsMatchIn(f.strippedLine(i))) continue
                val window = f.strippedLines.subList(
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
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, hits) ->
        hits.joinToString("\n") { (i, line) ->
            "  ${f.relPath}:$i: $line"
        }
    }
    return "setIncludePending used without an SDK branch against " +
        "Build.VERSION_CODES.R — must run only on API 29 (deprecated " +
        "and unreliable on API 30+). Offenders:\n$report"
}

// ─── checkExportQueryArgMatchPendingGuarded ───────────────────────────────────

/**
 * Verbatim lift of checkExportQueryArgMatchPendingGuarded.
 *
 * Regex: \bQUERY_ARG_MATCH_PENDING\b
 * Comment handling: hit detection on f.strippedLines (CommentStripper); ±30 SDK-branch window on f.strippedLines.
 * Window: ±30 lines around each hit. Both Build.VERSION_CODES.R AND
 * Build.VERSION.SDK_INT must be present in the window.
 *   Round-4 (2026-06-28): the ±30 SDK-branch REQUIRE window reads f.strippedLines
 *   (was raw lines) — a commented `Build.VERSION_CODES.R` / SDK_INT must NOT satisfy
 *   the require (was a false-PASS hole). Both checks are literal `.contains`, so the
 *   migration is stricter-only and keeps the hit + window substrate consistent.
 * Scope: .kt files in [files] (export dir).
 */
internal fun ruleExportQueryArgMatchPendingGuarded(files: List<SourceFile>): String? {
    val pattern = Regex("""\bQUERY_ARG_MATCH_PENDING\b""")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .mapNotNull { f ->
            val lines = f.lines
            val hits = mutableListOf<Pair<Int, String>>()
            for ((i, line) in lines.withIndex()) {
                // Detect the hit on the comment-stripped line; the ±30 SDK-branch
                // REQUIRE window reads stripped too (round-4: a commented guard must
                // not satisfy the require).
                if (!pattern.containsMatchIn(f.strippedLine(i))) continue
                val window = f.strippedLines.subList(
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
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, hits) ->
        hits.joinToString("\n") { (i, line) ->
            "  ${f.relPath}:$i: $line"
        }
    }
    return "QUERY_ARG_MATCH_PENDING used without an SDK branch against " +
        "Build.VERSION_CODES.R — must run only on API 30+ " +
        "(NoSuchFieldError on Q). Offenders:\n$report"
}

// ─── checkExportPendingVisibilityOnQuery ──────────────────────────────────────

/**
 * Verbatim lift of checkExportPendingVisibilityOnQuery.
 *
 * REQUIRE co-presence gate: any file whose query TRIGGER fires (a real
 * resolver.query( in f.strippedText) must also contain ALL THREE require-tokens
 * on f.strippedText:
 *   1. setIncludePending (strippedText.contains)
 *   2. QUERY_ARG_MATCH_PENDING (strippedText.contains)
 *   3. IS_PENDING}?=1 regex match on strippedText
 *
 * NOTE (round-3 hardening 2026-06-28, codex reconcile): the TRIGGER and all three
 * REQUIRE-tokens read f.strippedText. A commented resolver.query( must not trigger
 * the gate, and a commented setIncludePending / QUERY_ARG_MATCH_PENDING /
 * IS_PENDING=1 must not satisfy the require — these were false-PASS holes (the
 * requires) and a new-false-fail inconsistency (the trigger) on the old raw
 * f.text + nonCommentText substrate. strippedText keeps all real code verbatim,
 * so no real query/token is missed: the migration is hardening-only.
 *
 * EMPTY-INPUT REASONING: if no file in the set calls resolver.query(, the gate passes
 * vacuously (null). This matches the old behaviour: the old code returned early with no
 * offenders if no file matched the query pattern.
 *
 * Pattern: resolver\s*\.\s*query\s*\(
 * IS_PENDING filter pattern: IS_PENDING\}?\s*=\s*1\b
 */
internal fun ruleExportPendingVisibilityOnQuery(files: List<SourceFile>): String? {
    val queryPattern = Regex("""resolver\s*\.\s*query\s*\(""")
    val isPendingFilterPattern = Regex("""IS_PENDING\}?\s*=\s*1\b""")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .mapNotNull { f ->
            // Query TRIGGER on f.strippedText (round-3, codex reconcile): a commented
            // resolver.query( must NOT trigger the gate. The old nonCommentText kept
            // inline/trailing comments verbatim, so a commented query could spuriously
            // trigger and — with the require-tokens now on strippedText — newly-fail a
            // clean file. strippedText keeps all real code, so no real query is missed.
            if (!queryPattern.containsMatchIn(f.strippedText)) return@mapNotNull null
            // Require-tokens on f.strippedText (round-3 false-pass close): commented
            // visibility/selection tokens must NOT satisfy the require.
            val hasIncludePending = f.strippedText.contains("setIncludePending")
            val hasMatchPending = f.strippedText.contains("QUERY_ARG_MATCH_PENDING")
            val hasIsPendingFilter = isPendingFilterPattern.containsMatchIn(f.strippedText)
            if (hasIncludePending && hasMatchPending && hasIsPendingFilter) null
            else f to listOfNotNull(
                if (!hasIncludePending) "missing setIncludePending (API 29 visibility)" else null,
                if (!hasMatchPending) "missing QUERY_ARG_MATCH_PENDING (API 30+ visibility)" else null,
                if (!hasIsPendingFilter) "missing explicit IS_PENDING = 1 SQL selection (defense in depth — visibility flags alone are not enough)" else null
            )
        }
        .toList()
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, problems) ->
        problems.joinToString("\n") { p -> "  ${f.relPath}: $p" }
    }
    return "Pending-visibility-on-query rule violated in service/export/ — " +
        "any file calling resolver.query(...) must wire BOTH visibility " +
        "mechanisms AND an explicit IS_PENDING = 1 SQL selection. The " +
        "visibility flags alone do not exclude non-pending rows on API 29 " +
        "(setIncludePending = \"include in addition\", not \"only\") and " +
        "OEM MediaStore behavior on MATCH_ONLY varies. " +
        "Offenders:\n$report"
}

// ─── checkExportPipelineSingleEntry ───────────────────────────────────────────

/**
 * Verbatim lift of checkExportPipelineSingleEntry.
 *
 * TWO complementary invariants:
 *
 * Invariant 1 — exactly ONE ExportPipeline.export( call across ALL files in [files],
 * and it must live in RovaRecordingService.kt.
 *   - count==0 → "ExportPipeline.export(...) not called anywhere — performMerge must dispatch to the pipeline."
 *   - count==1, wrong file → "${f.relPath}:$line — only RovaRecordingService.performMerge may call ExportPipeline.export."
 *   - count>1 → all sites listed with "ExportPipeline.export has more than one call site."
 *   - count==1, correct file → pass for invariant 1.
 *
 * EMPTY-INPUT REASONING: if [files] is empty, count==0 → the old gate fails with
 * "not called anywhere". We replicate: `when (pipelineCalls.size) { 0 -> problems += … }`.
 *
 * Invariant 2 — VideoMerger mux callers restricted to service/export/ plus the
 * definition file VideoMerger.kt.
 *   - Pattern: \bVideoMerger\s*\.\s*(mergeSegments|mergeSegmentsToFd)\s*\(
 *   - Exclusion of VideoMerger.kt: old code used `f.canonicalFile != videoMergerFile`.
 *     We replace with relPath normalised-suffix check on "utils/VideoMerger.kt".
 *   - Callers outside service/export/ are flagged (normalised pathStr must contain
 *     "service/export/").
 *   - Comment handling (inv1 + inv2): detection on f.strippedLines (CommentStripper);
 *     file/path filters, problem messages, and line refs use the raw line.
 *
 * Allowed call site for invariant 1: relPath normalised-suffix ends with
 * "service/RovaRecordingService.kt". Unique in the tree.
 */
internal fun ruleExportPipelineSingleEntry(files: List<SourceFile>): String? {
    val problems = mutableListOf<String>()

    // Invariant 1 — exactly one ExportPipeline.export( call site.
    val pipelineCalls = mutableListOf<Pair<SourceFile, Int>>()
    for (f in files) {
        if (!f.relPath.endsWith(".kt")) continue
        f.lines.forEachIndexed { i, line ->
            if (f.strippedLine(i).contains("ExportPipeline.export(")) {
                pipelineCalls += f to (i + 1)
            }
        }
    }
    when (pipelineCalls.size) {
        0 -> problems += "ExportPipeline.export(...) not called anywhere — performMerge must dispatch to the pipeline."
        1 -> {
            val (f, line) = pipelineCalls.single()
            val normRelPath = f.relPath.replace('\\', '/')
            if (!normRelPath.endsWith("service/RovaRecordingService.kt")) {
                problems += "${f.relPath}:$line — only RovaRecordingService.performMerge may call ExportPipeline.export."
            }
        }
        else -> pipelineCalls.forEach { (f, line) ->
            problems += "${f.relPath}:$line — ExportPipeline.export has more than one call site."
        }
    }

    // Invariant 2 — VideoMerger mux callers restricted to service/export/.
    val muxPattern = Regex("""\bVideoMerger\s*\.\s*(mergeSegments|mergeSegmentsToFd)\s*\(""")
    for (f in files) {
        if (!f.relPath.endsWith(".kt")) continue
        val normRelPath = f.relPath.replace('\\', '/')
        // Exclude the definition file itself.
        if (normRelPath.endsWith("utils/VideoMerger.kt")) continue
        val hits = f.lines.withIndex().filter { (idx, _) ->
            muxPattern.containsMatchIn(f.strippedLine(idx))
        }
        if (hits.isEmpty()) continue
        if (normRelPath.contains("service/export/")) continue
        hits.forEach { (i, line) ->
            problems += "${f.relPath}:${i + 1}: ${line.trim()} — VideoMerger mux callers must live under service/export/"
        }
    }

    if (problems.isEmpty()) return null
    return "Single-entry rule violated for the tier export pipeline (Phase 1.7 commit-7):\n" +
        problems.joinToString("\n") { "  $it" } +
        "\nFix: live publish goes through ExportPipeline.export from " +
        "RovaRecordingService.performMerge ONLY. VideoMerger mux helpers " +
        "are pipeline internals; consumers must live under service/export/."
}

// ─── checkSafTargetCommittedBeforeStream ──────────────────────────────────────

/**
 * Verbatim lift of checkSafTargetCommittedBeforeStream.
 *
 * For each file: find the FIRST line containing "copyFileToDocument(" or
 * "openOutputStream(" when checked on the comment-stripped line. If found,
 * check whether any line BEFORE it (lines.take(streamIdx)) contains
 * "setExportSafTarget" or "setSafTarget(". The commitsBefore window STAYS RAW.
 * If not, and the file is NOT SafAndroidOps.kt, report an offender.
 *
 * Filename exemption: old code used `f.name != "SafAndroidOps.kt"`. We extract the
 * trailing filename component of relPath after normalising separators.
 *
 * Comment handling: detection of stream op on f.strippedLines (CommentStripper);
 * offender report uses the raw lines.
 *   Round-4 (2026-06-28): the commitsBefore REQUIRE reads f.strippedLine (was raw
 *   lines.take) — a commented `setExportSafTarget` / `setSafTarget(` before the
 *   stream op must NOT satisfy the commit-before-stream require (was a false-PASS
 *   hole). `.contains` is literal so the migration is stricter-only and keeps the
 *   stream-op trigger + commitsBefore substrate consistent.
 *
 * Scope: .kt files in [files] (export dir).
 */
internal fun ruleSafTargetCommittedBeforeStream(files: List<SourceFile>): String? {
    val offenders = mutableListOf<String>()
    files
        .filter { it.relPath.endsWith(".kt") }
        .forEach { f ->
            val lines = f.lines
            val streamIdx = lines.indices.indexOfFirst { i ->
                val c = f.strippedLine(i)
                c.contains("copyFileToDocument(") || c.contains("openOutputStream(")
            }
            if (streamIdx >= 0) {
                val commitsBefore = (0 until streamIdx).any { i ->
                    val c = f.strippedLine(i)
                    c.contains("setExportSafTarget") || c.contains("setSafTarget(")
                }
                val normRelPath = f.relPath.replace('\\', '/')
                val fileName = normRelPath.substringAfterLast('/')
                if (!commitsBefore && fileName != "SafAndroidOps.kt") {
                    offenders += "${f.relPath}:${streamIdx + 1}: SAF stream op without a prior setExportSafTarget commit"
                }
            }
        }
    if (offenders.isEmpty()) return null
    return "ADR-0024 §commit-before-stream violation:\n" + offenders.joinToString("\n") { "  $it" } +
        "\nThe SAF target doc Uri MUST be committed to the manifest before any byte is written to it."
}

// ─── checkCompletedWriteOnlyFromPerformMerge ──────────────────────────────────

/**
 * Verbatim lift of checkCompletedWriteOnlyFromPerformMerge.
 *
 * Detection: any line where the comment-stripped form contains "markTerminated("
 * and whose 3-line forward window (i..minOf(i+3, lastIndex)) on RAW lines
 * contains "Terminated.COMPLETED".
 *
 * File-level opt-out: if a file contains "completed-write-opt-out:" AND is NOT
 * RovaRecordingService.kt → skip the file entirely.
 *
 * For RovaRecordingService.kt: the COMPLETED write must lie inside the performMerge
 * body (between "private suspend fun performMerge(" and the next 4-space-indented
 * function declaration). If performMerge declaration is not found → offender.
 * Function boundary detection: fnDeclPattern = ^    (private suspend fun|private fun|
 * suspend fun|fun) \w+
 *
 * For all other files: any COMPLETED write is an offender (opt-out covers the only
 * legitimate exception).
 *
 * Allowed file: relPath normalised-suffix ends with "service/RovaRecordingService.kt".
 * Unique in the tree.
 *
 * Comment handling: detection of markTerminated( on f.strippedLines (CommentStripper);
 * the 3-line COMPLETED window + performMerge boundary logic + opt-out marker stay RAW.
 */
internal fun ruleCompletedWriteOnlyFromPerformMerge(files: List<SourceFile>): String? {
    val offenders = mutableListOf<Pair<SourceFile, String>>()
    files
        .filter { it.relPath.endsWith(".kt") }
        .forEach { f ->
            val lines = f.lines
            val hasOptOut = lines.any { it.contains("completed-write-opt-out:") }
            val normRelPath = f.relPath.replace('\\', '/')
            val isRecordingService = normRelPath.endsWith("service/RovaRecordingService.kt")
            if (hasOptOut && !isRecordingService) return@forEach

            lines.forEachIndexed { i, raw ->
                if (!f.strippedLine(i).contains("markTerminated(")) return@forEachIndexed
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
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, msg) ->
        "  ${f.relPath}: $msg"
    }
    return "B7 violation (ADR 0006 §Terminal-Write Ordering) — " +
        "Terminated.COMPLETED writes restricted to performMerge:\n$report\n" +
        "Fix: write COMPLETED only from RovaRecordingService.performMerge. " +
        "Late-terminal reconciliation (ExportRecoveryRunner) must carry " +
        "`// completed-write-opt-out: <reason>` marker."
}
