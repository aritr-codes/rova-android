package com.aritr.rova.gradle

/**
 * Verbatim lifts of five former app/build.gradle.kts inline gate bodies
 * (structural / window / counter gates).
 * Mechanical swaps only vs the inline body:
 *   - iterate [files] parameter instead of dir.walkTopDown() / fileTree(...)
 *   - f.lines / f.text / f.relPath instead of readLines() / readText() / relativeTo(rootDir)
 *   - return message instead of throw GradleException(message); return null to pass
 *   - No "dir.exists()" guard — an empty file set means no offenders → null
 *   - Filename allowlist (checkExternalRootShared): old code used `it.name !in allowedFiles`
 *     (filename only). We keep that exact behaviour: check the trailing filename component
 *     of relPath. We normalise a LOCAL var to forward slashes for the suffix check so this
 *     works identically on Windows and Linux.
 */

// ─── checkRecoveryReceiverCounter ────────────────────────────────────────────

/**
 * Verbatim lift of checkRecoveryReceiverCounter.
 * File-level opt-out marker: `guard-b-opt-out:` (with reason) skips the file.
 * Logic: find first non-comment goAsync() line; find increment/decrement of
 * activeReceiverWork around it; report ordering/missing problems.
 */
internal fun ruleRecoveryReceiverCounter(files: List<SourceFile>): String? {
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .mapNotNull { f ->
            val lines = f.lines

            // Find first non-comment goAsync() call line.
            val goAsyncIdx = lines.indexOfFirst { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                else line.contains("goAsync()")
            }
            if (goAsyncIdx < 0) return@mapNotNull null  // no goAsync — not a receiver-async pattern

            // Opt-out: any file marker `guard-b-opt-out:` (with reason)
            // skips the check.
            val hasOptOut = lines.any { it.contains("guard-b-opt-out:") }
            if (hasOptOut) return@mapNotNull null

            val problems = mutableListOf<String>()

            // Increment: first non-comment line that contains the
            // synchronous incrementAndGet on activeReceiverWork.
            val incIdx = lines.indexOfFirst { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                else line.contains("activeReceiverWork.incrementAndGet")
            }
            val hasDec = lines.any { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                else line.contains("activeReceiverWork.decrementAndGet")
            }

            if (incIdx < 0) {
                problems += "missing activeReceiverWork.incrementAndGet() before goAsync() (line ${goAsyncIdx + 1})"
            } else if (incIdx >= goAsyncIdx) {
                problems += "activeReceiverWork.incrementAndGet() (line ${incIdx + 1}) must precede goAsync() (line ${goAsyncIdx + 1}); the gap between goAsync() and the launched coroutine body is the race window"
            }
            if (!hasDec) {
                problems += "missing activeReceiverWork.decrementAndGet() — must run in the launched coroutine's finally so every exit path releases the counter"
            }

            if (problems.isEmpty()) null else f to problems
        }
        .toList()
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, problems) ->
        problems.joinToString("\n") { "  ${f.relPath}: $it" }
    }
    return "Guard B violations (ADR 0005 §Concurrency Invariants item 3):\n$report\n" +
        "Add a documented opt-out marker `// guard-b-opt-out: <reason>` " +
        "to the file (with a reason) only if the receiver does no " +
        "asynchronous work and never races the recovery scan."
}

// ─── checkAtomicTerminalWriteForbiddenPair ────────────────────────────────────

/**
 * Verbatim lift of checkAtomicTerminalWriteForbiddenPair.
 * Window: markTerminated( opener → inspect current line + next 3 lines.
 * Line-level opt-out: `terminal-ordering-opt-out:` on the opener line skips it.
 * Forbidden pair: Terminated.USER_STOPPED (or ", USER_STOPPED") AND
 *   StopReason.NONE (or ", NONE") in the same 4-line window.
 */
internal fun ruleAtomicTerminalWriteForbiddenPair(files: List<SourceFile>): String? {
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .mapNotNull { f ->
            val lines = f.lines
            val hits = mutableListOf<Int>()
            for ((i, line) in lines.withIndex()) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                if (!line.contains("markTerminated(")) continue
                if (line.contains("terminal-ordering-opt-out:")) continue
                // Window of up to 3 lines covers multi-line invocations.
                val window = (i..minOf(i + 3, lines.lastIndex))
                    .joinToString("\n") { lines[it] }
                val hasUserStopped = window.contains("Terminated.USER_STOPPED") ||
                    window.contains(", USER_STOPPED")
                val hasReasonNone = window.contains("StopReason.NONE") ||
                    window.contains(", NONE")
                if (hasUserStopped && hasReasonNone) {
                    hits += i + 1
                }
            }
            if (hits.isEmpty()) null else f to hits
        }
        .toList()
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, ls) ->
        "  ${f.relPath}: lines ${ls.joinToString(", ")}"
    }
    return "Forbidden pair markTerminated(USER_STOPPED, NONE) detected (ADR 0006 B16):\n$report\n" +
        "USER_STOPPED writers must pass a real StopReason " +
        "(USER, INIT_FAILED, PERMISSION_REVOKED, LOW_STORAGE). " +
        "Only KILLED_* and merge-success COMPLETED pass NONE."
}

// ─── checkExternalRootShared ──────────────────────────────────────────────────

/**
 * Verbatim lift of checkExternalRootShared.
 * Allowlist: files named exactly "RovaApp.kt" or "SessionStore.kt" are skipped.
 * Old gate used `it.name !in allowedFiles` (just the filename, not path).
 * We reproduce that by extracting the trailing component of relPath after
 * normalising separators.
 */
internal fun ruleExternalRootShared(files: List<SourceFile>): String? {
    val allowedFiles = setOf("RovaApp.kt", "SessionStore.kt")
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .filter { f ->
            // Reproduce `it.name !in allowedFiles` — extract filename from relPath.
            val normalised = f.relPath.replace('\\', '/')
            val name = normalised.substringAfterLast('/')
            name !in allowedFiles
        }
        .mapNotNull { f ->
            val hits = mutableListOf<Int>()
            for ((i, line) in f.lines.withIndex()) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                if (line.contains("getExternalFilesDir(null)")) hits += i + 1
            }
            if (hits.isEmpty()) null else f to hits
        }
        .toList()
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, ls) ->
        "  ${f.relPath}: lines ${ls.joinToString(", ")}"
    }
    return "External-root drift detected (ADR 0006 B21):\n$report\n" +
        "Use RovaApp.videosRoot or RovaApp.externalRoot instead. " +
        "Only RovaApp and SessionStore are allowed direct references."
}

// ─── checkAudioModeFgsTypeMatch ───────────────────────────────────────────────

/**
 * Verbatim lift of checkAudioModeFgsTypeMatch.
 * File-level opt-out marker: `audio-mode-opt-out:` skips the file.
 * Logic: find first non-comment FOREGROUND_SERVICE_TYPE_MICROPHONE line;
 * require that at least one of the preceding lines contains "audioMode" or
 * "AudioMode.".
 */
internal fun ruleAudioModeFgsTypeMatch(files: List<SourceFile>): String? {
    val offenders = files
        .filter { it.relPath.endsWith(".kt") }
        .mapNotNull { f ->
            val lines = f.lines
            val micIdx = lines.indexOfFirst { line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) false
                else line.contains("FOREGROUND_SERVICE_TYPE_MICROPHONE")
            }
            if (micIdx < 0) return@mapNotNull null
            if (lines.any { it.contains("audio-mode-opt-out:") }) return@mapNotNull null
            // Search lines BEFORE the MIC literal for an audioMode
            // reference (variable read, AudioMode enum, when branch).
            val hasAudioModeBefore = (0 until micIdx).any { i ->
                val s = lines[i]
                s.contains("audioMode") || s.contains("AudioMode.")
            }
            if (hasAudioModeBefore) null else f to micIdx + 1
        }
        .toList()
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, line) ->
        "  ${f.relPath}: line $line — FGS MIC type without audioMode gate"
    }
    return "FGS-type / audio-mode coupling violation (ADR 0006 B18 + B20):\n$report\n" +
        "FOREGROUND_SERVICE_TYPE_MICROPHONE must be gated by an " +
        "audioMode == AudioMode.VIDEO_AUDIO check. Hardcoding the " +
        "type bitfield risks SecurityException on Android 14+."
}

// ─── checkFGSStartGuarded ─────────────────────────────────────────────────────

/**
 * Verbatim lift of checkFGSStartGuarded.
 * File-level opt-out marker: `fgs-guard-opt-out:` skips the file.
 * Logic: for each file containing startForegroundService( or startForeground(,
 * for each non-comment line that contains a call site, look forward up to 60
 * lines for catch arms, SDK gate, is-check, and (service-side) SecurityException.
 */
internal fun ruleFGSStartGuarded(files: List<SourceFile>): String? {
    val offenders = mutableListOf<Pair<SourceFile, String>>()
    files
        .filter { it.relPath.endsWith(".kt") }
        .filter { f ->
            f.text.contains("startForegroundService(") || f.text.contains("startForeground(")
        }
        .forEach { f ->
            val lines = f.lines
            if (lines.any { it.contains("fgs-guard-opt-out:") }) return@forEach
            for ((i, line) in lines.withIndex()) {
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue
                val isCallerSide = line.contains("startForegroundService(")
                val isServiceSide = line.contains("startForeground(") &&
                    !line.contains("startForegroundService(")
                if (!isCallerSide && !isServiceSide) continue
                // Look forward up to 60 lines for catch arms.
                val end = minOf(i + 60, lines.lastIndex)
                val window = lines.subList(i, end + 1).joinToString("\n")
                val hasIseCatch = window.contains("catch (e: IllegalStateException)") ||
                    window.contains("catch(e: IllegalStateException)") ||
                    window.contains("catch (e:IllegalStateException)")
                val hasSdkGate = window.contains("Build.VERSION.SDK_INT") &&
                    window.contains("Build.VERSION_CODES.S")
                val hasIsCheck = window.contains("ForegroundServiceStartNotAllowedException")
                val hasSecurityCatch = window.contains("catch (e: SecurityException)") ||
                    window.contains("catch(e: SecurityException)")
                if (!hasIseCatch) {
                    offenders += f to "line ${i + 1}: missing catch (e: IllegalStateException)"
                } else {
                    if (!hasSdkGate) offenders += f to "line ${i + 1}: catch is not SDK-gated (Build.VERSION.SDK_INT >= S)"
                    if (!hasIsCheck) offenders += f to "line ${i + 1}: missing ForegroundServiceStartNotAllowedException is-check"
                }
                if (isServiceSide && !hasSecurityCatch) {
                    offenders += f to "line ${i + 1}: service-side startForeground missing catch (e: SecurityException) (B18 + B20)"
                }
            }
        }
    if (offenders.isEmpty()) return null
    val report = offenders.joinToString("\n") { (f, msg) ->
        "  ${f.relPath}: $msg"
    }
    return "FGS guard violations (ADR 0006 B10 + B11 + B20):\n$report\n" +
        "Caller-side: catch IllegalStateException, SDK-gate the is-check.\n" +
        "Service-side: also catch SecurityException for FGS-type / permission mismatch."
}
