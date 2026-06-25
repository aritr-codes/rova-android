package com.aritr.rova.gradle

/**
 * Single-pass, string/char/raw-string-literal-aware comment stripper shared by
 * the static gates. Replaces `//` line comments and C-style block comments
 * (nesting-aware — Kotlin allows nested block comments) with spaces, preserving
 * every `\n` and `\r` and the total length, so a gate that detects on the
 * stripped text and REPORTS from the raw line keeps byte-identical line/column
 * offsets.
 *
 * String (`"…"`), char (`'…'`) and raw (`"""…"""`) literals are emitted
 * VERBATIM: a comment marker inside a literal can never start a comment, and a
 * quote inside a comment can never start a literal. This closes the F1/F2
 * false-pass holes (a block-comment opener inside a `//` line-comment or a
 * string literal used to flip a hand-rolled block-comment flag and disable the
 * gate).
 *
 * OUT OF SCOPE (documented, invariant-safe): Kotlin string-template `${ … }`
 * bodies are NOT descended into — template-internal comments are kept verbatim.
 * The pre-migration gates ran their regex on raw lines, so they already saw
 * template-internal content; descending would REMOVE detections the original
 * kept, violating the migration invariant. Nested templates or block comments
 * inside a template body are therefore not handled (acceptably rare in gate-
 * scanned source; a clean :app:assembleDebug proves no real file regresses).
 *
 * Also out of scope: a raw string with 4+ consecutive quotes (e.g. a body
 * ending in a quote, `""""x""""`) closes greedily on the FIRST `"""` run. The
 * only effect is that a later comment marker still inside that raw-string body
 * could be blanked — strictly the lenient direction (a possible missed
 * detection, never a NEW false-reject), so it cannot violate the migration
 * invariant; and the whole-repo :app:assembleDebug backstop proves no real
 * gate-scanned file regresses. Such literals do not occur in this repo's
 * gate-scanned source.
 */
internal object CommentStripper {
    fun strip(text: String): String {
        val out = CharArray(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                // Raw string """ … """ — copy verbatim incl. // and /* markers.
                c == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"' -> {
                    out[i] = '"'; out[i + 1] = '"'; out[i + 2] = '"'; i += 3
                    while (i < n) {
                        if (text[i] == '"' && i + 2 < n && text[i + 1] == '"' && text[i + 2] == '"') {
                            out[i] = '"'; out[i + 1] = '"'; out[i + 2] = '"'; i += 3; break
                        }
                        out[i] = text[i]; i++
                    }
                }
                // Normal string — newline terminates even after a backslash
                // (defensive against malformed/uncompilable input).
                c == '"' -> {
                    out[i] = c; i++
                    while (i < n) {
                        val d = text[i]
                        if (d == '\n' || d == '\r') break
                        out[i] = d
                        if (d == '\\' && i + 1 < n) { out[i + 1] = text[i + 1]; i += 2; continue }
                        i++
                        if (d == '"') break
                    }
                }
                // Char literal — same escape + newline-terminates rule.
                c == '\'' -> {
                    out[i] = c; i++
                    while (i < n) {
                        val d = text[i]
                        if (d == '\n' || d == '\r') break
                        out[i] = d
                        if (d == '\\' && i + 1 < n) { out[i + 1] = text[i + 1]; i += 2; continue }
                        i++
                        if (d == '\'') break
                    }
                }
                // Line comment — blank to (not including) the next \n OR \r so the
                // terminators survive for reader().readLines() alignment.
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n' && text[i] != '\r') { out[i] = ' '; i++ }
                }
                // Block comment — nesting-aware; blank everything except \n and \r.
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    var depth = 0
                    while (i < n) {
                        if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                            out[i] = ' '; out[i + 1] = ' '; depth++; i += 2
                        } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                            out[i] = ' '; out[i + 1] = ' '; depth--; i += 2
                            if (depth == 0) break
                        } else {
                            out[i] = if (text[i] == '\n' || text[i] == '\r') text[i] else ' '
                            i++
                        }
                    }
                }
                else -> { out[i] = c; i++ }
            }
        }
        return String(out)
    }
}
