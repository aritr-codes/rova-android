package com.aritr.rova.gradle

/**
 * Verbatim lifts of nine former app/build.gradle.kts inline gate bodies
 * (icon/theme/glass/library/vault gates — ADR-0025, ADR-0028, ADR-0030, ADR-0031).
 * Mechanical swaps only vs the inline body:
 *   - iterate [files] parameter instead of fileTree / walkTopDown / single-file read
 *   - f.lines / f.text / f.relPath instead of readLines() / readText() / relativeTo(rootDir)
 *   - return message instead of throw GradleException(message); return null to pass
 *   - #1 (checkVaultExporterNoPublicPublish): single-file gate; missing-file => return message;
 *     report HARDCODES "VaultExporter.kt" (verbatim from old gate)
 *   - #2 (checkRecordSurfaceNoBlur): filename filter on f.relPath in rule
 *   - #3 (checkGlassSurfaceRoleUsage): filename allowlist; scans ui subtree
 *   - #4 (checkRecordChromeLockSingleSite): block-comment strip via Regex DOT_MATCHES_ALL;
 *     canonical-suffix compare for allowedWriter
 *   - #5 (checkLibraryNoManifestWrite): path-filter in rule; comment-skip incl slash-star
 *   - #6 (checkSemanticIconNoRawAlpha): 3-line window; canonical-suffix compare for seam
 *   - #7 (checkStatusColorLocked): simple pattern forbid
 *   - #8 (checkRovaGlyphHome): stripComments helper; canonical-suffix compare for home
 *   - #9 (checkSingleColorSchemeSource): strip+balanced-paren scan; waived helper inline
 *   - Separator note: relPath preserves platform separator (Windows '\', Linux '/').
 *     Local .replace('\\','/') used ONLY for suffix/path matching; reported relPath never
 *     normalised — byte-identical to old gate output.
 */

// ─── checkVaultExporterNoPublicPublish ────────────────────────────────────────

/**
 * Verbatim lift of checkVaultExporterNoPublicPublish.
 * Scope: single file — VaultExporter.kt (matched by relPath suffix).
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 * Missing file: return "source missing" message.
 * Report HARDCODES "VaultExporter.kt" (not relPath) — verbatim from old gate.
 */
internal fun ruleVaultExporterNoPublicPublish(files: List<SourceFile>): String? {
    val suffix = "service/export/VaultExporter.kt"
    val vf = files.firstOrNull { it.relPath.replace('\\', '/').endsWith(suffix) }
        ?: return "checkVaultExporterNoPublicPublish: VaultExporter.kt missing"

    val forbidden = listOf(
        "MediaStore",
        "MediaScannerConnection",
        "insertPendingRow",
        "scanAndWait",
        "DIRECTORY_MOVIES",
        "IS_PENDING",
        ".insert(",
        "getExternalStoragePublicDirectory",
    )
    val hits = vf.lines.withIndex().filter { (idx, _) ->
        forbidden.any { vf.strippedLine(idx).contains(it) }
    }
    if (hits.isNotEmpty()) {
        val report = hits.joinToString("\n") { (i, line) -> "  VaultExporter.kt:${i + 1}: ${line.trim()}" }
        return "ADR-0025: VaultExporter must not reference any public-publish API " +
            "(vault recordings stay app-private). Offenders:\n$report"
    }
    return null
}

// ─── checkRecordSurfaceNoBlur ─────────────────────────────────────────────────

/**
 * Verbatim lift of checkRecordSurfaceNoBlur.
 * Scope: files whose name (last path segment) is RecordScreen.kt or RecordChrome.kt.
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 */
internal fun ruleRecordSurfaceNoBlur(files: List<SourceFile>): String? {
    val recordChromeNames = setOf("RecordScreen.kt", "RecordChrome.kt")
    val blurPattern = Regex("""\.blur\s*\(|Modifier\s*\.\s*blur\b|RenderEffect|createBlurEffect""")
    val offenders = files
        .filter { f ->
            val name = f.relPath.replace('\\', '/').substringAfterLast('/')
            name in recordChromeNames
        }
        .mapNotNull { f ->
            val hits = f.lines.withIndex().filter { (idx, _) ->
                blurPattern.containsMatchIn(f.strippedLine(idx))
            }
            if (hits.isEmpty()) null else f to hits
        }
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, hits) ->
            hits.joinToString("\n") { (i, line) -> "  ${f.relPath}:${i + 1}: ${line.trim()}" }
        }
        return "ADR-0028 §2.3 violation: record-chrome files must not blur. Record glass " +
            "renders through GlassRole.RecordChrome, whose resolver returns " +
            "blurRadius=0 (fill + scrim + edge + opaque text capsule instead). " +
            "DualPreviewZone's RenderEffect on non-recorded margins is the only " +
            "documented carve-out and is not record chrome.\nOffenders:\n$report"
    }
    return null
}

// ─── checkGlassSurfaceRoleUsage ───────────────────────────────────────────────

/**
 * Verbatim lift of checkGlassSurfaceRoleUsage.
 * Scope: ui subtree files; allowlist by filename.
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 */
internal fun ruleGlassSurfaceRoleUsage(files: List<SourceFile>): String? {
    val allowlist = setOf(
        "GlassSurface.kt", "DualPreviewZone.kt", "WarningSheet.kt", "RecoveryCard.kt",
    )
    val blurPattern = Regex("""\.blur\s*\(|Modifier\s*\.\s*blur\b|RenderEffect|createBlurEffect""")
    val offenders = files
        .filter { f ->
            val name = f.relPath.replace('\\', '/').substringAfterLast('/')
            name !in allowlist
        }
        .mapNotNull { f ->
            val hits = f.lines.withIndex().filter { (idx, _) ->
                blurPattern.containsMatchIn(f.strippedLine(idx))
            }
            if (hits.isEmpty()) null else f to hits
        }
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, hits) ->
            hits.joinToString("\n") { (i, line) -> "  ${f.relPath}:${i + 1}: ${line.trim()}" }
        }
        return "ADR-0028 §2.1/§5 violation: Modifier.blur outside the sanctioned glass " +
            "wrapper. All translucent glass must render through " +
            "GlassSurface(role=…) so the GlassResolver owns the blur/fallback " +
            "decision. Permitted only in GlassSurface.kt, the DualPreviewZone.kt " +
            "carve-out, and the pre-existing glow-bloom sites.\nOffenders:\n$report"
    }
    return null
}

// ─── checkRecordChromeLockSingleSite ──────────────────────────────────────────

/**
 * Verbatim lift of checkRecordChromeLockSingleSite.
 * Scope: ui subtree; canonical allowedWriter = ui/screens/RecordScreen.kt (suffix match).
 * Comment handling: detection on f.strippedLines (CommentStripper); report uses the raw line.
 * C-consolidation: replaces the former non-nesting blockComment Regex + substringBefore("//")
 * with CommentStripper (nesting-aware, literal-preserving). Detection input is stricter;
 * failure-message bytes and allowedSuffix filter are unchanged.
 */
internal fun ruleRecordChromeLockSingleSite(files: List<SourceFile>): String? {
    val allowedSuffix = "ui/screens/RecordScreen.kt"
    val offenders = files
        .filter { f ->
            !f.relPath.replace('\\', '/').endsWith(allowedSuffix)
        }
        .mapNotNull { f ->
            val hits = f.lines.indices
                .filter { i -> f.strippedLine(i).contains("requestedOrientation") }
                .map { i -> i to f.lines.getOrElse(i) { "" } }
            if (hits.isEmpty()) null else f to hits
        }
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, hits) ->
            hits.joinToString("\n") { (i, line) -> "  ${f.relPath}:${i + 1}: ${line.trim()}" }
        }
        return "ADR-0029 §B″ violation: requestedOrientation touched on the UI side " +
            "outside RecordScreen.kt. The unified DisposableEffect in " +
            "RecordScreen is the ONLY UI-layer requestedOrientation writer; " +
            "RecordChromeLockPolicy.shouldLock is the sole decision point. " +
            "A second writer reintroduces lock/unlock races.\nOffenders:\n$report"
    }
    return null
}

// ─── checkLibraryNoManifestWrite ──────────────────────────────────────────────

/**
 * Verbatim lift of checkLibraryNoManifestWrite.
 * Scope: files under ui/library/ or ui/screens/ containing "History" or "Library".
 * Comment handling: detection on f.strippedLines (CommentStripper); report uses the raw line.
 */
internal fun ruleLibraryNoManifestWrite(files: List<SourceFile>): String? {
    val forbidden = listOf(
        "markTerminated", "appendSegment", "submitPersistFinalizedSegment",
        "setExportPending", "setExportPrivateTarget", "setExportCopying",
        "setExportSafPrivateTemp", "setExportSafTarget", "setExportFinalized",
        "setExportFailed", "setMediaScanCompleted", "incrementSafTransientRetry",
        "setExportPendingForSide", "setExportPrivateTargetForSide",
        "setExportSafPrivateTempForSide", "setExportSafTargetForSide",
        "setExportFinalizedForSide", "setMediaScanCompletedForSide",
        "setVaultFinalized", "setVaultFinalizedForSide", "setVaultState",
        "setVaultMovedOut", "setVaultStateVaultedAndClearPublic",
        "setPendingMoveOutTier1", "setPendingMoveOutPreQ", "setStopRequested",
        "writeManifestAtomic",
    )
    val callRegex = Regex("\\b(${forbidden.joinToString("|")})\\s*\\(")
    val offenders = mutableListOf<String>()
    files.forEach { f ->
        val rel = f.relPath.replace('\\', '/').substringAfter("com/aritr/rova/")
        val inScope = rel.startsWith("ui/library/") ||
            (rel.startsWith("ui/screens/") && (rel.contains("History") || rel.contains("Library")))
        if (!inScope) return@forEach
        f.lines.forEachIndexed { i, line ->
            // Detect on the comment-stripped line (shared CommentStripper) so a
            // `/* … */`-then-code line or a string-literal marker can no longer
            // hide a forbidden manifest write. Report the RAW line (bytes unchanged).
            val code = f.strippedLine(i)
            if (callRegex.containsMatchIn(code)) offenders += "$rel:${i + 1}: ${line.trim()}"
        }
    }
    if (offenders.isNotEmpty()) {
        return "ADR-0030 §2: Library/History UI must not mutate SessionManifest — use LibraryMetadataStore " +
            "(recovery-owned writes belong in ui/recovery/):\n" + offenders.joinToString("\n")
    }
    return null
}

// ─── checkSemanticIconNoRawAlpha ──────────────────────────────────────────────

/**
 * Verbatim lift of checkSemanticIconNoRawAlpha.
 * Scope: all .kt files under rova/; canonical seam excluded by suffix.
 * 3-line window scan: any line containing "tint" triggers a window[i..i+2] check.
 * Opt-out: "semanticicon-opt-out" on the line suppresses it (read from raw).
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 */
internal fun ruleSemanticIconNoRawAlpha(files: List<SourceFile>): String? {
    val rawTintPattern = Regex("""tint\s*=.*?\bColor\s*[.(]""")
    val seamSuffix = "ui/components/SemanticIcon.kt"
    val offenders = files
        .filter { f ->
            !f.relPath.replace('\\', '/').endsWith(seamSuffix)
        }
        .mapNotNull { f ->
            val lines = f.lines
            val hits = lines.indices.mapNotNull { i ->
                val line = lines[i]
                if (line.contains("semanticicon-opt-out")) return@mapNotNull null
                val stripped = f.strippedLine(i)
                if (!stripped.contains("tint")) return@mapNotNull null
                val window = (i until minOf(i + 3, lines.size)).joinToString(" ") { lines[it] }
                if (rawTintPattern.containsMatchIn(window)) i to line else null
            }
            if (hits.isEmpty()) null else f to hits
        }
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, hits) ->
            hits.joinToString("\n") { (i, line) -> "  ${f.relPath}:${i + 1}: ${line.trim()}" }
        }
        return "ADR-0031 §4 violation: raw Color literal used as an Icon tint outside the SemanticIcon " +
            "seam. Route glyph color through `SemanticIcon(role = …, status = …)` / " +
            "`SemanticIconSpec` so the theme engine drives icon color from one place. " +
            "For a genuinely non-themed glyph, add `// semanticicon-opt-out: <reason>` on the line.\n" +
            "Offenders:\n$report"
    }
    return null
}

// ─── checkStatusColorLocked ───────────────────────────────────────────────────

/**
 * Verbatim lift of checkStatusColorLocked.
 * Scope: all .kt files under rova/.
 * Forbid RovaSemantics.<X>.copy( (pattern catches multiline/positional cases).
 * Comment handling: detection on f.strippedLines (CommentStripper); opt-out + window + report use the raw line.
 */
internal fun ruleStatusColorLocked(files: List<SourceFile>): String? {
    val dilutePattern = Regex("""RovaSemantics\s*\.\s*\w+\s*\.copy\s*\(""")
    val offenders = files
        .mapNotNull { f ->
            val hits = f.lines.withIndex().filter { (idx, _) ->
                dilutePattern.containsMatchIn(f.strippedLine(idx))
            }
            if (hits.isEmpty()) null else f to hits
        }
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, hits) ->
            hits.joinToString("\n") { (i, line) -> "  ${f.relPath}:${i + 1}: ${line.trim()}" }
        }
        return "ADR-0031 §3 violation: a locked RovaSemantics status color is mutated (.copy) at the " +
            "call-site. Status colors render exact, at full locked opacity (and are paired with " +
            "shape, WCAG 1.4.1). Use the color directly, or vary emphasis with shape/size.\n" +
            "Offenders:\n$report"
    }
    return null
}

// ─── checkRovaGlyphHome ───────────────────────────────────────────────────────

/**
 * Verbatim lift of checkRovaGlyphHome.
 * Scope: all .kt files under rova/; canonical home excluded by suffix.
 * Comment handling: detection on CommentStripper.strip(f.text) (nesting-aware, literal-preserving,
 * length+newline-preserving so char-offset to line-number conversion is byte-stable).
 * C-consolidation: replaces the former non-nesting blockComment Regex + inline // strip
 * with CommentStripper. Catches ImageVector.Builder( split across lines.
 */
internal fun ruleRovaGlyphHome(files: List<SourceFile>): String? {
    val builderPattern = Regex("""\bImageVector\s*\.\s*Builder\s*\(""")
    val homeSuffix = "ui/theme/RovaGlyphs.kt"

    val offenders = files
        .filter { f ->
            !f.relPath.replace('\\', '/').endsWith(homeSuffix)
        }
        .mapNotNull { f ->
            val text = CommentStripper.strip(f.text)
            val hits = builderPattern.findAll(text)
                .map { m -> text.substring(0, m.range.first).count { it == '\n' } + 1 }
                .toList()
            if (hits.isEmpty()) null else f to hits
        }
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, hits) ->
            hits.joinToString("\n") { line -> "  ${f.relPath}:$line" }
        }
        return "ADR-0031 §5 violation: a bespoke ImageVector is declared outside RovaGlyphs.kt. " +
            "All hand-authored glyphs live in ui/theme/RovaGlyphs.kt so the icon family and the " +
            "theme engine resolve glyphs from one place. Move the vector there.\nOffenders:\n$report"
    }
    return null
}

// ─── checkSingleColorSchemeSource ─────────────────────────────────────────────

/**
 * Verbatim lift of checkSingleColorSchemeSource.
 * Scope: all .kt files under rova/; two canonical files excluded by suffix.
 * Strip block + line comments AND string literals AND char literals (preserve newlines).
 * Forbid darkColorScheme(/lightColorScheme( outside allow; forbid MaterialTheme(
 * whose balanced-paren args contain colorScheme =.
 * Opt-out: "colorscheme-source-opt-out" on same or previous line waives a hit.
 *
 * NOTE: not migrated onto CommentStripper — this gate's local strip() blanks string and char
 * literals so that darkColorScheme( or lightColorScheme( inside a string literal is not
 * detected. CommentStripper keeps literals verbatim, so migrating would newly-detect a
 * literal occurrence — a change to WHAT the gate enforces, violating the migration invariant.
 */
internal fun ruleSingleColorSchemeSource(files: List<SourceFile>): String? {
    val allowSuffixes = setOf(
        "ui/theme/Theme.kt",
        "ui/theme/PaletteColorScheme.kt",
    )

    fun strip(src: String): String {
        val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(src) { m ->
            m.value.replace(Regex("[^\n]"), " ")
        }
        val noLine = noBlock.lines().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) + " ".repeat(line.length - i) else line
        }
        val noStr = Regex(""""[^"\\]*(?:\\.[^"\\]*)*"""").replace(noLine) { m ->
            "\"" + " ".repeat((m.value.length - 2).coerceAtLeast(0)) + "\""
        }
        return Regex("""'(\\.|[^'\\])'""").replace(noStr) { m ->
            "'" + " ".repeat(m.value.length - 2) + "'"
        }
    }

    fun lineAt(text: String, idx: Int) = text.substring(0, idx).count { it == '\n' } + 1

    fun waived(raw: String, idx: Int): Boolean {
        val lineStart = raw.lastIndexOf('\n', idx).let { if (it < 0) 0 else it }
        val lineEnd = raw.indexOf('\n', idx).let { if (it < 0) raw.length else it }
        val prevStart = raw.lastIndexOf('\n', (lineStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it }
        return raw.substring(prevStart, lineEnd).contains("colorscheme-source-opt-out")
    }

    val factory = Regex("""\b(dark|light)ColorScheme\s*\(""")
    val mt = Regex("""\bMaterialTheme\s*\(""")

    val offenders = files
        .filter { f ->
            val rel = f.relPath.replace('\\', '/')
            allowSuffixes.none { rel.endsWith(it) }
        }
        .mapNotNull { f ->
            val raw = f.text
            val text = strip(raw)
            val hits = mutableListOf<Int>()
            factory.findAll(text).forEach { m ->
                if (!waived(raw, m.range.first)) hits += lineAt(text, m.range.first)
            }
            mt.findAll(text).forEach { m ->
                val open = text.indexOf('(', m.range.first)
                if (open < 0) return@forEach
                var depth = 0; var i = open; var close = -1
                while (i < text.length) {
                    when (text[i]) { '(' -> depth++; ')' -> { depth--; if (depth == 0) { close = i; break } } }
                    i++
                }
                if (close < 0) return@forEach
                val args = text.substring(open + 1, close)
                if (Regex("""\bcolorScheme\s*=""").containsMatchIn(args) && !waived(raw, m.range.first)) {
                    hits += lineAt(text, m.range.first)
                }
            }
            if (hits.isEmpty()) null else f to hits.sorted()
        }
    if (offenders.isNotEmpty()) {
        val report = offenders.joinToString("\n") { (f, hits) ->
            hits.joinToString("\n") { "  ${f.relPath}:$it" }
        }
        return "ADR-0028 amendment: the Material ColorScheme must be built only by the theme engine " +
            "(Theme.kt / PaletteColorScheme.kt). A surface constructs or overrides its own scheme " +
            "— route it through the active palette instead.\nOffenders:\n$report"
    }
    return null
}
