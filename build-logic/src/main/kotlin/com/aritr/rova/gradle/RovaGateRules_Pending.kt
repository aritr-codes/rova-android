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
 * Comment handling: detection on f.strippedLines (CommentStripper); file-level guard via raw f.text.
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
                    pattern.containsMatchIn(f.strippedLines.getOrElse(idx) { "" })
                }
            if (hits.isEmpty()) return@mapNotNull null
            val hasFileGuard = f.text.contains("@RequiresApi(Build.VERSION_CODES.Q)") ||
                f.text.contains("@RequiresApi(Build.VERSION_CODES.R)") ||
                f.text.contains("@RequiresApi(android.os.Build.VERSION_CODES.Q)") ||
                (f.text.contains("Build.VERSION.SDK_INT") && f.text.contains("Build.VERSION_CODES.Q"))
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
 * Comment handling: hit detection on f.strippedLines (CommentStripper); ±30 SDK-branch window stays raw.
 * Window: ±30 lines around each hit. Both Build.VERSION_CODES.R AND
 * Build.VERSION.SDK_INT must be present in the window.
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
                // window below stays RAW (guards are code, byte-identical to today).
                if (!pattern.containsMatchIn(f.strippedLines.getOrElse(i) { "" })) continue
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
 * Comment handling: hit detection on f.strippedLines (CommentStripper); ±30 SDK-branch window stays raw.
 * Window: ±30 lines around each hit. Both Build.VERSION_CODES.R AND
 * Build.VERSION.SDK_INT must be present in the window.
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
                // window below stays RAW (guards are code, byte-identical to today).
                if (!pattern.containsMatchIn(f.strippedLines.getOrElse(i) { "" })) continue
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
 * REQUIRE co-presence gate: any file calling resolver.query( (in non-comment lines)
 * must also contain ALL THREE in the full file text / non-comment text:
 *   1. setIncludePending (text.contains)
 *   2. QUERY_ARG_MATCH_PENDING (text.contains)
 *   3. IS_PENDING}?=1 regex match in non-comment lines
 *
 * Non-comment text: lines filtered by trimStart() not starting with "//", "*", or
 * block-comment opener, joined with "\n".
 *
 * NOTE (comment-strip hardening 2026-06-25): NOT migrated to CommentStripper.
 * This is a co-presence REQUIRE; its comment-prefix skip filter (trimStart
 * startsWith "//", "*", or a block-comment opener) only ever causes
 * over-strictness (a token hidden after a same-line block comment is dropped →
 * the require false-FAILS), never a false-PASS. Migrating would shift behavior
 * in the lenient direction — out of scope for the false-pass-hardening track.
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
            val nonCommentText = f.lines
                .filter {
                    val t = it.trimStart()
                    !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/*")
                }
                .joinToString("\n")
            if (!queryPattern.containsMatchIn(nonCommentText)) return@mapNotNull null
            val hasIncludePending = f.text.contains("setIncludePending")
            val hasMatchPending = f.text.contains("QUERY_ARG_MATCH_PENDING")
            val hasIsPendingFilter = isPendingFilterPattern.containsMatchIn(nonCommentText)
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
 *   - Comment-skip: "//", "*" (trimStart).
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
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed
            if (line.contains("ExportPipeline.export(")) {
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
        val hits = f.lines.withIndex().filter { (_, line) ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
            else muxPattern.containsMatchIn(line)
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
 * For each file: find the FIRST non-comment line containing "copyFileToDocument("
 * or "openOutputStream(". If found, check whether any line BEFORE it (lines.take(streamIdx))
 * contains "setExportSafTarget" or "setSafTarget(". If not, and the file is NOT
 * SafAndroidOps.kt, report an offender.
 *
 * Filename exemption: old code used `f.name != "SafAndroidOps.kt"`. We extract the
 * trailing filename component of relPath after normalising separators.
 *
 * Non-comment check for the stream line: trimStart() not starting with "//" or "*"
 * (note: old gate did NOT include block-comment opener in this check — reproduced
 * verbatim).
 *
 * Scope: .kt files in [files] (export dir).
 */
internal fun ruleSafTargetCommittedBeforeStream(files: List<SourceFile>): String? {
    val offenders = mutableListOf<String>()
    files
        .filter { it.relPath.endsWith(".kt") }
        .forEach { f ->
            val lines = f.lines
            val streamIdx = lines.indexOfFirst {
                val t = it.trimStart()
                !t.startsWith("//") && !t.startsWith("*") &&
                    (it.contains("copyFileToDocument(") || it.contains("openOutputStream("))
            }
            if (streamIdx >= 0) {
                val commitsBefore = lines.take(streamIdx).any {
                    it.contains("setExportSafTarget") || it.contains("setSafTarget(")
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
 * Detection: any non-comment line containing "markTerminated(" whose 3-line forward
 * window (i..minOf(i+3, lastIndex)) contains "Terminated.COMPLETED".
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
 * Comment-skip: "//", "*" (trimStart).
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
