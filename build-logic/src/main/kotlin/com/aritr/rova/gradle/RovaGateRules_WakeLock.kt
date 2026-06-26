package com.aritr.rova.gradle

/**
 * Verbatim lifts of three former app/build.gradle.kts inline gate bodies
 * (Phase 1.8 / C17 WakeLock discipline gates).
 * Mechanical swaps only vs the inline body:
 *   - iterate [files] parameter instead of listOf(serviceFile, policyFile).filter { it.exists() }
 *   - f.lines / f.text / f.relPath instead of readLines() / readText() / relativeTo(rootDir)
 *   - return message instead of throw GradleException(message); return null to pass
 *   - #1 (forbid): empty input -> null (no files, no offenders)
 *   - #2 and #3 (require/structural): old code threw on !exists() before the require-check,
 *     so empty input -> FAIL (return the "source file missing" message), fail-closed.
 */

// ─── checkWakeLockBoundedAcquire ─────────────────────────────────────────────

/**
 * Verbatim lift of checkWakeLockBoundedAcquire.
 * Scope: RovaRecordingService.kt and WakeLockPolicy.kt.
 * Forbid bare .acquire() (no timeout arg).
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 * Empty input: null (no files, no offenders — forbid gate).
 */
internal fun ruleWakeLockBoundedAcquire(files: List<SourceFile>): String? {
    val unboundedAcquire = Regex("""\.acquire\s*\(\s*\)""")
    val offenders = mutableListOf<String>()
    files.forEach { f ->
        f.lines.forEachIndexed { idx, raw ->
            if (unboundedAcquire.containsMatchIn(f.strippedLines.getOrElse(idx) { "" })) {
                offenders += "  ${f.relPath}:${idx + 1}: ${raw.trimStart().take(120)}"
            }
        }
    }
    if (offenders.isNotEmpty()) {
        return "C17 violation (Phase 1.8 §WakeLock Discipline) — " +
            "WakeLock.acquire() must pass a timeout. Use " +
            "acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS):\n" +
            offenders.joinToString("\n")
    }
    return null
}

// ─── checkWakeLockHeldRefresh ─────────────────────────────────────────────────

/**
 * Verbatim lift of checkWakeLockHeldRefresh.
 * Single-file REQUIRE: inside private fun acquireWakeLock() body, require
 * "existing?.isHeld == true" AND "existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)".
 * Closing-brace heuristic: first line == "    }" after fnStart (4-space member indent).
 * Empty input: FAIL with "source file missing" — old gate threw on !exists()
 * before the require-check (no early pass/return@doLast was present).
 */
internal fun ruleWakeLockHeldRefresh(files: List<SourceFile>): String? {
    val serviceRelPathSuffix = "service/RovaRecordingService.kt"
    val f = files.firstOrNull {
        it.relPath.replace('\\', '/').endsWith(serviceRelPathSuffix)
    } ?: return "checkWakeLockHeldRefresh: source file missing: " +
        "src/main/java/com/aritr/rova/service/RovaRecordingService.kt"

    val lines = f.lines
    val fnStart = lines.indexOfFirst { it.contains("private fun acquireWakeLock()") }
    if (fnStart < 0) {
        return "checkWakeLockHeldRefresh: acquireWakeLock() declaration not found in " +
            f.relPath
    }
    // Walk forward to the first matching } at the function's
    // opening-brace indent. acquireWakeLock is declared with 4-space
    // indent, so its closing brace is `    }` exactly.
    val fnEnd = (fnStart + 1 until lines.size).firstOrNull { lines[it] == "    }" }
        ?: return "checkWakeLockHeldRefresh: could not locate end of acquireWakeLock() body."

    val body = lines.subList(fnStart, fnEnd + 1).joinToString("\n")
    val hasHeldGuard = body.contains("existing?.isHeld == true")
    val hasRefresh = body.contains("existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)")
    if (!hasHeldGuard || !hasRefresh) {
        return "C17 round-2 violation (Phase 1.8 §WakeLock Discipline) — " +
            "acquireWakeLock() must refresh the bounded timeout on the " +
            "held branch.\n" +
            "Required statements inside the function body:\n" +
            "  - `existing?.isHeld == true` guard: ${if (hasHeldGuard) "OK" else "MISSING"}\n" +
            "  - `existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)`: ${if (hasRefresh) "OK" else "MISSING"}\n" +
            "Fix: in the `if (existing?.isHeld == true)` branch, call " +
            "`existing.acquire(WakeLockPolicy.ACQUIRE_TIMEOUT_MS)` before " +
            "returning so continuous sessions cannot outlive the timeout."
    }
    return null
}

// ─── checkWakeLockZeroGapRefresh ──────────────────────────────────────────────

/**
 * Verbatim lift of checkWakeLockZeroGapRefresh.
 * Single-file structural: from "if (waitSeconds > 0)", inspect next 20 lines;
 * require a } else { (regex) and an acquireWakeLock() in that window.
 * Empty input: FAIL with "source file missing" — old gate threw on !exists()
 * before the require-check (no early pass/return@doLast was present).
 */
internal fun ruleWakeLockZeroGapRefresh(files: List<SourceFile>): String? {
    val serviceRelPathSuffix = "service/RovaRecordingService.kt"
    val f = files.firstOrNull {
        it.relPath.replace('\\', '/').endsWith(serviceRelPathSuffix)
    } ?: return "checkWakeLockZeroGapRefresh: source file missing: " +
        "src/main/java/com/aritr/rova/service/RovaRecordingService.kt"

    val lines = f.lines
    val ifIdx = lines.indexOfFirst { it.contains("if (waitSeconds > 0)") }
    if (ifIdx < 0) {
        return "checkWakeLockZeroGapRefresh: `if (waitSeconds > 0)` not found in " +
            f.relPath
    }
    val window = lines.subList(ifIdx, minOf(ifIdx + 20, lines.size)).joinToString("\n")
    val hasElse = Regex("""\}\s*else\s*\{""").containsMatchIn(window)
    val hasRefresh = window.contains("acquireWakeLock()")
    if (!hasElse || !hasRefresh) {
        return "C17 round-3 violation (Phase 1.8 §WakeLock Discipline) — " +
            "the `if (waitSeconds > 0) { waitForNextSegment(...) }` block must " +
            "have an `else { acquireWakeLock() }` clause so back-to-back " +
            "(interval <= duration) sessions still refresh the bounded " +
            "WakeLock timeout.\n" +
            "  - `} else {` clause: ${if (hasElse) "OK" else "MISSING"}\n" +
            "  - `acquireWakeLock()` in window: ${if (hasRefresh) "OK" else "MISSING"}\n" +
            "Fix: add `} else { acquireWakeLock() }` after the " +
            "`waitForNextSegment` call inside the recording loop."
    }
    return null
}
