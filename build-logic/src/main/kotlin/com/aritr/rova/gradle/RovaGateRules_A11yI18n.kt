package com.aritr.rova.gradle

/**
 * Verbatim lifts of six former app/build.gradle.kts inline gate bodies
 * (i18n / locale / a11y gates — ADR-0020, ADR-0022, ADR-0023, ADR-0029).
 * Mechanical swaps only vs the inline body:
 *   - iterate [files] parameter instead of dir.walkTopDown() / fileTree(...)
 *   - f.lines / f.text / f.relPath instead of readLines() / readText() / relativeTo(rootDir)
 *   - return message instead of throw GradleException(message); return null to pass
 *   - drop "if(!exists()) throw" guards; an empty file set is handled per gate:
 *       - FORBID gates (#1, #2, #4, #5): no files -> no offenders -> null (pass)
 *       - VOCAB gate (#3): empty list -> return null (no banned vocab found; files always
 *         exist in practice; old gate threw on file-missing which cannot happen here
 *         because the declared sources always flow in)
 *       - REQUIRE gate (#6): same as forbid/pass on empty (themeDir scan; no files = no
 *         interactive tokens declared = no violation; old gate threw only on dir-missing,
 *         not on file absence)
 *   - XML gates #2 and #3 use f.text / f.lines; NO .kt extension filter — the declared
 *     sources are the .xml files and they flow in as-is
 *   - #3 count-sanity: if declared != matched -> return that exact message (old threw)
 *   - #5 window: backWindow=15, forwardWindow=20 (idx-15..idx+20 from old code)
 *   - Separator note: f.relPath preserves platform separator (Windows '\', Linux '/')
 *     byte-identical to old `f.relativeTo(rootDir)`, NEVER normalised in reports
 */

// ─── checkNoHardcodedUiStrings ───────────────────────────────────────────────

/**
 * Verbatim lift of checkNoHardcodedUiStrings.
 * Forbid hardcoded user-facing string literals at Text( / contentDescription = call sites.
 * Comment-skip: lines starting with // or * (after trimStart) are ignored.
 * Opt-out: line containing "i18n-opt-out" is skipped.
 * Empty input: null (forbid gate, no files = no offenders).
 */
internal fun ruleNoHardcodedUiStrings(files: List<SourceFile>): String? {
    // `Text("` or `Text(text = "` — require `"` as the next non-space token
    // so `Text(stringResource(...))` / `Text(text = getString(...))` don't match.
    val textLiteral = Regex("""(^|[^A-Za-z0-9_])Text\(\s*(text\s*=\s*)?"""")
    // `contentDescription = "` (with or without spaces around `=`).
    val contentDescLiteral = Regex("""contentDescription\s*=\s*"""")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .mapNotNull { f ->
            val hits = f.lines
                .withIndex()
                .filter { (_, line) ->
                    if (line.contains("i18n-opt-out")) return@filter false
                    val trimmed = line.trimStart()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                    else textLiteral.containsMatchIn(line) ||
                        contentDescLiteral.containsMatchIn(line)
                }
            if (hits.isEmpty()) null else f to hits
        }
        .toList()
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, hits) ->
            hits.joinToString("\n") { (i, line) ->
                "  ${f.relPath}:${i + 1}: ${line.trim()}"
            }
        }
        return "Hardcoded user-facing string literal(s) found at Compose " +
            "`Text(`/`contentDescription =` call sites. User-facing copy " +
            "must live in `res/values/strings.xml` and be read via " +
            "`stringResource(...)` / `getString(...)` (ADR-0022 §No " +
            "Hardcoded UI Strings). For a genuinely non-user-facing " +
            "literal or @Preview-only sample data, add a " +
            "`// i18n-opt-out: <reason>` marker on the line to skip it. " +
            "Offenders:\n$report"
    }
    return null
}

// ─── checkLocaleConfigNoPseudolocale ─────────────────────────────────────────

/**
 * Verbatim lift of checkLocaleConfigNoPseudolocale.
 * Single-file XML: regex on android:name="..." attribute values for pseudolocale tags.
 * Scans ALL lines including XML comments (NO comment-skip).
 * Empty input: null (forbid gate, no files = no offenders).
 */
internal fun ruleLocaleConfigNoPseudolocale(files: List<SourceFile>): String? {
    // Match pseudolocale tags only inside android:name="..." attribute values.
    // Covers BCP-47 forms: en-XA, en-rXA, ar-XB, ar-rXB (case-insensitive).
    val pseudo = Regex("""android:name\s*=\s*"[^"]*\b(en|ar)-r?X[AB]\b[^"]*"""", RegexOption.IGNORE_CASE)
    val offenders = files.flatMap { f ->
        f.lines.withIndex().filter { (_, line) -> pseudo.containsMatchIn(line) }
            .map { (i, line) -> f to Pair(i, line) }
    }
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, pair) ->
            val (i, line) = pair
            "  ${f.relPath}:${i + 1}: ${line.trim()}"
        }
        return "Pseudolocale tag(s) found in locales_config.xml. Pseudolocales " +
            "(en_XA / ar_XB) are a DEBUG-ONLY QA tool and must never be " +
            "advertised as a user language (ADR-0023 §No Pseudolocale In " +
            "LocaleConfig). Remove them; keep generateLocaleConfig OFF. " +
            "Offenders:\n$report"
    }
    return null
}

// ─── checkUserCopyVocabulary ──────────────────────────────────────────────────

/**
 * Verbatim lift of checkUserCopyVocabulary.
 * XML two-file: parse <string name="..">val</string> via DOT_MATCHES_ALL over f.text,
 * flag banned vocab regex in values; throws (returns) if regex-extracted count != declared count.
 * Empty input: null (files always exist; empty list = no banned vocab found).
 */
internal fun ruleUserCopyVocabulary(files: List<SourceFile>): String? {
    val banned = Regex("(?i)\\b(loops?|repeats?|segments?|ciclos?|segmentos?|repeticion(es)?|bucles?)\\b")
    // Allowlist by resource NAME for justified exceptions (none expected at γ).
    val allowNames = setOf<String>()
    val offenders = mutableListOf<String>()
    for (f in files) {
        // Old gate reported the bare module-relative path literal it passed to
        // file(p) — e.g. "src/main/res/values/strings.xml" — NOT relativeTo(rootDir).
        // Reproduce byte-for-byte: drop the "app/" module prefix + forward slashes.
        val p = f.relPath.replace('\\', '/').removePrefix("app/")
        val text = f.text
        val nameRe = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val matches = nameRe.findAll(text).toList()
        val declared = Regex("<string ").findAll(text).count()
        if (declared != matches.size) {
            return "checkUserCopyVocabulary: parser matched ${matches.size}/$declared strings in $p — fix the regex"
        }
        matches.forEach { m ->
            val (name, value) = m.destructured
            if (name in allowNames) return@forEach
            if (banned.containsMatchIn(value)) offenders += "$p: $name = ${value.trim()}"
        }
    }
    if (offenders.isNotEmpty()) {
        return "ADR-0029 §C: banned vocabulary in user copy (use clip/session):\n" + offenders.joinToString("\n")
    }
    return null
}

// ─── checkA11yAnimationGated ──────────────────────────────────────────────────

/**
 * Verbatim lift of checkA11yAnimationGated.
 * REQUIRE: any .kt file using rememberInfiniteTransition( / infiniteRepeatable( must
 * also contain rememberReduceMotion( or ReducedMotion.isReduced.
 * Opt-out: line containing "a11y-opt-out" is skipped.
 * Comment-skip: lines starting with // or * (after trimStart) are ignored.
 * Empty input: null (no files, no triggers, passes).
 */
internal fun ruleA11yAnimationGated(files: List<SourceFile>): String? {
    // `\s*\(` tolerates the legal `rememberInfiniteTransition (` spacing variant.
    val rawPrimitive = Regex("""rememberInfiniteTransition\s*\(|infiniteRepeatable\s*\(""")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .mapNotNull { f ->
            val lines = f.lines
            val triggers = lines.withIndex().filter { (_, line) ->
                if (line.contains("a11y-opt-out")) return@filter false
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                else rawPrimitive.containsMatchIn(line)
            }
            if (triggers.isEmpty()) return@mapNotNull null
            val hasSeam = lines.any {
                it.contains("rememberReduceMotion(") || it.contains("ReducedMotion.isReduced")
            }
            if (hasSeam) null else f to triggers
        }
        .toList()
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, hits) ->
            hits.joinToString("\n") { (i, line) ->
                "  ${f.relPath}:${i + 1}: ${line.trim()}"
            }
        }
        return "ADR-0020 §Decision-3 violation (WCAG 2.2 AA — SC 2.3.3 Animation " +
            "from Interactions / SC 2.2.2 Pause, Stop, Hide): looping/" +
            "auto-playing animation primitive(s) used without a " +
            "reduced-motion guard in the same file. Every file that uses " +
            "`rememberInfiniteTransition` / `infiniteRepeatable` must also " +
            "read the reduced-motion seam (`rememberReduceMotion()` or " +
            "`ReducedMotion.isReduced`) and select a static value when " +
            "motion is reduced. For a genuinely static or @Preview-only " +
            "animation, add `// a11y-opt-out: <reason>` on the primitive " +
            "line.\nOffenders:\n$report"
    }
    return null
}

// ─── checkA11yClickableHasRole ────────────────────────────────────────────────

/**
 * Verbatim lift of checkA11yClickableHasRole.
 * REQUIRE window: .clickable / .combinedClickable needs role = Role. in idx-15..idx+20 window.
 * Opt-out: line matching a11y-opt-out:\s*\S is skipped.
 * Comment-skip: lines starting with // or * (after trimStart) are ignored.
 * Empty input: null (forbid/require gate, no files = no clickables = passes).
 */
internal fun ruleA11yClickableHasRole(files: List<SourceFile>): String? {
    // `.clickable(`/`.combinedClickable(` (paren) or `… {` (trailing lambda).
    val clickable = Regex("""\.(clickable|combinedClickable)\s*[({]""")
    // Require a literal `Role.` so the design-system `role = GlassRole.…`
    // Liquid Glass arg does NOT satisfy the a11y-role requirement.
    val roleAssign = Regex("""\brole\s*=\s*Role\.""")
    // Opt-out is honored only when a non-empty reason follows the colon.
    val optOut = Regex("""a11y-opt-out:\s*\S""")
    val backWindow = 15
    val forwardWindow = 20
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .mapNotNull { f ->
            val lines = f.lines
            val hits = lines.withIndex().filter { (idx, line) ->
                if (optOut.containsMatchIn(line)) return@filter false
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@filter false
                if (!clickable.containsMatchIn(line)) return@filter false
                val from = maxOf(0, idx - backWindow)
                val to = minOf(lines.size - 1, idx + forwardWindow)
                !roleAssign.containsMatchIn(lines.subList(from, to + 1).joinToString("\n"))
            }
            if (hits.isEmpty()) null else f to hits
        }
        .toList()
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, hits) ->
            hits.joinToString("\n") { (i, line) ->
                "  ${f.relPath}:${i + 1}: ${line.trim()}"
            }
        }
        return "ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 4.1.2 Name, " +
            "Role, Value): custom Modifier.clickable / combinedClickable " +
            "used without an accessibility role on the same modifier " +
            "chain. A custom clickable container (Row/Box/Surface/Column) " +
            "must declare a role so TalkBack announces it as actionable — " +
            "either `clickable(role = Role.Button, …)` or an adjacent " +
            "`.semantics { role = … }` / `.clearAndSetSemantics { role = … }`. " +
            "Material Button/IconButton supply a role already. For toggles/" +
            "selections use toggleable/selectable (out of scope here). For a " +
            "genuinely role-exempt case, add `// a11y-opt-out: <reason>` " +
            "(reason required) on the clickable line.\nOffenders:\n$report"
    }
    return null
}

// ─── checkA11yTargetSizeToken ─────────────────────────────────────────────────

/**
 * Verbatim lift of checkA11yTargetSizeToken.
 * Structural: val NAME = N.dp token decls in a curated interactive-token set must be >= 24.0.
 * Block-comment state tracking: /* ... */ blocks are skipped (including KDoc).
 * Opt-out: line matching a11y-opt-out:\s*\S is skipped.
 * Comment-skip: lines starting with // or * or /+ are skipped.
 * Empty input: null (no files = no interactive tokens = passes).
 */
internal fun ruleA11yTargetSizeToken(files: List<SourceFile>): String? {
    // Enumerated interactive-target size tokens (bare `val` names; the same
    // name declared in two token objects — e.g. camControlSize in RovaTokens
    // and RecordChromeTokens — is checked in both). EXTEND this set when a
    // new tappable control gets a size token.
    val interactiveSizeTokens = setOf(
        "camControlSize",    // cam-ctrl-btn diameter
        "stepperButtonSize", // stepper +/- button (RovaTokens)
        "stepBtnSize",       // stepper +/- button (SettingsSheetTokens)
        "primaryActionSize", // Start FAB
        "stopActionSize",    // Stop FAB
        "fabSize",           // record-chrome FAB
        "navIconBoxSize",    // bottom-nav item tap box
        "tileMinHeight",     // preset tile
        // NOT pinned: cellSlot — it is a 44dp VISUAL slot whose 24dp touch
        // floor is owned by the parent card's `heightIn(min = 48.dp)` at the
        // call site, not by the token. Pinning a proxy here would assert the
        // wrong thing; the call-site floor is a separate (future) invariant.
    )
    val minTargetDp = 24.0
    // `val NAME [: Dp] = <number>.dp` — tolerates the optional `: Dp` type.
    val tokenDecl = Regex("""\bval\s+(\w+)\s*(?::\s*Dp\s*)?=\s*(\d+(?:\.\d+)?)\s*\.dp\b""")
    val optOut = Regex("""a11y-opt-out:\s*\S""")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") || it.relPath.endsWith(".java") }
        .flatMap { f ->
            var inBlock = false // inside a /* … */ (incl. /** KDoc */) block
            f.lines.withIndex().mapNotNull inner@{ (idx, line) ->
                // Track block-comment state so a commented-out
                // `val NAME = N.dp` can NEVER false-fail (trailing marker
                // wins; a same-line `/* … */` is skipped by the `/*` prefix
                // check below). KDoc inner `*` lines are skipped too.
                val wasInBlock = inBlock
                val opens = line.lastIndexOf("/*")
                val closes = line.lastIndexOf("*/")
                if (opens >= 0 || closes >= 0) inBlock = opens > closes
                if (wasInBlock) return@inner null
                if (optOut.containsMatchIn(line)) return@inner null
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*") ||
                    trimmed.startsWith("/*")
                ) return@inner null
                val m = tokenDecl.find(line) ?: return@inner null
                if (m.groupValues[1] !in interactiveSizeTokens) return@inner null
                if (m.groupValues[2].toDouble() >= minTargetDp) null
                else Triple(f, idx + 1, line.trim())
            }
        }
        .toList()
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, ln, line) ->
            "  ${f.relPath}:$ln: $line"
        }
        return "ADR-0020 §Decision-1 violation (WCAG 2.2 AA — SC 2.5.8 Target " +
            "Size (Minimum)): an interactive-target size token is below " +
            "the 24.dp accessibility floor. A tappable control's size " +
            "token (button diameter / tap box / FAB / tile) " +
            "must be >= 24.dp. (Material 3's 48dp is a guideline; 24dp is " +
            "the WCAG AA bar.) Raise the token, or — if the 24dp touch " +
            "floor is met by call-site padding/`heightIn` rather than the " +
            "token itself — add `// a11y-opt-out: <reason>` (reason " +
            "required) on the token line.\nOffenders:\n$report"
    }
    return null
}
