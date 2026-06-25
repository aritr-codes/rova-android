package com.aritr.rova.gradle

/**
 * Verbatim lift of the former app/build.gradle.kts checkSchedulerNoGetService
 * doLast body. Two mechanical swaps only: iterate the passed [files] instead of
 * dir.walkTopDown(); use f.relPath/f.lines instead of File.relativeTo(rootDir)/
 * readLines(). Rule, comment-skip, predicate, and message are unchanged.
 */
internal fun ruleSchedulerNoGetService(files: List<SourceFile>): String? {
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .mapNotNull { f ->
            val hits = f.lines
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
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, hits) ->
        hits.joinToString("\n") { (i, line) ->
            "  ${f.relPath}:${i + 1}: ${line.trim()}"
        }
    }
    return "PendingIntent.getService is forbidden in alarm scheduler sources " +
        "(Android 12+ forbids FGS starts from background contexts). Offenders:\n$report"
}
