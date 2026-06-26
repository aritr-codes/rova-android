package com.aritr.rova.gradle

/**
 * Verbatim lift of the former app/build.gradle.kts checkSchedulerNoGetService
 * doLast body. Two mechanical swaps only: iterate the passed [files] instead of
 * dir.walkTopDown(); use f.relPath/f.lines instead of File.relativeTo(rootDir)/
 * readLines(). Rule, comment-skip, predicate, and message are unchanged.
 *
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out +
 * window + report use the raw line.
 */
internal fun ruleSchedulerNoGetService(files: List<SourceFile>): String? {
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .mapNotNull { f ->
            val hits = f.lines
                .withIndex()
                .filter { (idx, line) ->
                    // Match getService( on PendingIntent. Detection runs on the
                    // CommentStripper-blanked line so block-comment and line-comment
                    // tokens are ignored; the raw line is used only for reporting.
                    val stripped = f.strippedLines.getOrElse(idx) { "" }
                    stripped.contains("PendingIntent.getService(") ||
                        Regex("""\bgetService\s*\(""").containsMatchIn(stripped) &&
                        stripped.contains("PendingIntent")
                }
            if (hits.isEmpty()) null else f to hits
        }
        .toList()
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, hits) ->
        hits.joinToString("\n") { (i, line) ->
            "  ${f.relPath}:${i + 1}: ${line.trim()}"
        }
    }
    return "PendingIntent.getService is forbidden in alarm scheduler sources " +
        "(Android 12+ forbids FGS starts from background contexts). Offenders:\n$report"
}
