package com.aritr.rova.gradle

/**
 * Verbatim lifts of four former app/build.gradle.kts inline gate bodies
 * (mode/rotation/preset gates — ADR-0026, ADR-0029).
 * Mechanical swaps only vs the inline body:
 *   - iterate [files] parameter instead of fileTree("src/main/java") / listOf(file(...))
 *   - f.lines / f.text / f.relPath instead of readLines() / readText() / relativeTo(rootDir)
 *   - return message instead of throw GradleException(message); return null to pass
 *   - #1 (checkPresetNoOrientation): two-file structural; empty input -> return null (no
 *     source to check); file-missing check preserved for RovaSettings.kt via firstOrNull
 *   - #2 (checkNoLegacyModeStrings): forbid gate; exclusion by relPath suffix; comment-skip
 *   - #3 (checkSetTargetRotationBoundaryOnly): forbid gate; exclusion by relPath suffix; NO
 *     comment-skip (scans ALL lines including comments — old gate had no skip logic)
 *   - #4 (checkFrontBackCapabilityGated): forbid gate; exclusion by relPath suffix; comment-skip
 *   - Separator note: relPath preserves platform separator (Windows '\', Linux '/').
 *     Local .replace('\\','/') is used ONLY for suffix matching (same as old gate);
 *     the reported relPath is never normalised — byte-identical to old gate output.
 */

// ─── checkPresetNoOrientation ─────────────────────────────────────────────────

/**
 * Verbatim lift of checkPresetNoOrientation.
 * Scope: two specific files — RovaSettings.kt (RovaPreset param-block check) and
 * BuiltInPresets.kt (property-decl check).
 * NO comment-skip (old gate had none — confirm: the inline body had no trimStart/startsWith
 * guard before the regex match in either check).
 * Empty input: return null (no source files to check).
 * RovaSettings.kt missing: return "source missing" message (old gate threw on !exists()).
 */
internal fun rulePresetNoOrientation(files: List<SourceFile>): String? {
    val offendingProp = Regex("""(^|\s)(val|var)\s+(mode|orientation)\b""")
    val ctorParam = Regex("""\b(mode|orientation)\s*:""")

    val rovaSettingsSuffix = "data/RovaSettings.kt"
    val builtInsSuffix = "data/BuiltInPresets.kt"

    val rovaPresetSrc = files.firstOrNull {
        it.relPath.replace('\\', '/').endsWith(rovaSettingsSuffix)
    } ?: return "checkPresetNoOrientation: source missing: " +
        "src/main/java/com/aritr/rova/data/RovaSettings.kt"

    // Narrow to the RovaPreset declaration block to avoid matching unrelated
    // `mode` usage elsewhere in RovaSettings.kt (e.g. the legacy `mode` pref).
    val text = rovaPresetSrc.text
    val start = text.indexOf("data class RovaPreset")
    if (start >= 0) {
        val end = text.indexOf(")", start).let { if (it < 0) text.length else it }
        val block = text.substring(start, end)
        if (ctorParam.containsMatchIn(block)) {
            return "checkPresetNoOrientation: RovaPreset must not declare a mode/orientation field (ADR-0026)."
        }
    }

    val builtIns = files.firstOrNull {
        it.relPath.replace('\\', '/').endsWith(builtInsSuffix)
    }
    if (builtIns != null && offendingProp.containsMatchIn(builtIns.text)) {
        return "checkPresetNoOrientation: BuiltInPresets must not declare a mode/orientation property (ADR-0026)."
    }

    return null
}

// ─── checkNoLegacyModeStrings ─────────────────────────────────────────────────

/**
 * Verbatim lift of checkNoLegacyModeStrings.
 * Forbid "Portrait"|"Landscape"|"PortraitLandscape" string literals in .kt files,
 * except in the three legacy read-compat allowlisted paths.
 * Comment handling: detection on f.strippedLines (CommentStripper); string literals kept so real mode-string literals still match; report uses the raw line.
 * Empty input: null (forbid gate, no files = no offenders).
 */
internal fun ruleNoLegacyModeStrings(files: List<SourceFile>): String? {
    val allow = setOf(
        "data/SessionManifest.kt",
        "data/ModeMigration.kt",
        "data/RovaSettings.kt",
    )
    val legacyMode = Regex("\"(Portrait|Landscape|PortraitLandscape)\"")
    val offenders = mutableListOf<String>()
    files.forEach { f ->
        val rel = f.relPath.replace('\\', '/').substringAfter("com/aritr/rova/")
        if (allow.any { rel.endsWith(it) }) return@forEach
        f.lines.forEachIndexed { i, line ->
            // Detect on the comment-stripped line (string literals kept verbatim,
            // so a real "Portrait" literal still matches); report the RAW line.
            if (legacyMode.containsMatchIn(f.strippedLine(i))) {
                offenders += "$rel:${i + 1}: ${line.trim()}"
            }
        }
    }
    if (offenders.isNotEmpty()) {
        return "ADR-0029 PR-γ §6: legacy mode strings in live paths (use CaptureTopology):\n" +
            offenders.joinToString("\n")
    }
    return null
}

// ─── checkSetTargetRotationBoundaryOnly ───────────────────────────────────────

/**
 * Verbatim lift of checkSetTargetRotationBoundaryOnly.
 * Forbid setTargetRotation( outside service/RovaRecordingService.kt, service/dualrecord/, and service/singlerecord/.
 * NO comment-skip — old gate scanned ALL lines including comments (no trimStart guard present).
 * Empty input: null (forbid gate, no files = no offenders).
 */
internal fun ruleSetTargetRotationBoundaryOnly(files: List<SourceFile>): String? {
    val offenders = mutableListOf<String>()
    files.forEach { f ->
        val rel = f.relPath.replace('\\', '/').substringAfter("com/aritr/rova/")
        val allowed = rel.endsWith("service/RovaRecordingService.kt") ||
            rel.contains("service/dualrecord/") ||
            rel.contains("service/singlerecord/")
        if (allowed) return@forEach
        f.lines.forEachIndexed { i, line ->
            if (line.contains("setTargetRotation(")) offenders += "$rel:${i + 1}"
        }
    }
    if (offenders.isNotEmpty()) {
        return "ADR-0029 §3: setTargetRotation outside boundary-owning files:\n" +
            offenders.joinToString("\n")
    }
    return null
}

// ─── checkFrontBackCapabilityGated ────────────────────────────────────────────

/**
 * Verbatim lift of checkFrontBackCapabilityGated.
 * Forbid "FrontBack" outside data/CaptureTopology.kt and ui/screens/CaptureModes.kt.
 * Comment handling: detection on f.strippedLines (CommentStripper).
 * Empty input: null (forbid gate, no files = no offenders).
 */
internal fun ruleFrontBackCapabilityGated(files: List<SourceFile>): String? {
    val allow = setOf("data/CaptureTopology.kt", "ui/screens/CaptureModes.kt")
    val offenders = mutableListOf<String>()
    files.forEach { f ->
        val rel = f.relPath.replace('\\', '/').substringAfter("com/aritr/rova/")
        if (allow.any { rel.endsWith(it) }) return@forEach
        f.lines.forEachIndexed { i, line ->
            // Detect on the comment-stripped line; report is "$rel:line" (no content).
            if (f.strippedLine(i).contains("FrontBack")) offenders += "$rel:${i + 1}"
        }
    }
    if (offenders.isNotEmpty()) {
        return "ADR-0029 §5: FrontBack outside the capability-gated registry:\n" +
            offenders.joinToString("\n")
    }
    return null
}

// ─── checkAeFpsRangeCapabilityGated ───────────────────────────────────────────

/**
 * ADR-0034 — the DualShot AE target fps range must be capability-gated via
 * AeFpsRangePolicy, never a hard-coded literal. Scope: RovaRecordingService.kt.
 * Forbid any single line that both references the request key
 * `CONTROL_AE_TARGET_FPS_RANGE` AND constructs a `Range(` literal — that is the
 * camera-open footgun. The legitimate form builds `android.util.Range(...)` on
 * its own line and passes it by reference to setCaptureRequestOption.
 * Detection on strippedLines (comment-aware); report from the raw line.
 * The AVAILABLE-list key (CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) does not
 * contain the substring `CONTROL_AE_TARGET_FPS_RANGE`, so it never triggers.
 * Empty input / file absent: null (forbid gate, nothing to forbid).
 */
internal fun ruleAeFpsRangeCapabilityGated(files: List<SourceFile>): String? {
    val suffix = "service/RovaRecordingService.kt"
    val src = files.firstOrNull { it.relPath.replace('\\', '/').endsWith(suffix) } ?: return null
    val offenders = mutableListOf<String>()
    src.lines.forEachIndexed { i, line ->
        val s = src.strippedLine(i)
        if (s.contains("CONTROL_AE_TARGET_FPS_RANGE") && s.contains("Range(")) {
            offenders += "$suffix:${i + 1}: ${line.trim()}"
        }
    }
    if (offenders.isNotEmpty()) {
        return "ADR-0034: AE target fps range must be capability-gated via AeFpsRangePolicy, " +
            "not a hard-coded Range(...) literal:\n" + offenders.joinToString("\n")
    }
    return null
}
