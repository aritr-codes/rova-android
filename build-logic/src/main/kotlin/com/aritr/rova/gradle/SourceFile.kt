package com.aritr.rova.gradle

/**
 * Pure, Gradle-free representation of one scanned source file.
 *
 * Invariant (CLAUDE.md "Static-check gate"): rules operate ONLY on this — no
 * java.io.File / Project / rootDir — so the same logic is unit-testable and the
 * @TaskAction never captures the build-script receiver.
 *
 * @param relPath path relative to the report base dir (rootProject), forward/OS
 *   separators identical to the old `file.relativeTo(rootDir).path`.
 * @param lines `file.readLines(UTF_8)` — for the per-line regex/window gates.
 * @param text  `file.readText(UTF_8)` — for content-regex gates (DOT_MATCHES_ALL).
 */
data class SourceFile(
    val relPath: String,
    val lines: List<String>,
    val text: String,
) {
    /**
     * [text] with `//` and `/* */` comments blanked to spaces (string/char/raw
     * literals kept verbatim), newlines + length preserved. Computed once.
     */
    val strippedText: String by lazy { CommentStripper.strip(text) }

    /**
     * [strippedText] split with identical reader semantics to `File.readLines()`
     * (the source of [lines]) so `strippedLines[i]` corresponds to the same
     * source line as `lines[i]`. Rules detect on this, but REPORT from [lines].
     */
    val strippedLines: List<String> by lazy { strippedText.reader().readLines() }
}
