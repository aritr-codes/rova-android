# Static-gate comment-strip hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the false-pass comment-detection holes (F1/F2) in the static gates with one correct shared comment-strip primitive, without changing any gate's accept/reject behavior on real source.

**Architecture:** Add `CommentStripper.strip` — a single-pass, string/char/raw-string/template-aware, nesting-aware, newline+length-preserving scanner — in the `build-logic/` included build. Expose it as lazy `strippedText`/`strippedLines` on the pure `SourceFile` value type. Migrate the must-fix F1 gate plus 6 forbid F2 gates onto it (detect on stripped, opt-out + report from raw). Document-leave the 2 require/co-presence gates and the F4 alias gap.

**Tech Stack:** Kotlin (JVM, build-logic included build), JUnit 4 golden tests, Gradle 9.4.1 / AGP 9.2.1.

## Global Constraints

- **Invariant (overrides everything):** a migrated gate may only become STRICTER in the false-pass direction. It must NEVER newly reject code the original accepted, and NEVER change the failure-message bytes for any real (non-comment-edge) violation. It must still RED-fire byte-identically on a genuine violation. If closing a hole turns any real file red → STOP and surface it (latent real violation), escalate; do not "fix" it.
- **Scope:** `build-logic/` only. No `:app` source, no app behavior, no manifest schema bump, no ADR amend. If a clean fix needs an ADR/CLAUDE.md gate-doc touch → STOP and escalate.
- Never weaken/disable/delete a `check*`. JVM golden tests only; no Robolectric/instrumented.
- **Build WARM** — no prophylactic cache wipes. Only allowed wipe: deleting `.gradle/configuration-cache` between store/reuse experiments.
- **Subagent-driven:** subagents are EDIT-ONLY. The controller runs ALL gradle/tests/git/commits and serializes the shared Gradle daemon (one build at a time).
- **Standard Verification (run by controller after each task's edits, before commit):**
  - `./gradlew -p build-logic test` → BUILD SUCCESSFUL (all golden tests green: byte-identical existing + new hole-closed).
  - `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (fires all 46 gates on `preBuild`; proves no currently-green source flips red). NOT `:app:lintDebug` (pre-existing `VaultAndroidOps:267` NewApi RED is unrelated).
- **Final-only verification (Task 11):** `./gradlew :app:testDebugUnitTest` green; one config-cache-ON run `./gradlew :app:assembleDebug --configuration-cache --configuration-cache-problems=fail`; confirm the built APK installs on device `RZCYA1VBQ2H`.
- **Push/PR/merge ONLY on explicit owner GO.** PowerShell for git on Windows.

---

### Task 1: `CommentStripper` scanner + golden tests

**Files:**
- Create: `build-logic/src/main/kotlin/com/aritr/rova/gradle/CommentStripper.kt`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/CommentStripperTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal object CommentStripper { fun strip(text: String): String }` — returns `text` with `//` line comments and `/* */` block comments (nesting-aware) replaced by spaces, every `\n` and `\r` preserved, total length preserved; string/char/raw-string literals and template `${…}` bodies emitted verbatim.

- [ ] **Step 1: Write the failing tests**

Create `CommentStripperTest.kt`:

```kotlin
package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentStripperTest {

    // Expectations use padStart/padEnd so the "everything before/after the code
    // becomes spaces" intent is expressed without hand-counting space runs.

    @Test
    fun blanksLineComment_keepsCodeBefore() {
        val src = "val x = 1 // c"
        // " // c" → spaces; total length preserved.
        assertEquals("val x = 1".padEnd(src.length), CommentStripper.strip(src))
    }

    @Test
    fun blanksBlockComment_inline_keepsTrailingCode() {
        // The F2 shape: /* … */ then real code on the same line. Everything
        // before "val x = 1" (comment + the space) becomes spaces.
        val src = "/* note */ val x = 1"
        assertEquals("val x = 1".padStart(src.length), CommentStripper.strip(src))
    }

    @Test
    fun blanksMultiLineBlock_preservesNewlines() {
        val src = "a\n/* line1\nline2 */\nb"
        val out = CommentStripper.strip(src)
        assertEquals(src.length, out.length)
        val lines = out.split("\n")
        assertEquals(4, lines.size)
        assertEquals("a", lines[0])
        assertTrue(lines[1].isBlank())  // "/* line1" → all spaces
        assertTrue(lines[2].isBlank())  // "line2 */" → all spaces
        assertEquals("b", lines[3])
    }

    @Test
    fun handlesNestedBlockComment() {
        // Kotlin allows nested block comments — the whole thing is one comment.
        val src = "/* /* */ */x"
        assertEquals("x".padStart(src.length), CommentStripper.strip(src))
    }

    @Test
    fun slashSlashInsideStringIsNotAComment() {
        val src = """val u = "http://x" + a"""
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun slashStarInsideStringIsNotAComment() {
        val src = """val s = "a /* not comment" + b"""
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun quoteInsideBlockCommentDoesNotStartString() {
        // The reference layered-regex strip corrupted this; the scanner must not.
        // Everything up to and including the */ (and the trailing space) is comment.
        val src = """/* he said "hi */ val x = 1"""
        assertEquals("val x = 1".padStart(src.length), CommentStripper.strip(src))
    }

    @Test
    fun charLiteralSlashIsNotAComment() {
        val src = "val c = '/' ; val d = 2"
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun rawStringKeepsCommentMarkers() {
        val src = "val r = \"\"\"a // b /* c */ d\"\"\" + e"
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun divisionOperatorIsKept() {
        val src = "val q = a / b"
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun crlfPreservedAcrossLineComment() {
        // \r must survive so reader().readLines() splits identically to the raw text.
        val src = "a // c\r\nb"
        val out = CommentStripper.strip(src)
        assertEquals(src.length, out.length)
        assertTrue(out.contains("\r\n"))
        assertEquals(src.reader().readLines().size, out.reader().readLines().size)
        assertEquals("b", out.reader().readLines().last())
    }

    @Test
    fun lengthPreservedAlways() {
        val src = "x /* y */ z // w\n\"q\" 'r' /* a\nb */ end"
        assertEquals(src.length, CommentStripper.strip(src).length)
    }

    @Test
    fun templateBodyKeptVerbatim_documentedOutOfScope() {
        // Out-of-scope (invariant-safe): template-internal comments are NOT
        // descended into; kept verbatim, matching pre-migration raw-line behavior.
        val src = "val s = \"\${ /* x */ value }\""
        assertEquals(src, CommentStripper.strip(src))
    }

    @Test
    fun strayCloseBlockOutsideCommentIsCode() {
        val src = "val x = 1 */ "
        assertTrue(CommentStripper.strip(src).contains("val x = 1"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.CommentStripperTest"`
Expected: FAIL — `CommentStripper` is unresolved (does not compile yet).

- [ ] **Step 3: Write the scanner**

Create `CommentStripper.kt`:

```kotlin
package com.aritr.rova.gradle

/**
 * Single-pass, string/char/raw-string-literal-aware comment stripper shared by
 * the static gates. Replaces `//` line comments and `/* */` block comments
 * (nesting-aware — Kotlin allows nested block comments) with spaces, preserving
 * every `\n` and `\r` and the total length, so a gate that detects on the
 * stripped text and REPORTS from the raw line keeps byte-identical line/column
 * offsets.
 *
 * String (`"…"`), char (`'…'`) and raw (`"""…"""`) literals are emitted
 * VERBATIM: a `//` or `/*` inside a literal can never start a comment, and a
 * quote inside a comment can never start a literal. This closes the F1/F2
 * false-pass holes (a `/*` inside a `//` line-comment or a string literal used
 * to flip a hand-rolled block-comment flag and disable the gate).
 *
 * OUT OF SCOPE (documented, invariant-safe): Kotlin string-template `${ … }`
 * bodies are NOT descended into — template-internal comments are kept verbatim.
 * The pre-migration gates ran their regex on raw lines, so they already saw
 * template-internal content; descending would REMOVE detections the original
 * kept, violating the migration invariant. Nested templates / block comments
 * inside a template body are therefore not handled (acceptably rare in gate-
 * scanned source; a clean :app:assembleDebug proves no real file regresses).
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.CommentStripperTest"`
Expected: PASS (13 tests).

- [ ] **Step 5: Commit**

```bash
git add build-logic/src/main/kotlin/com/aritr/rova/gradle/CommentStripper.kt \
        build-logic/src/test/kotlin/com/aritr/rova/gradle/CommentStripperTest.kt
git commit -m "build(gates): add shared CommentStripper scanner + golden tests"
```

---

### Task 2: `SourceFile` lazy `strippedText` / `strippedLines`

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/SourceFile.kt`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/SourceFileTest.kt` (create)

**Interfaces:**
- Consumes: `CommentStripper.strip` (Task 1).
- Produces: `SourceFile.strippedText: String` and `SourceFile.strippedLines: List<String>` — lazy; `strippedLines[i]` aligns 1:1 with `lines[i]`.

- [ ] **Step 1: Write the failing test**

Create `SourceFileTest.kt`:

```kotlin
package com.aritr.rova.gradle

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceFileTest {

    private fun fromText(text: String) =
        SourceFile("a/B.kt", text.reader().readLines(), text)

    @Test
    fun strippedLinesAlignWithLines_noTrailingNewline() {
        val text = "val a = 1 // x\n/* b */ val c = 2"
        val sf = fromText(text)
        assertEquals(sf.lines.size, sf.strippedLines.size)
        assertEquals("val a = 1".padEnd("val a = 1 // x".length), sf.strippedLines[0])
        assertEquals("val c = 2".padStart("/* b */ val c = 2".length), sf.strippedLines[1])
    }

    @Test
    fun strippedLinesAlignWithLines_trailingNewline() {
        val text = "val a = 1\nval b = 2\n"
        val sf = fromText(text)
        assertEquals(sf.lines.size, sf.strippedLines.size)
    }

    @Test
    fun strippedLinesAlignWithLines_crlf() {
        val text = "val a = 1 // c\r\nval b = 2"
        val sf = fromText(text)
        assertEquals(sf.lines.size, sf.strippedLines.size)
    }

    @Test
    fun strippedLinesAlignWithLines_multiLineBlock() {
        val text = "a\n/* c1\nc2\nc3 */\nb"
        val sf = fromText(text)
        assertEquals(sf.lines.size, sf.strippedLines.size)
        assertEquals("a", sf.strippedLines[0])
        assertEquals("b", sf.strippedLines[4])
    }

    @Test
    fun strippedTextEqualsStripOfText() {
        val text = "x /* y */ z"
        assertEquals(CommentStripper.strip(text), fromText(text).strippedText)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.SourceFileTest"`
Expected: FAIL — `strippedText` / `strippedLines` unresolved.

- [ ] **Step 3: Add the lazy props**

Edit `SourceFile.kt` — replace the `data class` body:

```kotlin
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
```

(The two vals are body properties, NOT constructor params — `equals`/`hashCode`/`copy` and `SourceCheckTask`'s `sortedBy { relPath }` are unchanged.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.SourceFileTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add build-logic/src/main/kotlin/com/aritr/rova/gradle/SourceFile.kt \
        build-logic/src/test/kotlin/com/aritr/rova/gradle/SourceFileTest.kt
git commit -m "build(gates): add lazy strippedText/strippedLines to SourceFile"
```

---

### Task 3: Migrate F1 `ruleA11yTargetSizeToken` (must-fix) + F4 doc

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_A11yI18n.kt` (KDoc ~249-256; body 280-305)
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/A11yI18nRulesTest.kt` (add tests)

**Interfaces:**
- Consumes: `SourceFile.strippedLines` (Task 2).
- Produces: behavior-identical `ruleA11yTargetSizeToken` with the hole closed.

- [ ] **Step 1: Write the failing tests** (append to `A11yI18nRulesTest`, before the closing brace)

```kotlin
    @Test
    fun a11yTargetSizeToken_catchesTokenAfterInlineBlockComment() {
        // F2 hole: `/* … */` then a sub-floor token on one line used to be
        // dropped wholesale by the startsWith("/*") skip.
        val relPath = "app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt"
        val body = "    /* tweaked */ val camControlSize = 20.dp"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkA11yTargetSizeToken", files)
        val expected = "ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 2.5.8 Target " +
            "Size (Minimum)): an interactive-target size token is below " +
            "the 24.dp accessibility floor. A tappable control's size " +
            "token (button diameter / tap box / FAB / tile) " +
            "must be >= 24.dp. (Material 3's 48dp is a guideline; 24dp is " +
            "the WCAG AA bar.) Raise the token, or — if the 24dp touch " +
            "floor is met by call-site padding/`heightIn` rather than the " +
            "token itself — add `// a11y-opt-out: <reason>` (reason " +
            "required) on the token line.\nOffenders:\n" +
            "  $relPath:1: /* tweaked */ val camControlSize = 20.dp"
        assertEquals(expected, msg)
    }

    @Test
    fun a11yTargetSizeToken_stringSlashStarDoesNotDisableGate() {
        // F1 hole: a `/*` inside a string literal used to set inBlock=true and
        // never clear, silently disabling the gate for the rest of the file.
        val relPath = "app/src/main/java/com/aritr/rova/ui/theme/RovaTokens.kt"
        val body = "    val label = \"a /* not a comment\"\n    val camControlSize = 18.dp"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkA11yTargetSizeToken", files)
        val expected = "ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 2.5.8 Target " +
            "Size (Minimum)): an interactive-target size token is below " +
            "the 24.dp accessibility floor. A tappable control's size " +
            "token (button diameter / tap box / FAB / tile) " +
            "must be >= 24.dp. (Material 3's 48dp is a guideline; 24dp is " +
            "the WCAG AA bar.) Raise the token, or — if the 24dp touch " +
            "floor is met by call-site padding/`heightIn` rather than the " +
            "token itself — add `// a11y-opt-out: <reason>` (reason " +
            "required) on the token line.\nOffenders:\n" +
            "  $relPath:2: val camControlSize = 18.dp"
        assertEquals(expected, msg)
    }
```

- [ ] **Step 2: Run to verify the new tests fail and ALL existing ones still pass**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.A11yI18nRulesTest"`
Expected: the two new tests FAIL (current code drops/disables those lines); every existing `a11yTargetSizeToken_*` test PASSES.

- [ ] **Step 3: Migrate the rule body**

In `RovaGateRules_A11yI18n.kt`, replace the offenders block (the `.flatMap { f -> var inBlock = false … }.toList()`, currently ~lines 280-305) with:

```kotlin
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .flatMap { f ->
            f.lines.withIndex().mapNotNull inner@{ (idx, raw) ->
                // Opt-out marker lives in a `//` comment → read it from the RAW line.
                if (optOut.containsMatchIn(raw)) return@inner null
                // Detect the token decl on the comment-stripped line (shared
                // CommentStripper) so a `/*`-then-code line OR a `/*` inside a
                // string literal can neither hide a token nor disable the gate
                // (F1/F2 holes). REPORT the RAW line so message bytes are unchanged.
                val strippedLine = f.strippedLines.getOrElse(idx) { "" }
                val m = tokenDecl.find(strippedLine) ?: return@inner null
                if (m.groupValues[1] !in interactiveSizeTokens) return@inner null
                if (m.groupValues[2].toDouble() >= minTargetDp) null
                else Triple(f, idx + 1, raw.trim())
            }
        }
        .toList()
```

This deletes the `var inBlock`/`wasInBlock`/`opens`/`closes` machine and the
`trimmed.startsWith("//" / "*" / "/*")` skip. The `interactiveSizeTokens`,
`minTargetDp`, `tokenDecl`, `optOut` declarations above stay.

Also update the rule KDoc (replace the "Block-comment state tracking" / "Comment-skip" lines and add the F4 note):

```kotlin
/**
 * Verbatim lift of checkA11yTargetSizeToken (comment detection hardened 2026-06-25).
 * Structural: val NAME = N.dp token decls in a curated interactive-token set must be >= 24.0.
 * Comment handling: detection runs on `f.strippedLines` (shared CommentStripper —
 * block + line comments blanked, string/char literals kept), so a `/*`-then-code
 * line or a string-literal `/*` can neither hide a token nor disable the gate.
 * Opt-out (`a11y-opt-out:\s*\S`) is read from the RAW line; offenders REPORT the RAW line.
 * F4 (accepted, documented): the value regex matches only a numeric literal
 * `\d+(\.\d+)?\.dp`, so a curated token redefined as an alias/expression
 * (`val primaryActionSize = fabSize`) is NOT range-checked. Widening to flag
 * aliases is deliberately out of scope — it could newly reject legitimate
 * indirection (invariant violation) and would need separate owner sign-off.
 * Empty input: null (no files = no interactive tokens = passes).
 */
```

- [ ] **Step 4: Run the full family suite**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.A11yI18nRulesTest"`
Expected: PASS (all existing + 2 new). The existing `a11yTargetSizeToken_skipsBlockComment`, `_skipsOptOut`, `_failsWhenTokenBelowFloor`, `_passesWhenExactlyAtFloor`, `_skipsNonCuratedToken` MUST stay green (proves byte-identical accept/reject + message on real cases).

- [ ] **Step 5: Standard Verification**

Run (controller): `./gradlew -p build-logic test` then `./gradlew :app:assembleDebug`.
Expected: both BUILD SUCCESSFUL. If `:app:assembleDebug` turns RED on a real file → STOP, surface as latent violation, escalate.

- [ ] **Step 6: Commit**

```bash
git add build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_A11yI18n.kt \
        build-logic/src/test/kotlin/com/aritr/rova/gradle/A11yI18nRulesTest.kt
git commit -m "build(gates): migrate F1 ruleA11yTargetSizeToken onto CommentStripper; doc F4"
```

---

### Task 4: Migrate `ruleLibraryNoManifestWrite` (F2)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_IconTheme.kt:205-210`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/IconThemeRulesTest.kt`

**Interfaces:** Consumes `SourceFile.strippedLines`. Produces behavior-identical rule, hole closed.

- [ ] **Step 1: Write the failing test** (append to `IconThemeRulesTest`)

```kotlin
    @Test
    fun libraryNoManifestWrite_catchesCallAfterInlineBlockComment() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/library/LibraryScreen.kt"
        val body = "    /* tidy */ writeManifestAtomic(m)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkLibraryNoManifestWrite", files)
        val expected = "ADR-0030 §2: Library/History UI must not mutate SessionManifest — use LibraryMetadataStore " +
            "(recovery-owned writes belong in ui/recovery/):\n" +
            "ui/library/LibraryScreen.kt:1: /* tidy */ writeManifestAtomic(m)"
        assertEquals(expected, msg)
    }
```

(Confirm the helper name and existing imports match the file; reuse the file's existing `src(...)` helper.)

- [ ] **Step 2: Run to verify new test fails, existing pass**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.IconThemeRulesTest"`
Expected: new test FAILS; existing `libraryNoManifestWrite_*` tests PASS.

- [ ] **Step 3: Migrate the loop body**

Replace (lines ~205-210):

```kotlin
        f.lines.forEachIndexed { i, line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
            val code = line.substringBefore("//")
            if (callRegex.containsMatchIn(code)) offenders += "$rel:${i + 1}: ${line.trim()}"
        }
```

with:

```kotlin
        f.lines.forEachIndexed { i, line ->
            // Detect on the comment-stripped line (shared CommentStripper) so a
            // `/* … */`-then-code line or a string-literal marker can no longer
            // hide a forbidden manifest write. Report the RAW line (bytes unchanged).
            val code = f.strippedLines.getOrElse(i) { "" }
            if (callRegex.containsMatchIn(code)) offenders += "$rel:${i + 1}: ${line.trim()}"
        }
```

Update the rule's `Comment-skip` KDoc line to: `Comment handling: detection on f.strippedLines (CommentStripper); report uses the raw line.`

- [ ] **Step 4: Run the family suite**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.IconThemeRulesTest"`
Expected: PASS (existing + new).

- [ ] **Step 5: Standard Verification** — `./gradlew -p build-logic test` then `./gradlew :app:assembleDebug`. Both SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_IconTheme.kt \
        build-logic/src/test/kotlin/com/aritr/rova/gradle/IconThemeRulesTest.kt
git commit -m "build(gates): migrate ruleLibraryNoManifestWrite onto CommentStripper"
```

---

### Task 5: Migrate `ruleNoLegacyModeStrings` (F2)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotation.kt:87-93`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/ModeRotationRulesTest.kt`

**Interfaces:** Consumes `SourceFile.strippedLines`. Produces behavior-identical rule, hole closed.

- [ ] **Step 1: Write the failing test** (append to `ModeRotationRulesTest`)

```kotlin
    @Test
    fun noLegacyModeStrings_catchesLiteralAfterInlineBlockComment() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt"
        val body = "    /* legacy */ val m = \"Portrait\""
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkNoLegacyModeStrings", files)
        val expected = "ADR-0029 PR-γ §6: legacy mode strings in live paths (use CaptureTopology):\n" +
            "ui/screens/SomeScreen.kt:1: /* legacy */ val m = \"Portrait\""
        assertEquals(expected, msg)
    }
```

- [ ] **Step 2: Run to verify new test fails, existing pass**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.ModeRotationRulesTest"`
Expected: new FAILS; existing PASS.

- [ ] **Step 3: Migrate the loop body**

Replace (lines ~87-93):

```kotlin
        f.lines.forEachIndexed { i, line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
            if (legacyMode.containsMatchIn(line)) {
                offenders += "$rel:${i + 1}: ${line.trim()}"
            }
        }
```

with:

```kotlin
        f.lines.forEachIndexed { i, line ->
            // Detect on the comment-stripped line (string literals kept verbatim,
            // so a real "Portrait" literal still matches); report the RAW line.
            if (legacyMode.containsMatchIn(f.strippedLines.getOrElse(i) { "" })) {
                offenders += "$rel:${i + 1}: ${line.trim()}"
            }
        }
```

Update the KDoc `Comment-skip` line to: `Comment handling: detection on f.strippedLines (CommentStripper); string literals kept so real mode-string literals still match; report uses the raw line.`

- [ ] **Step 4: Run the family suite**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.ModeRotationRulesTest"`
Expected: PASS.

- [ ] **Step 5: Standard Verification** — `./gradlew -p build-logic test` then `./gradlew :app:assembleDebug`. Both SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotation.kt \
        build-logic/src/test/kotlin/com/aritr/rova/gradle/ModeRotationRulesTest.kt
git commit -m "build(gates): migrate ruleNoLegacyModeStrings onto CommentStripper"
```

---

### Task 6: Migrate `ruleFrontBackCapabilityGated` (F2)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotation.kt:142-146`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/ModeRotationRulesTest.kt`

**Interfaces:** Consumes `SourceFile.strippedLines`. Produces behavior-identical rule, hole closed.

- [ ] **Step 1: Write the failing test** (append to `ModeRotationRulesTest`)

```kotlin
    @Test
    fun frontBackCapabilityGated_catchesAfterInlineBlockComment() {
        val relPath = "app/src/main/java/com/aritr/rova/ui/screens/SomeScreen.kt"
        val body = "    /* note */ val t = FrontBack"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkFrontBackCapabilityGated", files)
        val expected = "ADR-0029 §5: FrontBack outside the capability-gated registry:\n" +
            "ui/screens/SomeScreen.kt:1"
        assertEquals(expected, msg)
    }
```

- [ ] **Step 2: Run to verify new test fails, existing pass**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.ModeRotationRulesTest"`
Expected: new FAILS; existing PASS.

- [ ] **Step 3: Migrate the loop body**

Replace (lines ~142-146):

```kotlin
        f.lines.forEachIndexed { i, line ->
            val t = line.trimStart()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
            if (line.contains("FrontBack")) offenders += "$rel:${i + 1}"
        }
```

with:

```kotlin
        f.lines.forEachIndexed { i, line ->
            // Detect on the comment-stripped line; report is "$rel:line" (no content).
            if (f.strippedLines.getOrElse(i) { "" }.contains("FrontBack")) offenders += "$rel:${i + 1}"
        }
```

Update the KDoc `Comment-skip` line to: `Comment handling: detection on f.strippedLines (CommentStripper).`

- [ ] **Step 4: Run the family suite**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.ModeRotationRulesTest"`
Expected: PASS.

- [ ] **Step 5: Standard Verification** — `./gradlew -p build-logic test` then `./gradlew :app:assembleDebug`. Both SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_ModeRotation.kt \
        build-logic/src/test/kotlin/com/aritr/rova/gradle/ModeRotationRulesTest.kt
git commit -m "build(gates): migrate ruleFrontBackCapabilityGated onto CommentStripper"
```

---

### Task 7: Migrate `ruleExportIsPendingGuarded` (F2)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Pending.kt:51-56`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/PendingRulesTest.kt`

**Interfaces:** Consumes `SourceFile.strippedLines`. Produces behavior-identical rule. The file-level guard check (`f.text.contains(...)`) stays on RAW text — unchanged.

- [ ] **Step 1: Write the failing test** (append to `PendingRulesTest`)

```kotlin
    @Test
    fun exportIsPendingGuarded_catchesAfterInlineBlockComment_whenUnguarded() {
        // Unguarded IS_PENDING hidden after a same-line block comment used to escape.
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = "    /* set */ values.put(IS_PENDING, 0)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportIsPendingGuarded", files)
        val expected = "IS_PENDING used without SDK gating in service/export/ — " +
            "the file must be annotated @RequiresApi(Build.VERSION_CODES.Q) " +
            "or guard the reference with `Build.VERSION.SDK_INT >= " +
            "Build.VERSION_CODES.Q`. Offenders:\n" +
            "  $relPath:1: /* set */ values.put(IS_PENDING, 0)"
        assertEquals(expected, msg)
    }
```

- [ ] **Step 2: Run to verify new test fails, existing pass**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.PendingRulesTest"`
Expected: new FAILS; existing `exportIsPendingGuarded_*` PASS.

- [ ] **Step 3: Migrate the hit filter**

Replace (lines ~51-56):

```kotlin
            val hits = f.lines.withIndex()
                .filter { (_, line) ->
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) false
                    else pattern.containsMatchIn(line)
                }
```

with:

```kotlin
            val hits = f.lines.withIndex()
                .filter { (idx, _) ->
                    // Detect IS_PENDING on the comment-stripped line so a
                    // `/* … */`-then-code line or a string-literal marker can't
                    // hide it. The file-level guard check below stays on raw text.
                    pattern.containsMatchIn(f.strippedLines.getOrElse(idx) { "" })
                }
```

The `hits` still hold `(i, line)` raw pairs → the report `"  ${f.relPath}:${i + 1}: ${line.trim()}"` is byte-identical. Update the KDoc `Comment-skip` line to: `Comment handling: detection on f.strippedLines (CommentStripper); file-level guard via raw f.text.`

- [ ] **Step 4: Run the family suite**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.PendingRulesTest"`
Expected: PASS.

- [ ] **Step 5: Standard Verification** — `./gradlew -p build-logic test` then `./gradlew :app:assembleDebug`. Both SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Pending.kt \
        build-logic/src/test/kotlin/com/aritr/rova/gradle/PendingRulesTest.kt
git commit -m "build(gates): migrate ruleExportIsPendingGuarded onto CommentStripper"
```

---

### Task 8: Migrate `ruleExportSetIncludePendingGuarded` (F2)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Pending.kt:96-99`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/PendingRulesTest.kt`

**Interfaces:** Consumes `SourceFile.strippedLines`. Hit detection moves to stripped; the ±30 SDK-branch window stays RAW (guards are code, byte-identical).

- [ ] **Step 1: Write the failing test** (append to `PendingRulesTest`)

```kotlin
    @Test
    fun exportSetIncludePendingGuarded_catchesAfterInlineBlockComment_whenNoSdkBranch() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = "    /* x */ builder.setIncludePending(1)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportSetIncludePendingGuarded", files)
        val expected = "setIncludePending used without an SDK branch against " +
            "Build.VERSION_CODES.R — must run only on API 29 (deprecated " +
            "and unreliable on API 30+). Offenders:\n" +
            "  $relPath:1: /* x */ builder.setIncludePending(1)"
        assertEquals(expected, msg)
    }
```

- [ ] **Step 2: Run to verify new test fails, existing pass**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.PendingRulesTest"`
Expected: new FAILS; existing PASS.

- [ ] **Step 3: Migrate the hit detection**

Replace (lines ~96-99):

```kotlin
            for ((i, line) in lines.withIndex()) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
                if (!pattern.containsMatchIn(line)) continue
```

with:

```kotlin
            for ((i, line) in lines.withIndex()) {
                // Detect the hit on the comment-stripped line; the ±30 SDK-branch
                // window below stays RAW (guards are code, byte-identical to today).
                if (!pattern.containsMatchIn(f.strippedLines.getOrElse(i) { "" })) continue
```

(`lines` is `f.lines`; the window, `hasSdkBranch`, and `hits += i + 1 to line.trim()` below are unchanged — raw.) Update the KDoc `Comment-skip` line to: `Comment handling: hit detection on f.strippedLines (CommentStripper); ±30 SDK-branch window stays raw.`

- [ ] **Step 4: Run the family suite**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.PendingRulesTest"`
Expected: PASS.

- [ ] **Step 5: Standard Verification** — `./gradlew -p build-logic test` then `./gradlew :app:assembleDebug`. Both SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Pending.kt \
        build-logic/src/test/kotlin/com/aritr/rova/gradle/PendingRulesTest.kt
git commit -m "build(gates): migrate ruleExportSetIncludePendingGuarded onto CommentStripper"
```

---

### Task 9: Migrate `ruleExportQueryArgMatchPendingGuarded` (F2)

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Pending.kt:141-144`
- Test: `build-logic/src/test/kotlin/com/aritr/rova/gradle/PendingRulesTest.kt`

**Interfaces:** Same shape as Task 8 — hit detection moves to stripped; ±30 window stays RAW.

- [ ] **Step 1: Write the failing test** (append to `PendingRulesTest`)

```kotlin
    @Test
    fun exportQueryArgMatchPendingGuarded_catchesAfterInlineBlockComment_whenNoSdkBranch() {
        val relPath = "app/src/main/java/com/aritr/rova/service/export/Tier1Exporter.kt"
        val body = "    /* x */ args.putInt(QUERY_ARG_MATCH_PENDING, 1)"
        val files = listOf(src(relPath, body))
        val msg = RovaGateRules.run("checkExportQueryArgMatchPendingGuarded", files)
        val expected = "QUERY_ARG_MATCH_PENDING used without an SDK branch against " +
            "Build.VERSION_CODES.R — must run only on API 30+ " +
            "(NoSuchFieldError on Q). Offenders:\n" +
            "  $relPath:1: /* x */ args.putInt(QUERY_ARG_MATCH_PENDING, 1)"
        assertEquals(expected, msg)
    }
```

- [ ] **Step 2: Run to verify new test fails, existing pass**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.PendingRulesTest"`
Expected: new FAILS; existing PASS.

- [ ] **Step 3: Migrate the hit detection**

Replace (lines ~141-144):

```kotlin
            for ((i, line) in lines.withIndex()) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
                if (!pattern.containsMatchIn(line)) continue
```

with:

```kotlin
            for ((i, line) in lines.withIndex()) {
                // Detect the hit on the comment-stripped line; the ±30 SDK-branch
                // window below stays RAW (guards are code, byte-identical to today).
                if (!pattern.containsMatchIn(f.strippedLines.getOrElse(i) { "" })) continue
```

Update the KDoc `Comment-skip` line as in Task 8.

- [ ] **Step 4: Run the family suite**

Run: `./gradlew -p build-logic test --tests "com.aritr.rova.gradle.PendingRulesTest"`
Expected: PASS.

- [ ] **Step 5: Standard Verification** — `./gradlew -p build-logic test` then `./gradlew :app:assembleDebug`. Both SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Pending.kt \
        build-logic/src/test/kotlin/com/aritr/rova/gradle/PendingRulesTest.kt
git commit -m "build(gates): migrate ruleExportQueryArgMatchPendingGuarded onto CommentStripper"
```

---

### Task 10: Document the conservative-leave of the 2 require gates

**Files:**
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Pending.kt` (KDoc of `ruleExportPendingVisibilityOnQuery`, ~169-187)
- Modify: `build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Export.kt` (KDoc of `ruleExportCleanupPredicate`, ~245-264)

**Rationale:** Both gates are co-presence REQUIREs that build a non-comment text via the `startsWith("//"/"*"/"/*")` filter, then check `contains(token)`. Their `startsWith("/*")` edge only ever causes OVER-strictness (a real token hidden after a same-line block comment is dropped → require false-FAILS) — it never false-PASSES. Migrating them would change behavior in the LENIENT direction (fix the false-fail), which is out of scope for this false-pass-hardening track and would shift require semantics. Per owner guidance ("migrate conservatively, do not blind-rewrite all 9 sites"), leave the logic and document the residual edge. No code/test behavior change in this task.

- [ ] **Step 1: Add the doc note to `ruleExportPendingVisibilityOnQuery` KDoc**

Append to its KDoc (after the existing `Non-comment text:` line):

```
 * NOTE (comment-strip hardening 2026-06-25): NOT migrated to CommentStripper.
 * This is a co-presence REQUIRE; its startsWith("/*") filter only ever causes
 * over-strictness (a token hidden after a same-line block comment is dropped →
 * the require false-FAILS), never a false-PASS. Migrating would shift behavior
 * in the lenient direction — out of scope for the false-pass-hardening track.
```

- [ ] **Step 2: Add the doc note to `ruleExportCleanupPredicate` KDoc**

Append to its KDoc (after the existing `Comment-strip:` paragraph):

```
 * NOTE (comment-strip hardening 2026-06-25): NOT migrated to CommentStripper —
 * same rationale as ruleExportPendingVisibilityOnQuery. This is a co-presence
 * REQUIRE; the startsWith("/*") edge only over-strictly FAILS (never
 * false-PASSES), so closing it would change behavior in the lenient direction,
 * out of scope here.
```

- [ ] **Step 3: Standard Verification** — `./gradlew -p build-logic test` then `./gradlew :app:assembleDebug`. Both SUCCESSFUL (doc-only; no test delta).

- [ ] **Step 4: Commit**

```bash
git add build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Pending.kt \
        build-logic/src/main/kotlin/com/aritr/rova/gradle/RovaGateRules_Export.kt
git commit -m "build(gates): document conservative-leave of the 2 require gates (comment-strip)"
```

---

### Task 11: Full-suite verification, config-cache, device install

**Files:** none (verification only).

- [ ] **Step 1: Full build-logic test suite**

Run: `./gradlew -p build-logic test`
Expected: BUILD SUCCESSFUL — CommentStripperTest, SourceFileTest, and all `*RulesTest` (incl. `RegistryTest`) green. Confirm `RegistryTest` `EXPECTED_IDS` still lists all 46 ids (no gate added/removed).

- [ ] **Step 2: App gates + app unit tests**

Run: `./gradlew :app:assembleDebug` then `./gradlew :app:testDebugUnitTest`
Expected: both BUILD SUCCESSFUL (46 gates fire green on preBuild; 1241 app tests pass). NOT `:app:lintDebug`.

- [ ] **Step 3: Config-cache survives**

Run: `./gradlew :app:assembleDebug --configuration-cache --configuration-cache-problems=fail`
Expected: BUILD SUCCESSFUL, `0 problems`, config cache stored/reused. (Only allowed cache wipe if needed: delete `.gradle/configuration-cache`.)

- [ ] **Step 4: Device install smoke**

```bash
adb -s RZCYA1VBQ2H install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`. (Build-logic-only change; no functional device smoke required beyond install.)

- [ ] **Step 5: Final hole-audit confirmation**

Confirm zero `startsWith("/*")` remain in the 7 migrated rule bodies and the F1 `inBlock`/`lastIndexOf` machine is gone:
Run: `grep -rn 'startsWith("/\*")' build-logic/src/main/kotlin/com/aritr/rova/gradle/`
Expected: matches ONLY in `RovaGateRules_Pending.kt` `ruleExportPendingVisibilityOnQuery` (line ~197) — the documented conservative-leave. (`ruleExportCleanupPredicate` lives in `RovaGateRules_Export.kt`; its leave is also documented.)

- [ ] **Step 6: Report to owner** — summarize commits, the 7 migrated gates + 2 documented leaves + F4 doc, and the GREEN verification evidence. STOP — push/PR/merge only on explicit owner GO.

---

## Self-Review

**Spec coverage:** CommentStripper (Task 1) ✓; SourceFile props (Task 2) ✓; F1 migration + proof a/b (Task 3) ✓; F4 doc (Task 3) ✓; 6 forbid F2 migrations + hole tests (Tasks 4-9) ✓; 2 require-gate documented-leave (Task 10) ✓; proof (c) assembleDebug + config-cache + device + RegistryTest (per-task Standard Verification + Task 11) ✓. The spec's "8 F2 sites" reconciles to 6 migrated + 2 documented-leave (the 2 require gates) — an explicit, owner-aligned refinement surfaced during file reads, recorded in Task 10's rationale.

**Placeholder scan:** no TBD/TODO; every code step shows full before/after; every test shows the exact expected message bytes.

**Type consistency:** `CommentStripper.strip(text: String): String`, `SourceFile.strippedText: String`, `SourceFile.strippedLines: List<String>`, `RovaGateRules.run(checkId, files)` used consistently across all tasks; `f.strippedLines.getOrElse(idx) { "" }` is the single detection idiom in every migration.
